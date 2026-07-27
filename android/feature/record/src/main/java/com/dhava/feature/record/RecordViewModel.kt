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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    fun startRecording() {
        if (!hasRecordingStorage()) return
        _startError.value = null
        RecordingService.start(getApplication())
    }

    fun continueRecording(id: String) {
        if (!hasRecordingStorage()) return
        _startError.value = null
        RecordingService.continueRecording(getApplication(), id)
    }

    private fun hasRecordingStorage(): Boolean {
        val free = getApplication<Application>().filesDir.usableSpace
        if (free >= 250L * 1024 * 1024) return true
        _startError.value = "Not enough free storage. Keep at least 250 MB available."
        return false
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

    /** Leave the save workspace without deleting the finalized raw recording. */
    fun dismissSave() = closeSave()

    private fun closeSave() {
        repository.acknowledgeFinished()
    }
}
