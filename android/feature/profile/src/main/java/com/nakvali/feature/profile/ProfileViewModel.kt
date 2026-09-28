package com.nakvali.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nakvali.core.recording.Bike
import com.nakvali.core.recording.BikeType
import com.nakvali.core.recording.RecordingRepository
import com.nakvali.core.recording.bikeyard.BikeyardMounting
import com.nakvali.core.recording.bikeyard.BikeyardRepository
import com.nakvali.core.recording.bikeyard.BikeyardUiState
import com.nakvali.core.recording.bikeyard.BikeyardVisibility
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Local, offline-first garage state shown on the rider profile. */
class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RecordingRepository.getInstance(application)
    private val bikeyardRepository = BikeyardRepository.getInstance(application)

    val bikeyard: StateFlow<BikeyardUiState> = bikeyardRepository.state

    /**
     * Starts BIKEYARD authorization and hands back the browser URL. [upgrade]
     * re-authorizes the connected rider to allow reading ride results.
     */
    fun connectBikeyard(upgrade: Boolean = false, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch { onResult(runCatching { bikeyardRepository.beginConnect(upgrade) }) }
    }
    fun refreshBikeyardProfile() = bikeyardRepository.refreshProfile()
    fun disconnectBikeyard() = bikeyardRepository.disconnect()
    fun cancelBikeyard() = bikeyardRepository.cancelConnect()
    fun bikeyardSettings(automatic: Boolean, visibility: BikeyardVisibility) =
        bikeyardRepository.setSettings(automatic, visibility)
    fun bikeyardMetricsSettings(enabled: Boolean, mounting: BikeyardMounting) =
        bikeyardRepository.setMetricsSettings(enabled, mounting)

    val bikes: StateFlow<List<Bike>> = repository.bikes
    val activeBikeId: StateFlow<String?> = repository.lastUsedBikeId

    fun addBike(name: String, type: BikeType) {
        repository.addBike(name, type, makeActive = true)
    }

    fun selectBike(id: String) = repository.selectBike(id)
}
