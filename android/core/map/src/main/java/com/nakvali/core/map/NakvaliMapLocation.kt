package com.nakvali.core.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Where the rider is, for framing a map — not for recording. */
data class NakvaliLocationFix(val lat: Double, val lon: Double)

/** Cached fixes older than this are refreshed before the camera is moved. */
private const val FRESH_FIX_AGE_MS = 60_000L

/**
 * One location fix for a map action such as "my location".
 *
 * Returns null instead of throwing when the permission is missing or no fix can
 * be obtained, so a browsing screen degrades to "we don't know where you are"
 * rather than failing. Recording never uses this path: it owns its own
 * continuous, high-rate location stream.
 */
suspend fun currentLocationFix(context: Context): NakvaliLocationFix? {
    // Fine location, matching what recording already asks for: a rider who has
    // never granted it has no fix to frame anyway.
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val tokenSource = CancellationTokenSource()
    return try {
        val cached = client.lastLocation.awaitOrNull()
        val fresh = cached?.takeIf {
            System.currentTimeMillis() - it.time <= FRESH_FIX_AGE_MS
        }
        val fix = fresh
            ?: client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                tokenSource.token,
            ).awaitOrNull()
            // A stale cached fix still beats no answer at all for framing a map.
            ?: cached
        fix?.let { NakvaliLocationFix(it.latitude, it.longitude) }
    } catch (revoked: SecurityException) {
        // The permission can be withdrawn between the check and the call.
        null
    }
}

private suspend fun Task<Location>.awaitOrNull(): Location? =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { location -> continuation.resume(location) }
        addOnFailureListener { continuation.resume(null) }
        addOnCanceledListener { continuation.resume(null) }
    }
