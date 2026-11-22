package com.whisk.hackexlogger

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
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
    /** Receives log lines the database layer drops. Null (no-op) unless Raw Dump Mode is recording a scrape. */
    fun interface DbDiscardSink {
        fun onDbDiscard(line: String, reason: String)
    }

    @Volatile var activeDiscardSink: DbDiscardSink? = null

    private fun dbDiscard(update: ParsedUpdate, reason: String) {
        activeDiscardSink?.onDbDiscard(update.raw, reason)
    }

    fun init(context: Context) {
        TargetRepository.init(context)
        SiphonTracker.init()
        PlayerProfileRepository.init(context)
    }

    val recordsFlow = TargetRepository.recordsFlow

    fun getAllRecords(): List<TargetRecord> = TargetRepository.getAllRecords()

    fun getNextTargetForAdvancedTargeting(): TargetRecord? = TargetRepository.getNextTargetForAdvancedTargeting()

    fun markTargetValidated(ip: String) = TargetRepository.markTargetValidated(ip)

    fun wipeDatabase() = TargetRepository.wipeDatabase()

    data class MaintenanceResult(
        val backupFilePath: String,
        val multiWalletRecordsFixed: Int,
        val maskedSoftwareCleared: Int,
        val maskedWalletsMigrated: Int
    )

    fun runDatabaseMaintenance(): MaintenanceResult {
        val result = TargetRepository.runDatabaseMaintenance()
        return MaintenanceResult(
            backupFilePath = result.backupFilePath,
            multiWalletRecordsFixed = result.multiWalletRecordsFixed,
            maskedSoftwareCleared = result.maskedSoftwareCleared,
            maskedWalletsMigrated = result.maskedWalletsMigrated
        )
    }

    fun purgeOldTheftEvents(): Int = TargetRepository.purgeOldTheftEvents()

    fun getNextRecordToValidate(): TargetRecord? = TargetRepository.getNextRecordToValidate()

    fun markValidated(ip: String) = TargetRepository.markValidated(ip)

    /** `M-D H:MM` log time (UTC) → epoch ms. See [LogTime]. */
    fun parseLogTime(timeStr: String, anchorUtcMs: Long? = null): Long? = LogTime.toEpochMs(timeStr, anchorUtcMs)

    fun exportDataBundle(): String = TargetRepository.exportDataBundle()

    fun importDataBundle(jsonString: String): ImportResult {
        return TargetRepository.importDataBundle(jsonString)
    }

    fun processUpdates(updates: List<ParsedUpdate>, logAnchorUtcMs: Long? = null) {
        if (updates.isEmpty()) return
        val currentRecords = TargetRepository.getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()
        
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

            if (ip.isEmpty()) {
                dbDiscard(update, LogDiscardReason.EMPTY_IP)
                continue
            }

            var record = recordMap[ip]
            if (record == null) {
                record = TargetRecord(ip = ip)
                recordMap[ip] = record
            }

            if (update.wallet != null) {
                // Log-inferred wallets are only "highly probable", so a target may collect several of them.
                // Once its wallet screen has been scraped, that wallet is proof and logs can't add another.
                if (record.walletFromPage && !record.wallets.contains(update.wallet)) {
                    TargetRepository.logWalletConflict(ip, record.wallets.first(), update.wallet, "LogUpdate")
                    dbDiscard(update, LogDiscardReason.WALLET_CONFLICT)
                } else {
                    val previousOwner = recordMap.values.find { it.ip != ip && it.wallets.contains(update.wallet) }
                    if (previousOwner != null && !previousOwner.isTemporary && !record.isTemporary) {
                        // The wallet already belongs to another real target. A log line can't move it
                        // (developer rule 2026-11-20: only a wallet-screen scrape may), so drop this line.
                        TargetRepository.logWalletConflict(ip, previousOwner.ip, update.wallet, "LogWalletOwnedElsewhere")
                        dbDiscard(update, LogDiscardReason.WALLET_CONFLICT)
                        continue
                    }
                    if (previousOwner != null) {
                        // A wallet placeholder (or this placeholder) joins its real target
                        val merged = TargetRepository.mergeRecords(record, previousOwner)
                        recordMap.remove(record.ip)
                        recordMap.remove(previousOwner.ip)
                        record = merged
                        ip = merged.ip
                        recordMap[ip] = record
                    }
                    if (!record.wallets.contains(update.wallet)) {
                        record.wallets.add(update.wallet)
                        if (record.walletSource == null) record.walletSource = TargetRecord.WALLET_FROM_LOG
                    }
                    
                    if (update.stolenAmount != null && update.isOwnLogTheft) {
                        val timestampMs = update.time?.let { parseLogTime(it, logAnchorUtcMs) }
                        if (timestampMs != null) {
                            record.addTheftEvent(update.wallet, timestampMs, update.stolenAmount)
                        } else {
                            dbDiscard(update, LogDiscardReason.BAD_TIMESTAMP)
                        }
                    }
                }
            }

            if (update.software != null && update.isOwnedSoftware) {
                if (!record.isMasked) {
                    record.downloads[update.software.name] = SoftwareEntry(
                        level = update.software.level,
                        status = update.software.action,
                        lastSeen = update.time
                    )
                } else {
                    dbDiscard(update, LogDiscardReason.MASKED_SOFTWARE_IGNORED)
                }
            }
        }
        TargetRepository.saveAllRecords(recordMap.values.toList())
        TargetRepository.reconcileDatabase()
    }

    fun processHomeScreen(homeScreen: HomeScreen) {
        val currentRecords = TargetRepository.getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()
        
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
        record.lastValidated = TargetRepository.getCurrentTimestamp()
        
        recordMap[homeScreen.ip] = record
        TargetRepository.saveAllRecords(recordMap.values.toList())
        TargetRepository.reconcileDatabase()
    }

    fun editTargetIp(oldIp: String, newIp: String) = TargetRepository.editTargetIp(oldIp, newIp)

    fun deleteTarget(ip: String) = TargetRepository.deleteTarget(ip)

    fun clearTargetTheftEvents(ip: String, wallet: String? = null) = TargetRepository.clearTargetTheftEvents(ip, wallet)

    fun processSoftwareScreen(softwareScreen: SoftwareScreen) {
        if (softwareScreen.softwareItems.isEmpty()) return
        val currentRecords = TargetRepository.getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()
        
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
        targetRecord.lastValidated = TargetRepository.getCurrentTimestamp()
        
        TargetRepository.saveAllRecords(recordMap.values.toList())
        TargetRepository.reconcileDatabase()
    }

    /**
     * The wallet screen is the authority on a target's wallet (developer rule 2026-11-20):
     * - it **replaces** whatever wallet the target had (from a log or an older wallet screen); thefts recorded
     *   under the replaced wallet are dropped, and the change goes to the conflict log;
     * - if another real target holds this wallet (wrongly inferred from a log, or the wallet moved), it is
     *   taken from that target together with its thefts;
     * - a placeholder holding the wallet (`unknown:wallet_…`) merges into this target.
     * Logs can only fill in a wallet that's missing; they never replace or move one.
     */
    fun processWalletScreen(walletScreen: WalletScreen) {
        val currentRecords = TargetRepository.getAllRecords().toMutableList()
        val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()

        val username = walletScreen.username
        val wallet = walletScreen.wallet

        var targetRecord = recordMap.values.find { it.username.equals(username, ignoreCase = true) }

        if (targetRecord == null) {
            val newIp = "unknown:${username}"
            targetRecord = TargetRecord(ip = newIp, username = username)
            recordMap[newIp] = targetRecord
        }

        val previousOwner = recordMap.values.find { it.ip != targetRecord.ip && it.wallets.contains(wallet) }
        if (previousOwner != null) {
            if (previousOwner.isTemporary || targetRecord.isTemporary) {
                val merged = TargetRepository.mergeRecords(targetRecord, previousOwner)
                recordMap.remove(targetRecord.ip)
                recordMap.remove(previousOwner.ip)
                targetRecord = merged
                recordMap[targetRecord.ip] = targetRecord
            } else {
                previousOwner.wallets.remove(wallet)
                if (previousOwner.wallets.isEmpty()) previousOwner.walletSource = null
                val movedThefts = previousOwner.walletThefts.remove(wallet)
                if (movedThefts != null) {
                    val list = targetRecord.walletThefts.getOrPut(wallet) { mutableListOf() }
                    for (event in movedThefts) {
                        if (list.none { it.timestampMs == event.timestampMs && it.amount == event.amount }) list.add(event)
                    }
                    list.sortBy { it.timestampMs }
                }
                TargetRepository.logWalletConflict(previousOwner.ip, wallet, wallet, "WalletScreenMovedFrom")
            }
        }

        val replaced = targetRecord.wallets.filter { it != wallet }
        if (replaced.isNotEmpty()) {
            TargetRepository.logWalletConflict(targetRecord.ip, replaced.first(), wallet, "WalletScreenReplaced")
            replaced.forEach { targetRecord.walletThefts.remove(it) }
        }
        targetRecord.wallets = mutableListOf(wallet)
        targetRecord.walletSource = TargetRecord.WALLET_FROM_PAGE
        targetRecord.lastValidated = TargetRepository.getCurrentTimestamp()

        TargetRepository.saveAllRecords(recordMap.values.toList())
        TargetRepository.reconcileDatabase()
    }

    fun getSiphonAlerts(): List<String> = SiphonTracker.getSiphonAlerts()
    
    fun removeSiphonAlert(ip: String) = SiphonTracker.removeSiphonAlert(ip)

    fun clearSiphonTracking() = SiphonTracker.clearSiphonTracking()

    fun processSiphonScreen(siphonScreen: SiphonScreen) {
        SiphonTracker.processSiphonScreen(siphonScreen, TargetRepository.appContext)
    }

    fun processStoreScreen(storeScreen: StoreScreen) {
        PlayerProfileRepository.updateProfile(storeScreen)
    }

    fun processOwnHomeScreen(ownHomeScreen: OwnHomeScreen) {
        PlayerProfileRepository.updateOwnProfile(ownHomeScreen.username, ownHomeScreen.level)
    }

    fun processBypassScreen(bypassScreen: BypassScreen) {
        val ip = bypassScreen.targetIp
        if (ip != null) {
            val currentRecords = TargetRepository.getAllRecords().toMutableList()
            val recordMap = currentRecords.associateBy { it.ip }.toMutableMap()
            var record = recordMap[ip]
            if (record == null) {
                record = TargetRecord(ip = ip)
                recordMap[ip] = record
            }
            if (bypassScreen.firewall != null) record.firewall = bypassScreen.firewall
            if (bypassScreen.encryptor != null) record.encryptor = bypassScreen.encryptor
            
            TargetRepository.saveAllRecords(recordMap.values.toList())
            TargetRepository.reconcileDatabase()
        }

        if (bypassScreen.playerBypasser != null) {
            val totalBypasser = bypassScreen.playerBypasser + (bypassScreen.apexBonus ?: 0)
            PlayerProfileRepository.updateBypasserLevel(totalBypasser)
        }
    }

    fun processCrewScreen(crewScreen: CrewScreen) {
        val currentRecords = TargetRepository.getAllRecords().toMutableList()
        val map = currentRecords.associateBy { it.ip }.toMutableMap()
        var foundClanTag: String? = null

        // Masked members (44.72.45.xxx, xxx.xxx.xxx.xxx) can't be keyed: skip them entirely
        val members = crewScreen.members.filterNot { CommonParserUtil.isMaskedIp(it) }

        for (ip in members) {
            val record = map[ip]
            if (record != null && !record.clan.isNullOrBlank()) {
                foundClanTag = record.clan
                break
            }
        }

        if (foundClanTag == null) return

        var changed = false
        val now = TargetRepository.getCurrentTimestamp()

        for (ip in members) {
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
            TargetRepository.saveAllRecords(map.values.toList())
            TargetRepository.reconcileDatabase()
        }
    }

    fun processProcessScreen(processScreen: ProcessScreen) {
        val currentRecords = TargetRepository.getAllRecords().toMutableList()
        val map = currentRecords.associateBy { it.ip }.toMutableMap()
        var changed = false
        val now = TargetRepository.getCurrentTimestamp()

        val allIps = processScreen.ips + processScreen.invalidatedIps

        for (ip in allIps) {
            if (CommonParserUtil.isMaskedIp(ip)) continue // can't key a target on a hidden IP
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
            TargetRepository.saveAllRecords(map.values.toList())
            TargetRepository.reconcileDatabase()
        }
    }
}