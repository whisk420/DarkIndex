# Knowledge Base review

Where the in-app Knowledge Base (📖) stands, what we're still checking, and what to measure next.
Last updated 2026-11-22.

## How it works

Every fact in the Knowledge Base has a badge saying where it came from:

| Badge | Means |
|---|---|
| **MEASURED** | Checked against real in-game numbers |
| **CONFIRMED** | You've confirmed it from playing |
| **CALCULATED** | Our own maths, built from measured or guide numbers |
| **GUIDE** | Says so on hackex.net/guide, not checked yet |
| **EMPIRICAL** | Old numbers from GEMINI.md, not checked yet |
| **UNKNOWN** | Nobody knows yet. Has a question number below |

We're going through the topics in the app's order. When you confirm something, its badge changes to CONFIRMED
(or MEASURED if you have numbers). When something turns out wrong, it gets fixed or removed. Open questions
show at the bottom of each topic page with the numbers used here (Q-03, Q-04 …). Answered or dropped questions
are deleted; the others keep their numbers.

---

## Review progress

**Done**
- Getting started & the core loop
- Spam virus & income
- Siphon virus
- Rootkit virus
- Virus rules (all types)

**Partly done**
- Botnet & Spam fees: the fee is guide (checked)
- Uploads & downloads: the upload model is measured
- Antivirus: the scan report lists every virus, removed or not
- Scanning: 10 targets by default (12 with our +2 Uplink); Encryptor only shows on an Apex reboot

**Not started**
- Bypass & crack
- Processes
- Consumables & hardening
- Proxy, Stealth & Trace
- Keygen & Store prices
- Software
- Wallet, theft & gas
- Logs
- Reputation & score
- Leveling & XP
- Missions, streaks & Watch & Earn
- Hardware
- Crews & the crew server
- Reboot Protocol
- Premium (Credits, OC, passes)
- How DarkIndex works

---

## Open questions

Numbered in the order the topics appear in the app. ⭐ marks the ones that feed the app's own maths (upload
times, odds, prices), so they're worth the most. A 📼 Raw Dump of the screen is always the best evidence.

### Spam
- **Q-03 Spam fee.** Is the fee taken on collection really 0.5% per active Spam beyond 2?
  *To measure:* compare a collection amount with the earnings shown before collecting.

### Siphon
- **Q-04 What gets skimmed.** Probably theft only: you've never seen a siphon loss when Spam income comes in, and
  the other rewards go straight to cold storage. The guide contradicts itself (one section adds Spam
  collections, another adds transfers, mission and login rewards).
  *To check:* next time you're siphoned, compare your transaction history around a Spam collection and a steal.

### Uploads & downloads
- **Q-05 ⭐ Download times.** How long a download takes by software level and network.
  *To measure:* software and level, your network, and the duration shown.
- **Q-06 Shared slowdown.** Running several processes slows them down. Do uploads and downloads slow down
  bypasses and cracks too, or do they have their own pool?
  *To measure:* run one upload alongside one bypass and compare the durations.

### Bypass & crack
- **Q-07 ⭐ Success odds.** The app's odds table isn't in the guide at all; it came from old notes.
  *To measure:* for each attempt, your Bypasser/Cracker level (with bonuses), their Firewall/Encryptor, and
  whether it worked. Needs about 20 attempts per level gap to tell 58% from 66%.
- **Q-08 ⭐ Bypass and crack durations** by level and gap.
  *To measure:* duration shown, your device, both levels, with nothing else running.
- **Q-09 Order of bonuses.** With a Rootkit on a Cipher target (−3 and +3), which applies first?
- **Q-10 Rep from a hack.** The guide says up to about 105, scaling with the victim's defence.
  *To measure:* your rep before and after a bypass and a crack, with both software levels.

### Antivirus
- **Q-11 Scan time.** The guide's text and table disagree at device Lv 10 (55 vs 53 min) and Lv 12 (48 vs 45 min).
  *To measure:* your device and the scan duration, with nothing else running.

### Proxy & Trace
- **Q-12 Trace vs Proxy odds.** How the unmask chance changes with the level gap. Equal levels are about 50%.
  *To measure:* every `[TRACED]` vs masked line in your victim log, with your Trace level.

### Keygen & Store prices
- **Q-13 ⭐ Exact Keygen fee.** 15% of the Store price for a 1-level jump, rising to 50% at 20+ levels. The steps
  in between and the rounding aren't known.
  *To measure:* dump your own Apps page when it shows `LOCKED LVL N available <price>`.
- **Q-14 Low-level Store prices.** The price formula is exact from about Lv 15 up but drifts below that.
  *To measure:* Store screen at low levels during a reboot run.
- **Q-15 XP per Crypto spent** for software, devices and networks. One data point so far: Keygen Lv 26 gave
  0.170 XP per Crypto.
  *To measure:* own Home before and after a purchase, plus its price.

### Wallet
- **Q-16 Cold storage cap at every level.** Known at Lv 1, 5, 10, 20 and 30.
  *To measure:* your own Wallet shows `/ N max`; dump it once per level.
- **Q-17 Steal limits.** Can you take a whole hot wallet in one go? Is there a minimum?

### Scanning
- **Q-18 Scan level window.** Exactly how far above and below your level a scan reaches.
  *To measure:* your level and the levels of everyone in a few scans.

### Leveling
- **Q-19 XP needed for every level.** Known at Lv 1, 5, 10, 20, 30, 35, 38 and 44.
  *To measure:* own Home shows `xp / next`; dump it once per level.

### Missions
- **Q-20 Mission odds.** The guide's spawn rates (80% / 50% / 20%) don't add up to 100%, so each slot probably
  rolls separately. *To measure:* tally mission difficulties for a couple of weeks.

### Crews
- **Q-21 Crew server prices.** Part upgrade and surge prices aren't published.
  *To measure:* dump the crew server screen.

---

## Things to watch for in the guide

- It contradicts itself on what Siphon skims (Q-04) and on Antivirus scan times (Q-11).
- Its Spam earnings table has the wrong shape. The app uses our own measured model instead (17 real uploads).
- Its Store prices for low levels don't match the formula that's exact at higher levels (Q-14).
- It says rep falls off with the **software** gap (your offence against their defence), not the player level gap.

**Idea (not built):** many of these numbers (XP table, cold cap, Keygen fees) are on screens the app already
scrapes. A quiet "research log" could collect them automatically. It belongs on the to-do list if wanted.
