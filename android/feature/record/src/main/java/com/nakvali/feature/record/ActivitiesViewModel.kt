package com.nakvali.feature.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.nakvali.core.recording.ActivitySummary
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordingRepository
import com.nakvali.core.recording.UploadState
import com.nakvali.core.recording.bikeyard.BikeyardRepository
import com.nakvali.core.recording.bikeyard.BikeyardUiState
import com.nakvali.core.recording.bikeyard.BikeyardUploadStatus
import kotlinx.coroutines.flow.StateFlow

/** The ride list: local recordings, their list summaries and BIKEYARD receipts. */
class ActivitiesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RecordingRepository.getInstance(application)
    private val bikeyard = BikeyardRepository.getInstance(application)

    val recordings: StateFlow<List<LocalRecording>> = repository.recordings
    val uploads: StateFlow<Map<String, UploadState>> = repository.uploads
    val summaries: StateFlow<Map<String, ActivitySummary>> = repository.summaries
    val bikeyardState: StateFlow<BikeyardUiState> = bikeyard.state

    /** The list is on screen: fill in missing maps and read back BIKEYARD results. */
    fun onVisible() {
        repository.prepareSummaries()
        refreshBikeyardResults()
    }

    fun onHidden() = repository.stopPreparingSummaries()

    fun refreshBikeyardResults() {
        val state = bikeyard.state.value
        val uploaded = recordings.value.map { it.id }.filter { id ->
            state.uploadFor(id)?.status == BikeyardUploadStatus.UPLOADED
        }
        bikeyard.refreshRideResults(uploaded)
    }

    fun retryUpload(id: String) = repository.retryUpload(id)
}
