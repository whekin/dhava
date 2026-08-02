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
)

internal fun recordingNotificationPresentation(
    elapsedMs: Long,
    preparing: Boolean,
    recovering: Boolean,
    paused: Boolean,
    recentlyRecovered: Boolean,
): RecordingNotificationPresentation {
    val elapsed = formatNotificationElapsed(elapsedMs)
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
            text = "GPS and motion capture paused",
            action = RecordingNotificationAction.Resume,
        )
        recentlyRecovered -> RecordingNotificationPresentation(
            title = "Ride restored — $elapsed",
            text = "Recording resumed after interruption",
            action = RecordingNotificationAction.Pause,
        )
        else -> RecordingNotificationPresentation(
            title = "Recording ride — $elapsed",
            text = "GPS and motion capture active",
            action = RecordingNotificationAction.Pause,
        )
    }
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
