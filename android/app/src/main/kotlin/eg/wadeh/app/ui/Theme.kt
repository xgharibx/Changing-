package eg.wadeh.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import eg.wadeh.app.R

/** IBM Plex Sans Arabic (SIL OFL 1.1): Arabic and Latin in one family with legible tabular numerals. */
val Plex = FontFamily(
    Font(R.font.plex_arabic_regular, FontWeight.Normal),
    Font(R.font.plex_arabic_medium, FontWeight.Medium),
    Font(R.font.plex_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.plex_arabic_bold, FontWeight.Bold),
)

/** Status colours are always paired with text or an icon; colour is never the only signal. */
@Immutable
data class StatusColors(val ok: Color, val okBg: Color, val warn: Color, val warnBg: Color, val bad: Color, val badBg: Color, val muted: Color, val line: Color, val money: Color)

val LocalStatus = staticCompositionLocalOf { lightStatus }

private val lightStatus = StatusColors(
    ok = Color(0xFF0B5D3F), okBg = Color(0xFFE2F3EA), warn = Color(0xFF7A4B00), warnBg = Color(0xFFFFF1D6),
    bad = Color(0xFF9B1C1C), badBg = Color(0xFFFCE4E4), muted = Color(0xFF55625B), line = Color(0xFFDCE3DE), money = Color(0xFF10231A),
)
private val darkStatus = StatusColors(
    ok = Color(0xFF8BD8B2), okBg = Color(0xFF123526), warn = Color(0xFFFFCF7A), warnBg = Color(0xFF3A2A08),
    bad = Color(0xFFFFA4A4), badBg = Color(0xFF3D1414), muted = Color(0xFFA8B5AE), line = Color(0xFF2A3630), money = Color(0xFFF2F7F4),
)

private val light = lightColorScheme(
    primary = Color(0xFF0F5C45), onPrimary = Color.White, primaryContainer = Color(0xFFCFEBDD), onPrimaryContainer = Color(0xFF052117),
    secondary = Color(0xFF8A5A00), secondaryContainer = Color(0xFFFFE3A8), background = Color(0xFFF6F7F4), surface = Color.White,
    surfaceVariant = Color(0xFFEEF2EF), onSurface = Color(0xFF10231A), onSurfaceVariant = Color(0xFF45524B), outline = Color(0xFFB9C6BF),
)
private val dark = darkColorScheme(
    primary = Color(0xFF8BD8B2), onPrimary = Color(0xFF003824), primaryContainer = Color(0xFF0F5C45), onPrimaryContainer = Color(0xFFCFEBDD),
    secondary = Color(0xFFFFCF7A), background = Color(0xFF0F1512), surface = Color(0xFF161E1A), surfaceVariant = Color(0xFF1F2924),
    onSurface = Color(0xFFE6EEE9), onSurfaceVariant = Color(0xFFB8C6BE), outline = Color(0xFF55625B),
)

private fun t(size: Int, weight: FontWeight, line: Int) = TextStyle(fontFamily = Plex, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)

private val typography = Typography(
    headlineSmall = t(22, FontWeight.Bold, 32),
    titleLarge = t(20, FontWeight.SemiBold, 30),
    titleMedium = t(17, FontWeight.SemiBold, 26),
    titleSmall = t(15, FontWeight.SemiBold, 22),
    bodyLarge = t(16, FontWeight.Normal, 26),
    bodyMedium = t(15, FontWeight.Normal, 24),
    bodySmall = t(13, FontWeight.Normal, 20),
    labelLarge = t(15, FontWeight.SemiBold, 22),
    labelMedium = t(13, FontWeight.Medium, 18),
    labelSmall = t(12, FontWeight.Medium, 16),
)

@Composable
fun WadehTheme(content: @Composable () -> Unit) {
    val darkMode = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides if (darkMode) darkStatus else lightStatus) {
        MaterialTheme(colorScheme = if (darkMode) dark else light, typography = typography, content = content)
    }
}
