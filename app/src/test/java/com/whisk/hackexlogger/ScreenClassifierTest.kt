package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenClassifierTest {

    @Test
    fun testSoftwareScreenDetection() {
        val text = "User's installed software\nSpam Lv.10\nSiphon Lv.5"
        val lines = text.split("\n")
        assertEquals(ScreenType.SOFTWARE, ScreenClassifier.detectScreenType(text, lines))
    }

    @Test
    fun testWalletScreenDetection() {
        val text1 = "User's wallet\nWALLET ADDRESS 12345678901234567890"
        assertEquals(ScreenType.WALLET, ScreenClassifier.detectScreenType(text1, text1.split("\n")))

        val text2 = "User’s wallet\nWALLET ADDRESS 12345678901234567890"
        assertEquals(ScreenType.WALLET, ScreenClassifier.detectScreenType(text2, text2.split("\n")))
    }

    @Test
    fun testSiphonScreenDetection() {
        val text = "DEPLOYED 5\n192.168.1.1 LV.10 5% 1000 siphoned 1h ago"
        val lines = text.split("\n")
        assertEquals(ScreenType.SIPHON, ScreenClassifier.detectScreenType(text, lines))
    }

    @Test
    fun testLogsScreenDetection() {
        val text1 = "Device accessed from 192.168.1.50"
        assertEquals(ScreenType.LOGS, ScreenClassifier.detectScreenType(text1, text1.split("\n")))

        val text2 = "Accessed device at 10.0.0.1"
        assertEquals(ScreenType.LOGS, ScreenClassifier.detectScreenType(text2, text2.split("\n")))

        val text3 = "Cracked password on 172.16.0.2"
        assertEquals(ScreenType.LOGS, ScreenClassifier.detectScreenType(text3, text3.split("\n")))

        val text4 = "Bypassed firewall on 192.168.1.1"
        assertEquals(ScreenType.LOGS, ScreenClassifier.detectScreenType(text4, text4.split("\n")))
    }

    @Test
    fun testProcessScreenDetection() {
        // Real format (2026-11-20 dump)
        val text = "< back\nDISCONNECT\n// PROCESSES\n3 active tasks\nFirewall Bypass \nLv.25\nDONE\n64.59.40.157"
        assertEquals(ScreenType.PROCESS, ScreenClassifier.detectScreenType(text, text.split("\n")))
        // The nav bar's "PROCESSES" button plus the word "task" is not a process screen
        val notProcess = "Daily missions\nComplete a task\nHOME\nSCAN\nPROCESSES\nAPPS\nSTORE\nCREWS 1"
        assertEquals(ScreenType.UNKNOWN, ScreenClassifier.detectScreenType(notProcess, notProcess.split("\n")))
    }

    @Test
    fun testLogScreenDetectionFromHeadersAndLines() {
        for (text in listOf(
            "// LOG\nSAVE\n~/log.txt\n[11-20 12:38] Downloaded Lv25 Firewall from 113.149.48.111",
            "// VICTIM LOG\n~/victim_log.txt",
            "[11-20 4:18] Failed to crack password on 44.204.208.233",
            "[11-20 12:37] Purchased Keygen Lv26"
        )) {
            assertEquals(text, ScreenType.LOGS, ScreenClassifier.detectScreenType(text, text.split("\n")))
        }
    }

    @Test
    fun testHomeScreenDetectionByKeywords() {
        val text = "FIREWALL Lv.15\nENCRYPTOR Lv.10\nIP 192.168.1.100"
        val lines = text.split("\n")
        assertEquals(ScreenType.HOME, ScreenClassifier.detectScreenType(text, lines))
    }

    @Test
    fun testHomeScreenDetectionByRegexFallback() {
        val text = "> PlayerOne\nIP 192.168.1.50\nLVL 25\nFIREWALL Lv.10"
        val lines = text.split("\n")
        assertEquals(ScreenType.HOME, ScreenClassifier.detectScreenType(text, lines))
    }

    @Test
    fun testCrewScreenDetection() {
        val text1 = "LEADER\nPlayerOne"
        assertEquals(ScreenType.CREW, ScreenClassifier.detectScreenType(text1, text1.split("\n")))

        val text2 = "// ShadowClan Members\nPlayerOne"
        assertEquals(ScreenType.CREW, ScreenClassifier.detectScreenType(text2, text2.split("\n")))
    }

    @Test
    fun testStoreScreenDetection() {
        val text = "Keygen\nLVL 21\nProprietary key generator built for your device. Cracks license protection on downloaded software, allowing you to... read more"
        val lines = text.split("\n")
        assertEquals(ScreenType.STORE, ScreenClassifier.detectScreenType(text, lines))
    }

    @Test
    fun testUnknownScreenDetection() {
        val text = "Random text that matches no signatures at all"
        val lines = text.split("\n")
        assertEquals(ScreenType.UNKNOWN, ScreenClassifier.detectScreenType(text, lines))
    }

    @Test
    fun testScreenPrecedenceOrder() {
        // SOFTWARE takes precedence over WALLET if text contains signatures of both
        val textSoftwareWallet = "User's installed software\nwallet address 12345678901234567890"
        assertEquals(ScreenType.SOFTWARE, ScreenClassifier.detectScreenType(textSoftwareWallet, textSoftwareWallet.split("\n")))

        // WALLET takes precedence over SIPHON
        val textWalletSiphon = "User's wallet\nsiphoned deployed"
        assertEquals(ScreenType.WALLET, ScreenClassifier.detectScreenType(textWalletSiphon, textWalletSiphon.split("\n")))

        // SIPHON takes precedence over LOGS
        val textSiphonLogs = "siphoned deployed\ndevice accessed"
        assertEquals(ScreenType.SIPHON, ScreenClassifier.detectScreenType(textSiphonLogs, textSiphonLogs.split("\n")))
    }
}
