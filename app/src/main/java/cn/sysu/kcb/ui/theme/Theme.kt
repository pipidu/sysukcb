package cn.sysu.kcb.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import cn.sysu.kcb.data.prefs.SettingsRepository

val PresetThemeColors = listOf(
    0xFF8C1A1A to "中大红",
    0xFFC62828 to "朱红",
    0xFF1A237E to "靛蓝",
    0xFF00695C to "青绿",
    0xFF1B5E20 to "墨绿",
    0xFF4A148C to "暮紫",
    0xFFE65100 to "琥珀",
    0xFF37474F to "石板",
)

fun colorSchemeFromSeed(seed: Long, dark: Boolean): ColorScheme {
    val primary = Color(seed)
    val onPrimary = if (primary.luminance() > 0.45f) Color(0xFF1A1A1A) else Color.White
    val container = if (dark) primary.copy(alpha = 0.24f).compositeOn(Color(0xFF141218)) else primary.copy(alpha = 0.12f).compositeOn(Color.White)
    val background = if (dark) Color(0xFF141218) else Color(0xFFF7F4F4)
    val surface = if (dark) Color(0xFF1C1B1F) else Color(0xFFFFFBFA)
    val onBg = if (dark) Color(0xFFE6E1E5) else Color(0xFF1C1B1F)
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onBg,
            secondary = primary,
            background = background,
            surface = surface,
            onBackground = onBg,
            onSurface = onBg,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = Color(0xFF3B0A0A),
            secondary = primary,
            background = background,
            surface = surface,
            onBackground = onBg,
            onSurface = onBg,
        )
    }
}

private fun Color.compositeOn(bg: Color): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(
        red = red * a + bg.red * (1 - a),
        green = green * a + bg.green * (1 - a),
        blue = blue * a + bg.blue * (1 - a),
        alpha = 1f,
    )
}

@Composable
fun KcbTheme(
    themeColor: Long = SettingsRepository.DEFAULT_THEME_COLOR,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = colorSchemeFromSeed(themeColor, darkTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                scheme.primary.luminance() > 0.45f
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
