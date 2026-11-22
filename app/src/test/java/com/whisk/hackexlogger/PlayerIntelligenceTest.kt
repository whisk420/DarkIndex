package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerIntelligenceTest {

    @Before
    fun setUp() {
        TestEnv.freshRepository()
    }

    @Test
    fun testApexDeviceBonus() {
        // Mock store screen with Apex VII device
        val storeScreen = StoreScreen(
            nextDevice = "Apex VII",
            nextNetwork = "Null Route X",
            nextSoftwareLevels = mapOf("Bypasser" to 21, "Password Cracker" to 21) // Installed = 20
        )
        PlayerProfileRepository.updateProfile(storeScreen)

        assertTrue(PlayerProfileRepository.isApexDeviceOwned())
        // Base = 20, Apex bonus = +3 -> Effective = 23
        assertEquals(23, PlayerProfileRepository.getEffectiveBypasserLevel())
        assertEquals(23, PlayerProfileRepository.getEffectiveCrackerLevel())
    }

    @Test
    fun testBypassScreenParser() {
        val sampleLogcat = """
            // SCAN RESULTS
            1 targets
            231.130.229.178 FW Lv.23 ENC Lv.24 >
            // BYPASS FIREWALL
            231.130.229.178
            Firewall Lv.23
            Encryptor Lv.24
            Using Bypasser Lv.20
            (+3)
            Initiate firewall bypass on this target?
            CANCEL
            BYPASS
            Use Backdoor x38
        """.trimIndent()

        val parsed = BypassScreenParser.parse(sampleLogcat)
        assertNotNull(parsed)
        assertEquals("231.130.229.178", parsed?.targetIp)
        assertEquals(23, parsed?.firewall)
        assertEquals(24, parsed?.encryptor)
        assertEquals(20, parsed?.playerBypasser)
        assertEquals(3, parsed?.apexBonus)
    }
}
