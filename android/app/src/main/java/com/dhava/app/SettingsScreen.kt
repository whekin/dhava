package com.dhava.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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

@Composable
internal fun SettingsScreen() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("recorder_settings", 0) }
    var offline by remember { mutableStateOf(preferences.getBoolean("offline_mode", true)) }
    var diagnostics by remember { mutableStateOf(preferences.getBoolean("sensor_diagnostics", false)) }
    var keepScreenOn by remember { mutableStateOf(preferences.getBoolean("keep_screen_on", false)) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Field kit", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(vertical = 8.dp))
        Text("Recorder controls and tools for testing without a backend.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        SettingToggle("Offline mode", "Keep every activity local and skip sync attempts.", offline) {
            offline = it; preferences.edit().putBoolean("offline_mode", it).apply()
        }
        HorizontalDivider()
        Text("DEVELOPER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 28.dp, bottom = 8.dp))
        SettingToggle("Sensor diagnostics", "Show GPS accuracy and raw sample counters while riding.", diagnostics) {
            diagnostics = it; preferences.edit().putBoolean("sensor_diagnostics", it).apply()
        }
        SettingToggle("Keep screen awake", "Useful for field tests; consumes more battery.", keepScreenOn) {
            keepScreenOn = it
            preferences.edit().putBoolean("keep_screen_on", it).apply()
            (context as? MainActivity)?.applyKeepScreenOn()
        }
        Spacer(Modifier.weight(1f))
        Text("Raw recordings stay on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Dhava recorder · prototype 0.1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
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
