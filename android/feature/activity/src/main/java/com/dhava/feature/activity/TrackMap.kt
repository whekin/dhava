package com.dhava.feature.activity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dhava.core.map.DHAVA_MAP_STYLE_URI
import com.dhava.core.map.DhavaMapPalette
import com.dhava.core.map.applyDhavaMapPalette
import com.dhava.core.map.configureDhavaMapChrome
import com.dhava.core.map.rememberDhavaMapPalette
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

private const val EMPTY_FEATURE_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
private const val RAW_SOURCE_ID = "raw-track-source"
private const val RAW_LAYER_ID = "raw-track-layer"
private const val RAW_POINTS_SOURCE_ID = "raw-track-points-source"
private const val RAW_POINTS_LAYER_ID = "raw-track-points-layer"
private const val FUSED_SOURCE_ID = "fused-track-source"
private const val FUSED_CASING_LAYER_ID = "fused-track-casing-layer"
private const val FUSED_LAYER_ID = "fused-track-layer"
private const val START_SOURCE_ID = "track-start-source"
private const val START_LAYER_ID = "track-start-layer"
private const val START_IMAGE_ID = "track-start-image"
private const val FINISH_SOURCE_ID = "track-finish-source"
private const val FINISH_LAYER_ID = "track-finish-layer"
private const val FINISH_IMAGE_ID = "track-finish-image"
private const val MARKER_SIZE_PX = 48
private const val BOUNDS_PADDING_PX = 96
private const val SINGLE_POINT_ZOOM = 15.0

internal enum class TrackMode(val label: String) {
    Gps("GPS"),
    Fusion("Fusion"),
    Compare("Compare"),
}

internal data class MapTrackPoint(
    val lat: Double,
    val lon: Double,
    val sectionId: Int,
    val accuracyM: Double? = null,
)

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
    val palette = rememberDhavaMapPalette()
    val accuracyColors = rememberGpsAccuracyColors()
    val overlayBottomPx = with(LocalDensity.current) { 240.dp.roundToPx() }
    val mapChromeMarginPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val currentMode = rememberUpdatedState(mode)
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(mapView, rawPoints, fusedPoints, rawColor, fusedColor, palette, accuracyColors) {
        mapView.getMapAsync { map ->
            @Suppress("DEPRECATION")
            map.setPadding(0, 0, 0, overlayBottomPx)
            map.configureDhavaMapChrome(palette, overlayBottomPx, mapChromeMarginPx)
            map.setStyle(Style.Builder().fromUri(DHAVA_MAP_STYLE_URI)) { style ->
                style.applyDhavaMapPalette(palette)
                style.addSource(GeoJsonSource(RAW_SOURCE_ID).also { source ->
                    rawPoints.toMultiLineStringOrNull()?.let(source::setGeoJson)
                })
                style.addLayer(
                    LineLayer(RAW_LAYER_ID, RAW_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(rawColor.toArgb()),
                        PropertyFactory.lineWidth(2f),
                        PropertyFactory.lineOpacity(0.55f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addSource(GeoJsonSource(RAW_POINTS_SOURCE_ID).also { source ->
                    rawPoints.toAccuracyFeatureCollectionOrNull()?.let(source::setGeoJson)
                })
                style.addSource(GeoJsonSource(FUSED_SOURCE_ID).also { source ->
                    fusedPoints.toMultiLineStringOrNull()?.let(source::setGeoJson)
                })
                style.addLayer(
                    LineLayer(FUSED_CASING_LAYER_ID, FUSED_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(palette.roadCasing),
                        PropertyFactory.lineWidth(9f),
                        PropertyFactory.lineOpacity(0.9f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer(FUSED_LAYER_ID, FUSED_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(fusedColor.toArgb()),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                // Keep the raw line beneath fusion, but put individual GPS
                // fixes above it so Compare exposes the actual measurements.
                style.addLayer(
                    CircleLayer(RAW_POINTS_LAYER_ID, RAW_POINTS_SOURCE_ID).withProperties(
                        PropertyFactory.circleColor(accuracyColorExpression(accuracyColors)),
                        PropertyFactory.circleRadius(3.25f),
                        PropertyFactory.circleOpacity(0.9f),
                        PropertyFactory.circleStrokeColor(palette.roadCasing),
                        PropertyFactory.circleStrokeWidth(1f),
                    ),
                )
                style.addImage(START_IMAGE_ID, createStartMarker(palette))
                style.addImage(FINISH_IMAGE_ID, createFinishMarker(palette))
                style.addSource(GeoJsonSource(START_SOURCE_ID))
                style.addSource(GeoJsonSource(FINISH_SOURCE_ID))
                style.addLayer(
                    SymbolLayer(START_LAYER_ID, START_SOURCE_ID).withProperties(
                        PropertyFactory.iconImage(START_IMAGE_ID),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    ),
                )
                style.addLayer(
                    SymbolLayer(FINISH_LAYER_ID, FINISH_SOURCE_ID).withProperties(
                        PropertyFactory.iconImage(FINISH_IMAGE_ID),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    ),
                )
                applyMode(style, currentMode.value, rawPoints, fusedPoints)
                fitCamera(map, visiblePoints(currentMode.value, rawPoints, fusedPoints))
            }
        }
    }

    LaunchedEffect(mapView, mode) {
        mapView.getMapAsync { map ->
            map.style?.let { style ->
                applyMode(style, mode, rawPoints, fusedPoints)
                fitCamera(map, visiblePoints(mode, rawPoints, fusedPoints))
            }
        }
    }
}

private fun List<MapTrackPoint>.toMultiLineStringOrNull(): MultiLineString? {
    val drawableSections = continuousSections()
        .filter { it.size >= 2 }
        .map { section -> section.map { Point.fromLngLat(it.lon, it.lat) } }
    return drawableSections.takeIf { it.isNotEmpty() }
        ?.let(MultiLineString::fromLngLats)
}

internal fun List<MapTrackPoint>.continuousSections(): List<List<MapTrackPoint>> {
    val sections = mutableListOf<MutableList<MapTrackPoint>>()
    var previousSectionId: Int? = null
    for (point in this) {
        if (point.sectionId != previousSectionId) {
            sections.add(mutableListOf())
            previousSectionId = point.sectionId
        }
        sections.last() += point
    }
    return sections
}

internal fun List<MapTrackPoint>.toAccuracyFeatureCollectionOrNull(): FeatureCollection? =
    takeIf { it.isNotEmpty() }?.let { points ->
        FeatureCollection.fromFeatures(
            points.map { point ->
                Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).apply {
                    addNumberProperty(
                        GPS_ACCURACY_PROPERTY,
                        point.accuracyM
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                            ?: UNKNOWN_GPS_ACCURACY_STYLE_VALUE,
                    )
                }
            },
        )
    }

private fun accuracyColorExpression(colors: GpsAccuracyColors): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.get(GPS_ACCURACY_PROPERTY),
        Expression.stop(
            UNKNOWN_GPS_ACCURACY_STYLE_VALUE,
            Expression.color(colors.unknown.toArgb()),
        ),
        Expression.stop(0.0, Expression.color(colors.good.toArgb())),
        Expression.stop(5.0, Expression.color(colors.good.toArgb())),
        Expression.stop(10.0, Expression.color(colors.fair.toArgb())),
        Expression.stop(20.0, Expression.color(colors.poor.toArgb())),
    )

private fun applyMode(
    style: Style,
    mode: TrackMode,
    rawPoints: List<MapTrackPoint>,
    fusedPoints: List<MapTrackPoint>,
) {
    style.getLayer(RAW_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(
            if (mode == TrackMode.Fusion) Property.NONE else Property.VISIBLE,
        ),
    )
    style.getLayer(RAW_POINTS_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(
            if (mode == TrackMode.Fusion) Property.NONE else Property.VISIBLE,
        ),
    )
    style.getLayer(FUSED_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(
            if (mode == TrackMode.Gps) Property.NONE else Property.VISIBLE,
        ),
    )
    style.getLayer(FUSED_CASING_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(
            if (mode == TrackMode.Gps) Property.NONE else Property.VISIBLE,
        ),
    )
    val markerTrack = markerPoints(mode, rawPoints, fusedPoints)
    style.getSourceAs<GeoJsonSource>(START_SOURCE_ID)?.setPointOrEmpty(markerTrack.firstOrNull())
    style.getSourceAs<GeoJsonSource>(FINISH_SOURCE_ID)?.setPointOrEmpty(markerTrack.lastOrNull())
}

private fun GeoJsonSource.setPointOrEmpty(point: MapTrackPoint?) {
    if (point == null) setGeoJson(EMPTY_FEATURE_COLLECTION)
    else setGeoJson(Point.fromLngLat(point.lon, point.lat))
}

private fun markerPoints(
    mode: TrackMode,
    raw: List<MapTrackPoint>,
    fused: List<MapTrackPoint>,
): List<MapTrackPoint> = when (mode) {
    TrackMode.Gps -> raw
    TrackMode.Fusion, TrackMode.Compare -> fused.ifEmpty { raw }
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

private fun createStartMarker(palette: DhavaMapPalette): Bitmap =
    markerBitmap(palette.roadCasing, palette.vegetationStrong) { canvas, paint, size ->
        paint.color = palette.label
        paint.style = Paint.Style.FILL
        val path = Path().apply {
            moveTo(size * 0.43f, size * 0.34f)
            lineTo(size * 0.70f, size * 0.50f)
            lineTo(size * 0.43f, size * 0.66f)
            close()
        }
        canvas.drawPath(path, paint)
    }

private fun createFinishMarker(palette: DhavaMapPalette): Bitmap =
    markerBitmap(palette.roadCasing, palette.primary) { canvas, paint, size ->
        val left = size * 0.34f
        val top = size * 0.30f
        val cell = size * 0.105f
        paint.style = Paint.Style.FILL
        paint.color = palette.label
        canvas.drawRoundRect(
            left - size * 0.035f,
            top,
            left + size * 0.025f,
            size * 0.72f,
            size * 0.02f,
            size * 0.02f,
            paint,
        )
        repeat(2) { row ->
            repeat(3) { column ->
                paint.color = if ((row + column) % 2 == 0) palette.label else palette.roadCasing
                canvas.drawRect(
                    left + column * cell,
                    top + row * cell,
                    left + (column + 1) * cell,
                    top + (row + 1) * cell,
                    paint,
                )
            }
        }
    }

private inline fun markerBitmap(
    outerColor: Int,
    innerColor: Int,
    drawGlyph: (Canvas, Paint, Float) -> Unit,
): Bitmap {
    val size = MARKER_SIZE_PX.toFloat()
    val bitmap = Bitmap.createBitmap(MARKER_SIZE_PX, MARKER_SIZE_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    paint.color = outerColor
    canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, paint)
    paint.color = innerColor
    canvas.drawCircle(size / 2f, size / 2f, size * 0.38f, paint)
    drawGlyph(canvas, paint, size)
    return bitmap
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
