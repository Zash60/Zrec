package com.zrec

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ZrecColors = darkColorScheme(
    primary = Color(0xFFE5484D),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF8AB4FF),
    onSecondary = Color(0xFF0B1B33),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF1F242E),
    onSurfaceVariant = Color(0xFFA9AFBB),
    outline = Color(0xFF2A303C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun ZrecTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZrecColors,
        content = content,
    )
}
