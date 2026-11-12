package io.github.edrock200.appdownloader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.edrock200.appdownloader.ui.rememberIsTv

// A complete blue / teal / amber palette, so containers (update cards, badges, buttons) don't fall
// back to Material's default purple and pink on devices without dynamic colour (most TVs).
private val Light = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8ECE6),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0B2),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
    surfaceVariant = Color(0xFFE1E4EA),
    onSurfaceVariant = Color(0xFF44474E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF0D47A1),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF004D44),
    onSecondaryContainer = Color(0xFFC8ECE6),
    tertiary = Color(0xFFFFB300),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF5C3F00),
    onTertiaryContainer = Color(0xFFFFE0B2),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF2B3036),
    onSurfaceVariant = Color(0xFFC3C7CF),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val isTv = rememberIsTv()
    // TVs are watched in the dark and rarely have a dark-mode setting: always use the dark palette there.
    val dark = isTv || isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        !isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
