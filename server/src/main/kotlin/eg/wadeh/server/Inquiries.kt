package eg.wadeh.server

import eg.wadeh.core.api.*
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

class InquiryException(val code: String, message: String, val body: Any? = null) : RuntimeException(message)

/**
 * Viewing-request lifecycle. A click-to-call or WhatsApp handoff is recorded as a handoff, never as an appointment.
 * Status is only "confirmed" when the partner confirms in their inbox.
 */
class Inquiries(private val db: Db, private val catalog: Catalog, private val leadsEnabled: Boolean, private val clock: () -> Instant = Instant::now) {
    private val rnd = SecureRandom()
    private val retention = Duration.ofDays(180)
    private val cairo = ZoneId.of("Africa/Cairo")

    private fun event(tx: Tx, id: UUID, from: String?, to: String, actor: String, note: String? = null) =
        tx.exec("INSERT INTO inquiry_event(inquiry_id, from_status, to_status, actor, note, at) VALUES (?,?,?,?,?,?)", id, from, to, actor, note, clock())

    /** Next free slots for the listing, from the standard viewing grid (10:00, 13:00, 16:00 Cairo time). */
    fun alternatives(tx: Tx, listingId: UUID, after: Instant, n: Int = 3): List<Instant> {
        val taken = tx.list("SELECT slot_start FROM inquiry WHERE listing_id=? AND holds_slot AND slot_start > ?", listingId, after) { it.instant("slot_start") }.toSet()
        val out = mutableListOf<Instant>(); var day = after.atZone(cairo).toLocalDate()
        while (out.size < n) {
            for (h in listOf(10, 13, 16)) {
                val s = day.atTime(h, 0).atZone(cairo).toInstant()
                if (s.isAfter(after) && s !in taken && out.size < n) out += s
            }
            day = day.plusDays(1)
        }
        return out
    }

    fun create(r: CreateInquiryRequest): InquiryStatus {
        if (!leadsEnabled) throw InquiryException("leads_disabled", "Viewing requests are not available yet in this environment.")
        require(r.idempotencyKey.length in 16..64) { "idempotencyKey must be 16-64 chars" }
        require(r.consentShareContact) { "Consent to share contact details with the seller is required for a viewing request" }
        require(r.consentTextVersion == CONSENT_TEXT_VERSION) { "Consent text changed; please review it again" }
        require(r.channel in setOf("in_app", "phone_call", "whatsapp")) { "Unknown channel" }
        val phone = eg.wadeh.core.Digits.toWestern(r.phone).filter { it.isDigit() || it == '+' }
        require(Regex("^(\\+?20)?0?1[0125]\\d{8}$").matches(phone)) { "Enter an Egyptian mobile number" }
        require(r.name.isNotBlank() && r.name.length <= 80) { "Name is required" }
        val listingId = UUID.fromString(r.listingId)
        val slot = Instant.parse(r.slotStart)
        require(slot.isAfter(clock())) { "Choose a future time" }

        // Idempotent retry: same key returns the same inquiry (never a duplicate lead).
        db.read { one("SELECT id FROM inquiry WHERE idempotency_key=?", r.idempotencyKey) { it.uuid("id") } }?.let { return status(it, null) }

        // Revalidate the offer the buyer saw.
        val detail = catalog.detail(listingId) ?: throw InquiryException("not_published", "This offer is no longer published")
        if (detail.card.offerVersionId != r.offerVersionIdSeen) {
            val seenAt = db.read { one("SELECT recorded_at FROM offer_version WHERE id=?", UUID.fromString(r.offerVersionIdSeen)) { it.instant("recorded_at") } }
            throw InquiryException("offer_changed", "The offer changed since you opened it", OfferChanged("offer_changed", detail.card,
                detail.changes.filter { seenAt == null || Instant.parse(it.recordedAt).isAfter(seenAt) }))
        }
        if (detail.card.availability !in setOf("AVAILABLE", "RELEASED")) throw InquiryException("not_available", "This unit is not currently available (${detail.card.availability})")

        val token = ByteArray(24).also(rnd::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val id = newId()
        val recipient = db.read { one("SELECT seller_org_id FROM listing WHERE id=?", listingId) { it.uuid("seller_org_id") } }!!
        return try {
            db.tx {
                exec("""INSERT INTO inquiry(id,idempotency_key,buyer_token_hash,listing_id,offer_version_id,recipient_org_id,channel,contact_name,contact_phone,
                        consent_share_contact,consent_text_version,preferred_lang,slot_start,holds_slot,status,retention_until,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,true,'requested',?,?,?)""",
                    id, r.idempotencyKey, sha256Hex(token), listingId, UUID.fromString(r.offerVersionIdSeen), recipient, r.channel, r.name.trim(), phone,
                    true, r.consentTextVersion, r.lang, slot, clock().plus(retention), clock(), clock())
                event(this, id, null, "requested", "buyer")
                exec("INSERT INTO outbox(dedupe_key, topic, payload) VALUES (?,?,?) ON CONFLICT DO NOTHING", "inquiry:$id:new", "partner_inbox.new_inquiry",
                    Json("""{"inquiry":"$id","org":"$recipient"}"""))
            }
            status(id, token)
        } catch (e: SQLException) {
            if (e.sqlState == "23505" && e.message?.contains("idempotency_key") == true)
                return status(db.read { one("SELECT id FROM inquiry WHERE idempotency_key=?", r.idempotencyKey) { it.uuid("id") } }!!, null)
            if (e.sqlState != "23505") throw e
            // Slot already held: record the request as waitlisted and offer real alternatives.
            db.tx {
                exec("""INSERT INTO inquiry(id,idempotency_key,buyer_token_hash,listing_id,offer_version_id,recipient_org_id,channel,contact_name,contact_phone,
                        consent_share_contact,consent_text_version,preferred_lang,slot_start,holds_slot,status,retention_until,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,false,'waitlisted',?,?,?) ON CONFLICT (idempotency_key) DO NOTHING""",
                    id, r.idempotencyKey, sha256Hex(token), listingId, UUID.fromString(r.offerVersionIdSeen), recipient, r.channel, r.name.trim(), phone,
                    true, r.consentTextVersion, r.lang, slot, clock().plus(retention), clock(), clock())
                event(this, id, null, "waitlisted", "system", "Requested slot already held by another request")
            }
            status(id, token)
        }
    }

    fun status(id: UUID, token: String?): InquiryStatus = db.read {
        val row = one("""SELECT i.status, i.slot_start, i.proposed_slot, i.listing_id, o.name_en, o.name_ar FROM inquiry i JOIN organization o ON o.id=i.recipient_org_id WHERE i.id=?""", id) {
            arrayOf<Any?>(it.getString("status"), it.instant("slot_start"), it.instantOrNull("proposed_slot"), it.uuid("listing_id"), it.getString("name_en"), it.getString("name_ar"))
        }!!
        val tl = list("SELECT to_status, at, note FROM inquiry_event WHERE inquiry_id=? ORDER BY id", id) { InquiryEvent(it.getString(1), it.instant("at").toString(), it.getString(3)) }
        val alts = if (row[0] == "waitlisted") alternatives(this, row[3] as UUID, row[1] as Instant).map { it.toString() } else emptyList()
        InquiryStatus(id.toString(), row[0] as String, L10n(row[4] as String, row[5] as String), (row[1] as Instant).toString(), (row[2] as Instant?)?.toString(), alts, tl, token)
    }

    fun buyerStatus(id: UUID, token: String): InquiryStatus? =
        db.read { one("SELECT 1 FROM inquiry WHERE id=? AND buyer_token_hash=?", id, sha256Hex(token)) { 1 } }?.let { status(id, null) }

    fun nextActions(status: String) = partnerTransitions.filter { status in it.value.first }.keys.toList()

    private val partnerTransitions = mapOf(
        "acknowledge" to (setOf("requested", "no_response") to "acknowledged"),
        "confirm" to (setOf("requested", "acknowledged", "no_response") to "confirmed"),
        "propose" to (setOf("requested", "acknowledged", "waitlisted", "no_response") to "alternative_proposed"),
        "decline" to (setOf("requested", "acknowledged", "waitlisted", "no_response") to "declined"),
        "attended" to (setOf("confirmed") to "attended"),
        "no_show" to (setOf("confirmed") to "no_show"),
    )

    /** Partner actions are scoped to the partner's own organisation (object-level authorisation). */
    fun partnerAction(who: Principal, id: UUID, action: String, proposed: Instant? = null, note: String? = null) = db.tx {
        val (org, from) = one("SELECT recipient_org_id, status FROM inquiry WHERE id=? FOR UPDATE", id) { it.uuid("recipient_org_id") to it.getString("status") }
            ?: throw InquiryException("not_found", "No such request")
        if (!(who.isPlatformAdmin || who.memberships.any { it.orgId == org })) throw InquiryException("forbidden", "Not your organisation's request")
        val (allowed, to) = partnerTransitions[action] ?: throw IllegalArgumentException("Unknown action")
        if (from !in allowed) throw InquiryException("bad_state", "Cannot $action a request that is $from")
        if (action == "propose") require(proposed != null && proposed.isAfter(clock())) { "Propose a future time" }
        val freesSlot = to in setOf("declined", "alternative_proposed")
        exec("UPDATE inquiry SET status=?, proposed_slot=COALESCE(?, proposed_slot), holds_slot=CASE WHEN ? THEN false ELSE holds_slot END, updated_at=? WHERE id=?", to, proposed, freesSlot, clock(), id)
        event(this, id, from, to, "partner:${who.userId}", note)
        exec("INSERT INTO outbox(dedupe_key, topic, payload) VALUES (?,?,?) ON CONFLICT DO NOTHING", "inquiry:$id:$to", "buyer.inquiry_status", Json("""{"inquiry":"$id","status":"$to"}"""))
    }

    fun cancelByBuyer(id: UUID, token: String) = db.tx {
        val from = one("SELECT status FROM inquiry WHERE id=? AND buyer_token_hash=? FOR UPDATE", id, sha256Hex(token)) { it.getString(1) } ?: throw InquiryException("not_found", "No such request")
        if (from in setOf("cancelled", "attended", "no_show", "declined")) throw InquiryException("bad_state", "Already $from")
        exec("UPDATE inquiry SET status='cancelled', holds_slot=false, updated_at=? WHERE id=?", clock(), id)
        event(this, id, from, "cancelled", "buyer")
    }

    /** Requests nobody acknowledged within the SLA become operator work, and the buyer is told honestly. */
    fun sweepUnanswered(sla: Duration = Duration.ofHours(24)): Int = db.tx {
        val stale = list("SELECT id FROM inquiry WHERE status='requested' AND created_at < ?", clock().minus(sla)) { it.uuid("id") }
        stale.forEach { exec("UPDATE inquiry SET status='no_response', updated_at=? WHERE id=?", clock(), it); event(this, it, "requested", "no_response", "system", "No acknowledgement within ${sla.toHours()} h; operator follow-up queued") }
        stale.size
    }

    /** Retention: personal fields are erased after retention_until; the anonymous event trail remains for metrics. */
    fun purgeExpired(): Int = db.tx { exec("UPDATE inquiry SET contact_name='[erased]', contact_phone='[erased]' WHERE retention_until < ? AND contact_phone<>'[erased]'", clock()) }

    fun inbox(who: Principal): List<Map<String, Any?>> = db.read {
        val orgs = who.memberships.map { it.orgId.toString() }.toTypedArray()
        list("""SELECT i.id, i.status, i.slot_start, i.proposed_slot, i.channel, i.contact_name, i.contact_phone, i.created_at, u.unit_code, p.name_en
                FROM inquiry i JOIN listing l ON l.id=i.listing_id JOIN unit u ON u.id=l.unit_id JOIN project p ON p.id=u.project_id
                WHERE ${if (who.isPlatformAdmin) "true" else "i.recipient_org_id = ANY(?::uuid[])"} ORDER BY i.created_at DESC LIMIT 200""",
            *(if (who.isPlatformAdmin) emptyArray() else arrayOf<Any?>(orgs))) {
            mapOf("id" to it.uuid("id"), "status" to it.getString("status"), "slot" to it.instant("slot_start"), "proposed" to it.instantOrNull("proposed_slot"), "channel" to it.getString("channel"),
                "name" to it.getString("contact_name"), "phone" to it.getString("contact_phone"), "created" to it.instant("created_at"), "unit" to it.getString("unit_code"), "project" to it.getString("name_en"))
        }
    }
}
