package eg.wadeh.server

import java.util.UUID

object TestDb {
    val url: String = System.getenv("DATABASE_URL")!!
    val db: Db by lazy { Db.connect(url).also { Db.migrate(it.ds) } }

    fun reset() {
        db.tx {
            exec("""TRUNCATE change_event, availability_observation, offer_version, payment_plan_version, source_record, import_batch, listing,
                    unit_match_review, unit, building, phase, project, locality, membership, session_token, audit_log, app_user, organization CASCADE""")
        }
        Seed.run(db, "test")
    }

    fun principal(id: UUID): Principal = db.read {
        val p = one("SELECT id,email,display_name,platform_role FROM app_user WHERE id=?", id) { Principal(it.uuid("id"), it.getString("email"), it.getString("display_name"), it.getString("platform_role"), emptyList()) }!!
        p.copy(memberships = list("SELECT org_id, role FROM membership WHERE user_id=?", id) { Membership(it.uuid("org_id"), it.getString("role")) })
    }
}

/** Builds supplier CSV files. Headers deliberately mix Arabic and English like real supplier sheets. */
object Csv {
    val header = listOf("كود المشروع", "phase", "العمارة", "كود الوحدة", "listing_id", "نوع الوحدة", "غرف", "bathrooms", "المساحة", "area_basis", "الدور", "التشطيب",
        "الحالة", "list_price", "cash_price", "المقدم", "تاريخ المقدم", "reservation", "القسط", "دورية القسط", "عدد الأقساط", "تاريخ أول قسط",
        "وديعة الصيانة", "maintenance_due", "الجراج", "موعد الجراج", "الاستلام", "تاريخ قائمة الأسعار", "correction", "correction_reason")

    data class Row(
        val project: String = "TST-GARDENS", val unit: String, val listingId: String = "", val type: String = "شقة", val beds: String = "3", val area: String = "165",
        val status: String = "متاح", val list: String = "8,000,000", val cash: String = "", val dp: String = "800000", val dpDate: String = "2027-01-01",
        val res: String = "100000", val inst: String = "300000", val freq: String = "ربع سنوي", val count: String = "24", val first: String = "2027-04-01",
        val mnt: String = "400000", val mntDue: String = "2030-01-01", val parking: String = "120000", val parkingDue: String = "2030-01-01", val handover: String = "2029-Q4", val asOf: String = "2026-11-03",
        val correction: String = "", val reason: String = "", val building: String = "B12", val phase: String = "P1",
    ) {
        fun cells() = listOf(project, phase, building, unit, listingId, type, beds, "2", area, "إجمالي", "3", "نصف تشطيب", status, list, cash, dp, dpDate, res, inst, freq, count, first,
            mnt, mntDue, parking, parkingDue, handover, asOf, correction, reason)
    }

    fun of(vararg rows: Row): ByteArray = ("﻿" + (listOf(header) + rows.map { it.cells() }).joinToString("\n") { r -> r.joinToString(",") { "\"$it\"" } } + "\n").toByteArray()
}
