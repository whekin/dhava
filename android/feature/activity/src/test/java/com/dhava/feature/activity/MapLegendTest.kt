package com.dhava.feature.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLegendTest {

    @Test
    fun `GPS mode exposes only GPS accuracy`() {
        assertEquals(
            listOf(MapLegendSection.GpsAccuracy),
            mapLegendSections(
                mode = TrackMode.Gps,
                hasActivityStates = true,
                hasAccuracy = true,
            ),
        )
    }

    @Test
    fun `Fusion mode exposes only activity state`() {
        assertEquals(
            listOf(MapLegendSection.ActivityState),
            mapLegendSections(
                mode = TrackMode.Fusion,
                hasActivityStates = true,
                hasAccuracy = true,
            ),
        )
    }

    @Test
    fun `Compare mode exposes activity state before GPS accuracy`() {
        assertEquals(
            listOf(
                MapLegendSection.ActivityState,
                MapLegendSection.GpsAccuracy,
            ),
            mapLegendSections(
                mode = TrackMode.Compare,
                hasActivityStates = true,
                hasAccuracy = true,
            ),
        )
    }

    @Test
    fun `missing data omits its legend section`() {
        assertEquals(
            listOf(MapLegendSection.GpsAccuracy),
            mapLegendSections(
                mode = TrackMode.Compare,
                hasActivityStates = false,
                hasAccuracy = true,
            ),
        )
        assertEquals(
            listOf(MapLegendSection.ActivityState),
            mapLegendSections(
                mode = TrackMode.Compare,
                hasActivityStates = true,
                hasAccuracy = false,
            ),
        )
        assertTrue(
            mapLegendSections(
                mode = TrackMode.Gps,
                hasActivityStates = true,
                hasAccuracy = false,
            ).isEmpty(),
        )
        assertTrue(
            mapLegendSections(
                mode = TrackMode.Fusion,
                hasActivityStates = false,
                hasAccuracy = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `no data returns no sections in every mode`() {
        TrackMode.entries.forEach { mode ->
            assertTrue(
                "$mode should have no legend sections",
                mapLegendSections(
                    mode = mode,
                    hasActivityStates = false,
                    hasAccuracy = false,
                ).isEmpty(),
            )
        }
    }
}
