package com.nakvali.core.recording

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nakvali.core.fusion.FusionCore
import com.nakvali.fusion.LatLon
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
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
        private const val ARTIFACTS_DIR = "activity-artifacts"
        private const val SEGMENTS_DIR = "segments"
        private const val SEGMENT_RESULTS_DIR = "segment-results"
        private const val IMPORTED_TRACES_DIR = "imported-traces"
        private const val LOG_TAG = "RecordingRepository"
        private val SAFE_BACKUP_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")

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
    private val stravaApi = StravaApi(StravaCredentialStore(appContext))
    private val canonicalStore = CanonicalActivityStore(
        artifactsDir = File(appContext.filesDir, ARTIFACTS_DIR),
        currentAlgorithmVersion = { FusionCore.algorithmVersion },
        produce = { path -> FusionCore.finalize(path).toArtifactPayload() },
    )
    private val segmentStore = SegmentStore(
        segmentsDir = File(appContext.filesDir, SEGMENTS_DIR),
        resultsDir = File(appContext.filesDir, SEGMENT_RESULTS_DIR),
    )
    private val importedTraceStore = ImportedTraceStore(
        context = appContext,
        directory = File(appContext.filesDir, IMPORTED_TRACES_DIR),
    )
    private val segmentMatcher = SegmentMatcher(
        store = segmentStore,
        canonicalArtifact = { id -> canonicalActivity(id) },
        rawFile = { id -> recordingFile(id) },
        algorithmVersion = { FusionCore.algorithmVersion },
        matchVersion = { FusionCore.segmentMatchVersion },
    )
    private val segmentsMutex = Mutex()
    private val backupMutex = Mutex()

    /** Completed once the JSON files are read; [awaitRecording] gates on it. */
    private val loaded = CompletableDeferred<Unit>()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _recordings = MutableStateFlow<List<LocalRecording>>(emptyList())
    val recordings: StateFlow<List<LocalRecording>> = _recordings.asStateFlow()

    private val _uploads = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    val uploads: StateFlow<Map<String, UploadState>> = _uploads.asStateFlow()

    private val _stravaConnection =
        MutableStateFlow<StravaConnectionState>(StravaConnectionState.Loading)
    val stravaConnection: StateFlow<StravaConnectionState> = _stravaConnection.asStateFlow()

    private val _bikes = MutableStateFlow<List<Bike>>(emptyList())
    val bikes: StateFlow<List<Bike>> = _bikes.asStateFlow()

    private val _segments = MutableStateFlow<List<StoredSegment>>(emptyList())
    val segments: StateFlow<List<StoredSegment>> = _segments.asStateFlow()

    private val _lastUsedBikeId = MutableStateFlow<String?>(null)
    val lastUsedBikeId: StateFlow<String?> = _lastUsedBikeId.asStateFlow()

    /**
     * Id of the entry that was stuck in [RecordingStatus.RECORDING] when the
     * index was loaded, i.e. the ride a killed process left behind. A
     * START_STICKY-restarted [RecordingService] consumes it once via
     * [takeResumableRecording] to continue the ride.
     */
    private val resumableId = AtomicReference<String?>(null)

    init {
        scope.launch {
            indexMutex.withLock {
                loadIndex()
                loadBikes()
                // Must run before `loaded` completes so UploadWorker and the
                // UI never observe pre-recovery state.
                recoverInterruptedRecordings()
            }
            _segments.value = segmentStore.loadSegments()
            loaded.complete(Unit)
            // Saved-but-not-uploaded entries normally already have their
            // unique job persisted in WorkManager; re-enqueueing with KEEP is
            // a harmless no-op and covers a crash between save and enqueue.
            _recordings.value
                .filter { it.status == RecordingStatus.PENDING_UPLOAD }
                .forEach { UploadWorker.enqueue(appContext, it.id) }
            _recordings.value
                .filter {
                    it.stravaExportStatus == StravaExportStatus.QUEUED ||
                        it.stravaExportStatus == StravaExportStatus.PROCESSING
                }
                .forEach { StravaExportWorker.enqueue(appContext, it.id) }
            refreshStravaConnection()
        }
    }

    /** Directory holding the raw `.jsonl.gz` files. */
    fun recordingsDir(): File = File(appContext.filesDir, RECORDINGS_DIR)

    /** Directory holding derived `.canonical.json.gz` artifacts. */
    fun artifactsDir(): File = File(appContext.filesDir, ARTIFACTS_DIR)

    fun recordingFile(id: String): File = File(recordingsDir(), "$id.jsonl.gz")

    /** Append-only operational heartbeat beside (but outside) immutable raw data. */
    fun recordingHealthFile(id: String): File = File(recordingsDir(), "$id.health.jsonl")

    /**
     * Deletes every derived canonical artifact; raw recordings are never
     * touched. [canonicalActivity] transparently recomputes an artifact the
     * next time its activity is opened. Returns the number of files removed.
     */
    suspend fun clearProcessedArtifacts(): Int = canonicalStore.clearAll()

    // --- complete local backup ---------------------------------------------

    /**
     * Writes one self-contained, versioned archive through Android's document
     * provider. Only irreplaceable local inputs are included; derived tracks,
     * map caches, upload work state and credentials are deliberately omitted.
     */
    suspend fun exportBackup(uri: Uri): BackupSummary {
        loaded.await()
        return backupMutex.withLock {
            requireBackupIdle()
            indexMutex.withLock {
                segmentsMutex.withLock {
                    val recordingsJson = IndexJson.encodeToString(_recordings.value).encodeToByteArray()
                    val bikesJson = IndexJson.encodeToString(
                        BikesFile(_bikes.value, _lastUsedBikeId.value),
                    ).encodeToByteArray()
                    withContext(Dispatchers.IO) {
                        val output = appContext.contentResolver.openOutputStream(uri, "w")
                            ?: throw NakvaliBackupException("The selected backup file could not be opened")
                        output.use {
                            NakvaliBackupArchive(appContext.filesDir).write(
                                output = it,
                                recordingsJson = recordingsJson,
                                bikesJson = bikesJson,
                                createdAtMs = System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Reads only the bounded first manifest entry; no files are restored. */
    suspend fun inspectBackup(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw NakvaliBackupException("The selected backup file could not be opened")
        input.use { NakvaliBackupArchive(appContext.filesDir).inspect(it) }
    }

    /**
     * Verifies every checksum into a private staging directory before merging
     * the backup. Existing rides always win; an immutable raw-file collision
     * with different bytes aborts the restore instead of overwriting either
     * version. New recordings, bikes, segments and GPX seeds are added.
     */
    suspend fun restoreBackup(uri: Uri): RestoreSummary {
        loaded.await()
        return backupMutex.withLock {
            requireBackupIdle()
            indexMutex.withLock {
                segmentsMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val staging = File(appContext.cacheDir, "backup-restore/${UUID.randomUUID()}")
                        val staged = appContext.contentResolver.openInputStream(uri)?.use { input ->
                            NakvaliBackupArchive(appContext.filesDir).extract(input, staging)
                        } ?: throw NakvaliBackupException("The selected backup file could not be opened")
                        try {
                            applyBackup(staged)
                        } finally {
                            staging.deleteRecursively()
                        }
                    }
                }
            }
        }
    }

    private suspend fun applyBackup(staged: NakvaliBackupArchive.StagedBackup): RestoreSummary {
        val incomingRecordings = runCatching {
            IndexJson.decodeFromString<List<LocalRecording>>(staged.file(INDEX_FILE).readText())
        }.getOrElse { error ->
            throw NakvaliBackupException("Backup recording index is invalid", error)
        }
        val incomingBikes = runCatching {
            IndexJson.decodeFromString<BikesFile>(staged.file(BIKES_FILE).readText())
        }.getOrElse { error ->
            throw NakvaliBackupException("Backup bike index is invalid", error)
        }
        validateBackupIndex(incomingRecordings, incomingBikes)
        incomingRecordings.forEach { recording ->
            val raw = staged.file("$RECORDINGS_DIR/${recording.id}.jsonl.gz")
            if (!raw.isFile) throw NakvaliBackupException("Backup is missing raw data for ${recording.id}")
        }

        preflightRawConflicts(staged)
        installBackupDirectory(staged, RECORDINGS_DIR)
        installBackupDirectory(staged, SEGMENTS_DIR)
        installBackupDirectory(staged, IMPORTED_TRACES_DIR)

        val currentRecordingIds = _recordings.value.mapTo(mutableSetOf(), LocalRecording::id)
        _recordings.value = (
            _recordings.value + incomingRecordings.filter { currentRecordingIds.add(it.id) }
            ).sortedByDescending(LocalRecording::startedAtMs)

        val currentBikeIds = _bikes.value.mapTo(mutableSetOf(), Bike::id)
        _bikes.value = _bikes.value + incomingBikes.bikes.filter { currentBikeIds.add(it.id) }
        if (_lastUsedBikeId.value == null) _lastUsedBikeId.value = incomingBikes.lastUsedId
        saveIndex()
        saveBikes()
        _segments.value = segmentStore.loadSegments()

        return RestoreSummary(
            recordingCount = staged.preview.recordingCount,
            segmentCount = staged.preview.segmentCount,
            importedTraceCount = staged.preview.importedTraceCount,
        )
    }

    private fun validateBackupIndex(recordings: List<LocalRecording>, bikes: BikesFile) {
        if (recordings.map(LocalRecording::id).toSet().size != recordings.size) {
            throw NakvaliBackupException("Backup recording index contains duplicate IDs")
        }
        if (bikes.bikes.map(Bike::id).toSet().size != bikes.bikes.size) {
            throw NakvaliBackupException("Backup bike index contains duplicate IDs")
        }
        recordings.forEach { recording ->
            if (!SAFE_BACKUP_ID.matches(recording.id)) {
                throw NakvaliBackupException("Backup contains an invalid recording ID")
            }
            if (recording.status == RecordingStatus.RECORDING) {
                throw NakvaliBackupException("Backup contains an unfinished active recording")
            }
        }
        bikes.bikes.forEach { bike ->
            if (!SAFE_BACKUP_ID.matches(bike.id)) {
                throw NakvaliBackupException("Backup contains an invalid bike ID")
            }
        }
    }

    private fun preflightRawConflicts(staged: NakvaliBackupArchive.StagedBackup) {
        staged.directory.resolve(RECORDINGS_DIR)
            .listFiles { file -> file.isFile && file.name.endsWith(".jsonl.gz") }
            ?.forEach { source ->
                val relative = "$RECORDINGS_DIR/${source.name}"
                val target = appContext.filesDir.resolve(relative)
                if (target.isFile && sha256(target) != staged.sha256(relative)) {
                    throw NakvaliBackupException("A different raw recording already uses ${source.name}")
                }
            }
    }

    private fun installBackupDirectory(staged: NakvaliBackupArchive.StagedBackup, directoryName: String) {
        val sourceDirectory = staged.directory.resolve(directoryName)
        sourceDirectory.listFiles()?.filter(File::isFile)?.forEach { source ->
            val relative = "$directoryName/${source.name}"
            val target = appContext.filesDir.resolve(relative)
            if (target.isFile) {
                // Current authored metadata and diagnostic tails win on a
                // merge. A fresh install has no target and restores normally.
                return@forEach
            }
            copyAtomically(source, target)
        }
    }

    private fun copyAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.restore.tmp")
        source.inputStream().buffered().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireBackupIdle() {
        if (_state.value is RecordingState.Preparing ||
            _state.value is RecordingState.Recording ||
            _recordings.value.any { it.status == RecordingStatus.RECORDING }
        ) {
            throw NakvaliBackupException("Finish the active recording before using backup")
        }
    }

    /**
     * Loads a valid derived artifact or rebuilds it from immutable raw data.
     * A missing/corrupt/old-version cache is never fatal and never mutates raw.
     */
    suspend fun canonicalActivity(id: String): CanonicalActivityArtifact? {
        loaded.await()
        val rawFile = recordingFile(id)
        return runCatching { canonicalStore.loadOrCreate(id, rawFile) }
            .onFailure { error -> Log.w(LOG_TAG, "canonical finalization failed for $id", error) }
            .getOrNull()
    }

    // --- segments -----------------------------------------------------------

    /** Suspends until the on-disk index, bikes and segments have been read. */
    suspend fun awaitReady() {
        loaded.await()
    }

    suspend fun segment(id: String): StoredSegment? {
        loaded.await()
        return _segments.value.firstOrNull { it.id == id }
    }

    /** Copies and validates a GPX into durable local seed storage. */
    suspend fun importGpx(uri: Uri): ImportedTrace {
        loaded.await()
        return withContext(Dispatchers.IO) { importedTraceStore.import(uri) }
    }

    /** Loads an imported source without treating it as a recorded activity. */
    suspend fun importedTrace(id: String): ImportedTrace? {
        loaded.await()
        return withContext(Dispatchers.IO) { importedTraceStore.load(id) }
    }

    /**
     * Authors a draft segment from a selection on one ride's finalized track.
     *
     * Rust derives the gates, corridor, length and drop from the source ride's
     * own accuracy; Android only persists the result. The returned segment is
     * a draft: its geometry is one ride, so it is not authoritative and never
     * corrects GPS.
     *
     * @throws com.nakvali.fusion.SegmentException.InvalidSelection when the
     *   selection is inverted, too short, or crosses a pause/gap.
     */
    suspend fun createSegment(
        recordingId: String,
        name: String,
        startPosition: Double,
        endPosition: Double,
        startGateCenter: LatLon,
        finishGateCenter: LatLon,
    ): StoredSegment {
        loaded.await()
        val artifact = canonicalActivity(recordingId)
            ?: error("Canonical artifact unavailable for $recordingId")
        return segmentsMutex.withLock {
            val definition = withContext(Dispatchers.Default) {
                FusionCore.buildSegmentContinuousWithGates(
                    id = UUID.randomUUID().toString(),
                    name = name.trim().ifBlank { "Segment" },
                    sourceRecordingId = recordingId,
                    track = artifact.finalizedTrack.toCanonicalTrack(),
                    startPosition = startPosition,
                    endPosition = endPosition,
                    startGateCenter = startGateCenter,
                    finishGateCenter = finishGateCenter,
                ).definition
            }
            val segment = definition.toStored(createdAtMs = System.currentTimeMillis())
            segmentStore.saveSegment(segment)
            _segments.update { list -> (list + segment).sortedBy { it.createdAtMs } }
            segment
        }
    }

    /** Authors a draft segment from preserved GPX seed geometry. */
    suspend fun createSegmentFromImportedTrace(
        traceId: String,
        name: String,
        startPosition: Double,
        endPosition: Double,
        startGateCenter: LatLon,
        finishGateCenter: LatLon,
    ): StoredSegment {
        loaded.await()
        val trace = importedTrace(traceId) ?: error("Imported GPX unavailable: $traceId")
        return segmentsMutex.withLock {
            val definition = withContext(Dispatchers.Default) {
                FusionCore.buildSegmentContinuousWithGates(
                    id = UUID.randomUUID().toString(),
                    name = name.trim().ifBlank { "Segment" },
                    sourceRecordingId = "imported-gpx:$traceId",
                    track = trace.points.toCanonicalTrack(),
                    startPosition = startPosition,
                    endPosition = endPosition,
                    startGateCenter = startGateCenter,
                    finishGateCenter = finishGateCenter,
                ).definition
            }
            val segment = definition.toStored(
                createdAtMs = System.currentTimeMillis(),
                sourceKind = SegmentSourceKind.IMPORTED_GPX,
            )
            segmentStore.saveSegment(segment)
            _segments.update { list -> (list + segment).sortedBy { it.createdAtMs } }
            segment
        }
    }

    suspend fun renameSegment(id: String, name: String) {
        loaded.await()
        segmentsMutex.withLock {
            val segment = _segments.value.firstOrNull { it.id == id } ?: return@withLock
            val renamed = segment.copy(name = name.trim().ifBlank { segment.name })
            segmentStore.saveSegment(renamed)
            _segments.update { list -> list.map { if (it.id == id) renamed else it } }
        }
    }

    /** Removes an authored segment and every result derived from it. */
    suspend fun deleteSegment(id: String) {
        loaded.await()
        segmentsMutex.withLock {
            segmentStore.deleteSegment(id)
            _segments.update { list -> list.filterNot { it.id == id } }
        }
    }

    /**
     * Attempts of one segment across every saved ride, recomputing only what
     * changed since the last call. Returns null when the segment is gone.
     */
    suspend fun segmentResults(id: String): SegmentResults? {
        loaded.await()
        val segment = _segments.value.firstOrNull { it.id == id } ?: return null
        val candidates = _recordings.value.filter { it.status != RecordingStatus.RECORDING }
        return runCatching {
            withContext(Dispatchers.Default) { segmentMatcher.results(segment, candidates) }
        }
            .onFailure { error -> Log.w(LOG_TAG, "segment matching failed for $id", error) }
            .getOrNull()
    }

    /** Deletes every derived segment result; authored segments are kept. */
    suspend fun clearSegmentResults(): Int = segmentStore.clearResults()

    /** Observes a single index entry; emits null once the entry is gone (discarded). */
    fun recording(id: String): Flow<LocalRecording?> =
        recordings.map { list -> list.firstOrNull { it.id == id } }

    /** Pushed by [RecordingService]; throttled to ~4 updates/s on its side. */
    internal fun pushState(state: RecordingState) {
        _state.value = state
    }

    /**
     * Called by the service the moment a recording STARTS. Persisting the
     * entry (status `recording`) before any data is captured guarantees a
     * hard process kill can never make the ride invisible — the 2026-07
     * OnePlus "o-kill" incident happened because the entry used to be
     * created only at Stop. Idempotent: an existing entry with this id
     * (e.g. Stop already landed for a very short ride) is never downgraded.
     */
    internal fun addActiveRecording(id: String, startedAtMs: Long) {
        scope.launch {
            loaded.await()
            indexMutex.withLock {
                if (_recordings.value.none { it.id == id }) {
                    val entry = LocalRecording(
                        id = id,
                        startedAtMs = startedAtMs,
                        status = RecordingStatus.RECORDING,
                    )
                    _recordings.update { listOf(entry) + it }
                    saveIndex()
                }
            }
        }
    }

    /** Called by the service when a recording is finalized on disk. */
    internal fun addRecording(summary: RecordingSummary) {
        scope.launch {
            loaded.await()
            indexMutex.withLock {
                _recordings.update { list ->
                    val previous = list.firstOrNull { it.id == summary.id }
                    val entry = LocalRecording(
                        id = summary.id,
                        startedAtMs = summary.startedAtMs,
                        endedAtMs = summary.endedAtMs,
                        sizeBytes = summary.sizeBytes,
                        status = RecordingStatus.RECORDED,
                        // A resumed-after-crash ride keeps its recovered mark.
                        recovered = previous?.recovered ?: false,
                        // Reaching Stop means this interruption was resolved;
                        // preserve its history but do not offer Continue again.
                        continuationAllowed = if (previous?.recovered == true) false else null,
                    )
                    listOf(entry) + list.filterNot { it.id == summary.id }
                }
                saveIndex()
            }
            // The save workspace can appear immediately. Canonicalization is
            // an IO-bound rebuildable cache; if the process dies here, the
            // first Activity Detail/GPX access recreates it from raw.
            canonicalActivity(summary.id)
        }
        _state.value = RecordingState.Finished(summary)
    }

    // --- crash recovery ------------------------------------------------------

    /**
     * Recovers recordings a killed process left behind. Runs once, at index
     * load, while [indexMutex] is held and before [loaded] completes —
     * nothing can be recording in this process yet, so every `recording`
     * entry is necessarily from a dead process.
     *
     * Two damage shapes (both from the 2026-07 OnePlus "o-kill" incident):
     *  - index entries stuck in [RecordingStatus.RECORDING] (written at
     *    Start, the Stop transition never happened);
     *  - orphan `.jsonl.gz` files with no index entry at all (rides recorded
     *    before the entry-at-Start change existed).
     *
     * Each gets its truncated gzip repaired in place ([RecordingRecovery])
     * and an index entry in `recorded` with `recovered: true`, so it shows
     * up in the list with the normal "Finish saving" flow. The most recent
     * interrupted entry is also remembered in [resumableId] so a restarted
     * service can resume the ride instead.
     */
    private suspend fun recoverInterruptedRecordings() = withContext(Dispatchers.IO) {
        // Leftover temp files from a repair that died mid-way: the source
        // file they were about to replace is still intact, drop them.
        recordingsDir().listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }

        var changed = false
        var resumable: LocalRecording? = null

        // 1. Index entries stuck in `recording`.
        for (entry in _recordings.value.filter { it.status == RecordingStatus.RECORDING }) {
            changed = true
            val file = recordingFile(entry.id)
            runCatching {
                val healthLog = RecordingHealthLog(recordingHealthFile(entry.id))
                RecordingExitDiagnostics.latestAfter(appContext, entry.startedAtMs)?.let { exit ->
                    if (!healthLog.hasProcessExit(exit.exitTimestampMs ?: exit.timestampMs)) {
                        healthLog.append(exit)
                    }
                }
            }.onFailure { error ->
                // Diagnostics must never prevent raw repair or sticky resume.
                Log.w(LOG_TAG, "could not persist exit diagnostics for ${entry.id}", error)
            }
            val stats = RecordingRecovery.repairFile(file)
            if (stats == null) {
                // Keep unreadable bytes visible instead of silently removing
                // the index entry and leaving an orphan the UI cannot reach.
                val endedAtMs = file
                    .takeIf(File::isFile)
                    ?.lastModified()
                    ?.coerceAtLeast(entry.startedAtMs)
                    ?: entry.startedAtMs
                val rawOnly = entry.copy(
                    endedAtMs = endedAtMs,
                    sizeBytes = file.takeIf(File::isFile)?.length() ?: 0,
                    status = RecordingStatus.RECORDED,
                    recovered = true,
                    recoveryFailed = true,
                    continuationAllowed = false,
                )
                _recordings.update { list ->
                    list.map { if (it.id == entry.id) rawOnly else it }
                }
                continue
            }
            val repaired = entry.copy(
                endedAtMs = stats.endedAtMs ?: entry.startedAtMs,
                sizeBytes = file.length(),
                status = RecordingStatus.RECORDED,
                recovered = true,
                continuationAllowed = true,
            )
            _recordings.update { list -> list.map { if (it.id == entry.id) repaired else it } }
            if (resumable == null || repaired.startedAtMs > resumable.startedAtMs) {
                resumable = repaired
            }
        }

        // 2. Orphan raw files with no index entry.
        val known = _recordings.value.mapTo(mutableSetOf()) { it.id }
        recordingsDir()
            .listFiles { f -> f.name.endsWith(".jsonl.gz") }
            ?.filter { it.name.removeSuffix(".jsonl.gz") !in known }
            ?.forEach { file ->
                val stats = RecordingRecovery.repairFile(file)
                val startedAtMs = stats?.startedAtMs ?: file.lastModified()
                val entry = LocalRecording(
                    id = file.name.removeSuffix(".jsonl.gz"),
                    startedAtMs = startedAtMs,
                    endedAtMs = stats?.endedAtMs ?: startedAtMs,
                    sizeBytes = file.length(),
                    status = RecordingStatus.RECORDED,
                    recovered = true,
                    recoveryFailed = stats == null,
                    continuationAllowed = stats != null,
                )
                _recordings.update { listOf(entry) + it }
                changed = true
            }

        if (changed) {
            _recordings.update { list -> list.sortedByDescending { it.startedAtMs } }
            saveIndex()
        }
        resumableId.set(resumable?.id)
    }

    /** What a restarted service needs to continue an interrupted ride. */
    internal data class ResumeTarget(
        val id: String,
        val startedAtMs: Long,
        val endedAtMs: Long,
    )

    /**
     * Claims the interrupted recording for a resume (START_STICKY restart
     * after a system kill). Consumes [resumableId] at most once per process;
     * the entry — already repaired by [recoverInterruptedRecordings] — is
     * flipped back to `recording` so the service can append to its file.
     * Returns null when there is nothing (safely) resumable; the entry then
     * simply stays in the list as a recovered ride.
     */
    internal suspend fun takeResumableRecording(requestedId: String? = null): ResumeTarget? {
        loaded.await()
        val id = requestedId ?: resumableId.getAndSet(null) ?: return null
        if (requestedId != null) resumableId.compareAndSet(requestedId, null)
        return indexMutex.withLock {
            val entry = _recordings.value.firstOrNull { it.id == id }
            // Only resume a readable interrupted ride the user has not saved.
            if (entry == null || !entry.canContinueRecording()) {
                return@withLock null
            }
            _recordings.update { list ->
                list.map { if (it.id == id) it.copy(status = RecordingStatus.RECORDING) else it }
            }
            saveIndex()
            ResumeTarget(
                id = entry.id,
                startedAtMs = entry.startedAtMs,
                endedAtMs = entry.endedAtMs,
            )
        }
    }

    // --- save flow ----------------------------------------------------------

    /**
     * Attaches the save-sheet metadata to a recording, marks it pending and
     * queues the background upload. Fully offline-safe: the enqueued job just
     * waits for network.
     */
    fun saveActivity(id: String, title: String, description: String, bike: Bike?) {
        val offline = appContext
            .getSharedPreferences("recorder_settings", Context.MODE_PRIVATE)
            .getBoolean("offline_mode", true)
        scope.launch {
            updateEntry(id) {
                it.withMetadata(title, description, bike).copy(
                    savedAtMs = System.currentTimeMillis(),
                    status = if (offline) RecordingStatus.RECORDED else RecordingStatus.PENDING_UPLOAD,
                )
            }
            if (bike != null) {
                indexMutex.withLock {
                    _lastUsedBikeId.value = bike.id
                    saveBikes()
                }
            }
            if (!offline) UploadWorker.enqueue(appContext, id)
        }
    }

    /**
     * Edits the metadata of an already-saved activity in place. Local-only by
     * design: for an uploaded activity the server copy is deliberately NOT
     * re-synced here — upload/finish already happened, and metadata sync-back
     * is a future backend concern (needs an update endpoint in the contract).
     */
    suspend fun updateMetadata(id: String, title: String, description: String, bike: Bike?) {
        loaded.await()
        updateEntry(id) { it.withMetadata(title, description, bike) }
    }

    /**
     * Deletes an activity entirely: the pending upload job (if any), the
     * index entry, the raw `.jsonl.gz` and the derived canonical artifact.
     * Irreversible.
     *
     * This is the one deliberate exception to the "raw sensor data is kept
     * forever" principle: that principle governs automatic behavior (nothing
     * may ever drop raw data as a side effect), not an explicit, confirmed
     * user request to delete an activity.
     */
    suspend fun deleteActivity(id: String) {
        loaded.await()
        // Cancel first so a queued worker cannot pick the entry up while the
        // files underneath it are being removed. A no-op when nothing is
        // queued; a worker that already ran to completion is unaffected.
        UploadWorker.cancel(appContext, id)
        StravaExportWorker.cancel(appContext, id)
        indexMutex.withLock {
            _recordings.update { list -> list.filterNot { it.id == id } }
            saveIndex()
        }
        recordingFile(id).delete()
        recordingHealthFile(id).delete()
        // Serializes on the store mutex against an in-flight artifact
        // generation for this id, so a concurrent finalization cannot
        // resurrect the artifact after this delete (a generation that loses
        // the race fails its raw fingerprint recheck and persists nothing).
        canonicalStore.delete(id)
    }

    /** Deletes an unsaved recording (save-sheet Discard). Irreversible. */
    fun discard(id: String) {
        scope.launch { deleteActivity(id) }
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

    // --- Strava connection/export ------------------------------------------

    /** Returns the mobile OAuth URL; the caller opens it with ACTION_VIEW. */
    suspend fun beginStravaConnect(): String {
        _stravaConnection.value = StravaConnectionState.Connecting
        return runCatching { stravaApi.beginConnect() }
            .onFailure { error ->
                _stravaConnection.value = StravaConnectionState.Unavailable(
                    stravaConnectionError(error),
                )
            }
            .getOrThrow()
    }

    /** Rechecks server-owned OAuth state after app launch or deep-link return. */
    fun refreshStravaConnection() {
        scope.launch {
            _stravaConnection.value = runCatching { stravaApi.connection() }
                .getOrElse { error ->
                    StravaConnectionState.Unavailable(
                        stravaConnectionError(error),
                    )
                }
        }
    }

    fun onStravaOAuthRedirect(result: String?) {
        when (result) {
            "connected" -> refreshStravaConnection()
            "denied" -> _stravaConnection.value = StravaConnectionState.Disconnected
            else -> _stravaConnection.value = StravaConnectionState.Unavailable(
                "Could not connect Strava",
            )
        }
    }

    /** One tap is durable: persist queued before asking WorkManager to run. */
    fun exportToStrava(id: String) {
        scope.launch {
            updateEntry(id) {
                it.copy(
                    stravaExportStatus = StravaExportStatus.QUEUED,
                    stravaError = null,
                )
            }
            StravaExportWorker.enqueue(appContext, id)
        }
    }

    fun retryStravaExport(id: String) = exportToStrava(id)

    internal suspend fun performStravaExport(recording: LocalRecording): StravaExportStatus {
        val artifact = canonicalActivity(recording.id)
            ?: throw IllegalStateException("Processed track is unavailable")
        check(artifact.finalizedTrack.isNotEmpty()) { "Processed track is empty" }
        val output = File(
            appContext.cacheDir,
            "strava/nakvali-${recording.id.take(8)}-processed.gpx",
        )
        GpxExporter.write(
            points = artifact.finalizedTrack.map { point ->
                GpxTrackPoint(
                    timestampMs = point.timestampMs,
                    lat = point.lat,
                    lon = point.lon,
                    altitudeM = point.altitudeM,
                    sectionId = point.sectionId,
                )
            },
            name = recording.title ?: "Nakvali ride",
            output = output,
        )
        val response = try {
            stravaApi.export(recording, artifact.algorithmVersion, output)
        } finally {
            output.delete()
        }
        val status = when (response.status) {
            "uploaded" -> StravaExportStatus.UPLOADED
            "failed" -> StravaExportStatus.FAILED
            else -> StravaExportStatus.PROCESSING
        }
        updateEntry(recording.id) {
            it.copy(
                stravaExportStatus = status,
                stravaUploadId = response.stravaUploadId ?: it.stravaUploadId,
                stravaActivityId = response.stravaActivityId ?: it.stravaActivityId,
                stravaError = response.error,
            )
        }
        return status
    }

    internal suspend fun onStravaExportRetry(id: String, message: String) {
        updateEntry(id) {
            it.copy(
                stravaExportStatus = StravaExportStatus.PROCESSING,
                stravaError = message.takeIf(String::isNotBlank),
            )
        }
    }

    internal suspend fun onStravaExportFailed(id: String, message: String) {
        updateEntry(id) {
            it.copy(
                stravaExportStatus = StravaExportStatus.FAILED,
                stravaError = message.takeIf(String::isNotBlank) ?: "Strava export failed",
            )
        }
    }

    internal suspend fun onStravaConnectionLost(id: String, message: String) {
        _stravaConnection.value = StravaConnectionState.Disconnected
        onStravaExportFailed(id, message.ifBlank { "Connect Strava again" })
    }

    private fun stravaConnectionError(error: Throwable): String =
        if (error is StravaApiException) {
            error.message ?: "Strava connection is unavailable"
        } else {
            "Nakvali backend is unreachable"
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
