package com.samjaydeguy.fps1

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

enum class Fps1ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light")
}

data class Fps1Palette(
    val background: Color,
    val panel: Color,
    val elevated: Color,
    val accent: Color,
    val secondaryAccent: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val purple: Color
)

private val DarkPalette = Fps1Palette(
    background = Color(0xFF101318),
    panel = Color(0xFF20242B),
    elevated = Color(0xFF292E36),
    accent = Color(0xFFD4AF37),
    secondaryAccent = Color(0xFFE8C968),
    text = Color(0xFFF5F5F5),
    muted = Color(0xFFA6ABB4),
    border = Color(0xFF363B44),
    success = Color(0xFF22C55E),
    warning = Color(0xFFF59E0B),
    error = Color(0xFFEF4444),
    purple = Color(0xFFAA91F5)
)

private val LightPalette = Fps1Palette(
    background = Color(0xFFF4F5F7),
    panel = Color(0xFFFFFFFF),
    elevated = Color(0xFFEAECF0),
    accent = Color(0xFFB58B22),
    secondaryAccent = Color(0xFFD4AF37),
    text = Color(0xFF17191F),
    muted = Color(0xFF667085),
    border = Color(0xFFDDE1E7),
    success = Color(0xFF15803D),
    warning = Color(0xFFB45309),
    error = Color(0xFFDC2626),
    purple = Color(0xFF7955C7)
)

internal val LocalFps1Palette = compositionLocalOf { DarkPalette }

private const val THEME_PREFS = "fps1_theme_preferences"
private const val THEME_KEY = "theme_mode"

fun loadFps1ThemeMode(context: Context): Fps1ThemeMode {
    val saved = context.getSharedPreferences(
        THEME_PREFS,
        Context.MODE_PRIVATE
    ).getString(THEME_KEY, Fps1ThemeMode.SYSTEM.name)

    return Fps1ThemeMode.values().firstOrNull {
        it.name == saved
    } ?: Fps1ThemeMode.SYSTEM
}

fun saveFps1ThemeMode(context: Context, mode: Fps1ThemeMode) {
    context.getSharedPreferences(
        THEME_PREFS,
        Context.MODE_PRIVATE
    ).edit().putString(THEME_KEY, mode.name).apply()
}

@Composable
fun Fps1Theme(
    mode: Fps1ThemeMode,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (mode) {
        Fps1ThemeMode.SYSTEM -> isSystemInDarkTheme()
        Fps1ThemeMode.DARK -> true
        Fps1ThemeMode.LIGHT -> false
    }

    val palette = if (useDarkTheme) DarkPalette else LightPalette

    val colorScheme = if (useDarkTheme) {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.secondaryAccent,
            background = palette.background,
            surface = palette.panel,
            surfaceVariant = palette.elevated,
            onPrimary = Color(0xFF17130A),
            onBackground = palette.text,
            onSurface = palette.text,
            onSurfaceVariant = palette.muted,
            error = palette.error,
            outline = palette.border
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.secondaryAccent,
            background = palette.background,
            surface = palette.panel,
            surfaceVariant = palette.elevated,
            onPrimary = Color.White,
            onBackground = palette.text,
            onSurface = palette.text,
            onSurfaceVariant = palette.muted,
            error = palette.error,
            outline = palette.border
        )
    }

    CompositionLocalProvider(
        LocalFps1Palette provides palette
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
