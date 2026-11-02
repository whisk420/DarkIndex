package com.whisk.hackexlogger

/**
 * Utility object containing regular expressions and string parser functions
 * for extracting game entities (IPs, usernames, clans, firewall levels, software levels,
 * and crypto wallets) from text scraped from the HackEx game interface and logs.
 */
object HackExParser {

    // Core IP and Masked IP Patterns
    private val REGEX_IP = Regex("""\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b""")
    private val REGEX_MASKED_IP = Regex("""\b(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\b""", RegexOption.IGNORE_CASE)
    private val REGEX_UNKNOWN_IP = Regex("""\b\[?UNKNOWN\]?\b""", RegexOption.IGNORE_CASE)

    // Log Parsing Patterns
    private val REGEX_LOG_PREFIX = Regex("""^\[.+?\]""")
    private val REGEX_TIME_HEADER = Regex("""^\[([^\]]+)\]""")
    private val REGEX_TIME_MINUTES = Regex("""(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{1,2})""")
    private val REGEX_LOG_ACCESS = Regex("""(?:Device accessed from|Accessed device at)\s+((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx)|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_SOFTWARE = Regex("""(Downloaded|Uploaded|Downloading|Uploading)\s+Lv(\d+)\s+(.+?)(?:\s+(?:from|to)\b|\.|\.\.|\s*$)""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_WALLET = Regex("""(?:Stole\s+([\d,]+)\s+Crypto\s+from|([\d,]+)\s+Crypto\s+transferred\s+to)\s+([a-zA-Z0-9.]{6,})""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_CRACK = Regex("""(Cracking|Cracked)\s+password\s+(?:on\s+)?((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx)|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_FIREWALL = Regex("""(Bypassed|Failed to bypass)\s+firewall\s+(?:on\s+)?((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx)|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)

    // Screen Entity Extraction Patterns
    private val REGEX_PROFILE_IP = Regex("""IP\s+([0-9]{1,3}(?:\.[0-9]{1,3}|\.xxx){3}|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)
    private val REGEX_USER_HEADER = Regex("""^[ \t]*>\s*(.+?)$""", RegexOption.MULTILINE)
    private val REGEX_CLAN_TAG = Regex("""\[([^\]]{2,10})\]""")
    private val REGEX_LEVEL = Regex("""LVL\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val REGEX_DEVICE = Regex("""DEVICE\s*\r?\n?\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)
    private val REGEX_NETWORK = Regex("""NETWORK\s*\r?\n?\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)
    private val REGEX_FIREWALL = Regex("""FIREWALL\s*\r?\n?\s*Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val REGEX_ENCRYPTOR = Regex("""ENCRYPTOR\s*\r?\n?\s*Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val REGEX_SOFTWARE_HEADER = Regex("""^([^\r\n]+?)['’]s installed software\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val REGEX_WALLET_HEADER = Regex("""^([^\r\n]+?)['’]s wallet\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val REGEX_WALLET_ADDRESS = Regex("""WALLET ADDRESS\s+([a-zA-Z0-9]{20,})""", RegexOption.IGNORE_CASE)
    private val REGEX_SIPHON_DEPLOYED = Regex("""DEPLOYED\s+(\d+)""")
    private val REGEX_SIPHON_ITEM = Regex("""((?:\d{1,3}\.){3}\d{1,3})\s+LV\.(\d+)\s+([\d.]+[%])\s+([\d,]+)\s+siphoned\s+([a-zA-Z0-9]+ ago)""", RegexOption.IGNORE_CASE)

    /**
     * Parsed software item detail.
     */
    data class Software(
        val action: String,
        val level: Int,
        val name: String
    )

    /**
     * Data update parsed from a HackEx log line.
     */
    data class ParsedUpdate(
        val ip: String,
        val time: String?,
        val wallet: String? = null,
        val software: Software? = null,
        val isOwnedSoftware: Boolean = false,
        val raw: String,
        val stolenAmount: Int? = null
    )

    /**
     * Parsed details from a HackEx target profile home screen.
     */
    data class HomeScreen(
        val ip: String,
        val username: String?,
        val clan: String?,
        val level: Int?,
        val hardware: String?,
        val firewall: Int?,
        val encryptor: Int?
    )

    /**
     * Software item parsed from a target software screen.
     */
    data class SoftwareScreenItem(
        val level: Int,
        val status: String
    )

    /**
     * Parsed software screen inventory for a target user.
     */
    data class SoftwareScreen(
        val username: String?,
        val softwareItems: Map<String, SoftwareScreenItem>
    )

    /**
     * Wallet details parsed from a target wallet screen.
     */
    data class WalletScreen(
        val username: String,
        val wallet: String
    )

    /**
     * Siphon details for an individual target.
     */
    data class SiphonItem(
        val ip: String,
        val level: Int,
        val percent: String,
        val amount: Int,
        val timeAgo: String
    )

    /**
     * Parsed Siphon Earnings screen data.
     */
    data class SiphonScreen(
        val deployedCount: Int,
        val items: List<SiphonItem>
    )

    /**
     * Parsed Crew Screen containing members.
     */
    data class CrewScreen(
        val members: List<String>
    )

    /**
     * Parsed software item extracted from the Process Screen.
     */
    data class ProcessDownloadedSoftware(
        val ip: String,
        val name: String,
        val level: Int
    )

    /**
     * Parsed Process Screen containing IPs.
     */
    data class ProcessScreen(
        val ips: List<String>,
        val invalidatedIps: List<String>,
        val downloads: List<ProcessDownloadedSoftware>
    )

    /**
     * Enum representing detected HackEx game screen types.
     */
    enum class ScreenType {
        HOME,
        SOFTWARE,
        WALLET,
        LOGS,
        SIPHON,
        CREW,
        PROCESS,
        UNKNOWN
    }

    /**
     * Fast-Path Screen Classification: Detects active HackEx screen type in ~0.2ms using keyword signatures.
     */
    fun detectScreenType(text: String, lines: List<String>): ScreenType {
        val lowerText = text.lowercase()

        // 1. Installed Software Screen Signature
        if (lowerText.contains("installed software")) {
            return ScreenType.SOFTWARE
        }

        // 2. Wallet Screen Signature
        if (lowerText.contains("wallet address") || lowerText.contains("'s wallet") || lowerText.contains("’s wallet")) {
            return ScreenType.WALLET
        }

        // 3. Siphon Screen Signature
        if (lowerText.contains("siphoned") && lowerText.contains("deployed")) {
            return ScreenType.SIPHON
        }

        // 4. Game Logs Screen Signature
        if (lowerText.contains("device accessed") || lowerText.contains("accessed device") || lowerText.contains("cracked password") || lowerText.contains("bypassed firewall")) {
            return ScreenType.LOGS
        }

        // 5. Process Screen Signature
        if (lowerText.contains("process") && lowerText.contains("task")) {
            return ScreenType.PROCESS
        }

        // 6. Home / Target Profile Screen Signature
        if (lowerText.contains("firewall") && (lowerText.contains("encryptor") || lowerText.contains("ip") || lowerText.contains("device"))) {
            return ScreenType.HOME
        }

        // 7. Crew Screen Signature (Every Crew screen has a 'LEADER' role line or 'Members' header regardless of custom clan name)
        if (lines.any { it.trim().equals("LEADER", ignoreCase = true) || it.contains("Members", ignoreCase = true) }) {
            return ScreenType.CREW
        }

        // Fallback check for target profile
        if (REGEX_PROFILE_IP.containsMatchIn(text) && REGEX_FIREWALL.containsMatchIn(text)) {
            return ScreenType.HOME
        }

        return ScreenType.UNKNOWN
    }

    /**
     * Truncates long wallet address strings for UI display (e.g., "hx1234...5678").
     */
    fun shortenWallet(wallet: String): String {
        if (wallet.length > 10 && !wallet.contains("...")) {
            return wallet.take(6) + "..." + wallet.takeLast(4)
        }
        return wallet
    }

    /**
     * Searches a string for an IP address or masked IP address matching HackEx formatting patterns.
     *
     * @param text Raw text containing potential IP addresses.
     * @return Extracted IP address string, or null if no match found.
     */
    fun extractIp(text: String): String? {
        val ipMatch = REGEX_IP.find(text)
        if (ipMatch != null) return ipMatch.value

        val maskedIpMatch = REGEX_MASKED_IP.find(text)
        if (maskedIpMatch != null) return maskedIpMatch.value

        val unknownMatch = REGEX_UNKNOWN_IP.find(text)
        if (unknownMatch != null) return "[UNKNOWN]"

        return null
    }

    /**
     * Converts a log time string like "11-2 3:21" to total minutes (day * 1440 + hour * 60 + minute)
     * for simple, lightning-fast minute-difference calculations.
     */
    fun parseLogMinutes(timeStr: String?): Int? {
        if (timeStr == null) return null
        val match = REGEX_TIME_MINUTES.find(timeStr) ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val hour = match.groupValues[3].toIntOrNull() ?: return null
        val minute = match.groupValues[4].toIntOrNull() ?: return null
        return day * 1440 + hour * 60 + minute
    }

    /**
     * Parses log lines from a log screen
     *
     * @param rawLines List of text lines extracted from the log UI.
     * @return List of [ParsedUpdate] entries.
     */
    fun parseLogs(rawLines: List<String>): List<ParsedUpdate> {
        val updates = mutableListOf<ParsedUpdate>()
        var lastAccessIp: String? = null
        var lastAccessMinutes: Int? = null

        // Process lines in reverse order (bottom to top), preserving blank line boundaries
        for (i in rawLines.indices.reversed()) {
            val line = rawLines[i].trim()

            // 1. Blank Line Barrier: Blank lines clear previous access state
            if (line.isEmpty()) {
                lastAccessIp = null
                lastAccessMinutes = null
                continue
            }

            if (!REGEX_LOG_PREFIX.containsMatchIn(line)) {
                continue
            }

            val timeStr = REGEX_TIME_HEADER.find(line)?.groupValues?.get(1)
            val currentMinutes = parseLogMinutes(timeStr)

            if (line.contains("[UNKNOWN]")) {
                lastAccessIp = null
                lastAccessMinutes = null
                continue
            }

            // Catch "Device accessed from IP" or "Accessed device at IP"
            val accessMatch = REGEX_LOG_ACCESS.find(line)
            if (accessMatch != null) {
                val ip = extractIp(accessMatch.groupValues[1])
                if (ip != null && ip != "[UNKNOWN]") {
                    lastAccessIp = ip
                    lastAccessMinutes = currentMinutes
                    updates.add(ParsedUpdate(ip = ip, time = timeStr, raw = line))
                    continue
                }
            }

            var action: String? = null
            var level: Int? = null
            var softwareName: String? = null
            var targetIp: String? = extractIp(line)
            var isOwned = false

            // Software Actions
            val softMatch = REGEX_LOG_SOFTWARE.find(line)
            if (softMatch != null) {
                action = softMatch.groupValues[1].lowercase()
                level = softMatch.groupValues[2].toIntOrNull()
                softwareName = softMatch.groupValues[3].trim()
                isOwned = action.startsWith("download")
            }

            // 2. 3-Minute Time Cap Barrier: Check minute difference between last access and current line
            if (targetIp == null && lastAccessIp != null) {
                if (currentMinutes != null && lastAccessMinutes != null) {
                    val diff = Math.abs(currentMinutes - lastAccessMinutes)
                    if (diff <= 3) { // 3 minutes max
                        targetIp = lastAccessIp
                    } else {
                        // Exceeded 3 minute window - clear access state
                        lastAccessIp = null
                        lastAccessMinutes = null
                    }
                } else {
                    targetIp = lastAccessIp
                }
            }

            // Wallets
            val walletMatch = REGEX_LOG_WALLET.find(line)
            if (walletMatch != null) {
                val rawAmt = if (walletMatch.groupValues[1].isNotEmpty()) walletMatch.groupValues[1] else walletMatch.groupValues[2]
                val amount = rawAmt.replace(",", "").toIntOrNull()
                val wallet = shortenWallet(walletMatch.groupValues[3])
                updates.add(
                    ParsedUpdate(
                        ip = targetIp ?: "",
                        time = timeStr,
                        wallet = wallet,
                        stolenAmount = amount,
                        raw = line
                    )
                )
                // Clear the access IP so subsequent Siphon collections don't falsely claim this IP
                lastAccessIp = null
                lastAccessMinutes = null
                continue
            }

            // Cracking / Firewall
            val crackMatch = REGEX_LOG_CRACK.find(line)
            if (crackMatch != null) {
                val ip = extractIp(crackMatch.groupValues[2])
                updates.add(ParsedUpdate(ip = ip ?: (targetIp ?: ""), time = timeStr, raw = line))
                continue
            }

            val firewallMatch = REGEX_LOG_FIREWALL.find(line)
            if (firewallMatch != null) {
                val ip = extractIp(firewallMatch.groupValues[2])
                updates.add(ParsedUpdate(ip = ip ?: (targetIp ?: ""), time = timeStr, raw = line))
                continue
            }

            // Add software update if found
            if (softMatch != null && targetIp != null && !line.contains("[UNKNOWN]")) {
                updates.add(
                    ParsedUpdate(
                        ip = targetIp,
                        time = timeStr,
                        software = if (action != null && level != null && softwareName != null) Software(action, level, softwareName) else null,
                        isOwnedSoftware = isOwned,
                        raw = line
                    )
                )
            }
        }
        return updates
    }

    /**
     * Parses target stats (IP, username, clan, level, hardware, firewall, encryptor) from raw text
     * scraped from a HackEx profile/home screen.
     */
    fun parseHomeScreen(text: String): HomeScreen? {
        val ipMatch = REGEX_PROFILE_IP.find(text) ?: return null
        val ip = ipMatch.groupValues[1].trim()

        val userLineMatch = REGEX_USER_HEADER.find(text)
        var username = userLineMatch?.groupValues?.get(1)?.trim()

        var clan: String? = null
        if (username != null) {
            val clanMatch = REGEX_CLAN_TAG.find(username)
            if (clanMatch != null) {
                clan = clanMatch.groupValues[1].trim()
                username = username.replace(clanMatch.value, "").trim()
            }
            username = username.removeSuffix("_").trim()
        }

        if (clan == null) {
            val genericClanMatch = REGEX_CLAN_TAG.find(text)
            clan = genericClanMatch?.groupValues?.get(1)?.trim()
        }

        val level = REGEX_LEVEL.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val device = REGEX_DEVICE.find(text)?.groupValues?.get(1)?.trim()
        val network = REGEX_NETWORK.find(text)?.groupValues?.get(1)?.trim()
        val firewall = REGEX_FIREWALL.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val encryptor = REGEX_ENCRYPTOR.find(text)?.groupValues?.get(1)?.toIntOrNull()

        val hardware = if (device != null && network != null) "$device • $network" else device ?: network

        return HomeScreen(ip, username, clan, level, hardware, firewall, encryptor)
    }

    /**
     * Parses installed software levels from text scraped from a HackEx software screen.
     */
    fun parseSoftwareScreen(text: String): SoftwareScreen? {
        val userMatch = REGEX_SOFTWARE_HEADER.find(text) ?: return null
        val username = userMatch.groupValues[1].trim()

        val softwareItems = mutableMapOf<String, SoftwareScreenItem>()
        val validNames = listOf(
            "Antivirus", "Spam", "Rootkit", "Firewall", "Bypasser",
            "Password Cracker", "Password Encryptor", "Proxy", "Trace",
            "Siphon"
        )

        validNames.forEach { name ->
            val patternStr = "^" + Regex.escape(name) + "$[\\s\\S]*?LVL\\s*(\\d+)"
            val match = Regex(patternStr, setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).find(text)
            if (match != null) {
                val level = match.groupValues[1].toIntOrNull() ?: 0
                softwareItems[name] = SoftwareScreenItem(level, "installed")
            }
        }

        if (softwareItems.isEmpty()) return null
        return SoftwareScreen(username, softwareItems)
    }

    /**
     * Parses username and wallet address from text scraped from a HackEx wallet screen.
     */
    fun parseWalletScreen(text: String): WalletScreen? {
        val userMatch = REGEX_WALLET_HEADER.find(text) ?: return null
        val username = userMatch.groupValues[1].trim()

        val walletMatch = REGEX_WALLET_ADDRESS.find(text)
        val rawWallet = walletMatch?.groupValues?.get(1)?.trim()

        if (rawWallet != null) {
            return WalletScreen(username, shortenWallet(rawWallet))
        }
        return null
    }

    /**
     * Parses the Siphon Earnings screen to extract currently deployed siphons.
     */
    fun parseSiphonScreen(text: String): SiphonScreen? {
        if (!text.contains("SIPHON EARNINGS")) return null

        val deployedCount = REGEX_SIPHON_DEPLOYED.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val items = mutableListOf<SiphonItem>()
        val matches = REGEX_SIPHON_ITEM.findAll(text)
        for (match in matches) {
            val ip = match.groupValues[1]
            val level = match.groupValues[2].toIntOrNull() ?: 0
            val percent = match.groupValues[3]
            val amount = match.groupValues[4].replace(",", "").toIntOrNull() ?: 0
            val timeAgo = match.groupValues[5]
            items.add(SiphonItem(ip, level, percent, amount, timeAgo))
        }

        return SiphonScreen(deployedCount, items)
    }

    /**
     * Parses a Crew Screen to extract all listed crew member IP addresses.
     */
    fun parseCrewScreen(rawLines: List<String>): CrewScreen? {
        val isCrewScreen = rawLines.any { line ->
            line.contains("Crew", ignoreCase = true) || line.contains("Cr3w", ignoreCase = true) ||
            line.contains("Members", ignoreCase = true) || line.trim().equals("LEADER", ignoreCase = true) ||
            line.trim().equals("OFFICER", ignoreCase = true) || line.trim().equals("MEMBER", ignoreCase = true)
        }
        if (!isCrewScreen) return null

        val members = mutableListOf<String>()
        for (line in rawLines) {
            val ip = extractIp(line)
            if (ip != null && !ip.startsWith("0.0.") && !members.contains(ip)) {
                members.add(ip)
            }
        }

        if (members.isEmpty()) return null
        return CrewScreen(members)
    }

    /**
     * Parses active task/download processes from the Process Screen.
     */
    fun parseProcessScreen(rawLines: List<String>): ProcessScreen? {
        val isProcessScreen = rawLines.any { line ->
            line.contains("PROCESS", ignoreCase = true) || line.contains("active tasks", ignoreCase = true)
        }
        if (!isProcessScreen) return null

        val ips = mutableListOf<String>()
        val invalidatedIps = mutableListOf<String>()
        val downloads = mutableListOf<ProcessDownloadedSoftware>()

        var currentSoftwareName: String? = null
        var currentSoftwareLevel: Int? = null
        var isDownloadTask = false
        var isInvalidated = false

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.contains("Download", ignoreCase = true) || trimmed.contains("Downloading", ignoreCase = true)) {
                isDownloadTask = true
            }

            if (trimmed.contains("INVALIDATED", ignoreCase = true)) {
                isInvalidated = true
            }

            val levelMatch = Regex("""Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)
            if (levelMatch != null) {
                currentSoftwareLevel = levelMatch.groupValues[1].toIntOrNull()
            }

            val validSoftwareNames = listOf(
                "Antivirus", "Spam", "Rootkit", "Firewall", "Bypasser",
                "Password Cracker", "Password Encryptor", "Proxy", "Trace", "Siphon"
            )
            for (sw in validSoftwareNames) {
                if (trimmed.equals(sw, ignoreCase = true)) {
                    currentSoftwareName = sw
                    break
                }
            }

            val ip = extractIp(trimmed)
            if (ip != null && !ip.startsWith("0.0.")) {
                if (isInvalidated) {
                    invalidatedIps.add(ip)
                    isInvalidated = false
                } else {
                    ips.add(ip)
                    if (isDownloadTask && currentSoftwareName != null && currentSoftwareLevel != null) {
                        downloads.add(ProcessDownloadedSoftware(ip, currentSoftwareName, currentSoftwareLevel))
                        currentSoftwareName = null
                        currentSoftwareLevel = null
                        isDownloadTask = false
                    }
                }
            }
        }

        return ProcessScreen(ips.distinct(), invalidatedIps.distinct(), downloads)
    }
}
