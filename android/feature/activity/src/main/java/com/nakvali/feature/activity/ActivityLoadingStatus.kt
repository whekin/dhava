package com.nakvali.feature.activity

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.nakvali.core.ui.NakvaliSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ActivityLoadingStatus(state: ActivityLoadingState) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = NakvaliSpacing.xLarge, vertical = NakvaliSpacing.small),
        verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.xSmall),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.phase.title, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            state.readFraction?.let { Text("${(it * 100).toInt()}% read", style = MaterialTheme.typography.labelMedium) }
        }
        Text(
            when {
                state.phase == ActivityLoadPhase.FAILED -> "Original recording kept. Reopen to retry analysis."
                state.phase == ActivityLoadPhase.PROFILE -> "Track and ride totals are ready"
                state.fromCache -> "Saved GPS preview · preparing details"
                state.preview.isNotEmpty() -> "GPS preview through " + DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.preview.last().timestampMs))
                else -> "The recorded track will appear as it is read"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.phase != ActivityLoadPhase.FAILED) {
            val fraction = state.readFraction
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
