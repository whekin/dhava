package com.nakvali.app

import android.net.Uri
import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.nakvali.core.map.clearMapCache
import com.nakvali.core.map.initNakvaliMap
import com.nakvali.core.map.mapCacheSizeBytes
import com.nakvali.core.recording.BackupPreview
import com.nakvali.core.recording.DirectoryUsage
import com.nakvali.core.recording.RecordingRepository
import com.nakvali.core.recording.directoryUsage
import com.nakvali.core.ui.NakvaliDivider
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import java.text.SimpleDateFormat
import java.util.Date
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
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.xLarge),
    ) {
        NakvaliScreenHeader(
            eyebrow = "Field kit",
            title = "Settings",
            description = "Recorder behavior and tools for field testing.",
        )
        Spacer(Modifier.height(NakvaliSpacing.xxLarge))
        NakvaliSectionLabel("Recording")
        Spacer(Modifier.height(NakvaliSpacing.medium))
        NakvaliPanel(Modifier.fillMaxWidth()) {
            SettingToggle("Offline mode", "Keep every activity local and skip sync attempts.", offline) {
                offline = it
                preferences.edit().putBoolean("offline_mode", it).apply()
            }
        }
        Spacer(Modifier.height(NakvaliSpacing.xxLarge))
        NakvaliSectionLabel("Field diagnostics")
        Spacer(Modifier.height(NakvaliSpacing.medium))
        NakvaliPanel(Modifier.fillMaxWidth()) {
            Column {
                SettingToggle("Sensor diagnostics", "Show GPS accuracy while riding.", diagnostics) {
                    diagnostics = it
                    preferences.edit().putBoolean("sensor_diagnostics", it).apply()
                }
                NakvaliDivider(Modifier.padding(horizontal = NakvaliSpacing.large))
                SettingToggle("Keep screen awake", "Useful during field tests; consumes more battery.", keepScreenOn) {
                    keepScreenOn = it
                    preferences.edit().putBoolean("keep_screen_on", it).apply()
                    (context as? MainActivity)?.applyKeepScreenOn()
                }
            }
        }
        Spacer(Modifier.height(NakvaliSpacing.xxLarge))
        BackupSection()
        Spacer(Modifier.height(NakvaliSpacing.xxLarge))
        StorageSection()
        Spacer(Modifier.height(40.dp))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                "Raw recordings stay under your control — on this device and in backups you create.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(NakvaliSpacing.large),
            )
        }
        Spacer(Modifier.height(NakvaliSpacing.large))
        Text(
            "Nakvali recorder · prototype 0.1",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- backup ------------------------------------------------------------------

private enum class BackupOperation {
    Exporting,
    Inspecting,
    Restoring,
}

private data class PendingRestore(
    val uri: Uri,
    val preview: BackupPreview,
)

@Composable
private fun BackupSection() {
    val context = LocalContext.current
    val repository = remember { RecordingRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    var operation by remember { mutableStateOf<BackupOperation?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }

    fun showFailure(prefix: String, error: Throwable) {
        val detail = error.message?.takeIf(String::isNotBlank) ?: "Unknown error"
        Toast.makeText(context, "$prefix: $detail", Toast.LENGTH_LONG).show()
    }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            operation = BackupOperation.Exporting
            scope.launch {
                runCatching { repository.exportBackup(uri) }
                    .onSuccess { summary ->
                        Toast.makeText(
                            context,
                            "Backup saved · ${formatBackupContents(summary.preview)}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .onFailure { error -> showFailure("Backup failed", error) }
                operation = null
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            operation = BackupOperation.Inspecting
            scope.launch {
                runCatching { repository.inspectBackup(uri) }
                    .onSuccess { preview -> pendingRestore = PendingRestore(uri, preview) }
                    .onFailure { error -> showFailure("Could not open backup", error) }
                operation = null
            }
        }
    }

    NakvaliSectionLabel("Backup")
    Spacer(Modifier.height(NakvaliSpacing.medium))
    NakvaliPanel(Modifier.fillMaxWidth()) {
        Column {
            BackupActionRow(
                title = "Export backup",
                description = when (operation) {
                    BackupOperation.Exporting -> "Building and verifying the archive…"
                    else -> "Raw rides, health logs, metadata, bikes, segments and GPX seeds."
                },
                enabled = operation == null,
                loading = operation == BackupOperation.Exporting,
            ) {
                val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
                createBackup.launch("nakvali-backup-$timestamp.zip")
            }
            NakvaliDivider(Modifier.padding(horizontal = NakvaliSpacing.large))
            BackupActionRow(
                title = "Restore backup",
                description = when (operation) {
                    BackupOperation.Inspecting -> "Reading the backup manifest…"
                    BackupOperation.Restoring -> "Verifying every file before restore…"
                    else -> "Add missing rides and authored data from a Nakvali backup."
                },
                enabled = operation == null,
                loading = operation == BackupOperation.Inspecting || operation == BackupOperation.Restoring,
            ) {
                openBackup.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                )
            }
        }
    }
    Spacer(Modifier.height(NakvaliSpacing.medium))
    Text(
        "Processed tracks are rebuilt when needed. Account tokens and other secrets are never exported.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    pendingRestore?.let { candidate ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "${formatBackupContents(candidate.preview)} · ${formatBytes(candidate.preview.totalBytes)}. " +
                        "Nakvali verifies every file, keeps existing data and adds anything missing. " +
                        "A conflicting raw recording stops the restore without overwriting it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    operation = BackupOperation.Restoring
                    scope.launch {
                        runCatching { repository.restoreBackup(candidate.uri) }
                            .onSuccess { restored ->
                                Toast.makeText(
                                    context,
                                    "Restore complete · ${restored.recordingCount} rides available",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            .onFailure { error -> showFailure("Restore failed", error) }
                        operation = null
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BackupActionRow(
    title: String,
    description: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(NakvaliSpacing.large),
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

private fun formatBackupContents(preview: BackupPreview): String = buildString {
    append(preview.recordingCount)
    append(if (preview.recordingCount == 1) " ride" else " rides")
    if (preview.segmentCount > 0) append(" · ${preview.segmentCount} segments")
    if (preview.importedTraceCount > 0) append(" · ${preview.importedTraceCount} GPX seeds")
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
        initNakvaliMap(context)
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

    NakvaliSectionLabel("Storage")
    Spacer(Modifier.height(NakvaliSpacing.medium))
    NakvaliPanel(Modifier.fillMaxWidth()) {
        Column {
            StorageRow(
                title = "Recordings (raw)",
                description = "Original sensor data; kept on this device.",
                value = overview?.raw?.let(::formatUsage),
            )
            NakvaliDivider(Modifier.padding(horizontal = NakvaliSpacing.large))
            StorageRow(
                title = "Processed artifacts",
                description = "Derived tracks; rebuilt from raw when missing.",
                value = overview?.artifacts?.let(::formatUsage),
            )
            NakvaliDivider(Modifier.padding(horizontal = NakvaliSpacing.large))
            StorageRow(
                title = "Map cache",
                description = "Tiles kept for offline maps, up to 512 MB.",
                value = overview?.let { formatBytes(it.mapCacheBytes) },
            )
            NakvaliDivider(Modifier.padding(horizontal = NakvaliSpacing.large))
            StorageActionRow(
                title = "Clear map cache",
                enabled = overview != null && !clearing,
            ) { confirmAction = StorageClearAction.MapCache }
            NakvaliDivider(Modifier.padding(horizontal = NakvaliSpacing.large))
            StorageActionRow(
                title = "Clear processed artifacts",
                enabled = overview != null && !clearing,
            ) { confirmAction = StorageClearAction.Artifacts }
        }
    }
    Spacer(Modifier.height(NakvaliSpacing.medium))
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
        Modifier.fillMaxWidth().padding(NakvaliSpacing.large),
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
            .padding(NakvaliSpacing.large),
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
        Modifier.fillMaxWidth().padding(NakvaliSpacing.large),
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
