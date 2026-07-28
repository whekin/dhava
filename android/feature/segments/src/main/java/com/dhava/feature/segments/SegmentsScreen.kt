package com.dhava.feature.segments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhava.core.ui.DhavaDivider
import com.dhava.core.ui.DhavaEmptyState
import com.dhava.core.ui.DhavaPanel
import com.dhava.core.ui.DhavaScreenHeader
import com.dhava.core.ui.DhavaSpacing
import com.dhava.core.ui.DhavaStatusPill

/** Local segments with their best and latest result. */
@Composable
fun SegmentsScreen(
    onOpenSegment: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SegmentsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        DhavaScreenHeader(
            eyebrow = "Local",
            title = "Segments",
            description = "Timed downhill sections found in your own rides",
            modifier = Modifier.padding(
                start = DhavaSpacing.screen,
                end = DhavaSpacing.screen,
                top = DhavaSpacing.large,
                bottom = DhavaSpacing.large,
            ),
        )
        when (val current = state) {
            SegmentsState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is SegmentsState.Ready -> if (current.summaries.isEmpty()) {
                DhavaEmptyState(
                    title = "No segments yet",
                    description = "Open a saved ride, pick Create segment, and choose the " +
                        "start and finish along its descent. Dhava then finds every run of it " +
                        "in your other rides.",
                    icon = Icons.Filled.Timer,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = DhavaSpacing.screen,
                        end = DhavaSpacing.screen,
                        bottom = DhavaSpacing.xxLarge,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DhavaSpacing.medium),
                ) {
                    items(current.summaries, key = { it.segment.id }) { summary ->
                        SegmentCard(
                            summary = summary,
                            onClick = { onOpenSegment(summary.segment.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentCard(summary: SegmentSummary, onClick: () -> Unit) {
    val segment = summary.segment
    DhavaPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(DhavaSpacing.large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = segment.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!segment.trusted) {
                    DhavaStatusPill(text = "Draft")
                }
            }
            Spacer(Modifier.height(DhavaSpacing.xSmall))
            Text(
                text = listOfNotNull(
                    SegmentFormat.length(segment.lengthM),
                    SegmentFormat.descent(segment.descentM),
                    SegmentFormat.ascent(segment.ascentM)
                        ?.takeUnless { segment.ascentM == 0.0 },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DhavaDivider(Modifier.padding(vertical = DhavaSpacing.medium))
            val best = summary.best
            if (best == null) {
                Text(
                    text = "No countable run yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = SegmentFormat.elapsedWithUncertainty(
                        best.elapsedMs,
                        best.uncertaintyMs,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Best of ${summary.attemptCount} " +
                        if (summary.attemptCount == 1) "run" else "runs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val notes = listOfNotNull(
                summary.uncertainCount.takeIf { it > 0 }?.let { "$it uncertain" },
                summary.rejectedCount.takeIf { it > 0 }?.let { "$it not counted" },
            )
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(DhavaSpacing.small))
                Text(
                    text = notes.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
