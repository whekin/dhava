package com.dhava.feature.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.dhava.core.recording.Bike
import com.dhava.core.recording.BikeType
import com.dhava.core.recording.LocalRecording
import com.dhava.core.recording.RecordingRepository
import com.dhava.core.recording.RecordingService
import com.dhava.core.recording.RecordingState
import com.dhava.core.recording.UploadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin bridge between the Record UI and the recording core: observes the
 * repository flows and forwards start/stop/save commands. Manual wiring —
 * no DI framework yet.
 */
class RecordViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)

    val state: StateFlow<RecordingState> = repository.state
    val recordings: StateFlow<List<LocalRecording>> = repository.recordings
    val uploads: StateFlow<Map<String, UploadState>> = repository.uploads
    val bikes: StateFlow<List<Bike>> = repository.bikes
    val lastUsedBikeId: StateFlow<String?> = repository.lastUsedBikeId

    /**
     * Recording id whose save sheet was reopened from the list ("Finish
     * saving" on an unsaved entry). The just-stopped recording opens the
     * sheet through [RecordingState.Finished] instead.
     */
    private val _reopenedSaveId = MutableStateFlow<String?>(null)
    val reopenedSaveId: StateFlow<String?> = _reopenedSaveId.asStateFlow()

    fun startRecording() = RecordingService.start(getApplication())

    fun stopRecording() = RecordingService.stop(getApplication())

    fun openSave(id: String) {
        _reopenedSaveId.value = id
    }

    fun save(id: String, title: String, description: String, bike: Bike?) {
        repository.saveActivity(id, title, description, bike)
        closeSave()
    }

    fun discard(id: String) {
        repository.discard(id)
        closeSave()
    }

    fun addBike(name: String, type: BikeType): Bike = repository.addBike(name, type)

    fun retryUpload(id: String) = repository.retryUpload(id)

    private fun closeSave() {
        _reopenedSaveId.value = null
        repository.acknowledgeFinished()
    }
}
