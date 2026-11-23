package eg.wadeh.core.api

import eg.wadeh.core.CashFlowSummary
import eg.wadeh.core.PaymentPlan
import kotlinx.serialization.Serializable

/** Public API v1 contract shared by server and Android. Additive changes only within v1. */
const val API_VERSION = "v1"

@Serializable
data class L10n(val en: String, val ar: String) {
    fun pick(arabic: Boolean) = if (arabic) ar.ifBlank { en } else en.ifBlank { ar }
}

/** Where a fact came from, in words a buyer understands. No invented confidence percentages. */
@Serializable
data class Evidence(
    val kind: String,              // supplier_import | partner_feed | manual_verification
    val source: L10n,              // organisation that asserted it
    val assertedOn: String?,       // date the source says the fact was true (e.g. price sheet date)
    val receivedAt: String,        // when we learned it (ISO instant)
    val label: L10n,               // e.g. "Developer price sheet, 3 Nov 2026"
    val stale: Boolean,
    val refreshTargetDays: Int,
)

@Serializable
data class ListingCard(
    val id: String,
    val unitId: String,
    val project: L10n,
    val projectId: String,
    val locality: L10n,
    val developer: L10n,
    val seller: L10n,
    val channel: String,
    val unitType: String,
    val unitCode: String?,
    val bedrooms: Int?,
    val bathrooms: Int?,
    val area: String?,             // decimal string, m²
    val areaBasis: String,
    val floor: String?,
    val finishing: String,
    val offerType: String,
    val currency: String,
    val priceMinor: Long?,
    val priceBasis: String,        // cash | instalment_list | monthly_rent
    val cashPriceMinor: Long?,
    val cashToStartMinor: Long?,
    val availability: String,
    val availabilityAsOf: String?,
    val handover: String?,         // precise-date string, e.g. 2028-Q3
    val offerVersion: Int,
    val offerVersionId: String,
    val evidence: Evidence,
    val lat: Double?,
    val lng: Double?,
    val locationPrecisionM: Int?,
    val isTestData: Boolean,
    val changeCount: Int,
)

@Serializable
data class ChangeItem(
    val id: String,
    val kind: String,              // price_changed | plan_changed | availability_changed | offer_published | offer_withdrawn | correction
    val field: String?,
    val oldValue: String?,
    val newValue: String?,
    val currency: String?,
    val effectiveOn: String?,
    val recordedAt: String,
    val source: L10n,
    val note: String?,
    val likeForLike: Boolean,      // false when e.g. the plan or area basis changed together with the price
    val reasons: List<String> = emptyList(), // plan_changed | area_changed | currency_changed — localised by clients
)

@Serializable
data class OfferVersionInfo(
    val id: String,
    val version: Int,
    val effectiveOn: String?,
    val recordedAt: String,
    val status: String,
    val supersededAt: String?,
)

@Serializable
data class ListingDetail(
    val card: ListingCard,
    val versions: List<OfferVersionInfo>,
    val plan: PaymentPlan?,
    val cashflow: CashFlowSummary?,
    val changes: List<ChangeItem>,
    val otherOffersForUnit: List<ListingCard>,
    val notes: L10n?,
)

@Serializable
data class Page<T>(val items: List<T>, val nextCursor: String?, val asOf: String, val total: Int? = null)

@Serializable
data class ApiError(val code: String, val message: String, val correlationId: String?)

@Serializable
data class Health(val status: String, val env: String, val version: String, val dbMigration: String?, val time: String)

@Serializable
data class CompareResponse(val cards: List<ListingCard>, val comparison: eg.wadeh.core.Comparison, val asOf: String)

@Serializable
data class CreateShareRequest(val listingIds: List<String>, val lang: String = "ar")

/** ownerSecret is returned exactly once; the app keeps it locally to revoke the link later. */
@Serializable
data class CreateShareResponse(val token: String, val ownerSecret: String, val url: String, val expiresAt: String)

@Serializable
data class SharedComparison(val token: String, val compare: CompareResponse, val expiresAt: String, val missing: Int)
