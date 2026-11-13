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
        val standardIp = CommonParserUtil.extractIp("Found target at 192.168.1.100 today")
        assertEquals("192.168.1.100", standardIp)

        val maskedIp = CommonParserUtil.extractIp("Device accessed from 10.20.xxx.xxx")
        assertEquals("10.20.xxx.xxx", maskedIp)
        
        val unknownIp = CommonParserUtil.extractIp("Device accessed from [UNKNOWN]")
        assertEquals("[UNKNOWN]", unknownIp)
    }

    @Test
    fun testParseSoloLeaderCrewScreenRawLogcat() {
        val rawSoloCrewText = """
            < back
            DISCONNECT
            // kurimi Members
            97.68.44.77
            LEADER
        """.trimIndent()

        val lines = rawSoloCrewText.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawSoloCrewText, lines)
        assertEquals(ScreenType.CREW, detectedType)

        val result = CrewScreenParser.parse(lines)
        assertNotNull(result)
        assertEquals(1, result?.members?.size)
        assertEquals("97.68.44.77", result?.members?.get(0))
    }

    @Test
    fun testShortenWallet() {
        val wallet = "hx1234567890abcdef"
        val shortened = CommonParserUtil.shortenWallet(wallet)
        assertEquals("hx1234...cdef", shortened)
        
        val alreadyShort = "hx1234"
        assertEquals("hx1234", CommonParserUtil.shortenWallet(alreadyShort))
    }

    @Test
    fun testParseRichVictimLogScreenRawLogcat() {
        val rawVictimLogText = """
            < back
            // VICTIM LOG
            DISCONNECT
            SAVE
            ~/victim_log.txt
            [11-1 11:01] Stole 86 Crypto from hxed11...16ef
            [11-1 11:00] Accessed device at 88.205.81.140
            [11-1 10:58] Stole 10 Crypto from hx47fc...9b0b
            [11-1 10:57] Accessed device at 205.101.110.145
            [11-1 10:43] Stole 9,906 Crypto from hx47fc...9b0b
            [11-1 10:43] Cracked password on 205.101.110.145
            [11-1 10:28] Accessed device at 202.94.241.81
        """.trimIndent()

        val lines = rawVictimLogText.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawVictimLogText, lines)
        assertEquals(ScreenType.LOGS, detectedType)

        val updates = LogScreenParser.parse(lines)
        assertNotNull(updates)

        // Find the 9,906 Crypto theft update (10:43)
        val bigTheft = updates.find { it.stolenAmount == 9906 }
        assertNotNull(bigTheft)
        assertEquals("hx47fc...9b0b", bigTheft?.wallet)
        // Verify 3-minute cap prevented attributing it to 202.94.241.81 (which happened 15 mins earlier at 10:28)
        assertEquals("", bigTheft?.ip)

        // Find the 10 Crypto theft update (10:58)
        val smallTheft = updates.find { it.stolenAmount == 10 }
        assertNotNull(smallTheft)
        assertEquals("hx47fc...9b0b", smallTheft?.wallet)
        assertEquals("205.101.110.145", smallTheft?.ip) // Attributed to 10:57 access on 205.101.110.145 (1 min diff)
    }

    @Test
    fun testParseLogsTimeCapAndBlankLine() {
        val rawLogWithBlankLine = """
            // LOG
            [11-2 3:21] Accessed device at 4.226.131.111
            
            [11-2 3:22] 100 Crypto transferred to hx1234...5678
        """.trimIndent()

        val updatesBlank = LogScreenParser.parse(rawLogWithBlankLine.lines())
        assertEquals(2, updatesBlank.size)
        assertEquals("", updatesBlank[0].ip) // Cleared by blank line barrier

        val rawLogExceeds3Min = """
            // LOG
            [11-2 3:20] Accessed device at 4.226.131.111
            [11-2 3:25] 100 Crypto transferred to hx1234...5678
        """.trimIndent()

        val updatesTimeCap = LogScreenParser.parse(rawLogExceeds3Min.lines())
        assertEquals(2, updatesTimeCap.size)
        assertEquals("", updatesTimeCap[0].ip) // Cleared by 3-minute cap (5 min diff)
    }

    @Test
    fun testParseProcessScreenMassiveRawLogcat() {
        val rawProcessText = """
            < back
            DISCONNECT
            // PROCESSES
            109 active tasks
            Antivirus Scan 
            Lv.7
            DONE
            0.0.0.0
            Password Crack 
            Lv.8
            DONE
            181.169.106.12
            Password Crack 
            Lv.7
            DONE
            237.126.200.165
            Firewall Bypass 
            Lv.5
            INVALIDATED
            233.150.205.118
            Download 
            Lv.6
            DONE
            DL:
            Rootkit
            96.88.199.69
        """.trimIndent()

        val lines = rawProcessText.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawProcessText, lines)
        assertEquals(ScreenType.PROCESS, detectedType)

        val result = ProcessScreenParser.parse(lines)
        assertNotNull(result)
        assertEquals(3, result?.ips?.size)
        assertEquals("181.169.106.12", result?.ips?.get(0))
        assertEquals("237.126.200.165", result?.ips?.get(1))
        assertEquals("96.88.199.69", result?.ips?.get(2))

        assertEquals(1, result?.invalidatedIps?.size)
        assertEquals("233.150.205.118", result?.invalidatedIps?.get(0))

        assertEquals(1, result?.downloads?.size)
        assertEquals("96.88.199.69", result?.downloads?.get(0)?.ip)
        assertEquals("Rootkit", result?.downloads?.get(0)?.name)
        assertEquals(6, result?.downloads?.get(0)?.level)
    }

    @Test
    fun testParseVictimLogScreenRawLogcat() {
        val rawVictimLog = """
            < back
            // VICTIM LOG
            DISCONNECT
            SAVE
            ~/victim_log.txt
            [11-2 3:34] 28 Crypto transferred to hx5a4e...3c37
            [11-2 3:34] Device accessed from 168.222.130.xxx
            [11-1 22:59] 10 Crypto transferred to hx06b4...f328
            [11-1 22:59] Device accessed from 197.xxx.xxx.xxx
            [11-1 22:19] Device accessed from 86.123.81.1
            [11-1 21:03] 34 Crypto transferred to hxc7cb...dc3b
            [11-1 21:02] Device accessed from 86.123.81.1
        """.trimIndent()

        val lines = rawVictimLog.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawVictimLog, lines)
        assertEquals(ScreenType.LOGS, detectedType)

        val updates = LogScreenParser.parse(lines)
        assertNotNull(updates)
        assertEquals(7, updates.size)
        assertEquals("86.123.81.1", updates[0].ip)
        assertEquals("86.123.81.1", updates[1].ip)
        assertEquals("hxc7cb...dc3b", updates[1].wallet)
        assertEquals(34, updates[1].stolenAmount)
        assertEquals("197.xxx.xxx.xxx", updates[4].ip)
        assertEquals("hx06b4...f328", updates[4].wallet)
        assertEquals(10, updates[4].stolenAmount)
        assertEquals("168.222.130.xxx", updates[6].ip)
        assertEquals("hx5a4e...3c37", updates[6].wallet)
        assertEquals(28, updates[6].stolenAmount)
    }

    @Test
    fun testParseLogScreenRawLogcat() {
        val rawLogText = """
            // LOG
            SAVE
            ~/log.txt
            [11-2 3:21] Accessed device at 4.226.131.111
            [11-2 3:21] Uploaded Lv6 Spam to 131.58.156.236
            [11-2 3:08] Uploaded Lv2 Siphon to 129.101.254.235
            [11-2 3:08] Downloaded Lv7 Spam from 163.254.190.176
            [11-2 0:31] Stole 415 Crypto from hxf9f1...d59b
        """.trimIndent()

        val lines = rawLogText.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawLogText, lines)
        assertEquals(ScreenType.LOGS, detectedType)

        val updates = LogScreenParser.parse(lines)
        assertNotNull(updates)
        assertEquals(5, updates.size)
        assertEquals("hxf9f1...d59b", updates[0].wallet)
        assertEquals(415, updates[0].stolenAmount)
        assertEquals("163.254.190.176", updates[1].ip)
        assertEquals("129.101.254.235", updates[2].ip)
        assertEquals("131.58.156.236", updates[3].ip)
        assertEquals("4.226.131.111", updates[4].ip)
    }

    @Test
    fun testParseWalletScreenRawLogcat() {
        val rawWalletText = """
            < back
            DISCONNECT
            // CRYPTO WALLET
            jaywat2550's wallet
            WALLET ADDRESS
            hx59a3c5b2c8ce22e619c9cb837be70a
            HHOT WALLET92 CryptoCCOLD STORAGESecured777 Crypto/ 29,124 max
            // External Transfer
            Amount (Crypto)
            0
            MAX
            TRANSFER TO MY WALLET
        """.trimIndent()

        val lines = rawWalletText.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawWalletText, lines)
        assertEquals(ScreenType.WALLET, detectedType)

        val result = WalletScreenParser.parse(rawWalletText)
        assertNotNull(result)
        assertEquals("jaywat2550", result?.username)
        assertEquals("hx59a3...e70a", result?.wallet)
    }

    @Test
    fun testParseSoftwareScreenRawLogcat() {
        val rawSoftwareText = """
            < back
            DISCONNECT
            // APPS
            jaywat2550's installed software
            + UPLOAD VIRUS 2 / 2 slots ▼
            Antivirus
            Scans and removes viruses. Restores 90% of lost reputation.
            LVL 4
            +2
            Spam
            Generates passive Crypto income from deployed devices.
            LVL 5
            -1
            Rootkit
            Weakens victim defenses for you and your crew.
            LVL 3
            +2
            Firewall
            Blocks unauthorized access. Higher levels increase bypass difficulty and duration for attackers.
            LVL 5
            +1
            Bypasser
            Bypasses target firewalls. Higher levels improve success rate and reduce hack time.
            LVL 5
            -1
            Password Cracker
            Cracks encrypted passwords to access victim devices.
            LVL 4
            -2
            Password Encryptor
            Encrypts your password. Higher levels make cracking harder and slower.
            LVL 4
            +3
            Proxy
            Routes traffic through proxy hops to mask IP when activated. Higher levels mask more octets and resist Trace.
            LVL 3
            -1
            Trace
            Traces connections back through proxy hops to reveal masked IPs. Higher levels have a greater chance of unmasking.
            LVL 4
            +3
            Keygen
            Hardware-bound key generator. Cannot be downloaded.
            LVL 5
            DEVICE-BOUND
            Siphon
            Skims a percentage of Crypto flowing through the victim's account.
            LVL 3
            +1
        """.trimIndent()

        val lines = rawSoftwareText.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawSoftwareText, lines)
        assertEquals(ScreenType.SOFTWARE, detectedType)

        val result = SoftwareScreenParser.parse(rawSoftwareText)
        assertNotNull(result)
        assertEquals("jaywat2550", result?.username)
        assertEquals(4, result?.softwareItems?.get("Antivirus")?.level)
        assertEquals(5, result?.softwareItems?.get("Spam")?.level)
        assertEquals(3, result?.softwareItems?.get("Rootkit")?.level)
        assertEquals(5, result?.softwareItems?.get("Firewall")?.level)
        assertEquals(5, result?.softwareItems?.get("Bypasser")?.level)
        assertEquals(4, result?.softwareItems?.get("Password Cracker")?.level)
        assertEquals(4, result?.softwareItems?.get("Password Encryptor")?.level)
        assertEquals(3, result?.softwareItems?.get("Proxy")?.level)
        assertEquals(4, result?.softwareItems?.get("Trace")?.level)
        assertEquals(3, result?.softwareItems?.get("Siphon")?.level)
    }

    @Test
    fun testParseCrewScreenWithCustomClanName() {
        val rawCrewText = """
            < back
            DISCONNECT
            // The Backdoors [BDR] Members
            75.231.228.6
            LEADER
            195.50.245.47
            MEMBER
            203.120.84.171
            MEMBER
            228.18.xxx.xxx
            MEMBER
        """.trimIndent()

        val lines = rawCrewText.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawCrewText, lines)
        assertEquals(ScreenType.CREW, detectedType)

        val result = CrewScreenParser.parse(lines)
        assertNotNull(result)
        assertEquals(4, result?.members?.size)
        assertEquals("75.231.228.6", result?.members?.get(0))
        assertEquals("195.50.245.47", result?.members?.get(1))
        assertEquals("203.120.84.171", result?.members?.get(2))
        assertEquals("228.18.xxx.xxx", result?.members?.get(3))
    }

    @Test
    fun testParseCrewScreenRawLogcat() {
        val rawCrewText = """
            < back
            DISCONNECT
            // Crypt0 Cr3w Members
            230.131.154.161
            LEADER
            129.222.194.210
            OFFICER
            xxx.xxx.xxx.xxx
            OFFICER
            218.200.75.39
            OFFICER
        """.trimIndent()

        val lines = rawCrewText.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawCrewText, lines)
        assertEquals(ScreenType.CREW, detectedType)

        val result = CrewScreenParser.parse(lines)
        assertNotNull(result)
        assertEquals(4, result?.members?.size)
        assertEquals("230.131.154.161", result?.members?.get(0))
        assertEquals("129.222.194.210", result?.members?.get(1))
        assertEquals("xxx.xxx.xxx.xxx", result?.members?.get(2))
        assertEquals("218.200.75.39", result?.members?.get(3))
    }

    @Test
    fun testParseHomeScreenNewlines() {
        val rawLogcatText = """
            CONNECTED
            DISCONNECT
            > jaywat2550 _ [POKE]
            LVL 10
            //
            REP 0
            0 
            SCORE
            // XP PROGRESS
            1894 / 5919
            32%
            WALLET
            APPS
            PROCESSES
            LOG
            CREWS
            // SYSTEM INFO
            IP
            4.226.131.111
            DEVICE
            Raider
            NETWORK
            DSL
            FIREWALL
            Lv.5
            ENCRYPTOR
            Lv.4
        """.trimIndent()

        val lines = rawLogcatText.lines()
        val detectedType = ScreenClassifier.detectScreenType(rawLogcatText, lines)
        assertEquals(ScreenType.HOME, detectedType)

        val result = HomeScreenParser.parse(rawLogcatText)
        assertNotNull(result)
        assertEquals("4.226.131.111", result?.ip)
        assertEquals("jaywat2550", result?.username)
        assertEquals(10, result?.level)
        assertEquals("Raider • DSL", result?.hardware)
        assertEquals(5, result?.firewall)
        assertEquals(4, result?.encryptor)
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

        val result = HomeScreenParser.parse(simulatedScreenText)
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

        val result = CrewScreenParser.parse(simulatedScreenText.lines())
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
        
        val result = ProcessScreenParser.parse(simulatedScreenText.lines())
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

        val result = WalletScreenParser.parse(text)
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

        val result = SoftwareScreenParser.parse(text)
        assertNotNull(result)
        assertEquals("Pizaaparty", result?.username)
        assertEquals(10, result?.softwareItems?.get("Spam")?.level)
        assertEquals(5, result?.softwareItems?.get("Firewall")?.level)
    }
}

