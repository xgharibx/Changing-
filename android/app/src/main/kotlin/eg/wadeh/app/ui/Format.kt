package eg.wadeh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import eg.wadeh.core.Digits
import eg.wadeh.core.Money
import eg.wadeh.core.MoneyFormat
import eg.wadeh.core.PreciseDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val CAIRO: ZoneId = ZoneId.of("Africa/Cairo")

@Composable @ReadOnlyComposable
fun isArabic(): Boolean = LocalConfiguration.current.locales[0].language == "ar"

fun money(minor: Long?, currency: String, arabic: Boolean): String =
    minor?.let { MoneyFormat.format(Money(it, currency), arabic) } ?: "—"

/** Wrap with LRM/RLM isolation so a Latin unit code or an amount never reorders an Arabic sentence. */
fun isolate(s: String) = "⁨$s⁩"

fun num(n: Number, arabic: Boolean) = if (arabic) Digits.toArabicIndic(n.toString()) else n.toString()
fun numStr(s: String, arabic: Boolean) = if (arabic) Digits.toArabicIndic(s) else s

private fun locale(arabic: Boolean): Locale = if (arabic) Locale.forLanguageTag("ar-EG") else Locale.forLanguageTag("en-GB")

fun date(iso: String?, arabic: Boolean): String {
    if (iso == null) return "—"
    if (iso == "ready") return if (arabic) "استلام فوري" else "ready to move"
    return runCatching {
        when {
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(iso) -> LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale(arabic)))
            Regex("^\\d{4}-Q[1-4]$").matches(iso) -> {
                val q = iso.last().digitToInt(); val y = iso.take(4)
                if (arabic) "الربع ${listOf("الأول", "الثاني", "الثالث", "الرابع")[q - 1]} ${Digits.toArabicIndic(y)}" else "Q$q $y"
            }
            iso.contains('T') -> Instant.parse(iso).atZone(CAIRO).format(DateTimeFormatter.ofPattern(if (arabic) "d MMM yyyy، HH:mm" else "d MMM yyyy, HH:mm", locale(arabic)))
            else -> PreciseDate.parseIso(iso).iso()
        }.let { if (arabic) Digits.toArabicIndic(it) else it }
    }.getOrDefault(iso)
}

fun dateTime(i: Instant, arabic: Boolean): String = i.atZone(CAIRO).format(DateTimeFormatter.ofPattern(if (arabic) "d MMM، HH:mm" else "d MMM, HH:mm", locale(arabic))).let { if (arabic) Digits.toArabicIndic(it) else it }

@Composable
fun stringByName(prefix: String, key: String, fallback: String = key): String {
    val ctx = LocalContext.current
    val id = ctx.resources.getIdentifier("${prefix}_$key", "string", ctx.packageName)
    return if (id != 0) ctx.getString(id) else fallback
}
