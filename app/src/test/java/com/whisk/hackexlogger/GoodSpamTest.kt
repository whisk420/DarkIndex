package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodSpamTest {

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
    fun spamPriorityPutsCappingTargetsFirstThenWeakestDeviceThenLowestFirewall() {
        val net = "Null Route XI"
        fun t(ip: String, level: Int?, fw: Int, hw: String?) = TargetRecord(ip = ip, level = level, firewall = fw, hardware = hw)
        val ranked = GoodSpamUtils.rankForSpam(
            listOf(
                t("lowVictimRaider", 5, 1, "Raider • DSL"),            // can't cap: ranks after every capping target
                t("capUltra", 45, 1, "Nova Ultra • Fiber"),
                t("capRaiderHighFw", 30, 25, "Raider • Fiber"),
                t("capRaiderLowFw", 40, 3, "Raider III • Fiber"),      // Raider I–III upload alike → FW decides
                t("capBolt", 50, 1, "Bolt • 5G"),
                t("capUnknownDevice", 40, 1, null),
                t("unknownLevel", null, 0, "Raider • DSL"),
            ),
            net, installedSpamLevel = 20
        ).map { it.ip }
        assertEquals(
            listOf("capRaiderLowFw", "capRaiderHighFw", "capBolt", "capUltra", "capUnknownDevice", "lowVictimRaider", "unknownLevel"),
            ranked
        )
    }
}
