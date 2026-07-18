package com.dhava.feature.activity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.util.Log
import com.dhava.core.fusion.FusionCore
import com.dhava.core.recording.Bike
import com.dhava.core.recording.BikeType
import com.dhava.core.recording.CanonicalActivityArtifact
import com.dhava.core.recording.CanonicalQuality
import com.dhava.core.recording.GpsTrackReader
import com.dhava.core.recording.GpxExporter
import com.dhava.core.recording.GpxTrackPoint
import com.dhava.core.recording.LocalRecording
import com.dhava.core.recording.RecordLine
import com.dhava.core.recording.RecordingRepository
import com.dhava.core.recording.rawGpsPoints
import com.dhava.core.recording.toRecordingReplay
import com.dhava.core.recording.toRideAnalysis
import com.dhava.fusion.RideAnalysis
import com.dhava.fusion.RecordingReplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** GPS polyline load progress for the detail map. */
sealed interface TrackState {
    /** Streaming the raw file on IO. */
    data object Loading : TrackState

    /** Recording is readable but has no usable GPS fixes. */
    data object Empty : TrackState

    /**
     * Raw file missing or unreadable — a user-visible terminal state.
     * [rawFileMissing] distinguishes "gone" (nothing to export) from
     * "unreadable" (raw diagnostics export is exactly what a bug report needs).
     */
    data class Failed(val message: String, val rawFileMissing: Boolean = false) : TrackState

    data class Loaded(
        val points: List<RecordLine.Gps>,
    ) : TrackState
}

sealed interface DiagnosticTrackState {
    data object Loading : DiagnosticTrackState
    data object Unavailable : DiagnosticTrackState
    data class Loaded(val replay: RecordingReplay) : DiagnosticTrackState
}

/** What the share menu can produce; the mime type drives the share intent. */
enum class ActivityExportKind(val mimeType: String) {
    PROCESSED_5_HZ("application/gpx+xml"),
    RAW_GPS("application/gpx+xml"),
    /** The raw sensor recording as-is, for diagnostics/bug reports. */
    RAW_RECORDING("application/gzip"),
}

/**
 * Loads one recording's index entry plus its GPS polyline for the detail
 * screen. Manual wiring — no DI framework yet.
 */
class ActivityDetailViewModel(
    application: Application,
    private val recordingId: String,
) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)

    /** Index entry (title, bike, upload status); null once discarded. */
    val recording: StateFlow<LocalRecording?> = repository
        .recording(recordingId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _track = MutableStateFlow<TrackState>(TrackState.Loading)
    val track: StateFlow<TrackState> = _track.asStateFlow()

    /**
     * Canonical ride stats from the Rust fusion-core (UniFFI). Null while
     * computing or if analysis failed — tiles fall back to "—".
     */
    private val _analysis = MutableStateFlow<RideAnalysis?>(null)
    val analysis: StateFlow<RideAnalysis?> = _analysis.asStateFlow()

    private val _diagnostics = MutableStateFlow<DiagnosticTrackState>(DiagnosticTrackState.Loading)
    val diagnostics: StateFlow<DiagnosticTrackState> = _diagnostics.asStateFlow()

    /**
     * Rust-derived signal quality from the canonical artifact. Null while the
     * artifact is computing or on the legacy fallback path — the quality row
     * stays hidden instead of flashing wrong data.
     */
    private val _quality = MutableStateFlow<CanonicalQuality?>(null)
    val quality: StateFlow<CanonicalQuality?> = _quality.asStateFlow()

    @Volatile
    private var canonicalArtifact: CanonicalActivityArtifact? = null

    /** Bikes for the edit sheet's picker. */
    val bikes: StateFlow<List<Bike>> = repository.bikes

    fun addBike(name: String, type: BikeType): Bike = repository.addBike(name, type)

    fun updateMetadata(title: String, description: String, bike: Bike?) {
        viewModelScope.launch {
            repository.updateMetadata(recordingId, title, description, bike)
        }
    }

    /**
     * Deletes the whole activity (index entry, raw file, artifact, pending
     * upload). The screen pops itself once [recording] emits null.
     */
    fun deleteActivity() {
        viewModelScope.launch { repository.deleteActivity(recordingId) }
    }

    fun export(kind: ActivityExportKind, onResult: (Result<File>) -> Unit) {
        if (kind == ActivityExportKind.RAW_RECORDING) {
            exportRawRecording(onResult)
            return
        }
        val title = recording.value?.title ?: "Dhava ride"
        val replay = (_diagnostics.value as? DiagnosticTrackState.Loaded)?.replay
        val artifact = canonicalArtifact
        val points = when (kind) {
            ActivityExportKind.PROCESSED_5_HZ -> artifact?.finalizedTrack
                ?.takeIf { it.isNotEmpty() }
                ?.map { point ->
                    GpxTrackPoint(
                        timestampMs = point.timestampMs,
                        lat = point.lat,
                        lon = point.lon,
                        altitudeM = point.altitudeM,
                        sectionId = point.sectionId,
                    )
                }
            ActivityExportKind.RAW_GPS -> {
                artifact?.rawTrack?.map { point ->
                    GpxTrackPoint(point.timestampMs, point.lat, point.lon, point.altitudeM, point.sectionId)
                } ?: run {
                    val raw = (_track.value as? TrackState.Loaded)?.points
                    val sectionByTimestamp = replay?.rawTrack
                        ?.associate { point -> point.timestampMs to point.sectionId }
                        .orEmpty()
                    raw?.map { point ->
                        GpxTrackPoint(
                            timestampMs = point.timestampMs,
                            lat = point.lat,
                            lon = point.lon,
                            altitudeM = point.altitudeM,
                            sectionId = sectionByTimestamp[point.timestampMs] ?: 0,
                        )
                    }
                }
            }
            ActivityExportKind.RAW_RECORDING -> null // handled above
        }
        if (points.isNullOrEmpty()) {
            onResult(Result.failure(IllegalStateException("The selected GPX track is unavailable")))
            return
        }
        val suffix = when (kind) {
            ActivityExportKind.PROCESSED_5_HZ -> "processed-5hz"
            ActivityExportKind.RAW_GPS -> "raw-gps"
            ActivityExportKind.RAW_RECORDING -> error("unreachable")
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val output = File(
                    getApplication<Application>().cacheDir,
                    "exports/dhava-${recordingId.take(8)}-$suffix.gpx",
                )
                GpxExporter.write(points, title, output)
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /**
     * Shares the immutable raw sensor file as-is for diagnostics. The
     * FileProvider only exposes `cache/exports/`, so the file is copied there
     * first; the source under `files/recordings/` is never touched.
     */
    private fun exportRawRecording(onResult: (Result<File>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val source = repository.recordingFile(recordingId)
                check(source.isFile) { "The raw recording file is missing" }
                val output = File(
                    getApplication<Application>().cacheDir,
                    "exports/dhava-${recordingId.take(8)}-raw.jsonl.gz",
                )
                output.parentFile?.mkdirs()
                source.copyTo(output, overwrite = true)
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val rawFile = repository.recordingFile(recordingId)
            val path = rawFile.absolutePath
            val artifact = repository.canonicalActivity(recordingId)
            if (artifact != null) {
                canonicalArtifact = artifact
                _quality.value = artifact.quality
                val points = artifact.rawGpsPoints()
                _track.value = if (points.isEmpty()) TrackState.Empty else TrackState.Loaded(points)
                _analysis.value = artifact.toRideAnalysis()
                val replay = artifact.toRecordingReplay()
                _diagnostics.value = if (replay.rawTrack.isEmpty() && replay.finalizedTrack.isEmpty()) {
                    DiagnosticTrackState.Unavailable
                } else {
                    DiagnosticTrackState.Loaded(replay)
                }
                return@launch
            }

            // Raw file gone while the index entry still exists (external
            // cleanup, restored backup, …): a terminal error state instead of
            // a misleading "no GPS" empty state.
            if (!rawFile.isFile) {
                _track.value = TrackState.Failed(
                    message = "The raw recording file is missing from this phone.",
                    rawFileMissing = true,
                )
                _diagnostics.value = DiagnosticTrackState.Unavailable
                return@launch
            }

            // Damage-tolerant fallback for an artifact that cannot be built.
            // It preserves the old read path and leaves the raw file untouched.
            val points = GpsTrackReader.read(rawFile)
            _analysis.value = runCatching { FusionCore.analyze(path) }
                .onFailure { Log.w("ActivityDetail", "analysis fallback failed for $recordingId", it) }
                .getOrNull()
            val replayResult = runCatching { FusionCore.replay(path) }
                .onFailure { Log.w("ActivityDetail", "replay fallback failed for $recordingId", it) }
            _diagnostics.value = replayResult.getOrNull()
                ?.takeIf { it.rawTrack.isNotEmpty() || it.fusedTrack.isNotEmpty() }
                ?.let { DiagnosticTrackState.Loaded(it) }
                ?: DiagnosticTrackState.Unavailable
            _track.value = when {
                points.isNotEmpty() -> TrackState.Loaded(points)
                // No GPS anywhere and even the Rust replay failed: the file
                // itself is unreadable, not merely GPS-free.
                replayResult.isFailure -> TrackState.Failed(
                    "This recording could not be read. The raw file is preserved on this phone.",
                )
                else -> TrackState.Empty
            }
        }
    }

    companion object {
        fun factory(recordingId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("APPLICATION_KEY missing from ViewModel CreationExtras")
                ActivityDetailViewModel(application as Application, recordingId)
            }
        }
    }
}
