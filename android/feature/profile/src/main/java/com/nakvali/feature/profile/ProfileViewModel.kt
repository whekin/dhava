package com.nakvali.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.nakvali.core.recording.Bike
import com.nakvali.core.recording.BikeType
import com.nakvali.core.recording.RecordingRepository
import kotlinx.coroutines.flow.StateFlow

/** Local, offline-first garage state shown on the rider profile. */
class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RecordingRepository.getInstance(application)

    val bikes: StateFlow<List<Bike>> = repository.bikes
    val activeBikeId: StateFlow<String?> = repository.lastUsedBikeId

    fun addBike(name: String, type: BikeType) {
        repository.addBike(name, type, makeActive = true)
    }

    fun selectBike(id: String) = repository.selectBike(id)
}
