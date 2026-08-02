package com.nakvali.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.Velocity

/**
 * Keeps a fling inside a bottom sheet's scroll region.
 *
 * Material hands a scrollable's leftover velocity to the sheet, so one flick
 * that reaches the top of the content carries on into the sheet and collapses
 * it: the rider asked to scroll back and the sheet closed instead. Swallowing
 * only the leftover *fling* velocity fixes that while leaving the deliberate
 * gestures intact — a slow drag at the top of the content still collapses the
 * sheet, and the handle and pinned header still move it.
 *
 * Apply before the scroll modifier so this sits between the scrollable and the
 * sheet: `Modifier.nestedScroll(rememberSheetFlingBoundary()).verticalScroll(…)`.
 */
@Composable
fun rememberSheetFlingBoundary(): NestedScrollConnection = remember {
    object : NestedScrollConnection {
        override suspend fun onPostFling(
            consumed: Velocity,
            available: Velocity,
        ): Velocity = available
    }
}
