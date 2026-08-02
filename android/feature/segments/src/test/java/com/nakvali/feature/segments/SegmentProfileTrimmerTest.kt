package com.nakvali.feature.segments

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentProfileTrimmerTest {

    /** A 1 000-point ride, one meter and one sample apart, descending steadily. */
    private val profile = RideProfileUi(
        samples = (0..1_000).map { index ->
            ProfileSample(
                position = index.toDouble(),
                distanceM = index.toDouble(),
                altitudeM = 1_000.0 - index * 0.1,
                gradientPercent = -10.0,
                continues = index > 0,
            )
        },
        lengthM = 1_000.0,
        minAltitudeM = 900.0,
        maxAltitudeM = 1_000.0,
        lastPosition = 1_000.0,
    )

    @Test
    fun `positions and pixels round trip through the domain`() {
        val domain = ProfileDomain(200.0, 400.0)
        assertEquals(0f, xForPosition(200.0, 1_000f, domain))
        assertEquals(1_000f, xForPosition(400.0, 1_000f, domain))
        assertEquals(300.0, positionForX(500f, 1_000f, domain), 1e-9)
    }

    @Test
    fun `the focused domain surrounds the selection without leaving the ride`() {
        val focused = focusedDomain(400.0, 600.0, 1_000.0)
        assertTrue(focused.start < 400.0)
        assertTrue(focused.end > 600.0)

        val atStart = focusedDomain(0.0, 200.0, 1_000.0)
        assertEquals(0.0, atStart.start, 1e-9)
        val atEnd = focusedDomain(800.0, 1_000.0, 1_000.0)
        assertEquals(1_000.0, atEnd.end, 1e-9)
    }

    @Test
    fun `zooming past the ride is clamped instead of scrolling into nothing`() {
        val clamped = clampDomain(ProfileDomain(-500.0, 2_000.0), 1_000.0)
        assertEquals(0.0, clamped.start, 1e-9)
        assertEquals(1_000.0, clamped.end, 1e-9)
    }

    @Test
    fun `the gates keep a real distance apart instead of collapsing`() {
        // Dragging the start onto the finish must stop a full gap short of it.
        val (start, end) = applyHandle(
            handle = SelectionHandle.START,
            proposed = 700.0,
            profile = profile,
            startPosition = 400.0,
            endPosition = 600.0,
        )
        assertEquals(600.0, end, 1e-9)
        assertEquals(600.0 - MIN_SELECTION_GAP_M, start, 1e-6)

        val (start2, end2) = applyHandle(
            handle = SelectionHandle.FINISH,
            proposed = 10.0,
            profile = profile,
            startPosition = 400.0,
            endPosition = 600.0,
        )
        assertEquals(400.0, start2, 1e-9)
        assertEquals(400.0 + MIN_SELECTION_GAP_M, end2, 1e-6)
    }

    @Test
    fun `a gate never leaves the ride`() {
        val (start, _) = applyHandle(
            handle = SelectionHandle.START,
            proposed = -50.0,
            profile = profile,
            startPosition = 400.0,
            endPosition = 600.0,
        )
        assertEquals(0.0, start, 1e-9)

        val (_, end) = applyHandle(
            handle = SelectionHandle.FINISH,
            proposed = 5_000.0,
            profile = profile,
            startPosition = 400.0,
            endPosition = 600.0,
        )
        assertEquals(1_000.0, end, 1e-9)
    }

    @Test
    fun `coincident gates are told apart by where the chart was touched`() {
        val domain = ProfileDomain(0.0, 1_000.0)
        val chartHeight = 400f
        val x = xForPosition(500.0, 1_000f, domain)

        assertEquals(
            SelectionHandle.START,
            grabbedHandle(
                touch = Offset(x, 20f),
                width = 1_000f,
                chartHeight = chartHeight,
                domain = domain,
                startPosition = 500.0,
                endPosition = 500.0,
                slopPx = 40f,
            ),
        )
        assertEquals(
            SelectionHandle.FINISH,
            grabbedHandle(
                touch = Offset(x, chartHeight - 20f),
                width = 1_000f,
                chartHeight = chartHeight,
                domain = domain,
                startPosition = 500.0,
                endPosition = 500.0,
                slopPx = 40f,
            ),
        )
    }

    @Test
    fun `a touch away from both gates belongs to the axis`() {
        assertNull(
            grabbedHandle(
                touch = Offset(500f, 100f),
                width = 1_000f,
                chartHeight = 400f,
                domain = ProfileDomain(0.0, 1_000.0),
                startPosition = 100.0,
                endPosition = 900.0,
                slopPx = 40f,
            ),
        )
    }

    @Test
    fun `distance and position convert both ways`() {
        assertEquals(250.0, profile.distanceAt(250.0), 1e-6)
        assertEquals(250.0, profile.positionAtDistance(250.0), 1e-6)
    }
}
