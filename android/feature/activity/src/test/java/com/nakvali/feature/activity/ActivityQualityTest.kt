package com.nakvali.feature.activity

import com.nakvali.core.recording.CanonicalElevationSource
import com.nakvali.core.recording.CanonicalQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityQualityTest {

    @Test fun `median accuracy buckets follow the 5 and 10 meter thresholds`() {
        assertEquals(GpsQualityBucket.GOOD, gpsQualityBucket(3.8))
        assertEquals(GpsQualityBucket.GOOD, gpsQualityBucket(5.0))
        assertEquals(GpsQualityBucket.FAIR, gpsQualityBucket(5.1))
        assertEquals(GpsQualityBucket.FAIR, gpsQualityBucket(10.0))
        assertEquals(GpsQualityBucket.POOR, gpsQualityBucket(10.1))
        assertNull(gpsQualityBucket(null))
        assertNull(gpsQualityBucket(Double.NaN))
    }

    @Test fun `gps chip text includes bucket, median and gap count`() {
        assertEquals("GPS: Good · 3.8 m", gpsChipText(quality(medianAccuracyM = 3.8)))
        assertEquals(
            "GPS: Fair · 7.2 m · 1 gap",
            gpsChipText(quality(medianAccuracyM = 7.2, gpsGapCount = 1)),
        )
        assertEquals(
            "GPS: Poor · 14.0 m · 3 gaps",
            gpsChipText(quality(medianAccuracyM = 14.0, gpsGapCount = 3)),
        )
        assertNull(gpsChipText(quality(medianAccuracyM = null)))
    }

    @Test fun `elevation chip text reflects the rust-reported source`() {
        assertEquals(
            "Elevation: Barometric",
            elevationChipText(quality(source = CanonicalElevationSource.BAROMETRIC)),
        )
        assertEquals(
            "Elevation: GPS net (±8 m)",
            elevationChipText(
                quality(
                    source = CanonicalElevationSource.GPS_INTERPOLATED,
                    elevationUncertaintyM = 8.2,
                ),
            ),
        )
        assertEquals(
            "Elevation: GPS net",
            elevationChipText(
                quality(
                    source = CanonicalElevationSource.GPS_INTERPOLATED,
                    elevationUncertaintyM = null,
                ),
            ),
        )
        assertEquals(
            "No elevation",
            elevationChipText(quality(source = CanonicalElevationSource.NONE)),
        )
    }

    @Test fun `GPS elevation labels descent as net drop`() {
        assertEquals(
            "Net drop",
            descentMetricLabel(quality(source = CanonicalElevationSource.GPS_INTERPOLATED)),
        )
        assertEquals(
            "Descent",
            descentMetricLabel(quality(source = CanonicalElevationSource.BAROMETRIC)),
        )
        assertEquals("Descent", descentMetricLabel(null))
    }

    private fun quality(
        source: CanonicalElevationSource = CanonicalElevationSource.GPS_INTERPOLATED,
        medianAccuracyM: Double? = 4.0,
        gpsGapCount: Int = 0,
        elevationUncertaintyM: Double? = 6.0,
    ): CanonicalQuality = CanonicalQuality(
        elevationSource = source,
        baroSampleCount = 0,
        gpsFixCount = 100,
        gpsAcceptedCount = 95,
        medianAccuracyM = medianAccuracyM,
        p90AccuracyM = medianAccuracyM,
        gpsGapCount = gpsGapCount,
        longestGapS = if (gpsGapCount > 0) 12.0 else 0.0,
        elevationUncertaintyM = elevationUncertaintyM,
    )
}
