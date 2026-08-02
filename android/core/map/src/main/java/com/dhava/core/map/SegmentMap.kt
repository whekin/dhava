package com.dhava.core.map

import android.graphics.PointF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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

enum class SegmentMapCameraTarget { SEGMENT, FULL_RIDE }

/** A one-shot editor camera action; [token] lets the same target be requested again. */
data class SegmentMapCameraRequest(
    val target: SegmentMapCameraTarget,
    val token: Int,
)

private const val CONTEXT_SOURCE_ID = "dhava-segment-context"
private const val CONTEXT_LAYER_ID = "dhava-segment-context-line"
private const val SEGMENT_SOURCE_ID = "dhava-segment"
private const val SEGMENT_CASING_LAYER_ID = "dhava-segment-casing"
private const val SEGMENT_LAYER_ID = "dhava-segment-line"
private const val ENDPOINT_SOURCE_ID = "dhava-segment-endpoints"
private const val ENDPOINT_LAYER_ID = "dhava-segment-endpoint-circles"
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
    focusOnSegment: Boolean = true,
    cameraRequest: SegmentMapCameraRequest? = null,
    trackedPoint: SegmentMapPoint? = null,
    trackingBottomInset: Dp = 0.dp,
    onZoomChanged: (Double) -> Unit = {},
    startGate: SegmentMapPoint? = segment.firstOrNull(),
    finishGate: SegmentMapPoint? = segment.lastOrNull(),
    onGateDrag: ((SegmentMapGate, SegmentMapPoint) -> Unit)? = null,
    onGateDragStateChanged: (SegmentMapGate?) -> Unit = {},
) {
    val mapView = rememberDhavaMapView()
    val palette = rememberDhavaMapPalette()
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
    val currentStartGate = rememberUpdatedState(startGate)
    val currentFinishGate = rememberUpdatedState(finishGate)
    val currentOnGateDrag = rememberUpdatedState(onGateDrag)
    val currentOnGateDragStateChanged = rememberUpdatedState(onGateDragStateChanged)
    AndroidView(factory = { mapView }, modifier = modifier)

    DisposableEffect(mapView, gateHitRadiusPx) {
        var boundMap: MapLibreMap? = null
        var draggedGate: SegmentMapGate? = null
        val listener = MapLibreMap.OnCameraIdleListener {
            boundMap?.let { currentOnZoomChanged.value(it.cameraPosition.zoom) }
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
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            currentOnGateDragStateChanged.value(gate)
                        }
                        draggedGate != null
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val gate = draggedGate ?: return@setOnTouchListener false
                        val coordinate = map.projection.fromScreenLocation(PointF(event.x, event.y))
                        currentOnGateDrag.value?.invoke(
                            gate,
                            SegmentMapPoint(coordinate.latitude, coordinate.longitude),
                        )
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = draggedGate != null
                        if (wasDragging) {
                            draggedGate = null
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

    LaunchedEffect(mapView, palette) {
        mapView.getMapAsync { map ->
            map.configureDhavaMapChrome(palette, edgeMarginPx, edgeMarginPx)
            mapView.setDhavaMapStyle(map, palette) { style ->
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
                        PropertyFactory.lineColor(palette.roadCasing),
                        PropertyFactory.lineWidth(9f),
                        PropertyFactory.lineOpacity(0.9f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer(SEGMENT_LAYER_ID, SEGMENT_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(palette.primary),
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
                                Expression.color(palette.primary),
                                Expression.stop(ROLE_START, Expression.color(palette.vegetationStrong)),
                                Expression.stop(ROLE_FINISH, Expression.color(palette.primary)),
                            ),
                        ),
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleStrokeColor(palette.roadCasing),
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
        val points = when (request.target) {
            SegmentMapCameraTarget.SEGMENT -> segment
            SegmentMapCameraTarget.FULL_RIDE -> sections.flatten().ifEmpty { segment }
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
