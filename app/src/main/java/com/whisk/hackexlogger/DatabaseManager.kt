package com.whisk.hackexlogger

import android.content.Context

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

    fun runDatabaseMaintenance(): DatabaseMaintenance.MaintenanceResult = DatabaseMaintenance.runDatabaseMaintenance()

    fun purgeOldTheftEvents(): Int = DatabaseMaintenance.purgeOldTheftEvents()

    fun getNextRecordToValidate(): TargetRecord? = TargetRepository.getNextRecordToValidate()

    fun markValidated(ip: String) = TargetRepository.markValidated(ip)

    /** `M-D H:MM` log time (UTC) → epoch ms. See [LogTime]. */
    fun parseLogTime(timeStr: String, anchorUtcMs: Long? = null): Long? = LogTime.toEpochMs(timeStr, anchorUtcMs)

    fun exportDataBundle(): String = DatabaseBackup.exportDataBundle()

    fun importDataBundle(jsonString: String): ImportResult = DatabaseBackup.importDataBundle(jsonString)

    /** Working copy of the database for one write path: records keyed by IP, in order. */
    private fun recordMapOf(list: List<TargetRecord>): LinkedHashMap<String, TargetRecord> =
        LinkedHashMap<String, TargetRecord>(list.size * 2).also { m -> list.forEach { m[it.ip] = it } }

    fun processUpdates(updates: List<ParsedUpdate>, logAnchorUtcMs: Long? = null) {
        if (updates.isEmpty()) return
        TargetRepository.update { list ->
            val recordMap = recordMapOf(list)
            // wallet → IPs of the records holding it (in database order), kept in step with recordMap, so each
            // log line finds a wallet's owner without scanning all 4,000+ records
            val walletOwners = HashMap<String, LinkedHashSet<String>>()
            for (r in list) for (w in r.wallets) walletOwners.getOrPut(w) { LinkedHashSet() }.add(r.ip)
            fun remove(ip: String) {
                recordMap.remove(ip)?.wallets?.forEach { w -> walletOwners[w]?.remove(ip) }
            }
            fun put(record: TargetRecord) {
                recordMap.put(record.ip, record)?.wallets?.forEach { w -> if (w !in record.wallets) walletOwners[w]?.remove(record.ip) }
                for (w in record.wallets) walletOwners.getOrPut(w) { LinkedHashSet() }.add(record.ip)
            }
            fun ownerOf(wallet: String, excludingIp: String? = null): TargetRecord? =
                walletOwners[wallet]?.firstOrNull { it != excludingIp }?.let { recordMap[it] }

            for (update in updates) {
                var ip = update.ip
                val isIpMasked = ip.isEmpty() || ip.contains("xxx", ignoreCase = true) || ip.contains("UNKNOWN", ignoreCase = true)

                if (update.wallet != null && isIpMasked) {
                    ip = ownerOf(update.wallet)?.ip ?: "unknown:wallet_${update.wallet}"
                }

                if (ip.isEmpty()) {
                    dbDiscard(update, LogDiscardReason.EMPTY_IP)
                    continue
                }

                var record = recordMap[ip] ?: TargetRecord(ip = ip)

                if (update.wallet != null) {
                    // Log-inferred wallets are only "highly probable", so a target may collect several of them.
                    // Once its wallet screen has been scraped, that wallet is proof and logs can't add another.
                    if (record.walletFromPage && !record.wallets.contains(update.wallet)) {
                        WalletConflictLog.log(ip, record.wallets.first(), update.wallet, "LogUpdate")
                        dbDiscard(update, LogDiscardReason.WALLET_CONFLICT)
                    } else {
                        val previousOwner = ownerOf(update.wallet, excludingIp = ip)
                        if (previousOwner != null && !previousOwner.isTemporary && !record.isTemporary) {
                            // The wallet already belongs to another real target. A log line can't move it
                            // (developer rule 2026-11-20: only a wallet-screen scrape may), so drop this line.
                            WalletConflictLog.log(ip, previousOwner.ip, update.wallet, "LogWalletOwnedElsewhere")
                            dbDiscard(update, LogDiscardReason.WALLET_CONFLICT)
                            put(record)
                            continue
                        }
                        if (previousOwner != null) {
                            // A wallet placeholder (or this placeholder) joins its real target
                            val merged = RecordMerger.mergeRecords(record, previousOwner)
                            remove(record.ip)
                            remove(previousOwner.ip)
                            record = merged
                            ip = merged.ip
                        }
                        if (!record.wallets.contains(update.wallet)) {
                            record = record.copy(wallets = record.wallets + update.wallet, walletSource = record.walletSource ?: WalletSource.LOG)
                        }

                        if (update.stolenAmount != null && update.isOwnLogTheft) {
                            val timestampMs = update.time?.let { parseLogTime(it, logAnchorUtcMs) }
                            if (timestampMs != null) {
                                record = record.withTheftEvent(update.wallet, timestampMs, update.stolenAmount)
                            } else {
                                dbDiscard(update, LogDiscardReason.BAD_TIMESTAMP)
                            }
                        }
                    }
                }

                if (update.software != null && update.isOwnedSoftware) {
                    if (!record.isMasked) {
                        record = record.withDownload(update.software.name, SoftwareEntry(update.software.level, update.software.action, update.time))
                    } else {
                        dbDiscard(update, LogDiscardReason.MASKED_SOFTWARE_IGNORED)
                    }
                }
                put(record)
            }
            recordMap.values.toList()
        }
    }

    fun processHomeScreen(homeScreen: HomeScreen) {
        val now = TargetRepository.getCurrentTimestamp()
        TargetRepository.update { list ->
            val recordMap = recordMapOf(list)
            val record = recordMap[homeScreen.ip] ?: TargetRecord(ip = homeScreen.ip)
            recordMap[homeScreen.ip] = record.copy(
                username = homeScreen.username ?: record.username,
                clan = homeScreen.clan ?: record.clan,
                level = homeScreen.level ?: record.level,
                hardware = homeScreen.hardware ?: record.hardware,
                firewall = homeScreen.firewall ?: record.firewall,
                encryptor = homeScreen.encryptor ?: record.encryptor,
                lastValidated = now
            )
            recordMap.values.toList()
        }
    }

    fun editTargetIp(oldIp: String, newIp: String) = TargetRepository.editTargetIp(oldIp, newIp)

    fun deleteTarget(ip: String) = TargetRepository.deleteTarget(ip)

    fun clearTargetTheftEvents(ip: String, wallet: String? = null) = TargetRepository.clearTargetTheftEvents(ip, wallet)

    fun processSoftwareScreen(softwareScreen: SoftwareScreen) {
        if (softwareScreen.softwareItems.isEmpty()) return
        val now = TargetRepository.getCurrentTimestamp()
        TargetRepository.update { list ->
            val recordMap = recordMapOf(list)
            val username = softwareScreen.username
            val existing = if (username != null) recordMap.values.find { it.username.equals(username, ignoreCase = true) } else null
            val record = existing ?: TargetRecord(ip = "unknown:${username ?: System.currentTimeMillis()}", username = username)
            recordMap[record.ip] = record.copy(
                downloads = record.downloads + softwareScreen.softwareItems.mapValues { (_, item) -> SoftwareEntry(item.level, item.status, null) },
                lastValidated = now
            )
            recordMap.values.toList()
        }
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
        val now = TargetRepository.getCurrentTimestamp()
        TargetRepository.update { list ->
            val recordMap = recordMapOf(list)
            val username = walletScreen.username
            val wallet = walletScreen.wallet

            var targetRecord = recordMap.values.find { it.username.equals(username, ignoreCase = true) }
                ?: TargetRecord(ip = "unknown:${username}", username = username).also { recordMap[it.ip] = it }

            val previousOwner = recordMap.values.find { it.ip != targetRecord.ip && it.wallets.contains(wallet) }
            if (previousOwner != null) {
                if (previousOwner.isTemporary || targetRecord.isTemporary) {
                    val merged = RecordMerger.mergeRecords(targetRecord, previousOwner)
                    recordMap.remove(targetRecord.ip)
                    recordMap.remove(previousOwner.ip)
                    targetRecord = merged
                } else {
                    val remaining = previousOwner.wallets - wallet
                    recordMap[previousOwner.ip] = previousOwner.copy(
                        wallets = remaining,
                        walletSource = if (remaining.isEmpty()) null else previousOwner.walletSource,
                        walletThefts = previousOwner.walletThefts - wallet
                    )
                    previousOwner.walletThefts[wallet]?.let { targetRecord = targetRecord.withTheftEvents(wallet, it) }
                    WalletConflictLog.log(previousOwner.ip, wallet, wallet, "WalletScreenMovedFrom")
                }
            }

            val replaced = targetRecord.wallets.filter { it != wallet }
            if (replaced.isNotEmpty()) {
                WalletConflictLog.log(targetRecord.ip, replaced.first(), wallet, "WalletScreenReplaced")
            }
            recordMap[targetRecord.ip] = targetRecord.copy(
                wallets = listOf(wallet),
                walletThefts = targetRecord.walletThefts - replaced.toSet(),
                walletSource = WalletSource.PAGE,
                lastValidated = now
            )
            recordMap.values.toList()
        }
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
            TargetRepository.update { list ->
                val recordMap = recordMapOf(list)
                val record = recordMap[ip] ?: TargetRecord(ip = ip)
                recordMap[ip] = record.copy(
                    firewall = bypassScreen.firewall ?: record.firewall,
                    encryptor = bypassScreen.encryptor ?: record.encryptor
                )
                recordMap.values.toList()
            }
        }

        if (bypassScreen.playerBypasser != null) {
            val totalBypasser = bypassScreen.playerBypasser + (bypassScreen.apexBonus ?: 0)
            PlayerProfileRepository.updateBypasserLevel(totalBypasser)
        }
    }

    fun processCrewScreen(crewScreen: CrewScreen) {
        val now = TargetRepository.getCurrentTimestamp()
        // Masked members (44.72.45.xxx, xxx.xxx.xxx.xxx) can't be keyed: skip them entirely
        val members = crewScreen.members.filterNot { CommonParserUtil.isMaskedIp(it) }
        TargetRepository.update { list ->
            val map = recordMapOf(list)
            val clan = members.firstNotNullOfOrNull { ip -> map[ip]?.clan?.takeIf { it.isNotBlank() } } ?: return@update null
            var changed = false
            for (ip in members) {
                val existing = map[ip]
                if (existing == null) {
                    map[ip] = TargetRecord(ip = ip, clan = clan, lastValidated = now)
                    changed = true
                } else if (existing.clan != clan) {
                    map[ip] = existing.copy(clan = clan)
                    changed = true
                }
            }
            if (changed) map.values.toList() else null
        }
    }

    fun processProcessScreen(processScreen: ProcessScreen) {
        val now = TargetRepository.getCurrentTimestamp()
        TargetRepository.update { list ->
            val map = recordMapOf(list)
            var changed = false

            for (ip in processScreen.ips + processScreen.invalidatedIps) {
                if (CommonParserUtil.isMaskedIp(ip)) continue // can't key a target on a hidden IP
                if (!map.containsKey(ip)) {
                    val isInvalidated = processScreen.invalidatedIps.contains(ip) && !processScreen.ips.contains(ip)
                    map[ip] = TargetRecord(ip = ip, lastValidated = if (isInvalidated) null else now)
                    changed = true
                }
            }

            for (download in processScreen.downloads) {
                val record = map[download.ip] ?: continue
                val existing = record.downloads[download.name]
                if (existing == null || existing.level < download.level) {
                    map[download.ip] = record.withDownload(download.name, SoftwareEntry(download.level, "Unknown", now))
                    changed = true
                }
            }

            if (changed) map.values.toList() else null
        }
    }
}
