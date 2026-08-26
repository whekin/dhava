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

/**
 * `12:34` under an hour, `1:02:33` above it.
 *
 * The zero-padded `00:12:34` the live sheet used before was eight glyphs wide
 * and ended up as visually heavy as the speed beside it, so nothing on the
 * screen declared which number was the subject. Dropping an hour that has not
 * happened yet halves the width and costs no information.
 */
internal fun formatElapsedShort(elapsedMs: Long): String {
    val totalSeconds = elapsedMs.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * A measurement with its unit kept separate.
 *
 * The live sheet sets the number at display size and the unit at label size, on
 * the same baseline, so the pair reads in one fixation. A pre-joined `"1.2 km"`
 * cannot be typeset that way.
 */
internal data class Measured(val value: String, val unit: String)

/** `480 m` below a kilometre, `1.2 km` above it — the activity screen's rule. */
internal fun formatDistance(meters: Double): String =
    measuredDistance(meters).let { "${it.value} ${it.unit}" }

internal fun measuredDistance(meters: Double): Measured = when {
    meters >= 1_000.0 -> Measured(String.format(Locale.US, "%.1f", meters / 1_000.0), "km")
    else -> Measured(String.format(Locale.US, "%.0f", meters), "m")
}

/** Accumulated descent as a signed drop, e.g. `−182 m`. */
internal fun formatDescent(meters: Double): String =
    measuredDescent(meters).let { "${it.value} ${it.unit}" }

internal fun measuredDescent(meters: Double): Measured =
    Measured(String.format(Locale.US, "−%.0f", meters.coerceAtLeast(0.0)), "m")

/** Live speed in km/h, or an em dash while the fix is still settling. */
internal fun measuredSpeed(metersPerSecond: Float?): Measured = Measured(
    value = metersPerSecond?.let { String.format(Locale.US, "%.1f", it * 3.6f) } ?: "—",
    unit = "km/h",
)

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
