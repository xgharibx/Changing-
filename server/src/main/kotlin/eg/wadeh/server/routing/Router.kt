package eg.wadeh.server.routing

import eg.wadeh.core.api.*
import eg.wadeh.server.*
import java.time.Instant
import java.util.UUID

/** Traffic is a separate, switchable provider. With none connected, routes say so instead of implying current conditions. */
interface TrafficProvider { fun factorFor(edgeIds: List<Long>, at: Instant): Double? }
object NoTraffic : TrafficProvider { override fun factorFor(edgeIds: List<Long>, at: Instant): Double? = null }

class Router(private val db: Db, private val traffic: TrafficProvider = NoTraffic) {
    data class Candidate(val e: EntranceInfo, val node: Long, val modes: List<String>)

    fun entrances(projectId: UUID? = null, ids: List<UUID>? = null, placeKey: String? = null): List<Candidate> = db.read {
        val where = when { projectId != null -> "project_id=?"; ids != null -> "id = ANY(?::uuid[])"; else -> "place_key=?" }
        val arg: Any = projectId ?: ids?.map { it.toString() }?.toTypedArray() ?: placeKey!!
        list("""SELECT id, name_en, name_ar, kind, locked, conditions_en, conditions_ar, source, observed_at, verified_at, node_id, modes::text[] m,
                       ST_Y(geom) lat, ST_X(geom) lng FROM entrance WHERE $where""", arg) {
            Candidate(EntranceInfo(it.uuid("id").toString(), eg.wadeh.core.api.L10n(it.getString("name_en"), it.getString("name_ar")), it.getString("kind"), it.getBoolean("locked"),
                it.getString("conditions_en")?.let { en -> eg.wadeh.core.api.L10n(en, it.getString("conditions_ar") ?: en) }, it.getString("source"),
                it.instantOrNull("observed_at")?.toString(), it.instantOrNull("verified_at")?.toString(), it.getDouble("lat"), it.getDouble("lng")),
                it.getLong("node_id"), (it.getArray("m").array as Array<*>).map { x -> x.toString() })
        }
    }

    private fun costCols(mode: String) = if (mode == "car") "car_cost" to "car_reverse_cost" else "foot_cost" to "foot_reverse_cost"

    fun route(originLng: Double, originLat: Double, mode: String, candidates: List<Candidate>, at: Instant = Instant.now()): RouteResult {
        require(mode in setOf("car", "foot")) { "mode must be car or foot" }
        val (cost, rcost) = costCols(mode)
        val excluded = mutableListOf<String>()
        val usable = candidates.filter { c ->
            val ok = !c.e.locked && mode in c.modes && c.e.kind != "service"
            if (!ok) excluded += "${c.e.name.en}: " + when { c.e.locked -> "locked"; c.e.kind == "service" -> "service/private access"; else -> "not for $mode" }
            ok
        }
        val assumptions = mutableListOf(
            if (mode == "car") eg.wadeh.core.api.L10n("Free-flow estimate from road-class speeds. No live or typical traffic data.", "تقدير بدون زحام حسب نوع الطريق. لا توجد بيانات مرور حيّة أو معتادة.")
            else eg.wadeh.core.api.L10n("Walking at 4.8 km/h on mapped paths. Crossings and pavement quality are not verified.", "مشي بسرعة ٤٫٨ كم/س على المسارات المرسومة. المعابر وجودة الأرصفة غير مُتحقّق منها."),
            eg.wadeh.core.api.L10n("Only roads recorded as existing are used; roads under construction or proposed are excluded.", "نستخدم الطرق القائمة فقط؛ الطرق تحت الإنشاء أو المقترحة مستبعدة."),
        )
        val dataAsOf = db.read { one("SELECT max(observed_at) FROM map_layer WHERE code LIKE 'roads:%'") { it.getTimestamp(1)?.toInstant()?.toString() } }
        val attribution = "© OpenStreetMap contributors (ODbL)"
        // Snap origin to the nearest node that has an edge usable by this mode.
        val snap = db.read { one("""SELECT n.id, ST_Distance(n.geom::geography, ST_SetSRID(ST_MakePoint(?,?),4326)::geography) d FROM road_node n
                                     WHERE EXISTS (SELECT 1 FROM road_edge e WHERE (e.source=n.id AND e.$cost>=0) OR (e.target=n.id AND e.$rcost>=0))
                                     ORDER BY n.geom <-> ST_SetSRID(ST_MakePoint(?,?),4326) LIMIT 1""", originLng, originLat, originLng, originLat) { it.getLong(1) to it.getDouble(2) } }
        fun noRoute(code: String, en: String, ar: String, straight: Int) = RouteResult(mode, usable.firstOrNull()?.e, null, null, snap?.second?.toInt() ?: 0, emptyList(), emptyList(),
            "unavailable", assumptions, listOf(MissingLeg(code, "origin", "entrance", straight, eg.wadeh.core.api.L10n(en, ar))), candidates.size, excluded, dataAsOf, attribution)
        val target = usable.firstOrNull()
        val straight = target?.let { OsmLoader.haversine(originLat, originLng, it.e.lat, it.e.lng).toInt() } ?: 0
        if (usable.isEmpty()) return noRoute("no_usable_entrance", "No verified entrance accepts this mode.", "لا توجد بوابة مُتحقّق منها تسمح بهذه الوسيلة.", 0)
        if (snap == null || snap.second > 400) return noRoute("origin_not_on_graph", "Your starting point is not near a mapped road.", "نقطة البداية ليست قريبة من طريق مرسوم.", straight)

        val edgesSql = "SELECT id, source, target, $cost AS cost, $rcost AS reverse_cost FROM road_edge"
        data class Hop(val endNode: Long, val edge: Long, val agg: Double)
        val hops = db.read {
            list("SELECT end_vid, edge, agg_cost FROM pgr_dijkstra(?, ?, ?::bigint[], directed => true) ORDER BY end_vid, path_seq",
                edgesSql, snap.first, usable.map { it.node.toString() }.toTypedArray()) { Hop(it.getLong(1), it.getLong(2), it.getDouble(3)) }
        }
        val byEnd = hops.groupBy { it.endNode }
        val best = byEnd.minByOrNull { (_, h) -> h.last().agg } ?: run {
            val same = usable.firstOrNull { it.node == snap.first }
            if (same != null) return RouteResult(mode, same.e, 0, 0, snap.second.toInt(), emptyList(), emptyList(), "unavailable", assumptions, emptyList(), candidates.size, excluded, dataAsOf, attribution)
            return noRoute(if (mode == "foot") "no_pedestrian_connection" else "no_road_connection",
                if (mode == "foot") "The map has no walkable connection to any open entrance (for example, no recorded crossing of a divided road). Check on site." else "No legal driving route to an open entrance exists in our road graph.",
                if (mode == "foot") "الخريطة لا تحتوي على وصلة مشي إلى أي بوابة مفتوحة (مثلًا لا يوجد معبر مسجّل على طريق مزدوج). تحقّق في الموقع." else "لا يوجد طريق قيادة قانوني إلى بوابة مفتوحة في شبكة الطرق لدينا.", straight)
        }
        val edgeIds = best.value.map { it.edge }.filter { it > 0 }
        val chosen = usable.first { it.node == best.key }
        val rows = db.read { list("SELECT id, length_m, highway, name_en, name_ar, ST_AsText(geom) g, source, target FROM road_edge WHERE id = ANY(?::bigint[])", edgeIds.map { it.toString() }.toTypedArray()) {
            arrayOf<Any?>(it.getLong("id"), it.getDouble("length_m"), it.getString("highway"), it.getString("name_en"), it.getString("name_ar"), it.getString("g"), it.getLong("source"), it.getLong("target")) } }
            .associateBy { it[0] as Long }
        val ordered = edgeIds.mapNotNull { rows[it] }
        // Path coordinates in travel order.
        val path = mutableListOf<List<Double>>(); var at0 = snap.first
        for (r in ordered) {
            val pts = Regex("(-?[0-9.]+) (-?[0-9.]+)").findAll(r[5] as String).map { listOf(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }.toList()
            val seq = if (r[6] as Long == at0) pts else pts.reversed()
            if (path.isEmpty()) path += seq else path += seq.drop(1)
            at0 = if (r[6] as Long == at0) r[7] as Long else r[6] as Long
        }
        val steps = mutableListOf<RouteStep>()
        for (r in ordered) {
            val name = (r[3] as String?)?.let { eg.wadeh.core.api.L10n(it, (r[4] as String?) ?: it) }
            val last = steps.lastOrNull()
            if (last != null && last.road == name && last.highway == r[2]) steps[steps.size - 1] = last.copy(distanceM = last.distanceM + (r[1] as Double).toInt())
            else steps += RouteStep(name, (r[1] as Double).toInt(), r[2] as String)
        }
        val dist = ordered.sumOf { it[1] as Double }
        val factor = runCatching { traffic.factorFor(edgeIds, at) }.getOrNull()
        val trafficStatus = if (factor == null) "unavailable" else "live"
        if (chosen.e.verifiedAt == null) assumptions += eg.wadeh.core.api.L10n("Entrance position comes from map data and has not been verified on site by Wadeh.", "موقع البوابة من بيانات الخريطة ولم يتحقّق منه فريق «واضح» في الموقع.")
        return RouteResult(mode, chosen.e, dist.toInt(), (best.value.last().agg * (factor ?: 1.0)).toInt(), snap.second.toInt(), steps, path, trafficStatus, assumptions, emptyList(),
            candidates.size, excluded, dataAsOf, attribution)
    }

    /** Current status of a station = latest APPROVED claim from an authoritative source (operator/government). */
    fun stationStatus(stationId: UUID): String = db.read {
        one("""SELECT status FROM station_status_claim WHERE station_id=? AND approved_at IS NOT NULL AND source_kind IN ('operator','government')
               ORDER BY published_at DESC LIMIT 1""", stationId) { it.getString(1) } ?: "unknown"
    }
}
