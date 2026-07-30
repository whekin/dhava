package com.dhava.core.map

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
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
import org.maplibre.geojson.Point

/** One selectable segment on the library map. */
data class SegmentLibraryLine(
    val id: String,
    val points: List<SegmentMapPoint>,
)

/** The rider's own view of the library, retained across navigation. */
data class SegmentLibraryCamera(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val bearing: Double,
    val tilt: Double,
)

/** An explicit, rider-initiated camera move. */
sealed interface SegmentLibraryCameraAction {
    /** Frame every segment of the current area. */
    data object FitAll : SegmentLibraryCameraAction

    /** Frame one segment, e.g. after it was picked from the list. */
    data class FitSegment(val id: String) : SegmentLibraryCameraAction

    data class Center(val lat: Double, val lon: Double, val zoom: Double) :
        SegmentLibraryCameraAction
}

/** [token] lets the same action be requested again. */
data class SegmentLibraryCameraRequest(
    val action: SegmentLibraryCameraAction,
    val token: Int,
)

private const val LIBRARY_SOURCE_ID = "dhava-library-segments"
private const val LIBRARY_CASING_LAYER_ID = "dhava-library-segment-casing"
private const val LIBRARY_LINE_LAYER_ID = "dhava-library-segment-line"
private const val LIBRARY_SELECTED_CASING_LAYER_ID = "dhava-library-segment-selected-casing"
private const val LIBRARY_SELECTED_LAYER_ID = "dhava-library-segment-selected"
private const val LIBRARY_ENDPOINT_SOURCE_ID = "dhava-library-endpoints"
private const val LIBRARY_ENDPOINT_LAYER_ID = "dhava-library-endpoint-circles"
private const val SEGMENT_ID_PROPERTY = "segment_id"
private const val ENDPOINT_ROLE_PROPERTY = "role"
private const val LIBRARY_ROLE_START = "start"
private const val LIBRARY_ROLE_FINISH = "finish"
private const val CENTER_FALLBACK_ZOOM = 15.5

private val EMPTY_COLLECTION = FeatureCollection.fromFeatures(emptyList())

/**
 * The map behind the segment library.
 *
 * Every segment is drawn in the same muted weight: the map answers "what is
 * around here", not "which of these matters", so nothing competes for
 * attention until the rider picks one. Tapping a line selects it; opening the
 * segment stays a separate explicit action.
 *
 * The camera belongs to the rider. This composable only frames the segments on
 * a first visit (when [initialCamera] is null) or when an explicit
 * [cameraRequest] arrives — never because geometry or selection changed.
 */
@Composable
fun SegmentLibraryMap(
    lines: List<SegmentLibraryLine>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onCameraSettled: (SegmentLibraryCamera) -> Unit,
    modifier: Modifier = Modifier,
    initialCamera: SegmentLibraryCamera? = null,
    cameraRequest: SegmentLibraryCameraRequest? = null,
    bottomInset: Dp = 0.dp,
) {
    val mapView = rememberDhavaMapView()
    val palette = rememberDhavaMapPalette()
    val density = LocalDensity.current
    val edgeMarginPx = with(density) { 12.dp.roundToPx() }
    val cameraPaddingPx = with(density) { 32.dp.roundToPx() }
    val bottomInsetPx = with(density) { (bottomInset + 32.dp).roundToPx() }
    val touchSlopPx = with(density) { 22.dp.toPx() }
    val currentLines = rememberUpdatedState(lines)
    val currentSelectedId = rememberUpdatedState(selectedId)
    val currentInitialCamera = rememberUpdatedState(initialCamera)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentOnCameraSettled = rememberUpdatedState(onCameraSettled)

    AndroidView(factory = { mapView }, modifier = modifier)

    DisposableEffect(mapView) {
        var boundMap: MapLibreMap? = null
        // Reported on idle rather than on every frame: this value is persisted
        // as the rider's retained view, and a mid-gesture position is not it.
        val idleListener = MapLibreMap.OnCameraIdleListener {
            boundMap?.cameraPosition?.let { position ->
                position.target?.let { target ->
                    currentOnCameraSettled.value(
                        SegmentLibraryCamera(
                            lat = target.latitude,
                            lon = target.longitude,
                            zoom = position.zoom,
                            bearing = position.bearing,
                            tilt = position.tilt,
                        ),
                    )
                }
            }
        }
        val clickListener = MapLibreMap.OnMapClickListener { latLng ->
            val map = boundMap ?: return@OnMapClickListener false
            if (map.style == null) return@OnMapClickListener false
            val screen = map.projection.toScreenLocation(latLng)
            // A 5.5 px line is far thinner than a gloved fingertip, so hit
            // testing uses a touch-sized box around the tap instead of the
            // exact pixel.
            val box = RectF(
                screen.x - touchSlopPx,
                screen.y - touchSlopPx,
                screen.x + touchSlopPx,
                screen.y + touchSlopPx,
            )
            val hit = map.queryRenderedFeatures(
                box,
                LIBRARY_SELECTED_LAYER_ID,
                LIBRARY_LINE_LAYER_ID,
            ).firstNotNullOfOrNull { feature ->
                feature.getStringProperty(SEGMENT_ID_PROPERTY)
            }
            // Tapping empty map clears the selection: the rider gets the
            // unobstructed map back without hunting for a close action.
            currentOnSelect.value(hit)
            true
        }
        mapView.getMapAsync { map ->
            boundMap = map
            map.addOnCameraIdleListener(idleListener)
            map.addOnMapClickListener(clickListener)
        }
        onDispose {
            boundMap?.removeOnCameraIdleListener(idleListener)
            boundMap?.removeOnMapClickListener(clickListener)
        }
    }

    LaunchedEffect(mapView, palette) {
        mapView.getMapAsync { map ->
            map.configureDhavaMapChrome(palette, edgeMarginPx, edgeMarginPx)
            mapView.setDhavaMapStyle(map, palette) { style ->
                style.addSource(
                    GeoJsonSource(
                        LIBRARY_SOURCE_ID,
                        GeoJsonOptions().withLineMetrics(false).withTolerance(0f),
                    ),
                )
                // Muted means low emphasis, not neutral colour: a segment drawn
                // in the label colour is indistinguishable from the road it
                // follows. Same brand hue as the selection, dimmer, over a dark
                // casing that separates it from any basemap.
                style.addLayer(
                    LineLayer(LIBRARY_CASING_LAYER_ID, LIBRARY_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(palette.roadCasing),
                        PropertyFactory.lineWidth(6.5f),
                        PropertyFactory.lineOpacity(0.7f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer(LIBRARY_LINE_LAYER_ID, LIBRARY_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(palette.primary),
                        PropertyFactory.lineWidth(3.5f),
                        PropertyFactory.lineOpacity(0.62f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer(
                        LIBRARY_SELECTED_CASING_LAYER_ID,
                        LIBRARY_SOURCE_ID,
                    ).withProperties(
                        PropertyFactory.lineColor(palette.roadCasing),
                        PropertyFactory.lineWidth(9f),
                        PropertyFactory.lineOpacity(0.9f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer(LIBRARY_SELECTED_LAYER_ID, LIBRARY_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(palette.primary),
                        PropertyFactory.lineWidth(5.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addSource(GeoJsonSource(LIBRARY_ENDPOINT_SOURCE_ID))
                style.addLayer(
                    CircleLayer(
                        LIBRARY_ENDPOINT_LAYER_ID,
                        LIBRARY_ENDPOINT_SOURCE_ID,
                    ).withProperties(
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get(ENDPOINT_ROLE_PROPERTY),
                                Expression.color(palette.primary),
                                Expression.stop(
                                    LIBRARY_ROLE_START,
                                    Expression.color(palette.vegetationStrong),
                                ),
                                Expression.stop(
                                    LIBRARY_ROLE_FINISH,
                                    Expression.color(palette.primary),
                                ),
                            ),
                        ),
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleStrokeColor(palette.roadCasing),
                        PropertyFactory.circleStrokeWidth(2.5f),
                    ),
                )
                renderLibrary(map, currentLines.value, currentSelectedId.value)
                val retained = currentInitialCamera.value
                if (retained != null) {
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(retained.lat, retained.lon))
                                .zoom(retained.zoom)
                                .bearing(retained.bearing)
                                .tilt(retained.tilt)
                                .build(),
                        ),
                    )
                } else {
                    fitLibraryCamera(
                        map = map,
                        points = currentLines.value.flatMap { it.points },
                        paddingPx = cameraPaddingPx,
                        bottomInsetPx = bottomInsetPx,
                        animate = false,
                    )
                }
            }
        }
    }

    LaunchedEffect(mapView, lines, selectedId) {
        mapView.getMapAsync { map ->
            if (map.style != null) renderLibrary(map, lines, selectedId)
        }
    }

    LaunchedEffect(mapView, cameraRequest) {
        val request = cameraRequest ?: return@LaunchedEffect
        mapView.getMapAsync { map ->
            if (map.style == null) return@getMapAsync
            when (val action = request.action) {
                SegmentLibraryCameraAction.FitAll -> fitLibraryCamera(
                    map = map,
                    points = lines.flatMap { it.points },
                    paddingPx = cameraPaddingPx,
                    bottomInsetPx = bottomInsetPx,
                )

                is SegmentLibraryCameraAction.FitSegment -> fitLibraryCamera(
                    map = map,
                    points = lines.firstOrNull { it.id == action.id }?.points.orEmpty(),
                    paddingPx = cameraPaddingPx,
                    bottomInsetPx = bottomInsetPx,
                )

                is SegmentLibraryCameraAction.Center -> map.easeCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(action.lat, action.lon),
                        action.zoom,
                    ),
                    600,
                )
            }
        }
    }
}

private fun renderLibrary(
    map: MapLibreMap,
    lines: List<SegmentLibraryLine>,
    selectedId: String?,
) {
    val style = map.style ?: return
    style.getSourceAs<GeoJsonSource>(LIBRARY_SOURCE_ID)?.let { source ->
        val features = lines.filter { it.points.size >= 2 }.map { line ->
            Feature.fromGeometry(
                LineString.fromLngLats(line.points.map { Point.fromLngLat(it.lon, it.lat) }),
            ).also { it.addStringProperty(SEGMENT_ID_PROPERTY, line.id) }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    // An unmatchable id keeps the highlight layers present but empty, so the
    // layer order never changes as the selection comes and goes.
    val filter = Expression.eq(Expression.get(SEGMENT_ID_PROPERTY), selectedId ?: "")
    style.getLayerAs<LineLayer>(LIBRARY_SELECTED_CASING_LAYER_ID)?.setFilter(filter)
    style.getLayerAs<LineLayer>(LIBRARY_SELECTED_LAYER_ID)?.setFilter(filter)
    style.getSourceAs<GeoJsonSource>(LIBRARY_ENDPOINT_SOURCE_ID)?.let { source ->
        val selected = lines.firstOrNull { it.id == selectedId }?.points.orEmpty()
        if (selected.size < 2) {
            source.setGeoJson(EMPTY_COLLECTION)
        } else {
            source.setGeoJson(
                FeatureCollection.fromFeatures(
                    listOf(
                        endpoint(selected.first(), LIBRARY_ROLE_START),
                        endpoint(selected.last(), LIBRARY_ROLE_FINISH),
                    ),
                ),
            )
        }
    }
}

private fun endpoint(point: SegmentMapPoint, role: String): Feature =
    Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).also { feature ->
        feature.addStringProperty(ENDPOINT_ROLE_PROPERTY, role)
    }

private fun fitLibraryCamera(
    map: MapLibreMap,
    points: List<SegmentMapPoint>,
    paddingPx: Int,
    bottomInsetPx: Int,
    animate: Boolean = true,
) {
    if (points.isEmpty()) return
    val distinct = points.mapTo(LinkedHashSet()) { it.lat to it.lon }
    if (distinct.size < 2) {
        val only = points.first()
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(only.lat, only.lon),
                CENTER_FALLBACK_ZOOM,
            ),
        )
        return
    }
    val bounds = LatLngBounds.Builder()
        .apply { points.forEach { include(LatLng(it.lat, it.lon)) } }
        .build()
    val update = CameraUpdateFactory.newLatLngBounds(
        bounds,
        paddingPx,
        paddingPx,
        paddingPx,
        bottomInsetPx.coerceAtLeast(paddingPx),
    )
    if (animate) map.easeCamera(update, 600) else map.moveCamera(update)
}
