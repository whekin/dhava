package com.nakvali.feature.activity

import com.nakvali.fusion.RideProfile
import com.nakvali.fusion.RideProfilePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityElevationProfileTest {

    @Test fun `scrubber chooses the nearest Rust profile sample by ridden distance`() {
        val profile = profile(
            point(position = 0.0, distanceM = 0.0),
            point(position = 20.0, distanceM = 105.0),
            point(position = 50.0, distanceM = 290.0),
        )

        assertEquals(0.0, profile.closestPointToDistance(20.0)?.position ?: -1.0, 0.0)
        assertEquals(20.0, profile.closestPointToDistance(120.0)?.position ?: -1.0, 0.0)
        assertEquals(50.0, profile.closestPointToDistance(260.0)?.position ?: -1.0, 0.0)
    }

    @Test fun `empty profile has no inspectable point`() {
        assertNull(RideProfile(emptyList(), 0.0, null, null).closestPointToDistance(0.0))
    }

    private fun profile(vararg points: RideProfilePoint) = RideProfile(
        points = points.toList(),
        lengthM = points.lastOrNull()?.distanceM ?: 0.0,
        minAltitudeM = 700.0,
        maxAltitudeM = 900.0,
    )

    private fun point(position: Double, distanceM: Double) = RideProfilePoint(
        position = position,
        distanceM = distanceM,
        altitudeM = 900.0 - distanceM / 2.0,
        gradientPercent = -8.0,
        sectionId = 0,
        continues = position > 0.0,
    )
}
