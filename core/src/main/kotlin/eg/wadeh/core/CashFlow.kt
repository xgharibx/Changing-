package eg.wadeh.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Versioned, deterministic cash-flow engine. The SAME code runs on the server (API, comparison, AI tools)
 * and on Android (offline). Change ENGINE_VERSION whenever an output can change for the same input.
 *
 * Rules (engine v1):
 *  - Amounts are integer minor units. Percentages are basis points (1% = 100 bps) applied to a declared basis
 *    and rounded HALF_UP to the currency's minor unit, once per item.
 *  - A "split" recurring item divides a total evenly; the remainder (in minor units) is added to the LAST payment.
 *  - An item with includedIn = X is credited against X: it is shown on the schedule as part of X and never added twice.
 *  - Unknown amounts are never treated as zero. Any mandatory unknown makes the exact total unknown.
 *  - Rent and refundable deposits are reported on their own lines, never mixed into purchase totals.
 */
const val ENGINE_VERSION = "cashflow-1.0.0"

@Serializable
enum class ItemKind {
    RESERVATION, DEPOSIT, INSTALMENT, BALLOON, HANDOVER, MAINTENANCE, CLUB, PARKING, FINISHING,
    BROKERAGE, TAX, OPTIONAL_EXTRA, RENT, SECURITY_DEPOSIT, OTHER_FEE
}

@Serializable
enum class PercentBasis { LIST_PRICE, CONTRACT_PRICE }

@Serializable
sealed interface Amount {
    @Serializable @SerialName("fixed") data class Fixed(val minor: Long) : Amount
    /** Total split evenly across a recurring schedule. */
    @Serializable @SerialName("split") data class Split(val totalMinor: Long) : Amount
    @Serializable @SerialName("percent") data class Percent(val basisPoints: Long, val basis: PercentBasis) : Amount
    @Serializable @SerialName("unknown") data object Unknown : Amount
}

@Serializable
sealed interface Due {
    @Serializable @SerialName("on") data class On(val date: PreciseDate) : Due
    @Serializable @SerialName("every") data class Every(val start: PreciseDate, val everyMonths: Int, val count: Int) : Due
    @Serializable @SerialName("handover") data object AtHandover : Due
    @Serializable @SerialName("unknown") data object Unknown : Due
}

@Serializable
enum class Certainty { FIXED, ESTIMATED, UNKNOWN }

@Serializable
data class PlanItem(
    val id: String,
    val kind: ItemKind,
    val amount: Amount,
    val due: Due,
    val mandatory: Boolean = true,
    val certainty: Certainty = Certainty.FIXED,
    val includedIn: String? = null,
    val refundable: Boolean = false,
    val labelEn: String? = null,
    val labelAr: String? = null,
)

@Serializable
data class PaymentPlan(
    val currency: String,
    /** Headline nominal price of this plan (e.g. instalment price). */
    val listPriceMinor: Long,
    /** Price after any discount this plan grants (e.g. cash discount). Defaults to list price. */
    val contractPriceMinor: Long = listPriceMinor,
    val handover: PreciseDate? = null,
    val items: List<PlanItem>,
    val nameEn: String? = null,
    val nameAr: String? = null,
)

@Serializable
data class ScheduledPayment(
    val date: String?,            // ISO date or precise-date string; null when unknown
    val datePrecision: PreciseDate.Precision?,
    val itemId: String,
    val kind: ItemKind,
    val amountMinor: Long?,       // null when amount unknown
    val mandatory: Boolean,
    val refundable: Boolean,
    val creditedFrom: List<String> = emptyList(), // ids of items credited inside this payment
    val sequence: Int? = null,
    val of: Int? = null,
)

@Serializable
data class UnresolvedCharge(val itemId: String, val kind: ItemKind, val reason: String, val mandatory: Boolean)

@Serializable
data class CashFlowSummary(
    val engineVersion: String,
    val currency: String,
    val schedule: List<ScheduledPayment>,
    /** Sum of every mandatory, known, non-refundable, non-rent amount. */
    val knownMandatoryMinor: Long,
    /** True only if no mandatory charge is unknown in amount. */
    val exactTotalKnown: Boolean,
    val exactMandatoryTotalMinor: Long?,
    val unresolved: List<UnresolvedCharge>,
    val optionalKnownMinor: Long,
    val refundableMinor: Long,
    val rentMinor: Long,
    val firstPaymentDate: String?,
    val cashToStartMinor: Long,
    val largestSingleDate: String?,
    val largestSingleDateMinor: Long,
    val first12MonthsMinor: Long,
)

object CashFlowEngine {

    fun resolveAmount(plan: PaymentPlan, a: Amount): Long? = when (a) {
        is Amount.Fixed -> a.minor
        is Amount.Split -> a.totalMinor
        is Amount.Percent -> {
            val base = if (a.basis == PercentBasis.LIST_PRICE) plan.listPriceMinor else plan.contractPriceMinor
            BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(a.basisPoints))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact()
        }
        Amount.Unknown -> null
    }

    private fun dueDates(plan: PaymentPlan, due: Due): List<PreciseDate?> = when (due) {
        is Due.On -> listOf(due.date)
        is Due.Every -> {
            require(due.count > 0 && due.everyMonths > 0) { "Recurring schedule needs positive count and interval" }
            val start = due.start
            (0 until due.count).map { i ->
                if (start.precision == PreciseDate.Precision.DAY) PreciseDate.day(start.earliest().plusMonths(i.toLong() * due.everyMonths))
                else null
            }
        }
        Due.AtHandover -> listOf(plan.handover)
        Due.Unknown -> listOf(null)
    }

    fun compute(plan: PaymentPlan): CashFlowSummary {
        Currencies.of(plan.currency)
        val byId = plan.items.associateBy { it.id }
        require(byId.size == plan.items.size) { "Duplicate plan item id" }
        plan.items.forEach { it.includedIn?.let { p -> require(byId.containsKey(p)) { "includedIn refers to unknown item $p" } } }

        val credited = plan.items.filter { it.includedIn != null }.groupBy { it.includedIn!! }
        val rows = mutableListOf<ScheduledPayment>()
        val unresolved = mutableListOf<UnresolvedCharge>()

        for (item in plan.items) {
            if (item.includedIn != null) continue // shown inside its parent
            val total = resolveAmount(plan, item.amount)
            val dates = dueDates(plan, item.due)
            if (total == null) unresolved += UnresolvedCharge(item.id, item.kind, "Amount not provided by source", item.mandatory)
            if (dates.any { it == null }) unresolved += UnresolvedCharge(item.id, item.kind, "Due date not provided by source", item.mandatory)
            val n = dates.size
            val per: List<Long?> = when {
                total == null -> List(n) { null }
                item.amount is Amount.Split || (item.due is Due.Every && item.amount !is Amount.Fixed) -> {
                    val base = total / n; val rem = total - base * n
                    List(n) { i -> if (i == n - 1) base + rem else base }
                }
                item.due is Due.Every -> List(n) { total } // Fixed amount = per payment
                else -> listOf(total)
            }
            dates.forEachIndexed { i, d ->
                rows += ScheduledPayment(
                    date = d?.iso(), datePrecision = d?.precision, itemId = item.id, kind = item.kind,
                    amountMinor = per[i], mandatory = item.mandatory, refundable = item.refundable,
                    creditedFrom = if (i == 0) credited[item.id].orEmpty().map { it.id } else emptyList(),
                    sequence = if (n > 1) i + 1 else null, of = if (n > 1) n else null,
                )
            }
        }

        val sorted = rows.sortedWith(compareBy<ScheduledPayment, LocalDate?>(nullsLast()) { it.date?.let { d -> PreciseDate.parseIso(d).earliest() } }.thenBy { it.kind.ordinal })

        fun purchase(p: ScheduledPayment) = p.kind != ItemKind.RENT && !p.refundable
        val knownMandatory = sorted.filter { it.mandatory && purchase(it) }.sumOf { it.amountMinor ?: 0L }
        val mandatoryUnknownAmount = unresolved.any { it.mandatory && it.reason.startsWith("Amount") }
        val optional = sorted.filter { !it.mandatory && purchase(it) }.sumOf { it.amountMinor ?: 0L }
        val refundable = sorted.filter { it.refundable }.sumOf { it.amountMinor ?: 0L }
        val rent = sorted.filter { it.kind == ItemKind.RENT }.sumOf { it.amountMinor ?: 0L }

        val dayRows = sorted.filter { it.mandatory && it.datePrecision == PreciseDate.Precision.DAY && it.amountMinor != null }
        val firstDate = dayRows.minOfOrNull { LocalDate.parse(it.date!!) }
        val byDate = dayRows.groupBy { it.date!! }.mapValues { (_, v) -> v.sumOf { it.amountMinor!! } }
        val largest = byDate.maxWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenByDescending { it.key })
        val first12 = firstDate?.let { f -> dueBetween(sorted, f, f.plusMonths(12).minusDays(1)) } ?: 0L

        return CashFlowSummary(
            engineVersion = ENGINE_VERSION, currency = plan.currency, schedule = sorted,
            knownMandatoryMinor = knownMandatory,
            exactTotalKnown = !mandatoryUnknownAmount,
            exactMandatoryTotalMinor = if (mandatoryUnknownAmount) null else knownMandatory,
            unresolved = unresolved, optionalKnownMinor = optional, refundableMinor = refundable, rentMinor = rent,
            firstPaymentDate = firstDate?.toString(),
            cashToStartMinor = firstDate?.let { byDate[it.toString()] } ?: 0L,
            largestSingleDate = largest?.key, largestSingleDateMinor = largest?.value ?: 0L,
            first12MonthsMinor = first12,
        )
    }

    /** Mandatory known purchase payments with a day-precise date in [from, to] inclusive. */
    fun dueBetween(schedule: List<ScheduledPayment>, from: LocalDate, to: LocalDate): Long =
        schedule.filter { it.mandatory && it.kind != ItemKind.RENT && !it.refundable && it.datePrecision == PreciseDate.Precision.DAY && it.amountMinor != null }
            .filter { val d = LocalDate.parse(it.date!!); !d.isBefore(from) && !d.isAfter(to) }
            .sumOf { it.amountMinor!! }

    fun dueOn(schedule: List<ScheduledPayment>, day: LocalDate) = dueBetween(schedule, day, day)

    /** Quarter-by-quarter mandatory outflow; used for "when will this stretch my budget". */
    fun byQuarter(schedule: List<ScheduledPayment>): List<Pair<String, Long>> =
        schedule.filter { it.mandatory && it.kind != ItemKind.RENT && !it.refundable && it.datePrecision == PreciseDate.Precision.DAY && it.amountMinor != null }
            .groupBy { val d = LocalDate.parse(it.date!!); "${d.year}-Q${(d.monthValue - 1) / 3 + 1}" }
            .mapValues { (_, v) -> v.sumOf { it.amountMinor!! } }
            .toSortedMap().toList()
}
