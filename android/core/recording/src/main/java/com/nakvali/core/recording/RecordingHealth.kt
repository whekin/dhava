package com.nakvali.core.recording

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Append-only operational diagnostics for one recording.
 *
 * This deliberately lives beside, not inside, the immutable raw sensor file:
 * health instrumentation can evolve without changing the fusion input
 * contract or invalidating canonical artifacts.
 */
@Serializable
data class RecordingHealthEntry(
    val version: Int = 1,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val kind: String,
    @SerialName("session_elapsed_ms") val sessionElapsedMs: Long? = null,
    @SerialName("process_uptime_ms") val processUptimeMs: Long? = null,
    @SerialName("process_cpu_ms") val processCpuMs: Long? = null,
    @SerialName("pss_kb") val pssKb: Long? = null,
    @SerialName("rss_kb") val rssKb: Long? = null,
    @SerialName("java_heap_used_kb") val javaHeapUsedKb: Long? = null,
    @SerialName("native_heap_allocated_kb") val nativeHeapAllocatedKb: Long? = null,
    @SerialName("raw_bytes") val rawBytes: Long? = null,
    @SerialName("writer_pending_critical") val writerPendingCritical: Int? = null,
    @SerialName("writer_pending_imu") val writerPendingImu: Int? = null,
    @SerialName("writer_dropped_imu_total") val writerDroppedImuTotal: Long? = null,
    @SerialName("process_gps_count") val processGpsCount: Int? = null,
    @SerialName("process_imu_count") val processImuCount: Int? = null,
    @SerialName("process_baro_count") val processBaroCount: Int? = null,
    @SerialName("last_gps_age_ms") val lastGpsAgeMs: Long? = null,
    val paused: Boolean? = null,
    @SerialName("thermal_status") val thermalStatus: Int? = null,
    @SerialName("battery_percent") val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    @SerialName("restart_gap_ms") val restartGapMs: Long? = null,
    @SerialName("exit_timestamp_ms") val exitTimestampMs: Long? = null,
    @SerialName("exit_reason") val exitReason: Int? = null,
    @SerialName("exit_status") val exitStatus: Int? = null,
    @SerialName("exit_importance") val exitImportance: Int? = null,
    @SerialName("exit_pss_kb") val exitPssKb: Long? = null,
    @SerialName("exit_rss_kb") val exitRssKb: Long? = null,
    @SerialName("exit_description") val exitDescription: String? = null,
)

internal data class RecordingHealthInput(
    val timestampMs: Long,
    val kind: String,
    val sessionElapsedMs: Long,
    val rawFile: File,
    val healthFile: File,
    val writerHealth: RecordingWriterHealth?,
    val gpsCount: Int,
    val imuCount: Int,
    val baroCount: Int,
    val lastGpsAgeMs: Long?,
    val paused: Boolean,
    val restartGapMs: Long? = null,
)

internal object RecordingHealthMetrics {
    fun capture(context: Context, input: RecordingHealthInput): RecordingHealthEntry {
        val runtime = Runtime.getRuntime()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        return RecordingHealthEntry(
            timestampMs = input.timestampMs,
            kind = input.kind,
            sessionElapsedMs = input.sessionElapsedMs,
            processUptimeMs = SystemClock.elapsedRealtime(),
            processCpuMs = Process.getElapsedCpuTime(),
            pssKb = Debug.getPss(),
            rssKb = currentRssKb(),
            javaHeapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1_024,
            nativeHeapAllocatedKb = Debug.getNativeHeapAllocatedSize() / 1_024,
            rawBytes = input.rawFile.takeIf(File::isFile)?.length() ?: 0,
            writerPendingCritical = input.writerHealth?.pendingCritical,
            writerPendingImu = input.writerHealth?.pendingImu,
            writerDroppedImuTotal = input.writerHealth?.droppedImuTotal,
            processGpsCount = input.gpsCount,
            processImuCount = input.imuCount,
            processBaroCount = input.baroCount,
            lastGpsAgeMs = input.lastGpsAgeMs,
            paused = input.paused,
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                powerManager?.currentThermalStatus
            } else {
                null
            },
            batteryPercent = batteryManager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 },
            charging = batteryManager?.isCharging,
            restartGapMs = input.restartGapMs,
        )
    }

    private fun currentRssKb(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(':')
                ?.trim()
                ?.substringBefore(' ')
                ?.toLongOrNull()
        }
    }.getOrNull()
}

internal class RecordingHealthLog(private val file: File) {
    private val lock = Any()

    fun append(entry: RecordingHealthEntry) {
        val bytes = (RecordingHealthJson.encodeToString(entry) + "\n").toByteArray()
        synchronized(lock) {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { output ->
                if (file.length() > 0 && !endsWithNewline(file)) {
                    // A process kill can truncate the previous JSON object.
                    // Separate it so this heartbeat remains independently
                    // decodable instead of being glued to the damaged tail.
                    output.write('\n'.code)
                }
                output.write(bytes)
                output.flush()
                // One tiny fsync per minute gives the heartbeat meaning even
                // if the process is killed immediately after it was written.
                output.fd.sync()
            }
        }
    }

    private fun endsWithNewline(source: File): Boolean =
        RandomAccessFile(source, "r").use { input ->
            input.seek(input.length() - 1)
            input.read() == '\n'.code
        }

    fun entries(): List<RecordingHealthEntry> {
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    RecordingHealthJson.decodeFromString<RecordingHealthEntry>(line)
                }.getOrNull()
            }.toList()
        }
    }

    fun hasProcessExit(timestampMs: Long): Boolean =
        entries().any { it.kind == KIND_PROCESS_EXIT && it.exitTimestampMs == timestampMs }

    companion object {
        const val KIND_START = "start"
        const val KIND_HEARTBEAT = "heartbeat"
        const val KIND_RESTART = "restart"
        const val KIND_STOP = "stop"
        const val KIND_PROCESS_EXIT = "process_exit"
    }
}

internal object RecordingExitDiagnostics {
    fun latestAfter(context: Context, startedAtMs: Long): RecordingHealthEntry? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { latestAfterApi30(context, startedAtMs) }.getOrNull()
    }

    // The version guard lives in the caller, which lint cannot see across a
    // function boundary; the annotation states the same contract for it.
    @RequiresApi(Build.VERSION_CODES.R)
    private fun latestAfterApi30(context: Context, startedAtMs: Long): RecordingHealthEntry? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .asSequence()
            .filter { it.timestamp >= startedAtMs }
            .maxByOrNull(ApplicationExitInfo::getTimestamp)
            ?: return null
        return RecordingHealthEntry(
            timestampMs = exit.timestamp,
            kind = RecordingHealthLog.KIND_PROCESS_EXIT,
            exitTimestampMs = exit.timestamp,
            exitReason = exit.reason,
            exitStatus = exit.status,
            exitImportance = exit.importance,
            exitPssKb = exit.pss.takeIf { it > 0 },
            exitRssKb = exit.rss.takeIf { it > 0 },
            exitDescription = exit.description,
        )
    }
}

internal val RecordingHealthJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
