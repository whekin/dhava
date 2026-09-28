package com.nakvali.feature.activity

import com.nakvali.core.ui.air
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.ui.Alignment
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
import com.nakvali.fusion.AirtimeWindow
import com.nakvali.fusion.isLikelyJump
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
private const val SEGMENT_SOURCE_ID = "segment-runs-source"
private const val SEGMENT_HALO_LAYER_ID = "segment-runs-halo-layer"
private const val SEGMENT_LAYER_ID = "segment-runs-layer"
private const val SEGMENT_LABEL_LAYER_ID = "segment-runs-label-layer"
private const val SEGMENT_NAME_PROPERTY = "name"
private const val SEGMENT_GATES_SOURCE_ID = "segment-gates-source"
private const val SEGMENT_GATES_LAYER_ID = "segment-gates-layer"
private const val SEGMENT_GATE_PROPERTY = "gate"
private const val SEGMENT_GATE_START = "start"
private const val SEGMENT_GATE_FINISH = "finish"
/**
 * The glyph stack Liberty ships. A name the style has no glyphs for simply
 * does not draw, which is why this is pinned rather than left to the default.
 */
private val SEGMENT_LABEL_FONT = arrayOf("Noto Sans Regular")
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
private const val AIRTIME_SOURCE_ID = "track-airtime-source"
private const val AIRTIME_LAYER_ID = "track-airtime-layer"
private const val AIRTIME_INDEX_PROPERTY = "airtime_index"
private const val AIRTIME_SELECTED_SOURCE_ID = "track-airtime-selected-source"
private const val AIRTIME_SELECTED_LAYER_ID = "track-airtime-selected-layer"
private const val AIRTIME_SPAN_SOURCE_ID = "track-airtime-span-source"
private const val AIRTIME_SPAN_LAYER_ID = "track-airtime-span-layer"
private const val AIRTIME_OVERVIEW_SOURCE_ID = "track-airtime-overview-source"
private const val AIRTIME_OVERVIEW_DOT_LAYER_ID = "track-airtime-overview-dot-layer"
private const val AIRTIME_OVERVIEW_COUNT_LAYER_ID = "track-airtime-overview-count-layer"
private const val AIRTIME_COUNT_PROPERTY = "airtime_count"
private const val AIRTIME_LABEL_PROPERTY = "airtime_label"
private const val AIRTIME_DURATION_PROPERTY = "airtime_duration_ms"
private const val AIRTIME_TOTAL_PROPERTY = "airtime_total_ms"
private const val AIRTIME_FLIGHT_SOURCE_ID = "track-airtime-flight-source"
private const val AIRTIME_FLIGHT_CASING_LAYER_ID = "track-airtime-flight-casing-layer"
private const val AIRTIME_FLIGHT_LAYER_ID = "track-airtime-flight-layer"
private const val AIRTIME_LABEL_LAYER_ID = "track-airtime-label-layer"
private const val AIRTIME_OVERVIEW_LABEL_MIN_MS = 300.0
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
private const val AIRTIME_MARKERS_MIN_ZOOM = 17f
private val AIRTIME_OVERVIEW_SPACING = 36.dp
internal const val SEMANTIC_TRACK_MAX_GAP_MS = 3_000L
// Transport GPS normally runs at 5 seconds; allow cadence jitter, not outages.
private const val TRANSPORT_DISPLAY_MAX_GAP_MS = 7_500L

/** Below this, confirmed stillness is not an event worth a map marker. */
internal const val MIN_RIDER_STOP_MS = 60_000L
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
    // Draft transport geometry uses the selected color without assigning a saved state.
    val isTransportPreview: Boolean = false,
)

/** One timed run at an authored segment, ready to draw and to label. */
internal data class MapSegmentRun(
    val name: String,
    val points: List<MapTrackPoint>,
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

internal data class MapAirtimeCandidate(
    val eventIndex: Int,
    val start: MapTrackPoint,
    val end: MapTrackPoint,
    val startMs: Long,
    val durationMs: Long,
    val takeoffPeakG: Double?,
    val landingPeakG: Double,
    /** Takeoff to landing along the fused track, for drawing the flight itself. */
    val path: List<MapTrackPoint> = listOf(start, end),
    /** Long enough to count as a jump (Rust's `is_likely_jump`). */
    val jump: Boolean = true,
)

internal data class AirtimeOverviewMarker(
    val candidate: MapAirtimeCandidate,
    /** Jumps in this screen-space group; short candidates are not counted. */
    val count: Int,
    /** Summed airtime of those jumps. */
    val totalMs: Long = if (candidate.jump) candidate.durationMs else 0,
)

/** Raw and replayed live tracks on one map; all computation remains in Rust. */
@Composable
internal fun TrackMap(
    rawPoints: List<MapTrackPoint>,
    fusedPoints: List<MapTrackPoint>,
    mode: TrackMode,
    rawColor: Color,
    fusedColor: Color,
    segmentRuns: List<MapSegmentRun> = emptyList(),
    segmentColor: Color = Color.Unspecified,
    airtimeCandidates: List<MapAirtimeCandidate> = emptyList(),
    selectedAirtimeIndex: Int? = null,
    onAirtimeSelected: (Int?) -> Unit = {},
    inspectedPoint: MapTrackPoint? = null,
    overlayBottomPadding: androidx.compose.ui.unit.Dp = 240.dp,
    respectUserCamera: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberNakvaliMapView()
    val palette = rememberNakvaliMapPalette()
    val accuracyColors = rememberGpsAccuracyColors()
    val activityStateColors = rememberActivityStateColors()
    val overlayBottomPx = with(LocalDensity.current) { overlayBottomPadding.roundToPx() }
    val mapChromeMarginPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val overviewSpacingPx = with(LocalDensity.current) { AIRTIME_OVERVIEW_SPACING.toPx() }
    val currentMode = rememberUpdatedState(mode)
    val currentInspectedPoint = rememberUpdatedState(inspectedPoint)
    val currentData = rememberUpdatedState(Triple(rawPoints, fusedPoints, segmentRuns))
    val currentAirtime = rememberUpdatedState(airtimeCandidates)
    val currentSelectedAirtimeIndex = rememberUpdatedState(selectedAirtimeIndex)
    val currentOnAirtimeSelected = rememberUpdatedState(onAirtimeSelected)
    val rendering = remember(mapView) { TrackRenderingState() }
    var airtimeMarkersVisible by remember(mapView) { mutableStateOf(false) }
    val selectedAirtime = airtimeCandidates.firstOrNull { it.eventIndex == selectedAirtimeIndex }
    var styleReadyRevision by remember { mutableIntStateOf(0) }
    val styleKey = listOf(palette, rawColor, fusedColor, segmentColor, accuracyColors, activityStateColors)
    val gestureListener = remember(mapView) { MapLibreMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) rendering.userMoved = true
    } }
    val airtimeClickListener = remember(mapView) { MapLibreMap.OnMapClickListener { latLng ->
        val map = rendering.map ?: return@OnMapClickListener false
        if (map.style == null) return@OnMapClickListener false
        val screen = map.projection.toScreenLocation(latLng)
        val index = map.queryRenderedFeatures(
            RectF(screen.x - 20f, screen.y - 20f, screen.x + 20f, screen.y + 20f),
            AIRTIME_LAYER_ID,
        ).firstOrNull()?.getNumberProperty(AIRTIME_INDEX_PROPERTY)?.toInt()
        val eventIndex = index?.takeIf { found ->
            currentAirtime.value.any { it.eventIndex == found }
        }
        if (eventIndex != null) {
            currentOnAirtimeSelected.value(eventIndex)
            return@OnMapClickListener true
        }
        val overview = map.queryRenderedFeatures(
            RectF(screen.x - 18f, screen.y - 18f, screen.x + 18f, screen.y + 18f),
            AIRTIME_OVERVIEW_COUNT_LAYER_ID,
            AIRTIME_OVERVIEW_DOT_LAYER_ID,
        ).firstOrNull()
        val overviewIndex = overview?.getNumberProperty(AIRTIME_INDEX_PROPERTY)?.toInt()
        val overviewCount = overview?.getNumberProperty(AIRTIME_COUNT_PROPERTY)?.toInt() ?: 0
        if (overviewIndex != null && overviewCount > 0) {
            val candidate = currentAirtime.value.firstOrNull { it.eventIndex == overviewIndex }
                ?: return@OnMapClickListener false
            if (overviewCount > 1) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(candidate.start.lat, candidate.start.lon),
                    minOf(AIRTIME_MARKERS_MIN_ZOOM.toDouble(), map.cameraPosition.zoom + 2.0),
                ))
            } else {
                currentOnAirtimeSelected.value(overviewIndex)
            }
            return@OnMapClickListener true
        }
        currentOnAirtimeSelected.value(eventIndex)
        false
    } }
    val airtimeZoomListener = remember(mapView) { MapLibreMap.OnCameraIdleListener {
        rendering.map?.let { map ->
            airtimeMarkersVisible = map.cameraPosition.zoom >= AIRTIME_MARKERS_MIN_ZOOM
            updateAirtimeOverviewSource(map, currentAirtime.value, overviewSpacingPx)
        }
    } }
    DisposableEffect(mapView) {
        onDispose {
            rendering.disposed = true
            rendering.map?.removeOnCameraMoveStartedListener(gestureListener)
            rendering.map?.removeOnMapClickListener(airtimeClickListener)
            rendering.map?.removeOnCameraIdleListener(airtimeZoomListener)
        }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (respectUserCamera) FilledTonalIconButton(
            onClick = {
                rendering.userMoved = false
                rendering.map?.let { fitCamera(it, cameraBoundsPoints(currentMode.value, currentData.value.first, currentData.value.second)) }
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 84.dp, end = 12.dp),
        ) { Icon(Icons.Default.CenterFocusStrong, "Fit activity") }
        selectedAirtime?.takeIf { airtimeMarkersVisible }?.let { candidate ->
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = overlayBottomPadding + 12.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "POSSIBLE AIR · ${candidate.eventIndex + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.air,
                    )
                    Text(formatAirSeconds(candidate.durationMs), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Raw phone peak: before ${formatPhoneG(candidate.takeoffPeakG)} · " +
                            "after ${formatPhoneG(candidate.landingPeakG)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Swipe up for event details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    LaunchedEffect(mapView, styleReadyRevision, selectedAirtimeIndex, airtimeCandidates) {
        val candidate = airtimeCandidates.firstOrNull { it.eventIndex == selectedAirtimeIndex }
        mapView.getMapAsync { map ->
            if (map.style?.getSource(AIRTIME_SOURCE_ID) == null) return@getMapAsync
            map.style?.getSourceAs<GeoJsonSource>(AIRTIME_SELECTED_SOURCE_ID)
                ?.setPointOrEmpty(candidate?.start)
            map.style?.getSourceAs<GeoJsonSource>(AIRTIME_SPAN_SOURCE_ID)?.setGeoJson(
                candidate?.toSpanLine()?.toJson() ?: EMPTY_FEATURE_COLLECTION,
            )
            if (candidate == null) return@getMapAsync
            if (!rendering.disposed) {
                val targetZoom = maxOf(map.cameraPosition.zoom, AIRTIME_MARKERS_MIN_ZOOM.toDouble() + 1.0)
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(candidate.start.lat, candidate.start.lon), targetZoom),
                )
            }
        }
    }

    LaunchedEffect(
        mapView,
        styleReadyRevision,
        rawPoints,
        fusedPoints,
        rawColor,
        fusedColor,
        segmentRuns,
        airtimeCandidates,
        segmentColor,
        palette,
        accuracyColors,
        activityStateColors,
    ) {
        mapView.getMapAsync { map ->
            if (rendering.disposed) return@getMapAsync
            if (rendering.map == null) {
                rendering.map = map
                map.addOnCameraMoveStartedListener(gestureListener)
                map.addOnMapClickListener(airtimeClickListener)
                map.addOnCameraIdleListener(airtimeZoomListener)
            }
            val currentStyle = map.style
            if (rendering.styleKey == styleKey && currentStyle?.getSource(RAW_SOURCE_ID) != null) {
                val data = Triple(rawPoints, fusedPoints, segmentRuns)
                if (rendering.data != data || rendering.airtimeCandidates != airtimeCandidates) {
                    updateTrackSources(currentStyle, rawPoints, fusedPoints, segmentRuns)
                    currentStyle.getSourceAs<GeoJsonSource>(AIRTIME_SOURCE_ID)?.setGeoJson(
                        airtimeCandidates.toAirtimeFeatureCollectionOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION,
                    )
                    currentStyle.getSourceAs<GeoJsonSource>(AIRTIME_FLIGHT_SOURCE_ID)
                        ?.setGeoJson(airtimeCandidates.toAirtimeFlightFeatureCollection())
                    updateAirtimeOverviewSource(map, airtimeCandidates, overviewSpacingPx)
                    applyMode(currentStyle, currentMode.value, rawPoints, fusedPoints)
                    if (!respectUserCamera || !rendering.userMoved) fitCamera(map, cameraBoundsPoints(currentMode.value, rawPoints, fusedPoints), if (respectUserCamera) 250 else 1_000)
                    rendering.data = data
                    rendering.airtimeCandidates = airtimeCandidates
                }
                return@getMapAsync
            }
            if (rendering.loadingStyle) return@getMapAsync
            rendering.loadingStyle = true
            @Suppress("DEPRECATION")
            map.setPadding(0, 0, 0, overlayBottomPx)
            map.configureNakvaliMapChrome(palette, overlayBottomPx, mapChromeMarginPx)
            // Fallback-aware: track layers are added even when the remote
            // style cannot load offline, so recorded lines always render.
            mapView.setNakvaliMapStyle(map, palette) { style ->
                if (rendering.disposed) return@setNakvaliMapStyle
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
                // A segment gets its own colour on the line itself, plus a
                // narrow glow under the track so the stretch still stands out
                // when the line is thin at ride-overview zoom.
                style.addSource(GeoJsonSource(SEGMENT_SOURCE_ID).also { source ->
                    segmentRuns.toFeatureCollectionOrNull()?.let(source::setGeoJson)
                })
                style.addLayer(
                    LineLayer(SEGMENT_HALO_LAYER_ID, SEGMENT_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(segmentColor.toArgb()),
                        PropertyFactory.lineWidth(12f),
                        PropertyFactory.lineOpacity(0.22f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
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
                style.addLayer(
                    // Above every state line: on a segment, "this is a segment"
                    // is what the rider came to see, and the state underneath is
                    // still legible from the glow and from the sheet.
                    LineLayer(SEGMENT_LAYER_ID, SEGMENT_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(segmentColor.toArgb()),
                        PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addSource(GeoJsonSource(FUSED_POINTS_SOURCE_ID).also { source ->
                    fusedPoints.toVisibleFusionPointFeatureCollectionOrNull()?.let(source::setGeoJson)
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
                style.addSource(GeoJsonSource(AIRTIME_FLIGHT_SOURCE_ID).also { source ->
                    source.setGeoJson(airtimeCandidates.toAirtimeFlightFeatureCollection())
                })
                style.addSource(GeoJsonSource(AIRTIME_SOURCE_ID).also { source ->
                    airtimeCandidates.toAirtimeFeatureCollectionOrNull()?.let(source::setGeoJson)
                })
                style.addSource(GeoJsonSource(AIRTIME_OVERVIEW_SOURCE_ID))
                style.addSource(GeoJsonSource(AIRTIME_SELECTED_SOURCE_ID))
                style.addSource(GeoJsonSource(AIRTIME_SPAN_SOURCE_ID))
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
                    CircleLayer(STOP_LAYER_ID, STOP_SOURCE_ID).also { it.setMinZoom(15f) }.withProperties(
                        PropertyFactory.circleColor(palette.roadCasing),
                        PropertyFactory.circleOpacity(0.35f),
                        PropertyFactory.circleRadius(stopRadiusExpression()),
                        PropertyFactory.circleStrokeColor(activityStateColors.still.toArgb()),
                        PropertyFactory.circleStrokeWidth(1.2f),
                        PropertyFactory.circleStrokeOpacity(0.75f),
                    ),
                )
                // Airtime has its own ink (the list's jump band uses the same)
                // so it never reads as more green track. Once zoomed in, the
                // flight itself is drawn over the stretch the bike was in the
                // air, as thick as it was long.
                style.addLayer(
                    LineLayer(AIRTIME_FLIGHT_CASING_LAYER_ID, AIRTIME_FLIGHT_SOURCE_ID)
                        .also { it.setMinZoom(15f) }
                        .withProperties(
                            PropertyFactory.lineColor(palette.background),
                            PropertyFactory.lineOpacity(0.85f),
                            PropertyFactory.lineWidth(airtimeFlightWidthExpression(extra = 3.0)),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                )
                style.addLayer(
                    LineLayer(AIRTIME_FLIGHT_LAYER_ID, AIRTIME_FLIGHT_SOURCE_ID)
                        .also { it.setMinZoom(15f) }
                        .withProperties(
                            PropertyFactory.lineColor(palette.air),
                            PropertyFactory.lineOpacity(airtimeOpacityExpression(AIRTIME_DURATION_PROPERTY)),
                            PropertyFactory.lineWidth(airtimeFlightWidthExpression()),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                )
                style.addLayer(
                    CircleLayer(AIRTIME_OVERVIEW_DOT_LAYER_ID, AIRTIME_OVERVIEW_SOURCE_ID)
                        .also { it.setMaxZoom(AIRTIME_MARKERS_MIN_ZOOM) }
                        .withProperties(
                            PropertyFactory.circleColor(palette.air),
                            PropertyFactory.circleOpacity(airtimeOpacityExpression(AIRTIME_TOTAL_PROPERTY)),
                            PropertyFactory.circleRadius(airtimeRadiusExpression(AIRTIME_TOTAL_PROPERTY)),
                            PropertyFactory.circleStrokeColor(palette.background),
                            PropertyFactory.circleStrokeWidth(1.5f),
                        ),
                )
                style.addLayer(
                    SymbolLayer(AIRTIME_OVERVIEW_COUNT_LAYER_ID, AIRTIME_OVERVIEW_SOURCE_ID)
                        .also { it.setMaxZoom(AIRTIME_MARKERS_MIN_ZOOM) }
                        .withProperties(
                            PropertyFactory.textField(Expression.get(AIRTIME_LABEL_PROPERTY)),
                            PropertyFactory.textFont(SEGMENT_LABEL_FONT),
                            PropertyFactory.textSize(11f),
                            PropertyFactory.textColor(palette.label),
                            PropertyFactory.textHaloColor(palette.labelHalo),
                            PropertyFactory.textHaloWidth(1.6f),
                            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                            PropertyFactory.textOffset(arrayOf(1.2f, 0f)),
                            // Labels give way when crowded; the discs never do.
                            PropertyFactory.textAllowOverlap(false),
                            PropertyFactory.textOptional(true),
                        ),
                )
                style.addLayer(
                    LineLayer(AIRTIME_SPAN_LAYER_ID, AIRTIME_SPAN_SOURCE_ID)
                        .also { it.setMinZoom(15f) }
                        .withProperties(
                            PropertyFactory.lineColor(palette.label),
                            PropertyFactory.lineWidth(2f),
                            PropertyFactory.lineOpacity(0.9f),
                            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        ),
                )
                style.addLayer(
                    CircleLayer(AIRTIME_SELECTED_LAYER_ID, AIRTIME_SELECTED_SOURCE_ID)
                        .also { it.setMinZoom(AIRTIME_MARKERS_MIN_ZOOM) }
                        .withProperties(
                            PropertyFactory.circleColor(palette.air),
                            PropertyFactory.circleOpacity(0.16f),
                            PropertyFactory.circleRadius(19f),
                            PropertyFactory.circleStrokeColor(palette.air),
                            PropertyFactory.circleStrokeWidth(2f),
                        ),
                )
                style.addLayer(
                    CircleLayer(AIRTIME_LAYER_ID, AIRTIME_SOURCE_ID)
                        .also { it.setMinZoom(AIRTIME_MARKERS_MIN_ZOOM) }
                        .withProperties(
                            PropertyFactory.circleColor(palette.air),
                            PropertyFactory.circleOpacity(airtimeOpacityExpression(AIRTIME_DURATION_PROPERTY)),
                            PropertyFactory.circleRadius(airtimeRadiusExpression(AIRTIME_DURATION_PROPERTY)),
                            PropertyFactory.circleStrokeColor(palette.background),
                            PropertyFactory.circleStrokeWidth(1.5f),
                        ),
                )
                style.addLayer(
                    SymbolLayer(AIRTIME_LABEL_LAYER_ID, AIRTIME_SOURCE_ID)
                        .also { it.setMinZoom(AIRTIME_MARKERS_MIN_ZOOM) }
                        .withProperties(
                            PropertyFactory.textField(Expression.get(AIRTIME_LABEL_PROPERTY)),
                            PropertyFactory.textFont(SEGMENT_LABEL_FONT),
                            PropertyFactory.textSize(11f),
                            PropertyFactory.textColor(palette.label),
                            PropertyFactory.textHaloColor(palette.labelHalo),
                            PropertyFactory.textHaloWidth(1.6f),
                            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                            PropertyFactory.textOffset(arrayOf(1.3f, 0f)),
                            PropertyFactory.textAllowOverlap(false),
                            PropertyFactory.textOptional(true),
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
                style.addSource(GeoJsonSource(SEGMENT_GATES_SOURCE_ID).also { source ->
                    segmentRuns.toGateFeatureCollectionOrNull()?.let(source::setGeoJson)
                })
                style.addLayer(
                    // Where the clock started and stopped. Filled at the start,
                    // hollow at the finish, so a run reads in the direction it
                    // was ridden without needing an arrow.
                    CircleLayer(SEGMENT_GATES_LAYER_ID, SEGMENT_GATES_SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(4.5f),
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get(SEGMENT_GATE_PROPERTY),
                                Expression.color(palette.background),
                                Expression.stop(SEGMENT_GATE_START, Expression.color(segmentColor.toArgb())),
                            ),
                        ),
                        PropertyFactory.circleStrokeColor(segmentColor.toArgb()),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                style.addLayer(
                    SymbolLayer(SEGMENT_LABEL_LAYER_ID, SEGMENT_SOURCE_ID).withProperties(
                        PropertyFactory.textField(Expression.get(SEGMENT_NAME_PROPERTY)),
                        PropertyFactory.textFont(SEGMENT_LABEL_FONT),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textColor(segmentColor.toArgb()),
                        PropertyFactory.textHaloColor(palette.background),
                        PropertyFactory.textHaloWidth(1.6f),
                        PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                        PropertyFactory.symbolSpacing(320f),
                        PropertyFactory.textPadding(6f),
                        // Left to collide: a name that cannot fit on screen is
                        // better dropped than stamped over the trail it labels.
                        PropertyFactory.textAllowOverlap(false),
                        PropertyFactory.textIgnorePlacement(false),
                    ),
                )
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
                val latest = currentData.value
                updateTrackSources(style, latest.first, latest.second, latest.third)
                style.getSourceAs<GeoJsonSource>(AIRTIME_SOURCE_ID)?.setGeoJson(
                    currentAirtime.value.toAirtimeFeatureCollectionOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION,
                )
                style.getSourceAs<GeoJsonSource>(AIRTIME_FLIGHT_SOURCE_ID)
                    ?.setGeoJson(currentAirtime.value.toAirtimeFlightFeatureCollection())
                updateAirtimeOverviewSource(map, currentAirtime.value, overviewSpacingPx)
                val selectedCandidate = currentAirtime.value.firstOrNull {
                    it.eventIndex == currentSelectedAirtimeIndex.value
                }
                style.getSourceAs<GeoJsonSource>(AIRTIME_SELECTED_SOURCE_ID)
                    ?.setPointOrEmpty(selectedCandidate?.start)
                style.getSourceAs<GeoJsonSource>(AIRTIME_SPAN_SOURCE_ID)?.setGeoJson(
                    selectedCandidate?.toSpanLine()?.toJson() ?: EMPTY_FEATURE_COLLECTION,
                )
                applyMode(style, currentMode.value, latest.first, latest.second)
                if (!respectUserCamera || !rendering.userMoved) fitCamera(map, cameraBoundsPoints(currentMode.value, latest.first, latest.second), if (respectUserCamera) 250 else 1_000)
                rendering.loadingStyle = false
                rendering.data = latest
                rendering.airtimeCandidates = currentAirtime.value
                rendering.styleKey = styleKey
                styleReadyRevision++
            }
        }
    }

    LaunchedEffect(mapView, mode) {
        mapView.getMapAsync { map ->
            map.style?.let { style ->
                applyMode(style, mode, rawPoints, fusedPoints)
                if (!respectUserCamera || !rendering.userMoved) fitCamera(map, cameraBoundsPoints(mode, rawPoints, fusedPoints))
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

private class TrackRenderingState {
    var map: MapLibreMap? = null
    var loadingStyle = false
    var data: Triple<List<MapTrackPoint>, List<MapTrackPoint>, List<MapSegmentRun>>? = null
    var airtimeCandidates: List<MapAirtimeCandidate>? = null
    var styleKey: List<Any>? = null
    var userMoved = false
    var disposed = false
}

/** Data changes update sources in place; they do not reload the basemap. */
private fun updateTrackSources(style: Style, raw: List<MapTrackPoint>, fused: List<MapTrackPoint>, runs: List<MapSegmentRun>) {
    style.getSourceAs<GeoJsonSource>(RAW_SOURCE_ID)?.setGeoJson(raw.toMultiLineStringOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION)
    style.getSourceAs<GeoJsonSource>(RAW_POINTS_SOURCE_ID)?.setGeoJson(raw.toAccuracyFeatureCollectionOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION)
    style.getSourceAs<GeoJsonSource>(FUSED_SOURCE_ID)?.setGeoJson(fused.toSemanticLineFeatureCollectionOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION)
    style.getSourceAs<GeoJsonSource>(FUSED_POINTS_SOURCE_ID)?.setGeoJson(fused.toVisibleFusionPointFeatureCollectionOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION)
    style.getSourceAs<GeoJsonSource>(STOP_SOURCE_ID)?.setGeoJson(fused.toStopFeatureCollectionOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION)
    style.getSourceAs<GeoJsonSource>(SEGMENT_SOURCE_ID)?.setGeoJson(runs.toFeatureCollectionOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION)
    style.getSourceAs<GeoJsonSource>(SEGMENT_GATES_SOURCE_ID)?.setGeoJson(runs.toGateFeatureCollectionOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION)
}

/**
 * One feature per segment run, each carrying its name for the label layer.
 *
 * A run is already a contiguous slice of the finalized track, so unlike the
 * track itself it needs no pause splitting: a run spanning a manual pause
 * would never have been timed in the first place.
 */
/** The two gate crossings that bound each run, as points. */
private fun List<MapSegmentRun>.toGateFeatureCollectionOrNull(): FeatureCollection? {
    val features = filter { it.points.size >= 2 }.flatMap { run ->
        listOf(
            run.points.first() to SEGMENT_GATE_START,
            run.points.last() to SEGMENT_GATE_FINISH,
        ).map { (point, gate) ->
            Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).apply {
                addStringProperty(SEGMENT_GATE_PROPERTY, gate)
            }
        }
    }
    return features.takeIf { it.isNotEmpty() }?.let(FeatureCollection::fromFeatures)
}

private fun List<MapSegmentRun>.toFeatureCollectionOrNull(): FeatureCollection? {
    val features = filter { it.points.size >= 2 }.map { run ->
        val line = LineString.fromLngLats(run.points.map { Point.fromLngLat(it.lon, it.lat) })
        Feature.fromGeometry(line).apply { addStringProperty(SEGMENT_NAME_PROPERTY, run.name) }
    }
    return features.takeIf { it.isNotEmpty() }?.let(FeatureCollection::fromFeatures)
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
    val maxGapMs = if (
        (activityState == ActivityState.LIKELY_MOTORIZED &&
            next.activityState == ActivityState.LIKELY_MOTORIZED) ||
        (isTransportPreview && next.isTransportPreview)
    ) TRANSPORT_DISPLAY_MAX_GAP_MS else SEMANTIC_TRACK_MAX_GAP_MS
    return deltaMs in 0..maxGapMs
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

/** Stillness remains in the canonical track but does not form a bead cloud on the ride map. */
internal fun List<MapTrackPoint>.toVisibleFusionPointFeatureCollectionOrNull(): FeatureCollection? =
    filter { it.activityState != ActivityState.STILL }.toPointFeatureCollectionOrNull()

/** Only place an airborne candidate if both ends belong to one uninterrupted moving section. */
/** [isJump] is Rust's rule; it is a parameter only so JVM tests need no native library. */
internal fun List<MapTrackPoint>.placeAirtimeCandidates(
    windows: List<AirtimeWindow>,
    isJump: (Long) -> Boolean = ::isLikelyJump,
): List<MapAirtimeCandidate> =
    windows.mapIndexedNotNull { eventIndex, window ->
        if (window.durationMs <= 0 || window.startMs < 0 || window.startMs > Long.MAX_VALUE - window.durationMs) {
            return@mapIndexedNotNull null
        }
        val endMs = window.startMs + window.durationMs
        val firstEdge = (0 until lastIndex).firstOrNull { index ->
            timestampBrackets(index, window.startMs)
        } ?: return@mapIndexedNotNull null
        val lastEdge = (firstEdge until lastIndex).firstOrNull { index ->
            timestampBrackets(index, endMs)
        } ?: return@mapIndexedNotNull null
        val span = subList(firstEdge, lastEdge + 2)
        if (span.zipWithNext().any { (a, b) ->
                !a.isSemanticallyContinuousWith(b) ||
                    a.activityState == ActivityState.STILL || b.activityState == ActivityState.STILL ||
                    a.activityState == ActivityState.LIKELY_MOTORIZED ||
                    b.activityState == ActivityState.LIKELY_MOTORIZED
            }) return@mapIndexedNotNull null
        MapAirtimeCandidate(
            eventIndex = eventIndex,
            start = interpolatePosition(this[firstEdge], this[firstEdge + 1], window.startMs),
            end = interpolatePosition(this[lastEdge], this[lastEdge + 1], endMs),
            startMs = window.startMs,
            durationMs = window.durationMs,
            takeoffPeakG = window.takeoffPeakG,
            landingPeakG = window.landingPeakG,
        ).let { candidate ->
            candidate.copy(
                path = listOf(candidate.start) + subList(firstEdge + 1, lastEdge + 1) + candidate.end,
                jump = isJump(window.durationMs),
            )
        }
    }

private fun List<MapTrackPoint>.timestampBrackets(index: Int, timestampMs: Long): Boolean {
    val a = this[index]
    val b = this[index + 1]
    return a.timestampMs > 0 && timestampMs in a.timestampMs..b.timestampMs &&
        a.isSemanticallyContinuousWith(b)
}

private fun interpolatePosition(a: MapTrackPoint, b: MapTrackPoint, timestampMs: Long): MapTrackPoint {
    val fraction = if (a.timestampMs == b.timestampMs) 0.0 else
        (timestampMs - a.timestampMs).toDouble() / (b.timestampMs - a.timestampMs)
    return a.copy(
        lat = a.lat + (b.lat - a.lat) * fraction,
        lon = a.lon + (b.lon - a.lon) * fraction,
        timestampMs = timestampMs,
    )
}

internal fun List<MapAirtimeCandidate>.toAirtimeFeatureCollectionOrNull(): FeatureCollection? =
    takeIf { it.isNotEmpty() }?.let { candidates ->
        FeatureCollection.fromFeatures(candidates.map { candidate ->
            Feature.fromGeometry(Point.fromLngLat(candidate.start.lon, candidate.start.lat)).apply {
                addNumberProperty(AIRTIME_INDEX_PROPERTY, candidate.eventIndex)
                // Short candidates keep a speck on the map but none of the size,
                // brightness or label that a counted jump earns.
                addNumberProperty(AIRTIME_DURATION_PROPERTY, if (candidate.jump) candidate.durationMs else 0)
                addStringProperty(AIRTIME_LABEL_PROPERTY, if (candidate.jump) formatAirLabel(candidate.durationMs) else "")
            }
        })
    }

/** Every flight as the stretch of track the bike was in the air over. */
private fun List<MapAirtimeCandidate>.toAirtimeFlightFeatureCollection(): String =
    FeatureCollection.fromFeatures(mapNotNull { candidate ->
        candidate.path.takeIf { it.size >= 2 }?.let { path ->
            Feature.fromGeometry(LineString.fromLngLats(path.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addNumberProperty(AIRTIME_INDEX_PROPERTY, candidate.eventIndex)
                addNumberProperty(AIRTIME_DURATION_PROPERTY, if (candidate.jump) candidate.durationMs else 0)
            }
        }
    }).toJson()

private fun formatAirLabel(milliseconds: Long): String =
    String.format(Locale.US, "%.1f s", milliseconds / 1_000.0)

/** One small map hint per screen-space group, independent of GPS point density. */
internal fun clusterAirtimeOverview(
    candidates: List<MapAirtimeCandidate>,
    minSpacingPx: Float,
    project: (MapTrackPoint) -> Pair<Float, Float>,
): List<AirtimeOverviewMarker> {
    val markers = mutableListOf<AirtimeOverviewMarker>()
    val screenPositions = mutableListOf<Pair<Float, Float>>()
    val minDistanceSquared = minSpacingPx * minSpacingPx
    for (candidate in candidates) {
        val position = project(candidate.start)
        if (!position.first.isFinite() || !position.second.isFinite()) continue
        val nearby = screenPositions.indexOfFirst { anchor ->
            val dx = position.first - anchor.first
            val dy = position.second - anchor.second
            dx * dx + dy * dy < minDistanceSquared
        }
        val jumps = if (candidate.jump) 1 else 0
        val airMs = if (candidate.jump) candidate.durationMs else 0
        if (nearby >= 0) {
            markers[nearby] = markers[nearby].copy(
                count = markers[nearby].count + jumps,
                totalMs = markers[nearby].totalMs + airMs,
            )
        } else {
            markers += AirtimeOverviewMarker(candidate, jumps, airMs)
            screenPositions += position
        }
    }
    return markers
}

private fun List<AirtimeOverviewMarker>.toAirtimeOverviewFeatureCollectionOrNull(): FeatureCollection? =
    takeIf { it.isNotEmpty() }?.let { markers ->
        FeatureCollection.fromFeatures(markers.map { marker ->
            Feature.fromGeometry(
                Point.fromLngLat(marker.candidate.start.lon, marker.candidate.start.lat),
            ).apply {
                addNumberProperty(AIRTIME_INDEX_PROPERTY, marker.candidate.eventIndex)
                addNumberProperty(AIRTIME_COUNT_PROPERTY, marker.count)
                addNumberProperty(AIRTIME_TOTAL_PROPERTY, marker.totalMs)
                // Seconds in the air, not a count: a cluster of bunny hops and
                // one big step-down should not look alike.
                addStringProperty(
                    AIRTIME_LABEL_PROPERTY,
                    when {
                        marker.count == 0 || marker.totalMs < AIRTIME_OVERVIEW_LABEL_MIN_MS -> ""
                        marker.count == 1 -> formatAirLabel(marker.totalMs)
                        else -> "${formatAirLabel(marker.totalMs)} ×${if (marker.count > 99) "99+" else marker.count}"
                    },
                )
            }
        })
    }

private fun updateAirtimeOverviewSource(
    map: MapLibreMap,
    candidates: List<MapAirtimeCandidate>,
    minSpacingPx: Float,
) {
    val source = map.style?.getSourceAs<GeoJsonSource>(AIRTIME_OVERVIEW_SOURCE_ID) ?: return
    if (map.cameraPosition.zoom >= AIRTIME_MARKERS_MIN_ZOOM) {
        source.setGeoJson(EMPTY_FEATURE_COLLECTION)
        return
    }
    val markers = clusterAirtimeOverview(candidates, minSpacingPx) { point ->
        val screen = map.projection.toScreenLocation(LatLng(point.lat, point.lon))
        screen.x to screen.y
    }
    source.setGeoJson(markers.toAirtimeOverviewFeatureCollectionOrNull()?.toJson() ?: EMPTY_FEATURE_COLLECTION)
}

private fun MapAirtimeCandidate.toSpanLine(): LineString =
    LineString.fromLngLats(path.map { Point.fromLngLat(it.lon, it.lat) })

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

/**
 * Disc size follows time in the air, roughly by area: a 0.1 s hop over a root
 * is a speck, a second-long step-down or a run of doubles is unmissable.
 */
private fun airtimeRadiusExpression(property: String): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.get(property),
        Expression.stop(0.0, 2.5),
        Expression.stop(150.0, 3.0),
        Expression.stop(400.0, 5.0),
        Expression.stop(1_000.0, 7.0),
        Expression.stop(3_000.0, 10.0),
        Expression.stop(8_000.0, 13.5),
        Expression.stop(15_000.0, 17.0),
    )

/** Short hops sit back; long flights come forward. */
private fun airtimeOpacityExpression(property: String): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.get(property),
        Expression.stop(0.0, 0.55),
        Expression.stop(500.0, 0.85),
        Expression.stop(1_000.0, 1.0),
    )

/** Flight line width by duration, growing with zoom like the track does. */
private fun airtimeFlightWidthExpression(extra: Double = 0.0): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(15.0, Expression.interpolate(
            Expression.linear(), Expression.get(AIRTIME_DURATION_PROPERTY),
            Expression.stop(0.0, 2.5 + extra), Expression.stop(1_000.0, 6.0 + extra),
        )),
        Expression.stop(19.0, Expression.interpolate(
            Expression.linear(), Expression.get(AIRTIME_DURATION_PROPERTY),
            Expression.stop(0.0, 4.0 + extra), Expression.stop(1_000.0, 12.0 + extra),
        )),
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
    listOf(
        SEGMENT_HALO_LAYER_ID,
        SEGMENT_LAYER_ID,
        SEGMENT_GATES_LAYER_ID,
        SEGMENT_LABEL_LAYER_ID,
    ).forEach { layerId ->
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
    style.getLayer(AIRTIME_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(
            if (mode == TrackMode.Gps) Property.NONE else Property.VISIBLE,
        ),
    )
    listOf(
        AIRTIME_SELECTED_LAYER_ID,
        AIRTIME_SPAN_LAYER_ID,
        AIRTIME_OVERVIEW_DOT_LAYER_ID,
        AIRTIME_OVERVIEW_COUNT_LAYER_ID,
    ).forEach { layerId ->
        style.getLayer(layerId)?.setProperties(
            PropertyFactory.visibility(
                if (mode == TrackMode.Gps) Property.NONE else Property.VISIBLE,
            ),
        )
    }
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

/** A small upward chevron distinguishes possible airtime from stops and segment gates. */
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

private fun fitCamera(map: MapLibreMap, points: List<MapTrackPoint>, durationMs: Int = 1_000) {
    if (points.isEmpty()) return
    val distinct = points.mapTo(LinkedHashSet()) { it.lat to it.lon }
    if (distinct.size < 2) {
        val only = points.first()
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), SINGLE_POINT_ZOOM), durationMs)
        return
    }
    val bounds = LatLngBounds.Builder()
        .apply { points.forEach { include(LatLng(it.lat, it.lon)) } }
        .build()
    map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING_PX), durationMs)
}
