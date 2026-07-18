package com.dhava.core.map

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource

private const val LOG_TAG = "DhavaMapCache"

/**
 * Ceiling for MapLibre's ambient cache — the style JSON, sprites, glyphs and
 * every tile fetched while browsing. The SDK evicts least-recently-used
 * resources at the ceiling, so previously seen trails keep rendering with
 * zero connectivity without unbounded disk growth. 512 MB is roomy enough for
 * several riding regions at trail zoom levels.
 */
private const val AMBIENT_CACHE_MAX_BYTES = 512L * 1024L * 1024L

/** MapLibre stores the ambient + offline cache in one SQLite database. */
private const val CACHE_DATABASE_NAME = "mbgl-offline.db"

@Volatile
private var ambientCacheConfigured = false

/**
 * Initializes MapLibre and applies the ambient cache ceiling once per process.
 * Call on the main thread before creating a [org.maplibre.android.maps.MapView]
 * or touching the cache; both map screens and storage settings go through here.
 */
fun initDhavaMap(context: Context) {
    MapLibre.getInstance(context)
    if (ambientCacheConfigured) return
    ambientCacheConfigured = true
    OfflineManager.getInstance(context).setMaximumAmbientCacheSize(
        AMBIENT_CACHE_MAX_BYTES,
        object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = Unit

            override fun onError(message: String) {
                Log.w(LOG_TAG, "Applying ambient cache ceiling failed: $message")
            }
        },
    )
}

/**
 * On-disk size of the MapLibre cache database (plus its WAL/journal files).
 * Requires [initDhavaMap] to have run; performs file IO, call off the main
 * thread.
 */
fun mapCacheSizeBytes(context: Context): Long =
    File(FileSource.getResourcesCachePath(context))
        .listFiles { file -> file.isFile && file.name.startsWith(CACHE_DATABASE_NAME) }
        ?.sumOf { it.length() }
        ?: 0L

/**
 * Clears the ambient tile cache, then vacuums the database so the reclaimed
 * space is actually returned to the filesystem (SQLite keeps its file size
 * after plain deletes). Everything removed is refetched on demand; explicit
 * offline regions (none exist yet) would survive. Returns false on failure.
 */
suspend fun clearMapCache(context: Context): Boolean = withContext(Dispatchers.Main) {
    initDhavaMap(context)
    val manager = OfflineManager.getInstance(context)
    manager.awaitFileSourceOperation { clearAmbientCache(it) } &&
        manager.awaitFileSourceOperation { packDatabase(it) }
}

private suspend fun OfflineManager.awaitFileSourceOperation(
    operation: OfflineManager.(OfflineManager.FileSourceCallback) -> Unit,
): Boolean = suspendCancellableCoroutine { continuation ->
    operation(object : OfflineManager.FileSourceCallback {
        override fun onSuccess() {
            continuation.resume(true)
        }

        override fun onError(message: String) {
            Log.w(LOG_TAG, "Map cache operation failed: $message")
            continuation.resume(false)
        }
    })
}
