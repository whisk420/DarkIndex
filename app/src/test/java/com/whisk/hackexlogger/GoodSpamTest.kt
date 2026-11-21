package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodSpamTest {

    @Test
    fun testStandardHardwarePenaltyMapping() {
        assertEquals(0, GoodSpamUtils.getHardwarePenalty(null))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Raider • Null Route"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("raider ii • DSL"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("raider iii • Fiber"))
        assertEquals(1, GoodSpamUtils.getHardwarePenalty("Bolt • Null Route"))
        assertEquals(2, GoodSpamUtils.getHardwarePenalty("Bolt II • 5G"))
        assertEquals(3, GoodSpamUtils.getHardwarePenalty("Bolt III • Fiber"))
        assertEquals(4, GoodSpamUtils.getHardwarePenalty("Nova • DSL"))
        assertEquals(5, GoodSpamUtils.getHardwarePenalty("Nova II • Fiber"))
        assertEquals(6, GoodSpamUtils.getHardwarePenalty("Nova III • Gigabit"))
        assertEquals(7, GoodSpamUtils.getHardwarePenalty("Nova S • Fiber"))
        assertEquals(8, GoodSpamUtils.getHardwarePenalty("Nova X • 10G"))
        assertEquals(9, GoodSpamUtils.getHardwarePenalty("Nova Ultra • Null Route IX"))
    }

    @Test
    fun testPrestigeHardwarePenaltyMapping() {
        // Prestige devices: the numeral is the level, same tier as the standard device at that level.
        // (Old version used invented network names like "Dark Routing"; only the device half matters here.)
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Vector I • Contra Band I"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Scythe II • Harvest Node IV"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Cipher III • Void Net III"))
        assertEquals(1, GoodSpamUtils.getHardwarePenalty("Apex IV • Null Route IV"))
        assertEquals(2, GoodSpamUtils.getHardwarePenalty("Ghost V • Ghost Link V"))
        assertEquals(3, GoodSpamUtils.getHardwarePenalty("Vector VI • Harvest Node VI"))
        assertEquals(4, GoodSpamUtils.getHardwarePenalty("Scythe VII • Contra Band VII"))
        assertEquals(5, GoodSpamUtils.getHardwarePenalty("Cipher VIII • Void Net VIII"))
        assertEquals(6, GoodSpamUtils.getHardwarePenalty("Apex IX • Null Route IX"))
        assertEquals(7, GoodSpamUtils.getHardwarePenalty("Ghost X • Ghost Link X"))
        assertEquals(8, GoodSpamUtils.getHardwarePenalty("Vector XI • Null Route XI"))
        assertEquals(9, GoodSpamUtils.getHardwarePenalty("Apex XII • Null Route XI"))

        // A bare line name (old data) counts as level 1
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Ghost • Ghost Link"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Vector • Contra Band"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Apex"))
    }

    @Test
    fun testUnknownHardwareFallback() {
        // Unknown strings fallback to tier 3 (Bolt III / 6039s baseline)
        assertEquals(3, GoodSpamUtils.getHardwarePenalty("Quantum Supercomputer • Fiber"))
        assertEquals(3, GoodSpamUtils.getHardwarePenalty("Unknown Device String"))
    }

    @Test
    fun testCalculateScore() {
        // Formula: (Level * 10.0) - (EstimatedUploadMinutes * 0.5) - (Firewall * 1.0)
        // Target A: Level 35, Raider (upload = 45285s / 1.0 = 754.75 mins), Firewall 20 -> (350.0) - (377.375) - 20 = -47.375
        val score1 = GoodSpamUtils.calculateScore(level = 35, firewall = 20, hardware = "Raider • Fiber", playerNetwork = "DSL")
        assertEquals(-47.375, score1, 0.01)

        // Null handling defaults: null hardware falls back to Tier 0 Raider (45285s = 754.75 mins -> 377.375 penalty)
        val scoreNull = GoodSpamUtils.calculateScore(level = null, firewall = null, hardware = null)
        assertEquals(-377.375, scoreNull, 0.1)
    }

    @Test
    fun testCalculateRecommendedSpamLevel() {
        // Guide model. Target 45 vs player 35 (+10 → ×1.41): Lv18 already reaches 100/hr
        val (lvl1, payout1) = GoodSpamUtils.calculateRecommendedSpamLevel(targetLevel = 45, playerLevel = 35)
        assertEquals(18, lvl1)
        assertEquals(100, payout1)

        // Same level: Lv20 earns 83/hr (guide), so the cap needs Lv24
        val (lvl2, payout2) = GoodSpamUtils.calculateRecommendedSpamLevel(targetLevel = 35, playerLevel = 35)
        assertEquals(24, lvl2)
        assertEquals(83, payout2)

        // 12 levels below (×0.40): out of reach; Lv20 earns 33/hr
        val (lvl3, payout3) = GoodSpamUtils.calculateRecommendedSpamLevel(targetLevel = 23, playerLevel = 35)
        assertEquals(20, lvl3)
        assertEquals(33, payout3)
    }

    @Test
    fun testCalculateSpamPayoutInfo() {
        // Same level, installed Lv16 → 66/hr; the cap needs Lv24
        val info1 = GoodSpamUtils.calculateSpamPayoutInfo(targetLevel = 35, playerLevel = 35, currentSpamLevel = 16)
        assertEquals(16, info1.currentSpamLevel)
        assertEquals(66, info1.currentSpamIncome)
        assertEquals(24, info1.recommendedSpamLevel)
        assertEquals(83, info1.maxAchievableIncome)
        assertTrue(info1.isCapReachable)
        assertFalse(info1.isCapReachedAtCurrent)

        // Target 5 above (×1.205): Lv20 rounds to 100 → capped, and Lv20 is the cheapest level that caps
        val info2 = GoodSpamUtils.calculateSpamPayoutInfo(targetLevel = 40, playerLevel = 35, currentSpamLevel = 20)
        assertEquals(100, info2.currentSpamIncome)
        assertEquals(20, info2.recommendedSpamLevel)
        assertTrue(info2.isCapReachedAtCurrent)

        // Target 32 below → ×0.10 floor → 8/hr
        val info3 = GoodSpamUtils.calculateSpamPayoutInfo(targetLevel = 3, playerLevel = 35, currentSpamLevel = 20)
        assertEquals(8, info3.currentSpamIncome)
        assertEquals(20, info3.recommendedSpamLevel)
        assertEquals(8, info3.maxAchievableIncome)
        assertFalse(info3.isCapReachable)
        assertFalse(info3.isCapReachedAtCurrent)
    }

    @Test
    fun testFirewallEligibility() {
        // Threshold cutoff tests
        assertTrue(GoodSpamUtils.isEligible(firewall = 15, maxFirewallLevel = 20))
        assertTrue(GoodSpamUtils.isEligible(firewall = 20, maxFirewallLevel = 20))
        assertFalse(GoodSpamUtils.isEligible(firewall = 21, maxFirewallLevel = 20))

        // Null firewall treated as 0
        assertTrue(GoodSpamUtils.isEligible(firewall = null, maxFirewallLevel = 20))

        // maxFirewallLevel == 31 means Uncapped (allows any firewall)
        assertTrue(GoodSpamUtils.isEligible(firewall = 100, maxFirewallLevel = 31))
        assertTrue(GoodSpamUtils.isEligible(firewall = 999, maxFirewallLevel = 31))
    }

    @Test
    fun testGoodSpamTargetSorting() {
        // Assume player has Null Route X (18.0x) for a realistic test
        // Raider = 45285s / 18 = 2515s = 41.9 mins -> penalty = 20.95
        // Nova Ultra = 181245s / 18 = 10069s = 167.8 mins -> penalty = 83.9
        
        val target1 = TargetRecord(ip = "1.1.1.1", level = 35, firewall = 20, hardware = "Raider • Fiber")     // score = 350 - 20.95 - 20 = 309.05
        val target2 = TargetRecord(ip = "2.2.2.2", level = 40, firewall = 5, hardware = "Nova Ultra • Fiber")  // score = 400 - 83.9 - 5 = 311.1
        val target3 = TargetRecord(ip = "3.3.3.3", level = 25, firewall = 5, hardware = "Raider • Fiber")      // score = 250 - 20.95 - 5 = 224.05

        val targets = listOf(target1, target2, target3)
        val sorted = targets.sortedByDescending { GoodSpamUtils.calculateScore(it.level, it.firewall, it.hardware, "Null Route X") }

        // Expected Order: Target 2 (311.1), Target 1 (309.05), Target 3 (224.05)
        assertEquals(listOf("2.2.2.2", "1.1.1.1", "3.3.3.3"), sorted.map { it.ip })
    }
}
