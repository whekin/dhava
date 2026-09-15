package com.nakvali.feature.activity

import com.nakvali.core.recording.StoredRideBounds
import com.nakvali.fusion.CanonicalTrackPoint

/**
 * Draft trim, as elapsed HH:MM:SS from the start of the recording.
 *
 * Like a transport draft it keeps [original] so a boundary the rider never
 * touched round-trips its exact millisecond instead of being re-quantized to a
 * whole second every time the editor is opened.
 */
internal data class TrimDraft(
    val start: String,
    val finish: String,
    val original: StoredRideBounds? = null,
) {
    fun bounds(originMs: Long): StoredRideBounds {
        val first =
            if (original != null && start == elapsedClock(original.startedAtMs - originMs)) {
                original.startedAtMs
            } else originMs + parseElapsedClock(start)
        val last =
            if (original != null && finish == elapsedClock(original.endedAtMs - originMs)) {
                original.endedAtMs
            } else originMs + parseElapsedClock(finish)
        return StoredRideBounds(first, last)
    }
}

internal data class TrimEditorState(
    val originMs: Long,
    val endedAtMs: Long,
    val track: List<CanonicalTrackPoint>,
    val draft: TrimDraft,
    /** True while the whole recording is the ride, i.e. nothing is trimmed. */
    val useWholeActivity: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

internal fun StoredRideBounds.draft(origin: Long) = TrimDraft(
    elapsedClock(startedAtMs - origin), elapsedClock(endedAtMs - origin), this,
)
