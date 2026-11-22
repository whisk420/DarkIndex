package com.whisk.hackexlogger

import kotlin.math.abs

/**
 * Dashboard search (pure, JVM-tested). Query forms, case-insensitive:
 * - `lvl 30`, `lv30`, `level 30`: player level 30. `lvl 28-32`: a range.
 * - `sw 20`, `software 20`, `all 20`: targets to download Lv20 software from, best first: the most apps at
 *   exactly Lv20 (Firewall not counted: it's the wall you have to get through, not loot), then the lowest
 *   Firewall, then the lowest Encryptor, then the most apps one level off.
 * - `<app> 20` (`firewall 20`, `fw 20`, `spam lv20`, `crack 20` …): that app at Lv20; if no target has it,
 *   ±1, then ±2. Best first: closest level, then lowest Firewall.
 * - anything else: text match on IP, username, clan, hardware, wallet, app name, or an exact player level.
 */
object TargetSearch {

    /** Apps a target can hold that you can download from it (Keygen is device-bound and never downloadable). */
    val DOWNLOADABLE_APPS = listOf(
        "Firewall", "Password Encryptor", "Antivirus", "Proxy", "Trace",
        "Bypasser", "Password Cracker", "Spam", "Siphon", "Rootkit"
    )

    /** Apps counted by the all-software search: everything except Firewall. */
    val LOOT_APPS = DOWNLOADABLE_APPS - "Firewall"

    private val ALIASES = mapOf(
        "fw" to "Firewall", "firewall" to "Firewall",
        "enc" to "Password Encryptor", "encryptor" to "Password Encryptor",
        "av" to "Antivirus", "antivirus" to "Antivirus",
        "proxy" to "Proxy", "trace" to "Trace",
        "bp" to "Bypasser", "bypass" to "Bypasser", "bypasser" to "Bypasser",
        "pc" to "Password Cracker", "crack" to "Password Cracker", "cracker" to "Password Cracker",
        "spam" to "Spam", "siphon" to "Siphon", "rootkit" to "Rootkit", "rk" to "Rootkit",
    )

    sealed class Query {
        data class PlayerLevel(val min: Int, val max: Int) : Query()
        data class AllSoftware(val level: Int) : Query()
        data class App(val app: String?, val namePart: String, val level: Int) : Query()
        data class Text(val text: String) : Query()
    }

    private val LEVEL_QUERY = Regex("""^(?:lvl|lv|level)\.?\s*(\d+)(?:\s*-\s*(\d+))?$""")
    private val ALL_SOFTWARE_QUERY = Regex("""^(?:sw|software|all)\s*(?:lv\.?|lvl\.?)?\s*(\d+)$""")
    private val APP_QUERY = Regex("""^([a-z][a-z ]*?)\s*(?:lv\.?|lvl\.?)?\s*(\d+)$""")

    fun parse(raw: String): Query {
        val q = raw.trim().lowercase().replace(Regex("""\s+"""), " ")
        LEVEL_QUERY.find(q)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toIntOrNull() ?: a
            return Query.PlayerLevel(minOf(a, b), maxOf(a, b))
        }
        ALL_SOFTWARE_QUERY.find(q)?.let { return Query.AllSoftware(it.groupValues[1].toInt()) }
        APP_QUERY.find(q)?.let { m ->
            val name = m.groupValues[1].trim()
            val app = ALIASES[name] ?: DOWNLOADABLE_APPS.firstOrNull { it.lowercase() == name }
            if (app != null || (name.length >= 3 && DOWNLOADABLE_APPS.any { it.lowercase().contains(name) })) {
                return Query.App(app, name, m.groupValues[2].toInt())
            }
        }
        return Query.Text(q)
    }

    /** Records matching [raw], in display order (best first). */
    fun search(records: List<TargetRecord>, raw: String): List<TargetRecord> = when (val q = parse(raw)) {
        is Query.PlayerLevel -> records
            .filter { r -> r.level?.let { it in q.min..q.max } == true }
            .sortedWith(compareBy<TargetRecord> { it.level }.then(byWalls))
        is Query.AllSoftware -> allSoftware(records, q.level)
        is Query.App -> app(records, q)
        is Query.Text -> text(records, q.text)
    }

    /** Lower Firewall first (quicker to get in), then lower Encryptor; unknown walls last. */
    private val byWalls: Comparator<TargetRecord> =
        compareBy<TargetRecord>({ firewallOf(it) ?: Int.MAX_VALUE }, { encryptorOf(it) ?: Int.MAX_VALUE })

    fun firewallOf(r: TargetRecord): Int? = r.firewall ?: r.downloads["Firewall"]?.level
    private fun encryptorOf(r: TargetRecord): Int? = r.encryptor ?: r.downloads["Password Encryptor"]?.level

    /** How many [LOOT_APPS] the target holds at exactly [level]. */
    fun lootCount(r: TargetRecord, level: Int): Int = LOOT_APPS.count { r.downloads[it]?.level == level }

    private fun allSoftware(records: List<TargetRecord>, level: Int): List<TargetRecord> {
        fun near(r: TargetRecord) = LOOT_APPS.count { r.downloads[it]?.level?.let { l -> abs(l - level) == 1 } == true }
        return records
            .filter { lootCount(it, level) > 0 }
            .sortedWith(compareByDescending<TargetRecord> { lootCount(it, level) }.then(byWalls).thenByDescending { near(it) })
    }

    private fun app(records: List<TargetRecord>, q: Query.App): List<TargetRecord> {
        fun levelOf(r: TargetRecord): Int? {
            if (q.app != null) return r.downloads[q.app]?.level ?: if (q.app == "Firewall") r.firewall else null
            return r.downloads.entries.filter { it.key.lowercase().contains(q.namePart) }.minByOrNull { abs(it.value.level - q.level) }?.value?.level
        }
        for (spread in 0..2) {
            val hits = records.filter { r -> levelOf(r)?.let { abs(it - q.level) <= spread } == true }
            if (hits.isNotEmpty()) return hits.sortedWith(compareBy<TargetRecord> { abs(levelOf(it)!! - q.level) }.then(byWalls))
        }
        return emptyList()
    }

    private fun text(records: List<TargetRecord>, query: String): List<TargetRecord> {
        val bare = query.replace("[", "").replace("]", "")
        return records.filter { r ->
            r.ip.lowercase().contains(query) ||
                r.username?.lowercase()?.contains(query) == true ||
                r.clan?.lowercase()?.contains(bare) == true ||
                r.level?.toString() == query ||
                r.hardware?.lowercase()?.contains(query) == true ||
                r.wallets.any { it.lowercase().contains(query) } ||
                r.downloads.keys.any { it.lowercase().contains(query) }
        }.sortedBy { it.ip }
    }
}
