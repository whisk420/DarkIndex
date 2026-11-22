package com.whisk.hackexlogger

import java.io.File
import kotlin.time.Duration.Companion.days

/** ⋮ → Maintenance, and the 30-day theft purge. Both work on the live database after taking a backup. */
object DatabaseMaintenance {

    data class MaintenanceResult(
        val backupFilePath: String,
        val multiWalletRecordsFixed: Int,
        val maskedSoftwareCleared: Int,
        val maskedWalletsMigrated: Int
    )

    fun runDatabaseMaintenance(): MaintenanceResult {
        var backupPath = "none"
        TargetRepository.contextOrNull()?.let { context ->
            val backupFile = File(context.filesDir, "maintenance_backup_${TargetStore.stamp()}.json")
            backupFile.bufferedWriter().use { DatabaseBackup.writeDataBundle(it) }
            backupPath = backupFile.absolutePath
        }

        var multiWalletCount = 0
        var maskedSoftwareCount = 0
        var maskedWalletsMigratedCount = 0

        TargetRepository.update { list ->
            val recordMap = LinkedHashMap<String, TargetRecord>()
            list.forEach { recordMap[it.ip] = it }

            for (original in list) {
                var record = recordMap[original.ip] ?: continue

                // Several log-inferred wallets on one target are allowed (developer rule 2026-11-20), so only a
                // target holding a wallet-screen wallet plus extras is cleaned (keep the proven one).
                if (record.wallets.size > 1 && record.walletFromPage) {
                    val proven = record.wallets.first()
                    val extras = record.wallets.drop(1)
                    extras.forEach { WalletConflictLog.log(record.ip, proven, it, "MaintenanceDroppedUnproven") }
                    record = record.copy(wallets = listOf(proven), walletThefts = record.walletThefts - extras.toSet())
                    multiWalletCount++
                }
                if (record.isMasked) {
                    if (record.downloads.isNotEmpty()) {
                        record = record.copy(downloads = emptyMap())
                        maskedSoftwareCount++
                    }
                    if (record.wallets.isNotEmpty()) {
                        val walletToMigrate = record.wallets.first()
                        val thefts = record.walletThefts[walletToMigrate]
                        record = record.copy(wallets = emptyList())
                        maskedWalletsMigratedCount++

                        val ownerExists = recordMap.values.any { !it.isMasked && it.wallets.contains(walletToMigrate) }
                        if (!ownerExists) {
                            record = record.copy(walletThefts = record.walletThefts - walletToMigrate)
                            val placeholderIp = "unknown:wallet_$walletToMigrate"
                            var placeholder = recordMap[placeholderIp] ?: TargetRecord(ip = placeholderIp)
                            if (!placeholder.wallets.contains(walletToMigrate)) placeholder = placeholder.copy(wallets = placeholder.wallets + walletToMigrate)
                            if (thefts != null) {
                                placeholder = placeholder.copy(walletThefts = placeholder.walletThefts +
                                    (walletToMigrate to (placeholder.walletThefts[walletToMigrate].orEmpty() + thefts)))
                            }
                            recordMap[placeholderIp] = placeholder
                        }
                    }
                }
                recordMap[record.ip] = record
            }
            recordMap.values.toList()
        }

        return MaintenanceResult(
            backupFilePath = backupPath,
            multiWalletRecordsFixed = multiWalletCount,
            maskedSoftwareCleared = maskedSoftwareCount,
            maskedWalletsMigrated = maskedWalletsMigratedCount
        )
    }

    /**
     * Drops thefts older than 30 days, unless that would leave a wallet with no multi-event cluster (its only source
     * of an income rate).
     */
    fun purgeOldTheftEvents(): Int {
        val cutoff = (kotlinx.datetime.Clock.System.now() - 30.days).toEpochMilliseconds()
        var purgedCount = 0

        TargetRepository.update { list ->
            val result = list.map { original ->
                var record = original
                for (wallet in original.walletThefts.keys) {
                    val events = record.walletThefts[wallet] ?: continue
                    val oldEvents = events.filter { it.timestampMs < cutoff }
                    if (oldEvents.isEmpty()) continue
                    val newEvents = events.filter { it.timestampMs >= cutoff }

                    val clustersBefore = record.getReliableClusters(wallet).filter { it.size >= 2 }
                    val purged = record.copy(walletThefts = record.walletThefts + (wallet to newEvents))
                    val clustersAfter = purged.getReliableClusters(wallet).filter { it.size >= 2 }
                    if (clustersBefore.isNotEmpty() && clustersAfter.isEmpty()) continue // only reliable cluster source

                    record = purged
                    purgedCount += oldEvents.size
                }
                record
            }
            if (purgedCount > 0) result else null
        }
        return purgedCount
    }
}
