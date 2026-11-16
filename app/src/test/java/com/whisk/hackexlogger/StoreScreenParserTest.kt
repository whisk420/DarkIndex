package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreScreenParserTest {
    @Test
    fun parseStoreScreen() {
        val rawLog = """
            // STORE
            Notepad
            Wallet:
            18,843
            Credits: 1,287
            UPGRADES
            CREDITS
            MARKET
            // NEXT DEVICE
            Apex VII
            LVL 7
            6.0 GHz 22 Core
            Apex VII. The Collective offensive standard.... read more
            56,000 Crypto
            UPGRADE
            // NEXT NETWORK
            Null Route X
            LVL 10
            DL: 220 gbps
            UL: 45 gbps
            Higher bandwidth speeds up all download and upload processes.
            120,000 Crypto
            UPGRADE
            // BOTNET
            Botnet - Nexus
            64 slots
            MAX TIER
            // SOFTWARE
            Firewall
            LVL 21
            Defends your device from bypass attempts. Higher levels increase hack time and reduce attacker success rate. Hardening:... read more
            35,596 Crypto
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
            Keygen
            LVL 21
            Proprietary key generator built for your device.
        """.trimIndent()

        val lines = rawLog.split("\n")
        val storeScreen = StoreScreenParser.parse(lines)

        assertEquals("Apex VII", storeScreen?.nextDevice)
        assertEquals("Null Route X", storeScreen?.nextNetwork)
        assertEquals("Botnet - Nexus (64 slots) [MAX]", storeScreen?.botnetStatus)
        assertEquals(21, storeScreen?.nextSoftwareLevels?.get("Firewall"))
        assertEquals(21, storeScreen?.nextSoftwareLevels?.get("Bypasser"))
        assertEquals(19, storeScreen?.nextSoftwareLevels?.get("Password Encryptor"))
    }
}