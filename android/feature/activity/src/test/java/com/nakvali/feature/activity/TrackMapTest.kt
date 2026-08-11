package com.nakvali.feature.activity

import com.nakvali.fusion.ActivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMapTest {

    @Test
    fun `manual pause creates separate drawable sections`() {
        val beforePause = MapTrackPoint(41.7, 44.8, sectionId = 0)
        val beforePauseEnd = MapTrackPoint(41.71, 44.81, sectionId = 0)
        val afterResume = MapTrackPoint(42.0, 45.0, sectionId = 1)
        val afterResumeEnd = MapTrackPoint(42.01, 45.01, sectionId = 1)

        val sections = listOf(
            beforePause,
            beforePauseEnd,
            afterResume,
            afterResumeEnd,
        ).continuousSections()

        assertEquals(
            listOf(
                listOf(beforePause, beforePauseEnd),
                listOf(afterResume, afterResumeEnd),
            ),
            sections,
        )
    }

    @Test
    fun `raw drawable sections split real gaps but preserve legacy zero timestamps`() {
        val beforeGap = MapTrackPoint(41.7, 44.8, sectionId = 0, timestampMs = 10_000)
        val beforeGapEnd = MapTrackPoint(41.71, 44.81, sectionId = 0, timestampMs = 11_000)
        val afterGap = MapTrackPoint(41.72, 44.82, sectionId = 0, timestampMs = 14_001)
        val afterGapEnd = MapTrackPoint(41.73, 44.83, sectionId = 0, timestampMs = 15_000)
        val legacyA = MapTrackPoint(41.8, 44.9, sectionId = 1)
        val legacyB = MapTrackPoint(41.81, 44.91, sectionId = 1)

        val sections = listOf(
            beforeGap,
            beforeGapEnd,
            afterGap,
            afterGapEnd,
            legacyA,
            legacyB,
        ).continuousSections()

        assertEquals(
            listOf(
                listOf(beforeGap, beforeGapEnd),
                listOf(afterGap, afterGapEnd),
                listOf(legacyA, legacyB),
            ),
            sections,
        )
    }

    @Test
    fun `GPS point features preserve accuracy and mark unknown estimates`() {
        val features = listOf(
            MapTrackPoint(41.7, 44.8, sectionId = 0, accuracyM = 3.8),
            MapTrackPoint(41.71, 44.81, sectionId = 0, accuracyM = null),
            MapTrackPoint(41.72, 44.82, sectionId = 0, accuracyM = Double.NaN),
        ).toAccuracyFeatureCollectionOrNull()?.features()

        assertNotNull(features)
        assertEquals(
            3.8,
            features!![0].getNumberProperty(GPS_ACCURACY_PROPERTY).toDouble(),
            0.0,
        )
        assertEquals(
            UNKNOWN_GPS_ACCURACY_STYLE_VALUE,
            features[1].getNumberProperty(GPS_ACCURACY_PROPERTY).toDouble(),
            0.0,
        )
        assertEquals(
            UNKNOWN_GPS_ACCURACY_STYLE_VALUE,
            features[2].getNumberProperty(GPS_ACCURACY_PROPERTY).toDouble(),
            0.0,
        )
    }

    @Test
    fun `fusion sample features preserve every computed point`() {
        val points = listOf(
            MapTrackPoint(41.7, 44.8, sectionId = 0),
            MapTrackPoint(41.70001, 44.80001, sectionId = 0),
            MapTrackPoint(41.70002, 44.80002, sectionId = 0),
        )

        val features = points.toPointFeatureCollectionOrNull()?.features()

        assertNotNull(features)
        assertEquals(3, features!!.size)
    }

    @Test
    fun `state change shares a boundary vertex without leaving a line gap`() {
        val downhillStart = point(0, ActivityState.DOWNHILL)
        val sharedBoundary = point(1_000, ActivityState.DOWNHILL, offset = 0.001)
        val transitStart = point(2_000, ActivityState.TRANSIT, offset = 0.002)
        val transitEnd = point(3_000, ActivityState.TRANSIT, offset = 0.003)

        val runs = listOf(
            downhillStart,
            sharedBoundary,
            transitStart,
            transitEnd,
        ).semanticLineRuns()

        assertEquals(2, runs.size)
        assertEquals(ActivityState.DOWNHILL, runs[0].activityState)
        assertEquals(listOf(downhillStart, sharedBoundary), runs[0].points)
        assertEquals(ActivityState.TRANSIT, runs[1].activityState)
        assertEquals(listOf(sharedBoundary, transitStart, transitEnd), runs[1].points)
    }

    @Test
    fun `pause and long gap create hard line breaks without shared vertices`() {
        val firstStart = point(0, ActivityState.DOWNHILL, sectionId = 0)
        val firstEnd = point(1_000, ActivityState.DOWNHILL, sectionId = 0, offset = 0.001)
        val resumedStart = point(2_000, ActivityState.DOWNHILL, sectionId = 1, offset = 0.010)
        val resumedEnd = point(3_000, ActivityState.DOWNHILL, sectionId = 1, offset = 0.011)
        val afterGapStart = point(7_000, ActivityState.DOWNHILL, sectionId = 1, offset = 0.020)
        val afterGapEnd = point(8_000, ActivityState.DOWNHILL, sectionId = 1, offset = 0.021)

        val runs = listOf(
            firstStart,
            firstEnd,
            resumedStart,
            resumedEnd,
            afterGapStart,
            afterGapEnd,
        ).semanticLineRuns()

        assertEquals(
            listOf(
                listOf(firstStart, firstEnd),
                listOf(resumedStart, resumedEnd),
                listOf(afterGapStart, afterGapEnd),
            ),
            runs.map(SemanticLineRun::points),
        )
    }

    @Test
    fun `still points aggregate into one stop marker and never create a still line`() {
        val arrival = point(0, ActivityState.DOWNHILL)
        val stillStart = point(1_000, ActivityState.STILL, confidence = 0.7, offset = 0.001)
        val stillMiddle = point(2_000, ActivityState.STILL, confidence = 0.8, offset = 0.001)
        val stillEnd = point(3_000, ActivityState.STILL, confidence = 0.9, offset = 0.001)
        val departure = point(4_000, ActivityState.TRANSIT, offset = 0.002)
        val points = listOf(arrival, stillStart, stillMiddle, stillEnd, departure)

        val runs = points.semanticLineRuns()

        assertEquals(listOf(ActivityState.DOWNHILL, ActivityState.TRANSIT), runs.map { it.activityState })
        assertEquals(listOf(arrival, stillStart), runs[0].points)
        assertEquals(listOf(stillEnd, departure), runs[1].points)
    }

    @Test
    fun `a real stop aggregates into one marker at its middle`() {
        val arrival = point(0, ActivityState.DOWNHILL)
        val still = (1..20).map { step ->
            point(step * 1_000L, ActivityState.STILL, confidence = 0.8, offset = 0.001)
        }
        val departure = point(21_000, ActivityState.TRANSIT, offset = 0.002)

        val markers = (listOf(arrival) + still + departure).aggregatedStopMarkers()

        assertEquals(1, markers.size)
        assertEquals(19_000L, markers.single().durationMs)
        assertEquals(0.8, markers.single().confidence!!, 0.000_001)
    }

    @Test
    fun `a track stand is not a stop worth marking`() {
        val points = listOf(
            point(0, ActivityState.DOWNHILL),
            point(1_000, ActivityState.STILL, offset = 0.001),
            point(3_000, ActivityState.STILL, offset = 0.001),
            point(4_000, ActivityState.DOWNHILL, offset = 0.002),
        )

        assertEquals(emptyList<StopMarker>(), points.aggregatedStopMarkers())
    }

    @Test
    fun `a traffic light inside a vehicle is not a rider stop`() {
        val inTraffic = buildList {
            add(point(0, ActivityState.LIKELY_MOTORIZED))
            (1..40).forEach { step ->
                add(point(step * 1_000L, ActivityState.STILL, offset = 0.001))
            }
            add(point(41_000, ActivityState.LIKELY_MOTORIZED, offset = 0.002))
        }

        assertEquals(emptyList<StopMarker>(), inTraffic.aggregatedStopMarkers())
    }

    @Test
    fun `getting off the shuttle and standing is still a stop`() {
        val arrival = buildList {
            add(point(0, ActivityState.LIKELY_MOTORIZED))
            (1..40).forEach { step ->
                add(point(step * 1_000L, ActivityState.STILL, offset = 0.001))
            }
            add(point(41_000, ActivityState.DOWNHILL, offset = 0.002))
        }

        assertEquals(1, arrival.aggregatedStopMarkers().size)
    }

    @Test
    fun `unclassified legacy track retains the fallback line style key`() {
        val points = listOf(
            point(0, state = null),
            point(1_000, state = null, offset = 0.001),
        )

        val runs = points.semanticLineRuns()
        val features = points.toSemanticLineFeatureCollectionOrNull()?.features()

        assertEquals(1, runs.size)
        assertNull(runs.single().activityState)
        assertNotNull(features)
        assertEquals(
            ACTIVITY_STATE_UNCLASSIFIED,
            features!!.single().getStringProperty(ACTIVITY_STATE_PROPERTY),
        )
    }

    @Test
    fun `camera bounds prefer accepted GPS and finalized track`() {
        val accepted = MapTrackPoint(41.7, 44.8, sectionId = 0, accuracyM = 4.0)
        val rejected = MapTrackPoint(42.7, 45.8, sectionId = 0, accuracyM = 40.0)
        val unknown = MapTrackPoint(43.7, 46.8, sectionId = 0)
        val fused = point(1_000, ActivityState.DOWNHILL)

        assertEquals(
            listOf(accepted),
            cameraBoundsPoints(TrackMode.Gps, listOf(accepted, rejected, unknown), emptyList()),
        )
        assertEquals(
            listOf(fused),
            cameraBoundsPoints(TrackMode.Compare, listOf(accepted, rejected), listOf(fused)),
        )
        assertEquals(
            listOf(rejected, unknown),
            cameraBoundsPoints(TrackMode.Gps, listOf(rejected, unknown), emptyList()),
        )
    }

    @Test
    fun `diagnostic lines disable MapLibre coordinate simplification`() {
        assertEquals(0f, diagnosticLineOptions()["tolerance"])
    }

    private fun point(
        timestampMs: Long,
        state: ActivityState?,
        sectionId: Int = 0,
        confidence: Double = 0.9,
        offset: Double = 0.0,
    ) = MapTrackPoint(
        lat = 41.7 + offset,
        lon = 44.8 + offset,
        sectionId = sectionId,
        timestampMs = timestampMs,
        activityState = state,
        activityConfidence = confidence,
    )
}
