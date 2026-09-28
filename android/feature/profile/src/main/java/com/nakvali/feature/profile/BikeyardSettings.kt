package com.nakvali.feature.profile

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nakvali.core.recording.bikeyard.BikeyardMounting
import com.nakvali.core.recording.bikeyard.BikeyardProfile
import com.nakvali.core.recording.bikeyard.BikeyardRideAccess
import com.nakvali.core.recording.bikeyard.BikeyardUiState
import com.nakvali.core.recording.bikeyard.BikeyardVisibility
import com.nakvali.core.ui.NakvaliDivider
import com.nakvali.core.ui.NakvaliFitMetric
import com.nakvali.core.ui.NakvaliLoading
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliSecondaryButton
import com.nakvali.core.ui.NakvaliSpacing
import java.util.Locale

/** Everything the BIKEYARD card can ask for; the screen owns the dialogs. */
internal class BikeyardActions(
    val connect: () -> Unit,
    val allowRideResults: () -> Unit,
    val cancelConnect: () -> Unit,
    val open: (String) -> Unit,
    val disconnect: () -> Unit,
    val setAutomatic: (Boolean) -> Unit,
    val setVisibility: (BikeyardVisibility) -> Unit,
    val setAirtime: (Boolean) -> Unit,
    val setMounting: (BikeyardMounting) -> Unit,
)

@Composable
internal fun BikeyardSection(viewModel: ProfileViewModel) {
    val state by viewModel.bikeyard.collectAsState()
    val context = LocalContext.current
    var confirmAutomatic by remember { mutableStateOf(false) }
    var confirmAutomaticMetrics by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    LifecycleResumeEffect(state.connected) {
        if (state.connected) viewModel.refreshBikeyardProfile()
        onPauseOrDispose { }
    }

    fun authorize(upgrade: Boolean) {
        viewModel.connectBikeyard(upgrade) { result ->
            result.onSuccess { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure {
                        viewModel.cancelBikeyard()
                        Toast.makeText(context, "No browser is available", Toast.LENGTH_LONG).show()
                    }
            }.onFailure {
                Toast.makeText(context, "Could not start BIKEYARD connection", Toast.LENGTH_LONG).show()
            }
        }
    }

    BikeyardCard(
        state = state,
        actions = BikeyardActions(
            connect = { authorize(upgrade = false) },
            allowRideResults = { authorize(upgrade = true) },
            cancelConnect = viewModel::cancelBikeyard,
            open = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            disconnect = { confirmDisconnect = true },
            setAutomatic = { enabled ->
                if (enabled) confirmAutomatic = true else viewModel.bikeyardSettings(false, state.visibility)
            },
            setVisibility = { viewModel.bikeyardSettings(state.automatic, it) },
            setAirtime = { enabled ->
                if (enabled) confirmAutomaticMetrics = true
                else viewModel.bikeyardMetricsSettings(false, state.metricsMounting)
            },
            setMounting = { viewModel.bikeyardMetricsSettings(true, it) },
        ),
    )

    if (confirmAutomatic) AlertDialog(
        onDismissRequest = { confirmAutomatic = false },
        title = { Text("Upload new rides automatically?") },
        text = {
            Text(
                "This turns off Offline mode. New rides you save will upload to ${state.riderName}, " +
                    "with visibility ${state.visibility.label.lowercase()}. Processed tracks and ride " +
                    "details are sent; raw sensor files stay here.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.bikeyardSettings(true, state.visibility)
                confirmAutomatic = false
            }) { Text("Enable uploads") }
        },
        dismissButton = { TextButton(onClick = { confirmAutomatic = false }) { Text("Cancel") } },
    )
    if (confirmAutomaticMetrics) AlertDialog(
        onDismissRequest = { confirmAutomaticMetrics = false },
        title = { Text("Sync jumps automatically?") },
        text = {
            Text(
                "For new private rides, Nakvali will send jump and airtime event times, measured " +
                    "sensor coverage and phone position after the track is accepted. Air of 0.25 s or " +
                    "more is sent as a jump, BIKEYARD counts those; shorter air goes as uncounted " +
                    "candidates. It is a phone-sensor estimate. Raw sensor files, GPS coordinates and " +
                    "phone G peaks stay on this device.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.bikeyardMetricsSettings(true, state.metricsMounting)
                confirmAutomaticMetrics = false
            }) { Text("Enable airtime sync") }
        },
        dismissButton = { TextButton(onClick = { confirmAutomaticMetrics = false }) { Text("Cancel") } },
    )
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect BIKEYARD?") },
        text = {
            Text(
                "Queued uploads stop and connection data is removed from this phone. A ride already " +
                    "accepted by BIKEYARD stays there. Your local recordings are kept.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.disconnectBikeyard()
                confirmDisconnect = false
            }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
    )
}

@Composable
internal fun BikeyardCard(state: BikeyardUiState, actions: BikeyardActions) {
    NakvaliPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(NakvaliSpacing.xLarge)) {
            when {
                state.loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NakvaliLoading(Modifier.size(24.dp))
                    Text("Opening BIKEYARD connection…", style = MaterialTheme.typography.bodyMedium)
                }
                state.connected -> ConnectedBikeyard(state, actions)
                else -> DisconnectedBikeyard(state, actions)
            }
            state.message?.let {
                Spacer(Modifier.height(NakvaliSpacing.medium))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DisconnectedBikeyard(state: BikeyardUiState, actions: BikeyardActions) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BikeyardMark()
        Column(Modifier.weight(1f)) {
            Text("BIKEYARD", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(NakvaliSpacing.xSmall))
            Text(
                "Send rides to BIKEYARD for trail matches, KOMs and medals. Raw sensor data stays on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(NakvaliSpacing.xLarge))
    if (state.connecting) {
        BrowserPending(
            text = "Finish connecting in your browser. If the link stays there, enable " +
                "Open supported links for Nakvali in Android settings.",
            onCancel = actions.cancelConnect,
        )
    } else {
        NakvaliSecondaryButton(text = "Connect BIKEYARD", onClick = actions.connect)
    }
}

@Composable
private fun ConnectedBikeyard(state: BikeyardUiState, actions: BikeyardActions) {
    val profile = state.profile
    var menu by remember { mutableStateOf(false) }
    var showUploads by rememberSaveable { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BikeyardMark()
        Column(Modifier.weight(1f)) {
            // A full name is the one thing on the card that must not end in
            // an ellipsis; it shrinks to fit a 360 dp phone instead.
            val nameStyle = MaterialTheme.typography.titleLarge
            BasicText(
                text = state.riderName ?: "BIKEYARD rider",
                style = nameStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = nameStyle.fontSize),
            )
            Spacer(Modifier.height(NakvaliSpacing.xSmall))
            Text(
                text = listOfNotNull(
                    profile?.username?.takeIf { it.isNotBlank() }?.let { "@$it" },
                    profile?.repLevel?.let { "REP $it" },
                ).joinToString(" · ").ifEmpty { "BIKEYARD" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "BIKEYARD options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                profile?.profileUrl?.let { url ->
                    DropdownMenuItem(
                        text = { Text("Open profile in BIKEYARD") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                        onClick = {
                            menu = false
                            actions.open(url)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Disconnect", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Filled.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        menu = false
                        actions.disconnect()
                    },
                )
            }
        }
    }

    profile?.stats?.let { stats ->
        Spacer(Modifier.height(NakvaliSpacing.xLarge))
        // Two by two: four columns wrapped "64.4 km" onto two lines at a large
        // system font. Descent is summed from the ride list because BIKEYARD's
        // own totals only count climb; climb stands in until that is read.
        Column(verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.large)) {
            Row(horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large)) {
                NakvaliFitMetric(String.format(Locale.US, "%,d", stats.rideCount), "Rides", Modifier.weight(1f))
                NakvaliFitMetric(formatKilometres(stats.distanceM), "Distance", Modifier.weight(1f), unit = "km")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large)) {
                profile.descentM?.let {
                    NakvaliFitMetric(formatMetres(it), "Descent", Modifier.weight(1f), unit = "m")
                } ?: NakvaliFitMetric(formatMetres(stats.elevationGainM), "Climb", Modifier.weight(1f), unit = "m")
                NakvaliFitMetric(formatHours(stats.movingS), "Moving", Modifier.weight(1f), unit = "h")
            }
        }
        val honours = listOfNotNull(
            profile.kom.takeIf { it > 0 }?.let { "$it KOM" },
            profile.medals.takeIf { it > 0 }?.let { "$it ${if (it == 1) "medal" else "medals"}" },
            profile.localLegend.takeIf { it > 0 }?.let { "$it local ${if (it == 1) "legend" else "legends"}" },
        )
        if (honours.isNotEmpty()) {
            Spacer(Modifier.height(NakvaliSpacing.medium))
            Row(
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(honours.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(NakvaliSpacing.small))
        Text(
            text = listOfNotNull(
                "Lifetime on BIKEYARD",
                "public rides only".takeUnless { profile.includesPrivate },
                "newest 2,000 rides".takeIf { profile.totalsPartial },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    when {
        state.upgrading -> {
            Spacer(Modifier.height(NakvaliSpacing.large))
            BrowserPending(
                text = "Approve reading your rides in the browser. Your current connection keeps working.",
                onCancel = actions.cancelConnect,
            )
        }
        state.rideAccess != BikeyardRideAccess.ALL -> {
            Spacer(Modifier.height(NakvaliSpacing.large))
            RideResultsPrompt(onAllow = actions.allowRideResults, enabled = !state.connecting)
        }
    }

    Spacer(Modifier.height(NakvaliSpacing.large))
    NakvaliDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { showUploads = !showUploads },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = NakvaliSpacing.small)) {
            Text("Ride uploads", style = MaterialTheme.typography.titleSmall)
            Text(
                text = listOfNotNull(
                    if (state.automatic) "Automatic" else "Manual",
                    state.visibility.label,
                    "Airtime sync".takeIf { state.automaticMetrics },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (showUploads) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (showUploads) "Hide upload settings" else "Show upload settings",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AnimatedVisibility(visible = showUploads) {
        UploadSettings(state, actions)
    }
}

@Composable
private fun UploadSettings(state: BikeyardUiState, actions: BikeyardActions) {
    var mountingMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.large)) {
        Spacer(Modifier.height(NakvaliSpacing.xSmall))
        Column(verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small)) {
            Text("Visibility for new uploads", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                BikeyardVisibility.entries.forEachIndexed { index, visibility ->
                    SegmentedButton(
                        selected = state.visibility == visibility,
                        onClick = { actions.setVisibility(visibility) },
                        shape = SegmentedButtonDefaults.itemShape(index, BikeyardVisibility.entries.size),
                    ) { Text(visibility.label) }
                }
            }
        }
        SwitchRow(
            title = "Upload new rides automatically",
            description = "When online. Rides already on this phone are not sent.",
            checked = state.automatic,
            onCheckedChange = actions.setAutomatic,
            semanticsLabel = "Automatic BIKEYARD uploads",
        )
        SwitchRow(
            title = "Jumps and airtime",
            description = if (state.automatic && state.visibility == BikeyardVisibility.PRIVATE) {
                "Send jumps with new private rides, from the phone sensor. Phone G stays local."
            } else {
                "Needs automatic uploads with visibility Only me."
            },
            checked = state.automaticMetrics,
            enabled = state.automatic && state.visibility == BikeyardVisibility.PRIVATE,
            onCheckedChange = actions.setAirtime,
            semanticsLabel = "Automatic experimental airtime sync",
        )
        if (state.automaticMetrics) Box {
            TextButton(onClick = { mountingMenu = true }) {
                Text("Phone position: ${state.metricsMounting.label}")
            }
            DropdownMenu(expanded = mountingMenu, onDismissRequest = { mountingMenu = false }) {
                BikeyardMounting.entries.forEach { mounting ->
                    DropdownMenuItem(text = { Text(mounting.label) }, onClick = {
                        actions.setMounting(mounting)
                        mountingMenu = false
                    })
                }
            }
        }
        Text(
            "Queued rides keep the visibility chosen when queued. Edits after an upload are not synced.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    semanticsLabel: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = semanticsLabel },
        )
    }
}

@Composable
private fun RideResultsPrompt(onAllow: () -> Unit, enabled: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = NakvaliSpacing.large,
                top = NakvaliSpacing.medium,
                bottom = NakvaliSpacing.medium,
                end = NakvaliSpacing.small,
            ),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "Show trail results and achievements for your uploads here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAllow, enabled = enabled) { Text("Allow") }
        }
    }
}

@Composable
private fun BrowserPending(text: String, onCancel: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NakvaliLoading(Modifier.size(24.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/**
 * A "BY" monogram, not BIKEYARD's logo: they publish no mark for integrations
 * and their API terms forbid implying endorsement.
 */
@Composable
private fun BikeyardMark() {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("BY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        }
    }
}

private fun formatKilometres(meters: Double): String =
    if (meters >= 100_000) String.format(Locale.US, "%,.0f", meters / 1_000)
    else String.format(Locale.US, "%.1f", meters / 1_000)

private fun formatHours(seconds: Long): String =
    if (seconds >= 36_000) String.format(Locale.US, "%,d", seconds / 3_600)
    else String.format(Locale.US, "%.1f", seconds / 3_600.0)

private fun formatMetres(meters: Double): String = String.format(Locale.US, "%,.0f", meters.coerceAtLeast(0.0))

internal fun previewBikeyardProfile() = BikeyardProfile(
    username = "stas",
    profileUrl = "https://yard.bike/riders/stas",
    repLevel = 3,
    stats = com.nakvali.core.recording.bikeyard.BikeyardStats(42, 612_400.0, 172_800, 18_450.0),
    descentM = 23_696.0,
    kom = 3,
    medals = 11,
    includesPrivate = true,
)
