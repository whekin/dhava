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
import com.nakvali.fusion.sensorMetricsEvidence
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/** Device-only integration. It never uses Nakvali's API or Firebase identity. */
class BikeyardRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(BikeyardUiState())
    val state = _state.asStateFlow()
    private val snapshots = File(context.noBackupFilesDir, "bikeyard/uploads")
    private val metricsSnapshots = File(context.noBackupFilesDir, "bikeyard/metrics")
    private val metricsPreparation = Mutex()
    private val engine = scope.async {
        BikeyardEngine(BikeyardEncryptedStore(context), BikeyardApi()).also { core ->
            if (core.retiredSandbox) {
                snapshots.deleteRecursively()
                metricsSnapshots.deleteRecursively()
            }
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
                core.state.value.uploads.filter { it.metricsStatus in BikeyardEngine.activeMetricsStatuses }
                    .forEach { BikeyardMetricsWorker.enqueue(context, it.key) }
                if (core.state.value.automaticMetrics) {
                    core.state.value.uploads.filter { it.metricsAutomatic &&
                        it.status == BikeyardUploadStatus.UPLOADED &&
                        it.metricsStatus == BikeyardMetricsStatus.NONE }
                        .forEach { syncMetrics(it.recordingId, it.metricsMounting, automatic = true) }
                }
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
        if (!automatic || visibility != BikeyardVisibility.PRIVATE) {
            state.value.uploads.filter { it.metricsAutomatic }.forEach { BikeyardMetricsWorker.cancel(context, it.key) }
        }
        return action {
            settings(automatic, visibility)
            if (automatic) RecorderSettings.preferences(context).edit()
                .putBoolean(RecorderSettings.OFFLINE_MODE, false).apply()
            state.value.uploads.filter { it.status == BikeyardUploadStatus.CANCELLED }.forEach {
                BikeyardUploadWorker.cancel(context, it.key)
            }
            state.value.uploads.filter { it.metricsStatus == BikeyardMetricsStatus.CANCELLED }.forEach {
                BikeyardMetricsWorker.cancel(context, it.key)
            }
        }
    }

    fun setMetricsSettings(enabled: Boolean, mounting: BikeyardMounting): Job {
        if (!enabled) state.value.uploads.filter { it.metricsAutomatic }
            .forEach { BikeyardMetricsWorker.cancel(context, it.key) }
        return action { metricsSettings(enabled, mounting) }
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
        BikeyardMetricsWorker.cancelAll(context)
        action {
            disconnect()
            snapshots.deleteRecursively()
            metricsSnapshots.deleteRecursively()
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
        jobs.forEach { BikeyardMetricsWorker.cancel(context, it.key) }
        core.cancelRecording(id)
        jobs.forEach { snapshot(it.key).delete(); metricsSnapshot(it.key).delete() }
    }

    /** Manual action or separately enabled automatic sensor-metrics consent. */
    fun syncMetrics(recordingId: String, mounting: BikeyardMounting, automatic: Boolean = false): Job = scope.launch {
        try {
        if (automatic && (automaticPaused ||
                RecorderSettings.preferences(context).getBoolean(RecorderSettings.OFFLINE_MODE, true))) return@launch
        val core = engine.await()
        val job = core.state.value.uploads.firstOrNull { it.recordingId == recordingId &&
            it.accountKey == core.state.value.accountKey && it.status == BikeyardUploadStatus.UPLOADED }
            ?: return@launch
        metricsPreparation.withLock {
            val latest = core.state.value.uploads.firstOrNull { it.key == job.key } ?: return@withLock
            if (latest.metricsStatus in BikeyardEngine.activeMetricsStatuses) {
                BikeyardMetricsWorker.enqueue(context, job.key)
                return@withLock
            }
            val file = metricsSnapshot(job.key)
            if (latest.metricsStatus == BikeyardMetricsStatus.FAILED && file.isFile) {
                if (core.queueMetrics(job.key, manual = !automatic)) BikeyardMetricsWorker.enqueue(context, job.key, replace = true)
                return@withLock
            }
            try {
                val repository = RecordingRepository.getInstance(context)
                val artifact = repository.canonicalActivity(recordingId)
                    ?: error("Processed ride is unavailable")
                val scopes = latest.sensorScopes.map { com.nakvali.fusion.SensorTimeScope(it.startedAtMs, it.endedAtMs) }
                check(scopes.isNotEmpty()) {
                    "This ride predates frozen sensor scope. Upload a new ride to test metrics safely"
                }
                val evidence = sensorMetricsEvidence(repository.recordingFile(recordingId).absolutePath, scopes)
                val appVersion = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull()
                val document = buildBikeyardMetricsDocument(evidence, mounting,
                    artifact.analysis.algorithmVersion, appVersion, System.currentTimeMillis())
                val payload = bikeyardMetricsJson.encodeToString(document).toByteArray(Charsets.UTF_8)
                check(payload.size <= 20_000_000) { "Sensor metrics exceed BIKEYARD’s 20 MB limit" }
                file.parentFile!!.mkdirs()
                val temporary = File(file.parentFile, "${file.name}.tmp")
                try {
                    temporary.writeBytes(payload)
                    check(temporary.renameTo(file)) { "Could not save the sensor metrics snapshot" }
                } finally { temporary.delete() }
                if (core.queueMetrics(job.key, manual = !automatic)) BikeyardMetricsWorker.enqueue(context, job.key, replace = true)
                else file.delete()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                core.failMetrics(job.key, if (error is IllegalArgumentException || error is IllegalStateException)
                    error.message ?: "Unable to prepare sensor metrics" else "Unable to prepare sensor metrics")
            }
        }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            _state.value = _state.value.copy(message = "Unable to prepare BIKEYARD sensor metrics")
        }
    }

    internal suspend fun processMetrics(key: String, attempt: Int): Boolean = withContext(Dispatchers.IO) {
        val core = engine.await()
        val job = core.state.value.uploads.firstOrNull { it.key == key } ?: return@withContext true
        if (job.metricsStatus !in BikeyardEngine.activeMetricsStatuses) return@withContext true
        if (attempt >= 10) {
            core.failMetrics(key, "Sensor metrics paused after repeated attempts. Retry when ready")
            return@withContext true
        }
        val file = metricsSnapshot(key)
        if (!file.isFile) {
            core.failMetrics(key, "Sensor metrics snapshot is missing. Retry when ready")
            return@withContext true
        }
        val complete = core.processMetrics(key, file)
        if (core.state.value.uploads.firstOrNull { it.key == key }
                ?.metricsStatus == BikeyardMetricsStatus.UPLOADED) file.delete()
        complete
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
                val frozenScopes = points.sensorScopes().map {
                    BikeyardSensorScope(it.startedAtMs, it.endedAtMs)
                }
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
                if (!core.prepared(key, frozenScopes)) { file.delete(); return@withContext true }
            }
            // Do not regenerate a file whose first request may already have succeeded.
            check(job.uploadId != null || file.isFile) { "Upload snapshot is missing. Reconnect before preparing another upload" }
            val complete = core.process(key, file)
            core.state.value.uploads.firstOrNull { it.key == key }
                ?.takeIf { it.status == BikeyardUploadStatus.UPLOADED }
                ?.let { uploaded ->
                    file.delete()
                    if (complete && uploaded.metricsAutomatic &&
                        core.state.value.automaticMetrics &&
                        uploaded.metricsStatus == BikeyardMetricsStatus.NONE) {
                        syncMetrics(uploaded.recordingId, uploaded.metricsMounting, automatic = true)
                    }
                }
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

    private fun metricsSnapshot(key: String): File {
        require(key.matches(Regex("[A-Za-z0-9_-]{43}")))
        return File(metricsSnapshots, "$key.json")
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
