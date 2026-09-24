package com.nakvali.core.recording.bikeyard

import com.nakvali.core.recording.TrackExportPoint
import com.nakvali.fusion.AirtimeWindow
import com.nakvali.fusion.SensorMetricsEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeyardSensorMetricsTest {
    @Test fun `candidate document follows v1 without phone impact or coordinates`() {
        val evidence = SensorMetricsEvidence(
            coverage = 0.94,
            sampleRateHz = 119.0,
            events = listOf(AirtimeWindow(1_000, 420, 17.28, 6.02)),
        )
        val document = buildBikeyardMetricsDocument(evidence, BikeyardMounting.POCKET,
            "gps-bounded-0.17", "1.4.0", 2_000)
        val encoded = bikeyardMetricsJson.encodeToString(BikeyardSensorMetricsDocument.serializer(), document)

        assertTrue(encoded.contains("\"schema\":\"bikeyard.sensor-metrics\""))
        assertTrue(encoded.contains("\"mounting\":\"pocket\""))
        assertTrue(encoded.contains("\"kind\":\"airtime\""))
        assertTrue(encoded.contains("\"status\":\"candidate\""))
        assertTrue(encoded.contains("\"end_ms\":1420"))
        assertFalse(encoded.contains("landing_peak_g"))
        assertFalse(encoded.contains("takeoff_peak_g"))
        assertFalse(encoded.contains("\"lat\""))
        assertFalse(encoded.contains("\"lon\""))
        assertFalse(encoded.contains("\"accel\""))
    }

    @Test fun `low coverage cannot assert an empty measured ride`() {
        val evidence = SensorMetricsEvidence(0.5, 100.0, emptyList())
        val result = runCatching {
            buildBikeyardMetricsDocument(evidence, BikeyardMounting.UNKNOWN, "v1", null, 2_000)
        }
        assertTrue(result.isFailure)
    }

    @Test fun `sensor scopes use the same breaks as the uploaded TCX`() {
        val points = listOf(
            TrackExportPoint(1_000, 41.0, 44.0, sectionId = 0, runId = 0),
            TrackExportPoint(2_000, 41.0, 44.0, sectionId = 0, runId = 0),
            TrackExportPoint(3_000, 41.0, 44.0, sectionId = 1, runId = 1),
            TrackExportPoint(4_000, 41.0, 44.0, sectionId = 1, runId = 1),
        )

        val scopes = points.sensorScopes()
        assertEquals(2, scopes.size)
        assertEquals(1_000L, scopes[0].startedAtMs)
        assertEquals(2_000L, scopes[0].endedAtMs)
        assertEquals(3_000L, scopes[1].startedAtMs)
    }
}
