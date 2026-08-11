package com.nakvali.core.map

import android.graphics.PointF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.hypot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/** One coordinate of a segment or of the ride it was authored from. */
data class SegmentMapPoint(val lat: Double, val lon: Double)

enum class SegmentMapGate { START, FINISH }

enum class SegmentMapCameraTarget {
    SEGMENT,
    FULL_RIDE,

    /**
     * Close enough on one gate that a metre is a visible distance.
     *
     * Used while a handle is held: precision comes from scale, never from
     * slowing the finger down.
     */
    GATE_CLOSEUP,

    /** Back to wherever the rider had the camera before a close-up. */
    RESTORE,
}

/** A one-shot editor camera action; [token] lets the same target be requested again. */
data class SegmentMapCameraRequest(
    val target: SegmentMapCameraTarget,
    val token: Int,
    /** Subject of [SegmentMapCameraTarget.GATE_CLOSEUP]. */
    val point: SegmentMapPoint? = null,
    /** Camera to return to for [SegmentMapCameraTarget.RESTORE]. */
    val restore: SegmentMapCamera? = null,
)

/** Enough of the camera to put it back exactly where it was. */
data class SegmentMapCamera(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
)

/** Zoom for a gate close-up: roughly a 30 m wide view on a phone. */
private const val GATE_CLOSEUP_ZOOM = 19.5

/** Short enough to feel like a response to the hold, not a flight. */
private const val GATE_CLOSEUP_ANIMATION_MS = 220

/**
 * How far a gate may travel in one drag on the map.
 *
 * The map is where a gate is placed to the metre, so the marker follows the
 * finger only within reach of where it started. Moving the gate to a different
 * part of the ride is the trimmer's job, where the whole track is visible.
 */
private const val GATE_DRAG_RADIUS_M = 10.0

/** Keeps [target] within [radiusM] of [anchor], along the same bearing. */
private fun clampToRadius(
    anchor: SegmentMapPoint,
    target: SegmentMapPoint,
    radiusM: Double,
): SegmentMapPoint {
    val metresPerDegreeLat = 111_320.0
    val metresPerDegreeLon = metresPerDegreeLat * cos(Math.toRadians(anchor.lat)).coerceAtLeast(0.01)
    val north = (target.lat - anchor.lat) * metresPerDegreeLat
    val east = (target.lon - anchor.lon) * metresPerDegreeLon
    val distance = hypot(north, east)
    if (distance <= radiusM || distance == 0.0) return target
    val scale = radiusM / distance
    return SegmentMapPoint(
        lat = anchor.lat + north * scale / metresPerDegreeLat,
        lon = anchor.lon + east * scale / metresPerDegreeLon,
    )
}

private const val CONTEXT_SOURCE_ID = "nakvali-segment-context"
private const val CONTEXT_LAYER_ID = "nakvali-segment-context-line"
private const val SEGMENT_SOURCE_ID = "nakvali-segment"
private const val SEGMENT_CASING_LAYER_ID = "nakvali-segment-casing"
private const val SEGMENT_LAYER_ID = "nakvali-segment-line"
private const val ENDPOINT_SOURCE_ID = "nakvali-segment-endpoints"
private const val ENDPOINT_LAYER_ID = "nakvali-segment-endpoint-circles"
private const val ENDPOINT_ROLE = "role"
private const val ROLE_START = "start"
private const val ROLE_FINISH = "finish"
private const val SINGLE_POINT_ZOOM = 16.0

private val EMPTY_FEATURES = FeatureCollection.fromFeatures(emptyList())

/**
 * Renders one directed segment (or a pending selection) over the ride it came
 * from.
 *
 * [sections] is drawn as thin context and must already be split at manual
 * pause boundaries and recording gaps — this composable never joins two
 * sections, because a straight line across a pause is exactly the false
 * geometry the recorder is careful to avoid. [segment] is the ordered
 * start-to-finish polyline; its first and last coordinates get start/finish
 * markers.
 */
@Composable
fun SegmentMap(
    sections: List<List<SegmentMapPoint>>,
    segment: List<SegmentMapPoint>,
    modifier: Modifier = Modifier,
    /** Optional authored difficulty color; editor previews keep the brand default. */
    segmentColor: Int? = null,
    segmentCasingColor: Int? = null,
    focusOnSegment: Boolean = true,
    cameraRequest: SegmentMapCameraRequest? = null,
    trackedPoint: SegmentMapPoint? = null,
    trackingBottomInset: Dp = 0.dp,
    onZoomChanged: (Double) -> Unit = {},
    onCameraSettled: (SegmentMapCamera) -> Unit = {},
    startGate: SegmentMapPoint? = segment.firstOrNull(),
    finishGate: SegmentMapPoint? = segment.lastOrNull(),
    onGateDrag: ((SegmentMapGate, SegmentMapPoint) -> Unit)? = null,
    onGateDragStateChanged: (SegmentMapGate?) -> Unit = {},
) {
    val mapView = rememberNakvaliMapView()
    val palette = rememberNakvaliMapPalette()
    val edgeMarginPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val cameraPaddingPx = with(LocalDensity.current) { 32.dp.roundToPx() }
    val gateHitRadiusPx = with(LocalDensity.current) { 32.dp.toPx() }
    val trackingTopInsetPx = with(LocalDensity.current) { 96.dp.roundToPx() }
    val trackingBottomInsetPx = with(LocalDensity.current) {
        (trackingBottomInset + 32.dp).roundToPx()
    }
    val currentSections = rememberUpdatedState(sections)
    val currentSegment = rememberUpdatedState(segment)
    val currentFocus = rememberUpdatedState(focusOnSegment)
    val currentOnZoomChanged = rememberUpdatedState(onZoomChanged)
    val currentOnCameraSettled = rememberUpdatedState(onCameraSettled)
    val currentStartGate = rememberUpdatedState(startGate)
    val currentFinishGate = rememberUpdatedState(finishGate)
    val currentOnGateDrag = rememberUpdatedState(onGateDrag)
    val currentOnGateDragStateChanged = rememberUpdatedState(onGateDragStateChanged)
    AndroidView(factory = { mapView }, modifier = modifier)

    DisposableEffect(mapView, gateHitRadiusPx) {
        var boundMap: MapLibreMap? = null
        var draggedGate: SegmentMapGate? = null
        var dragAnchor: SegmentMapPoint? = null
        val listener = MapLibreMap.OnCameraIdleListener {
            boundMap?.let { map ->
                currentOnZoomChanged.value(map.cameraPosition.zoom)
                map.cameraPosition.target?.let { target ->
                    currentOnCameraSettled.value(
                        SegmentMapCamera(target.latitude, target.longitude, map.cameraPosition.zoom),
                    )
                }
            }
        }
        mapView.getMapAsync { map ->
            boundMap = map
            map.addOnCameraIdleListener(listener)
            currentOnZoomChanged.value(map.cameraPosition.zoom)
        }
        mapView.setOnTouchListener { view, event ->
            val map = boundMap
            if (map == null || currentOnGateDrag.value == null) {
                false
            } else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val touch = PointF(event.x, event.y)
                        val candidates = listOfNotNull(
                            currentStartGate.value?.let { SegmentMapGate.START to it },
                            currentFinishGate.value?.let { SegmentMapGate.FINISH to it },
                        )
                        draggedGate = candidates.minByOrNull { (_, point) ->
                            val screen = map.projection.toScreenLocation(LatLng(point.lat, point.lon))
                            val dx = screen.x - touch.x
                            val dy = screen.y - touch.y
                            dx * dx + dy * dy
                        }?.takeIf { (_, point) ->
                            val screen = map.projection.toScreenLocation(LatLng(point.lat, point.lon))
                            val dx = screen.x - touch.x
                            val dy = screen.y - touch.y
                            dx * dx + dy * dy <= gateHitRadiusPx * gateHitRadiusPx
                        }?.first
                        draggedGate?.let { gate ->
                            dragAnchor = when (gate) {
                                SegmentMapGate.START -> currentStartGate.value
                                SegmentMapGate.FINISH -> currentFinishGate.value
                            }
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            currentOnGateDragStateChanged.value(gate)
                        }
                        draggedGate != null
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val gate = draggedGate ?: return@setOnTouchListener false
                        val coordinate = map.projection.fromScreenLocation(PointF(event.x, event.y))
                        // The marker is a nudge tool, not a teleport. Beyond a
                        // few metres the rider means a different part of the
                        // ride, and that belongs to the trimmer where the whole
                        // track is visible; here the gate simply stops
                        // following once it is far enough from where the drag
                        // began.
                        val moved = dragAnchor?.let { anchor ->
                            clampToRadius(
                                anchor = anchor,
                                target = SegmentMapPoint(coordinate.latitude, coordinate.longitude),
                                radiusM = GATE_DRAG_RADIUS_M,
                            )
                        } ?: SegmentMapPoint(coordinate.latitude, coordinate.longitude)
                        currentOnGateDrag.value?.invoke(gate, moved)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = draggedGate != null
                        if (wasDragging) {
                            draggedGate = null
                            dragAnchor = null
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            currentOnGateDragStateChanged.value(null)
                        }
                        wasDragging
                    }
                    else -> draggedGate != null
                }
            }
        }
        onDispose {
            boundMap?.removeOnCameraIdleListener(listener)
            mapView.setOnTouchListener(null)
        }
    }

    LaunchedEffect(mapView, palette, segmentColor, segmentCasingColor) {
        mapView.getMapAsync { map ->
            map.configureNakvaliMapChrome(palette, edgeMarginPx, edgeMarginPx)
            mapView.setNakvaliMapStyle(map, palette) { style ->
                val activeSegmentColor = segmentColor ?: palette.primary
                val activeCasingColor = segmentCasingColor ?: palette.roadCasing
                style.addSource(
                    GeoJsonSource(
                        CONTEXT_SOURCE_ID,
                        GeoJsonOptions().withLineMetrics(false).withTolerance(0f),
                    ),
                )
                style.addLayer(
                    LineLayer(CONTEXT_LAYER_ID, CONTEXT_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(palette.label),
                        PropertyFactory.lineWidth(2f),
                        PropertyFactory.lineOpacity(0.34f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addSource(
                    GeoJsonSource(
                        SEGMENT_SOURCE_ID,
                        GeoJsonOptions().withLineMetrics(false).withTolerance(0f),
                    ),
                )
                style.addLayer(
                    LineLayer(SEGMENT_CASING_LAYER_ID, SEGMENT_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(activeCasingColor),
                        PropertyFactory.lineWidth(9f),
                        PropertyFactory.lineOpacity(0.9f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer(SEGMENT_LAYER_ID, SEGMENT_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(activeSegmentColor),
                        PropertyFactory.lineWidth(5.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addSource(GeoJsonSource(ENDPOINT_SOURCE_ID))
                style.addLayer(
                    CircleLayer(ENDPOINT_LAYER_ID, ENDPOINT_SOURCE_ID).withProperties(
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get(ENDPOINT_ROLE),
                                Expression.color(activeSegmentColor),
                                Expression.stop(ROLE_START, Expression.color(palette.vegetationStrong)),
                                Expression.stop(ROLE_FINISH, Expression.color(activeSegmentColor)),
                            ),
                        ),
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleStrokeColor(activeCasingColor),
                        PropertyFactory.circleStrokeWidth(2.5f),
                    ),
                )
                render(
                    style,
                    currentSections.value,
                    currentSegment.value,
                    currentStartGate.value,
                    currentFinishGate.value,
                )
                val initialFocus = currentSegment.value
                    .takeIf { currentFocus.value && it.isNotEmpty() }
                    ?: currentSections.value.flatten().ifEmpty { currentSegment.value }
                fitCamera(map, initialFocus, cameraPaddingPx, trackingBottomInsetPx)
            }
        }
    }

    // Geometry updates while the rider drags a selection must not reset the
    // camera. In particular, a manually zoomed start/finish view stays exactly
    // where the rider left it.
    LaunchedEffect(mapView, sections, segment, startGate, finishGate) {
        mapView.getMapAsync { map ->
            map.style?.let { style -> render(style, sections, segment, startGate, finishGate) }
        }
    }

    // Explicit range actions own reframing in the editor. Geometry updates do
    // not appear in this key, so dragging never destroys a manual map zoom.
    LaunchedEffect(mapView, cameraRequest) {
        val request = cameraRequest ?: return@LaunchedEffect
        // A close-up and a restore move the camera directly; the fitting
        // targets frame a set of points.
        when (request.target) {
            SegmentMapCameraTarget.GATE_CLOSEUP -> {
                val point = request.point ?: return@LaunchedEffect
                mapView.getMapAsync { map ->
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(point.lat, point.lon),
                            GATE_CLOSEUP_ZOOM,
                        ),
                        GATE_CLOSEUP_ANIMATION_MS,
                    )
                }
                return@LaunchedEffect
            }
            SegmentMapCameraTarget.RESTORE -> {
                val camera = request.restore ?: return@LaunchedEffect
                mapView.getMapAsync { map ->
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(camera.lat, camera.lon),
                            camera.zoom,
                        ),
                        GATE_CLOSEUP_ANIMATION_MS,
                    )
                }
                return@LaunchedEffect
            }
            else -> Unit
        }
        val points = when (request.target) {
            SegmentMapCameraTarget.FULL_RIDE -> sections.flatten().ifEmpty { segment }
            else -> segment
        }
        mapView.getMapAsync { map ->
            if (map.style != null) {
                fitCamera(map, points, cameraPaddingPx, trackingBottomInsetPx)
            }
        }
    }

    // Preserve the rider's chosen zoom. While a gate is moving, pan only once
    // its marker approaches the obscured/edge area; a marker already in the
    // useful viewport causes no camera movement.
    LaunchedEffect(mapView, trackedPoint) {
        val point = trackedPoint ?: return@LaunchedEffect
        mapView.getMapAsync { map ->
            if (map.style == null || mapView.width <= 0 || mapView.height <= 0) return@getMapAsync
            val screen = map.projection.toScreenLocation(LatLng(point.lat, point.lon))
            val outsideSafeViewport =
                screen.x < cameraPaddingPx ||
                    screen.x > mapView.width - cameraPaddingPx ||
                    screen.y < trackingTopInsetPx ||
                    screen.y > mapView.height - trackingBottomInsetPx
            if (outsideSafeViewport) {
                map.moveCamera(
                    CameraUpdateFactory.newLatLng(LatLng(point.lat, point.lon)),
                )
            }
        }
    }
}

private fun render(
    style: Style,
    sections: List<List<SegmentMapPoint>>,
    segment: List<SegmentMapPoint>,
    startGate: SegmentMapPoint?,
    finishGate: SegmentMapPoint?,
) {
    style.getSourceAs<GeoJsonSource>(CONTEXT_SOURCE_ID)?.let { source ->
        val drawable = sections.filter { it.size >= 2 }
            .map { section -> section.map { Point.fromLngLat(it.lon, it.lat) } }
        if (drawable.isEmpty()) {
            source.setGeoJson(EMPTY_FEATURES)
        } else {
            source.setGeoJson(MultiLineString.fromLngLats(drawable))
        }
    }
    style.getSourceAs<GeoJsonSource>(SEGMENT_SOURCE_ID)?.let { source ->
        if (segment.size < 2) {
            source.setGeoJson(EMPTY_FEATURES)
        } else {
            source.setGeoJson(
                LineString.fromLngLats(segment.map { Point.fromLngLat(it.lon, it.lat) }),
            )
        }
    }
    style.getSourceAs<GeoJsonSource>(ENDPOINT_SOURCE_ID)?.let { source ->
        val endpoints = listOfNotNull(
            startGate?.let { endpointFeature(it, ROLE_START) },
            finishGate?.let { endpointFeature(it, ROLE_FINISH) },
        )
        source.setGeoJson(FeatureCollection.fromFeatures(endpoints))
    }

}

private fun endpointFeature(point: SegmentMapPoint, role: String): Feature =
    Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).also { feature ->
        feature.addStringProperty(ENDPOINT_ROLE, role)
    }

private fun fitCamera(
    map: MapLibreMap,
    points: List<SegmentMapPoint>,
    paddingPx: Int,
    bottomInsetPx: Int,
) {
    if (points.isEmpty()) return
    val distinct = points.mapTo(LinkedHashSet()) { it.lat to it.lon }
    if (distinct.size < 2) {
        val only = points.first()
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), SINGLE_POINT_ZOOM),
        )
        return
    }
    val bounds = LatLngBounds.Builder()
        .apply { points.forEach { include(LatLng(it.lat, it.lon)) } }
        .build()
    map.easeCamera(
        CameraUpdateFactory.newLatLngBounds(
            bounds,
            paddingPx,
            paddingPx,
            paddingPx,
            bottomInsetPx.coerceAtLeast(paddingPx),
        ),
        600,
    )
}
