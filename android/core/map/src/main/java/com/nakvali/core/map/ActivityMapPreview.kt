package com.nakvali.core.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter

/** One vertex of a thumbnail track. [riding] false draws as transport. */
data class MapPreviewPoint(
    val lat: Double,
    val lon: Double,
    val riding: Boolean = true,
    val breakBefore: Boolean = false,
)

/**
 * A still map of one ride for list cards.
 *
 * A live MapView per list row would mean a GL surface per card; this renders a
 * bitmap once with MapLibre's offscreen snapshotter and caches it. Until the
 * snapshot exists — and whenever it cannot (no network and no cached tiles) —
 * the track is drawn directly on a plain ground, so a card never waits on
 * connectivity to show the ride's shape.
 *
 * [cacheKey] must change whenever [points] do.
 */
@Composable
fun ActivityMapPreview(
    cacheKey: String,
    points: List<MapPreviewPoint>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val palette = rememberNakvaliMapPalette()
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier = modifier.background(Color(palette.land))) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        var snapshot by remember(cacheKey, palette.dark) { mutableStateOf(ActivityMapPreviews.cached(cacheKey, palette.dark, widthPx, heightPx)) }
        TrackSketch(points, palette, Modifier.fillMaxSize())
        AnimatedVisibility(visible = snapshot != null, enter = fadeIn(), exit = fadeOut()) {
            snapshot?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        LaunchedEffect(cacheKey, palette.dark, widthPx, heightPx) {
            if (points.size < 2 || widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
            if (snapshot != null && ActivityMapPreviews.isComplete(cacheKey, palette.dark, widthPx, heightPx)) {
                return@LaunchedEffect
            }
            ActivityMapPreviews.load(context, cacheKey, points, palette, widthPx, heightPx, density)
                ?.let { snapshot = it }
        }
    }
}

/** The offline drawing: the same inks as the snapshot, projected the same way. */
@Composable
private fun TrackSketch(points: List<MapPreviewPoint>, palette: NakvaliMapPalette, modifier: Modifier) {
    val casing = Color(palette.background).copy(alpha = 0.85f)
    val riding = Color(palette.primary)
    val transport = Color(palette.road)
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val project = fitProjection(points, size.width, size.height, 22.dp.toPx())
        val ridingPath = Path()
        val transportPath = Path()
        var previous: MapPreviewPoint? = null
        for (point in points) {
            val target = if (point.riding) ridingPath else transportPath
            val (x, y) = project(point)
            val prior = previous
            if (prior == null || point.breakBefore || prior.riding != point.riding) {
                if (prior != null && !point.breakBefore && prior.riding != point.riding) {
                    val (px, py) = project(prior)
                    target.moveTo(px, py)
                    target.lineTo(x, y)
                } else {
                    target.moveTo(x, y)
                }
            } else {
                target.lineTo(x, y)
            }
            previous = point
        }
        val width = 3.dp.toPx()
        drawPath(transportPath, transport, style = Stroke(width * 0.8f, cap = StrokeCap.Round,
            join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 2, width * 1.6f))))
        drawPath(ridingPath, casing, style = Stroke(width * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(ridingPath, riding, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        endpoints(points, project, palette)
    }
}

private fun DrawScope.endpoints(
    points: List<MapPreviewPoint>,
    project: (MapPreviewPoint) -> Pair<Float, Float>,
    palette: NakvaliMapPalette,
) {
    val (sx, sy) = project(points.first())
    val (ex, ey) = project(points.last())
    val radius = 4.dp.toPx()
    drawCircle(Color(palette.background), radius + 2.dp.toPx(), Offset(ex, ey))
    drawCircle(Color(palette.label), radius, Offset(ex, ey))
    drawCircle(Color(palette.background), radius + 2.dp.toPx(), Offset(sx, sy))
    drawCircle(Color(palette.primary), radius, Offset(sx, sy))
}

/** Web Mercator fit with padding, matching what the snapshotter's region does. */
private fun fitProjection(
    points: List<MapPreviewPoint>,
    width: Float,
    height: Float,
    padding: Float,
): (MapPreviewPoint) -> Pair<Float, Float> {
    fun mx(lon: Double) = (lon + 180.0) / 360.0
    fun my(lat: Double): Double {
        val clamped = lat.coerceIn(-85.0, 85.0) * PI / 180.0
        return (1.0 - ln(tan(clamped) + 1.0 / cos(clamped)) / PI) / 2.0
    }
    // Same 8% world margin as the snapshot region, so the fade between the
    // sketch and the rendered map does not jump.
    val rawMinX = points.minOf { mx(it.lon) }
    val rawMaxX = points.maxOf { mx(it.lon) }
    val rawMinY = points.minOf { my(it.lat) }
    val rawMaxY = points.maxOf { my(it.lat) }
    val minX = rawMinX - (rawMaxX - rawMinX) * 0.08
    val minY = rawMinY - (rawMaxY - rawMinY) * 0.08
    val spanX = ((rawMaxX - rawMinX) * 1.16).coerceAtLeast(1e-9)
    val spanY = ((rawMaxY - rawMinY) * 1.16).coerceAtLeast(1e-9)
    val scale = min((width - 2 * padding) / spanX, (height - 2 * padding) / spanY)
    val offsetX = (width - spanX * scale) / 2
    val offsetY = (height - spanY * scale) / 2
    return { point ->
        ((offsetX + (mx(point.lon) - minX) * scale).toFloat()) to
            ((offsetY + (my(point.lat) - minY) * scale).toFloat())
    }
}

/** Process-wide snapshot cache and a one-at-a-time renderer. */
internal object ActivityMapPreviews {
    private const val LOG_TAG = "ActivityMapPreview"
    private const val STYLE_VERSION = 3
    private const val RENDER_TIMEOUT_MS = 20_000L
    private const val DIRECTORY = "activity-map-previews"
    private const val MAX_DISK_FILES = 200

    private data class Entry(val bitmap: Bitmap, val complete: Boolean)

    private val memory = object : LruCache<String, Entry>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry) = value.bitmap.byteCount
    }
    private val renderer = Mutex()

    private fun key(cacheKey: String, dark: Boolean, width: Int, height: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$STYLE_VERSION|$cacheKey|$dark|$width|$height".toByteArray())
        return digest.take(16).joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    fun cached(cacheKey: String, dark: Boolean, width: Int, height: Int): Bitmap? =
        memory.get(key(cacheKey, dark, width, height))?.bitmap

    fun isComplete(cacheKey: String, dark: Boolean, width: Int, height: Int): Boolean =
        memory.get(key(cacheKey, dark, width, height))?.complete == true

    suspend fun load(
        context: Context,
        cacheKey: String,
        points: List<MapPreviewPoint>,
        palette: NakvaliMapPalette,
        width: Int,
        height: Int,
        density: Float,
    ): Bitmap? {
        val key = key(cacheKey, palette.dark, width, height)
        memory.get(key)?.takeIf { it.complete }?.let { return it.bitmap }
        val file = File(File(context.cacheDir, DIRECTORY), "$key.webp")
        withContext(Dispatchers.IO) {
            if (file.isFile) runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() else null
        }?.let { bitmap ->
            memory.put(key, Entry(bitmap, complete = true))
            return bitmap
        }
        // Rendering without a network may still find every tile in the ambient
        // cache, but it may not: such a picture is shown, never persisted, and
        // replaced by a complete one the next time the card appears online.
        val online = isOnline(context)
        val bitmap = renderer.withLock {
            memory.get(key)?.takeIf { it.complete }?.let { return it.bitmap }
            render(context, points, palette, width, height, density)
        } ?: return null
        memory.put(key, Entry(bitmap, complete = online))
        if (online) {
            withContext(Dispatchers.IO) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it)
                    }
                    if (!temporary.renameTo(file)) temporary.delete()
                    trimDisk(file.parentFile!!)
                }.onFailure { Log.w(LOG_TAG, "Could not store map preview", it) }
            }
        }
        return bitmap
    }

    private fun trimDisk(directory: File) {
        val files = directory.listFiles { it.isFile && it.name.endsWith(".webp") } ?: return
        if (files.size <= MAX_DISK_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_DISK_FILES).forEach { it.delete() }
    }

    private fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun render(
        context: Context,
        points: List<MapPreviewPoint>,
        palette: NakvaliMapPalette,
        width: Int,
        height: Int,
        density: Float,
    ): Bitmap? = withContext(Dispatchers.Main) {
        initNakvaliMap(context)
        val logicalWidth = max(1, (width / density).roundToInt())
        val logicalHeight = max(1, (height / density).roundToInt())
        val padding = 22
        val options = MapSnapshotter.Options(logicalWidth, logicalHeight)
            .withPixelRatio(density)
            .withStyleJson(previewStyleJson(points, palette))
            .withRegion(regionFor(points))
            .withPadding(padding, padding, padding, padding)
            .withLogo(false)
            .withAttribution(false)
        val snapshotter = MapSnapshotter(context, options)
        val result = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            suspendCancellableCoroutine<Bitmap?> { continuation ->
                continuation.invokeOnCancellation { snapshotter.cancel() }
                snapshotter.start(
                    { snapshot -> if (continuation.isActive) continuation.resume(snapshot.bitmap) },
                    { error ->
                        Log.w(LOG_TAG, "Map preview failed: $error")
                        if (continuation.isActive) continuation.resume(null)
                    },
                )
            }
        }
        if (result == null) snapshotter.cancel()
        result
    }

    /** A thumbnail too small in the world would zoom to street furniture. */
    private fun regionFor(points: List<MapPreviewPoint>): LatLngBounds {
        var south = points.minOf { it.lat }
        var north = points.maxOf { it.lat }
        var west = points.minOf { it.lon }
        var east = points.maxOf { it.lon }
        // Region padding alone left the line grazing the frame; a margin in
        // the world keeps start and finish markers clear of the card edge.
        val marginLat = (north - south) * 0.08
        val marginLon = (east - west) * 0.08
        south -= marginLat
        north += marginLat
        west -= marginLon
        east += marginLon
        val minSpan = 0.003
        if (north - south < minSpan) {
            val middle = (north + south) / 2
            south = middle - minSpan / 2
            north = middle + minSpan / 2
        }
        if (east - west < minSpan) {
            val middle = (east + west) / 2
            west = middle - minSpan / 2
            east = middle + minSpan / 2
        }
        return LatLngBounds.Builder().include(LatLng(south, west)).include(LatLng(north, east)).build()
    }

    /**
     * A purpose-built style instead of the browsing one: the snapshotter cannot
     * enumerate a remote style's layers to recolour them, and a thumbnail needs
     * no labels, sprites or glyphs. It reads the same vector source URL as the
     * main map, so tiles already seen there come from the shared ambient cache.
     */
    private fun previewStyleJson(points: List<MapPreviewPoint>, palette: NakvaliMapPalette): String {
        fun hex(color: Int) = String.format(Locale.US, "#%06X", 0xFFFFFF and color)
        fun layer(id: String, type: String, sourceLayer: String?, filter: JSONArray?, paint: JSONObject,
            source: String = "openmaptiles", minZoom: Double? = null, layout: JSONObject? = null) = JSONObject().apply {
            put("id", id); put("type", type); put("source", source)
            sourceLayer?.let { put("source-layer", it) }
            filter?.let { put("filter", it) }
            minZoom?.let { put("minzoom", it) }
            layout?.let { put("layout", it) }
            put("paint", paint)
        }
        fun classIs(vararg classes: String) = JSONArray().apply {
            put("match"); put(JSONArray().put("get").put("class")); put(JSONArray().apply { classes.forEach { put(it) } })
            put(true); put(false)
        }
        fun byZoom(low: Double, high: Double) = JSONArray().put("interpolate").put(JSONArray().put("linear"))
            .put(JSONArray().put("zoom")).put(11).put(low).put(16).put(high)
        val roundLine = JSONObject().put("line-cap", "round").put("line-join", "round")

        val layers = JSONArray()
            .put(JSONObject().put("id", "background").put("type", "background")
                .put("paint", JSONObject().put("background-color", hex(palette.land))))
            .put(layer("residential", "fill", "landuse", classIs("residential"),
                JSONObject().put("fill-color", hex(palette.landMuted)).put("fill-opacity", 0.7)))
            .put(layer("wood", "fill", "landcover", classIs("wood"),
                JSONObject().put("fill-color", hex(palette.vegetation)).put("fill-opacity", 0.95)))
            .put(layer("grass", "fill", "landcover", classIs("grass", "wetland"),
                JSONObject().put("fill-color", hex(palette.vegetation)).put("fill-opacity", 0.55)))
            .put(layer("park", "fill", "park", null,
                JSONObject().put("fill-color", hex(palette.vegetation)).put("fill-opacity", 0.5)))
            .put(layer("water", "fill", "water", null, JSONObject().put("fill-color", hex(palette.water))))
            .put(layer("waterway", "line", "waterway", null,
                JSONObject().put("line-color", hex(palette.waterLine)).put("line-width", byZoom(0.6, 2.0))))
            .put(layer("building", "fill", "building", null,
                JSONObject().put("fill-color", hex(palette.building)).put("fill-opacity", 0.6), minZoom = 14.0))
            .put(layer("paths", "line", "transportation", classIs("path", "track"),
                JSONObject().put("line-color", hex(palette.trail)).put("line-opacity", 0.75)
                    .put("line-width", byZoom(0.5, 1.4))
                    .put("line-dasharray", JSONArray().put(2).put(1.5))))
            .put(layer("roads", "line", "transportation",
                classIs("minor", "service", "tertiary", "secondary", "street", "street_limited"),
                JSONObject().put("line-color", hex(palette.road)).put("line-opacity", 0.7)
                    .put("line-width", byZoom(0.5, 2.6)), layout = roundLine))
            .put(layer("major-roads", "line", "transportation", classIs("primary", "trunk", "motorway"),
                JSONObject().put("line-color", hex(palette.majorRoad)).put("line-width", byZoom(1.0, 4.0)),
                layout = roundLine))
            .put(layer("track-transport", "line", null,
                JSONArray().put("==").put(JSONArray().put("get").put("kind")).put("transport"),
                JSONObject().put("line-color", hex(palette.road)).put("line-width", 2.4)
                    .put("line-dasharray", JSONArray().put(1.6).put(1.4)),
                source = "track", layout = roundLine))
            .put(layer("track-casing", "line", null,
                JSONArray().put("==").put(JSONArray().put("get").put("kind")).put("riding"),
                JSONObject().put("line-color", hex(palette.background)).put("line-opacity", 0.85)
                    .put("line-width", 6.6),
                source = "track", layout = roundLine))
            .put(layer("track", "line", null,
                JSONArray().put("==").put(JSONArray().put("get").put("kind")).put("riding"),
                JSONObject().put("line-color", hex(palette.primary)).put("line-width", 3.0),
                source = "track", layout = roundLine))
            .put(layer("track-ends", "circle", null,
                JSONArray().put("has").put("end"),
                JSONObject()
                    .put("circle-radius", 4.0)
                    .put("circle-color", JSONArray().put("match").put(JSONArray().put("get").put("end"))
                        .put("start").put(hex(palette.primary)).put(hex(palette.label)))
                    .put("circle-stroke-width", 2.0)
                    .put("circle-stroke-color", hex(palette.background)),
                source = "track"))

        val style = JSONObject()
            .put("version", 8)
            .put("name", "nakvali-activity-preview")
            .put("sources", JSONObject()
                .put("openmaptiles", JSONObject().put("type", "vector").put("url", OPENMAPTILES_URL))
                .put("track", JSONObject().put("type", "geojson").put("data", trackGeoJson(points))))
            .put("layers", layers)
        return style.toString()
    }

    private fun trackGeoJson(points: List<MapPreviewPoint>): JSONObject {
        val features = JSONArray()
        fun line(kind: String, coordinates: JSONArray) {
            if (coordinates.length() < 2) return
            features.put(JSONObject().put("type", "Feature")
                .put("properties", JSONObject().put("kind", kind))
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates)))
        }
        fun position(point: MapPreviewPoint) = JSONArray().put(point.lon).put(point.lat)
        var current = JSONArray()
        var riding = points.first().riding
        var previous: MapPreviewPoint? = null
        for (point in points) {
            val prior = previous
            if (prior != null && (point.breakBefore || point.riding != riding)) {
                line(if (riding) "riding" else "transport", current)
                current = JSONArray()
                // A riding/transport handover shares its vertex; a gap does not.
                if (!point.breakBefore) current.put(position(prior))
                riding = point.riding
            }
            current.put(position(point))
            previous = point
        }
        line(if (riding) "riding" else "transport", current)
        listOf("start" to points.first(), "finish" to points.last()).forEach { (end, point) ->
            features.put(JSONObject().put("type", "Feature")
                .put("properties", JSONObject().put("end", end))
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", position(point))))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features)
    }

    /** The vector source the Liberty style reads; one URL means one cache. */
    private const val OPENMAPTILES_URL = "https://tiles.openfreemap.org/planet"
}
