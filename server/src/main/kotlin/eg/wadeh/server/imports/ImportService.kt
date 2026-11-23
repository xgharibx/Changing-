package eg.wadeh.server.imports

import eg.wadeh.core.*
import eg.wadeh.server.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as KJson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Serializable data class FieldDiff(val field: String, val old: String?, val new: String?)

@Serializable
data class BatchSummary(
    val rows: Int = 0, val valid: Int = 0, val invalid: Int = 0,
    val add: Int = 0, val revise: Int = 0, val confirm: Int = 0, val unchanged: Int = 0,
    val stale: Int = 0, val conflict: Int = 0, val withdraw: Int = 0,
    val availabilityChanges: Int = 0, val availabilityIgnored: Int = 0,
    val suspicious: Int = 0, val warnings: Int = 0, val unknownHeaders: List<String> = emptyList(),
    val changeEvents: Int = 0,
)

class ImportException(val code: String, message: String) : RuntimeException(message)

data class StageResult(val batchId: UUID, val duplicateOf: UUID?, val summary: BatchSummary)

/**
 * Stage -> preview -> publish. Planning is a pure read of current state; publishing re-plans inside the
 * publishing transaction so a preview made against older state can never overwrite newer facts.
 */
class ImportService(private val db: Db, private val clock: () -> Instant = Instant::now) {
    private val json = KJson { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val cairo = ZoneId.of("Africa/Cairo")
    private val suspiciousShift = 0.20

    // ---------- Staging ----------

    fun stage(orgId: UUID, uploader: Principal, filename: String, bytes: ByteArray, mode: String, idempotencyKey: String? = null): StageResult {
        if (!uploader.canUploadFor(orgId)) throw ImportException("forbidden", "You cannot upload for this organisation")
        require(mode in setOf("partial", "full_snapshot")) { "mode must be partial or full_snapshot" }
        val sha = sha256Hex(bytes)
        // Idempotency: an identical file (by content, not name) already staged or published returns that batch.
        db.read {
            one("SELECT id, summary FROM import_batch WHERE org_id=? AND file_sha256=? AND status IN ('staged','published') ORDER BY created_at DESC LIMIT 1", orgId, sha) {
                it.uuid("id") to it.getString("summary")
            }
        }?.let { (id, s) -> return StageResult(id, id, json.decodeFromString(BatchSummary.serializer(), s)) }

        val table = try { SupplierFile.parse(filename, bytes) } catch (e: IllegalArgumentException) { throw ImportException("bad_file", e.message ?: "Unreadable file") }
        val missing = TemplateColumns.required.filter { r -> table.rows.isEmpty() || table.headers.none { h -> h.split('|').any { TemplateColumns.canonical(it.trim()) == r } } }
        if (missing.isNotEmpty()) throw ImportException("missing_columns", "Required columns missing: ${missing.joinToString()}. Download the template to see accepted headers.")

        val kind = if (filename.lowercase().endsWith(".xlsx")) "xlsx" else "csv"
        val currency = db.read { one("SELECT c.currency FROM organization o JOIN country c ON c.code=o.country_code WHERE o.id=?", orgId) { it.getString(1).trim() } } ?: "EGP"
        val batchId = newId()
        return db.tx {
            exec("INSERT INTO import_batch(id,org_id,mode,source_kind,filename,file_sha256,idempotency_key,status,uploaded_by) VALUES (?,?,?,?,?,?,?,'staged',?)",
                batchId, orgId, mode, kind, filename.take(200), sha, idempotencyKey, uploader.userId)
            val seenKeys = HashSet<String>()
            table.rows.forEachIndexed { i, raw ->
                val r = RowNormalizer.normalize(raw, currency)
                var errors = r.errors; var action = "invalid"; var diff = emptyList<FieldDiff>(); val warnings = r.warnings.toMutableList()
                var listingId: UUID? = null
                if (r.row != null) {
                    if (!seenKeys.add(r.row.listingKey)) errors = listOf("listing_id '${r.row.listingKey}' appears more than once in this file")
                    else {
                        val p = planRow(this, orgId, r.row)
                        action = p.action; diff = p.diff; warnings += p.warnings; listingId = p.listingId
                        if (p.error != null) errors = listOf(p.error)
                    }
                }
                exec("INSERT INTO source_record(id,batch_id,row_no,raw,normalized,errors,warnings,action,listing_id,diff) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    newId(), batchId, i + 2, Json(json.encodeToString(raw)), r.row?.let { Json(json.encodeToString(NormalizedRow.serializer(), it)) },
                    Json(json.encodeToString(errors)), Json(json.encodeToString(warnings.toList())), if (errors.isNotEmpty()) "invalid" else action, listingId,
                    Json(json.encodeToString(diff)))
            }
            if (mode == "full_snapshot") stageWithdrawals(this, orgId, batchId, seenKeys, table.rows.size + 2)
            val s = summarize(this, batchId).copy(unknownHeaders = table.unknownHeaders)
            exec("UPDATE import_batch SET summary=? WHERE id=?", Json(json.encodeToString(s)), batchId)
            audit(this, uploader, orgId, "import.stage", "import_batch", batchId.toString(), mapOf("file" to filename, "sha256" to sha, "rows" to s.rows.toString()))
            StageResult(batchId, null, s)
        }
    }

    private fun stageWithdrawals(tx: Tx, orgId: UUID, batchId: UUID, keys: Set<String>, firstRowNo: Int) {
        val active = tx.list("SELECT id, supplier_key FROM listing WHERE seller_org_id=? AND publication='published'", orgId) { it.uuid("id") to it.getString("supplier_key") }
        val gone = active.filter { it.second !in keys }
        val suspicious = active.isNotEmpty() && gone.size.toDouble() / active.size > 0.30
        gone.forEachIndexed { i, (lid, key) ->
            tx.exec("INSERT INTO source_record(id,batch_id,row_no,raw,action,listing_id,diff,warnings) VALUES (?,?,?,?,?,?,?,?)",
                newId(), batchId, firstRowNo + i, Json("""{"listing_id":${json.encodeToString(key)},"_snapshot":"absent"}"""), "withdraw", lid,
                Json(json.encodeToString(listOf(FieldDiff("publication", "published", "withdrawn")))),
                Json(json.encodeToString(if (suspicious) listOf("suspicious: full snapshot would withdraw ${gone.size} of ${active.size} active listings") else emptyList())))
        }
    }

    private fun summarize(tx: Tx, batchId: UUID): BatchSummary {
        val rows = tx.list("SELECT action, errors::text e, warnings::text w, diff::text d FROM source_record WHERE batch_id=?", batchId) { arrayOf(it.getString("action"), it.getString("e"), it.getString("w"), it.getString("d")) }
        fun c(a: String) = rows.count { it[0] == a }
        val warnLists = rows.map { json.decodeFromString<List<String>>(it[2]) }
        val diffs = rows.map { json.decodeFromString<List<FieldDiff>>(it[3]) }
        return BatchSummary(
            rows = rows.count { it[0] != "withdraw" }, valid = rows.count { it[0] != "invalid" && it[0] != "withdraw" }, invalid = c("invalid"),
            add = c("add"), revise = c("revise"), confirm = c("confirm"), unchanged = c("unchanged"), stale = c("stale"), conflict = c("conflict"), withdraw = c("withdraw"),
            availabilityChanges = diffs.count { d -> d.any { it.field == "availability" } },
            availabilityIgnored = warnLists.count { w -> w.any { it.startsWith("availability ignored") } },
            suspicious = warnLists.count { w -> w.any { it.startsWith("suspicious") } },
            warnings = warnLists.sumOf { it.size },
            changeEvents = tx.one("SELECT count(*) FROM change_event WHERE batch_id=?", batchId) { it.getInt(1) } ?: 0,
        )
    }

    // ---------- Planning (pure read) ----------

    data class Current(
        val listingId: UUID, val unitId: UUID, val publication: String,
        val offerId: UUID?, val versionNo: Int, val contentHash: String?, val assertedOn: LocalDate?,
        val list: Long?, val cash: Long?, val rent: Long?, val currency: String?, val planHash: String?, val handover: String?,
        val areaM2: String?, val areaBasis: String, val availability: Availability?, val availabilityAt: Instant?, val lastConfirmed: Instant?,
    )

    data class Planned(val action: String, val diff: List<FieldDiff>, val warnings: List<String>, val listingId: UUID?, val error: String? = null,
                       val availabilityDecision: AvailabilityRules.Decision? = null, val current: Current? = null, val projectId: UUID? = null)

    fun effectiveInstant(isoDate: String): Instant = LocalDate.parse(isoDate).atStartOfDay(cairo).toInstant()

    private fun current(tx: Tx, orgId: UUID, key: String): Current? = tx.one(
        """SELECT l.id lid, l.unit_id, l.publication, l.last_confirmed_at, o.id oid, o.version_no, o.content_hash, o.asserted_on, o.list_price_minor, o.cash_price_minor,
                  o.monthly_rent_minor, o.currency, p.content_hash phash, o.handover, u.area_m2::text area, u.area_basis,
                  (SELECT state FROM availability_observation a WHERE a.listing_id=l.id AND a.accepted AND NOT a.retracted ORDER BY effective_at DESC, recorded_at DESC LIMIT 1) av,
                  (SELECT effective_at FROM availability_observation a WHERE a.listing_id=l.id AND a.accepted AND NOT a.retracted ORDER BY effective_at DESC, recorded_at DESC LIMIT 1) av_at
           FROM listing l JOIN unit u ON u.id=l.unit_id
           LEFT JOIN offer_version o ON o.listing_id=l.id AND o.status='current'
           LEFT JOIN payment_plan_version p ON p.id=o.plan_version_id
           WHERE l.seller_org_id=? AND l.supplier_key=?""", orgId, key
    ) {
        Current(it.uuid("lid"), it.uuid("unit_id"), it.getString("publication"), it.uuidOrNull("oid"), it.intOrNull("version_no") ?: 0, it.getString("content_hash"),
            it.getObject("asserted_on", LocalDate::class.java), it.longOrNull("list_price_minor"), it.longOrNull("cash_price_minor"), it.longOrNull("monthly_rent_minor"),
            it.getString("currency")?.trim(), it.getString("phash"), it.getString("handover"), it.getString("area"), it.getString("area_basis"),
            it.getString("av")?.let(Availability::valueOf), it.instantOrNull("av_at"), it.instantOrNull("last_confirmed_at"))
    }

    fun resolveProject(tx: Tx, ref: String): Pair<UUID?, String?> {
        val n = ArabicText.normalize(ref)
        val hits = tx.list("SELECT id, code, name_en, name_ar, aliases FROM project") { rs ->
            val names = listOf(rs.getString("code"), rs.getString("name_en"), rs.getString("name_ar")) + (rs.getArray("aliases").array as Array<*>).map { it.toString() }
            rs.uuid("id") to names.map(ArabicText::normalize)
        }.filter { (_, names) -> n in names }
        return when (hits.size) {
            1 -> hits[0].first to null
            0 -> null to "project_code: '$ref' is not in the catalogue. Ask the operator to register the project first."
            else -> null to "project_code: '$ref' matches ${hits.size} projects; use the exact project code"
        }
    }

    fun planRow(tx: Tx, orgId: UUID, row: NormalizedRow): Planned {
        val (projectId, projectErr) = resolveProject(tx, row.projectRef)
        if (projectId == null) return Planned("invalid", emptyList(), emptyList(), null, projectErr)
        val cur = current(tx, orgId, row.listingKey)
        val eff = effectiveInstant(row.assertedOn)
        val warnings = mutableListOf<String>()
        val facts = row.offerFacts(); val hash = sha256Hex(facts)
        val planHash = row.plan?.let { sha256Hex(json.encodeToString(PaymentPlan.serializer(), it)) }

        if (cur == null) {
            // Physical unit identity: same project + unit code = same unit, even from another seller.
            val existing = tx.one("SELECT id, unit_type, bedrooms, area_m2::text a FROM unit WHERE project_id=? AND unit_code=?", projectId, row.unitCode) {
                Triple(it.uuid("id"), it.getString("unit_type"), it.intOrNull("bedrooms"))
            }
            if (existing != null && (existing.second != row.unitType.name || (existing.third != null && row.bedrooms != null && existing.third != row.bedrooms)))
                return Planned("conflict", emptyList(), listOf("conflict: unit ${row.unitCode} exists as ${existing.second}/${existing.third ?: "?"} bedrooms; this row says ${row.unitType}/${row.bedrooms}. Held for review."), null)
            if (existing != null) warnings += "unit ${row.unitCode} already listed by another seller: this becomes a second offer on the same physical unit"
            val diff = listOf(FieldDiff("offer", null, priceLabel(row)), FieldDiff("availability", null, row.availability.name))
            return Planned("add", diff, warnings, null, availabilityDecision = AvailabilityRules.Decision.Apply(row.availability), projectId = projectId)
        }

        val diff = mutableListOf<FieldDiff>()
        val asserted = LocalDate.parse(row.assertedOn)
        val olderThanCurrent = cur.assertedOn != null && asserted.isBefore(cur.assertedOn)
        var action: String
        if (cur.contentHash == hash) {
            action = if (cur.assertedOn == null || asserted.isAfter(cur.assertedOn)) "confirm" else "unchanged"
            if (action == "confirm") diff += FieldDiff("confirmed_on", cur.assertedOn?.toString(), row.assertedOn)
        } else if (olderThanCurrent) {
            action = "stale"
            warnings += "stale: price sheet dated ${row.assertedOn} is older than the published one (${cur.assertedOn}); not applied"
        } else {
            action = "revise"
            fun cmp(f: String, o: Long?, n: Long?) { if (o != n) diff += FieldDiff(f, o?.toString(), n?.toString()) }
            cmp("list_price", cur.list, row.listPriceMinor); cmp("cash_price", cur.cash, row.cashPriceMinor); cmp("monthly_rent", cur.rent, row.monthlyRentMinor)
            if (cur.planHash != planHash) diff += FieldDiff("payment_plan", cur.planHash?.take(8), planHash?.take(8))
            if (cur.handover != row.handover) diff += FieldDiff("handover", cur.handover, row.handover)
            if (cur.currency != row.currency) diff += FieldDiff("currency", cur.currency, row.currency)
            if (diff.isEmpty()) diff += FieldDiff("notes", null, row.notes)
            for ((o, n) in listOf(cur.list to row.listPriceMinor, cur.cash to row.cashPriceMinor, cur.rent to row.monthlyRentMinor)) {
                if (o != null && n != null && o > 0 && kotlin.math.abs(n - o).toDouble() / o > suspiciousShift)
                    warnings += "suspicious: price moves ${"%.0f".format((n - o) * 100.0 / o)}% (review before publishing)"
            }
        }
        if (row.areaM2 != null && (row.areaM2 != cur.areaM2 || row.areaBasis.name != cur.areaBasis) && !olderThanCurrent) {
            diff += FieldDiff("area", "${cur.areaM2} ${cur.areaBasis}", "${row.areaM2} ${row.areaBasis}")
            if (action == "unchanged" || action == "confirm") action = "revise"
        }

        val decision = AvailabilityRules.decide(cur.availability, cur.availabilityAt, row.availability, eff, row.correction, row.correctionReason)
        when (decision) {
            is AvailabilityRules.Decision.Apply -> {
                diff += FieldDiff("availability", cur.availability?.name, decision.to.name)
                if (action == "unchanged" || action == "confirm" || action == "stale") action = if (action == "stale") "stale" else "revise"
            }
            is AvailabilityRules.Decision.Ignore -> if (decision.reason != "No change") warnings += "availability ignored: ${decision.reason}"
        }
        if (action == "stale" && decision is AvailabilityRules.Decision.Apply) action = "revise" // availability can still be newer than price
        if (cur.publication == "withdrawn" && action != "invalid") { diff += FieldDiff("publication", "withdrawn", "published"); if (action == "unchanged" || action == "confirm") action = "revise" }
        return Planned(action, diff, warnings, cur.listingId, availabilityDecision = decision, current = cur, projectId = projectId)
    }

    private fun priceLabel(r: NormalizedRow) = listOfNotNull(
        r.listPriceMinor?.let { "list " + MoneyFormat.format(Money(it, r.currency), false) },
        r.cashPriceMinor?.let { "cash " + MoneyFormat.format(Money(it, r.currency), false) },
        r.monthlyRentMinor?.let { "rent " + MoneyFormat.format(Money(it, r.currency), false) + "/month" },
    ).joinToString(", ")

    // ---------- Publishing ----------

    fun publish(batchId: UUID, reviewer: Principal, acknowledgeSuspicious: Boolean, note: String? = null): BatchSummary = db.tx {
        val b = one("SELECT org_id, status, uploaded_by FROM import_batch WHERE id=? FOR UPDATE", batchId) { Triple(it.uuid("org_id"), it.getString("status"), it.uuidOrNull("uploaded_by")) }
            ?: throw ImportException("not_found", "Batch not found")
        val (orgId, status, uploadedBy) = b
        if (!reviewer.canReviewFor(orgId)) throw ImportException("forbidden", "You cannot review imports for this organisation")
        if (uploadedBy == reviewer.userId) throw ImportException("four_eyes", "The person who uploaded a batch cannot publish it")
        if (status != "staged") throw ImportException("bad_state", "Batch is $status; only staged batches can be published")
        // Serialise publishing per supplier so two batches cannot interleave.
        run("SELECT pg_advisory_xact_lock(hashtext(?))", "publish:$orgId")

        val rows = list("SELECT id, row_no, normalized::text n, action, listing_id, warnings::text w FROM source_record WHERE batch_id=? ORDER BY row_no", batchId) {
            arrayOf<Any?>(it.uuid("id"), it.getInt("row_no"), it.getString("n"), it.getString("action"), it.uuidOrNull("listing_id"), it.getString("w"))
        }
        val flagged = rows.count { json.decodeFromString<List<String>>(it[5] as String).any { w -> w.startsWith("suspicious") } }
        if (flagged > 0 && !acknowledgeSuspicious) throw ImportException("needs_ack", "$flagged rows are flagged as suspicious. Review them and confirm before publishing.")

        val now = clock()
        for (r in rows) {
            val srId = r[0] as UUID
            if (r[3] == "withdraw") { applyWithdraw(this, batchId, srId, r[4] as UUID, now); continue }
            val n = r[2] as String? ?: continue
            if (r[3] == "invalid") continue
            val row = json.decodeFromString(NormalizedRow.serializer(), n)
            val p = planRow(this, orgId, row) // re-plan against current state inside this transaction
            val applied = when (p.action) {
                "add" -> applyAdd(this, orgId, batchId, srId, row, p, now)
                "revise" -> applyRevise(this, batchId, srId, row, p, now)
                "confirm" -> { exec("UPDATE listing SET last_confirmed_at=? WHERE id=?", effectiveInstant(row.assertedOn), p.listingId); exec("UPDATE offer_version SET asserted_on=? WHERE listing_id=? AND status='current'", LocalDate.parse(row.assertedOn), p.listingId); p.listingId }
                else -> p.listingId
            }
            // Audit-only record of an availability claim we did not apply (e.g. late "available" after "sold").
            val d = p.availabilityDecision
            if (d is AvailabilityRules.Decision.Ignore && d.reason != "No change" && applied != null && p.current != null)
                exec("INSERT INTO availability_observation(id,listing_id,unit_id,state,effective_at,accepted,is_correction,note,source_record_id,batch_id) VALUES (?,?,?,?,?,false,?,?,?,?)",
                    newId(), applied, p.current.unitId, row.availability.name, effectiveInstant(row.assertedOn), row.correction, d.reason, srId, batchId)
            exec("UPDATE source_record SET action=?, diff=?, listing_id=?, warnings=? WHERE id=?", p.action, Json(json.encodeToString(p.diff)), applied,
                Json(json.encodeToString((json.decodeFromString<List<String>>(r[5] as String) + p.warnings).distinct())), srId)
        }
        val s = summarize(this, batchId)
        exec("UPDATE import_batch SET status='published', reviewed_by=?, review_note=?, published_at=?, summary=? WHERE id=?", reviewer.userId, note, now, Json(json.encodeToString(s)), batchId)
        audit(this, reviewer, orgId, "import.publish", "import_batch", batchId.toString(), mapOf("events" to s.changeEvents.toString(), "ack_suspicious" to acknowledgeSuspicious.toString()))
        s
    }

    fun reject(batchId: UUID, reviewer: Principal, note: String) = db.tx {
        val org = one("SELECT org_id FROM import_batch WHERE id=? AND status='staged' FOR UPDATE", batchId) { it.uuid("org_id") } ?: throw ImportException("bad_state", "Only staged batches can be rejected")
        if (!reviewer.canReviewFor(org)) throw ImportException("forbidden", "Not allowed")
        exec("UPDATE import_batch SET status='rejected', reviewed_by=?, review_note=? WHERE id=?", reviewer.userId, note, batchId)
        audit(this, reviewer, org, "import.reject", "import_batch", batchId.toString(), mapOf("note" to note))
    }

    private fun event(tx: Tx, listingId: UUID, unitId: UUID, kind: String, field: String?, old: String?, new: String?, currency: String?, likeForLike: Boolean,
                      effectiveOn: String?, batchId: UUID?, note: String?, dedupe: String, at: Instant, reasons: List<String> = emptyList()) {
        tx.exec("""INSERT INTO change_event(id,listing_id,unit_id,kind,field,old_value,new_value,currency,like_for_like,effective_on,recorded_at,batch_id,note,dedupe_key,reasons)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (dedupe_key) DO NOTHING""",
            newId(), listingId, unitId, kind, field, old, new, currency, likeForLike, effectiveOn?.let { LocalDate.parse(it.take(10)) }, at, batchId, note, sha256Hex(dedupe), reasons.toTypedArray())
    }

    private fun ensurePhase(tx: Tx, projectId: UUID, code: String?): UUID? = code?.let { c ->
        tx.one("SELECT id FROM phase WHERE project_id=? AND code=?", projectId, c) { it.uuid("id") }
            ?: newId().also { tx.exec("INSERT INTO phase(id,project_id,code,name_en,name_ar) VALUES (?,?,?,?,?)", it, projectId, c, c, c) }
    }

    private fun ensureBuilding(tx: Tx, projectId: UUID, phaseId: UUID?, code: String?): UUID? = code?.let { c ->
        tx.one("SELECT id FROM building WHERE project_id=? AND code=?", projectId, c) { it.uuid("id") }
            ?: newId().also { tx.exec("INSERT INTO building(id,project_id,phase_id,code) VALUES (?,?,?,?)", it, projectId, phaseId, c) }
    }

    private fun insertOffer(tx: Tx, listingId: UUID, versionNo: Int, row: NormalizedRow, srId: UUID, batchId: UUID, now: Instant): UUID {
        val planId = row.plan?.let { plan ->
            val text = json.encodeToString(PaymentPlan.serializer(), plan)
            newId().also { tx.exec("INSERT INTO payment_plan_version(id,listing_id,plan,content_hash) VALUES (?,?,?,?)", it, listingId, Json(text), sha256Hex(text)) }
        }
        val id = newId()
        tx.exec("""INSERT INTO offer_version(id,listing_id,version_no,currency,list_price_minor,cash_price_minor,monthly_rent_minor,plan_version_id,handover,notes_en,
                   content_hash,asserted_on,effective_at,recorded_at,status,source_record_id,batch_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'current',?,?)""",
            id, listingId, versionNo, row.currency, row.listPriceMinor, row.cashPriceMinor, row.monthlyRentMinor, planId, row.handover, row.notes,
            sha256Hex(row.offerFacts()), LocalDate.parse(row.assertedOn), effectiveInstant(row.assertedOn), now, srId, batchId)
        return id
    }

    private fun applyAdd(tx: Tx, orgId: UUID, batchId: UUID, srId: UUID, row: NormalizedRow, p: Planned, now: Instant): UUID {
        val projectId = p.projectId!!
        val phaseId = ensurePhase(tx, projectId, row.phase)
        val buildingId = ensureBuilding(tx, projectId, phaseId, row.building)
        val unitId = tx.one("SELECT id FROM unit WHERE project_id=? AND unit_code=?", projectId, row.unitCode) { it.uuid("id") }
            ?: newId().also {
                tx.exec("INSERT INTO unit(id,project_id,phase_id,building_id,unit_code,unit_type,bedrooms,bathrooms,area_m2,area_basis,floor,finishing) VALUES (?,?,?,?,?,?,?,?,?::numeric,?,?,?)",
                    it, projectId, phaseId, buildingId, row.unitCode, row.unitType.name, row.bedrooms, row.bathrooms, row.areaM2, row.areaBasis.name, row.floor, row.finishing.name)
            }
        val channel = when {
            row.offerType == OfferType.RENT -> ListingChannel.RENTAL
            tx.one("SELECT kind FROM organization WHERE id=?", orgId) { it.getString(1) } in setOf("developer", "test_supplier") -> ListingChannel.DEVELOPER_PRIMARY
            else -> ListingChannel.BROKER_RESALE
        }
        val listingId = newId()
        tx.exec("INSERT INTO listing(id,unit_id,seller_org_id,supplier_key,channel,offer_type,last_confirmed_at,created_at) VALUES (?,?,?,?,?,?,?,?)",
            listingId, unitId, orgId, row.listingKey, channel.name, row.offerType.name, effectiveInstant(row.assertedOn), now)
        insertOffer(tx, listingId, 1, row, srId, batchId, now)
        tx.exec("INSERT INTO availability_observation(id,listing_id,unit_id,state,effective_at,recorded_at,accepted,is_correction,note,source_record_id,batch_id) VALUES (?,?,?,?,?,?,true,?,?,?,?)",
            newId(), listingId, unitId, row.availability.name, effectiveInstant(row.assertedOn), now, row.correction, row.correctionReason, srId, batchId)
        event(tx, listingId, unitId, "offer_published", "offer", null, (row.listPriceMinor ?: row.cashPriceMinor ?: row.monthlyRentMinor)?.toString(), row.currency, true,
            row.assertedOn, batchId, null, "$listingId|published|1", now)
        return listingId
    }

    private fun applyRevise(tx: Tx, batchId: UUID, srId: UUID, row: NormalizedRow, p: Planned, now: Instant): UUID {
        val cur = p.current!!; val lid = cur.listingId
        val fields = p.diff.map { it.field }.toSet()
        val offerChanged = fields.any { it in setOf("list_price", "cash_price", "monthly_rent", "payment_plan", "handover", "currency", "notes") }
        val areaChanged = "area" in fields
        val planChanged = "payment_plan" in fields
        var version = cur.versionNo
        if (offerChanged) {
            version = cur.versionNo + 1
            tx.exec("UPDATE offer_version SET status='superseded', superseded_at=? WHERE listing_id=? AND status='current'", now, lid)
            insertOffer(tx, lid, version, row, srId, batchId, now)
            val likeForLike = !planChanged && !areaChanged && "currency" !in fields
            for (d in p.diff.filter { it.field in setOf("list_price", "cash_price", "monthly_rent") })
                event(tx, lid, cur.unitId, "price_changed", d.field, d.old, d.new, row.currency, likeForLike, row.assertedOn, batchId,
                    if (!likeForLike) "Payment plan ${if (planChanged) "changed too" else "unchanged"}${if (areaChanged) "; area changed" else ""}" else null,
                    "$lid|price|${d.field}|v$version", now,
                    reasons = listOfNotNull(if (planChanged) "plan_changed" else null, if (areaChanged) "area_changed" else null, if ("currency" in fields) "currency_changed" else null))
            // One revision = one visible change. A plan change travels with the price change as its "not like-for-like" reason;
            // it is a separate event only when no price moved.
            val priceMoved = p.diff.any { it.field in setOf("list_price", "cash_price", "monthly_rent") }
            if (planChanged && !priceMoved) event(tx, lid, cur.unitId, "plan_changed", "payment_plan", d(p, "payment_plan")?.old, d(p, "payment_plan")?.new, row.currency, false, row.assertedOn, batchId, "Payment terms changed; headline price unchanged", "$lid|plan|v$version", now)
            d(p, "handover")?.let { event(tx, lid, cur.unitId, "handover_changed", "handover", it.old, it.new, null, true, row.assertedOn, batchId, null, "$lid|handover|v$version", now) }
        }
        if (areaChanged) {
            tx.exec("UPDATE unit SET area_m2=?::numeric, area_basis=? WHERE id=?", row.areaM2, row.areaBasis.name, cur.unitId)
            d(p, "area")?.let { event(tx, lid, cur.unitId, "area_changed", "area", it.old, it.new, null, false, row.assertedOn, batchId, null, "$lid|area|${row.assertedOn}|${it.new}", now) }
        }
        val dec = p.availabilityDecision
        if (dec is AvailabilityRules.Decision.Apply) {
            val eff = effectiveInstant(row.assertedOn)
            tx.exec("INSERT INTO availability_observation(id,listing_id,unit_id,state,effective_at,recorded_at,accepted,is_correction,note,source_record_id,batch_id) VALUES (?,?,?,?,?,?,true,?,?,?,?)",
                newId(), lid, cur.unitId, dec.to.name, eff, now, row.correction, dec.note ?: row.correctionReason, srId, batchId)
            event(tx, lid, cur.unitId, if (row.correction) "correction" else "availability_changed", "availability", cur.availability?.name, dec.to.name, null, true, row.assertedOn, batchId,
                dec.note, "$lid|availability|$eff|${dec.to}", now)
        }
        if ("publication" in fields) tx.exec("UPDATE listing SET publication='published' WHERE id=?", lid)
        val asserted = effectiveInstant(row.assertedOn)
        tx.exec("UPDATE listing SET last_confirmed_at=GREATEST(COALESCE(last_confirmed_at, ?), ?) WHERE id=?", asserted, asserted, lid)
        return lid
    }

    private fun d(p: Planned, f: String) = p.diff.firstOrNull { it.field == f }

    private fun applyWithdraw(tx: Tx, batchId: UUID, srId: UUID, listingId: UUID, now: Instant) {
        val unit = tx.one("SELECT unit_id FROM listing WHERE id=? AND publication='published'", listingId) { it.uuid("unit_id") } ?: return
        tx.exec("UPDATE listing SET publication='withdrawn' WHERE id=?", listingId)
        event(tx, listingId, unit, "offer_withdrawn", "publication", "published", "withdrawn", null, true, now.atZone(cairo).toLocalDate().toString(), batchId,
            "Absent from the supplier's full snapshot", "$listingId|withdrawn|$batchId", now)
    }

    // ---------- Revert ----------

    fun revert(batchId: UUID, actor: Principal, reason: String): Int = db.tx {
        val org = one("SELECT org_id FROM import_batch WHERE id=? AND status='published' FOR UPDATE", batchId) { it.uuid("org_id") }
            ?: throw ImportException("bad_state", "Only published batches can be reverted")
        if (!actor.canReviewFor(org)) throw ImportException("forbidden", "Not allowed")
        run("SELECT pg_advisory_xact_lock(hashtext(?))", "publish:$org")
        val newer = one("""SELECT count(*) FROM offer_version o WHERE o.batch_id=? AND o.status='superseded'
                           AND EXISTS (SELECT 1 FROM offer_version n WHERE n.listing_id=o.listing_id AND n.version_no>o.version_no AND n.batch_id<>?)""", batchId, batchId) { it.getInt(1) } ?: 0
        if (newer > 0) throw ImportException("superseded", "$newer offers from this batch were already revised by a newer batch. Revert the newer batch first.")
        val now = clock()
        val versions = list("SELECT id, listing_id, version_no FROM offer_version WHERE batch_id=? AND status='current'", batchId) { Triple(it.uuid("id"), it.uuid("listing_id"), it.getInt("version_no")) }
        var corrections = 0
        for ((vid, lid, vno) in versions) {
            exec("UPDATE offer_version SET status='retracted', superseded_at=? WHERE id=?", now, vid)
            val prev = one("SELECT id, list_price_minor, cash_price_minor, currency, asserted_on FROM offer_version WHERE listing_id=? AND version_no<? AND status='superseded' ORDER BY version_no DESC LIMIT 1", lid, vno) {
                arrayOf<Any?>(it.uuid("id"), it.longOrNull("list_price_minor"), it.longOrNull("cash_price_minor"), it.getString("currency"), it.getObject("asserted_on", LocalDate::class.java))
            }
            val unitId = one("SELECT unit_id FROM listing WHERE id=?", lid) { it.uuid("unit_id") }!!
            if (prev != null) {
                exec("UPDATE offer_version SET status='current', superseded_at=NULL WHERE id=?", prev[0])
                exec("UPDATE listing SET last_confirmed_at=? WHERE id=?", (prev[4] as LocalDate?)?.atStartOfDay(cairo)?.toInstant(), lid)
                event(this, lid, unitId, "correction", "list_price", null, (prev[1] ?: prev[2])?.toString(), (prev[3] as String?)?.trim(), true, null, null,
                    "Import reverted: $reason. Previous offer restored.", "$lid|revert|$batchId", now)
            } else {
                exec("UPDATE listing SET publication='withdrawn' WHERE id=?", lid)
                event(this, lid, unitId, "offer_withdrawn", "publication", "published", "withdrawn", null, true, null, null, "Import reverted: $reason", "$lid|revert|$batchId", now)
            }
            corrections++
        }
        exec("UPDATE availability_observation SET retracted=true WHERE batch_id=?", batchId)
        exec("UPDATE change_event SET retracted=true WHERE batch_id=?", batchId)
        // Withdrawals made by this batch are undone.
        list("SELECT listing_id FROM source_record WHERE batch_id=? AND action='withdraw'", batchId) { it.uuid("listing_id") }.forEach { exec("UPDATE listing SET publication='published' WHERE id=?", it) }
        exec("UPDATE import_batch SET status='reverted', reverted_at=?, review_note=coalesce(review_note,'') || ? WHERE id=?", now, " | reverted: $reason", batchId)
        audit(this, actor, org, "import.revert", "import_batch", batchId.toString(), mapOf("reason" to reason, "offers" to corrections.toString()))
        corrections
    }

    fun audit(tx: Tx, actor: Principal?, orgId: UUID?, action: String, entity: String, entityId: String, details: Map<String, String>) {
        tx.exec("INSERT INTO audit_log(actor_user_id, actor_org_id, action, entity, entity_id, details) VALUES (?,?,?,?,?,?)",
            actor?.userId, orgId, action, entity, entityId, Json(json.encodeToString(details)))
    }
}
