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

    /** What Spam priority (Good Spam) ranks a target on: see [rankForSpam]. */
    data class SpamPriority(
        val record: TargetRecord,
        /** Fresh-upload Crypto/hr of the best Spam you can upload here; null when the target's level is unknown. */
        val income: Int?,
        /** Upload time of that Spam level on your current network; null when the target's device is unknown. */
        val uploadSeconds: Int?,
    )

    fun spamPriority(record: TargetRecord, playerNetwork: String?, installedSpamLevel: Int): SpamPriority {
        val level = record.level
        val uploadLevel = if (level == null) 20
            else spamUploadLevel(calculateSpamPayoutInfo(level, installedSpamLevel), installedSpamLevel)
        val deviceKnown = record.hardware != null && HardwareCatalog.uploadDefenceTier(record.hardware!!) != null
        return SpamPriority(
            record = record,
            income = level?.let { spamIncome(uploadLevel, it) },
            uploadSeconds = if (deviceKnown) NetworkSpeedUtils.getUploadSecondsForLevel(record.hardware, playerNetwork, uploadLevel) else null,
        )
    }

    /**
     * Spam priority order (owner, 2026-11-22). Your own level plays no part in Spam income, and past the 100/hr cap
     * a higher victim level pays nothing extra, so:
     * 1. targets where your best Spam hits 100/hr first, then the rest by income (unknown level last);
     * 2. then the fastest upload: weaker device, and the lower Spam level a target needs (unknown device last);
     * 3. then the lowest Firewall.
     */
    fun rankForSpam(records: List<TargetRecord>, playerNetwork: String?, installedSpamLevel: Int): List<TargetRecord> =
        records.map { spamPriority(it, playerNetwork, installedSpamLevel) }
            .sortedWith(
                compareByDescending<SpamPriority> { it.income ?: -1 }
                    .thenBy { it.uploadSeconds ?: Int.MAX_VALUE }
                    .thenBy { it.record.firewall ?: 0 }
            )
            .map { it.record }

    /**
     * The Spam level worth uploading to a target: the minimum level that hits the 100/hr cap, never more than you
     * have installed. When that can't cap, the level that earns the most up to that limit: a Spam above the
     * victim's level loses 5% per level, so a higher upload can earn less.
     */
    fun spamUploadLevel(info: SpamPayoutInfo, installedSpamLevel: Int): Int {
        val top = if (installedSpamLevel <= 0) info.recommendedSpamLevel
            else minOf(info.recommendedSpamLevel, installedSpamLevel)
        val victim = info.targetLevel ?: return top
        return GameFormulas.spamBestLevel(victim, top)
    }

    /**
     * Checks if a target's firewall level is within the maxFirewallLevel threshold.
     * maxFirewallLevel == 31 means "Uncapped" (allows any firewall level).
     */
    fun isEligible(firewall: Int?, maxFirewallLevel: Int): Boolean {
        val fw = firewall ?: 0
        val maxFw = if (maxFirewallLevel == 31) Int.MAX_VALUE else maxFirewallLevel
        return fw <= maxFw
    }

    /** Highest Spam level the cap search looks at when your installed level is lower (the game goes past Lv.20). */
    const val SPAM_SEARCH_MAX_LEVEL = 30

    /** Hourly payout (fresh upload, no decay) of a Spam of [spamLevel] on a victim of [targetLevel]. */
    fun spamIncome(spamLevel: Int, targetLevel: Int): Int = GameFormulas.spamIncomePerHour(spamLevel, targetLevel)

    /**
     * Calculates Spam payout metrics for a target (fresh deployment, no decay):
     * - currentSpamIncome: what your installed Spam level would earn
     * - recommendedSpamLevel: the **minimum** Spam level that hits the 100/hr cap (searched up to
     *   max(30, installed)); when the cap is out of reach, the level that earns the most on this victim
     * - maxAchievableIncome: the payout at recommendedSpamLevel
     *
     * Model: [GameFormulas.spamIncomePerHour]. Only the victim's level and the Spam level matter, not yours.
     */
    fun calculateSpamPayoutInfo(targetLevel: Int?, currentSpamLevel: Int): SpamPayoutInfo {
        val currSpam = currentSpamLevel.coerceAtLeast(0)
        val tLvl = targetLevel ?: return SpamPayoutInfo(currSpam, if (currSpam == 0) 0 else 100, 20, 100, currSpam >= 20, true, null)

        val currIncome = spamIncome(currSpam, tLvl)
        val searchTop = maxOf(SPAM_SEARCH_MAX_LEVEL, currSpam)
        val minCapLevel = (1..searchTop).firstOrNull { spamIncome(it, tLvl) >= GameFormulas.SPAM_CAP_PER_HOUR }
        val recommended = minCapLevel ?: GameFormulas.spamBestLevel(tLvl, searchTop)

        return SpamPayoutInfo(
            currentSpamLevel = currSpam,
            currentSpamIncome = currIncome,
            recommendedSpamLevel = recommended,
            maxAchievableIncome = spamIncome(recommended, tLvl),
            isCapReachedAtCurrent = currIncome >= GameFormulas.SPAM_CAP_PER_HOUR,
            isCapReachable = minCapLevel != null,
            targetLevel = tLvl
        )
    }
}

data class SpamPayoutInfo(
    val currentSpamLevel: Int,
    val currentSpamIncome: Int,
    /** Minimum Spam level that hits the 100/hr cap; the best-earning level when the cap is unreachable. */
    val recommendedSpamLevel: Int,
    val maxAchievableIncome: Int,
    val isCapReachedAtCurrent: Boolean,
    /** False when no Spam level (up to max(30, installed)) reaches 100/hr on this target. */
    val isCapReachable: Boolean = true,
    /** The victim level the numbers were worked out for (null = unknown); use with [GoodSpamUtils.spamIncome]. */
    val targetLevel: Int? = null
)
