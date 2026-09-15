package com.nakvali.feature.activity

import java.io.File
import java.io.OutputStream

/** The same prepared file can go to a document provider or another app. */
enum class ExportDestination { SAVE, SHARE }

data class PreparedActivityExport(
    val file: File,
    val kind: ActivityExportKind,
    val destination: ExportDestination,
)

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
