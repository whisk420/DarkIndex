# Knowledge Base — preparation (2026-11-21)

Plan, topic map and the owner's measurement list for the in-app Knowledge Base.

**Status (2026-11-21): built.** Owner answers: use the guide's Spam numbers (done: `GameFormulas`, all 17 cells
exact); content stays in Kotlin (`KnowledgeBaseContent.kt`); calculators implemented (9, in `KbCalculators`);
IAPs kept brief (one short topic). §2's asset-file recommendation was declined. §4 (M-01..M-25) is the live
to-do list for the owner; when a value is measured, update `GameFormulas`/`KnowledgeBaseContent` and switch the
fact's badge to MEASURED.

Sources: CLAUDE.md, the code, and a full read of **hackex.net/guide on 2026-11-21** (fetched directly this
time, not through a summariser, so the guide figures below are exact).

Source tags used throughout (these become the badges in the app):

| Tag | Meaning |
|---|---|
| **[M]** | Measured by the owner / verified against real game numbers |
| **[F]** | Formula that reproduces every published number exactly (derived here or in CLAUDE.md §4) |
| **[G]** | Stated by hackex.net/guide, not yet checked in-game |
| **[O]** | Owner knowledge (CLAUDE.md §10) |
| **[E]** | Empirical from GEMINI.md, unverified |
| **[?]** | Unknown. Has a measurement id (M-xx) |

---

## 1. Current state

- `KnowledgeBaseActivity` + `activity_knowledge_base.xml` + `item_kb_topic.xml` exist. The topic list is
  hard-coded in Kotlin (3 topics, a "Read More" expander per card).
- The home-screen button already exists: `knowledgeBaseBtn` in `activity_main.xml`, **directly left of
  `siphonAlertsBtn`**, set to `visibility="gone"`, and its click listener is commented out in
  `MainActivity` (~line 251). Re-enabling is two lines.
- The existing three topics contain errors and must not ship as-is:
  - "100 credits/hr" → the cap is 100 **Crypto**/hr.
  - "~2.00/hr per Spam level on same-level targets" contradicts the guide table (≈3–4.2/level, §3.2).
  - The Spam penalty numbers (2.6/3.3 per gap level) are GEMINI's and disagree with both the code and the guide (B6).
  - "Offensive 1.00x / Defensive 0.85x" is right, but Proxy and Trace are missing from the defensive list.

## 2. Proposed design

**Navigation (as requested):** home toolbar 📖 button next to Siphon Alerts → **KB index page** (one card per
topic: icon, title, 2–3 line overview, a small "measured N / guide N / unknown N" tally) → tap a card →
**topic page** with everything about it.

**Topic page layout:**
1. Overview paragraph (plain language).
2. "Key numbers" table, every row tagged [M]/[F]/[G]/[O]/[?].
3. Detailed sections (mechanics, edge cases, interactions with other topics, linked as chips).
4. Strategy notes (what to actually do).
5. Calculator(s) where a formula exists — this is the main way we beat the guide. Reuse app code
   (`NetworkSpeedUtils`, `GoodSpamUtils`, `TargetRecord.calculateSuccessProbability`, store-price cubic),
   pre-filled from the player profile.
6. "Open questions" footer listing the [?] items with their M-ids, so gaps are visible, not hidden.

**Content storage:** keep content out of Kotlin string literals. Recommendation: one
`app/src/main/assets/kb/<topic>.md`-style file per topic (lightweight markdown subset rendered to
`Spanned`), with the tags inline. Not `.json` (git-ignored). Benefits: content edits don't touch code, the
owner can edit text directly, and a JVM test can check that every topic parses, every [?] has an M-id, and
every M-id exists in this list.

**Other features:** search across all topics (title + body); "last verified" date per fact; formulas shared
with the rest of the app so the KB and the target cards can never disagree.

**Effort estimate:** UI + loader + renderer + tests ≈ 1 session. Content is the bulk of the work and grows as
measurements arrive.

---

## 3. Topic map

Order below is the proposed index order. Each topic lists what we already know and what's missing.

### 3.1 Getting started & the core loop
- Start: Raider (1×), DSL (1×), 3,000 Crypto, 50 OC, 100 Credits, FW / Bypasser / Cracker / Encryptor Lv1 [G].
- Loop: Scan → Bypass → Crack → steal / download / upload → stash to cold storage [G][O].
- XP never comes from hacking, only from spending, missions, streaks [G][O]. Rep comes from hacking [G].
- Guide tip: upload a Rootkit **before** cracking (debuffs the encryptor) [G].

### 3.2 Spam virus & Spam economy  ⚠ highest value topic
- Cap 100 Crypto/hr per victim [G]. Locked at upload level; uninstall + re-upload to upgrade [G][O].
- **Guide earnings table** [G]:

  | Spam Lv | vs equal Lv | vs +10 Lv | vs −10 Lv |
  |---|---|---|---|
  | 1 | 12 | 17 | — |
  | 5 | 24 | 34 | — |
  | 10 | 42 | 59 | — |
  | 15 | 62 | 87 | 31 |
  | 20 | 83 | 100 | 42 |

  - Exact fit [F]: `round((8.85 + 2.89·L + 0.0415·L²) × m)`, m = 1 + 4.1%/level above (max 1.5), 1 − 5%/level below: all 17 cells.
  - +10 above ≈ ×1.40 on every row; −10 below ≈ ×0.50. Guide tips say "up to 50% more against higher-level
    victims", so the bonus probably caps at +50%. Shape between 0 and ±10 unknown → **M-01**.
  - The app now uses this model (owner, Q1). Before, `spamLvl×5` gave Lv20 = 100 at equal level (guide: 83).
- **Decay** [G], fits a straight line exactly [F]: 100% for days 1–21, then `100 − (day − 21) × 90/79` %,
  floor 10% from day 100 (gives 89.7 / 67.0 / 44.2 / 10 for days 30/50/70/100; guide 90/67/44/10).
  Per deployment, not per victim. Scythe device halves decay speed [G] → **M-02** (does that mean floor at day ~179?).
- **Slot fee** [G], exact fit [F]: `fee = min(30%, 0.5% × (active spam − 2))`, charged on collection, by
  **active** count, not tier. Hacker Pass −25% fee, Elite −50% [G].
- **Botnet tiers** [G]: Free 2 / Node 4 (800, Lv3 req) / Cluster 8 (3,500) / Grid 16 (15,000) / Farm 28 (42,000) /
  Hive 44 (95,000) / Nexus 64 (180,000). Crew-server RAM adds up to +5 slots.
- Rep from upload: flat 5, once per victim per type per 24 h [G]. Self-recall gives the victim 100% of the rep back [G].
- Calculator: Spam level × your level × target level × days since upload × active count → net Crypto/hr.
- Gaps: M-01, M-02, M-03.

### 3.3 Siphon virus
- 1% at Lv1 [G]; % per level beyond that unknown → **M-04**. Combined cap 30% per victim across attackers [G].
- No decay, no slot cap [G]. Scythe device: 2× drain [G].
- ⚠ **Guide contradicts itself on what is skimmed**: the Software section says "theft, incoming transfers, spam
  collection, mission and login rewards"; the Viruses section says "only theft and spam collection"; other
  sections say mission/login/ad/stash/subscription Crypto go straight to cold storage and are immune. → **M-05**.
- AV wipe removes the row from SIPHON EARNINGS immediately [O].
- Gaps: M-04, M-05, M-06.

### 3.4 Rootkit virus
- Debuffs victim FW + ENC by up to 3 effective levels for you and your whole crew; only the highest rootkit
  applies [G]. Applies before the Backdoor/Exploit hardening roll [G].
- Debuff per rootkit level unknown → **M-07** (the upload card shows "defense debuff" stat line [G], so one dump answers it).

### 3.5 Virus rules (shared)
- 2 slots per victim per attacker, any 2 of the 3 types [G][O]. Many attackers can infect one victim.
- Choose upload level ≤ installed [O]. Lower uploads faster.
- Victim rep loss `−min(500, 25 × virus level)`, daily loss cap 500, floor 50% of peak, AV restores 90% [G].
- Performance penalty −1% per active virus on a device, cap −40%; hits bypass, crack, download, upload, AV [G][O].

### 3.6 Uploading & downloading (network)
- Network table [G] (boost / price): DSL 1.0 free, Cable 1.5 / 3,000, Cable+ 2.25 / 7,000, 4G LTE 3.5 / 12,000,
  5G 5 / 20,000, 5G+ 7 / 30,000, Fiber 9.5 / 44,000, Fiber+ 12 / 60,000, 10G Fiber 15 / 85,000,
  10G Fiber+ 18 / 120,000, Quantum Link 21 / 170,000.
- Upload time ≈ `base(victim device tier) × (virusLv/20)^2.25 / network boost` [E, CLAUDE §4], victim tiers 0–9 with
  Raider I–III equal [O]. Scythe network −50% upload, Apex network −40% download [G].
- Download time formula unknown → **M-08**. Upload model needs re-checking at more points → **M-09**.
- Concurrency: +75% per extra process [G] (app deliberately doesn't model it).

### 3.7 Bypass & crack (device)
- Device table [G] (boost / price): Raider 1.0 free, Raider II 1.5 / 4,000, Raider III 2.25 / 10,000, Bolt 3.5 / 18,000,
  Bolt II 5 / 28,000, Bolt III 7 / 40,000, Nova 9 / 56,000, Nova II 11 / 82,000, Nova III 14 / 120,000,
  Nova S 17 / 160,000, Nova X 20 / 220,000, Nova Ultra 23 / 300,000. Divides bypass, crack and AV time [G].
- Success odds by `offence − defence` [E, in code]: ≥+6 95, +5 90, +4 82, +3 74, +2 66, +1 58, 0 50, −1 42, −2 34,
  −3 18, ≤−4 5. **Not in the guide at all** → **M-10** (big win if verified).
- Base duration by FW/ENC level and gap unknown → **M-11**.
- Firewall upgrade kills incoming bypass + crack rows and gives a 4 h protection window; the cracked-password
  authorisation survives a FW upgrade and comes back on re-bypass unless ENC also rose [G]. ENC upgrade
  invalidates cracks lazily (checked on next wallet access) [G].
- Rootkit, Apex (+3 offence), Cipher (+3 defence) modifiers [G]. Order of stacking unknown → **M-12**.

### 3.8 Processes
- Statuses: Pending, Completed, Failed, Invalidated, EMP Killed [G].
- OC skips 90 s each; ads cut 10/9/8/7% (34% total, so additive, max 4 per process) [G].
- Limits: 500 running, 1,000 rows total [G].
- Concurrency +75% per extra process in the **pool**; AV shares the hack pool [G]. Do upload/download share a
  separate pool? → **M-13**.

### 3.9 Antivirus
- Removal odds by `AV − virus` [G]: ≥+5 95, +4 90, +3 80, +2 70, +1 60, 0 50, −1 20, −2 18, −3 16, −4 14, −5 12,
  −6 10, −7 8, −8 6, ≤−9 5. Rolled per virus per scan [G]. Scythe viruses count +2 [G].
- Scan time depends on **device**, not AV level; 3 h on Raider, floor 45 min [G].
  - ⚠ Guide disagrees with itself: text says ~55 min at device Lv10 and ~48 min at Lv12, matching
    `180 / (1 + 0.25·(devLv − 1))` min exactly; the table says 53 min / 45 min. → **M-14**.
- AV restores 90% of rep lost to the removed viruses [G].

### 3.10 Firewall & Encryptor hardening, consumables
- Hardening: block chance `3% × (level − 2)` from Lv3, cap 90% (FW vs Backdoor, ENC vs Exploit Kit) [G].
  Blocked items are still consumed. Cipher network = 100% block [G].
- Consumables and Black Market prices [G]: Backdoor 40 Cr (Common), Exploit Kit 50 (C), IP Scrambler 200 (C),
  Log Lock 12 (Uncommon, 8 h, 12 h cooldown), OC 50-pack 45 (U), Crypto Stash 10K 100 (U, 1/day, to cold),
  Wallet Shield 150 (U, 24 h, 48 h cooldown), System Purge 85 (Rare), EMP Pulse 80 (R, 1 h, 2 h cooldown),
  OC 150-pack 90 (R), Crypto Stash 40K 400 (R, 1/day). Daily stock 5–6 items; appearance ≈ Common 71% /
  Uncommon 52% / Rare 30% [G].
- Stealth-on Backdoor/Exploit leaves no victim log entry [G].

### 3.11 Proxy, Stealth & Trace
- Masking: Lv1–5 last octet, 6–10 two, 11–15 three, 16–19 all, 20+ Stealth toggle [G][O].
- **Activation cost** [G], exact fit on all 20 cells [F]: `ceil(10 × √ProxyLv × hours × (1 − discount))`,
  hours 2/6/12/24 with discount 0/5/10/15%. Stacks at the same level with no cap; upgrading mid-activation keeps
  the old level and re-activating forfeits remaining time [G]. Calculator.
- Stealth reserve: pool 170 h, drains 1:1 while on, refills 1 h per 1.5 h off (Ghost Link 1:1), 15 min minimum [G].
- Trace: offensive roll once at bypass (incl. Backdoor) vs victim mask level; defensive roll on **every device
  entry**, not on sub-pages or individual crack/download/upload [G]. Equal levels ≈ 50% [G]; curve unknown → **M-15**.
- Rep: proxy hack chain +10 (24 h gate), Trace detection `15 + (traceLv − 1)`, cap 25 [G].
- Ghost: `[UNKNOWN]` in logs, Trace-immune, last octet hidden in scanners even without proxy; Ghost Link: proxy +5
  vs Trace and 2× duration [G].

### 3.12 Keygen & software prices
- Store price cubic, verified to the crypto at L19–25 [M][F, CLAUDE §4]. Offensive prices round to nearest,
  defensive (×0.85) round up: fits every exact verified price and the guide's Lv10 column [F]. Encryptor 19's
  recorded "42,973/4" vs 42,975 is unresolved.
- ⚠ Cubic fails at low levels: guide Keygen Lv5 = 4,862 (cubic 4,568), Lv10 = 11,972 (cubic 11,938),
  Lv15 = 20,281 (cubic 20,283). → **M-16**.
- Key fee: 15% of target-level Store price at a 1-level jump, linear to 50% at 20+ [G]. Exact interpolation and
  rounding unknown → **M-17** (the Apps screen shows `LOCKED LVL N available <price> Crypto`, ideal data).
- Can't key at or below your level [G]. Full XP for the unlocked level, +5 rep [G].
- One-off utilities: Notepad 5,000, Message Encryptor 9,000 [G].
- Calculator: cost of a jump vs chained steps; XP gained.

### 3.13 Wallet, theft & gas
- Hot vs cold; cold cap by level: L1 3,000, L5 8,841, L10 29,124, L20 121,891, L30 294,147+ [G][O]. Full table → **M-18**.
- Gas: victim loses 100%, thief gets 90%; "Stole N" shows the victim's loss [G][O].
- Max steal per transfer / per access? → **M-19**.
- Wallet Shield blocks manual theft 24 h, siphons still work [G].
- Crypto → Credits 250:1, min 5 Cr, cap 750 Cr per calendar month (UTC), drains hot then cold [G].
- Address `hx` + 30 hex; regenerate 50 Cr; theft log shows truncated attacker wallet [G][O].

### 3.14 Logs
- Format `[M-D H:MM]` UTC, last 100 lines kept, editable (forgeable), not strictly chronological, `Last saved:` footer [O][M].
- Log Lock prevents editing for 8 h [G]. Who can edit (anyone who bypassed) [G].
- All known line formats (CLAUDE §5.2) with meaning; what each tells you. The app's own parsing explained.

### 3.15 Scanning & matchmaking
- "Scan Random" returns up to 10 targets near your level, widening as needed [G]; ~±10 levels [O].
- Crew-server Uplink: +1 target per level (15 at Lv5, 17 with surge) and reaches a little higher [G].
- Exact level window, Stealth exclusion, direct-IP lookup [G]. → **M-20**.
- Apex network shows target ENC on scan [G].

### 3.16 Reputation & score
- Score `floor(Level × Rep^0.75)` [M ×6 profiles].
- Earning [G]: bypass/crack scales with victim defence and software gap, up to ~105 (formula → **M-21**);
  download/upload flat 5; Backdoor/Exploit base only, min 5; defence (attacker fails) `15 + ⌈attacker SW ÷ 5⌉`
  once per attacker per 24 h; proxy +10; Trace `15 + (lv − 1)` ≤ 25; Keygen +5. All hack rep gated once per
  victim per process type per 24 h.
- Gap penalty uses **software** levels, not player levels [G]: 0..+5 full, +6..+9 linear, +10 zero.
  ⚠ CLAUDE.md §3 says "matchmaking … rep falls off at +6..+9 gap" next to player levels; worth rewording.
- Losing: −min(500, 25 × virusLv), 500/day cap, 50% of peak floor, AV restores 90% (scar 10%) [G].
- Leaderboards rank score **growth** in the period (current month, previous month, rolling 3 months) [G].

### 3.17 Leveling & XP
- XP to next [G][O]: L1 589, L5 2,605, L10 5,919, L20 14,209, L30 24,079, L35 29,484, L38 32,860, L44 39,888.
  Cumulative [G]: L5 7,752, L10 30,437, L20 133,683, L30 328,940. Full table → **M-22**.
- XP per crypto [E/M]: software ≈ 0.1717 (one owner data point 0.170), device 0.25 [G], older hardware/network 0.20 [E]. → **M-23**.
- Level-up: +10 OC, +10 Credits, bigger cold cap [G].

### 3.18 Missions, login streak, Watch & Earn
- Missions [G]: 3/day, at most one per group (hack: bypass/crack/download; upload: spam/rootkit/siphon); a victim
  counts once per slot per 48 h; reset UTC midnight. Easy 100 XP / 30 rep / 250 Crypto or 3 OC (80% spawn),
  Medium 150 / 55 / 500 or 7 (50%), Hard 200 / 90 / 1,000 or 15 (20%). Hack missions pay Crypto, upload missions OC.
  Crypto goes to cold storage, ignores the cap, siphon-immune.
- Streak [G]: D1 100 XP, D2 5 OC, D3 500 Crypto, D4 5 Cr, D5 1,000 Crypto, D6 10 OC, D7 15 Cr; subs double it.
- Watch & Earn [G]: per type 4/day — Credits 3, Crypto 250 (to cold), OC 5.
- Spawn-rate percentages don't sum to 100 → **M-24** (probably independent per-slot rolls).

### 3.19 Credits, OC, IAP, subscriptions
- Earning/spending lists, OC packs (50/55 Cr, 150/125, 400/250, 1,000/500), credit packs, Starter Kit, Hacker
  Pass / Elite Pass perks and drops [G]. Username change 200 Cr / 30 days [G]. Low priority; reference only.

### 3.20 Crews & Crew Server
- Size 30 (+10 with Storage), roles, join policies, one crew at a time [G].
- OPSEC: proxy is per-person in crew views; Message Encryptor protects on the **viewing** device [G].
- Server [G]: CPU +3%/lvl hack speed, NIC +3%/lvl transfer, RAM +1 spam slot/lvl, Uplink +1 scan target/lvl,
  Storage +2 seats/lvl; max Lv5 each. Upkeep 0.5% of value/day (table given; Storage free). Contribution limit
  2,000 × player level/day, cap 50,000; 24 h before new members contribute/receive buffs. Surge +2 levels for
  6/12/24 h. Part costs and surge costs not published → **M-25**.

### 3.21 Hardware tiers & prestige hardware
- Standard device/network tables (above). Prestige lines and names: `HardwareCatalog` [M].
- Upload defence tiers 0–9 (Raider I–III equal) [O]. Process speed multipliers [G].

### 3.22 Reboot Protocol (prestige)
- From Lv50, optional forever, R-tier uncapped; R1–R5 one new loadout each, R6+ mix device and network [G][O].
- Resets/keeps lists [G]. Monthly leaderboard progress is kept [G].
- Loadout passives with the guide's perk names (device / network) [G]:
  Ghost (Ghost Strike / Dark Routing), Vector (Efficient Use / Backchannel), Scythe (Siphon Surge + Slow Burn /
  Fast Deploy + Buried Roots), Cipher (Hardened Core / Adaptive Block), Apex (Overreach / Deep Link).
- Prestige hardware speeds equal the standard item at the same position [G][M].

### 3.23 DarkIndex itself (how the app works)
- What each screen scrape does, wallet authority, income estimate, Good Spam, Recon, Raw Dump Mode.
  Written from CLAUDE.md §5; no measurements needed.

---

## 4. Measurement to-do list (for the owner)

Ordered by value. "Capture" says what to send; a 📼 Raw Dump of the screen is always the best evidence.

### High priority (feeds app maths directly)
- **M-01 Spam income vs level gap.** Your SPAM EARNINGS page shows Cr/hr per victim. Send dumps with a spread of
  victims (target level known from their Home) for 2–3 Spam levels; ideally gaps −15..+15. Also note day of upload.
  → decides Q1 / B6.
- **M-10 Bypass/crack success odds.** For each bypass/crack: your Bypasser/Cracker level (incl. bonuses), their
  FW/ENC level, success or fail. A Processes-page dump after a batch is enough if the target levels are known.
  Need ~20+ attempts per gap value to tell 58% from 66%.
- **M-11 Bypass/crack durations.** Start time + duration shown for a bypass/crack, with your device, their FW/ENC,
  your Bypasser/Cracker level, and how many hack processes were running. Single-process runs are cleanest.
- **M-09 Upload durations.** Virus type + level, victim device, your network, concurrent processes, duration shown.
  Several virus levels on the same victim device give the level exponent.
- **M-08 Download durations.** Software + level, your network, duration.
- **M-17 Keygen fees.** Apps page `LOCKED LVL N available <price>` rows: your installed level, locked level, price.
  Dumps of your own Apps page whenever something is waiting to be unlocked.
- **M-04 Siphon % per level.** SIPHON EARNINGS rows show `LV.x  y%`. One dump with mixed levels answers it.
- **M-07 Rootkit debuff per level.** The UPLOAD VIRUS card's stat line for your rootkit at a few levels.

### Medium priority (fills KB tables)
- **M-22 XP table.** Own Home shows `xp / next` — dump own Home once per level as you climb (R-run makes this easy).
- **M-18 Cold storage cap.** Own Wallet shows `/ N max` — dump once per level.
- **M-23 XP per purchase.** Before/after own-Home dumps around a Store purchase or Keygen unlock (price + XP gained).
  Software vs device vs network.
- **M-16 Low-level Store prices.** Store screen at low levels during a reboot run (every app shows its next-level price).
- **M-14 AV scan time.** Device + scan duration when no other hack process runs.
- **M-21 Hack rep.** Rep before/after a bypass and a crack, with both software levels.
- **M-15 Trace vs Proxy odds.** Every `[TRACED]` vs masked entry in your victim log, with your Trace level and
  (if known) their proxy level.
- **M-02 Spam decay under Scythe**, **M-03 slot fee shown on collection** (does the deducted fee match 0.5%/spam?).
- **M-12 Modifier stacking.** e.g. Rootkit −3 vs a Cipher +3 target: does the debuff apply before or after?
- **M-13 Process pools.** Does an upload slow a running bypass? (Run one upload alongside one bypass, compare durations.)

### Low priority / one-off checks
- **M-05 Siphon flows.** Does a victim's mission/login/transfer income show up in your siphoned totals? (Hard to test
  without a friendly victim; crew mate test.)
- **M-06 Siphon list after victim AV** (already [O]; just confirm the removed row's history).
- **M-19 Steal limits.** Can you take 100% of a hot wallet in one transfer? Minimum transfer?
- **M-20 Scan window.** Your level vs the min/max target levels in a few `Scan Random` results (dump the scan screen).
- **M-24 Mission spawn rates.** Just tally difficulties over a couple of weeks.
- **M-25 Crew server costs.** Part upgrade prices and surge prices (dump the server screen).

**Possible app idea (for TODO.md, not done):** many of these (XP table, cold cap, siphon %, keygen fees, spam
Cr/hr per level gap) are already on screens the app scrapes. A "research log" that quietly appends these numbers
to a local file would build the tables without manual work.

---

## 5. Questions for the owner

- **Q1.** The guide's Spam table disagrees with `GoodSpamUtils` (Lv20 equal-level: guide 83/hr, app 100/hr; app
  model also drives `recommendedSpamLevel`). Switch the app to the guide-fitted model now, or wait for M-01?
- **Q2.** Content format: per-topic asset files (recommended) vs Kotlin data classes?
- **Q3.** Calculators in the KB: all of them in the first version, or text first and calculators after?
- **Q4.** Should the KB cover the paid side (IAP, subscriptions, Black Market) in full, or keep it to a short
  reference topic?
