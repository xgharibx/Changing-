package eg.wadeh.server.imports

import eg.wadeh.core.ArabicText
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.time.ZoneOffset

/** A supplier file reduced to header -> raw string per row, with original header text retained. */
data class RawTable(val headers: List<String>, val rows: List<Map<String, String>>, val unknownHeaders: List<String>)

object TemplateColumns {
    /** Canonical key -> accepted header spellings (English, Arabic, common variants). Matching uses ArabicText.normalize. */
    val aliases: Map<String, List<String>> = linkedMapOf(
        "project_code" to listOf("project_code", "project", "project code", "كود المشروع", "المشروع", "اسم المشروع"),
        "phase" to listOf("phase", "المرحلة", "مرحله"),
        "building" to listOf("building", "block", "العمارة", "المبنى", "البلوك"),
        "unit_code" to listOf("unit_code", "unit code", "unit", "unit no", "كود الوحدة", "رقم الوحدة", "الوحدة"),
        "listing_id" to listOf("listing_id", "listing id", "ref", "reference", "رقم الإعلان", "المرجع", "كود العرض"),
        "unit_type" to listOf("unit_type", "type", "unit type", "نوع الوحدة", "النوع"),
        "bedrooms" to listOf("bedrooms", "beds", "rooms", "غرف النوم", "عدد الغرف", "غرف"),
        "bathrooms" to listOf("bathrooms", "baths", "الحمامات", "حمامات", "عدد الحمامات"),
        "area_m2" to listOf("area_m2", "area", "area (m2)", "bua", "المساحة", "المساحة م2", "مساحة"),
        "area_basis" to listOf("area_basis", "area type", "نوع المساحة", "أساس المساحة"),
        "floor" to listOf("floor", "الدور", "الطابق"),
        "finishing" to listOf("finishing", "التشطيب", "نوع التشطيب"),
        "availability" to listOf("availability", "status", "الحالة", "التوافر", "الإتاحة"),
        "offer_type" to listOf("offer_type", "offer type", "نوع العرض", "بيع او ايجار"),
        "currency" to listOf("currency", "العملة"),
        "cash_price" to listOf("cash_price", "cash price", "سعر الكاش", "السعر كاش", "الكاش"),
        "list_price" to listOf("list_price", "price", "total price", "instalment price", "installment price", "السعر", "السعر الإجمالي", "سعر التقسيط", "إجمالي السعر"),
        "monthly_rent" to listOf("monthly_rent", "rent", "الإيجار الشهري", "الايجار"),
        "reservation" to listOf("reservation", "booking", "مبلغ الحجز", "الحجز", "جدية الحجز"),
        "reservation_included" to listOf("reservation_included", "reservation in down payment", "الحجز من المقدم", "الحجز ضمن المقدم"),
        "down_payment" to listOf("down_payment", "deposit", "down payment", "المقدم", "مقدم", "الدفعة المقدمة"),
        "down_payment_date" to listOf("down_payment_date", "deposit date", "تاريخ المقدم"),
        "installment_amount" to listOf("installment_amount", "instalment_amount", "installment", "instalment", "القسط", "قيمة القسط"),
        "installment_frequency" to listOf("installment_frequency", "instalment_frequency", "frequency", "دورية القسط", "نظام القسط"),
        "installment_count" to listOf("installment_count", "instalment_count", "number of installments", "عدد الأقساط", "عدد الاقساط"),
        "first_installment_date" to listOf("first_installment_date", "first instalment date", "تاريخ أول قسط", "تاريخ اول قسط"),
        "maintenance" to listOf("maintenance", "maintenance deposit", "وديعة الصيانة", "الصيانة"),
        "maintenance_due" to listOf("maintenance_due", "maintenance due", "موعد الصيانة", "استحقاق الصيانة"),
        "parking" to listOf("parking", "garage", "الجراج", "الجراچ", "جراج", "موقف"),
        "parking_due" to listOf("parking_due", "parking due", "موعد الجراج", "استحقاق الجراج"),
        "handover" to listOf("handover", "delivery", "الاستلام", "موعد الاستلام", "التسليم"),
        "price_sheet_date" to listOf("price_sheet_date", "price date", "as of", "تاريخ قائمة الأسعار", "تاريخ الأسعار", "بتاريخ"),
        "correction" to listOf("correction", "تصحيح"),
        "correction_reason" to listOf("correction_reason", "سبب التصحيح"),
        "notes" to listOf("notes", "ملاحظات"),
    )

    private val lookup: Map<String, String> = aliases.flatMap { (k, vs) -> vs.map { ArabicText.normalize(it).replace(' ', '_') to k } }.toMap()

    fun canonical(header: String): String? = lookup[ArabicText.normalize(header).replace(' ', '_')]

    val required = listOf("project_code", "unit_code", "unit_type", "availability", "price_sheet_date")

    /** Bilingual header row for the downloadable template. */
    fun templateHeader(): List<String> = aliases.map { (k, v) -> "$k | ${v.firstOrNull { it.any { c -> c in '؀'..'ۿ' } } ?: k}" }
}

object SupplierFile {
    fun parse(filename: String, bytes: ByteArray): RawTable {
        val lower = filename.lowercase()
        val grid = when {
            lower.endsWith(".xlsx") -> xlsx(bytes)
            lower.endsWith(".csv") || lower.endsWith(".txt") -> csv(String(bytes, Charsets.UTF_8))
            else -> throw IllegalArgumentException("Unsupported file type. Upload .csv (UTF-8) or .xlsx")
        }
        require(grid.isNotEmpty()) { "The file is empty" }
        val rawHeaders = grid.first()
        // Template headers look like "unit_code | كود الوحدة": either side may match.
        val keys = rawHeaders.map { h -> h.split('|').map { it.trim() }.firstNotNullOfOrNull { TemplateColumns.canonical(it) } }
        val unknown = rawHeaders.filterIndexed { i, h -> keys[i] == null && h.isNotBlank() }
        val dupes = keys.filterNotNull().groupBy { it }.filter { it.value.size > 1 }.keys
        require(dupes.isEmpty()) { "Columns appear more than once: ${dupes.joinToString()}" }
        val rows = grid.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r ->
            buildMap { keys.forEachIndexed { i, k -> if (k != null) put(k, r.getOrElse(i) { "" }.trim()) } }
        }
        return RawTable(rawHeaders, rows, unknown)
    }

    fun csv(text: String): List<List<String>> {
        val s = text.removePrefix("﻿")
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val f = StringBuilder(); var q = false; var i = 0
        val delim = if (s.lineSequence().firstOrNull()?.let { it.count { c -> c == ';' } > it.count { c -> c == ',' } } == true) ';' else ','
        while (i < s.length) {
            val c = s[i]
            if (q) {
                if (c == '"') { if (i + 1 < s.length && s[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(c)
            } else when (c) {
                '"' -> q = true
                delim -> { row.add(f.toString()); f.clear() }
                '\r' -> {}
                '\n' -> { row.add(f.toString()); f.clear(); out.add(row); row = mutableListOf() }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); out.add(row) }
        return out
    }

    private fun xlsx(bytes: ByteArray): List<List<String>> = XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
        val sheet = wb.getSheetAt(0); val fmt = DataFormatter()
        (sheet.firstRowNum..sheet.lastRowNum).map { r ->
            val row = sheet.getRow(r) ?: return@map emptyList()
            (0 until maxOf(0, row.lastCellNum.toInt())).map { ci ->
                val cell = row.getCell(ci) ?: return@map ""
                if (cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
                    cell.localDateTimeCellValue.atOffset(ZoneOffset.UTC).toLocalDate().toString()
                else if (cell.cellType == CellType.NUMERIC) java.math.BigDecimal.valueOf(cell.numericCellValue).stripTrailingZeros().toPlainString()
                else fmt.formatCellValue(cell)
            }
        }
    }
}
