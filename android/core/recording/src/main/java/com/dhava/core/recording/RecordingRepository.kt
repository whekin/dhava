package com.dhava.core.recording

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Single source of truth for recording state, the on-device recording list,
 * and the local bike garage.
 *
 * Manually wired process-wide singleton (no DI framework yet): the service
 * pushes live state in, the Record UI observes the flows, [UploadWorker]
 * reads/updates entries. Lists are persisted to flat JSON files next to the
 * raw recordings (`recordings.json`, `bikes.json`) — migrate to Room when
 * they outgrow single JSON lists.
 *
 * Everything here works offline: saving only touches local files and
 * enqueues a network-constrained WorkManager job that waits for connectivity.
 */
class RecordingRepository private constructor(private val appContext: Context) {

    companion object {
        private const val INDEX_FILE = "recordings.json"
        private const val BIKES_FILE = "bikes.json"
        private const val RECORDINGS_DIR = "recordings"

        @Volatile
        private var instance: RecordingRepository? = null

        fun getInstance(context: Context): RecordingRepository =
            instance ?: synchronized(this) {
                instance ?: RecordingRepository(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexMutex = Mutex()
    private val uploader = ActivityUploader()

    /** Completed once the JSON files are read; [awaitRecording] gates on it. */
    private val loaded = CompletableDeferred<Unit>()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _recordings = MutableStateFlow<List<LocalRecording>>(emptyList())
    val recordings: StateFlow<List<LocalRecording>> = _recordings.asStateFlow()

    private val _uploads = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    val uploads: StateFlow<Map<String, UploadState>> = _uploads.asStateFlow()

    private val _bikes = MutableStateFlow<List<Bike>>(emptyList())
    val bikes: StateFlow<List<Bike>> = _bikes.asStateFlow()

    private val _lastUsedBikeId = MutableStateFlow<String?>(null)
    val lastUsedBikeId: StateFlow<String?> = _lastUsedBikeId.asStateFlow()

    init {
        scope.launch {
            indexMutex.withLock {
                loadIndex()
                loadBikes()
            }
            loaded.complete(Unit)
            // Saved-but-not-uploaded entries normally already have their
            // unique job persisted in WorkManager; re-enqueueing with KEEP is
            // a harmless no-op and covers a crash between save and enqueue.
            _recordings.value
                .filter { it.status == RecordingStatus.PENDING_UPLOAD }
                .forEach { UploadWorker.enqueue(appContext, it.id) }
        }
    }

    /** Directory holding the raw `.jsonl.gz` files. */
    fun recordingsDir(): File = File(appContext.filesDir, RECORDINGS_DIR)

    fun recordingFile(id: String): File = File(recordingsDir(), "$id.jsonl.gz")

    /** Pushed by [RecordingService]; throttled to ~4 updates/s on its side. */
    internal fun pushState(state: RecordingState) {
        _state.value = state
    }

    /** Called by the service when a recording is finalized on disk. */
    internal fun addRecording(summary: RecordingSummary) {
        val entry = LocalRecording(
            id = summary.id,
            startedAtMs = summary.startedAtMs,
            endedAtMs = summary.endedAtMs,
            sizeBytes = summary.sizeBytes,
            status = RecordingStatus.RECORDED,
        )
        scope.launch {
            indexMutex.withLock {
                _recordings.update { listOf(entry) + it.filterNot { r -> r.id == entry.id } }
                saveIndex()
            }
        }
        _state.value = RecordingState.Finished(summary)
    }

    // --- save flow ----------------------------------------------------------

    /**
     * Attaches the save-sheet metadata to a recording, marks it pending and
     * queues the background upload. Fully offline-safe: the enqueued job just
     * waits for network.
     */
    fun saveActivity(id: String, title: String, description: String, bike: Bike?) {
        scope.launch {
            updateEntry(id) {
                it.copy(
                    title = title.trim().ifBlank { null },
                    description = description.trim().ifBlank { null },
                    bikeId = bike?.id,
                    bikeName = bike?.name,
                    bikeType = bike?.type,
                    savedAtMs = System.currentTimeMillis(),
                    status = RecordingStatus.PENDING_UPLOAD,
                )
            }
            if (bike != null) {
                indexMutex.withLock {
                    _lastUsedBikeId.value = bike.id
                    saveBikes()
                }
            }
            UploadWorker.enqueue(appContext, id)
        }
    }

    /** Deletes the raw file and drops the index entry. Irreversible. */
    fun discard(id: String) {
        scope.launch {
            indexMutex.withLock {
                _recordings.update { list -> list.filterNot { it.id == id } }
                saveIndex()
            }
            recordingFile(id).delete()
        }
    }

    /** Re-enqueues an upload whose worker exhausted its retries. */
    fun retryUpload(id: String) {
        scope.launch {
            updateEntry(id) {
                if (it.status == RecordingStatus.FAILED) {
                    it.copy(status = RecordingStatus.PENDING_UPLOAD)
                } else {
                    it
                }
            }
            UploadWorker.enqueue(appContext, id)
        }
    }

    /** Adds a bike to the local garage and returns it. */
    fun addBike(name: String, type: BikeType): Bike {
        val bike = Bike(id = UUID.randomUUID().toString(), name = name.trim(), type = type)
        scope.launch {
            indexMutex.withLock {
                _bikes.update { it + bike }
                saveBikes()
            }
        }
        return bike
    }

    /** Resets a Finished state back to Idle (UI acknowledged the summary). */
    fun acknowledgeFinished() {
        _state.update { if (it is RecordingState.Finished) RecordingState.Idle else it }
    }

    // --- upload (driven by UploadWorker) -------------------------------------

    /** Waits for the index to load, then returns the entry (or null if gone). */
    internal suspend fun awaitRecording(id: String): LocalRecording? {
        loaded.await()
        return _recordings.value.firstOrNull { it.id == id }
    }

    /**
     * Runs the three-step upload for one entry, persisting the server id as
     * soon as create succeeds (idempotency across retries) and flipping the
     * entry to UPLOADED at the end. Throws IOException back to the worker.
     */
    internal suspend fun performUpload(recording: LocalRecording) {
        _uploads.update { it + (recording.id to UploadState.Uploading) }
        try {
            uploader.upload(recording, recordingFile(recording.id)) { serverId ->
                updateEntry(recording.id) { it.copy(serverId = serverId) }
            }
            updateEntry(recording.id) { it.copy(status = RecordingStatus.UPLOADED) }
        } finally {
            _uploads.update { it - recording.id }
        }
    }

    /** Attempt failed; WorkManager retries later with backoff. */
    internal fun onUploadRetrying(id: String, message: String) {
        _uploads.update { it + (id to UploadState.Retrying(message)) }
    }

    /** Retries exhausted; entry waits for a manual retry from the list. */
    internal suspend fun onUploadExhausted(id: String, message: String) {
        _uploads.update { it - id }
        updateEntry(id) { it.copy(status = RecordingStatus.FAILED) }
    }

    // --- index persistence -------------------------------------------------

    private suspend fun updateEntry(id: String, transform: (LocalRecording) -> LocalRecording) {
        indexMutex.withLock {
            _recordings.update { list -> list.map { if (it.id == id) transform(it) else it } }
            saveIndex()
        }
    }

    private fun indexFile(): File = File(appContext.filesDir, INDEX_FILE)

    private fun bikesFile(): File = File(appContext.filesDir, BIKES_FILE)

    private suspend fun loadIndex() = withContext(Dispatchers.IO) {
        val file = indexFile()
        if (!file.exists()) return@withContext
        runCatching {
            _recordings.value = IndexJson
                .decodeFromString<List<LocalRecording>>(file.readText())
                .sortedByDescending { it.startedAtMs }
        }
        // A corrupt index is not fatal; it gets rewritten on the next save.
    }

    private suspend fun saveIndex() = withContext(Dispatchers.IO) {
        indexFile().writeText(IndexJson.encodeToString(_recordings.value))
    }

    private suspend fun loadBikes() = withContext(Dispatchers.IO) {
        val file = bikesFile()
        if (!file.exists()) return@withContext
        runCatching {
            val stored = IndexJson.decodeFromString<BikesFile>(file.readText())
            _bikes.value = stored.bikes
            _lastUsedBikeId.value = stored.lastUsedId
        }
    }

    private suspend fun saveBikes() = withContext(Dispatchers.IO) {
        bikesFile().writeText(
            IndexJson.encodeToString(
                BikesFile(bikes = _bikes.value, lastUsedId = _lastUsedBikeId.value),
            ),
        )
    }
}
