package com.nakvali.core.recording

import java.util.Locale

internal const val RECOVERY_NOTIFICATION_DURATION_MS = 30_000L

internal enum class RecordingNotificationAction {
    Pause,
    Resume,
}

internal data class RecordingNotificationPresentation(
    val title: String,
    val text: String,
    val action: RecordingNotificationAction?,
    /** Shown when the rider expands the notification; null keeps one line. */
    val expandedText: String? = null,
)

/**
 * [distanceM] and [descentM] are the live Rust totals and [lastRun] is the
 * segment run just completed. A rider who stops and glances at the phone
 * should read the ride from the shade without unlocking it, so the collapsed
 * line carries the newest thing worth knowing — the run if there was one,
 * the totals otherwise — and expanding shows both.
 */
internal fun recordingNotificationPresentation(
    elapsedMs: Long,
    preparing: Boolean,
    recovering: Boolean,
    paused: Boolean,
    recentlyRecovered: Boolean,
    distanceM: Double = 0.0,
    descentM: Double = 0.0,
    lastRun: LiveSegmentRun? = null,
    powerSaving: Boolean = false,
): RecordingNotificationPresentation {
    val elapsed = formatNotificationElapsed(elapsedMs)
    val run = lastRun?.let(::formatNotificationRun)
    val totals = formatNotificationTotals(distanceM, descentM)
    val headline = run ?: totals
    val expanded = if (run != null && totals != null) "$run\n$totals" else null
    return when {
        recovering -> RecordingNotificationPresentation(
            title = "Restoring interrupted ride",
            text = "Your recorded data is safe",
            action = null,
        )
        preparing -> RecordingNotificationPresentation(
            title = "Preparing ride",
            text = "Warming GPS and motion sensors",
            action = null,
        )
        paused -> RecordingNotificationPresentation(
            title = "Ride paused — $elapsed",
            text = headline?.let { "$it · capture paused" } ?: "GPS and motion capture paused",
            action = RecordingNotificationAction.Resume,
            expandedText = expanded,
        )
        recentlyRecovered -> RecordingNotificationPresentation(
            title = "Ride restored — $elapsed",
            text = headline ?: "Recording resumed after interruption",
            action = RecordingNotificationAction.Pause,
            expandedText = expanded,
        )
        powerSaving -> RecordingNotificationPresentation(
            title = "Recording ride — $elapsed",
            // Reduced sampling is never silent: a rider looking at the shade
            // should know the transit is being recorded coarsely on purpose.
            text = headline?.let { "$it · transport" } ?: "Transport · saving power",
            action = RecordingNotificationAction.Pause,
            expandedText = expanded,
        )
        else -> RecordingNotificationPresentation(
            title = "Recording ride — $elapsed",
            text = headline ?: "GPS and motion capture active",
            action = RecordingNotificationAction.Pause,
            expandedText = expanded,
        )
    }
}

/**
 * `Ridgeline 3:23.6 · PR −2.1 s`. Marked provisional nowhere here: the shade
 * has no room for the caveat, and the recording screen carries it instead.
 */
internal fun formatNotificationRun(run: LiveSegmentRun): String = buildString {
    append(run.name)
    append(' ')
    val elapsed = run.elapsedMs.coerceAtLeast(0)
    append(
        String.format(
            Locale.US,
            "%d:%02d.%d",
            elapsed / 60_000,
            (elapsed % 60_000) / 1_000,
            (elapsed % 1_000) / 100,
        ),
    )
    if (run.personalRecord) append(" · PR")
    run.deltaMs?.let { delta ->
        append(String.format(Locale.US, " · %s%.1f s", if (delta < 0) "−" else "+", kotlin.math.abs(delta) / 1_000.0))
    }
}

/**
 * `8.2 km · −612 m`, or null before the ride has covered any ground — an
 * all-zero line would read like a broken recorder during GPS warm-up.
 */
internal fun formatNotificationTotals(distanceM: Double, descentM: Double): String? {
    if (distanceM < 1.0 && descentM < 1.0) return null
    val distance = if (distanceM >= 1_000.0) {
        String.format(Locale.US, "%.1f km", distanceM / 1_000.0)
    } else {
        String.format(Locale.US, "%.0f m", distanceM)
    }
    return String.format(Locale.US, "%s · −%.0f m", distance, descentM.coerceAtLeast(0.0))
}

internal fun formatNotificationElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1_000
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3_600,
        (totalSeconds % 3_600) / 60,
        totalSeconds % 60,
    )
}

internal fun isRecoveryNotificationActive(
    recoveredAtElapsedMs: Long,
    nowElapsedMs: Long,
): Boolean {
    if (recoveredAtElapsedMs == Long.MIN_VALUE) return false
    return nowElapsedMs - recoveredAtElapsedMs in 0 until RECOVERY_NOTIFICATION_DURATION_MS
}
