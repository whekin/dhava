package com.nakvali.core.recording

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.xml.parsers.SAXParserFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** A locally preserved GPX trace used only as seed geometry for authoring. */
data class ImportedTrace(
    val id: String,
    val displayName: String,
    val importedAtMs: Long,
    val points: List<CanonicalPoint>,
)

@Serializable
private data class ImportedTraceMetadata(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("imported_at_ms") val importedAtMs: Long,
)

/**
 * Durable storage for imported GPX source files.
 *
 * The original bytes are retained so a future parser or segment refinement
 * algorithm can be rerun. Parsed points are intentionally not a canonical
 * activity and never become a ride, attempt, PR or KOM by importing them.
 */
internal class ImportedTraceStore(
    private val context: Context,
    private val directory: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun import(uri: Uri): ImportedTrace {
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        val source = sourceFile(id)
        val temporary = File(directory, "$id.gpx.tmp")
        val displayName = displayName(uri)
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "Imported trail"

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected GPX file could not be opened" }
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_GPX_BYTES) { "GPX is larger than 25 MB" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            moveAtomically(temporary, source)
            val points = GpxTraceParser.parse(source)
            val metadata = ImportedTraceMetadata(id, displayName, nowMs())
            metadataFile(id).writeText(JsonCodec.encodeToString(metadata))
            return ImportedTrace(id, displayName, metadata.importedAtMs, points)
        } catch (error: Throwable) {
            temporary.delete()
            source.delete()
            metadataFile(id).delete()
            throw error
        }
    }

    fun load(id: String): ImportedTrace? {
        val source = sourceFile(id)
        val metadata = runCatching {
            JsonCodec.decodeFromString<ImportedTraceMetadata>(metadataFile(id).readText())
        }.getOrNull() ?: return null
        if (!source.isFile || metadata.id != id) return null
        val points = GpxTraceParser.parse(source)
        return ImportedTrace(id, metadata.displayName, metadata.importedAtMs, points)
    }

    private fun displayName(uri: Uri): String? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun sourceFile(id: String) = File(directory, "$id.gpx")
    private fun metadataFile(id: String) = File(directory, "$id.json")

    private fun moveAtomically(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MAX_GPX_BYTES = 25L * 1024L * 1024L
        val JsonCodec = Json { ignoreUnknownKeys = true }
    }
}

/** Namespace-tolerant, external-entity-disabled parser for GPX tracks/routes. */
internal object GpxTraceParser {
    fun parse(file: File): List<CanonicalPoint> {
        val sections = mutableListOf<MutableList<GpxPoint>>()
        var current: MutableList<GpxPoint>? = null
        var currentPoint: GpxPoint? = null
        var text = StringBuilder()

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val handler = object : DefaultHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes,
            ) {
                val name = localName?.takeIf(String::isNotEmpty) ?: qName.orEmpty()
                when (name) {
                    "trkseg", "rte" -> {
                        current = mutableListOf<GpxPoint>().also(sections::add)
                    }
                    "trkpt", "rtept" -> {
                        if (current == null) {
                            current = mutableListOf<GpxPoint>().also(sections::add)
                        }
                        val lat = attributes.getValue("lat")?.toDoubleOrNull()
                        val lon = attributes.getValue("lon")?.toDoubleOrNull()
                        currentPoint = if (
                            lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
                            lat in -90.0..90.0 && lon in -180.0..180.0
                        ) {
                            GpxPoint(lat, lon, null)
                        } else {
                            null
                        }
                    }
                    "ele" -> text = StringBuilder()
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (currentPoint != null) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val name = localName?.takeIf(String::isNotEmpty) ?: qName.orEmpty()
                when (name) {
                    "ele" -> currentPoint = currentPoint?.copy(
                        elevationM = text.toString().trim().toDoubleOrNull()?.takeIf(Double::isFinite),
                    )
                    "trkpt", "rtept" -> {
                        currentPoint?.let { current?.add(it) }
                        currentPoint = null
                        text = StringBuilder()
                    }
                    "trkseg", "rte" -> current = null
                }
            }
        }
        file.inputStream().buffered().use { input ->
            factory.newSAXParser().parse(input, handler)
        }

        // A GPX can contain unrelated tracks. Do not invent a straight bridge;
        // use the most detailed continuous section as the authoring source.
        val selected = sections.maxByOrNull { it.size }.orEmpty()
        require(selected.size >= 2) { "GPX contains fewer than two track points" }
        return selected.mapIndexed { index, point ->
            CanonicalPoint(
                timestampMs = index * 1_000L,
                lat = point.lat,
                lon = point.lon,
                altitudeM = point.elevationM,
                accuracyM = null,
                speedMps = null,
                stationary = null,
                sectionId = 0,
            )
        }
    }

    private data class GpxPoint(
        val lat: Double,
        val lon: Double,
        val elevationM: Double?,
    )
}
