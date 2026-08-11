package com.nakvali.feature.activity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nakvali.core.map.NakvaliMapPalette
import com.nakvali.core.map.configureNakvaliMapChrome
import com.nakvali.core.map.rememberNakvaliMapPalette
import com.nakvali.core.map.rememberNakvaliMapView
import com.nakvali.core.map.setNakvaliMapStyle
import com.nakvali.fusion.ActivityState
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
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
private const val FUSED_UNKNOWN_LAYER_ID = "fused-track-unknown-layer"
private const val FUSED_LIKELY_MOTORIZED_LAYER_ID = "fused-track-likely-motorized-layer"
private const val FUSED_TRANSIT_LAYER_ID = "fused-track-transit-layer"
private const val FUSED_DOWNHILL_LAYER_ID = "fused-track-downhill-layer"
private const val FUSED_POINTS_SOURCE_ID = "fused-track-points-source"
private const val FUSED_POINTS_LAYER_ID = "fused-track-points-layer"
private const val STOP_SOURCE_ID = "track-stops-source"
private const val STOP_LAYER_ID = "track-stops-layer"
private const val START_SOURCE_ID = "track-start-source"
private const val START_LAYER_ID = "track-start-layer"
private const val START_IMAGE_ID = "track-start-image"
private const val FINISH_SOURCE_ID = "track-finish-source"
private const val FINISH_LAYER_ID = "track-finish-layer"
private const val FINISH_IMAGE_ID = "track-finish-image"
private const val INSPECT_SOURCE_ID = "track-inspect-source"
private const val INSPECT_CASING_LAYER_ID = "track-inspect-casing-layer"
private const val INSPECT_LAYER_ID = "track-inspect-layer"
private const val MARKER_SIZE_PX = 48
private const val BOUNDS_PADDING_PX = 96
private const val SINGLE_POINT_ZOOM = 15.0
private const val FUSION_POINTS_MIN_ZOOM = 18f
internal const val SEMANTIC_TRACK_MAX_GAP_MS = 3_000L

/** Below this, confirmed stillness is not an event worth a map marker. */
internal const val MIN_RIDER_STOP_MS = 15_000L
internal const val STOP_DURATION_PROPERTY = "duration_ms"

private val FUSED_LINE_LAYER_IDS = listOf(
    FUSED_LAYER_ID,
    FUSED_UNKNOWN_LAYER_ID,
    FUSED_LIKELY_MOTORIZED_LAYER_ID,
    FUSED_TRANSIT_LAYER_ID,
    FUSED_DOWNHILL_LAYER_ID,
)

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
    val timestampMs: Long = 0L,
    val activityState: ActivityState? = null,
    val activityConfidence: Double? = null,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
)

internal data class SemanticLineRun(
    val activityState: ActivityState?,
    val points: List<MapTrackPoint>,
)

internal data class StopMarker(
    val point: MapTrackPoint,
    val durationMs: Long,
    val confidence: Double?,
)

/** Raw and replayed live tracks on one map; all computation remains in Rust. */
@Composable
internal fun TrackMap(
    rawPoints: List<MapTrackPoint>,
    fusedPoints: List<MapTrackPoint>,
    mode: TrackMode,
    rawColor: Color,
    fusedColor: Color,
    inspectedPoint: MapTrackPoint? = null,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberNakvaliMapView()
    val palette = rememberNakvaliMapPalette()
    val accuracyColors = rememberGpsAccuracyColors()
    val activityStateColors = rememberActivityStateColors()
    val overlayBottomPx = with(LocalDensity.current) { 240.dp.roundToPx() }
    val mapChromeMarginPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val currentMode = rememberUpdatedState(mode)
    val currentInspectedPoint = rememberUpdatedState(inspectedPoint)
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(
        mapView,
        rawPoints,
        fusedPoints,
        rawColor,
        fusedColor,
        palette,
        accuracyColors,
        activityStateColors,
    ) {
        mapView.getMapAsync { map ->
            @Suppress("DEPRECATION")
            map.setPadding(0, 0, 0, overlayBottomPx)
            map.configureNakvaliMapChrome(palette, overlayBottomPx, mapChromeMarginPx)
            // Fallback-aware: track layers are added even when the remote
            // style cannot load offline, so recorded lines always render.
            mapView.setNakvaliMapStyle(map, palette) { style ->
                style.addSource(
                    GeoJsonSource(RAW_SOURCE_ID, diagnosticLineOptions()).also { source ->
                        rawPoints.toMultiLineStringOrNull()?.let(source::setGeoJson)
                    },
                )
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
                style.addSource(
                    GeoJsonSource(FUSED_SOURCE_ID, diagnosticLineOptions()).also { source ->
                        fusedPoints.toSemanticLineFeatureCollectionOrNull()?.let(source::setGeoJson)
                    },
                )
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
                    ).withFilter(activityStateFilter(ACTIVITY_STATE_UNCLASSIFIED)),
                )
                style.addLayer(
                    LineLayer(FUSED_UNKNOWN_LAYER_ID, FUSED_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(activityStateColors.unknown.toArgb()),
                        PropertyFactory.lineWidth(2f),
                        PropertyFactory.lineOpacity(0.4f),
                        PropertyFactory.lineDasharray(arrayOf(0.5f, 1.5f)),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ).withFilter(activityStateFilter(ACTIVITY_STATE_UNKNOWN)),
                )
                style.addLayer(
                    LineLayer(
                        FUSED_LIKELY_MOTORIZED_LAYER_ID,
                        FUSED_SOURCE_ID,
                    ).withProperties(
                        // A shuttle lap is not the rider's achievement. Kept
                        // visible enough to explain how they got back up, and
                        // no more.
                        PropertyFactory.lineColor(activityStateColors.likelyMotorized.toArgb()),
                        PropertyFactory.lineWidth(1.8f),
                        PropertyFactory.lineOpacity(0.22f),
                        PropertyFactory.lineDasharray(arrayOf(3.0f, 2.6f)),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ).withFilter(activityStateFilter(ACTIVITY_STATE_LIKELY_MOTORIZED)),
                )
                style.addLayer(
                    // Everything that is not a descent is context, not content.
                    // The transit line still has to be followable — it is how
                    // the rider reads the shape of the day — but it must never
                    // compete with a run for attention.
                    LineLayer(FUSED_TRANSIT_LAYER_ID, FUSED_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(activityStateColors.transit.toArgb()),
                        PropertyFactory.lineWidth(2.4f),
                        PropertyFactory.lineOpacity(0.42f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ).withFilter(activityStateFilter(ACTIVITY_STATE_TRANSIT)),
                )
                style.addLayer(
                    LineLayer(FUSED_DOWNHILL_LAYER_ID, FUSED_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(activityStateColors.downhill.toArgb()),
                        PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ).withFilter(activityStateFilter(ACTIVITY_STATE_DOWNHILL)),
                )
                style.addSource(GeoJsonSource(FUSED_POINTS_SOURCE_ID).also { source ->
                    fusedPoints.toPointFeatureCollectionOrNull()?.let(source::setGeoJson)
                })
                style.addLayer(
                    CircleLayer(FUSED_POINTS_LAYER_ID, FUSED_POINTS_SOURCE_ID)
                        .also { it.setMinZoom(FUSION_POINTS_MIN_ZOOM) }
                        .withProperties(
                            // At ride overview scale, 5 Hz points merge into a
                            // solid bead chain. Reveal and grow them only once
                            // the map has enough room to distinguish samples.
                            PropertyFactory.circleColor(palette.onPrimary),
                            PropertyFactory.circleRadius(fusionPointRadiusExpression()),
                            PropertyFactory.circleOpacity(0.92f),
                            PropertyFactory.circleStrokeColor(
                                activityStateColorExpression(activityStateColors, fusedColor),
                            ),
                            PropertyFactory.circleStrokeWidth(1f),
                        ),
                )
                style.addSource(GeoJsonSource(STOP_SOURCE_ID).also { source ->
                    fusedPoints.toStopFeatureCollectionOrNull()?.let(source::setGeoJson)
                })
                // Keep the raw line beneath fusion, but put individual GPS
                // fixes above it so Compare exposes the actual measurements.
                style.addLayer(
                    CircleLayer(RAW_POINTS_LAYER_ID, RAW_POINTS_SOURCE_ID).withProperties(
                        PropertyFactory.circleColor(accuracyColorExpression(accuracyColors)),
                        PropertyFactory.circleRadius(gpsPointRadiusExpression()),
                        PropertyFactory.circleOpacity(0.9f),
                        PropertyFactory.circleStrokeColor(palette.roadCasing),
                        PropertyFactory.circleStrokeWidth(gpsPointStrokeExpression()),
                    ),
                )
                // The semantic stop ring belongs above diagnostic GPS dots:
                // the dots remain above fused geometry, while a stop cannot
                // disappear beneath a dense cloud of stationary raw fixes.
                style.addLayer(
                    // A stop is an annotation on the track, not a landmark.
                    // The old filled cream disks were larger than the trail
                    // itself and buried the ride at overview zoom.
                    CircleLayer(STOP_LAYER_ID, STOP_SOURCE_ID).withProperties(
                        PropertyFactory.circleColor(palette.roadCasing),
                        PropertyFactory.circleOpacity(0.35f),
                        PropertyFactory.circleRadius(stopRadiusExpression()),
                        PropertyFactory.circleStrokeColor(activityStateColors.still.toArgb()),
                        PropertyFactory.circleStrokeWidth(1.2f),
                        PropertyFactory.circleStrokeOpacity(0.75f),
                    ),
                )
                style.addSource(GeoJsonSource(INSPECT_SOURCE_ID).also { source ->
                    source.setPointOrEmpty(currentInspectedPoint.value)
                })
                style.addLayer(
                    CircleLayer(INSPECT_CASING_LAYER_ID, INSPECT_SOURCE_ID).withProperties(
                        PropertyFactory.circleColor(palette.roadCasing),
                        PropertyFactory.circleRadius(10f),
                        PropertyFactory.circleOpacity(0.96f),
                    ),
                )
                style.addLayer(
                    CircleLayer(INSPECT_LAYER_ID, INSPECT_SOURCE_ID).withProperties(
                        PropertyFactory.circleColor(palette.label),
                        PropertyFactory.circleRadius(6f),
                        PropertyFactory.circleStrokeColor(fusedColor.toArgb()),
                        PropertyFactory.circleStrokeWidth(2.5f),
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
                fitCamera(map, cameraBoundsPoints(currentMode.value, rawPoints, fusedPoints))
            }
        }
    }

    LaunchedEffect(mapView, mode) {
        mapView.getMapAsync { map ->
            map.style?.let { style ->
                applyMode(style, mode, rawPoints, fusedPoints)
                fitCamera(map, cameraBoundsPoints(mode, rawPoints, fusedPoints))
            }
        }
    }

    LaunchedEffect(mapView, inspectedPoint) {
        mapView.getMapAsync { map ->
            map.style
                ?.getSourceAs<GeoJsonSource>(INSPECT_SOURCE_ID)
                ?.setPointOrEmpty(inspectedPoint)
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
    var previousPoint: MapTrackPoint? = null
    for (point in this) {
        val realTimestampGap = previousPoint?.let { previous ->
            previous.timestampMs > 0L &&
                point.timestampMs > 0L &&
                point.timestampMs - previous.timestampMs > SEMANTIC_TRACK_MAX_GAP_MS
        } == true
        if (point.sectionId != previousSectionId || realTimestampGap) {
            sections.add(mutableListOf())
            previousSectionId = point.sectionId
        }
        sections.last() += point
        previousPoint = point
    }
    return sections
}

/**
 * Builds drawable, state-homogeneous runs from adjacent canonical points.
 *
 * A state transition shares the previous vertex between both runs so the
 * colored line has no visual hole. Manual pauses and long GPS gaps flush the
 * active run without sharing a point, so the map never invents a bridge.
 * Consecutive STILL points intentionally produce no line; arrival/departure
 * edges still meet the aggregated stop marker.
 */
internal fun List<MapTrackPoint>.semanticLineRuns(): List<SemanticLineRun> {
    if (size < 2) return emptyList()
    val runs = mutableListOf<SemanticLineRun>()
    var activeState: ActivityState? = null
    var activePoints: MutableList<MapTrackPoint>? = null

    fun flush() {
        activePoints?.takeIf { it.size >= 2 }?.let { points ->
            runs += SemanticLineRun(activeState, points.toList())
        }
        activePoints = null
        activeState = null
    }

    for (index in 1 until size) {
        val previous = this[index - 1]
        val current = this[index]
        if (!previous.isSemanticallyContinuousWith(current)) {
            flush()
            continue
        }

        val previousStill = previous.activityState == ActivityState.STILL
        val currentStill = current.activityState == ActivityState.STILL
        if (previousStill && currentStill) {
            flush()
            continue
        }
        val edgeState = when {
            currentStill -> previous.activityState
            previousStill -> current.activityState
            else -> current.activityState
        }
        val points = activePoints
        if (
            points != null &&
            activeState == edgeState &&
            points.last() == previous
        ) {
            points += current
        } else {
            flush()
            activeState = edgeState
            activePoints = mutableListOf(previous, current)
        }
    }
    flush()
    return runs
}

/**
 * Stops worth drawing on a ride map.
 *
 * Two kinds of confirmed stillness are deliberately dropped. A stop shorter
 * than [MIN_RIDER_STOP_MS] is a track stand or a GPS hesitation, not an event
 * in the rider's day. And stillness with vehicle evidence on both sides is a
 * traffic light seen from inside a bus: it belongs to the transport line, not
 * to a ring over the trail. A shuttle lap through town used to cover the map
 * in stop markers that had nothing to do with riding.
 */
internal fun List<MapTrackPoint>.aggregatedStopMarkers(): List<StopMarker> {
    val markers = mutableListOf<StopMarker>()
    var active = mutableListOf<MapTrackPoint>()
    var precedingState: ActivityState? = null
    var pending: Pair<StopMarker, ActivityState?>? = null

    fun flush(followingState: ActivityState?) {
        if (active.isNotEmpty()) {
            val firstTimestamp = active.first().timestampMs
            val lastTimestamp = active.last().timestampMs
            pending = StopMarker(
                point = active[active.size / 2],
                durationMs = (lastTimestamp - firstTimestamp).coerceAtLeast(0L),
                confidence = active.averageConfidence(ActivityState.STILL),
            ) to precedingState
            active = mutableListOf()
        }
        val (marker, before) = pending ?: return
        pending = null
        if (marker.durationMs < MIN_RIDER_STOP_MS) return
        if (before == ActivityState.LIKELY_MOTORIZED &&
            followingState == ActivityState.LIKELY_MOTORIZED
        ) {
            return
        }
        markers += marker
    }

    for (point in this) {
        if (point.activityState != ActivityState.STILL) {
            flush(point.activityState)
            precedingState = point.activityState
            continue
        }
        if (active.isNotEmpty() && !active.last().isSemanticallyContinuousWith(point)) {
            flush(null)
        }
        active += point
    }
    flush(null)
    return markers
}

private fun MapTrackPoint.isSemanticallyContinuousWith(next: MapTrackPoint): Boolean {
    if (sectionId != next.sectionId) return false
    val deltaMs = next.timestampMs - timestampMs
    return deltaMs in 0..SEMANTIC_TRACK_MAX_GAP_MS
}

private fun List<MapTrackPoint>.averageConfidence(state: ActivityState?): Double? {
    val values = mapNotNull { point ->
        point.activityConfidence
            ?.takeIf { point.activityState == state && it.isFinite() }
            ?.coerceIn(0.0, 1.0)
    }
    return values.takeIf { it.isNotEmpty() }?.average()
}

internal fun List<MapTrackPoint>.toSemanticLineFeatureCollectionOrNull(): FeatureCollection? {
    val features = semanticLineRuns().map { run ->
        Feature.fromGeometry(
            LineString.fromLngLats(
                run.points.map { point -> Point.fromLngLat(point.lon, point.lat) },
            ),
        ).apply {
            addStringProperty(ACTIVITY_STATE_PROPERTY, run.activityState.styleKey())
            addNumberProperty(
                ACTIVITY_CONFIDENCE_PROPERTY,
                run.points.averageConfidence(run.activityState) ?: 0.0,
            )
        }
    }
    return features.takeIf { it.isNotEmpty() }?.let(FeatureCollection::fromFeatures)
}

internal fun List<MapTrackPoint>.toStopFeatureCollectionOrNull(): FeatureCollection? {
    val features = aggregatedStopMarkers().map { marker ->
        Feature.fromGeometry(Point.fromLngLat(marker.point.lon, marker.point.lat)).apply {
            addNumberProperty(STOP_DURATION_PROPERTY, marker.durationMs)
            addNumberProperty(ACTIVITY_CONFIDENCE_PROPERTY, marker.confidence ?: 0.0)
        }
    }
    return features.takeIf { it.isNotEmpty() }?.let(FeatureCollection::fromFeatures)
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

internal fun List<MapTrackPoint>.toPointFeatureCollectionOrNull(): FeatureCollection? =
    takeIf { it.isNotEmpty() }?.let { points ->
        FeatureCollection.fromFeatures(
            points.map { point ->
                Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).apply {
                    addStringProperty(ACTIVITY_STATE_PROPERTY, point.activityState.styleKey())
                    addNumberProperty(
                        ACTIVITY_CONFIDENCE_PROPERTY,
                        point.activityConfidence
                            ?.takeIf(Double::isFinite)
                            ?.coerceIn(0.0, 1.0)
                            ?: 0.0,
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
        Expression.stop(15.0, Expression.color(colors.weak.toArgb())),
        Expression.stop(20.0, Expression.color(colors.weak.toArgb())),
        // Fusion rejects fixes above 20 m, but Compare still shows every raw
        // sample. Use a sharp red boundary only for those rejected fixes so
        // the accepted accuracy scale stays distinct from the green track.
        Expression.stop(20.0001, Expression.color(colors.rejected.toArgb())),
    )

private fun activityStateFilter(stateKey: String): Expression =
    Expression.eq(
        Expression.get(ACTIVITY_STATE_PROPERTY),
        Expression.literal(stateKey),
    )

private fun activityStateColorExpression(
    colors: ActivityStateColors,
    fallback: Color,
): Expression = Expression.match(
    Expression.get(ACTIVITY_STATE_PROPERTY),
    Expression.literal(ACTIVITY_STATE_DOWNHILL),
    Expression.color(colors.downhill.toArgb()),
    Expression.literal(ACTIVITY_STATE_TRANSIT),
    Expression.color(colors.transit.toArgb()),
    Expression.literal(ACTIVITY_STATE_LIKELY_MOTORIZED),
    Expression.color(colors.likelyMotorized.toArgb()),
    Expression.literal(ACTIVITY_STATE_STILL),
    Expression.color(colors.still.toArgb()),
    Expression.literal(ACTIVITY_STATE_UNKNOWN),
    Expression.color(colors.unknown.toArgb()),
    Expression.color(fallback.toArgb()),
)

/// Duration still sets the size, but over a much narrower range: the point is
/// to tell a pause from a lunch break, not to draw a target over the trail.
private fun stopRadiusExpression(): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.get(STOP_DURATION_PROPERTY),
        Expression.stop(0.0, 2.5),
        Expression.stop(30_000.0, 3.5),
        Expression.stop(300_000.0, 5.5),
    )

private fun fusionPointRadiusExpression(): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(FUSION_POINTS_MIN_ZOOM.toDouble(), 0.6),
        Expression.stop(19.0, 1.0),
        Expression.stop(20.0, 1.5),
    )

internal fun diagnosticLineOptions(): GeoJsonOptions =
    // GeoJSON-VT simplifies line geometry by default, while our separate point
    // sources retain every coordinate. Diagnostics must render both from the
    // exact same vertices even at maximum zoom.
    GeoJsonOptions().withTolerance(0f)

private fun gpsPointRadiusExpression(): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(14.0, 0.9),
        Expression.stop(16.0, 1.4),
        Expression.stop(18.0, 3.25),
        Expression.stop(20.0, 4.0),
    )

private fun gpsPointStrokeExpression(): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(14.0, 0.4),
        Expression.stop(18.0, 1.0),
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
    FUSED_LINE_LAYER_IDS.forEach { layerId ->
        style.getLayer(layerId)?.setProperties(
            PropertyFactory.visibility(
                if (mode == TrackMode.Gps) Property.NONE else Property.VISIBLE,
            ),
        )
    }
    style.getLayer(FUSED_CASING_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(
            if (mode == TrackMode.Gps) Property.NONE else Property.VISIBLE,
        ),
    )
    style.getLayer(FUSED_POINTS_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(
            if (mode == TrackMode.Gps) Property.NONE else Property.VISIBLE,
        ),
    )
    style.getLayer(STOP_LAYER_ID)?.setProperties(
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

internal fun cameraBoundsPoints(
    mode: TrackMode,
    raw: List<MapTrackPoint>,
    fused: List<MapTrackPoint>,
): List<MapTrackPoint> = when (mode) {
    TrackMode.Gps -> raw.acceptedGpsBoundsPoints()
    TrackMode.Fusion -> fused.ifEmpty { raw.acceptedGpsBoundsPoints() }
    TrackMode.Compare -> fused.ifEmpty { raw.acceptedGpsBoundsPoints() }
}

private fun List<MapTrackPoint>.acceptedGpsBoundsPoints(): List<MapTrackPoint> {
    val accepted = filter { point ->
        point.accuracyM?.let { it.isFinite() && it in 0.0..20.0 } == true
    }
    return accepted.ifEmpty { this }
}

private fun createStartMarker(palette: NakvaliMapPalette): Bitmap =
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

private fun createFinishMarker(palette: NakvaliMapPalette): Bitmap =
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
