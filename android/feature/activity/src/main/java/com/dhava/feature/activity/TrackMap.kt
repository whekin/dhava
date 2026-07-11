package com.dhava.feature.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dhava.core.recording.RecordLine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// OpenFreeMap "Liberty" style: free vector tiles, no API key required.
private const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

private const val TRACK_SOURCE_ID = "ride-track-source"
private const val TRACK_LAYER_ID = "ride-track-layer"

/** Camera padding around the fitted track, px. */
private const val BOUNDS_PADDING_PX = 96

/** Fallback zoom when the track has no spatial extent (stationary fixes). */
private const val SINGLE_POINT_ZOOM = 15.0

/**
 * The ride track on an interactive MapLibre map: the raw GPS polyline drawn
 * as a GeoJSON LineLayer in the theme accent color, camera animated to fit
 * the track bounds. Display-only — the polyline comes straight from the raw
 * `gps` lines (see [com.dhava.core.recording.GpsTrackReader]).
 */
@Composable
internal fun TrackMap(
    points: List<RecordLine.Gps>,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    val trackArgb = trackColor.toArgb()

    AndroidView(factory = { mapView }, modifier = modifier)

    // The track is immutable for the lifetime of the screen; set the style,
    // source, layer and camera up once per (mapView, points) pair.
    LaunchedEffect(mapView, points) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(STYLE_URI)) { style ->
                if (style.getSource(TRACK_SOURCE_ID) == null) {
                    val line = LineString.fromLngLats(
                        points.map { Point.fromLngLat(it.lon, it.lat) },
                    )
                    style.addSource(GeoJsonSource(TRACK_SOURCE_ID, line))
                    style.addLayer(
                        LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).withProperties(
                            PropertyFactory.lineColor(trackArgb),
                            PropertyFactory.lineWidth(4f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )
                }
                fitCameraToTrack(map, points)
            }
        }
    }
}

private fun fitCameraToTrack(
    map: org.maplibre.android.maps.MapLibreMap,
    points: List<RecordLine.Gps>,
) {
    // LatLngBounds.Builder needs at least two positions with some extent;
    // a single fix (or a stationary recording) falls back to a fixed zoom.
    val distinct = points.mapTo(LinkedHashSet()) { it.lat to it.lon }
    if (distinct.size < 2) {
        val only = points.first()
        map.easeCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), SINGLE_POINT_ZOOM),
        )
        return
    }
    val bounds = LatLngBounds.Builder()
        .apply { points.forEach { include(LatLng(it.lat, it.lon)) } }
        .build()
    map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING_PX), 1_000)
}

/**
 * A [MapView] whose onCreate/onStart/onResume/onPause/onStop/onDestroy are
 * driven by the host lifecycle. MapLibre's native rendering surface leaks GL
 * resources (and crashes on some devices) when these forwarding calls are
 * skipped, so hosting it in Compose requires this bridge.
 */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        // Must run before the first MapView is instantiated (initializes
        // native libs and the asset/file source machinery).
        MapLibre.getInstance(context)
        MapView(context)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // addObserver replays events up to the current state, so
                // ON_CREATE/ON_START/ON_RESUME arrive even when the screen
                // enters composition on an already-resumed activity.
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
            // Back navigation removes the screen while the host lifecycle is
            // still up — wind the view down manually. Each step is guarded so
            // an actual ON_DESTROY (already forwarded above) is not repeated.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) mapView.onDestroy()
        }
    }
    return mapView
}
