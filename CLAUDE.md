# CLAUDE.md — DarkIndex (HackExLogger)

Context for Claude working in this repo. Last full review: **2026-11-19**, updated through **2026-11-21**. That review read every source, test, resource, build and CI file, ran the JVM test suite, ran a local corpus of 2,862 real screen captures (git-ignored, `local-data/`) through the parsers, and cross-checked hackex.net/guide.

**DarkIndex** is an offline Android companion for the mobile game **HackEx 2**. The player taps a floating 📸 button, an AccessibilityService snapshots the visible text of the game screen, regex parsers turn it into target intel, and the app stores it locally for search, ranking, odds, upload-time and income estimates.

- Package / applicationId: `com.whisk.hackexlogger`. Repo: `DarkIndex` on GitHub. Gradle root name: `DarkIndex`.
- One `app` module. AGP **9.5.1** (built-in Kotlin, so no `kotlin-android` plugin is applied). Gradle wrapper **9.9.0**, daemon toolchain JVM 25, Java target 11.
- `compileSdk/targetSdk 35`, `minSdk 26`.
- Deps: Gson 2.10.1, kotlinx-datetime 0.6.1, Flexbox 3.0.0, RecyclerView 1.3.2, AppCompat, Material, ConstraintLayout, activity-ktx, core-ktx.
- **No INTERNET permission. Never add networking, analytics or cloud SDKs.**
- `GEMINI.md` (local-only, not committed) is the older long-form reference (empirical XP/price logs, post-mortems). It is **partly stale**. Code beats GEMINI.md, and verified data beats both. See "Source reliability" below.
- **Work tracking:** `TODO.md` (owned by the user) holds the open items **and their implementation plans**. Add new ideas there and don't execute them unless asked. Keep it short: when an item is done, move it to the one-line Done log and record any lasting facts here in CLAUDE.md. `docs/plans/` is the developer's local-only scratch folder (not committed); don't reference it from committed files.
- **Developer preference:** queue work as to-do items first. Implement only when explicitly asked.
- **Bugs found while working (developer, 2026-11-20): fix them on the fly**, don't add them to TODO.md. If unsure it's really a bug, ask for context first: the game is dynamic, so a "bug" may be a screen, layout or player you haven't seen before. Only **new ideas** go on the to-do list.
- **Raw Dump Mode is a dev tool for one dump at a time** (targeted troubleshooting). No bulk-copy or wrong-app features. Developer-declined ideas (don't re-propose): `.dev` build variant; wallet hot/cold balance tracking (conflicts when you drain before scraping); REP/SCORE storage; log-based deployment tracking (the game shows deployments; Siphon tracker + planned Spam tracker cover it); badge-derived own levels.

---

## 1. Commands

```bash
./gradlew assembleDebug          # build APK
./gradlew testDebugUnitTest      # JVM unit tests (app/src/test) — 195 tests, all passing as of 2026-11-22
./gradlew connectedAndroidTest   # instrumented tests (device/emulator); read-only, never touch saved data
./gradlew assembleRelease -PversionName=1.6   # versionCode is automatic
```
- **Versions are automatic** (2026-11-20): versionCode = 1000 + `git rev-list --count HEAD`; versionName = latest `v*` tag + `-dev.<commits>` (or `dev.<commits>` with no local tags; `git fetch --tags`). `-PversionName/-PversionCode` or env `VERSION_NAME/VERSION_CODE` override. No git → 1000 / `dev` + a warning.
- **Signing (since 2026-11-19):** one permanent key signs **debug and release** builds (signing config `darkindex` in `app/build.gradle.kts`), read from git-ignored `keystore.properties` (template: `keystore.properties.example`; use forward slashes in paths) or env `SIGNING_STORE_FILE/SIGNING_STORE_PASSWORD/SIGNING_KEY_ALIAS/SIGNING_KEY_PASSWORD`. Without them, builds fall back to the machine debug key and print a warning. **Never change the key or applicationId**: that forces an uninstall, which wipes the DB.
- **CI** (`.github/workflows/android.yml`) runs on a `v*` tag push or manual dispatch. It uses JDK 17, decodes the key from secret `SIGNING_KEYSTORE_BASE64` (+ 3 password/alias secrets), **fails if any are missing**, runs `assembleRelease`, prints the APK cert SHA-256 (enforced if repo variable `SIGNING_CERT_SHA256` is set), **runs `testDebugUnitTest` first (no release if a test fails)**, uploads `DarkIndex.apk` and creates a GitHub Release. "Run workflow" has a **Release type** dropdown (2026-11-22): **minor** (default) bumps the last number of the highest `v*` tag (1.6.1 → 1.6.2, 1.7 → 1.7.1), **major** bumps the middle number and drops the last (1.6.5 → 1.7). An optional exact version overrides it; an existing tag is refused. `.github/workflows/tests.yml` runs the unit tests on every push and PR (no secrets needed) and uploads the HTML report. Claude cannot write files under `.github/` on the developer's PC; hand the developer the file instead.

### Running tests without the Android SDK (cloud sandboxes)
Google Maven and services.gradle.org may be blocked. The pure-logic code can still be compiled and tested with the Kotlin compiler bundled in a local Gradle install (`/opt/gradle/lib/kotlin-compiler-embeddable-*.jar` + gson, junit, hamcrest, coroutines from the same folder):
- Write tiny stubs for `android.content.{Context,ContextWrapper,SharedPreferences,Intent}`, `android.os.Environment` (return null), `android.util.Log`, `kotlinx.datetime.{Instant,Clock}` and `androidx.recyclerview.widget.DiffUtil.ItemCallback`.
- Compile `TargetRecord, ConsoleBundle, ScreenParsers, GoodSpamUtils, NetworkSpeedUtils, PlayerProfileRepository, TargetRepository, DatabaseManager, SiphonTracker, HardwareCatalog, GameFormulas, KnowledgeBase, KnowledgeBaseContent, Marathon, RawDump, ScrapePipeline, TargetSearch`, plus all of `src/test`. Write the `android.content`/`android.os` stubs in **Java** (Kotlin stubs clash with property syntax like `context.filesDir`).
- Run with `org.junit.runner.JUnitCore`, with `app/src/test/resources` on the runtime classpath (fixtures). Reproduces the full suite.

---

## 2. Source reliability (what to trust)

| Source | Trust | Notes |
|---|---|---|
| Code + passing tests | Highest for app behaviour | |
| Formulas verified against real numbers (§4) | High | Store price and score formulas matched to the crypto |
| hackex.net/guide | Medium | Dev-published, mostly right, but the user says it can mislead, and it contradicts itself in places (Siphon flows, AV scan times). Read in full and exactly on 2026-11-21 (the `DarkIndex` cloud environment allows hackex.net); the guide's numbers now live in `KnowledgeBaseContent`/`GameFormulas`; `docs/knowledge-base-prep.md` is the developer's review tracker |
| GEMINI.md empirical sections | Medium | Some tables contradict the code (Spam payouts, clustering) |
| GEMINI.md architecture sections | Low | Several statements are stale (§9) |

---

## 3. How the game works (domain model)

### Core loop
Scan → **bypass Firewall** with Bypasser → **crack Encryptor** with Password Cracker → then any of: steal Hot Wallet crypto, download better software (unlock it with Keygen), or upload viruses (Spam/Siphon/Rootkit). XP comes **only** from spending (upgrades, Keygen unlocks), plus missions and login streaks, never from hacking. Rep comes from hacking.

### Identities on screen
- **IP**: pseudo-IPv4 (e.g. `12.34.56.78`). GEMINI.md claims octets can exceed 255, but none were seen in 786 real targets. `0.0.0.0` / `0.0.*` is your own localhost (AV scans) and must never be stored.
- **Masking**: Proxy Lv1–5 masks the last octet, 6–10 two, 11–15 three, 16–19 all (`xxx.xxx.xxx.xxx`). Lv20+ unlocks Stealth. `[UNKNOWN]` appears for Ghost-loadout players. A victim's Trace may unmask an attacker and append ` [TRACED]`.
- **Own log can show masked targets too** (e.g. `Uploaded Lv20 Spam to xxx.xxx.xxx.xxx`).
- **Wallet**: `hx` + 30 hex = 32 chars. Logs show `hx1a2b...9f0e`, and the app normalises everything to `first6...last4`.
- **Clan tag** `[TAG]` (2–10 chars) shows on the profile. The crew screen shows IPs + `LEADER/OFFICER/MEMBER` only.

### Software (11 apps)
| App | Role | Guide base price | Notes |
|---|---|---|---|
| Firewall | defence vs Bypass | 800 (×0.85) | Upgrading it invalidates incoming bypass and crack rows. Lv3+ "hardening" blocks Backdoors 3%/lvl above 2, max 90% |
| Password Encryptor | defence vs Crack | 1100 (×0.85) | Upgrading invalidates cracks. Hardening blocks Exploit Kits the same way |
| Antivirus | removes viruses | 1000 (×0.85) | Restores 90% of rep drained. Removal odds use their own table (§4) |
| Proxy | masking / Stealth | 900 (×0.85 **verified**, dump #6: Lv21 = 40,046) | |
| Trace | unmasks proxies | 900 (×0.85 **verified**, dump #6) | Defensive roll on every entry, offensive roll at bypass |
| Bypasser | offence vs Firewall | 800 | |
| Password Cracker | offence vs Encryptor | 1100 | |
| Spam | virus, passive income | 1800 | ≤100 Crypto/hr per victim, set at upload by victim level vs Spam level (§5.7). Full rate for 21 days, decays to a 10% floor around day 100 |
| Siphon | virus, skims victim's flow | 1600 | **0.75% + 0.25% × level** (Lv1 1%, Lv20 5.75%, 6% from Lv21; measured, Raw Dump #16). Max 700 deployments. Combined cap 30% per victim. No decay. Cold-storage deposits bypass it. Developer: theft is likely the only flow skimmed (no siphon loss seen on Spam income; to re-check, Q-02) |
| Rootkit | virus, debuff | 1400 | Victim's defences −1 (Lv1–2), −2 (Lv3–5), −3 (Lv6+, cap) effective levels for you and your crew (measured 2026-11-22; Lv4–5 by elimination; `GameFormulas.rootkitDebuff`). Only the highest rootkit applies |
| Keygen | device-bound unlocker | 600 | Never downloadable. Key fee is 15% of target-level Store price at jump 1, rising linearly to 50% at a 20+ jump. Full XP. +5 rep |

- **You choose the upload level of a virus** (any level up to your installed one). Lower uploads faster, so the goal is the **minimum Spam level that hits the 100/hr cap**, not your max level (developer uploads Lv17 Spam with Lv20 installed).
- At most **2 virus slots per victim per attacker** (`+ UPLOAD VIRUS 2 / 2 slots`). Many attackers can infect one device.
- Viruses are locked at their upload level. To upgrade, uninstall and re-upload.
- **Consumables:** Backdoor (instant bypass), Exploit Kit (instant crack), IP Scrambler, System Purge, EMP Pulse, Wallet Shield, Log Lock.

### Hardware
- **Device** (CPU) affects your bypass, crack and AV speed, and **as a victim** it scales how long others take to upload to you.
  - Order: `Raider, Raider II, Raider III, Bolt, Bolt II, Bolt III, Nova, Nova II, Nova III, Nova S, Nova X, Nova Ultra`.
  - Guide speed multipliers 1.0, 1.5, 2.25, 3.5, 5, 7, 9, 11, 14, 17, 20, 23 are **your process speed** (bypass/crack/AV). They are **not** upload defence.
  - **Upload defence (developer-confirmed, rigorous):** Raider, Raider II and Raider III upload identically, which gives the 10 defence tiers 0–9 used in code. The guide does not document upload defence.
- **Network** affects your downloads and uploads.
  - Order: `DSL 1.0, Cable 1.5, Cable+ 2.25, 4G LTE 3.5, 5G 5, 5G+ 7, Fiber 9.5, Fiber+ 12, 10G Fiber 15, 10G Fiber+ 18, Quantum Link 21` (multipliers).
- Upload time ≈ `base(target device) / your network boost`. Concurrent processes do get slower (guide: +75% per extra process). **Two separate pools** (developer, 2026-11-22): network (uploads + downloads slow each other) and processor (bypasses + cracks slow each other); a bypass does not slow an upload. The app **deliberately does not** model concurrency, because it can't know how many processes are running. Don't add it.
- **Virus performance penalty:** each active virus *on a device* cuts that device's performance by 1%, capped at 40%. The guide says this applies to the infected player.
- Stored as `TargetRecord.hardware = "<Device> • <Network>"`.

### Prestige ("Reboot Protocol")
- Unlocks at **Level 50**. Resets level, rep, software, crypto, botnet, siphons/rootkits, cracks, processes and IP.
- Keeps R-tier, badges, Credits, OC, inventory, crew and LIFETIME score.
- The home screen shows `R1`.. above `LVL` and `<n> LIFETIME` under SCORE.
- **R1–R5** must use each of 5 loadouts once. **R6+** can mix any device line with any network line.
- Loadouts and perks per the guide:
  - Ghost (Stealth): `[UNKNOWN]` in logs, Trace-immune. Proxy counts +5 vs Trace and lasts 2×.
  - Vector (Efficiency): 50% OC discounts and consumable perks.
  - Scythe (Economy): 2× siphon drain, half spam decay. Uploads 50% faster, viruses count +2 vs AV.
  - Cipher (Defense): FW/ENC count +3. 100% Backdoor/Exploit block.
  - Apex (Mastery): Bypasser/Cracker count +3. Scans reveal ENC, downloads 40% faster.
- **Loadout hardware names (guide):** Ghost + **Ghost Link**, Vector + **Contra Band**, Scythe + **Harvest Node**, Cipher + **Void Net**, Apex + **Null Route**.
  - Names seen in real data: `Vector`, `Scythe IV`, `Apex VII` (Store `LVL 7`), `Contra Band III`, `Harvest Node IV/X`, `Null Route IX/X` (Store `LVL 10`).
  - The numeral equals the level, and is the same speed as the standard item at that position.
  - Networks max at tier 11. At max, the Store shows the current item with `MAX TIER` instead of a price.
  - GEMINI.md's names `Dark Routing, Backchannel, Fast Deploy, Deep Link` are wrong (they look like perk names). All names now live in `HardwareCatalog` (§5.10).

### Economy & progression
- **Steal limits (developer, 2026-11-22):** no minimum or maximum per steal (the whole hot wallet can go in one transfer); there seems to be a daily maximum, amount unknown (Q-11).
- **Gas fee:** victims lose 100%, the thief receives 90% (guide). The "Stole N" log shows **what the victim lost** (developer). That's fine because income is only compared across targets.
- **Cold storage cap** by level: 3,000 (L1), 8,841 (L5), 29,124 (L10), 121,891 (L20), 294,147+ (L30+).
- **XP to next level:** L1 589, L5 2,605, L10 5,919, L20 14,209, L30 24,079, L35 29,484, L38 32,860, L44 39,888.
- **Over-levelling hurts:** matchmaking is about ±10 levels. (Spam income ignores your level: §5.7.) Hack rep falls off at a +6..+9 **software** gap (your offence vs their defence software, not player levels) and is zero at +10 (guide).
- **Botnet** tiers (spam slots): Free 2, Node 4, Cluster 8, Grid 16, Farm 28, Hive 44, Nexus 64. There is a scaling fee on spam income up to 30% at Nexus. The Store shows e.g. `Botnet - Nexus / 64 slots / MAX TIER`.
- **Downloaded apps are activated manually** (developer, 2026-11-20). Activation writes **no log line**, so installed levels can rise with no `Purchased`/log evidence. **Both the Apps screen and the Store are reliable for your levels** (developer, 2026-11-20): Apps shows the installed level directly (plus `LOCKEDLVL N available <price> Crypto` for a downloaded version waiting to be activated, and `(+N)` bonuses); the Store shows next level, so subtract 1. They only disagree when one is stale (in dump #8 the developer had upgraded since the last Store scrape). The app currently only scrapes the Store.
- **XP from purchases:** one data point: Keygen Lv26 (41,459 Crypto) → +7,045 XP (0.170 XP/crypto, close to GEMINI's 0.1717 for software in §4); Rep and Score unchanged. Score = floor(L × Rep^0.75) re-confirmed on two own-Home scrapes.
- **Rep loss** per virus = min(500, 25×virus level). Daily loss cap 500, floor 50% of peak. AV restores 90%.

---

## 4. Verified formulas & tables

**Store price — VERIFIED to the crypto on 5 real prices** (Firewall 21 = 35,596, Bypasser 21 = 41,877, Encryptor 19 = 42,973/4, Keygen 21 = 31,408, Keygen 25 = 39,398):
```
Cubic(L) = (-L³ + 147L² + 6814L - 10212) / 6          # = Keygen price
Price(app, L) = Cubic(L) × base(app)/600 × (0.85 if defensive)
base: Keygen 600, Firewall 800, Bypasser 800, Cracker 1100, Encryptor 1100,
      Antivirus 1000, Spam 1800, Siphon 1600, Rootkit 1400, Proxy 900, Trace 900
```
The cubic is only valid at higher levels (verified L19–25). It goes **negative at L1**, so don't use it below roughly L5 without data. GEMINI.md's claim that the cubic is the "offensive" price is wrong: it is the Keygen price.

**Leaderboard score — VERIFIED on 6 profiles:** `Score = floor(Level × Rep^0.75)`.

**Keygen XP (measured, developer logs; badged MEASURED in the KB):** software XP is ≈ 0.1717 XP per full Store crypto, awarded in full on Keygen unlocks. Device upgrades give 0.25 XP/crypto, older hardware/network 0.20.

**Hack (bypass/crack) success — measured from the developer's logged attempts, in code (`TargetRecord.calculateSuccessProbability`):** modifiers (Apex +3, Rootkit −N, Cipher +3) just add up, order doesn't matter.
`delta = offence − defence`: ≥+6 → 95, +5 → 90, +4 → 82, +3 → 74, +2 → 66, +1 → 58, 0 → 50, −1 → 42, −2 → 34, −3 → 18, ≤−4 → 5.

**Guide-derived exact fits (2026-11-21, reproduce every published cell):** Proxy activation `ceil(10 × √ProxyLv × hours × (1 − disc))` (2/6/12/24 h, 0/5/10/15%); Spam slot fee `min(30%, 0.5% × (activeSpam − 2))`; Spam decay `100 − (day − 21) × 90/79` %, floor 10%; Spam income: the guide curve `8.85 + 2.89L + 0.0415L²` × gap multiplier (×1.41 at +10, ×0.5 at −10) hits all 17 guide cells, **but in the game the gap is victim level − Spam level and the curve is different** (measured 2026-11-22, §5.7). Store prices: offensive round to nearest (Bypasser 21 = 41,877.33 → 41,877), defensive ×0.85 round up (Proxy 21 = 40,045.2 → 40,046); Encryptor 19 → 42,975 vs the recorded "42,973/4" is unresolved. All in `GameFormulas`. The Store cubic misses the guide's low-level prices (Keygen L5 4,862 vs 4,568).

**Siphon % (measured 2026-11-22, Raw Dump #16, all 122 deployments):** `0.75 + 0.25 × L` fits Lv1/2/5/6/13/17/20; Lv25 = 6% (not 7%), so `GameFormulas.siphonPercent` caps at 6% from Lv21 (the line reaches 6% at Lv21; developer-accepted). **Max 700 siphon deployments** (developer). A siphon removed by the victim's AV vanishes from SIPHON EARNINGS with all its info; the game keeps no history.

**AV removal (guide, not in code):** AV−virus ≥+5 → 95, +4 → 90, +3 → 80, +2 → 70, +1 → 60, 0 → 50, −1 → 20, then −2% per level to a 5% floor at −9.

**Upload base seconds** (`NetworkSpeedUtils`, from GEMINI benchmark: Lv20 virus vs a 15× network, ×15 to normalise to DSL):
tier 0..9 → 45,285 / 60,390 / 75,480 / 90,585 / 105,720 / 120,825 / 135,930 / 151,035 / 166,140 / 181,245 s, about +15,100 s per tier. Virus level scaling is `(L/20)^2.25`.

---

## 5. App pipeline & code map (`app/src/main/java/com/whisk/hackexlogger/`)

```
OverlayService (📸, TYPE_APPLICATION_OVERLAY, FGS specialUse)
  └─ tap (ACTION_UP, not a drag) → ScraperAccessibilityService.instance.scrapeAndParse()
        (fallback: broadcast TRIGGER_SCRAPE)
        main thread: rootInActiveWindow → recursive extractText (node.text only, not contentDescription)
        Dispatchers.Default: join "\n" → ScreenClassifier → <X>ScreenParser → DatabaseManager.process<X>
        → broadcast SHOW_TOAST (HUD pill "Saved: …") + DATA_UPDATED (legacy, unused)
TargetRepository: cachedRecords (synchronized) → prefs "HackExDB"/"targets" (Gson)
                  → mirror ConsoleBundle to Downloads/DarkIndex/darkindex_database.json on every save
                  → recordsFlow (StateFlow) → MainActivity → TargetAdapter
```

| File | Role |
|---|---|
| `ScreenParsers.kt` | `SharedRegex`, `ScreenClassifier`, `CommonParserUtil` (`isMaskedIp`, `stripNavBar`, `decodeEntities`), `LogTime` (UTC log times), plus parsers: Home, OwnHome, Log, Software, Wallet, Siphon, SpamEarnings, Crew, Process, Store, Bypass |
| `HardwareCatalog.kt` | Single source of truth for device/network names, levels, network boost and upload-defence tier (§5.10) |
| `ScraperAccessibilityService.kt` | On-demand only (`onAccessibilityEvent` is a no-op). Main thread: text snapshot (+ node tree in dump mode). Background: `ScrapePipeline.run`. Pipeline exceptions are caught and toasted ("Scrape error: …") instead of killing the coroutine. Logs `DOSSIER_RAW` / `DOSSIER_BENCHMARK` |
| `ScrapePipeline.kt` | Classify → parse → `DatabaseManager.process*` for one snapshot (moved verbatim out of the service; JVM-testable). `UNKNOWN` runs all 10 parsers in the original order. Takes an optional `DumpSession` |
| `RawDump.kt` | Raw Dump Mode (JVM-testable): `RawDumpSettings` (`HackExSettings`/`pref_raw_dump_mode`), `RawDump` model (JSON field order = declaration order), `RawDumpSummary`, `DumpSession` (installs `DatabaseManager.activeDiscardSink` + `TargetRepository.walletConflictListener`, diffs DB, profile and siphons before/after; serialized by `DumpSession.LOCK`), `RawDumpStore` (`filesDir/raw_dumps/<id>.json` + `<id>.meta.json` + `next_id`; ids are never reused; `exportAll` streams `{format:"darkindex-raw-dumps",…,dumps:[…]}`; Gson with `disableHtmlEscaping` so `&#39;` survives) |
| `RawDumpCapture.kt` | Android-only: app version (`PackageInfoCompat`) and the flattened accessibility node tree (`d, cls, id, text, desc, bounds`; cap 20k nodes) |
| `RawDumpsActivity.kt` | Dump browser: cards (big local timestamp, `#id · UTC · package`, type chip with amber for fallback, result, matched parsers, log stats amber when anything was discarded, errors in red), tap = copy that dump's **lean** JSON (toast shows the size and warns above 20,000), ▸ = expand, long-press → DELETE, EXPORT ALL (SAF `.json`), DELETE ALL (confirm) |
| `DatabaseManager.kt` | Facade. `process*` methods map screens into repos and call `reconcileDatabase()`. `parseLogTime` delegates to `LogTime` |
| `TargetRepository.kt` | Cache/prefs/Downloads mirror (ordered background save queue), `mergeRecords`, `reconcileDatabase` (O(N²), skipped unless a username/wallet is shared), validation queues, `editTargetIp`, maintenance, 30-day purge, import/export, `wallet_conflicts.jsonl` |
| `TargetRecord.kt` | Model, `isMasked`/`isTemporary`/`needsAdvancedTargeting`, theft clustering/income, success odds |
| `PlayerProfileRepository.kt` | YOUR stats in prefs `HackExPlayerProfile`/`player_profile`: username, level, nextDevice, nextNetwork, botnet, software **next** levels |
| `GoodSpamUtils.kt` | Device tier, Good Spam score, firewall eligibility, Spam payout (delegates to `GameFormulas`) |
| `GameFormulas.kt` | Pure game maths shared by the app and the Knowledge Base: Spam income/decay/fee, botnet tiers, Store/Keygen prices, proxy cost and masking, AV odds and scan time, hardening, hack odds, rep and score, process speed-ups, device/network prices. Each function names its source |
| `NetworkSpeedUtils.kt` | Network boost table, base upload seconds, level scaling, duration formatting |
| `SiphonTracker.kt` | Known siphons and amounts, first-seen times, alerts, `siphon_history.jsonl` |
| `OverlayService.kt` | 📸 button (drag to the bottom ✖ to stop the service), HUD toast, Validation toolbar (✓ / 🗑 / ✕), Recon toolbar (⏭ skip / ✓ / 🗑 / ✕). Copies the IP to the clipboard |
| `TargetSearch.kt` | Dashboard search (pure, tested): `lvl 30`/`lvl 28-32` player level; `sw 20` = most loot apps (all but Firewall) at exactly Lv20, then lowest FW, ENC, near misses; `<app|alias> 20` one app, ±1/±2 fallback, closest then lowest FW; else text. Results keep this order (MainActivity skips the IP sort while searching) |
| `MainActivity.kt` | Dashboard: search (via `TargetSearch`), Good Spam switch + FW slider, Recon switch, VALIDATE/RECON FAB, ⋮ menu (Settings, Export, Import, Maintenance, Wipe (type-`WIPE` confirm), Reset Siphon Tracker), Siphon Alerts button, Android 13 restricted-settings onboarding |
| `TargetAdapter.kt` | `ListItem` (Header/Target) + `ListItemDiffCallback`. Card rows: IP (tap copies) / username / wallets + income; chips LVL FW ENC DEV NET CREW; odds chips (Bypass %, Crack %, tap for detail); "⏱ Upload Times" dialog with Spam payout advice; DEFENSE (FW, ENC, AV, Proxy) / OFFENSE (Bypasser, Cracker, Trace) / VIRUSES grid. Long-press opens EDIT / SHARE / DELETE |
| `SettingsActivity.kt` | Shows the parsed player profile (current = next − 1). Purge thefts > 30 days |
| `SiphonAlertsActivity.kt` | Alert list: "Siphon Re-installed" clears the alert. "IP Not Found" clears it and deletes the target |
| `ConsoleBundle.kt` | Export schema `{format:"target-intelligence-console", version:1, exportedAt, targets, ingests[{type,raw,importedAt,id}], siphonAlerts, knownSiphons}` |
| `Marathon.kt` | Marathon Mode engine (pure, JVM-tested): phases, own-log steal collection, per-wallet income rows, chain, `marathon_history.jsonl` (§5.11) |
| `MarathonOverlay.kt` | Marathon UI owned by `OverlayService`: draggable 🏃 bar (timer / big red `0` + 400 ms buzz / prompts / buttons) and the draggable, scrollable results panel |
| `KnowledgeBase.kt` | KB model (`KbTopic`, `KbBlock`, `KbSource` badges: MEASURED, CALCULATED, GUIDE, CONFIRMED, EMPIRICAL, UNKNOWN; developer renamed OWNER→CONFIRMED and EXACT FIT→CALCULATED 2026-11-22), search, and `KbCalculators` (9 calculators as pure input specs + compute functions; defaults from the player profile via `KbPlayerDefaults`) |
| `KnowledgeBaseContent.kt` | The 25 topics, in Kotlin (developer choice). Tables are generated from `GameFormulas` where a formula exists. Open questions carry Q-ids (Q-01…Q-14; answered ones are deleted) listed in `docs/knowledge-base-prep.md`, the developer's human-readable review tracker (rewritten 2026-11-22: review progress by topic + open questions numbered in topic order; covered items are deleted, not struck through). When a question is answered, delete it from the doc and the topic; don't renumber again unless the developer asks |
| `KnowledgeBaseActivity.kt` / `KnowledgeTopicActivity.kt` | 📖 button left of Siphon Alerts → index (search, badge legend, topic cards with source tallies) → topic page (facts, tables, tips, warnings, live calculators, open questions, related chips). `KbViews` = shared view factories |

### 5.1 Classifier (first match wins, case-insensitive substring)
1. `OWN_PROFILE`: "my device" AND ("daily missions" OR "login streak")
2. `BYPASS`: "using bypasser" OR "// bypass firewall" OR ("// scan results" AND "fw lv")
3. `STORE`: "proprietary key generator built for your device"
4. `SOFTWARE`: "installed software"
5. `WALLET`: "wallet address" OR "'s wallet" OR "’s wallet"
6. `SPAM_EARNINGS`: "SPAM EARNINGS" (before Siphon: both pages start with the Apps page text)
7. `SIPHON`: "siphoned" AND "deployed"
8. `LOGS` (`isLogScreen`): log headers (`// LOG`, `~/log.txt`, `// VICTIM LOG`, `~/victim_log.txt`), the old access/crack/bypass phrases, or any `[M-D H:MM]` line with a known action (stole, uploaded, downloaded, failed, purchased …)
9. `PROCESS`: a `// PROCESSES` line OR `N active tasks`
10. `HOME`: "firewall" AND ("encryptor" | "ip" | "device")
11. `CREW`: a line equal to "LEADER" or containing "Members"
12. `HOME` (IP + FIREWALL Lv regex)
13. `UNKNOWN` → run all parsers. Crew needs a `// … Members` header and a LEADER line, Process needs `// PROCESSES` or `active tasks`, and both strip the bottom nav bar first, so the fallback can't tag random IPs.

`ScrapePipeline.run` decodes HTML entities (`&#39;` etc.) before classifying.

### 5.2 Real screen text formats (from the real captures)
**Home (target, connected):** each item is on its own line:
`CONNECTED`, `DISCONNECT`, `>`, `<username>`, `_`, optional `[TAG]`, `badge`, optional `R1`, `LVL 38`, `//`, `REP 6,000`, `25,000 SCORE`, optional `90,000 LIFETIME`, `// XP PROGRESS`, `16000 / 32860`, `49%`, `Wallet` `Apps` `Processes` `Log` `Crews`, `// SYSTEM INFO`, `IP`, `<ip>`, `DEVICE`, `Nova`, `NETWORK`, `Fiber`, `FIREWALL`, `Lv.24`, `ENCRYPTOR`, `Lv.25`.
- `REGEX_USER_HEADER` (`^\s*>\s*(.+?)$`) works because `\s*` spans the newline after `>`.
- The clan is found by the generic `[..]` fallback.

**Own home:** `> Name_`, `MY DEVICE`, `R<n>`, `LVL <n>`, REP, SCORE, `// CRYPTO`, `// CREDITS`, `// DAILY MISSIONS`.

**Software:** `<user>'s installed software`, `+`, `UPLOAD VIRUS`, `2 / 2 slots`, `▼`, then for each app: a lower-case id line (`antivirus`), `Antivirus`, description, `LVL 24`, delta badge (`=`, `+2`, `-6`, or `DEVICE-BOUND` for keygen).

**Wallet:** `<user>'s wallet`, `WALLET ADDRESS`, `<32-char addr>`, then `HHOT WALLET100 CryptoCCOLD STORAGESecured500 Crypto/ 29,124 max` all on one line.

**Store:** `// STORE`, `Notepad`, `Wallet:`/`<crypto>`, `Credits: <n>`, `UPGRADES/CREDITS/MARKET`; `// NEXT DEVICE`, `Apex VII`, `LVL 7`, specs, price; `// NEXT NETWORK`, `Null Route X`, `LVL 10`. **When maxed, the header drops NEXT** (`// NETWORK`), shows the item you own, and has `MAX TIER` instead of a price (dump #6: `Null Route XI` / `LVL 11` / `MAX TIER`). Software rows have view ids `sw-<n>`; `// BOTNET`, `Botnet - Nexus`, `64 slots`, `MAX TIER`; `// SOFTWARE`, then `<App>` `LVL <next>` description, price, `UPGRADE`. Keygen's description contains the STORE signature.

**Scan (Raw Dump #24, fixture `scan_2026-11-22.txt`):** `// SCAN`, Notepad, `Wallet:`/`<crypto>`, `Credits:`/`<n>`, `SCAN`, `REFRESH SCAN`, `// SCAN RESULTS`, `12 targets` (10 default + crew Uplink +2), then one row per target `1.2.3.4 FW Lv.23 ENC Lv.24 >`; rows can be masked (`xxx.xxx.xxx.xxx`, `226.217.xxx.xxx`); **ENC is not default**: it shows because the developer is on an Apex reboot (guide: Null Route perk), so default rows are presumably `<ip> FW Lv.N >` (unseen; the row regex makes ENC optional); ends with the bottom nav. `BypassScreenParser` stores only the first unmasked row, with that row's own FW/ENC (fixed 2026-11-22: it used to take the first FW/ENC on screen, even from a masked row). Scraping every row is a to-do.

**Bypass:** `// BYPASS FIREWALL`, `Firewall Lv.23`, `Encryptor Lv.24`, `Using Bypasser Lv.20`, `(+3)`, `Use Backdoor x<n>`.

**Siphon:** `SIPHON EARNINGS`, `DEPLOYED 55`, rows `<ip> LV.10 5% 1,000 siphoned 1h ago`. Only about 6 rows are visible at once.

**Process:** `// PROCESSES`, `109 active tasks`, then blocks `Password Crack `/`Lv.8`/`DONE|INVALIDATED`/`<ip>`; downloads are `Download `/`Lv.6`/`DONE`/`DL:`/`Rootkit`/`<ip>`.

**Crew:** `// <Crew Name> Members`, then `<ip>` / `LEADER|OFFICER|MEMBER` pairs.

**Own crew page (Raw Dump #25, fixture `crew_server_2026-11-22.txt`):** role, `LEAVE`, crew name, `#TAG`, `SERVER ONLINE`, `37 / 40 members`, buffs, `CREW TREASURY` (`Upkeep 19,325/day (805/hr) · runway 42d 22h`), top contributors, `CREW SERVER` `LV 16 / 25`, then per part: name, `ON`, `L3`, effect, `<fund> / <next price>` (e.g. `829.8K / 1.1M`), next level + effect; `SERVER LOG` lines (`X contributed +N`, `Daily upkeep −N`). Classified CREW, saves nothing (no roster). **Crew server maths (measured from the crew's complete server log, Raw Dump #27, which balances to the treasury to the crypto):** price to reach part level k = base × k² (CPU/NIC 70K, RAM 120K, Uplink 45K, Storage 60K); upkeep/day = 0.5% of the total spent (Storage none), billed about daily for the hours since the last charge; surge (+2 levels, CPU/NIC/Uplink only) = base × ((L+1)² + (L+2)²) × hours/1200 × (1 − 0/5/10% for 6/12/24 h; 6 h no discount, developer-confirmed); extending = same price; ending early = no refund. Prices on screen are rounded **down** to one decimal (RAM L3 1.08M shows "1M"). Server log line formats: `X contributed +N`, `X upgraded <Part> to L<n> −<price>`, `X started a <Part> Surge (24h) −N`, `X extended the <Part> Surge by 24h −N`, `X ended the <Part> Surge early (16h left)`, `X powered on <Part> · toggled 2×`, `Daily upkeep −N`, `X joined the crew` + `24H TENURE GATE`, `X is now getting crew buffs`, `X was removed from the crew`, `✓ BUFFS LIVE`; each followed by `<n>m|h|d ago`. Daily contributions top up to exactly 50,000. `GameFormulas.crewPartUpgradePrice/crewPartUpkeep/crewSurgePrice`.

**Log lines** (`[M-D H:MM] …`, newest first). The parser handles:
- `Accessed device at <ip>` (own log)
- `Device accessed from <ip|masked|[UNKNOWN]>[ [TRACED]]` (victim log)
- `Stole N Crypto from <W>` (own log; the only lines counted as income)
- `N Crypto transferred to <W>` (victim log; W is the **attacker's** wallet)
- `Cracking/Cracked password on <ip>`
- `Bypassed/Failed to bypass firewall on <ip>`
- `Downloaded/Downloading/Uploaded/Uploading LvN <App> from|to <ip>`

- `Failed to crack password on <ip>` (touches/creates the target, like failed bypass)

Lines seen in real data but **ignored**:
- `Password being cracked by <ip>...`
- `LvN <App> being uploaded|downloaded by <ip>...`
- `Used Backdoor|Exploit Kit on`
- `Backdoor blocked by <ip>'s firewall` (the game emits HTML entities like `&#39;`; decoded before parsing)
- `Purchased Keygen LvN`
- `Upgraded network to Harvest Node X`

The last two could feed the player profile automatically (TODO: profile from own-log lines).

### 5.2b Raw Dump Mode (debug tool, one dump at a time)
- Toggle: Settings → Debug → Raw Dump Mode. While on, the 📸 overlay shows **📼 with a red ring**, the toast appends `· 📼 #id`, and the home toolbar shows **Raw Dumps** instead of Siphon Alerts.
- Every scrape is recorded, including null root, empty text and exceptions. **Scrapes still save to the DB normally** (no dry run).
- `LogScreenParser.parseDetailed()` returns updates + discarded lines with reasons. Parser stage: `NO_PATTERN`, `UNKNOWN_SOURCE`, `NO_TARGET_IP`. DB stage (via `DatabaseManager.activeDiscardSink`): `EMPTY_IP`, `WALLET_CONFLICT`, `MASKED_SOFTWARE_IGNORED`, `BAD_TIMESTAMP` (partial: the wallet is still linked). `parse()` == `parseDetailed().updates`, and `timestamped == updates + parserDiscards` always.
- The accessibility config now includes `flagReportViewIds`, so node-tree `id`s are populated.
- Game package: **`net.cncapps.hackex2`** (seen in dump #1).
- **The game UI is a WebView** (`android.webkit.WebView` → `android.view.View`/`TextView`/`EditText`), which is why game text contains HTML entities. A log screen is only ~20 nodes.
- **Log screen = one `EditText` holding the entire log** (dump #1: exactly 100 `[M-D H:MM]` lines, newest first), so one scrape captures the whole log, not just the visible part. **The game keeps only the last 100 lines** (developer-confirmed), and that includes non-entry lines such as ASCII-art graffiti or messages written to hackers. The log is editable text, so expect non-`[M-D H:MM]` lines and possibly hand-written/forged lines, especially in victim logs. Scrape often to avoid losing steals.
- **Log lines are NOT strictly chronological** (e.g. `[16:38] Uploaded…` sits below `[17:40]` lines). Entries are inserted when written, with the timestamp from when the device was accessed. Don't assume monotonic times.
- The log screen footer shows **`Last saved: YYYY-MM-DD HH:MM:SS` in UTC**, a full date usable to anchor log times.
- **Every screen's raw text ends with the bottom nav** `HOME`, `SCAN`, `PROCESSES`, `APPS`, `STORE`, `CREWS <n>` (n = unread crew messages). The nav buttons have view ids `nav-item-dashboard|scan|processes|apps|store|crews`. Parsers must never key on these words.
- **The bottom nav only appears on your OWN screens.** Target screens (Home/Wallet/Apps/Crew while CONNECTED) don't have it; they start with `CONNECTED`/`< back` + `DISCONNECT`.
- **Target Home (2026-11 format):** `> <user> _ [TAG]` on one line; `LVL` / `33`, `REP` / `20,000`, `SCORE` / `55,000` as label/value line pairs; SYSTEM INFO as label/value pairs. The Notepad button has desc `Notepad`, the avatar has desc `badge`.
- **Wallet:** the address is on its own line after `WALLET ADDRESS`; the balance line is mashed: `HHOT WALLET10,000 CryptoCCOLD STORAGESecured1,000 Crypto/ 362,654 max`; the transfer box has view id `steal-amount`.
- **Software:** `+ UPLOAD VIRUS 0 / 2 slots ▼` = **your** viruses on that target (0 after the target's AV wiped them; developer-confirmed); each app has an icon node whose desc is the lower-case app name, then name, description, `LVL n`, and a delta badge vs your **installed** level (downloaded-but-not-installed copies don't count). The parser **intentionally ignores** the badges.
- **Crew:** `// <name incl. emoji> Members`, then ip/role pairs. **Off-screen rows are still exposed** (bounds clamp to the screen height of 2244), so the whole roster is captured without scrolling. Rosters can contain masked IPs.
- Typical single-screen timing: extract 2–100 ms (main thread), parse + DB 300–400 ms (dominated by full-DB saves at 4,298 records).
- Dump #1 timing: extract 24 ms, parse+DB 401 ms (96 updates, 4,294 records).
- ⚠ Chat pastes are truncated at **20,000 chars**. Ask the developer to attach exported `.json` files for big dumps. On Android, Gson field order is not declaration order (dump #1 came out alphabetical).
- **Stored dump JSON** (`raw_dumps/<id>.json`, EXPORT ALL) has an explicit key order: summary → logStats → discardedLogLines → dbEffects → timing → interpretation → rawText → nodeTree. Dumps from build 1.5 (14) are alphabetical; everything reads keys by name, so both work.
- **Single tap copies the LEAN form** (`RawDumpJson.leanJson`, format `darkindex-raw-dump-lean` v1), always well under the 20,000-char paste limit (full 100-line log ≈ 10.6k). How to read it:
  - `dump` id, `utc`, `app` `"1.5 (14)"`, `pkg`, `screen`, `fallback` (only if true), `parsers`, `result`, `error` (only if any), `logStats`
  - `discarded`: `[[line, "stage:REASON"], …]`
  - `db`: dbEffects with empty lists dropped (siphon counts only if changed)
  - `ms`: extract/parse/total timings
  - `interp`: per parser; **`Log` is `{cols, rows}`, rows `[rawLineNo, ip, wallet, amount, ownTheft, software, ownedSoftware]`** where rawLineNo indexes `rawText.split("\n")`, and **trailing null/false columns are omitted** (`[12,"1.2.3.4"]`). An extra 8th column holds the line itself if it couldn't be mapped.
  - `rawText`: exact
  - `nodes`: `{cols:[d,cls,id,desc,text,x1,y1,x2,y2], total, rows}`, only nodes with text/id/desc, short class names, text/desc clipped to 60 chars.
- **When the developer sends dumps:** read `detectedType`/`fallbackUsed`, `parsersMatched`, `interpretation`, `discardedLogLines`, `dbEffects`, then `rawText`; use `nodeTree` for layout questions. Turn interesting dumps into fixture tests (CLAUDE.md §7 rule 6).

### 5.3 Log parser rules (`LogScreenParser`)
- Walks lines bottom→top. Only `[…]`-prefixed lines count. Times are real UTC epoch minutes (`LogTime`), so the access window works across midnight, month ends and New Year.
- An access line sets `lastAccessIp`. A later IP-less action inherits it only if within **≤3 min**.
- Blank lines, `[UNKNOWN]` lines and any wallet line reset that state.
  - **Developer-confirmed (dump #1):** the game sometimes doesn't log an "Accessed" line, giving two steals in a row after one access. Only the first belongs to the accessed IP; the second must stay unattributed (→ wallet owner or `unknown:wallet_*`). Keep the reset-after-steal rule. Pattern: `[16:03] Accessed <ip>` / `Stole 797 <wallet A>` (→ that IP) / `Stole 772 <wallet B>` (→ none). Covered by `RealDataRegressionTest`.
- `isOwnLogTheft` is true for `Stole…`. `isOwnedSoftware` is true for `download*`.
- **Game log times are UTC+0** (developer). `LogTime.toEpochMs` reads `[M-D H:MM]` as UTC; the year comes from the screen's `Last saved:` footer (`LogParseResult.lastSavedUtcMs`), else UTC now; a month after the anchor's month means last year. Stored thefts from before 2026-11-20 were in phone time and are shifted to UTC once at startup (`thefts_utc_v1` flag); exports carry `theftTimesUtc: true`, and older exports are converted on import.
- A log entry's timestamp is created when the device is accessed: when you log in, or when an attacker accesses you. So completed bypasses can share a timestamp.
- Masking in logs can be partial (`123.xxx.xxx.xxx`, `123.45.xxx.xxx`, `123.45.67.xxx`), full (`xxx.xxx.xxx.xxx`), or `[UNKNOWN]` (Ghost-reboot attacker). This happens because the *other* player's Proxy is active. Trace unmasks passively, but not always.

### 5.4 DatabaseManager write paths
- **processUpdates (logs):**
  - A wallet on a masked or empty IP is redirected to its known owner, or else to `unknown:wallet_<W>`.
  - **Wallet authority (developer rule):** logs only *suggest* wallets (`walletSource = log`). A target with no proven wallet may hold several log wallets. Logs never add to a target whose wallet came from its wallet page, and never move a wallet between two real targets (conflict logged instead). Otherwise, an `unknown:*` placeholder that owns the wallet is merged in.
  - Thefts are added only for own-log `Stole` lines with a parsable time.
  - Software is stored only for downloads onto **unmasked** records (status = action, e.g. "downloaded").
- **processHomeScreen:** upserts by IP, sets fields, `lastValidated = now`.
- **processSoftwareScreen:** finds the record by username, or else creates `unknown:<username>`. Sets downloads with status "installed".
- **processWalletScreen:** finds by username, or else `unknown:<username>`. **The wallet page is proof** (`walletSource = wallet_page`): it replaces every other wallet on the target (dropping their thefts) and takes the wallet, with its thefts, from any other record. Every change is written to `wallet_conflicts.jsonl`.
- **processBypassScreen:** upserts target FW/ENC by IP. Stores the player's Bypasser as `Using Lv + (+N)`.
- **processCrewScreen:** does nothing unless some member IP already has a clan. Then it tags every member and creates bare records for unknown IPs. Masked members are skipped.
- **processProcessScreen:** creates bare records for new IPs (invalidated-only IPs get `lastValidated = null`; masked IPs and `0.0.0.0` skipped). Records process downloads with status "Unknown" when the level is higher.
- **processStoreScreen / processOwnHomeScreen:** update the player profile only.

### 5.5 Records, merging, placeholders
- The key is `ip`. Placeholders are `unknown:<Username>` and `unknown:wallet_<W>`. Scraping the Wallet screen (username + wallet) merges them.
- `mergeRecords(base, incoming)`:
  - Canonical IP: unmasked beats masked, and a concrete IP beats `unknown:*`.
  - Scalars: incoming wins when non-null.
  - **Anti-pollution:** if exactly one side is masked, only the unmasked side's wallets, downloads and thefts survive.
  - Wallets: a proven (wallet-page) wallet wins and the other wallets are dropped with their thefts; with no proven wallet, both sides' log wallets are kept. Conflicts are logged.
- `reconcileDatabase()` merges any pair sharing a case-insensitive username or any wallet. It runs after every write path.
- `needsAdvancedTargeting` = concrete IP AND (no wallet OR missing Bypasser, Firewall or Password Encryptor in `downloads`).
- Validation queue: unmasked records, never-validated first, then oldest `lastValidated` (a string `yyyy-MM-dd HH:mm`).

### 5.6 Income estimate (`getEstimatedIncome`)
- Own-log thefts are sorted and split into clusters wherever the gap is **> 1 hour**. Clusters longer than 7 days are cut into 7-day windows.
- For each cluster with ≥2 events spanning ≥0.1 h: `sum(amounts except first) / hours`. Display the average as `₵n/hr` (≥10k shown as `₵X.Xk`).
- If no cluster gives a rate, show the latest single theft as a flat `₵n`.
- The 30-day purge keeps old events if removing them would leave a wallet with no multi-event cluster.

### 5.7 Good Spam / upload / payout (MainActivity, GoodSpamUtils, NetworkSpeedUtils, TargetAdapter)
- **Filter:** `firewall ≤ slider` (0–31, 31 = uncapped; null FW counts as 0). Turning it on sets the slider to your effective Bypasser.
- **Sort (developer, 2026-11-22):** `GoodSpamUtils.rankForSpam`: fresh income of your best upload level, descending (100/hr cappers first; unknown level last), then that level's upload time ascending (weakest device first; unknown device last), then Firewall ascending. Replaced the old `level×10 − uploadMinutes×0.5 − firewall` score, whose level term rewarded victim levels that pay nothing extra past the cap.
- **Device tier:** standard names map exactly. Prestige devices map by numeral (I–III=0, IV=1 … XII=9). A bare prestige name is 0. **Unknown strings are 3.**
- **Spam payout model (measured 2026-11-22, 17 fresh uploads):** `GameFormulas.spamIncomePerHour(spamLevel, victimLevel)` = `round(spamMaxIncome(L) / 1.475 × m(victim − spam))`, capped at 100, then decayed. `spamMaxIncome(L) = 13.9 + 4.3L + 0.0645L²` (the rate with the full bonus). `m` = +4%/level the victim is above the **Spam** (max ×1.475; fits 3.65–4.15%, the mirrored +5% is ruled out by victim 19 / Spam 10 = 58), −5%/level the Spam is above the victim (min ×0.10, assumed). **Your own level plays no part** (contradicts the guide's wording).
  - Data (victim Lv: Spam Lv → /hr): 43: 1→18, 2→23, 3→27, 7→47, 11→69. 33: 7→47, 13→81, 20→100. 28: 1→18, 2→23, 6→42. 7: 13→38, 20→30. 19: 10→58. 2: 1→13, 3→18, 7→24, 11→26. Plus 2026-11-20: Lv16 tops out at 99, Lv17 shows 100.
  - Rejected along the way: a straight line; the guide's player-level gap; the guide's curve × any scale (fails Lv2 victim Lv3 vs Lv7 victim Lv13); a ×0.50 floor (Lv20 on Lv7 = 30 = ×0.35); "income never falls" (developer's assumption, disproved by the developer's own uploads: Lv13 38 > Lv20 30 on a Lv7 victim).
  - **Over-levelled Spam earns less.** `GameFormulas.spamBestLevel(victim, max)` = lowest level with the highest rate. Lv17 is the cheapest cap, on any victim Lv27+. Peak on low victims: Lv2 → Lv10 (26/hr), Lv7 → Lv12 (38), Lv10 → Lv13 (47).
  - **Income is locked at upload** (developer): later level changes on either side don't change it, so SPAM EARNINGS rows can't be joined with current DB levels. Gap data must come from fresh uploads.
  - Unmeasured: Spam more than 13 levels above the victim (×0.10 floor assumed; developer: not worth measuring).
  - `GoodSpamUtils`: `recommendedSpamLevel` = cap level, else the best-earning level (search up to max(30, installed)); `spamUploadLevel` = the best-earning level up to min(recommended, installed). `calculateSpamPayoutInfo(targetLevel, installedSpam)` takes no player level.
- **Current hardware:** `PlayerProfile.currentDevice/currentNetwork` (shown item if MAX TIER, else `decrementHardwareName(next)`); `nextDevice/nextNetwork` are null once maxed. Always use `PlayerProfileRepository.getCurrentNetwork()/getCurrentDevice()` for maths (they fall back to decrement(next) for old profiles).
- **Spam upload level:** `GoodSpamUtils.calculateSpamPayoutInfo` → `recommendedSpamLevel` = **minimum** level reaching 100/hr, searched up to max(30, installed) (`isCapReachable`), `levelMultiplier` exposed. `spamUploadLevel(info, installed)` = min(recommended, installed) (recommended when installed is unknown). The target card chip shows `⏱ Spam Lv.X ~time`; Good Spam's `calculateScore(…, playerNetwork, playerLevel, installedSpam)` uses that level's upload time (falls back to Lv20 when player info is unknown).
- **Player levels:** Store shows NEXT levels, so current = stored − 1. `getEffectiveBypasser/Cracker` = (next − 1) + 3 if `nextDevice` contains "apex". `updateBypasserLevel` stores `(Using Lv + bonus) + 1`.

### 5.8 SiphonTracker
- If `items` is empty and `deployed > 0`, the scrape is ignored (scrolled to the header).
- Visible IPs accumulate. When `deployed == 0` or `accumulated ≥ deployed`, every known IP that wasn't seen is marked `REMOVED`. It is alerted unless it's a "crap install" (known > 24 h and amount < 1000). Then the accumulator clears.
- Re-seeing an alerted IP clears its alert. An amount drop is logged `REINSTALLED` and alerted. A new IP is logged `INSTALLED`, with its start time stored.
- There is **no TTL**: the accumulator persists only in memory, across scrapes.
- Prefs keys: `siphon_alerts`, `known_siphons`, `known_siphon_starts`. Use `clearSiphonTracking()` after a prestige.

### 5.9 Storage
- Prefs `HackExDB`: `targets`, `siphon_*`, `thefts_utc_v1`.
- Prefs `HackExPlayerProfile`: `player_profile`.
- Prefs `HackExSettings`: `pref_advanced_targeting`.
- Prefs `AppPrefs`: `hide_permission_warning`.
- `filesDir`: `siphon_history.jsonl`, `wallet_conflicts.jsonl`, `maintenance_backup_<ts>.json`, `marathon_history.jsonl` (one finished Marathon run per line).
- `Downloads/DarkIndex/darkindex_database.json` (pretty-printed full DB + player profile) is rewritten after saves, on the background save queue (superseded saves are skipped). The SAF pickers open in `Download/DarkIndex`.
- The manifest has `allowBackup=true`.

---

### 5.10 Added 2026-11-20
- **`HardwareCatalog`** — single source of truth for device/network names (standard + 5 prestige lines; devices I–XII, networks I–XI; bare line name = I). `parse/device/network/previous/networkBoost/uploadDefenceTier`. Unknown names → null; consumers keep their old fallbacks (tier 3 / 1.0×), the card marks the name with " ?", and `noteUnknown` logs each new name once.
- **Save queue** — `TargetRepository.saveAllRecords` updates memory immediately and queues disk writes on one background thread (`persistExecutor`, generation counter: always writes the latest state, skips superseded saves; `wipeDatabase` uses the same queue). `flushPendingWrites()` blocks until written. Tests set a same-thread executor via `TestEnv`.
- **Reconcile fast path** — `reconcileDatabase` returns early unless `hasSharedUsernameOrWallet` (O(n)); the O(n²) merge only runs when there is something to merge.
- **Profile in backups** — `ConsoleBundle.playerProfile` (optional). Profile writes call `TargetRepository.requestBackupWrite()`. Import uses `PlayerProfileRepository.restoreFromBackup` (fills only missing fields).
- **Wallet authority (developer rule)** — the target's wallet screen is proof (`walletSource = wallet_page`). Log-inferred wallets (`log`, or null on pre-2026-11-20 records) are only probable: a target may hold several. `processWalletScreen` replaces all other wallets (dropping their thefts) and takes the wallet from any other real target (with its thefts); logs only fill missing wallets, never add to a proven one or move one between real targets; `mergeRecords` keeps the proven wallet. All changes go to `wallet_conflicts.jsonl`. Tests: `WalletAuthorityTest`.
- **Bug batch (2026-11-20 afternoon):** `CommonParserUtil.isMaskedIp/stripNavBar/decodeEntities`; masked IPs never become records (Process/Crew); `SiphonScreen.maskedCount` counts towards the full-scroll check; `ScreenType.SPAM_EARNINGS` + `SpamEarningsParser` (parsed, not stored); log classifier matches headers and any timestamped log line; `Failed to crack` parsed; **log times are UTC** via `LogTime` (anchor = `Last saved:` footer; one-time migration of stored thefts, flag `thefts_utc_v1`; `ConsoleBundle.theftTimesUtc`); import saves targets before ingests (B2 fixed); Lv.? chip + level banner; search hidden in Recon/Spam Priority; unknown hardware marked " ?". Tests: `ScreenFixesTest`, `LogTimeTest`.
- **Plans** for the remaining work live inline in `TODO.md`.

### 5.11 Marathon Mode (built 2026-11-21, developer spec)
- **MARATHON** FAB (above VALIDATE) → pick 5/10/20 min → `OverlayService.ACTION_START_MARATHON`. Manifest has `VIBRATE` (the only new permission).
- Phases: `TIMED` → (timer ends, or **End early**) `AWAITING_LOG` (timer shows a big red `0`, one buzz) → own-log grab → `READY_PASS2` (prompt) → `PASS2` (untimed; shows + copies the run's **first** target) → **Finish** → `AWAITING_FINAL_LOG` (Back undoes) → own-log grab → `RESULTS` panel → ✕ → `CHAIN_PROMPT` (5m/10m/20m chain, shows + copies where the last run ended = its latest steal; or End). ✕ on the bar ends the marathon (two taps within 3 s).
- **Every own-log grab during a run feeds it** (`ScrapePipeline.logs` → `Marathon.onOwnLog`, after the DB save), so wiping the log mid-run loses nothing; an empty own log still advances the flow. Own log = `// LOG` / `~/log.txt` with no victim header and no `CONNECTED`/`DISCONNECT`. Toast appends `Marathon +N (M steals)`.
- Steals count from the run's start minute; a chained run never recounts the previous run's steals (key `wallet|time|amount`). Ordering = log time, then parse order (bottom→top = chronological).
- Rows are **per wallet** (developer: match by wallet). Rate = card maths (sum after the first steal ÷ span), but with any span ≥ 1 min instead of the cards' 0.1 h minimum. Rated rows first by rate; single-steal rows after, by amount. Label: log IP, else the wallet owner's concrete IP from the DB, else username, else wallet. Tap a row to copy. Each run's panel is independent (no cross-run ranking, owner).
- In memory only: a marathon does not survive a service/app restart (developer). Tests: `MarathonTest` (fixture `own_log_2026-11-21.txt`, Raw Dump #15).

## 6. Test suite (195 JVM + 2 instrumented; audited 2026-11-20, trimmed 2026-11-22)
**Developer rule (2026-11-22): every test must be able to catch a real bug.** No tests that restate a table or formula (guide cells, lookup tables, arithmetic, pinned numbers from a formula) or that test test helpers: 31 such tests were deleted, including all guide-number checks. A test passing against the guide proves nothing about the game (the Spam model passed 17 guide-cell tests while wrong). Keep: real in-game data (measurements, dumps, verified prices), parsing, DB/merge rules, edge cases, crash guards, content integrity.

Every test now calls production code. The 2026-11-20 audit found and fixed these "can't fail" tests:
5 in `UnmaskingTest` (re-implemented the logic inside the test), `ConsoleBundleTest.testImportInvalidFormat`
(threw the exception itself), the instrumented `testTargetRecordModelOnDevice` (only read back constructor
values) and a duplicate template test. A mutation check (breaking masked-software filtering, masked-merge
handling and conflict logging) now fails 6 tests; before, the old Unmasking tests would all have passed.

| Class | What it covers |
|---|---|
| **RealDataRegressionTest** (new) | Real Raw Dump screens through `ScrapePipeline.run` → DB: scan list (first real row keeps its own FW/ENC past a masked row), double-steal attribution, a missing Accessed line can't steal another target's wallet (conflict logged), the same steal seen on two days is stored once, one target built from Home + Wallet + Software + Log, crew clan spreading, own Home → player level, Processes, Siphons (masked row skipped), the 6 known log discards |
| **DatabaseCoverageTest** (new) | Processes (create/keep/invalidated/downloads only if higher/no 0.0.0.0), Crew (known clan spreads, unknown → nothing saved), Bypass (target defences + stored bypasser), Import (merge by IP/username, siphon state, raw ingests) |
| **WalletAuthorityTest**, **SaveQueueAndBackupTest**, **HardwareCatalogTest**, **ScreenFixesTest**, **LogTimeTest** (new) | Wallet-page authority rules; save queue order + profile in backups; every hardware name; masked IPs, nav bar, Spam Earnings, entities, failed crack; UTC log times + migration |
| HackExParserTest | Real logcat fixtures for every parser, the 3-min cap, the blank-line barrier |
| UnmaskingTest | Classification + real `mergeRecords` / `processUpdates` / `runDatabaseMaintenance` (incl. backup file + conflict log) |
| TargetRepositoryTest | Validate doesn't wipe, IP-edit merge keeps the existing wallet + logs the conflict, delete, maintenance keeps thefts, purge shield, Downloads mirror goes only to the backup folder |
| RawDumpTest, PlayerHardwareAndSpamLevelTest | Raw Dump Mode; maxed Store, current hardware, Spam levels |
| **MarathonTest** (new 2026-11-21) | Full run on a real own log: mid-run grabs, End early, wiped log, first target, Back, results order/rates, history file; timer expiry; start-minute filter; chain hint + no recount; own-log detection; DB owner IP for masked steals |
| **KnowledgeBaseTest** (new 2026-11-21) | Real data only: Spam against every in-game upload (+ Lv17 cap), Siphon % against Raw Dump #16, the 5 verified Store prices, rootkit debuff by level, crew server prices, upkeep and surges (Raw Dumps #25, #27, incl. the treasury balance); KB content integrity (ids, related links, calculator ids, Q-ids, table widths); every calculator at defaults and extremes; profile defaults; search |
| **TargetSearchTest** (new 2026-11-22) | `sw 20` ranking (one-stop FW1 target first), tie-breaks, app aliases + ±1/±2 fallback, Home-screen firewall, level exact/range, plain text not mistaken for a software query |
| ScreenClassifierTest, GoodSpamTest (firewall filter, Spam priority order), PlayerIntelligenceTest (Apex bonus, bypass parser), SiphonTrackerTest, ConsoleBundleTest, TargetIncomeTest, RecordMergingTest, PlayerProfileRepositoryTest (+ profile cache), OwnHome/Store/SpecialChars parser tests | As named |
| androidTest | Package name; a stored record with missing fields loads + `sanitizeRecord` on ART (read-only) |

Test infrastructure (`TestSupport.kt`):
- `TestEnv.freshRepository()` resets all three singletons and points `appContext` (a `TestContext` with a temp `filesDir`) and `TargetRepository.backupDirProvider` at a fresh temp folder. Use it in every `@Before`.
- `TestEnv.conflictLog(dir)` reads `wallet_conflicts.jsonl`; `TestEnv.fixture(name)` reads `src/test/resources/fixtures/<name>`.
- **Fixtures are `.txt`** (raw screen text from Raw Dump Mode), because `*.json` is git-ignored.
- The Downloads mirror is only written when `appContext` is set (always true in the app), so tests can't create a stray `DarkIndex/` folder any more.
- Not covered: `getNextTargetForAdvancedTargeting`, all UI (incl. the KB screens; CI's `tests.yml` is what compiles them).

---

## 7. Rules / pitfalls

1. Never pass `cachedRecords` (or any list aliasing it) into `saveAllRecords`. Always use `getAllRecords().toMutableList()`.
2. The accessibility tree walk stays **synchronous on the main thread**. Parsing stays off it.
3. Filter `0.0.*` wherever IPs are collected.
4. Masked and victim-log data must never write wallets, downloads or thefts onto real targets.
5. Large lists (4,000+): keep the `forceFastSwap` (`submitList(null)`) pattern for re-sorts.
6. For any parser change, get a raw `DOSSIER_RAW` dump or a real ingest, add it as a fixture test, then change the regex. Put new fixtures in `app/src/test/resources/fixtures/` as `.txt` and load them with `TestEnv.fixture()`. The older 2,862-capture corpus `target_console_2026-09-19.json` (`ingests[]`) is local-only research data in `local-data/` (git-ignored).
7. Keep the app 100% offline.
8. Store-derived player levels are **next** levels. Subtract 1.
9. **Versioning is automatic** (2026-11-20): versionCode = 1000 + `git rev-list --count HEAD`, versionName = latest `v*` tag + `-dev.<commits>`; CI passes the release name and the same code rule. Don't hand-edit version numbers.
10. Remote-device file commits can land one version behind. After committing a file to the developer's PC, list the folder and check the size matches; commit again if not.

---

## 8. Known bugs & risks

Found in the 2026-11-19 review. All fixed except where noted; kept as history so the same mistakes aren't reintroduced.

| # | Status | Issue |
|---|---|---|
| B1 | Fixed | Upload estimates and Good Spam used the NEXT network instead of the current one |
| B2 | Fixed | Import dropped raw ingests (final save overwrote them); targets are now saved before ingests run |
| B3 | Fixed | Downloads auto-backup broke after a reinstall forced by CI's throwaway signing key; one permanent key now |
| B4 | Fixed | Wipe Database had no confirmation |
| B5 | Fixed | Log-only screens fell to UNKNOWN; classifier now matches log headers and lines |
| B6 | Mitigated | Spam payout re-fitted to 17 in-game uploads (2026-11-22, §5.7): the gap is victim − Spam level, not your level; over-levelled Spam earns less; Lv17 caps on victims Lv27+. Only Spam 13+ above the victim still estimated (not worth measuring) |
| B7 | Mitigated | Unknown hardware still gets tier 3 / 1.0×, but is marked " ?" and logged |
| B8 | Fixed | Merges orphaned thefts of the dropped wallet; replaced by the wallet-authority rule |
| B9 | Fixed | Search is hidden while Recon or Good Spam is on |
| B10 | Fixed | Profile JSON parsed several times per card bind; now cached |
| B11 | Fixed | Whole-DB saves blocked scrapes; now on an ordered background queue |
| B12 | **Partly open** | `isApexDeviceOwned()` reads the owned device now; possible double Apex +3 still to verify (TODO) |
| B13 | Fixed | Log minutes ignored the month; now UTC epoch minutes |
| B14 | Fixed | Leftover ProGuard Google Identity rules removed; releases signed with the permanent key |
| B15 | Fixed | Repo clutter removed; tests no longer write `DarkIndex/` |

---

## 9. GEMINI.md drift (don't trust these GEMINI statements)
- Theft clusters are split on a 1 h gap, not 24 h.
- Good Spam is the upload-time formula, not either of GEMINI's two hardware formulas.
- Siphons: there's no 15-min TTL.
- The Recon/Advanced Targeting switch is on MainActivity, not Settings.
- There are 10 screen types, not 7.
- The cubic price formula is Keygen's, not "offensive" (§4).
- "Proxy/Trace 1.00x" conflicts with the guide (0.85×).
- GEMINI's prestige network names are wrong (§3).
- Test counts are now 80 + 3. `ExampleUnitTest` no longer exists.
- Files GEMINI omits: `GoodSpamUtils`, `NetworkSpeedUtils`, `PlayerProfileRepository`, and tests `GoodSpamTest`, `ScreenClassifierTest`, `StoreScreenParserTest`, `OwnHomeScreenParserTest`, `PlayerIntelligenceTest`, `PlayerProfileRepositoryTest`, `TargetAdapterTest`, `FakeSharedPreferencesTest`.
- GEMINI's "Raider I–III upload identically" claim is **correct** (developer-confirmed). The guide's per-device multipliers are process speed, not upload defence.

---

## 10. Developer Q&A (answered 2026-11-19)

**Game**
1. "Stole N" = what the **victim lost** (pre-gas-fee). Consistent across targets, so fine.
2. Raider / II / III targets **upload equally fast**. Rigorously confirmed.
3. Concurrent processes do get slower, but it's unknown whether that's overhead or bandwidth sharing. Not modelled (the app can't count processes).
4. Prestige names: see §3 (from the guide).
5. Player's own rebirth/hardware: not relevant to code; changes over time.
6. At max tier the Store shows the tier-11 network with **`MAX TIER`** instead of a price.
7. Masked IPs come from the other player's Proxy. Partial masks (1–3 octets) are possible; `[UNKNOWN]` means a Ghost-reboot attacker. Trace unmasks passively, not guaranteed.
8. Log timestamps are created when the device is accessed (your login, or an attacker's access).
9. Log times are **UTC+0**.
10. Spam payout vs level gap: not yet calculated; it's definitely worse on lower targets. To-do (research).
11. Virus performance penalty: in the guide (applies to the infected device; see follow-up F2).
12. Siphon list behaviour after an AV wipe: the developer says it's in the guide (see follow-up F3).
13. Proxy/Trace Store discount: irrelevant. Players download apps and unlock them with Keygen, which is always far cheaper.

**App**

14. Most-scraped screens: **target Home, Software, Wallet**.
15. Backups were broken (last file 14 Nov); fixed by the permanent signing key (B3).
16. Auto-backup is **crucial**. Export is for peace of mind or sending data elsewhere.
17. Old bundles with raw `ingests`: rarely used, but keep the compatibility.
18. The DB has **4,000+ targets**.
19. Wipe Database should ask for confirmation (done).
20. Keep the Good Spam slider behaviour as is.
21. **Remove search entirely while Recon or Good Spam is on** (done).
22. Crew: "if they're on that screen, they're in the crew" (see follow-up F4).
23. Knowledge Base will be built out. To-do.
24. CI should run tests.
25. Test device: **the developer's phone (Android 17)**.

### Follow-up answers (2026-11-19)
- F1: Yes. The 14 Nov break coincided with an install needing an uninstall, caused by CI's throwaway signing key.
- F2: Viruses installed **on you** slow **your** processing. The only way to find out is to run Antivirus: its report lists every virus removed **and** every one not removed, so one scan shows 100% of the viruses on you (developer, 2026-11-22).
- F3: After a victim runs AV, the wiped siphon **disappears from SIPHON EARNINGS immediately** on your next scan. `SiphonTracker`'s "missing after a full scroll" logic matches this.
- F4: A crew's full name can't be correlated with a home-screen `[TAG]`. **Keep the current behaviour:** discard a Crew screen unless a member's clan is already known.
- F5: After a reboot you start at `<Line> I`; devices go to XII, networks to XI; R6+ mixed loadouts name each piece `<Line> <Roman>` (a bare line name = I).
- Log line example: `[11-19 16:09] Stole 1,226 Crypto from hx1a2b...9f0e`. The number is what the victim lost.
- The developer wanted an in-app **Raw Dump mode** instead of tethered logcat (built 2026-11-20, §5.2b).
