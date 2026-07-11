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
 * JSON codec for API bodies. `encodeDefaults = false` (the kotlinx default)
 * means optional metadata fields that stayed null are omitted from the wire,
 * per the finish contract ("fields optional; omit empty ones").
 */
internal val ApiJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class CreateActivityRequest(
    val sport: String,
    @SerialName("started_at_ms") val startedAtMs: Long,
)

@Serializable
internal data class CreateActivityResponse(val id: String)

/**
 * Finish body. Besides the end timestamp it carries the save-time metadata;
 * field names are a contract with the backend — do not rename.
 */
@Serializable
internal data class FinishActivityRequest(
    @SerialName("ended_at_ms") val endedAtMs: Long,
    val title: String? = null,
    val description: String? = null,
    /** Bike display name (server stores it denormalized for now). */
    val bike: String? = null,
    @SerialName("bike_type") val bikeType: BikeType? = null,
)

/**
 * Uploads one finished recording per `proto/raw-recording-format.md`:
 * create activity → PUT raw gzip → finish (with save metadata).
 *
 * The phone already generated a local UUID for the file name / meta line, but
 * the server mints its own activity id on create. The server id is the
 * canonical one: raw bytes and finish are addressed to it, while the local id
 * stays a device-only key for the file and the index.
 *
 * Retries are driven by [UploadWorker]. Idempotency: when the recording
 * already carries a server id from a previous attempt, create is skipped and
 * the id is reused; a freshly minted id is handed to [onServerIdAssigned]
 * (persisted into the index) before raw/finish run, so a crash between steps
 * never creates a duplicate activity.
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

    /** Runs the three-step upload; throws [IOException] on any failure. */
    suspend fun upload(
        recording: LocalRecording,
        file: File,
        onServerIdAssigned: suspend (String) -> Unit = {},
    ) {
        withContext(Dispatchers.IO) {
            val serverId = recording.serverId
                ?: createActivity(recording.startedAtMs).also { onServerIdAssigned(it) }
            putRaw(serverId, file)
            finish(serverId, recording)
        }
    }

    private fun createActivity(startedAtMs: Long): String {
        val body = ApiJson
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
            return ApiJson.decodeFromString<CreateActivityResponse>(payload).id
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

    private fun finish(serverId: String, recording: LocalRecording) {
        val body = ApiJson
            .encodeToString(
                FinishActivityRequest(
                    endedAtMs = recording.endedAtMs,
                    title = recording.title?.takeIf { it.isNotBlank() },
                    description = recording.description?.takeIf { it.isNotBlank() },
                    bike = recording.bikeName?.takeIf { it.isNotBlank() },
                    bikeType = recording.bikeType,
                ),
            )
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
