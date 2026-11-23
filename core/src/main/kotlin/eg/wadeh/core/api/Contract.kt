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

@Serializable
data class EntranceInfo(val id: String, val name: L10n, val kind: String, val locked: Boolean, val conditions: L10n?, val source: String, val observedAt: String?, val verifiedAt: String?, val lat: Double, val lng: Double)

@Serializable
data class RouteStep(val road: L10n?, val distanceM: Int, val highway: String)

@Serializable
data class MissingLeg(val code: String, val from: String, val to: String, val straightLineM: Int, val explanation: L10n)

/**
 * A route to a specific entrance. durationS is a FREE-FLOW estimate from road-class speeds unless trafficStatus says otherwise.
 * When no connected path exists, route is null and missing explains the gap; we never invent a complete travel time.
 */
@Serializable
data class RouteResult(
    val mode: String,
    val entrance: EntranceInfo?,
    val distanceM: Int?,
    val durationS: Int?,
    val approachM: Int,                 // straight-line distance from the given origin to the first graph node
    val steps: List<RouteStep>,
    val path: List<List<Double>>,       // [lng, lat]
    val trafficStatus: String,          // unavailable | typical | live
    val assumptions: List<L10n>,
    val missing: List<MissingLeg>,
    val candidatesConsidered: Int,
    val excludedEntrances: List<String>,
    val dataAsOf: String?,
    val attribution: String,
)

@Serializable
data class Landmark(val id: String, val name: L10n, val kind: String, val lat: Double, val lng: Double, val source: String)

/** Operational coverage for one locality. There is no reliable national denominator, so we report counts and say so. */
@Serializable
data class CoverageRow(
    val locality: L10n, val projectsDiscovered: Int, val projectsWithPublicationRights: Int, val phases: Int,
    val identifiableUnits: Int, val currentOffers: Int, val confirmedWithinTarget: Int, val pastRefreshTarget: Int,
    val withVerifiedEntrance: Int, val testDataOnly: Boolean,
)

@Serializable
data class Coverage(val asOf: String, val denominatorNote: L10n, val rows: List<CoverageRow>, val requestMissingUrl: String)

const val CONSENT_TEXT_VERSION = "viewing-consent-1"

@Serializable
data class CreateInquiryRequest(
    val idempotencyKey: String,
    val listingId: String,
    val offerVersionIdSeen: String,
    val channel: String,               // in_app | phone_call | whatsapp
    val name: String,
    val phone: String,
    val consentShareContact: Boolean,
    val consentTextVersion: String,
    val slotStart: String,             // ISO instant
    val lang: String = "ar",
)

@Serializable
data class InquiryStatus(
    val id: String, val status: String, val recipient: L10n, val slotStart: String, val proposedSlot: String?,
    val alternatives: List<String>, val timeline: List<InquiryEvent>, val buyerToken: String? = null,
)

@Serializable
data class InquiryEvent(val status: String, val at: String, val note: String?)

/** 409 body when the offer changed since the buyer last looked. The buyer decides whether to continue. */
@Serializable
data class OfferChanged(val code: String, val current: ListingCard, val changes: List<ChangeItem>)
