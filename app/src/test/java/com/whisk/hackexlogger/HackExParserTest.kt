package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for the HackEx text parsing engine.
 * 
 * Demonstrates how developers can build out the test suite for core logic
 * without impacting the production APK size (as tests are stripped from release).
 */
class HackExParserTest {

    @Test
    fun testIpExtraction() {
        val standardIp = HackExParser.extractIp("Found target at 192.168.1.100 today")
        assertEquals("192.168.1.100", standardIp)

        val maskedIp = HackExParser.extractIp("Device accessed from 10.20.xxx.xxx")
        assertEquals("10.20.xxx.xxx", maskedIp)
        
        val unknownIp = HackExParser.extractIp("Device accessed from [UNKNOWN]")
        assertEquals(null, unknownIp)
    }

    @Test
    fun testShortenWallet() {
        val wallet = "hx1234567890abcdef"
        val shortened = HackExParser.shortenWallet(wallet)
        assertEquals("hx1234...cdef", shortened)
        
        val alreadyShort = "hx1234"
        assertEquals("hx1234", HackExParser.shortenWallet(alreadyShort))
    }

    @Test
    fun testParseHomeScreen() {
        val simulatedScreenText = """
            IP 10.0.0.55
            > Pizaaparty [XYZ]
            LVL 25
            DEVICE Raider II
            NETWORK Cable
            FIREWALL Lv. 10
            ENCRYPTOR Lv. 5
        """.trimIndent()

        val result = HackExParser.parseHomeScreen(simulatedScreenText)
        assertNotNull(result)
        
        assertEquals("10.0.0.55", result?.ip)
        assertEquals("Pizaaparty", result?.username)
        assertEquals("XYZ", result?.clan)
        assertEquals(25, result?.level)
        assertEquals("Raider II • Cable", result?.hardware)
        assertEquals(10, result?.firewall)
        assertEquals(5, result?.encryptor)
    }

    @Test
    fun testParseCrewScreen() {
        val simulatedScreenText = """
< back
DISCONNECT
// The Squirtle Squad Members
4.226.131.111
LEADER
214.19.182.187
OFFICER
92.196.181.208
MEMBER
        """.trimIndent()

        val result = HackExParser.parseCrewScreen(simulatedScreenText)
        assertNotNull(result)
        assertEquals(3, result?.members?.size)
        assertEquals("4.226.131.111", result?.members?.get(0))
        assertEquals("214.19.182.187", result?.members?.get(1))
        assertEquals("92.196.181.208", result?.members?.get(2))
    }

    @Test
    fun testParseProcessScreen() {
        val simulatedScreenText = """
< back
DISCONNECT
// PROCESSES
41 active tasks
Antivirus Scan 
Lv.4
DONE
0.0.0.0
Password Crack 
Lv.3
DONE
252.170.3.29
Download 
Lv.3
DONE
DL:
Software
189.9.36.37
Download 
Lv.4
DONE
DL:
Spam
189.9.36.38
Password Crack 
Lv.3
INVALIDATED
95.127.223.107
Password Crack 
Lv.2
DONE
254.7.137.239
        """.trimIndent()
        
        val result = HackExParser.parseProcessScreen(simulatedScreenText)
        assertNotNull(result)
        assertEquals(4, result?.ips?.size)
        assertEquals("252.170.3.29", result?.ips?.get(0))
        assertEquals("189.9.36.37", result?.ips?.get(1))
        assertEquals("189.9.36.38", result?.ips?.get(2))
        assertEquals("254.7.137.239", result?.ips?.get(3))
        
        assertEquals(1, result?.invalidatedIps?.size)
        assertEquals("95.127.223.107", result?.invalidatedIps?.get(0))
        
        assertEquals(1, result?.downloads?.size)
        assertEquals("189.9.36.38", result?.downloads?.get(0)?.ip)
        assertEquals("Spam", result?.downloads?.get(0)?.name)
        assertEquals(4, result?.downloads?.get(0)?.level)
    }

    @Test
    fun testParseWalletScreen() {
        val text = """
            < back
            DISCONNECT
            Pizaaparty's wallet
            BALANCE $100,000
            WALLET ADDRESS hx1234567890abcdef123456
        """.trimIndent()

        val result = HackExParser.parseWalletScreen(text)
        assertNotNull(result)
        assertEquals("Pizaaparty", result?.username)
        assertEquals("hx1234...3456", result?.wallet)
    }

    @Test
    fun testParseSoftwareScreen() {
        val text = """
            < back
            DISCONNECT
            Pizaaparty's installed software
            Spam
            LVL 10
            Firewall
            LVL 5
        """.trimIndent()

        val result = HackExParser.parseSoftwareScreen(text)
        assertNotNull(result)
        assertEquals("Pizaaparty", result?.username)
        assertEquals(10, result?.softwareItems?.get("Spam")?.level)
        assertEquals(5, result?.softwareItems?.get("Firewall")?.level)
    }
}
