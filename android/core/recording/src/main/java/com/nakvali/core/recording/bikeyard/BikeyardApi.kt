package com.nakvali.core.recording.bikeyard

import java.io.File
import com.nakvali.core.recording.TrackFileExport
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

@Serializable
internal data class BikeyardTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("token_type") val tokenType: String,
    @SerialName("rider_id") val riderId: String,
    val scope: String,
) {
    fun tokens(now: Long): BikeyardTokens {
        if (!scope.split(' ').containsAll(BikeyardPkce.SCOPES.split(' ')) ||
            tokenType != "Bearer" || accessToken.isBlank() || refreshToken.isBlank() ||
            riderId.isBlank() || expiresIn !in 1..604800
        ) throw BikeyardFailure(BikeyardFailure.Kind.AUTH, "Allow profile access and ride uploads, then connect again")
        return BikeyardTokens(accessToken, refreshToken, now + expiresIn * 1000, riderId)
    }
}

@Serializable
internal data class BikeyardRider(
    val id: String, val username: String,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
) {
    val name: String get() = "$firstName $lastName".trim().ifBlank { username }
}

@Serializable
internal data class BikeyardReceipt(
    val id: String,
    val status: String,
    @SerialName("ride_id") val rideId: String? = null,
    @SerialName("duplicate_of") val duplicateOf: String? = null,
)

@Serializable
private data class BikeyardDuplicate(val upload: BikeyardReceipt)

internal interface BikeyardRemote {
    suspend fun exchange(environment: BikeyardEnvironment, code: String, verifier: String): BikeyardTokenResponse
    suspend fun refresh(environment: BikeyardEnvironment, token: String): BikeyardTokenResponse
    suspend fun rider(environment: BikeyardEnvironment, token: String): BikeyardRider
    suspend fun revoke(environment: BikeyardEnvironment, token: String)
    suspend fun upload(environment: BikeyardEnvironment, token: String, job: BikeyardUpload, file: File): BikeyardReceipt
    suspend fun receipt(environment: BikeyardEnvironment, token: String, id: String): BikeyardReceipt
}

internal class BikeyardApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        // A refresh is single-use. No transparent retry or credential redirect.
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
) : BikeyardRemote {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun exchange(environment: BikeyardEnvironment, code: String, verifier: String) =
        token(environment, FormBody.Builder().add("grant_type", "authorization_code")
            .add("code", code).add("code_verifier", verifier).add("redirect_uri", BikeyardPkce.REDIRECT_URI))

    override suspend fun refresh(environment: BikeyardEnvironment, token: String) =
        token(environment, FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", token))

    private suspend fun token(environment: BikeyardEnvironment, form: FormBody.Builder): BikeyardTokenResponse =
        request(Request.Builder().url("${environment.apiOrigin}/oauth/token")
            .post(form.add("client_id", environment.clientId).build()).build()) { json.decodeFromString(it) }

    override suspend fun rider(environment: BikeyardEnvironment, token: String): BikeyardRider =
        request(authorized(environment, token, "/v1/me").build()) { json.decodeFromString(it) }

    override suspend fun revoke(environment: BikeyardEnvironment, token: String) {
        request(Request.Builder().url("${environment.apiOrigin}/oauth/revoke").post(
            FormBody.Builder().add("client_id", environment.clientId).add("token", token)
                .add("token_type_hint", "refresh_token").build(),
        ).build()) { Unit }
    }

    override suspend fun upload(environment: BikeyardEnvironment, token: String, job: BikeyardUpload, file: File): BikeyardReceipt {
        // Keep the canonical retry snapshot unchanged; compress its exact bytes.
        if (file.length() > 128_000_000) throw BikeyardFailure(BikeyardFailure.Kind.PERMANENT,
            "The processed track exceeds BIKEYARD’s 128 MB unpacked limit")
        val compressed = TrackFileExport.gzip(file)
        try {
            if (compressed.length() > 20_000_000) throw BikeyardFailure(BikeyardFailure.Kind.PERMANENT,
                "The compressed track exceeds BIKEYARD’s 20 MB upload limit")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "nakvali.tcx.gz", compressed.asRequestBody("application/gzip".toMediaType()))
                .addFormDataPart("external_id", job.externalId)
                .addFormDataPart("name", job.name).addFormDataPart("description", job.description)
                .addFormDataPart("bike_type", job.bikeType).addFormDataPart("visibility", job.visibility.wire)
                .build()
            return request(authorized(environment, token, "/v1/uploads")
                .header("Prefer", "respond-async").post(body).build(), duplicate = true) {
                json.decodeFromString(it)
            }
        } finally { compressed.delete() }
    }

    override suspend fun receipt(environment: BikeyardEnvironment, token: String, id: String): BikeyardReceipt =
        request(authorized(environment, token, "/v1/uploads/${java.net.URLEncoder.encode(id, "UTF-8")}").build()) {
            json.decodeFromString(it)
        }

    private fun authorized(environment: BikeyardEnvironment, token: String, path: String) =
        Request.Builder().url(environment.apiOrigin + path).header("Authorization", "Bearer $token")

    private suspend inline fun <reified T> request(request: Request, duplicate: Boolean = false, decode: (String) -> T): T {
        try {
            execute(request).use { response ->
                val body = response.body.string()
                if (duplicate && response.code == 409) return json.decodeFromString<BikeyardDuplicate>(body).upload as T
                if (!response.isSuccessful) {
                    val retryAt = retryAt(response.header("Retry-After"), System.currentTimeMillis())
                    throw when (response.code) {
                        401, 403 -> BikeyardFailure(BikeyardFailure.Kind.AUTH, "Connect BIKEYARD again and allow ride uploads")
                        408, 429 -> BikeyardFailure(BikeyardFailure.Kind.RETRY, "BIKEYARD is busy. Upload will retry", retryAt)
                        in 500..599 -> BikeyardFailure(BikeyardFailure.Kind.RETRY, "BIKEYARD is unavailable. Upload will retry", retryAt)
                        413 -> BikeyardFailure(BikeyardFailure.Kind.PERMANENT, "The processed file exceeds BIKEYARD’s 20 MB limit")
                        else -> BikeyardFailure(BikeyardFailure.Kind.PERMANENT, "BIKEYARD rejected the request (HTTP ${response.code})")
                    }
                }
                return decode(body)
            }
        } catch (error: IOException) {
            throw BikeyardFailure(BikeyardFailure.Kind.RETRY, "Connection interrupted. Upload will retry")
        } catch (error: kotlinx.serialization.SerializationException) {
            throw BikeyardFailure(BikeyardFailure.Kind.RETRY, "BIKEYARD returned an unreadable response")
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    companion object {
        fun authorizeUrl(environment: BikeyardEnvironment, pending: BikeyardPending): String =
            "https://yard.bike/oauth/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("response_type", "code").addQueryParameter("client_id", environment.clientId)
                .addQueryParameter("redirect_uri", BikeyardPkce.REDIRECT_URI).addQueryParameter("scope", BikeyardPkce.SCOPES)
                .addQueryParameter("state", pending.state).addQueryParameter("code_challenge", BikeyardPkce.challenge(pending.verifier))
                .addQueryParameter("code_challenge_method", "S256").build().toString()

        fun retryAt(value: String?, now: Long): Long {
            val seconds = value?.toLongOrNull()
            if (seconds != null) return now + seconds.coerceIn(0, (Long.MAX_VALUE - now) / 1000) * 1000
            return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                .getOrDefault(now).coerceAtLeast(now)
        }
    }
}
