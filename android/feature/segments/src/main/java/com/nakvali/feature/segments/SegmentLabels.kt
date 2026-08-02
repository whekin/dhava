package com.nakvali.feature.segments

import com.nakvali.core.recording.StoredAttemptFlag
import com.nakvali.core.recording.StoredRejectionReason

/**
 * Wording for Rust's attempt flags and rejection reasons.
 *
 * A rejected gate pair is always shown with its reason: silently dropping it
 * would leave the rider guessing why a run they remember riding is missing.
 */
internal fun StoredRejectionReason.label(): String = when (this) {
    StoredRejectionReason.NO_FINISH -> "No finish"
    StoredRejectionReason.PAUSED_INSIDE -> "Paused inside"
    StoredRejectionReason.GAP_INSIDE -> "Recording gap"
    StoredRejectionReason.OFF_CORRIDOR -> "Off the segment"
    StoredRejectionReason.BACKTRACKED -> "Rode backwards"
    StoredRejectionReason.INCOMPLETE -> "Segment not covered"
}

internal fun StoredAttemptFlag.label(): String = when (this) {
    StoredAttemptFlag.DEFINING_RIDE -> "Defines segment"
    StoredAttemptFlag.LOW_GPS_QUALITY -> "Weak GPS"
    StoredAttemptFlag.LIKELY_MOTORIZED -> "Transport?"
    StoredAttemptFlag.HIGH_UNCERTAINTY -> "Wide margin"
}

internal fun StoredAttemptFlag.explanation(): String = when (this) {
    StoredAttemptFlag.DEFINING_RIDE ->
        "This ride drew the segment, so it cannot confirm its geometry."
    StoredAttemptFlag.LOW_GPS_QUALITY ->
        "Median GPS accuracy inside this run was worse than 15 m."
    StoredAttemptFlag.LIKELY_MOTORIZED ->
        "The ride classifier saw vehicle-like evidence inside this run."
    StoredAttemptFlag.HIGH_UNCERTAINTY ->
        "The timing margin is large compared to the result itself."
}
