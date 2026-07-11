package com.dhava.core.recording

import android.content.Context
import java.io.File
import java.io.IOException
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
import kotlinx.serialization.json.Json

/**
 * Single source of truth for recording state and the on-device recording list.
 *
 * Manually wired process-wide singleton (no DI framework yet): the service
 * pushes live state in, the Record UI observes the flows. The recording list
 * is persisted to a flat `recordings.json` index next to the raw files —
 * migrate to Room when the index outgrows a single JSON list.
 */
class RecordingRepository private constructor(private val appContext: Context) {

    companion object {
        private const val INDEX_FILE = "recordings.json"
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
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val uploader = ActivityUploader()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _recordings = MutableStateFlow<List<LocalRecording>>(emptyList())
    val recordings: StateFlow<List<LocalRecording>> = _recordings.asStateFlow()

    private val _uploads = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    val uploads: StateFlow<Map<String, UploadState>> = _uploads.asStateFlow()

    init {
        scope.launch { indexMutex.withLock { loadIndex() } }
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
            uploaded = false,
        )
        scope.launch {
            indexMutex.withLock {
                _recordings.update { listOf(entry) + it.filterNot { r -> r.id == entry.id } }
                saveIndex()
            }
        }
        _state.value = RecordingState.Finished(summary)
    }

    /** Kicks off a manual upload of one recording. Progress lands in [uploads]. */
    fun upload(id: String) {
        val recording = _recordings.value.firstOrNull { it.id == id } ?: return
        if (_uploads.value[id] is UploadState.Uploading) return
        _uploads.update { it + (id to UploadState.Uploading) }
        scope.launch {
            try {
                uploader.upload(recording, recordingFile(id))
                indexMutex.withLock {
                    _recordings.update { list ->
                        list.map { if (it.id == id) it.copy(uploaded = true) else it }
                    }
                    saveIndex()
                }
                _uploads.update { it - id }
            } catch (e: IOException) {
                _uploads.update { it + (id to UploadState.Failed(e.message ?: "upload failed")) }
            }
        }
    }

    /** Resets a Finished state back to Idle (UI acknowledged the summary). */
    fun acknowledgeFinished() {
        _state.update { if (it is RecordingState.Finished) RecordingState.Idle else it }
    }

    // --- index persistence -------------------------------------------------

    private fun indexFile(): File = File(appContext.filesDir, INDEX_FILE)

    private suspend fun loadIndex() = withContext(Dispatchers.IO) {
        val file = indexFile()
        if (!file.exists()) return@withContext
        runCatching {
            _recordings.value = json
                .decodeFromString<List<LocalRecording>>(file.readText())
                .sortedByDescending { it.startedAtMs }
        }
        // A corrupt index is not fatal; it gets rewritten on the next save.
    }

    private suspend fun saveIndex() = withContext(Dispatchers.IO) {
        indexFile().writeText(json.encodeToString(_recordings.value))
    }
}
