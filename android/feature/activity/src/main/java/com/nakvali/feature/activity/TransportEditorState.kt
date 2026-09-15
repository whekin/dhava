package com.nakvali.feature.activity

import com.nakvali.core.recording.StoredTransportEpisode
import com.nakvali.fusion.CanonicalTrackPoint
import java.util.Locale

internal data class TransportDraft(
    val start: String,
    val finish: String,
    val original: StoredTransportEpisode? = null,
) {
    fun interval(originMs: Long): StoredTransportEpisode {
        val first = if (original != null && start == elapsedClock(original.startedAtMs - originMs)) original.startedAtMs
            else originMs + parseElapsedClock(start)
        val last = if (original != null && finish == elapsedClock(original.endedAtMs - originMs)) original.endedAtMs
            else originMs + parseElapsedClock(finish)
        return StoredTransportEpisode(first, last)
    }
}

internal data class TransportEditorState(
    val originMs: Long,
    val endedAtMs: Long,
    val track: List<CanonicalTrackPoint>,
    val automatic: List<StoredTransportEpisode>,
    val drafts: List<TransportDraft>,
    val useAutomatic: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

internal fun StoredTransportEpisode.draft(origin: Long) = TransportDraft(
    elapsedClock(startedAtMs - origin), elapsedClock(endedAtMs - origin), this,
)

internal fun elapsedClock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

internal fun parseElapsedClock(text: String): Long {
    require(Regex("[0-9]{1,3}:[0-5][0-9]:[0-5][0-9]").matches(text)) { "Use elapsed time as HH:MM:SS" }
    val (hours, minutes, seconds) = text.split(':').map(String::toLong)
    return (hours * 3600 + minutes * 60 + seconds) * 1000
}
