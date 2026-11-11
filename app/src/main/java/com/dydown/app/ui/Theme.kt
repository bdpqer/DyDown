package com.dydown.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0F6E56),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F5EE),
    onPrimaryContainer = Color(0xFF04342C),
    secondary = Color(0xFF378ADD),
    secondaryContainer = Color(0xFFE6F1FB),
    onSecondaryContainer = Color(0xFF042C53),
    background = Color(0xFFFAFAF8),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1EFE8),
    error = Color(0xFFA32D2D)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5DCAA5),
    onPrimary = Color(0xFF04342C),
    primaryContainer = Color(0xFF0F6E56),
    onPrimaryContainer = Color(0xFFE1F5EE),
    secondary = Color(0xFF85B7EB),
    secondaryContainer = Color(0xFF185FA5),
    onSecondaryContainer = Color(0xFFE6F1FB),
    background = Color(0xFF121211),
    surface = Color(0xFF1C1C1A),
    surfaceVariant = Color(0xFF2C2C2A),
    error = Color(0xFFF09595)
)

@Composable
fun DyDownTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
