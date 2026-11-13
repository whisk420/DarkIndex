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
    fun init(context: Context) {
        TargetRepository.init(context)
        SiphonTracker.init()
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
        val maskedWalletsMigrated: Int,
        val oldTheftsCleared: Int
    )

    fun runDatabaseMaintenance(): MaintenanceResult {
        val result = TargetRepository.runDatabaseMaintenance()
        return MaintenanceResult(
            backupFilePath = result.backupFilePath,
            multiWalletRecordsFixed = result.multiWalletRecordsFixed,
            maskedSoftwareCleared = result.maskedSoftwareCleared,
            maskedWalletsMigrated = result.maskedWalletsMigrated,
            oldTheftsCleared = result.oldTheftsCleared
        )
    }

    fun getNextRecordToValidate(): TargetRecord? = TargetRepository.getNextRecordToValidate()

    fun markValidated(ip: String) = TargetRepository.markValidated(ip)

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

    fun exportDataBundle(): String = TargetRepository.exportDataBundle()

    fun importDataBundle(jsonString: String): ImportResult {
        return TargetRepository.importDataBundle(jsonString)
    }

    fun processUpdates(updates: List<ParsedUpdate>) {
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

            if (ip.isEmpty()) continue

            var record = recordMap[ip]
            if (record == null) {
                record = TargetRecord(ip = ip)
                recordMap[ip] = record
            }

            if (update.wallet != null) {
                if (record.wallets.isNotEmpty() && !record.wallets.contains(update.wallet)) {
                    TargetRepository.logWalletConflict(ip, record.wallets.first(), update.wallet, "LogUpdate")
                } else {
                    val previousOwner = recordMap.values.find { it.ip != ip && it.wallets.contains(update.wallet) }
                    if (previousOwner != null) {
                        val merged = TargetRepository.mergeRecords(record, previousOwner)
                        merged.ip = ip
                        recordMap.remove(previousOwner.ip)
                        record = merged
                        recordMap[ip] = record
                    }
                    if (!record.wallets.contains(update.wallet)) {
                        record.wallets.add(update.wallet)
                    }
                    
                    if (update.stolenAmount != null && update.time != null && update.isOwnLogTheft) {
                        val timestampMs = parseLogTime(update.time)
                        if (timestampMs != null) {
                            record.addTheftEvent(update.wallet, timestampMs, update.stolenAmount)
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

        if (targetRecord.wallets.isNotEmpty() && !targetRecord.wallets.contains(wallet)) {
            TargetRepository.logWalletConflict(targetRecord.ip, targetRecord.wallets.first(), wallet, "WalletScreen")
        } else {
            val previousOwner = recordMap.values.find { it.ip != targetRecord.ip && it.wallets.contains(wallet) }
            if (previousOwner != null) {
                val merged = TargetRepository.mergeRecords(targetRecord, previousOwner)
                merged.ip = targetRecord.ip
                recordMap.remove(previousOwner.ip)
                targetRecord = merged
                recordMap[targetRecord.ip] = targetRecord
            }
            
            if (!targetRecord.wallets.contains(wallet)) {
                targetRecord.wallets.add(wallet)
            }
        }
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

    fun processCrewScreen(crewScreen: CrewScreen) {
        val currentRecords = TargetRepository.getAllRecords().toMutableList()
        val map = currentRecords.associateBy { it.ip }.toMutableMap()
        var foundClanTag: String? = null

        for (ip in crewScreen.members) {
            val record = map[ip]
            if (record != null && !record.clan.isNullOrBlank()) {
                foundClanTag = record.clan
                break
            }
        }

        if (foundClanTag == null) return

        var changed = false
        val now = TargetRepository.getCurrentTimestamp()

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