package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OwnHomeScreenParserTest {

    @Test
    fun testOwnHomeScreenParsing() {
        val rawLogcat = """
            > Seamus_
            MY DEVICE
            R3
            LVL 35
            //
            REP 20713
            60,429 
            SCORE
            // CRYPTO
            20,274
            // CREDITS
            1,287
            // DAILY MISSIONS
            Upload Rootkit to 5 Devices
        """.trimIndent()

        val parsed = OwnHomeScreenParser.parse(rawLogcat)
        assertNotNull(parsed)
        assertEquals("Seamus", parsed?.username)
        assertEquals(35, parsed?.level)
    }
}
