package eg.wadeh.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/** A date kept at the precision the source gave. A "Q3 2027" handover is never silently turned into 1 July. */
@Serializable
data class PreciseDate(val precision: Precision, val year: Int, val month: Int? = null, val day: Int? = null, val quarter: Int? = null) {
    enum class Precision { DAY, MONTH, QUARTER, YEAR }

    /** Earliest calendar day this value can refer to (for sorting/filtering only). */
    fun earliest(): LocalDate = when (precision) {
        Precision.DAY -> LocalDate.of(year, month!!, day!!)
        Precision.MONTH -> LocalDate.of(year, month!!, 1)
        Precision.QUARTER -> LocalDate.of(year, (quarter!! - 1) * 3 + 1, 1)
        Precision.YEAR -> LocalDate.of(year, 1, 1)
    }

    fun latest(): LocalDate = when (precision) {
        Precision.DAY -> earliest()
        Precision.MONTH -> YearMonth.of(year, month!!).atEndOfMonth()
        Precision.QUARTER -> YearMonth.of(year, quarter!! * 3).atEndOfMonth()
        Precision.YEAR -> LocalDate.of(year, 12, 31)
    }

    fun iso(): String = when (precision) {
        Precision.DAY -> earliest().toString()
        Precision.MONTH -> "%04d-%02d".format(year, month)
        Precision.QUARTER -> "$year-Q$quarter"
        Precision.YEAR -> "$year"
    }

    companion object {
        fun day(d: LocalDate) = PreciseDate(Precision.DAY, d.year, d.monthValue, d.dayOfMonth)
        fun month(m: YearMonth) = PreciseDate(Precision.MONTH, m.year, m.monthValue)
        fun quarter(year: Int, q: Int) = PreciseDate(Precision.QUARTER, year, quarter = q)
        fun year(y: Int) = PreciseDate(Precision.YEAR, y)
        fun parseIso(s: String): PreciseDate = when {
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s) -> day(LocalDate.parse(s))
            Regex("^\\d{4}-Q[1-4]$").matches(s) -> quarter(s.take(4).toInt(), s.last().digitToInt())
            Regex("^\\d{4}-\\d{2}$").matches(s) -> month(YearMonth.parse(s))
            Regex("^\\d{4}$").matches(s) -> year(s.toInt())
            else -> throw IllegalArgumentException("Bad precise date $s")
        }
    }
}

enum class UnitType { APARTMENT, STUDIO, DUPLEX, PENTHOUSE, VILLA, TOWNHOUSE, TWINHOUSE, CHALET, LAND, OFFICE, RETAIL, CLINIC, OTHER }
enum class Finishing { CORE_AND_SHELL, SEMI_FINISHED, FULLY_FINISHED, FURNISHED, UNKNOWN }
enum class AreaBasis { GROSS, NET, SALEABLE, BUILT_UP, LAND, UNKNOWN }
enum class ListingChannel { DEVELOPER_PRIMARY, BROKER_RESALE, OWNER_RESALE, RENTAL }
enum class OfferType { SALE, RENT }

/**
 * Availability of a unit as asserted by one source for one listing.
 * Transition rules are deliberately conservative: a terminal state (SOLD, RENTED, WITHDRAWN)
 * is only reopened by an explicit, newer, authorised correction.
 */
enum class Availability {
    ANNOUNCED, RELEASED, AVAILABLE, ON_HOLD, RESERVED, SOLD, RENTED, WITHDRAWN, EXPIRED, UNKNOWN, CONTESTED;

    val isTerminal get() = this == SOLD || this == RENTED || this == WITHDRAWN
    val isOfferable get() = this == AVAILABLE || this == RELEASED
}

object AvailabilityRules {
    sealed interface Decision {
        data class Apply(val to: Availability, val note: String? = null) : Decision
        data class Ignore(val reason: String) : Decision
    }

    /**
     * @param current latest accepted state and its effective time (null if none)
     * @param incoming proposed state and its effective time
     * @param isCorrection the source explicitly marked this row as a correction with a reason
     */
    fun decide(
        current: Availability?, currentEffective: java.time.Instant?,
        incoming: Availability, incomingEffective: java.time.Instant,
        isCorrection: Boolean, correctionReason: String?,
    ): Decision {
        if (current == null) return Decision.Apply(incoming)
        if (currentEffective != null && incomingEffective.isBefore(currentEffective))
            return Decision.Ignore("Older than the current state (${current.name} effective $currentEffective)")
        if (current == incoming) return Decision.Ignore("No change")
        if (current.isTerminal && !incoming.isTerminal) {
            if (!isCorrection || correctionReason.isNullOrBlank())
                return Decision.Ignore("${current.name} can only be reopened by a newer correction with a reason")
            return Decision.Apply(incoming, "Reopened by correction: $correctionReason")
        }
        return Decision.Apply(incoming)
    }
}
