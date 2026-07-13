package com.dhava.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dhava.core.ui.DhavaDivider
import com.dhava.core.ui.DhavaPanel
import com.dhava.core.ui.DhavaScreenHeader
import com.dhava.core.ui.DhavaSectionLabel
import com.dhava.core.ui.DhavaSpacing

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
