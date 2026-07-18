package com.dhava.core.map

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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer

const val DHAVA_MAP_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

data class DhavaMapPalette(
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
fun rememberDhavaMapPalette(): DhavaMapPalette {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    return remember(colors, dark) {
        DhavaMapPalette(
            dark = dark,
            background = colors.background.toArgb(),
            land = colors.surfaceContainerLow.toArgb(),
            landMuted = colors.surfaceContainer.toArgb(),
            vegetation = colors.tertiaryContainer.toArgb(),
            vegetationStrong = colors.tertiary.toArgb(),
            water = if (dark) Color(0xFF203438).toArgb() else Color(0xFFD7E5E5).toArgb(),
            waterLine = if (dark) Color(0xFF557276).toArgb() else Color(0xFF668285).toArgb(),
            building = colors.surfaceContainerHighest.toArgb(),
            road = colors.outline.toArgb(),
            majorRoad = colors.primaryContainer.toArgb(),
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

fun Style.applyDhavaMapPalette(palette: DhavaMapPalette) {
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

// Style-fallback bookkeeping is main-thread only (MapView callbacks); one
// listener per MapView, dropped automatically with the view.
private val styleFallbacks = WeakHashMap<MapView, DhavaStyleFallback>()

/**
 * Loads the Dhava remote style with an offline fallback. If the style document
 * itself cannot be loaded (offline with a cold ambient cache), a local
 * background-only style is applied instead and [onStyleReady] still runs — so
 * track overlays (polylines, markers, position) never depend on network or
 * tile availability. Failed tile fetches inside a successfully loaded style do
 * not trigger the fallback; MapLibre keeps rendering whatever is cached.
 */
fun MapView.setDhavaMapStyle(
    map: MapLibreMap,
    palette: DhavaMapPalette,
    onStyleReady: (Style) -> Unit,
) {
    val fallback = styleFallbacks.getOrPut(this) {
        DhavaStyleFallback().also(::addOnDidFailLoadingMapListener)
    }
    fallback.pending = {
        // OnDidFailLoadingMap can also fire for individual failed resources;
        // only replace the style when none ever loaded.
        if (map.style == null) {
            map.setStyle(Style.Builder().fromJson(fallbackStyleJson(palette))) { style ->
                style.applyDhavaMapPalette(palette)
                onStyleReady(style)
            }
        }
    }
    map.setStyle(Style.Builder().fromUri(DHAVA_MAP_STYLE_URI)) { style ->
        fallback.pending = null
        style.applyDhavaMapPalette(palette)
        onStyleReady(style)
    }
}

/** One-shot per style attempt so repeated resource errors cannot loop. */
private class DhavaStyleFallback : MapView.OnDidFailLoadingMapListener {
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
private fun fallbackStyleJson(palette: DhavaMapPalette): String {
    val background = String.format(Locale.US, "#%06X", 0xFFFFFF and palette.background)
    return """{"version":8,"name":"dhava-offline-fallback","sources":{},""" +
        """"layers":[{"id":"background","type":"background","paint":{"background-color":"$background"}}]}"""
}

fun MapLibreMap.configureDhavaMapChrome(
    palette: DhavaMapPalette,
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
