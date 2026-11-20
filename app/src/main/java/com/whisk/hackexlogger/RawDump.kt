package com.whisk.hackexlogger

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
        TargetRepository.walletConflictListener = conflictListener
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
        if (TargetRepository.walletConflictListener === conflictListener) TargetRepository.walletConflictListener = null

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
        writeAtomic(File(dir, "$id.json"), prettyGson.toJson(dump))
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
