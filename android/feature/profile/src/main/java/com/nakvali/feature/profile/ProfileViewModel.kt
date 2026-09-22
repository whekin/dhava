package com.nakvali.feature.profile

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.lifecycle.AndroidViewModel
import com.nakvali.core.recording.Bike
import com.nakvali.core.recording.BikeType
import com.nakvali.core.recording.RecordingRepository
import kotlinx.coroutines.flow.StateFlow

/** Local, offline-first garage state shown on the rider profile. */
class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RecordingRepository.getInstance(application)

    private val bikeyardRepository = com.nakvali.core.recording.bikeyard.BikeyardRepository.getInstance(application)
    val bikeyard = bikeyardRepository.state
    fun connectBikeyard(onResult: (Result<String>) -> Unit) {
        viewModelScope.launch { onResult(runCatching { bikeyardRepository.beginConnect() }) }
    }
    fun disconnectBikeyard() = bikeyardRepository.disconnect()
    fun cancelBikeyard() = bikeyardRepository.cancelConnect()
    fun bikeyardSettings(automatic: Boolean, visibility: com.nakvali.core.recording.bikeyard.BikeyardVisibility) = bikeyardRepository.setSettings(automatic, visibility)

    val bikes: StateFlow<List<Bike>> = repository.bikes
    val activeBikeId: StateFlow<String?> = repository.lastUsedBikeId

    fun addBike(name: String, type: BikeType) {
        repository.addBike(name, type, makeActive = true)
    }

    fun selectBike(id: String) = repository.selectBike(id)
}
