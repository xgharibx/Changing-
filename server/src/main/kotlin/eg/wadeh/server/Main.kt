package eg.wadeh.server

import eg.wadeh.core.CashFlowEngine
import eg.wadeh.core.PaymentPlan
import eg.wadeh.core.api.ApiError
import eg.wadeh.core.api.Health
import eg.wadeh.server.console.consoleRoutes
import eg.wadeh.server.imports.ImportException
import eg.wadeh.server.imports.ImportService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Instant
import java.util.UUID

data class Config(val databaseUrl: String, val port: Int, val env: String, val version: String, val publicBase: String = "http://localhost:8080") {
    companion object {
        fun fromEnv() = Config(
            databaseUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/wadeh_dev?user=wadeh&password=wadeh_dev",
            port = System.getenv("PORT")?.toInt() ?: 8080,
            env = System.getenv("APP_ENV") ?: "dev",
            version = System.getenv("APP_VERSION") ?: "0.1.0-dev",
            publicBase = System.getenv("PUBLIC_BASE_URL") ?: "http://localhost:8080",
        )
    }
}

class App(val cfg: Config, val db: Db, val migration: String?) {
    val catalog = Catalog(db, cfg.env)
    val imports = ImportService(db)
    val sessions = Sessions(db)
    val sharing = Sharing(db, catalog, cfg.publicBase)
    val router = eg.wadeh.server.routing.Router(db)
    // Lead handoff stays OFF in production until the PDPC licence decision (release register L-01) sets LEADS_ENABLED=true.
    val leadsEnabled = (System.getenv("LEADS_ENABLED") ?: if (cfg.env == "prod") "false" else "true") == "true"
    val inquiries = Inquiries(db, catalog, leadsEnabled)
}

val apiJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true; classDiscriminator = "type" }

fun main(args: Array<String>) {
    val cfg = Config.fromEnv()
    val db = Db.connect(cfg.databaseUrl)
    val migration = Db.migrate(db.ds)
    when (args.firstOrNull()) {
        "migrate" -> { println("Migrated to $migration"); return }
        "seed" -> { Seed.run(db, cfg.env); println("Seeded (${cfg.env})"); return }
        "load-osm" -> { val r = eg.wadeh.server.routing.OsmLoader.load(db, java.io.File(args[1]).inputStream(), args[2]); println("Loaded ${r.nodes} nodes, ${r.edges} edges, OSM base ${r.osmBase}"); return }
    }
    if (cfg.env != "prod" && System.getenv("SEED_ON_START") == "1") Seed.run(db, cfg.env)
    val app = App(cfg, db, migration)
    embeddedServer(Netty, port = cfg.port, host = "0.0.0.0") { module(app) }.start(wait = true)
}

@Serializable data class CashflowRequest(val plan: PaymentPlan)
@Serializable data class ChangesRequest(val ids: List<String>, val since: String? = null)

fun Application.module(app: App) {
    install(ContentNegotiation) { json(apiJson) }
    install(CallId) {
        retrieveFromHeader("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.length in 8..64 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
        replyToHeader("X-Request-Id")
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("cid")
        // Never log query strings: they can contain user search text.
        format { c -> "${c.request.httpMethod.value} ${c.request.path()} -> ${c.response.status()?.value} cid=${c.callId}" }
    }
    install(StatusPages) {
        exception<ImportException> { call, e ->
            call.respond(if (e.code == "forbidden") HttpStatusCode.Forbidden else HttpStatusCode.UnprocessableEntity, ApiError(e.code, e.message ?: "", call.callId))
        }
        exception<InquiryException> { call, e ->
            when (e.code) {
                "offer_changed" -> call.respond(HttpStatusCode.Conflict, e.body as eg.wadeh.core.api.OfferChanged)
                "leads_disabled" -> call.respond(HttpStatusCode.ServiceUnavailable, ApiError(e.code, e.message ?: "", call.callId))
                "forbidden" -> call.respond(HttpStatusCode.Forbidden, ApiError(e.code, e.message ?: "", call.callId))
                "not_found" -> call.respond(HttpStatusCode.NotFound, ApiError(e.code, e.message ?: "", call.callId))
                else -> call.respond(HttpStatusCode.Conflict, ApiError(e.code, e.message ?: "", call.callId))
            }
        }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", e.message ?: "Bad request", call.callId)) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled cid=${call.callId}", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", "Something went wrong. Reference: ${call.callId}", call.callId))
        }
    }
    routing {
        get("/") { call.respondRedirect("/console") }
        staticResources("/static", "static")
        route("/v1") { publicApi(app) }
        consoleRoutes(app)
        sharingRoutes(app, app.sharing)
    }
}

private fun Parameters.major(name: String): Long? = this[name]?.toBigDecimalOrNull()?.let { eg.wadeh.core.Money.ofMajor(it, this["currency"] ?: "EGP").minor }

fun Route.publicApi(app: App) {
    get("/health") {
        val dbOk = runCatching { app.db.read { one("SELECT 1") { it.getInt(1) } } }.isSuccess
        call.respond(if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            Health(if (dbOk) "ok" else "degraded", app.cfg.env, app.cfg.version, app.migration, Instant.now().toString()))
    }
    get("/listings") {
        val p = call.request.queryParameters
        val q = ListingQuery(
            q = p["q"]?.take(120), offerType = p["offerType"], minBedrooms = p["minBedrooms"]?.toIntOrNull(),
            maxPriceMinor = p.major("maxPrice"), maxCashToStartMinor = p.major("maxCashToStart"), unitType = p["unitType"],
            availableOnly = p["available"] == "true", projectId = p["projectId"]?.let(UUID::fromString),
            bbox = p["bbox"]?.split(',')?.mapNotNull { it.toDoubleOrNull() }, sort = p["sort"] ?: "recent", cursor = p["cursor"],
            limit = p["limit"]?.toIntOrNull() ?: 20, ids = p["ids"]?.split(',')?.filter { it.isNotBlank() }?.map(UUID::fromString),
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(app.catalog.search(q))
    }
    get("/listings/{id}") {
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such listing", call.callId))
        val d = app.catalog.detail(id) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "This offer is no longer published", call.callId))
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(d)
    }
    post("/changes") {
        val r = call.receive<ChangesRequest>()
        require(r.ids.size <= 100) { "At most 100 ids" }
        call.respond(app.catalog.changesFor(r.ids.map(UUID::fromString), r.since?.let(Instant::parse)))
    }
    get("/route") {
        val p = call.request.queryParameters
        val lng = p["fromLng"]?.toDoubleOrNull(); val lat = p["fromLat"]?.toDoubleOrNull()
        require(lng != null && lat != null) { "fromLng and fromLat are required" }
        val mode = p["mode"] ?: "car"
        val candidates = when {
            p["listing"] != null -> app.db.read { one("SELECT u.project_id FROM listing l JOIN unit u ON u.id=l.unit_id WHERE l.id=?", UUID.fromString(p["listing"])) { it.uuid("project_id") } }
                ?.let { app.router.entrances(projectId = it) } ?: emptyList()
            p["place"] != null -> app.router.entrances(placeKey = p["place"])
            else -> throw IllegalArgumentException("listing or place is required")
        }
        // Origins are personal: never logged (CallLogging omits query strings) and never stored.
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(app.router.route(lng, lat, mode, candidates))
    }
    post("/inquiries") { call.respond(HttpStatusCode.Created, app.inquiries.create(call.receive())) }
    get("/inquiries/{id}") {
        val st = app.inquiries.buyerStatus(UUID.fromString(call.parameters["id"]), call.request.headers["X-Buyer-Token"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such request", call.callId))
        call.response.header(HttpHeaders.CacheControl, "no-store"); call.respond(st)
    }
    post("/inquiries/{id}/cancel") {
        app.inquiries.cancelByBuyer(UUID.fromString(call.parameters["id"]), call.request.headers["X-Buyer-Token"].orEmpty()); call.respond(HttpStatusCode.NoContent)
    }
    get("/coverage") { call.respond(app.catalog.coverage()) }
    get("/landmarks") {
        val q = call.request.queryParameters["q"]?.let { "%" + eg.wadeh.core.ArabicText.normalize(it) + "%" } ?: "%"
        call.respond(app.db.read { list("SELECT id,name_en,name_ar,kind,ST_Y(geom) lat,ST_X(geom) lng,source FROM landmark WHERE search_norm LIKE ? ORDER BY name_en LIMIT 20", q) {
            eg.wadeh.core.api.Landmark(it.uuid("id").toString(), eg.wadeh.core.api.L10n(it.getString("name_en"), it.getString("name_ar")), it.getString("kind"), it.getDouble("lat"), it.getDouble("lng"), it.getString("source")) } })
    }
    post("/cashflow") {
        val r = call.receive<CashflowRequest>()
        call.respond(CashFlowEngine.compute(r.plan))
    }
}
