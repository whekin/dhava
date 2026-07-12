package com.dhava.feature.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dhava.core.recording.LiveTrackPoint
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
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
internal fun LiveTrackMap(
    points: List<LiveTrackPoint>,
    trackColor: Color,
    following: Boolean,
    recenterRequest: Int,
    onUserMovedMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberLiveMapView()
    val context = LocalContext.current
    val initialLocationApplied = remember { mutableStateOf(false) }
    val currentOnUserMovedMap = rememberUpdatedState(onUserMovedMap)
    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    @Suppress("DEPRECATION")
                    map.locationComponent.cameraMode = CameraMode.NONE
                    currentOnUserMovedMap.value()
                }
            }
        }
    }

    LaunchedEffect(mapView, trackColor, hasLocationPermission) {
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
                if (hasLocationPermission) {
                    @Suppress("DEPRECATION")
                    map.locationComponent.apply {
                        activateLocationComponent(
                            LocationComponentActivationOptions.builder(context, style)
                                .useDefaultLocationEngine(true)
                                .build(),
                        )
                        isLocationComponentEnabled = true
                        cameraMode = CameraMode.TRACKING
                        renderMode = RenderMode.COMPASS
                        zoomWhileTracking(16.5)
                    }
                }
            }
        }
    }

    // LocationComponent draws the puck but does not reliably move the camera
    // when its first fix arrives after style activation. Explicitly consume
    // that first fix so the idle recorder opens around the rider, not Earth.
    LaunchedEffect(mapView, hasLocationPermission) {
        if (!hasLocationPermission || initialLocationApplied.value) return@LaunchedEffect
        val client = LocationServices.getFusedLocationProviderClient(context)
        fun focus(lat: Double, lon: Double) {
            if (initialLocationApplied.value) return
            initialLocationApplied.value = true
            mapView.getMapAsync { map ->
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.5),
                    900,
                )
            }
        }
        try {
            client.lastLocation.addOnSuccessListener { location ->
                if (location != null) focus(location.latitude, location.longitude)
                else client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { current ->
                        if (current != null) focus(current.latitude, current.longitude)
                    }
            }
        } catch (_: SecurityException) {
            // Permission can be revoked between composition and the request.
        }
    }

    LaunchedEffect(mapView, points, following) {
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
                if (following) map.easeCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lon), 16.5), 700,
                )
            }
        }
    }

    LaunchedEffect(mapView, recenterRequest) {
        if (recenterRequest == 0) return@LaunchedEffect
        mapView.getMapAsync { map ->
            @Suppress("DEPRECATION")
            map.locationComponent.apply {
                cameraMode = CameraMode.TRACKING
                zoomWhileTracking(16.5)
            }
            points.lastOrNull()?.let { last ->
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lon), 16.5), 700)
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
