package eg.wadeh.server

import eg.wadeh.core.ArabicText
import java.util.UUID

/**
 * Development/staging seed. Everything here is SYNTHETIC and flagged is_test so the release check and the
 * production API exclude it. Fixed UUIDs make the seed idempotent.
 */
object Seed {
    val TEST_SUPPLIER: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
    val TEST_BROKER: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")
    val TEST_DEVELOPER_B: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
    val OPERATOR: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    val REVIEWER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
    val ADMIN: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a3")
    val BROKER_AGENT: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a4")
    val CAIRO: UUID = UUID.fromString("00000000-0000-4000-8000-000000000101")
    val NEW_CAIRO: UUID = UUID.fromString("00000000-0000-4000-8000-000000000102")
    val GIZA: UUID = UUID.fromString("00000000-0000-4000-8000-000000000103")
    val ZAYED: UUID = UUID.fromString("00000000-0000-4000-8000-000000000104")
    val OCTOBER: UUID = UUID.fromString("00000000-0000-4000-8000-000000000105")
    val NAC: UUID = UUID.fromString("00000000-0000-4000-8000-000000000106")
    val PROJECT_GARDENS: UUID = UUID.fromString("00000000-0000-4000-8000-000000000201")
    val PROJECT_HEIGHTS: UUID = UUID.fromString("00000000-0000-4000-8000-000000000202")
    val PROJECT_RIVER: UUID = UUID.fromString("00000000-0000-4000-8000-000000000203")

    const val DEV_PASSWORD = "dev-only-password"

    fun run(db: Db, env: String) {
        require(env != "prod") { "Refusing to seed synthetic data into prod" }
        val pw = Passwords.hash(System.getenv("SEED_PASSWORD") ?: DEV_PASSWORD)
        db.tx {
            fun org(id: UUID, kind: String, en: String, ar: String, refresh: Int = 14) =
                exec("INSERT INTO organization(id,kind,name_en,name_ar,country_code,is_test,refresh_target_days) VALUES (?,?,?,?, 'EG', true, ?) ON CONFLICT (id) DO NOTHING", id, kind, en, ar, refresh)
            org(TEST_SUPPLIER, "test_supplier", "Test Developer (synthetic data)", "مطوّر تجريبي (بيانات اصطناعية)")
            org(TEST_BROKER, "brokerage", "Test Brokerage (synthetic data)", "وساطة تجريبية (بيانات اصطناعية)", 7)
            org(TEST_DEVELOPER_B, "test_supplier", "Second Test Developer (synthetic)", "مطوّر تجريبي ثانٍ (اصطناعي)")

            fun user(id: UUID, email: String, name: String, role: String?) =
                exec("INSERT INTO app_user(id,email,display_name,password_hash,platform_role) VALUES (?,?,?,?,?) ON CONFLICT (id) DO NOTHING", id, email, name, pw, role)
            user(OPERATOR, "operator@test.wadeh.invalid", "Test Operator", null)
            user(REVIEWER, "reviewer@test.wadeh.invalid", "Test Reviewer", "verifier")
            user(ADMIN, "admin@test.wadeh.invalid", "Test Admin", "platform_admin")
            user(BROKER_AGENT, "broker@test.wadeh.invalid", "Test Broker Agent", null)
            exec("INSERT INTO membership VALUES (?,?,'supplier_agent') ON CONFLICT DO NOTHING", OPERATOR, TEST_SUPPLIER)
            exec("INSERT INTO membership VALUES (?,?,'supplier_agent') ON CONFLICT DO NOTHING", OPERATOR, TEST_DEVELOPER_B)
            exec("INSERT INTO membership VALUES (?,?,'supplier_agent') ON CONFLICT DO NOTHING", BROKER_AGENT, TEST_BROKER)

            // Real place names with approximate centroids (public knowledge; boundaries not asserted).
            fun loc(id: UUID, parent: UUID?, kind: String, en: String, ar: String, aliases: List<String>, lng: Double, lat: Double) {
                val norm = (listOf(en, ar) + aliases).joinToString(" | ") { ArabicText.normalize(it) }
                exec("""INSERT INTO locality(id,country_code,parent_id,kind,name_en,name_ar,aliases,search_norm,centroid) VALUES (?,'EG',?,?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326)::geography)
                        ON CONFLICT (id) DO UPDATE SET aliases=EXCLUDED.aliases, search_norm=EXCLUDED.search_norm""", id, parent, kind, en, ar, aliases.toTypedArray(), norm, lng, lat)
            }
            loc(CAIRO, null, "governorate", "Cairo", "القاهرة", listOf("Kairo", "El Qahera"), 31.2357, 30.0444)
            loc(GIZA, null, "governorate", "Giza", "الجيزة", listOf("Gizeh"), 31.2089, 30.0131)
            loc(NEW_CAIRO, CAIRO, "city", "New Cairo", "القاهرة الجديدة", listOf("التجمع", "التجمع الخامس", "Fifth Settlement", "5th Settlement", "Tagamoa", "El Tagamo3"), 31.4700, 30.0300)
            loc(ZAYED, GIZA, "city", "Sheikh Zayed", "الشيخ زايد", listOf("Zayed", "زايد", "El Sheikh Zayed"), 30.9900, 30.0400)
            loc(OCTOBER, GIZA, "city", "6th of October", "السادس من أكتوبر", listOf("October", "6 October", "أكتوبر", "اكتوبر"), 30.9300, 29.9700)
            loc(NAC, CAIRO, "city", "New Administrative Capital", "العاصمة الإدارية الجديدة", listOf("New Capital", "NAC", "العاصمة الإدارية", "العاصمة"), 31.7600, 30.0200)

            // Synthetic projects. Names are deliberately fictional; locations are disclosed as approximate (500 m).
            fun project(id: UUID, dev: UUID, loc: UUID, code: String, en: String, ar: String, aliases: List<String>, lng: Double, lat: Double) {
                val norm = (listOf(code, en, ar) + aliases).joinToString(" | ") { ArabicText.normalize(it) }
                exec("""INSERT INTO project(id,developer_org_id,locality_id,code,name_en,name_ar,aliases,search_norm,location,location_precision_m,publication_rights)
                        VALUES (?,?,?,?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326)::geography,500,'full') ON CONFLICT (id) DO UPDATE SET search_norm=EXCLUDED.search_norm""",
                    id, dev, loc, code, en, ar, aliases.toTypedArray(), norm, lng, lat)
            }
            project(PROJECT_GARDENS, TEST_SUPPLIER, NEW_CAIRO, "TST-GARDENS", "Test Gardens (synthetic)", "حدائق الاختبار (اصطناعي)", listOf("Test Gardens", "حدائق الاختبار"), 31.4912, 30.0181)
            project(PROJECT_HEIGHTS, TEST_SUPPLIER, ZAYED, "TST-HEIGHTS", "Sample Heights (synthetic)", "مرتفعات العيّنة (اصطناعي)", listOf("Sample Heights"), 30.9812, 30.0469)
            project(PROJECT_RIVER, TEST_DEVELOPER_B, NAC, "TST-RIVER", "Fixture River (synthetic)", "نهر التجربة (اصطناعي)", listOf("Fixture River"), 31.7420, 30.0090)
        }
    }
}
