package eg.wadeh.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

object Digits {
    private const val ARABIC_INDIC = "٠١٢٣٤٥٦٧٨٩"
    private const val PERSIAN = "۰۱۲۳۴۵۶۷۸۹"

    fun toWestern(s: String): String = buildString(s.length) {
        for (c in s) {
            val a = ARABIC_INDIC.indexOf(c); val p = PERSIAN.indexOf(c)
            append(
                when {
                    a >= 0 -> '0' + a
                    p >= 0 -> '0' + p
                    c == '٫' -> '.'   // Arabic decimal separator
                    c == '٬' -> ','   // Arabic thousands separator
                    else -> c
                }
            )
        }
    }

    fun toArabicIndic(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '0'..'9') ARABIC_INDIC[c - '0'] else c)
    }
}

/** Normalisation used for matching and search only. Original text is always stored separately. */
object ArabicText {
    private val diacritics = Regex("[\\u064B-\\u0652\\u0670\\u0640]") // harakat, dagger alef, tatweel

    fun normalize(input: String): String {
        var s = Digits.toWestern(input).lowercase().trim()
        s = diacritics.replace(s, "")
        s = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ى', 'ي').replace('ئ', 'ي').replace('ؤ', 'و').replace('ة', 'ه')
        s = s.replace(Regex("[\\p{Punct}،؛؟«»]"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}

sealed interface Parsed<out T> {
    data class Ok<T>(val value: T, val warning: String? = null) : Parsed<T>
    data class Err(val message: String) : Parsed<Nothing>
    data object Missing : Parsed<Nothing>
}

/** Tolerant parsers for supplier spreadsheets: Arabic-Indic digits, currency strings, Egyptian date conventions. */
object SupplierParse {
    private val currencyTokens = listOf("egp", "le", "l.e", "l.e.", "جنيه", "جنيها", "جنيهاً", "ج.م", "ج م", "ج", "pounds", "pound")

    fun blank(s: String?) = s == null || s.isBlank() || s.trim() in setOf("-", "—", "n/a", "N/A", "غير متاح", "لا يوجد")

    /** Returns an amount in MAJOR units (e.g. pounds). Accepts "8,000,000", "٨٬٠٠٠٬٠٠٠ ج.م", "EGP 8m", "8 مليون", "750 ألف". */
    fun amount(raw: String?): Parsed<BigDecimal> {
        if (blank(raw)) return Parsed.Missing
        var s = Digits.toWestern(raw!!).lowercase().trim()
        for (t in currencyTokens.sortedByDescending { it.length }) s = s.replace(t, " ")
        s = s.replace(" ", " ").trim()
        var multiplier = BigDecimal.ONE
        var warning: String? = null
        when {
            Regex("(مليون|million|mn|m)\\s*$").containsMatchIn(s) -> { multiplier = BigDecimal(1_000_000); s = s.replace(Regex("(مليون|million|mn|m)\\s*$"), "") ; warning = "Amount used a 'million' shorthand; confirm exact figure" }
            Regex("(الف|ألف|k|thousand)\\s*$").containsMatchIn(s) -> { multiplier = BigDecimal(1_000); s = s.replace(Regex("(الف|ألف|k|thousand)\\s*$"), ""); warning = "Amount used a 'thousand' shorthand; confirm exact figure" }
        }
        s = s.replace(" ", "")
        // Thousands separators: commas, or dots used as thousands groups like 8.000.000
        if (Regex("^\\d{1,3}(\\.\\d{3}){2,}$").matches(s)) s = s.replace(".", "")
        s = s.replace(",", "")
        if (!Regex("^-?\\d+(\\.\\d+)?$").matches(s)) return Parsed.Err("Not a number: '$raw'")
        val v = BigDecimal(s).multiply(multiplier)
        if (v.signum() < 0) return Parsed.Err("Negative amount: '$raw'")
        return Parsed.Ok(v, warning)
    }

    fun int(raw: String?): Parsed<Int> {
        if (blank(raw)) return Parsed.Missing
        val s = Digits.toWestern(raw!!).trim().removeSuffix(".0")
        return s.toIntOrNull()?.let { Parsed.Ok(it) } ?: Parsed.Err("Not a whole number: '$raw'")
    }

    fun decimal(raw: String?): Parsed<BigDecimal> {
        if (blank(raw)) return Parsed.Missing
        val s = Digits.toWestern(raw!!).trim().replace(",", "").replace(Regex("(m2|م2|م²|m²|sqm|متر)"), "").trim()
        return runCatching { Parsed.Ok(BigDecimal(s)) }.getOrElse { Parsed.Err("Not a number: '$raw'") }
    }

    /**
     * Dates: ISO yyyy-mm-dd is unambiguous. d/m/yyyy follows the Egyptian day-first convention;
     * when both parts are <= 12 and differ, we accept day-first but attach a warning so the reviewer sees it.
     * Month precision ("2027-03", "Q2 2027") is returned as DatePrecision.
     */
    fun date(raw: String?): Parsed<PreciseDate> {
        if (blank(raw)) return Parsed.Missing
        val s = Digits.toWestern(raw!!).trim()
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matchEntire(s)?.let { m ->
            return runCatching { Parsed.Ok(PreciseDate.day(LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()))) }
                .getOrElse { Parsed.Err("Invalid date: '$raw'") }
        }
        Regex("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})$").matchEntire(s)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
            return runCatching {
                val warn = if (a <= 12 && b <= 12 && a != b) "Ambiguous date '$raw' read as day/month (Egyptian convention)" else null
                Parsed.Ok(PreciseDate.day(LocalDate.of(y, b, a)), warn)
            }.getOrElse { Parsed.Err("Invalid date: '$raw'") }
        }
        Regex("^(\\d{4})-(\\d{1,2})$").matchEntire(s)?.let { m ->
            return runCatching { Parsed.Ok(PreciseDate.month(YearMonth.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()))) }
                .getOrElse { Parsed.Err("Invalid month: '$raw'") }
        }
        Regex("^(?:q|Q|ربع)\\s*([1-4])\\s*[-/ ]?\\s*(\\d{4})$").matchEntire(s)?.let { m ->
            return Parsed.Ok(PreciseDate.quarter(m.groupValues[2].toInt(), m.groupValues[1].toInt()))
        }
        Regex("^(\\d{4})\\s*[-/ ]?\\s*(?:q|Q|ربع)\\s*([1-4])$").matchEntire(s)?.let { m ->
            return Parsed.Ok(PreciseDate.quarter(m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        }
        Regex("^(\\d{4})$").matchEntire(s)?.let { m -> return Parsed.Ok(PreciseDate.year(m.groupValues[1].toInt())) }
        return Parsed.Err("Unrecognised date: '$raw' (use YYYY-MM-DD)")
    }
}
