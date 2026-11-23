package eg.wadeh.server

import eg.wadeh.server.routing.OsmLoader
import eg.wadeh.server.routing.Router
import eg.wadeh.server.routing.TrafficProvider
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class RoutingTest {
    private val db get() = TestDb.db
    private val fixture = File("../fixtures/m3/synthetic-divided-road.osm")
    private val origin = 31.8030 to 30.0030

    @BeforeTest fun setup() {
        TestDb.reset()
        db.tx { exec("TRUNCATE road_edge, entrance, road_node, station_status_claim, transit_station CASCADE") }
        OsmLoader.load(db, fixture.inputStream(), "synthetic-divided-road")
        db.tx { exec("UPDATE entrance SET place_key='synthetic-compound' WHERE source='osm:synthetic-divided-road'") }
    }

    private fun router(t: TrafficProvider = eg.wadeh.server.routing.NoTraffic) = Router(db, t)
    private fun gates(r: Router) = r.entrances(placeKey = "synthetic-compound")

    @Test fun `car routes to the visitor gate via a legal U-turn, never the locked nearest gate`() {
        val r = router(); val res = r.route(origin.first, origin.second, "car", gates(r))
        assertEquals("Visitor Gate (synthetic)", res.entrance!!.name.en)
        assertTrue(res.excludedEntrances.any { it.startsWith("North Fence Gate") && it.endsWith("locked") })
        val roads = res.steps.mapNotNull { it.road?.en }
        assertEquals(listOf("Origin Street", "Divided Road (westbound)", "West U-turn", "Divided Road (eastbound)", "Visitor Service Road"), roads)
        // The path must go west first (legal direction) before turning east.
        val lngs = res.path.map { it[0] }
        assertEquals(31.8000, lngs.minOrNull()!!, 1e-9)
        assertEquals(31.8050, res.path.last()[0], 1e-9); assertEquals(29.9995, res.path.last()[1], 1e-9)
        assertTrue(res.distanceM!! > OsmLoader.haversine(origin.second, origin.first, 29.9995, 31.8050))
        assertEquals("unavailable", res.trafficStatus)
        assertTrue(res.assumptions.any { it.en.contains("No live or typical traffic") })
    }

    @Test fun `walking keeps the missing pedestrian connection instead of inventing one, proposed footbridge ignored`() {
        val r = router(); val res = r.route(origin.first, origin.second, "foot", gates(r))
        assertNull(res.distanceM); assertNull(res.durationS)
        assertEquals("no_pedestrian_connection", res.missing.single().code)
        assertTrue(res.missing.single().straightLineM in 300..600)
        val proposed = db.read { one("SELECT status, foot_cost FROM road_edge WHERE osm_way_id=-107") { it.getString(1) to it.getDouble(2) } }!!
        assertEquals("proposed" to -1.0, proposed)
    }

    @Test fun `approved operational footbridge then connects pedestrians`() {
        // Simulates an approved authoritative record changing the edge status (the only path that may change the graph).
        db.tx { exec("UPDATE road_edge SET status='existing', foot_cost=length_m/(4.8/3.6), foot_reverse_cost=length_m/(4.8/3.6), status_source='operator record (synthetic), approved' WHERE osm_way_id=-107") }
        val r = router(); val res = r.route(origin.first, origin.second, "foot", gates(r))
        assertTrue(res.missing.isEmpty()); assertEquals("Visitor Gate (synthetic)", res.entrance!!.name.en)
    }

    @Test fun `disconnected traffic provider degrades to an explicit free-flow estimate`() {
        val broken = object : TrafficProvider { override fun factorFor(edgeIds: List<Long>, at: Instant): Double? = throw RuntimeException("provider timeout") }
        val res = router(broken).route(origin.first, origin.second, "car", gates(router()))
        assertEquals("unavailable", res.trafficStatus)
        assertNotNull(res.durationS)
        val live = object : TrafficProvider { override fun factorFor(edgeIds: List<Long>, at: Instant) = 1.5 }
        val res2 = router(live).route(origin.first, origin.second, "car", gates(router()))
        assertEquals("live", res2.trafficStatus); assertEquals((res.durationS!! * 1.5).toInt(), res2.durationS!!, "")
    }

    @Test fun `station becomes operational only after a newer approved authoritative record`() {
        val st = UUID.randomUUID()
        db.tx {
            exec("INSERT INTO transit_station(id,name_en,name_ar,line,geom) VALUES (?,?,?,?,ST_SetSRID(ST_MakePoint(31.803,30.003),4326))", st, "Synthetic Station", "محطة اصطناعية", "Synthetic Line")
            exec("INSERT INTO station_status_claim(id,station_id,status,source_kind,source_ref,published_at,approved_by,approved_at) VALUES (?,?,'proposed','government','gazette (synthetic)','2026-01-01',?,now())", UUID.randomUUID(), st, Seed.REVIEWER)
            exec("INSERT INTO station_status_claim(id,station_id,status,source_kind,source_ref,published_at,approved_by,approved_at) VALUES (?,?,'operational','developer','brochure (synthetic)','2026-06-01',?,now())", UUID.randomUUID(), st, Seed.REVIEWER)
            exec("INSERT INTO station_status_claim(id,station_id,status,source_kind,source_ref,published_at) VALUES (?,?,'operational','operator','operator notice (synthetic)','2026-07-01')", UUID.randomUUID(), st)
        }
        val r = router()
        assertEquals("proposed", r.stationStatus(st)) // developer claim and unapproved operator claim do not count
        db.tx { exec("UPDATE station_status_claim SET approved_by=?, approved_at=now() WHERE source_kind='operator'", Seed.REVIEWER) }
        assertEquals("operational", r.stationStatus(st))
    }
}
