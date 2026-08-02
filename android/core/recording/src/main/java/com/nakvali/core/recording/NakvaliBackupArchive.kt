package com.nakvali.core.recording

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stable user-facing description of one complete local backup. */
data class BackupPreview(
    val createdAtMs: Long,
    val recordingCount: Int,
    val segmentCount: Int,
    val importedTraceCount: Int,
    val totalBytes: Long,
)

data class BackupSummary(
    val preview: BackupPreview,
)

data class RestoreSummary(
    val recordingCount: Int,
    val segmentCount: Int,
    val importedTraceCount: Int,
)

class NakvaliBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

@Serializable
private data class BackupManifest(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("recording_count") val recordingCount: Int,
    @SerialName("segment_count") val segmentCount: Int,
    @SerialName("imported_trace_count") val importedTraceCount: Int,
    val entries: List<BackupManifestEntry>,
)

@Serializable
internal data class BackupManifestEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

/**
 * Versioned archive for irreplaceable on-device input data.
 *
 * Canonical activity artifacts, segment results, map tiles, WorkManager state
 * and credentials are deliberately excluded: they are either recomputable,
 * cache data, process state, or secrets. The manifest is the first ZIP entry,
 * so an import can reject an incompatible or implausibly large archive before
 * extracting any user data.
 */
internal class NakvaliBackupArchive(
    private val filesDir: File,
) {
    fun write(
        output: OutputStream,
        recordingsJson: ByteArray,
        bikesJson: ByteArray,
        createdAtMs: Long,
    ): BackupSummary {
        val sources = buildList {
            add(Source.Bytes(RECORDINGS_INDEX, recordingsJson))
            add(Source.Bytes(BIKES_INDEX, bikesJson))
            addDirectory(
                directory = filesDir.resolve(RECORDINGS_DIR),
                prefix = RECORDINGS_DIR,
                acceptedSuffixes = listOf(".jsonl.gz", ".health.jsonl"),
            )
            addDirectory(
                directory = filesDir.resolve(SEGMENTS_DIR),
                prefix = SEGMENTS_DIR,
                acceptedSuffixes = listOf(".segment.json"),
            )
            addDirectory(
                directory = filesDir.resolve(IMPORTED_TRACES_DIR),
                prefix = IMPORTED_TRACES_DIR,
                acceptedSuffixes = listOf(".gpx", ".json"),
            )
        }.sortedBy(Source::path)

        val prepared = sources.map(::prepare)
        val manifest = BackupManifest(
            formatVersion = FORMAT_VERSION,
            createdAtMs = createdAtMs,
            recordingCount = decodeRecordings(recordingsJson).size,
            segmentCount = prepared.count { it.source.path.startsWith("$SEGMENTS_DIR/") },
            importedTraceCount = prepared.count { it.source.path.endsWith(".gpx") },
            entries = prepared.map {
                BackupManifestEntry(it.source.path, it.size, it.sha256)
            },
        )
        val manifestBytes = Codec.encodeToString(manifest).encodeToByteArray()

        ZipOutputStream(output.buffered()).use { zip ->
            writeStoredEntry(zip, MANIFEST_PATH, Source.Bytes(MANIFEST_PATH, manifestBytes), prepareBytes(manifestBytes))
            prepared.forEach { source ->
                writeStoredEntry(zip, source.source.path, source.source, source)
            }
        }
        return BackupSummary(manifest.toPreview())
    }

    fun inspect(input: InputStream): BackupPreview =
        ZipInputStream(input.buffered()).use { zip -> readManifest(zip).toPreview() }

    fun extract(input: InputStream, stagingDirectory: File): StagedBackup {
        if (stagingDirectory.exists()) stagingDirectory.deleteRecursively()
        check(stagingDirectory.mkdirs()) { "Could not create backup restore workspace" }
        try {
            ZipInputStream(input.buffered()).use { zip ->
                val manifest = readManifest(zip)
                validateManifest(manifest, stagingDirectory)
                val expected = manifest.entries.associateBy(BackupManifestEntry::path)
                val seen = mutableSetOf<String>()

                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) throw NakvaliBackupException("Backup contains an unexpected directory")
                    val manifestEntry = expected[entry.name]
                        ?: throw NakvaliBackupException("Backup contains an unexpected file: ${entry.name}")
                    if (!seen.add(entry.name)) {
                        throw NakvaliBackupException("Backup contains the same file twice: ${entry.name}")
                    }
                    val target = safeTarget(stagingDirectory, entry.name)
                    target.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance(SHA_256)
                    var written = 0L
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > manifestEntry.size) {
                                throw NakvaliBackupException("Backup file is larger than declared: ${entry.name}")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                    if (written != manifestEntry.size || digest.hexDigest() != manifestEntry.sha256) {
                        throw NakvaliBackupException("Backup checksum failed: ${entry.name}")
                    }
                    zip.closeEntry()
                }
                val missing = expected.keys - seen
                if (missing.isNotEmpty()) {
                    throw NakvaliBackupException("Backup is incomplete: ${missing.first()}")
                }
                return StagedBackup(manifest.toPreview(), stagingDirectory, expected)
            }
        } catch (error: Throwable) {
            stagingDirectory.deleteRecursively()
            if (error is NakvaliBackupException) throw error
            throw NakvaliBackupException("Could not read Nakvali backup", error)
        }
    }

    private fun readManifest(zip: ZipInputStream): BackupManifest {
        val first = zip.nextEntry
            ?: throw NakvaliBackupException("Backup is empty")
        if (first.isDirectory || first.name != MANIFEST_PATH) {
            throw NakvaliBackupException("This is not a Nakvali backup")
        }
        val buffer = ByteArrayOutputStream()
        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = zip.read(bytes)
            if (read < 0) break
            total += read
            if (total > MAX_MANIFEST_BYTES) {
                throw NakvaliBackupException("Backup manifest is too large")
            }
            buffer.write(bytes, 0, read)
        }
        zip.closeEntry()
        return runCatching {
            Codec.decodeFromString<BackupManifest>(buffer.toString(Charsets.UTF_8.name()))
        }.getOrElse { error ->
            throw NakvaliBackupException("Backup manifest is invalid", error)
        }.also(::validateManifestShape)
    }

    private fun validateManifest(manifest: BackupManifest, stagingDirectory: File) {
        validateManifestShape(manifest)
        val total = manifest.totalBytes()
        val reserve = MIN_FREE_BYTES.coerceAtMost(stagingDirectory.parentFile?.usableSpace ?: 0L)
        val usable = stagingDirectory.parentFile?.usableSpace ?: 0L
        if (total > usable - reserve) {
            throw NakvaliBackupException("Not enough free space to verify this backup")
        }
    }

    private fun validateManifestShape(manifest: BackupManifest) {
        if (manifest.formatVersion != FORMAT_VERSION) {
            throw NakvaliBackupException("Backup format ${manifest.formatVersion} is not supported")
        }
        if (manifest.entries.size > MAX_ENTRY_COUNT) {
            throw NakvaliBackupException("Backup contains too many files")
        }
        if (manifest.recordingCount < 0 || manifest.segmentCount < 0 || manifest.importedTraceCount < 0) {
            throw NakvaliBackupException("Backup manifest contains an invalid item count")
        }
        if (manifest.entries.map(BackupManifestEntry::path).toSet().size != manifest.entries.size) {
            throw NakvaliBackupException("Backup manifest contains duplicate files")
        }
        manifest.entries.forEach { entry ->
            if (!allowedPath(entry.path)) throw NakvaliBackupException("Backup path is not allowed: ${entry.path}")
            if (entry.size < 0L) throw NakvaliBackupException("Backup contains an invalid file size")
            if (!SHA256_PATTERN.matches(entry.sha256)) {
                throw NakvaliBackupException("Backup contains an invalid checksum")
            }
        }
        manifest.totalBytes()
    }

    private fun prepare(source: Source): PreparedSource = source.open().use { input ->
        val digest = MessageDigest.getInstance(SHA_256)
        val crc = CRC32()
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            require(size <= MAX_ARCHIVE_BYTES) { "Backup source is larger than 50 GB" }
            digest.update(buffer, 0, read)
            crc.update(buffer, 0, read)
        }
        PreparedSource(source, size, crc.value, digest.hexDigest())
    }

    private fun prepareBytes(bytes: ByteArray): PreparedSource = prepare(Source.Bytes("", bytes))

    private fun writeStoredEntry(
        zip: ZipOutputStream,
        path: String,
        source: Source,
        prepared: PreparedSource,
    ) {
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            size = prepared.size
            compressedSize = prepared.size
            crc = prepared.crc32
            time = 0L
        }
        zip.putNextEntry(entry)
        val digest = MessageDigest.getInstance(SHA_256)
        val crc = CRC32()
        var size = 0L
        source.open().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                digest.update(buffer, 0, read)
                crc.update(buffer, 0, read)
                zip.write(buffer, 0, read)
            }
        }
        if (size != prepared.size || crc.value != prepared.crc32 || digest.hexDigest() != prepared.sha256) {
            throw NakvaliBackupException("Source changed while backup was being created: $path")
        }
        zip.closeEntry()
    }

    private fun MutableList<Source>.addDirectory(
        directory: File,
        prefix: String,
        acceptedSuffixes: List<String>,
    ) {
        directory.listFiles()
            ?.filter { file -> file.isFile && acceptedSuffixes.any(file.name::endsWith) }
            ?.forEach { file ->
                val path = "$prefix/${file.name}"
                if (allowedPath(path)) add(Source.Disk(path, file))
            }
    }

    private fun decodeRecordings(bytes: ByteArray): List<LocalRecording> = runCatching {
        IndexJson.decodeFromString<List<LocalRecording>>(bytes.decodeToString())
    }.getOrElse { error ->
        throw NakvaliBackupException("Recording index could not be backed up", error)
    }

    private sealed interface Source {
        val path: String
        fun open(): InputStream

        data class Disk(override val path: String, val file: File) : Source {
            override fun open(): InputStream = file.inputStream().buffered()
        }

        data class Bytes(override val path: String, val bytes: ByteArray) : Source {
            override fun open(): InputStream = ByteArrayInputStream(bytes)
        }
    }

    private data class PreparedSource(
        val source: Source,
        val size: Long,
        val crc32: Long,
        val sha256: String,
    )

    internal data class StagedBackup(
        val preview: BackupPreview,
        val directory: File,
        private val entries: Map<String, BackupManifestEntry>,
    ) {
        fun file(path: String): File = safeTarget(directory, path)
        fun sha256(path: String): String? = entries[path]?.sha256
    }

    private fun BackupManifest.toPreview() = BackupPreview(
        createdAtMs = createdAtMs,
        recordingCount = recordingCount,
        segmentCount = segmentCount,
        importedTraceCount = importedTraceCount,
        totalBytes = totalBytes(),
    )

    private fun BackupManifest.totalBytes(): Long {
        var total = 0L
        entries.forEach { entry ->
            total = try {
                Math.addExact(total, entry.size)
            } catch (_: ArithmeticException) {
                throw NakvaliBackupException("Backup size is invalid")
            }
            if (total > MAX_ARCHIVE_BYTES) throw NakvaliBackupException("Backup is larger than 50 GB")
        }
        return total
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST_PATH = "manifest.json"
        const val RECORDINGS_INDEX = "recordings.json"
        const val BIKES_INDEX = "bikes.json"
        const val RECORDINGS_DIR = "recordings"
        const val SEGMENTS_DIR = "segments"
        const val IMPORTED_TRACES_DIR = "imported-traces"
        const val SHA_256 = "SHA-256"
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        const val MAX_ENTRY_COUNT = 10_000
        const val MAX_ARCHIVE_BYTES = 50L * 1024L * 1024L * 1024L
        const val MIN_FREE_BYTES = 64L * 1024L * 1024L
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        val Codec = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        fun allowedPath(path: String): Boolean {
            if (path == RECORDINGS_INDEX || path == BIKES_INDEX) return true
            val pieces = path.split('/')
            if (pieces.size != 2 || !SAFE_FILE_NAME.matches(pieces[1])) return false
            return when (pieces[0]) {
                RECORDINGS_DIR -> pieces[1].endsWith(".jsonl.gz") || pieces[1].endsWith(".health.jsonl")
                SEGMENTS_DIR -> pieces[1].endsWith(".segment.json")
                IMPORTED_TRACES_DIR -> pieces[1].endsWith(".gpx") || pieces[1].endsWith(".json")
                else -> false
            }
        }

        fun safeTarget(root: File, path: String): File {
            if (!allowedPath(path)) throw NakvaliBackupException("Backup path is not allowed: $path")
            val target = root.resolve(path)
            val rootPath = root.canonicalFile.toPath()
            if (!target.canonicalFile.toPath().startsWith(rootPath)) {
                throw NakvaliBackupException("Backup path escapes its workspace")
            }
            return target
        }

        fun MessageDigest.hexDigest(): String = digest().joinToString("") { "%02x".format(it) }
    }
}
