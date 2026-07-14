package com.dhava.core.recording

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            finalizedTrack = listOf(CanonicalPoint(1_000, 41.7, 44.8, sectionId = 0)),
        )
}
