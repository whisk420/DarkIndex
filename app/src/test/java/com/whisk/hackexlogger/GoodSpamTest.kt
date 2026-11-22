package com.whisk.hackexlogger

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

}
