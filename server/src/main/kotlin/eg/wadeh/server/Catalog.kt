package eg.wadeh.server

import eg.wadeh.core.*
import eg.wadeh.core.api.*
import kotlinx.serialization.json.Json as KJson
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

data class ListingQuery(
    val q: String? = null,
    val offerType: String? = null,
    val minBedrooms: Int? = null,
    val maxPriceMinor: Long? = null,
    val maxCashToStartMinor: Long? = null,
    val unitType: String? = null,
    val availableOnly: Boolean = false,
    val projectId: UUID? = null,
    val bbox: List<Double>? = null, // minLng,minLat,maxLng,maxLat
    val sort: String = "recent",
    val cursor: String? = null,
    val limit: Int = 20,
    val ids: List<UUID>? = null,
)

class Catalog(private val db: Db, private val env: String, private val clock: () -> Instant = Instant::now) {
    private val json = KJson { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val includeTest get() = env != "prod"

    private val baseSelect = """
        SELECT l.id lid, l.unit_id, l.channel, l.offer_type, l.last_confirmed_at, l.publication,
               u.unit_code, u.unit_type, u.bedrooms, u.bathrooms, u.area_m2::text area, u.area_basis, u.floor, u.finishing,
               p.id pid, p.name_en p_en, p.name_ar p_ar, ST_Y(p.location::geometry) lat, ST_X(p.location::geometry) lng, p.location_precision_m,
               loc.name_en loc_en, loc.name_ar loc_ar, dev.name_en dev_en, dev.name_ar dev_ar,
               s.name_en s_en, s.name_ar s_ar, s.kind s_kind, s.is_test, s.refresh_target_days,
               o.id oid, o.version_no, o.currency, o.list_price_minor, o.cash_price_minor, o.monthly_rent_minor, o.handover, o.asserted_on, o.recorded_at o_recorded,
               o.notes_en, pp.plan::text plan,
               av.state av_state, av.effective_at av_at,
               (SELECT count(*) FROM change_event ce WHERE ce.listing_id=l.id AND NOT ce.retracted AND ce.kind<>'offer_published') changes
        FROM listing l
        JOIN unit u ON u.id=l.unit_id
        JOIN project p ON p.id=u.project_id
        JOIN locality loc ON loc.id=p.locality_id
        JOIN organization dev ON dev.id=p.developer_org_id
        JOIN organization s ON s.id=l.seller_org_id
        JOIN offer_version o ON o.listing_id=l.id AND o.status='current'
        LEFT JOIN payment_plan_version pp ON pp.id=o.plan_version_id
        LEFT JOIN LATERAL (SELECT state, effective_at FROM availability_observation a WHERE a.listing_id=l.id AND a.accepted AND NOT a.retracted
                           ORDER BY effective_at DESC, recorded_at DESC LIMIT 1) av ON true
        WHERE l.publication='published' AND p.publication_rights='full'
    """.trimIndent()

    fun search(q: ListingQuery): Page<ListingCard> = db.read {
        val where = StringBuilder(); val args = mutableListOf<Any?>()
        if (!includeTest) where.append(" AND NOT s.is_test")
        q.q?.takeIf { it.isNotBlank() }?.let {
            where.append(" AND (p.search_norm LIKE ? OR loc.search_norm LIKE ? OR dev.name_en ILIKE ? OR u.unit_code ILIKE ?)")
            val n = "%" + ArabicText.normalize(it) + "%"; args += n; args += n; args += "%${it.trim()}%"; args += it.trim()
        }
        q.offerType?.let { where.append(" AND l.offer_type=?"); args += it.uppercase() }
        q.minBedrooms?.let { where.append(" AND u.bedrooms>=?"); args += it }
        q.unitType?.let { where.append(" AND u.unit_type=?"); args += it.uppercase() }
        q.maxPriceMinor?.let { where.append(" AND COALESCE(o.cash_price_minor, o.list_price_minor, o.monthly_rent_minor) <= ?"); args += it }
        if (q.availableOnly) where.append(" AND av.state IN ('AVAILABLE','RELEASED')")
        q.projectId?.let { where.append(" AND p.id=?"); args += it }
        q.ids?.let { ids -> if (ids.isEmpty()) where.append(" AND false") else { where.append(" AND l.id = ANY(?::uuid[])"); args.add(ids.map { it.toString() }.toTypedArray()) } }
        q.bbox?.takeIf { it.size == 4 }?.let { b ->
            where.append(" AND p.location && ST_MakeEnvelope(?,?,?,?,4326)::geography"); args += b[0]; args += b[1]; args += b[2]; args += b[3]
        }
        val order = when (q.sort) {
            "price_asc" -> "COALESCE(o.cash_price_minor, o.list_price_minor, o.monthly_rent_minor) ASC NULLS LAST, l.id"
            "price_desc" -> "COALESCE(o.cash_price_minor, o.list_price_minor, o.monthly_rent_minor) DESC NULLS LAST, l.id"
            else -> "l.last_confirmed_at DESC NULLS LAST, l.id"
        }
        val offset = q.cursor?.let { runCatching { String(Base64.getUrlDecoder().decode(it)).removePrefix("o:").toInt() }.getOrNull() } ?: 0
        val limit = q.limit.coerceIn(1, 50)
        var cards = list("$baseSelect $where ORDER BY $order LIMIT ${limit + 1} OFFSET $offset", *args.toTypedArray()) { card(it) }
        // Cash-to-start is a derived, deterministic figure: filter after computing it with the shared engine.
        q.maxCashToStartMinor?.let { m -> cards = cards.filter { c -> c.cashToStartMinor.let { v -> v != null && v <= m } } }
        val next = if (cards.size > limit) Base64.getUrlEncoder().encodeToString("o:${offset + limit}".toByteArray()) else null
        Page(cards.take(limit), next, clock().toString())
    }

    fun detail(id: UUID): ListingDetail? = db.read {
        val card = one("$baseSelect ${if (!includeTest) " AND NOT s.is_test" else ""} AND l.id=?", id) { card(it) } ?: return@read null
        val versions = list("SELECT id, version_no, asserted_on, recorded_at, status, superseded_at FROM offer_version WHERE listing_id=? ORDER BY version_no DESC", id) {
            OfferVersionInfo(it.uuid("id").toString(), it.getInt("version_no"), it.getObject("asserted_on", LocalDate::class.java)?.toString(), it.instant("recorded_at").toString(), it.getString("status"), it.instantOrNull("superseded_at")?.toString())
        }
        val planText = one("SELECT pp.plan::text FROM offer_version o JOIN payment_plan_version pp ON pp.id=o.plan_version_id WHERE o.id=?", UUID.fromString(card.offerVersionId)) { it.getString(1) }
        val plan = planText?.let { json.decodeFromString(PaymentPlan.serializer(), it) }
        val changes = changes(this, id)
        val others = list("$baseSelect ${if (!includeTest) " AND NOT s.is_test" else ""} AND l.unit_id=? AND l.id<>?", UUID.fromString(card.unitId), id) { card(it) }
        val notes = one("SELECT notes_en FROM offer_version WHERE id=?", UUID.fromString(card.offerVersionId)) { it.getString(1) }
        ListingDetail(card, versions, plan, plan?.let { CashFlowEngine.compute(it) }, changes, others, notes?.let { L10n(it, it) })
    }

    fun changes(tx: Tx, listingId: UUID, since: Instant? = null): List<ChangeItem> = tx.list(
        """SELECT ce.*, s.name_en s_en, s.name_ar s_ar FROM change_event ce JOIN listing l ON l.id=ce.listing_id JOIN organization s ON s.id=l.seller_org_id
           WHERE ce.listing_id=? AND NOT ce.retracted ${if (since != null) "AND ce.recorded_at > ?" else ""} ORDER BY ce.seq DESC""",
        *listOfNotNull<Any>(listingId, since).toTypedArray()
    ) {
        ChangeItem(it.uuid("id").toString(), it.getString("kind"), it.getString("field"), it.getString("old_value"), it.getString("new_value"), it.getString("currency")?.trim(),
            it.getObject("effective_on", LocalDate::class.java)?.toString(), it.instant("recorded_at").toString(), L10n(it.getString("s_en"), it.getString("s_ar")),
            it.getString("note"), it.getBoolean("like_for_like"), (it.getArray("reasons").array as Array<*>).map { r -> r.toString() })
    }

    fun changesFor(ids: List<UUID>, since: Instant?): Map<String, List<ChangeItem>> = db.read { ids.associate { it.toString() to changes(this, it, since) } }

    private fun card(rs: ResultSet): ListingCard {
        val plan = rs.getString("plan")?.let { json.decodeFromString(PaymentPlan.serializer(), it) }
        val cf = plan?.let { runCatching { CashFlowEngine.compute(it) }.getOrNull() }
        val cash = rs.longOrNull("cash_price_minor"); val list = rs.longOrNull("list_price_minor"); val rent = rs.longOrNull("monthly_rent_minor")
        val (price, basis) = when {
            rent != null -> rent to "monthly_rent"
            list != null && plan != null -> list to "instalment_list"
            cash != null -> cash to "cash"
            else -> list to "instalment_list"
        }
        val asserted = rs.getObject("asserted_on", LocalDate::class.java)
        val confirmed = rs.instantOrNull("last_confirmed_at")
        val target = rs.getInt("refresh_target_days")
        val stale = confirmed == null || Duration.between(confirmed, clock()).toDays() > target
        val sellerEn = rs.getString("s_en"); val sellerAr = rs.getString("s_ar")
        val isDev = rs.getString("channel") == "DEVELOPER_PRIMARY"
        val dEn = asserted?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
        val dAr = asserted?.let { Digits.toArabicIndic(it.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ar-EG")))) }
        val label = if (stale) L10n("Last price ${if (isDev) "the developer" else "the seller"} confirmed was on $dEn. Ask for confirmation before visiting.",
                                   "آخر سعر أكّده ${if (isDev) "المطوّر" else "البائع"} كان يوم $dAr. اطلب تأكيد السعر والتوافر قبل الزيارة.")
                    else L10n("${if (isDev) "Developer" else "Seller"} price sheet, $dEn", "${if (isDev) "قائمة أسعار المطوّر" else "قائمة أسعار البائع"}، $dAr")
        return ListingCard(
            id = rs.uuid("lid").toString(), unitId = rs.uuid("unit_id").toString(),
            project = L10n(rs.getString("p_en"), rs.getString("p_ar")), projectId = rs.uuid("pid").toString(),
            locality = L10n(rs.getString("loc_en"), rs.getString("loc_ar")), developer = L10n(rs.getString("dev_en"), rs.getString("dev_ar")),
            seller = L10n(sellerEn, sellerAr), channel = rs.getString("channel"), unitType = rs.getString("unit_type"), unitCode = rs.getString("unit_code"),
            bedrooms = rs.intOrNull("bedrooms"), bathrooms = rs.intOrNull("bathrooms"), area = rs.getString("area"), areaBasis = rs.getString("area_basis"),
            floor = rs.getString("floor"), finishing = rs.getString("finishing"), offerType = rs.getString("offer_type"), currency = rs.getString("currency").trim(),
            priceMinor = price, priceBasis = basis, cashPriceMinor = cash, cashToStartMinor = cf?.cashToStartMinor?.takeIf { cf.firstPaymentDate != null },
            availability = rs.getString("av_state") ?: "UNKNOWN", availabilityAsOf = rs.instantOrNull("av_at")?.toString(), handover = rs.getString("handover"),
            offerVersion = rs.getInt("version_no"), offerVersionId = rs.uuid("oid").toString(),
            evidence = Evidence("supplier_import", L10n(sellerEn, sellerAr), asserted?.toString(), rs.instant("o_recorded").toString(), label, stale, target),
            lat = rs.doubleOrNull("lat"), lng = rs.doubleOrNull("lng"), locationPrecisionM = rs.intOrNull("location_precision_m"),
            isTestData = rs.getBoolean("is_test"), changeCount = rs.getInt("changes"),
        )
    }
}
