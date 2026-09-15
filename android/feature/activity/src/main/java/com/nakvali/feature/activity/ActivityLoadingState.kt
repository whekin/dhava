package com.nakvali.feature.activity

import com.nakvali.core.recording.CanonicalActivityArtifact
import com.nakvali.fusion.CanonicalPreviewPoint
import com.nakvali.fusion.CanonicalProgress
import com.nakvali.fusion.CanonicalStage

internal enum class ActivityLoadPhase(val title: String) {
    OPENING("Opening activity"), READING("Reading recording"), MOTION("Analyzing sensor data"),
    TRACK("Refining the track"), ELEVATION("Building elevation"), TRANSPORT("Finding transport episodes"),
    FINALIZING("Saving the analysis"), PREPARING("Preparing activity"), PROFILE("Preparing the elevation profile"), READY("Ready"), FAILED("Analysis unavailable"),
}

/** Preview points are display-only and cannot enter results or export. */
internal data class ActivityLoadingState(
    val phase: ActivityLoadPhase = ActivityLoadPhase.OPENING,
    val preview: List<CanonicalPreviewPoint> = emptyList(),
    val fromCache: Boolean = false,
    val readFraction: Float? = null,
    val gpsFixes: Long = 0,
) {
    fun finishedPreview(update: CanonicalProgress) = if (phase == ActivityLoadPhase.OPENING) copy(
        phase = ActivityLoadPhase.PREPARING, preview = update.preview.ifEmpty { preview },
        fromCache = true, readFraction = null, gpsFixes = update.gpsFixes.toLong(),
    ) else progress(update).copy(readFraction = null)

    fun progress(update: CanonicalProgress) = copy(
        phase = when (update.stage) {
            CanonicalStage.READING -> ActivityLoadPhase.READING
            CanonicalStage.MOTION -> ActivityLoadPhase.MOTION
            CanonicalStage.TRACK -> ActivityLoadPhase.TRACK
            CanonicalStage.ELEVATION -> ActivityLoadPhase.ELEVATION
            CanonicalStage.TRANSPORT -> ActivityLoadPhase.TRANSPORT
            CanonicalStage.FINALIZING -> ActivityLoadPhase.FINALIZING
        },
        preview = if (fromCache) preview else update.preview.ifEmpty { preview },
        readFraction = if (update.stage == CanonicalStage.READING && update.totalBytes > 0uL)
            (update.readBytes.toDouble() / update.totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else null,
        gpsFixes = update.gpsFixes.toLong(),
    )

    fun cached(artifact: CanonicalActivityArtifact): ActivityLoadingState {
        val points = artifact.rawTrack
        val stride = ((points.size + 1_998) / 1_999).coerceAtLeast(1)
        return copy(fromCache = true, gpsFixes = points.size.toLong(), preview = points.filterIndexed { i, point ->
            (i % stride == 0 || i == points.lastIndex) && point.lat in -90.0..90.0 && point.lon in -180.0..180.0
        }.map { CanonicalPreviewPoint(it.timestampMs, it.lat, it.lon, it.accuracyM) })
    }
}
