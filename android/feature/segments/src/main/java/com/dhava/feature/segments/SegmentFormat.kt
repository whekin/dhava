package com.dhava.feature.segments

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Presentation of segment results.
 *
 * Every result is shown with its uncertainty, because a downhill run timed
 * from GPS gate crossings is never exact: `2:31.4 ± 0.8 s` is the honest form,
 * `2:31.4` alone would imply a precision the sensors do not have. Formatting is
 * pure and unit-tested; the numbers themselves come from Rust.
 */
object SegmentFormat {

    /** `2:31.4`, or `1:04:02.7` for runs over an hour. */
    fun elapsed(elapsedMs: Long): String {
        val totalTenths = (abs(elapsedMs) / 100.0).roundToLong()
        val tenths = totalTenths % 10
        val totalSeconds = totalTenths / 10
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3_600
        val sign = if (elapsedMs < 0) "-" else ""
        return if (hours > 0) {
            String.format(Locale.US, "%s%d:%02d:%02d.%d", sign, hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.US, "%s%d:%02d.%d", sign, minutes, seconds, tenths)
        }
    }

    /** `± 0.8 s`; never rounds a real uncertainty down to zero. */
    fun uncertainty(uncertaintyMs: Long): String {
        val seconds = abs(uncertaintyMs) / 1000.0
        val shown = if (uncertaintyMs != 0L && seconds < 0.1) 0.1 else seconds
        return String.format(Locale.US, "± %.1f s", shown)
    }

    /** `2:31.4 ± 0.8 s`. */
    fun elapsedWithUncertainty(elapsedMs: Long, uncertaintyMs: Long): String =
        "${elapsed(elapsedMs)} ${uncertainty(uncertaintyMs)}"

    /** `480 m` below a kilometer, `1.24 km` above it. */
    fun length(lengthM: Double): String = if (lengthM < 1_000.0) {
        String.format(Locale.US, "%.0f m", lengthM)
    } else {
        String.format(Locale.US, "%.2f km", lengthM / 1_000.0)
    }

    /** Accumulated descent, e.g. `−182 m`; null when unknown. */
    fun descent(descentM: Double?): String? = descentM?.let {
        String.format(Locale.US, "−%.0f m", abs(it))
    }

    /** Accumulated climb, e.g. `+24 m`; null when unknown. */
    fun ascent(ascentM: Double?): String? = ascentM?.let {
        String.format(Locale.US, "+%.0f m", abs(it))
    }

    fun altitude(altitudeM: Double): String =
        String.format(Locale.US, "%.0f m", altitudeM)

    /** Average speed of a run, `28.4 km/h`; null when it cannot be derived. */
    fun averageSpeed(lengthM: Double, elapsedMs: Long): String? {
        if (elapsedMs <= 0L || lengthM <= 0.0) return null
        val kmh = lengthM / (elapsedMs / 1_000.0) * 3.6
        return String.format(Locale.US, "%.1f km/h", kmh)
    }
}
