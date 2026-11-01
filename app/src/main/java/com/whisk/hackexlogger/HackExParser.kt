package com.whisk.hackexlogger

/**
 * Utility object containing regular expressions and string parser functions
 * for extracting game entities (IPs, usernames, clans, firewall levels, software levels,
 * and crypto wallets) from text scraped from the HackEx game interface and logs.
 */
object HackExParser {
    
    private val REGEX_IP = Regex("""\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b""")
    private val REGEX_MASKED_IP = Regex("""\b(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\b""", RegexOption.IGNORE_CASE)

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
     * Parsed Process Screen containing IPs.
     */
    data class ProcessScreen(
        val ips: List<String>,
        val invalidatedIps: List<String>
    )

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
        if (text.contains("[UNKNOWN]")) return null
        
        val ipMatch = REGEX_IP.find(text)
        if (ipMatch != null) return ipMatch.value
        
        val maskedIpMatch = REGEX_MASKED_IP.find(text)
        if (maskedIpMatch != null) return maskedIpMatch.value
        
        return null
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
        var lastAccessTime: String? = null

        val validLines = rawLines.filter { line ->
            line.trim().isNotEmpty() && Regex("""^\[.+?\]""").containsMatchIn(line.trim())
        }

        for (i in validLines.indices.reversed()) {
            val line = validLines[i].trim()
            if (line.isEmpty()) continue

            val timeMatch = Regex("""^\[([^\]]+)\]""").find(line)
            val time = timeMatch?.groupValues?.get(1)

            if (line.contains("[UNKNOWN]")) {
                lastAccessIp = null
                continue
            }

            // Catch "Device accessed from IP" or "Accessed device at IP"
            val accessMatch = Regex("""(?:Device accessed from|Accessed device at)\s+((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx))""", RegexOption.IGNORE_CASE).find(line)
            if (accessMatch != null) {
                val ip = extractIp(accessMatch.groupValues[1])
                if (ip != null && ip != "[UNKNOWN]") {
                    lastAccessIp = ip
                    lastAccessTime = time
                    updates.add(ParsedUpdate(ip = ip, time = time, raw = line))
                    continue
                }
            }

            var action: String? = null
            var level: Int? = null
            var softwareName: String? = null
            var targetIp: String? = extractIp(line)
            var isOwned = false

            // Software Actions
            val softMatch = Regex("""(Downloaded|Uploaded|Downloading|Uploading)\s+Lv(\d+)\s+(.+?)(?:\s+(?:from|to)\b|\.|\.\.|\s*$)""", RegexOption.IGNORE_CASE).find(line)
            if (softMatch != null) {
                action = softMatch.groupValues[1].lowercase()
                level = softMatch.groupValues[2].toIntOrNull()
                softwareName = softMatch.groupValues[3].trim()
                isOwned = action.startsWith("download")
            }

            // Fallback to the last accessed IP if no explicit IP is in the line and it happened at the same time
            if (targetIp == null && lastAccessIp != null && lastAccessTime == time) {
                targetIp = lastAccessIp
            }

            // Wallets
            val walletMatch = Regex("""(?:Stole\s+([\d,]+)\s+Crypto\s+from|([\d,]+)\s+Crypto\s+transferred\s+to)\s+([a-zA-Z0-9.]{6,})""", RegexOption.IGNORE_CASE).find(line)
            if (walletMatch != null) {
                val rawAmt = if (walletMatch.groupValues[1].isNotEmpty()) walletMatch.groupValues[1] else walletMatch.groupValues[2]
                val amount = rawAmt.replace(",", "").toIntOrNull()
                val wallet = shortenWallet(walletMatch.groupValues[3])
                updates.add(
                    ParsedUpdate(
                        ip = targetIp ?: "",
                        time = time,
                        wallet = wallet,
                        stolenAmount = amount,
                        raw = line
                    )
                )
                // Clear the access IP so subsequent Siphon collections don't falsely claim this IP
                lastAccessIp = null
                continue
            }

            // Cracking / Firewall
            val crackMatch = Regex("""(Cracking|Cracked)\s+password\s+(?:on\s+)?((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx))""", RegexOption.IGNORE_CASE).find(line)
            if (crackMatch != null) {
                val ip = extractIp(crackMatch.groupValues[2])
                updates.add(ParsedUpdate(ip = ip ?: (targetIp ?: ""), time = time, raw = line))
                continue
            }

            val firewallMatch = Regex("""(Bypassed|Failed to bypass)\s+firewall\s+(?:on\s+)?((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx))""", RegexOption.IGNORE_CASE).find(line)
            if (firewallMatch != null) {
                val ip = extractIp(firewallMatch.groupValues[2])
                updates.add(ParsedUpdate(ip = ip ?: (targetIp ?: ""), time = time, raw = line))
                continue
            }

            // Add software update if found
            if (softMatch != null && targetIp != null && !line.contains("[UNKNOWN]")) {
                updates.add(
                    ParsedUpdate(
                        ip = targetIp,
                        time = time,
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
        val ipMatch = Regex("""IP\s+([0-9]{1,3}(?:\.[0-9]{1,3}|\.xxx){3})""", RegexOption.IGNORE_CASE).find(text)
        if (ipMatch == null) return null
        val ip = ipMatch.groupValues[1].trim()

        val userLineMatch = Regex("""^[ \t]*>\s*(.+?)$""", RegexOption.MULTILINE).find(text)
        var username = userLineMatch?.groupValues?.get(1)?.trim()

        var clan: String? = null
        if (username != null) {
            val clanMatch = Regex("""\[([^\]]{2,10})\]""").find(username)
            if (clanMatch != null) {
                clan = clanMatch.groupValues[1].trim()
                username = username.replace(clanMatch.value, "").trim()
            }
            username = username.removeSuffix("_").trim()
        }
        
        if (clan == null) {
            val genericClanMatch = Regex("""\[([^\]]{2,10})\]""").find(text)
            clan = genericClanMatch?.groupValues?.get(1)?.trim()
        }

        val lvlMatch = Regex("""LVL\s+(\d+)""", RegexOption.IGNORE_CASE).find(text)
        val level = lvlMatch?.groupValues?.get(1)?.toIntOrNull()

        val devMatch = Regex("""DEVICE\s+([^\n\r]+)""", RegexOption.IGNORE_CASE).find(text)
        val device = devMatch?.groupValues?.get(1)?.trim()

        val netMatch = Regex("""NETWORK\s+([^\n\r]+)""", RegexOption.IGNORE_CASE).find(text)
        val network = netMatch?.groupValues?.get(1)?.trim()

        val fwMatch = Regex("""FIREWALL\s+Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        val firewall = fwMatch?.groupValues?.get(1)?.toIntOrNull()

        val encMatch = Regex("""ENCRYPTOR\s+Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        val encryptor = encMatch?.groupValues?.get(1)?.toIntOrNull()

        val hardware = if (device != null && network != null) "$device • $network" else device ?: network

        return HomeScreen(ip, username, clan, level, hardware, firewall, encryptor)
    }

    /**
     * Parses installed software levels from text scraped from a HackEx software screen.
     */
    fun parseSoftwareScreen(text: String): SoftwareScreen? {
        val userMatch = Regex("""^([^\r\n]+?)['’]s installed software\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).find(text)
        val username = userMatch?.groupValues?.get(1)?.trim()

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

        if (username == null && softwareItems.isEmpty()) return null
        return SoftwareScreen(username, softwareItems)
    }

    /**
     * Parses username and wallet address from text scraped from a HackEx wallet screen.
     */
    fun parseWalletScreen(text: String): WalletScreen? {
        val userMatch = Regex("""^([^\r\n]+?)['’]s wallet\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).find(text)
        val username = userMatch?.groupValues?.get(1)?.trim()

        val walletMatch = Regex("""WALLET ADDRESS\s+([a-zA-Z0-9]{20,})""", RegexOption.IGNORE_CASE).find(text)
        val rawWallet = walletMatch?.groupValues?.get(1)?.trim()

        if (username != null && rawWallet != null) {
            return WalletScreen(username, shortenWallet(rawWallet))
        }
        return null
    }

    /**
     * Parses the Siphon Earnings screen to extract currently deployed siphons.
     */
    fun parseSiphonScreen(text: String): SiphonScreen? {
        if (!text.contains("SIPHON EARNINGS")) return null

        val deployedMatch = Regex("""DEPLOYED\s+(\d+)""").find(text)
        val deployedCount = deployedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val items = mutableListOf<SiphonItem>()
        // Match structure: IP, LV.x, y%, z siphoned, w ago
        val pattern = Regex("""((?:\d{1,3}\.){3}\d{1,3})\s+LV\.(\d+)\s+([\d.]+[%])\s+([\d,]+)\s+siphoned\s+([a-zA-Z0-9]+ ago)""", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(text)

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
     * Parses the crew screen to extract member IPs.
     */
    fun parseCrewScreen(rawText: String): CrewScreen? {
        val lines = rawText.split(Regex("""\r?\n""")).map { it.trim() }
        var isCrewScreen = false
        for (line in lines) {
            if (line.startsWith("// ") && line.endsWith(" Members")) {
                isCrewScreen = true
                break
            }
        }
        if (!isCrewScreen) return null

        val ips = mutableListOf<String>()
        for (line in lines) {
            val ip = extractIp(line)
            if (ip != null) {
                ips.add(ip)
            }
        }

        if (ips.isEmpty()) return null
        return CrewScreen(ips)
    }

    /**
     * Parses the process screen to extract IPs.
     */
    fun parseProcessScreen(rawText: String): ProcessScreen? {
        if (!rawText.contains("// PROCESSES")) return null

        val ips = mutableListOf<String>()
        val invalidatedIps = mutableListOf<String>()
        val lines = rawText.split(Regex("""\r?\n""")).map { it.trim() }
        for (i in lines.indices) {
            val line = lines[i]
            val ip = extractIp(line)
            // 0.0.0.0 is the player's own local device in the log, ignore it
            if (ip != null && ip != "0.0.0.0") {
                val prevLine = if (i > 0) lines[i - 1] else ""
                if (prevLine == "INVALIDATED") {
                    invalidatedIps.add(ip)
                } else {
                    ips.add(ip)
                }
            }
        }

        if (ips.isEmpty() && invalidatedIps.isEmpty()) return null
        return ProcessScreen(ips.distinct(), invalidatedIps.distinct())
    }
}
