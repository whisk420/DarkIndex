package com.whisk.hackexlogger

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Writer

/**
 * Compact multi-dump export (developer request 2026-11-24, for long reboot runs with many dumps per batch).
 *
 * The full export (one pretty JSON object per dump, node tree included) is ~60 KB for a single Apps page, almost all
 * of it node tree. Attached files are read in full, so this format keeps only what's needed to review a batch:
 *
 * - plain text, one `## dump` section per dump, small fields on one line each;
 * - no node tree, no timings, no package (always HackEx 2), no local time;
 * - the bottom nav is removed from the end of each text (the header says so; `nav` on the dump line marks it);
 * - long lines that repeat across dumps (disclaimers, consumable descriptions…) are listed once under `## shared`
 *   and written as `§n` in the texts;
 * - a text identical to an earlier dump's is written as `text: same as #id`.
 *
 * `tools/dumps.py` turns it back into exact screen text (for fixtures). Built from the stored JSON, so it also works
 * on dumps saved by older builds.
 */
object RawDumpCompact {
    const val FORMAT = "darkindex-dumps-compact"
    const val VERSION = 1
    /** Lines at least this long that occur in 2+ dumps go to the shared list. */
    const val SHARED_MIN_LENGTH = 40
    /** Single-line JSON longer than this is broken up one field / element per line. */
    private const val WRAP = 160

    private val NAV = listOf("HOME", "SCAN", "PROCESSES", "APPS", "STORE")
    private val CREWS = Regex("""CREWS(\s+\d+)?""")
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** Text without a trailing bottom nav, and whether one was removed. */
    fun stripTrailingNav(text: String): Pair<String, Boolean> {
        val lines = text.split("\n")
        var end = lines.size
        while (end > 0 && lines[end - 1].isBlank()) end--
        if (end < NAV.size + 1 || !CREWS.matches(lines[end - 1].trim())) return text to false
        val start = end - 1 - NAV.size
        if (NAV.indices.any { lines[start + it].trim() != NAV[it] }) return text to false
        return lines.subList(0, start).joinToString("\n") to true
    }

    /** Writes the compact export of [storedJsons] (oldest first). Returns the number of dumps written. */
    fun export(storedJsons: List<String>, out: Writer, exportedAtUtc: String): Int {
        val dumps = storedJsons.mapNotNull { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        val texts = dumps.map { stripTrailingNav(it.str("rawText") ?: "") }

        // Identical texts are written once
        val firstWithText = HashMap<String, Long>()
        val sameAs = dumps.indices.map { i ->
            val id = dumps[i].prim("id")?.asLong ?: -1
            val prev = firstWithText[texts[i].first]
            if (prev == null) firstWithText[texts[i].first] = id
            prev
        }

        // Shared lines: long lines occurring in 2+ distinct texts
        val counts = LinkedHashMap<String, Int>()
        dumps.indices.filter { sameAs[it] == null }.forEach { i ->
            texts[i].first.split("\n").filter { it.length >= SHARED_MIN_LENGTH }.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val shared = counts.filter { it.value >= 2 }.keys.toList()
        val ref = shared.withIndex().associate { (i, l) -> l to "§$i" }

        val apps = dumps.mapNotNull { appOf(it) }.distinct()
        out.write("# $FORMAT v$VERSION\n")
        out.write("# exported ${shortUtc(exportedAtUtc)} · ${dumps.size} dumps · app ${apps.singleOrNull() ?: apps.joinToString(", ")}\n")
        out.write("# Bottom nav removed from texts marked nav. §n = line n of ## shared.\n")
        if (shared.isNotEmpty()) {
            out.write("## shared\n")
            shared.forEachIndexed { i, l -> out.write("§$i $l\n") }
        }

        dumps.forEachIndexed { i, d ->
            val id = d.prim("id")?.asLong ?: -1
            val head = mutableListOf("## dump $id", shortUtc(d.str("capturedAtUtc") ?: "?"), d.str("detectedType") ?: "?")
            if (texts[i].second) head += "nav"
            out.write(head.joinToString(" · ") + "\n")
            if (apps.size > 1) appOf(d)?.let { out.write("app: $it\n") }
            if (d.prim("fallbackUsed")?.asBoolean == true) out.write("fallback: yes\n")
            d.str("result")?.let { out.write("result: $it\n") }
            d.arr("parsersMatched")?.takeIf { it.size() > 0 }?.let { out.write("parsers: ${it.joinToString(", ") { e -> e.asString }}\n") }
            d.str("error")?.let { e -> out.write("error: ${e.replace("\n", "\n  ")}\n") }
            d.obj("logStats")?.let { s ->
                out.write("log: ${s.int("timestampedLines")} timestamped, ${s.int("interpreted")} interpreted, ${s.int("discarded")} discarded, ${s.int("partial")} partial\n")
            }
            d.arr("discardedLogLines")?.forEach { e ->
                val o = e.asJsonObject
                out.write("discarded: ${o.str("stage")}:${o.str("reason")} | ${o.str("line")}\n")
            }
            d.obj("dbEffects")?.let { writeDb(it, out) }
            d.obj("interpretation")?.entrySet()?.forEach { (k, v) ->
                if (k == "Log" && v.isJsonArray) writeLog(v.asJsonArray, texts[i].first, out) else emit("interp $k", v, "", out)
            }
            if (sameAs[i] != null) {
                out.write("text: same as #${sameAs[i]}\n")
            } else {
                out.write("text:\n")
                texts[i].first.split("\n").forEach { out.write((ref[it] ?: it) + "\n") }
            }
        }
        out.flush()
        return dumps.size
    }

    private fun writeDb(db: JsonObject, out: Writer) {
        val parts = mutableListOf<String>()
        val before = db.prim("recordsBefore")?.asInt
        val after = db.prim("recordsAfter")?.asInt
        if (before != null && after != null && before != after) parts += "records $before→$after"
        for (k in listOf("created", "updated", "removed")) {
            db.arr(k)?.takeIf { it.size() > 0 }?.let { parts += "$k ${it.size()}: ${it.joinToString(", ") { e -> if (e.isJsonPrimitive) e.asString else gson.toJson(e) }}" }
        }
        db.obj("profileChanges")?.takeIf { it.size() > 0 }?.let { p ->
            parts += "profile " + p.entrySet().joinToString(", ") { (k, v) ->
                if (v.isJsonArray && v.asJsonArray.size() == 2) "$k ${txt(v.asJsonArray[0])}→${txt(v.asJsonArray[1])}" else "$k ${gson.toJson(v)}"
            }
        }
        for (k in listOf("siphonAlertsAdded", "siphonAlertsCleared")) {
            db.arr(k)?.takeIf { it.size() > 0 }?.let { parts += "$k: ${it.joinToString(", ") { e -> txt(e) }}" }
        }
        val ksb = db.prim("knownSiphonsBefore")?.asInt
        val ksa = db.prim("knownSiphonsAfter")?.asInt
        if (ksb != null && ksa != null && ksb != ksa) parts += "knownSiphons $ksb→$ksa"
        if (parts.isNotEmpty()) out.write("db: ${parts.joinToString(" · ")}\n")
        db.arr("walletConflicts")?.forEach { out.write("walletConflict: ${gson.toJson(it)}\n") }
    }

    /**
     * Log rows: `L<n> <ip|-> <wallet|-> <amount|-> [own] [sw:<software>] [owned]`, where n is the 0-based line of this
     * dump's text. A line that can't be found in the text is written out after `|`.
     */
    private fun writeLog(entries: JsonArray, text: String, out: Writer) {
        out.write("interp Log (${entries.size()}):\n")
        val lines = text.split("\n").map { it.trim() }
        val used = HashSet<Int>()
        for (e in entries) {
            val u = e.asJsonObject
            val line = u.str("line")
            val idx = if (line == null) -1 else lines.indices.firstOrNull { it !in used && lines[it] == line } ?: -1
            if (idx >= 0) used += idx
            val cols = mutableListOf(
                if (idx >= 0) "L$idx" else "L?", u.str("ip") ?: "-", u.str("wallet") ?: "-", u.prim("amount")?.asString ?: "-"
            )
            if (u.prim("ownTheft")?.asBoolean == true) cols += "own"
            u.str("software")?.let { cols += "sw:$it" }
            if (u.prim("ownedSoftware")?.asBoolean == true) cols += "owned"
            out.write("  ${cols.joinToString(" ")}${if (idx < 0 && line != null) " | $line" else ""}\n")
        }
    }

    /** One line when short; otherwise one field / element per line, recursively. */
    private fun emit(label: String, v: JsonElement, indent: String, out: Writer) {
        val one = gson.toJson(v)
        if (one.length <= WRAP || !(v.isJsonObject || v.isJsonArray)) {
            out.write("$indent$label: $one\n"); return
        }
        out.write("$indent$label:\n")
        if (v.isJsonObject) v.asJsonObject.entrySet().forEach { (k, c) -> emit(k, c, "$indent  ", out) }
        else v.asJsonArray.forEachIndexed { i, c -> emit("[$i]", c, "$indent  ", out) }
    }

    private fun appOf(d: JsonObject): String? = d.obj("app")?.let { "${it.str("versionName") ?: "?"} (${it.prim("versionCode")?.asString ?: "?"})" }
    private fun shortUtc(s: String) = s.replace('T', ' ').replace(Regex("""\.\d+Z$"""), "Z")
    private fun txt(e: JsonElement): String = if (e.isJsonNull) "null" else if (e.isJsonPrimitive) e.asString else gson.toJson(e)

    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.prim(k: String): com.google.gson.JsonPrimitive? = get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
    private fun JsonObject.str(k: String): String? = prim(k)?.asString
    private fun JsonObject.int(k: String): String = prim(k)?.asString ?: "?"
}
