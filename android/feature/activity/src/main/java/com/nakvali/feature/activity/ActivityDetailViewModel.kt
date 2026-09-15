package com.nakvali.feature.activity

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.util.Log
import com.nakvali.core.fusion.FusionCore
import com.nakvali.core.recording.Bike
import com.nakvali.core.recording.BikeType
import com.nakvali.core.recording.CanonicalActivityArtifact
import com.nakvali.core.recording.CanonicalQuality
import com.nakvali.core.recording.CanonicalRideTotals
import com.nakvali.core.recording.GpsTrackReader
import com.nakvali.core.recording.GpxExporter
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordLine
import com.nakvali.core.recording.RecordingRepository
import com.nakvali.core.recording.RideSegmentRun
import com.nakvali.core.recording.StravaConnectionState
import com.nakvali.core.recording.StoredRideBounds
import com.nakvali.core.recording.TcxExporter
import com.nakvali.core.recording.TrackExport
import com.nakvali.core.recording.TrackExportPoint
import com.nakvali.core.recording.exportPoints
import com.nakvali.core.recording.ridingRuns
import com.nakvali.core.recording.rawGpsPoints
import com.nakvali.core.recording.toCanonicalTrack
import com.nakvali.core.recording.toRecordingReplay
import com.nakvali.core.recording.toRideAnalysis
import com.nakvali.fusion.CanonicalTrackPoint
import com.nakvali.fusion.RideAnalysis
import com.nakvali.fusion.RideRun
import com.nakvali.fusion.RideProfile
import com.nakvali.fusion.RecordingReplay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** GPS polyline load progress for the detail map. */
sealed interface TrackState {
    /** Streaming the raw file on IO. */
    data object Loading : TrackState

    /** Recording is readable but has no usable GPS fixes. */
    data object Empty : TrackState

    /**
     * Raw file missing or unreadable — a user-visible terminal state.
     * [rawFileMissing] distinguishes "gone" (nothing to export) from
     * "unreadable" (raw diagnostics export is exactly what a bug report needs).
     */
    data class Failed(val message: String, val rawFileMissing: Boolean = false) : TrackState

    data class Loaded(
        val points: List<RecordLine.Gps>,
    ) : TrackState
}

sealed interface DiagnosticTrackState {
    data object Loading : DiagnosticTrackState
    data object Unavailable : DiagnosticTrackState
    data class Loaded(val replay: RecordingReplay) : DiagnosticTrackState
}

/** Rust-authored elevation story and the exact finalized track behind it. */
data class ActivityRideInsights(
    val profile: RideProfile,
    val track: List<CanonicalTrackPoint>,
)

/**
 * How much of the activity a file covers.
 *
 * A run is addressed by its start time, not its position: a transport edit or a
 * trim renumbers every run, and an ordinal would quietly export a different
 * descent than the one the rider tapped.
 */
sealed interface ActivityExportScope {
    data object WholeActivity : ActivityExportScope
    data class OneRun(val startedAtMs: Long) : ActivityExportScope
}

/** What the share menu can produce; the mime type drives the share intent. */
enum class ActivityExportKind(val mimeType: String, val extension: String) {
    RIDING_ONLY("application/gpx+xml", "gpx"),
    PROCESSED_5_HZ("application/gpx+xml", "gpx"),
    /**
     * The same tracks as a TCX activity. Worth offering separately because TCX
     * states its own distance and laps: a GPX reader charges the rider for the
     * straight line across every excluded shuttle, a TCX reader does not.
     */
    RIDING_ONLY_TCX("application/vnd.garmin.tcx+xml", "tcx"),
    PROCESSED_5_HZ_TCX("application/vnd.garmin.tcx+xml", "tcx"),
    RAW_GPS("application/gpx+xml", "gpx"),
    /** The raw sensor recording as-is, for diagnostics/bug reports. */
    RAW_RECORDING("application/gzip", "jsonl.gz"),
    /** Append-only process/memory/writer heartbeat; never part of fusion input. */
    HEALTH_LOG("application/x-ndjson", "jsonl"),
    ;

    val isProcessedTrack: Boolean
        get() = this == RIDING_ONLY || this == PROCESSED_5_HZ ||
            this == RIDING_ONLY_TCX || this == PROCESSED_5_HZ_TCX
    val excludesTransport: Boolean get() = this == RIDING_ONLY || this == RIDING_ONLY_TCX
    val isTcx: Boolean get() = this == RIDING_ONLY_TCX || this == PROCESSED_5_HZ_TCX

    companion object {
        fun processedTrack(tcx: Boolean, excludeTransport: Boolean): ActivityExportKind = when {
            tcx && excludeTransport -> RIDING_ONLY_TCX
            tcx -> PROCESSED_5_HZ_TCX
            excludeTransport -> RIDING_ONLY
            else -> PROCESSED_5_HZ
        }
    }
}

/**
 * Loads one recording's index entry plus its GPS polyline for the detail
 * screen. Manual wiring — no DI framework yet.
 */
class ActivityDetailViewModel(
    application: Application,
    private val recordingId: String,
) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)

    /** Index entry (title, bike, upload status); null once discarded. */
    val recording: StateFlow<LocalRecording?> = repository
        .recording(recordingId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _track = MutableStateFlow<TrackState>(TrackState.Loading)
    val track: StateFlow<TrackState> = _track.asStateFlow()

    /**
     * Canonical ride stats from the Rust fusion-core (UniFFI). Null while
     * computing or if analysis failed — tiles fall back to "—".
     */
    private val _loading = MutableStateFlow(ActivityLoadingState())
    internal val loading: StateFlow<ActivityLoadingState> = _loading.asStateFlow()
    private val _analysis = MutableStateFlow<RideAnalysis?>(null)
    val analysis: StateFlow<RideAnalysis?> = _analysis.asStateFlow()

    private val _diagnostics = MutableStateFlow<DiagnosticTrackState>(DiagnosticTrackState.Loading)
    val diagnostics: StateFlow<DiagnosticTrackState> = _diagnostics.asStateFlow()

    /**
     * Rust-derived signal quality from the canonical artifact. Null while the
     * artifact is computing or on the legacy fallback path — the quality row
     * stays hidden instead of flashing wrong data.
     */
    private val _quality = MutableStateFlow<CanonicalQuality?>(null)
    val quality: StateFlow<CanonicalQuality?> = _quality.asStateFlow()

    /**
     * Totals with transport excluded. Null on the legacy fallback path, where
     * the screen falls back to whole-recording numbers rather than showing
     * nothing.
     */
    private val _ride = MutableStateFlow<CanonicalRideTotals?>(null)
    val ride: StateFlow<CanonicalRideTotals?> = _ride.asStateFlow()

    /**
     * Pause-aware elevation/gradient profile for the detail sheet. Null means
     * that no canonical finalized track exists; the UI never invents a profile
     * from raw GPS points.
     */
    private val _rideInsights = MutableStateFlow<ActivityRideInsights?>(null)
    val rideInsights: StateFlow<ActivityRideInsights?> = _rideInsights.asStateFlow()

    /**
     * Segment runs this ride produced, ordered as they were ridden. Null while
     * matching is still going: the section renders nothing rather than an empty
     * one, so "no segments here" is never claimed before it is known.
     */
    private val _segmentRuns = MutableStateFlow<List<RideSegmentRun>?>(null)
    val segmentRuns: StateFlow<List<RideSegmentRun>?> = _segmentRuns.asStateFlow()

    @Volatile
    private var canonicalArtifact: CanonicalActivityArtifact? = null

    /** Bikes for the edit sheet's picker. */
    val bikes: StateFlow<List<Bike>> = repository.bikes

    val stravaConnection: StateFlow<StravaConnectionState> = repository.stravaConnection

    private val _healthLogAvailable = MutableStateFlow(
        repository.recordingHealthFile(recordingId).isFile,
    )
    val healthLogAvailable: StateFlow<Boolean> = _healthLogAvailable.asStateFlow()

    fun addBike(name: String, type: BikeType): Bike = repository.addBike(name, type)

    fun updateMetadata(title: String, description: String, bike: Bike?) {
        viewModelScope.launch {
            repository.updateMetadata(recordingId, title, description, bike)
        }
    }

    /**
     * Deletes the whole activity (index entry, raw file, artifact, pending
     * upload). The screen pops itself once [recording] emits null.
     */
    fun deleteActivity() {
        viewModelScope.launch { repository.deleteActivity(recordingId) }
    }

    fun beginStravaConnect(onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repository.beginStravaConnect() })
        }
    }

    fun exportToStrava() {
        repository.exportToStrava(recordingId)
    }

    fun retryStravaExport() {
        repository.retryStravaExport(recordingId)
    }

    /**
     * The activity's riding runs, enumerated in Rust. Published here rather than
     * derived in the export sheet so the picker, the file's laps and any future
     * map highlight all read one list.
     */
    private val _ridingRuns = MutableStateFlow<List<RideRun>>(emptyList())
    val ridingRuns: StateFlow<List<RideRun>> = _ridingRuns.asStateFlow()

    private val _exportState = MutableStateFlow(ActivityExportState())
    val exportState: StateFlow<ActivityExportState> = _exportState.asStateFlow()

    fun prepareExport(
        kind: ActivityExportKind,
        destination: ExportDestination,
        scope: ActivityExportScope = ActivityExportScope.WholeActivity,
    ) {
        if (_exportState.value.busy || _exportState.value.prepared != null) return
        _exportState.value = ActivityExportState(busy = true, message = "Preparing file…")
        export(kind, scope) { result ->
            _exportState.value = result.fold(
                onSuccess = { ActivityExportState(prepared = PreparedActivityExport(it, kind, destination)) },
                onFailure = { ActivityExportState(error = it.message ?: "Could not prepare the file. Try again.") },
            )
        }
    }

    fun exportFeedback(message: String? = null, error: String? = null) {
        _exportState.value = ActivityExportState(message = message, error = error)
    }

    fun saveExport(file: File, uri: Uri) {
        _exportState.value = ActivityExportState(busy = true, message = "Saving file…")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    copyExportFile(file) {
                        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                    }
                }
                exportFeedback(message = "Saved ${file.name}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ActivityExport", "Document save failed", error)
                exportFeedback(error = "Could not save the file. Choose another location and try again.")
            }
        }
    }

    private fun export(
        kind: ActivityExportKind,
        scope: ActivityExportScope,
        onResult: (Result<File>) -> Unit,
    ) {
        if (kind == ActivityExportKind.RAW_RECORDING) {
            exportRawRecording(onResult)
            return
        }
        if (kind == ActivityExportKind.HEALTH_LOG) {
            exportHealthLog(onResult)
            return
        }
        val title = recording.value?.title ?: "Nakvali ride"
        val replay = (_diagnostics.value as? DiagnosticTrackState.Loaded)?.replay
        val artifact = canonicalArtifact
        val run = (scope as? ActivityExportScope.OneRun)
            ?.let { chosen -> _ridingRuns.value.find { it.startedAtMs == chosen.startedAtMs } }
        if (scope is ActivityExportScope.OneRun && run == null) {
            onResult(Result.failure(IllegalStateException(
                "That run is no longer part of this activity. Pick it again.",
            )))
            return
        }
        val points = when {
            kind.isProcessedTrack -> artifact?.let {
                val whole = it.exportPoints(kind.excludesTransport, _ridingRuns.value)
                if (run == null) whole else TrackExport.singleRun(whole, run)
            }
            kind == ActivityExportKind.RAW_GPS -> {
                artifact?.rawTrack?.map { point ->
                    TrackExportPoint(point.timestampMs, point.lat, point.lon, point.altitudeM, point.sectionId)
                } ?: run {
                    val raw = (_track.value as? TrackState.Loaded)?.points
                    val sectionByTimestamp = replay?.rawTrack
                        ?.associate { point -> point.timestampMs to point.sectionId }
                        .orEmpty()
                    raw?.map { point ->
                        TrackExportPoint(
                            timestampMs = point.timestampMs,
                            lat = point.lat,
                            lon = point.lon,
                            altitudeM = point.altitudeM,
                            sectionId = sectionByTimestamp[point.timestampMs] ?: 0,
                        )
                    }
                }
            }
            else -> null // RAW_RECORDING and HEALTH_LOG are handled above
        }
        if (points.isNullOrEmpty() || (kind.excludesTransport && points.size < 2)) {
            onResult(Result.failure(IllegalStateException(
                if (kind.excludesTransport) "No riding track remains after excluding transport"
                else "The selected track is unavailable",
            )))
            return
        }
        val scopeSuffix = run?.let { "-run${it.index + 1u}" }.orEmpty()
        val suffix = when (kind) {
            ActivityExportKind.RIDING_ONLY, ActivityExportKind.RIDING_ONLY_TCX -> "riding-only"
            ActivityExportKind.PROCESSED_5_HZ, ActivityExportKind.PROCESSED_5_HZ_TCX -> "processed-5hz"
            ActivityExportKind.RAW_GPS -> "raw-gps"
            ActivityExportKind.RAW_RECORDING, ActivityExportKind.HEALTH_LOG -> error("unreachable")
        } + scopeSuffix
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val output = File(
                    getApplication<Application>().cacheDir,
                    "exports/nakvali-${recordingId.take(8)}-$suffix.${kind.extension}",
                )
                if (kind.isTcx) TcxExporter.write(points, title, output)
                else GpxExporter.write(points, title, output)
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /**
     * Shares the immutable raw sensor file as-is for diagnostics. The
     * FileProvider only exposes `cache/exports/`, so the file is copied there
     * first; the source under `files/recordings/` is never touched.
     */
    private fun exportRawRecording(onResult: (Result<File>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val source = repository.recordingFile(recordingId)
                check(source.isFile) { "The raw recording file is missing" }
                val output = File(
                    getApplication<Application>().cacheDir,
                    "exports/nakvali-${recordingId.take(8)}-raw.jsonl.gz",
                )
                output.parentFile?.mkdirs()
                source.copyTo(output, overwrite = true)
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** Shares the small operational sidecar without touching the raw sensor file. */
    private fun exportHealthLog(onResult: (Result<File>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val source = repository.recordingHealthFile(recordingId)
                check(source.isFile) { "No health log is available for this recording" }
                val output = File(
                    getApplication<Application>().cacheDir,
                    "exports/nakvali-${recordingId.take(8)}-health.jsonl",
                )
                output.parentFile?.mkdirs()
                source.copyTo(output, overwrite = true)
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private fun publishArtifact(artifact: CanonicalActivityArtifact) {
        canonicalArtifact = artifact
        _quality.value = artifact.quality
        _ride.value = artifact.ride
        _ridingRuns.value = runCatching { artifact.ridingRuns() }
            .onFailure { Log.w("ActivityDetail", "run enumeration failed for $recordingId", it) }
            .getOrDefault(emptyList())
        val points = artifact.rawGpsPoints()
        _track.value = if (points.isEmpty()) TrackState.Empty else TrackState.Loaded(points)
        _analysis.value = artifact.toRideAnalysis()
        val replay = artifact.toRecordingReplay()
        _diagnostics.value = if (replay.rawTrack.isEmpty() && replay.finalizedTrack.isEmpty()) {
            DiagnosticTrackState.Unavailable
        } else {
            DiagnosticTrackState.Loaded(replay)
        }
        _loading.update { it.copy(phase = ActivityLoadPhase.PROFILE, preview = emptyList(), readFraction = null) }
        val finalizedTrack = artifact.finalizedTrack.toCanonicalTrack()
        val insights = finalizedTrack
            .takeIf { it.size >= 2 }
            ?.let { track ->
                runCatching {
                    ActivityRideInsights(
                        profile = FusionCore.rideProfile(track),
                        track = track,
                    )
                }
                    .onFailure {
                        Log.w(
                            "ActivityDetail",
                            "ride profile failed for $recordingId",
                            it,
                        )
                    }
                    .getOrNull()
            }
        if (canonicalArtifact === artifact) {
            _rideInsights.value = insights
            _loading.update { it.copy(phase = ActivityLoadPhase.READY) }
        }
    }


    private val _transportEditor = MutableStateFlow<TransportEditorState?>(null)
    internal val transportEditor: StateFlow<TransportEditorState?> = _transportEditor.asStateFlow()

    internal fun openTransportEditor() {
        val artifact = canonicalArtifact ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val automatic = repository.automaticTransportEpisodes(recordingId)
                val intervals = recording.value?.transportEpisodes ?: automatic
                _transportEditor.value = TransportEditorState(
                    originMs = artifact.analysis.startedAtMs, endedAtMs = artifact.analysis.endedAtMs,
                    track = artifact.finalizedTrack.toCanonicalTrack(), automatic = automatic,
                    drafts = intervals.map { it.draft(artifact.analysis.startedAtMs) },
                    useAutomatic = recording.value?.transportEpisodes == null,
                )
            } catch (error: Exception) { Log.w("TransportEditor", "Could not load episodes", error) }
        }
    }

    internal fun editTransportDrafts(drafts: List<TransportDraft>) {
        _transportEditor.value = _transportEditor.value?.copy(drafts = drafts, useAutomatic = false, error = null)
    }

    internal fun resetTransportDrafts() {
        _transportEditor.value = _transportEditor.value?.let {
            it.copy(drafts = it.automatic.map { episode -> episode.draft(it.originMs) }, useAutomatic = true, error = null)
        }
    }

    internal fun dismissTransportEditor() {
        if (_transportEditor.value?.busy != true) _transportEditor.value = null
    }

    internal fun applyTransportDrafts() {
        val editor = _transportEditor.value ?: return
        if (editor.busy) return
        _transportEditor.value = editor.copy(busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val intervals = if (editor.useAutomatic) null else editor.drafts.map { it.interval(editor.originMs) }
                repository.saveTransportEpisodes(recordingId, intervals)
                val artifact = repository.canonicalActivity(recordingId) ?: error("Could not reload activity")
                _segmentRuns.value = null
                publishArtifact(artifact)
                _transportEditor.value = null
                refreshSegmentRuns()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = (error as? com.nakvali.fusion.TransportException.InvalidInterval)?.msg
                    ?: error.message ?: "Could not update transport"
                _transportEditor.value = editor.copy(error = message)
            }
        }
    }

    private val _trimEditor = MutableStateFlow<TrimEditorState?>(null)
    internal val trimEditor: StateFlow<TrimEditorState?> = _trimEditor.asStateFlow()

    internal fun openTrimEditor() {
        val artifact = canonicalArtifact ?: return
        val origin = artifact.analysis.startedAtMs
        val stored = recording.value?.rideBounds
        _trimEditor.value = TrimEditorState(
            originMs = origin, endedAtMs = artifact.analysis.endedAtMs,
            track = artifact.finalizedTrack.toCanonicalTrack(),
            draft = stored?.draft(origin)
                ?: StoredRideBounds(origin, artifact.analysis.endedAtMs).draft(origin),
            useWholeActivity = stored == null,
        )
    }

    internal fun editTrimDraft(draft: TrimDraft) {
        _trimEditor.value = _trimEditor.value?.copy(
            draft = draft, useWholeActivity = false, error = null,
        )
    }

    internal fun resetTrimDraft() {
        _trimEditor.value = _trimEditor.value?.let {
            it.copy(
                draft = StoredRideBounds(it.originMs, it.endedAtMs).draft(it.originMs),
                useWholeActivity = true, error = null,
            )
        }
    }

    internal fun dismissTrimEditor() {
        if (_trimEditor.value?.busy != true) _trimEditor.value = null
    }

    internal fun applyTrimDraft() {
        val editor = _trimEditor.value ?: return
        if (editor.busy) return
        _trimEditor.value = editor.copy(busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bounds =
                    if (editor.useWholeActivity) null else editor.draft.bounds(editor.originMs)
                repository.saveRideBounds(recordingId, bounds)
                val artifact = repository.canonicalActivity(recordingId)
                    ?: error("Could not reload activity")
                publishArtifact(artifact)
                _trimEditor.value = null
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = (error as? com.nakvali.fusion.RideBoundsException.Invalid)?.msg
                    ?: error.message ?: "Could not trim the activity"
                _trimEditor.value = editor.copy(busy = false, error = message)
            }
        }
    }

    private suspend fun refreshSegmentRuns() {
        val revision = repository.recordings.value.firstOrNull { it.id == recordingId }?.transportRevision ?: return
        val runs = runCatching { repository.rideSegments(recordingId) }
            .onFailure { Log.w("ActivityDetail", "segment runs failed for $recordingId", it) }
            .getOrDefault(emptyList())
        if (repository.recordings.value.firstOrNull { it.id == recordingId }?.transportRevision == revision) _segmentRuns.value = runs
    }

    init {
        // Observe work even if it was started by Finish before this screen.
        val progressJob = viewModelScope.launch(Dispatchers.IO) {
            repository.canonicalProgress(recordingId).collect { update ->
                _loading.update { if (update.finished) it.finishedPreview(update.progress) else it.progress(update.progress) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val rawFile = repository.recordingFile(recordingId)
            val path = rawFile.absolutePath
            val artifact = repository.canonicalActivity(recordingId) { preview ->
                _loading.update { it.cached(preview) }
            }
            progressJob.cancelAndJoin()
            if (artifact != null) {
                publishArtifact(artifact)
                // Segment rematching can scan other rides. Start only after the
                // activity itself is usable, so it cannot steal the cache lock.
                refreshSegmentRuns()
                return@launch
            }
            _loading.update { it.copy(phase = ActivityLoadPhase.FAILED, readFraction = null) }

            // Raw file gone while the index entry still exists (external
            // cleanup, restored backup, …): a terminal error state instead of
            // a misleading "no GPS" empty state.
            if (!rawFile.isFile) {
                _track.value = TrackState.Failed(
                    message = "The raw recording file is missing from this phone.",
                    rawFileMissing = true,
                )
                _diagnostics.value = DiagnosticTrackState.Unavailable
                return@launch
            }

            // Damage-tolerant fallback for an artifact that cannot be built.
            // It preserves the old read path and leaves the raw file untouched.
            val points = GpsTrackReader.read(rawFile)
            _analysis.value = runCatching { FusionCore.analyze(path) }
                .onFailure { Log.w("ActivityDetail", "analysis fallback failed for $recordingId", it) }
                .getOrNull()
            val replayResult = runCatching { FusionCore.replay(path) }
                .onFailure { Log.w("ActivityDetail", "replay fallback failed for $recordingId", it) }
            _diagnostics.value = replayResult.getOrNull()
                ?.takeIf { it.rawTrack.isNotEmpty() || it.fusedTrack.isNotEmpty() }
                ?.let { DiagnosticTrackState.Loaded(it) }
                ?: DiagnosticTrackState.Unavailable
            _track.value = when {
                points.isNotEmpty() -> TrackState.Loaded(points)
                // No GPS anywhere and even the Rust replay failed: the file
                // itself is unreadable, not merely GPS-free.
                replayResult.isFailure -> TrackState.Failed(
                    "This recording could not be read. The raw file is preserved on this phone.",
                )
                else -> TrackState.Empty
            }
        }
    }

    companion object {
        fun factory(recordingId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("APPLICATION_KEY missing from ViewModel CreationExtras")
                ActivityDetailViewModel(application as Application, recordingId)
            }
        }
    }
}
