package com.whisk.hackexlogger

import kotlin.math.roundToInt

object GoodSpamUtils {
    /**
     * Upload-defence tier (0–9) of a target's device, via [HardwareCatalog]: Raider I–III = 0, then one tier per
     * level up to Nova Ultra / `<Line> XII` = 9. No hardware known = 0. Unknown names fall back to tier 3
     * (see TODO "Unknown device and network fallbacks").
     */
    fun getHardwarePenalty(hardware: String?): Int {
        if (hardware == null) return 0
        return HardwareCatalog.uploadDefenceTier(hardware) ?: 3
    }

    /**
     * Calculates the Good Spam priority score for a target.
     * Formula: (Level * 10.0) - (UploadMinutes * 0.5) - (Firewall * 1.0)
     *
     * UploadMinutes is the upload time of the **cheapest Spam level that hits the 100/hr cap** on this target
     * (see [spamUploadLevel]), on the player's **current** network. Lower Spam levels upload faster, so a target
     * that caps with a Lv.5 Spam is much cheaper to farm than one that needs Lv.20.
     * With no player level/Spam known, this falls back to the Lv.20 time (the old behaviour).
     */
    fun calculateScore(
        level: Int?,
        firewall: Int?,
        hardware: String?,
        playerNetwork: String? = null,
        playerLevel: Int? = null,
        installedSpamLevel: Int = 0
    ): Double {
        val lvl = (level ?: 0).toDouble()
        val fw = (firewall ?: 0).toDouble()
        val uploadLevel = if (playerLevel == null) 20
            else spamUploadLevel(calculateSpamPayoutInfo(level, playerLevel, installedSpamLevel), installedSpamLevel)
        val uploadSecs = NetworkSpeedUtils.getUploadSecondsForLevel(hardware, playerNetwork, uploadLevel)
        val uploadMins = uploadSecs / 60.0
        return (lvl * 10.0) - (uploadMins * 0.5) - fw
    }

    /**
     * The Spam level worth uploading to a target: the minimum level that hits the 100/hr cap, but never more than
     * you have installed (then your installed level is the best you can do).
     */
    fun spamUploadLevel(info: SpamPayoutInfo, installedSpamLevel: Int): Int =
        if (installedSpamLevel <= 0) info.recommendedSpamLevel
        else minOf(info.recommendedSpamLevel, installedSpamLevel)

    /**
     * Checks if a target's firewall level is within the maxFirewallLevel threshold.
     * maxFirewallLevel == 31 means "Uncapped" (allows any firewall level).
     */
    fun isEligible(firewall: Int?, maxFirewallLevel: Int): Boolean {
        val fw = firewall ?: 0
        val maxFw = if (maxFirewallLevel == 31) Int.MAX_VALUE else maxFirewallLevel
        return fw <= maxFw
    }

    /**
     * Calculates the minimum Spam level required to reach the 100 Crypto/hr payout cap
     * against a specific target level.
     * Returns Pair(RecommendedSpamLevel, HourlyCrypto at max(20, installed) [0..100]).
     */
    fun calculateRecommendedSpamLevel(targetLevel: Int?, playerLevel: Int?): Pair<Int, Int> {
        val info = calculateSpamPayoutInfo(targetLevel, playerLevel, 20)
        return Pair(info.recommendedSpamLevel, info.maxAchievableIncome)
    }

    /** Highest Spam level the cap search looks at when your installed level is lower (the game goes past Lv.20). */
    const val SPAM_SEARCH_MAX_LEVEL = 30

    /** Hourly payout (day 1, no decay) of a Spam of [spamLevel] with a [levelMultiplier] from [GameFormulas.spamLevelGapMultiplier]. */
    fun spamIncome(spamLevel: Int, levelMultiplier: Double): Int {
        if (spamLevel <= 0) return 0
        return (GameFormulas.spamBaseIncome(spamLevel) * levelMultiplier).roundToInt().coerceIn(0, GameFormulas.SPAM_CAP_PER_HOUR)
    }

    /**
     * Calculates Spam payout metrics for a target (fresh deployment, no decay):
     * - currentSpamIncome: what your installed Spam level would earn
     * - recommendedSpamLevel: the **minimum** Spam level that hits the 100/hr cap (searched up to
     *   max(30, installed)); max(20, installed) when the cap is out of reach
     * - maxAchievableIncome: the payout at max(20, installed)
     *
     * Model: the guide's earnings table (see [GameFormulas.spamBaseIncome] and [GameFormulas.spamLevelGapMultiplier]).
     */
    fun calculateSpamPayoutInfo(targetLevel: Int?, playerLevel: Int?, currentSpamLevel: Int): SpamPayoutInfo {
        val currSpam = currentSpamLevel.coerceAtLeast(0)
        val fallbackLevel = maxOf(20, currSpam)
        val tLvl = targetLevel ?: return SpamPayoutInfo(currSpam, if (currSpam == 0) 0 else 100, 20, 100, currSpam >= 20, true)
        val pLvl = playerLevel ?: tLvl

        val multiplier = GameFormulas.spamLevelGapMultiplier(tLvl - pLvl)
        val currIncome = spamIncome(currSpam, multiplier)
        val searchTop = maxOf(SPAM_SEARCH_MAX_LEVEL, currSpam)
        val minCapLevel = (1..searchTop).firstOrNull { spamIncome(it, multiplier) >= GameFormulas.SPAM_CAP_PER_HOUR }

        return SpamPayoutInfo(
            currentSpamLevel = currSpam,
            currentSpamIncome = currIncome,
            recommendedSpamLevel = minCapLevel ?: fallbackLevel,
            maxAchievableIncome = spamIncome(fallbackLevel, multiplier),
            isCapReachedAtCurrent = currIncome >= GameFormulas.SPAM_CAP_PER_HOUR,
            isCapReachable = minCapLevel != null,
            levelMultiplier = multiplier
        )
    }
}

data class SpamPayoutInfo(
    val currentSpamLevel: Int,
    val currentSpamIncome: Int,
    /** Minimum Spam level that hits the 100/hr cap; the model's top level when the cap is unreachable. */
    val recommendedSpamLevel: Int,
    val maxAchievableIncome: Int,
    val isCapReachedAtCurrent: Boolean,
    /** False when no Spam level (up to max(30, installed)) reaches 100/hr on this target. */
    val isCapReachable: Boolean = true,
    /** Level-gap income multiplier (×1.0 at equal level); use with [GoodSpamUtils.spamIncome]. */
    val levelMultiplier: Double = 1.0
)
