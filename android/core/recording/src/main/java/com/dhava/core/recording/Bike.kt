package com.dhava.core.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bike type. The [SerialName] values are the wire format sent to the backend
 * on activity finish (`bike_type`) — do not rename.
 */
@Serializable
enum class BikeType(val label: String) {
    @SerialName("full_sus") FULL_SUS("Full-sus"),
    @SerialName("hardtail") HARDTAIL("Hardtail"),
    @SerialName("ebike") EBIKE("E-bike"),
    @SerialName("other") OTHER("Other"),
}

/** One bike in the user's local garage. */
@Serializable
data class Bike(
    val id: String,
    val name: String,
    val type: BikeType,
)

/**
 * On-device bike store (`bikes.json`, next to `recordings.json`), managed by
 * [RecordingRepository]. Migrates to Room together with the recording index.
 */
@Serializable
internal data class BikesFile(
    val bikes: List<Bike> = emptyList(),
    /** Preselected in the save sheet as "your usual bike". */
    @SerialName("last_used_id") val lastUsedId: String? = null,
)

/** JSON codec shared by the flat on-device index files. */
internal val IndexJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}
