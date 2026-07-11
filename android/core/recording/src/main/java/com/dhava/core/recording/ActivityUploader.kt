package com.dhava.core.recording

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Uploads one finished recording per `proto/raw-recording-format.md`:
 * create activity → PUT raw gzip → finish.
 *
 * The phone already generated a local UUID for the file name / meta line, but
 * the server mints its own activity id on create. The server id is the
 * canonical one: raw bytes and finish are addressed to it, while the local id
 * stays a device-only key for the file and the index. No retry queue yet —
 * failed uploads are retried manually from the UI (WorkManager later).
 */
internal class ActivityUploader(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Raw files can be tens of MB; give the PUT room on slow uplinks.
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CreateActivityRequest(
        val sport: String,
        @SerialName("started_at_ms") val startedAtMs: Long,
    )

    @Serializable
    private data class CreateActivityResponse(val id: String)

    @Serializable
    private data class FinishActivityRequest(
        @SerialName("ended_at_ms") val endedAtMs: Long,
    )

    /** Runs the three-step upload; throws [IOException] on any failure. */
    suspend fun upload(recording: LocalRecording, file: File) {
        withContext(Dispatchers.IO) {
            val serverId = createActivity(recording.startedAtMs)
            putRaw(serverId, file)
            finish(serverId, recording.endedAtMs)
        }
    }

    private fun createActivity(startedAtMs: Long): String {
        val body = json
            .encodeToString(CreateActivityRequest(sport = "downhill", startedAtMs = startedAtMs))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/v1/activities")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("create activity failed: HTTP ${response.code}")
            }
            val payload = response.body?.string()
                ?: throw IOException("create activity: empty response body")
            return json.decodeFromString<CreateActivityResponse>(payload).id
        }
    }

    private fun putRaw(serverId: String, file: File) {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/activities/$serverId/raw")
            .put(file.asRequestBody("application/gzip".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("raw upload failed: HTTP ${response.code}")
            }
        }
    }

    private fun finish(serverId: String, endedAtMs: Long) {
        val body = json
            .encodeToString(FinishActivityRequest(endedAtMs = endedAtMs))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/v1/activities/$serverId/finish")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("finish failed: HTTP ${response.code}")
            }
        }
    }
}
