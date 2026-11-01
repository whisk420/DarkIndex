package com.whisk.hackexlogger

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Local data manager for persisting, querying, merging, and exporting HackEx target records.
 */
object DatabaseManager {
    private const val PREFS_NAME = "HackExDB"
    private const val KEY_TARGETS = "targets"
    private const val KEY_SIPHON_ALERTS = "siphon_alerts"
    private const val KEY_KNOWN_SIPHONS = "known_siphons"
    
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val gson = Gson()
    private val cachedRecords = mutableListOf<TargetRecord>()
    
    // IP -> Amount Siphoned map
    private val knownSiphons = mutableMapOf<String, Int>()
    
    // Set of IPs that were removed (alerted)
    private val siphonAlerts = mutableSetOf<String>()
    
    private val recentlyScrapedSiphons = mutableMapOf<String, Long>()
    
    @Volatile private var isInitialized = false

    /**
     * Initializes the local storage manager and populates the in-memory cache from private preferences.
     */
    fun init(context: Context) {
        if (!isInitialized) {
            appContext = context.applicationContext
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadCacheFromDisk()
            isInitialized = true
        }
    }

    /**
     * Deserializes target records stored in app-private [SharedPreferences] into memory.
     */
    private fun loadCacheFromDisk() {
        val json = prefs.getString(KEY_TARGETS, "[]")
        val type = object : TypeToken<List<TargetRecord>>() {}.type
        val loaded: List<TargetRecord>? = gson.fromJson(json, type)
        synchronized(cachedRecords) {
            cachedRecords.clear()
            if (loaded != null) {
                for (record in loaded) {
                    sanitizeRecord(record)
                }
                cachedRecords.addAll(loaded)
            }
        }
        
        val alertsJson = prefs.getString(KEY_SIPHON_ALERTS, "[]")
        val alertsType = object : TypeToken<Set<String>>() {}.type
        val loadedAlerts: Set<String>? = gson.fromJson(alertsJson, alertsType)
        if (loadedAlerts != null) {
            siphonAlerts.clear()
            siphonAlerts.addAll(loadedAlerts)
        }
        
        val siphonsJson = prefs.getString(KEY_KNOWN_SIPHONS, "{}")
        val siphonsType = object : TypeToken<Map<String, Int>>() {}.type
        val loadedSiphons: Map<String, Int>? = gson.fromJson(siphonsJson, siphonsType)
        if (loadedSiphons != null) {
            knownSiphons.clear()
            knownSiphons.putAll(loadedSiphons)
        }
    }

    /**
     * Ensures deserialized objects from JSON have non-null collections even if missing in legacy saved data.
     */
    private fun sanitizeRecord(record: TargetRecord) {
        val fields = TargetRecord::class.java.declaredFields
        for (field in fields) {
            field.isAccessible = true
            if (field.get(record) == null) {
                when (field.name) {
                    "wallets" -> field.set(record, mutableListOf<String>())
                    "downloads" -> field.set(record, mutableMapOf<String, SoftwareEntry>())
                    "walletThefts" -> field.set(record, mutableMapOf<String, MutableList<TheftEvent>>())
                }
            }
        }
    }

    /**
     * Retrieves a thread-safe deep copy of all currently stored target records.
     *
     * @return List of [TargetRecord] objects.
     */
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

    /**
     * Saves the provided target list to the in-memory cache and persists it to disk via [SharedPreferences].
     */
    private fun saveAllRecords(records: List<TargetRecord>) {
        synchronized(cachedRecords) {
            cachedRecords.clear()
            cachedRecords.addAll(records)
        }
        val json = gson.toJson(records)
        prefs.edit().putString(KEY_TARGETS, json).apply()
    }
    
    private fun saveSiphonData() {
        prefs.edit()
            .putString(KEY_SIPHON_ALERTS, gson.toJson(siphonAlerts))
            .putString(KEY_KNOWN_SIPHONS, gson.toJson(knownSiphons))
            .apply()
    }

    /**
     * Clears all local target records from memory and app-private disk storage.
     */
    fun wipeDatabase() {
        synchronized(cachedRecords) {
            cachedRecords.clear()
        }
        prefs.edit().remove(KEY_TARGETS).apply()
    }

    /**
     * Finds the next unmasked target record that requires verification/validation in-game.
     * Prioritizes unmasked targets that have never been validated, followed by the oldest validated.
     */
    fun getNextRecordToValidate(): TargetRecord? {
        val unmaskedRecords = getAllRecords().filter { !it.isMasked }
        if (unmaskedRecords.isEmpty()) return null

        val neverValidated = unmaskedRecords.filter { it.lastValidated == null }
        if (neverValidated.isNotEmpty()) {
            return neverValidated.first()
        }

        return unmaskedRecords.minByOrNull { it.lastValidated ?: "" }
    }

    /**
     * Sets the validation timestamp for the specified target IP to the current date and time.
     *
     * @param ip Target IP address.
     */
    fun markValidated(ip: String) {
        val currentRecords = getAllRecords().toMutableList()
        val record = currentRecords.find { it.ip == ip } ?: return
        record.lastValidated = getCurrentTimestamp()
        saveAllRecords(currentRecords)
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    }

    /**
     * Helper to parse log timestamps like "10-16 1:16" or "10-15 23:40" into epoch milliseconds.
     */
    fun parseLogTime(timeStr: String): Long? {
        val match = Regex("""^(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})$""").find(timeStr.trim()) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val hour = match.groupValues[3].toIntOrNull() ?: return null
        val minute = match.groupValues[4].toIntOrNull() ?: return null

        val cal = java.util.Calendar.getInstance()
        var year = cal.get(java.util.Calendar.YEAR)
        val currentMonth = cal.get(java.util.Calendar.MONTH) + 1

        if (month > currentMonth + 1) {
            year -= 1
        }

        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Exports all stored target data as a formatted JSON bundle for backup or migration.
     *
     * @return Pretty-printed JSON string conforming to [ConsoleBundle] schema.
     */
    fun exportDataBundle(): String {
        val records = getAllRecords()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val bundle = ConsoleBundle(
            format = "target-intelligence-console",
            version = 1,
            exportedAt = isoFormat.format(Date()),
            targets = records,
            ingests = emptyList()
        )
        val exportGson = GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create()
        return exportGson.toJson(bundle)
    }

    /**
     * Imports a JSON bundle, merging new target records and updating existing entries.
     *
     * @param jsonString Serialized JSON bundle.
     * @return [ImportResult] containing the count of processed targets and ingests.
     * @throws IllegalArgumentException If JSON format or header schema is invalid.
     */
    fun importDataBundle(jsonString: String): ImportResult {
        val bundle = try {
            gson.fromJson(jsonString, ConsoleBundle::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON format: ${e.message}", e)
        }

        if (bundle == null || bundle.format != "target-intelligence-console" || bundle.version != 1) {
            throw IllegalArgumentException("This is not a Target Intelligence Console export.")
        }

        val importedTargets = bundle.targets ?: emptyList()
        val importedIngests = bundle.ingests ?: emptyList()

        val currentRecords = getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()

        // Merge imported target records with existing database records
        for (imported in importedTargets) {
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
        }

        // Process raw ingests included in the bundle
        for (ingest in importedIngests) {
            val raw = ingest.raw
            if (raw.isNullOrEmpty()) continue
            when (ingest.type) {
                "logs" -> {
                    val lines = raw.lines()
                    val updates = HackExParser.parseLogs(lines)
                    applyUpdatesToMap(recordMap, updates)
                }
                "home" -> {
                    val homeScreen = HackExParser.parseHomeScreen(raw)
                    if (homeScreen != null) {
                        applyHomeScreenToMap(recordMap, homeScreen)
                    }
                }
                "software" -> {
                    val softwareScreen = HackExParser.parseSoftwareScreen(raw)
                    if (softwareScreen != null) {
                        applySoftwareScreenToMap(recordMap, softwareScreen)
                    }
                }
            }
        }

        saveAllRecords(recordMap.values.toList())
        reconcileDatabase()

        return ImportResult(importedTargets.size, importedIngests.size)
    }

    /**
     * Applies parsed log updates to the database.
     *
     * @param updates List of [HackExParser.ParsedUpdate] instances from log scraping.
     */
    fun processUpdates(updates: List<HackExParser.ParsedUpdate>) {
        if (updates.isEmpty()) return
        val currentRecords = getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()
        applyUpdatesToMap(recordMap, updates)
        saveAllRecords(recordMap.values.toList())
        reconcileDatabase()
    }

    private fun applyUpdatesToMap(recordMap: MutableMap<String, TargetRecord>, updates: List<HackExParser.ParsedUpdate>) {
        for (update in updates) {
            var ip = update.ip
            val isIpMasked = ip.isEmpty() || ip.contains("xxx", ignoreCase = true) || ip.contains("UNKNOWN", ignoreCase = true)
            
            if (update.wallet != null) {
                val existingOwner = recordMap.values.find { it.wallets.contains(update.wallet) }
                if (isIpMasked) {
                    if (existingOwner != null) {
                        ip = existingOwner.ip
                    } else {
                        ip = "unknown:wallet_${update.wallet}"
                    }
                }
            }

            if (ip.isEmpty()) continue

            var record = recordMap[ip]
            if (record == null) {
                record = TargetRecord(ip = ip)
                recordMap[ip] = record
            }

            if (update.wallet != null) {
                if (record.wallets.isNotEmpty() && !record.wallets.contains(update.wallet)) {
                    logWalletConflict(ip, record.wallets.first(), update.wallet, "LogUpdate")
                } else {
                    val previousOwner = recordMap.values.find { it.ip != ip && it.wallets.contains(update.wallet) }
                    if (previousOwner != null) {
                        val merged = mergeRecords(record, previousOwner)
                        merged.ip = ip
                        recordMap.remove(previousOwner.ip)
                        record = merged
                        recordMap[ip] = record
                    }
                    if (!record.wallets.contains(update.wallet)) {
                        record.wallets.add(update.wallet)
                    }
                    
                    if (update.stolenAmount != null && update.time != null) {
                        val timestampMs = parseLogTime(update.time)
                        if (timestampMs != null) {
                            record.addTheftEvent(update.wallet, timestampMs, update.stolenAmount)
                        }
                    }
                }
            }

            if (update.software != null && update.isOwnedSoftware) {
                // Do not attach software from logs if the log target IP is masked
                if (!record.isMasked) {
                    record.downloads[update.software.name] = SoftwareEntry(
                        level = update.software.level,
                        status = update.software.action,
                        lastSeen = update.time
                    )
                }
            }
        }
    }

    /**
     * Updates or creates a target record from parsed game home screen data.
     */
    fun processHomeScreen(homeScreen: HackExParser.HomeScreen) {
        val currentRecords = getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()
        applyHomeScreenToMap(recordMap, homeScreen)
        saveAllRecords(recordMap.values.toList())
        reconcileDatabase()
    }

    private fun applyHomeScreenToMap(recordMap: MutableMap<String, TargetRecord>, homeScreen: HackExParser.HomeScreen) {
        var record = recordMap[homeScreen.ip]
        if (record == null) {
            record = TargetRecord(ip = homeScreen.ip)
        }
        
        homeScreen.username?.let { record.username = it }
        homeScreen.clan?.let { record.clan = it }
        homeScreen.level?.let { record.level = it }
        homeScreen.hardware?.let { record.hardware = it }
        homeScreen.firewall?.let { record.firewall = it }
        homeScreen.encryptor?.let { record.encryptor = it }
        record.lastValidated = getCurrentTimestamp()
        
        recordMap[homeScreen.ip] = record
    }

    /**
     * Updates a target IP address in local storage and merges records if the new IP collides with an existing entry.
     */
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
    
    /**
     * Removes a target record from local storage by IP address.
     */
    fun deleteTarget(ip: String) {
        val currentRecords = getAllRecords().toMutableList()
        currentRecords.removeAll { it.ip == ip }
        saveAllRecords(currentRecords)
    }

    /**
     * Updates or creates target record software inventory from parsed game software screen data.
     */
    fun processSoftwareScreen(softwareScreen: HackExParser.SoftwareScreen) {
        if (softwareScreen.softwareItems.isEmpty()) return
        val currentRecords = getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()
        applySoftwareScreenToMap(recordMap, softwareScreen)
        saveAllRecords(recordMap.values.toList())
        reconcileDatabase()
    }

    private fun applySoftwareScreenToMap(recordMap: MutableMap<String, TargetRecord>, softwareScreen: HackExParser.SoftwareScreen) {
        if (softwareScreen.softwareItems.isEmpty()) return
        val username = softwareScreen.username
        var targetRecord: TargetRecord? = null
        
        if (username != null) {
            targetRecord = recordMap.values.find { it.username.equals(username, ignoreCase = true) }
        }
        
        if (targetRecord == null) {
            val newIp = "unknown:${username ?: System.currentTimeMillis()}"
            targetRecord = TargetRecord(ip = newIp, username = username)
            recordMap[newIp] = targetRecord
        }
        
        softwareScreen.softwareItems.forEach { (name, item) ->
            targetRecord.downloads[name] = SoftwareEntry(item.level, item.status, null)
        }
        targetRecord.lastValidated = getCurrentTimestamp()
    }

    /**
     * Associates a parsed in-game crypto wallet address with a target user record.
     */
    fun processWalletScreen(walletScreen: HackExParser.WalletScreen) {
        val currentRecords = getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()
        
        val username = walletScreen.username
        val wallet = walletScreen.wallet
        
        var targetRecord = recordMap.values.find { it.username.equals(username, ignoreCase = true) }
        
        if (targetRecord == null) {
            val newIp = "unknown:${username}"
            targetRecord = TargetRecord(ip = newIp, username = username)
            recordMap[newIp] = targetRecord
        }

        if (targetRecord.wallets.isNotEmpty() && !targetRecord.wallets.contains(wallet)) {
            logWalletConflict(targetRecord.ip, targetRecord.wallets.first(), wallet, "WalletScreen")
        } else {
            val previousOwner = recordMap.values.find { it.ip != targetRecord.ip && it.wallets.contains(wallet) }
            if (previousOwner != null) {
                val merged = mergeRecords(targetRecord, previousOwner)
                merged.ip = targetRecord.ip
                recordMap.remove(previousOwner.ip)
                targetRecord = merged
                recordMap[targetRecord.ip] = targetRecord
            }
            
            if (!targetRecord.wallets.contains(wallet)) {
                targetRecord.wallets.add(wallet)
            }
        }
        targetRecord.lastValidated = getCurrentTimestamp()
        
        saveAllRecords(recordMap.values.toList())
        reconcileDatabase()
    }

    fun getSiphonAlerts(): List<String> {
        return siphonAlerts.toList()
    }
    
    fun removeSiphonAlert(ip: String) {
        siphonAlerts.remove(ip)
        saveSiphonData()
    }

    /**
     * Clears all internal tracking for Siphons, used when prestiging.
     */
    fun clearSiphonTracking() {
        knownSiphons.clear()
        siphonAlerts.clear()
        recentlyScrapedSiphons.clear()
        saveSiphonData()
    }

    /**
     * Processes a parsed Siphon Earnings screen, updates tracking, generates alerts, 
     * and logs history to an exportable file.
     */
    fun processSiphonScreen(siphonScreen: HackExParser.SiphonScreen) {
        if (siphonScreen.items.isEmpty() && siphonScreen.deployedCount > 0) return
        
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowStr = isoFormat.format(Date())
        val exportFile = File(appContext.filesDir, "siphon_history.jsonl")
        
        val currentTime = System.currentTimeMillis()
        val iterator = recentlyScrapedSiphons.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime - entry.value > 15 * 60 * 1000) {
                iterator.remove()
            }
        }
        
        for (item in siphonScreen.items) {
            recentlyScrapedSiphons[item.ip] = currentTime
        }

        // 1. Check for removed siphons
        if (siphonScreen.deployedCount == 0) {
            val missingIps = knownSiphons.keys.toList()
            for (missingIp in missingIps) {
                siphonAlerts.add(missingIp)
                knownSiphons.remove(missingIp)
                val logLine = """{"timestamp":"$nowStr","ip":"$missingIp","event":"REMOVED"}"""
                exportFile.appendText("$logLine\n")
            }
        } else if (recentlyScrapedSiphons.size >= siphonScreen.deployedCount) {
            val missingIps = knownSiphons.keys - recentlyScrapedSiphons.keys
            for (missingIp in missingIps) {
                siphonAlerts.add(missingIp)
                knownSiphons.remove(missingIp)
                val logLine = """{"timestamp":"$nowStr","ip":"$missingIp","event":"REMOVED"}"""
                exportFile.appendText("$logLine\n")
            }
        }

        // 2. Process current siphons and check for drops (wiped & reinstalled)
        for (item in siphonScreen.items) {
            val previousAmount = knownSiphons[item.ip]
            
            if (previousAmount != null && item.amount < previousAmount) {
                // The amount went down: it was wiped and reinstalled!
                siphonAlerts.add(item.ip)
                val logLine = """{"timestamp":"$nowStr","ip":"${item.ip}","event":"REINSTALLED","old_amount":$previousAmount,"new_amount":${item.amount}}"""
                exportFile.appendText("$logLine\n")
            } else if (previousAmount == null) {
                // New siphon installation!
                val logLine = """{"timestamp":"$nowStr","ip":"${item.ip}","event":"INSTALLED","amount":${item.amount},"timeAgo":"${item.timeAgo}"}"""
                exportFile.appendText("$logLine\n")
            }
            
            // Update tracking map
            knownSiphons[item.ip] = item.amount
        }
        
        saveSiphonData()
    }

    /**
     * Processes a parsed Crew Screen, matching known clan tags to unknown IPs.
     */
    fun processCrewScreen(crewScreen: HackExParser.CrewScreen) {
        val map = cachedRecords.associateBy { it.ip }.toMutableMap()
        var foundClanTag: String? = null

        // Try to find a clan tag from existing known members
        for (ip in crewScreen.members) {
            val record = map[ip]
            if (record != null && !record.clan.isNullOrBlank()) {
                foundClanTag = record.clan
                break
            }
        }

        // If no clan tag is found in the database, we discard the data
        if (foundClanTag == null) {
            return
        }

        var changed = false
        val now = getCurrentTimestamp()

        for (ip in crewScreen.members) {
            val existing = map[ip]
            if (existing != null) {
                if (existing.clan != foundClanTag) {
                    existing.clan = foundClanTag
                    changed = true
                }
            } else {
                val newRecord = TargetRecord(
                    ip = ip,
                    username = null,
                    clan = foundClanTag,
                    level = null,
                    hardware = null,
                    firewall = null,
                    encryptor = null,
                    wallets = mutableListOf(),
                    downloads = mutableMapOf(),
                    walletThefts = mutableMapOf(),
                    lastValidated = now
                )
                map[ip] = newRecord
                changed = true
            }
        }

        if (changed) {
            saveAllRecords(map.values.toList())
            reconcileDatabase()
        }
    }

    /**
     * Processes a parsed Process Screen, adding any unknown IPs to the database.
     */
    fun processProcessScreen(processScreen: HackExParser.ProcessScreen) {
        val map = cachedRecords.associateBy { it.ip }.toMutableMap()
        var changed = false
        val now = getCurrentTimestamp()

        val allIps = processScreen.ips + processScreen.invalidatedIps

        for (ip in allIps) {
            if (!map.containsKey(ip)) {
                val isInvalidated = processScreen.invalidatedIps.contains(ip) && !processScreen.ips.contains(ip)
                val newRecord = TargetRecord(
                    ip = ip,
                    username = null,
                    clan = null,
                    level = null,
                    hardware = null,
                    firewall = null,
                    encryptor = null,
                    wallets = mutableListOf(),
                    downloads = mutableMapOf(),
                    walletThefts = mutableMapOf(),
                    lastValidated = if (isInvalidated) null else now
                )
                map[ip] = newRecord
                changed = true
            }
        }

        for (download in processScreen.downloads) {
            val record = map[download.ip]
            if (record != null) {
                val existing = record.downloads[download.name]
                if (existing == null || existing.level < download.level) {
                    record.downloads[download.name] = SoftwareEntry(download.level, "Unknown", now)
                    changed = true
                }
            }
        }

        if (changed) {
            saveAllRecords(map.values.toList())
            reconcileDatabase()
        }
    }

    /**
     * Merges non-null fields from an incoming record into a base target record.
     */
    private fun mergeRecords(base: TargetRecord, incoming: TargetRecord): TargetRecord {
        val baseIsMasked = base.isMasked
        val incomingIsMasked = incoming.isMasked
        val canonicalIp = if ((!incomingIsMasked && baseIsMasked) || (base.isTemporary && !incoming.isTemporary)) incoming.ip else base.ip

        // When merging an unmasked target with a masked generic target placeholder,
        // do not contaminate the unmasked target with software/wallet data from the masked placeholder.
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

    private fun logWalletConflict(ip: String, wallet1: String, wallet2: String, source: String) {
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

    /**
     * Deduplicates database records by combining targets that share the same username or crypto wallet address.
     */
    private fun reconcileDatabase() {
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