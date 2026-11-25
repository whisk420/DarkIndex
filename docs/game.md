# HackEx 2: game knowledge

What DarkIndex knows about the game itself: mechanics, measured numbers, formulas, and what each screen's text looks like. Moved out of CLAUDE.md on 2026-11-22 (CLAUDE.md covers the app and how to work on it).

Code and its tests beat this file for app behaviour; data verified in-game beats both. When the developer confirms or measures something new, record it here.

---

## 1. Source reliability (what to trust)

| Source | Trust | Notes |
|---|---|---|
| Code + passing tests | Highest for app behaviour | |
| Formulas verified against real numbers (§3) | High | Store price and score formulas matched to the crypto |
| hackex.net/guide | Medium | Dev-published, mostly right, but the user says it can mislead, and it contradicts itself in places (Siphon flows, AV scan times). Read in full and exactly on 2026-11-21 (the `DarkIndex` cloud environment allows hackex.net); the guide's numbers now live in `KnowledgeBaseContent`/`GameFormulas`; `docs/knowledge-base-prep.md` is the developer's review tracker |

---

## 2. How the game works (domain model)

### Core loop
Scan → **bypass Firewall** with Bypasser → **crack Encryptor** with Password Cracker → then any of: steal Hot Wallet crypto, download better software (unlock it with Keygen), or upload viruses (Spam/Siphon/Rootkit). XP comes **only** from spending (upgrades, Keygen unlocks), plus missions and login streaks, never from hacking. Rep comes from hacking.

### Identities on screen
- **IP**: pseudo-IPv4 (e.g. `12.34.56.78`). No octet above 255 has been seen in 786 real targets. `0.0.0.0` / `0.0.*` is your own localhost (AV scans) and must never be stored.
- **Masking**: Proxy Lv1–5 masks the last octet, 6–10 two, 11–15 three, 16–19 all (`xxx.xxx.xxx.xxx`). Lv20+ unlocks Stealth. `[UNKNOWN]` appears for Ghost-loadout players. A victim's Trace may unmask an attacker and append ` [TRACED]`.
- **Own log can show masked targets too** (e.g. `Uploaded Lv20 Spam to xxx.xxx.xxx.xxx`).
- **Wallet**: `hx` + 30 hex = 32 chars. Logs show `hx1a2b...9f0e`, and the app normalises everything to `first6...last4`.
- **Clan tag** `[TAG]` (2–10 chars) shows on the profile. The crew screen shows IPs + `LEADER/OFFICER/MEMBER` only.
- **Crew OPSEC (developer, 2026-11-24):** hacking any member shows the attacker the crew roster with every member's IP, and the crew chat unless the **hacked** member owns Message Encryptor (the Store's E2E item). Each roster IP is masked by that member's **own** Proxy. **Trace never rolls on crew screens**, so a masked roster IP stays masked.

### Software (11 apps)
| App | Role | Guide base price | Notes |
|---|---|---|---|
| Firewall | defence vs Bypass | 800 (×0.85) | Upgrading it invalidates incoming bypass and crack rows. Lv3+ "hardening" blocks Backdoors 3%/lvl above 2, max 90% |
| Password Encryptor | defence vs Crack | 1100 (×0.85) | Upgrading invalidates cracks. Hardening blocks Exploit Kits the same way |
| Antivirus | removes viruses | 1000 (×0.85) | Restores 90% of rep drained. Removal odds use their own table (§3) |
| Proxy | masking / Stealth | 900 (×0.85 **verified**, dump #6: Lv21 = 40,046) | |
| Trace | unmasks proxies | 900 (×0.85 **verified**, dump #6) | Defensive roll on every entry, offensive roll at bypass |
| Bypasser | offence vs Firewall | 800 | |
| Password Cracker | offence vs Encryptor | 1100 | |
| Spam | virus, passive income | 1800 | ≤100 Crypto/hr per victim, set at upload by victim level vs Spam level (CLAUDE.md §3.7). Full rate for 21 days, decays to a 10% floor around day 100 |
| Siphon | virus, skims victim's flow | 1600 | **0.75% + 0.25% × level** (Lv1 1%, Lv20 5.75%, 6% from Lv21; measured, Raw Dump #16). Max 700 deployments. Combined cap 30% per victim. No decay. Cold-storage deposits bypass it. Developer (2026-11-24): **only theft is skimmed**, not Spam collections; the guide is wrong here. A small disclaimer stays in the KB |
| Rootkit | virus, debuff | 1400 | Victim's defences −1 (Lv1–2), −2 (Lv3–5), −3 (Lv6+, cap) effective levels for you and your crew (measured 2026-11-22; Lv4–5 by elimination; `GameFormulas.rootkitDebuff`). Only the highest rootkit applies |
| Keygen | device-bound unlocker | 600 | Never downloadable. Key fee is 15% of target-level Store price at jump 1, rising linearly to 50% at a 20+ jump, rounded up (all 9 LOCKED rows of Raw Dump #33 fit; Rootkit 6 → 25 = 44,272 exactly). Full XP. +5 rep |

- **You choose the upload level of a virus** (any level up to your installed one). Lower uploads faster, so the goal is the **minimum Spam level that hits the 100/hr cap**, not your max level (developer uploads Lv17 Spam with Lv20 installed).
- At most **2 virus slots per victim per attacker** (`+ UPLOAD VIRUS 2 / 2 slots`). Many attackers can infect one device.
- Viruses are locked at their upload level. To upgrade, uninstall and re-upload.
- **Consumables:** Backdoor (instant bypass), Exploit Kit (instant crack), IP Scrambler, System Purge, EMP Pulse, Wallet Shield, Log Lock. In-game descriptions (own Apps page, Raw Dump #33): IP Scrambler "fresh random IP; current active bypasses against you will fail"; System Purge "every virus, no scan; restores 90% of lost reputation"; EMP Pulse "cancels all pending processes against you and blocks new ones for 1 hour; 2 hr cooldown"; Wallet Shield "blocks crypto theft for 24 hours; 48 hr cooldown" (siphons keep skimming, guide); Backdoor / Exploit Kit "targets with Firewall / Encryptor Hardening (Lv.3+) have a chance to block it; consumed on use"; Log Lock "locks your device log for 8 hours; 12 hr cooldown".

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
  - `Dark Routing, Backchannel, Fast Deploy, Deep Link` are not hardware names (they look like perk names). All names now live in `HardwareCatalog` (CLAUDE.md §3.10).

### Economy & progression
- **Steal limits (developer, 2026-11-22):** no minimum or maximum per steal (the whole hot wallet can go in one transfer); there seems to be a daily maximum, amount unknown (Q-11).
- **Crypto → Credits (confirmed, Raw Dump #32):** 250 Crypto = 1 Credit, 750 Credits per month, resets on the 1st. Minimum 5 Credits and "hot wallet drains first" are guide-only.
- **Changing your wallet address** (`regen`) costs 50 Credits (guide); old log lines keep the old address.
- **Gas fee (measured, Raw Dump #31, 10 thefts):** gas = floor(10% of the steal); the victim loses the full amount, the thief receives the rest (e.g. 993 → 99 gas, +894). The "Stole N" log shows **what the victim lost** (developer). That's fine because income is only compared across targets.
- **Cold storage cap** by level: 3,000 (L1), 8,841 (L5), 29,124 (L10), 121,891 (L20), 294,147+ (L30+). The developer's own Wallet showed `Max Limit: 755,519` on 2026-11-24 (level not recorded; the developer will measure Q-10 after their next reboot).
- **XP to next level:** L1 589, L5 2,605, L10 5,919, L20 14,209, L30 24,079, L35 29,484, L38 32,860, L44 39,888.
- **Over-levelling hurts:** scans find targets near your level (exact window unknown, Q-12). (Spam income ignores your level: CLAUDE.md §3.7.) Hack rep falls off at a +6..+9 **software** gap (your offence vs their defence software, not player levels) and is zero at +10 (guide).
- **Botnet** tiers (spam slots): Free 2, Node 4, Cluster 8, Grid 16, Farm 28, Hive 44, Nexus 64. There is a scaling fee on spam income up to 30% at Nexus. The Store shows e.g. `Botnet - Nexus / 64 slots / MAX TIER`.
- **Downloaded apps are activated manually** (developer, 2026-11-20). Activation writes **no log line**, so installed levels can rise with no `Purchased`/log evidence. **Both the Apps screen and the Store are reliable for your levels** (developer, 2026-11-20): Apps shows the installed level directly (plus `LOCKEDLVL N available <price> Crypto` for a downloaded version waiting to be activated, and `(+N)` bonuses); the Store shows next level, so subtract 1. They only disagree when one is stale (in dump #8 the developer had upgraded since the last Store scrape). Since 2026-11-24 the app reads both (`OwnAppsParser`, Raw Dump #29); the newest scrape wins.
- **XP from purchases:** one data point: Keygen Lv26 (41,459 Crypto) → +7,045 XP (0.170 XP/crypto, close to the 0.1717 for software in §3); Rep and Score unchanged. Score = floor(L × Rep^0.75) re-confirmed on two own-Home scrapes.
- **Rep loss** per virus = min(500, 25×virus level). Daily loss cap 500, floor 50% of peak. AV restores 90%.
- **Daily missions are RNG** (developer, 2026-11-24): not worth modelling; the guide's spawn rates (80/50/20%) were dropped from the KB.

### Processes (developer, 2026-11-24)
- Finished uploads, downloads, password cracks and antivirus scans can all be deleted; nothing needs them afterwards. Keep the bypass row (its HACK button re-enters the device; guide).
- **Invalidated** rows: delete freely if the IP is masked, even partly. If the real IP shows, re-hack instead: the player may appear masked now, so that row can be the only copy of their real IP.
- **Tags** apply both ways between a player's masked process and their unmasked IP.
- After an **EMP** kills your process, the row must be deleted before that IP can be hacked again (bug or intended, unknown).

### Logs (developer, 2026-11-24)
- Wipe your own log after scraping it: anyone who bypasses you can read it and harvest your best targets.

---

## 3. Verified formulas & tables

**Store price — VERIFIED to the crypto on 5 real prices** (Firewall 21 = 35,596, Bypasser 21 = 41,877, Encryptor 19 = 42,973/4, Keygen 21 = 31,408, Keygen 25 = 39,398):
```
Cubic(L) = (-L³ + 147L² + 6814L - 10212) / 6          # = Keygen price
Price(app, L) = Cubic(L) × base(app)/600 × (0.85 if defensive)
base: Keygen 600, Firewall 800, Bypasser 800, Cracker 1100, Encryptor 1100,
      Antivirus 1000, Spam 1800, Siphon 1600, Rootkit 1400, Proxy 900, Trace 900
```
The cubic is only valid at higher levels (verified L19–25). **Above L25 it runs slightly low:** the Keygen fees in Raw Dump #33 imply curve values +0.04% (L30), +0.1% (L33, two apps agree), +0.26% (L37), +0.45% (L40), +0.6% (L42) above it (Q-09). **Real Store prices, Raw Dump #38 (2026-11-24), real vs formula:** Firewall 32 61,547 (+45, +0.07%), Bypasser 34 78,346 (+103, +0.13%), Encryptor 34 91,567 (+120, +0.13%), Cracker 35 111,862 (+188, +0.17%), Spam 21 94,224 (exact), Siphon 26 110,557 (+2), Rootkit 7 17,570 (+324, +1.9%), Antivirus 31 73,824 (+39), Proxy 28 58,207 (+8), Trace 30 63,669 (+23), Keygen 35 61,016 (+103, +0.17%). Same drift the Keygen fees showed. The LOCKED Lv 34 Bypasser (Raw Dump #35, jump 1) costs 11,752 = ceil(15% × the real Store price 78,346), so the fee is taken from the real price and rounds up. It goes **negative at L1**, so don't use it below roughly L5 without data. It is the Keygen price, not a general "offensive" price.

**Leaderboard score — VERIFIED on 6 profiles:** `Score = floor(Level × Rep^0.75)`.

**Keygen XP (measured, developer logs; badged MEASURED in the KB):** software XP is ≈ 0.1717 XP per full Store crypto, awarded in full on Keygen unlocks. Device upgrades give 0.25 XP/crypto, older hardware/network 0.20.

**Hack (bypass/crack) success — measured from the developer's logged attempts, in code (`TargetRecord.calculateSuccessProbability`):** modifiers (Apex +3, Rootkit −N, Cipher +3) just add up, order doesn't matter.
`delta = offence − defence`: ≥+6 → 95, +5 → 90, +4 → 82, +3 → 74, +2 → 66, +1 → 58, 0 → 50, −1 → 42, −2 → 34, −3 → 18, ≤−4 → 5.

**Guide-derived exact fits (2026-11-21, reproduce every published cell):** Proxy activation `ceil(10 × √ProxyLv × hours × (1 − disc))` (2/6/12/24 h, 0/5/10/15%; matches the game at Lv 27: 104/297/562/1,061, Raw Dump #34, which also shows Stealth reserve `90h / 170h`, refill +1 h per 1.5 h off, activations stack with no cap); Spam slot fee `min(30%, 0.5% × (activeSpam − 2))`; Spam decay `100 − (day − 21) × 90/79` %, floor 10%; Spam income: the guide curve `8.85 + 2.89L + 0.0415L²` × gap multiplier (×1.41 at +10, ×0.5 at −10) hits all 17 guide cells, **but in the game the gap is victim level − Spam level and the curve is different** (measured 2026-11-22, CLAUDE.md §3.7). Store prices: offensive round to nearest (Bypasser 21 = 41,877.33 → 41,877), defensive ×0.85 round up (Proxy 21 = 40,045.2 → 40,046); Encryptor 19 → 42,975 vs the recorded "42,973/4" is unresolved. All in `GameFormulas`. The Store cubic misses the guide's low-level prices (Keygen L5 4,862 vs 4,568).

**Siphon % (measured 2026-11-22, Raw Dump #16, all 122 deployments):** `0.75 + 0.25 × L` fits Lv1/2/5/6/13/17/20; Lv25 = 6% (not 7%), so `GameFormulas.siphonPercent` caps at 6% from Lv21 (the line reaches 6% at Lv21; developer-accepted). **Max 700 siphon deployments** (developer). A siphon removed by the victim's AV vanishes from SIPHON EARNINGS with all its info; the game keeps no history.

**AV removal (guide, not in code):** AV−virus ≥+5 → 95, +4 → 90, +3 → 80, +2 → 70, +1 → 60, 0 → 50, −1 → 20, then −2% per level to a 5% floor at −9.

**Upload base seconds** (`NetworkSpeedUtils`, from an old benchmark: Lv20 virus vs a 15× network, ×15 to normalise to DSL):
tier 0..9 → 45,285 / 60,390 / 75,480 / 90,585 / 105,720 / 120,825 / 135,930 / 151,035 / 166,140 / 181,245 s, about +15,100 s per tier. Virus level scaling is `(L/20)^2.25`.

---

## 4. Screen text formats (from real captures)

Parser-facing descriptions of each game screen's accessibility text, mostly learned from Raw Dumps (CLAUDE.md §3.2). Fixtures for most of these live in `app/src/test/resources/fixtures/`.

**The game's UI in general**
- Game package: **`net.cncapps.hackex2`** (seen in dump #1).
- **The game UI is a WebView** (`android.webkit.WebView` → `android.view.View`/`TextView`/`EditText`), which is why game text contains HTML entities. A log screen is only ~20 nodes.
- **Log screen = one `EditText` holding the entire log** (dump #1: exactly 100 `[M-D H:MM]` lines, newest first), so one scrape captures the whole log, not just the visible part. **The game keeps only the last 100 lines** (developer-confirmed), and that includes non-entry lines such as ASCII-art graffiti or messages written to hackers. The log is editable text, so expect non-`[M-D H:MM]` lines and possibly hand-written/forged lines, especially in victim logs. Scrape often to avoid losing steals.
- **Log lines are NOT strictly chronological** (e.g. `[16:38] Uploaded…` sits below `[17:40]` lines). Entries are inserted when written, with the timestamp from when the device was accessed. Don't assume monotonic times.
- The log screen footer shows **`Last saved: YYYY-MM-DD HH:MM:SS` in UTC**, a full date usable to anchor log times.
- **Every screen's raw text ends with the bottom nav** `HOME`, `SCAN`, `PROCESSES`, `APPS`, `STORE`, `CREWS <n>` (n = unread crew messages). The nav buttons have view ids `nav-item-dashboard|scan|processes|apps|store|crews`. Parsers must never key on these words.
- **The bottom nav only appears on your OWN screens.** Target screens (Home/Wallet/Apps/Crew while CONNECTED) don't have it; they start with `CONNECTED`/`< back` + `DISCONNECT`.
- **Target Home (2026-11 format):** `> <user> _ [TAG]` on one line; `LVL` / `33` (a prestiged target puts its rank between them: `LVL` / `R1` / `48`, Raw Dump #28), `REP` / `20,000`, `SCORE` / `55,000` as label/value line pairs; SYSTEM INFO as label/value pairs. The Notepad button has desc `Notepad`, the avatar has desc `badge`.
- **Wallet:** the address is on its own line after `WALLET ADDRESS`; the balance line is mashed: `HHOT WALLET10,000 CryptoCCOLD STORAGESecured1,000 Crypto/ 362,654 max`; the transfer box has view id `steal-amount`.
- **Proxy panel (own Apps → Proxy, Raw Dump #34):** `Proxy`, `LVL 27`, description, `STEALTH MODE UNLOCKED`, `STATUS` `ACTIVE` `Lv27` `11h 43m`, `STEALTH RESERVE` `90h / 170h`, `Refills +1h per 1.5h with Stealth off`, `STEALTH OFF` + "Your Proxy still masks your IP.", `> Select Duration` `2h 104 Crypto` … `24h 1,061 Crypto`, "Activations stack — buy another while active to extend your timer past 24h. No cap." Appended after the Apps page text in the same snapshot.
- **Own Wallet (Raw Dumps #31, #32, 2026-11-24):** `// WALLET`, the address after `WALLET ADDRESS` / `?`, then `regen`; `H` / `HOT WALLET` / `<n> Crypto` and `C` / `COLD STORAGE` / `Secured` / `<n> Crypto` / `Max Limit: <cap> ⓘ` on separate lines; transfer box id `transfer-amount`. Collapsible `// Convert to Credits` (`RATE` `250 in-game Crypto = 1 in-game Credit`, `MONTHLY` `750 / 750 Credits (0 left)`, `Monthly limit reached. Resets on the 1st.`) and `// Transaction History` (per theft: `THEFT`, `Crypto stolen from another player`, shortened victim wallet `hxfec3...ace3`, `Network (Gas) Fee: -99 Crypto`, `+894`, `1h ago`; 10 rows then `LOAD MORE`). Classified WALLET; nothing is saved (no username).
- **Software:** `+ UPLOAD VIRUS 0 / 2 slots ▼` = **your** viruses on that target (0 after the target's AV wiped them; developer-confirmed); each app has an icon node whose desc is the lower-case app name, then name, description, `LVL n`, and a delta badge vs your **installed** level (downloaded-but-not-installed copies don't count). The parser **intentionally ignores** the badges.
- **Crew:** `// <name incl. emoji> Members`, then ip/role pairs. **Off-screen rows are still exposed** (bounds clamp to the screen height of 2244), so the whole roster is captured without scrolling. Rosters can contain masked IPs.

**Per screen**

**Home (target, connected):** each item is on its own line:
`CONNECTED`, `DISCONNECT`, `>`, `<username>`, `_`, optional `[TAG]`, `badge`, optional `R1`, `LVL 38`, `//`, `REP 6,000`, `25,000 SCORE`, optional `90,000 LIFETIME`, `// XP PROGRESS`, `16000 / 32860`, `49%`, `Wallet` `Apps` `Processes` `Log` `Crews`, `// SYSTEM INFO`, `IP`, `<ip>`, `DEVICE`, `Nova`, `NETWORK`, `Fiber`, `FIREWALL`, `Lv.24`, `ENCRYPTOR`, `Lv.25`.
- `REGEX_USER_HEADER` (`^\s*>\s*(.+?)$`) works because `\s*` spans the newline after `>`.
- The clan is found by the generic `[..]` fallback.

**Own home:** `> Name_`, `MY DEVICE`, `R<n>`, `LVL <n>`, REP, SCORE, `// CRYPTO`, `// CREDITS`, `// DAILY MISSIONS`.

**Software:** `<user>'s installed software`, `+`, `UPLOAD VIRUS`, `2 / 2 slots`, `▼`, then for each app: a lower-case id line (`antivirus`), `Antivirus`, description, `LVL 24`, delta badge (`=`, `+2`, `-6`, or `DEVICE-BOUND` for keygen).

**Wallet:** `<user>'s wallet`, `WALLET ADDRESS`, `<32-char addr>`, then `HHOT WALLET100 CryptoCCOLD STORAGESecured500 Crypto/ 29,124 max` all on one line.

**Store:** `// STORE`, `Notepad`, `Wallet:`/`<crypto>`, `Credits: <n>`, `UPGRADES/CREDITS/MARKET`; `// NEXT DEVICE`, `Apex VII`, `LVL 7`, specs, price; `// NEXT NETWORK`, `Null Route X`, `LVL 10`. **When maxed, the header drops NEXT** (`// NETWORK`), shows the item you own, and has `MAX TIER` instead of a price (dump #6: `Null Route XI` / `LVL 11` / `MAX TIER`). Software rows have view ids `sw-<n>`; `// BOTNET`, `Botnet - Nexus`, `64 slots`, `MAX TIER`; `// SOFTWARE`, then `<App>` `LVL <next>` description, price, `UPGRADE`. Keygen's description contains the STORE signature.

**Scan (Raw Dump #24, fixture `scan_2026-11-22.txt`):** `// SCAN`, Notepad, `Wallet:`/`<crypto>`, `Credits:`/`<n>`, `SCAN`, `REFRESH SCAN`, `// SCAN RESULTS`, `12 targets` (10 default + crew Uplink +2), then one row per target `1.2.3.4 FW Lv.23 ENC Lv.24 >`; rows can be masked (`xxx.xxx.xxx.xxx`, `226.217.xxx.xxx`); **ENC is not default**: it shows because the developer is on an Apex reboot (guide: Null Route perk), so default rows are presumably `<ip> FW Lv.N >` (unseen; the row regex makes ENC optional); ends with the bottom nav. `BypassScreenParser` stores only the first unmasked row, with that row's own FW/ENC (fixed 2026-11-22: it used to take the first FW/ENC on screen, even from a masked row). Scraping every row is a to-do.

**Own Apps (Raw Dump #29, fixture `own_apps_2026-11-24.txt`):** `// APPS`, Notepad, `Wallet:`/`Credits:`, a SPAM row (`63` `/ 67` `+3` `Botnet - Nexus` `6,262/hr` `150,288/day`), a SIPHON row (`126` `76,789` `DEPLOYED EARNED`), `▼ Installed Applications`, `List view`/`Grid view`/`[-]`, then per app: name (Proxy/Spam/Siphon carry a trailing space), optional status lines (`ACTIVE` + `12h 27m`, `6,262/hr`, `76,789 siphoned`), `LVL <installed>`, optional `(+3)` (the Apex reboot perk on Bypasser and Password Cracker, developer-confirmed; a separate node beside the level, so `LVL` excludes it). The `+3` on the SPAM row at the top is different: extra Spam slots from crew-server RAM (64 Nexus + 3 = 67), only for players whose crew has RAM, optional `LOCKEDLVL <n> available<price> Crypto`. Notepad and Message Encryptor have no level. Then `▼ Consumables` (name, description, `Use`, `x<count>`) and the bottom nav. The page contains "siphoned" and "DEPLOYED", which is why it's checked before the old Siphon signature. The Spam and Siphon earnings pages start with this same text.

**Bypass:** `// BYPASS FIREWALL`, `Firewall Lv.23`, `Encryptor Lv.24`, `Using Bypasser Lv.20`, `(+3)`, `Use Backdoor x<n>`. `Using Bypasser Lv.N` is the installed level; the `(+3)` is the Apex perk shown separately (Raw Dump #30: Lv.31 + (+3), with the Apps page also saying 31). Seen as a popup over the Scan results (fixture `bypass_apex_2026-11-24.txt`).

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

## 5. Developer Q&A (answered 2026-11-19)

**Game**
1. "Stole N" = what the **victim lost** (pre-gas-fee). Consistent across targets, so fine.
2. Raider / II / III targets **upload equally fast**. Rigorously confirmed.
3. Concurrent processes do get slower, but it's unknown whether that's overhead or bandwidth sharing. Not modelled (the app can't count processes).
4. Prestige names: see §2 (from the guide).
5. Player's own rebirth/hardware: not relevant to code; changes over time.
6. At max tier the Store shows the tier-11 network with **`MAX TIER`** instead of a price.
7. Masked IPs come from the other player's Proxy. Partial masks (1–3 octets) are possible; `[UNKNOWN]` means a Ghost-reboot attacker. Trace unmasks passively, not guaranteed.
8. Log timestamps are created when the device is accessed (your login, or an attacker's access).
9. Log times are **UTC+0**.
10. Spam payout vs level gap: measured since (2026-11-22, 17 uploads); see CLAUDE.md §3.7.
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
- The developer wanted an in-app **Raw Dump mode** instead of tethered logcat (built 2026-11-20, CLAUDE.md §3.2).
