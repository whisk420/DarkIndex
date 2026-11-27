package com.whisk.hackexlogger

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Game mechanics as pure functions, shared by the Knowledge Base calculators and the rest of the app so the two
 * can never disagree. Each function says where its numbers come from (see docs/game.md §1/§3 and
 * KnowledgeBaseContent). "Exact fit" means the formula reproduces every number the guide publishes.
 */
object GameFormulas {

    // ── Spam ────────────────────────────────────────────────────────────────────────────────────────────────

    const val SPAM_CAP_PER_HOUR = 100

    /*
     * Spam income model, fitted 2026-11-22 to 16 fresh uploads (rate read off SPAM EARNINGS; victim Lv / Spam Lv → /hr):
     *   victim Lv43: 1→18, 2→23, 3→27, 7→47, 11→69      victim Lv33: 7→47, 13→81, 20→100
     *   victim Lv28: 1→18, 2→23, 6→42                   victim Lv7:  13→38, 20→30
     *   victim Lv19: 10→58
     *   victim Lv2:  1→13, 3→18, 7→24, 11→26
     * - Only VICTIM level − SPAM level matters; the player's own level plays no part (Lv7 pays 47 on both Lv43 and
     *   Lv33 victims; the guide implies the player's level).
     * - Victim 11+ levels above the Spam: full bonus, rate = [spamMaxIncome].
     * - Spam above the victim: −5% per level with no floor seen (Lv20 on a Lv7 victim pays 30 = ×0.35), so an
     *   over-levelled Spam earns LESS (Lv13 38 > Lv20 30 on that victim). Fitted slope 4.9–5.1%.
     * - The guide's Spam curve (8.85 + 2.89L + 0.0415L²) has the wrong shape: no scaling of it fits all 16.
     * - 2026-11-20 SPAM EARNINGS: eight Lv16 Spams at 99, none at 100; Lv17 at 100. So Lv16 tops out at 99.
     * - Bonus: +4% per level the victim is above the Spam (fits 3.65–4.15%; the mirrored +5% is ruled out by
     *   victim Lv19 / Spam Lv10 = 58), full bonus from about +12.
     * Unmeasured: Spam more than 13 levels above the victim (the app assumes the −5% line stops at ×0.10).
     */

    /** Crypto/hr when the victim is far enough above the Spam for the full bonus (fit above; Lv16 99.2, Lv17 105.6). */
    fun spamMaxIncome(spamLevel: Int): Double {
        if (spamLevel <= 0) return 0.0
        val l = spamLevel.toDouble()
        return 13.9 + 4.3 * l + 0.0645 * l * l
    }

    /** Full bonus over an equal-level victim (fit: 1.465–1.485; the guide says "up to 50%"). */
    const val SPAM_MAX_BONUS = 1.475

    /** Bonus per level the victim is above the Spam (measured: fits 3.65–4.15%). */
    const val SPAM_BONUS_PER_LEVEL = 0.04

    /** Penalty per level the Spam is above the victim (measured, 4.9–5.1%). */
    const val SPAM_PENALTY_PER_LEVEL = 0.05

    /** Lowest multiplier: an assumption, nothing measured past ×0.35. */
    const val SPAM_MIN_MULTIPLIER = 0.10

    /** Crypto/hr of a Spam of [spamLevel] when the victim's level equals the Spam's, before the cap. */
    fun spamBaseIncome(spamLevel: Int): Double = spamMaxIncome(spamLevel) / SPAM_MAX_BONUS

    /**
     * Income multiplier for [levelGap] = **victim level − Spam level**: +4% per level the victim is above the Spam
     * (max [SPAM_MAX_BONUS]), −5% per level the Spam is above the victim (min [SPAM_MIN_MULTIPLIER]).
     */
    fun spamLevelGapMultiplier(levelGap: Int): Double = when {
        levelGap > 0 -> (1.0 + SPAM_BONUS_PER_LEVEL * levelGap).coerceAtMost(SPAM_MAX_BONUS)
        levelGap < 0 -> (1.0 + SPAM_PENALTY_PER_LEVEL * levelGap).coerceAtLeast(SPAM_MIN_MULTIPLIER)
        else -> 1.0
    }

    /**
     * Spam decay: full rate for days 1–21, then a straight line to 10% at day 100 (exact fit of the guide's
     * 90% / 67% / 44% at days 30 / 50 / 70). [halfDecay] = Scythe device perk ("spam decays half as fast").
     */
    fun spamDecayFactor(daysSinceUpload: Double, halfDecay: Boolean = false): Double {
        val d = if (halfDecay) daysSinceUpload / 2.0 else daysSinceUpload
        if (d <= 21.0) return 1.0
        return (1.0 - (d - 21.0) * 0.90 / 79.0).coerceAtLeast(0.10)
    }

    /**
     * Hourly income of one Spam of [spamLevel] uploaded to a victim of [victimLevel], capped at 100 and then decayed.
     * Fixed at upload: later level changes on either side don't change it (developer). Not monotonic: past the victim's
     * level a higher Spam can earn less (see [spamBestLevel]).
     */
    fun spamIncomePerHour(spamLevel: Int, victimLevel: Int, daysSinceUpload: Double = 0.0, halfDecay: Boolean = false): Int {
        if (spamLevel <= 0) return 0
        val fresh = (spamBaseIncome(spamLevel) * spamLevelGapMultiplier(victimLevel - spamLevel))
            .roundToInt().coerceAtMost(SPAM_CAP_PER_HOUR)
        return (fresh * spamDecayFactor(daysSinceUpload, halfDecay)).roundToInt()
    }

    /** The lowest Spam level up to [maxLevel] that earns the most on a victim of [victimLevel] (fresh upload). */
    fun spamBestLevel(victimLevel: Int, maxLevel: Int): Int {
        var best = 1
        for (l in 2..maxLevel) if (spamIncomePerHour(l, victimLevel) > spamIncomePerHour(best, victimLevel)) best = l
        return best
    }

    /** Cheapest Spam level that reaches the 100/hr cap on a victim of [victimLevel], or null if none up to [maxLevel]. */
    fun spamCapLevel(victimLevel: Int, maxLevel: Int = 60): Int? =
        (1..maxLevel).firstOrNull { spamIncomePerHour(it, victimLevel) >= SPAM_CAP_PER_HOUR }

    /** The lowest Spam level that can ever cap (on a high enough victim), and the lowest victim level where it does. */
    fun spamBestCap(): Pair<Int, Int> {
        val level = spamCapLevel(999)!!
        return level to (1..999).first { spamIncomePerHour(level, it) >= SPAM_CAP_PER_HOUR }
    }

    /**
     * Fee taken from Spam collections: 0.5% per active Spam beyond 2, capped at 30% (exact fit of the guide's
     * table). Hacker Pass cuts the fee by 25%, Elite Pass by 50% ([subscriptionDiscount] = 0.25 / 0.50).
     */
    fun spamFeeRate(activeSpam: Int, subscriptionDiscount: Double = 0.0): Double {
        val rate = (0.005 * (activeSpam - 2)).coerceIn(0.0, 0.30)
        return rate * (1.0 - subscriptionDiscount)
    }

    data class BotnetTier(val name: String, val slots: Int, val cost: Int?, val levelReq: Int?)

    /** Guide: botnet tiers, slots, Crypto cost, level requirement. */
    val BOTNET_TIERS = listOf(
        BotnetTier("Free", 2, null, null),
        BotnetTier("Node", 4, 800, 3),
        BotnetTier("Cluster", 8, 3_500, null),
        BotnetTier("Grid", 16, 15_000, null),
        BotnetTier("Farm", 28, 42_000, null),
        BotnetTier("Hive", 44, 95_000, null),
        BotnetTier("Nexus", 64, 180_000, null),
    )

    // ── Siphon ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Highest siphon rate seen: a Lv25 siphon shows 6%, not the line's 7% (Raw Dump #16). */
    const val SIPHON_MAX_PERCENT = 6.0

    /**
     * Siphon skim rate in % for a siphon uploaded at [level]. Measured 2026-11-22 (Raw Dump #16, all 122 deployments):
     * Lv1 1%, Lv2 1.25%, Lv5 2%, Lv6 2.25%, Lv13 4%, Lv17 5%, Lv20 5.75%, Lv25 6%. 0.75 + 0.25 × level fits every row
     * up to Lv20; the line reaches 6% at Lv21 and Lv25 shows 6%, so the rate caps at 6% from Lv21 (developer-accepted).
     */
    fun siphonPercent(level: Int): Double? =
        if (level <= 0) null else minOf(0.75 + 0.25 * level, SIPHON_MAX_PERCENT)

    // ── Store prices & Keygen ───────────────────────────────────────────────────────────────────────────────

    /** Guide base prices; defensive apps get ×0.85 (verified for Firewall, Encryptor, Proxy, Trace). */
    enum class App(val displayName: String, val basePrice: Int, val defensive: Boolean) {
        FIREWALL("Firewall", 800, true),
        BYPASSER("Bypasser", 800, false),
        PASSWORD_CRACKER("Password Cracker", 1100, false),
        PASSWORD_ENCRYPTOR("Password Encryptor", 1100, true),
        ANTIVIRUS("Antivirus", 1000, true),
        SPAM("Spam", 1800, false),
        SIPHON("Siphon", 1600, false),
        ROOTKIT("Rootkit", 1400, false),
        PROXY("Proxy", 900, true),
        TRACE("Trace", 900, true),
        KEYGEN("Keygen", 600, false);
    }

    /**
     * Store price of [app] at [level]: ceil(base × level^1.3), then for defensive apps × 0.85 rounded up.
     * Exact on all 42 real Store prices seen (Lv 1–35, every app; Raw Dumps #6, #38, #41–#134), including
     * Encryptor 19 = 42,973. Replaced a cubic fit that was only right between Lv 15 and 25 (Q-09, 2026-11-27).
     */
    fun storePrice(app: App, level: Int): Int? {
        if (level < 1) return null
        val full = ceil(app.basePrice * level.toDouble().pow(1.3) - 1e-9)
        return if (app.defensive) ceil(full * 0.85 - 1e-9).toInt() else full.toInt()
    }

    /**
     * Keygen fee as a fraction of the target level's Store price: 15% at a 1-level jump, linear to 50% at 20+ (guide).
     * Confirmed by 9 LOCKED rows on the developer's Apps page (Raw Dump #33): every row fits these steps, and the two
     * Lv 33 unlocks (+2 and +3 jumps) imply the same Store curve value.
     */
    fun keygenFeeRatio(jump: Int): Double? {
        if (jump < 1) return null // can't key at or below your level
        return (0.15 + (jump - 1) * (0.35 / 19.0)).coerceAtMost(0.50)
    }

    fun keygenFee(app: App, fromLevel: Int, toLevel: Int): Int? {
        val ratio = keygenFeeRatio(toLevel - fromLevel) ?: return null
        val price = storePrice(app, toLevel) ?: return null
        return ceil(price * ratio - 1e-9).toInt() // rounds up: Rootkit 6 → 25 = 44,271.07 → 44,272 (Raw Dump #33)
    }

    /**
     * XP for buying [app] at [level] in the Store, or unlocking a downloaded copy with Keygen (the same XP either way):
     * 0.2 × base × level^1.25, × 0.85 for defensive apps. The game keeps the fraction (the bar shows it rounded down),
     * so single readings can be 1 off. Fits every 2026-11-27 purchase and unlock (Lv 1–5) and Keygen 26 = 7,045.
     */
    fun softwareXp(app: App, level: Int): Double =
        0.2 * app.basePrice * level.toDouble().pow(1.25) * (if (app.defensive) 0.85 else 1.0)

    // ── Proxy / Trace / hardening ───────────────────────────────────────────────────────────────────────────

    /** Guide activation durations (hours) and their bulk discounts. */
    val PROXY_DURATIONS = listOf(2 to 0.0, 6 to 0.05, 12 to 0.10, 24 to 0.15)

    /** Proxy activation cost: ceil(10 × √level × hours × (1 − discount)). Exact fit of all 20 cells of the guide's table. */
    fun proxyActivationCost(proxyLevel: Int, hours: Int): Int? {
        val discount = PROXY_DURATIONS.firstOrNull { it.first == hours }?.second ?: return null
        if (proxyLevel < 1) return null
        val raw = 10.0 * sqrt(proxyLevel.toDouble()) * hours * (1.0 - discount)
        return ceil(raw - 1e-6).toInt()
    }

    /** Octets hidden by a Proxy of [level]: 1–5 → 1, 6–10 → 2, 11–15 → 3, 16+ → 4 (20+ also unlocks Stealth). */
    fun proxyMaskedOctets(level: Int): Int = when {
        level <= 0 -> 0
        level <= 5 -> 1
        level <= 10 -> 2
        level <= 15 -> 3
        else -> 4
    }

    /** Example IP with [level]'s masking applied, e.g. 192.168.1.xxx. */
    fun proxyMaskExample(level: Int): String {
        val octets = listOf("192", "168", "1", "42")
        val masked = proxyMaskedOctets(level)
        return octets.mapIndexed { i, o -> if (i >= 4 - masked) "xxx" else o }.joinToString(".")
    }

    /** Trace detection rep: 15 + (trace level − 1), capped at 25 (guide). */
    fun traceDetectionRep(traceLevel: Int): Int = (15 + (traceLevel - 1)).coerceIn(15, 25)

    /** Hardening: Firewall vs Backdoor / Encryptor vs Exploit Kit block chance in %, 3% per level above 2, cap 90% (guide). */
    fun hardeningBlockChance(defenceLevel: Int): Int = (3 * (defenceLevel - 2)).coerceIn(0, 90)

    // ── Hacking, antivirus, processes ───────────────────────────────────────────────────────────────────────

    /** Bypass/crack success % by offence − defence (measured from the developer's logged attempts; not in the guide). */
    fun hackSuccessChance(offence: Int, defence: Int): Int = TargetRecord.calculateSuccessProbability(offence, defence)

    /** Antivirus removal % per virus per scan by AV level − virus level (guide table). */
    fun avRemovalChance(avLevel: Int, virusLevel: Int): Int {
        val d = avLevel - virusLevel
        return when {
            d >= 5 -> 95
            d >= 0 -> 50 + 10 * d
            d == -1 -> 20
            else -> (20 - 2 * (-d - 1)).coerceAtLeast(5)
        }
    }

    /** Chance that at least one of [scans] scans removes a virus. */
    fun avRemovalAfterScans(chancePercent: Int, scans: Int): Double =
        1.0 - (1.0 - chancePercent / 100.0).pow(scans.coerceAtLeast(0))

    /** Guide's AV scan table: device level → solo scan minutes. Its text gives slightly different values at 10/12 (55/48). */
    private val AV_SCAN_POINTS = listOf(1 to 180.0, 3 to 120.0, 5 to 90.0, 8 to 63.0, 10 to 53.0, 12 to 45.0)
    const val AV_SCAN_FLOOR_MINUTES = 45.0

    /** Solo AV scan minutes for a device level (guide table, straight-line between its points, floor 45). */
    fun avScanMinutes(deviceLevel: Int): Double {
        val lvl = deviceLevel.coerceIn(1, HardwareCatalog.MAX_DEVICE_LEVEL)
        val exact = AV_SCAN_POINTS.firstOrNull { it.first == lvl }?.second
        if (exact != null) return exact
        val upper = AV_SCAN_POINTS.first { it.first > lvl }
        val lower = AV_SCAN_POINTS.last { it.first < lvl }
        val t = (lvl - lower.first).toDouble() / (upper.first - lower.first)
        return (lower.second + t * (upper.second - lower.second)).coerceAtLeast(AV_SCAN_FLOOR_MINUTES)
    }

    /** Concurrency penalty: each extra process in the pool adds +75% of the base time (guide). */
    fun concurrencyMultiplier(processesInPool: Int): Double = 1.0 + 0.75 * (processesInPool.coerceAtLeast(1) - 1)

    /** Each Overclock skips 90 s (guide). */
    const val OC_SECONDS = 90

    /**
     * Ads: up to 4 per process, cutting 10%, 9%, 8%, 7% (guide: "totaling 34%", so the cuts add up rather than
     * compound). Returns the time left as a fraction.
     */
    fun remainingAfterAds(ads: Int): Double = 1.0 - listOf(0.10, 0.09, 0.08, 0.07).take(ads.coerceIn(0, 4)).sum()

    /** Device process speed (bypass, crack, AV) by device level 1–12 (guide). Not the same as upload defence. */
    val DEVICE_SPEED = listOf(1.0, 1.5, 2.25, 3.5, 5.0, 7.0, 9.0, 11.0, 14.0, 17.0, 20.0, 23.0)

    /** Guide device prices by level (index 0 = Raider, free). */
    val DEVICE_PRICES = listOf(0, 4_000, 10_000, 18_000, 28_000, 40_000, 56_000, 82_000, 120_000, 160_000, 220_000, 300_000)

    /** Guide network prices by level (index 0 = DSL, free). */
    val NETWORK_PRICES = listOf(0, 3_000, 7_000, 12_000, 20_000, 30_000, 44_000, 60_000, 85_000, 120_000, 170_000)

    // ── Reputation, score, levels ───────────────────────────────────────────────────────────────────────────

    /** Leaderboard score: floor(Level × Rep^0.75). Verified on 6 profiles. */
    fun score(level: Int, rep: Int): Long = floor(level * rep.coerceAtLeast(0).toDouble().pow(0.75)).toLong()

    /** Rep needed for [targetScore] at [level] (inverse of [score]). */
    fun repForScore(level: Int, targetScore: Long): Int {
        if (level <= 0) return 0
        var rep = ceil((targetScore.toDouble() / level).pow(1.0 / 0.75)).toInt().coerceAtLeast(0)
        while (rep > 0 && score(level, rep - 1) >= targetScore) rep--
        while (score(level, rep) < targetScore) rep++
        return rep
    }

    /**
     * Effective levels a rootkit takes off the victim's Firewall and Encryptor (measured by the developer, 2026-11-22):
     * Lv 1–2 → 1, Lv 3 → 2, Lv 6+ → 3 (the cap). Lv 4–5 are 2 by elimination (between Lv 3's 2 and Lv 6's
     * first 3), not yet seen. 0 for no rootkit.
     */
    fun rootkitDebuff(rootkitLevel: Int): Int = when {
        rootkitLevel <= 0 -> 0
        rootkitLevel <= 2 -> 1
        rootkitLevel <= 5 -> 2
        else -> 3
    }

    /** Rep a victim loses when a virus lands: min(500, 25 × virus level). Daily loss cap 500, floor 50% of peak (guide). */
    fun repLossFromVirus(virusLevel: Int): Int = minOf(500, 25 * virusLevel.coerceAtLeast(0))

    /** Rep for beating an attacker's failed attempt: 15 + ⌈attacker software level ÷ 5⌉ (guide). */
    fun defenceRep(attackerSoftwareLevel: Int): Int = 15 + ceil(attackerSoftwareLevel.coerceAtLeast(0) / 5.0).toInt()

    /** Hack rep factor by software gap (your offence − their defence): full to +5, linear to zero at +10 (guide). */
    fun hackRepGapFactor(softwareGap: Int): Double = when {
        softwareGap <= 5 -> 1.0
        softwareGap >= 10 -> 0.0
        else -> (10 - softwareGap) / 5.0
    }

    /** XP to the next level, known points only (guide + developer). Null = not measured yet (Q-13). */
    val XP_TO_NEXT_KNOWN = mapOf(1 to 589, 2 to 1_019, 3 to 1_504, 4 to 2_035, 5 to 2_605, 6 to 3_209, 7 to 3_845, 8 to 4_510, 9 to 5_202, 10 to 5_919, 20 to 14_209, 30 to 24_079, 35 to 29_484, 38 to 32_860, 44 to 39_888)

    /** Cold storage cap, known points only (guide). Q-10. */
    val COLD_STORAGE_KNOWN = mapOf(1 to 3_000, 2 to 3_714, 3 to 4_899, 5 to 8_841, 6 to 11_659, 7 to 15_075, 8 to 19_112, 9 to 23_789, 10 to 29_124, 20 to 121_891, 30 to 294_147)

    /** Crypto → Credits exchange: 250:1, min 5 Credits, max 750 Credits per calendar month (guide). */
    const val CRYPTO_PER_CREDIT = 250

    // ── Crew server ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Crew-server parts, all measured from a crew's complete server log (Raw Dumps #25 and #27; the log's
     * contributions minus its spending equal the treasury balance to the crypto):
     * - Upgrading a part to level k costs `base × k²` (every upgrade in the log, L1–L5).
     * - Upkeep is 0.5% of everything spent on the part per day (the guide's table; 19,325/day on screen). It is
     *   charged about once a day for the time since the last charge. Storage has no upkeep.
     * - Surge (+2 levels): see [crewSurgePrice].
     */
    enum class CrewPart(val displayName: String, val base: Int, val hasUpkeep: Boolean = true, val surgeable: Boolean = true) {
        CPU("CPU", 70_000), NIC("NIC", 70_000), RAM("RAM", 120_000, surgeable = false), UPLINK("Uplink", 45_000),
        STORAGE("Storage", 60_000, hasUpkeep = false, surgeable = false)
    }

    /** Price to upgrade [part] from level − 1 to [level] (1–5). */
    fun crewPartUpgradePrice(part: CrewPart, level: Int): Long? =
        if (level in 1..5) part.base.toLong() * level * level else null

    /** Daily upkeep of [part] at [level] while powered: 0.5% of the total spent on it. 0 at level 0. */
    fun crewPartUpkeep(part: CrewPart, level: Int): Long? {
        if (level !in 0..5) return null
        if (!part.hasUpkeep) return 0
        return part.base.toLong() * level * (level + 1) * (2 * level + 1) / 6 / 200
    }

    /**
     * Price of a surge (+2 levels) on [part] at [level] for [hours] (6/12/24): the next two levels' price
     * × hours/1200, with 5% off for 12 h and 10% off for 24 h. Matches all 19 surges in the log (L1–L3, 12 h and 24 h;
     * extending costs the same as starting). 6 h is full price (developer-confirmed). Null if the part can't surge.
     */
    fun crewSurgePrice(part: CrewPart, level: Int, hours: Int): Long? {
        if (!part.surgeable || level !in 0..5) return null
        val discount = when (hours) { 6 -> 0.0; 12 -> 0.05; 24 -> 0.10; else -> return null }
        val nextTwo = part.base.toLong() * ((level + 1) * (level + 1) + (level + 2) * (level + 2))
        return Math.round(nextTwo * hours / 1200.0 * (1 - discount))
    }
}
