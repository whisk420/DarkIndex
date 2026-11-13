package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HackExParserSpecialCharsTest {
    @Test
    fun testSpecialChars() {
        val text = """
                                                                                                    CONNECTED
                                                                                                    
                                                                                                    DISCONNECT
                                                                                                    > ÂLPHA _
                                                                                                    
                                                                                                    
                                                                                                    LVL 30
                                                                                                    //
                                                                                                    REP 10,565
                                                                                                    31,262 
                                                                                                    SCORE
                                                                                                    
                                                                                                    // XP PROGRESS
                                                                                                    15488 / 24079
                                                                                                    
                                                                                                    
                                                                                                    64%
                                                                                                    
                                                                                                    WALLET
                                                                                                    APPS
                                                                                                    PROCESSES
                                                                                                    LOG
                                                                                                    CREWS
                                                                                                    
                                                                                                    // SYSTEM INFO
                                                                                                    IP
                                                                                                    46.218.66.245
                                                                                                    DEVICE
                                                                                                    Bolt II
                                                                                                    NETWORK
                                                                                                    5G+
                                                                                                    FIREWALL
                                                                                                    Lv.20
                                                                                                    ENCRYPTOR
                                                                                                    Lv.20
"""
        val result = HomeScreenParser.parse(text)
        assertNotNull("HomeScreen should not be null", result)
        assertEquals("ÂLPHA", result?.username)
        assertEquals(30, result?.level)
    }
}

