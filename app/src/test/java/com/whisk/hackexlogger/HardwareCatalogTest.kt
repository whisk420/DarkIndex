package com.whisk.hackexlogger

import com.whisk.hackexlogger.HardwareCatalog.Kind
import com.whisk.hackexlogger.HardwareCatalog.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HardwareCatalogTest {

    private val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")

    @Test
    fun parsingIsCaseAndSpaceTolerantAndReturnsGameSpelling() {
        assertEquals("Harvest Node X", HardwareCatalog.parse("  harvest   node x ")!!.name)
        assertEquals("4G LTE", HardwareCatalog.parse("4G")!!.name)
        assertEquals("Ghost Link IV", HardwareCatalog.parse("GHOST LINK iv")!!.name)
        // "Ghost" (device) and "Ghost Link" (network) don't get mixed up
        assertEquals(Kind.DEVICE, HardwareCatalog.parse("Ghost IV")!!.kind)
        assertEquals(Kind.NETWORK, HardwareCatalog.parse("Ghost Link IV")!!.kind)
    }

    @Test
    fun bareLineNameCountsAsLevelOne() {
        val hw = HardwareCatalog.parse("Vector")!!
        assertEquals(1, hw.level)
        assertEquals(Line.VECTOR, hw.line)
        assertEquals("Vector I", hw.name)
    }

    @Test
    fun unknownNamesAreNull() {
        assertNull(HardwareCatalog.parse("Dark Routing VIII")) // perk-like name from GEMINI.md, not hardware
        assertNull(HardwareCatalog.parse("Quantum Supercomputer"))
        assertNull(HardwareCatalog.parse(""))
        assertNull(HardwareCatalog.parse(null))
        assertNull(HardwareCatalog.parse("Apex XIII"))
    }

    @Test
    fun targetHardwareStringsSplitIntoDeviceAndNetwork() {
        // Mixed R6+ loadout: any device line with any network line
        val target = "Scythe IV • Null Route IX"
        assertEquals(Line.SCYTHE, HardwareCatalog.device(target)!!.line)
        assertEquals(4, HardwareCatalog.device(target)!!.level)
        assertEquals(Line.APEX, HardwareCatalog.network(target)!!.line)
        assertEquals(15.0, HardwareCatalog.networkBoost(target)!!, 0.001)
        // Real target string from a Home screen
        assertEquals(3, HardwareCatalog.uploadDefenceTier("Bolt III • 5G+"))
        assertEquals(7.0, HardwareCatalog.networkBoost("Bolt III • 5G+")!!, 0.001)
    }

    @Test
    fun previousIsOneLevelDownAndStopsAtLevelOne() {
        assertEquals("Nova X", HardwareCatalog.previous("Nova Ultra"))
        assertEquals("Raider", HardwareCatalog.previous("Raider II"))
        assertEquals("Raider", HardwareCatalog.previous("Raider"))
        assertEquals("DSL", HardwareCatalog.previous("DSL"))
        assertEquals("Fiber+", HardwareCatalog.previous("10G Fiber"))
        assertEquals("Apex VIII", HardwareCatalog.previous("Apex IX"))
        assertEquals("Null Route X", HardwareCatalog.previous("Null Route XI"))
        assertEquals("Ghost Link I", HardwareCatalog.previous("Ghost Link I"))
        assertEquals("Mystery Box", HardwareCatalog.previous(" Mystery Box "))
        assertNull(HardwareCatalog.previous(null))
    }

    @Test
    fun unknownNamesAreRecordedOnceAndMarked() {
        assertEquals(" ?", HardwareCatalog.noteUnknown("Mystery Rig"))
        assertEquals(" ?", HardwareCatalog.noteUnknown("Mystery Rig"))
        assertTrue("Mystery Rig" in HardwareCatalog.unknownNamesSeen())
        // Fallbacks used by the maths
        assertEquals(3, GoodSpamUtils.getHardwarePenalty("Mystery Rig • Dial-up"))
        assertEquals(1.0, NetworkSpeedUtils.getNetworkSpeedBoost("Dial-up"), 0.001)
    }

    // ---- Player profile integration -------------------------------------------------------------

    @Before
    fun setUp() {
        TestEnv.freshRepository()
    }

    @Test
    fun apexDetectionUsesTheDeviceYouOwn() {
        PlayerProfileRepository.updateProfile(StoreScreen(nextDevice = "Apex IX", nextNetwork = "Null Route XI", nextSoftwareLevels = emptyMap()))
        assertTrue(PlayerProfileRepository.isApexDeviceOwned()) // own Apex VIII

        // Own Cipher with an Apex network (mixed R6+ loadout): no Apex device perk
        PlayerProfileRepository.prefs = FakeSharedPreferences()
        PlayerProfileRepository.updateProfile(StoreScreen(nextDevice = "Cipher V", nextNetwork = "Null Route V", nextSoftwareLevels = emptyMap()))
        assertFalse(PlayerProfileRepository.isApexDeviceOwned())
        assertEquals("Cipher IV", PlayerProfileRepository.getCurrentDevice())
        assertEquals("Null Route IV", PlayerProfileRepository.getCurrentNetwork())
    }

    @Test
    fun standardNetworkProfileGivesTheRightCurrentNetwork() {
        PlayerProfileRepository.updateProfile(StoreScreen(nextDevice = "Nova", nextNetwork = "10G Fiber", nextSoftwareLevels = emptyMap()))
        assertEquals("Bolt III", PlayerProfileRepository.getCurrentDevice())
        assertEquals("Fiber+", PlayerProfileRepository.getCurrentNetwork())
        assertEquals(12.0, NetworkSpeedUtils.getNetworkSpeedBoost(PlayerProfileRepository.getCurrentNetwork()), 0.001)
    }
}
