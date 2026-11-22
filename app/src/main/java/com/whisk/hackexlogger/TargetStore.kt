package com.whisk.hackexlogger

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File storage for the target database: `filesDir/targets.json`, a compact JSON array of [TargetRecord]s.
 *
 * The array is the same format the app stored in SharedPreferences (`HackExDB` / `targets`) up to 2026-11-22,
 * so the legacy prefs string and this file are read by the same code.
 *
 * **Nothing is ever thrown away while loading:** records that can't be read are written to
 * `targets_skipped_<time>.json`, a file that can't be read at all is renamed to `targets_unreadable_<time>.json`,
 * and the original prefs text is kept as `targets_from_prefs_<time>.json` when it is migrated.
 */
object TargetStore {
    const val FILE_NAME = "targets.json"
    private val gson = Gson()

    /** Records read from JSON, plus the array elements that couldn't be turned into a record. */
    class Parsed(val records: List<TargetRecord>, val skipped: List<JsonElement>)

    /**
     * Reads a JSON array of records one element at a time, so a single bad or incompatible record doesn't stop
     * the rest from loading. Throws if the text isn't a JSON array at all.
     */
    fun parse(reader: Reader): Parsed {
        val root = JsonParser.parseReader(reader)
        if (root.isJsonNull) return Parsed(emptyList(), emptyList())
        val array = root.asJsonArray
        val records = ArrayList<TargetRecord>(array.size())
        val skipped = ArrayList<JsonElement>()
        for (element in array) {
            val record = try {
                gson.fromJson(element, TargetRecord::class.java)?.normalized()
            } catch (_: Exception) {
                null
            }
            if (record != null) records.add(record) else skipped.add(element)
        }
        return Parsed(records, skipped)
    }

    fun parse(json: String): Parsed = parse(json.reader())

    /** Reads [file]; null if it doesn't exist. Throws if it exists but isn't a JSON array. */
    fun read(file: File): Parsed? {
        if (!file.exists()) return null
        return file.bufferedReader().use { parse(it) }
    }

    /**
     * Writes [records] to [file] atomically: a temp file is written and then renamed over the old one, so a crash
     * mid-write leaves the previous database intact instead of a half-written one.
     */
    fun write(file: File, records: List<TargetRecord>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { gson.toJson(records, it) }
        if (!tmp.renameTo(file)) {
            // Some file systems refuse to rename over an existing file
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("Could not replace ${file.name}")
        }
    }

    /** Saves elements that couldn't be loaded next to the database, so they can be inspected or recovered. */
    fun keepSkipped(dir: File, skipped: List<JsonElement>): File? {
        if (skipped.isEmpty()) return null
        val out = File(dir, "targets_skipped_${stamp()}.json")
        val array = JsonArray().apply { skipped.forEach { add(it) } }
        out.writeText(gson.toJson(array))
        return out
    }

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
