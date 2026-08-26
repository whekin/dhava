package com.nakvali.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun NakvaliScreenHeader(
    eyebrow: String,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            NakvaliSectionLabel(eyebrow)
            Spacer(Modifier.height(NakvaliSpacing.xSmall))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Spacer(Modifier.height(NakvaliSpacing.small))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun NakvaliSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The app's resting surface.
 *
 * [outlined] draws a hairline in `outlineVariant`. It exists because in the
 * light scheme a panel and the map ground behind it sit close enough in value
 * that the panel loses its edge; over a map, ask for the outline.
 */
@Composable
fun NakvaliPanel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    outlined: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = color,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = if (outlined) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
        content = content,
    )
}

/**
 * How loudly a metric speaks. Exactly one metric per screen should be [Hero];
 * the point of the scale is that a rider glancing down knows without deciding
 * which number the screen is about.
 */
enum class NakvaliMetricEmphasis { Hero, Primary, Secondary }

/**
 * One measurement: value, its unit, and what it is.
 *
 * The unit sits on the same baseline as the value rather than under it, so the
 * reading is one fixation instead of two. Everything is start-aligned — mixing
 * start- and end-alignment across a grid leaves a ragged middle the eye has to
 * cross twice.
 */
@Composable
fun NakvaliMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    emphasis: NakvaliMetricEmphasis = NakvaliMetricEmphasis.Secondary,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val valueStyle = when (emphasis) {
        NakvaliMetricEmphasis.Hero -> MaterialTheme.typography.displayMedium
        NakvaliMetricEmphasis.Primary -> MaterialTheme.typography.displaySmall
        NakvaliMetricEmphasis.Secondary -> MaterialTheme.typography.titleLarge
    }
    val unitStyle = when (emphasis) {
        NakvaliMetricEmphasis.Hero -> MaterialTheme.typography.titleLarge
        NakvaliMetricEmphasis.Primary -> MaterialTheme.typography.titleMedium
        NakvaliMetricEmphasis.Secondary -> MaterialTheme.typography.labelLarge
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = valueStyle,
                color = valueColor,
                maxLines = 1,
            )
            if (unit != null) {
                Spacer(Modifier.width(NakvaliSpacing.xSmall))
                Text(
                    text = unit,
                    style = unitStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // Optical: the unit's baseline sits slightly high next to a
                    // display-size numeral because its cap height is so much smaller.
                    modifier = Modifier.offset(y = (-2).dp),
                )
            }
        }
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Two metrics per row, equal columns, all start-aligned. */
@Composable
fun NakvaliMetricRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
        content = content,
    )
}

enum class NakvaliControlTone { Primary, Destructive, Neutral }

@Composable
fun NakvaliRideControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: NakvaliControlTone = NakvaliControlTone.Primary,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = NakvaliSizes.primaryControl,
) {
    val colors = when (tone) {
        NakvaliControlTone.Primary -> ButtonDefaults.buttonColors()
        NakvaliControlTone.Destructive -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        NakvaliControlTone.Neutral -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
    Button(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = colors,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.43f),
        )
    }
}

/**
 * Ride controls with the primary action nailed to the centre.
 *
 * The rider's thumb learns one position during a run and must still find it
 * after the state changes. Laying these out in a Row would recentre the group
 * whenever [secondary] appears, moving the primary control out from under the
 * thumb and putting a different button — historically Stop — where it used to
 * be. So the primary is centre-anchored and the secondary is offset beside it.
 */
@Composable
fun NakvaliRideControlBar(
    modifier: Modifier = Modifier,
    secondary: (@Composable () -> Unit)? = null,
    primary: @Composable () -> Unit,
) {
    val step = NakvaliSizes.primaryControl / 2 + NakvaliSizes.secondaryControl / 2 +
        NakvaliSpacing.xLarge
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NakvaliSizes.primaryControl),
        contentAlignment = Alignment.Center,
    ) {
        if (secondary != null) {
            Box(modifier = Modifier.offset(x = -step)) { secondary() }
        }
        primary()
    }
}

/**
 * What a pill is telling the rider. Each tone owns one meaning so a glance is
 * enough: green is running, ochre is held or unsettled, red is wrong, neutral
 * is just information.
 */
enum class NakvaliStatusTone { Live, Held, Alert, Neutral }

@Composable
fun NakvaliStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: NakvaliStatusTone = NakvaliStatusTone.Neutral,
) {
    val container = when (tone) {
        NakvaliStatusTone.Live -> MaterialTheme.colorScheme.primaryContainer
        NakvaliStatusTone.Held -> MaterialTheme.colorScheme.tertiaryContainer
        NakvaliStatusTone.Alert -> MaterialTheme.colorScheme.errorContainer
        NakvaliStatusTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (tone) {
        NakvaliStatusTone.Live -> MaterialTheme.colorScheme.onPrimaryContainer
        NakvaliStatusTone.Held -> MaterialTheme.colorScheme.onTertiaryContainer
        NakvaliStatusTone.Alert -> MaterialTheme.colorScheme.onErrorContainer
        NakvaliStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * The app's only button shapes.
 *
 * Call sites pick a role, not a colour. Before this existed the choice between
 * `Button` and `FilledTonalButton` was made 80 times independently, which is
 * why an optional sign-in ended up louder than anything a rider actually does.
 */
@Composable
fun NakvaliPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = NakvaliSizes.primaryActionHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
fun NakvaliSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = NakvaliSizes.primaryActionHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
fun NakvaliTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NakvaliSpacing.small))
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}

/**
 * The app's busy indicator: the Expressive shape-morphing one rather than the
 * legacy sweep. The theme has declared `MaterialExpressiveTheme` since it was
 * written; this is the first component to actually collect on it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NakvaliLoading(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(modifier = modifier, color = color)
}

/**
 * Gradient scrim for the top of a full-bleed map, so basemap labels never run
 * into the status bar clock.
 */
@Composable
fun NakvaliTopScrim(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 112.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/**
 * Empty state.
 *
 * Centred in whatever height it is given rather than parked at the top with the
 * rest of the screen left black — pass a modifier that fills the available
 * space. The icon is opt-in: repeating the screen's own navigation icon inside
 * a coloured circle added nothing but a second stock-Material impression.
 */
@Composable
fun NakvaliEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
    secondaryAction: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(
                horizontal = NakvaliSpacing.xLarge,
                vertical = NakvaliSpacing.xxLarge,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(NakvaliSpacing.large))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(NakvaliSpacing.small))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(NakvaliSpacing.xLarge))
                action()
            }
            if (secondaryAction != null) {
                Spacer(Modifier.height(NakvaliSpacing.small))
                secondaryAction()
            }
        }
    }
}

@Composable
fun NakvaliDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.outlineVariant)
}
