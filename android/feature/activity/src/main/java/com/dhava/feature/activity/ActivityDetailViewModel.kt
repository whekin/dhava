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
import com.dhava.core.recording.LocalRecording
import com.dhava.core.recording.RecordLine
import com.dhava.core.recording.RecordingRepository
import com.dhava.fusion.RideAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

/**
 * Loads one recording's index entry plus its GPS polyline for the detail
 * screen. Manual wiring — no DI framework yet.
 */
class ActivityDetailViewModel(
    application: Application,
    recordingId: String,
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
            _analysis.value = try {
                FusionCore.analyze(repository.recordingFile(recordingId).absolutePath)
            } catch (e: Exception) {
                // Unanalyzable file (missing, empty, corrupt beyond repair) —
                // the screen simply keeps placeholder tiles.
                Log.w("ActivityDetail", "fusion-core analysis failed for $recordingId", e)
                null
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
