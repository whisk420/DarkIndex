package com.whisk.hackexlogger

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Game mechanics as pure functions, shared by the Knowledge Base calculators and the rest of the app so the two
 * can never disagree. Each function says where its numbers come from (see CLAUDE.md §2/§4 and
 * docs/knowledge-base-prep.md). "Exact fit" means the formula reproduces every number the guide publishes.
 */
object GameFormulas {

    // ── Spam ────────────────────────────────────────────────────────────────────────────────────────────────

    const val SPAM_CAP_PER_HOUR = 100

    /**
     * Crypto/hr of a Spam of [spamLevel] on a victim of your own level, before the cap.
     * Fit of the guide's table: with [spamLevelGapMultiplier] and normal rounding it reproduces all 17 published
     * cells exactly (equal level Lv1 12, Lv5 24, Lv10 42, Lv15 62, Lv20 83; +10 and −10 columns).
     */
    fun spamBaseIncome(spamLevel: Int): Double {
        if (spamLevel <= 0) return 0.0
        val l = spamLevel.toDouble()
        return 8.85 + 2.89 * l + 0.0415 * l * l
    }

    /**
     * Income multiplier for a victim [levelGap] = victim level − your level.
     * Guide: +10 levels ≈ ×1.41 on every row, −10 = ×0.50; "up to 50% more against higher-level victims".
     * Modelled as +4.1%/level (cap +50%) and −5%/level. The straight lines between 0 and ±10 are unmeasured (M-01);
     * below −10 the guide has no data and the ×0.10 floor is a guess.
     */
    fun spamLevelGapMultiplier(levelGap: Int): Double = when {
        levelGap > 0 -> (1.0 + 0.041 * levelGap).coerceAtMost(1.5)
        levelGap < 0 -> (1.0 + 0.05 * levelGap).coerceAtLeast(0.10)
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

    /** Hourly income of one Spam deployment, capped at 100 and then decayed. Rounded like the guide's table. */
    fun spamIncomePerHour(spamLevel: Int, levelGap: Int, daysSinceUpload: Double = 0.0, halfDecay: Boolean = false): Int {
        if (spamLevel <= 0) return 0
        val capped = (spamBaseIncome(spamLevel) * spamLevelGapMultiplier(levelGap)).roundToInt().coerceAtMost(SPAM_CAP_PER_HOUR)
        return (capped * spamDecayFactor(daysSinceUpload, halfDecay)).roundToInt()
    }

    /** Cheapest Spam level that reaches the 100/hr cap on a fresh upload at [levelGap], or null if none up to [maxLevel]. */
    fun spamCapLevel(levelGap: Int, maxLevel: Int = 60): Int? =
        (1..maxLevel).firstOrNull { spamIncomePerHour(it, levelGap) >= SPAM_CAP_PER_HOUR }

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

    /** The Keygen price curve, verified to the crypto at L19–25. Below ~L15 it drifts from the guide (L5: 4,568 vs 4,862). */
    fun keygenCubic(level: Int): Double {
        val l = level.toDouble()
        return (-l * l * l + 147 * l * l + 6814 * l - 10212) / 6.0
    }

    /** Lowest level where the cubic matches the guide closely (L15: 20,283 vs 20,281). */
    const val STORE_PRICE_RELIABLE_FROM = 15

    /**
     * Store price of [app] at [level]. Offensive apps round to the nearest crypto (Bypasser 21: 41,877.33 → 41,877),
     * defensive (×0.85) apps round up (Proxy 21: 40,045.2 → 40,046; Firewall 21: 35,595.7 → 35,596). This also fits
     * the guide's Lv10 column. Encryptor 19 gives 42,975 against a recorded "42,973/4" (unresolved).
     */
    fun storePrice(app: App, level: Int): Int? {
        if (level < 2) return null
        val raw = keygenCubic(level) * app.basePrice / 600.0 * (if (app.defensive) 0.85 else 1.0)
        if (raw <= 0) return null
        return if (app.defensive) ceil(raw - 1e-9).toInt() else raw.roundToInt()
    }

    /** Keygen fee as a fraction of the target level's Store price: 15% at a 1-level jump, linear to 50% at 20+ (guide). */
    fun keygenFeeRatio(jump: Int): Double? {
        if (jump < 1) return null // can't key at or below your level
        return (0.15 + (jump - 1) * (0.35 / 19.0)).coerceAtMost(0.50)
    }

    fun keygenFee(app: App, fromLevel: Int, toLevel: Int): Int? {
        val ratio = keygenFeeRatio(toLevel - fromLevel) ?: return null
        val price = storePrice(app, toLevel) ?: return null
        return (price * ratio).roundToInt() // rounding unconfirmed (M-17)
    }

    /** XP per Store crypto for software (GEMINI empirical 0.1717; one owner data point 0.170). Keygen unlocks pay full XP. */
    const val SOFTWARE_XP_PER_CRYPTO = 0.1717

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

    /** Bypass/crack success % by offence − defence (GEMINI empirical; not in the guide). */
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

    /** XP to the next level, known points only (guide + owner). Null = not measured yet (M-22). */
    val XP_TO_NEXT_KNOWN = mapOf(1 to 589, 5 to 2_605, 10 to 5_919, 20 to 14_209, 30 to 24_079, 35 to 29_484, 38 to 32_860, 44 to 39_888)

    /** Cold storage cap, known points only (guide). M-18. */
    val COLD_STORAGE_KNOWN = mapOf(1 to 3_000, 5 to 8_841, 10 to 29_124, 20 to 121_891, 30 to 294_147)

    /** Crypto → Credits exchange: 250:1, min 5 Credits, max 750 Credits per calendar month (guide). */
    const val CRYPTO_PER_CREDIT = 250
}
