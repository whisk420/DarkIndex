package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Current hardware (incl. MAX TIER Store screens) and the "cheapest Spam that caps 100/hr" logic used by the
 * target card's upload chip and Good Spam ranking.
 */
class PlayerHardwareAndSpamLevelTest {

    /** Raw text of the real maxed Store screen (raw dump #6, 2026-11-19): network section is "// NETWORK" + MAX TIER. */
    private val maxedStoreRaw = """// STORE
Notepad
Wallet:
63,536
Credits: 1,377
UPGRADES
CREDITS
MARKET
// NEXT DEVICE
Apex IX
LVL 9
6.4 GHz 24 Core
Apex IX. The predator of predators.... read more
120,000 Crypto
UPGRADE
// NETWORK
Null Route XI
LVL 11
DL: 260 gbps
UL: 50 gbps
Higher bandwidth speeds up all download and upload processes.
MAX TIER
// BOTNET
Botnet - Nexus
64 slots
MAX TIER
// SOFTWARE
Firewall
LVL 23
Defends your device from bypass attempts. Higher levels increase hack time and reduce attacker success rate. Hardening:... read more
40,065 Crypto
UPGRADE
Bypasser
LVL 21
Breaks through a target's Firewall. Higher levels increase success rate and reduce hack time.... read more
41,877 Crypto
UPGRADE
Password Encryptor
LVL 19
Defends against password crack attempts. Higher levels increase crack time and reduce attacker success rate. Hardening:... read more
42,973 Crypto
UPGRADE
Password Cracker
LVL 21
Decrypts a target's password after bypassing their Firewall. Cracked passwords must be re-cracked if the victim... read more
57,581 Crypto
UPGRADE
Spam
LVL 21
A virus that earns passive Crypto from a victim's device. Higher spam levels, higher-level victims, and higher-tier... read more
94,224 Crypto
UPGRADE
Siphon
LVL 21
A parasitic virus that silently skims Crypto flowing through the victim's account. Quantity is king — deploy on as many... read more
83,754 Crypto
UPGRADE
Rootkit
LVL 7
A virus that weakens a victim's defenses, reducing their effective Firewall and Password Encryptor levels for you and... read more
17,570 Crypto
UPGRADE
Antivirus
LVL 21
Scans your device to remove viruses. Removal chance depends on your AV level vs. each virus level. Viruses on your... read more
44,495 Crypto
UPGRADE
Proxy
LVL 21
Routes traffic through proxy hops to mask your IP in scan results and victim logs. Must be manually activated from Apps... read more
40,046 Crypto
UPGRADE
Trace
LVL 21
Traces connections back through proxy hops to reveal masked IPs during scans. Higher levels have a greater chance of... read more
40,046 Crypto
UPGRADE
Keygen
LVL 26
Proprietary key generator built for your device. Cracks license protection on downloaded software, allowing you to... read more
41,459 Crypto
UPGRADE
HOME
SCAN
PROCESSES
APPS
STORE
CREWS 3"""

    @Before
    fun setUp() {
        PlayerProfileRepository.prefs = FakeSharedPreferences()
    }

    @Test
    fun storeParserReadsMaxedNetworkSection() {
        val store = StoreScreenParser.parse(maxedStoreRaw.split("\n"))!!
        assertEquals("Apex IX", store.nextDevice)
        assertFalse(store.deviceMaxed)
        assertEquals("Null Route XI", store.nextNetwork)
        assertTrue(store.networkMaxed)
        assertEquals("Botnet - Nexus (64 slots) [MAX]", store.botnetStatus)
        assertEquals(21, store.nextSoftwareLevels["Spam"])
        assertEquals(26, store.nextSoftwareLevels["Keygen"])
    }

    @Test
    fun profileStoresCurrentHardwareWhenMaxed() {
        PlayerProfileRepository.updateProfile(StoreScreenParser.parse(maxedStoreRaw.split("\n"))!!)
        val p = PlayerProfileRepository.getProfile()
        assertEquals("Null Route XI", p.currentNetwork)
        assertNull(p.nextNetwork)
        assertEquals("Apex VIII", p.currentDevice)
        assertEquals("Apex IX", p.nextDevice)
        assertEquals("Null Route XI", PlayerProfileRepository.getCurrentNetwork())
        assertEquals(21.0, NetworkSpeedUtils.getNetworkSpeedBoost(PlayerProfileRepository.getCurrentNetwork()), 0.01)
    }

    @Test
    fun profileStoresOneBelowNextWhenNotMaxed() {
        PlayerProfileRepository.updateProfile(StoreScreen(nextDevice = "Apex VII", nextNetwork = "Null Route X"))
        assertEquals("Null Route IX", PlayerProfileRepository.getCurrentNetwork())
        assertEquals("Apex VI", PlayerProfileRepository.getCurrentDevice())
    }

    @Test
    fun maxedDeviceStillCountsAsApex() {
        PlayerProfileRepository.updateProfile(StoreScreen(nextDevice = "Apex XII", deviceMaxed = true))
        assertEquals("Apex XII", PlayerProfileRepository.getCurrentDevice())
        assertTrue(PlayerProfileRepository.isApexDeviceOwned())
    }

    @Test
    fun legacyProfileFallsBackToDecrementedNext() {
        // Profile saved before currentNetwork existed
        PlayerProfileRepository.prefs.edit().putString("player_profile", """{"nextNetwork":"Null Route X","nextDevice":"Apex VII"}""").apply()
        assertEquals("Null Route IX", PlayerProfileRepository.getCurrentNetwork())
        assertEquals("Apex VI", PlayerProfileRepository.getCurrentDevice())
    }

    @Test
    fun minimumSpamLevelThatCapsIsRecommended() {
        // Target 10 levels above a Lv35 player: ×1.41 → Lv18 already hits 100/hr
        val info = GoodSpamUtils.calculateSpamPayoutInfo(targetLevel = 45, playerLevel = 35, currentSpamLevel = 20)
        assertTrue(info.isCapReachable)
        assertEquals(18, info.recommendedSpamLevel)
        assertEquals(18, GoodSpamUtils.spamUploadLevel(info, installedSpamLevel = 20))
        assertTrue(info.isCapReachedAtCurrent)
    }

    @Test
    fun capNeedingMoreThanInstalledUsesInstalledLevel() {
        // Same level: the cap needs Lv24, but you only have Lv20 → upload Lv20 (83/hr)
        val info = GoodSpamUtils.calculateSpamPayoutInfo(targetLevel = 35, playerLevel = 35, currentSpamLevel = 20)
        assertTrue(info.isCapReachable)
        assertEquals(24, info.recommendedSpamLevel)
        assertEquals(20, GoodSpamUtils.spamUploadLevel(info, installedSpamLevel = 20))
        assertEquals(83, GoodSpamUtils.spamIncome(20, info.levelMultiplier))
        // Unknown installed level → the recommendation itself
        assertEquals(24, GoodSpamUtils.spamUploadLevel(info, installedSpamLevel = 0))
    }

    @Test
    fun unreachableCapUsesStrongestInstalledLevel() {
        // Target 12 below: ×0.40 → 100 out of reach; you can only upload what you have installed
        val info = GoodSpamUtils.calculateSpamPayoutInfo(targetLevel = 23, playerLevel = 35, currentSpamLevel = 15)
        assertFalse(info.isCapReachable)
        assertEquals(15, GoodSpamUtils.spamUploadLevel(info, installedSpamLevel = 15))
        assertEquals(25, GoodSpamUtils.spamIncome(15, info.levelMultiplier))
    }

    @Test
    fun goodSpamScoreUsesCheapestCappingLevelUploadTime() {
        val net = "Null Route XI"
        val lv20Secs = NetworkSpeedUtils.getUploadSecondsForLevel("Nova • Fiber", net, 20)
        val lv18Secs = NetworkSpeedUtils.getUploadSecondsForLevel("Nova • Fiber", net, 18)
        assertTrue(lv18Secs < lv20Secs)

        val withPlayer = GoodSpamUtils.calculateScore(45, 10, "Nova • Fiber", net, playerLevel = 35, installedSpamLevel = 20)
        val expected = 45 * 10.0 - (lv18Secs / 60.0) * 0.5 - 10
        assertEquals(expected, withPlayer, 0.001)

        // Without player info it falls back to the old Lv20 behaviour
        val legacy = GoodSpamUtils.calculateScore(45, 10, "Nova • Fiber", net)
        assertEquals(45 * 10.0 - (lv20Secs / 60.0) * 0.5 - 10, legacy, 0.001)
    }

    @Test
    fun cheapTargetOutranksSameLevelTargetNeedingFullSpam() {
        val net = "Null Route XI"
        // Same hardware & firewall; A is 10 levels above you (caps with Lv18), B is your level (Lv20, 83/hr)
        val a = GoodSpamUtils.calculateScore(45, 10, "Nova III • Fiber", net, playerLevel = 35, installedSpamLevel = 20)
        val b = GoodSpamUtils.calculateScore(35, 10, "Nova III • Fiber", net, playerLevel = 35, installedSpamLevel = 20)
        assertTrue(a > b)
    }
}
