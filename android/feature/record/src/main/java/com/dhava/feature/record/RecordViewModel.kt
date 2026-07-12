package com.dhava.feature.record

import android.app.Application
import android.os.PowerManager
import androidx.core.content.ContextCompat
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

    companion object {
        /**
         * Process-scoped "asked already" latch for the battery-exemption
         * dialog: at most one prompt per app run, asked again on the next
         * run only while the app is still not exempt. Deliberately not
         * persisted — a declined prompt should come back eventually, and
         * "next app start" is the simple version of that.
         */
        private var batteryExemptionAskedThisRun = false
    }

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

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    fun startRecording() {
        val free = getApplication<Application>().filesDir.usableSpace
        if (free < 250L * 1024 * 1024) {
            _startError.value = "Not enough free storage. Keep at least 250 MB available."
            return
        }
        _startError.value = null
        RecordingService.start(getApplication())
    }

    /**
     * True when hitting Start should also surface the battery-optimization
     * exemption dialog (2026-07 OnePlus incident: aggressive OEM battery
     * managers kill recording mid-ride). Consumes the once-per-run latch,
     * so a second Start in the same run never nags again.
     */
    fun shouldAskBatteryExemption(): Boolean {
        if (batteryExemptionAskedThisRun) return false
        val application = getApplication<Application>()
        val powerManager = ContextCompat.getSystemService(application, PowerManager::class.java)
        val exempt = powerManager?.isIgnoringBatteryOptimizations(application.packageName) ?: true
        if (exempt) return false
        batteryExemptionAskedThisRun = true
        return true
    }

    fun stopRecording() = RecordingService.stop(getApplication())

    fun pauseRecording() = RecordingService.pause(getApplication())

    fun resumeRecording() = RecordingService.resume(getApplication())

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
