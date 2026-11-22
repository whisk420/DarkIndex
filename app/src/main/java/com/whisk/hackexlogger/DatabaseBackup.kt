package com.whisk.hackexlogger

import com.google.gson.GsonBuilder
import java.io.StringWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Export and import of the whole database as a [ConsoleBundle] (`target-intelligence-console` v1). The same format
 * is the Downloads auto-backup, so every app version can import every other version's backup.
 */
object DatabaseBackup {

    private val exportGson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    private fun currentBundle(): ConsoleBundle {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return ConsoleBundle(
            format = "target-intelligence-console",
            version = 1,
            exportedAt = isoFormat.format(Date()),
            targets = TargetRepository.getAllRecords().toTypedArray(),
            ingests = emptyArray(),
            siphonAlerts = SiphonTracker.getSiphonAlerts().toTypedArray(),
            knownSiphons = SiphonTracker.getKnownSiphons(),
            playerProfile = if (PlayerProfileRepository.isReady()) PlayerProfileRepository.getProfile().takeIf { it != PlayerProfile() } else null,
            theftTimesUtc = true
        )
    }

    /** Streams the export to [writer] (no whole-database string in memory). */
    fun writeDataBundle(writer: Writer) {
        exportGson.toJson(currentBundle(), writer)
    }

    fun exportDataBundle(): String = StringWriter().also { writeDataBundle(it) }.toString()

    /**
     * Before 2026-11-20, `[M-D H:MM]` log times (UTC in the game) were read as phone-local time, so every
     * stored theft is off by the phone's UTC offset at that moment. Moves each one to the correct UTC time.
     * @return the corrected records and the number of theft events moved.
     */
    fun shiftTheftsLocalToUtc(records: List<TargetRecord>, zone: TimeZone): Pair<List<TargetRecord>, Int> {
        var count = 0
        val shifted = records.map { record ->
            if (record.walletThefts.values.all { it.isEmpty() }) record
            else record.copy(walletThefts = record.walletThefts.mapValues { (_, events) ->
                count += events.size
                events.map { TheftEvent(it.timestampMs + zone.getOffset(it.timestampMs), it.amount) }.sortedBy { it.timestampMs }
            })
        }
        return shifted to count
    }

    fun importDataBundle(jsonString: String): ImportResult {
        val bundle = try {
            TargetRepository.gson.fromJson(jsonString, ConsoleBundle::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON format: ${e.message}", e)
        }

        if (bundle == null || bundle.format != "target-intelligence-console" || bundle.version != 1) {
            throw IllegalArgumentException("This is not a Target Intelligence Console export.")
        }

        SiphonTracker.restoreSiphonData(bundle.siphonAlerts?.toList(), bundle.knownSiphons)
        if (bundle.playerProfile != null && PlayerProfileRepository.isReady()) {
            PlayerProfileRepository.restoreFromBackup(bundle.playerProfile)
        }

        val rawTargets = bundle.targets ?: emptyArray()
        val importedIngests = bundle.ingests ?: emptyArray()
        var importedTargets = rawTargets.mapNotNull { it?.normalized() }
        // Exports made before the UTC fix carry phone-local theft times
        if (bundle.theftTimesUtc != true) {
            importedTargets = shiftTheftsLocalToUtc(importedTargets, TimeZone.getDefault()).first
        }

        // Save the merged targets first: the ingests below read and write through DatabaseManager, and saving
        // afterwards used to overwrite everything they added (bug B2).
        TargetRepository.update { current ->
            val recordMap = LinkedHashMap<String, TargetRecord>()
            current.forEach { recordMap[it.ip] = it }
            for (imported in importedTargets) {
                try {
                    val existing = recordMap[imported.ip]
                    if (existing != null) {
                        recordMap[imported.ip] = RecordMerger.mergeRecords(existing, imported)
                    } else {
                        val previousMatch = recordMap.values.find { candidate ->
                            (imported.username != null && candidate.username != null && candidate.username.equals(imported.username, ignoreCase = true)) ||
                                (imported.wallets.isNotEmpty() && candidate.wallets.any { imported.wallets.contains(it) })
                        }
                        if (previousMatch != null) {
                            val merged = RecordMerger.mergeRecords(previousMatch, imported)
                            recordMap.remove(previousMatch.ip)
                            recordMap[merged.ip] = merged
                        } else {
                            recordMap[imported.ip] = imported
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DatabaseBackup", "Error merging target record: ${e.message}", e)
                }
            }
            recordMap.values.toList()
        }

        for (ingest in importedIngests) {
            val raw = ingest.raw
            if (raw.isNullOrEmpty()) continue
            try {
                when (ingest.type) {
                    "logs" -> DatabaseManager.processUpdates(LogScreenParser.parse(raw.lines()))
                    "home" -> HomeScreenParser.parse(raw)?.let { DatabaseManager.processHomeScreen(it) }
                    "software" -> SoftwareScreenParser.parse(raw)?.let { DatabaseManager.processSoftwareScreen(it) }
                }
            } catch (e: Exception) {
                android.util.Log.e("DatabaseBackup", "Error processing ingest: ${e.message}", e)
            }
        }

        TargetRepository.reconcileDatabase()
        return ImportResult(rawTargets.size, importedIngests.size)
    }
}
