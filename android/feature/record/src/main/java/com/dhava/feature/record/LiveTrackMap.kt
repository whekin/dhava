package com.dhava.feature.record

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
import com.dhava.core.recording.LiveTrackPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
private const val TRACK_SOURCE = "live-track-source"
private const val TRACK_LAYER = "live-track-layer"
private const val POSITION_SOURCE = "live-position-source"
private const val POSITION_LAYER = "live-position-layer"

@Composable
internal fun LiveTrackMap(points: List<LiveTrackPoint>, trackColor: Color, modifier: Modifier = Modifier) {
    val mapView = rememberLiveMapView()
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(mapView, trackColor) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(STYLE_URI)) { style ->
                style.addSource(GeoJsonSource(TRACK_SOURCE))
                style.addLayer(LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                    PropertyFactory.lineColor(trackColor.toArgb()),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ))
                style.addSource(GeoJsonSource(POSITION_SOURCE))
                style.addLayer(CircleLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
                    PropertyFactory.circleColor(trackColor.toArgb()),
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleStrokeColor(Color.White.toArgb()),
                    PropertyFactory.circleStrokeWidth(3f),
                ))
            }
        }
    }

    LaunchedEffect(mapView, points) {
        val last = points.lastOrNull() ?: return@LaunchedEffect
        mapView.getMapAsync { map ->
            map.style?.let { style ->
                if (points.size >= 2) {
                    style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(
                        LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) }),
                    )
                }
                style.getSourceAs<GeoJsonSource>(POSITION_SOURCE)
                    ?.setGeoJson(Point.fromLngLat(last.lon, last.lat))
                map.easeCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lon), 16.5),
                    700,
                )
            }
        }
    }
}

@Composable
private fun rememberLiveMapView(): MapView {
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
