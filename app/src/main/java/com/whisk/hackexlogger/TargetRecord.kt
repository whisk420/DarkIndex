package com.whisk.hackexlogger

import com.google.gson.annotations.SerializedName
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
 * Where a target's wallet came from. Stored as the same strings as before the enum existed
 * (`"wallet_page"`, `"log"`), so databases and exports read and write identically across versions.
 * An unrecognised value loads as null, which is treated like a log wallet.
 */
enum class WalletSource {
    /** The target's own wallet screen: proof. Only a wallet-screen scrape may replace or move it. */
    @SerializedName("wallet_page") PAGE,
    /** Inferred from your log: highly probable, and a target may hold several. */
    @SerializedName("log") LOG
}

/**
 * One target in the database. **Immutable**: change a record with `copy(...)` or the `with…` helpers, then save
 * the new list. That is what lets [TargetRepository.getAllRecords] hand out the cached list without copying it.
 *
 * The JSON layout (field names and value formats) is the storage and export format; keep it unchanged.
 * Gson can leave collections null for old or hand-edited JSON, so records from disk or imports go through
 * [normalized] first.
 */
data class TargetRecord(
    val ip: String,
    val username: String? = null,
    val clan: String? = null,
    val level: Int? = null,
    /** `"<Device> • <Network>"` (either part may be missing). Use [device] / [network] to read it. */
    val hardware: String? = null,
    val firewall: Int? = null,
    val encryptor: Int? = null,
    val wallets: List<String> = emptyList(),
    val downloads: Map<String, SoftwareEntry> = emptyMap(),
    val walletThefts: Map<String, List<TheftEvent>> = emptyMap(),
    /** Local time `yyyy-MM-dd HH:mm` (sorts as text). Kept as a string so older versions can read it. */
    val lastValidated: String? = null,
    /** Null for records saved before 2026-11-20 (treated like a log wallet). */
    val walletSource: WalletSource? = null
) {
    val walletFromPage: Boolean get() = walletSource == WalletSource.PAGE

    /** The device part of [hardware], e.g. `Nova II` (same split as [HardwareCatalog.device]). */
    val device: String? get() = hardware?.substringBefore("•")?.trim()?.takeIf { it.isNotEmpty() }

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

    /** Adds a theft (ignored if the same time + amount is already stored); the list stays sorted by time. */
    fun withTheftEvent(wallet: String, timestampMs: Long, amount: Int): TargetRecord {
        val list = walletThefts[wallet] ?: emptyList()
        if (list.any { it.timestampMs == timestampMs && it.amount == amount }) return this
        return copy(walletThefts = walletThefts + (wallet to (list + TheftEvent(timestampMs, amount)).sortedBy { it.timestampMs }))
    }

    /** Adds [events] to [wallet]'s thefts, skipping duplicates (same time + amount). */
    fun withTheftEvents(wallet: String, events: List<TheftEvent>): TargetRecord =
        events.fold(this) { record, e -> record.withTheftEvent(wallet, e.timestampMs, e.amount) }

    fun withoutTheftEvents(wallet: String): TargetRecord =
        if (wallet in walletThefts) copy(walletThefts = walletThefts - wallet) else this

    fun withDownload(name: String, entry: SoftwareEntry): TargetRecord = copy(downloads = downloads + (name to entry))

    /**
     * The same record with every collection non-null. Gson bypasses Kotlin's defaults, so a record read from JSON
     * that lacks `wallets`, `downloads` or `walletThefts` (old data, hand-edited files) would otherwise hold nulls.
     * Returns null when the record has no usable `ip` (it can't be keyed).
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun normalized(): TargetRecord? {
        if (ip == null || ip.isBlank()) return null
        val w = wallets ?: emptyList()
        val d = downloads ?: emptyMap()
        val t = walletThefts ?: emptyMap()
        val cleanW = if (w.any { it == null }) w.filterNotNull() else w
        val cleanD = if (d.values.any { it == null || it.status == null }) {
            d.filterValues { it != null }.mapValues { (_, e) -> if (e.status == null) e.copy(status = "Unknown") else e }
        } else d
        val cleanT = if (t.values.any { it == null || it.any { e -> e == null } }) {
            t.filterValues { it != null }.mapValues { (_, list) -> list.filterNotNull() }
        } else t
        return if (w === wallets && cleanW === w && d === downloads && cleanD === d && t === walletThefts && cleanT === t) this
        else copy(wallets = cleanW, downloads = cleanD, walletThefts = cleanT)
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
            if (gap > 1.hours) {
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
                delta == -3 -> 26 // the game's own crack row showed 26% at −3 (Raw Dump #166); was 18
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