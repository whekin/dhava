package com.dhava.feature.record

import android.Manifest
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dhava.core.map.DhavaMapPalette
import com.dhava.core.map.configureDhavaMapChrome
import com.dhava.core.map.initDhavaMap
import com.dhava.core.map.rememberDhavaMapPalette
import com.dhava.core.map.setDhavaMapStyle
import com.dhava.core.recording.LiveTrackPoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private const val EMPTY_FEATURE_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
private const val PREVIEW_INTERVAL_MS = 1_000L
private const val PREVIEW_MIN_INTERVAL_MS = 500L
private const val RECENT_CACHED_FIX_MS = 10 * 60 * 1_000L
private const val FOLLOW_ZOOM = 16.8

private const val ACCURACY_SOURCE = "live-accuracy-source"
private const val ACCURACY_FILL_LAYER = "live-accuracy-fill-layer"
private const val ACCURACY_LINE_LAYER = "live-accuracy-line-layer"
private const val TRACK_SOURCE = "live-track-source"
private const val TRACK_CASING_LAYER = "live-track-casing-layer"
private const val TRACK_LAYER = "live-track-layer"
private const val POSITION_SOURCE = "live-position-source"
private const val POSITION_HALO_LAYER = "live-position-halo-layer"
private const val POSITION_LAYER = "live-position-layer"

private data class MapPosition(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
)

@Composable
internal fun LiveTrackMap(
    points: List<LiveTrackPoint>,
    positionAccuracyM: Float?,
    previewLocationEnabled: Boolean,
    cameraBottomPadding: Dp,
    following: Boolean,
    recenterRequest: Int,
    onUserMovedMap: () -> Unit,
    onPreviewAccuracyChanged: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberLiveMapView()
    val context = LocalContext.current
    val bottomPaddingPx = with(LocalDensity.current) { cameraBottomPadding.roundToPx() }
    val mapChromeMarginPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val palette = rememberDhavaMapPalette()
    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    var previewPosition by remember { mutableStateOf<MapPosition?>(null) }
    val livePosition = points.lastOrNull()?.let {
        MapPosition(lat = it.lat, lon = it.lon, accuracyM = positionAccuracyM)
    }
    val position = livePosition ?: previewPosition
    val currentPoints = rememberUpdatedState(points)
    val currentPosition = rememberUpdatedState(position)
    val currentFollowing = rememberUpdatedState(following)
    val currentOnUserMovedMap = rememberUpdatedState(onUserMovedMap)
    val currentOnPreviewAccuracyChanged = rememberUpdatedState(onPreviewAccuracyChanged)
    val initialLocationApplied = remember { mutableStateOf(false) }

    AndroidView(factory = { mapView }, modifier = modifier)

    // Warm a precise GPS fix while the rider is looking at the idle recorder.
    // The effect is disposed on screen-off, tab change and as soon as the
    // recording service takes ownership of location during Preparing.
    DisposableEffect(context, previewLocationEnabled, hasLocationPermission) {
        if (!previewLocationEnabled || !hasLocationPermission) {
            onDispose {}
        } else {
            val client = LocationServices.getFusedLocationProviderClient(context)
            var active = true
            fun accept(location: Location) {
                if (!active) return
                previewPosition = MapPosition(
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracyM = location.takeIf { it.hasAccuracy() }?.accuracy,
                )
                currentOnPreviewAccuracyChanged.value(previewPosition?.accuracyM)
            }
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach(::accept)
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, PREVIEW_INTERVAL_MS)
                .setMinUpdateIntervalMillis(PREVIEW_MIN_INTERVAL_MS)
                .build()
            try {
                client.lastLocation.addOnSuccessListener { cached ->
                    if (cached != null && cached.ageMs() <= RECENT_CACHED_FIX_MS) accept(cached)
                }
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (_: SecurityException) {
                // Permission can be revoked between composition and request.
            }
            onDispose {
                active = false
                client.removeLocationUpdates(callback)
            }
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    currentOnUserMovedMap.value()
                }
            }
        }
    }

    LaunchedEffect(mapView, palette) {
        mapView.getMapAsync { map ->
            map.applyContentPadding(bottomPaddingPx, mapChromeMarginPx, palette)
            // Fallback-aware: the overlay layers below are added even when the
            // remote style cannot load offline, so the live track always draws.
            mapView.setDhavaMapStyle(map, palette) { style ->
                style.addSource(GeoJsonSource(ACCURACY_SOURCE))
                style.addLayer(
                    FillLayer(ACCURACY_FILL_LAYER, ACCURACY_SOURCE).withProperties(
                        PropertyFactory.fillColor(palette.primary),
                        PropertyFactory.fillOpacity(0.12f),
                    ),
                )
                style.addLayer(
                    LineLayer(ACCURACY_LINE_LAYER, ACCURACY_SOURCE).withProperties(
                        PropertyFactory.lineColor(palette.primary),
                        PropertyFactory.lineOpacity(0.42f),
                        PropertyFactory.lineWidth(1.5f),
                    ),
                )
                style.addSource(GeoJsonSource(TRACK_SOURCE))
                style.addLayer(
                    LineLayer(TRACK_CASING_LAYER, TRACK_SOURCE).withProperties(
                        PropertyFactory.lineColor(palette.roadCasing),
                        PropertyFactory.lineOpacity(0.92f),
                        PropertyFactory.lineWidth(10f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                        PropertyFactory.lineColor(palette.primary),
                        PropertyFactory.lineWidth(5.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addSource(GeoJsonSource(POSITION_SOURCE))
                style.addLayer(
                    CircleLayer(POSITION_HALO_LAYER, POSITION_SOURCE).withProperties(
                        PropertyFactory.circleColor(palette.primaryContainer),
                        PropertyFactory.circleRadius(14f),
                        PropertyFactory.circleStrokeColor(palette.primary),
                        PropertyFactory.circleStrokeOpacity(0.38f),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                style.addLayer(
                    CircleLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
                        PropertyFactory.circleColor(palette.primary),
                        PropertyFactory.circleRadius(7.5f),
                        PropertyFactory.circleStrokeColor(palette.onPrimary),
                        PropertyFactory.circleStrokeWidth(2.5f),
                    ),
                )
                updateMapContent(style, currentPoints.value, currentPosition.value)
                currentPosition.value?.takeIf { currentFollowing.value }?.let { current ->
                    initialLocationApplied.value = true
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(current.lat, current.lon), FOLLOW_ZOOM),
                        700,
                    )
                }
            }
        }
    }

    LaunchedEffect(mapView, points, position, following, bottomPaddingPx) {
        mapView.getMapAsync { map ->
            map.applyContentPadding(bottomPaddingPx, mapChromeMarginPx, palette)
            map.style?.let { style -> updateMapContent(style, points, position) }
            if (position != null && following) {
                val duration = if (initialLocationApplied.value) 500 else 850
                initialLocationApplied.value = true
                map.easeCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(position.lat, position.lon), FOLLOW_ZOOM),
                    duration,
                )
            }
        }
    }

    LaunchedEffect(mapView, recenterRequest) {
        if (recenterRequest == 0) return@LaunchedEffect
        mapView.getMapAsync { map ->
            currentPosition.value?.let { current ->
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(current.lat, current.lon), FOLLOW_ZOOM),
                    700,
                )
            }
        }
    }
}

private fun MapLibreMap.applyContentPadding(
    bottomPaddingPx: Int,
    mapChromeMarginPx: Int,
    palette: DhavaMapPalette,
) {
    @Suppress("DEPRECATION")
    setPadding(0, 0, 0, bottomPaddingPx)
    configureDhavaMapChrome(palette, bottomPaddingPx, mapChromeMarginPx)
}

private fun Location.ageMs(): Long =
    max(0L, (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L)

private fun updateMapContent(style: Style, points: List<LiveTrackPoint>, position: MapPosition?) {
    style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.let { source ->
        if (points.size >= 2) {
            source.setGeoJson(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) }))
        } else {
            source.setGeoJson(EMPTY_FEATURE_COLLECTION)
        }
    }
    style.getSourceAs<GeoJsonSource>(POSITION_SOURCE)?.let { source ->
        if (position == null) source.setGeoJson(EMPTY_FEATURE_COLLECTION)
        else source.setGeoJson(Point.fromLngLat(position.lon, position.lat))
    }
    style.getSourceAs<GeoJsonSource>(ACCURACY_SOURCE)?.let { source ->
        val accuracy = position?.accuracyM?.takeIf { it.isFinite() && it > 0f }
        if (position == null || accuracy == null) source.setGeoJson(EMPTY_FEATURE_COLLECTION)
        else source.setGeoJson(accuracyPolygon(position, accuracy.coerceAtMost(250f).toDouble()))
    }
}

private fun accuracyPolygon(position: MapPosition, radiusM: Double): Polygon {
    val latitudeDegreesPerMeter = 1.0 / 111_320.0
    val longitudeDegreesPerMeter = 1.0 / (111_320.0 * cos(position.lat * PI / 180.0).coerceAtLeast(0.01))
    val ring = (0..48).map { step ->
        val angle = 2.0 * PI * step / 48.0
        Point.fromLngLat(
            position.lon + cos(angle) * radiusM * longitudeDegreesPerMeter,
            position.lat + sin(angle) * radiusM * latitudeDegreesPerMeter,
        )
    }
    return Polygon.fromLngLats(listOf(ring))
}

@Composable
private fun rememberLiveMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        initDhavaMap(context)
        MapView(context).also { it.onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
