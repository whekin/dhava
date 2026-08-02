package com.nakvali.feature.record

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val startTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneId.systemDefault())

private val startClockFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault())

internal fun formatStartTime(epochMs: Long): String =
    startTimeFormatter.format(Instant.ofEpochMilli(epochMs))

internal fun formatStartClock(epochMs: Long): String =
    startClockFormatter.format(Instant.ofEpochMilli(epochMs))

internal fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs.coerceAtLeast(0) / 1_000
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3_600,
        (totalSeconds % 3_600) / 60,
        totalSeconds % 60,
    )
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
