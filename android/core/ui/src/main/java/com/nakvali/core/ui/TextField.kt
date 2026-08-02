package com.nakvali.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Nakvali's text input.
 *
 * Built on [BasicTextField] rather than a Material text field because the
 * platform default carries a whole vocabulary this product does not use: a label
 * that animates into a notch in an outline, an indicator line, and a container
 * that reads as a web form control. A Nakvali field is one quiet filled surface
 * with its label stated plainly above it, matching the panels and metrics it
 * sits between.
 *
 * The field brings itself into view when it takes focus, so a form inside a
 * bottom sheet stays usable with the keyboard open — provided the surface
 * hosting it applies `Modifier.imePadding()` at its own boundary.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NakvaliTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    imeAction: ImeAction = ImeAction.Done,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    val borderColor = when {
        isError -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(NakvaliSpacing.small))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(requester)
                .onFocusEvent { state ->
                    if (state.isFocused) scope.launch { requester.bringIntoView() }
                },
            enabled = enabled,
            textStyle = LocalTextStyle.current.merge(
                MaterialTheme.typography.bodyLarge.copy(color = contentColor),
            ),
            singleLine = singleLine,
            minLines = minLines,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            decorationBox = { field ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium,
                        )
                        .border(
                            width = if (focused || isError) 1.5.dp else 1.dp,
                            color = borderColor,
                            shape = MaterialTheme.shapes.medium,
                        )
                        .defaultMinSize(minHeight = NakvaliSizes.compactControl)
                        .padding(horizontal = NakvaliSpacing.large, vertical = NakvaliSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                    }
                    trailing?.invoke()
                }
            },
        )
        if (supportingText != null) {
            Spacer(Modifier.height(NakvaliSpacing.xSmall))
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
