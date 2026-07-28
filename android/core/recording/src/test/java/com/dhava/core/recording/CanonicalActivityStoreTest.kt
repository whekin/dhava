package com.dhava.core.recording

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalActivityStoreTest {
    @Test fun `reuses valid cache and rebuilds when raw or algorithm changes`() = runBlocking {
        val root = Files.createTempDirectory("dhava-artifact").toFile()
        val raw = root.resolve("ride.jsonl.gz").apply { writeText("raw-v1") }
        var version = "gps-bounded-0.2"
        var produceCalls = 0
        val store = CanonicalActivityStore(
            artifactsDir = root.resolve("artifacts"),
            currentAlgorithmVersion = { version },
            produce = {
                produceCalls++
                payload(version, distanceM = produceCalls.toDouble())
            },
            nowMs = { 123L },
        )

        val first = store.loadOrCreate("ride", raw)
        val cached = store.loadOrCreate("ride", raw)
        assertEquals(1, produceCalls)
        assertEquals(first, cached)
        assertEquals(
            CanonicalActivityState.DOWNHILL,
            cached.finalizedTrack.single().activityState,
        )
        assertEquals(0.88, cached.finalizedTrack.single().activityConfidence, 0.0)

        val oldModified = raw.lastModified()
        raw.appendText("-changed")
        assertTrue(raw.setLastModified(oldModified + 1_000))
        val changedRaw = store.loadOrCreate("ride", raw)
        assertEquals(2, produceCalls)
        assertEquals(2.0, changedRaw.analysis.distanceM, 0.0)

        version = "gps-bounded-0.3"
        val changedAlgorithm = store.loadOrCreate("ride", raw)
        assertEquals(3, produceCalls)
        assertEquals("gps-bounded-0.3", changedAlgorithm.algorithmVersion)
    }

    @Test fun `artifact with an older schema version is recomputed`() = runBlocking {
        val root = Files.createTempDirectory("dhava-artifact-schema").toFile()
        val raw = root.resolve("ride.jsonl.gz").apply { writeText("raw") }
        var produceCalls = 0
        val store = CanonicalActivityStore(
            artifactsDir = root.resolve("artifacts"),
            currentAlgorithmVersion = { "gps-bounded-0.2" },
            produce = {
                produceCalls++
                payload("gps-bounded-0.2", distanceM = 7.0)
            },
        )

        val fresh = store.loadOrCreate("ride", raw)
        assertEquals(CanonicalActivityStore.SCHEMA_VERSION, fresh.schemaVersion)
        assertNotNull("new artifacts must carry the quality summary", fresh.quality)

        // Simulate the previous artifact schema: same raw fingerprint and
        // algorithm, but without the current classifier contract.
        val legacyJson = Json { encodeDefaults = true; explicitNulls = false }
        val legacy = fresh.copy(schemaVersion = CanonicalActivityStore.SCHEMA_VERSION - 1, quality = null)
        GZIPOutputStream(store.artifactFile("ride").outputStream()).bufferedWriter().use { writer ->
            writer.write(legacyJson.encodeToString(legacy))
        }

        val rebuilt = store.loadOrCreate("ride", raw)
        assertEquals(2, produceCalls)
        assertEquals(CanonicalActivityStore.SCHEMA_VERSION, rebuilt.schemaVersion)
        assertNotNull(rebuilt.quality)
        assertEquals(CanonicalElevationSource.GPS_INTERPOLATED, rebuilt.quality?.elevationSource)
    }

    @Test fun `corrupt artifact is replaced and delete removes cache`() = runBlocking {
        val root = Files.createTempDirectory("dhava-artifact-corrupt").toFile()
        val raw = root.resolve("ride.jsonl.gz").apply { writeText("raw") }
        var produceCalls = 0
        val store = CanonicalActivityStore(
            artifactsDir = root.resolve("artifacts"),
            currentAlgorithmVersion = { "gps-bounded-0.2" },
            produce = {
                produceCalls++
                payload("gps-bounded-0.2", distanceM = 42.0)
            },
        )

        store.loadOrCreate("ride", raw)
        store.artifactFile("ride").writeText("not gzip")
        val rebuilt = store.loadOrCreate("ride", raw)
        assertEquals(2, produceCalls)
        assertEquals(42.0, rebuilt.analysis.distanceM, 0.0)

        store.delete("ride")
        assertFalse(store.artifactFile("ride").exists())
        assertTrue(raw.exists())
    }

    @Test fun `clearAll removes every artifact and temp file but preserves raw and recomputes`() = runBlocking {
        val root = Files.createTempDirectory("dhava-artifact-clear").toFile()
        val rawA = root.resolve("a.jsonl.gz").apply { writeText("raw-a") }
        val rawB = root.resolve("b.jsonl.gz").apply { writeText("raw-b") }
        var produceCalls = 0
        val store = CanonicalActivityStore(
            artifactsDir = root.resolve("artifacts"),
            currentAlgorithmVersion = { "gps-bounded-0.2" },
            produce = {
                produceCalls++
                payload("gps-bounded-0.2", distanceM = produceCalls.toDouble())
            },
        )
        store.loadOrCreate("a", rawA)
        store.loadOrCreate("b", rawB)
        root.resolve("artifacts/stale.canonical.tmp").writeText("partial")

        assertEquals(2, store.clearAll())
        assertFalse(store.artifactFile("a").exists())
        assertFalse(store.artifactFile("b").exists())
        assertFalse(root.resolve("artifacts/stale.canonical.tmp").exists())
        assertTrue(rawA.exists())
        assertTrue(rawB.exists())

        store.loadOrCreate("a", rawA)
        assertEquals(3, produceCalls)
        assertTrue(store.artifactFile("a").exists())
    }

    @Test fun `does not cache a result when raw changes during finalization`() = runBlocking {
        val root = Files.createTempDirectory("dhava-artifact-changing").toFile()
        val raw = root.resolve("ride.jsonl.gz").apply { writeText("raw") }
        val store = CanonicalActivityStore(
            artifactsDir = root.resolve("artifacts"),
            currentAlgorithmVersion = { "gps-bounded-0.2" },
            produce = { path ->
                File(path).appendText("-new-samples")
                payload("gps-bounded-0.2", distanceM = 1.0)
            },
        )

        val result = runCatching { store.loadOrCreate("ride", raw) }
        assertTrue(result.isFailure)
        assertFalse(store.artifactFile("ride").exists())
        assertTrue(raw.readText().endsWith("-new-samples"))
    }

    private fun payload(version: String, distanceM: Double): CanonicalArtifactPayload =
        CanonicalArtifactPayload(
            algorithmVersion = version,
            analysis = CanonicalAnalysis(
                startedAtMs = 1_000,
                endedAtMs = 2_000,
                movingTimeS = 1.0,
                distanceM = distanceM,
                ascentM = 0.0,
                descentM = 10.0,
                maxSpeedMps = 5.0,
                avgMovingSpeedMps = 4.0,
                airtimeTotalMs = 0,
                airtimeWindows = emptyList(),
                track = emptyList(),
                gpsCount = 2,
                imuCount = 10,
                algorithmVersion = version,
            ),
            rawTrack = listOf(CanonicalPoint(1_000, 41.7, 44.8, sectionId = 0)),
            finalizedTrack = listOf(
                CanonicalPoint(
                    timestampMs = 1_000,
                    lat = 41.7,
                    lon = 44.8,
                    sectionId = 0,
                    activityState = CanonicalActivityState.DOWNHILL,
                    activityConfidence = 0.88,
                ),
            ),
            quality = CanonicalQuality(
                elevationSource = CanonicalElevationSource.GPS_INTERPOLATED,
                baroSampleCount = 0,
                gpsFixCount = 2,
                gpsAcceptedCount = 2,
                medianAccuracyM = 4.0,
                p90AccuracyM = 4.0,
                gpsGapCount = 0,
                longestGapS = 0.0,
                elevationUncertaintyM = 6.0,
            ),
        )
}
