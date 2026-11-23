package eg.wadeh.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * Deterministic constraint extraction from a buyer's own words (Egyptian Arabic, MSA, Arabizi, English, mixed).
 * Output is a list of EDITABLE chips, each carrying the exact text span that produced it, plus explicit
 * clarifications when an assumption would change results. It never invents a place, a number or an action.
 * An LLM may later propose chips too, but they pass through the same validation and the same chip UI.
 */
const val PARSER_VERSION = "qp-1.0.0"

@Serializable
data class Chip(
    val kind: String,            // place | unit_type | min_bedrooms | max_price | max_down_payment | max_instalment | offer_type | handover_by | commute | finishing
    val value: String,           // canonical value (enum name, minor units, ISO date, place key, HH:mm)
    val evidence: String,        // the words it came from
    val extra: Map<String, String> = emptyMap(),
)

@Serializable
data class ParsedQuery(val version: String, val chips: List<Chip>, val clarifications: List<String>, val ignored: List<String>, val freeText: String)

/** Places the parser knows by alias. Supplied by the server's locality/landmark tables; a small default list is bundled for offline use. */
@Serializable
data class Gazetteer(val places: List<Place>) {
    @Serializable data class Place(val key: String, val kind: String, val aliases: List<String>)
    companion object {
        val DEFAULT = Gazetteer(listOf(
            Place("new-cairo", "locality", listOf("التجمع", "التجمع الخامس", "القاهرة الجديدة", "new cairo", "fifth settlement", "5th settlement", "tagamo3", "el tagamo3", "tagamoa", "el tagamoa")),
            Place("sheikh-zayed", "locality", listOf("الشيخ زايد", "زايد", "sheikh zayed", "zayed", "el sheikh zayed")),
            Place("6th-october", "locality", listOf("أكتوبر", "اكتوبر", "السادس من أكتوبر", "6 october", "6th of october", "october")),
            Place("new-capital", "locality", listOf("العاصمة الإدارية", "العاصمة الادارية", "العاصمة", "new capital", "nac", "new administrative capital")),
            Place("mivida", "landmark", listOf("ميفيدا", "mivida")),
            Place("auc-new-cairo", "landmark", listOf("الجامعة الأمريكية", "الجامعه الامريكيه", "auc")),
            Place("smart-village", "landmark", listOf("القرية الذكية", "smart village")),
        ))
    }
}

object QueryParser {
    private val arabicWordsNum = mapOf(
        "واحد" to 1, "واحده" to 1, "اتنين" to 2, "اثنين" to 2, "تلاته" to 3, "ثلاثه" to 3, "تلات" to 3, "ثلاث" to 3, "اربعه" to 4, "اربع" to 4, "خمسه" to 5, "خمس" to 5,
        "سته" to 6, "ست" to 6, "سبعه" to 7, "سبع" to 7, "تمانيه" to 8, "ثمانيه" to 8, "تمن" to 8, "تسعه" to 9, "تسع" to 9, "عشره" to 10, "عشر" to 10,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )
    private val injection = Regex("(ignore (all |any )?(previous|prior|above) instructions|system prompt|mark (this|it) as verified|تجاهل التعليمات|اعتبره موثق)", RegexOption.IGNORE_CASE)

    /** Parser-specific normalisation: like ArabicText.normalize but keeps decimal points/commas between digits. */
    private fun normalize(s: String): String {
        var t = Digits.toWestern(s).lowercase()
        t = Regex("[\\u064B-\\u0652\\u0670\\u0640]").replace(t, "")
        t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
        t = Regex("(?<=\\d)[.,](?=\\d)").replace(t, "\u0000")          // protect 1.5 and 8,000,000
        t = Regex("[\\p{Punct}،؛؟«»]").replace(t, " ").replace('\u0000', '.')
        t = Regex("(?<=\\d)\\.(?=\\d{3}(\\D|$))").replace(t, "")      // 8.000.000 style grouping -> digits
        val arabizi = mapOf("sha2a" to "شقه", "sha2" to "شقه", "malyon" to "مليون", "million" to "مليون", "mlyon" to "مليون", "alf" to "الف", "mo2adam" to "مقدم",
            "mokadam" to "مقدم", "2st" to "قسط", "a2sat" to "قسط", "cash" to "كاش", "kash" to "كاش", "ghoraf" to "غرف", "fel" to "في", "fi" to "في")
        for ((k, v) in arabizi) t = Regex("(?<![a-z0-9])$k(?![a-z0-9])").replace(t, v)
        // Detach the Arabic preposition ب from amounts: "بمليون" -> "ب مليون", "ب5" -> "ب 5"
        t = Regex("(^|\\s)ب(?=مليون|\\d)").replace(t) { it.value + " " }
        return Regex("\\s+").replace(t, " ").trim()
    }

    private val numRe = "(\\d+(?:[.,]\\d+)?)"

    /** Parses an amount phrase into minor units of EGP. Supports "مليون ونص", "٢ مليون", "8m", "500 الف", "60k", bare "مليون". */
    fun amount(phrase: String, currency: String = "EGP"): Long? {
        val t = phrase.trim()
        Regex("^$numRe?\\s*(مليون|م|m|mn)\\s*(و ?نص|ونص|و نصف|and a half)?$").matchEntire(t)?.let { m ->
            val n = m.groupValues[1].ifEmpty { "1" }.replace(",", ".").toBigDecimal()
            val half = if (m.groupValues[3].isNotEmpty()) BigDecimal("0.5") else BigDecimal.ZERO
            return Money.ofMajor((n + half) * BigDecimal(1_000_000), currency).minor
        }
        Regex("^$numRe?\\s*(الف|الاف|k|thousand)$").matchEntire(t)?.let { m ->
            return Money.ofMajor(m.groupValues[1].ifEmpty { "1" }.replace(",", "").toBigDecimal() * BigDecimal(1_000), currency).minor
        }
        Regex("^(\\d{1,3}(?:,\\d{3})+|\\d{4,})$").matchEntire(t)?.let { return Money.ofMajor(it.value.replace(",", "").toBigDecimal(), currency).minor }
        return null
    }

    // Letters-only suffixes (k, m, mn) need a number in front; Arabic words may stand alone ("مليون" = one million).
    private val amountRe = "(?<![a-z\\u0600-\\u06FF0-9])((?:\\d+(?:\\.\\d+)?\\s*)?(?:مليون(?:\\s*و ?نص)?|الف|الاف|thousand)|\\d+(?:\\.\\d+)?\\s*(?:k|m|mn)(?![a-z])|\\d{1,3}(?:,\\d{3})+|\\d{5,})"
    private val ceiling = "(?:مايزيدش عن|ما يزيدش عن|مايزيدش على|لا يزيد عن|لا يتجاوز|اقل من|تحت|حد اقصى|بحد اقصى|في حدود|لحد|under|below|less than|max|up to|at most|within)"

    fun parse(input: String, gaz: Gazetteer = Gazetteer.DEFAULT): ParsedQuery {
        val raw = input.take(500)
        val t = normalize(raw)
        val chips = mutableListOf<Chip>(); val clar = mutableListOf<String>(); val ignored = mutableListOf<String>()
        injection.find(raw)?.let { ignored += "instruction_like_text: '${it.value}'" }

        // Offer type
        if (Regex("(ايجار|للايجار|rent|rental|ايجار شهري)").containsMatchIn(t)) chips += Chip("offer_type", "RENT", Regex("(ايجار|للايجار|rent|rental)").find(t)!!.value)
        // Unit type
        val types = listOf("شقه" to "APARTMENT", "apartment" to "APARTMENT", "flat" to "APARTMENT", "ستوديو" to "STUDIO", "studio" to "STUDIO", "دوبلكس" to "DUPLEX", "duplex" to "DUPLEX",
            "فيلا" to "VILLA", "villa" to "VILLA", "تاون هاوس" to "TOWNHOUSE", "townhouse" to "TOWNHOUSE", "توين هاوس" to "TWINHOUSE", "شاليه" to "CHALET", "chalet" to "CHALET", "بنتهاوس" to "PENTHOUSE", "penthouse" to "PENTHOUSE")
        types.firstOrNull { (w, _) -> Regex("(^|\\s)$w").containsMatchIn(t) }?.let { (w, v) -> chips += Chip("unit_type", v, w) }
        // Bedrooms: "3 غرف", "تلات اوض", "3 bedroom(s)", "3br"
        Regex("(\\d+|${arabicWordsNum.keys.joinToString("|")})\\s*(غرف|غرفه|اوض|اوضه|bedrooms?|beds?|br|bed)(?=$|\\s)").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: arabicWordsNum[m.groupValues[1]]
            if (n != null) chips += Chip("min_bedrooms", n.toString(), m.value)
        }
        // Places (longest alias first so "التجمع الخامس" beats "التجمع")
        val aliasList = gaz.places.flatMap { p -> p.aliases.map { ArabicText.normalize(it) to p } }.sortedByDescending { it.first.length }
        val used = mutableListOf<IntRange>()
        // Commute destination: "اوصل شغلي في X قبل N" / "commute to X by 9" / "work in X"
        val commuteRe = Regex("(اوصل|اروح|وصول|commute to|get to|work (?:in|at)|office in|شغلي في|شغلي ف|شغلي)\\s*(?:شغلي\\s*)?(?:في\\s*|ف\\s*)?(.{2,40}?)(?:\\s+(?:قبل|before|by)\\s+(?:الساعه\\s*)?(\\d{1,2}(?::\\d{2})?|${arabicWordsNum.keys.joinToString("|")})(?:\\s*(?:الصبح|صباحا|am|pm|مساء))?)?(?=$|[ ,.،]|\\s)")
        commuteRe.find(t)?.let { m ->
            val dest = aliasList.firstOrNull { (a, _) -> m.groupValues[2].contains(a) }
            if (dest != null) {
                val start = t.indexOf(dest.first, m.range.first); used += start until start + dest.first.length
                val hour = m.groupValues[3].let { h -> h.toIntOrNull() ?: h.substringBefore(":").toIntOrNull() ?: arabicWordsNum[h] }
                val pm = Regex("(pm|مساء|بالليل)").containsMatchIn(m.value)
                val extra = mutableMapOf("destination_kind" to dest.second.kind, "destination_text" to dest.first)
                hour?.let { extra["arrive_by"] = "%02d:%s".format(if (pm && it < 12) it + 12 else it, m.groupValues[3].substringAfter(":", "00").take(2).padStart(2, '0')) }
                if (hour != null && !pm && hour in 1..11 && !Regex("(am|الصبح|صباحا)").containsMatchIn(m.value)) clar += "arrive_by_am_assumed"
                chips += Chip("commute", dest.second.key, m.value.trim(), extra)
                clar += "travel_mode"; clar += "commute_day"
            }
        }
        for ((a, p) in aliasList) {
            if (p.kind != "locality") continue
            val idx = Regex("(^|\\s)${Regex.escape(a)}(?=$|\\s|[,.،])").find(t) ?: continue
            val r = (idx.range.first + idx.groupValues[1].length)..idx.range.last
            if (used.any { it.first <= r.last && r.first <= it.last }) continue
            if (chips.none { it.kind == "place" }) { chips += Chip("place", p.key, a); used += r }
        }
        // Money: classify by the nearest preceding cue word.
        val cues = listOf(
            "max_down_payment" to "(مقدم|مقدمها|المقدم|مقدمه|down ?payment|deposit|downpayment)",
            "max_instalment" to "(قسط|القسط|قسطها|اقساط|installment|instalment|monthly payment)",
            "max_price" to "(سعر|سعرها|بسعر|ميزانيه|ميزانيتي|budget|price|كاش|cash|اجمالي|total|(?:^|\\s)ب$)",
        )
        Regex("$amountRe").findAll(t).forEach { m ->
            val amt = amount(m.value.trim()) ?: return@forEach
            val before = t.substring(maxOf(0, m.range.first - 40), m.range.first)
            val afterTxt = t.substring(m.range.last + 1, minOf(t.length, m.range.last + 16))
            // Nearest cue wins, before or after the amount ("بمليون ونص مقدم" puts the cue after).
            val beforeHit = cues.mapNotNull { (k, re) -> Regex(re).findAll(before).lastOrNull()?.let { k to (before.length - 1 - it.range.last) } }.minByOrNull { it.second }
            val afterHit = cues.filter { it.first != "max_price" }.mapNotNull { (k, re) -> Regex(re).find(afterTxt)?.let { k to it.range.first } }.minByOrNull { it.second }
            val kind = when {
                beforeHit != null && afterHit != null -> if (afterHit.second < beforeHit.second && beforeHit.first == "max_price") afterHit.first else beforeHit.first
                beforeHit != null -> beforeHit.first
                afterHit != null && afterHit.second <= 6 -> afterHit.first
                else -> "max_price"
            }
            if (chips.any { it.kind == kind }) return@forEach
            val extra = mutableMapOf<String, String>()
            if (kind == "max_instalment") {
                val after = t.substring(m.range.last + 1, minOf(t.length, m.range.last + 25))
                val ctx = before.takeLast(40) + " " + after
                extra["frequency"] = when {
                    Regex("(ربع سنوي|كل 3 شهور|quarter)").containsMatchIn(ctx) -> "quarterly"
                    Regex("(شهري|في الشهر|monthly|a month|per month)").containsMatchIn(ctx) -> "monthly"
                    Regex("(سنوي|annual|yearly)").containsMatchIn(ctx) -> "annual"
                    else -> { clar += "instalment_frequency"; "unknown" }
                }
            }
            if (kind == "max_price" && Regex("(كاش|cash)").containsMatchIn(before.takeLast(20) + t.substring(m.range.last + 1, minOf(t.length, m.range.last + 12)))) extra["basis"] = "cash"
            if (!Regex(ceiling).containsMatchIn(before.takeLast(30))) extra["bound"] = "approximate"
            chips += Chip(kind, amt.toString(), m.value.trim(), extra)
        }
        // Handover by year: "استلام 2027", "delivery 2027", "استلام فوري" / "ready"
        Regex("(استلام|تسليم|handover|delivery)\\s*(فوري|immediate|ready|now|\\d{4})").find(t)?.let { m ->
            val v = m.groupValues[2]; chips += Chip("handover_by", if (v.all { it.isDigit() }) "$v-12-31" else "ready", m.value)
        } ?: Regex("(جاهزه للسكن|ready to move|immediate delivery)").find(t)?.let { chips += Chip("handover_by", "ready", it.value) }
        // Finishing
        listOf("نص تشطيب" to "SEMI_FINISHED", "نصف تشطيب" to "SEMI_FINISHED", "semi finished" to "SEMI_FINISHED", "متشطبه" to "FULLY_FINISHED", "تشطيب كامل" to "FULLY_FINISHED",
            "fully finished" to "FULLY_FINISHED", "مفروشه" to "FURNISHED", "furnished" to "FURNISHED").firstOrNull { t.contains(it.first) }?.let { chips += Chip("finishing", it.second, it.first) }

        return ParsedQuery(PARSER_VERSION, chips, clar.distinct(), ignored, raw)
    }
}
