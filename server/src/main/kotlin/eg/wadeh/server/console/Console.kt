package eg.wadeh.server.console

import eg.wadeh.core.Money
import eg.wadeh.core.MoneyFormat
import eg.wadeh.server.*
import eg.wadeh.server.imports.BatchSummary
import eg.wadeh.server.imports.FieldDiff
import eg.wadeh.server.imports.ImportException
import eg.wadeh.server.imports.TemplateColumns
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.html.*
import java.util.UUID

private const val COOKIE = "wadeh_session"

private val CSS = """
:root{--ink:#14231c;--muted:#5b6b63;--line:#d9e2dc;--bg:#f6f8f6;--card:#fff;--brand:#0f5c45;--warn:#8a5a00;--warnbg:#fff4dc;--bad:#a32020;--badbg:#fde8e8;--ok:#0f5c45;--okbg:#e3f4ec}
*{box-sizing:border-box}body{margin:0;font:15px/1.5 system-ui,-apple-system,"Segoe UI","Noto Sans Arabic",sans-serif;color:var(--ink);background:var(--bg)}
header{background:var(--brand);color:#fff;padding:10px 20px;display:flex;gap:16px;align-items:center;flex-wrap:wrap}header a{color:#fff;text-decoration:none;font-weight:600}
header .env{background:#ffd166;color:#14231c;border-radius:4px;padding:2px 8px;font-size:12px;font-weight:700}
main{max-width:1200px;margin:0 auto;padding:20px}h1{font-size:22px;margin:4px 0 16px}h2{font-size:17px;margin:24px 0 8px}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:16px;margin-bottom:16px}
table{border-collapse:collapse;width:100%;background:#fff}th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:start;vertical-align:top;font-size:14px}th{background:#eef3ef;position:sticky;top:0}
.pill{display:inline-block;border-radius:999px;padding:1px 8px;font-size:12px;font-weight:600;background:#eef3ef}
.a-add,.a-revise{background:var(--okbg);color:var(--ok)}.a-invalid,.a-conflict{background:var(--badbg);color:var(--bad)}.a-stale,.a-withdraw{background:var(--warnbg);color:var(--warn)}
.err{color:var(--bad)}.warn{color:var(--warn)}.muted{color:var(--muted)}
.stats{display:flex;gap:8px;flex-wrap:wrap}.stat{background:#fff;border:1px solid var(--line);border-radius:8px;padding:8px 12px;min-width:90px}.stat b{display:block;font-size:20px}
button,.btn{background:var(--brand);color:#fff;border:0;border-radius:6px;padding:8px 14px;font-weight:600;cursor:pointer;text-decoration:none;display:inline-block}
.btn.secondary,button.secondary{background:#fff;color:var(--brand);border:1px solid var(--brand)}button.danger{background:var(--bad)}
input,select,textarea{font:inherit;padding:6px 8px;border:1px solid var(--line);border-radius:6px}label{display:block;margin:8px 0 4px;font-weight:600}
.flash{padding:10px 14px;border-radius:6px;margin-bottom:12px}.flash.bad{background:var(--badbg);color:var(--bad)}.flash.ok{background:var(--okbg);color:var(--ok)}
code{background:#eef3ef;padding:1px 4px;border-radius:4px}
"""

private suspend fun ApplicationCall.principal(app: App): Principal? = app.sessions.principal(request.cookies[COOKIE])

/** Same-origin check for state-changing requests (in addition to SameSite=Strict cookies). */
private fun ApplicationCall.sameOrigin(): Boolean {
    val origin = request.headers[HttpHeaders.Origin] ?: request.headers[HttpHeaders.Referrer] ?: return true
    val host = request.headers[HttpHeaders.Host] ?: return false
    return Url(origin).let { "${it.host}${if (it.specifiedPort != 0 && it.specifiedPort != it.protocol.defaultPort) ":" + it.specifiedPort else ""}" } == host
}

private fun HTML.page(app: App, title: String, who: Principal?, flash: Pair<String, String>? = null, body: MAIN.() -> Unit) {
    head {
        meta(charset = "utf-8"); meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("$title · Wadeh console"); style { unsafe { +CSS } }
    }
    body {
        header {
            a("/console") { +"Wadeh · واضح — Operations" }
            if (app.cfg.env != "prod") span("env") { +app.cfg.env.uppercase() }
            if (who != null) {
                a("/console/imports/new") { +"New import" }
                a("/console/template.csv") { +"Template" }
                a("/console/inbox") { +"Viewing requests" }
                span { style = "margin-inline-start:auto"; +"${who.displayName} (${who.platformRole ?: who.memberships.map { it.role }.distinct().joinToString()})" }
                form(action = "/console/logout", method = FormMethod.post) { style = "margin:0"; button(classes = "secondary") { +"Sign out" } }
            }
        }
        main {
            flash?.let { (kind, msg) -> div("flash $kind") { +msg } }
            body()
        }
    }
}

fun Route.consoleRoutes(app: App) {
    route("/console") {
        get("/login") {
            call.respondHtml { page(app, "Sign in", null, call.parameters["e"]?.let { "bad" to "Email or password is incorrect." }) {
                h1 { +"Sign in" }
                form(action = "/console/login", method = FormMethod.post, classes = "card") {
                    label { htmlFor = "email"; +"Email" }; input(InputType.email, name = "email") { id = "email"; required = true; attributes["autocomplete"] = "username" }
                    label { htmlFor = "password"; +"Password" }; input(InputType.password, name = "password") { id = "password"; required = true; attributes["autocomplete"] = "current-password" }
                    p { button(type = ButtonType.submit) { +"Sign in" } }
                    p("muted") { +"Privileged accounts will require a second factor before production (see release register)." }
                }
            } }
        }
        post("/login") {
            if (!call.sameOrigin()) return@post call.respond(HttpStatusCode.Forbidden)
            val f = call.receiveParameters()
            val token = app.sessions.login(f["email"].orEmpty(), f["password"].orEmpty()) ?: return@post call.respondRedirect("/console/login?e=1")
            call.response.cookies.append(Cookie(COOKIE, token, httpOnly = true, secure = app.cfg.env == "prod", path = "/console", extensions = mapOf("SameSite" to "Strict"), maxAge = 12 * 3600))
            call.respondRedirect("/console")
        }
        post("/logout") {
            call.request.cookies[COOKIE]?.let { app.sessions.logout(it) }
            call.response.cookies.append(Cookie(COOKIE, "", path = "/console", maxAge = 0))
            call.respondRedirect("/console/login")
        }
        get("/template.csv") {
            val header = TemplateColumns.templateHeader().joinToString(",") { "\"$it\"" }
            val sample = listOf("TST-GARDENS", "P1", "B12", "B12-0304", "", "شقة", "٣", "2", "165", "إجمالي", "3", "نصف تشطيب", "متاح", "بيع", "EGP",
                "", "8,000,000", "", "100000", "نعم", "800000", "2027-01-01", "300000", "ربع سنوي", "24", "2027-04-01", "400000", "2030-01-01", "120000", "2030-01-01", "2029-Q4", "2026-11-03", "", "", "")
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=wadeh-inventory-template.csv")
            call.respondText("﻿" + header + "\n" + sample.joinToString(",") { "\"$it\"" } + "\n", ContentType.Text.CSV.withCharset(Charsets.UTF_8))
        }

        get {
            val who = call.principal(app) ?: return@get call.respondRedirect("/console/login")
            val orgs = app.db.read { list("SELECT id, name_en, name_ar, is_test FROM organization ORDER BY name_en") { Triple(it.uuid("id"), it.getString("name_en") + " · " + it.getString("name_ar"), it.getBoolean("is_test")) } }
                .filter { who.canSee(it.first) }
            val visible = orgs.map { it.first }
            val batches = app.db.read {
                list("""SELECT b.id, b.org_id, b.filename, b.mode, b.status, b.created_at, b.summary::text s, u.display_name up, r.display_name rv
                        FROM import_batch b LEFT JOIN app_user u ON u.id=b.uploaded_by LEFT JOIN app_user r ON r.id=b.reviewed_by ORDER BY b.created_at DESC LIMIT 50""") {
                    arrayOf<Any?>(it.uuid("id"), it.uuid("org_id"), it.getString("filename"), it.getString("mode"), it.getString("status"), it.instant("created_at"), it.getString("s"), it.getString("up"), it.getString("rv"))
                }
            }.filter { (it[1] as UUID) in visible }
            val health = app.db.read {
                list("""SELECT o.name_en, count(l.id) FILTER (WHERE l.publication='published') active,
                               count(l.id) FILTER (WHERE l.publication='published' AND (l.last_confirmed_at IS NULL OR l.last_confirmed_at < now() - make_interval(days => o.refresh_target_days))) stale,
                               min(l.last_confirmed_at) FILTER (WHERE l.publication='published') oldest, o.id
                        FROM organization o LEFT JOIN listing l ON l.seller_org_id=o.id GROUP BY o.id, o.name_en ORDER BY o.name_en""") {
                    arrayOf<Any?>(it.getString(1), it.getInt(2), it.getInt(3), it.instantOrNull("oldest"), it.uuid("id"))
                }
            }.filter { (it[4] as UUID) in visible }
            val conflicts = app.db.read { one("SELECT count(*) FROM source_record s JOIN import_batch b ON b.id=s.batch_id WHERE s.action='conflict' AND b.status IN ('staged','published')") { it.getInt(1) } } ?: 0
            call.respondHtml { page(app, "Queue", who) {
                h1 { +"Today's queue" }
                val waiting = batches.filter { it[4] == "staged" }
                div("stats") {
                    div("stat") { b { +"${waiting.size}" }; +"batches awaiting review" }
                    div("stat") { b { +"${health.sumOf { it[2] as Int }}" }; +"offers past refresh target" }
                    div("stat") { b { +"$conflicts" }; +"unit conflicts held" }
                    div("stat") { b { +"${health.sumOf { it[1] as Int }}" }; +"published offers" }
                }
                h2 { +"Supplier feed health" }
                table { thead { tr { th { +"Supplier" }; th { +"Published offers" }; th { +"Past refresh target" }; th { +"Oldest confirmation" } } }
                    tbody { health.forEach { h -> tr { td { +(h[0] as String) }; td { +"${h[1]}" }; td(if ((h[2] as Int) > 0) "warn" else null) { +"${h[2]}" }; td { +(h[3]?.toString()?.take(10) ?: "—") } } } } }
                h2 { +"Import batches" }
                table {
                    thead { tr { th { +"Created" }; th { +"File" }; th { +"Mode" }; th { +"Status" }; th { +"Rows" }; th { +"Uploaded by" }; th { +"Reviewed by" }; th { +"Next action" } } }
                    tbody { batches.forEach { b ->
                        val s = apiJson.decodeFromString(BatchSummary.serializer(), b[6] as String)
                        tr {
                            td { +(b[5].toString().take(16).replace('T', ' ')) }; td { a("/console/imports/${b[0]}") { +(b[2] as String? ?: "—") } }
                            td { +(b[3] as String) }; td { span("pill") { +(b[4] as String) } }
                            td { +"${s.rows} (${s.invalid} invalid)" }; td { +(b[7] as String? ?: "") }; td { +(b[8] as String? ?: "") }
                            td { +when (b[4]) { "staged" -> "Reviewer: publish or reject"; "published" -> "—"; else -> "—" } }
                        }
                    } }
                }
            } }
        }

        get("/inbox") {
            val who = call.principal(app) ?: return@get call.respondRedirect("/console/login")
            val rows = app.inquiries.inbox(who)
            call.respondHtml { page(app, "Viewing requests", who, call.parameters["err"]?.let { "bad" to it } ?: call.parameters["ok"]?.let { "ok" to it }) {
                h1 { +"Viewing requests" }
                p("muted") { +"Only requests addressed to your organisation are shown. Contact details are shared because the buyer consented (viewing-consent-1) and are erased after the retention period." }
                table {
                    thead { tr { th { +"Received" }; th { +"Unit" }; th { +"Requested slot (Cairo)" }; th { +"Buyer" }; th { +"Channel" }; th { +"Status" }; th { +"Next action" } } }
                    tbody { rows.forEach { r ->
                        tr {
                            td { +r["created"].toString().take(16).replace('T', ' ') }; td { +"${r["project"]} ${r["unit"]}" }
                            td { +java.time.ZonedDateTime.ofInstant(r["slot"] as java.time.Instant, java.time.ZoneId.of("Africa/Cairo")).toString().take(16).replace('T', ' ') }
                            td { attributes["dir"] = "auto"; +"${r["name"]} · ${r["phone"]}" }; td { +r["channel"].toString() }; td { span("pill") { +r["status"].toString() } }
                            td {
                                val id = r["id"]
                                val next = app.inquiries.nextActions(r["status"].toString())
                                if (next.isEmpty()) span("muted") { +"No action needed" }
                                next.filter { it != "propose" }.forEach { act ->
                                    form(action = "/console/inbox/$id/$act", method = FormMethod.post) { style = "display:inline"; button(classes = "secondary") { +act } }
                                }
                                if ("propose" in next) form(action = "/console/inbox/$id/propose", method = FormMethod.post) { style = "display:inline"
                                    input(InputType.dateTimeLocal, name = "when") { required = true }; button(classes = "secondary") { +"propose time" } }
                            }
                        }
                    } }
                }
            } }
        }
        post("/inbox/{id}/{action}") {
            val who = call.principal(app) ?: return@post call.respondRedirect("/console/login")
            if (!call.sameOrigin()) return@post call.respond(HttpStatusCode.Forbidden)
            val f = call.receiveParameters()
            val proposed = f["when"]?.takeIf { it.isNotBlank() }?.let { java.time.LocalDateTime.parse(it).atZone(java.time.ZoneId.of("Africa/Cairo")).toInstant() }
            try { app.inquiries.partnerAction(who, UUID.fromString(call.parameters["id"]), call.parameters["action"]!!, proposed); call.respondRedirect("/console/inbox?ok=Updated") }
            catch (e: Exception) { call.respondRedirect("/console/inbox?err=" + (e.message ?: "Failed").encodeURLParameter()) }
        }

        get("/imports/new") {
            val who = call.principal(app) ?: return@get call.respondRedirect("/console/login")
            val orgs = app.db.read { list("SELECT id, name_en, name_ar FROM organization ORDER BY name_en") { it.uuid("id") to (it.getString("name_en") + " · " + it.getString("name_ar")) } }.filter { who.canUploadFor(it.first) }
            call.respondHtml { page(app, "New import", who, call.parameters["err"]?.let { "bad" to it }) {
                h1 { +"Upload inventory" }
                if (orgs.isEmpty()) { p("err") { +"Your account cannot upload for any organisation." }; return@page }
                form(action = "/console/imports", method = FormMethod.post, encType = FormEncType.multipartFormData, classes = "card") {
                    label { htmlFor = "org"; +"Supplier" }
                    select { name = "org"; id = "org"; orgs.forEach { option { value = it.first.toString(); +it.second } } }
                    label { htmlFor = "mode"; +"Import mode" }
                    select { name = "mode"; id = "mode"
                        option { value = "partial"; +"Partial update — rows not in the file are left unchanged" }
                        option { value = "full_snapshot"; +"Full snapshot — offers missing from the file will be withdrawn" } }
                    label { htmlFor = "file"; +"CSV (UTF-8) or XLSX" }
                    input(InputType.file, name = "file") { id = "file"; required = true; accept = ".csv,.xlsx,text/csv" }
                    p("muted") { +"Arabic or English headers, Arabic-Indic digits and amounts like \"٨ مليون\" are accepted. Every row gets a remediation note. Nothing is public until a reviewer publishes it." }
                    p { button(type = ButtonType.submit) { +"Upload and preview" }; +" "; a("/console/template.csv", classes = "btn secondary") { +"Download template" } }
                }
            } }
        }

        post("/imports") {
            val who = call.principal(app) ?: return@post call.respondRedirect("/console/login")
            if (!call.sameOrigin()) return@post call.respond(HttpStatusCode.Forbidden)
            var org: UUID? = null; var mode = "partial"; var name = "upload.csv"; var bytes: ByteArray? = null
            call.receiveMultipart(formFieldLimit = 20L * 1024 * 1024).forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> when (part.name) { "org" -> org = runCatching { UUID.fromString(part.value) }.getOrNull(); "mode" -> mode = part.value }
                    is PartData.FileItem -> { name = part.originalFileName ?: name; bytes = part.provider().toByteArray() }
                    else -> {}
                }
                part.dispose()
            }
            val o = org ?: return@post call.respondRedirect("/console/imports/new?err=Choose+a+supplier")
            val b = bytes ?: return@post call.respondRedirect("/console/imports/new?err=Choose+a+file")
            try {
                val r = app.imports.stage(o, who, name, b, mode)
                call.respondRedirect("/console/imports/${r.batchId}" + if (r.duplicateOf != null) "?dup=1" else "")
            } catch (e: ImportException) {
                call.respondRedirect("/console/imports/new?err=" + e.message.orEmpty().encodeURLParameter())
            }
        }

        get("/imports/{id}") {
            val who = call.principal(app) ?: return@get call.respondRedirect("/console/login")
            val id = UUID.fromString(call.parameters["id"])
            val b = app.db.read { one("""SELECT b.*, b.summary::text s, o.name_en org, u.display_name up, r.display_name rv FROM import_batch b JOIN organization o ON o.id=b.org_id
                                        LEFT JOIN app_user u ON u.id=b.uploaded_by LEFT JOIN app_user r ON r.id=b.reviewed_by WHERE b.id=?""", id) {
                mapOf("org_id" to it.uuid("org_id"), "status" to it.getString("status"), "mode" to it.getString("mode"), "filename" to it.getString("filename"), "s" to it.getString("s"),
                    "org" to it.getString("org"), "up" to it.getString("up"), "rv" to it.getString("rv"), "sha" to it.getString("file_sha256"), "uploaded_by" to it.uuidOrNull("uploaded_by"), "note" to it.getString("review_note"))
            } } ?: return@get call.respond(HttpStatusCode.NotFound)
            val orgId = b["org_id"] as UUID
            if (!who.canSee(orgId)) return@get call.respond(HttpStatusCode.Forbidden)
            val s = apiJson.decodeFromString(BatchSummary.serializer(), b["s"] as String)
            val rows = app.db.read { list("SELECT row_no, action, raw::text raw, errors::text e, warnings::text w, diff::text d FROM source_record WHERE batch_id=? ORDER BY row_no", id) {
                arrayOf(it.getInt("row_no").toString(), it.getString("action"), it.getString("raw"), it.getString("e"), it.getString("w"), it.getString("d")) } }
            val flash = when {
                call.parameters["dup"] != null -> "ok" to "This exact file (same content) was already uploaded. Showing the existing batch; nothing was duplicated."
                call.parameters["err"] != null -> "bad" to call.parameters["err"]!!
                call.parameters["ok"] != null -> "ok" to call.parameters["ok"]!!
                else -> null
            }
            call.respondHtml { page(app, "Batch", who, flash) {
                h1 { +"Import: ${b["filename"]} " ; span("pill") { +(b["status"] as String) } }
                p("muted") { +"Supplier: ${b["org"]} · mode: ${b["mode"]} · uploaded by ${b["up"]}${b["rv"]?.let { " · reviewed by $it" } ?: ""} · sha256 ${(b["sha"] as String).take(12)}…" }
                div("stats") {
                    listOf("rows" to s.rows, "invalid" to s.invalid, "add" to s.add, "revise" to s.revise, "confirm" to s.confirm, "unchanged" to s.unchanged,
                        "stale" to s.stale, "conflict" to s.conflict, "withdraw" to s.withdraw, "suspicious" to s.suspicious, "change events" to s.changeEvents)
                        .forEach { (k, v) -> div("stat") { b { +"$v" }; +k } }
                }
                if (s.unknownHeaders.isNotEmpty()) p("warn") { +"Ignored columns: ${s.unknownHeaders.joinToString()}" }
                p { a("/console/imports/$id/report.csv", classes = "btn secondary") { +"Download row-by-row report" } }
                when (b["status"]) {
                    "staged" -> if (who.canReviewFor(orgId) && b["uploaded_by"] != who.userId) div("card") {
                        h2 { +"Review" }
                        form(action = "/console/imports/$id/publish", method = FormMethod.post) {
                            if (s.suspicious > 0) label { checkBoxInput(name = "ack") { value = "yes" }; +" I reviewed the ${s.suspicious} rows flagged as suspicious" }
                            label { htmlFor = "note"; +"Review note (optional)" }; textArea(rows = "2", cols = "60") { name = "note"; attributes["id"] = "note" }
                            p { button(type = ButtonType.submit) { +"Publish ${s.valid - s.unchanged} changes" } }
                        }
                        form(action = "/console/imports/$id/reject", method = FormMethod.post) {
                            input(InputType.text, name = "note") { placeholder = "Reason for rejection"; required = true }; +" "; button(classes = "danger") { +"Reject" }
                        }
                    } else p("muted") { +"Waiting for a reviewer from another account. The uploader cannot publish their own batch." }
                    "published" -> if (who.canReviewFor(orgId)) div("card") {
                        h2 { +"Reverse this import" }
                        form(action = "/console/imports/$id/revert", method = FormMethod.post) {
                            input(InputType.text, name = "reason") { placeholder = "Why is this import being reversed?"; required = true; size = "50" }; +" "; button(classes = "danger") { +"Revert batch" }
                        }
                    }
                }
                h2 { +"Rows" }
                table {
                    thead { tr { th { +"Row" }; th { +"Action" }; th { +"Unit / listing" }; th { +"Changes" }; th { +"Problems" } } }
                    tbody { rows.forEach { r ->
                        val raw = runCatching { apiJson.decodeFromString<Map<String, String>>(r[2]) }.getOrDefault(emptyMap())
                        val diff = apiJson.decodeFromString<List<FieldDiff>>(r[5]); val errs = apiJson.decodeFromString<List<String>>(r[3]); val warns = apiJson.decodeFromString<List<String>>(r[4])
                        tr {
                            td { +r[0] }; td { span("pill a-${r[1]}") { +r[1] } }
                            td { attributes["dir"] = "auto"; +"${raw["project_code"] ?: ""} ${raw["unit_code"] ?: raw["listing_id"] ?: ""}" }
                            td { diff.forEach { d -> div { +"${d.field}: ${fmt(d.field, d.old)} → ${fmt(d.field, d.new)}" } } }
                            td { errs.forEach { div("err") { +it } }; warns.forEach { div("warn") { +it } } }
                        }
                    } }
                }
            } }
        }

        get("/imports/{id}/report.csv") {
            val who = call.principal(app) ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val id = UUID.fromString(call.parameters["id"])
            val org = app.db.read { one("SELECT org_id FROM import_batch WHERE id=?", id) { it.uuid("org_id") } } ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!who.canSee(org)) return@get call.respond(HttpStatusCode.Forbidden)
            val rows = app.db.read { list("SELECT row_no, action, errors::text e, warnings::text w, diff::text d FROM source_record WHERE batch_id=? ORDER BY row_no", id) {
                listOf(it.getInt(1).toString(), it.getString(2), apiJson.decodeFromString<List<String>>(it.getString(3)).joinToString(" | "),
                    apiJson.decodeFromString<List<String>>(it.getString(4)).joinToString(" | "),
                    apiJson.decodeFromString<List<FieldDiff>>(it.getString(5)).joinToString(" | ") { d -> "${d.field}: ${d.old} -> ${d.new}" }) } }
            val csv = "﻿row,action,errors_to_fix,warnings,changes\n" + rows.joinToString("\n") { r -> r.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } }
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=import-$id-report.csv")
            call.respondText(csv, ContentType.Text.CSV.withCharset(Charsets.UTF_8))
        }

        post("/imports/{id}/publish") {
            val who = call.principal(app) ?: return@post call.respondRedirect("/console/login")
            if (!call.sameOrigin()) return@post call.respond(HttpStatusCode.Forbidden)
            val id = UUID.fromString(call.parameters["id"]); val f = call.receiveParameters()
            try {
                val s = app.imports.publish(id, who, f["ack"] == "yes", f["note"])
                call.respondRedirect("/console/imports/$id?ok=" + "Published. ${s.changeEvents} consumer-visible changes.".encodeURLParameter())
            } catch (e: ImportException) { call.respondRedirect("/console/imports/$id?err=" + e.message.orEmpty().encodeURLParameter()) }
        }
        post("/imports/{id}/reject") {
            val who = call.principal(app) ?: return@post call.respondRedirect("/console/login")
            if (!call.sameOrigin()) return@post call.respond(HttpStatusCode.Forbidden)
            val id = UUID.fromString(call.parameters["id"]); val f = call.receiveParameters()
            try { app.imports.reject(id, who, f["note"].orEmpty()); call.respondRedirect("/console/imports/$id?ok=Rejected") }
            catch (e: ImportException) { call.respondRedirect("/console/imports/$id?err=" + e.message.orEmpty().encodeURLParameter()) }
        }
        post("/imports/{id}/revert") {
            val who = call.principal(app) ?: return@post call.respondRedirect("/console/login")
            if (!call.sameOrigin()) return@post call.respond(HttpStatusCode.Forbidden)
            val id = UUID.fromString(call.parameters["id"]); val f = call.receiveParameters()
            try { val n = app.imports.revert(id, who, f["reason"].orEmpty().ifBlank { "no reason given" }); call.respondRedirect("/console/imports/$id?ok=" + "Reverted $n offers.".encodeURLParameter()) }
            catch (e: ImportException) { call.respondRedirect("/console/imports/$id?err=" + e.message.orEmpty().encodeURLParameter()) }
        }
    }
}

private fun fmt(field: String, v: String?): String = when {
    v == null -> "—"
    field in setOf("list_price", "cash_price", "monthly_rent") -> v.toLongOrNull()?.let { MoneyFormat.format(Money(it, "EGP"), false) } ?: v
    else -> v
}
