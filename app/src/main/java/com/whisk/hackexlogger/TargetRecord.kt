package com.whisk.hackexlogger

import java.util.Locale
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Represents software installed or stored on a target device in the HackEx game.
 *
 * @property level Software level in-game.
 * @property status Current status (e.g. "installed", "downloading").
 * @property lastSeen Timestamp string of when this software state was observed.
 */
data class SoftwareEntry(
    val level: Int,
    val status: String,
    val lastSeen: String?
)

/**
 * Data model representing a recorded crypto theft event from a target wallet.
 */
data class TheftEvent(
    val timestampMs: Long,
    val amount: Int
)

/**
 * Data model representing an in-game target entity collected within HackExLogger.
 *
 * Stores target information extracted from game screens and logs, such as virtual IP addresses,
 * player usernames, clan affiliations, hardware setups, defense levels, and associated crypto wallets.
 */
data class TargetRecord(
    var ip: String,
    var username: String? = null,
    var clan: String? = null,
    var level: Int? = null,
    var hardware: String? = null,
    var firewall: Int? = null,
    var encryptor: Int? = null,
    var wallets: MutableList<String> = mutableListOf(),
    var downloads: MutableMap<String, SoftwareEntry> = mutableMapOf(),
    var walletThefts: MutableMap<String, MutableList<TheftEvent>> = mutableMapOf(),
    var lastValidated: String? = null
) {
    /**
     * True if the target IP address contains masked components (e.g., "192.168.xxx.12" or "[UNKNOWN]").
     */
    val isMasked: Boolean
        get() = (ip.contains("xxx", ignoreCase = true) || ip.contains("UNKNOWN", ignoreCase = true)) && !ip.startsWith("unknown:")

    /**
     * True if this record was created without a known IP address and assigned a temporary placeholder key.
     */
    val isTemporary: Boolean
        get() = ip.startsWith("unknown:")

    /**
     * True if the target lacks a wallet OR lacks any of the three core software required to assume 
     * the software screen was scraped (Bypasser, Firewall, Password Encryptor).
     */
    val needsAdvancedTargeting: Boolean
        get() {
            if (isMasked || isTemporary) return false
            val lacksWallet = wallets.isEmpty()
            val lacksBypasser = !downloads.containsKey("Bypasser")
            val lacksFirewall = !downloads.containsKey("Firewall")
            val lacksEncryptor = !downloads.containsKey("Password Encryptor")
            return lacksWallet || lacksBypasser || lacksFirewall || lacksEncryptor
        }

    fun addTheftEvent(wallet: String, timestampMs: Long, amount: Int) {
        val list = walletThefts.getOrPut(wallet) { mutableListOf() }
        if (list.none { it.timestampMs == timestampMs && it.amount == amount }) {
            list.add(TheftEvent(timestampMs, amount))
            list.sortBy { it.timestampMs }
        }
    }

    fun clearTheftEvents(wallet: String) {
        walletThefts.remove(wallet)
    }

    fun getReliableClusters(wallet: String): List<List<TheftEvent>> {
        val list = walletThefts[wallet] ?: return emptyList()
        val sorted = list.sortedBy { it.timestampMs }
        if (sorted.isEmpty()) return emptyList()

        val rawClusters = mutableListOf<MutableList<TheftEvent>>()
        var current = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val t1 = Instant.fromEpochMilliseconds(sorted[i - 1].timestampMs)
            val t2 = Instant.fromEpochMilliseconds(sorted[i].timestampMs)
            val gap = t2 - t1
            if (gap > 24.hours) {
                rawClusters.add(current)
                current = mutableListOf(sorted[i])
            } else {
                current.add(sorted[i])
            }
        }
        rawClusters.add(current)

        val finalClusters = mutableListOf<List<TheftEvent>>()

        for (cluster in rawClusters) {
            if (cluster.size <= 1) {
                finalClusters.add(cluster)
                continue
            }
            val firstTs = Instant.fromEpochMilliseconds(cluster.first().timestampMs)
            val lastTs = Instant.fromEpochMilliseconds(cluster.last().timestampMs)
            
            if (lastTs - firstTs <= 7.days) {
                finalClusters.add(cluster)
            } else {
                var windowStart = firstTs
                var windowEnd = windowStart + 7.days
                var subCluster = mutableListOf<TheftEvent>()

                for (event in cluster) {
                    val eventTs = Instant.fromEpochMilliseconds(event.timestampMs)
                    if (eventTs <= windowEnd) {
                        subCluster.add(event)
                    } else {
                        if (subCluster.isNotEmpty()) finalClusters.add(subCluster)
                        windowStart = eventTs
                        windowEnd = windowStart + 7.days
                        subCluster = mutableListOf(event)
                    }
                }
                if (subCluster.isNotEmpty()) finalClusters.add(subCluster)
            }
        }
        return finalClusters
    }

    fun getEstimatedIncome(wallet: String): String? {
        val clusters = getReliableClusters(wallet)
        if (clusters.isEmpty()) return null

        val multiEventRates = mutableListOf<Double>()
        for (cluster in clusters) {
            if (cluster.size < 2) continue
            val t0 = cluster.first().timestampMs
            val tn = cluster.last().timestampMs
            val hours = (tn - t0) / 3600000.0
            if (hours < 0.1) continue

            val incomeInSpan = cluster.drop(1).sumOf { it.amount }
            val ratePerHour = incomeInSpan / hours
            multiEventRates.add(ratePerHour)
        }

        if (multiEventRates.isNotEmpty()) {
            val avgRate = multiEventRates.average()
            return "₵${formatAmount(avgRate.toInt())}/hr"
        }

        val lastEvent = walletThefts[wallet]?.maxByOrNull { it.timestampMs } ?: return null
        return "₵${formatAmount(lastEvent.amount)}"
    }

    private fun formatAmount(amount: Int): String {
        return if (amount >= 10000) {
            String.format(Locale.US, "%.1fk", amount / 1000.0)
        } else {
            String.format(Locale.US, "%,d", amount)
        }
    }

    companion object {
        /**
         * Calculates the hack success probability percentage (5% to 95%) based on level delta.
         * Delta = PlayerOffenseLevel - TargetDefenseLevel
         */
        fun calculateSuccessProbability(playerOffenseLevel: Int, targetDefenseLevel: Int): Int {
            val delta = playerOffenseLevel - targetDefenseLevel
            return when {
                delta >= 6 -> 95
                delta == 5 -> 90
                delta == 4 -> 82
                delta == 3 -> 74
                delta == 2 -> 66
                delta == 1 -> 58
                delta == 0 -> 50
                delta == -1 -> 42
                delta == -2 -> 34
                delta == -3 -> 18
                else -> 5
            }
        }
    }

    fun getBypassSuccessRate(playerBypasser: Int): Int? {
        val fw = firewall ?: return null
        return calculateSuccessProbability(playerBypasser, fw)
    }

    fun getCrackSuccessRate(playerCracker: Int): Int? {
        val enc = encryptor ?: return null
        return calculateSuccessProbability(playerCracker, enc)
    }
}