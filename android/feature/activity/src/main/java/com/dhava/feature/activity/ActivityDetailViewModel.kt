package com.dhava.feature.activity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.util.Log
import com.dhava.core.fusion.FusionCore
import com.dhava.core.recording.GpsTrackReader
import com.dhava.core.recording.GpxExporter
import com.dhava.core.recording.GpxTrackPoint
import com.dhava.core.recording.LocalRecording
import com.dhava.core.recording.RecordLine
import com.dhava.core.recording.RecordingRepository
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

    /** Recording has no usable GPS fixes (or the file is gone). */
    data object Empty : TrackState

    data class Loaded(
        val points: List<RecordLine.Gps>,
    ) : TrackState
}

sealed interface DiagnosticTrackState {
    data object Loading : DiagnosticTrackState
    data object Unavailable : DiagnosticTrackState
    data class Loaded(val replay: RecordingReplay) : DiagnosticTrackState
}

enum class GpxExportKind {
    PROCESSED_5_HZ,
    RAW_GPS,
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

    fun exportGpx(kind: GpxExportKind, onResult: (Result<File>) -> Unit) {
        val title = recording.value?.title ?: "Dhava ride"
        val replay = (_diagnostics.value as? DiagnosticTrackState.Loaded)?.replay
        val points = when (kind) {
            GpxExportKind.PROCESSED_5_HZ -> replay?.finalizedTrack
                ?.takeIf { it.isNotEmpty() }
                ?.map { point ->
                    GpxTrackPoint(
                        timestampMs = point.timestampMs,
                        lat = point.lat,
                        lon = point.lon,
                        sectionId = point.sectionId,
                    )
                }
            GpxExportKind.RAW_GPS -> {
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
        if (points.isNullOrEmpty()) {
            onResult(Result.failure(IllegalStateException("The selected GPX track is unavailable")))
            return
        }
        val suffix = when (kind) {
            GpxExportKind.PROCESSED_5_HZ -> "processed-5hz"
            GpxExportKind.RAW_GPS -> "raw-gps"
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

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // One streaming pass over the raw file; see GpsTrackReader for
            // why this stays display-only (polyline for the map, nothing else).
            val points = GpsTrackReader.read(repository.recordingFile(recordingId))
            _track.value = if (points.isEmpty()) {
                TrackState.Empty
            } else {
                TrackState.Loaded(points = points)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val path = repository.recordingFile(recordingId).absolutePath
            _analysis.value = try {
                FusionCore.analyze(path)
            } catch (e: Exception) {
                // Unanalyzable file (missing, empty, corrupt beyond repair) —
                // the screen simply keeps placeholder tiles.
                Log.w("ActivityDetail", "fusion-core analysis failed for $recordingId", e)
                null
            }
            _diagnostics.value = try {
                val replay = FusionCore.replay(path)
                if (replay.rawTrack.isEmpty() && replay.fusedTrack.isEmpty()) {
                    DiagnosticTrackState.Unavailable
                } else {
                    DiagnosticTrackState.Loaded(replay)
                }
            } catch (e: Exception) {
                Log.w("ActivityDetail", "live replay failed for $recordingId", e)
                DiagnosticTrackState.Unavailable
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
