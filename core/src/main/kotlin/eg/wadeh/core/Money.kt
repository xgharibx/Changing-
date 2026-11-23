package eg.wadeh.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Currency rules live in one table so a second country only adds a row.
 * minorDigits = digits after the decimal point that the currency supports.
 */
data class CurrencyRule(val code: String, val minorDigits: Int, val symbolEn: String, val symbolAr: String)

object Currencies {
    private val rules = listOf(
        CurrencyRule("EGP", 2, "EGP", "ج.م"),
        CurrencyRule("SAR", 2, "SAR", "ر.س"),
        CurrencyRule("AED", 2, "AED", "د.إ"),
        CurrencyRule("GBP", 2, "£", "£"),
        CurrencyRule("USD", 2, "$", "$"),
        CurrencyRule("KWD", 3, "KWD", "د.ك"), // 3 minor digits: portability test currency
        CurrencyRule("JPY", 0, "¥", "¥"),
    ).associateBy { it.code }

    fun of(code: String): CurrencyRule =
        rules[code.uppercase()] ?: throw IllegalArgumentException("Unsupported currency: $code")

    fun isSupported(code: String) = rules.containsKey(code.uppercase())
}

/**
 * Fixed-precision money stored as an integer count of minor units.
 * Never constructed from Double.
 */
@JvmInline
value class Minor(val value: Long)

data class Money(val minor: Long, val currency: String) : Comparable<Money> {
    init { Currencies.of(currency) }

    operator fun plus(o: Money): Money { requireSame(o); return Money(Math.addExact(minor, o.minor), currency) }
    operator fun minus(o: Money): Money { requireSame(o); return Money(Math.subtractExact(minor, o.minor), currency) }
    override fun compareTo(other: Money): Int { requireSame(other); return minor.compareTo(other.minor) }
    fun isZero() = minor == 0L

    fun toMajor(): BigDecimal = BigDecimal.valueOf(minor, Currencies.of(currency).minorDigits)

    private fun requireSame(o: Money) =
        require(o.currency == currency) { "Currency mismatch: $currency vs ${o.currency}" }

    companion object {
        fun zero(currency: String) = Money(0, currency)

        /** Rounds HALF_UP to the currency's minor digits. The rounding rule is part of the engine version. */
        fun ofMajor(major: BigDecimal, currency: String, mode: RoundingMode = RoundingMode.HALF_UP): Money {
            val d = Currencies.of(currency).minorDigits
            return Money(major.setScale(d, mode).movePointRight(d).longValueExact(), currency)
        }

        fun ofMajor(major: String, currency: String) = ofMajor(BigDecimal(major), currency)
    }
}

object MoneyFormat {
    /** Formats with grouping; arabicDigits renders Eastern Arabic numerals. Minor part shown only if non-zero. */
    fun format(m: Money, arabic: Boolean, alwaysShowMinor: Boolean = false): String {
        val rule = Currencies.of(m.currency)
        val major = m.toMajor()
        val showMinor = alwaysShowMinor || major.stripTrailingZeros().scale() > 0
        val plain = (if (showMinor) major.setScale(rule.minorDigits) else major.setScale(0, RoundingMode.UNNECESSARY)).toPlainString()
        val neg = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val intPart = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(if (arabic) "٬" else ",").reversed()
        var s = (if (neg) "-" else "") + grouped + (if (frac.isNotEmpty()) (if (arabic) "٫" else ".") + frac else "")
        if (arabic) s = Digits.toArabicIndic(s)
        return if (arabic) "$s ${rule.symbolAr}" else "${rule.symbolEn} $s"
    }
}
