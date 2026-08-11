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

/** `3:23.6` — a segment run, tenths included because runs are decided there. */
internal fun formatSegmentElapsed(elapsedMs: Long): String {
    val safe = elapsedMs.coerceAtLeast(0)
    val minutes = safe / 60_000
    val seconds = (safe % 60_000) / 1_000
    val tenths = (safe % 1_000) / 100
    return String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
}

/** `−2.1 s` when faster than the record, `+4.0 s` when slower. */
internal fun formatSegmentDelta(deltaMs: Long): String {
    val sign = if (deltaMs < 0) "−" else "+"
    return String.format(Locale.US, "%s%.1f s", sign, kotlin.math.abs(deltaMs) / 1_000.0)
}

/** `480 m` below a kilometre, `1.2 km` above it — the activity screen's rule. */
internal fun formatDistance(meters: Double): String = when {
    meters >= 1_000.0 -> String.format(Locale.US, "%.1f km", meters / 1_000.0)
    else -> String.format(Locale.US, "%.0f m", meters)
}

/** Accumulated descent as a signed drop, e.g. `−182 m`. */
internal fun formatDescent(meters: Double): String =
    String.format(Locale.US, "−%.0f m", meters.coerceAtLeast(0.0))

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
