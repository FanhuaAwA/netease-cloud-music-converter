package com.fanhua.yuncheng.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF0A66F5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEAFF), onPrimaryContainer = Color(0xFF072B6F),
    background = Color(0xFFF3F6FB), onBackground = Color(0xFF0B1220),
    surface = Color.White, onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFEEF2F8), onSurfaceVariant = Color(0xFF687386),
    outline = Color(0xFFD8E0EC), error = Color(0xFFD92D20),
)

@Composable fun YunChengTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)
