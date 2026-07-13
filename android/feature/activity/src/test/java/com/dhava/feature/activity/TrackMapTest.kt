package com.dhava.feature.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
