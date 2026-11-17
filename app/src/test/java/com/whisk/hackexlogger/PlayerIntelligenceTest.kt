package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerIntelligenceTest {

    @Before
    fun setUp() {
        TargetRepository.prefs = FakeSharedPreferences()
        PlayerProfileRepository.prefs = FakeSharedPreferences()
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
    fun testSuccessProbabilityModel() {
        assertEquals(95, TargetRecord.calculateSuccessProbability(26, 20)) // Delta +6
        assertEquals(90, TargetRecord.calculateSuccessProbability(25, 20)) // Delta +5
        assertEquals(82, TargetRecord.calculateSuccessProbability(24, 20)) // Delta +4
        assertEquals(74, TargetRecord.calculateSuccessProbability(23, 20)) // Delta +3
        assertEquals(66, TargetRecord.calculateSuccessProbability(22, 20)) // Delta +2
        assertEquals(58, TargetRecord.calculateSuccessProbability(21, 20)) // Delta +1
        assertEquals(50, TargetRecord.calculateSuccessProbability(20, 20)) // Delta 0
        assertEquals(42, TargetRecord.calculateSuccessProbability(19, 20)) // Delta -1
        assertEquals(34, TargetRecord.calculateSuccessProbability(18, 20)) // Delta -2
        assertEquals(18, TargetRecord.calculateSuccessProbability(17, 20)) // Delta -3
        assertEquals(5, TargetRecord.calculateSuccessProbability(16, 20))  // Delta -4
        assertEquals(5, TargetRecord.calculateSuccessProbability(10, 20))  // Delta -10
    }

    @Test
    fun testNetworkSpeedBoostLookup() {
        assertEquals(1.0, NetworkSpeedUtils.getNetworkSpeedBoost("DSL"), 0.01)
        assertEquals(5.0, NetworkSpeedUtils.getNetworkSpeedBoost("5G"), 0.01)
        assertEquals(9.5, NetworkSpeedUtils.getNetworkSpeedBoost("Fiber"), 0.01)
        assertEquals(21.0, NetworkSpeedUtils.getNetworkSpeedBoost("Quantum Link"), 0.01)
        assertEquals(21.0, NetworkSpeedUtils.getNetworkSpeedBoost("Null Route XII"), 0.01)
        assertEquals(12.0, NetworkSpeedUtils.getNetworkSpeedBoost("Dark Routing VIII"), 0.01)
    }

    @Test
    fun testEstimatedUploadDurationAndFormatting() {
        // Null Route X (18.0x) vs Raider target (45,285s DSL base) -> 45285 / 18.0 = 2515s
        val secs = NetworkSpeedUtils.getEstimatedUploadSeconds("Raider • DSL", "Null Route X")
        assertEquals(2515, secs)
        assertEquals("41m 55s", NetworkSpeedUtils.formatUploadDuration(secs))

        // 1h 15m formatting test (4500s)
        assertEquals("1h 15m", NetworkSpeedUtils.formatUploadDuration(4500))
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
