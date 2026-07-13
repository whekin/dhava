package com.dhava.feature.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
private const val RAW_SOURCE_ID = "raw-track-source"
private const val RAW_LAYER_ID = "raw-track-layer"
private const val FUSED_SOURCE_ID = "fused-track-source"
private const val FUSED_LAYER_ID = "fused-track-layer"
private const val BOUNDS_PADDING_PX = 96
private const val SINGLE_POINT_ZOOM = 15.0

internal enum class TrackMode(val label: String) {
    Gps("GPS"),
    Fusion("Fusion"),
    Compare("Compare"),
}

internal data class MapTrackPoint(val lat: Double, val lon: Double)

/** Raw and replayed live tracks on one map; all computation remains in Rust. */
@Composable
internal fun TrackMap(
    rawPoints: List<MapTrackPoint>,
    fusedPoints: List<MapTrackPoint>,
    mode: TrackMode,
    rawColor: Color,
    fusedColor: Color,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    val currentMode = rememberUpdatedState(mode)
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(mapView, rawPoints, fusedPoints, rawColor, fusedColor) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(STYLE_URI)) { style ->
                style.addSource(GeoJsonSource(RAW_SOURCE_ID).also { source ->
                    rawPoints.toLineStringOrNull()?.let(source::setGeoJson)
                })
                style.addLayer(
                    LineLayer(RAW_LAYER_ID, RAW_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(rawColor.toArgb()),
                        PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineOpacity(0.82f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addSource(GeoJsonSource(FUSED_SOURCE_ID).also { source ->
                    fusedPoints.toLineStringOrNull()?.let(source::setGeoJson)
                })
                style.addLayer(
                    LineLayer(FUSED_LAYER_ID, FUSED_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(fusedColor.toArgb()),
                        PropertyFactory.lineWidth(3.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                applyMode(style, currentMode.value)
                fitCamera(map, visiblePoints(currentMode.value, rawPoints, fusedPoints))
            }
        }
    }

    LaunchedEffect(mapView, mode) {
        mapView.getMapAsync { map ->
            map.style?.let { style ->
                applyMode(style, mode)
                fitCamera(map, visiblePoints(mode, rawPoints, fusedPoints))
            }
        }
    }
}

private fun List<MapTrackPoint>.toLineStringOrNull(): LineString? =
    takeIf { it.size >= 2 }?.let { points ->
        LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
    }

private fun applyMode(style: Style, mode: TrackMode) {
    style.getLayer(RAW_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(
            if (mode == TrackMode.Fusion) Property.NONE else Property.VISIBLE,
        ),
    )
    style.getLayer(FUSED_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(
            if (mode == TrackMode.Gps) Property.NONE else Property.VISIBLE,
        ),
    )
}

private fun visiblePoints(
    mode: TrackMode,
    raw: List<MapTrackPoint>,
    fused: List<MapTrackPoint>,
): List<MapTrackPoint> = when (mode) {
    TrackMode.Gps -> raw
    TrackMode.Fusion -> fused.ifEmpty { raw }
    TrackMode.Compare -> raw + fused
}

private fun fitCamera(map: MapLibreMap, points: List<MapTrackPoint>) {
    if (points.isEmpty()) return
    val distinct = points.mapTo(LinkedHashSet()) { it.lat to it.lon }
    if (distinct.size < 2) {
        val only = points.first()
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), SINGLE_POINT_ZOOM))
        return
    }
    val bounds = LatLngBounds.Builder()
        .apply { points.forEach { include(LatLng(it.lat, it.lon)) } }
        .build()
    map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING_PX), 1_000)
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) mapView.onDestroy()
        }
    }
    return mapView
}
