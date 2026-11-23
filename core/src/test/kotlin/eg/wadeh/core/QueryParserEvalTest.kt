package eg.wadeh.core

import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** Runs the versioned evaluation suite and writes a report with denominators and every failure. */
class QueryParserEvalTest {
    @Test fun `query parser eval suite v1`() = run("query-parser-v1.json", enforce = true)

    /** Held-out: reported, not enforced, so it cannot be silently tuned into passing. */
    @Test fun `query parser held-out suite v1 (report only)`() = run("query-parser-holdout-v1.json", enforce = false)

    private fun run(file: String, enforce: Boolean) {
        val suite = Json.parseToJsonElement(javaClass.classLoader!!.getResource("eval/$file")!!.readText()).jsonObject
        val failures = mutableListOf<String>(); var checks = 0
        val byLang = sortedMapOf<String, IntArray>()
        for (c in suite["cases"]!!.jsonArray.map { it.jsonObject }) {
            val id = c["id"]!!.jsonPrimitive.content; val lang = c["lang"]!!.jsonPrimitive.content
            val p = QueryParser.parse(c["input"]!!.jsonPrimitive.content)
            val got = p.chips.associate { it.kind to it.value }
            val errs = mutableListOf<String>()
            c["expect"]!!.jsonObject.forEach { (k, v) ->
                checks++; val want = v.jsonPrimitive.content
                if (want.endsWith("_or_absent")) { if (got[k] != null && got[k] != want.removeSuffix("_or_absent")) errs += "$k=${got[k]} (want ${want.removeSuffix("_or_absent")} or none)" }
                else if (got[k] != want) errs += "$k=${got[k]} (want $want)"
            }
            c["absent"]?.jsonArray?.forEach { checks++; if (got.containsKey(it.jsonPrimitive.content)) errs += "unexpected ${it.jsonPrimitive.content}=${got[it.jsonPrimitive.content]}" }
            c["clar"]?.jsonArray?.forEach { checks++; if (it.jsonPrimitive.content !in p.clarifications) errs += "missing clarification ${it.jsonPrimitive.content}" }
            c["clar_absent"]?.jsonArray?.forEach { checks++; if (it.jsonPrimitive.content in p.clarifications) errs += "unneeded clarification ${it.jsonPrimitive.content}" }
            c["ignored"]?.let { checks++; if (p.ignored.size != it.jsonPrimitive.int) errs += "ignored=${p.ignored.size}" }
            c["extra"]?.jsonObject?.forEach { (k, v) -> checks++; val (kind, key) = k.split('.'); if (p.chips.firstOrNull { it.kind == kind }?.extra?.get(key) != v.jsonPrimitive.content) errs += "$k" }
            val stat = byLang.getOrPut(lang) { IntArray(2) }; stat[1]++
            if (errs.isEmpty()) stat[0]++ else failures += "$id [$lang]: ${errs.joinToString("; ")}  chips=$got"
        }
        val total = byLang.values.sumOf { it[1] }; val passed = byLang.values.sumOf { it[0] }
        val report = buildString {
            appendLine("suite=$file parser=$PARSER_VERSION cases=$total passed=$passed checks=$checks")
            byLang.forEach { (l, s) -> appendLine("  $l: ${s[0]}/${s[1]}") }
            failures.forEach { appendLine("  FAIL $it") }
        }
        println(report)
        File(System.getProperty("evalReportDir") ?: "build").apply { mkdirs() }.resolve(file.removeSuffix(".json") + "-report.txt").writeText(report)
        if (enforce && failures.isNotEmpty()) fail(report)
    }
}
