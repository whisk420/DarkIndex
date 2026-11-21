package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerProfileRepositoryTest {

    @Test
    fun testDecrementHardwareNamePrestigeDevices() {
        assertEquals("Apex VI", PlayerProfileRepository.decrementHardwareName("Apex VII"))
        assertEquals("Apex XI", PlayerProfileRepository.decrementHardwareName("Apex XII"))
        assertEquals("Ghost III", PlayerProfileRepository.decrementHardwareName("Ghost IV"))
        assertEquals("Vector I", PlayerProfileRepository.decrementHardwareName("Vector II"))
        // A reboot starts at <Line> I, so nothing sits below it (old code wrongly returned "Nova Ultra")
        assertEquals("Scythe I", PlayerProfileRepository.decrementHardwareName("Scythe I"))
    }

    @Test
    fun testDecrementHardwareNameNetworks() {
        assertEquals("Null Route IX", PlayerProfileRepository.decrementHardwareName("Null Route X"))
        assertEquals("Null Route I", PlayerProfileRepository.decrementHardwareName("Null Route II"))
        assertEquals("Null Route I", PlayerProfileRepository.decrementHardwareName("Null Route I"))
        assertEquals("Harvest Node IX", PlayerProfileRepository.decrementHardwareName("Harvest Node X"))
        // Standard networks (old code returned them unchanged)
        assertEquals("Fiber+", PlayerProfileRepository.decrementHardwareName("10G Fiber"))
        assertEquals("10G Fiber+", PlayerProfileRepository.decrementHardwareName("Quantum Link"))
        assertEquals("DSL", PlayerProfileRepository.decrementHardwareName("Cable"))
    }

    @Test
    fun testDecrementHardwareNameStandardDevices() {
        assertEquals("Bolt", PlayerProfileRepository.decrementHardwareName("Bolt II"))
        assertEquals("Raider III", PlayerProfileRepository.decrementHardwareName("Bolt"))
        assertEquals("Nova III", PlayerProfileRepository.decrementHardwareName("Nova S"))
        assertEquals("Nova S", PlayerProfileRepository.decrementHardwareName("Nova X"))
        assertEquals("Raider", PlayerProfileRepository.decrementHardwareName("Raider"))
    }

    @Test
    fun testNullAndUnknown() {
        assertNull(PlayerProfileRepository.decrementHardwareName(null))
        assertEquals("Unknown Hardware", PlayerProfileRepository.decrementHardwareName("Unknown Hardware"))
    }

    @Test
    fun testCachedProfileStillSeesUpdates() {
        PlayerProfileRepository.prefs = FakeSharedPreferences()
        PlayerProfileRepository.updateOwnProfile("Seamus", 38)
        val first = PlayerProfileRepository.getProfile()
        assertEquals(38, first.playerLevel)
        // Unchanged JSON: the same parsed object comes back (no re-parse per card)
        assertSame(first, PlayerProfileRepository.getProfile())

        PlayerProfileRepository.updateOwnProfile("Seamus", 39)
        assertEquals(39, PlayerProfileRepository.getProfile().playerLevel)

        // A fresh, empty store must not return the old cached profile
        PlayerProfileRepository.prefs = FakeSharedPreferences()
        assertNull(PlayerProfileRepository.getProfile().playerLevel)
    }
}
