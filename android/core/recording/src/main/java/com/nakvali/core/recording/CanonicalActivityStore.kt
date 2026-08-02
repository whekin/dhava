package com.nakvali.core.recording

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Atomic, invalidating persistence for rebuildable canonical artifacts. */
internal class CanonicalActivityStore(
    private val artifactsDir: File,
    private val currentAlgorithmVersion: () -> String,
    private val produce: (rawPath: String) -> CanonicalArtifactPayload,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun loadOrCreate(id: String, rawFile: File): CanonicalActivityArtifact =
        mutex.withLock {
            require(rawFile.isFile) { "Raw recording is missing: ${rawFile.absolutePath}" }
            val version = currentAlgorithmVersion()
            readValid(id, rawFile, version)?.let { return@withLock it }

            val sourceSizeBytes = rawFile.length()
            val sourceLastModifiedMs = rawFile.lastModified()
            val payload = produce(rawFile.absolutePath)
            check(payload.algorithmVersion == version) {
                "Finalizer returned ${payload.algorithmVersion}, expected $version"
            }
            check(
                rawFile.length() == sourceSizeBytes &&
                    rawFile.lastModified() == sourceLastModifiedMs,
            ) {
                "Raw recording changed during finalization; retry with the completed source"
            }
            val artifact = CanonicalActivityArtifact(
                schemaVersion = SCHEMA_VERSION,
                algorithmVersion = version,
                sourceSizeBytes = sourceSizeBytes,
                sourceLastModifiedMs = sourceLastModifiedMs,
                generatedAtMs = nowMs(),
                analysis = payload.analysis,
                rawTrack = payload.rawTrack,
                finalizedTrack = payload.finalizedTrack,
                quality = payload.quality,
            )
            writeAtomically(id, artifact)
            artifact
        }

    suspend fun delete(id: String) = mutex.withLock {
        artifactFile(id).delete()
        temporaryFile(id).delete()
    }

    /**
     * Removes every persisted artifact (and stray temp files) under the mutex
     * so an in-flight finalization cannot interleave. Artifacts are purely
     * derived data: the next [loadOrCreate] rebuilds them from immutable raw.
     * Returns the number of artifacts deleted.
     */
    suspend fun clearAll(): Int = mutex.withLock {
        artifactsDir.listFiles { file -> file.name.endsWith(".canonical.tmp") }
            ?.forEach { it.delete() }
        artifactsDir.listFiles { file -> file.name.endsWith(".canonical.json.gz") }
            ?.count { it.delete() }
            ?: 0
    }

    internal fun artifactFile(id: String): File = File(artifactsDir, "$id.canonical.json.gz")

    private fun readValid(
        id: String,
        rawFile: File,
        algorithmVersion: String,
    ): CanonicalActivityArtifact? {
        val file = artifactFile(id)
        if (!file.isFile) return null
        val artifact = runCatching {
            GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
                ArtifactJson.decodeFromString<CanonicalActivityArtifact>(reader.readText())
            }
        }.getOrNull() ?: return null
        return artifact.takeIf {
            it.schemaVersion == SCHEMA_VERSION &&
                it.algorithmVersion == algorithmVersion &&
                it.analysis.algorithmVersion == algorithmVersion &&
                it.sourceSizeBytes == rawFile.length() &&
                it.sourceLastModifiedMs == rawFile.lastModified()
        }
    }

    private fun writeAtomically(id: String, artifact: CanonicalActivityArtifact) {
        artifactsDir.mkdirs()
        val target = artifactFile(id)
        val temporary = temporaryFile(id)
        GZIPOutputStream(temporary.outputStream()).bufferedWriter().use { writer ->
            writer.write(ArtifactJson.encodeToString(artifact))
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun temporaryFile(id: String): File = File(artifactsDir, "$id.canonical.tmp")

    internal companion object {
        // v3: adds Rust-derived per-point activity state + confidence.
        const val SCHEMA_VERSION = 3
        val ArtifactJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}
