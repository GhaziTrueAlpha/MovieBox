package com.moviebox.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.moviebox.android.model.AppTheme

private val MochaColors = darkColorScheme(
    primary = Color(0xFFCBA6F7),
    onPrimary = Color(0xFF11111B),
    secondary = Color(0xFF89B4FA),
    background = Color(0xFF1E1E2E),
    surface = Color(0xFF181825),
    surfaceVariant = Color(0xFF313244),
    onBackground = Color(0xFFCDD6F4),
    onSurface = Color(0xFFCDD6F4)
)

private val TokyoNightColors = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF15161E),
    secondary = Color(0xFFBB9AF7),
    background = Color(0xFF1A1B26),
    surface = Color(0xFF16161E),
    surfaceVariant = Color(0xFF24283B),
    onBackground = Color(0xFFC0CAF5),
    onSurface = Color(0xFFC0CAF5)
)

private val NordColors = darkColorScheme(
    primary = Color(0xFF88C0D0),
    onPrimary = Color(0xFF2E3440),
    secondary = Color(0xFF81A1C1),
    background = Color(0xFF2E3440),
    surface = Color(0xFF272C36),
    surfaceVariant = Color(0xFF3B4252),
    onBackground = Color(0xFFECEFF4),
    onSurface = Color(0xFFECEFF4)
)

@Composable
fun MovieBoxTheme(
    appTheme: AppTheme = AppTheme.MOCHA,
    content: @Composable () -> Unit
) {
    val scheme = when (appTheme) {
        AppTheme.MOCHA -> MochaColors
        AppTheme.TOKYO_NIGHT -> TokyoNightColors
        AppTheme.NORD -> NordColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
