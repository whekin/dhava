package com.nakvali.feature.activity

import com.nakvali.core.recording.TrackExportRate
import java.io.File
import java.io.OutputStream

data class ActivityExportOptions(
    val rate: TrackExportRate = TrackExportRate.FIVE_HZ,
    val gzip: Boolean = false,
)

/** The same prepared file can go to a document provider or another app. */
enum class ExportDestination { SAVE, SHARE }

data class PreparedActivityExport(
    val file: File,
    val kind: ActivityExportKind,
    val destination: ExportDestination,
) {
    val mimeType: String get() = if (file.name.endsWith(".gz")) "application/gzip" else kind.mimeType
}

data class ActivityExportState(
    val busy: Boolean = false,
    val prepared: PreparedActivityExport? = null,
    val message: String? = null,
    val error: String? = null,
)

/** Open the source first; a missing cache file must not truncate the destination. */
internal fun copyExportFile(source: File, openOutput: () -> OutputStream?) {
    source.inputStream().use { input ->
        val output = openOutput() ?: error("The selected location could not be opened")
        output.use { input.copyTo(it) }
    }
}
