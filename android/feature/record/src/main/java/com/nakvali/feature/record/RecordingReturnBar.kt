package com.nakvali.feature.record

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nakvali.core.recording.RecordingRepository
import com.nakvali.core.recording.RecordingState
import com.nakvali.core.ui.NakvaliSpacing

/**
 * The ride, collapsed: shown above the navigation on every other screen while
 * a recording is preparing, running or waiting to be saved. Tapping it goes
 * back to the recorder. Renders nothing when there is no ride.
 *
 * [edgeToEdge] adds the navigation-bar inset for screens that hide the app's
 * own navigation bar, which would otherwise have provided it.
 */
@Composable
fun RecordingReturnBar(
    onReturn: () -> Unit,
    modifier: Modifier = Modifier,
    edgeToEdge: Boolean = false,
) {
    val context = LocalContext.current
    val repository = remember { RecordingRepository.getInstance(context) }
    val state by repository.state.collectAsState()
    val ride = when (val current = state) {
        is RecordingState.Recording -> ReturnBarContent(
            title = if (current.paused) "Paused" else "Recording",
            detail = "${formatElapsedShort(current.elapsedMs)} · ${formatDistance(current.distanceM)}",
            held = current.paused,
        )
        is RecordingState.Preparing -> ReturnBarContent("Starting ride", "Finding a clean start", held = false)
        is RecordingState.Finished -> ReturnBarContent("Ride finished", "Save or discard it", held = true)
        RecordingState.Idle -> return
    }
    val container = if (ride.held) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (ride.held) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        onClick = onReturn,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${ride.title}, ${ride.detail}. Back to ride" },
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (edgeToEdge) Modifier.navigationBarsPadding() else Modifier)
                .heightIn(min = 56.dp)
                .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ride.held) {
                Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(20.dp))
            } else {
                LiveDot()
            }
            Column(Modifier.weight(1f)) {
                Text(ride.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    ride.detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Back to ride", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

private data class ReturnBarContent(val title: String, val detail: String, val held: Boolean)

/** Breathes slowly: alive, not alarming. */
@Composable
private fun LiveDot() {
    val transition = rememberInfiniteTransition(label = "recording-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "recording-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(10.dp).alpha(alpha),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {}
    }
}
