package com.whisk.hackexlogger

object SharedRegex {
    val REGEX_IP = Regex("""\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b""")
    val REGEX_MASKED_IP = Regex("""\b(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\.(?:\d{1,3}|xxx)\b""", RegexOption.IGNORE_CASE)
    val REGEX_UNKNOWN_IP = Regex("""\b\[?UNKNOWN\]?\b""", RegexOption.IGNORE_CASE)
}

enum class ScreenType {
    HOME, SOFTWARE, WALLET, LOGS, SIPHON, SPAM_EARNINGS, CREW, PROCESS, STORE, BYPASS, OWN_PROFILE, UNKNOWN
}

object ScreenClassifier {
    private val LOG_LINE = Regex("""^\[\d{1,2}-\d{1,2}\s+\d{1,2}:\d{2}]\s+(stole |uploaded lv|downloaded lv|failed to bypass|failed to crack|bypassed firewall|cracked password|accessed device|uploading lv|purchased )""", RegexOption.IGNORE_CASE)

    private fun isLogScreen(lowerText: String, lines: List<String>): Boolean {
        if (lowerText.contains("device accessed") || lowerText.contains("accessed device") || lowerText.contains("cracked password") || lowerText.contains("bypassed firewall")) return true
        if (lines.any { val t = it.trim().lowercase(); t == "// log" || t == "~/log.txt" || t == "// victim log" || t == "~/victim_log.txt" }) return true
        return lines.any { LOG_LINE.containsMatchIn(it.trim()) }
    }

    fun detectScreenType(text: String, lines: List<String>): ScreenType {
        val lowerText = text.lowercase()
        if (lowerText.contains("my device") && (lowerText.contains("daily missions") || lowerText.contains("login streak"))) return ScreenType.OWN_PROFILE
        if (lowerText.contains("using bypasser") || lowerText.contains("// bypass firewall") || (lowerText.contains("// scan results") && lowerText.contains("fw lv"))) return ScreenType.BYPASS
        if (lowerText.contains("proprietary key generator built for your device")) return ScreenType.STORE
        if (lowerText.contains("installed software")) return ScreenType.SOFTWARE
        if (lowerText.contains("wallet address") || lowerText.contains("'s wallet") || lowerText.contains("’s wallet")) return ScreenType.WALLET
        // The Spam and Siphon earnings pages both start with the Apps page text, so check Spam first
        if (text.contains("SPAM EARNINGS")) return ScreenType.SPAM_EARNINGS
        if (lowerText.contains("siphoned") && lowerText.contains("deployed")) return ScreenType.SIPHON
        if (isLogScreen(lowerText, lines)) return ScreenType.LOGS
        if (lines.any { it.trim().equals("// PROCESSES", ignoreCase = true) } || Regex("""\b\d+ active tasks?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return ScreenType.PROCESS
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

    /** True for IPs we can't use as a target key: masked (`xxx`), `[UNKNOWN]` (Ghost), or blank. */
    fun isMaskedIp(ip: String?): Boolean =
        ip.isNullOrBlank() || ip.contains("xxx", ignoreCase = true) || ip.contains("UNKNOWN", ignoreCase = true)

    private val NAV_BAR = listOf("HOME", "SCAN", "PROCESSES", "APPS", "STORE")

    /**
     * Removes the game's bottom navigation block (`HOME SCAN PROCESSES APPS STORE CREWS[ n]`), wherever it
     * appears in the snapshot. It shows up on your own screens and contains words like "PROCESSES"/"CREWS"
     * that fooled the Crew and Process parsers.
     */
    fun stripNavBar(lines: List<String>): List<String> {
        val trimmed = lines.map { it.trim() }
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val isBlock = i + NAV_BAR.size < lines.size &&
                NAV_BAR.indices.all { trimmed[i + it].equals(NAV_BAR[it], ignoreCase = true) } &&
                Regex("""CREWS(\s+\d+)?""", RegexOption.IGNORE_CASE).matches(trimmed[i + NAV_BAR.size])
            if (isBlock) { i += NAV_BAR.size + 1; continue }
            out.add(lines[i]); i++
        }
        return out
    }

    private val ENTITY = Regex("""&(#\d+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);""")

    /** Decodes the HTML entities the game's WebView leaks into text (e.g. `jaywat2550&#39;s wallet`). */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return ENTITY.replace(text) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> {
                    val code = if (e.startsWith("#x") || e.startsWith("#X")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
                }
            }
        }
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
// OwnHomeScreen (Player's Own Profile)
// ----------------------------------------
data class OwnHomeScreen(
    val username: String?,
    val level: Int?
)

object OwnHomeScreenParser {
    private val REGEX_USER_HEADER = Regex("""^[ \t]*>\s*(.+?)$""", RegexOption.MULTILINE)
    private val REGEX_LEVEL = Regex("""LVL\s+(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): OwnHomeScreen? {
        if (!text.lowercase().contains("my device")) return null
        val userMatch = REGEX_USER_HEADER.find(text)
        var username = userMatch?.groupValues?.get(1)?.trim()
        if (username != null) {
            username = username.removeSuffix("_").trim()
        }
        val level = REGEX_LEVEL.find(text)?.groupValues?.get(1)?.toIntOrNull()
        return OwnHomeScreen(username, level)
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

/**
 * A timestamped log line that did not produce a [ParsedUpdate] (or was dropped later by the database layer).
 * @property stage "parser" (LogScreenParser) or "db" (DatabaseManager)
 * @property reason machine-readable reason code, see [LogDiscardReason]
 */
data class DiscardedLogLine(val line: String, val stage: String, val reason: String)

object LogDiscardReason {
    // Parser stage
    const val NO_PATTERN = "NO_PATTERN"             // matched none of the log regexes
    const val UNKNOWN_SOURCE = "UNKNOWN_SOURCE"     // contains [UNKNOWN]; also resets IP attribution state
    const val NO_TARGET_IP = "NO_TARGET_IP"         // software line with no IP and no access line within 3 min
    // Database stage
    const val EMPTY_IP = "EMPTY_IP"                                 // no IP and no wallet to key the record on
    const val WALLET_CONFLICT = "WALLET_CONFLICT"                   // record already owns a different wallet
    const val MASKED_SOFTWARE_IGNORED = "MASKED_SOFTWARE_IGNORED"   // download onto a masked IP is not stored
    const val BAD_TIMESTAMP = "BAD_TIMESTAMP"                       // theft amount not recorded: time unparsable (wallet still linked)
}

/** Full result of a log parse: the updates plus every timestamped line that produced nothing. */
data class LogParseResult(
    val updates: List<ParsedUpdate>,
    val discarded: List<DiscardedLogLine>,
    val timestampedLineCount: Int,
    /** The log footer's `Last saved: YYYY-MM-DD HH:MM:SS` (UTC) as epoch ms, if shown. Anchors the year of `[M-D H:MM]` lines. */
    val lastSavedUtcMs: Long? = null
)

/**
 * Log timestamps. The game writes `[M-D H:MM]` in **UTC** with no year (owner-confirmed 2026-11-19).
 * The year comes from an anchor: the log's `Last saved:` footer when present, else the current UTC time.
 * A line whose month is after the anchor's month belongs to the previous year (December lines read in January).
 */
object LogTime {
    private val REGEX_LINE_TIME = Regex("""^(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})$""")
    private val REGEX_LAST_SAVED = Regex("""Last saved:\s*(\d{4})-(\d{2})-(\d{2})\s+(\d{1,2}):(\d{2}):(\d{2})""", RegexOption.IGNORE_CASE)
    private val UTC = java.util.TimeZone.getTimeZone("UTC")

    /** Overridable clock for tests. */
    @Volatile var nowProvider: () -> Long = { System.currentTimeMillis() }

    fun lastSaved(lines: List<String>): Long? {
        for (line in lines) {
            val m = REGEX_LAST_SAVED.find(line) ?: continue
            val v = m.groupValues.drop(1).map { it.toInt() }
            return utc(v[0], v[1], v[2], v[3], v[4], v[5])
        }
        return null
    }

    /** `M-D H:MM` (UTC) → epoch ms, or null if it isn't a valid log time. */
    fun toEpochMs(timeStr: String, anchorUtcMs: Long? = null): Long? {
        val m = REGEX_LINE_TIME.find(timeStr.trim()) ?: return null
        val month = m.groupValues[1].toInt()
        val day = m.groupValues[2].toInt()
        val hour = m.groupValues[3].toInt()
        val minute = m.groupValues[4].toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59) return null
        val ref = java.util.Calendar.getInstance(UTC).apply { timeInMillis = anchorUtcMs ?: nowProvider() }
        var year = ref.get(java.util.Calendar.YEAR)
        if (month > ref.get(java.util.Calendar.MONTH) + 1) year -= 1
        return utc(year, month, day, hour, minute, 0)
    }

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        java.util.Calendar.getInstance(UTC).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
}

object LogScreenParser {
    private val REGEX_LOG_PREFIX = Regex("""^\[.+?\]""")
    private val REGEX_TIME_HEADER = Regex("""^\[([^\]]+)\]""")
    private val REGEX_TIME_MINUTES = Regex("""(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{1,2})""")
    private val REGEX_LOG_ACCESS = Regex("""(?:Device accessed from|Accessed device at)\s+((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx)|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_SOFTWARE = Regex("""(Downloaded|Uploaded|Downloading|Uploading)\s+Lv(\d+)\s+(.+?)(?:\s+(?:from|to)\b|\.|\.\.|\s*$)""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_WALLET = Regex("""(?:Stole\s+([\d,]+)\s+Crypto\s+from|([\d,]+)\s+Crypto\s+transferred\s+to)\s+([a-zA-Z0-9.]{6,})""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_CRACK = Regex("""(Cracking|Cracked|Failed to crack)\s+password\s+(?:on\s+)?((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx)|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)
    private val REGEX_LOG_FIREWALL = Regex("""(Bypassed|Failed to bypass)\s+firewall\s+(?:on\s+)?((?:(?:\d{1,3}|xxx)\.){3}(?:\d{1,3}|xxx)|\[?UNKNOWN\]?)""", RegexOption.IGNORE_CASE)

    /**
     * Minutes since the epoch (UTC) for a `M-D H:MM` log time, so the 3-minute "steal belongs to the last
     * access" window also works across midnight, month ends and New Year. (It used to ignore the month.)
     */
    fun parseLogMinutes(timeStr: String?, anchorUtcMs: Long? = null): Long? {
        if (timeStr == null) return null
        val match = REGEX_TIME_MINUTES.find(timeStr) ?: return null
        return LogTime.toEpochMs(match.value, anchorUtcMs)?.div(60_000L)
    }

    fun parse(rawLines: List<String>): List<ParsedUpdate> = parseDetailed(rawLines).updates

    /**
     * Same parse as [parse], but also reports every timestamped line that produced no update and why.
     * The `updates` list is identical to what [parse] returns.
     */
    fun parseDetailed(rawLines: List<String>): LogParseResult {
        val updates = mutableListOf<ParsedUpdate>()
        val discarded = mutableListOf<DiscardedLogLine>()
        var timestampedCount = 0
        var lastAccessIp: String? = null
        var lastAccessMinutes: Long? = null
        val lastSaved = LogTime.lastSaved(rawLines)

        fun discard(line: String, reason: String) {
            discarded.add(DiscardedLogLine(line, "parser", reason))
        }

        for (i in rawLines.indices.reversed()) {
            val line = rawLines[i].trim()
            if (line.isEmpty()) {
                lastAccessIp = null
                lastAccessMinutes = null
                continue
            }
            if (!REGEX_LOG_PREFIX.containsMatchIn(line)) continue
            timestampedCount++

            val timeStr = REGEX_TIME_HEADER.find(line)?.groupValues?.get(1)
            val currentMinutes = parseLogMinutes(timeStr, lastSaved)

            if (line.contains("[UNKNOWN]")) {
                lastAccessIp = null
                lastAccessMinutes = null
                discard(line, LogDiscardReason.UNKNOWN_SOURCE)
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
            } else if (softMatch != null) {
                discard(line, LogDiscardReason.NO_TARGET_IP)
            } else {
                discard(line, LogDiscardReason.NO_PATTERN)
            }
        }
        return LogParseResult(updates, discarded, timestampedCount, lastSaved)
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
/** [maskedCount]: rows whose IP is hidden (`xxx.xxx.xxx.xxx`); they can't be tracked but still count towards [deployedCount]. */
data class SiphonScreen(val deployedCount: Int, val items: List<SiphonItem>, val maskedCount: Int = 0)

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
        val masked = REGEX_MASKED_ITEM.findAll(text).count { it.groupValues[1].contains("xxx", ignoreCase = true) }
        return SiphonScreen(deployedCount, items, masked)
    }

    private val REGEX_MASKED_ITEM = Regex("""((?:\d{1,3}|xxx)(?:\.(?:\d{1,3}|xxx)){3})\s+LV\.\d+\s+[\d.]+%\s+[\d,]+\s+siphoned""", RegexOption.IGNORE_CASE)
}

// ----------------------------------------
// Spam Earnings Screen (Apps → Spam card)
// ----------------------------------------
data class SpamItem(val ip: String, val level: Int, val perHour: Int, val earned: Int, val age: String)
data class SpamEarningsScreen(val deployedCount: Int, val ratePerHour: Int?, val items: List<SpamItem>, val maskedCount: Int)

object SpamEarningsParser {
    private val REGEX_DEPLOYED = Regex("""SPAM EARNINGS\s+DEPLOYED\s+(\d+)""")
    private val REGEX_RATE = Regex("""RATE\s+([\d,]+)/hr""")
    // Row: <ip> / LV.17 / 100/hr / 12,100 earned / 5d (age can also be e.g. "today")
    private val REGEX_ROW = Regex("""((?:\d{1,3}|xxx)(?:\.(?:\d{1,3}|xxx)){3})\s+LV\.(\d+)\s+(\d+)/hr\s+([\d,]+)\s+earned\s+(\S+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): SpamEarningsScreen? {
        if (!text.contains("SPAM EARNINGS")) return null
        val body = text.substring(text.indexOf("SPAM EARNINGS"))
        val deployed = REGEX_DEPLOYED.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val rate = REGEX_RATE.find(body)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val items = mutableListOf<SpamItem>()
        var masked = 0
        for (m in REGEX_ROW.findAll(body)) {
            val ip = m.groupValues[1]
            if (ip.contains("xxx", ignoreCase = true)) { masked++; continue }
            items.add(SpamItem(ip, m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].replace(",", "").toInt(), m.groupValues[5]))
        }
        return SpamEarningsScreen(deployed, rate, items, masked)
    }
}

// ----------------------------------------
// Crew Screen
// ----------------------------------------
data class CrewScreen(val members: List<String>)

object CrewScreenParser {
    fun parse(allLines: List<String>): CrewScreen? {
        // A real crew page has a "// <crew> Members" header and a LEADER line. The old check accepted any
        // line containing "Crew", which matched the nav bar's "CREWS" on every one of your own screens.
        val rawLines = CommonParserUtil.stripNavBar(allLines)
        val hasHeader = rawLines.any { val t = it.trim(); t.startsWith("//") && t.endsWith("Members", ignoreCase = true) }
        val hasLeader = rawLines.any { it.trim().equals("LEADER", ignoreCase = true) }
        if (!hasHeader || !hasLeader) return null

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
    fun parse(allLines: List<String>): ProcessScreen? {
        // Requires the real header; "PROCESSES" alone is also a nav bar button on your own screens.
        val rawLines = CommonParserUtil.stripNavBar(allLines)
        val isProcessScreen = rawLines.any { line ->
            line.trim().equals("// PROCESSES", ignoreCase = true) || Regex("""\b\d+ active tasks?\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)
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

// ----------------------------------------
// StoreScreen
// ----------------------------------------
data class StoreScreen(
    val nextDevice: String? = null,
    val nextNetwork: String? = null,
    val botnetStatus: String? = null,
    val nextSoftwareLevels: Map<String, Int> = emptyMap(),
    /** True when the device section shows MAX TIER: then [nextDevice] is the device you already own. */
    val deviceMaxed: Boolean = false,
    /** True when the network section shows MAX TIER: then [nextNetwork] is the network you already own. */
    val networkMaxed: Boolean = false
)

object StoreScreenParser {
    private enum class Section { DEVICE, NETWORK, BOTNET, SOFTWARE, OTHER }

    /**
     * Section headers differ by state: `// NEXT DEVICE` / `// NEXT NETWORK` normally, but once maxed the game shows
     * `// DEVICE` / `// NETWORK` with the current item and `MAX TIER` instead of a price (raw dump #6).
     */
    private fun sectionOf(header: String): Section {
        val h = header.uppercase()
        return when {
            h.contains("SOFTWARE") -> Section.SOFTWARE
            h.contains("BOTNET") -> Section.BOTNET
            h.contains("NETWORK") -> Section.NETWORK
            h.contains("DEVICE") -> Section.DEVICE
            else -> Section.OTHER
        }
    }

    fun parse(lines: List<String>): StoreScreen? {
        val text = lines.joinToString("\n")
        if (!text.lowercase().contains("proprietary key generator built for your device")) return null

        var nextDevice: String? = null
        var nextNetwork: String? = null
        var botnetStatus: String? = null
        var deviceMaxed = false
        var networkMaxed = false
        val softwareLevels = mutableMapOf<String, Int>()

        var currentSection: Section? = null
        var currentSoftware: String? = null

        val validSoftwareNames = listOf(
            "Firewall", "Bypasser", "Password Encryptor", "Password Cracker",
            "Spam", "Siphon", "Rootkit", "Antivirus", "Proxy", "Trace", "Keygen"
        )

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("// ")) {
                currentSection = sectionOf(line)
                continue
            }

            when (currentSection) {
                Section.DEVICE -> {
                    if (line.equals("MAX TIER", ignoreCase = true)) deviceMaxed = true
                    else if (nextDevice == null && line.isNotEmpty()) nextDevice = line
                }
                Section.NETWORK -> {
                    if (line.equals("MAX TIER", ignoreCase = true)) networkMaxed = true
                    else if (nextNetwork == null && line.isNotEmpty()) nextNetwork = line
                }
                Section.BOTNET -> {
                    if (botnetStatus == null && line.isNotEmpty()) {
                        botnetStatus = line // e.g. "Botnet - Nexus"
                    } else if (botnetStatus != null && line.contains("slots")) {
                        botnetStatus = "$botnetStatus ($line)"
                    } else if (botnetStatus != null && line.contains("MAX TIER")) {
                        botnetStatus = "$botnetStatus [MAX]"
                    }
                }
                Section.SOFTWARE -> {
                    if (validSoftwareNames.contains(line)) {
                        currentSoftware = line
                    } else if (currentSoftware != null && line.startsWith("LVL ")) {
                        val level = line.removePrefix("LVL ").trim().toIntOrNull()
                        if (level != null) {
                            softwareLevels[currentSoftware] = level
                        }
                        currentSoftware = null
                    }
                }
                else -> {}
            }
        }

        return StoreScreen(nextDevice, nextNetwork, botnetStatus, softwareLevels, deviceMaxed, networkMaxed)
    }
}

// ----------------------------------------
// BypassScreen
// ----------------------------------------
data class BypassScreen(
    val targetIp: String?,
    val firewall: Int?,
    val encryptor: Int?,
    val playerBypasser: Int?,
    val apexBonus: Int?
)

object BypassScreenParser {
    private val REGEX_FW = Regex("""(?:Firewall|FW)\s*Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val REGEX_ENC = Regex("""(?:Encryptor|ENC)\s*Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val REGEX_USING_BYPASSER = Regex("""Using\s+Bypasser\s+Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val REGEX_APEX_BONUS = Regex("""\(\+(\d+)\)""")
    private val REGEX_SCAN_ROW = Regex("""^(\S+)\s+FW\s*Lv\.?\s*(\d+)(?:\s+ENC\s*Lv\.?\s*(\d+))?""", RegexOption.IGNORE_CASE)

    fun parse(text: String): BypassScreen? {
        val lower = text.lowercase()
        if (!lower.contains("using bypasser") && !lower.contains("// bypass firewall") && !lower.contains("// scan results")) return null

        // Scan list: one row per target ("1.2.3.4 FW Lv.32 ENC Lv.40 >"), masked rows included. Take the IP and
        // its levels from the same row, so a masked row above can't lend its FW/ENC to the next real IP.
        val scanRow = if (lower.contains("// scan results")) {
            text.lineSequence().mapNotNull { REGEX_SCAN_ROW.find(it.trim()) }
                .firstOrNull { !CommonParserUtil.isMaskedIp(it.groupValues[1]) && !it.groupValues[1].startsWith("0.0.") }
        } else null
        val ip: String?
        val firewall: Int?
        val encryptor: Int?
        if (scanRow != null) {
            ip = scanRow.groupValues[1]
            firewall = scanRow.groupValues[2].toIntOrNull()
            encryptor = scanRow.groupValues[3].toIntOrNull() // ENC shows only on an Apex reboot, not by default
        } else {
            ip = CommonParserUtil.extractIp(text)
            firewall = REGEX_FW.find(text)?.groupValues?.get(1)?.toIntOrNull()
            encryptor = REGEX_ENC.find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
        val playerBypasser = REGEX_USING_BYPASSER.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val apexBonus = REGEX_APEX_BONUS.find(text)?.groupValues?.get(1)?.toIntOrNull()

        if (ip == null && firewall == null && playerBypasser == null) return null

        return BypassScreen(ip, firewall, encryptor, playerBypasser, apexBonus)
    }
}

