package eg.wadeh.server.routing

import eg.wadeh.server.Db
import eg.wadeh.server.newId
import java.io.InputStream
import java.time.Instant
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.math.*

/**
 * Loads an OSM XML extract (ODbL) into the routing graph. Rules are deliberately conservative:
 * - highway=construction / proposed are stored with that status and are NOT routable.
 * - oneway is enforced for cars only; pedestrians may walk both ways unless foot=no.
 * - motorway/trunk are not walkable; footway/steps/path/pedestrian are not drivable.
 * - access=private/no excludes the segment for both modes (a private compound road is not a public route).
 * Speeds are free-flow assumptions by road class, published with every route.
 */
object OsmLoader {
    val carKmh = mapOf(
        "motorway" to 90.0, "trunk" to 80.0, "primary" to 60.0, "secondary" to 50.0, "tertiary" to 40.0,
        "motorway_link" to 50.0, "trunk_link" to 45.0, "primary_link" to 40.0, "secondary_link" to 35.0, "tertiary_link" to 30.0,
        "unclassified" to 30.0, "residential" to 25.0, "living_street" to 10.0, "service" to 15.0, "road" to 20.0,
    )
    private val footOnly = setOf("footway", "pedestrian", "steps", "path", "cycleway", "track", "corridor", "bridleway")
    private val notWalkable = setOf("motorway", "motorway_link", "trunk", "trunk_link")
    const val FOOT_KMH = 4.8

    data class Way(val id: Long, val nodes: List<Long>, val tags: Map<String, String>)
    data class Result(val nodes: Int, val edges: Int, val gates: List<Pair<Long, Map<String, String>>>, val osmBase: String?)

    private fun accessText(a: String?, ar: Boolean): String? = when (a) {
        null -> null
        "yes", "public" -> if (ar) "مفتوحة للعامة حسب بيانات الخريطة" else "Open to the public according to map data"
        "permissive" -> if (ar) "الدخول بإذن المالك حسب بيانات الخريطة" else "Entry at the owner's discretion according to map data"
        "private" -> if (ar) "خاصة: غير مخصصة للزوار" else "Private: not for visitors"
        "customers", "destination" -> if (ar) "للزوار والعملاء فقط" else "Visitors/customers only"
        else -> if (ar) "قيود دخول غير محددة" else "Unspecified access restriction"
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371008.8; val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    fun load(db: Db, input: InputStream, dataset: String): Result {
        val coords = HashMap<Long, Pair<Double, Double>>(); val nodeTags = HashMap<Long, Map<String, String>>()
        val ways = ArrayList<Way>(); val places = ArrayList<Way>(); var base: String? = null
        val r = XMLInputFactory.newInstance().createXMLStreamReader(input)
        var curNode: Long? = null; var curWay: Long? = null; var nds = ArrayList<Long>(); var tags = HashMap<String, String>()
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                    "osm" -> base = r.getAttributeValue(null, "timestamp")
                    "node" -> { curNode = r.getAttributeValue(null, "id").toLong(); coords[curNode!!] = r.getAttributeValue(null, "lat").toDouble() to r.getAttributeValue(null, "lon").toDouble(); tags = HashMap() }
                    "way" -> { curWay = r.getAttributeValue(null, "id").toLong(); nds = ArrayList(); tags = HashMap() }
                    "nd" -> nds.add(r.getAttributeValue(null, "ref").toLong())
                    "tag" -> tags[r.getAttributeValue(null, "k")] = r.getAttributeValue(null, "v")
                }
                XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                    "node" -> { if (tags.isNotEmpty()) nodeTags[curNode!!] = tags; curNode = null }
                    "way" -> {
                        if ("highway" in tags) ways.add(Way(curWay!!, nds, tags))
                        else if ((tags["shop"] == "mall" || tags["amenity"] in setOf("university", "hospital")) && tags["name"] != null) places.add(Way(curWay!!, nds, tags))
                        curWay = null
                    }
                }
            }
        }
        var edges = 0; val used = HashSet<Long>()
        db.tx {
            exec("DELETE FROM road_edge WHERE dataset=?", dataset); exec("DELETE FROM entrance WHERE source=?", "osm:$dataset")
            exec("DELETE FROM road_node WHERE dataset=? AND id NOT IN (SELECT node_id FROM entrance WHERE node_id IS NOT NULL)", dataset)
            val nodeInsert = c.prepareStatement("INSERT INTO road_node(id, geom, dataset) VALUES (?, ST_SetSRID(ST_MakePoint(?,?),4326), ?) ON CONFLICT (id) DO NOTHING")
            val edgeInsert = c.prepareStatement("""INSERT INTO road_edge(source,target,car_cost,car_reverse_cost,foot_cost,foot_reverse_cost,length_m,highway,name_en,name_ar,status,status_source,geom,dataset,osm_way_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,ST_SetSRID(ST_MakeLine(ST_MakePoint(?,?),ST_MakePoint(?,?)),4326),?,?)""")
            for (w in ways) {
                val t = w.tags; var hw = t["highway"]!!
                val status = when (hw) { "construction" -> "under_construction"; "proposed" -> "proposed"; else -> "existing" }
                if (status != "existing") hw = t["construction"] ?: t["proposed"] ?: hw
                val access = t["access"]
                val privateRoad = access in setOf("private", "no")
                val carSpeed = carKmh[hw]
                val car = carSpeed != null && !privateRoad && t["motor_vehicle"] != "no" && t["motorcar"] != "no" && hw !in footOnly
                val foot = hw !in notWalkable && t["foot"] != "no" && !(privateRoad && t["foot"] !in setOf("yes", "designated", "permissive"))
                val oneway = when (t["oneway"]) { "yes", "true", "1" -> 1; "-1" -> -1; else -> if (t["junction"] == "roundabout" || hw.endsWith("motorway")) 1 else 0 }
                for (i in 0 until w.nodes.size - 1) {
                    val a = w.nodes[i]; val b = w.nodes[i + 1]
                    val ca = coords[a] ?: continue; val cb = coords[b] ?: continue
                    val len = haversine(ca.first, ca.second, cb.first, cb.second)
                    val carSec = if (car) len / (carSpeed!! / 3.6) else -1.0
                    val footSec = if (foot) len / (FOOT_KMH / 3.6) else -1.0
                    val usable = status == "existing"
                    for ((id, c2) in listOf(a to ca, b to cb)) if (used.add(id)) { nodeInsert.setLong(1, id); nodeInsert.setDouble(2, c2.second); nodeInsert.setDouble(3, c2.first); nodeInsert.setString(4, dataset); nodeInsert.addBatch() }
                    edgeInsert.setLong(1, a); edgeInsert.setLong(2, b)
                    edgeInsert.setDouble(3, if (usable && oneway >= 0) carSec else -1.0)
                    edgeInsert.setDouble(4, if (usable && oneway <= 0) carSec else -1.0)
                    edgeInsert.setDouble(5, if (usable) footSec else -1.0); edgeInsert.setDouble(6, if (usable) footSec else -1.0)
                    edgeInsert.setDouble(7, len); edgeInsert.setString(8, hw)
                    edgeInsert.setString(9, t["name:en"] ?: t["name"]); edgeInsert.setString(10, t["name:ar"] ?: t["name"])
                    edgeInsert.setString(11, status); edgeInsert.setString(12, "osm:way/${w.id}")
                    edgeInsert.setDouble(13, ca.second); edgeInsert.setDouble(14, ca.first); edgeInsert.setDouble(15, cb.second); edgeInsert.setDouble(16, cb.first)
                    edgeInsert.setString(17, dataset); edgeInsert.setLong(18, w.id); edgeInsert.addBatch(); edges++
                }
            }
            nodeInsert.executeBatch(); edgeInsert.executeBatch()
            // Gates/entrances that sit on the graph become entrance candidates for the place that contains them.
            for ((id, t) in nodeTags) {
                val isGate = t["barrier"] in setOf("gate", "lift_gate") || t.containsKey("entrance")
                if (!isGate || id !in used) continue
                val c2 = coords[id]!!
                exec("""INSERT INTO entrance(id, place_key, name_en, name_ar, kind, modes, node_id, geom, locked, source, observed_at, conditions_en, conditions_ar)
                        VALUES (?,?,?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326),?,?,?,?,?)""",
                    newId(), null, t["name:en"] ?: t["name"] ?: "Gate", t["name:ar"] ?: t["name"] ?: "بوابة", if (t["access"] == "private") "service" else "any",
                    arrayOf("car", "foot"), id, c2.second, c2.first, t["locked"] == "yes", "osm:$dataset", base?.let { Instant.parse(it) },
                    accessText(t["access"], false), accessText(t["access"], true))
            }
            exec("DELETE FROM landmark WHERE source=?", "osm:$dataset")
            for (p in places) {
                val pts = p.nodes.mapNotNull { coords[it] }; if (pts.isEmpty()) continue
                val en = p.tags["name:en"] ?: p.tags["name"]!!; val ar = p.tags["name:ar"] ?: p.tags["name"]!!
                exec("INSERT INTO landmark(id,name_en,name_ar,kind,geom,source,search_norm) VALUES (?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326),?,?)",
                    newId(), en, ar, p.tags["shop"] ?: p.tags["amenity"]!!, pts.map { it.second }.average(), pts.map { it.first }.average(), "osm:$dataset",
                    eg.wadeh.core.ArabicText.normalize("$en | $ar | ${p.tags["name"]}"))
            }
            exec("""INSERT INTO map_layer(code,name_en,name_ar,source,licence,attribution,extent,observed_at,imported_at,offline_allowed,status)
                    VALUES (?,?,?,?,?,?,?,?,now(),false,'active') ON CONFLICT (code) DO UPDATE SET observed_at=EXCLUDED.observed_at, imported_at=now()""",
                "roads:$dataset", "Road graph ($dataset)", "شبكة الطرق ($dataset)", "OpenStreetMap extract", "ODbL 1.0", "© OpenStreetMap contributors",
                dataset, base?.let { Instant.parse(it) })
        }
        return Result(used.size, edges, nodeTags.filter { it.value["barrier"] == "gate" }.map { it.key to it.value }, base)
    }
}
