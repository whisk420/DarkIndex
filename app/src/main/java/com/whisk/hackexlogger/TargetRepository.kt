package com.whisk.hackexlogger

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TargetRepository {
    const val PREFS_NAME = "HackExDB"
    const val KEY_TARGETS = "targets"

    lateinit var prefs: SharedPreferences
    lateinit var appContext: Context
    val gson = Gson()
    val cachedRecords = mutableListOf<TargetRecord>()
    
    private val _recordsFlow = MutableStateFlow<List<TargetRecord>>(emptyList())
    val recordsFlow: StateFlow<List<TargetRecord>> = _recordsFlow.asStateFlow()
    
    @Volatile var isInitialized = false

    fun init(context: Context) {
        if (!isInitialized) {
            appContext = context.applicationContext
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadCacheFromDisk()
            isInitialized = true
        }
    }

    private fun loadCacheFromDisk() {
        try {
            val json = prefs.getString(KEY_TARGETS, "[]")
            val loadedArray: Array<TargetRecord>? = gson.fromJson(json, Array<TargetRecord>::class.java)
            synchronized(cachedRecords) {
                cachedRecords.clear()
                if (loadedArray != null) {
                    for (record in loadedArray) {
                        sanitizeRecord(record)
                        cachedRecords.add(record)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TargetRepository", "Error loading target records cache: ${e.message}", e)
        }
    }

    fun sanitizeRecord(record: TargetRecord) {
        val fields = TargetRecord::class.java.declaredFields
        for (field in fields) {
            field.isAccessible = true
            try {
                if (field.get(record) == null) {
                    val fieldType = field.type
                    if (List::class.java.isAssignableFrom(fieldType)) {
                        field.set(record, mutableListOf<String>())
                    } else if (Map::class.java.isAssignableFrom(fieldType)) {
                        field.set(record, mutableMapOf<String, Any>())
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun getAllRecords(): List<TargetRecord> {
        if (!isInitialized) return emptyList()
        synchronized(cachedRecords) {
            return cachedRecords.map { record ->
                sanitizeRecord(record)
                record.copy(
                    wallets = record.wallets.toMutableList(),
                    downloads = record.downloads.toMutableMap(),
                    walletThefts = record.walletThefts.mapValues { it.value.toMutableList() }.toMutableMap()
                )
            }
        }
    }

    fun notifyDataUpdated() {
        _recordsFlow.value = getAllRecords()
        if (::appContext.isInitialized) {
            try {
                val intent = android.content.Intent("com.whisk.hackexlogger.DATA_UPDATED")
                    .setPackage(appContext.packageName)
                appContext.sendBroadcast(intent)
            } catch (_: Exception) {}
        }
    }

    fun getDownloadsBackupFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloadsDir, "HackExLogger")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "hackex_database.json")
    }

    fun saveAllRecords(records: List<TargetRecord>) {
        synchronized(cachedRecords) {
            cachedRecords.clear()
            cachedRecords.addAll(records)
        }
        val json = gson.toJson(records)
        prefs.edit().putString(KEY_TARGETS, json).apply()

        try {
            if (records.isNotEmpty()) {
                val downloadsFile = getDownloadsBackupFile()
                val bundleJson = exportDataBundle()
                downloadsFile.writeText(bundleJson)
            }
        } catch (e: Exception) {
            android.util.Log.e("TargetRepository", "Downloads auto-backup failed: ${e.message}", e)
        }

        notifyDataUpdated()
    }

    fun wipeDatabase() {
        synchronized(cachedRecords) {
            cachedRecords.clear()
        }
        prefs.edit().remove(KEY_TARGETS).apply()
        notifyDataUpdated()
    }

    fun getNextTargetForAdvancedTargeting(): TargetRecord? {
        val candidates = getAllRecords().filter { it.needsAdvancedTargeting }
        val nullChecked = candidates.filter { it.lastValidated == null }
        if (nullChecked.isNotEmpty()) {
            return nullChecked.first()
        }
        return candidates.minByOrNull { it.lastValidated ?: "" }
    }

    fun markTargetValidated(ip: String) {
        val currentRecords = getAllRecords().toMutableList()
        val record = currentRecords.find { it.ip == ip } ?: return
        record.lastValidated = getCurrentTimestamp()
        saveAllRecords(currentRecords)
    }

    fun getNextRecordToValidate(): TargetRecord? {
        val unmaskedRecords = getAllRecords().filter { !it.isMasked }
        if (unmaskedRecords.isEmpty()) return null
        val neverValidated = unmaskedRecords.filter { it.lastValidated == null }
        if (neverValidated.isNotEmpty()) {
            return neverValidated.first()
        }
        return unmaskedRecords.minByOrNull { it.lastValidated ?: "" }
    }

    fun markValidated(ip: String) {
        val currentRecords = getAllRecords().toMutableList()
        val record = currentRecords.find { it.ip == ip } ?: return
        record.lastValidated = getCurrentTimestamp()
        saveAllRecords(currentRecords)
    }

    fun editTargetIp(oldIp: String, newIp: String) {
        val currentRecords = getAllRecords().toMutableList()
        val existing = currentRecords.find { it.ip == oldIp } ?: return
        currentRecords.remove(existing)
        val collision = currentRecords.find { it.ip == newIp }
        if (collision != null) {
            currentRecords.remove(collision)
            val merged = mergeRecords(collision, existing)
            merged.ip = newIp
            currentRecords.add(merged)
        } else {
            existing.ip = newIp
            currentRecords.add(existing)
        }
        saveAllRecords(currentRecords)
        reconcileDatabase()
    }

    fun deleteTarget(ip: String) {
        val currentRecords = getAllRecords().toMutableList()
        currentRecords.removeAll { it.ip == ip }
        saveAllRecords(currentRecords)
    }

    fun clearTargetTheftEvents(ip: String, wallet: String? = null) {
        val currentRecords = getAllRecords().toMutableList()
        val record = currentRecords.find { it.ip == ip } ?: return
        if (wallet != null) {
            record.clearTheftEvents(wallet)
        } else {
            record.walletThefts.clear()
        }
        saveAllRecords(currentRecords)
        if (::appContext.isInitialized) {
            appContext.sendBroadcast(android.content.Intent("com.whisk.hackexlogger.DATA_UPDATED"))
        }
    }

    fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    }

    fun runDatabaseMaintenance(): DatabaseManager.MaintenanceResult {
        val backupJson = exportDataBundle()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupFile = File(appContext.filesDir, "maintenance_backup_$timestamp.json")
        backupFile.writeText(backupJson)

        val records = getAllRecords().toMutableList()
        val recordMap = records.associateBy { it.ip }.toMutableMap()

        var multiWalletCount = 0
        var maskedSoftwareCount = 0
        var maskedWalletsMigratedCount = 0
        var oldTheftsClearedCount = 0

        for (record in recordMap.values.toList()) {
            val walletsToProcess = record.walletThefts.keys.toList()
            for (wallet in walletsToProcess) {
                val list = record.walletThefts[wallet]
                if (list != null && list.size > 1) {
                    list.sortBy { it.timestampMs }
                    var startIndex = 0
                    for (i in 1 until list.size) {
                        if (list[i].timestampMs - list[i - 1].timestampMs > 24 * 3600000L) {
                            startIndex = i
                        }
                    }
                    if (startIndex > 0) {
                        oldTheftsClearedCount += startIndex
                        val recentCluster = list.subList(startIndex, list.size).toMutableList()
                        record.walletThefts[wallet] = recentCluster
                    }
                }
            }

            if (record.wallets.size > 1) {
                val walletsToDrop = record.wallets.toList()
                record.wallets.clear()
                multiWalletCount++

                for (w in walletsToDrop) {
                    logWalletConflict(record.ip, walletsToDrop[0], w, "MaintenanceCleanupAllDropped")
                    val owner = recordMap.values.find { !it.isMasked && it.wallets.contains(w) }
                    if (owner == null) {
                        val placeholderIp = "unknown:wallet_$w"
                        var placeholder = recordMap[placeholderIp]
                        if (placeholder == null) {
                            placeholder = TargetRecord(ip = placeholderIp, wallets = mutableListOf(w))
                            recordMap[placeholderIp] = placeholder
                        } else if (!placeholder.wallets.contains(w)) {
                            placeholder.wallets.add(w)
                        }
                        val thefts = record.walletThefts.remove(w)
                        if (thefts != null) {
                            placeholder.walletThefts.getOrPut(w) { mutableListOf() }.addAll(thefts)
                        }
                    }
                }
            }

            if (record.isMasked) {
                if (record.downloads.isNotEmpty()) {
                    record.downloads.clear()
                    maskedSoftwareCount++
                }

                if (record.wallets.isNotEmpty()) {
                    val walletToMigrate = record.wallets.first()
                    record.wallets.clear()
                    maskedWalletsMigratedCount++

                    val owner = recordMap.values.find { !it.isMasked && it.wallets.contains(walletToMigrate) }
                    if (owner == null) {
                        val placeholderIp = "unknown:wallet_$walletToMigrate"
                        var placeholder = recordMap[placeholderIp]
                        if (placeholder == null) {
                            placeholder = TargetRecord(ip = placeholderIp, wallets = mutableListOf(walletToMigrate))
                            recordMap[placeholderIp] = placeholder
                        } else if (!placeholder.wallets.contains(walletToMigrate)) {
                            placeholder.wallets.add(walletToMigrate)
                        }
                        val thefts = record.walletThefts.remove(walletToMigrate)
                        if (thefts != null) {
                            placeholder.walletThefts.getOrPut(walletToMigrate) { mutableListOf() }.addAll(thefts)
                        }
                    }
                }
            }
        }

        saveAllRecords(recordMap.values.toList())
        reconcileDatabase()

        return DatabaseManager.MaintenanceResult(
            backupFilePath = backupFile.absolutePath,
            multiWalletRecordsFixed = multiWalletCount,
            maskedSoftwareCleared = maskedSoftwareCount,
            maskedWalletsMigrated = maskedWalletsMigratedCount,
            oldTheftsCleared = oldTheftsClearedCount
        )
    }

    fun exportDataBundle(): String {
        val records = getAllRecords()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val bundle = ConsoleBundle(
            format = "target-intelligence-console",
            version = 1,
            exportedAt = isoFormat.format(Date()),
            targets = records.toTypedArray(),
            ingests = emptyArray()
        )
        val exportGson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        return exportGson.toJson(bundle)
    }

    fun importDataBundle(jsonString: String): ImportResult {
        val bundle = try {
            gson.fromJson(jsonString, ConsoleBundle::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON format: ${e.message}", e)
        }

        if (bundle == null || bundle.format != "target-intelligence-console" || bundle.version != 1) {
            throw IllegalArgumentException("This is not a Target Intelligence Console export.")
        }

        val importedTargets = bundle.targets ?: emptyArray()
        val importedIngests = bundle.ingests ?: emptyArray()

        val currentRecords = getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()

        for (imported in importedTargets) {
            try {
                sanitizeRecord(imported)
                val ip = imported.ip
                if (ip.isBlank()) continue

                val existing = recordMap[ip]
                if (existing != null) {
                    val merged = mergeRecords(existing, imported)
                    recordMap[ip] = merged
                } else {
                    val previousMatch = recordMap.values.find { candidate ->
                        (imported.username != null && candidate.username != null && candidate.username.equals(imported.username, ignoreCase = true)) ||
                        (imported.wallets.isNotEmpty() && candidate.wallets.any { imported.wallets.contains(it) })
                    }
                    if (previousMatch != null) {
                        val merged = mergeRecords(previousMatch, imported)
                        merged.ip = if (!imported.isMasked && previousMatch.isMasked) imported.ip else merged.ip
                        recordMap.remove(previousMatch.ip)
                        recordMap[merged.ip] = merged
                    } else {
                        recordMap[ip] = imported
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TargetRepository", "Error merging target record: ${e.message}", e)
            }
        }

        // Process raw ingests included in the bundle
        for (ingest in importedIngests) {
            val raw = ingest.raw
            if (raw.isNullOrEmpty()) continue
            try {
                when (ingest.type) {
                    "logs" -> {
                        val lines = raw.lines()
                        val updates = LogScreenParser.parse(lines)
                        DatabaseManager.processUpdates(updates)
                    }
                    "home" -> {
                        val homeScreen = HomeScreenParser.parse(raw)
                        if (homeScreen != null) {
                            DatabaseManager.processHomeScreen(homeScreen)
                        }
                    }
                    "software" -> {
                        val softwareScreen = SoftwareScreenParser.parse(raw)
                        if (softwareScreen != null) {
                            DatabaseManager.processSoftwareScreen(softwareScreen)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TargetRepository", "Error processing ingest: ${e.message}", e)
            }
        }

        saveAllRecords(recordMap.values.toList())
        reconcileDatabase()

        if (::appContext.isInitialized) {
            try {
                appContext.sendBroadcast(android.content.Intent("com.whisk.hackexlogger.DATA_UPDATED"))
            } catch (_: Exception) {}
        }

        return ImportResult(importedTargets.size, importedIngests.size)
    }

    fun mergeRecords(base: TargetRecord, incoming: TargetRecord): TargetRecord {
        val baseIsMasked = base.isMasked
        val incomingIsMasked = incoming.isMasked
        val canonicalIp = if ((!incomingIsMasked && baseIsMasked) || (base.isTemporary && !incoming.isTemporary)) incoming.ip else base.ip
        val ignoreMaskedData = (baseIsMasked != incomingIsMasked)

        val finalWallets = if (ignoreMaskedData) {
            val unmasked = if (!baseIsMasked) base else incoming
            unmasked.wallets.toMutableList()
        } else {
            val distinctWallets = (base.wallets + incoming.wallets).distinct()
            if (distinctWallets.size > 1) {
                logWalletConflict(canonicalIp, distinctWallets[0], distinctWallets[1], "MergeRecords")
                mutableListOf(distinctWallets[0])
            } else {
                distinctWallets.toMutableList()
            }
        }

        val mergedDownloads = if (ignoreMaskedData) {
            val unmasked = if (!baseIsMasked) base else incoming
            unmasked.downloads.toMutableMap()
        } else {
            (base.downloads + incoming.downloads).toMutableMap()
        }

        val mergedThefts = if (ignoreMaskedData) {
            val unmasked = if (!baseIsMasked) base else incoming
            unmasked.walletThefts.toMutableMap()
        } else {
            (base.walletThefts.keys + incoming.walletThefts.keys).distinct().associateWith { wallet ->
                val listA = base.walletThefts[wallet] ?: emptyList()
                val listB = incoming.walletThefts[wallet] ?: emptyList()
                (listA + listB).distinctBy { "${it.timestampMs}_${it.amount}" }.sortedBy { it.timestampMs }.toMutableList()
            }.toMutableMap()
        }

        return TargetRecord(
            ip = canonicalIp,
            username = incoming.username ?: base.username,
            clan = incoming.clan ?: base.clan,
            level = incoming.level ?: base.level,
            hardware = incoming.hardware ?: base.hardware,
            firewall = incoming.firewall ?: base.firewall,
            encryptor = incoming.encryptor ?: base.encryptor,
            wallets = finalWallets,
            downloads = mergedDownloads,
            walletThefts = mergedThefts,
            lastValidated = incoming.lastValidated ?: base.lastValidated
        )
    }

    fun logWalletConflict(ip: String, wallet1: String, wallet2: String, source: String) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val nowStr = isoFormat.format(Date())
            val exportFile = File(appContext.filesDir, "wallet_conflicts.jsonl")
            val logLine = """{"timestamp":"$nowStr","ip":"$ip","existing_wallet":"$wallet1","conflicting_wallet":"$wallet2","source":"$source"}"""
            exportFile.appendText("$logLine\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun reconcileDatabase() {
        val records = getAllRecords().toMutableList()
        if (records.size < 2) return

        var changed = false
        var i = 0
        while (i < records.size) {
            var j = i + 1
            while (j < records.size) {
                val recA = records[i]
                val recB = records[j]

                val sharesUser = recA.username != null && recB.username != null &&
                               recA.username.equals(recB.username, ignoreCase = true)
                val sharesWallet = recA.wallets.any { recB.wallets.contains(it) }

                if (sharesUser || sharesWallet) {
                    val merged = mergeRecords(recA, recB)
                    records[i] = merged
                    records.removeAt(j)
                    changed = true
                    j--
                }
                j++
            }
            i++
        }

        if (changed) {
            saveAllRecords(records)
        }
    }
}
