package com.nakvali.feature.record

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.map.ActivityMapPreview
import com.nakvali.core.map.MapPreviewPoint
import com.nakvali.core.recording.ActivityPreviewPoint
import com.nakvali.core.recording.ActivitySummary
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordingStatus
import com.nakvali.core.recording.UploadState
import com.nakvali.core.recording.bikeyard.BikeyardRideResult
import com.nakvali.core.recording.bikeyard.BikeyardUiState
import com.nakvali.core.recording.bikeyard.BikeyardUpload
import com.nakvali.core.recording.bikeyard.BikeyardUploadStatus
import com.nakvali.core.recording.needsRecoveryAttention
import com.nakvali.core.ui.NakvaliEmptyState
import com.nakvali.core.ui.NakvaliFitMetric
import com.nakvali.core.ui.NakvaliLoading
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSecondaryButton
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
import com.nakvali.core.ui.NakvaliStatusTone
import com.nakvali.core.ui.NakvaliTheme
import com.nakvali.core.ui.air
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ActivitiesScreen(
    onOpenActivity: (String) -> Unit,
    onFinishSaving: (String) -> Unit,
    onStartRecording: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivitiesViewModel = viewModel(),
) {
    val recordings by viewModel.recordings.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    val summaries by viewModel.summaries.collectAsState()
    val bikeyard by viewModel.bikeyardState.collectAsState()
    val finished = recordings.filter { it.status != RecordingStatus.RECORDING }
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
    }
    // Receipts arrive after the list is first shown (the credential store opens
    // asynchronously, uploads finish in the background); read results then too.
    val uploadedCount = bikeyard.uploads.count { it.status == BikeyardUploadStatus.UPLOADED }
    LaunchedEffect(bikeyard.loading, bikeyard.rideAccess, uploadedCount) {
        if (!bikeyard.loading) viewModel.refreshBikeyardResults()
    }

    ActivitiesContent(
        recordings = finished,
        summaries = summaries,
        uploads = uploads,
        bikeyard = bikeyard,
        onOpenActivity = onOpenActivity,
        onFinishSaving = onFinishSaving,
        onStartRecording = onStartRecording,
        onRetry = viewModel::retryUpload,
        onOpenUrl = { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
        modifier = modifier,
    )
}

private sealed interface ActivityListItem {
    val key: String

    data class Month(val month: YearMonth, val rides: Int, val descentM: Double?) : ActivityListItem {
        override val key: String get() = "month-$month"
    }

    data class Ride(val recording: LocalRecording) : ActivityListItem {
        override val key: String get() = recording.id
    }
}

@Composable
private fun ActivitiesContent(
    recordings: List<LocalRecording>,
    summaries: Map<String, ActivitySummary>,
    uploads: Map<String, UploadState>,
    bikeyard: BikeyardUiState,
    onOpenActivity: (String) -> Unit,
    onFinishSaving: (String) -> Unit,
    onStartRecording: () -> Unit,
    onRetry: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val items = remember(recordings, summaries) { listItems(recordings, summaries, zone) }
    val needsAttention = recordings.count { it.needsRecoveryAttention() || it.needsSaveAction() }

    Column(modifier = modifier.fillMaxSize()) {
        NakvaliScreenHeader(
            eyebrow = "On this device",
            title = "Activities",
            description = headerSummary(recordings, summaries, needsAttention),
            modifier = Modifier.padding(
                start = NakvaliSpacing.screen,
                end = NakvaliSpacing.screen,
                top = NakvaliSpacing.xLarge,
                bottom = NakvaliSpacing.medium,
            ),
        )
        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(NakvaliSpacing.screen),
                contentAlignment = Alignment.Center,
            ) {
                NakvaliEmptyState(
                    title = "No rides yet",
                    description = "Finish a recording and it will stay here on this device.",
                    action = {
                        NakvaliSecondaryButton(
                            text = "Record a ride",
                            onClick = onStartRecording,
                            icon = Icons.Filled.PlayArrow,
                        )
                    },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NakvaliSpacing.screen,
                    end = NakvaliSpacing.screen,
                    bottom = NakvaliSpacing.xLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
            ) {
                items(items, key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is ActivityListItem.Month -> MonthHeader(item)
                        is ActivityListItem.Ride -> ActivityCard(
                            recording = item.recording,
                            summary = summaries[item.recording.id],
                            uploadState = uploads[item.recording.id],
                            bikeyard = bikeyard,
                            onOpen = { onOpenActivity(item.recording.id) },
                            onFinishSaving = { onFinishSaving(item.recording.id) },
                            onRetry = { onRetry(item.recording.id) },
                            onOpenUrl = onOpenUrl,
                        )
                    }
                }
                item(key = "attribution", contentType = "attribution") {
                    Text(
                        text = "Maps © OpenStreetMap contributors · OpenMapTiles · OpenFreeMap",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().padding(top = NakvaliSpacing.small),
                    )
                }
            }
        }
    }
}

private fun listItems(
    recordings: List<LocalRecording>,
    summaries: Map<String, ActivitySummary>,
    zone: ZoneId,
): List<ActivityListItem> {
    val byMonth = recordings.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAtMs).atZone(zone)) }
    return byMonth.flatMap { (month, rides) ->
        val known = rides.mapNotNull { summaries[it.id] }
        listOf(ActivityListItem.Month(month, rides.size, known.takeIf { it.isNotEmpty() }?.sumOf { it.descentM })) +
            rides.map { ActivityListItem.Ride(it) }
    }
}

private fun headerSummary(
    recordings: List<LocalRecording>,
    summaries: Map<String, ActivitySummary>,
    needsAttention: Int,
): String? {
    if (recordings.isEmpty()) return null
    val rides = "${recordings.size} ${if (recordings.size == 1) "ride" else "rides"}"
    if (needsAttention > 0) {
        return "$rides · $needsAttention ${if (needsAttention == 1) "needs attention" else "need attention"}"
    }
    val known = recordings.mapNotNull { summaries[it.id] }
    if (known.isEmpty()) return rides
    val descent = known.sumOf { it.descentM }
    val distance = known.sumOf { it.distanceM }
    return "$rides · ${formatDistance(distance)} · ${formatTotalDescent(descent)} descent"
}

private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

@Composable
private fun MonthHeader(item: ActivityListItem.Month) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = NakvaliSpacing.medium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        NakvaliSectionLabel(monthFormatter.format(item.month))
        Text(
            text = listOfNotNull(
                "${item.rides} ${if (item.rides == 1) "ride" else "rides"}",
                item.descentM?.let { "${formatTotalDescent(it)} descent" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivityCard(
    recording: LocalRecording,
    summary: ActivitySummary?,
    uploadState: UploadState?,
    bikeyard: BikeyardUiState,
    onOpen: () -> Unit,
    onFinishSaving: () -> Unit,
    onRetry: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val needsAttention = recording.needsSaveAction() || recording.needsRecoveryAttention()
    val upload = bikeyard.uploadFor(recording.id)
    // A ride that never moved has no line to draw; it gets no empty map frame.
    val showMap = if (summary == null) !recording.recoveryFailed else summary.preview.size >= 2
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(
            1.dp,
            if (needsAttention) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        if (showMap) {
            // The card clips its content, so the map takes the card's top corners.
            Box(modifier = Modifier.fillMaxWidth().height(168.dp)) {
                RideMap(recording, summary)
                StatusPills(recording, uploadState, Modifier.align(Alignment.TopStart).padding(NakvaliSpacing.medium))
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = NakvaliSpacing.large,
                end = NakvaliSpacing.large,
                top = if (showMap) NakvaliSpacing.medium else NakvaliSpacing.large,
                bottom = NakvaliSpacing.large,
            ),
        ) {
            if (!showMap) StatusPills(recording, uploadState, Modifier.padding(bottom = NakvaliSpacing.medium))
            Text(
                text = recording.title ?: "Unfinished ride",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    formatStartTime(recording.startedAtMs),
                    recording.bikeName,
                    summary?.runCount?.takeIf { it > 0 }?.let { "$it ${if (it == 1) "run" else "runs"}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(NakvaliSpacing.medium))
            RideMetrics(recording, summary)
            summary?.takeIf { it.jumpCount > 0 }?.let { AirStrip(it) }
            ActivityFooter(
                recording = recording,
                upload = upload,
                bikeyard = bikeyard,
                onFinishSaving = onFinishSaving,
                onRetry = onRetry,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

@Composable
private fun StatusPills(recording: LocalRecording, uploadState: UploadState?, modifier: Modifier = Modifier) {
    val recovery = when {
        recording.recoveryFailed -> "Raw file kept" to NakvaliStatusTone.Alert
        recording.needsSaveAction() -> "Unfinished" to NakvaliStatusTone.Held
        recording.recovered -> "Recovered" to NakvaliStatusTone.Held
        else -> null
    }
    val upload = when (uploadState) {
        is UploadState.Uploading -> "Uploading"
        is UploadState.Retrying -> "Retrying"
        else -> null
    }
    if (recovery == null && upload == null) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small)) {
        recovery?.let { (text, tone) -> NakvaliStatusPill(text, tone = tone) }
        upload?.let { NakvaliStatusPill(it) }
    }
}

@Composable
private fun RideMap(recording: LocalRecording, summary: ActivitySummary?) {
    val points = remember(summary?.key) { summary?.preview?.map(ActivityPreviewPoint::toMapPoint).orEmpty() }
    when {
        points.size >= 2 && !LocalInspectionMode.current -> ActivityMapPreview(
            cacheKey = "${recording.id}:${summary!!.key}",
            points = points,
            modifier = Modifier.fillMaxSize(),
        )
        else -> Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            when {
                summary == null && !recording.recoveryFailed -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
                ) {
                    NakvaliLoading(Modifier.size(28.dp))
                    Text(
                        "Preparing map",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Icon(
                    Icons.Filled.PedalBike,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

private fun ActivityPreviewPoint.toMapPoint() = MapPreviewPoint(lat, lon, riding, breakBefore)

@Composable
private fun RideMetrics(recording: LocalRecording, summary: ActivitySummary?) {
    // Descent is a drop by definition; the minus sign only made it read as a loss.
    val descent = summary?.let { Measured(String.format(Locale.US, "%,.0f", it.descentM.coerceAtLeast(0.0)), "m") }
    val distance = summary?.let { measuredDistance(it.distanceM) }
    val moving = summary?.let { formatElapsedShort((it.movingTimeS * 1_000).toLong()) }
        ?: formatElapsedShort(recording.ridingDurationMs).takeIf { recording.ridingDurationMs > 0 }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
    ) {
        NakvaliFitMetric(descent?.value ?: "—", "Descent", Modifier.weight(1f), unit = descent?.unit)
        NakvaliFitMetric(distance?.value ?: "—", "Distance", Modifier.weight(1f), unit = distance?.unit)
        NakvaliFitMetric(moving ?: "—", if (summary != null) "Moving" else "Time", Modifier.weight(1f))
    }
}

/**
 * The jump band: its own surface and ink so a park lap full of air reads
 * differently from a long enduro loop at a glance. Only airtime long enough to
 * count as a jump (Rust's `is_likely_jump`) is counted; the shorter candidates
 * stay faint on the timeline and are mentioned, not added up.
 */
@Composable
private fun AirStrip(summary: ActivitySummary) {
    val accent = MaterialTheme.colorScheme.air
    val shorter = summary.airtimeCount - summary.jumpCount
    Spacer(Modifier.height(NakvaliSpacing.medium))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NakvaliSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
            ) {
                Text(
                    "AIR",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1,
                )
                Text(
                    listOfNotNull(
                        "phone sensor",
                        shorter.takeIf { it > 0 }?.let { "+$it shorter" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
                verticalAlignment = Alignment.Bottom,
            ) {
                NakvaliFitMetric(
                    summary.jumpCount.toString(),
                    if (summary.jumpCount == 1) "Jump" else "Jumps",
                    Modifier.weight(0.8f),
                    valueColor = accent,
                )
                NakvaliFitMetric(airSeconds(summary.jumpAirtimeMs, 1), "Total air", Modifier.weight(1f), unit = "s")
                NakvaliFitMetric(airSeconds(summary.airtimeLongestMs, 2), "Longest", Modifier.weight(1f), unit = "s")
            }
            AirTimeline(summary, accent, Modifier.fillMaxWidth().height(22.dp))
        }
    }
}

/** Each candidate as a tick along the recording; height is its share of the longest. */
@Composable
private fun AirTimeline(summary: ActivitySummary, accent: Color, modifier: Modifier) {
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val longest = summary.airtimeLongestMs.coerceAtLeast(1)
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 1.dp.toPx()
        drawLine(baseline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
        val tick = 2.5.dp.toPx()
        summary.airtimeMarks.forEach { mark ->
            val share = (mark.durationMs.toFloat() / longest).coerceIn(0.18f, 1f)
            val x = (mark.at * (size.width - tick)) + tick / 2
            val top = size.height * (1f - share)
            drawLine(
                color = if (mark.jump) accent.copy(alpha = 0.55f + 0.45f * share) else baseline,
                start = Offset(x, size.height),
                end = Offset(x, top),
                strokeWidth = tick,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun airSeconds(milliseconds: Long, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", milliseconds.coerceAtLeast(0) / 1_000.0)

/**
 * The one line below the numbers that says where the ride stands: a pending
 * local action first, then its BIKEYARD life. Rides that were never uploaded
 * get no line at all — local is the default, not a status.
 */
@Composable
private fun ActivityFooter(
    recording: LocalRecording,
    upload: BikeyardUpload?,
    bikeyard: BikeyardUiState,
    onFinishSaving: () -> Unit,
    onRetry: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val local = when {
        recording.needsSaveAction() -> FooterAction("Finish saving", onFinishSaving)
        recording.status == RecordingStatus.FAILED -> FooterAction("Retry upload", onRetry)
        else -> null
    }
    if (local != null) {
        FooterRow(
            icon = Icons.Filled.ErrorOutline,
            tint = MaterialTheme.colorScheme.tertiary,
            text = if (recording.needsSaveAction()) "Not saved yet" else "Upload failed",
            action = local,
        )
        return
    }
    if (upload == null) return
    val result = upload.result
    when (upload.status) {
        BikeyardUploadStatus.QUEUED, BikeyardUploadStatus.UPLOADING -> FooterRow(
            icon = Icons.Filled.CloudUpload,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            text = if (upload.uploadId != null) "BIKEYARD is processing" else "Waiting to upload to BIKEYARD",
            busy = true,
        )
        BikeyardUploadStatus.UPLOADED -> {
            val url = result?.url ?: bikeyard.profile?.profileUrl
            FooterRow(
                icon = if ((result?.achievementCount ?: 0) > 0) Icons.Filled.EmojiEvents else Icons.Filled.CloudDone,
                tint = if ((result?.achievementCount ?: 0) > 0) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                text = bikeyardResultLine(result, bikeyard),
                trailing = url?.let { { BikeyardLinkChip(onClick = { onOpenUrl(it) }) } },
            )
        }
        BikeyardUploadStatus.DUPLICATE -> FooterRow(
            icon = Icons.Filled.ErrorOutline,
            tint = MaterialTheme.colorScheme.tertiary,
            text = "BIKEYARD found this track elsewhere",
        )
        BikeyardUploadStatus.FAILED, BikeyardUploadStatus.NEEDS_AUTH -> FooterRow(
            icon = Icons.Filled.ErrorOutline,
            tint = MaterialTheme.colorScheme.error,
            text = if (upload.status == BikeyardUploadStatus.NEEDS_AUTH) {
                "BIKEYARD upload needs reconnecting"
            } else {
                "BIKEYARD upload failed · open to retry"
            },
        )
        BikeyardUploadStatus.CANCELLED -> Unit
    }
}

private data class FooterAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
)

/** Short enough to share one line with the BY chip at a large font scale. */
private fun bikeyardResultLine(result: BikeyardRideResult?, bikeyard: BikeyardUiState): String {
    if (result == null) return if (bikeyard.connected) "Uploaded" else "Uploaded · disconnected"
    val honours = listOfNotNull(
        result.kom.takeIf { it > 0 }?.let { "$it KOM" },
        result.medals.takeIf { it > 0 }?.let { "$it ${if (it == 1) "medal" else "medals"}" },
        result.localLegend.takeIf { it > 0 }?.let { "Legend" },
    )
    val trails = result.trails.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "trail" else "trails"}" }
    return when {
        honours.isNotEmpty() -> honours.joinToString(" · ")
        !result.ready -> "Matching trails…"
        trails != null -> trails
        else -> "Uploaded"
    }
}

/**
 * Opens the ride on BIKEYARD. A plain "BY" monogram rather than their logo:
 * BIKEYARD publishes no mark for integrations, and its API terms forbid
 * suggesting endorsement.
 */
@Composable
private fun BikeyardLinkChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { contentDescription = "Open in BIKEYARD" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NakvaliSpacing.medium, vertical = NakvaliSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.xSmall),
        ) {
            Text("BY", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun FooterRow(
    icon: ImageVector,
    tint: Color,
    text: String,
    action: FooterAction? = null,
    busy: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Spacer(Modifier.height(NakvaliSpacing.medium))
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = NakvaliSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = tint)
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
        action?.let {
            TextButton(onClick = it.onClick) {
                Text(it.label, style = MaterialTheme.typography.labelLarge)
                it.icon?.let { icon ->
                    Spacer(Modifier.width(NakvaliSpacing.xSmall))
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

internal fun LocalRecording.needsSaveAction(): Boolean =
    status == RecordingStatus.RECORDED && savedAtMs == null

private fun formatTotalDescent(meters: Double): String =
    String.format(Locale.US, "%,.0f m", meters.coerceAtLeast(0.0))

@Preview(name = "Activities · populated", widthDp = 412, heightDp = 900)
@Composable
private fun ActivitiesContentPreview() {
    NakvaliTheme(darkTheme = true) {
        ActivitiesContent(
            recordings = listOf(
                LocalRecording(
                    id = "unfinished",
                    startedAtMs = 1_767_004_000_000,
                    endedAtMs = 1_767_004_042_000,
                    sizeBytes = 42_000,
                    status = RecordingStatus.RECORDED,
                ),
                LocalRecording(
                    id = "preview",
                    startedAtMs = 1_767_000_000_000,
                    endedAtMs = 1_767_003_420_000,
                    sizeBytes = 48_234_120,
                    status = RecordingStatus.RECORDED,
                    title = "Morning laps at Turtle Lake",
                    bikeName = "Enduro",
                    savedAtMs = 1_767_003_500_000,
                ),
            ),
            summaries = mapOf(
                "preview" to ActivitySummary(
                    key = "k", distanceM = 18_400.0, movingTimeS = 4_210.0, descentM = 1_245.0,
                    ascentM = 80.0, maxSpeedMps = 14.2, runCount = 6, preview = emptyList(),
                    airtimeCount = 26, airtimeTotalMs = 7_420, airtimeLongestMs = 620,
                    jumpCount = 14, jumpAirtimeMs = 5_900,
                    airtimeMarks = (0 until 26).map {
                        val duration = 120 + (it * 37) % 500
                        com.nakvali.core.recording.ActivityAirtimeMark(it / 26f, duration, duration >= 250)
                    },
                ),
            ),
            uploads = emptyMap(),
            bikeyard = BikeyardUiState(loading = false),
            onOpenActivity = {},
            onFinishSaving = {},
            onStartRecording = {},
            onRetry = {},
            onOpenUrl = {},
        )
    }
}
