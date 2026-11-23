package eg.wadeh.server

import eg.wadeh.core.api.*
import eg.wadeh.server.imports.ImportService
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class InquiryTest {
    private val db get() = TestDb.db
    private lateinit var imports: ImportService
    private var now = Instant.parse("2026-11-23T10:00:00Z")
    private val slot = "2026-11-28T08:00:00Z" // Friday 10:00 Cairo

    @BeforeTest fun setup() {
        TestDb.reset(); imports = ImportService(db)
        val r = imports.stage(Seed.TEST_SUPPLIER, TestDb.principal(Seed.OPERATOR), "a.csv", Csv.of(Csv.Row(unit = "V1"), Csv.Row(unit = "V2", status = "sold")), "partial")
        imports.publish(r.batchId, TestDb.principal(Seed.REVIEWER), false)
    }

    private fun svc(enabled: Boolean = true) = Inquiries(db, Catalog(db, "test"), enabled) { now }
    private fun card(unit: String) = Catalog(db, "test").search(ListingQuery(q = unit)).items.single()
    private fun req(c: ListingCard, key: String = UUID.randomUUID().toString(), version: String = c.offerVersionId, s: String = slot, phone: String = "01012345678") =
        CreateInquiryRequest(key, c.id, version, "in_app", "Test Buyer (synthetic)", phone, true, CONSENT_TEXT_VERSION, s)

    @Test fun `network retry with the same key creates exactly one inquiry`() {
        val c = card("V1"); val r = req(c, key = "retry-key-0000000001")
        val a = svc().create(r); val b = svc().create(r)
        assertEquals(a.id, b.id); assertEquals("requested", a.status); assertNotNull(a.buyerToken); assertNull(b.buyerToken)
        assertEquals(1, db.read { one("SELECT count(*) FROM inquiry") { it.getInt(1) } })
        assertEquals(1, db.read { one("SELECT count(*) FROM outbox WHERE topic='partner_inbox.new_inquiry'") { it.getInt(1) } })
    }

    @Test fun `two buyers request the same slot at the same instant - one holds it, the other is waitlisted with alternatives`() {
        val c = card("V1"); val pool = Executors.newFixedThreadPool(2); val gate = CountDownLatch(1)
        val futures = (1..2).map { i -> pool.submit<InquiryStatus> { gate.await(); svc().create(req(c, key = "same-slot-key-00000$i")) } }
        gate.countDown(); val results = futures.map { it.get() }
        assertEquals(setOf("requested", "waitlisted"), results.map { it.status }.toSet())
        val wait = results.single { it.status == "waitlisted" }
        assertEquals(3, wait.alternatives.size); assertTrue(wait.alternatives.none { it == slot })
        assertEquals(1, db.read { one("SELECT count(*) FROM inquiry WHERE holds_slot") { it.getInt(1) } })
    }

    @Test fun `price changed since the buyer opened the offer - 409 with the change, nothing sent`() = testApplication {
        application { module(App(Config(TestDb.url, 0, "test", "test"), db, "6")) }
        val http = createClient { install(ContentNegotiation) { json(apiJson) } }
        val seen = card("V1")
        val r = imports.stage(Seed.TEST_SUPPLIER, TestDb.principal(Seed.OPERATOR), "b.csv", Csv.of(Csv.Row(unit = "V1", list = "8,200,000", asOf = "2026-11-10")), "partial")
        imports.publish(r.batchId, TestDb.principal(Seed.REVIEWER), false)
        val res = http.post("/v1/inquiries") { contentType(ContentType.Application.Json); setBody(req(seen, s = "2027-01-15T08:00:00Z")) }
        assertEquals(HttpStatusCode.Conflict, res.status)
        val body = res.body<OfferChanged>()
        assertEquals(820_000_000L, body.current.priceMinor)
        assertEquals("price_changed", body.changes.first().kind)
        assertEquals(0, db.read { one("SELECT count(*) FROM inquiry") { it.getInt(1) } })
    }

    @Test fun `partner isolation, lifecycle, cancellation frees the slot`() {
        val c = card("V1"); val created = svc().create(req(c)); val id = UUID.fromString(created.id)
        val broker = TestDb.principal(Seed.BROKER_AGENT); val owner = TestDb.principal(Seed.OPERATOR)
        assertTrue(svc().inbox(broker).isEmpty())
        assertEquals("forbidden", assertFailsWith<InquiryException> { svc().partnerAction(broker, id, "confirm") }.code)
        svc().partnerAction(owner, id, "acknowledge"); svc().partnerAction(owner, id, "confirm")
        assertEquals("confirmed", svc().buyerStatus(id, created.buyerToken!!)!!.status)
        assertNull(svc().buyerStatus(id, "wrong-token"))
        svc().cancelByBuyer(id, created.buyerToken!!)
        assertEquals(listOf("requested", "acknowledged", "confirmed", "cancelled"), svc().status(id, null).timeline.map { it.status })
        assertEquals("requested", svc().create(req(c)).status) // slot is free again
    }

    @Test fun `unanswered request becomes no_response after the SLA and partner can still act`() {
        val c = card("V1"); val created = svc().create(req(c)); val id = UUID.fromString(created.id)
        now = now.plus(Duration.ofHours(25))
        assertEquals(1, svc().sweepUnanswered())
        assertEquals("no_response", svc().status(id, null).status)
        svc().partnerAction(TestDb.principal(Seed.OPERATOR), id, "confirm")
        assertEquals("confirmed", svc().status(id, null).status)
    }

    @Test fun `sold unit, bad phone, missing consent and disabled leads are refused`() {
        assertEquals("not_available", assertFailsWith<InquiryException> { svc().create(req(card("V2"))) }.code)
        assertFailsWith<IllegalArgumentException> { svc().create(req(card("V1"), phone = "12345")) }
        assertFailsWith<IllegalArgumentException> { svc().create(req(card("V1")).copy(consentShareContact = false)) }
        assertEquals("leads_disabled", assertFailsWith<InquiryException> { svc(enabled = false).create(req(card("V1"))) }.code)
    }

    @Test fun `retention purge erases contact details but keeps the anonymous trail`() {
        val created = svc().create(req(card("V1")))
        now = now.plus(Duration.ofDays(181))
        assertEquals(1, svc().purgeExpired())
        assertEquals("[erased]" to "[erased]", db.read { one("SELECT contact_name, contact_phone FROM inquiry") { it.getString(1) to it.getString(2) } })
        assertEquals(1, svc().status(UUID.fromString(created.id), null).timeline.size)
    }
}
