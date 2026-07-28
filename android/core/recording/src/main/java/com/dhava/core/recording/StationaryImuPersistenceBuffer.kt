package com.dhava.core.recording

import java.util.ArrayDeque

/**
 * Selects which raw IMU samples should be persisted while live fusion reports
 * the device as stationary.
 *
 * Acquisition and live fusion continue at their normal rates. During a
 * confirmed stationary period this buffer retains a full-rate pre-roll and
 * emits only a sparse 20 Hz cadence from samples older than that window. That
 * cadence still satisfies Rust's replay-time stationary detector, which needs
 * at least 12 samples in its 700 ms window. If
 * motion resumes, the complete pre-roll is emitted before the first moving
 * sample, preserving the transition for canonical recomputation.
 */
internal class StationaryImuPersistenceBuffer(
    private val preRollDurationMs: Long = DEFAULT_PRE_ROLL_DURATION_MS,
    private val stationarySampleIntervalMs: Long = DEFAULT_STATIONARY_SAMPLE_INTERVAL_MS,
) {
    private companion object {
        const val DEFAULT_PRE_ROLL_DURATION_MS = 2_000L
        const val DEFAULT_STATIONARY_SAMPLE_INTERVAL_MS = 50L
    }

    private val preRoll = ArrayDeque<RecordLine.Imu>()
    private var lastAcceptedTimestampMs = Long.MIN_VALUE
    private var lastStationaryBucket = Long.MIN_VALUE
    private var lastExpiredTimestampMs = Long.MIN_VALUE

    init {
        require(preRollDurationMs > 0)
        require(stationarySampleIntervalMs > 0)
    }

    /**
     * Returns samples ready for immediate persistence in timestamp order.
     *
     * Duplicate or stale input timestamps are ignored defensively. Android
     * sensor timestamps are strictly monotonic in practice, but dropping an
     * anomalous callback is safer than ever reordering the immutable raw file.
     */
    fun accept(
        sample: RecordLine.Imu,
        stationary: Boolean,
    ): List<RecordLine.Imu> {
        if (sample.timestampMs <= lastAcceptedTimestampMs) return emptyList()
        lastAcceptedTimestampMs = sample.timestampMs

        if (!stationary) {
            if (preRoll.isEmpty()) {
                resetSparseCadence()
                return listOf(sample)
            }
            return buildList(preRoll.size + 1) {
                drainPreRollTo(this)
                add(sample)
            }.also {
                resetSparseCadence()
            }
        }

        preRoll.addLast(sample)
        val oldestRetainedTimestampMs = sample.timestampMs - preRollDurationMs
        var ready: MutableList<RecordLine.Imu>? = null
        while (
            preRoll.isNotEmpty() &&
            preRoll.first.timestampMs < oldestRetainedTimestampMs
        ) {
            val expired = preRoll.removeFirst()
            // Fixed epoch-aligned buckets avoid cadence aliasing. Comparing
            // against the last emitted timestamp would turn a sufficient
            // 49 ms source into 98 ms output forever. One sample per absolute
            // 50 ms bucket keeps a 20 Hz-or-slower source intact by density
            // and is independent of the recording's epoch offset.
            val bucket = Math.floorDiv(expired.timestampMs, stationarySampleIntervalMs)
            val sourceIntervalMs = if (lastExpiredTimestampMs == Long.MIN_VALUE) {
                null
            } else {
                expired.timestampMs - lastExpiredTimestampMs
            }
            lastExpiredTimestampMs = expired.timestampMs
            // SensorManager's requested period is only a hint. If a device is
            // already delivering near the minimum replay-safe density, retain
            // every sample; bucket decimation around the same frequency can
            // otherwise alias 49 ms input into occasional under-dense windows.
            val sourceNeedsFullDensity = sourceIntervalMs != null &&
                sourceIntervalMs >= (stationarySampleIntervalMs / 2).coerceAtLeast(1L)
            if (bucket != lastStationaryBucket || sourceNeedsFullDensity) {
                if (ready == null) ready = mutableListOf()
                ready.add(expired)
                lastStationaryBucket = bucket
            }
        }
        return ready ?: emptyList()
    }

    /** Emits every retained pre-roll sample and starts a fresh still window. */
    fun flush(): List<RecordLine.Imu> {
        if (preRoll.isEmpty()) {
            resetSparseCadence()
            return emptyList()
        }
        return buildList(preRoll.size) {
            drainPreRollTo(this)
        }.also {
            resetSparseCadence()
        }
    }

    /** Drops all process-local state before a fresh or recovered capture. */
    fun reset() {
        preRoll.clear()
        lastAcceptedTimestampMs = Long.MIN_VALUE
        resetSparseCadence()
    }

    private fun resetSparseCadence() {
        lastStationaryBucket = Long.MIN_VALUE
        lastExpiredTimestampMs = Long.MIN_VALUE
    }

    internal val bufferedSampleCount: Int
        get() = preRoll.size

    private fun drainPreRollTo(destination: MutableList<RecordLine.Imu>) {
        while (preRoll.isNotEmpty()) {
            destination.add(preRoll.removeFirst())
        }
    }
}
