package com.dhava.core.recording

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface StravaConnectionState {
    data object Loading : StravaConnectionState
    data object Disconnected : StravaConnectionState
    data object Connecting : StravaConnectionState
    data class Connected(val athleteName: String) : StravaConnectionState
    data class Unavailable(val message: String) : StravaConnectionState
}

@Serializable
private data class ConnectResponse(
    @SerialName("authorize_url") val authorizeUrl: String,
)

@Serializable
private data class ConnectionResponse(
    val connected: Boolean,
    @SerialName("athlete_name") val athleteName: String = "",
)

@Serializable
internal data class StravaExportResponse(
    val status: String,
    @SerialName("strava_upload_id") val stravaUploadId: Long? = null,
    @SerialName("strava_activity_id") val stravaActivityId: Long? = null,
    val error: String? = null,
)

internal class StravaCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "strava_connection",
        Context.MODE_PRIVATE,
    )

    fun token(): String = preferences.getString(DEVICE_TOKEN, null)
        ?: generateToken().also { token ->
            preferences.edit().putString(DEVICE_TOKEN, token).apply()
        }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val DEVICE_TOKEN = "device_token"
    }
}

internal class StravaApi(
    private val credentialStore: StravaCredentialStore,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val accessKey: String = BuildConfig.API_ACCESS_KEY,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun beginConnect(): String = withContext(Dispatchers.IO) {
        val request = authenticatedRequest("$baseUrl/api/v1/strava/connect")
            .post(ByteArray(0).toRequestBody())
            .build()
        executeJson<ConnectResponse>(request).authorizeUrl
    }

    suspend fun connection(): StravaConnectionState = withContext(Dispatchers.IO) {
        val request = authenticatedRequest("$baseUrl/api/v1/strava/connection")
            .get()
            .build()
        val response = executeJson<ConnectionResponse>(request)
        if (response.connected) {
            StravaConnectionState.Connected(response.athleteName)
        } else {
            StravaConnectionState.Disconnected
        }
    }

    suspend fun export(
        recording: LocalRecording,
        algorithmVersion: String,
        gpx: File,
    ): StravaExportResponse = withContext(Dispatchers.IO) {
        val safeVersion = algorithmVersion.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val externalId = "dhava-${recording.id}-$safeVersion.gpx"
        val sportType = if (recording.bikeType == BikeType.EBIKE) {
            "EMountainBikeRide"
        } else {
            "MountainBikeRide"
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                gpx.name,
                gpx.asRequestBody("application/gpx+xml".toMediaType()),
            )
            .addFormDataPart("external_id", externalId)
            .addFormDataPart("title", recording.title ?: "Nakvali ride")
            .addFormDataPart("description", recording.description.orEmpty())
            .addFormDataPart("sport_type", sportType)
            .build()
        val request = authenticatedRequest("$baseUrl/api/v1/strava/exports")
            .post(body)
            .build()
        executeJson(request, acceptedCodes = setOf(200, 202, 422))
    }

    private fun authenticatedRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .withDhavaAccessKey(accessKey)
        .header("Authorization", "Bearer ${credentialStore.token()}")

    private inline fun <reified T> executeJson(
        request: Request,
        acceptedCodes: Set<Int> = setOf(200),
    ): T {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code !in acceptedCodes) {
                throw StravaApiException(
                    code = response.code,
                    retryable = response.code == 408 ||
                        response.code == 429 ||
                        response.code >= 500,
                    message = apiError(body) ?: "Strava export failed (HTTP ${response.code})",
                )
            }
            if (body.isBlank()) throw IOException("Strava broker returned an empty response")
            return ApiJson.decodeFromString(body)
        }
    }

    private fun apiError(body: String): String? = runCatching {
        when (val error = ApiJson.decodeFromString<ApiError>(body).error) {
            "strava_not_configured" -> "Strava export needs the Nakvali backend"
            "strava_not_connected" -> "Connect Strava again"
            "strava_write_scope_required" -> "Allow activity uploads in Strava"
            "strava_unavailable" -> "Strava is temporarily unavailable"
            else -> error.replace('_', ' ')
        }
    }.getOrNull()
}

@Serializable
private data class ApiError(val error: String)

internal class StravaApiException(
    val code: Int,
    val retryable: Boolean,
    message: String,
) : IOException(message)
