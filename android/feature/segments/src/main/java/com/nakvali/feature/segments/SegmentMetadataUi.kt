package com.nakvali.feature.segments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nakvali.core.recording.SegmentDifficulty
import com.nakvali.core.ui.NakvaliSpacing

internal val SegmentDifficulty.label: String
    get() = when (this) {
        SegmentDifficulty.GREEN -> "Green"
        SegmentDifficulty.BLUE -> "Blue"
        SegmentDifficulty.RED -> "Red"
        SegmentDifficulty.BLACK -> "Black"
        SegmentDifficulty.DOUBLE_BLACK -> "Double black"
    }

internal val SegmentDifficulty.color: Color
    get() = when (this) {
        SegmentDifficulty.GREEN -> Color(0xFF56B86A)
        SegmentDifficulty.BLUE -> Color(0xFF5795E6)
        SegmentDifficulty.RED -> Color(0xFFE4655C)
        SegmentDifficulty.BLACK, SegmentDifficulty.DOUBLE_BLACK -> Color(0xFF242424)
    }

/**
 * Difficulty as trail signage: one row of marks, and the chosen one says its
 * name.
 *
 * Five labelled chips took two lines and read like a form. Riders already know
 * these marks from the trail head — green, blue and red circles, a black
 * diamond, two for double black — so the row shows the marks and spends words
 * only on the current answer.
 */
@Composable
internal fun SegmentDifficultyPicker(
    value: SegmentDifficulty?,
    onValueChange: (SegmentDifficulty?) -> Unit,
) {
    // Six marks plus a word on the selected one overflow a phone, and an
    // unreachable double black is the same as not offering it. The row scrolls
    // and follows the selection, so the chosen mark is always in view.
    val scroll = rememberScrollState()
    LaunchedEffect(value) {
        if (value == SegmentDifficulty.DOUBLE_BLACK) scroll.animateScrollTo(scroll.maxValue)
    }
    Row(
        modifier = Modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DifficultyOption(
            selected = value == null,
            label = "Unrated",
            onClick = { onValueChange(null) },
        ) {
            DifficultyMark(difficulty = null)
        }
        SegmentDifficulty.entries.forEach { difficulty ->
            DifficultyOption(
                selected = value == difficulty,
                label = difficulty.label,
                onClick = { onValueChange(difficulty) },
            ) {
                DifficultyMark(difficulty = difficulty)
            }
        }
    }
}

@Composable
private fun DifficultyOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    mark: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NakvaliSpacing.medium,
                vertical = NakvaliSpacing.small,
            ),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mark()
            // Only the answer needs a word; the rest are marks the rider knows.
            AnimatedVisibility(visible = selected) {
                Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/**
 * The mark itself: a circle for the graded-easy end, a diamond for black and
 * two for double black, as they appear at a trail head.
 */
@Composable
internal fun DifficultyMark(difficulty: SegmentDifficulty?, size: Dp = 14.dp) {
    val outline = MaterialTheme.colorScheme.outline
    val fill = difficulty?.color ?: Color.Transparent
    val diamonds = when (difficulty) {
        SegmentDifficulty.BLACK -> 1
        SegmentDifficulty.DOUBLE_BLACK -> 2
        else -> 0
    }
    Canvas(
        modifier = Modifier.size(
            width = if (diamonds == 2) size * 1.7f else size,
            height = size,
        ),
    ) {
        if (diamonds == 0) {
            val radius = this.size.minDimension / 2f
            drawCircle(color = fill, radius = radius)
            drawCircle(color = outline, radius = radius, style = Stroke(width = 1.dp.toPx()))
            return@Canvas
        }
        val half = this.size.height / 2f
        val centers = if (diamonds == 1) {
            listOf(this.size.width / 2f)
        } else {
            listOf(half * 0.9f, this.size.width - half * 0.9f)
        }
        centers.forEach { centerX ->
            val path = Path().apply {
                moveTo(centerX, 0f)
                lineTo(centerX + half, half)
                lineTo(centerX, this@Canvas.size.height)
                lineTo(centerX - half, half)
                close()
            }
            drawPath(path, color = fill)
            drawPath(path, color = outline, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

@Composable
internal fun DifficultyDot(difficulty: SegmentDifficulty) {
    DifficultyMark(difficulty = difficulty, size = 12.dp)
}

internal fun Color.cssHex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)
