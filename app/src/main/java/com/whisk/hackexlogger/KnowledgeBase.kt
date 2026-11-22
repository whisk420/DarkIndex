package com.whisk.hackexlogger

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Knowledge Base model (pure Kotlin, JVM-testable). Content lives in [KnowledgeBaseContent], calculators in
 * [KbCalculators]; the Android screens only render these.
 */

/** Where a fact comes from. Shown as a coloured badge next to it. */
enum class KbSource(val badge: String, val colorHex: String, val meaning: String) {
    MEASURED("MEASURED", "#10b981", "Measured in-game or verified to the crypto against real numbers"),
    FORMULA("EXACT FIT", "#38bdf8", "A formula that reproduces every number the guide publishes"),
    GUIDE("GUIDE", "#a78bfa", "Stated by hackex.net/guide, not yet checked in-game"),
    OWNER("OWNER", "#f59e0b", "Confirmed by the DarkIndex owner from play"),
    EMPIRICAL("EMPIRICAL", "#fb923c", "Older empirical data, not re-verified"),
    UNKNOWN("UNKNOWN", "#ef4444", "Not known yet: on the measurement list");
}

data class KbFact(val label: String, val value: String, val source: KbSource, val note: String? = null)

sealed class KbBlock {
    data class Heading(val text: String) : KbBlock()
    data class Para(val text: String, val source: KbSource? = null) : KbBlock()
    data class Bullets(val items: List<String>, val source: KbSource? = null) : KbBlock()
    data class Facts(val facts: List<KbFact>) : KbBlock()
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val source: KbSource,
        val caption: String? = null
    ) : KbBlock()
    data class Tip(val text: String) : KbBlock()
    data class Warning(val text: String) : KbBlock()
    data class Calc(val calculatorId: String) : KbBlock()
    /** Open questions: measurement id (e.g. "M-01") to what's missing. */
    data class Unknowns(val items: List<Pair<String, String>>) : KbBlock()
}

data class KbTopic(
    val id: String,
    val icon: String,
    val title: String,
    val overview: String,
    val blocks: List<KbBlock>,
    val related: List<String> = emptyList()
) {
    /** How many tagged items of each source the topic holds (for the index card tally). */
    fun sourceCounts(): Map<KbSource, Int> {
        val counts = mutableMapOf<KbSource, Int>()
        fun add(s: KbSource?, n: Int = 1) { if (s != null) counts[s] = (counts[s] ?: 0) + n }
        for (b in blocks) when (b) {
            is KbBlock.Facts -> b.facts.forEach { add(it.source) }
            is KbBlock.Table -> add(b.source)
            is KbBlock.Para -> add(b.source)
            is KbBlock.Bullets -> add(b.source)
            is KbBlock.Unknowns -> add(KbSource.UNKNOWN, b.items.size)
            else -> {}
        }
        return counts
    }

    /** Lower-case text of everything in the topic, for search. */
    fun searchText(): String = buildString {
        append(title).append('\n').append(overview).append('\n')
        for (b in blocks) when (b) {
            is KbBlock.Heading -> append(b.text)
            is KbBlock.Para -> append(b.text)
            is KbBlock.Bullets -> b.items.forEach { append(it).append('\n') }
            is KbBlock.Facts -> b.facts.forEach { append(it.label).append(' ').append(it.value).append(' ').append(it.note ?: "").append('\n') }
            is KbBlock.Table -> { append(b.caption ?: ""); append(b.header.joinToString(" ")); b.rows.forEach { append(it.joinToString(" ")).append('\n') } }
            is KbBlock.Tip -> append(b.text)
            is KbBlock.Warning -> append(b.text)
            is KbBlock.Calc -> append(KbCalculators.byId(b.calculatorId)?.title ?: "")
            is KbBlock.Unknowns -> b.items.forEach { append(it.first).append(' ').append(it.second).append('\n') }
        }.also { append('\n') }
    }.lowercase(Locale.ROOT)

    /** A short excerpt around the first match of [query] (for search results), or null. */
    fun matchSnippet(query: String): String? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return null
        val text = searchText()
        val i = text.indexOf(q)
        if (i < 0) return null
        val start = (i - 40).coerceAtLeast(0)
        val end = (i + q.length + 60).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end).replace('\n', ' ').trim() + (if (end < text.length) "…" else "")
    }
}

object KnowledgeBase {
    val topics: List<KbTopic> get() = KnowledgeBaseContent.topics

    fun topic(id: String): KbTopic? = topics.firstOrNull { it.id == id }

    /** Topics whose text contains [query] (case-insensitive); all topics for a blank query. */
    fun search(query: String): List<KbTopic> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return topics
        return topics.filter { it.searchText().contains(q) }
    }
}

// ── Calculators ───────────────────────────────────────────────────────────────────────────────────────────

sealed class CalcInput(val key: String, val label: String) {
    class Number(key: String, label: String, val default: Int, val min: Int, val max: Int) : CalcInput(key, label)
    class Choice(key: String, label: String, val options: List<String>, val defaultIndex: Int) : CalcInput(key, label)
    class Toggle(key: String, label: String, val default: Boolean) : CalcInput(key, label)
}

/** Values entered into a calculator. Numbers are clamped to the input's range. */
class CalcValues(private val inputs: List<CalcInput>, private val raw: Map<String, Any?>) {
    fun int(key: String): Int {
        val input = inputs.first { it.key == key } as CalcInput.Number
        val v = (raw[key] as? Int) ?: input.default
        return v.coerceIn(input.min, input.max)
    }
    fun choice(key: String): Int {
        val input = inputs.first { it.key == key } as CalcInput.Choice
        return ((raw[key] as? Int) ?: input.defaultIndex).coerceIn(0, input.options.size - 1)
    }
    fun choiceLabel(key: String): String = (inputs.first { it.key == key } as CalcInput.Choice).options[choice(key)]
    fun bool(key: String): Boolean {
        val input = inputs.first { it.key == key } as CalcInput.Toggle
        return (raw[key] as? Boolean) ?: input.default
    }
}

class KbCalculator(
    val id: String,
    val title: String,
    val inputs: List<CalcInput>,
    val compute: (CalcValues) -> String
) {
    fun run(raw: Map<String, Any?> = emptyMap()): String = compute(CalcValues(inputs, raw))
}

/** Your own numbers, used as calculator defaults. Anything unknown falls back to a sensible value. */
data class KbPlayerDefaults(
    val level: Int? = null,
    val deviceLevel: Int? = null,
    val networkLevel: Int? = null,
    val spamLevel: Int? = null,
    val bypasserLevel: Int? = null,
    val crackerLevel: Int? = null,
    val antivirusLevel: Int? = null,
    val proxyLevel: Int? = null
) {
    companion object {
        /** Reads the stored player profile (Store levels are NEXT levels, so subtract 1). */
        fun fromProfile(profile: PlayerProfile, currentDevice: String?, currentNetwork: String?): KbPlayerDefaults {
            fun installed(app: String) = profile.softwareLevels[app]?.let { (it - 1).coerceAtLeast(1) }
            return KbPlayerDefaults(
                level = profile.playerLevel,
                deviceLevel = HardwareCatalog.parse(currentDevice)?.level,
                networkLevel = HardwareCatalog.parse(currentNetwork)?.level,
                spamLevel = installed("Spam"),
                bypasserLevel = installed("Bypasser"),
                crackerLevel = installed("Password Cracker"),
                antivirusLevel = installed("Antivirus"),
                proxyLevel = installed("Proxy")
            )
        }
    }
}

object KbCalculators {
    const val SPAM = "spam"
    const val BOTNET = "botnet"
    const val UPLOAD = "upload"
    const val KEYGEN = "keygen"
    const val PROXY = "proxy"
    const val ANTIVIRUS = "antivirus"
    const val HACK = "hack"
    const val SCORE = "score"
    const val SPEEDUP = "speedup"

    /** Player defaults used when building calculators; the Activity sets this from the stored profile. */
    @Volatile var defaults: KbPlayerDefaults = KbPlayerDefaults()

    private fun n(v: Int): String = String.format(Locale.US, "%,d", v)
    private fun n(v: Long): String = String.format(Locale.US, "%,d", v)
    private fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v * 100)

    fun duration(seconds: Double): String = NetworkSpeedUtils.formatUploadDuration(seconds.roundToInt().coerceAtLeast(0))

    private val DEVICE_OPTIONS get() = HardwareCatalog.STANDARD_DEVICES.mapIndexed { i, d -> "$d (Lv ${i + 1})" }
    private val NETWORK_OPTIONS get() = HardwareCatalog.STANDARD_NETWORKS.mapIndexed { i, d -> "$d (${HardwareCatalog.NETWORK_BOOST[i]}×)" }
    private val APP_OPTIONS get() = GameFormulas.App.values().map { it.displayName }
    private val SUBSCRIPTIONS = listOf("None", "Hacker Pass (−25% fee)", "Elite Pass (−50% fee)")

    fun byId(id: String): KbCalculator? = all().firstOrNull { it.id == id }

    fun all(): List<KbCalculator> {
        val d = defaults
        val myLevel = d.level ?: 30
        return listOf(
            KbCalculator(
                SPAM, "Spam income",
                listOf(
                    CalcInput.Number("spam", "Spam level", d.spamLevel ?: 15, 1, 60),
                    CalcInput.Number("me", "Your level", myLevel, 1, 99),
                    CalcInput.Number("target", "Victim level", myLevel, 1, 99),
                    CalcInput.Number("days", "Days since upload", 0, 0, 400),
                    CalcInput.Toggle("scythe", "Scythe device (half decay)", false)
                )
            ) { v -> spamIncomeText(v.int("spam"), v.int("me"), v.int("target"), v.int("days"), v.bool("scythe")) },

            KbCalculator(
                BOTNET, "Botnet net income",
                listOf(
                    CalcInput.Number("active", "Active Spam deployments", 8, 0, 69),
                    CalcInput.Number("rate", "Average Crypto/hr per Spam", 62, 0, 100),
                    CalcInput.Choice("sub", "Subscription", SUBSCRIPTIONS, 0)
                )
            ) { v -> botnetText(v.int("active"), v.int("rate"), v.choice("sub")) },

            KbCalculator(
                UPLOAD, "Upload time",
                listOf(
                    CalcInput.Number("virus", "Virus level", d.spamLevel ?: 15, 1, 60),
                    CalcInput.Choice("device", "Victim device", DEVICE_OPTIONS, 0),
                    CalcInput.Choice("network", "Your network", NETWORK_OPTIONS, ((d.networkLevel ?: 1) - 1).coerceIn(0, 10)),
                    CalcInput.Number("procs", "Processes running (incl. this one)", 1, 1, 20),
                    CalcInput.Toggle("scythe", "Harvest Node network (−50% upload)", false)
                )
            ) { v -> uploadText(v.int("virus"), v.choice("device") + 1, v.choice("network") + 1, v.int("procs"), v.bool("scythe")) },

            KbCalculator(
                KEYGEN, "Store price & Keygen fee",
                listOf(
                    CalcInput.Choice("app", "Software", APP_OPTIONS, 0),
                    CalcInput.Number("from", "Your installed level", 15, 1, 60),
                    CalcInput.Number("to", "Level to unlock / buy", 20, 2, 60)
                )
            ) { v -> keygenText(GameFormulas.App.values()[v.choice("app")], v.int("from"), v.int("to")) },

            KbCalculator(
                PROXY, "Proxy activation",
                listOf(
                    CalcInput.Number("level", "Proxy level", d.proxyLevel ?: 10, 1, 60),
                    CalcInput.Choice("hours", "Duration", GameFormulas.PROXY_DURATIONS.map { "${it.first} h" }, 3),
                    CalcInput.Number("count", "Activations stacked", 1, 1, 30)
                )
            ) { v -> proxyText(v.int("level"), GameFormulas.PROXY_DURATIONS[v.choice("hours")].first, v.int("count")) },

            KbCalculator(
                ANTIVIRUS, "Antivirus odds & scan time",
                listOf(
                    CalcInput.Number("av", "Your Antivirus level", d.antivirusLevel ?: 15, 1, 60),
                    CalcInput.Number("virus", "Virus level", 15, 1, 60),
                    CalcInput.Toggle("scythe", "Virus owner on Harvest Node (+2)", false),
                    CalcInput.Number("scans", "Number of scans", 1, 1, 20),
                    CalcInput.Choice("device", "Your device", DEVICE_OPTIONS, ((d.deviceLevel ?: 1) - 1).coerceIn(0, 11)),
                    CalcInput.Number("hacks", "Bypasses/cracks running alongside", 0, 0, 20)
                )
            ) { v -> antivirusText(v.int("av"), v.int("virus") + if (v.bool("scythe")) 2 else 0, v.int("scans"), v.choice("device") + 1, v.int("hacks")) },

            KbCalculator(
                HACK, "Bypass / crack odds",
                listOf(
                    CalcInput.Number("off", "Your Bypasser or Cracker level", d.bypasserLevel ?: 15, 1, 60),
                    CalcInput.Number("def", "Their Firewall or Encryptor level", 15, 1, 60),
                    CalcInput.Number("rootkit", "Rootkit debuff on them (0–3)", 0, 0, 3),
                    CalcInput.Toggle("apex", "You run an Apex device (+3)", false),
                    CalcInput.Toggle("cipher", "They run a Cipher device (+3)", false)
                )
            ) { v -> hackText(v.int("off"), v.int("def"), v.int("rootkit"), v.bool("apex"), v.bool("cipher")) },

            KbCalculator(
                SCORE, "Score & reputation",
                listOf(
                    CalcInput.Number("level", "Level", myLevel, 1, 99),
                    CalcInput.Number("rep", "Reputation", 5_000, 0, 10_000_000),
                    CalcInput.Number("goal", "Target score (0 = skip)", 0, 0, 100_000_000)
                )
            ) { v -> scoreText(v.int("level"), v.int("rep"), v.int("goal").toLong()) },

            KbCalculator(
                SPEEDUP, "Process speed-ups",
                listOf(
                    CalcInput.Number("minutes", "Time left (minutes)", 60, 1, 100_000),
                    CalcInput.Number("ads", "Ads watched (0–4)", 0, 0, 4),
                    CalcInput.Number("oc", "Overclocks used", 0, 0, 10_000),
                    CalcInput.Number("procs", "Processes in the pool", 1, 1, 20)
                )
            ) { v -> speedupText(v.int("minutes"), v.int("ads"), v.int("oc"), v.int("procs")) },
        )
    }

    fun spamIncomeText(spam: Int, me: Int, target: Int, days: Int, scythe: Boolean): String {
        val gap = target - me
        val mult = GameFormulas.spamLevelGapMultiplier(gap)
        val fresh = GameFormulas.spamIncomePerHour(spam, gap)
        val now = GameFormulas.spamIncomePerHour(spam, gap, days.toDouble(), scythe)
        val decay = GameFormulas.spamDecayFactor(days.toDouble(), scythe)
        val capLevel = GameFormulas.spamCapLevel(gap)
        val gapText = when {
            gap > 0 -> "Victim is $gap above you: ×${"%.2f".format(Locale.US, mult)} bonus"
            gap < 0 -> "Victim is ${-gap} below you: ×${"%.2f".format(Locale.US, mult)} penalty"
            else -> "Same level: no bonus or penalty"
        }
        return buildString {
            appendLine(gapText)
            appendLine("Fresh upload: ${fresh}/hr (${n(fresh * 24)}/day)")
            if (days > 21) appendLine("After $days days: ${now}/hr (${pct(decay)} of fresh, ${n(now * 24)}/day)")
            else appendLine("Day $days: full rate until day ${if (scythe) 42 else 21}")
            append(
                if (capLevel != null) "Cheapest Spam that hits 100/hr here: Lv $capLevel"
                else "100/hr isn't reachable on this victim (up to Lv 60)"
            )
        }
    }

    fun botnetText(active: Int, rate: Int, subscription: Int): String {
        val discount = when (subscription) { 1 -> 0.25; 2 -> 0.50; else -> 0.0 }
        val fee = GameFormulas.spamFeeRate(active, discount)
        val gross = active * rate
        val feeHr = (gross * fee).roundToInt()
        val net = gross - feeHr
        val tier = GameFormulas.BOTNET_TIERS.firstOrNull { it.slots >= active }
        return buildString {
            appendLine("Fee rate: ${pct(fee)} (0.5% per Spam beyond 2, max 30%)")
            appendLine("Gross ${n(gross)}/hr − fee ${n(feeHr)}/hr = ${n(net)}/hr")
            appendLine("Net per day: ${n(net * 24)}")
            append(
                if (tier != null) "Smallest botnet with $active slots: ${tier.name} (${tier.slots})"
                else "More than 64 slots needs crew-server RAM (+1 slot per level, max +5)"
            )
        }
    }

    fun uploadText(virus: Int, deviceLevel: Int, networkLevel: Int, procs: Int, harvestNode: Boolean): String {
        val device = HardwareCatalog.STANDARD_DEVICES[deviceLevel - 1]
        val network = HardwareCatalog.STANDARD_NETWORKS[networkLevel - 1]
        val solo = NetworkSpeedUtils.getUploadSecondsForLevel(device, network, virus).toDouble() * (if (harvestNode) 0.5 else 1.0)
        val withPool = solo * GameFormulas.concurrencyMultiplier(procs)
        return buildString {
            appendLine("Victim upload-defence tier: ${HardwareCatalog.uploadDefenceTier(device) ?: "?"} (Raider I–III are all tier 0)")
            appendLine("Alone: ~${duration(solo)}")
            if (procs > 1) appendLine("With $procs processes running: ~${duration(withPool)} (+75% per extra process)")
            append("Model: Lv 20 benchmark × (level/20)^2.25 ÷ network boost (empirical, being re-measured)")
        }
    }

    fun keygenText(app: GameFormulas.App, from: Int, to: Int): String {
        val price = GameFormulas.storePrice(app, to) ?: return "No price for level $to"
        val fee = GameFormulas.keygenFee(app, from, to)
        val chainedStore = ((from + 1)..to).sumOf { GameFormulas.storePrice(app, it) ?: 0 }
        val chainedKeys = ((from + 1)..to).sumOf { GameFormulas.keygenFee(app, it - 1, it) ?: 0 }
        val xp = (price * GameFormulas.SOFTWARE_XP_PER_CRYPTO).roundToInt()
        return buildString {
            appendLine("${app.displayName} Lv $to Store price: ${n(price)}${if (to < GameFormulas.STORE_PRICE_RELIABLE_FROM) " (approx. below Lv 15)" else ""}")
            if (fee == null) {
                appendLine("No Keygen key: you can't key a level at or below yours")
            } else {
                val ratio = GameFormulas.keygenFeeRatio(to - from)!!
                appendLine("Keygen fee for a ${to - from}-level jump: ${n(fee)} (${pct(ratio)} of the Store price)")
                if (to - from > 1) {
                    appendLine("Stepping up one level at a time: keys ${n(chainedKeys)} vs Store ${n(chainedStore)}")
                }
            }
            append("XP for unlocking Lv $to: ~${n(xp)} (≈0.17 XP per Store crypto)")
        }
    }

    fun proxyText(level: Int, hours: Int, count: Int): String {
        val cost = GameFormulas.proxyActivationCost(level, hours) ?: return "Unknown duration"
        val perHour = cost.toDouble() / hours
        return buildString {
            appendLine("One ${hours} h activation: ${n(cost)} Crypto (${"%.1f".format(Locale.US, perHour)}/hr)")
            if (count > 1) appendLine("$count stacked: ${n(cost * count)} for ${hours * count} h")
            appendLine("Your IP shows as ${GameFormulas.proxyMaskExample(level)}")
            appendLine(if (level >= 20) "Stealth toggle unlocked (hidden from random scans while reserve lasts)" else "Stealth unlocks at Proxy Lv 20")
            append("All durations: " + GameFormulas.PROXY_DURATIONS.joinToString(", ") { "${it.first}h ${n(GameFormulas.proxyActivationCost(level, it.first)!!)}" })
        }
    }

    fun antivirusText(av: Int, virus: Int, scans: Int, deviceLevel: Int, hacks: Int): String {
        val chance = GameFormulas.avRemovalChance(av, virus)
        val afterN = GameFormulas.avRemovalAfterScans(chance, scans)
        val solo = GameFormulas.avScanMinutes(deviceLevel)
        val pooled = solo * GameFormulas.concurrencyMultiplier(hacks + 1)
        return buildString {
            appendLine("Removal chance per scan: $chance% (AV ${if (av - virus >= 0) "+" else ""}${av - virus} vs virus)")
            if (scans > 1) appendLine("Removed within $scans scans: ${pct(afterN)}")
            appendLine("Scan time on ${HardwareCatalog.STANDARD_DEVICES[deviceLevel - 1]}: ~${duration(solo * 60)}")
            if (hacks > 0) appendLine("With $hacks hack process(es) running: ~${duration(pooled * 60)}")
            append("Rep restored by a removal: 90% of what that virus cost you")
        }
    }

    fun hackText(off: Int, def: Int, rootkit: Int, apex: Boolean, cipher: Boolean): String {
        val effOff = off + if (apex) 3 else 0
        val effDef = (def - rootkit + if (cipher) 3 else 0).coerceAtLeast(1)
        val chance = GameFormulas.hackSuccessChance(effOff, effDef)
        val block = GameFormulas.hardeningBlockChance(effDef)
        val gap = effOff - effDef
        val repNote = when {
            gap >= 10 -> "no rep (gap +10 or more)"
            gap >= 6 -> "reduced rep (${(GameFormulas.hackRepGapFactor(gap) * 100).roundToInt()}% of full)"
            else -> "full rep"
        }
        return buildString {
            appendLine("Effective: you $effOff vs them $effDef (gap ${if (gap >= 0) "+" else ""}$gap)")
            appendLine("Success chance: $chance% (empirical table)")
            appendLine("Backdoor/Exploit Kit block chance: ${if (cipher) 100 else block}%")
            append("Rep on success: $repNote")
        }
    }

    fun scoreText(level: Int, rep: Int, goal: Long): String {
        val score = GameFormulas.score(level, rep)
        return buildString {
            appendLine("Score: ${n(score)} = floor($level × ${n(rep)}^0.75)")
            appendLine("Next level adds ~${n(GameFormulas.score(level + 1, rep) - score)}; +10% rep adds ~${n(GameFormulas.score(level, (rep * 1.1).roundToInt()) - score)}")
            if (goal > 0) {
                val need = GameFormulas.repForScore(level, goal)
                append(if (need <= rep) "Already above ${n(goal)}" else "Rep needed for ${n(goal)} at Lv $level: ${n(need)} (+${n(need - rep)})")
            } else {
                append("Rep floor: you can't drop below 50% of your peak rep")
            }
        }
    }

    fun speedupText(minutes: Int, ads: Int, oc: Int, procs: Int): String {
        val base = minutes * 60.0
        val afterAds = base * GameFormulas.remainingAfterAds(ads)
        val afterOc = (afterAds - oc * GameFormulas.OC_SECONDS).coerceAtLeast(0.0)
        val ocToFinish = kotlin.math.ceil(afterAds / GameFormulas.OC_SECONDS).toInt()
        return buildString {
            if (procs > 1) appendLine("Pool of $procs: every process runs ×${"%.2f".format(Locale.US, GameFormulas.concurrencyMultiplier(procs))} slower than alone")
            appendLine("After $ads ad(s): ~${duration(afterAds)} (${pct(1 - GameFormulas.remainingAfterAds(ads))} cut)")
            appendLine("After $oc Overclock(s): ~${duration(afterOc)}")
            append("Overclocks to finish now: $ocToFinish (90 s each)")
        }
    }
}
