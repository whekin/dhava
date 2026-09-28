package com.nakvali.core.recording.bikeyard

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
enum class BikeyardEnvironment {
    // Kept only to decode and retire state written by the sandbox alpha.
    SANDBOX,
    LIVE;

    val clientId: String get() {
        check(this == LIVE) { "Sandbox connections are no longer supported" }
        return "yb_live_3dnewziryr55f5hvrgd2"
    }
    val apiOrigin: String get() {
        check(this == LIVE) { "Sandbox connections are no longer supported" }
        return "https://open.yard.bike"
    }
}

@Serializable
enum class BikeyardVisibility(val wire: String, val label: String) {
    PRIVATE("private", "Only me"), PUBLIC("public", "Public"),
}

@Serializable
enum class BikeyardUploadStatus { QUEUED, UPLOADING, UPLOADED, FAILED, NEEDS_AUTH, DUPLICATE, CANCELLED }

@Serializable
enum class BikeyardMetricsStatus { NONE, QUEUED, UPLOADING, UPLOADED, FAILED, NEEDS_AUTH, CANCELLED }

@Serializable
enum class BikeyardMounting(val wire: String, val label: String) {
    UNKNOWN("unknown", "Not sure"),
    POCKET("pocket", "Pocket"),
    HANDLEBAR("handlebar", "Handlebar"),
    FRAME("frame", "Frame"),
    BODY("body", "Firmly on body"),
    BAG("bag", "Bag"),
}

@Serializable
data class BikeyardAutoRequest(
    val accountKey: String,
    val consentId: String,
    val visibility: BikeyardVisibility,
    val sensorMetrics: Boolean = false,
    val mounting: BikeyardMounting = BikeyardMounting.UNKNOWN,
)

@Serializable
data class BikeyardSensorScope(val startedAtMs: Long, val endedAtMs: Long)

/**
 * What the token lets Nakvali read back. Uploading never needs it; ride pages,
 * trail matches and achievements do, and private rides need [ALL].
 */
enum class BikeyardRideAccess { NONE, PUBLIC, ALL }

/** Lifetime totals from `GET /v1/me`. Private rides count only with [BikeyardRideAccess.ALL]. */
@Serializable
data class BikeyardStats(
    val rideCount: Int = 0,
    val distanceM: Double = 0.0,
    val movingS: Long = 0,
    val elevationGainM: Double = 0.0,
)

/** The connected rider as BIKEYARD shows them; refreshed opportunistically. */
@Serializable
data class BikeyardProfile(
    val username: String = "",
    val profileUrl: String? = null,
    val location: String = "",
    val repLevel: Int? = null,
    val repScore: Int? = null,
    val stats: BikeyardStats? = null,
    val includesPrivate: Boolean = false,
    val fetchedAtMs: Long = 0,
    /**
     * Summed from the rider's ride list, since `/v1/me` totals only climb.
     * Null until read; needs ride read access.
     */
    val descentM: Double? = null,
    val kom: Int = 0,
    val medals: Int = 0,
    val localLegend: Int = 0,
    /** More rides than one refresh reads; the sums cover the newest only. */
    val totalsPartial: Boolean = false,
)

@Serializable
enum class BikeyardAchievementKind { KOM, PERSONAL_BEST, LOCAL_LEGEND }

/** One trail BIKEYARD matched in an uploaded ride. */
@Serializable
data class BikeyardTrailResult(
    val name: String,
    val durationS: Int,
    val complete: Boolean,
    val achievement: BikeyardAchievementKind? = null,
    val rank: Int? = null,
)

/**
 * BIKEYARD's own processing of an uploaded ride. It is their trail matching,
 * not Nakvali's canonical timing, and is shown as such.
 */
@Serializable
data class BikeyardRideResult(
    val url: String? = null,
    /** False while BIKEYARD is still matching trails; counts may be zero. */
    val ready: Boolean = false,
    val kom: Int = 0,
    val medals: Int = 0,
    val localLegend: Int = 0,
    val trails: List<BikeyardTrailResult> = emptyList(),
    val likeCount: Int = 0,
    val commentCount: Int = 0,
) {
    val achievementCount: Int get() = kom + medals + localLegend
}

@Serializable
data class BikeyardUpload(
    val key: String,
    val recordingId: String,
    val accountKey: String,
    val visibility: BikeyardVisibility,
    val automatic: Boolean,
    val status: BikeyardUploadStatus = BikeyardUploadStatus.QUEUED,
    val externalId: String,
    val name: String,
    val description: String,
    val bikeType: String,
    val prepared: Boolean = false,
    val sensorScopes: List<BikeyardSensorScope> = emptyList(),
    val uploadId: String? = null,
    val rideId: String? = null,
    val retryAtMs: Long = 0,
    val error: String? = null,
    val metricsStatus: BikeyardMetricsStatus = BikeyardMetricsStatus.NONE,
    val metricsAutomatic: Boolean = false,
    val metricsMounting: BikeyardMounting = BikeyardMounting.UNKNOWN,
    val metricsError: String? = null,
    val metricsRetryAtMs: Long = 0,
    val metricsRevision: Int? = null,
    val result: BikeyardRideResult? = null,
    val resultCheckedAtMs: Long = 0,
    /** The last read was refused: the ride is private without read access, or gone. */
    val resultUnavailable: Boolean = false,
)

data class BikeyardUiState(
    val loading: Boolean = true,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    /** A connected rider is widening permissions in the browser. */
    val upgrading: Boolean = false,
    val riderName: String? = null,
    val profile: BikeyardProfile? = null,
    val rideAccess: BikeyardRideAccess = BikeyardRideAccess.NONE,
    val accountKey: String? = null,
    val automatic: Boolean = false,
    val automaticMetrics: Boolean = false,
    val metricsMounting: BikeyardMounting = BikeyardMounting.UNKNOWN,
    val visibility: BikeyardVisibility = BikeyardVisibility.PRIVATE,
    val uploads: List<BikeyardUpload> = emptyList(),
    val message: String? = null,
) {
    fun uploadFor(recordingId: String) = uploads.firstOrNull {
        it.recordingId == recordingId && it.accountKey == accountKey
    }
}

@Serializable
internal data class BikeyardTokens(
    val access: String,
    val refresh: String,
    val expiresAtMs: Long,
    val riderId: String,
    val riderName: String = "BIKEYARD rider",
    val refreshInFlight: Boolean = false,
    /** Granted scopes as BIKEYARD reported them. Blank for tokens stored before this was kept. */
    val scope: String = "",
) {
    val rideAccess: BikeyardRideAccess get() {
        val granted = scope.split(' ')
        return when {
            "rides:read_all" in granted -> BikeyardRideAccess.ALL
            "rides:read" in granted -> BikeyardRideAccess.PUBLIC
            else -> BikeyardRideAccess.NONE
        }
    }
}

@Serializable
internal data class BikeyardPending(
    val state: String,
    val verifier: String,
    val createdAtMs: Long,
    /** Re-authorizing the connected rider to widen scopes; must return the same rider. */
    val upgrade: Boolean = false,
)

@Serializable
internal data class BikeyardStoredState(
    val environment: BikeyardEnvironment = BikeyardEnvironment.LIVE,
    val tokens: BikeyardTokens? = null,
    val pending: BikeyardPending? = null,
    val autoConsentId: String? = null,
    val autoMetrics: Boolean = false,
    val metricsMounting: BikeyardMounting = BikeyardMounting.UNKNOWN,
    val visibility: BikeyardVisibility = BikeyardVisibility.PRIVATE,
    val uploads: List<BikeyardUpload> = emptyList(),
    val message: String? = null,
    val profile: BikeyardProfile? = null,
) {
    val accountKey: String? get() = tokens?.let { "${environment.name}:${it.riderId}" }
    fun ui() = BikeyardUiState(
        loading = false, connected = tokens != null,
        connecting = pending != null, upgrading = pending?.upgrade == true && tokens != null,
        riderName = tokens?.riderName, profile = profile.takeIf { tokens != null },
        rideAccess = tokens?.rideAccess ?: BikeyardRideAccess.NONE,
        accountKey = accountKey, automatic = autoConsentId != null,
        automaticMetrics = autoConsentId != null && autoMetrics,
        metricsMounting = metricsMounting,
        visibility = visibility, uploads = uploads, message = message,
    )
}

internal object BikeyardPkce {
    const val REDIRECT_URI = "https://nakvali.whekin.dev/oauth/bikeyard/callback"
    /** What a connection cannot work without. Tokens missing either are refused. */
    const val SCOPES = "profile:read rides:write"
    /**
     * What a connection asks for. Reading rides back (including private ones,
     * the upload default) is optional: an older or narrower grant still
     * uploads, and BIKEYARD widens an existing grant on re-authorization.
     */
    const val REQUESTED_SCOPES = "profile:read rides:write rides:read_all"
    fun random(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

internal class BikeyardFailure(
    val kind: Kind,
    message: String,
    val retryAtMs: Long = 0,
    val httpCode: Int? = null,
) : Exception(message) {
    enum class Kind { RETRY, AUTH, PERMANENT }
}
