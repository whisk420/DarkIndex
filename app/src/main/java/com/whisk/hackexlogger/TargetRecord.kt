package com.whisk.hackexlogger

import java.util.Locale

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

    fun getEstimatedIncome(wallet: String): String? {
        val list = walletThefts[wallet] ?: return null
        if (list.isEmpty()) return null

        val sorted = list.sortedBy { it.timestampMs }
        
        // Discard older thefts if there is a gap > 24 hours between consecutive hacks
        var startIndex = 0
        for (i in 1 until sorted.size) {
            if (sorted[i].timestampMs - sorted[i - 1].timestampMs > 24 * 3600000L) {
                startIndex = i
            }
        }
        
        val recentCluster = sorted.subList(startIndex, sorted.size)

        if (recentCluster.size == 1) {
            val amount = recentCluster.first().amount
            return "💵 ~${formatAmount(amount)}"
        }

        val t0 = recentCluster.first().timestampMs
        val tn = recentCluster.last().timestampMs
        val hours = (tn - t0) / 3600000.0

        if (hours < 0.1) {
            val total = recentCluster.sumOf { it.amount }
            return "💵 ~${formatAmount(total)}"
        }

        val totalStolen = recentCluster.drop(1).sumOf { it.amount }
        val rate = totalStolen / hours
        return "💵 ~${formatAmount(rate.toInt())}/hr"
    }

    private fun formatAmount(amount: Int): String {
        return if (amount >= 10000) {
            String.format(Locale.US, "%.1fk", amount / 1000.0)
        } else {
            String.format(Locale.US, "%,d", amount)
        }
    }
}