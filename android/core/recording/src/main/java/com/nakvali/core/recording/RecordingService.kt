package com.nakvali.core.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.nakvali.fusion.LiveFusion
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground service that records GPS + IMU + barometer into a raw
 * `.jsonl.gz` file (see `proto/raw-recording-format.md`).
 *
 * Runs with `foregroundServiceType="location"` and a persistent notification,
 * plus a partial wake lock (OEM killers, see [wakeLock]); START_STICKY so the
 * system restarts it if the process is killed mid-ride, in which case the
 * interrupted recording is repaired and resumed ([resumeRecovered]).
 * Sensor callbacks are delivered on a dedicated [HandlerThread] and only
 * enqueue lines into [RecordingWriter] — no I/O or allocation-heavy work on
 * the callback path.
 */
class RecordingService : Service() {

    companion object {
        private const val ACTION_START = "com.nakvali.core.recording.action.START"
        private const val ACTION_STOP = "com.nakvali.core.recording.action.STOP"
        private const val ACTION_PAUSE = "com.nakvali.core.recording.action.PAUSE"
        private const val ACTION_RESUME = "com.nakvali.core.recording.action.RESUME"
        private const val ACTION_CONTINUE = "com.nakvali.core.recording.action.CONTINUE"
        const val ACTION_OPEN_RECORDING = "com.nakvali.core.recording.action.OPEN_RECORDING"
        private const val EXTRA_RECORDING_ID = "recording_id"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val CONTENT_REQUEST_CODE = 10
        private const val PAUSE_REQUEST_CODE = 11
        private const val RESUME_REQUEST_CODE = 12

        private const val GPS_INTERVAL_MS = 1_000L
        private const val GPS_MIN_INTERVAL_MS = 500L
        private const val LIVE_IMU_INTERVAL_MS = 20L
        private const val RAW_IMU_INTERVAL_US = 5_000
        private const val MAG_INTERVAL_US = 20_000
        private const val BARO_INTERVAL_US = 100_000
        private const val MAX_LIVE_TRACK_POINTS = 10_800 // ~3 h at 1 Hz
        private const val PREPARE_TIMEOUT_MS = 10_000L
        private const val HEALTH_HEARTBEAT_INTERVAL_MS = 60_000L
        private const val LOG_TAG = "RecordingService"

        /** How often live state is pushed to the repository (~4/s). */
        private const val STATE_PUSH_INTERVAL_MS = 250L

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun pause(context: Context) = context.startService(
            Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE),
        )

        fun resume(context: Context) = context.startService(
            Intent(context, RecordingService::class.java).setAction(ACTION_RESUME),
        )

        fun continueRecording(context: Context, recordingId: String) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_CONTINUE)
                .putExtra(EXTRA_RECORDING_ID, recordingId)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Never cancelled: file finalization must complete even when [onDestroy]
     * cancels [scope] (e.g. the system kills the task mid-recording).
     */
    private val finalizeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val healthWriteMutex = Mutex()
    private lateinit var repository: RecordingRepository
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager

    /** All sensor + location callbacks land on this thread. */
    private var sensorThread: HandlerThread? = null

    private var writer: RecordingWriter? = null
    private var activityId: String = ""
    private var startedAtMs = 0L
    private var startedElapsedMs = 0L

    /**
     * Held for the whole recording. A foreground service alone is not enough:
     * the 2026-07 OnePlus "o-kill" incident killed the app mid-ride with the
     * foreground service alive (ApplicationExitInfo importance=125,
     * reason=13 OTHER). A partial wake lock keeps the CPU and sensor
     * delivery running with the screen off and signals "actively working"
     * to aggressive OEM power managers. Released in every teardown path
     * ([stopRecording] is the single funnel, [onDestroy] routes through it).
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Anchor mapping the monotonic elapsed-realtime clock to Unix epoch,
     * computed once at recording start. Both SensorEvent.timestamp and
     * Location.elapsedRealtimeNanos are elapsed-realtime nanos, so a single
     * anchor keeps every sample type on the same epoch timeline, per the
     * format spec's "same monotonic-anchored clock" rule.
     */
    private var epochAnchorMs = 0L

    // Counters and last-fix values, written from the sensor thread and read
    // from the main-thread ticker — keep them volatile, not synchronized.
    @Volatile private var gpsCount = 0
    @Volatile private var imuCount = 0
    @Volatile private var baroCount = 0
    @Volatile private var lastSpeedMps: Float? = null
    @Volatile private var lastAccuracyM: Float? = null
    @Volatile private var stationary = false
    @Volatile private var liveTrack: List<LiveTrackPoint> = emptyList()
    @Volatile private var liveSectionId = 0
    private var liveFusion: LiveFusion? = null
    private var lastLiveImuMs = Long.MIN_VALUE
    private var lastRawImuNs = Long.MIN_VALUE
    private val imuPersistenceLock = Any()
    private val imuPersistenceBuffer = StationaryImuPersistenceBuffer()
    @Volatile private var lastGpsReceivedElapsedMs = Long.MIN_VALUE
    private var lastHealthHeartbeatElapsedMs = Long.MIN_VALUE

    // Latest gyro/mag samples, paired with accelerometer events (see below).
    @Volatile private var latestGyro: FloatArray? = null
    @Volatile private var latestMag: FloatArray? = null

    private var recording = false
    @Volatile private var preparing = false
    @Volatile private var recovering = false
    @Volatile private var paused = false
    private var prepareStartedElapsedMs = 0L
    private var warmImuCount = 0
    private var warmGpsReady = false
    private var pausedAtElapsedMs = 0L
    private var totalPausedMs = 0L
    @Volatile private var recoveredAtElapsedMs = Long.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        repository = RecordingRepository.getInstance(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_CONTINUE -> if (!recording && !preparing && !recovering) {
                recovering = true
                resumeRecovered(intent.getStringExtra(EXTRA_RECORDING_ID))
            }
            // Null intent: START_STICKY restart after the system killed the
            // process mid-ride (seen in the wild: OnePlus "o-kill"). The
            // repository has already repaired the interrupted file at index
            // load; pick the ride back up and keep appending to it.
            null -> if (!recording && !preparing && !recovering) {
                recovering = true
                resumeRecovered()
            }
        }
        // Recovery claims the repaired entry asynchronously. Include that
        // window in the sticky state: returning NOT_STICKY here caused a
        // second OxygenOS kill to end an otherwise successfully resumed ride.
        return if (recording || preparing || recovering) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- recording lifecycle ------------------------------------------------

    private fun startRecording() {
        if (recording || preparing || recovering) return
        if (!hasLocationPermission()) {
            // UI is responsible for requesting permissions before starting.
            stopSelf()
            return
        }
        recoveredAtElapsedMs = Long.MIN_VALUE
        preparing = true
        // Foreground ASAP: startForegroundService() gives a short grace
        // window, and an early promotion narrows the window in which an
        // OEM killer sees a plain background process.
        if (!goForeground()) {
            preparing = false
            repository.pushState(RecordingState.Idle)
            stopSelf()
            return
        }

        prepareStartedElapsedMs = SystemClock.elapsedRealtime()
        startCapture()
    }

    private fun beginRecording() {
        if (!preparing || recording) return
        preparing = false
        recording = true
        activityId = UUID.randomUUID().toString()
        startedAtMs = System.currentTimeMillis()
        startedElapsedMs = SystemClock.elapsedRealtime()
        epochAnchorMs = startedAtMs - startedElapsedMs
        repository.addActiveRecording(activityId, startedAtMs)
        writer = RecordingWriter(repository.recordingFile(activityId)).also {
            it.write(
                RecordLine.Meta(
                    activityId = activityId,
                    device = Build.MODEL,
                    os = "android-${Build.VERSION.RELEASE}",
                    appVersion = BuildConfig.APP_VERSION,
                    startedAtMs = startedAtMs,
                ),
            )
        }
        lastHealthHeartbeatElapsedMs = SystemClock.elapsedRealtime()
        enqueueHealth(RecordingHealthLog.KIND_START, sessionElapsedMs = 0)
    }

    /**
     * Continues an interrupted ride after a START_STICKY restart: repository
     * recovery already repaired the truncated file, so we flip its entry back
     * to `recording` ([RecordingRepository.takeResumableRecording]) and keep
     * appending. If nothing is resumable (no interrupted entry, file was
     * unreadable, or the user already touched it), the entry — if any — has
     * been finalized through the recovery path; just stop gracefully.
     */
    private fun resumeRecovered(requestedId: String? = null) {
        // Foreground first: a restarted foreground service must promote
        // itself promptly, and the resume decision below is asynchronous.
        if (!goForeground()) {
            recovering = false
            // Android 14+ rejects a background-created location FGS when the
            // user granted only while-in-use location. The repository has
            // already repaired the interrupted file; stop without a crash
            // loop and leave that recovered activity visible to the user.
            stopSelf()
            return
        }
        scope.launch {
            val target = if (hasLocationPermission()) {
                repository.takeResumableRecording(requestedId)
            } else {
                null
            }
            if (target == null || recording) {
                recovering = false
                if (!recording) {
                    ServiceCompat.stopForeground(
                        this@RecordingService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    stopSelf()
                }
                return@launch
            }
            recording = true
            recovering = false

            activityId = target.id
            startedAtMs = target.startedAtMs
            // Preserve already-recorded ride time without counting the
            // process-restart gap as active live time.
            startedElapsedMs =
                SystemClock.elapsedRealtime() -
                (target.endedAtMs - target.startedAtMs).coerceAtLeast(0L)
            // Fresh anchor for the new samples; see the field docs.
            epochAnchorMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()

            // Append a new gzip member to the repaired file (valid per
            // RFC 1952, see RecordingWriter). Deliberately no second meta
            // line — samples only; epoch timestamps make ordering across the
            // gap unambiguous.
            writer = RecordingWriter(repository.recordingFile(activityId), append = true)
            val resumedAtMs = System.currentTimeMillis()
            writer?.write(RecordLine.Event(target.endedAtMs, "pause"))
            writer?.write(RecordLine.Event(resumedAtMs, "resume"))

            recoveredAtElapsedMs = SystemClock.elapsedRealtime()
            startCapture()
            lastHealthHeartbeatElapsedMs = SystemClock.elapsedRealtime()
            enqueueHealth(
                kind = RecordingHealthLog.KIND_RESTART,
                sessionElapsedMs = (target.endedAtMs - target.startedAtMs).coerceAtLeast(0L),
                restartGapMs = (resumedAtMs - target.endedAtMs).coerceAtLeast(0L),
            )
        }
    }

    /** Sensor/GPS/ticker startup shared by fresh starts and resumes. */
    private fun startCapture() {
        gpsCount = 0
        imuCount = 0
        baroCount = 0
        lastSpeedMps = null
        lastAccuracyM = null
        stationary = false
        liveTrack = emptyList()
        liveSectionId = 0
        liveFusion = LiveFusion()
        lastLiveImuMs = Long.MIN_VALUE
        lastRawImuNs = Long.MIN_VALUE
        lastGpsReceivedElapsedMs = Long.MIN_VALUE
        lastHealthHeartbeatElapsedMs = SystemClock.elapsedRealtime()
        latestGyro = null
        latestMag = null
        warmImuCount = 0
        warmGpsReady = false
        paused = false
        totalPausedMs = 0L
        synchronized(imuPersistenceLock) {
            imuPersistenceBuffer.reset()
        }

        acquireWakeLock()
        sensorThread = HandlerThread("recording-sensors").also { it.start() }
        registerSensors()
        requestLocationUpdates()
        startTicker()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun goForeground(): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(elapsedMs = 0L),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (error: SecurityException) {
            Log.e(LOG_TAG, "Location foreground promotion denied", error)
            false
        } catch (error: IllegalStateException) {
            Log.e(LOG_TAG, "Foreground restart not allowed from background", error)
            false
        }
    }

    private fun stopRecording() {
        if (preparing && !recording) {
            preparing = false
            tearDownCapture()
            repository.pushState(RecordingState.Idle)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (!recording) {
            stopSelf()
            return
        }
        val stopElapsedMs = currentSessionElapsedMs()
        val finishedWriter = writer
        recording = false
        flushBufferedImu(finishedWriter)

        tearDownCapture()

        val endedAtMs = System.currentTimeMillis()
        writer = null
        val id = activityId

        finalizeScope.launch {
            finishedWriter?.close()
            appendHealth(
                healthInput(
                    kind = RecordingHealthLog.KIND_STOP,
                    sessionElapsedMs = stopElapsedMs,
                    writerHealth = finishedWriter?.healthStats(),
                ),
            )
            val summary = RecordingSummary(
                id = id,
                startedAtMs = startedAtMs,
                endedAtMs = endedAtMs,
                sizeBytes = repository.recordingFile(id).length(),
                gpsCount = gpsCount,
                imuCount = imuCount,
                baroCount = baroCount,
            )
            repository.addRecording(summary)
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun tearDownCapture() {
        sensorManager.unregisterListener(sensorListener)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorThread?.quitSafely()
        sensorThread = null
        liveFusion?.close()
        liveFusion = null
        releaseWakeLock()
    }

    private fun pauseRecording() {
        if (!recording || paused) return
        paused = true
        pausedAtElapsedMs = SystemClock.elapsedRealtime()
        val currentWriter = writer
        synchronized(imuPersistenceLock) {
            writeImuLines(currentWriter, imuPersistenceBuffer.flush())
            currentWriter?.write(RecordLine.Event(System.currentTimeMillis(), "pause"))
        }
        lastSpeedMps = 0f
        updateNotification(currentSessionElapsedMs())
    }

    private fun resumeRecording() {
        if (!recording || !paused) return
        writer?.write(RecordLine.Event(System.currentTimeMillis(), "resume"))
        liveSectionId++
        liveFusion?.startNewSection()
        // The first post-pause sample starts a fresh causal section. These
        // gates mirror Rust replay's resume handling and must not suppress it
        // merely because the pause was shorter than one sampling interval.
        lastLiveImuMs = Long.MIN_VALUE
        lastRawImuNs = Long.MIN_VALUE
        stationary = false
        totalPausedMs += SystemClock.elapsedRealtime() - pausedAtElapsedMs
        paused = false
        lastSpeedMps = null
        updateNotification(currentSessionElapsedMs())
    }

    override fun onDestroy() {
        if (recording || preparing) {
            // Torn down without an explicit stop (e.g. task removed): make a
            // best effort to release sensors and finish the file.
            stopRecording()
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // --- wake lock ------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nakvali:recording")
            .also {
                it.setReferenceCounted(false)
                // No timeout on purpose: a ride has no upper bound and the
                // lock is released in every teardown path (see field docs).
                it.acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- sensors --------------------------------------------------------------

    private fun registerSensors() {
        val handler = sensorThread?.let { HandlerCompat.createAsync(it.looper) } ?: return
        // 200 Hz retains 5 ms airtime/impact timing while avoiding the
        // sustained 500 Hz allocation and I/O load seen before OxygenOS
        // killed several long foreground recordings.
        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE,
        ).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(
                    sensorListener,
                    sensor,
                    when (type) {
                        Sensor.TYPE_ACCELEROMETER,
                        Sensor.TYPE_GYROSCOPE,
                        -> RAW_IMU_INTERVAL_US
                        Sensor.TYPE_MAGNETIC_FIELD -> MAG_INTERVAL_US
                        else -> BARO_INTERVAL_US
                    },
                    handler,
                )
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (preparing && event.sensor.type == Sensor.TYPE_ACCELEROMETER) warmImuCount++
            val writer = writer
            when (event.sensor.type) {
                // Pairing choice: the accelerometer is the master clock of the
                // `imu` line. Gyro and mag callbacks only stash their latest
                // sample; each accel event emits one line with those most
                // recent values. Accel and gyro both run at FASTEST (similar
                // rates), so "latest gyro" is the nearest-timestamp match to
                // within one sample period — good enough for fusion, which
                // re-interpolates anyway, and far simpler than a merge queue.
                Sensor.TYPE_ACCELEROMETER -> {
                    if (writer == null || paused || !recording) return
                    if (
                        lastRawImuNs != Long.MIN_VALUE &&
                        event.timestamp - lastRawImuNs < RAW_IMU_INTERVAL_US * 1_000L
                    ) {
                        return
                    }
                    lastRawImuNs = event.timestamp
                    val timestampMs = epochAnchorMs + event.timestamp / 1_000_000
                    // Counts acquisition, not persistence: health telemetry
                    // must still prove that the sensor callback stays near
                    // 200 Hz while stationary rows are compressed on disk.
                    imuCount++
                    // JNI at the raw sensor rate would waste battery. 50 Hz retains
                    // more than enough bandwidth for attitude/EKF and ZUPT.
                    if (
                        lastLiveImuMs == Long.MIN_VALUE ||
                        timestampMs - lastLiveImuMs >= LIVE_IMU_INTERVAL_MS
                    ) {
                        lastLiveImuMs = timestampMs
                        val wasStationary = stationary
                        stationary = liveFusion?.pushImu(
                            timestampMs,
                            event.values.map { it.toDouble() },
                            (latestGyro ?: floatArrayOf(0f, 0f, 0f)).map { it.toDouble() },
                        ) ?: false
                        when {
                            stationary -> lastSpeedMps = 0f
                            wasStationary -> lastSpeedMps = null
                        }
                    }
                    persistImu(
                        writer = writer,
                        sample = RecordLine.Imu(
                            timestampMs = timestampMs,
                            accel = listOf(event.values[0], event.values[1], event.values[2]),
                            // Devices without a gyroscope report a zero rate
                            // (the field is required by the format spec).
                            gyro = latestGyro?.toList() ?: listOf(0f, 0f, 0f),
                            mag = latestMag?.toList(),
                        ),
                        stationary = stationary,
                    )
                }
                Sensor.TYPE_GYROSCOPE -> latestGyro = event.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD -> latestMag = event.values.clone()
                Sensor.TYPE_PRESSURE -> {
                    if (writer == null || paused) return
                    baroCount++
                    writer.write(
                        RecordLine.Baro(
                            timestampMs = epochAnchorMs + event.timestamp / 1_000_000,
                            pressureHpa = event.values[0],
                        ),
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun persistImu(
        writer: RecordingWriter,
        sample: RecordLine.Imu,
        stationary: Boolean,
    ) {
        synchronized(imuPersistenceLock) {
            // Pause/stop may race a callback that already constructed its
            // sample. Recheck under the same lock used by their final flush so
            // nothing can be enqueued after the pause boundary.
            if (!recording || paused || writer !== this.writer) return
            writeImuLines(writer, imuPersistenceBuffer.accept(sample, stationary))
        }
    }

    private fun flushBufferedImu(writer: RecordingWriter?) {
        synchronized(imuPersistenceLock) {
            writeImuLines(writer, imuPersistenceBuffer.flush())
        }
    }

    private fun writeImuLines(
        writer: RecordingWriter?,
        samples: List<RecordLine.Imu>,
    ) {
        if (writer == null) return
        samples.forEach { sample ->
            writer.write(sample)
        }
    }

    // --- location ---------------------------------------------------------------

    private fun requestLocationUpdates() {
        val looper = sensorThread?.looper ?: return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
            .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, looper)
        } catch (_: SecurityException) {
            // Permission checked in startRecording(); revoked mid-flight —
            // keep recording IMU/baro only.
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                lastGpsReceivedElapsedMs = SystemClock.elapsedRealtime()
                if (preparing && location.hasAccuracy() && location.accuracy <= 15f) warmGpsReady = true
                lastAccuracyM = if (location.hasAccuracy()) location.accuracy else null
                val writer = writer
                if (writer == null || paused) continue
                gpsCount++
                val timestampMs = epochAnchorMs + location.elapsedRealtimeNanos / 1_000_000
                liveFusion?.pushGps(
                    timestampMs = timestampMs,
                    lat = location.latitude,
                    lon = location.longitude,
                    altitudeM = if (location.hasAltitude()) location.altitude else null,
                    accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                    bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                )?.let { snapshot ->
                    lastSpeedMps = snapshot.speedMps.toFloat()
                    stationary = snapshot.stationary
                    liveTrack = (liveTrack + LiveTrackPoint(
                        timestampMs = snapshot.timestampMs,
                        lat = snapshot.lat,
                        lon = snapshot.lon,
                        speedMps = snapshot.speedMps,
                        stationary = snapshot.stationary,
                        sectionId = liveSectionId,
                    )).takeLast(MAX_LIVE_TRACK_POINTS)
                }
                writer.write(
                    RecordLine.Gps(
                        // elapsedRealtimeNanos is on the same monotonic clock
                        // as SensorEvent.timestamp — one anchor for everything.
                        timestampMs = timestampMs,
                        lat = location.latitude,
                        lon = location.longitude,
                        altitudeM = if (location.hasAltitude()) location.altitude else null,
                        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                        speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                        bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                    ),
                )
            }
        }
    }

    // --- durable health heartbeat ----------------------------------------------

    private fun currentSessionElapsedMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val currentPause = if (paused) nowElapsedMs - pausedAtElapsedMs else 0L
        return (nowElapsedMs - startedElapsedMs - totalPausedMs - currentPause).coerceAtLeast(0L)
    }

    private fun healthInput(
        kind: String,
        sessionElapsedMs: Long,
        writerHealth: RecordingWriterHealth? = writer?.healthStats(),
        restartGapMs: Long? = null,
    ): RecordingHealthInput {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        return RecordingHealthInput(
            timestampMs = System.currentTimeMillis(),
            kind = kind,
            sessionElapsedMs = sessionElapsedMs,
            rawFile = repository.recordingFile(activityId),
            healthFile = repository.recordingHealthFile(activityId),
            writerHealth = writerHealth,
            gpsCount = gpsCount,
            imuCount = imuCount,
            baroCount = baroCount,
            lastGpsAgeMs = lastGpsReceivedElapsedMs
                .takeIf { it != Long.MIN_VALUE }
                ?.let { (nowElapsedMs - it).coerceAtLeast(0L) },
            paused = paused,
            restartGapMs = restartGapMs,
        )
    }

    private fun enqueueHealth(
        kind: String,
        sessionElapsedMs: Long,
        restartGapMs: Long? = null,
    ) {
        if (activityId.isBlank()) return
        val input = healthInput(kind, sessionElapsedMs, restartGapMs = restartGapMs)
        finalizeScope.launch { appendHealth(input) }
    }

    private suspend fun appendHealth(input: RecordingHealthInput) {
        withContext(Dispatchers.IO) {
            healthWriteMutex.withLock {
                runCatching {
                    val entry = RecordingHealthMetrics.capture(this@RecordingService, input)
                    RecordingHealthLog(input.healthFile).append(entry)
                }.onFailure { error ->
                    // Health telemetry is strictly best-effort and must never
                    // make the recorder less reliable.
                    Log.w(LOG_TAG, "could not write recording health heartbeat", error)
                }
            }
        }
    }

    // --- live state + notification ------------------------------------------------

    private fun startTicker() {
        scope.launch {
            var lastNotifiedSecond = -1L
            while (isActive && (recording || preparing)) {
                if (preparing) {
                    val preparingMs = SystemClock.elapsedRealtime() - prepareStartedElapsedMs
                    val imuReady = warmImuCount >= 10
                    repository.pushState(RecordingState.Preparing(preparingMs, warmGpsReady, imuReady, lastAccuracyM))
                    if ((warmGpsReady && imuReady) || preparingMs >= PREPARE_TIMEOUT_MS) beginRecording()
                    delay(STATE_PUSH_INTERVAL_MS)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val currentPause = if (paused) now - pausedAtElapsedMs else 0L
                val elapsedMs = now - startedElapsedMs - totalPausedMs - currentPause
                // ~4 state pushes per second; IMU samples only bump counters,
                // they never touch the StateFlow directly.
                repository.pushState(
                    RecordingState.Recording(
                        startedAtMs = startedAtMs,
                        elapsedMs = elapsedMs,
                        lastSpeedMps = lastSpeedMps,
                        lastAccuracyM = lastAccuracyM,
                        stationary = stationary,
                        liveTrack = liveTrack,
                        gpsCount = gpsCount,
                        imuCount = imuCount,
                        baroCount = baroCount,
                        paused = paused,
                    ),
                )
                val second = elapsedMs / 1_000
                if (second != lastNotifiedSecond) {
                    lastNotifiedSecond = second
                    updateNotification(elapsedMs)
                }
                if (now - lastHealthHeartbeatElapsedMs >= HEALTH_HEARTBEAT_INTERVAL_MS) {
                    lastHealthHeartbeatElapsedMs = now
                    writer?.flushDiagnostics(System.currentTimeMillis())
                    enqueueHealth(RecordingHealthLog.KIND_HEARTBEAT, elapsedMs)
                }
                delay(STATE_PUSH_INTERVAL_MS)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ride recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a ride is being recorded"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(elapsedMs: Long): Notification {
        val recentlyRecovered = isRecoveryNotificationActive(
            recoveredAtElapsedMs = recoveredAtElapsedMs,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        val presentation = recordingNotificationPresentation(
            elapsedMs = elapsedMs,
            preparing = preparing,
            recovering = recovering,
            paused = paused,
            recentlyRecovered = recentlyRecovered,
        )
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent
                .setAction(ACTION_OPEN_RECORDING)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            PendingIntent.getActivity(
                this,
                CONTENT_REQUEST_CODE,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(presentation.title)
            .setContentText(presentation.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        when (presentation.action) {
            RecordingNotificationAction.Pause -> builder.addAction(
                R.drawable.ic_notification_pause,
                "Pause",
                serviceActionPendingIntent(ACTION_PAUSE, PAUSE_REQUEST_CODE),
            )
            RecordingNotificationAction.Resume -> builder.addAction(
                R.drawable.ic_notification_resume,
                "Resume",
                serviceActionPendingIntent(ACTION_RESUME, RESUME_REQUEST_CODE),
            )
            null -> Unit
        }
        return builder.build()
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun updateNotification(elapsedMs: Long) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(elapsedMs))
    }
}
