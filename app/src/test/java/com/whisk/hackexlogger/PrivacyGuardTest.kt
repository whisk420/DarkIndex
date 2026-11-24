package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the privacy promises in SECURITY.md, so a later change can't quietly break them:
 * HackEx 2 only, no networking, no Android cloud backup, no screen text in Logcat.
 * Reads the real manifest, resource XML and sources (unit tests run from the `app` module folder).
 */
class PrivacyGuardTest {

    private fun file(path: String) = File(path).also { assertTrue("missing $path", it.isFile) }
    private val manifest by lazy { file("src/main/AndroidManifest.xml").readText() }
    private val mainSources by lazy {
        File("src/main/java").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    @Test
    fun onlyTheHackEx2WindowMayBeRead() {
        assertTrue(ScrapeGate.isGameWindow("net.cncapps.hackex2"))
        for (other in listOf(null, "", "com.whisk.hackexlogger", "com.android.chrome", "com.google.android.gm",
                "net.cncapps.hackex2.evil", "xnet.cncapps.hackex2", "NET.CNCAPPS.HACKEX2")) {
            assertFalse("must refuse $other", ScrapeGate.isGameWindow(other))
        }
    }

    @Test
    fun theServiceChecksTheGateBeforeReadingAnyText() {
        val src = file("src/main/java/com/whisk/hackexlogger/ScraperAccessibilityService.kt").readText()
        val gate = src.indexOf("ScrapeGate.isGameWindow(")
        assertTrue("ScraperAccessibilityService must call ScrapeGate", gate >= 0)
        for (read in listOf("extractText(rootNode", "RawDumpCapture.nodeTree(", "RawDump.create(")) {
            val at = src.indexOf(read)
            assertTrue("$read not found", at >= 0)
            assertTrue("$read must come after the HackEx 2 check", at > gate)
        }
    }

    @Test
    fun accessibilityConfigIsLimitedToTheGame() {
        val config = file("src/main/res/xml/accessibility_service_config.xml").readText()
        assertTrue(config.contains("""android:packageNames="${ScrapeGate.GAME_PACKAGE}""""))
        val flags = Regex("""android:accessibilityFlags="([^"]*)"""").find(config)!!.groupValues[1]
        assertFalse("must not ask to see every window", flags.contains("flagRetrieveInteractiveWindows"))
        assertFalse("must not perform gestures", config.contains("canPerformGestures=\"true\""))
        assertFalse("must not filter key events", flags.contains("flagRequestFilterKeyEvents"))
    }

    @Test
    fun noInternetPermissionAndNoNetworkingCode() {
        for (perm in listOf("android.permission.INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE")) {
            assertFalse("manifest must not request $perm", manifest.contains(perm))
        }
        val banned = listOf("java.net.", "javax.net.", "okhttp", "retrofit", "HttpURLConnection", "Socket(", "android.webkit", "firebase", "com.google.android.gms")
        for (f in mainSources) {
            val text = f.readText()
            for (b in banned) assertFalse("${f.name} uses $b", text.contains(b, ignoreCase = true))
        }
    }

    @Test
    fun androidCloudBackupIsOff() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        val rules = file("src/main/res/xml/data_extraction_rules.xml").readText()
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
            assertTrue("cloud-backup must exclude $domain", cloud.contains("""<exclude domain="$domain" path="." />"""))
        }
        assertFalse(cloud.contains("<include"))
        val legacy = file("src/main/res/xml/backup_rules.xml").readText()
        assertFalse(legacy.contains("<include"))
    }

    @Test
    fun screenTextIsNeverLogged() {
        // Logcat is readable over adb and by bug-report tools; screen text belongs only in opt-in Raw Dumps.
        val src = file("src/main/java/com/whisk/hackexlogger/ScraperAccessibilityService.kt").readText()
        val logLines = src.lines().filter { it.contains("Log.") }
        assertEquals(emptyList<String>(), logLines.filter { it.contains("combinedText") || it.contains("extractedTextList)") || it.contains("rawText") })
        assertFalse(src.contains("DOSSIER_RAW"))
    }
}
