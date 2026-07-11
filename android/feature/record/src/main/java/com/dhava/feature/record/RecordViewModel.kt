package com.dhava.feature.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.dhava.core.recording.LocalRecording
import com.dhava.core.recording.RecordingRepository
import com.dhava.core.recording.RecordingService
import com.dhava.core.recording.RecordingState
import com.dhava.core.recording.UploadState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin bridge between the Record UI and the recording core: observes the
 * repository flows and forwards start/stop/upload commands. Manual wiring —
 * no DI framework yet.
 */
class RecordViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)

    val state: StateFlow<RecordingState> = repository.state
    val recordings: StateFlow<List<LocalRecording>> = repository.recordings
    val uploads: StateFlow<Map<String, UploadState>> = repository.uploads

    fun startRecording() = RecordingService.start(getApplication())

    fun stopRecording() = RecordingService.stop(getApplication())

    fun upload(id: String) = repository.upload(id)

    fun acknowledgeFinished() = repository.acknowledgeFinished()
}
