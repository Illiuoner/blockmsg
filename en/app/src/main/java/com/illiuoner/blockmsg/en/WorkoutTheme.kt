package com.illiuoner.blockmsg.en

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val gymColors = darkColorScheme(
    primary = Color(0xFFCFEC9F),
    onPrimary = Color(0xFF1C2B11),
    primaryContainer = Color(0xFF303D24),
    onPrimaryContainer = Color(0xFFE4F7CA),
    background = Color(0xFF141714),
    onBackground = Color(0xFFF2F4EC),
    surface = Color(0xFF1C211C),
    onSurface = Color(0xFFF2F4EC),
    surfaceContainerHigh = Color(0xFF242A24),
    onSurfaceVariant = Color(0xFFB8C1B2),
    outline = Color(0xFF687267),
    outlineVariant = Color(0xFF353E35),
    error = Color(0xFFF0BAA1),
)

internal val ControlShape = RoundedCornerShape(12.dp)

@Composable
internal fun WorkoutTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = gymColors, content = content)
}
