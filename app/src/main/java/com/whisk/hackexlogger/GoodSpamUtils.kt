package com.whisk.hackexlogger

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
        val uploadLevel = spamUploadLevel(calculateSpamPayoutInfo(level, playerLevel, installedSpamLevel), installedSpamLevel)
        val uploadSecs = NetworkSpeedUtils.getUploadSecondsForLevel(hardware, playerNetwork, uploadLevel)
        val uploadMins = uploadSecs / 60.0
        return (lvl * 10.0) - (uploadMins * 0.5) - fw
    }

    /**
     * The Spam level worth uploading to a target: the minimum level that hits the 100/hr cap, or, if the cap is
     * out of reach (big level gap), the strongest level you can actually upload.
     */
    fun spamUploadLevel(info: SpamPayoutInfo, installedSpamLevel: Int): Int =
        if (info.isCapReachable || installedSpamLevel <= 0) info.recommendedSpamLevel
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
     * Returns Pair(RecommendedSpamLevel, MaxAchievableHourlyCrypto [0..100]).
     */
    fun calculateRecommendedSpamLevel(targetLevel: Int?, playerLevel: Int?): Pair<Int, Int> {
        val info = calculateSpamPayoutInfo(targetLevel, playerLevel, 20)
        return Pair(info.recommendedSpamLevel, info.maxAchievableIncome)
    }

    /** Hourly payout of a Spam of [spamLevel] under the (unvalidated) payout model, clamped to 0..100. */
    fun spamIncome(spamLevel: Int, levelAdjustment: Double): Int =
        if (spamLevel <= 0) 0 else (spamLevel * 5.0 + levelAdjustment).toInt().coerceIn(0, 100)

    /**
     * Calculates Spam payout metrics for a target:
     * - currentSpamIncome: what your installed Spam level would earn
     * - recommendedSpamLevel: the **minimum** Spam level that hits the 100/hr cap (or the model's top level if unreachable)
     * - maxAchievableIncome: the best payout the model allows (at max(20, installed))
     *
     * Model (unvalidated, see TODO Research: Spam payout): 5 Crypto/hr per Spam level; +2.5 per level the target is
     * above you; −3.3 per level beyond a 9-level buffer when the target is below you.
     */
    fun calculateSpamPayoutInfo(targetLevel: Int?, playerLevel: Int?, currentSpamLevel: Int): SpamPayoutInfo {
        val currSpam = currentSpamLevel.coerceAtLeast(0)
        val modelMaxLevel = maxOf(20, currSpam)
        val tLvl = targetLevel ?: return SpamPayoutInfo(currSpam, if (currSpam == 0) 0 else 100, 20, 100, currSpam >= 20, true)
        val pLvl = playerLevel ?: tLvl

        val delta = tLvl - pLvl
        val levelAdjustment = when {
            delta > 0 -> delta * 2.5 // Target higher level: +2.5 bonus per target level
            delta < 0 -> {
                val gap = -delta
                // Lv 20 Spam absorbs the first 9 gap levels before penalty kicks in
                val gapBeyondBuffer = (gap - 9).coerceAtLeast(0)
                -gapBeyondBuffer * 3.3
            }
            else -> 0.0
        }

        val currIncome = spamIncome(currSpam, levelAdjustment)
        val minCapLevel = (1..modelMaxLevel).firstOrNull { spamIncome(it, levelAdjustment) >= 100 }
        val maxIncome = spamIncome(modelMaxLevel, levelAdjustment)

        return SpamPayoutInfo(
            currentSpamLevel = currSpam,
            currentSpamIncome = currIncome,
            recommendedSpamLevel = minCapLevel ?: modelMaxLevel,
            maxAchievableIncome = maxIncome,
            isCapReachedAtCurrent = currIncome >= 100,
            isCapReachable = minCapLevel != null,
            levelAdjustment = levelAdjustment
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
    /** False when no Spam level (up to max(20, installed)) reaches 100/hr on this target. */
    val isCapReachable: Boolean = true,
    /** Model's level-gap adjustment in Crypto/hr (+ above you, − below the 9-level buffer); use with [GoodSpamUtils.spamIncome]. */
    val levelAdjustment: Double = 0.0
)
