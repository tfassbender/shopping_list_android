package com.tfassbender.shoppinglist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tfassbender.shoppinglist.domain.Theme

/**
 * The app's colors - the same tokens as the web app (`frontend/src/index.css` in the server repo): light
 * blue brand bar with dark navy text on it, white/near-black surface, a slightly raised fill for inputs
 * and hover, thin lines, muted secondary text. Components use these instead of Material's roles.
 */
@Immutable
data class AppColors(
    val brand: Color,
    val brandInk: Color,
    /** Blue for small text, links and switches on the surface. */
    val accent: Color,
    val surface: Color,
    /** Input fills, pressed rows, chips. */
    val raised: Color,
    val line: Color,
    val fg: Color,
    val muted: Color,
    val danger: Color,
    val dangerBg: Color,
    val dangerFg: Color,
    val isDark: Boolean,
)

val LightColors = AppColors(
    brand = Color(0xFF8CCFF5),
    brandInk = Color(0xFF0B2A42),
    accent = Color(0xFF0A6AA6),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFF1F6FA),
    line = Color(0xFFDDE7EE),
    fg = Color(0xFF13212B),
    muted = Color(0xFF5F7180),
    danger = Color(0xFFDC2626),
    dangerBg = Color(0xFFFEF2F2),
    dangerFg = Color(0xFFB91C1C),
    isDark = false,
)

val DarkColors = AppColors(
    brand = Color(0xFF6DBBE8),
    brandInk = Color(0xFF0B2A42),
    accent = Color(0xFF8FD0F7),
    surface = Color(0xFF0F171E),
    raised = Color(0xFF18232D),
    line = Color(0xFF24323E),
    fg = Color(0xFFE6EEF4),
    muted = Color(0xFF93A6B5),
    danger = Color(0xFFF87171),
    dangerBg = Color(0xFF450A0A),
    dangerFg = Color(0xFFFCA5A5),
    isDark = true,
)

private val LocalAppColors = staticCompositionLocalOf { LightColors }

/** Access to the app colors: `AppTheme.colors.brand`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

/**
 * Larger type for use in a shop (the web app sets the root font size to 17px): body 17sp, items 19sp,
 * titles ~23sp.
 */
private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
)

fun isDarkTheme(preference: Theme, systemDark: Boolean): Boolean =
    preference == Theme.DARK || (preference == Theme.SYSTEM && systemDark)

@Composable
fun ShoppingListTheme(preference: Theme = Theme.SYSTEM, content: @Composable () -> Unit) {
    val colors = if (isDarkTheme(preference, isSystemInDarkTheme())) DarkColors else LightColors
    val material = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.surface, background = colors.surface, onBackground = colors.fg,
            surface = colors.surface, onSurface = colors.fg, surfaceVariant = colors.raised, onSurfaceVariant = colors.muted,
            surfaceContainerLow = colors.surface, surfaceContainer = colors.surface, surfaceContainerHigh = colors.surface,
            outline = colors.line, outlineVariant = colors.line, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.surface, background = colors.surface, onBackground = colors.fg,
            surface = colors.surface, onSurface = colors.fg, surfaceVariant = colors.raised, onSurfaceVariant = colors.muted,
            surfaceContainerLow = colors.surface, surfaceContainer = colors.surface, surfaceContainerHigh = colors.surface,
            outline = colors.line, outlineVariant = colors.line, error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = material, typography = AppTypography, content = content)
    }
}
