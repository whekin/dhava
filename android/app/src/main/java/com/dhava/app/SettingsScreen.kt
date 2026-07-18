package com.dhava.app

import android.os.StatFs
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dhava.core.map.clearMapCache
import com.dhava.core.map.initDhavaMap
import com.dhava.core.map.mapCacheSizeBytes
import com.dhava.core.recording.DirectoryUsage
import com.dhava.core.recording.RecordingRepository
import com.dhava.core.recording.directoryUsage
import com.dhava.core.ui.DhavaDivider
import com.dhava.core.ui.DhavaPanel
import com.dhava.core.ui.DhavaScreenHeader
import com.dhava.core.ui.DhavaSectionLabel
import com.dhava.core.ui.DhavaSpacing
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("recorder_settings", 0) }
    var offline by remember { mutableStateOf(preferences.getBoolean("offline_mode", true)) }
    var diagnostics by remember { mutableStateOf(preferences.getBoolean("sensor_diagnostics", false)) }
    var keepScreenOn by remember { mutableStateOf(preferences.getBoolean("keep_screen_on", false)) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DhavaSpacing.screen, vertical = DhavaSpacing.xLarge),
    ) {
        DhavaScreenHeader(
            eyebrow = "Field kit",
            title = "Settings",
            description = "Recorder behavior and tools for field testing.",
        )
        Spacer(Modifier.height(DhavaSpacing.xxLarge))
        DhavaSectionLabel("Recording")
        Spacer(Modifier.height(DhavaSpacing.medium))
        DhavaPanel(Modifier.fillMaxWidth()) {
            SettingToggle("Offline mode", "Keep every activity local and skip sync attempts.", offline) {
                offline = it
                preferences.edit().putBoolean("offline_mode", it).apply()
            }
        }
        Spacer(Modifier.height(DhavaSpacing.xxLarge))
        DhavaSectionLabel("Field diagnostics")
        Spacer(Modifier.height(DhavaSpacing.medium))
        DhavaPanel(Modifier.fillMaxWidth()) {
            Column {
                SettingToggle("Sensor diagnostics", "Show GPS accuracy while riding.", diagnostics) {
                    diagnostics = it
                    preferences.edit().putBoolean("sensor_diagnostics", it).apply()
                }
                DhavaDivider(Modifier.padding(horizontal = DhavaSpacing.large))
                SettingToggle("Keep screen awake", "Useful during field tests; consumes more battery.", keepScreenOn) {
                    keepScreenOn = it
                    preferences.edit().putBoolean("keep_screen_on", it).apply()
                    (context as? MainActivity)?.applyKeepScreenOn()
                }
            }
        }
        Spacer(Modifier.height(DhavaSpacing.xxLarge))
        StorageSection()
        Spacer(Modifier.height(40.dp))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                "Raw recordings stay on this device.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(DhavaSpacing.large),
            )
        }
        Spacer(Modifier.height(DhavaSpacing.large))
        Text(
            "Dhava recorder · prototype 0.1",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- storage -----------------------------------------------------------------

private data class StorageOverview(
    val raw: DirectoryUsage,
    val artifacts: DirectoryUsage,
    val mapCacheBytes: Long,
    val freeBytes: Long,
)

private enum class StorageClearAction(val title: String, val message: String) {
    MapCache(
        "Clear map cache?",
        "Cached map tiles are removed and downloaded again when needed. " +
            "Maps may be blank offline until areas are revisited online.",
    ),
    Artifacts(
        "Clear processed artifacts?",
        "Derived tracks are removed and recomputed from raw recordings the " +
            "next time an activity is opened. Raw data is not touched.",
    ),
}

@Composable
private fun StorageSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf<StorageOverview?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var clearing by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<StorageClearAction?>(null) }

    // Sizes are computed on IO once per section entry (and after each clear),
    // never on every recomposition; rows show a small spinner meanwhile.
    LaunchedEffect(refresh) {
        overview = null
        // MapLibre must be initialized on the main thread before its cache
        // path can be queried.
        initDhavaMap(context)
        val repository = RecordingRepository.getInstance(context)
        overview = withContext(Dispatchers.IO) {
            StorageOverview(
                raw = directoryUsage(repository.recordingsDir(), ".jsonl.gz"),
                artifacts = directoryUsage(repository.artifactsDir(), ".canonical.json.gz"),
                mapCacheBytes = mapCacheSizeBytes(context),
                freeBytes = StatFs(context.filesDir.absolutePath).availableBytes,
            )
        }
    }

    DhavaSectionLabel("Storage")
    Spacer(Modifier.height(DhavaSpacing.medium))
    DhavaPanel(Modifier.fillMaxWidth()) {
        Column {
            StorageRow(
                title = "Recordings (raw)",
                description = "Original sensor data; kept on this device.",
                value = overview?.raw?.let(::formatUsage),
            )
            DhavaDivider(Modifier.padding(horizontal = DhavaSpacing.large))
            StorageRow(
                title = "Processed artifacts",
                description = "Derived tracks; rebuilt from raw when missing.",
                value = overview?.artifacts?.let(::formatUsage),
            )
            DhavaDivider(Modifier.padding(horizontal = DhavaSpacing.large))
            StorageRow(
                title = "Map cache",
                description = "Tiles kept for offline maps, up to 512 MB.",
                value = overview?.let { formatBytes(it.mapCacheBytes) },
            )
            DhavaDivider(Modifier.padding(horizontal = DhavaSpacing.large))
            StorageActionRow(
                title = "Clear map cache",
                enabled = overview != null && !clearing,
            ) { confirmAction = StorageClearAction.MapCache }
            DhavaDivider(Modifier.padding(horizontal = DhavaSpacing.large))
            StorageActionRow(
                title = "Clear processed artifacts",
                enabled = overview != null && !clearing,
            ) { confirmAction = StorageClearAction.Artifacts }
        }
    }
    Spacer(Modifier.height(DhavaSpacing.medium))
    Text(
        overview?.let { "${formatBytes(it.freeBytes)} free on device" } ?: "Measuring storage…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAction = null
                    clearing = true
                    scope.launch {
                        val feedback = when (action) {
                            StorageClearAction.MapCache ->
                                if (clearMapCache(context)) "Map cache cleared" else "Could not clear map cache"

                            StorageClearAction.Artifacts -> {
                                val removed = RecordingRepository.getInstance(context).clearProcessedArtifacts()
                                "Removed $removed processed artifact${if (removed == 1) "" else "s"}"
                            }
                        }
                        clearing = false
                        refresh++
                        Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StorageRow(title: String, description: String, value: String?) {
    Row(
        Modifier.fillMaxWidth().padding(DhavaSpacing.large),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value == null) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StorageActionRow(title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(DhavaSpacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun formatUsage(usage: DirectoryUsage): String =
    "${usage.fileCount} · ${formatBytes(usage.totalBytes)}"

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024.0 -> String.format(Locale.US, "%.1f GB", mb / 1024.0)
        mb >= 100.0 -> String.format(Locale.US, "%.0f MB", mb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        bytes > 0L -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        else -> "0 MB"
    }
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(DhavaSpacing.large),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
