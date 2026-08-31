package com.nakvali.feature.record

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPowerSavingGuardTest {

    @Test
    fun `unchanged location policy allows screen-off recording`() {
        assertFalse(blocksReliableScreenOffLocation(PowerManager.LOCATION_MODE_NO_CHANGE))
    }

    @Test
    fun `every location-changing battery saver policy blocks recording start`() {
        listOf(
            PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
            PowerManager.LOCATION_MODE_FOREGROUND_ONLY,
            PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
        ).forEach { mode ->
            assertTrue("mode=$mode", blocksReliableScreenOffLocation(mode))
        }
    }

    @Test
    fun `configured power saving stays blocked while charging masks active location mode`() {
        assertTrue(
            blocksReliableScreenOffLocation(
                locationPowerSaveMode = PowerManager.LOCATION_MODE_NO_CHANGE,
                powerSavingConfigured = true,
            ),
        )
    }
}
