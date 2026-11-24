package com.whisk.hackexlogger

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// =====================================================================================
// Raw Dump Mode — debug capture of every 📸 scrape (spec: docs/plans/raw-dump-mode.md)
// Everything in this file is plain Kotlin/JVM so it can be unit tested.
// The Android-only node-tree capture lives in RawDumpCapture.kt.
// =====================================================================================

object RawDumpSettings {
    const val PREFS_NAME = "HackExSettings"
    const val KEY_ENABLED = "pref_raw_dump_mode"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

data class RawDumpAppInfo(val versionName: String?, val versionCode: Long)

data class RawDumpTiming(
    var extractMs: Double = 0.0,
    var detectMs: Double = 0.0,
    var parseMs: Double = 0.0,
    var totalMs: Double = 0.0,
    var nodeCount: Int = 0
)

data class RawDumpLogStats(
    /** Lines starting with a `[...]` prefix that the log parser looked at. */
    val timestampedLines: Int,
    /** Lines that fully took effect. */
    val interpreted: Int,
    /** Lines dropped by the parser or the database (see discardedLogLines). */
    val discarded: Int,
    /** Lines that partly took effect (e.g. wallet linked but theft not recorded). */
    val partial: Int
)

/** One accessibility node, flattened in pre-order. `d` = depth. */
data class RawDumpNode(
    val d: Int,
    val cls: String?,
    val id: String?,
    val text: String?,
    val desc: String?,
    val bounds: List<Int>?
)

data class RawDumpRecordChange(val ip: String, val fields: List<String>)
data class RawDumpRemovedRecord(val ip: String, val likelyMergedInto: String?)
data class RawDumpWalletConflict(val ip: String, val existingWallet: String, val conflictingWallet: String, val source: String)

data class RawDumpDbEffects(
    val recordsBefore: Int,
    val recordsAfter: Int,
    val created: List<String>,
    val updated: List<RawDumpRecordChange>,
    val removed: List<RawDumpRemovedRecord>,
    val walletConflicts: List<RawDumpWalletConflict>,
    /** field -> [before, after] for the player's own profile (Store / own Home / Bypass screens). */
    val profileChanges: Map<String, List<Any?>>,
    val siphonAlertsAdded: List<String>,
    val siphonAlertsCleared: List<String>,
    val knownSiphonsBefore: Int,
    val knownSiphonsAfter: Int
)

/**
 * A single captured scrape. Field order here is the JSON field order (Gson uses declaration order),
 * so the big `rawText` and `nodeTree` blobs stay at the end of each export.
 */
class RawDump(
    val format: String = FORMAT,
    val version: Int = VERSION,
    var id: Long = 0,
    var capturedAtMs: Long = 0,
    var capturedAtUtc: String = "",
    var capturedAtLocal: String = "",
    var app: RawDumpAppInfo? = null,
    var foregroundPackage: String? = null,
    var timing: RawDumpTiming = RawDumpTiming(),
    var detectedType: String? = null,
    var fallbackUsed: Boolean = false,
    var parsersMatched: MutableList<String> = mutableListOf(),
    var result: String? = null,
    var error: String? = null,
    var logStats: RawDumpLogStats? = null,
    var discardedLogLines: MutableList<DiscardedLogLine> = mutableListOf(),
    var interpretation: MutableMap<String, Any?> = linkedMapOf(),
    var dbEffects: RawDumpDbEffects? = null,
    var rawText: String = "",
    var nodeTree: List<RawDumpNode> = emptyList()
) {
    companion object {
        const val FORMAT = "darkindex-raw-dump"
        const val EXPORT_FORMAT = "darkindex-raw-dumps"
        const val VERSION = 1

        fun create(nowMs: Long = System.currentTimeMillis(), app: RawDumpAppInfo? = null): RawDump {
            val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US)
            return RawDump(
                capturedAtMs = nowMs,
                capturedAtUtc = utc.format(Date(nowMs)),
                capturedAtLocal = local.format(Date(nowMs)),
                app = app
            )
        }

        fun roundMs(ms: Double): Double = Math.round(ms * 100.0) / 100.0
    }
}

/** Small per-dump summary, stored next to the full dump so the list screen never parses big files. */
data class RawDumpSummary(
    val id: Long,
    val capturedAtMs: Long,
    val capturedAtLocal: String?,
    val capturedAtUtc: String?,
    val detectedType: String?,
    val fallbackUsed: Boolean,
    val parsersMatched: List<String>?,
    val result: String?,
    val error: String?,
    val logStats: RawDumpLogStats?,
    val discardedCount: Int,
    val rawChars: Int,
    val nodeCount: Int,
    val foregroundPackage: String?
) {
    companion object {
        fun of(dump: RawDump) = RawDumpSummary(
            id = dump.id,
            capturedAtMs = dump.capturedAtMs,
            capturedAtLocal = dump.capturedAtLocal,
            capturedAtUtc = dump.capturedAtUtc,
            detectedType = dump.detectedType,
            fallbackUsed = dump.fallbackUsed,
            parsersMatched = dump.parsersMatched.toList(),
            result = dump.result,
            error = dump.error?.lineSequence()?.firstOrNull()?.take(200),
            logStats = dump.logStats,
            discardedCount = dump.discardedLogLines.size,
            rawChars = dump.rawText.length,
            nodeCount = dump.nodeTree.size,
            foregroundPackage = dump.foregroundPackage
        )
    }
}

/**
 * Records what one scrape did while Raw Dump Mode is on.
 * Usage: begin() → run the normal pipeline (calling matched()/recordLogParse()) → end().
 * begin()/end() install and remove global hooks, so callers must serialize sessions (see [LOCK]).
 */
class DumpSession(val dump: RawDump) {
    companion object {
        /** Serializes recorded scrapes so the global hooks never see two sessions at once. */
        val LOCK = Any()
        private val PARTIAL_REASONS = setOf(LogDiscardReason.BAD_TIMESTAMP)
    }

    private val dbDiscards = mutableListOf<DiscardedLogLine>()
    private val conflicts = mutableListOf<RawDumpWalletConflict>()
    private val logResults = mutableListOf<LogParseResult>()
    private var recordsBefore: Map<String, TargetRecord> = emptyMap()
    private var profileBefore: PlayerProfile? = null
    private var siphonAlertsBefore: Set<String> = emptySet()
    private var knownSiphonsBefore: Int = 0

    private val discardSink = DatabaseManager.DbDiscardSink { line, reason ->
        synchronized(dbDiscards) { dbDiscards.add(DiscardedLogLine(line, "db", reason)) }
    }
    private val conflictListener: (String, String, String, String) -> Unit = { ip, w1, w2, source ->
        synchronized(conflicts) { conflicts.add(RawDumpWalletConflict(ip, w1, w2, source)) }
    }

    fun begin() {
        recordsBefore = safe { TargetRepository.getAllRecords().associateBy { it.ip } } ?: emptyMap()
        profileBefore = safe { PlayerProfileRepository.getProfile() }
        siphonAlertsBefore = safe { SiphonTracker.getSiphonAlerts().toSet() } ?: emptySet()
        knownSiphonsBefore = safe { SiphonTracker.getKnownSiphons().size } ?: 0
        DatabaseManager.activeDiscardSink = discardSink
        WalletConflictLog.listener = conflictListener
    }

    /** A parser recognised the screen; [interpretation] is what it extracted (serialized as-is). */
    fun matched(parserName: String, interpretation: Any?) {
        if (!dump.parsersMatched.contains(parserName)) dump.parsersMatched.add(parserName)
        dump.interpretation[parserName] = interpretation
    }

    fun recordLogParse(result: LogParseResult) {
        logResults.add(result)
    }

    fun end() {
        if (DatabaseManager.activeDiscardSink === discardSink) DatabaseManager.activeDiscardSink = null
        if (WalletConflictLog.listener === conflictListener) WalletConflictLog.listener = null

        // --- Log statistics & discarded lines (parser stage first, then db stage) ---
        if (logResults.isNotEmpty()) {
            val parserDiscards = logResults.flatMap { it.discarded }
            val dbList = synchronized(dbDiscards) { dbDiscards.toList() }
            val timestamped = logResults.sumOf { it.timestampedLineCount }
            val produced = logResults.sumOf { it.updates.size }
            val dbFull = dbList.count { it.reason !in PARTIAL_REASONS }
            val partial = dbList.count { it.reason in PARTIAL_REASONS }
            dump.discardedLogLines.addAll(parserDiscards)
            dump.discardedLogLines.addAll(dbList)
            dump.logStats = RawDumpLogStats(
                timestampedLines = timestamped,
                interpreted = (produced - dbFull - partial).coerceAtLeast(0),
                discarded = parserDiscards.size + dbFull,
                partial = partial
            )
        }

        // --- Database effects ---
        val after = safe { TargetRepository.getAllRecords().associateBy { it.ip } } ?: emptyMap()
        val created = (after.keys - recordsBefore.keys).sorted()
        val removedIps = (recordsBefore.keys - after.keys).sorted()
        val updated = recordsBefore.keys.intersect(after.keys).sorted().mapNotNull { ip ->
            val fields = changedFields(recordsBefore.getValue(ip), after.getValue(ip))
            if (fields.isEmpty()) null else RawDumpRecordChange(ip, fields)
        }
        val removed = removedIps.map { ip ->
            val old = recordsBefore.getValue(ip)
            val into = after.values.firstOrNull { cand ->
                (old.username != null && cand.username.equals(old.username, ignoreCase = true)) ||
                    old.wallets.any { cand.wallets.contains(it) }
            }?.ip
            RawDumpRemovedRecord(ip, into)
        }
        val profileAfter = safe { PlayerProfileRepository.getProfile() }
        val siphonAlertsAfter = safe { SiphonTracker.getSiphonAlerts().toSet() } ?: emptySet()

        dump.dbEffects = RawDumpDbEffects(
            recordsBefore = recordsBefore.size,
            recordsAfter = after.size,
            created = created,
            updated = updated,
            removed = removed,
            walletConflicts = synchronized(conflicts) { conflicts.toList() },
            profileChanges = profileDiff(profileBefore, profileAfter),
            siphonAlertsAdded = (siphonAlertsAfter - siphonAlertsBefore).sorted(),
            siphonAlertsCleared = (siphonAlertsBefore - siphonAlertsAfter).sorted(),
            knownSiphonsBefore = knownSiphonsBefore,
            knownSiphonsAfter = safe { SiphonTracker.getKnownSiphons().size } ?: 0
        )
    }

    private fun changedFields(a: TargetRecord, b: TargetRecord): List<String> {
        val out = mutableListOf<String>()
        if (a.username != b.username) out.add("username")
        if (a.clan != b.clan) out.add("clan")
        if (a.level != b.level) out.add("level")
        if (a.hardware != b.hardware) out.add("hardware")
        if (a.firewall != b.firewall) out.add("firewall")
        if (a.encryptor != b.encryptor) out.add("encryptor")
        if (a.wallets != b.wallets) out.add("wallets")
        if (a.downloads != b.downloads) out.add("downloads")
        if (a.walletThefts != b.walletThefts) out.add("walletThefts")
        if (a.lastValidated != b.lastValidated) out.add("lastValidated")
        return out
    }

    private fun profileDiff(a: PlayerProfile?, b: PlayerProfile?): Map<String, List<Any?>> {
        if (a == null || b == null || a == b) return emptyMap()
        val out = linkedMapOf<String, List<Any?>>()
        if (a.username != b.username) out["username"] = listOf(a.username, b.username)
        if (a.playerLevel != b.playerLevel) out["playerLevel"] = listOf(a.playerLevel, b.playerLevel)
        if (a.nextDevice != b.nextDevice) out["nextDevice"] = listOf(a.nextDevice, b.nextDevice)
        if (a.nextNetwork != b.nextNetwork) out["nextNetwork"] = listOf(a.nextNetwork, b.nextNetwork)
        if (a.currentDevice != b.currentDevice) out["currentDevice"] = listOf(a.currentDevice, b.currentDevice)
        if (a.currentNetwork != b.currentNetwork) out["currentNetwork"] = listOf(a.currentNetwork, b.currentNetwork)
        if (a.botnetStatus != b.botnetStatus) out["botnetStatus"] = listOf(a.botnetStatus, b.botnetStatus)
        (a.softwareLevels.keys + b.softwareLevels.keys).sorted().forEach { k ->
            if (a.softwareLevels[k] != b.softwareLevels[k]) out["softwareLevels.$k"] = listOf(a.softwareLevels[k], b.softwareLevels[k])
        }
        return out
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Throwable) { null }
}

/**
 * One file per dump (`<id>.json`) plus a tiny summary (`<id>.meta.json`). No cap, no auto-clear.
 * The id counter is kept in `next_id` and is never reused, even after Delete All.
 */
class RawDumpStore(val dir: File) {
    companion object {
        val prettyGson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
        private val compactGson: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

        fun forContext(context: Context): RawDumpStore = RawDumpStore(File(context.filesDir, "raw_dumps"))
    }

    @Synchronized
    fun save(dump: RawDump): Long {
        dir.mkdirs()
        val id = nextId()
        dump.id = id
        writeAtomic(File(dir, "$id.json"), RawDumpJson.fullJson(dump))
        writeAtomic(File(dir, "$id.meta.json"), compactGson.toJson(RawDumpSummary.of(dump)))
        return id
    }

    fun listSummaries(): List<RawDumpSummary> {
        val files = dir.listFiles { f -> f.name.endsWith(".meta.json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try { compactGson.fromJson(f.readText(), RawDumpSummary::class.java) } catch (_: Exception) { null }
        }.sortedByDescending { it.id }
    }

    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".meta.json") }?.size ?: 0

    /** The exact stored JSON for one dump (what single-tap copies). */
    fun readJson(id: Long): String? = File(dir, "$id.json").takeIf { it.exists() }?.readText()

    @Synchronized
    fun delete(id: Long): Boolean {
        val a = File(dir, "$id.json").delete()
        val b = File(dir, "$id.meta.json").delete()
        return a || b
    }

    @Synchronized
    fun deleteAll(): Int {
        var n = 0
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            if (f.name.endsWith(".meta.json")) n++
            f.delete()
        }
        return n
    }

    /** Streams every dump (oldest first) into one JSON document: {format, version, exportedAtUtc, count, dumps:[...]}. */
    fun exportAll(out: Writer): Int {
        val ids = (dir.listFiles { f -> f.name.endsWith(".meta.json") } ?: emptyArray())
            .mapNotNull { it.name.removeSuffix(".meta.json").toLongOrNull() }
            .sorted()
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        out.write("{\n  \"format\": \"${RawDump.EXPORT_FORMAT}\",\n  \"version\": ${RawDump.VERSION},\n")
        out.write("  \"exportedAtUtc\": \"${utc.format(Date())}\",\n  \"count\": ${ids.size},\n  \"dumps\": [\n")
        var written = 0
        for (id in ids) {
            val json = readJson(id) ?: continue
            if (written > 0) out.write(",\n")
            out.write(json)
            written++
        }
        out.write("\n  ]\n}\n")
        out.flush()
        return written
    }

    /** Every dump (oldest first) in the compact text format ([RawDumpCompact]). */
    fun exportCompact(out: Writer): Int {
        val ids = (dir.listFiles { f -> f.name.endsWith(".meta.json") } ?: emptyArray())
            .mapNotNull { it.name.removeSuffix(".meta.json").toLongOrNull() }
            .sorted()
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return RawDumpCompact.export(ids.mapNotNull { readJson(it) }, out, utc.format(Date()))
    }

    private fun nextId(): Long {
        val counter = File(dir, "next_id")
        val fromFile = if (counter.exists()) counter.readText().trim().toLongOrNull() else null
        val existingMax = (dir.listFiles { f -> f.name.endsWith(".meta.json") } ?: emptyArray())
            .mapNotNull { it.name.removeSuffix(".meta.json").toLongOrNull() }
            .maxOrNull() ?: 0L
        val id = maxOf(fromFile ?: 1L, existingMax + 1)
        counter.writeText((id + 1).toString())
        return id
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}

/**
 * JSON shapes for raw dumps.
 *
 * - [fullJson]: what's stored per dump (and what EXPORT ALL concatenates). Gson's reflection order is not
 *   guaranteed on Android (dump #1 came out alphabetical), so keys are re-ordered explicitly:
 *   summary first, big blobs (interpretation, rawText, nodeTree) last.
 * - [leanJson]: what single-tap copies. Built from the *stored* JSON, so it also works on dumps saved by
 *   older builds. Compact, drops empty/duplicate data, Log interpretation as rows that reference raw-text
 *   line numbers, and only the nodeTree rows that carry text/id/desc. Fits a chat paste (<20,000 chars)
 *   for every screen measured so far (largest: a full 100-line log ≈ 9.5k).
 */
object RawDumpJson {
    const val LEAN_FORMAT = "darkindex-raw-dump-lean"
    const val LEAN_VERSION = 1
    /** Chat apps truncate pasted text at 20,000 characters. */
    const val CHAT_PASTE_LIMIT = 20_000
    private const val NODE_TEXT_MAX = 60

    private val TOP_ORDER = listOf(
        "format", "version", "id", "capturedAtUtc", "capturedAtLocal", "capturedAtMs", "app", "foregroundPackage",
        "detectedType", "fallbackUsed", "parsersMatched", "result", "error", "logStats", "discardedLogLines",
        "dbEffects", "timing", "interpretation", "rawText", "nodeTree"
    )
    private val DB_ORDER = listOf(
        "recordsBefore", "recordsAfter", "created", "updated", "removed", "walletConflicts", "profileChanges",
        "siphonAlertsAdded", "siphonAlertsCleared", "knownSiphonsBefore", "knownSiphonsAfter"
    )
    private val LOG_STATS_ORDER = listOf("timestampedLines", "interpreted", "discarded", "partial")
    private val TIMING_ORDER = listOf("extractMs", "detectMs", "parseMs", "totalMs", "nodeCount")
    private val APP_ORDER = listOf("versionName", "versionCode")
    private val DISCARD_ORDER = listOf("line", "stage", "reason")
    private val NODE_ORDER = listOf("d", "cls", "id", "desc", "text", "bounds")
    private val LOG_ENTRY_ORDER = listOf("line", "time", "ip", "wallet", "amount", "ownTheft", "software", "ownedSoftware")

    private val compactGson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** Pretty, explicitly ordered JSON for storage / EXPORT ALL. */
    fun fullJson(dump: RawDump): String =
        RawDumpStore.prettyGson.toJson(ordered(RawDumpStore.prettyGson.toJsonTree(dump).asJsonObject))

    /** Re-orders a full dump's keys (top level and known nested objects). Unknown keys keep their order, at the end. */
    fun ordered(dump: JsonObject): JsonObject {
        val top = reorder(dump, TOP_ORDER)
        top.obj("app")?.let { top.add("app", reorder(it, APP_ORDER)) }
        top.obj("logStats")?.let { top.add("logStats", reorder(it, LOG_STATS_ORDER)) }
        top.obj("dbEffects")?.let { top.add("dbEffects", reorder(it, DB_ORDER)) }
        top.obj("timing")?.let { top.add("timing", reorder(it, TIMING_ORDER)) }
        top.arr("discardedLogLines")?.let { top.add("discardedLogLines", mapObjects(it, DISCARD_ORDER)) }
        top.arr("nodeTree")?.let { top.add("nodeTree", mapObjects(it, NODE_ORDER)) }
        top.obj("interpretation")?.let { interp ->
            interp.arr("Log")?.let { interp.add("Log", mapObjects(it, LOG_ENTRY_ORDER)) }
        }
        return top
    }

    /** Lean, paste-sized JSON from a stored (full) dump's JSON text. */
    fun leanJson(storedJson: String): String = compactGson.toJson(lean(JsonParser.parseString(storedJson).asJsonObject))

    fun lean(full: JsonObject): JsonObject {
        val o = JsonObject()
        o.addProperty("format", LEAN_FORMAT)
        o.addProperty("v", LEAN_VERSION)
        full.prim("id")?.let { o.add("dump", it) }
        full.prim("capturedAtUtc")?.let { o.add("utc", it) }
        full.obj("app")?.let { app ->
            o.addProperty("app", "${app.str("versionName") ?: "?"} (${app.prim("versionCode")?.asString ?: "?"})")
        }
        full.prim("foregroundPackage")?.let { o.add("pkg", it) }
        full.prim("detectedType")?.let { o.add("screen", it) }
        if (full.prim("fallbackUsed")?.asBoolean == true) o.addProperty("fallback", true)
        full.arr("parsersMatched")?.takeIf { it.size() > 0 }?.let { o.add("parsers", it) }
        full.prim("result")?.let { o.add("result", it) }
        full.prim("error")?.let { o.add("error", it) }
        full.obj("logStats")?.let { o.add("logStats", reorder(it, LOG_STATS_ORDER)) }

        full.arr("discardedLogLines")?.takeIf { it.size() > 0 }?.let { list ->
            val rows = JsonArray()
            for (e in list) {
                val d = e.asJsonObject
                rows.add(JsonArray().apply {
                    add(d.str("line") ?: "")
                    add("${d.str("stage") ?: "?"}:${d.str("reason") ?: "?"}")
                })
            }
            o.add("discarded", rows)
        }

        full.obj("dbEffects")?.let { db -> leanDb(db)?.let { o.add("db", it) } }

        full.obj("timing")?.let { t ->
            o.add("ms", JsonObject().apply {
                listOf("extractMs" to "extract", "parseMs" to "parse", "totalMs" to "total").forEach { (k, short) ->
                    t.prim(k)?.let { add(short, it) }
                }
            })
        }

        val rawText = full.str("rawText") ?: ""
        full.obj("interpretation")?.takeIf { it.size() > 0 }?.let { interp ->
            val li = JsonObject()
            for ((key, value) in interp.entrySet()) {
                li.add(key, if (key == "Log" && value.isJsonArray) leanLog(value.asJsonArray, rawText) else value)
            }
            o.add("interp", li)
        }

        o.addProperty("rawText", rawText)

        full.arr("nodeTree")?.let { tree ->
            val rows = JsonArray()
            for (e in tree) {
                val n = e.asJsonObject
                val text = n.str("text")?.takeIf { it.isNotEmpty() }
                val id = n.str("id")
                val desc = n.str("desc")?.takeIf { it.isNotEmpty() }
                if (text == null && id == null && desc == null) continue
                rows.add(JsonArray().apply {
                    add(n.prim("d")?.asInt ?: -1)
                    add(n.str("cls")?.substringAfterLast('.'))
                    add(id)
                    add(desc?.let { clip(it) })
                    add(text?.let { clip(it) })
                    n.arr("bounds")?.forEach { add(it) }
                })
            }
            o.add("nodes", JsonObject().apply {
                add("cols", JsonArray().apply {
                    listOf("d", "cls", "id", "desc", "text", "x1", "y1", "x2", "y2").forEach { add(it) }
                })
                addProperty("total", tree.size())
                add("rows", rows)
            })
        }
        return o
    }

    /**
     * Log entries as rows `[rawLineNo, ip, wallet, amount, ownTheft, software, ownedSoftware]`; the line text itself is
     * in rawText (line number = index in rawText split on \n). Trailing null/false columns are omitted.
     */
    private fun leanLog(entries: JsonArray, rawText: String): JsonObject {
        val lines = rawText.split("\n").map { it.trim() }
        val used = HashSet<Int>()
        val rows = JsonArray()
        for (e in entries) {
            val u = e.asJsonObject
            val line = u.str("line")
            var idx = -1
            if (line != null) {
                for (i in lines.indices) if (i !in used && lines[i] == line) { idx = i; break }
                if (idx >= 0) used.add(idx)
            }
            val row = JsonArray().apply {
                add(idx)
                add(u.str("ip"))
                add(u.str("wallet"))
                add(u.prim("amount")?.asInt)
                add(u.prim("ownTheft")?.asBoolean ?: false)
                add(u.str("software"))
                add(u.prim("ownedSoftware")?.asBoolean ?: false)
            }
            // Trailing null/false columns are implied: [12,"1.2.3.4"] == [12,"1.2.3.4",null,null,false,null,false]
            while (row.size() > 1) {
                val last = row[row.size() - 1]
                if (last.isJsonNull || (last.isJsonPrimitive && last.asJsonPrimitive.isBoolean && !last.asBoolean)) row.remove(row.size() - 1) else break
            }
            if (idx < 0 && line != null) { // couldn't map to rawText: keep the line itself as an extra column
                while (row.size() < 7) row.add(com.google.gson.JsonNull.INSTANCE)
                row.add(line)
            }
            rows.add(row)
        }
        return JsonObject().apply {
            add("cols", JsonArray().apply {
                listOf("rawLine", "ip", "wallet", "amount", "ownTheft", "software", "ownedSoftware").forEach { add(it) }
            })
            add("rows", rows)
        }
    }

    /** dbEffects without empty lists/maps and without unchanged siphon counts. Null when nothing is left. */
    private fun leanDb(db: JsonObject): JsonObject? {
        val out = JsonObject()
        db.prim("recordsBefore")?.let { out.add("recordsBefore", it) }
        db.prim("recordsAfter")?.let { out.add("recordsAfter", it) }
        for (k in listOf("created", "updated", "removed", "walletConflicts", "siphonAlertsAdded", "siphonAlertsCleared")) {
            db.arr(k)?.takeIf { it.size() > 0 }?.let { out.add(k, it) }
        }
        db.obj("profileChanges")?.takeIf { it.size() > 0 }?.let { out.add("profileChanges", it) }
        val ksb = db.prim("knownSiphonsBefore")
        val ksa = db.prim("knownSiphonsAfter")
        if (ksb != null && ksa != null && ksb != ksa) {
            out.add("knownSiphonsBefore", ksb); out.add("knownSiphonsAfter", ksa)
        }
        return if (out.size() == 0) null else out
    }

    private fun clip(s: String): String = if (s.length > NODE_TEXT_MAX) s.take(NODE_TEXT_MAX - 1) + "…" else s

    private fun reorder(obj: JsonObject, order: List<String>): JsonObject {
        val out = JsonObject()
        for (k in order) if (obj.has(k)) out.add(k, obj.get(k))
        for ((k, v) in obj.entrySet()) if (!out.has(k)) out.add(k, v)
        return out
    }

    private fun mapObjects(arr: JsonArray, order: List<String>): JsonArray = JsonArray().apply {
        for (e in arr) add(if (e.isJsonObject) reorder(e.asJsonObject, order) else e)
    }

    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.prim(k: String): com.google.gson.JsonPrimitive? = get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
    private fun JsonObject.str(k: String): String? = prim(k)?.asString
}
