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
        // Prestige devices with Roman numerals map 1-to-1 to standard tiers
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Vector I • Dark Routing"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Scythe II • Fast Deploy"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Cipher III • Adaptive Block"))
        assertEquals(1, GoodSpamUtils.getHardwarePenalty("Apex IV • Deep Link"))
        assertEquals(2, GoodSpamUtils.getHardwarePenalty("Ghost V • Dark Routing"))
        assertEquals(3, GoodSpamUtils.getHardwarePenalty("Vector VI • Backchannel"))
        assertEquals(4, GoodSpamUtils.getHardwarePenalty("Scythe VII • Fast Deploy"))
        assertEquals(5, GoodSpamUtils.getHardwarePenalty("Cipher VIII • Hardened Core"))
        assertEquals(6, GoodSpamUtils.getHardwarePenalty("Apex IX • Overreach"))
        assertEquals(7, GoodSpamUtils.getHardwarePenalty("Ghost X • Dark Routing"))
        assertEquals(8, GoodSpamUtils.getHardwarePenalty("Vector XI • Backchannel"))
        assertEquals(9, GoodSpamUtils.getHardwarePenalty("Nova Ultra XII • Null Route IX"))

        // Base prestige without numerals default to tier 0
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Ghost • Dark Routing"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Vector • Backchannel"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Scythe • Slow Burn"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Cipher • Adaptive Block"))
        assertEquals(0, GoodSpamUtils.getHardwarePenalty("Apex • Overreach"))
    }

    @Test
    fun testUnknownHardwareFallback() {
        // Unknown strings fallback to tier 3 (Bolt III / 6039s baseline)
        assertEquals(3, GoodSpamUtils.getHardwarePenalty("Quantum Supercomputer • Fiber"))
        assertEquals(3, GoodSpamUtils.getHardwarePenalty("Unknown Device String"))
    }

    @Test
    fun testCalculateScore() {
        // Score formula: (Level * 10.0) - (HardwarePenalty * 20.0) - (Firewall * 1.0)
        
        // Target A: Level 35, Raider (hw=0), Firewall 20 -> (35 * 10.0) - 0 - 20 = 350 - 20 = 330.0
        val score1 = GoodSpamUtils.calculateScore(level = 35, firewall = 20, hardware = "Raider • Fiber")
        assertEquals(330.0, score1, 0.001)

        // Target B: Level 40, Nova Ultra (hw=9), Firewall 5 -> 400 - 180 - 5 = 215.0
        val score2 = GoodSpamUtils.calculateScore(level = 40, firewall = 5, hardware = "Nova Ultra • Fiber")
        assertEquals(215.0, score2, 0.001)

        // Null handling defaults: level null (0), firewall null (0), hardware null (0)
        val scoreNull = GoodSpamUtils.calculateScore(level = null, firewall = null, hardware = null)
        assertEquals(0.0, scoreNull, 0.001)
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
        val target1 = TargetRecord(ip = "1.1.1.1", level = 35, firewall = 20, hardware = "Raider • Fiber")     // score = 350 - 0 - 20 = 330.0
        val target2 = TargetRecord(ip = "2.2.2.2", level = 40, firewall = 5, hardware = "Nova Ultra • Fiber")  // score = 400 - 180 - 5 = 215.0
        val target3 = TargetRecord(ip = "3.3.3.3", level = 25, firewall = 5, hardware = "Raider • Fiber")      // score = 250 - 0 - 5 = 245.0

        val targets = listOf(target1, target2, target3)
        val sorted = targets.sortedByDescending { GoodSpamUtils.calculateScore(it.level, it.firewall, it.hardware) }

        // Expected Order: 330.0 (Target 1), 245.0 (Target 3), 215.0 (Target 2)
        assertEquals(listOf("1.1.1.1", "3.3.3.3", "2.2.2.2"), sorted.map { it.ip })
    }
}
