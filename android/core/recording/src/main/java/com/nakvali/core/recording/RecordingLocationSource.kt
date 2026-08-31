package com.nakvali.core.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

internal data class RecordingLocationPolicy(
    val provider: String,
    val intervalMs: Long,
    val minIntervalMs: Long,
)

internal fun recordingLocationPolicy(powerSaving: Boolean): RecordingLocationPolicy =
    if (powerSaving) {
        RecordingLocationPolicy(
            provider = LocationManager.GPS_PROVIDER,
            intervalMs = 5_000L,
            minIntervalMs = 2_500L,
        )
    } else {
        RecordingLocationPolicy(
            provider = LocationManager.GPS_PROVIDER,
            intervalMs = 1_000L,
            minIntervalMs = 500L,
        )
    }

internal data class RecordingGnssDiagnostics(
    val provider: String = LocationManager.GPS_PROVIDER,
    val providerEnabled: Boolean? = null,
    val gnssStarted: Boolean? = null,
    val ttffMs: Int? = null,
    val satellitesVisible: Int? = null,
    val satellitesUsed: Int? = null,
)

/**
 * Authoritative earth-position source for an active ride.
 *
 * A ride is timing evidence, so this source deliberately requests the platform
 * GNSS provider by name. No network/fused fallback is allowed into the raw
 * stream: while satellites are unavailable the correct result is a measurable
 * gap, not a fresh coarse coordinate with unknown provenance.
 */
internal class RecordingLocationSource(
    private val context: Context,
    handler: Handler,
    private val onLocation: (Location) -> Unit,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val executor = Executor { command -> handler.post(command) }

    @Volatile
    private var diagnostics = RecordingGnssDiagnostics(
        providerEnabled = providerEnabled(),
    )

    private var registered = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                diagnostics = diagnostics.copy(providerEnabled = true)
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                diagnostics = diagnostics.copy(providerEnabled = false)
            }
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            diagnostics = diagnostics.copy(gnssStarted = true)
        }

        override fun onStopped() {
            diagnostics = diagnostics.copy(
                gnssStarted = false,
                satellitesVisible = 0,
                satellitesUsed = 0,
            )
        }

        override fun onFirstFix(ttffMillis: Int) {
            diagnostics = diagnostics.copy(ttffMs = ttffMillis)
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (index in 0 until status.satelliteCount) {
                if (status.usedInFix(index)) used++
            }
            diagnostics = diagnostics.copy(
                satellitesVisible = status.satelliteCount,
                satellitesUsed = used,
            )
        }
    }

    fun start(powerSaving: Boolean) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Direct GNSS recording requires precise location")
        }
        val policy = recordingLocationPolicy(powerSaving)
        if (!registered) {
            registered = locationManager.registerGnssStatusCallback(executor, gnssStatusCallback)
        } else {
            locationManager.removeUpdates(locationListener)
        }
        val request = LocationRequest.Builder(policy.intervalMs)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(policy.minIntervalMs)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(0L)
            .build()
        locationManager.requestLocationUpdates(
            policy.provider,
            request,
            executor,
            locationListener,
        )
        diagnostics = diagnostics.copy(
            providerEnabled = providerEnabled(),
        )
    }

    fun stop() {
        locationManager.removeUpdates(locationListener)
        if (registered) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            registered = false
        }
    }

    fun diagnostics(): RecordingGnssDiagnostics = diagnostics.copy(
        providerEnabled = providerEnabled(),
    )

    private fun providerEnabled(): Boolean? = runCatching {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrNull()
}
