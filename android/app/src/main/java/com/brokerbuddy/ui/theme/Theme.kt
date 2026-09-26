package com.brokerbuddy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brokerbuddy.R

/** Brand palette (from the BrokerBuddy home-screen design). */
object Brand {
    val Navy = Color(0xFF0B1F4B)
    val Blue = Color(0xFF2563EB)
    val BlueSoft = Color(0xFFE8F0FE)
    val Background = Color(0xFFF4F7FE)
    val Surface = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF111827)
    val TextSecondary = Color(0xFF6B7280)
    val Divider = Color(0xFFE9EDF5)
    val Green = Color(0xFF16A34A)
    val GreenSoft = Color(0xFFE6F6EC)
    val Red = Color(0xFFE11D48)
    val RedSoft = Color(0xFFFDE8EC)
    val Purple = Color(0xFF6D28D9)
    val PurpleSoft = Color(0xFFEFE9FE)
    val Amber = Color(0xFFB45309)
    val AmberSoft = Color(0xFFFEF3DC)
    val WhatsApp = Color(0xFF25D366)
}

/** Pastel container + strong content colour pairs used for chips, badges and icon tiles. */
@Immutable
data class Tint(val container: Color, val content: Color)

@Immutable
data class BrandColors(
    val background: Color,
    val card: Color,
    val navy: Color,
    val link: Color,
    val muted: Color,
    val divider: Color,
    val success: Tint,
    val danger: Tint,
    val info: Tint,
    val purple: Tint,
    val amber: Tint,
    val neutral: Tint,
    val teal: Tint,
    val heroTop: Color,
    val heroBottom: Color,
)

private val LightBrand = BrandColors(
    background = Brand.Background,
    card = Brand.Surface,
    navy = Brand.Navy,
    link = Brand.Blue,
    muted = Brand.TextSecondary,
    divider = Brand.Divider,
    success = Tint(Brand.GreenSoft, Brand.Green),
    danger = Tint(Brand.RedSoft, Brand.Red),
    info = Tint(Brand.BlueSoft, Brand.Blue),
    purple = Tint(Brand.PurpleSoft, Brand.Purple),
    amber = Tint(Brand.AmberSoft, Brand.Amber),
    neutral = Tint(Color(0xFFEEF0F4), Color(0xFF374151)),
    teal = Tint(Color(0xFFE4F6F4), Color(0xFF0F766E)),
    heroTop = Color(0xFFEAF2FF),
    heroBottom = Color(0xFFFFF4E8),
)

private val DarkBrand = BrandColors(
    background = Color(0xFF0B1220),
    card = Color(0xFF131C2E),
    navy = Color(0xFFDCE6FF),
    link = Color(0xFF8AB0FF),
    muted = Color(0xFF9AA4B8),
    divider = Color(0xFF223047),
    success = Tint(Color(0xFF12301F), Color(0xFF6EE7A0)),
    danger = Tint(Color(0xFF3A1520), Color(0xFFFF8FA8)),
    info = Tint(Color(0xFF16284A), Color(0xFF8AB0FF)),
    purple = Tint(Color(0xFF2A1B4A), Color(0xFFC4B0FF)),
    amber = Tint(Color(0xFF3A2A10), Color(0xFFFFC870)),
    neutral = Tint(Color(0xFF222B3B), Color(0xFFCBD2DE)),
    teal = Tint(Color(0xFF10302D), Color(0xFF6EE0D2)),
    heroTop = Color(0xFF15233F),
    heroBottom = Color(0xFF2A2233),
)

val LocalBrand = staticCompositionLocalOf { LightBrand }

/** Access brand colours: `MaterialTheme.brand.link`. */
val MaterialTheme.brand: BrandColors
    @Composable get() = LocalBrand.current

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, line: Int = size + 6) =
    TextStyle(fontFamily = Poppins, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val AppTypography = Typography(
    displaySmall = style(34, FontWeight.Bold, 40),
    headlineLarge = style(30, FontWeight.Bold, 36),
    headlineMedium = style(26, FontWeight.Bold, 32),
    headlineSmall = style(22, FontWeight.Bold, 28),
    titleLarge = style(20, FontWeight.SemiBold, 26),
    titleMedium = style(16, FontWeight.SemiBold, 22),
    titleSmall = style(14, FontWeight.SemiBold, 20),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(12, FontWeight.Normal, 16),
    labelLarge = style(14, FontWeight.Medium, 20),
    labelMedium = style(12, FontWeight.Medium, 16),
    labelSmall = style(11, FontWeight.Medium, 14),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightColors = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Brand.BlueSoft,
    onPrimaryContainer = Brand.Navy,
    secondary = Brand.Navy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9F7),
    onSecondaryContainer = Brand.Navy,
    tertiary = Brand.Purple,
    tertiaryContainer = Brand.PurpleSoft,
    background = Brand.Background,
    onBackground = Brand.TextPrimary,
    surface = Brand.Surface,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Color(0xFFF0F3FA),
    onSurfaceVariant = Brand.TextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF7F9FD),
    // Default Card colour: white cards on the light-blue page, as in the design.
    surfaceContainerHighest = Color.White,
    outline = Color(0xFFD5DCE8),
    outlineVariant = Brand.Divider,
    error = Brand.Red,
    errorContainer = Brand.RedSoft,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB0FF),
    onPrimary = Color(0xFF0A1B3D),
    primaryContainer = Color(0xFF1B3263),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFDCE6FF),
    secondaryContainer = Color(0xFF223047),
    tertiary = Color(0xFFC4B0FF),
    background = DarkBrand.background,
    surface = DarkBrand.card,
    surfaceVariant = Color(0xFF1A2438),
    surfaceContainerLowest = DarkBrand.card,
    surfaceContainerLow = DarkBrand.card,
    surfaceContainer = DarkBrand.card,
    surfaceContainerHigh = Color(0xFF1A2438),
    surfaceContainerHighest = Color(0xFF222D44),
    onSurfaceVariant = DarkBrand.muted,
    outlineVariant = DarkBrand.divider,
    error = Color(0xFFFF8FA8),
)

@Composable
fun BrokerBuddyTheme(content: @Composable () -> Unit) {
    // Brand colours are fixed (no wallpaper-based dynamic colour) so the app matches the design.
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalBrand provides if (dark) DarkBrand else LightBrand) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/**
 * Main action button (Sign in, Create account, Send OTP, Verify): navy in light mode, brand blue in
 * dark mode, where navy would disappear into the dark card; white text in both.
 */
@Composable
fun primaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = if (isSystemInDarkTheme()) Brand.Blue else Brand.Navy,
    contentColor = Color.White,
)
