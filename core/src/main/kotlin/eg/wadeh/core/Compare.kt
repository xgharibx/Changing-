package eg.wadeh.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Side-by-side comparison derived ONLY from the shared engine, so the phone, the API, the web share page and the AI
 * tools print the same numbers. Price per m² is suppressed when area bases, currencies or price bases differ.
 */
@Serializable
data class CompareInput(
    val id: String,
    val currency: String,
    val priceMinor: Long?,
    val priceBasis: String,          // cash | instalment_list | monthly_rent
    val cashPriceMinor: Long?,
    val areaM2: String?,
    val areaBasis: String,
    val handover: String?,
    val plan: PaymentPlan?,
)

@Serializable
data class CompareRow(
    val id: String,
    val nominalMinor: Long?,
    val cashPriceMinor: Long?,
    val knownMandatoryMinor: Long?,
    val exactTotalKnown: Boolean,
    val unresolvedCount: Int,
    val cashToStartMinor: Long?,
    val firstPaymentDate: String?,
    val first12MonthsMinor: Long?,
    val dueByHandoverMinor: Long?,
    val largestSingleDate: String?,
    val largestSingleDateMinor: Long?,
    val pricePerM2Minor: Long?,
    val handover: String?,
)

@Serializable
data class Comparison(
    val engineVersion: String,
    val rows: List<CompareRow>,
    /** Why a metric is not shown for all rows (e.g. "area basis differs: GROSS vs NET"). */
    val suppressed: Map<String, String>,
    /** Metric -> id of the row that is lowest, only when every row has a comparable value. */
    val lowest: Map<String, String>,
)

object Compare {
    fun build(items: List<CompareInput>): Comparison {
        require(items.size in 1..4) { "Compare 1 to 4 items" }
        val suppressed = linkedMapOf<String, String>()
        val currencies = items.map { it.currency }.toSet()
        if (currencies.size > 1) suppressed["all_money"] = "Different currencies: ${currencies.joinToString()}"
        val bases = items.map { it.areaBasis }.toSet()
        val ppmOk = bases.size == 1 && "UNKNOWN" !in bases && items.map { it.priceBasis }.toSet().size == 1
        if (!ppmOk) suppressed["price_per_m2"] = when {
            "UNKNOWN" in bases -> "At least one area basis is not stated"
            bases.size > 1 -> "Area basis differs: ${bases.joinToString(" vs ")}"
            else -> "Price basis differs: ${items.map { it.priceBasis }.toSet().joinToString(" vs ")}"
        }
        val rows = items.map { i ->
            val cf = i.plan?.let { CashFlowEngine.compute(it) }
            val handoverEnd = i.handover?.let { runCatching { PreciseDate.parseIso(it).latest() }.getOrNull() }
            val ppm = if (ppmOk && i.priceMinor != null && i.areaM2 != null) BigDecimal.valueOf(i.priceMinor)
                .divide(BigDecimal(i.areaM2), 0, RoundingMode.HALF_UP).longValueExact() else null
            CompareRow(
                id = i.id, nominalMinor = i.priceMinor, cashPriceMinor = i.cashPriceMinor,
                knownMandatoryMinor = cf?.knownMandatoryMinor ?: i.cashPriceMinor,
                exactTotalKnown = cf?.exactTotalKnown ?: (i.cashPriceMinor != null),
                unresolvedCount = cf?.unresolved?.count { it.mandatory } ?: 0,
                cashToStartMinor = cf?.cashToStartMinor?.takeIf { cf.firstPaymentDate != null } ?: i.cashPriceMinor,
                firstPaymentDate = cf?.firstPaymentDate,
                first12MonthsMinor = cf?.first12MonthsMinor ?: i.cashPriceMinor,
                dueByHandoverMinor = if (cf != null && handoverEnd != null) CashFlowEngine.dueBetween(cf.schedule, LocalDate.MIN, handoverEnd) else null,
                largestSingleDate = cf?.largestSingleDate, largestSingleDateMinor = cf?.largestSingleDateMinor,
                pricePerM2Minor = ppm, handover = i.handover,
            )
        }
        val lowest = linkedMapOf<String, String>()
        if ("all_money" !in suppressed && rows.size > 1) {
            fun low(name: String, f: (CompareRow) -> Long?) {
                val vals = rows.map { it to f(it) }
                if (vals.all { it.second != null }) { val min = vals.minOf { it.second!! }; vals.filter { it.second == min }.singleOrNull()?.let { lowest[name] = it.first.id } }
            }
            low("cash_to_start") { it.cashToStartMinor }
            low("first_12_months") { it.first12MonthsMinor }
            // A total with an unresolved mandatory fee is never declared "lowest".
            if (rows.all { it.exactTotalKnown }) low("known_mandatory") { it.knownMandatoryMinor } else suppressed["known_mandatory_lowest"] = "At least one offer has a mandatory fee with no amount"
            if (ppmOk) low("price_per_m2") { it.pricePerM2Minor }
        }
        return Comparison(ENGINE_VERSION, rows, suppressed, lowest)
    }
}
