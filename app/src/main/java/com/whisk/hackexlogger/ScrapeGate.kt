package com.whisk.hackexlogger

/**
 * Decides whether a window may be read at all. DarkIndex reads **only HackEx 2**: when the 📸 button is tapped
 * over any other app (a browser, a chat, a banking app…), the scrape stops before a single text node is read,
 * nothing is parsed, logged, stored or recorded as a Raw Dump, and the toast says so.
 *
 * The same package is declared in `res/xml/accessibility_service_config.xml` (`android:packageNames`);
 * `PrivacyGuardTest` keeps the two in sync.
 */
object ScrapeGate {
    /** HackEx 2's application id (docs/game.md, seen in every Raw Dump). */
    const val GAME_PACKAGE = "net.cncapps.hackex2"

    /** True only for the HackEx 2 window. A null or unknown package is refused. */
    fun isGameWindow(packageName: CharSequence?): Boolean = packageName?.toString() == GAME_PACKAGE

    /** The toast shown instead of scraping another app. */
    const val NOT_GAME_MESSAGE = "Not HackEx 2 — nothing was read"
}
