package com.dhava.core.recording

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
import android.os.SystemClock
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
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that records GPS + IMU + barometer into a raw
 * `.jsonl.gz` file (see `proto/raw-recording-format.md`).
 *
 * Runs with `foregroundServiceType="location"` and a persistent notification;
 * START_STICKY so the system restarts it if the process is killed mid-ride.
 * Sensor callbacks are delivered on a dedicated [HandlerThread] and only
 * enqueue lines into [RecordingWriter] — no I/O or allocation-heavy work on
 * the callback path.
 */
class RecordingService : Service() {

    companion object {
        private const val ACTION_START = "com.dhava.core.recording.action.START"
        private const val ACTION_STOP = "com.dhava.core.recording.action.STOP"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        private const val GPS_INTERVAL_MS = 1_000L
        private const val GPS_MIN_INTERVAL_MS = 500L

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Never cancelled: file finalization must complete even when [onDestroy]
     * cancels [scope] (e.g. the system kills the task mid-recording).
     */
    private val finalizeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

    // Latest gyro/mag samples, paired with accelerometer events (see below).
    @Volatile private var latestGyro: FloatArray? = null
    @Volatile private var latestMag: FloatArray? = null

    private var recording = false

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
            // Null intent: restarted by the system after being killed. There
            // is no in-memory session to resume, so just stop; the partial
            // file (flushed every ~2 s) stays on disk for later inspection.
            null -> if (!recording) stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- recording lifecycle ------------------------------------------------

    private fun startRecording() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // UI is responsible for requesting permissions before starting.
            stopSelf()
            return
        }
        recording = true

        activityId = UUID.randomUUID().toString()
        startedAtMs = System.currentTimeMillis()
        startedElapsedMs = SystemClock.elapsedRealtime()
        epochAnchorMs = startedAtMs - startedElapsedMs
        gpsCount = 0
        imuCount = 0
        baroCount = 0
        lastSpeedMps = null
        lastAccuracyM = null
        latestGyro = null
        latestMag = null

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

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(elapsedMs = 0L),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        sensorThread = HandlerThread("recording-sensors").also { it.start() }
        registerSensors()
        requestLocationUpdates()
        startTicker()
    }

    private fun stopRecording() {
        if (!recording) {
            stopSelf()
            return
        }
        recording = false

        sensorManager.unregisterListener(sensorListener)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorThread?.quitSafely()
        sensorThread = null

        val endedAtMs = System.currentTimeMillis()
        val finishedWriter = writer
        writer = null
        val id = activityId

        finalizeScope.launch {
            finishedWriter?.close()
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

    override fun onDestroy() {
        if (recording) {
            // Torn down without an explicit stop (e.g. task removed): make a
            // best effort to release sensors and finish the file.
            stopRecording()
        }
        scope.cancel()
        super.onDestroy()
    }

    // --- sensors --------------------------------------------------------------

    private fun registerSensors() {
        val handler = sensorThread?.let { HandlerCompat.createAsync(it.looper) } ?: return
        // SENSOR_DELAY_FASTEST + the HIGH_SAMPLING_RATE_SENSORS permission
        // gives 100 Hz+ on most devices. Missing sensors are simply skipped —
        // we record whatever hardware exists.
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
                    SensorManager.SENSOR_DELAY_FASTEST,
                    handler,
                )
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val writer = writer ?: return
            when (event.sensor.type) {
                // Pairing choice: the accelerometer is the master clock of the
                // `imu` line. Gyro and mag callbacks only stash their latest
                // sample; each accel event emits one line with those most
                // recent values. Accel and gyro both run at FASTEST (similar
                // rates), so "latest gyro" is the nearest-timestamp match to
                // within one sample period — good enough for fusion, which
                // re-interpolates anyway, and far simpler than a merge queue.
                Sensor.TYPE_ACCELEROMETER -> {
                    imuCount++
                    writer.write(
                        RecordLine.Imu(
                            timestampMs = epochAnchorMs + event.timestamp / 1_000_000,
                            accel = listOf(event.values[0], event.values[1], event.values[2]),
                            // Devices without a gyroscope report a zero rate
                            // (the field is required by the format spec).
                            gyro = latestGyro?.toList() ?: listOf(0f, 0f, 0f),
                            mag = latestMag?.toList(),
                        ),
                    )
                }
                Sensor.TYPE_GYROSCOPE -> latestGyro = event.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD -> latestMag = event.values.clone()
                Sensor.TYPE_PRESSURE -> {
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
            val writer = writer ?: return
            for (location in result.locations) {
                gpsCount++
                lastSpeedMps = if (location.hasSpeed()) location.speed else null
                lastAccuracyM = if (location.hasAccuracy()) location.accuracy else null
                writer.write(
                    RecordLine.Gps(
                        // elapsedRealtimeNanos is on the same monotonic clock
                        // as SensorEvent.timestamp — one anchor for everything.
                        timestampMs = epochAnchorMs + location.elapsedRealtimeNanos / 1_000_000,
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

    // --- live state + notification ------------------------------------------------

    private fun startTicker() {
        scope.launch {
            var lastNotifiedSecond = -1L
            while (isActive && recording) {
                val elapsedMs = SystemClock.elapsedRealtime() - startedElapsedMs
                // ~4 state pushes per second; IMU samples only bump counters,
                // they never touch the StateFlow directly.
                repository.pushState(
                    RecordingState.Recording(
                        startedAtMs = startedAtMs,
                        elapsedMs = elapsedMs,
                        lastSpeedMps = lastSpeedMps,
                        lastAccuracyM = lastAccuracyM,
                        gpsCount = gpsCount,
                        imuCount = imuCount,
                        baroCount = baroCount,
                    ),
                )
                val second = elapsedMs / 1_000
                if (second != lastNotifiedSecond) {
                    lastNotifiedSecond = second
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(elapsedMs))
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
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle("Recording ride — ${formatElapsed(elapsedMs)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1_000
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            totalSeconds / 3_600,
            (totalSeconds % 3_600) / 60,
            totalSeconds % 60,
        )
    }
}
