package com.nakvali.core.recording.bikeyard

import android.content.Context
import com.nakvali.core.recording.BikeType
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordingRepository
import com.nakvali.core.recording.RecorderSettings
import com.nakvali.core.recording.RecordingStatus
import com.nakvali.core.recording.exportPoints
import com.nakvali.core.recording.ridingRuns
import com.nakvali.core.recording.TcxExporter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Device-only integration. It never uses Nakvali's API or Firebase identity. */
class BikeyardRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(BikeyardUiState())
    val state = _state.asStateFlow()
    private val snapshots = File(context.noBackupFilesDir, "bikeyard/uploads")
    private val engine = scope.async {
        BikeyardEngine(BikeyardEncryptedStore(context), BikeyardApi()).also { core ->
            if (core.retiredSandbox) snapshots.deleteRecursively()
            scope.launch { core.state.collect { _state.value = it } }
        }
    }
    @Volatile private var automaticPaused = false
    private var connectionJob: Job? = null

    init {
        scope.launch {
            try {
                val core = engine.await()
                if (core.state.value.automatic && RecorderSettings.preferences(context).getBoolean(RecorderSettings.OFFLINE_MODE, true)) {
                    core.settings(false, core.state.value.visibility)
                }
                core.state.value.uploads.filter { it.status in BikeyardEngine.activeStatuses }
                    .forEach { BikeyardUploadWorker.enqueue(context, it.key) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.value = BikeyardUiState(loading = false, message = "Could not open secure BIKEYARD storage") }
        }
    }

    suspend fun beginConnect(): String = withContext(Dispatchers.IO) { engine.await().beginConnect() }
    fun finishConnect(callback: String) {
        connectionJob = action { finishConnect(callback) }
    }
    fun cancelConnect() {
        connectionJob?.cancel()
        action { cancelConnect() }
    }
    fun setSettings(automatic: Boolean, visibility: BikeyardVisibility): Job {
        automaticPaused = !automatic
        if (!automatic) state.value.uploads.filter { it.automatic }.forEach { BikeyardUploadWorker.cancel(context, it.key) }
        return action {
            settings(automatic, visibility)
            if (automatic) RecorderSettings.preferences(context).edit()
                .putBoolean(RecorderSettings.OFFLINE_MODE, false).apply()
            state.value.uploads.filter { it.status == BikeyardUploadStatus.CANCELLED }.forEach {
                BikeyardUploadWorker.cancel(context, it.key)
            }
        }
    }

    suspend fun automaticRequest(): BikeyardAutoRequest? = withContext(Dispatchers.IO) {
        if (automaticPaused || RecorderSettings.preferences(context).getBoolean(RecorderSettings.OFFLINE_MODE, true)) return@withContext null
        try { engine.await().automaticRequest() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null } // A credential problem must never stop local saving.
    }

    fun disconnect() {
        automaticPaused = true
        BikeyardUploadWorker.cancelAll(context)
        action {
            disconnect()
            snapshots.deleteRecursively()
        }
    }

    fun upload(recording: LocalRecording, automatic: BikeyardAutoRequest? = null) = action {
        if (recording.savedAtMs == null || recording.status == RecordingStatus.RECORDING) return@action
        val job = enqueue(recording.id, recording.title ?: "Nakvali ride", recording.description.orEmpty(),
            if (recording.bikeType == BikeType.EBIKE) "emtb" else "mtb", automatic) ?: return@action
        if (job.status in BikeyardEngine.activeStatuses) BikeyardUploadWorker.enqueue(context, job.key, replace = automatic == null)
    }

    fun recover(recordings: List<LocalRecording>) {
        recordings.forEach { recording -> recording.bikeyardAutoRequest?.let { upload(recording, it) } }
    }

    suspend fun deleteRecording(id: String) = withContext(Dispatchers.IO) {
        val core = try { engine.await() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { return@withContext }
        val jobs = core.state.value.uploads.filter { it.recordingId == id }
        jobs.forEach { BikeyardUploadWorker.cancel(context, it.key) }
        core.cancelRecording(id)
        jobs.forEach { snapshot(it.key).delete() }
    }

    internal suspend fun process(key: String, attempt: Int): Boolean = withContext(Dispatchers.IO) {
        val core = engine.await()
        val job = core.state.value.uploads.firstOrNull { it.key == key } ?: return@withContext true
        if (job.status !in BikeyardEngine.activeStatuses) return@withContext true
        if (job.automatic && RecorderSettings.preferences(context).getBoolean(RecorderSettings.OFFLINE_MODE, true)) {
            core.settings(false, core.state.value.visibility)
            return@withContext true
        }
        if (attempt >= 10) {
            core.fail(key, "Upload paused after repeated attempts. Retry when ready")
            return@withContext true
        }
        val repository = RecordingRepository.getInstance(context)
        if (repository.awaitRecording(job.recordingId) == null) {
            core.cancelRecording(job.recordingId); snapshot(key).delete(); return@withContext true
        }
        val file = snapshot(key)
        try {
            if (!job.prepared) {
                val artifact = repository.canonicalActivity(job.recordingId)
                    ?: error("Processed track is unavailable")
                val points = artifact.exportPoints(excludeTransport = true, runs = artifact.ridingRuns())
                check(points.size >= 2) { "No riding track remains after excluding transport" }
                file.parentFile!!.mkdirs()
                val temporary = File(file.parentFile, "${file.name}.tmp")
                try {
                    TcxExporter.write(points, job.name, temporary)
                    check(temporary.length() <= 128_000_000) {
                        val sizeMb = String.format(java.util.Locale.US, "%.1f", temporary.length() / 1_000_000.0)
                        "TCX is $sizeMb MB; BIKEYARD accepts 128 MB before gzip compression. You can export individual runs as files"
                    }
                    check(temporary.renameTo(file)) { "Could not save the upload snapshot" }
                } finally { temporary.delete() }
                if (!core.prepared(key)) { file.delete(); return@withContext true }
            }
            // Do not regenerate a file whose first request may already have succeeded.
            check(job.uploadId != null || file.isFile) { "Upload snapshot is missing. Reconnect before preparing another upload" }
            val complete = core.process(key, file)
            if (core.state.value.uploads.firstOrNull { it.key == key }?.status == BikeyardUploadStatus.UPLOADED) file.delete()
            complete
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // Never propagate API bodies, token values or callback URLs to UI/logs.
            core.fail(key, if (error is IllegalStateException) error.message ?: "Unable to prepare this ride" else "Unable to prepare this ride. Retry when ready")
            true
        }
    }

    private fun snapshot(key: String): File {
        require(key.matches(Regex("[A-Za-z0-9_-]{43}")))
        return File(snapshots, "$key.tcx")
    }

    private fun action(block: suspend BikeyardEngine.() -> Unit): Job =
        scope.launch {
            try { engine.await().block() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.value = _state.value.copy(message = "BIKEYARD action could not be completed. Try again") }
        }

    companion object {
        @Volatile private var instance: BikeyardRepository? = null
        fun getInstance(context: Context): BikeyardRepository = instance ?: synchronized(this) {
            instance ?: BikeyardRepository(context.applicationContext).also { instance = it }
        }
    }
}
