# CLAUDE.md — DarkIndex (HackExLogger)

Context for Claude working in this repo. Last full review: **2026-11-19**, updated through **2026-11-27** (R4 reboot run, Raw Dumps #39–#171). That review read every source, test, resource, build and CI file, ran the JVM test suite, ran a local corpus of 2,862 real screen captures (git-ignored, `local-data/`) through the parsers, and cross-checked hackex.net/guide.

**DarkIndex** is an offline Android companion for the mobile game **HackEx 2**. The player taps a floating 📸 button, an AccessibilityService snapshots the visible text of the game screen, regex parsers turn it into target intel, and the app stores it locally for search, ranking, odds, upload-time and income estimates.

- Package / applicationId: `com.whisk.hackexlogger`. Repo: `DarkIndex` on GitHub. Gradle root name: `DarkIndex`.
- One `app` module. AGP **9.5.1** (built-in Kotlin, so no `kotlin-android` plugin is applied). Gradle wrapper **9.9.0**, daemon toolchain JVM 25, Java target 11.
- `compileSdk/targetSdk 35`, `minSdk 26`.
- Deps: Gson 2.10.1, kotlinx-datetime 0.6.1, Flexbox 3.0.0, RecyclerView 1.3.2, AppCompat, Material, ConstraintLayout, activity-ktx, core-ktx.
- **No INTERNET permission. Never add networking, analytics or cloud SDKs.**
- **Privacy guarantees (2026-11-24, `SECURITY.md`, enforced by `PrivacyGuardTest` + the release workflow's `aapt2` check):** reads only HackEx 2 (`ScrapeGate`, checked before any node is read; accessibility config limited to `net.cncapps.hackex2`, no `flagRetrieveInteractiveWindows`); screen text never goes to Logcat (release strips `Log.v/d/i`); `allowBackup=false` with every domain excluded from cloud backup (device-to-device transfer still allowed). Keep `SECURITY.md` true when changing any of these.
- **Work tracking:** `TODO.md` (in the repo, owned by the user) holds the open items **and their implementation plans**. Add new ideas there and don't execute them unless asked. Keep it short: when an item is done, move it to the one-line Done log and record any lasting facts here in CLAUDE.md. `docs/plans/` is the developer's local-only scratch folder (not committed); don't reference it from committed files.
- **Developer preference:** queue work as to-do items first. Implement only when explicitly asked.
- **Bugs found while working (developer, 2026-11-20): fix them on the fly**, don't add them to TODO.md. If unsure it's really a bug, ask for context first: the game is dynamic, so a "bug" may be a screen, layout or player you haven't seen before. Only **new ideas** go on the to-do list.
- **Raw Dump Mode is a dev tool** (targeted troubleshooting). Single tap copies one dump; EXPORT ALL writes the compact multi-dump text the developer attaches in batches (developer request 2026-11-24, for the post-reboot level-1 run). No wrong-app features. Developer-declined ideas (don't re-propose): `.dev` build variant; wallet hot/cold balance tracking (conflicts when you drain before scraping); REP/SCORE storage; log-based deployment tracking (the game shows deployments; Siphon tracker + planned Spam tracker cover it); badge-derived own levels; parsing the own Wallet Transaction History (flooded by siphon skims at scale; useful only for one-off data gathering).

---

## 1. Commands

```bash
./gradlew assembleDebug          # build APK
./gradlew testDebugUnitTest      # JVM unit tests (app/src/test) — 177 tests, all passing as of 2026-11-27
./gradlew connectedAndroidTest   # instrumented tests (device/emulator); read-only, never touch saved data
./gradlew assembleRelease -PversionName=1.6   # versionCode is automatic
```
- **Versions are automatic** (2026-11-20): versionCode = 1000 + `git rev-list --count HEAD`; versionName = latest `v*` tag + `-dev.<commits>` (or `dev.<commits>` with no local tags; `git fetch --tags`). `-PversionName/-PversionCode` or env `VERSION_NAME/VERSION_CODE` override. No git → 1000 / `dev` + a warning.
- **Signing (since 2026-11-19):** one permanent key signs **debug and release** builds (signing config `darkindex` in `app/build.gradle.kts`), read from git-ignored `keystore.properties` (template: `keystore.properties.example`; use forward slashes in paths) or env `SIGNING_STORE_FILE/SIGNING_STORE_PASSWORD/SIGNING_KEY_ALIAS/SIGNING_KEY_PASSWORD`. Without them, builds fall back to the machine debug key and print a warning. **Never change the key or applicationId**: that forces an uninstall, which wipes the DB.
- **CI** (`.github/workflows/android.yml`) runs on a `v*` tag push or manual dispatch. It uses JDK 17, decodes the key from secret `SIGNING_KEYSTORE_BASE64` (+ 3 password/alias secrets), **fails if any are missing**, runs `assembleRelease`, prints the APK cert SHA-256 (enforced if repo variable `SIGNING_CERT_SHA256` is set), **runs `testDebugUnitTest` first (no release if a test fails)**, uploads `DarkIndex.apk` and creates a GitHub Release. "Run workflow" has a **Release type** dropdown (2026-11-22): **minor** (default) bumps the last number of the highest `v*` tag (1.6.1 → 1.6.2, 1.7 → 1.7.1), **major** bumps the middle number and drops the last (1.6.5 → 1.7). An optional exact version overrides it; an existing tag is refused. `.github/workflows/tests.yml` runs the unit tests on every push and PR (no secrets needed) and uploads the HTML report. Claude cannot write files under `.github/` on the developer's PC; hand the developer the file instead.

### Building and testing in a cloud sandbox
Google Maven and services.gradle.org were reachable on 2026-11-22, so the real Gradle build works:
- Android SDK: download `commandlinetools-linux-*_latest.zip` from dl.google.com into `/opt/android-sdk/cmdline-tools/latest`, then `yes | sdkmanager --sdk_root=/opt/android-sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools"`, and write `sdk.dir=/opt/android-sdk` to `local.properties` (git-ignored).
- The daemon toolchain (JVM 25) is fetched from api.foojay.io, which is blocked. Locally change `toolchainVersion=25` to `21` in `gradle/gradle-daemon-jvm.properties` and **never commit that change**.
- Run `bash gradlew …` (the wrapper isn't executable in a fresh clone). Maven Central sometimes answers 429, and the `foojay-resolver-convention` plugin sometimes fails to resolve from the plugin portal ("Plugin … was not found"): wait 30 s and retry. Run the tests with the local `toolchainVersion=21` edit, then `git checkout -- gradle/gradle-daemon-jvm.properties` before committing.
- If Google Maven is blocked again, the pure-logic code can still be compiled with the Kotlin compiler in `/opt/gradle/lib` plus Java stubs for `android.content`/`android.os`/`android.util.Log` (the method used before 2026-11-22).

---

## 2. Where things are documented

| File | What's in it |
|---|---|
| `CLAUDE.md` (this file) | The app: commands, pipeline and code map, storage, tests, rules |
| `docs/game.md` | The game: mechanics, measured and verified numbers, formulas, screen text formats, the developer's answers. **Read it before touching parsers or game maths** |
| `docs/history.md` | Fixed bugs (B1–B17) |
| `docs/knowledge-base-prep.md` | The developer's Knowledge Base review tracker and open questions (Q-ids) |

Facts about the game go in `docs/game.md`, not here.

## 3. App pipeline & code map (`app/src/main/java/com/whisk/hackexlogger/`)

```
OverlayService (📸, TYPE_APPLICATION_OVERLAY, FGS specialUse)
  └─ tap (ACTION_UP, not a drag) → ScraperAccessibilityService.instance.scrapeAndParse()
        (fallback: broadcast TRIGGER_SCRAPE)
        main thread: rootInActiveWindow → recursive extractText (node.text only, not contentDescription)
        Dispatchers.Default: join "\n" → ScreenClassifier → <X>ScreenParser → DatabaseManager.process<X>
        → broadcast SHOW_TOAST (HUD pill "Saved: …") + DATA_UPDATED (legacy, unused)
TargetRepository.update { list → new list }   (one write path at a time; reconcile; save once)
  → records: immutable List<TargetRecord> in memory (getAllRecords() hands it out, no copy)
  → background queue: filesDir/targets.json (TargetStore, atomic) right away
                      + Downloads/DarkIndex/darkindex_database.json 30 s later (one per burst)
  → recordsFlow (StateFlow) → MainActivity → TargetAdapter
```

| File | Role |
|---|---|
| `ScreenParsers.kt` | `SharedRegex`, `ScreenClassifier`, `CommonParserUtil` (`isMaskedIp`, `stripNavBar`, `decodeEntities`), `LogTime` (UTC log times), plus parsers: Home, OwnHome, OwnApps, Log, Software, Wallet, Siphon, SpamEarnings, Crew, Process, Store, Bypass |
| `HardwareCatalog.kt` | Single source of truth for device/network names, levels, network boost and upload-defence tier (§3.10) |
| `ScraperAccessibilityService.kt` | On-demand only (`onAccessibilityEvent` is a no-op). Main thread: text snapshot (+ node tree in dump mode). Background: `ScrapePipeline.run`. Pipeline exceptions are caught and toasted ("Scrape error: …") instead of killing the coroutine. Refuses any window that isn't HackEx 2 (`ScrapeGate`) before reading text or starting a dump. Logs `DOSSIER_BENCHMARK` timings only (screen text is never logged since 2026-11-24) |
| `ScrapeGate.kt` | `GAME_PACKAGE = net.cncapps.hackex2`, `isGameWindow(pkg)`: the privacy gate for every scrape |
| `ScrapePipeline.kt` | Classify → parse → `DatabaseManager.process*` for one snapshot (moved verbatim out of the service; JVM-testable). `UNKNOWN` runs all 11 parsers in the original order; `OWN_DEVICE` and `STORE_OTHER` run none. Takes an optional `DumpSession` |
| `RawDump.kt` | Raw Dump Mode (JVM-testable): `RawDumpSettings` (`HackExSettings`/`pref_raw_dump_mode`), `RawDump` model (JSON field order = declaration order), `RawDumpSummary`, `DumpSession` (installs `DatabaseManager.activeDiscardSink` + `WalletConflictLog.listener`, diffs DB, profile and siphons before/after; serialized by `DumpSession.LOCK`), `RawDumpStore` (`filesDir/raw_dumps/<id>.json` + `<id>.meta.json` + `next_id`; ids are never reused; `exportAll` streams `{format:"darkindex-raw-dumps",…,dumps:[…]}`; Gson with `disableHtmlEscaping` so `&#39;` survives) |
| `RawDumpCapture.kt` | Android-only: app version (`PackageInfoCompat`) and the flattened accessibility node tree (`d, cls, id, text, desc, bounds`; cap 20k nodes) |
| `RawDumpsActivity.kt` | Dump browser: cards (big local timestamp, `#id · UTC · package`, type chip with amber for fallback, result, matched parsers, log stats amber when anything was discarded, errors in red), tap = copy that dump's **lean** JSON (toast shows the size and warns above 20,000), ▸ = expand, long-press → DELETE, EXPORT ALL (SAF, compact `.txt`; **long-press** EXPORT ALL for the full `.json` with node trees), DELETE ALL (confirm) |
| `RawDumpCompact.kt` | The compact multi-dump export (§3.2), built from the stored JSON. `tools/dumps.py` reads it back |
| `DatabaseManager.kt` | Facade for the UI + the `process*` write paths (each one `TargetRepository.update { … }`). `parseLogTime` delegates to `LogTime` |
| `TargetRepository.kt` | The in-memory database, `update`/`commit`/`saveAllRecords`, loading (incl. migration from prefs), the ordered background save queue + debounced Downloads backup, validation queues, `editTargetIp`, delete, wipe |
| `TargetStore.kt` | `targets.json` read/write: tolerant per-record parsing (bad records kept in a side file), atomic writes |
| `RecordMerger.kt` | `mergeRecords`, `reconcile` (O(N²), skipped unless a username/wallet is shared), `hasSharedUsernameOrWallet` |
| `DatabaseBackup.kt` | Export (streamed) / import of `ConsoleBundle`, the one-time UTC shift of old theft times |
| `DatabaseMaintenance.kt` | ⋮ → Maintenance (backup first) and the 30-day theft purge |
| `WalletConflictLog.kt` | `wallet_conflicts.jsonl` + the Raw Dump listener |
| `TargetRecord.kt` | **Immutable** model (`copy`/`withTheftEvent`/`withDownload`), `WalletSource` enum, `normalized()`, `isMasked`/`isTemporary`/`needsAdvancedTargeting`, theft clustering/income, success odds |
| `PlayerProfileRepository.kt` | YOUR stats in prefs `HackExPlayerProfile`/`player_profile`: username, level, nextDevice, nextNetwork, botnet, software **next** levels |
| `GoodSpamUtils.kt` | Device tier, Good Spam score, firewall eligibility, Spam payout (delegates to `GameFormulas`) |
| `GameFormulas.kt` | Pure game maths shared by the app and the Knowledge Base: Spam income/decay/fee, botnet tiers, Store prices (exact: `ceil(base × L^1.3)`, defensive × 0.85 rounded up) and Keygen fees, software XP (`0.2 × base × L^1.25`), proxy cost and masking, AV odds and scan time, hardening, hack odds, rep and score, process speed-ups, device/network prices. Each function names its source |
| `NetworkSpeedUtils.kt` | Network boost table, base upload seconds, level scaling, duration formatting |
| `SiphonTracker.kt` | Known siphons and amounts, first-seen times, alerts, `siphon_history.jsonl` |
| `OverlayService.kt` | 📸 button (drag to the bottom ✖ to stop the service), HUD toast, Validation toolbar (✓ / 🗑 / ✕), Recon toolbar (⏭ skip / ✓ / 🗑 / ✕). Copies the IP to the clipboard |
| `TargetSearch.kt` | Dashboard search (pure, tested): `lvl 30`/`lvl 28-32` player level; `sw 20` = most loot apps (all but Firewall) at exactly Lv20, then lowest FW, ENC, near misses; `<app|alias> 20` one app, ±1/±2 fallback, closest then lowest FW; else text. Results keep this order (MainActivity skips the IP sort while searching) |
| `MainActivity.kt` | Dashboard: search (via `TargetSearch`), Good Spam switch + FW slider, Recon switch, VALIDATE/RECON FAB, ⋮ menu (Settings, Export, Import, Maintenance, Wipe (type-`WIPE` confirm), Reset Siphon Tracker), Siphon Alerts button, Android 13 restricted-settings onboarding |
| `TargetAdapter.kt` | `ListItem` (Header/Target) + `ListItemDiffCallback`. Card rows: IP (tap copies) / username / wallets + income; chips LVL FW ENC DEV NET CREW; odds chips (Bypass %, Crack %, tap for detail); "⏱ Upload Times" dialog with Spam payout advice; DEFENSE (FW, ENC, AV, Proxy) / OFFENSE (Bypasser, Cracker, Trace) / VIRUSES grid. Long-press opens EDIT / SHARE / DELETE |
| `SettingsActivity.kt` | Shows the parsed player profile (current = next − 1). Purge thefts > 30 days |
| `SiphonAlertsActivity.kt` | Alert list: "Siphon Re-installed" clears the alert. "IP Not Found" clears it and deletes the target |
| `ConsoleBundle.kt` | Export schema `{format:"target-intelligence-console", version:1, exportedAt, targets, ingests[{type,raw,importedAt,id}], siphonAlerts, knownSiphons}` |
| `Marathon.kt` | Marathon Mode engine (pure, JVM-tested): phases, own-log steal collection, per-wallet income rows, chain, `marathon_history.jsonl` (§3.11) |
| `MarathonOverlay.kt` | Marathon UI owned by `OverlayService`: draggable 🏃 bar (timer / big red `0` + 400 ms buzz / prompts / buttons) and the draggable, scrollable results panel |
| `KnowledgeBase.kt` | KB model (`KbTopic`, `KbBlock`, `KbSource` badges: MEASURED, CALCULATED, GUIDE, CONFIRMED, EMPIRICAL, UNKNOWN; developer renamed OWNER→CONFIRMED and EXACT FIT→CALCULATED 2026-11-22), search, and `KbCalculators` (9 calculators as pure input specs + compute functions; defaults from the player profile via `KbPlayerDefaults`) |
| `KnowledgeBaseContent.kt` | The 25 topics, in Kotlin (developer choice). Tables are generated from `GameFormulas` where a formula exists. Open questions carry Q-ids (Q-01…Q-13, minus answered or dropped ones, which are deleted) listed in `docs/knowledge-base-prep.md`, the developer's human-readable review tracker (rewritten 2026-11-22: review progress by topic + open questions numbered in topic order; covered items are deleted, not struck through). When a question is answered, delete it from the doc and the topic; don't renumber again unless the developer asks. Answered or dropped so far: Q-02 (Siphon skims theft only), Q-08 (Keygen fee, Raw Dump #33), Q-14 (missions are RNG), Q-01 (Spam fee 0.5% × (active − 2), Elite −50%, seen on the Store and SPAM EARNINGS, Raw Dumps #91, #94), Q-09 (Store price = ceil(base × L^1.3), defensive × 0.85 rounded up; exact on 42 real prices, 2026-11-27). **Writing rules (developer, 2026-11-24):** the KB is for players. Plain English; no parser or screen-text details (Store NEXT − 1, MAX TIER, raw log footers, quoted screen strings, file names, "DarkIndex reads…"); keep wording generic rather than worked number examples where a table already shows the numbers; state developer-confirmed facts plainly, with at most one small disclaimer at the bottom of a topic, not hedges everywhere. Parser and screen facts belong in `docs/game.md` §4 |
| `KnowledgeBaseActivity.kt` / `KnowledgeTopicActivity.kt` | 📖 button left of Siphon Alerts → index (search, badge legend, topic cards with source tallies) → topic page (facts, tables, tips, warnings, live calculators, open questions, related chips). `KbViews` = shared view factories |

### 3.1 Classifier (first match wins, case-insensitive substring)
1. `OWN_PROFILE`: "my device" AND ("daily missions" OR "login streak"); then `OWN_DEVICE`: a `// MY DEVICE` line (your own device/settings page, saves nothing; before 2026-11-27 it fell to UNKNOWN and saved a target named "IP Address", Raw Dump #146); then `STORE_OTHER`: a `// BLACK MARKET…` or `// CREDITS PACKS` line (the Store's other tabs, save nothing; Raw Dumps #156, #157)
2. `BYPASS`: "using bypasser" OR "// bypass firewall" OR ("// scan results" AND "fw lv")
3. `STORE`: "proprietary key generator built for your device"
4. `SOFTWARE`: "installed software"
5. `WALLET`: "wallet address" OR "'s wallet" OR "’s wallet"
6. `SPAM_EARNINGS`: "SPAM EARNINGS" (before Siphon: both pages start with the Apps page text)
7. `SIPHON`: "SIPHON EARNINGS"; then `APPS`: a `// APPS` line AND "Installed Applications" (your own Apps page, 2026-11-24); then `SIPHON` again for "siphoned" AND "deployed" (the Apps page has both words, so it must come first)
8. `LOGS` (`isLogScreen`): log headers (`// LOG`, `~/log.txt`, `// VICTIM LOG`, `~/victim_log.txt`), the old access/crack/bypass phrases, or any `[M-D H:MM]` line with a known action (stole, uploaded, downloaded, failed, purchased …)
9. `PROCESS`: a `// PROCESSES` line OR `N active tasks`
10. `HOME`: "firewall" AND ("encryptor" | "ip" | "device")
11. `CREW`: a line equal to "LEADER" or containing "Members"
12. `HOME` (IP + FIREWALL Lv regex)
13. `UNKNOWN` → run all parsers. Crew needs a `// … Members` header and a LEADER line, Process needs `// PROCESSES` or `active tasks`, and both strip the bottom nav bar first, so the fallback can't tag random IPs.

`ScrapePipeline.run` decodes HTML entities (`&#39;` etc.) before classifying.

### 3.2 Raw Dump Mode (debug tool, one dump at a time)
- Toggle: Settings → Debug → Raw Dump Mode. While on, the 📸 overlay shows **📼 with a red ring**, the toast appends `· 📼 #id`, and the home toolbar shows **Raw Dumps** instead of Siphon Alerts.
- Every scrape is recorded, including null root, empty text and exceptions. **Scrapes still save to the DB normally** (no dry run).
- `LogScreenParser.parseDetailed()` returns updates + discarded lines with reasons. Parser stage: `NO_PATTERN`, `UNKNOWN_SOURCE`, `NO_TARGET_IP`. DB stage (via `DatabaseManager.activeDiscardSink`): `EMPTY_IP`, `WALLET_CONFLICT`, `MASKED_SOFTWARE_IGNORED`, `BAD_TIMESTAMP` (partial: the wallet is still linked). `parse()` == `parseDetailed().updates`, and `timestamped == updates + parserDiscards` always.
- The accessibility config now includes `flagReportViewIds`, so node-tree `id`s are populated.
- What the game's screens look like to the accessibility service (WebView, the 100-line log, the bottom nav, per-screen layouts) is in `docs/game.md` §4.
- Typical single-screen timing before the 2026-11-22 storage rewrite: extract 2–100 ms (main thread), parse + DB 300–400 ms (dump #1: 401 ms for 96 updates on 4,294 records), dominated by copying and saving the whole DB. Since then the scrape only updates memory; disk writes run in the background. Desktop benchmark (4,300 records, a 100-line log + a Home screen): 28 ms → 5 ms on the scrape thread.
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
- **Builds before 2026-11-24** (e.g. 1.8 (1147), Raw Dumps #33, #34) classify your own Apps page as `SIPHON` and save nothing; the current classifier tags it `APPS`. Check the dump's `app` version before calling a misclassification a bug.
- **Compact export (EXPORT ALL, `darkindex-dumps-compact` v1, 2026-11-24).** Attached files are read into context in full, and the full JSON was ~35 KB per Apps page (mostly node tree), so EXPORT ALL now writes plain text: a header, `## shared` (long lines repeated across dumps, written as `§n` in the texts), then per dump `## dump <id> · <utc> · <SCREEN>[ · nav]`, one-line `result:`/`parsers:`/`fallback:`/`error:`/`log:`/`discarded:`/`db:`/`walletConflict:` fields, `interp <Parser>:` (Log as rows `L<n> ip wallet amount [own] [sw:…] [owned]`, n = 0-based line of that dump's text), and `text:` (exact screen text minus the trailing bottom nav when marked `nav`; an identical text is `text: same as #id`). No node tree, timings, package or local time. The 4 dumps #31–#34: 140.7 KB full → 6.1 KB compact, round-trip exact.
- **Reading dumps cheaply:** read the compact file directly. For a fixture, `python3 -I tools/dumps.py fixture FILE ID app/src/test/resources/fixtures/<name>.txt` restores the exact text (shared lines and nav). `list` gives one line per dump; `compact FILE.json` converts an old full export (never Read a full JSON export: run `compact` or `list` on it first); `text FILE ID` prints one screen. Use the long-press full export only for node-tree/layout questions, and ask for single dumps then.
- **When the developer sends dumps:** read `detectedType`/`fallbackUsed`, `parsersMatched`, `interpretation`, `discardedLogLines`, `dbEffects`, then `rawText`; use `nodeTree` for layout questions. Turn interesting dumps into fixture tests (CLAUDE.md §5 rule 6).

### 3.3 Log parser rules (`LogScreenParser`)
- Walks lines bottom→top. Only `[…]`-prefixed lines count. Times are real UTC epoch minutes (`LogTime`), so the access window works across midnight, month ends and New Year.
- An access line sets `lastAccessIp`. A later IP-less action inherits it only if within **≤3 min**.
- Blank lines, `[UNKNOWN]` lines and any wallet line reset that state.
  - **Developer-confirmed (dump #1):** the game sometimes doesn't log an "Accessed" line, giving two steals in a row after one access. Only the first belongs to the accessed IP; the second must stay unattributed (→ wallet owner or `unknown:wallet_*`). Keep the reset-after-steal rule. Pattern: `[16:03] Accessed <ip>` / `Stole 797 <wallet A>` (→ that IP) / `Stole 772 <wallet B>` (→ none). Covered by `RealDataRegressionTest`.
- `isOwnLogTheft` is true for `Stole…`. `isOwnedSoftware` is true for `download*`.
- **Game log times are UTC+0** (developer). `LogTime.toEpochMs` reads `[M-D H:MM]` as UTC; the year comes from the screen's `Last saved:` footer (`LogParseResult.lastSavedUtcMs`), else UTC now; a month after the anchor's month means last year. Stored thefts from before 2026-11-20 were in phone time and are shifted to UTC once at startup (`thefts_utc_v1` flag); exports carry `theftTimesUtc: true`, and older exports are converted on import.
- A log entry's timestamp is created when the device is accessed: when you log in, or when an attacker accesses you. So completed bypasses can share a timestamp.
- Masking in logs can be partial (`123.xxx.xxx.xxx`, `123.45.xxx.xxx`, `123.45.67.xxx`), full (`xxx.xxx.xxx.xxx`), or `[UNKNOWN]` (Ghost-reboot attacker). This happens because the *other* player's Proxy is active. Trace unmasks passively, but not always.

### 3.4 DatabaseManager write paths
Every write path runs inside `TargetRepository.update { list -> newList }`: updates are serialised (two quick scrapes can't overwrite each other), the result is reconciled once and saved once. Records are immutable, so a path builds new records with `copy(...)` in a `LinkedHashMap` keyed by IP (`recordMapOf`). `processUpdates` keeps a wallet → owner index so each log line doesn't scan the whole DB.
- **processUpdates (logs):**
  - A wallet on a masked or empty IP is redirected to its known owner, or else to `unknown:wallet_<W>`.
  - **Wallet authority (developer rule):** logs only *suggest* wallets (`walletSource = log`). A target with no proven wallet may hold several log wallets. Logs never add to a target whose wallet came from its wallet page, and never move a wallet between two real targets (conflict logged instead). Otherwise, an `unknown:*` placeholder that owns the wallet is merged in.
  - Thefts are added only for own-log `Stole` lines with a parsable time.
  - Software is stored only for downloads onto **unmasked** records (status = action, e.g. "downloaded").
- **processHomeScreen:** upserts by IP, sets fields, `lastValidated = now`.
- **processSoftwareScreen:** finds the record by username, or else creates `unknown:<username>`. Sets downloads with status "installed".
- **processWalletScreen:** finds by username, or else `unknown:<username>`. **The wallet page is proof** (`walletSource = wallet_page`): it replaces every other wallet on the target (dropping their thefts) and takes the wallet, with its thefts, from any other record. Every change is written to `wallet_conflicts.jsonl`.
- **processBypassScreen:** upserts target FW/ENC by IP; on the Scan list, for every unmasked row (new IPs stay never-validated, known ones keep everything else; ENC only when shown, 2026-11-27). Stores the player's Bypasser as `Using Lv` + 1 (Store form). The `(+N)` beside it is the Apex perk and is **not** stored: `getEffectiveBypasserLevel` adds it from the owned device (B12, fixed 2026-11-24).
- **processCrewScreen:** does nothing unless some member IP already has a clan. Then it tags every member and creates bare records for unknown IPs. Masked members are skipped.
- **processProcessScreen:** (the parser skips every group header `<ip>N PROCESS[ES]` (filtered and grouped views), and `extractIp` treats an address as masked only if an octet is `xxx`, so `205.79.204.429` is rejected) creates bare records for new IPs (invalidated-only IPs get `lastValidated = null`; masked IPs and `0.0.0.0` skipped). Records process downloads with status "Unknown" when the level is higher.
- **processStoreScreen / processOwnHomeScreen / processOwnAppsScreen:** update the player profile only. The Apps page (`OwnAppsParser`) stores installed + 1 per app, the Store's NEXT convention, so readers keep subtracting 1; the newest scrape of either wins. `(+N)` bonuses and `LOCKEDLVL` rows (level + Keygen price, `lockedPrices`) are parsed but not stored. The Spam and Siphon earnings pages carry the same Apps text, so `ScrapePipeline` reads levels from them too.

### 3.5 Records, merging, placeholders
- The key is `ip`. Placeholders are `unknown:<Username>` and `unknown:wallet_<W>`. Scraping the Wallet screen (username + wallet) merges them.
- `RecordMerger.mergeRecords(base, incoming)`:
  - Canonical IP: unmasked beats masked, and a concrete IP beats `unknown:*`.
  - Scalars: incoming wins when non-null.
  - **Anti-pollution:** if exactly one side is masked, only the unmasked side's wallets, downloads and thefts survive.
  - Wallets: a proven (wallet-page) wallet wins and the other wallets are dropped with their thefts; with no proven wallet, both sides' log wallets are kept. Conflicts are logged.
- `RecordMerger.reconcile` merges any pair sharing a case-insensitive username or any wallet. `TargetRepository.update`/`commit` run it on every write path.
- `needsAdvancedTargeting` = concrete IP AND (no wallet OR missing Bypasser, Firewall or Password Encryptor in `downloads`).
- Validation queue: unmasked records, never-validated first, then oldest `lastValidated` (a string `yyyy-MM-dd HH:mm`).

### 3.6 Income estimate (`getEstimatedIncome`)
- Own-log thefts are sorted and split into clusters wherever the gap is **> 1 hour**. Clusters longer than 7 days are cut into 7-day windows.
- For each cluster with ≥2 events spanning ≥0.1 h: `sum(amounts except first) / hours`. Display the average as `₵n/hr` (≥10k shown as `₵X.Xk`).
- If no cluster gives a rate, show the latest single theft as a flat `₵n`.
- The 30-day purge keeps old events if removing them would leave a wallet with no multi-event cluster.

### 3.7 Good Spam / upload / payout (MainActivity, GoodSpamUtils, NetworkSpeedUtils, TargetAdapter)
- **Filter:** `firewall ≤ slider` (0–31, 31 = uncapped; null FW counts as 0). Turning it on sets the slider to your effective Bypasser.
- **Sort (developer, 2026-11-22):** `GoodSpamUtils.rankForSpam`: fresh income of your best upload level, descending (100/hr cappers first; unknown level last), then that level's upload time ascending (weakest device first; unknown device last), then Firewall ascending. Replaced the old `level×10 − uploadMinutes×0.5 − firewall` score, whose level term rewarded victim levels that pay nothing extra past the cap.
- **Device tier:** standard names map exactly. Prestige devices map by numeral (I–III=0, IV=1 … XII=9). A bare prestige name is 0. **Unknown strings are 3.**
- **Spam payout model (measured 2026-11-22, 17 fresh uploads):** `GameFormulas.spamIncomePerHour(spamLevel, victimLevel)` = `round(spamMaxIncome(L) / 1.475 × m(victim − spam))`, capped at 100, then decayed. `spamMaxIncome(L) = 13.9 + 4.3L + 0.0645L²` (the rate with the full bonus). `m` = +4%/level the victim is above the **Spam** (max ×1.475; fits 3.65–4.15%, the mirrored +5% is ruled out by victim 19 / Spam 10 = 58), −5%/level the Spam is above the victim (min ×0.10, assumed). **Your own level plays no part** (contradicts the guide's wording).
  - Data (victim Lv: Spam Lv → /hr): 43: 1→18, 2→23, 3→27, 7→47, 11→69. 33: 7→47, 13→81, 20→100. 28: 1→18, 2→23, 6→42. 7: 13→38, 20→30. 19: 10→58. 2: 1→13, 3→18, 7→24, 11→26. 3: 1→13, 4: 1→14, 6: 1→15, 12: 1→18 (2026-11-27, Raw Dumps #83–#148). **Spam above the victim, first reading:** victim 2: Spam 5 → 21 (Raw Dump #200) = the model's −5%/level (×0.85). **The model is 1/hr high at Spam Lv5** (Raw Dump #200 + the 2026-11-27 export for victim levels): victims 21, 23, 24, 25, 26, 28: Spam 5 → 36 (model 37); victim 8 (×2): Spam 5 → 27 (model 28). No quadratic or power curve with this gap model fits all 29 uploads (checked 2026-11-28); not refitted yet, so these points are not in the Spam test. Plus 2026-11-20: Lv16 tops out at 99, Lv17 shows 100.
  - Rejected along the way: a straight line; the guide's player-level gap; the guide's curve × any scale (fails Lv2 victim Lv3 vs Lv7 victim Lv13); a ×0.50 floor (Lv20 on Lv7 = 30 = ×0.35); "income never falls" (developer's assumption, disproved by the developer's own uploads: Lv13 38 > Lv20 30 on a Lv7 victim).
  - **Over-levelled Spam earns less.** `GameFormulas.spamBestLevel(victim, max)` = lowest level with the highest rate. Lv17 is the cheapest cap, on any victim Lv27+. Peak on low victims: Lv2 → Lv10 (26/hr), Lv7 → Lv12 (38), Lv10 → Lv13 (47).
  - **Income is locked at upload** (developer): later level changes on either side don't change it, so SPAM EARNINGS rows can't be joined with current DB levels. Gap data must come from fresh uploads.
  - Unmeasured: Spam more than 13 levels above the victim (×0.10 floor assumed; developer: not worth measuring).
  - `GoodSpamUtils`: `recommendedSpamLevel` = cap level, else the best-earning level (search up to max(30, installed)); `spamUploadLevel` = the best-earning level up to min(recommended, installed). `calculateSpamPayoutInfo(targetLevel, installedSpam)` takes no player level.
- **Current hardware:** `PlayerProfile.currentDevice/currentNetwork` (shown item if MAX TIER, else `decrementHardwareName(next)`); `nextDevice/nextNetwork` are null once maxed. Always use `PlayerProfileRepository.getCurrentNetwork()/getCurrentDevice()` for maths (they fall back to decrement(next) for old profiles).
- **Spam upload level:** `GoodSpamUtils.calculateSpamPayoutInfo` → `recommendedSpamLevel` = **minimum** level reaching 100/hr, searched up to max(30, installed) (`isCapReachable`), `levelMultiplier` exposed. `spamUploadLevel(info, installed)` = min(recommended, installed) (recommended when installed is unknown). The target card chip shows `⏱ Spam Lv.X ~time`; Good Spam's `calculateScore(…, playerNetwork, playerLevel, installedSpam)` uses that level's upload time (falls back to Lv20 when player info is unknown).
- **Player levels:** Store shows NEXT levels, so current = stored − 1. Your own Apps page shows installed levels and is stored as installed + 1 to match (2026-11-24). `getEffectiveBypasser/Cracker` = (next − 1) + 3 if `nextDevice` contains "apex". `updateBypasserLevel` stores `Using Lv + 1` (no bonus; B12).

### 3.8 SiphonTracker
- If `items` is empty and `deployed > 0`, the scrape is ignored (scrolled to the header).
- Visible IPs accumulate. When `deployed == 0` or `accumulated ≥ deployed`, every known IP that wasn't seen is marked `REMOVED`. It is alerted unless it's a "crap install" (known > 24 h and amount < 1000). Then the accumulator clears.
- Re-seeing an alerted IP clears its alert. An amount drop is logged `REINSTALLED` and alerted. A new IP is logged `INSTALLED`, with its start time stored.
- There is **no TTL**: the accumulator persists only in memory, across scrapes.
- Prefs keys: `siphon_alerts`, `known_siphons`, `known_siphon_starts`. Use `clearSiphonTracking()` after a prestige.

### 3.9 Storage
- **The database: `filesDir/targets.json`** (since 2026-11-22), a compact JSON array of `TargetRecord`, written atomically (temp file + rename) on the background queue. Same format as the old prefs string, so the same code reads both.
- Prefs `HackExDB`: `siphon_*`, `thefts_utc_v1`. Until 2026-11-22 it also held `targets` (the whole DB as one string).
- **Version compatibility (developer requirement: never lose data across versions):**
  - First start of the new version: prefs `targets` is parsed, written to `targets.json`, read back, and only then removed from prefs. The original text is kept as `filesDir/targets_from_prefs_<time>.json`.
  - Downgrade then upgrade: an older build writes prefs `targets` again; the next start of this version merges it with `targets.json` (by IP, then reconcile). While on the older build the DB looks empty, but `targets.json` and the Downloads backup are untouched (the older build never writes an empty backup); importing the backup there restores it.
  - Loading is tolerant: a record that can't be read goes to `targets_skipped_<time>.json`, an unreadable file is renamed `targets_unreadable_<time>.json`, and MainActivity shows `TargetRepository.loadReport` once. Nothing is deleted. So incompatible data never blocks loading, and maintenance can keep working on the live DB (it takes a backup first).
  - The JSON field names and value formats of `TargetRecord` and `ConsoleBundle` are the compatibility contract. `WalletSource` uses `@SerializedName` to keep `"wallet_page"`/`"log"`; `lastValidated` stays a `yyyy-MM-dd HH:mm` string; `hardware` stays `"<Device> • <Network>"`. Change a field only with a migration in `TargetRepository.loadCacheFromDisk` and a test in `SaveQueueAndBackupTest`.
- Prefs `HackExPlayerProfile`: `player_profile`.
- Prefs `HackExSettings`: `pref_advanced_targeting`.
- Prefs `AppPrefs`: `hide_permission_warning`.
- `filesDir`: `siphon_history.jsonl`, `wallet_conflicts.jsonl`, `maintenance_backup_<ts>.json`, `marathon_history.jsonl` (one finished Marathon run per line).
- `Downloads/DarkIndex/darkindex_database.json` (pretty-printed full export + player profile) is rewritten `mirrorDelayMs` (30 s) after a save, once per burst, and right away when MainActivity stops, OverlayService is destroyed, or `flushPendingWrites()` runs. An empty DB never overwrites it. The SAF pickers open in `Download/DarkIndex`.
- The manifest has `allowBackup=false` (since 2026-11-24): nothing goes to Google cloud backup. Device-to-device transfer is still allowed. The user's backups are the Downloads copy and Export.

### 3.10 Added 2026-11-20
- **`HardwareCatalog`** — single source of truth for device/network names (standard + 5 prestige lines; devices I–XII, networks I–XI; bare line name = I). `parse/device/network/previous/networkBoost/uploadDefenceTier`. Unknown names → null; consumers keep their old fallbacks (tier 3 / 1.0×), the card marks the name with " ?", and `noteUnknown` logs each new name once.
- **Save queue** — `TargetRepository.saveAllRecords` updates memory immediately and queues disk writes on one background thread (`persistExecutor`, generation counter: always writes the latest state, skips superseded saves; `wipeDatabase` uses the same queue). `flushPendingWrites()` blocks until written (Downloads backup included). Tests set a same-thread executor and `mirrorDelayMs = 0` via `TestEnv`.
- **Reconcile fast path** — `RecordMerger.reconcile` returns early unless `hasSharedUsernameOrWallet` (O(n)); the O(n²) merge only runs when there is something to merge.
- **Profile in backups** — `ConsoleBundle.playerProfile` (optional). Profile writes call `TargetRepository.requestBackupWrite()`. Import uses `PlayerProfileRepository.restoreFromBackup` (fills only missing fields).
- **Wallet authority (developer rule)** — the target's wallet screen is proof (`walletSource = wallet_page`). Log-inferred wallets (`log`, or null on pre-2026-11-20 records) are only probable: a target may hold several. `processWalletScreen` replaces all other wallets (dropping their thefts) and takes the wallet from any other real target (with its thefts); logs only fill missing wallets, never add to a proven one or move one between real targets; `mergeRecords` keeps the proven wallet. All changes go to `wallet_conflicts.jsonl`. Tests: `WalletAuthorityTest`.
- **Bug batch (2026-11-20 afternoon):** `CommonParserUtil.isMaskedIp/stripNavBar/decodeEntities`; masked IPs never become records (Process/Crew); `SiphonScreen.maskedCount` counts towards the full-scroll check; `ScreenType.SPAM_EARNINGS` + `SpamEarningsParser` (parsed, not stored); log classifier matches headers and any timestamped log line; `Failed to crack` parsed; **log times are UTC** via `LogTime` (anchor = `Last saved:` footer; one-time migration of stored thefts, flag `thefts_utc_v1`; `ConsoleBundle.theftTimesUtc`); import saves targets before ingests (B2 fixed); Lv.? chip + level banner; search hidden in Recon/Spam Priority; unknown hardware marked " ?". Tests: `ScreenFixesTest`, `LogTimeTest`.
- **Plans** for the remaining work live inline in `TODO.md`.

### 3.11 Marathon Mode (built 2026-11-21, developer spec)
- **MARATHON** FAB (above VALIDATE) → pick 5/10/20 min → `OverlayService.ACTION_START_MARATHON`. Manifest has `VIBRATE` (the only new permission).
- Phases: `TIMED` → (timer ends, or **End early**) `AWAITING_LOG` (timer shows a big red `0`, one buzz) → own-log grab → `READY_PASS2` (prompt) → `PASS2` (untimed; shows + copies the run's **first** target) → **Finish** → `AWAITING_FINAL_LOG` (Back undoes) → own-log grab → `RESULTS` panel → ✕ → `CHAIN_PROMPT` (5m/10m/20m chain, shows + copies where the last run ended = its latest steal; or End). ✕ on the bar ends the marathon (two taps within 3 s).
- **Every own-log grab during a run feeds it** (`ScrapePipeline.logs` → `Marathon.onOwnLog`, after the DB save), so wiping the log mid-run loses nothing; an empty own log still advances the flow. Own log = `// LOG` / `~/log.txt` with no victim header and no `CONNECTED`/`DISCONNECT`. Toast appends `Marathon +N (M steals)`.
- Steals count from the run's start minute; a chained run never recounts the previous run's steals (key `wallet|time|amount`). Ordering = log time, then parse order (bottom→top = chronological).
- Rows are **per wallet** (developer: match by wallet). Rate = card maths (sum after the first steal ÷ span), but with any span ≥ 1 min instead of the cards' 0.1 h minimum. Rated rows first by rate; single-steal rows after, by amount. Label: log IP, else the wallet owner's concrete IP from the DB, else username, else wallet. Tap a row to copy. Each run's panel is independent (no cross-run ranking, owner).
- In memory only: a marathon does not survive a service/app restart (developer). Tests: `MarathonTest` (fixture `own_log_2026-11-21.txt`, Raw Dump #15).

## 4. Test suite (177 JVM + 2 instrumented; audited 2026-11-20, trimmed 2026-11-22 and 2026-11-24)
**Developer rule (2026-11-22): every test must be able to catch a real bug.** No tests that restate a table or formula (guide cells, lookup tables, arithmetic, pinned numbers from a formula) or that test test helpers: 31 such tests were deleted, including all guide-number checks. A test passing against the guide proves nothing about the game (the Spam model passed 17 guide-cell tests while wrong). Keep: real in-game data (measurements, dumps, verified prices), parsing, DB/merge rules, edge cases, crash guards, content integrity.

**2026-11-24 duplicate trim (213 → 165):** removed tests that a real-data test already covers (synthetic "simulated" parser screens next to their real-logcat twins, classifier checks repeated by parser tests, masked-IP checks on the same fixtures as `RealDataRegressionTest`, `decrementHardwareName` cases that only call `HardwareCatalog.previous`, `parse() == parseDetailed().updates` which can't fail since one calls the other, a Gson-only bundle read). Small checks were folded into the test that covers the same thing. Before adding a test, check `RealDataRegressionTest` and the class's own file for one that already runs that path.

Every test now calls production code. The 2026-11-20 audit found and fixed these "can't fail" tests:
5 in `UnmaskingTest` (re-implemented the logic inside the test), `ConsoleBundleTest.testImportInvalidFormat`
(threw the exception itself), the instrumented `testTargetRecordModelOnDevice` (only read back constructor
values) and a duplicate template test. A mutation check (breaking masked-software filtering, masked-merge
handling and conflict logging) now fails 6 tests; before, the old Unmasking tests would all have passed.

| Class | What it covers |
|---|---|
| **RealDataRegressionTest** (new) | Real Raw Dump screens through `ScrapePipeline.run` → DB: scan list (every real row stored with its own FW/ENC, masked rows skipped, default no-ENC rows keep a known ENC), double-steal attribution, a missing Accessed line can't steal another target's wallet (conflict logged), the same steal seen on two days is stored once, one target built from Home + Wallet + Software + Log, crew clan spreading, own Home → player level, own device page and the Store's Black Market/Credits tabs save nothing (#146, #156, #157), own Apps → installed levels (+ the Apps page is not a siphon scrape; newest scrape wins), Bypass screen doesn't count the Apex +3 twice (B12), prestiged target Home level (`R1` between label and value), Processes (incl. running rows; the filtered/grouped headers "IP + N PROCESS[ES]" are not IPs, #166, #202), Siphons (masked row skipped), the 6 known log discards |
| **DatabaseCoverageTest** (new) | Processes (create/keep/invalidated/downloads only if higher/no 0.0.0.0), Crew (known clan spreads, unknown → nothing saved), Bypass (target defences + stored bypasser), Import (merge by IP/username, siphon state, raw ingests) |
| **WalletAuthorityTest**, **SaveQueueAndBackupTest**, **HardwareCatalogTest**, **ScreenFixesTest**, **LogTimeTest** (new) | Wallet-page authority rules; save queue order, debounced backup, `targets.json` format, migration from prefs, downgrade merge, unreadable records/files kept aside, profile in backups; every hardware name; masked IPs, nav bar, Spam Earnings, entities, failed crack; UTC log times + migration |
| HackExParserTest | Real logcat fixtures for every parser, the 3-min cap, the blank-line barrier, accented names |
| UnmaskingTest | Classification + real `mergeRecords` / `processUpdates` / `runDatabaseMaintenance` (incl. backup file + conflict log) |
| TargetRepositoryTest | Validate doesn't wipe, IP-edit merge keeps the existing wallet + logs the conflict, delete, maintenance keeps thefts, purge shield, Downloads mirror goes only to the backup folder |
| RawDumpTest, PlayerHardwareAndSpamLevelTest | Raw Dump Mode (incl. the compact export on two real Apps pages: shared lines, nav strip, no node tree, same-text dedupe, Log rows pointing at their lines); maxed Store, current hardware, Spam levels |
| **MarathonTest** (new 2026-11-21) | Full run on a real own log: mid-run grabs, End early, wiped log, first target, Back, results order/rates, history file; timer expiry; start-minute filter; chain hint + no recount; own-log detection; DB owner IP for masked steals |
| **KnowledgeBaseTest** (new 2026-11-21) | Real data only: Spam against every in-game upload (+ Lv17 cap), Siphon % against Raw Dump #16, every real Store price (42, Lv 1–35) and software XP reading, Keygen fees against 17 real LOCKED rows (Raw Dump #33, Lv 25–42, and #173, Lv 3–5; fixtures `own_apps_locked_2026-11-24.txt`, `own_apps_locked_r4_2026-11-27.txt`), Proxy activation at Lv 27 (#34), rootkit debuff by level, crew server prices, upkeep and surges (Raw Dumps #25, #27, incl. the treasury balance); KB content integrity (ids, related links, calculator ids, Q-ids, table widths); every calculator at defaults and extremes; profile defaults; search |
| **PrivacyGuardTest** (new 2026-11-24) | The `SECURITY.md` promises: only HackEx 2 passes `ScrapeGate`, the gate comes before any text read or dump, accessibility config stays narrow, no network permission or networking code, cloud backup off, no screen text logged |
| **TargetSearchTest** (new 2026-11-22) | `sw 20` ranking (one-stop FW1 target first), tie-breaks, app aliases + ±1/±2 fallback, Home-screen firewall, level exact/range, plain text not mistaken for a software query |
| ScreenClassifierTest (only what parser tests don't already classify: curly apostrophe, log variants, nav bar, precedence), GoodSpamTest (firewall filter, Spam priority order), SiphonTrackerTest, ConsoleBundleTest, TargetIncomeTest | As named |
| androidTest | Package name; a stored record with missing fields loads through `TargetStore.parse` on ART (read-only) |

Test infrastructure (`TestSupport.kt`):
- `TestEnv.freshRepository()` resets all three singletons (`TargetRepository.resetForTests()`, same-thread save queue, `mirrorDelayMs = 0`) and points `appContext` (a `TestContext` with a temp `filesDir`) and `TargetRepository.backupDirProvider` at a fresh temp folder. Use it in every `@Before`.
- `TestEnv.conflictLog(dir)` reads `wallet_conflicts.jsonl`; `TestEnv.fixture(name)` reads `src/test/resources/fixtures/<name>`.
- **Fixtures are `.txt`** (raw screen text from Raw Dump Mode), because `*.json` is git-ignored.
- The Downloads mirror is only written when `appContext` is set (always true in the app), so tests can't create a stray `DarkIndex/` folder any more.
- Not covered: `getNextTargetForAdvancedTargeting`, all UI (incl. the KB screens; CI's `tests.yml` is what compiles them).

---

## 5. Rules / pitfalls

1. `TargetRecord` is immutable and `getAllRecords()` returns the live list: never cast it to a mutable type. Change data through `TargetRepository.update { }` (or `DatabaseManager`), which serialises writers and reconciles.
2. The accessibility tree walk stays **synchronous on the main thread**. Parsing stays off it.
3. Filter `0.0.*` wherever IPs are collected.
4. Masked and victim-log data must never write wallets, downloads or thefts onto real targets.
5. Large lists (4,000+): keep the `forceFastSwap` (`submitList(null)`) pattern for re-sorts.
6. For any parser change, get a Raw Dump (Raw Dump Mode) or a real ingest, add it as a fixture test, then change the regex. Put new fixtures in `app/src/test/resources/fixtures/` as `.txt` and load them with `TestEnv.fixture()`. The older 2,862-capture corpus `target_console_2026-09-19.json` (`ingests[]`) is local-only research data in `local-data/` (git-ignored).
7. Keep the app 100% offline.
8. Store-derived player levels are **next** levels. Subtract 1.
9. **Versioning is automatic** (2026-11-20): versionCode = 1000 + `git rev-list --count HEAD`, versionName = latest `v*` tag + `-dev.<commits>`; CI passes the release name and the same code rule. Don't hand-edit version numbers.
10. Remote-device file commits can land one version behind. After committing a file to the developer's PC, list the folder and check the size matches; commit again if not.
