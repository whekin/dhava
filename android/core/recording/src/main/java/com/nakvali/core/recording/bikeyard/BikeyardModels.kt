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
data class BikeyardAutoRequest(val accountKey: String, val consentId: String, val visibility: BikeyardVisibility)

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
    val uploadId: String? = null,
    val rideId: String? = null,
    val retryAtMs: Long = 0,
    val error: String? = null,
)

data class BikeyardUiState(
    val loading: Boolean = true,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val riderName: String? = null,
    val accountKey: String? = null,
    val automatic: Boolean = false,
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
)

@Serializable
internal data class BikeyardPending(val state: String, val verifier: String, val createdAtMs: Long)

@Serializable
internal data class BikeyardStoredState(
    val environment: BikeyardEnvironment = BikeyardEnvironment.LIVE,
    val tokens: BikeyardTokens? = null,
    val pending: BikeyardPending? = null,
    val autoConsentId: String? = null,
    val visibility: BikeyardVisibility = BikeyardVisibility.PRIVATE,
    val uploads: List<BikeyardUpload> = emptyList(),
    val message: String? = null,
) {
    val accountKey: String? get() = tokens?.let { "${environment.name}:${it.riderId}" }
    fun ui() = BikeyardUiState(
        loading = false, connected = tokens != null,
        connecting = pending != null, riderName = tokens?.riderName,
        accountKey = accountKey, automatic = autoConsentId != null,
        visibility = visibility, uploads = uploads, message = message,
    )
}

internal object BikeyardPkce {
    const val REDIRECT_URI = "https://nakvali.whekin.dev/oauth/bikeyard/callback"
    const val SCOPES = "profile:read rides:write"
    fun random(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

internal class BikeyardFailure(
    val kind: Kind,
    message: String,
    val retryAtMs: Long = 0,
) : Exception(message) {
    enum class Kind { RETRY, AUTH, PERMANENT }
}
