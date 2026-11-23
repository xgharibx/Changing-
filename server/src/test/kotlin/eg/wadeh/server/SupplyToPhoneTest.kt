package eg.wadeh.server

import eg.wadeh.core.api.ListingDetail
import eg.wadeh.core.api.Page
import eg.wadeh.core.api.ListingCard
import eg.wadeh.server.imports.ImportException
import eg.wadeh.server.imports.ImportService
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class SupplyToPhoneTest {
    private val db get() = TestDb.db
    private lateinit var imports: ImportService
    private val operator get() = TestDb.principal(Seed.OPERATOR)
    private val reviewer get() = TestDb.principal(Seed.REVIEWER)
    private val broker get() = TestDb.principal(Seed.BROKER_AGENT)

    @BeforeTest fun setup() { TestDb.reset(); imports = ImportService(db) }

    private fun importAndPublish(org: UUID, bytes: ByteArray, name: String = "sheet.csv", mode: String = "partial", by: Principal = operator, ack: Boolean = false): UUID {
        val r = imports.stage(org, by, name, bytes, mode)
        assertNull(r.duplicateOf, "unexpected duplicate")
        imports.publish(r.batchId, reviewer, ack)
        return r.batchId
    }

    private fun events(listingKey: String, org: UUID = Seed.TEST_SUPPLIER) = db.read {
        list("SELECT ce.kind, ce.field, ce.old_value, ce.new_value FROM change_event ce JOIN listing l ON l.id=ce.listing_id WHERE l.supplier_key=? AND l.seller_org_id=? AND NOT ce.retracted ORDER BY ce.seq", listingKey, org) {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }
    }

    private fun ApplicationTestBuilder.client() = createClient { install(ContentNegotiation) { json(apiJson) } }

    @Test fun `M1 acceptance - import, review, publish, phone list+detail, revise, replay shows exactly one change`() = testApplication {
        application { module(App(Config(TestDb.url, 0, "test", "test"), db, "1")) }
        val http = client()

        // 1. Operator imports; nothing is public before review.
        val staged = imports.stage(Seed.TEST_SUPPLIER, operator, "gardens-nov.csv", Csv.of(Csv.Row(unit = "B12-0304")), "partial")
        assertEquals(1, staged.summary.add)
        assertEquals(0, http.get("/v1/listings").body<Page<ListingCard>>().items.size)

        // Four-eyes: the uploader cannot publish.
        assertFailsWith<ImportException> { imports.publish(staged.batchId, operator, false) }.also { assertTrue(it.code in setOf("forbidden", "four_eyes")) }
        imports.publish(staged.batchId, reviewer, false)

        // 2. Phone retrieves it via the same API production uses.
        val page = http.get("/v1/listings").body<Page<ListingCard>>()
        assertEquals(1, page.items.size)
        val card = page.items.single()
        assertEquals(800_000_000L, card.priceMinor)
        assertEquals("instalment_list", card.priceBasis)
        assertEquals(80_000_000L, card.cashToStartMinor) // EGP 800,000 deposit (reservation credited inside it)
        assertEquals("AVAILABLE", card.availability)
        assertEquals(1, card.offerVersion)
        assertTrue(card.isTestData)

        // 3. Revise the price (new sheet date) -> one change.
        val revised = Csv.of(Csv.Row(unit = "B12-0304", list = "8,400,000", inst = "316,667", asOf = "2026-11-10"))
        importAndPublish(Seed.TEST_SUPPLIER, revised, "gardens-nov-10.csv", ack = false)
        // 4. Replay the same revision: identical bytes are recognised, renamed file too.
        val replay = imports.stage(Seed.TEST_SUPPLIER, operator, "gardens-nov-10 (copy).csv", revised, "partial")
        assertNotNull(replay.duplicateOf)
        // Same facts, different bytes (row re-serialised with an extra space): rows are "unchanged", zero events.
        val sameFacts = Csv.of(Csv.Row(unit = "B12-0304", list = "8,400,000 ", inst = "316,667", asOf = "2026-11-10"))
        val b2 = imports.stage(Seed.TEST_SUPPLIER, operator, "gardens-resend.csv", sameFacts, "partial")
        assertNull(b2.duplicateOf); assertEquals(1, b2.summary.unchanged)
        imports.publish(b2.batchId, reviewer, false)

        val detail = http.get("/v1/listings/${card.id}").body<ListingDetail>()
        val visible = detail.changes.filter { it.kind != "offer_published" }
        assertEquals(1, visible.size, "phone must show exactly one change, got $visible")
        val priceChanges = visible.filter { it.kind == "price_changed" }
        assertEquals(1, priceChanges.size)
        assertEquals("800000000", priceChanges[0].oldValue)
        assertEquals("840000000", priceChanges[0].newValue)
        assertFalse(priceChanges[0].likeForLike, "instalment amount changed too, so not like-for-like")
        assertEquals(listOf("plan_changed"), priceChanges[0].reasons)
        assertEquals(2, detail.card.offerVersion)
        assertEquals(listOf("current", "superseded"), detail.versions.map { it.status })
        assertEquals("2026-11-10", detail.card.evidence.assertedOn)
    }

    @Test fun `ready-to-move unit re-sent on a newer sheet creates no handover change`() {
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "R1", handover = "استلام فوري")))
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "R1", handover = "فوري", asOf = "2026-11-10")), "r2.csv")
        assertEquals(listOf("offer_published"), events("R1").map { it[0] })
        assertEquals("ready", db.read { one("SELECT handover FROM offer_version WHERE status='current'") { it.getString(1) } })
    }

    @Test fun `plan-only change is its own event`() {
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "P1")))
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "P1", mnt = "450000", asOf = "2026-11-10")), "p2.csv")
        assertEquals(listOf("offer_published", "plan_changed"), events("P1").map { it[0] })
    }

    @Test fun `like-for-like price cut is labelled as such`() {
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "A1", dp = "", res = "", inst = "", freq = "", count = "", first = "", list = "", cash = "5,000,000", mnt = "", parking = "")))
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "A1", dp = "", res = "", inst = "", freq = "", count = "", first = "", list = "", cash = "4,800,000", mnt = "", parking = "", asOf = "2026-11-12")))
        val e = events("A1").filter { it[0] == "price_changed" }
        assertEquals(1, e.size); assertEquals("cash_price", e[0][1])
        assertTrue(db.read { one("SELECT like_for_like FROM change_event WHERE kind='price_changed'") { it.getBoolean(1) }!! })
    }

    @Test fun `stale sheet re-uploaded with a new filename keeps original confirmation date`() {
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "S1", asOf = "2026-11-03")))
        val again = imports.stage(Seed.TEST_SUPPLIER, operator, "renamed-final-FINAL.csv", Csv.of(Csv.Row(unit = "S1", asOf = "2026-11-03", building = "B12 ")), "partial")
        assertEquals(1, again.summary.unchanged)
        imports.publish(again.batchId, reviewer, false)
        val confirmed = db.read { one("SELECT last_confirmed_at FROM listing WHERE supplier_key='S1'") { it.instant("last_confirmed_at") } }!!
        assertEquals(LocalDate.of(2026, 11, 3), confirmed.atZone(java.time.ZoneId.of("Africa/Cairo")).toLocalDate())
        // An older sheet with a different price is not applied.
        val older = imports.stage(Seed.TEST_SUPPLIER, operator, "old.csv", Csv.of(Csv.Row(unit = "S1", list = "7,000,000", asOf = "2026-10-01")), "partial")
        assertEquals(1, older.summary.stale)
        imports.publish(older.batchId, reviewer, true)
        assertEquals(1, events("S1").size) // only offer_published
    }

    @Test fun `sold then older available then duplicate - no reopen, no second event, newer correction reopens`() {
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "U9", asOf = "2026-11-01")))
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "U9", status = "مباع", asOf = "2026-11-05")))
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "U9", status = "available", asOf = "2026-11-02")), "late.csv")
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "U9", status = "sold", asOf = "2026-11-06")), "dup.csv")
        val av = events("U9").filter { it[1] == "availability" }
        assertEquals(listOf(listOf("availability_changed", "availability", "AVAILABLE", "SOLD")), av)
        // A newer AVAILABLE without correction flag is still ignored
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "U9", status = "available", asOf = "2026-11-07")), "noflag.csv")
        assertEquals(1, events("U9").count { it[1] == "availability" })
        // Correction with reason reopens and is recorded as a correction
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "U9", status = "available", asOf = "2026-11-08", correction = "yes", reason = "Buyer cancelled before contract")), "fix.csv")
        val last = events("U9").last()
        assertEquals(listOf("correction", "availability", "SOLD", "AVAILABLE"), last)
        val ignored = db.read { one("SELECT count(*) FROM availability_observation WHERE NOT accepted") { it.getInt(1) } }
        assertEquals(2, ignored) // the late and the unflagged claims are kept for audit, not applied
    }

    @Test fun `two sellers on the same unit - one physical unit, two attributable offers`() {
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "B12-0304")))
        importAndPublish(Seed.TEST_BROKER, Csv.of(Csv.Row(unit = "B12-0304", listingId = "BRK-77", list = "8,250,000", asOf = "2026-11-04")), by = broker)
        assertEquals(1, db.read { one("SELECT count(*) FROM unit") { it.getInt(1) } })
        assertEquals(2, db.read { one("SELECT count(*) FROM listing") { it.getInt(1) } })
        // Broker changes price: only the broker's offer changes.
        importAndPublish(Seed.TEST_BROKER, Csv.of(Csv.Row(unit = "B12-0304", listingId = "BRK-77", list = "8,100,000", asOf = "2026-11-09")), "b2.csv", by = broker)
        assertEquals(1, events("BRK-77", Seed.TEST_BROKER).count { it[0] == "price_changed" })
        assertEquals(0, events("B12-0304").count { it[0] == "price_changed" })
        val catalog = Catalog(db, "test")
        val devListing = db.read { one("SELECT id FROM listing WHERE supplier_key='B12-0304'") { it.uuid("id") } }!!
        assertEquals(1, catalog.detail(devListing)!!.otherOffersForUnit.size)
        // Conflicting physical description is held, not merged.
        val c = imports.stage(Seed.TEST_BROKER, broker, "c.csv", Csv.of(Csv.Row(unit = "B12-0304", listingId = "BRK-78", type = "villa")), "partial")
        assertEquals(1, c.summary.conflict)
    }

    @Test fun `supplier cannot upload for or review another organisation`() {
        assertFailsWith<ImportException> { imports.stage(Seed.TEST_SUPPLIER, broker, "x.csv", Csv.of(Csv.Row(unit = "Z1")), "partial") }
        val b = imports.stage(Seed.TEST_SUPPLIER, operator, "x.csv", Csv.of(Csv.Row(unit = "Z1")), "partial")
        assertFailsWith<ImportException> { imports.publish(b.batchId, broker, false) }
        assertFalse(broker.canSee(Seed.TEST_SUPPLIER))
    }

    @Test fun `100 rows with 2 invalid - report identifies them, corrected file publishes`() {
        val rows = (1..100).map { i -> Csv.Row(unit = "R-%03d".format(i)) }.toMutableList()
        rows[16] = rows[16].copy(list = "call us")            // row 18 in the file
        rows[57] = rows[57].copy(asOf = "31/02/2026")         // row 59
        val b = imports.stage(Seed.TEST_SUPPLIER, operator, "100.csv", Csv.of(*rows.toTypedArray()), "partial")
        assertEquals(98, b.summary.valid); assertEquals(2, b.summary.invalid)
        val bad = db.read { list("SELECT row_no, errors::text FROM source_record WHERE batch_id=? AND action='invalid' ORDER BY row_no", b.batchId) { it.getInt(1) to it.getString(2) } }
        assertEquals(listOf(18, 59), bad.map { it.first })
        assertTrue(bad[0].second.contains("list_price")); assertTrue(bad[1].second.contains("price_sheet_date"))
        imports.publish(b.batchId, reviewer, false)
        val fixed = imports.stage(Seed.TEST_SUPPLIER, operator, "100-fixed.csv", Csv.of(rows[16].copy(list = "8,000,000"), rows[57].copy(asOf = "2026-11-03")), "partial")
        assertEquals(2, fixed.summary.add)
        imports.publish(fixed.batchId, reviewer, false)
        assertEquals(100, db.read { one("SELECT count(*) FROM listing WHERE publication='published'") { it.getInt(1) } })
    }

    @Test fun `full snapshot withdraws missing offers, partial does not, suspicious needs ack`() {
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "F1"), Csv.Row(unit = "F2"), Csv.Row(unit = "F3")))
        val partial = imports.stage(Seed.TEST_SUPPLIER, operator, "p.csv", Csv.of(Csv.Row(unit = "F1", asOf = "2026-11-04")), "partial")
        assertEquals(0, partial.summary.withdraw)
        imports.reject(partial.batchId, reviewer, "test")
        val full = imports.stage(Seed.TEST_SUPPLIER, operator, "full.csv", Csv.of(Csv.Row(unit = "F1", asOf = "2026-11-04")), "full_snapshot")
        assertEquals(2, full.summary.withdraw)
        val e = assertFailsWith<ImportException> { imports.publish(full.batchId, reviewer, false) }
        assertEquals("needs_ack", e.code) // 2 of 3 withdrawn > 30%
        imports.publish(full.batchId, reviewer, true)
        assertEquals(1, db.read { one("SELECT count(*) FROM listing WHERE publication='published'") { it.getInt(1) } })
    }

    @Test fun `revert restores previous offer and retracts the batch's changes`() {
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "V1")))
        val bad = importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "V1", list = "80,000,000", asOf = "2026-11-06")), "typo.csv", ack = true)
        assertEquals(1, imports.revert(bad, reviewer, "extra zero typed by supplier"))
        val cur = db.read { one("SELECT list_price_minor, version_no FROM offer_version WHERE status='current'") { it.getLong(1) to it.getInt(2) } }!!
        assertEquals(800_000_000L to 1, cur)
        val visible = events("V1").map { it[0] }
        assertEquals(listOf("offer_published", "correction"), visible)
    }

    @Test fun `EGY-Q1 through the API matches the golden figures`() = testApplication {
        application { module(App(Config(TestDb.url, 0, "test", "test"), db, "1")) }
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "EGY-Q1")))
        val http = client()
        val id = http.get("/v1/listings?q=EGY-Q1").body<Page<ListingCard>>().items.single().id
        val d = http.get("/v1/listings/$id").body<ListingDetail>()
        val cf = d.cashflow!!
        assertEquals(852_000_000L, cf.exactMandatoryTotalMinor)
        assertEquals(170_000_000L, eg.wadeh.core.CashFlowEngine.dueBetween(cf.schedule, LocalDate.of(2000, 1, 1), LocalDate.of(2027, 12, 31)))
        assertEquals(82_000_000L, eg.wadeh.core.CashFlowEngine.dueOn(cf.schedule, LocalDate.of(2030, 1, 1)))
        // Parking "required, amount unknown"
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "EGY-Q1", parking = "مطلوب", asOf = "2026-11-04")), "q1b.csv")
        val d2 = http.get("/v1/listings/$id").body<ListingDetail>()
        assertEquals(840_000_000L, d2.cashflow!!.knownMandatoryMinor)
        assertNull(d2.cashflow!!.exactMandatoryTotalMinor)
        assertEquals("plan_changed", d2.changes.first().kind)
    }

    @Test fun `search finds Arabic spelling variants and aliases`() = testApplication {
        application { module(App(Config(TestDb.url, 0, "test", "test"), db, "1")) }
        importAndPublish(Seed.TEST_SUPPLIER, Csv.of(Csv.Row(unit = "Q1")))
        val http = client()
        for (q in listOf("التجمع", "القاهره الجديده", "Fifth Settlement", "حدائق الاختبار", "test gardens")) {
            assertEquals(1, http.get("/v1/listings") { parameter("q", q) }.body<Page<ListingCard>>().items.size, "query '$q'")
        }
        assertEquals(0, http.get("/v1/listings") { parameter("q", "زايد") }.body<Page<ListingCard>>().items.size)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/listings/${UUID.randomUUID()}").status)
    }
}
