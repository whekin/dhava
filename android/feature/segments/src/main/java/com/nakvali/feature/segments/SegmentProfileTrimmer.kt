package com.nakvali.feature.segments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One sample of the ride's elevation story, as the chart needs it. */
data class ProfileSample(
    /** Continuous position in the finalized track this sample came from. */
    val position: Double,
    val distanceM: Double,
    val altitudeM: Double?,
    val gradientPercent: Double?,
    /** False when a manual pause or a recording gap precedes this sample. */
    val continues: Boolean,
)

data class RideProfileUi(
    val samples: List<ProfileSample>,
    val lengthM: Double,
    val minAltitudeM: Double?,
    val maxAltitudeM: Double?,
    val lastPosition: Double,
) {
    val drawable: Boolean
        get() = samples.size >= 2 && minAltitudeM != null && maxAltitudeM != null
}

/** A descent the editor offers, plus the segment it would duplicate. */
data class CandidateSpan(
    val startPosition: Double,
    val endPosition: Double,
    val lengthM: Double,
    val descentM: Double?,
    val gradientPercent: Double?,
    /** Name of an existing segment this candidate duplicates, when it does. */
    val existingSegmentName: String?,
)

/** The visible window of the chart, in track positions. */
internal data class ProfileDomain(val start: Double, val end: Double) {
    val span: Double get() = (end - start).coerceAtLeast(MIN_DOMAIN_SPAN)
}

/** Minimum separation between the gates, in meters of ridden trail. */
internal const val MIN_SELECTION_GAP_M = 25.0
private const val MIN_DOMAIN_SPAN = 1.0
private const val DOMAIN_FOCUS_PADDING_FRACTION = 0.12
private val HandleTouchSlop = 28.dp
private val CandidateRibbonHeight = 18.dp

/**
 * The instrument for trimming a segment: the ride's elevation profile with the
 * two gates living directly on it.
 *
 * This replaced a two-thumbed range slider. A generic slider forced two
 * problems that could not be fixed inside it: its thumbs coincide and become
 * individually ungrabbable, and it re-anchors its internal offset to whatever
 * value the caller feeds back, which silently squared any attempt to scale
 * finger movement for precision. Here the gates are dragged directly, precision
 * comes from narrowing the domain instead of scaling the finger, and the two
 * handles sit at different heights so they stay separable even when they share
 * an x. Above all, the rider can see that the selection actually goes down.
 */
@Composable
internal fun SegmentProfileTrimmer(
    profile: RideProfileUi,
    candidates: List<CandidateSpan>,
    startPosition: Double,
    endPosition: Double,
    domain: ProfileDomain,
    onSelectionChange: (Double, Double) -> Unit,
    onDomainChange: (ProfileDomain) -> Unit,
    onCandidatePicked: (CandidateSpan) -> Unit,
    onActiveHandleChange: (SelectionHandle?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
) {
    val density = LocalDensity.current
    val slopPx = with(density) { HandleTouchSlop.toPx() }
    val ribbonPx = with(density) { CandidateRibbonHeight.toPx() }
    val descendColor = MaterialTheme.colorScheme.primary
    val climbColor = MaterialTheme.colorScheme.tertiary
    val flatColor = MaterialTheme.colorScheme.outline
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val existingColor = MaterialTheme.colorScheme.onSurfaceVariant

    val current = rememberUpdatedState(
        TrimmerInput(profile, candidates, startPosition, endPosition, domain),
    )
    val onSelection = rememberUpdatedState(onSelectionChange)
    val onDomain = rememberUpdatedState(onDomainChange)
    val onCandidate = rememberUpdatedState(onCandidatePicked)
    val onActiveHandle = rememberUpdatedState(onActiveHandleChange)
    var viewportWidth by remember { mutableIntStateOf(0) }

    // Two fingers narrow the domain, one finger away from a handle pans it.
    // Precision is a property of the axis, never of a scaled finger delta.
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val input = current.value
        val width = viewportWidth.toFloat()
        if (width <= 0f) return@rememberTransformableState
        val chartDomain = input.domain
        val zoomed = (chartDomain.span / zoomChange.toDouble()).coerceIn(
            MIN_DOMAIN_SPAN,
            input.profile.lastPosition.coerceAtLeast(MIN_DOMAIN_SPAN),
        )
        val center = chartDomain.start + chartDomain.span / 2.0 -
            panChange.x / width * chartDomain.span
        onDomain.value(
            clampDomain(
                ProfileDomain(center - zoomed / 2.0, center + zoomed / 2.0),
                input.profile.lastPosition,
            ),
        )
    }

    // The axis gesture lives on the parent and the gate gesture on the topmost
    // child, so a touch is offered to the gates first and only falls through to
    // panning and zooming when it grabbed neither.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height + CandidateRibbonHeight)
            .onSizeChanged { viewportWidth = it.width }
            .transformable(state = transformState),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = profile.accessibilityText(startPosition, endPosition)
                },
        ) {
            val chartHeight = size.height - ribbonPx
            drawLine(
                color = guideColor,
                start = Offset(0f, chartHeight),
                end = Offset(size.width, chartHeight),
                strokeWidth = 1.dp.toPx(),
            )
            if (!profile.drawable) return@Canvas

            val minAltitude = profile.minAltitudeM ?: return@Canvas
            val maxAltitude = profile.maxAltitudeM ?: return@Canvas
            val altitudeRange = (maxAltitude - minAltitude).coerceAtLeast(1.0)
            val topInset = 10.dp.toPx()
            fun x(position: Double) = xForPosition(position, size.width, domain)
            fun y(altitude: Double) = (
                topInset + (maxAltitude - altitude) / altitudeRange * (chartHeight - topInset)
                ).toFloat()

            // Fill under the selection first, so the coloured line stays on top.
            val selected = profile.samples.filter {
                it.position in startPosition..endPosition && it.altitudeM != null
            }
            if (selected.size >= 2) {
                val fill = Path().apply {
                    moveTo(x(selected.first().position), chartHeight)
                    selected.forEach { sample ->
                        lineTo(x(sample.position), y(sample.altitudeM ?: return@forEach))
                    }
                    lineTo(x(selected.last().position), chartHeight)
                    close()
                }
                drawPath(fill, color = descendColor.copy(alpha = 0.18f))
            }

            profile.samples.forEachIndexed { index, sample ->
                if (index == 0) return@forEachIndexed
                val previous = profile.samples[index - 1]
                // Never draw across a manual pause or a recording gap: a
                // straight line there is exactly the geometry the recorder is
                // careful not to invent.
                if (!sample.continues) return@forEachIndexed
                val from = previous.altitudeM ?: return@forEachIndexed
                val to = sample.altitudeM ?: return@forEachIndexed
                val inside = sample.position in startPosition..endPosition
                val gradient = sample.gradientPercent ?: 0.0
                val color = when {
                    gradient <= -1.0 -> descendColor
                    gradient >= 1.0 -> climbColor
                    else -> flatColor
                }
                drawLine(
                    color = if (inside) color else color.copy(alpha = 0.4f),
                    start = Offset(x(previous.position), y(from)),
                    end = Offset(x(sample.position), y(to)),
                    strokeWidth = if (inside) 3.5.dp.toPx() else 2.dp.toPx(),
                )
            }

            candidates.forEach { candidate ->
                val left = x(candidate.startPosition)
                val right = x(candidate.endPosition)
                if (right < 0f || left > size.width) return@forEach
                val already = candidate.existingSegmentName != null
                drawRoundedBar(
                    left = max(left, 0f),
                    right = min(right, size.width),
                    top = chartHeight + 5.dp.toPx(),
                    height = ribbonPx - 10.dp.toPx(),
                    color = if (already) existingColor else descendColor,
                    filled = !already,
                    strokeWidth = 1.5.dp.toPx(),
                    cornerRadius = 3.dp.toPx(),
                )
            }

            drawHandle(
                x = x(startPosition),
                chartHeight = chartHeight,
                color = descendColor,
                atTop = true,
            )
            drawHandle(
                x = x(endPosition),
                chartHeight = chartHeight,
                color = descendColor,
                atTop = false,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val input = current.value
                        val width = size.width.toFloat()
                        val chartHeight = size.height - ribbonPx
                        if (width <= 0f) return@awaitEachGesture

                        if (down.position.y > chartHeight) {
                            val picked = input.candidates.firstOrNull { candidate ->
                                down.position.x >= xForPosition(
                                    candidate.startPosition,
                                    width,
                                    input.domain,
                                ) - slopPx / 2f &&
                                    down.position.x <= xForPosition(
                                        candidate.endPosition,
                                        width,
                                        input.domain,
                                    ) + slopPx / 2f
                            }
                            if (picked != null) {
                                down.consume()
                                onCandidate.value(picked)
                            }
                            return@awaitEachGesture
                        }

                        val handle = grabbedHandle(
                            touch = down.position,
                            width = width,
                            chartHeight = chartHeight,
                            domain = input.domain,
                            startPosition = input.startPosition,
                            endPosition = input.endPosition,
                            slopPx = slopPx,
                        ) ?: return@awaitEachGesture

                        down.consume()
                        onActiveHandle.value(handle)
                        var position = when (handle) {
                            SelectionHandle.START -> input.startPosition
                            SelectionHandle.FINISH -> input.endPosition
                        }
                        drag(down.id) { change: PointerInputChange ->
                            // Read the movement before consuming it:
                            // positionChange() reports zero once the change is
                            // consumed, which silently froze the gate.
                            val movedX = change.positionChange().x
                            change.consume()
                            val live = current.value
                            position += movedX / width * live.domain.span
                            val (start, end) = applyHandle(
                                handle = handle,
                                proposed = position,
                                profile = live.profile,
                                startPosition = live.startPosition,
                                endPosition = live.endPosition,
                            )
                            onSelection.value(start, end)
                        }
                        onActiveHandle.value(null)
                    }
                },
        )
    }
}

private data class TrimmerInput(
    val profile: RideProfileUi,
    val candidates: List<CandidateSpan>,
    val startPosition: Double,
    val endPosition: Double,
    val domain: ProfileDomain,
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedBar(
    left: Float,
    right: Float,
    top: Float,
    height: Float,
    color: Color,
    filled: Boolean,
    strokeWidth: Float,
    cornerRadius: Float,
) {
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = left,
                top = top,
                right = max(right, left + cornerRadius * 2f),
                bottom = top + height,
                radiusX = cornerRadius,
                radiusY = cornerRadius,
            ),
        )
    }
    if (filled) {
        drawPath(path, color = color.copy(alpha = 0.55f))
    } else {
        drawPath(path, color = color.copy(alpha = 0.7f), style = Stroke(width = strokeWidth))
    }
}

/**
 * A gate marker. Start and finish sit at opposite ends of the vertical line so
 * that two gates sharing an x remain individually grabbable — the failure that
 * made the previous range slider impossible to pull apart.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(
    x: Float,
    chartHeight: Float,
    color: Color,
    atTop: Boolean,
) {
    val radius = 7.dp.toPx()
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, chartHeight),
        strokeWidth = 2.dp.toPx(),
    )
    drawCircle(
        color = color,
        radius = radius,
        center = Offset(x, if (atTop) radius else chartHeight - radius),
    )
}

internal fun xForPosition(position: Double, width: Float, domain: ProfileDomain): Float =
    ((position - domain.start) / domain.span * width).toFloat()

internal fun positionForX(x: Float, width: Float, domain: ProfileDomain): Double =
    if (width <= 0f) domain.start else domain.start + x / width * domain.span

/**
 * Which gate a touch grabbed, or null when the touch belongs to the axis.
 *
 * Ties are broken vertically rather than by proximity: with both gates at the
 * same x, the upper half of the chart grabs the start and the lower half the
 * finish, so a collapsed selection can always be pulled apart.
 */
internal fun grabbedHandle(
    touch: Offset,
    width: Float,
    chartHeight: Float,
    domain: ProfileDomain,
    startPosition: Double,
    endPosition: Double,
    slopPx: Float,
): SelectionHandle? {
    val startX = xForPosition(startPosition, width, domain)
    val endX = xForPosition(endPosition, width, domain)
    val startDistance = abs(touch.x - startX)
    val endDistance = abs(touch.x - endX)
    if (min(startDistance, endDistance) > slopPx) return null
    val ambiguous = abs(startDistance - endDistance) <= slopPx / 2f
    return when {
        ambiguous -> if (touch.y <= chartHeight / 2f) {
            SelectionHandle.START
        } else {
            SelectionHandle.FINISH
        }
        startDistance <= endDistance -> SelectionHandle.START
        else -> SelectionHandle.FINISH
    }
}

/**
 * Moves one gate, keeping the two at least [MIN_SELECTION_GAP_M] of ridden
 * trail apart.
 *
 * The gap is expressed in meters, not in track positions: at 5 Hz a fixed
 * number of samples means one distance while climbing and a completely
 * different one at speed, and it was a position-based gap of a thousandth of a
 * sample that let the old handles collapse into each other.
 */
internal fun applyHandle(
    handle: SelectionHandle,
    proposed: Double,
    profile: RideProfileUi,
    startPosition: Double,
    endPosition: Double,
): Pair<Double, Double> {
    val last = profile.lastPosition
    return when (handle) {
        SelectionHandle.START -> {
            val ceiling = profile.positionAtDistance(
                profile.distanceAt(endPosition) - MIN_SELECTION_GAP_M,
            )
            val start = proposed.coerceIn(0.0, max(0.0, min(ceiling, endPosition)))
            start to endPosition
        }

        SelectionHandle.FINISH -> {
            val floor = profile.positionAtDistance(
                profile.distanceAt(startPosition) + MIN_SELECTION_GAP_M,
            )
            val end = proposed.coerceIn(min(max(floor, startPosition), last), last)
            startPosition to end
        }
    }
}

/** Distance ridden up to [position], interpolated between chart samples. */
internal fun RideProfileUi.distanceAt(position: Double): Double {
    if (samples.isEmpty()) return 0.0
    val index = samples.indexOfFirst { it.position >= position }
    if (index <= 0) return samples.first().distanceM
    val previous = samples[index - 1]
    val next = samples[index]
    val span = next.position - previous.position
    if (span <= 0.0) return next.distanceM
    val fraction = (position - previous.position) / span
    return previous.distanceM + (next.distanceM - previous.distanceM) * fraction
}

/** The inverse of [distanceAt], clamped to the ride. */
internal fun RideProfileUi.positionAtDistance(distanceM: Double): Double {
    if (samples.isEmpty()) return 0.0
    val index = samples.indexOfFirst { it.distanceM >= distanceM }
    if (index <= 0) return samples.first().position
    val previous = samples[index - 1]
    val next = samples[index]
    val span = next.distanceM - previous.distanceM
    if (span <= 0.0) return next.position
    val fraction = (distanceM - previous.distanceM) / span
    return previous.position + (next.position - previous.position) * fraction
}

/** A window around the selection, with a margin for grabbing outside it. */
internal fun focusedDomain(
    startPosition: Double,
    endPosition: Double,
    lastPosition: Double,
): ProfileDomain {
    if (lastPosition <= 0.0) return ProfileDomain(0.0, MIN_DOMAIN_SPAN)
    val span = (endPosition - startPosition).coerceAtLeast(MIN_DOMAIN_SPAN)
    val padding = span * DOMAIN_FOCUS_PADDING_FRACTION
    return clampDomain(
        ProfileDomain(startPosition - padding, endPosition + padding),
        lastPosition,
    )
}

internal fun clampDomain(domain: ProfileDomain, lastPosition: Double): ProfileDomain {
    val limit = lastPosition.coerceAtLeast(MIN_DOMAIN_SPAN)
    val span = domain.span.coerceAtMost(limit)
    val start = domain.start.coerceIn(0.0, limit - span)
    return ProfileDomain(start, start + span)
}

private fun RideProfileUi.accessibilityText(
    startPosition: Double,
    endPosition: Double,
): String = buildString {
    append("Elevation profile of the ride. Selection from ")
    append(SegmentFormat.length(distanceAt(startPosition)))
    append(" to ")
    append(SegmentFormat.length(distanceAt(endPosition)))
    append(" of ")
    append(SegmentFormat.length(lengthM))
    append(". Drag the start marker at the top or the finish marker at the bottom.")
}
