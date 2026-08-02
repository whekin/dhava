package com.nakvali.core.ui

import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Stable layout vocabulary shared by all feature screens. */
object NakvaliSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val xLarge = 24.dp
    val xxLarge = 32.dp
    val screen = 20.dp
}

object NakvaliSizes {
    val compactControl = 48.dp
    val mapControl = 56.dp
    val primaryControl = 88.dp
    val primaryActionHeight = 56.dp
}

val NakvaliShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)
