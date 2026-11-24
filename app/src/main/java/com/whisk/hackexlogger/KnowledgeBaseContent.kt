package com.whisk.hackexlogger

import com.whisk.hackexlogger.KbSource.EMPIRICAL
import com.whisk.hackexlogger.KbSource.CALCULATED
import com.whisk.hackexlogger.KbSource.GUIDE
import com.whisk.hackexlogger.KbSource.MEASURED
import com.whisk.hackexlogger.KbSource.CONFIRMED
import com.whisk.hackexlogger.KbSource.UNKNOWN
import java.util.Locale

/**
 * Knowledge Base content. Sources: hackex.net/guide (read in full 2026-11-21), developer answers and verified
 * measurements (docs/game.md), and exact-fit formulas ([GameFormulas]). Open questions carry the measurement ids
 * (Q-xx) listed in docs/knowledge-base-prep.md. Keep facts in sync with GameFormulas: tables below are generated from it
 * where a formula exists.
 */
object KnowledgeBaseContent {

    private fun h(text: String) = KbBlock.Heading(text)
    private fun p(text: String, source: KbSource? = null) = KbBlock.Para(text, source)
    private fun bullets(source: KbSource?, vararg items: String) = KbBlock.Bullets(items.toList(), source)
    private fun facts(vararg f: KbFact) = KbBlock.Facts(f.toList())
    private fun f(label: String, value: String, source: KbSource, note: String? = null) = KbFact(label, value, source, note)
    private fun tip(text: String) = KbBlock.Tip(text)
    private fun warn(text: String) = KbBlock.Warning(text)
    private fun calc(id: String) = KbBlock.Calc(id)
    private fun unknowns(vararg items: Pair<String, String>) = KbBlock.Unknowns(items.toList())
    private fun table(source: KbSource, header: List<String>, rows: List<List<String>>, caption: String? = null) =
        KbBlock.Table(header, rows, source, caption)

    private fun n(v: Int) = String.format(Locale.US, "%,d", v)
    /** 45000 → "45K", 1120000 → "1.12M" (exact, trailing zeros dropped). */
    private fun compact(v: Long): String = when {
        v >= 1_000_000 -> java.math.BigDecimal(v).divide(java.math.BigDecimal(1_000_000)).stripTrailingZeros().toPlainString() + "M"
        v >= 1_000 -> java.math.BigDecimal(v).divide(java.math.BigDecimal(1_000)).stripTrailingZeros().toPlainString() + "K"
        else -> v.toString()
    }

    val topics: List<KbTopic> by lazy {
        listOf(
            start(), spam(), botnet(), siphon(), rootkit(), virusRules(), transfers(), hacking(), processes(),
            antivirus(), consumables(), proxy(), keygen(), software(), wallet(), logs(), scanning(), reputation(),
            leveling(), daily(), hardware(), crews(), reboot(), premium(), darkIndex()
        )
    }

    // ── Topics ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun start() = KbTopic(
        "start", "🚀", "Getting started & the core loop",
        "How a hack works from scan to payout, what you start with, and where XP and rep actually come from.",
        listOf(
            h("What you start with"),
            facts(
                f("Device", "Raider (1.0×)", CONFIRMED),
                f("Network", "DSL (1.0×)", CONFIRMED),
                f("Wallet", "3,000 Crypto", CONFIRMED),
                f("Overclocks", "50", CONFIRMED),
                f("Credits", "100", CONFIRMED),
                f("Software", "Firewall, Bypasser, Password Cracker, Password Encryptor at Lv 1", CONFIRMED),
            ),
            h("The loop"),
            bullets(
                GUIDE,
                "1. Scan: \"Scan Random\" lists up to 10 targets near your level, more with a crew-server Uplink (see Scanning).",
                "2. Bypass the Firewall with your Bypasser (a timed process, or an instant Backdoor).",
                "3. Crack the Password Encryptor with your Password Cracker (or an instant Exploit Kit). This unlocks the hot wallet.",
                "4. Pay out: steal hot-wallet Crypto, download software to unlock with Keygen, and/or upload viruses (Spam, Siphon, Rootkit).",
                "5. Stash: move Crypto from your hot wallet (stealable) to cold storage (safe, capped by level).",
            ),
            p("A bypass alone already lets you open the device, download software and upload viruses. The crack is only needed for the wallet.", GUIDE),
            h("Where progress comes from"),
            facts(
                f("XP", "Only from spending (upgrades, Keygen unlocks), missions and login streaks. Never from hacking itself", CONFIRMED),
                f("Reputation", "From hacking, defending and Trace detections; lost to viruses", CONFIRMED),
                f("Crypto", "Theft, Spam, Siphon, missions, streaks, ads", CONFIRMED),
                f("Leaderboard score", "floor(Level × Rep^0.75)", MEASURED, "Verified on 6 profiles"),
            ),
        ),
        related = listOf("hacking", "keygen", "spam", "wallet")
    )

    private fun spam() = KbTopic(
        "spam", "💸", "Spam virus & income",
        "Your main passive income: up to 100 Crypto/hr per victim. Set at upload by the victim's level vs the Spam's (your own level doesn't matter); an over-levelled Spam earns less. Decays after 21 days.",
        listOf(
            h("The basics"),
            facts(
                f("Cap", "100 Crypto/hr per victim", CONFIRMED),
                f("Accrues", "Continuously, collect any time", CONFIRMED),
                f("Upload level", "You choose any level up to your installed Spam; lower uploads faster", CONFIRMED),
                f("Locked level", "A deployment earns at the level it was uploaded forever; uninstall and re-upload to upgrade", CONFIRMED),
                f("Locked income", "The rate is fixed at upload from the Spam level and both players' levels then; levelling up later never changes it", CONFIRMED),
                f("Slots", "Limited by your botnet (2 free, up to 64) plus crew-server RAM", CONFIRMED),
                f("Rep for the upload", "Flat 5, once per victim per virus type per 24 h", CONFIRMED),
            ),
            h("Earnings by level"),
            p(
                "Measured in-game (2026-11-22, 17 fresh uploads; victim Lv → Spam Lv: /hr). Victim 43: Lv1 18, Lv2 23, Lv3 27, Lv7 47, Lv11 69. Victim 33: Lv7 47, Lv13 81, Lv20 100. Victim 28: Lv1 18, Lv2 23, Lv6 42. Victim 7: Lv13 38, Lv20 30. Victim 19: Lv10 58. Victim 2: Lv1 13, Lv3 18, Lv7 24, Lv11 26.",
                MEASURED
            ),
            p(
                "What sets the rate is the victim's level compared with the Spam's level. Your own level plays no part, despite what the guide implies. A victim 11+ levels above the Spam pays the full bonus. A Spam above the victim's level loses 5% per level, so an over-levelled Spam earns less: Lv 20 on a Lv 7 victim pays 30/hr, Lv 13 pays 38.",
                MEASURED
            ),
            p(
                "Model: full-bonus rate = 13.9 + 4.3·L + 0.0645·L². Divide by ${"%.3f".format(Locale.US, GameFormulas.SPAM_MAX_BONUS)} for a victim at the Spam's own level, then +4% per level the victim is above the Spam (up to the full bonus, about 12 levels) or −5% per level it is below. Rounded, capped at 100. It reproduces all 17 uploads exactly. The guide's own curve has the wrong shape.",
                CALCULATED
            ),
            table(
                CALCULATED, listOf("Spam Lv", "Victim 2", "Victim 10", "Victim 20", "Victim 30", "Victim 40+"),
                listOf(1, 3, 5, 7, 9, 11, 13, 15, 16, 17, 20).map { lvl ->
                    listOf("Lv $lvl") + listOf(2, 10, 20, 30, 40).map { GameFormulas.spamIncomePerHour(lvl, it).toString() }
                },
                "Crypto/hr for a fresh upload. The rate is fixed at upload. Note the low-victim columns fall at the bottom."
            ),
            tip("Upload Spam Lv 16 on victims Lv 28 and up: it pays 99/hr and uploads about 13% faster than Lv 17. Lv ${GameFormulas.spamBestCap().first} is the lowest level that reaches the full 100/hr (on victims Lv ${GameFormulas.spamBestCap().second}+), for 1 Crypto/hr more. Your own level doesn't matter."),
            tip("On low victims, don't upload your highest Spam: it earns less and takes longer. A Lv 7 victim pays most with Lv ${GameFormulas.spamBestLevel(7, 30)} (${GameFormulas.spamIncomePerHour(GameFormulas.spamBestLevel(7, 30), 7)}/hr); a Lv 2 victim with Lv ${GameFormulas.spamBestLevel(2, 30)} (${GameFormulas.spamIncomePerHour(GameFormulas.spamBestLevel(2, 30), 2)}/hr). The card's ⏱ Spam chip and the calculator below show the best level for each target."),
            calc(KbCalculators.SPAM),
            h("Decay"),
            p("Every deployment has a hidden age clock from its upload. Full rate for days 1–21, then a straight line down to 10% at day 100, which is the floor. Rate = 100% − (day − 21) × 90/79.", GUIDE),
            table(
                GUIDE, listOf("Day", "Income %"),
                listOf(1, 21, 30, 40, 50, 60, 70, 80, 90, 100, 150).map { d ->
                    listOf(d.toString(), "${(GameFormulas.spamDecayFactor(d.toDouble()) * 100).toInt()}%")
                },
                "Guide: 1–21 100%, 30 90%, 50 67%, 70 44%, 100+ 10%"
            ),
            bullets(
                GUIDE,
                "Decay is per deployment, not per victim: re-uploading after an AV wipe or a manual swap starts a fresh 21-day window.",
                "Scythe device (Slow Burn): your Spam decays half as fast.",
                "A full botnet of fresh deployments earns several times what the same botnet earns three months later.",
            ),
            h("Swapping an old deployment"),
            bullets(
                GUIDE,
                "From your own Apps page: uninstall instantly with no access check. Right for dropping a deployment.",
                "From the victim's device: open their Apps → UPLOAD VIRUS → UNINSTALL → CONFIRM, then upload the new level in the same visit. If their device page loads, your bypass is still valid.",
                "Never uninstall from your Apps page to replace: if their Firewall rose or they scrambled their IP, you lose a working deployment and can't upload the new one.",
                "Self-recall gives the victim 100% of their rep back (no scar). You get no new upload rep inside the 24 h window.",
            ),
            tip("Every few Spam levels, sweep your biggest earners and swap deployments that are 3+ levels below your current Spam."),
            h("Fees"),
            p("Collections lose 0.5% per active Spam beyond 2, up to 30% at 62+. See Botnet.", GUIDE),
            unknowns(
                "Q-01" to "Is the fee on collection exactly 0.5% per Spam?",
            ),
        ),
        related = listOf("botnet", "transfers", "virusRules", "siphon")
    )

    private fun botnet() = KbTopic(
        "botnet", "🕸", "Botnet & Spam fees",
        "Botnet tiers set how many Spam viruses you can run at once. Every Spam beyond 2 adds 0.5% to a fee on all collections, up to 30%.",
        listOf(
            h("Tiers"),
            table(
                GUIDE, listOf("Tier", "Slots", "Cost", "Fee when full"),
                GameFormulas.BOTNET_TIERS.map { t ->
                    listOf(t.name, t.slots.toString(), t.cost?.let { n(it) + (t.levelReq?.let { r -> " (Lv $r)" } ?: "") } ?: "free",
                        String.format(Locale.US, "%.1f%%", GameFormulas.spamFeeRate(t.slots) * 100))
                }
            ),
            p("The Store shows your tier as e.g. \"Botnet - Nexus / 64 slots / MAX TIER\".", CONFIRMED),
            h("The scaling fee"),
            facts(
                f("Fee rate", "0.5% × (active Spam − 2), max 30%", GUIDE),
                f("Counted by", "Active Spam, not tier size: empty slots cost nothing", GUIDE),
                f("When", "Deducted on collection", GUIDE),
                f("Hacker Pass", "−25% fee", GUIDE),
                f("Elite Pass", "−50% fee", GUIDE),
            ),
            table(
                CALCULATED, listOf("Tier", "Gross/hr", "Fee/hr", "Net/hr", "Net/day"),
                GameFormulas.BOTNET_TIERS.map { t ->
                    val gross = t.slots * GameFormulas.SPAM_CAP_PER_HOUR
                    val fee = Math.round(gross * GameFormulas.spamFeeRate(t.slots)).toInt()
                    listOf(t.name, n(gross), n(fee), n(gross - fee), n((gross - fee) * 24))
                },
                "Every slot at the 100/hr cap, day 1. (The guide's own example uses 62/hr, its too-low Lv 15 figure; the fee maths matches it.)"
            ),
            tip("Going from 44 to 64 Spam raises the fee from 21% to 30% on everything, so the last 20 slots add far less than 20 × your rate."),
            calc(KbCalculators.BOTNET),
            h("Crew-server RAM"),
            p("Each RAM level adds 1 Spam slot (max +5) on top of your botnet. Spam in those slots pauses if the server goes offline.", GUIDE),
        ),
        related = listOf("spam", "crews")
    )

    private fun siphon() = KbTopic(
        "siphon", "🩸", "Siphon virus",
        "Skims a percentage of Crypto flowing into a victim's hot wallet: 1% at Lv 1, +0.25% per level, 6% at most. Never decays; up to 700 deployments; combined cap 30% per victim.",
        listOf(
            facts(
                f("Rate at Lv 1", "1% of each incoming amount", MEASURED),
                f("Rate per level", "0.75% + 0.25% × level up to Lv 20 (5.75%)", MEASURED, "All 122 rows of a SIPHON EARNINGS dump, Lv 1–20"),
                f("Top rate", "6% from Lv 21 (a Lv 25 siphon shows 6%, not 7%)", MEASURED),
                f("Combined cap", "30% per victim, across all attackers", GUIDE),
                f("Decay", "None", CONFIRMED),
                f("Deployment cap", "700 siphons in total (each also takes one of your 2 virus slots on that victim)", CONFIRMED),
                f("Wallet Shield", "Doesn't stop siphons: they keep skimming while it's up", GUIDE),
            ),
            table(
                MEASURED, listOf("Siphon Lv", "Skim %"),
                listOf(1, 2, 5, 6, 10, 13, 15, 17, 20, 25).map { lvl ->
                    listOf("Lv $lvl", String.format(Locale.US, "%.2f%%", GameFormulas.siphonPercent(lvl)!!).replace(".00%", "%"))
                },
                "Measured at Lv 1, 2, 5, 6, 13, 17, 20 and 25; Lv 10 and 15 from the line."
            ),
            tip("Lv 20 skims about 2.6× what Lv 6 does (5.75% vs 2.25%) but takes much longer to upload. Weigh that against how long the victim is likely to stay infected."),
            h("What gets skimmed"),
            p("Theft proceeds: Crypto the victim steals from other players' hot wallets. Spam collections don't seem to be skimmed: no siphon loss shows up when Spam income comes in. Still to be re-checked with a fresh siphon on you.", CONFIRMED),
            bullets(
                GUIDE,
                "Never skimmed (lands straight in cold storage): subscription daily Crypto, Black Market Crypto Stash, login-streak Crypto (days 3 and 5), daily mission Crypto, Watch & Earn Crypto, and the victim's own hot → cold transfers.",
                "Outbound spending (Store, OC packs, proxy activations) doesn't trigger it either.",
            ),
            warn("The guide contradicts itself here: its Viruses section adds Spam collections, its Software section adds incoming transfers, mission and login rewards too. Play suggests theft only (Q-02)."),
            h("Strategy"),
            tip("Quantity is king: siphons on dozens of active, high-level victims compound. A siphon on a whale who hacks a lot earns far more than one on a casual player."),
            tip("Siphons stack with Spam on the same victim (2 slots: Spam + Siphon is the income pair)."),
            h("Detection"),
            p("After the victim runs AV, a removed siphon disappears from your SIPHON EARNINGS immediately, with all its info: the row and its siphoned total are gone, and the game keeps no history of it. DarkIndex's Siphon Tracker alerts you when a known siphon vanishes after a full scroll.", CONFIRMED),
            unknowns(
                "Q-02" to "Is theft the only flow skimmed? (Spam collections look untouched)",
            ),
        ),
        related = listOf("virusRules", "spam", "antivirus", "darkindex")
    )

    private fun rootkit() = KbTopic(
        "rootkit", "🪱", "Rootkit virus",
        "Weakens a victim's Firewall and Encryptor by up to 3 effective levels, for you and your whole crew. Only the highest rootkit on a victim counts.",
        listOf(
            facts(
                f("Effect", "Victim's Firewall and Encryptor count as up to 3 levels lower", GUIDE),
                f("Who benefits", "You and every crewmate", GUIDE),
                f("Stacking", "None: only the highest-level rootkit on a victim applies", GUIDE),
                f("Hardening", "The debuff applies before the Backdoor / Exploit Kit block roll, and can switch hardening off at low levels", GUIDE),
            ),
            h("Debuff by level"),
            table(
                MEASURED, listOf("Rootkit Lv", "Debuff"),
                listOf(listOf("1–2", "−1"), listOf("3–5", "−2"), listOf("6+", "−3 (cap)")),
                "Measured at Lv 1, 2, 3 and 6. Lv 4–5 must be −2 (between Lv 3 and Lv 6)."
            ),
            tip("Lv 6 already gives the full −3: a higher rootkit only costs more and uploads slower."),
            tip("Upload a rootkit after the bypass and before the crack: the crack is then up to 3 levels easier."),
            tip("Coordinate: one crewmate's high-level rootkit carries the whole crew. Your own lower rootkit on top adds nothing."),
            calc(KbCalculators.HACK),
        ),
        related = listOf("hacking", "crews", "virusRules")
    )

    private fun virusRules() = KbTopic(
        "virusRules", "🦠", "Virus rules (all types)",
        "Slots, levels, rep damage and the performance penalty that apply to Spam, Siphon and Rootkit alike.",
        listOf(
            facts(
                f("Slots per victim", "2 per attacker (any 2 of the 3 types). Many attackers can infect one victim", CONFIRMED),
                f("Upload level", "Any level up to your installed one", CONFIRMED),
                f("Upgrading", "Locked at upload level; uninstall and re-upload", CONFIRMED),
                f("UPLOAD VIRUS x / 2 slots", "Your viruses on that victim; 0 after their AV wiped yours", CONFIRMED),
            ),
            h("Damage to the victim"),
            table(
                GUIDE, listOf("Virus Lv", "Rep lost"),
                listOf(1, 5, 10, 15, 20, 25).map { listOf("Lv $it", "−${GameFormulas.repLossFromVirus(it)}") },
                "−min(500, 25 × level) when it lands"
            ),
            bullets(
                GUIDE,
                "Daily loss cap: 500 rep. Floor: never below 50% of the victim's peak rep.",
                "Antivirus or System Purge restores 90% of the rep; 10% is a permanent scar.",
                "Self-recall (you uninstall) restores 100% to the victim.",
            ),
            h("Performance penalty"),
            p("Each active virus on a device cuts that device's performance by 1%, capped at 40% (60% speed). It slows bypasses, cracks, downloads, uploads and its own AV scans.", GUIDE),
            warn("The only way to detect viruses on yourself is an Antivirus scan. It lists every virus it removed and every one it didn't, so after one scan you know exactly what's on you."),
        ),
        related = listOf("spam", "siphon", "rootkit", "antivirus")
    )

    private fun transfers() = KbTopic(
        "transfers", "📶", "Uploads & downloads",
        "File transfers run on your network. Upload time also depends on the virus level and the victim's device; Raider I–III are equally easy.",
        listOf(
            h("What sets the time"),
            facts(
                f("Your network", "Divides download and upload time by its boost", GUIDE),
                f("Upload: victim device", "Slower on better devices; 10 tiers, Raider I–III all tier 0", CONFIRMED),
                f("Upload: virus level", "Higher uploads slower: time ∝ (level/20)^2.25", MEASURED),
                f("Download: software level", "Higher downloads slower; formula unknown", UNKNOWN),
                f("Concurrency", "+75% per extra process", GUIDE),
                f("Shared slowdown", "Uploads and downloads slow each other down. Bypasses and cracks don't affect them", CONFIRMED),
                f("Virus penalty", "−1% speed per virus on your device (max −40%)", GUIDE),
                f("Harvest Node network", "Uploads −50%", GUIDE),
                f("Null Route network", "Downloads −40%", GUIDE),
                f("Crew-server NIC", "+3% transfer speed per level (max +15%)", GUIDE),
            ),
            table(
                MEASURED, listOf("Victim device tier", "Lv 20 upload on DSL"),
                (0..9).map { tier ->
                    val device = HardwareCatalog.STANDARD_DEVICES[if (tier == 0) 0 else tier + 2]
                    listOf("$tier (${if (tier == 0) "Raider I–III" else device})", KbCalculators.duration(NetworkSpeedUtils.getUploadSecondsForLevel(device, "DSL", 20).toDouble()))
                },
                "Benchmark: Lv 20 virus at 15× network, scaled to 1.0×. Divide by your network boost."
            ),
            calc(KbCalculators.UPLOAD),
            tip("Upload the lowest Spam level that caps 100/hr on that victim: it can be hours faster than your max level on a Nova Ultra."),
            unknowns(
                "Q-03" to "Download time by software level and network",
            ),
        ),
        related = listOf("hardware", "spam", "processes")
    )

    private fun hacking() = KbTopic(
        "hacking", "🔓", "Bypass & crack",
        "Your Bypasser against their Firewall, your Cracker against their Encryptor. Success odds by level gap, what speeds it up, and what invalidates your access.",
        listOf(
            h("Success odds"),
            p("Not published by the guide. Worked out from our own logged attempts; the target cards use it.", MEASURED),
            table(
                MEASURED, listOf("Your level − theirs", "Success"),
                listOf(6, 5, 4, 3, 2, 1, 0, -1, -2, -3, -4).map { d ->
                    listOf((if (d > 0) "+$d" else "$d") + (if (d == 6) " or more" else if (d == -4) " or less" else ""), "${GameFormulas.hackSuccessChance(d, 0)}%")
                }
            ),
            calc(KbCalculators.HACK),
            h("Modifiers"),
            facts(
                f("Rootkit (yours or crew's)", "Their FW/ENC −1 to −3", GUIDE),
                f("Apex device (Overreach)", "Your Bypasser and Cracker +3", GUIDE),
                f("Cipher device (Hardened Core)", "Their FW and ENC +3", GUIDE),
                f("Bonus in brackets", "\"Using Bypasser Lv.20 (+3)\" on the bypass screen", CONFIRMED),
                f("Stacking", "Modifiers just add up, in any order: Apex +3 with a Rootkit −3 on them is an effective +6", CONFIRMED),
            ),
            h("Duration"),
            facts(
                f("Depends on", "Their FW/ENC level, your device speed, the level gap", GUIDE),
                f("Device", "Divides the base time: a 10-min bypass on Raider takes 2 min on Bolt II, ~26 s on Nova Ultra", GUIDE),
                f("Base time formula", "Unknown", UNKNOWN),
                f("Crew-server CPU", "+3% speed per level (max +15%)", GUIDE),
            ),
            h("Losing access"),
            bullets(
                GUIDE,
                "Victim upgrades Firewall: both your bypass and crack rows flip to Invalidated and the bypass is deleted. They get a 4-hour protection window.",
                "Your cracked password survives a Firewall upgrade: re-bypass and, if their Encryptor hasn't risen past your crack, wallet access comes back without re-cracking.",
                "Victim upgrades Encryptor: only the crack dies, and only if the new level is higher. Checked lazily the next time you open their wallet.",
                "IP Scrambler: cancels all your pending processes on them and wipes bypass records; they get a new IP.",
                "EMP Pulse: cancels pending processes and blocks new ones for 1 hour.",
            ),
            h("Rep"),
            p("Successful bypass/crack rep scales with their defence level and the software gap, up to ~105 on hard targets, once per victim per process type per 24 h. Zero rep at a gap of +10 or more.", GUIDE),
            unknowns(
                "Q-04" to "Base bypass/crack duration by level and gap",
                "Q-05" to "Hack rep formula",
            ),
        ),
        related = listOf("rootkit", "consumables", "processes", "reputation")
    )

    private fun processes() = KbTopic(
        "processes", "⚙", "Processes",
        "Everything timed runs as a process. Running several at once slows them all; Overclocks and ads speed them up. Limits: 500 running, 1,000 rows.",
        listOf(
            table(
                GUIDE, listOf("Type", "Time depends on"),
                listOf(
                    listOf("Firewall Bypass", "Their FW, your device, the gap"),
                    listOf("Password Crack", "Their ENC, your device, the gap"),
                    listOf("Download", "Software level, your network"),
                    listOf("Upload", "Virus level, their device, your network"),
                    listOf("Antivirus Scan", "Your device + the hack pool (3 h base, 45 min floor)"),
                )
            ),
            facts(
                f("Statuses", "Pending, Completed, Failed, Invalidated, EMP Killed", GUIDE),
                f("Concurrency", "Each extra process adds +75% of base time: 2 → 1.75×, 3 → 2.5×, 4 → 3.25×", GUIDE),
                f("Two pools", "Network (uploads, downloads) and processor (bypasses, cracks) slow down only their own kind. A bypass doesn't slow an upload", CONFIRMED),
                f("Overclock", "Skips 90 s each", GUIDE),
                f("Ads", "Up to 4 per process: −10%, −9%, −8%, −7% (34% total)", GUIDE),
                f("Running limit", "500 in progress", GUIDE),
                f("List limit", "1,000 rows including finished ones; hard cap, delete to start more", GUIDE),
                f("Pause/resume", "Hacker Pass: bypass and crack. Elite Pass: all types", GUIDE),
            ),
            calc(KbCalculators.SPEEDUP),
            h("Keeping the list clean"),
            bullets(
                GUIDE,
                "Delete finished uploads and downloads: the row has no further use.",
                "Keep the bypass row: its HACK button re-enters the device, and it survives Encryptor upgrades. The crack row is redundant for navigation.",
                "Failed and Invalidated rows are safe to bulk-delete (long-press to select).",
                "Completed bypass/crack cards show your Spam / Siphon / Rootkit levels on that victim (– if none). The \"My viruses\" filter lists victims missing a type.",
                "Tap a process's target icon to pin it to the Targets filter.",
            ),
            p("Processes are also a scrape source for DarkIndex: it creates bare records for new IPs it sees there.", CONFIRMED),
        ),
        related = listOf("hacking", "transfers", "antivirus")
    )

    private fun antivirus() = KbTopic(
        "antivirus", "🛡", "Antivirus",
        "Removal chance depends on your AV level against each virus; scan speed depends on your device. Matched or better AV is the target: one level behind drops you from 50% to 20%.",
        listOf(
            h("Removal chance per virus per scan"),
            table(
                GUIDE, listOf("AV − virus", "Removal"),
                listOf(5, 4, 3, 2, 1, 0, -1, -2, -3, -4, -5, -6, -7, -8, -9).map { d ->
                    listOf((if (d > 0) "+$d" else "$d") + (if (d == 5) " or more" else if (d == -9) " or less" else ""), "${GameFormulas.avRemovalChance(d, 0)}%")
                }
            ),
            facts(
                f("Scan report", "Lists every virus found: what was removed and what wasn't. One scan shows everything on you", CONFIRMED),
                f("Harvest Node attackers", "Their viruses count +2 levels against your AV", GUIDE),
                f("Rep", "Each removal restores 90% of the rep that virus cost you", GUIDE),
                f("System Purge", "Removes every virus instantly and restores most rep", GUIDE),
            ),
            h("Scan time"),
            table(
                GUIDE, listOf("Device", "Alone", "+1 hack", "+2 hacks"),
                listOf(1, 3, 5, 8, 10, 12).map { lvl ->
                    val m = GameFormulas.avScanMinutes(lvl)
                    listOf(HardwareCatalog.STANDARD_DEVICES[lvl - 1], KbCalculators.duration(m * 60), KbCalculators.duration(m * 60 * 1.75), KbCalculators.duration(m * 60 * 2.5))
                },
                "Scans share the hack pool with bypasses and cracks"
            ),
            warn("The guide's text says ~55 min at device Lv 10 and ~48 min at Lv 12, its table says 53 and 45. The table is used here (Q-06)."),
            calc(KbCalculators.ANTIVIRUS),
            tip("AV level decides removal, device level decides speed. Scan when you're not hacking to avoid the +75% pool penalty."),
            tip("Behind on AV? A −2 AV still removes 18% per virus per scan: repeated scans clear infections over time."),
            unknowns("Q-06" to "Scan time at device Lv 8, 10, 12"),
        ),
        related = listOf("virusRules", "hardware", "processes")
    )

    private fun consumables() = KbTopic(
        "consumables", "🧨", "Consumables & hardening",
        "Backdoors, Exploit Kits, Scramblers, Purges, EMPs, Shields and Log Locks: what each does, cooldowns, prices, and how Firewall/Encryptor hardening blocks the instant attacks.",
        listOf(
            table(
                GUIDE, listOf("Item", "Effect", "Cooldown", "Black Market"),
                listOf(
                    listOf("Backdoor", "Instant firewall bypass", "—", "40 Cr, Common"),
                    listOf("Exploit Kit", "Instant password crack", "—", "50 Cr, Common"),
                    listOf("IP Scrambler", "New IP; cancels all pending processes against you; wipes bypass records", "—", "200 Cr, Common"),
                    listOf("Log Lock", "Your log can't be edited for 8 h", "12 h", "12 Cr, Uncommon"),
                    listOf("Wallet Shield", "Nobody can steal from your hot wallet for 24 h (siphons on you keep skimming)", "48 h", "150 Cr, Uncommon"),
                    listOf("System Purge", "Removes all viruses, restores most rep", "—", "85 Cr, Rare"),
                    listOf("EMP Pulse", "Cancels pending processes and blocks new ones for 1 h", "2 h", "80 Cr, Rare"),
                )
            ),
            h("Hardening"),
            p("From Lv 3, a Firewall can block Backdoors and an Encryptor can block Exploit Kits: 3% per level above 2, capped at 90%. A blocked item is still used up.", GUIDE),
            table(
                GUIDE, listOf("FW / ENC level", "Block chance"),
                listOf(2, 3, 5, 8, 10, 15, 20, 25, 30, 32).map { listOf("Lv $it", "${GameFormulas.hardeningBlockChance(it)}%") }
            ),
            bullets(
                GUIDE,
                "A Rootkit on the victim lowers their effective level before the block roll.",
                "Cipher network (Adaptive Block): 100% block on incoming Backdoors and Exploit Kits.",
                "With Stealth on, your Backdoor/Exploit Kit leaves no log entry on the victim.",
                "Backdoor still triggers the offensive Trace roll at bypass.",
                "Vector device: 50% chance to keep a consumable when you use it.",
            ),
            p("Game log: \"Backdoor blocked by <ip>'s firewall\" appears when hardening wins.", CONFIRMED),
            tip("Backdoors are 40 Credits and lose to hardening at high levels: against a Lv 30 firewall, 84% of them are wasted. Save them for low-firewall targets."),
        ),
        related = listOf("hacking", "proxy", "premium")
    )

    private fun proxy() = KbTopic(
        "proxy", "🕶", "Proxy, Stealth & Trace",
        "Proxy hides octets of your IP for a paid activation; Lv 20 unlocks Stealth (hidden from scans). Trace unmasks proxied attackers: one roll at your bypass, and a fresh roll for them every time you enter their device.",
        listOf(
            h("Masking"),
            table(
                GUIDE, listOf("Proxy level", "Masked", "Shows as"),
                listOf(1, 6, 11, 16, 20).map { lvl ->
                    val range = when (lvl) { 1 -> "1–5"; 6 -> "6–10"; 11 -> "11–15"; 16 -> "16–19"; else -> "20+" }
                    listOf(range, if (lvl >= 20) "All + Stealth toggle" else "${GameFormulas.proxyMaskedOctets(lvl)} octet(s)", GameFormulas.proxyMaskExample(lvl))
                }
            ),
            p("Logs can show partial masks (123.45.xxx.xxx), full masks, or [UNKNOWN] for a Ghost-loadout attacker. A victim's Trace can append [TRACED] with the real IP.", CONFIRMED),
            h("Activation cost"),
            p("Paid up front in Crypto for 2, 6, 12 or 24 hours: ceil(10 × √level × hours × (1 − discount)). Matches the game at Lv 27: 104 / 297 / 562 / 1,061.", MEASURED),
            table(
                CALCULATED, listOf("Level", "2 h", "6 h (−5%)", "12 h (−10%)", "24 h (−15%)"),
                listOf(1, 5, 10, 15, 19, 20, 25, 30).map { lvl ->
                    listOf("Lv $lvl") + GameFormulas.PROXY_DURATIONS.map { n(GameFormulas.proxyActivationCost(lvl, it.first)!!) }
                }
            ),
            calc(KbCalculators.PROXY),
            p("Activations stack with no cap: buy another while one is running to extend the timer past 24 h.", CONFIRMED),
            bullets(
                GUIDE,
                "An activation keeps the level it started with. Upgrading Proxy mid-activation doesn't apply until you re-activate, which forfeits the time left.",
                "Successful hack chains (download/upload) while proxied give +10 rep, once per victim per process type per 24 h.",
                "Ghost Link network: Proxy lasts twice as long and counts +5 vs Trace.",
            ),
            h("Stealth (Proxy 20+)"),
            facts(
                f("Effect", "Hidden from random scans; people with your IP can still look you up", GUIDE),
                f("Reserve", "Up to 170 h banked; drains 1:1 while on", CONFIRMED),
                f("Refill", "1 h per 1.5 h with Stealth off, so sustained use ≈ 40% of the time", CONFIRMED),
                f("Ghost Link refill", "1 h per hour", GUIDE),
                f("Minimum", "15 min banked to switch on", GUIDE),
                f("Cost", "Free toggle; only uses reserve. With Stealth off your Proxy still masks your IP", CONFIRMED),
                f("Logs", "Crack/download/upload still log with your IP as xxx.xxx.xxx.xxx; Backdoor/Exploit leave no entry", GUIDE),
            ),
            h("Trace"),
            table(
                GUIDE, listOf("", "Offensive (yours)", "Defensive (theirs)"),
                listOf(
                    listOf("Rolls against", "Victim's mask level", "Your Proxy level"),
                    listOf("When", "Once, at firewall bypass (incl. Backdoor)", "Every time you enter their device"),
                    listOf("If it lands", "You see their real IP", "Your real IP in their log, tagged [TRACED]"),
                )
            ),
            bullets(
                GUIDE,
                "Equal Trace and Proxy ≈ a coin flip; higher Trace wins more often.",
                "No re-roll on sub-pages (logs, crew chat, roster) or on individual crack/download/upload.",
                "Trace detection gives 15 + (Trace level − 1) rep, capped at 25.",
                "Ghost device: [UNKNOWN] in logs and immune to Trace.",
            ),
            tip("Keep a Log Lock (12 Credits) running: an attacker who bypassed you can edit your log and scrub a [TRACED] IP."),
            tip("Returning to the same victim many times gives their Trace many rolls. Get in, do everything, get out."),
            unknowns("Q-07" to "Trace vs Proxy unmask chance by level gap"),
        ),
        related = listOf("logs", "scanning", "crews", "reboot")
    )

    private fun keygen() = KbTopic(
        "keygen", "🔑", "Keygen & software prices",
        "Download software from victims and unlock it with a Keygen key for 15–50% of the Store price, with full XP. One big jump beats many small ones.",
        listOf(
            h("How it works"),
            bullets(
                GUIDE,
                "Download software from a bypassed victim. It arrives locked.",
                "Your Apps page shows it as LOCKED, \"LVL N available <price> Crypto\".",
                "Pay the key fee with your Keygen to unlock: you get the full XP of that level and +5 rep.",
                "You can't key a level at or below your current one (no XP farming).",
                "Keygen itself is device-bound and can't be downloaded; it's bought in the Store.",
                "Activation writes no log line.",
            ),
            h("Key fee"),
            p("15% of the target level's Store price for a 1-level jump, rising linearly to 50% at a 20+ level jump, rounded up. All 9 LOCKED prices on a real Apps page fit it.", MEASURED),
            table(
                CALCULATED, listOf("Jump", "Fee"),
                listOf(1, 2, 3, 5, 8, 10, 15, 20, 25).map { j -> listOf("+$j", String.format(Locale.US, "%.1f%%", GameFormulas.keygenFeeRatio(j)!! * 100)) },
            ),
            tip("A single big jump is far cheaper than stepping: each step pays 15% of its own level's price. Download the highest copy you can find and key it directly."),
            h("Store prices"),
            p("Price = Keygen curve × base/600, × 0.85 for defensive apps. Offensive prices round to the nearest crypto, defensive ones round up. Keygen curve = (−L³ + 147L² + 6814L − 10212) / 6. Verified to the crypto on 5 real prices (L19–25).", MEASURED),
            table(
                CALCULATED, listOf("App", "Base", "Lv 15", "Lv 20", "Lv 25", "Lv 30"),
                GameFormulas.App.values().map { app ->
                    listOf(app.displayName, n(app.basePrice) + if (app.defensive) " ×0.85" else "") +
                        listOf(15, 20, 25, 30).map { n(GameFormulas.storePrice(app, it)!!) }
                }
            ),
            warn("The curve is exact from about Lv 15 to 25. Below that it drifts from the guide (guide Keygen Lv 5 = 4,862; curve 4,568), and above it real prices run slightly higher (about +0.1% at Lv 33, +0.6% at Lv 42). Q-09."),
            calc(KbCalculators.KEYGEN),
            h("XP"),
            facts(
                f("Software", "≈0.17 XP per Store crypto, in full on Keygen unlocks", MEASURED, "e.g. Keygen Lv 26 (41,459) → +7,045 XP"),
                f("Device upgrades", "0.25 XP per crypto (best ratio)", MEASURED),
                f("Older hardware / network", "0.20 XP per crypto", MEASURED),
            ),
            tip("Keygen is a fast XP engine for active hackers, but XP raises your level, and over-levelling changes who your scans find. Upgrade what you use."),
            unknowns(
                "Q-09" to "Store prices below Lv 15 and above Lv 25",
            ),
        ),
        related = listOf("software", "leveling")
    )

    private fun software() = KbTopic(
        "software", "💾", "The 11 apps",
        "What every app does, offence vs defence, and the one-off utilities.",
        listOf(
            table(
                GUIDE, listOf("App", "Role", "Key facts"),
                listOf(
                    listOf("Firewall", "Defence", "Slows and blocks bypasses. Upgrade invalidates incoming bypass/crack + 4 h protection. Hardening vs Backdoor"),
                    listOf("Password Encryptor", "Defence", "Slows and blocks cracks. Upgrade invalidates cracks above the old level. Hardening vs Exploit Kit"),
                    listOf("Antivirus", "Defence", "Removes viruses; restores 90% of their rep damage"),
                    listOf("Proxy", "Defence", "Masks your IP when activated (paid); Lv 20 Stealth"),
                    listOf("Trace", "Defence", "Unmasks proxied attackers and victims"),
                    listOf("Bypasser", "Offence", "Breaks firewalls"),
                    listOf("Password Cracker", "Offence", "Breaks encryptors"),
                    listOf("Spam", "Virus", "Passive income up to 100/hr per victim"),
                    listOf("Siphon", "Virus", "Skims the victim's theft and Spam income"),
                    listOf("Rootkit", "Virus", "−3 effective defence for you and your crew"),
                    listOf("Keygen", "Utility", "Unlocks downloaded software cheaply; device-bound"),
                )
            ),
            facts(
                f("Defensive discount", "Firewall, Encryptor, Antivirus, Proxy, Trace cost ×0.85", MEASURED, "Proxy verified at Lv 21 = 40,046"),
                f("Notepad", "5,000 Crypto, one-off. Pencil icon on every page", GUIDE),
                f("Message Encryptor", "9,000 Crypto, one-off. Blanks crew chat for anyone who hacks you", GUIDE),
            ),
            h("Reading the Apps page"),
            bullets(
                CONFIRMED,
                "Your Apps page shows installed levels, (+N) bonuses, and LOCKED copies waiting for a key.",
                "A victim's Apps page shows each app's LVL and a badge comparing it to your installed level (=, +2, −6, DEVICE-BOUND).",
            ),
        ),
        related = listOf("keygen", "proxy", "antivirus")
    )

    private fun wallet() = KbTopic(
        "wallet", "👛", "Wallet, theft & gas",
        "Hot wallet is stealable and siphonable; cold storage is safe but capped by level. 10% of every steal is burned as gas.",
        listOf(
            facts(
                f("Hot wallet", "Spending balance; stealable by anyone who cracked you", GUIDE),
                f("Cold storage", "Can't be stolen or siphoned; capped by level", GUIDE),
                f("Gas fee", "10% of the steal, rounded down. The victim loses the full amount; you receive the rest", MEASURED, "All 10 thefts in a wallet history: e.g. 993 stolen = 99 gas + 894 received"),
                f("\"Stole N\" in your log", "What the victim lost (before gas)", CONFIRMED),
                f("Transaction history", "Your Wallet page lists each theft with the victim's wallet (shortened), the gas and what you received", CONFIRMED),
                f("Changing address", "The regen button gives you a new address for 50 Credits. Old log lines keep showing the old one", GUIDE),
                f("Wallet Shield", "Nobody can steal from your hot wallet for 24 h (48 h cooldown). Siphons installed on you keep skimming", GUIDE),
            ),
            h("Cold storage cap"),
            table(
                GUIDE, listOf("Level", "Cap"),
                GameFormulas.COLD_STORAGE_KNOWN.entries.map { listOf("Lv ${it.key}", n(it.value) + if (it.key == 30) "+" else "") },
                "Grows faster with level. Missions, streaks, ads and stashes ignore the cap."
            ),
            h("Crypto → Credits"),
            facts(
                f("Rate", "250 Crypto per Credit", CONFIRMED),
                f("Minimum", "5 Credits (1,250 Crypto)", GUIDE),
                f("Monthly cap", "750 Credits, resets on the 1st", CONFIRMED),
                f("Drains", "Hot wallet first, then cold storage", GUIDE),
            ),
            facts(
                f("Per steal", "No minimum or maximum: take any amount, up to the whole hot wallet", CONFIRMED),
                f("Per day", "There seems to be a daily maximum; the amount is unknown", UNKNOWN),
            ),
            tip("Stash right after every steal: tap the Wallet: badge at the top of any page, then transfer to cold."),
            tip("Your theft log shows the thief's wallet, not their IP. Match timestamps against \"Device accessed from\" lines to find them."),
            unknowns(
                "Q-10" to "Cold storage cap at every level",
                "Q-11" to "The daily steal maximum",
            ),
        ),
        related = listOf("logs", "siphon", "darkindex")
    )

    private fun logs() = KbTopic(
        "logs", "📜", "Logs",
        "Your device log and every victim's log hold the last 100 lines, timestamped in UTC. Anyone who bypassed a device can edit its log.",
        listOf(
            facts(
                f("Length", "Last 100 lines, including non-entry text such as graffiti or messages", CONFIRMED),
                f("Time zone", "UTC+0, format [M-D H:MM]", CONFIRMED),
                f("Order", "Newest first, but not strictly chronological", MEASURED),
                f("Footer", "\"Last saved: YYYY-MM-DD HH:MM:SS\" in UTC, which dates the lines", MEASURED),
                f("Timestamp", "Set when the device is accessed, so a session's actions can share one time", CONFIRMED),
                f("Editing", "Anyone who bypassed you can edit your log; Log Lock blocks it for 8 h", GUIDE),
            ),
            h("Lines and what they mean"),
            table(
                CONFIRMED, listOf("Line", "Where", "Meaning"),
                listOf(
                    listOf("Accessed device at <ip>", "Your log", "You entered a target"),
                    listOf("Device accessed from <ip>", "Victim log", "Someone entered; may be masked, [UNKNOWN] or [TRACED]"),
                    listOf("Stole N Crypto from <wallet>", "Your log", "You stole N (victim's loss)"),
                    listOf("N Crypto transferred to <wallet>", "Victim log", "You were robbed; wallet is the thief's"),
                    listOf("Cracking/Cracked password on <ip>", "Your log", "Crack progress"),
                    listOf("Bypassed / Failed to bypass firewall on <ip>", "Your log", "Bypass outcome"),
                    listOf("Failed to crack password on <ip>", "Your log", "Crack failed"),
                    listOf("Downloaded/Uploaded LvN <App> from/to <ip>", "Your log", "Transfers"),
                    listOf("Password being cracked by <ip>", "Victim log", "Incoming crack"),
                    listOf("LvN <App> being uploaded/downloaded by <ip>", "Victim log", "Incoming transfer"),
                    listOf("Backdoor blocked by <ip>'s firewall", "Your log", "Hardening blocked you"),
                    listOf("Purchased Keygen LvN / Upgraded network to …", "Your log", "Your purchases"),
                )
            ),
            bullets(
                CONFIRMED,
                "The game sometimes skips an \"Accessed\" line: two steals can follow one access, and only the first belongs to that IP.",
                "Masked lines (xxx) come from the other player's Proxy; [UNKNOWN] is a Ghost-loadout player.",
                "Victim logs are editable, so treat odd lines with suspicion.",
            ),
            tip("Scrape your log often: with only 100 lines kept, a busy day pushes steals off the end."),
            tip("Wipe your own log after scraping it: anyone who bypasses you can read it and harvest your best targets."),
        ),
        related = listOf("proxy", "wallet", "darkindex")
    )

    private fun scanning() = KbTopic(
        "scanning", "📡", "Scanning",
        "Scan Random fills 10 targets (more with a crew Uplink) close to your level, widening the range until the list is full. Stealth players don't appear.",
        listOf(
            facts(
                f("Results", "10 targets per scan by default; range widens as needed", CONFIRMED),
                f("Stealth", "Proxy 20+ players with Stealth on never appear in random scans", GUIDE),
                f("Direct lookup", "Anyone whose IP you have can be found directly", GUIDE),
                f("Ghost device", "Last octet hidden in scanners even without a Proxy", GUIDE),
                f("Proxied players", "Still listed, with their IP masked (fully or partly) as their Proxy dictates", CONFIRMED),
                f("Crew-server Uplink", "+1 target per level (15 at Lv 5, 17 with a surge) and reaches a little higher", GUIDE),
                f("Uplink in practice", "A crew Uplink giving +2 shows 12 targets per scan", CONFIRMED),
                f("Own device", "0.0.0.0 is your localhost (AV scans); never a target", CONFIRMED),
            ),
            p("Each result shows the target's IP and Firewall level; with the Apex reboot you also see their Encryptor level. Your level decides who you see: over-levelling shows you tougher targets.", CONFIRMED),
            tip("Look for low firewalls and levels at or above yours for full rep. For Spam, any victim Lv ${GameFormulas.spamBestCap().second}+ caps with Lv ${GameFormulas.spamBestCap().first}."),
            unknowns("Q-12" to "Exact level window of a scan at your level"),
        ),
        related = listOf("leveling", "proxy", "spam")
    )

    private fun reputation() = KbTopic(
        "reputation", "⭐", "Reputation & score",
        "Score = floor(Level × Rep^0.75). Rep comes from hacking, defending and Trace; viruses take it away. Hacking far weaker software earns nothing.",
        listOf(
            h("Score"),
            facts(
                f("Formula", "floor(Level × Rep^0.75)", MEASURED, "Verified on 6 profiles and 2 own-Home scrapes"),
                f("Doubling level", "Doubles score", CALCULATED),
                f("Doubling rep", "×1.68 score", CALCULATED),
                f("Leaderboards", "Rank score growth in the period: current month, previous month, rolling 3 months", GUIDE),
                f("Lifetime", "Every reboot folds score into a lifetime total (Reboot leaderboard)", GUIDE),
            ),
            calc(KbCalculators.SCORE),
            h("Earning rep"),
            table(
                GUIDE, listOf("Action", "Rep", "Limit"),
                listOf(
                    listOf("Successful bypass/crack", "Scales with their defence and the gap; up to ~105", "Once per victim per type per 24 h"),
                    listOf("Download / upload", "5", "Once per victim per type per 24 h"),
                    listOf("Backdoor / Exploit Kit", "Base only, min 5 after gap penalty", "Same 24 h gate"),
                    listOf("Defence (attacker fails)", "15 + ⌈attacker SW ÷ 5⌉", "Once per attacker per 24 h"),
                    listOf("Proxy hack chain", "+10 on download/upload", "Same 24 h gate"),
                    listOf("Trace detection", "15 + (Trace − 1), max 25", "—"),
                    listOf("Keygen use", "5", "—"),
                    listOf("Missions", "30 / 55 / 90", "Daily"),
                )
            ),
            p("The 24 h cooldowns only gate rep: theft, downloads and viruses still work every time.", GUIDE),
            h("Software gap penalty"),
            p("Compares your offence software with their defence software, not player levels.", GUIDE),
            table(
                GUIDE, listOf("Your SW − theirs", "Rep"),
                listOf(5, 6, 7, 8, 9, 10).map { g ->
                    val label = when (g) { 5 -> "+5 or less"; 10 -> "+10 or more"; else -> "+$g" }
                    listOf(label, "${Math.round(GameFormulas.hackRepGapFactor(g) * 100)}%")
                },
                "The guide says \"linear falloff\" from +6 to +9; the exact steps are assumed"
            ),
            h("Losing rep"),
            facts(
                f("Virus lands", "−min(500, 25 × virus level)", GUIDE),
                f("Daily cap", "500 rep lost per day", GUIDE),
                f("Floor", "Never below 50% of your peak", GUIDE),
                f("AV / Purge", "Restores 90%; 10% scar", GUIDE),
            ),
            unknowns("Q-05" to "Hack rep formula (rep before/after a bypass and crack with both levels)"),
        ),
        related = listOf("leveling", "hacking", "virusRules")
    )

    private fun leveling() = KbTopic(
        "leveling", "📈", "Leveling & XP",
        "XP comes from spending, not hacking. Levels raise cold storage and give Credits and OC, but over-levelling changes who your scans find and costs rep.",
        listOf(
            h("XP sources"),
            facts(
                f("Software (Store or Keygen)", "≈0.17 XP per Store crypto", MEASURED),
                f("Device", "0.25 XP per crypto", MEASURED),
                f("Missions", "100 / 150 / 200 XP", GUIDE),
                f("Login streak day 1", "100 XP (200 with a subscription)", GUIDE),
            ),
            h("XP to next level"),
            table(
                GUIDE, listOf("Level", "XP to next"),
                GameFormulas.XP_TO_NEXT_KNOWN.entries.map { listOf("Lv ${it.key}", n(it.value)) },
                "Cumulative: Lv 5 7,752 · Lv 10 30,437 · Lv 20 133,683 · Lv 30 328,940 (guide). Lv 35/38/44 confirmed in-game."
            ),
            h("Level-up rewards"),
            facts(
                f("Overclocks", "+10", GUIDE),
                f("Credits", "+10", GUIDE),
                f("Cold storage", "Bigger cap (see Wallet)", GUIDE),
                f("Node botnet", "Needs Lv 3", GUIDE),
                f("Reboot", "Invitation at Lv 50", GUIDE),
            ),
            h("Over-levelling"),
            bullets(
                GUIDE,
                "Scans pull targets near your level: a higher level shows you tougher players.",
                "Spam doesn't care about your level: only the victim's level vs the Spam level counts (measured).",
                "Hack rep falls off when your software outclasses theirs by 6+ levels.",
            ),
            tip("Spend on what you use (Bypasser, Cracker, Spam, Keygen, device). Upgrading unused apps for XP only drags your level ahead of your offence."),
            unknowns(
                "Q-13" to "XP to next for every level",
            ),
        ),
        related = listOf("keygen", "reputation", "scanning")
    )

    private fun daily() = KbTopic(
        "daily", "📅", "Missions, streaks & Watch & Earn",
        "The free daily loop: 3 missions, a 7-day login streak and 12 rewarded ads. All Crypto from these lands in cold storage, ignores the cap and can't be siphoned.",
        listOf(
            h("Daily missions"),
            table(
                GUIDE, listOf("Difficulty", "XP", "Rep", "Crypto / OC"),
                listOf(
                    listOf("Easy", "100", "30", "250 / 3"),
                    listOf("Medium", "150", "55", "500 / 7"),
                    listOf("Hard", "200", "90", "1,000 / 15"),
                )
            ),
            bullets(
                GUIDE,
                "3 per day, from hack groups (bypass, crack, download) and upload groups (Spam, Rootkit, Siphon); each group at most once.",
                "Hack missions pay Crypto, upload missions pay OC.",
                "A victim counts once per mission slot within 48 h.",
                "Reset at midnight UTC.",
            ),
            h("Login streak"),
            table(
                GUIDE, listOf("Day", "Reward", "With subscription"),
                listOf(
                    listOf("1", "100 XP", "200 XP"),
                    listOf("2", "5 OC", "10 OC"),
                    listOf("3", "500 Crypto", "1,000 Crypto"),
                    listOf("4", "5 Credits", "10 Credits"),
                    listOf("5", "1,000 Crypto", "2,000 Crypto"),
                    listOf("6", "10 OC", "20 OC"),
                    listOf("7", "15 Credits", "30 Credits"),
                ),
                "Miss a day and it resets to day 1"
            ),
            h("Watch & Earn"),
            facts(
                f("Where", "Store → Credits tab, bottom", GUIDE),
                f("Credits", "3 × 4 per day = 12", GUIDE),
                f("Crypto", "250 × 4 per day = 1,000 (to cold storage)", GUIDE),
                f("Overclocks", "5 × 4 per day = 20", GUIDE),
            ),
        ),
        related = listOf("leveling", "premium")
    )

    private fun hardware() = KbTopic(
        "hardware", "🖥", "Devices & networks",
        "Your device speeds up bypasses, cracks and AV scans; your network speeds up downloads and uploads. A victim's device also sets how long uploads to them take.",
        listOf(
            h("Devices"),
            table(
                GUIDE, listOf("Lv", "Device", "Speed", "Price", "Upload defence"),
                HardwareCatalog.STANDARD_DEVICES.mapIndexed { i, d ->
                    listOf("${i + 1}", d, "${GameFormulas.DEVICE_SPEED[i]}×", if (i == 0) "free" else n(GameFormulas.DEVICE_PRICES[i]), "tier ${HardwareCatalog.uploadDefenceTier(d)}")
                },
                "Speed divides bypass, crack and AV time. Upload-defence tiers are confirmed in-game: Raider I–III upload identically."
            ),
            h("Networks"),
            table(
                GUIDE, listOf("Lv", "Network", "Speed", "Price"),
                HardwareCatalog.STANDARD_NETWORKS.mapIndexed { i, nw ->
                    listOf("${i + 1}", nw, "${HardwareCatalog.NETWORK_BOOST[i]}×", if (i == 0) "free" else n(GameFormulas.NETWORK_PRICES[i]))
                }
            ),
            facts(
                f("Device XP", "0.25 XP per crypto", GUIDE),
                f("Virus penalty", "−1% per virus on your device, max −40%", GUIDE),
            ),
            h("Prestige hardware"),
            p("After a reboot: <Line> I to XII for devices, I to XI for networks. Each level is as fast as the standard item at the same position.", MEASURED),
            table(
                MEASURED, listOf("Loadout", "Device line", "Network line"),
                HardwareCatalog.Line.values().map { listOf(it.name.lowercase(Locale.ROOT).replaceFirstChar { c -> c.uppercase() }, it.deviceName, it.networkName) }
            ),
        ),
        related = listOf("transfers", "hacking", "reboot")
    )

    private fun crews() = KbTopic(
        "crews", "👥", "Crews & the crew server",
        "Up to 30 members (40 with Storage). Crews share rootkit debuffs and a server that buffs everyone: faster hacks and transfers, extra Spam slots, bigger scans.",
        listOf(
            facts(
                f("Size", "30, up to 40 with crew-server Storage", GUIDE),
                f("Membership", "One crew at a time", GUIDE),
                f("Roles", "Leader, Officer, Member", GUIDE),
                f("Join policy", "Open, Request or Closed", GUIDE),
                f("Tag", "[TAG] on profiles and leaderboards", GUIDE),
                f("Rootkits", "A crewmate's rootkit debuffs the victim for everyone; only the highest applies", GUIDE),
            ),
            h("OPSEC"),
            bullets(
                CONFIRMED,
                "Hack one member and you see the crew's roster, with every member's IP.",
                "You also see the crew chat, unless the member you hacked owns Message Encryptor (E2E, from the Store).",
                "Each IP on the roster is masked by that member's own Proxy, not by the hacked member's.",
                "Trace never rolls on crew screens: an IP that shows masked there stays masked.",
            ),
            tip("Join a crew as soon as you're active: crewmates' rootkits help you too, and the crew server buffs everyone."),
            p("The roster screen lists every member's IP and role, including off-screen rows. DarkIndex only uses it when one member's clan tag is already known.", CONFIRMED),
            h("Crew server"),
            table(
                CONFIRMED, listOf("Part", "Per level", "Max (Lv 5)"),
                listOf(
                    listOf("CPU", "+3% bypass & crack speed", "+15%"),
                    listOf("NIC", "+3% download & upload speed", "+15%"),
                    listOf("RAM", "+1 botnet slot", "+5"),
                    listOf("Uplink", "+1 scan target (10 → 15) and reaches higher levels", "15 targets"),
                    listOf("Storage", "+2 member slots, no upkeep", "+10 (40)"),
                ),
                "Seen on a Lv 16 server: CPU, NIC, RAM at L3 (+9%, +9%, +3 slots), Uplink L2 (12 targets), Storage L5 (40 members)."
            ),
            h("Upgrade price and upkeep"),
            table(
                MEASURED, listOf("Part", "Price to Lv 1 / 2 / 3 / 4 / 5", "Upkeep/day at Lv 1 / 2 / 3 / 4 / 5"),
                GameFormulas.CrewPart.values().map { part ->
                    listOf(
                        part.displayName,
                        (1..5).joinToString(" / ") { compact(GameFormulas.crewPartUpgradePrice(part, it)!!) },
                        if (part.hasUpkeep) (1..5).joinToString(" / ") { n(GameFormulas.crewPartUpkeep(part, it)!!.toInt()) } else "none"
                    )
                },
                "Price to reach level k = base × k² (CPU/NIC 70K, RAM 120K, Uplink 45K, Storage 60K). Upkeep = 0.5% of everything spent on the part per day. Checked against a crew's full server log: every upgrade matches, and the log balances to the treasury."
            ),
            facts(
                f("Upkeep billing", "Charged about once a day for the hours since the last charge, so single charges vary", MEASURED),
                f("Price display", "Rounded down to one decimal: RAM Lv 3 (1.08M) shows as \"1M\"", MEASURED),
            ),
            h("Surges"),
            table(
                MEASURED, listOf("Part at", "6 h", "12 h", "24 h"),
                listOf(GameFormulas.CrewPart.CPU, GameFormulas.CrewPart.UPLINK).flatMap { part ->
                    (1..5).map { lvl ->
                        listOf("${if (part == GameFormulas.CrewPart.CPU) "CPU/NIC" else part.displayName} L$lvl") +
                            listOf(6, 12, 24).map { n(GameFormulas.crewSurgePrice(part, lvl, it)!!.toInt()) }
                    }
                },
                "Price = the next two levels' price × hours ÷ 1,200, minus 5% for 12 h or 10% for 24 h. Matches all 19 surges in the log; 6 h is full price (confirmed)."
            ),
            facts(
                f("Extending", "Costs the same as starting a new surge", MEASURED),
                f("Ending early", "Allowed; no refund appears in the log", MEASURED),
            ),
            bullets(
                GUIDE,
                "Server level = sum of the 5 parts (max 25).",
                "Leaders can switch parts off; an unpowered part costs no upkeep.",
                "Treasury: one-way contributions, up to 2,000 × your level per day (max 50,000). Members top up to exactly 50,000 a day (confirmed in the log).",
                "New members wait 24 h (\"24H TENURE GATE\") before they get buffs (confirmed in the log).",
                "Treasury at 0: server offline, buffs pause, RAM Spam pauses; Storage keeps working and levels are never lost.",
                "Surge: CPU, NIC or Uplink +2 levels for 6/12/24 h (up to +21% or 17 targets), paid up front.",
                "Every contribution, upgrade and upkeep day shows in the Server Log.",
            ),
        ),
        related = listOf("rootkit", "botnet", "scanning")
    )

    private fun reboot() = KbTopic(
        "reboot", "♻", "Reboot Protocol (prestige)",
        "From Lv 50 you may wipe your run for a loadout with permanent passives. R1–R5 must use each loadout once; from R6 you mix any device with any network.",
        listOf(
            facts(
                f("Unlock", "Lv 50; optional forever; entry in My Device settings", GUIDE),
                f("Tier", "R1, R2, R3… with no cap; shown as R<n> above LVL", GUIDE),
                f("R1–R5", "A different loadout each time", GUIDE),
                f("R6+", "Any device line with any network line (25 combinations)", GUIDE),
                f("Passives", "Only your current loadout's; they never stack", GUIDE),
            ),
            h("Wiped"),
            bullets(GUIDE, "Level → 1, rep → 0", "Software back to starter", "Crypto wallets", "Botnet to free; Spam cleared", "Siphons, rootkits, cracks, processes", "Daily mission progress; new random IP", "Current score (added to lifetime first)"),
            h("Kept"),
            bullets(GUIDE, "R-tier, badges", "This month's leaderboard progress", "Credits, Overclocks, inventory", "Notepad, Message Encryptor", "Crew membership", "Lifetime score", "Loadout history"),
            h("Loadouts"),
            table(
                GUIDE, listOf("Loadout", "Device perk", "Network perk"),
                listOf(
                    listOf("Ghost", "Ghost Strike: [UNKNOWN] in logs, Trace-immune, last octet hidden in scans", "Dark Routing (Ghost Link): Proxy +5 vs Trace, lasts 2×; faster Stealth refill"),
                    listOf("Vector", "Efficient Use: 50% OC discount on hacks, 50% chance to keep consumables", "Backchannel (Contra Band): 50% OC discount on transfers, free consumable daily"),
                    listOf("Scythe", "Siphon Surge + Slow Burn: 2× siphon, half Spam decay", "Fast Deploy + Buried Roots (Harvest Node): uploads −50%, viruses +2 vs AV"),
                    listOf("Cipher", "Hardened Core: your FW and ENC +3", "Adaptive Block (Void Net): 100% Backdoor/Exploit block"),
                    listOf("Apex", "Overreach: your Bypasser and Cracker +3", "Deep Link (Null Route): scans show ENC, downloads −40%"),
                )
            ),
            p("Prestige hardware is as fast as standard hardware at the same level; the perk is the reward. DarkIndex reads names like Apex VII and Null Route X.", MEASURED),
            tip("Scythe is the income loadout: half decay plus double siphon plus 50% faster uploads."),
        ),
        related = listOf("hardware", "reputation")
    )

    private fun premium() = KbTopic(
        "premium", "💳", "Credits, Black Market & purchases",
        "Credits buy Black Market consumables and OC. Free sources: levels, streaks, ads and the Crypto exchange. Real-money options in brief.",
        listOf(
            facts(
                f("Free Credits", "+10 per level, streak days 4 and 7, 12/day from ads, Crypto exchange (750/month)", GUIDE),
                f("Black Market", "5–6 random items a day; per-item daily limits; flash sales", GUIDE),
                f("Crypto Stash", "10K for 100 Cr, 40K for 400 Cr; 1/day each; straight to cold storage", GUIDE),
                f("OC packs (Store)", "50 for 55 Cr · 150 for 125 · 400 for 250 · 1,000 for 500", GUIDE),
                f("Username change", "First free, then 200 Cr with a 30-day cooldown", GUIDE),
                f("Credit packs", "$0.99 = 100 Cr up to $49.99 = 6,000 Cr (+20%)", GUIDE),
                f("Starter Kit", "$2.99 one-off, 48 h after first disconnect at Lv 2+", GUIDE),
                f("Hacker Pass", "$4.99/mo: 5 Cr + 5 OC + 500 Crypto daily, weekly consumables, −25% Spam fee, 2× streak", GUIDE),
                f("Elite Pass", "$9.99/mo: 10 Cr + 10 OC + 1,000 Crypto daily, more drops, −50% Spam fee, pause any process", GUIDE),
            ),
            p("Consumable prices and effects are in Consumables & hardening.", null),
        ),
        related = listOf("consumables", "daily")
    )

    private fun darkIndex() = KbTopic(
        "darkindex", "📱", "How DarkIndex works",
        "What each scrape stores, how wallets and income are worked out, and what Good Spam, Recon, Siphon Alerts and Raw Dumps do.",
        listOf(
            h("Scraping"),
            bullets(
                CONFIRMED,
                "Tap 📸 on any game screen: DarkIndex reads its text (offline, nothing leaves your phone) and saves what it recognises.",
                "Target Home: level, FW, ENC, device, network, clan. Software: downloads. Wallet: proves the wallet address. Logs: steals, wallets, software. Bypass: FW/ENC. Processes: new IPs. Crew: clan tags. Your Home and Store: your own profile.",
                "Masked IPs and your own 0.0.0.0 are never stored as targets.",
            ),
            h("Wallets"),
            bullets(
                CONFIRMED,
                "A target's Wallet screen is proof: it replaces every other wallet on that target.",
                "Wallets from logs are only suggestions; a target may hold several until you scrape its wallet page.",
                "Every wallet change is logged to wallet_conflicts.jsonl.",
            ),
            h("Income estimate"),
            p("Your own Stole lines are grouped into sessions split at gaps over 1 hour. Each session's rate (all steals except the first, divided by hours) is averaged and shown as ₵/hr.", CONFIRMED),
            h("Search"),
            bullets(
                CONFIRMED,
                "lvl 30 (or level 30, lv30): players at level 30. lvl 28-32 for a range. Lowest Firewall first.",
                "sw 20 (or software 20, all 20): where to download Lv 20 software. Targets with the most apps at exactly Lv 20 come first; a target's own Firewall level isn't counted, and among equals the lowest Firewall, then Encryptor, wins. A Firewall 1 target with everything else at 20 tops the list.",
                "fw 20, enc 20, av 20, pc 20, bp 20, spam 20, siphon 20, rootkit 20, proxy 20, trace 20 (or the full name, with or without lv): one app at that level; if nobody has it, ±1 then ±2. Closest level first, then lowest Firewall.",
                "Anything else matches IP, username, clan, hardware, wallet, app name, or an exact player level.",
            ),
            h("Good Spam & Recon"),
            bullets(
                CONFIRMED,
                "Good Spam ranks targets where your best Spam hits 100/hr first, then the rest by income; within that, the fastest upload (weakest device, lowest Spam level needed), then the lowest Firewall. Your own level plays no part.",
                "Recon walks you through targets that are missing a wallet or core software.",
                "Validate re-checks the oldest targets first.",
            ),
            h("Siphon Alerts & Raw Dumps"),
            bullets(
                CONFIRMED,
                "Scroll your whole SIPHON EARNINGS page: any known siphon that's gone triggers an alert.",
                "Raw Dump Mode (Settings → Debug) records each scrape for troubleshooting; tap a dump to copy it.",
            ),
            p("Backups: every save also writes Downloads/DarkIndex/darkindex_database.json.", CONFIRMED),
        ),
        related = listOf("logs", "spam", "siphon")
    )
}
