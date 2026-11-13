package com.whisk.hackexlogger

object SharedRegex {
    val REGEX_IP = Regex("""\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b""")
    val REGEX_MASKED_IP = Regex("""\b(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\b""", RegexOption.IGNORE_CASE)
    val REGEX_UNKNOWN_IP = Regex("""\b\[?UNKNOWN\]?\b""", RegexOption.IGNORE_CASE)
}

enum class ScreenType {
    HOME, SOFTWARE, WALLET, LOGS, SIPHON, CREW, PROCESS, UNKNOWN
}

object ScreenClassifier {
    fun detectScreenType(text: String, lines: List<String>): ScreenType {
        val lowerText = text.lowercase()
        if (lowerText.contains("installed software")) return ScreenType.SOFTWARE
        if (lowerText.contains("wallet address") || lowerText.contains("'s wallet") || lowerText.contains("’s wallet")) return ScreenType.WALLET
        if (lowerText.contains("siphoned") && lowerText.contains("deployed")) return ScreenType.SIPHON
        if (lowerText.contains("device accessed") || lowerText.contains("accessed device") || lowerText.contains("cracked password") || lowerText.contains("bypassed firewall")) return ScreenType.LOGS
        if (lowerText.contains("process") && lowerText.contains("task")) return ScreenType.PROCESS
        if (lowerText.contains("firewall") && (lowerText.contains("encryptor") || lowerText.contains("ip") || lowerText.contains("device"))) return ScreenType.HOME
        if (lines.any { it.trim().equals("LEADER", ignoreCase = true) || it.contains("Members", ignoreCase = true) }) return ScreenType.CREW

        val regexProfileIp = Regex("""IP\s+([0-9]{1,3}(?:\.[0-9]{1,3}|\.xxx){3}|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)
        val regexFirewall = Regex("""FIREWALL\s*\r?\n?\s*Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
        if (regexProfileIp.containsMatchIn(text) && regexFirewall.containsMatchIn(text)) {
            return ScreenType.HOME
        }
        return ScreenType.UNKNOWN
    }
}

object CommonParserUtil {
    fun shortenWallet(wallet: String): String {
        if (wallet.length > 10 && !wallet.contains("...")) {
            return wallet.take(6) + "..." + wallet.takeLast(4)
        }
        return wallet
    }

    fun extractIp(text: String): String? {
        val ipMatch = SharedRegex.REGEX_IP.find(text)
        if (ipMatch != null) return ipMatch.value
        val maskedIpMatch = SharedRegex.REGEX_MASKED_IP.find(text)
        if (maskedIpMatch != null) return maskedIpMatch.value
        val unknownMatch = SharedRegex.REGEX_UNKNOWN_IP.find(text)
        if (unknownMatch != null) return "[UNKNOWN]"
        return null
    }
}

// ----------------------------------------
// HomeScreen
// ----------------------------------------
data class HomeScreen(
    val ip: String,
    val username: String?,
    val clan: String?,
    val level: Int?,
    val hardware: String?,
    val firewall: Int?,
    val encryptor: Int?
)

object HomeScreenParser {
    private val REGEX_PROFILE_IP = Regex("""IP\s+([0-9]{1,3}(?:\.[0-9]{1,3}|\.xxx){3}|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)
    private val REGEX_USER_HEADER = Regex("""^[ \t]*>\s*(.+?)$""", RegexOption.MULTILINE)
    private val REGEX_CLAN_TAG = Regex("""\[([^\]]{2,10})\]""")
    private val REGEX_LEVEL = Regex("""LVL\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val REGEX_DEVICE = Regex("""DEVICE\s*\r?\n?\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)
    private val REGEX_NETWORK = Regex("""NETWORK\s*\r?\n?\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)
    private val REGEX_FIREWALL = Regex("""FIREWALL\s*\r?\n?\s*Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val REGEX_ENCRYPTOR = Regex("""ENCRYPTOR\s*\r?\n?\s*Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): HomeScreen? {
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
}

// ----------------------------------------
// Logs
// ----------------------------------------
data class ParsedUpdate(
    val ip: String,
    val time: String?,
    val wallet: String? = null,
    val software: Software? = null,
    val isOwnedSoftware: Boolean = false,
    val raw: String,
    val stolenAmount: Int? = null,
    val isOwnLogTheft: Boolean = false
)

data class Software(val action: String, val level: Int, val name: String)

object LogScreenParser {
    private val REGEX_LOG_PREFIX = Regex("""^\[.+?\]""")
    private val REGEX_TIME_HEADER = Regex("""^\[([^\]]+)\]""")
    private val REGEX_TIME_MINUTES = Regex("""(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{1,2})""")
    private val REGEX_LOG_ACCESS = Regex("""(?:Device accessed from|Accessed device at)\s+((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx)|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_SOFTWARE = Regex("""(Downloaded|Uploaded|Downloading|Uploading)\s+Lv(\d+)\s+(.+?)(?:\s+(?:from|to)\b|\.|\.\.|\s*$)""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_WALLET = Regex("""(?:Stole\s+([\d,]+)\s+Crypto\s+from|([\d,]+)\s+Crypto\s+transferred\s+to)\s+([a-zA-Z0-9.]{6,})""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_CRACK = Regex("""(Cracking|Cracked)\s+password\s+(?:on\s+)?((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx)|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_FIREWALL = Regex("""(Bypassed|Failed to bypass)\s+firewall\s+(?:on\s+)?((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx)|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)

    fun parseLogMinutes(timeStr: String?): Int? {
        if (timeStr == null) return null
        val match = REGEX_TIME_MINUTES.find(timeStr) ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val hour = match.groupValues[3].toIntOrNull() ?: return null
        val minute = match.groupValues[4].toIntOrNull() ?: return null
        return day * 1440 + hour * 60 + minute
    }

    fun parse(rawLines: List<String>): List<ParsedUpdate> {
        val updates = mutableListOf<ParsedUpdate>()
        var lastAccessIp: String? = null
        var lastAccessMinutes: Int? = null

        for (i in rawLines.indices.reversed()) {
            val line = rawLines[i].trim()
            if (line.isEmpty()) {
                lastAccessIp = null
                lastAccessMinutes = null
                continue
            }
            if (!REGEX_LOG_PREFIX.containsMatchIn(line)) continue

            val timeStr = REGEX_TIME_HEADER.find(line)?.groupValues?.get(1)
            val currentMinutes = parseLogMinutes(timeStr)

            if (line.contains("[UNKNOWN]")) {
                lastAccessIp = null
                lastAccessMinutes = null
                continue
            }

            val accessMatch = REGEX_LOG_ACCESS.find(line)
            if (accessMatch != null) {
                val ip = CommonParserUtil.extractIp(accessMatch.groupValues[1])
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
            var targetIp: String? = CommonParserUtil.extractIp(line)
            var isOwned = false

            val softMatch = REGEX_LOG_SOFTWARE.find(line)
            if (softMatch != null) {
                action = softMatch.groupValues[1].lowercase()
                level = softMatch.groupValues[2].toIntOrNull()
                softwareName = softMatch.groupValues[3].trim()
                isOwned = action.startsWith("download")
            }

            if (targetIp == null && lastAccessIp != null) {
                if (currentMinutes != null && lastAccessMinutes != null) {
                    val diff = Math.abs(currentMinutes - lastAccessMinutes)
                    if (diff <= 3) {
                        targetIp = lastAccessIp
                    } else {
                        lastAccessIp = null
                        lastAccessMinutes = null
                    }
                } else {
                    targetIp = lastAccessIp
                }
            }

            val walletMatch = REGEX_LOG_WALLET.find(line)
            if (walletMatch != null) {
                val isStole = walletMatch.groupValues[1].isNotEmpty()
                val rawAmt = if (isStole) walletMatch.groupValues[1] else walletMatch.groupValues[2]
                val amount = rawAmt.replace(",", "").toIntOrNull()
                val wallet = CommonParserUtil.shortenWallet(walletMatch.groupValues[3])
                updates.add(ParsedUpdate(ip = targetIp ?: "", time = timeStr, wallet = wallet, stolenAmount = amount, raw = line, isOwnLogTheft = isStole))
                lastAccessIp = null
                lastAccessMinutes = null
                continue
            }

            val crackMatch = REGEX_LOG_CRACK.find(line)
            if (crackMatch != null) {
                val ip = CommonParserUtil.extractIp(crackMatch.groupValues[2])
                updates.add(ParsedUpdate(ip = ip ?: (targetIp ?: ""), time = timeStr, raw = line))
                continue
            }

            val firewallMatch = REGEX_LOG_FIREWALL.find(line)
            if (firewallMatch != null) {
                val ip = CommonParserUtil.extractIp(firewallMatch.groupValues[2])
                updates.add(ParsedUpdate(ip = ip ?: (targetIp ?: ""), time = timeStr, raw = line))
                continue
            }

            if (softMatch != null && targetIp != null && !line.contains("[UNKNOWN]")) {
                updates.add(ParsedUpdate(ip = targetIp, time = timeStr, software = if (action != null && level != null && softwareName != null) Software(action, level, softwareName) else null, isOwnedSoftware = isOwned, raw = line))
            }
        }
        return updates
    }
}

// ----------------------------------------
// Software Screen
// ----------------------------------------
data class SoftwareScreenItem(val level: Int, val status: String)
data class SoftwareScreen(val username: String?, val softwareItems: Map<String, SoftwareScreenItem>)

object SoftwareScreenParser {
    private val REGEX_SOFTWARE_HEADER = Regex("""^([^\r\n]+?)['’]s installed software\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    fun parse(text: String): SoftwareScreen? {
        val userMatch = REGEX_SOFTWARE_HEADER.find(text) ?: return null
        val username = userMatch.groupValues[1].trim()
        val softwareItems = mutableMapOf<String, SoftwareScreenItem>()
        val validNames = listOf("Antivirus", "Spam", "Rootkit", "Firewall", "Bypasser", "Password Cracker", "Password Encryptor", "Proxy", "Trace", "Siphon")

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
}

// ----------------------------------------
// Wallet Screen
// ----------------------------------------
data class WalletScreen(val username: String, val wallet: String)

object WalletScreenParser {
    private val REGEX_WALLET_HEADER = Regex("""^([^\r\n]+?)['’]s wallet\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val REGEX_WALLET_ADDRESS = Regex("""WALLET ADDRESS\s+([a-zA-Z0-9]{20,})""", RegexOption.IGNORE_CASE)

    fun parse(text: String): WalletScreen? {
        val userMatch = REGEX_WALLET_HEADER.find(text) ?: return null
        val username = userMatch.groupValues[1].trim()
        val walletMatch = REGEX_WALLET_ADDRESS.find(text)
        val rawWallet = walletMatch?.groupValues?.get(1)?.trim()
        if (rawWallet != null) {
            return WalletScreen(username, CommonParserUtil.shortenWallet(rawWallet))
        }
        return null
    }
}

// ----------------------------------------
// Siphon Screen
// ----------------------------------------
data class SiphonItem(val ip: String, val level: Int, val percent: String, val amount: Int, val timeAgo: String)
data class SiphonScreen(val deployedCount: Int, val items: List<SiphonItem>)

object SiphonScreenParser {
    private val REGEX_SIPHON_DEPLOYED = Regex("""DEPLOYED\s+(\d+)""")
    private val REGEX_SIPHON_ITEM = Regex("""((?:\d{1,3}\.){3}\d{1,3})\s+LV\.(\d+)\s+([\d.]+[%])\s+([\d,]+)\s+siphoned\s+([a-zA-Z0-9]+ ago)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): SiphonScreen? {
        if (!text.contains("SIPHON EARNINGS")) return null
        val deployedCount = REGEX_SIPHON_DEPLOYED.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val items = mutableListOf<SiphonItem>()
        val matches = REGEX_SIPHON_ITEM.findAll(text)
        for (match in matches) {
            items.add(SiphonItem(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 0, match.groupValues[3], match.groupValues[4].replace(",", "").toIntOrNull() ?: 0, match.groupValues[5]))
        }
        return SiphonScreen(deployedCount, items)
    }
}

// ----------------------------------------
// Crew Screen
// ----------------------------------------
data class CrewScreen(val members: List<String>)

object CrewScreenParser {
    fun parse(rawLines: List<String>): CrewScreen? {
        val isCrewScreen = rawLines.any { line ->
            line.contains("Crew", ignoreCase = true) || line.contains("Cr3w", ignoreCase = true) ||
            line.contains("Members", ignoreCase = true) || line.trim().equals("LEADER", ignoreCase = true) ||
            line.trim().equals("OFFICER", ignoreCase = true) || line.trim().equals("MEMBER", ignoreCase = true)
        }
        if (!isCrewScreen) return null

        val members = mutableListOf<String>()
        for (line in rawLines) {
            val ip = CommonParserUtil.extractIp(line)
            if (ip != null && !ip.startsWith("0.0.") && !members.contains(ip)) {
                members.add(ip)
            }
        }
        if (members.isEmpty()) return null
        return CrewScreen(members)
    }
}

// ----------------------------------------
// Process Screen
// ----------------------------------------
data class ProcessDownloadedSoftware(val ip: String, val name: String, val level: Int)
data class ProcessScreen(val ips: List<String>, val invalidatedIps: List<String>, val downloads: List<ProcessDownloadedSoftware>)

object ProcessScreenParser {
    fun parse(rawLines: List<String>): ProcessScreen? {
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

            val validSoftwareNames = listOf("Antivirus", "Spam", "Rootkit", "Firewall", "Bypasser", "Password Cracker", "Password Encryptor", "Proxy", "Trace", "Siphon")
            for (sw in validSoftwareNames) {
                if (trimmed.equals(sw, ignoreCase = true)) {
                    currentSoftwareName = sw
                    break
                }
            }

            val ip = CommonParserUtil.extractIp(trimmed)
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
