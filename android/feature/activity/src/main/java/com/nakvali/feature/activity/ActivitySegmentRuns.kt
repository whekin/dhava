package com.nakvali.feature.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.nakvali.core.recording.RideSegmentRun
import com.nakvali.core.recording.StoredAttemptQuality
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.SegmentFormat

/**
 * What this ride did on the rider's authored segments.
 *
 * One row per *run*, not per segment: riding the same trail three times in a
 * day is three results, and collapsing them would hide the two the rider is
 * most likely comparing. Rows are in the order they were ridden, which is the
 * order they appear along the track above.
 */
@Composable
fun ActivitySegmentRuns(
    runs: List<RideSegmentRun>,
    onOpenSegment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        NakvaliSectionLabel("Segments")
        Column(
            modifier = Modifier.padding(top = NakvaliSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
        ) {
            runs.forEach { run ->
                SegmentRunRow(run = run, onOpen = { onOpenSegment(run.segmentId) })
            }
        }
    }
}

@Composable
private fun SegmentRunRow(run: RideSegmentRun, onOpen: () -> Unit) {
    NakvaliPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpen)
                .padding(
                    horizontal = NakvaliSpacing.large,
                    vertical = NakvaliSpacing.medium,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = run.segmentName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The time always carries its uncertainty. A gate crossing is
                // interpolated between fixes, so a bare number would claim a
                // precision the sensors never had.
                Text(
                    text = SegmentFormat.elapsedWithUncertainty(
                        run.attempt.elapsedMs,
                        run.attempt.uncertaintyMs,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                standing(run)?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (run.place == null) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * Where this run sits among the rider's own.
 *
 * An unranked run says so plainly rather than being dressed up: the matcher
 * was not sure of its time, and a place it cannot support would be a lie in
 * the one place the rider is most likely to believe it.
 */
private fun standing(run: RideSegmentRun): String? {
    if (run.attempt.quality == StoredAttemptQuality.UNCERTAIN) return "Uncertain — not ranked"
    val place = run.place ?: return null
    if (place == 1) {
        return if (run.confirmedAttempts == 1) "First time here" else "Best of ${run.confirmedAttempts}"
    }
    val behind = run.behindBestMs?.let { " · +${SegmentFormat.elapsed(it)} on best" }.orEmpty()
    return "${ordinal(place)} of ${run.confirmedAttempts}$behind"
}

private fun ordinal(place: Int): String {
    val suffix = when {
        place % 100 in 11..13 -> "th"
        place % 10 == 1 -> "st"
        place % 10 == 2 -> "nd"
        place % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$place$suffix"
}
