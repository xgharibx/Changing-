package eg.wadeh.server

import eg.wadeh.core.Compare
import eg.wadeh.core.CompareInput
import eg.wadeh.core.Money
import eg.wadeh.core.MoneyFormat
import eg.wadeh.core.api.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class Sharing(private val db: Db, private val catalog: Catalog, private val publicBase: String) {
    private val rnd = SecureRandom()
    private fun token(bytes: Int) = ByteArray(bytes).also(rnd::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun compare(ids: List<UUID>): CompareResponse {
        val details = ids.mapNotNull { catalog.detail(it) }
        val cmp = if (details.isEmpty()) eg.wadeh.core.Comparison(eg.wadeh.core.ENGINE_VERSION, emptyList(), emptyMap(), emptyMap())
        else Compare.build(details.map { d ->
            val c = d.card
            CompareInput(c.id, c.currency, c.priceMinor, c.priceBasis, c.cashPriceMinor, c.area, c.areaBasis, c.handover, d.plan)
        })
        return CompareResponse(details.map { it.card }, cmp, Instant.now().toString())
    }

    fun create(req: CreateShareRequest, ttl: Duration = Duration.ofDays(30)): CreateShareResponse {
        val ids = req.listingIds.distinct().map(UUID::fromString)
        require(ids.size in 1..4) { "Share 1 to 4 homes" }
        // Only currently published listings can be shared.
        val visible = catalog.search(ListingQuery(ids = ids, limit = 4)).items.map { it.id }.toSet()
        require(ids.all { it.toString() in visible }) { "One of these homes is no longer published" }
        val t = token(16); val secret = token(24); val exp = Instant.now().plus(ttl)
        db.tx { exec("INSERT INTO share_link(token, owner_secret_hash, listing_ids, lang, expires_at) VALUES (?,?,?::uuid[],?,?)",
            t, sha256Hex(secret), ids.map { it.toString() }.toTypedArray(), if (req.lang == "en") "en" else "ar", exp) }
        return CreateShareResponse(t, secret, "$publicBase/s/$t", exp.toString())
    }

    sealed interface Resolved { data class Ok(val s: SharedComparison, val lang: String) : Resolved; data object Gone : Resolved; data object Missing : Resolved }

    fun resolve(token: String): Resolved {
        val row = db.read { one("SELECT listing_ids::text[] ids, lang, expires_at, revoked_at FROM share_link WHERE token=?", token) {
            arrayOf((it.getArray("ids").array as Array<*>).map { x -> UUID.fromString(x.toString()) }, it.getString("lang"), it.instant("expires_at"), it.instantOrNull("revoked_at"))
        } } ?: return Resolved.Missing
        @Suppress("UNCHECKED_CAST") val ids = row[0] as List<UUID>
        if (row[3] != null || (row[2] as Instant).isBefore(Instant.now())) return Resolved.Gone
        db.tx { exec("UPDATE share_link SET view_count=view_count+1 WHERE token=?", token) }
        val cmp = compare(ids)
        return Resolved.Ok(SharedComparison(token, cmp, (row[2] as Instant).toString(), ids.size - cmp.cards.size), row[1] as String)
    }

    fun revoke(token: String, secret: String): Boolean = db.tx {
        exec("UPDATE share_link SET revoked_at=now() WHERE token=? AND owner_secret_hash=? AND revoked_at IS NULL", token, sha256Hex(secret)) == 1
    }
}

fun Route.sharingRoutes(app: App, sharing: Sharing) {
    route("/v1") {
        get("/compare") {
            val ids = call.request.queryParameters["ids"].orEmpty().split(',').filter { it.isNotBlank() }.map(UUID::fromString)
            require(ids.size in 1..4) { "Compare 1 to 4 homes" }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(sharing.compare(ids))
        }
        post("/shares") { call.respond(HttpStatusCode.Created, sharing.create(call.receive())) }
        get("/shares/{token}") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            when (val r = sharing.resolve(call.parameters["token"]!!)) {
                is Sharing.Resolved.Ok -> call.respond(r.s)
                Sharing.Resolved.Gone -> call.respond(HttpStatusCode.Gone, ApiError("revoked", "This link was revoked by its owner or has expired", call.callId))
                Sharing.Resolved.Missing -> call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such link", call.callId))
            }
        }
        delete("/shares/{token}") {
            val ok = sharing.revoke(call.parameters["token"]!!, call.request.headers["X-Owner-Secret"].orEmpty())
            if (ok) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Unknown link or wrong owner secret", call.callId))
        }
    }

    // Lightweight web fallback for people without the app (e.g. a relative opening a WhatsApp link).
    get("/s/{token}") {
        call.response.header("X-Robots-Tag", "noindex, nofollow")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val r = sharing.resolve(call.parameters["token"]!!)
        val ar = (call.request.queryParameters["lang"] ?: (r as? Sharing.Resolved.Ok)?.lang ?: "ar") == "ar"
        when (r) {
            is Sharing.Resolved.Ok -> call.respondHtml { sharePage(r.s, ar) }
            else -> call.respondHtml(if (r is Sharing.Resolved.Gone) HttpStatusCode.Gone else HttpStatusCode.NotFound) {
                head { meta(charset = "utf-8"); meta(name = "viewport", content = "width=device-width, initial-scale=1"); title(if (ar) "الرابط غير متاح" else "Link unavailable") }
                body { attributes["dir"] = if (ar) "rtl" else "ltr"; style = "font-family:system-ui;padding:24px"
                    h1 { +(if (ar) "هذا الرابط لم يعد متاحًا" else "This link is no longer available") }
                    p { +(if (ar) "ألغاه صاحبه أو انتهت صلاحيته." else "Its owner revoked it or it expired.") } }
            }
        }
    }
}

private fun HTML.sharePage(s: SharedComparison, ar: Boolean) {
    val cmp = s.compare
    fun m(v: Long?, cur: String) = v?.let { MoneyFormat.format(Money(it, cur), ar) } ?: "—"
    head {
        meta(charset = "utf-8"); meta(name = "viewport", content = "width=device-width, initial-scale=1")
        // Preview text is generic on purpose: no prices, notes or addresses in link previews.
        title(if (ar) "مقارنة ${cmp.cards.size} وحدات · واضح" else "Comparing ${cmp.cards.size} homes · Wadeh")
        meta(name = "description", content = if (ar) "مقارنة للقراءة فقط" else "Read-only comparison")
        style { unsafe { +"body{margin:0;font:15px/1.6 system-ui,'Noto Sans Arabic',sans-serif;background:#f6f7f4;color:#10231a}main{max-width:900px;margin:auto;padding:16px}table{width:100%;border-collapse:collapse;background:#fff}th,td{padding:8px;border-bottom:1px solid #dce3de;text-align:start;vertical-align:top}th{background:#eef2ef}.low{color:#0b5d3f;font-weight:700}.warn{color:#7a4b00}.tag{background:#fff1d6;color:#7a4b00;border-radius:4px;padding:1px 6px;font-size:12px}" } }
    }
    body {
        attributes["dir"] = if (ar) "rtl" else "ltr"
        main {
            h1 { +(if (ar) "مقارنة للقراءة فقط" else "Read-only comparison") }
            p { +(if (ar) "مشاركة من مستخدم «واضح». لا تتضمن ملاحظاته الخاصة أو ميزانيته." else "Shared by a Wadeh user. Their private notes and budget are not included.") }
            if (s.missing > 0) p("warn") { +(if (ar) "${s.missing} من الوحدات لم تعد منشورة." else "${s.missing} of these homes are no longer published.") }
            table {
                thead { tr { th { }; cmp.cards.forEach { c -> th { +c.project.pick(ar); br; +(c.unitCode ?: ""); if (c.isTestData) { br; span("tag") { +(if (ar) "بيانات اصطناعية" else "synthetic") } } } } } }
                tbody {
                    fun row(label: String, key: String?, f: (Int) -> String) = tr { th { +label }; cmp.cards.indices.forEach { i -> td(if (key != null && cmp.comparison.lowest[key] == cmp.cards[i].id) "low" else null) { +f(i) } } }
                    row(if (ar) "السعر" else "Price", null) { i -> m(cmp.cards[i].priceMinor, cmp.cards[i].currency) }
                    row(if (ar) "المطلوب للبدء" else "Cash to start", "cash_to_start") { i -> m(cmp.comparison.rows[i].cashToStartMinor, cmp.cards[i].currency) }
                    row(if (ar) "أول ١٢ شهر" else "First 12 months", "first_12_months") { i -> m(cmp.comparison.rows[i].first12MonthsMinor, cmp.cards[i].currency) }
                    row(if (ar) "إجمالي إلزامي معروف" else "Known mandatory total", "known_mandatory") { i ->
                        val r = cmp.comparison.rows[i]; m(r.knownMandatoryMinor, cmp.cards[i].currency) + if (!r.exactTotalKnown) (if (ar) " + رسوم غير معروفة" else " + unknown fee") else "" }
                    row(if (ar) "سعر المتر" else "Price per m²", "price_per_m2") { i -> cmp.comparison.rows[i].pricePerM2Minor?.let { m(it, cmp.cards[i].currency) } ?: (if (ar) "غير قابل للمقارنة" else "not comparable") }
                    row(if (ar) "الاستلام" else "Handover", null) { i -> cmp.cards[i].handover ?: "—" }
                    row(if (ar) "المصدر" else "Source", null) { i -> cmp.cards[i].evidence.label.pick(ar) }
                }
            }
            cmp.comparison.suppressed.values.forEach { p("warn") { +it } }
            p { a("?lang=${if (ar) "en" else "ar"}") { +(if (ar) "English" else "العربية") } }
        }
    }
}
