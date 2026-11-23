package eg.wadeh.server

import eg.wadeh.core.api.*
import eg.wadeh.server.imports.ImportService
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlin.test.*

class DecisionTest {
    private val db get() = TestDb.db
    private lateinit var imports: ImportService

    @BeforeTest fun setup() { TestDb.reset(); imports = ImportService(db) }

    private fun publish(bytes: ByteArray, name: String = "s.csv") {
        val r = imports.stage(Seed.TEST_SUPPLIER, TestDb.principal(Seed.OPERATOR), name, bytes, "partial")
        imports.publish(r.batchId, TestDb.principal(Seed.REVIEWER), true)
    }

    private fun ApplicationTestBuilder.http() = createClient { install(ContentNegotiation) { json(apiJson) } }
    private fun ApplicationTestBuilder.boot() = application { module(App(Config(TestDb.url, 0, "test", "test"), db, "2")) }

    @Test fun `compare agrees with engine and refuses to crown a total with an unknown fee`() = testApplication {
        boot()
        publish(Csv.of(Csv.Row(unit = "Q1"), Csv.Row(unit = "Q1-NOPARK", parking = "مطلوب")))
        val c = http()
        val ids = c.get("/v1/listings").body<Page<ListingCard>>().items.map { it.id }
        val r = c.get("/v1/compare?ids=${ids.joinToString(",")}").body<CompareResponse>()
        val byUnit = r.cards.zip(r.comparison.rows).associate { (card, row) -> card.unitCode to row }
        assertEquals(852_000_000L, byUnit["Q1"]!!.knownMandatoryMinor)
        assertEquals(840_000_000L, byUnit["Q1-NOPARK"]!!.knownMandatoryMinor)
        assertFalse(byUnit["Q1-NOPARK"]!!.exactTotalKnown)
        assertNull(r.comparison.lowest["known_mandatory"])
    }

    @Test fun `share link is read-only, carries no private data, and revocation is enforced on an old link`() = testApplication {
        boot()
        publish(Csv.of(Csv.Row(unit = "S1"), Csv.Row(unit = "S2", list = "7,000,000")))
        val c = http()
        val ids = c.get("/v1/listings").body<Page<ListingCard>>().items.map { it.id }
        val share = c.post("/v1/shares") { contentType(ContentType.Application.Json); setBody(CreateShareRequest(ids, "ar")) }.body<CreateShareResponse>()
        assertTrue(share.url.endsWith("/s/${share.token}"))
        assertEquals(22, share.token.length)
        val page = c.get("/s/${share.token}")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("مقارنة للقراءة فقط"))
        assertEquals("noindex, nofollow", page.headers["X-Robots-Tag"])
        assertEquals(2, c.get("/v1/shares/${share.token}").body<SharedComparison>().compare.cards.size)

        // Wrong secret cannot revoke; owner can.
        assertEquals(HttpStatusCode.NotFound, c.delete("/v1/shares/${share.token}") { header("X-Owner-Secret", "guess") }.status)
        assertEquals(HttpStatusCode.NoContent, c.delete("/v1/shares/${share.token}") { header("X-Owner-Secret", share.ownerSecret) }.status)
        assertEquals(HttpStatusCode.Gone, c.get("/v1/shares/${share.token}").status)
        assertEquals(HttpStatusCode.Gone, c.get("/s/${share.token}").status)
        assertFalse(c.get("/s/${share.token}").bodyAsText().contains("8,000,000"))
        // The database stores only public listing ids.
        val cols = db.read { list("SELECT column_name FROM information_schema.columns WHERE table_name='share_link'") { it.getString(1) } }
        assertTrue(cols.none { it.contains("note") || it.contains("budget") || it.contains("address") })
    }

    @Test fun `a withdrawn home disappears from an existing share`() = testApplication {
        boot()
        publish(Csv.of(Csv.Row(unit = "W1"), Csv.Row(unit = "W2")))
        val c = http()
        val ids = c.get("/v1/listings").body<Page<ListingCard>>().items.map { it.id }
        val share = c.post("/v1/shares") { contentType(ContentType.Application.Json); setBody(CreateShareRequest(ids)) }.body<CreateShareResponse>()
        val r = imports.stage(Seed.TEST_SUPPLIER, TestDb.principal(Seed.OPERATOR), "snap.csv", Csv.of(Csv.Row(unit = "W1", asOf = "2026-11-05")), "full_snapshot")
        imports.publish(r.batchId, TestDb.principal(Seed.REVIEWER), true)
        val s = c.get("/v1/shares/${share.token}").body<SharedComparison>()
        assertEquals(1, s.compare.cards.size); assertEquals(1, s.missing)
    }
}
