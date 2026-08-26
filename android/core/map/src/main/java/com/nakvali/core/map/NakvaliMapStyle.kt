package com.nakvali.core.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.util.Locale
import java.util.WeakHashMap
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer

const val NAKVALI_MAP_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

data class NakvaliMapPalette(
    val dark: Boolean,
    val background: Int,
    val land: Int,
    val landMuted: Int,
    val vegetation: Int,
    val vegetationStrong: Int,
    val water: Int,
    val waterLine: Int,
    val building: Int,
    val road: Int,
    val majorRoad: Int,
    val roadCasing: Int,
    val trail: Int,
    val label: Int,
    val labelHalo: Int,
    val boundary: Int,
    val primary: Int,
    val primaryContainer: Int,
    val onPrimary: Int,
)

@Composable
fun rememberNakvaliMapPalette(): NakvaliMapPalette {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    return remember(colors, dark) {
        NakvaliMapPalette(
            dark = dark,
            background = colors.background.toArgb(),
            land = colors.surfaceContainerLow.toArgb(),
            landMuted = colors.surfaceContainer.toArgb(),
            // Woodland is written down rather than borrowed from the scheme's
            // tertiary. Tertiary is the app's ochre signal — armed, warming up,
            // degraded — and once it stopped being a sage green, every forest on
            // the map turned the colour of dry earth. Vegetation is not a signal
            // and should not move when the signal palette does.
            vegetation = if (dark) Color(0xFF16241A).toArgb() else Color(0xFFDCE8D6).toArgb(),
            vegetationStrong = if (dark) {
                Color(0xFF2C4632).toArgb()
            } else {
                Color(0xFFA9C2A0).toArgb()
            },
            water = if (dark) Color(0xFF203438).toArgb() else Color(0xFFD7E5E5).toArgb(),
            waterLine = if (dark) Color(0xFF557276).toArgb() else Color(0xFF668285).toArgb(),
            building = colors.surfaceContainerHighest.toArgb(),
            road = colors.outline.toArgb(),
            // Keep the base map earthy and neutral so the green canonical
            // track cannot disappear into roads or terrain at riding zooms.
            majorRoad = if (dark) Color(0xFF4A3932).toArgb() else Color(0xFFE7D3C9).toArgb(),
            roadCasing = colors.surfaceContainerLowest.toArgb(),
            trail = colors.tertiary.toArgb(),
            label = colors.onSurface.toArgb(),
            labelHalo = colors.surface.toArgb(),
            boundary = colors.outlineVariant.toArgb(),
            primary = colors.primary.toArgb(),
            primaryContainer = colors.primaryContainer.toArgb(),
            onPrimary = colors.onPrimary.toArgb(),
        )
    }
}

fun Style.applyNakvaliMapPalette(palette: NakvaliMapPalette) {
    setLayer("background", PropertyFactory.backgroundColor(palette.background))
    setLayer(
        "natural_earth",
        PropertyFactory.rasterSaturation(-0.75f),
        PropertyFactory.rasterContrast(if (palette.dark) 0.18f else -0.05f),
        PropertyFactory.rasterBrightnessMin(if (palette.dark) 0.05f else 0.72f),
        PropertyFactory.rasterBrightnessMax(if (palette.dark) 0.34f else 0.98f),
    )
    setLayer("landuse_residential", PropertyFactory.fillColor(palette.landMuted), PropertyFactory.fillOpacity(0.68f))
    setLayer("park", PropertyFactory.fillColor(palette.vegetation), PropertyFactory.fillOpacity(0.62f))
    setLayer("park_outline", PropertyFactory.lineColor(palette.vegetationStrong), PropertyFactory.lineOpacity(0.38f))
    listOf("landcover_wood", "landcover_grass", "landcover_wetland").forEach { id ->
        setLayer(id, PropertyFactory.fillColor(palette.vegetation), PropertyFactory.fillOpacity(0.52f))
    }
    listOf("landuse_pitch", "landuse_track", "landuse_cemetery", "landuse_hospital", "landuse_school").forEach { id ->
        setLayer(id, PropertyFactory.fillColor(palette.landMuted), PropertyFactory.fillOpacity(0.76f))
    }
    setLayer("landcover_sand", PropertyFactory.fillColor(palette.majorRoad), PropertyFactory.fillOpacity(0.42f))
    setLayer("water", PropertyFactory.fillColor(palette.water))
    listOf("waterway_tunnel", "waterway_river", "waterway_other").forEach { id ->
        setLayer(id, PropertyFactory.lineColor(palette.waterLine), PropertyFactory.lineOpacity(0.82f))
    }
    setLayer("building", PropertyFactory.fillColor(palette.building), PropertyFactory.fillOpacity(0.82f))
    setLayer(
        "building-3d",
        PropertyFactory.fillExtrusionColor(palette.building),
        PropertyFactory.fillExtrusionOpacity(0.9f),
    )
    listOf("boundary_2", "boundary_3", "boundary_disputed").forEach { id ->
        setLayer(id, PropertyFactory.lineColor(palette.boundary), PropertyFactory.lineOpacity(0.62f))
    }

    layers.filterIsInstance<LineLayer>().filter { it.id.endsWith("_casing") }.forEach { layer ->
        layer.setProperties(PropertyFactory.lineColor(palette.roadCasing), PropertyFactory.lineOpacity(0.86f))
    }
    layers.filterIsInstance<LineLayer>().filter { layer ->
        (layer.id.startsWith("road_") || layer.id.startsWith("bridge_") || layer.id.startsWith("tunnel_")) &&
            !layer.id.endsWith("_casing") &&
            !layer.id.contains("rail") &&
            !layer.id.contains("arrow")
    }.forEach { layer ->
        val color = when {
            layer.id.contains("path_pedestrian") || layer.id.contains("service_track") -> palette.trail
            layer.id.contains("motorway") || layer.id.contains("trunk_primary") -> palette.majorRoad
            else -> palette.road
        }
        layer.setProperties(PropertyFactory.lineColor(color), PropertyFactory.lineOpacity(0.88f))
    }
    layers.filterIsInstance<LineLayer>().filter { it.id.contains("rail") }.forEach { layer ->
        layer.setProperties(PropertyFactory.lineColor(palette.boundary), PropertyFactory.lineOpacity(0.6f))
    }
    // Apply contrast to every symbol layer: city styles do not consistently use
    // "label" in layer IDs, and missed road/POI text becomes illegible on the
    // themed building and land colors.
    layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        layer.setProperties(
            PropertyFactory.textColor(palette.label),
            PropertyFactory.textHaloColor(palette.labelHalo),
            PropertyFactory.textHaloWidth(2.25f),
            PropertyFactory.textHaloBlur(0.15f),
        )
    }
}

/**
 * How much of the basemap's own content the map is showing.
 *
 * The basemap is the largest surface Nakvali owns, and out of the box it was a
 * general-purpose city map: cafés, restaurants, shop pins and street shields on
 * a downhill mountain-bike app. Neither mode is "all labels off" — a rider does
 * need to know which trail they are on — but what counts as useful changes
 * completely between planning a ride and riding it.
 */
enum class NakvaliMapDetail {
    /** Browsing or authoring: rider-relevant points of interest, full labels. */
    Browse,

    /** Recording: track, terrain and trail names only. Everything else is noise. */
    Instrument,
}

/**
 * OpenMapTiles `poi.class` values worth showing a mountain biker.
 *
 * The style's own POI layers filter on `rank`, which is a measure of how
 * prominent something is in a town centre — exactly the wrong axis here. A
 * bike shop, a water tap, a lift station and a car park matter on a trail day;
 * a rank-20 restaurant does not.
 */
private val RIDER_POI_CLASSES = listOf(
    "parking",
    "bicycle",
    "drinking_water",
    "toilets",
    "shelter",
    "picnic_site",
    "information",
    "attraction",
    "lodging",
    "hospital",
    "pharmacy",
    "fuel",
    "aerialway",
)

/** POI layers in the Liberty style, all reading the `poi` source layer. */
private val POI_LAYERS = listOf("poi_r20", "poi_r7", "poi_r1", "poi_transit")

/** Labels a rider is still glad to have while moving. */
private val INSTRUMENT_KEEP_LABELS = setOf(
    "highway-name-path",
    "water_name_point_label",
    "water_name_line_label",
    "label_village",
    "label_town",
)

/** Symbol layers hidden while recording: street names, shields, place hierarchy. */
private val INSTRUMENT_HIDE_LABELS = listOf(
    "road_one_way_arrow",
    "road_one_way_arrow_opposite",
    "highway-name-minor",
    "highway-name-major",
    "highway-shield-non-us",
    "highway-shield-us-interstate",
    "road_shield_us",
    "waterway_line_label",
    "airport",
    "label_other",
    "label_state",
    "label_city",
    "label_city_capital",
    "label_country_1",
    "label_country_2",
    "label_country_3",
)

fun Style.applyNakvaliMapDetail(detail: NakvaliMapDetail) {
    when (detail) {
        NakvaliMapDetail.Browse -> {
            POI_LAYERS.forEach { id ->
                val layer = getLayer(id) as? SymbolLayer ?: return@forEach
                layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                layer.setFilter(riderPoiFilter(layer))
            }
            (INSTRUMENT_HIDE_LABELS + INSTRUMENT_KEEP_LABELS).forEach { id ->
                setLayer(id, PropertyFactory.visibility(Property.VISIBLE))
            }
        }

        NakvaliMapDetail.Instrument -> {
            POI_LAYERS.forEach { id -> setLayer(id, PropertyFactory.visibility(Property.NONE)) }
            INSTRUMENT_HIDE_LABELS.forEach { id ->
                setLayer(id, PropertyFactory.visibility(Property.NONE))
            }
            INSTRUMENT_KEEP_LABELS.forEach { id ->
                setLayer(id, PropertyFactory.visibility(Property.VISIBLE))
            }
        }
    }
}

/**
 * The layer's own filter, narrowed to [RIDER_POI_CLASSES].
 *
 * Kept per layer rather than replaced wholesale so the style's zoom and
 * geometry-type conditions survive; only the class allowlist is ours. The
 * filter is idempotent — reapplying it wraps an already-narrowed filter, which
 * still evaluates the same — so a repeated style pass costs nothing but a
 * slightly deeper expression.
 */
private fun riderPoiFilter(layer: SymbolLayer): Expression {
    val allowed = Expression.any(
        *RIDER_POI_CLASSES
            .map { Expression.eq(Expression.get("class"), it) }
            .toTypedArray(),
    )
    val existing = layer.filter ?: return allowed
    return Expression.all(existing, allowed)
}

// Style-fallback bookkeeping is main-thread only (MapView callbacks); one
// listener per MapView, dropped automatically with the view.
private val styleFallbacks = WeakHashMap<MapView, NakvaliStyleFallback>()

/**
 * Loads the Nakvali remote style with an offline fallback. If the style document
 * itself cannot be loaded (offline with a cold ambient cache), a local
 * background-only style is applied instead and [onStyleReady] still runs — so
 * track overlays (polylines, markers, position) never depend on network or
 * tile availability. Failed tile fetches inside a successfully loaded style do
 * not trigger the fallback; MapLibre keeps rendering whatever is cached.
 */
fun MapView.setNakvaliMapStyle(
    map: MapLibreMap,
    palette: NakvaliMapPalette,
    detail: NakvaliMapDetail = NakvaliMapDetail.Browse,
    onStyleReady: (Style) -> Unit,
) {
    val fallback = styleFallbacks.getOrPut(this) {
        NakvaliStyleFallback().also(::addOnDidFailLoadingMapListener)
    }
    fallback.pending = {
        // OnDidFailLoadingMap can also fire for individual failed resources;
        // only replace the style when none ever loaded.
        if (map.style == null) {
            map.setStyle(Style.Builder().fromJson(fallbackStyleJson(palette))) { style ->
                style.applyNakvaliMapPalette(palette)
                onStyleReady(style)
            }
        }
    }
    map.setStyle(Style.Builder().fromUri(NAKVALI_MAP_STYLE_URI)) { style ->
        fallback.pending = null
        style.applyNakvaliMapPalette(palette)
        style.applyNakvaliMapDetail(detail)
        onStyleReady(style)
    }
}

/** One-shot per style attempt so repeated resource errors cannot loop. */
private class NakvaliStyleFallback : MapView.OnDidFailLoadingMapListener {
    var pending: (() -> Unit)? = null

    override fun onDidFailLoadingMap(errorMessage: String) {
        pending?.invoke()
        pending = null
    }
}

/**
 * Minimal valid local style: a quiet themed background with no remote sources,
 * so it always loads synchronously and the overlay layers have a canvas.
 */
private fun fallbackStyleJson(palette: NakvaliMapPalette): String {
    val background = String.format(Locale.US, "#%06X", 0xFFFFFF and palette.background)
    return """{"version":8,"name":"nakvali-offline-fallback","sources":{},""" +
        """"layers":[{"id":"background","type":"background","paint":{"background-color":"$background"}}]}"""
}

fun MapLibreMap.configureNakvaliMapChrome(
    palette: NakvaliMapPalette,
    bottomMarginPx: Int,
    edgeMarginPx: Int,
) {
    uiSettings.apply {
        setLogoEnabled(false)
        setAttributionTintColor(palette.primary)
        setAttributionMargins(
            edgeMarginPx,
            edgeMarginPx,
            edgeMarginPx,
            bottomMarginPx + edgeMarginPx,
        )
    }
}

private fun Style.setLayer(id: String, vararg properties: PropertyValue<*>) {
    getLayer(id)?.setProperties(*properties)
}
