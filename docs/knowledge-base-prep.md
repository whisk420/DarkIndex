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
| **EMPIRICAL** | Old unverified numbers, not checked yet (none left) |
| **UNKNOWN** | Nobody knows yet. Has a question number below |

We're going through the topics in the app's order. When you confirm something, its badge changes to CONFIRMED
(or MEASURED if you have numbers). When something turns out wrong, it gets fixed or removed. Open questions
show at the bottom of each topic page with the same numbers as here. Answered questions are deleted.

---

## Review progress

**Done**
- Getting started & the core loop
- Spam virus & income
- Siphon virus
- Rootkit virus
- Virus rules (all types)
- Crews & the crew server (fully measured from the crew's whole server log)

**Partly done**
- Botnet & Spam fees: the fee is guide
- Uploads & downloads: upload times measured; uploads and downloads slow each other, not hacks
- Bypass & crack: success odds measured; bonuses just add up
- Processes: network and processor jobs slow down only their own kind
- Antivirus: one scan lists every virus on you, removed or not
- Keygen & Store prices: XP per Crypto measured
- Wallet, theft & gas: no minimum or maximum per steal
- Scanning: 10 targets by default (12 with our +2 Uplink); Encryptor only shows on an Apex reboot
- Leveling & XP: XP per Crypto measured

**Not started**
- Consumables & hardening
- Proxy, Stealth & Trace
- Software
- Logs
- Reputation & score
- Missions, streaks & Watch & Earn
- Hardware
- Reboot Protocol
- Premium (Credits, OC, passes)
- How DarkIndex works

---

## Open questions

Numbered in the order the topics appear in the app. ⭐ marks the ones that feed the app's own maths (upload
times, prices), so they're worth the most. A 📼 Raw Dump of the screen is always the best evidence.

### Spam
- **Q-01 Spam fee.** Is the fee taken on collection really 0.5% per active Spam beyond 2?
  *To measure:* compare a collection amount with the earnings shown before collecting.

### Uploads & downloads
- **Q-03 ⭐ Download times.** How long a download takes by software level and network.
  *To measure:* software and level, your network, and the duration shown.

### Bypass & crack
- **Q-04 ⭐ Bypass and crack durations** by level and gap.
  *To measure:* duration shown, your device, both levels, with nothing else running.
- **Q-05 Rep from a hack.** The guide says up to about 105, scaling with the victim's defence.
  *To measure:* your rep before and after a bypass and a crack, with both software levels.

### Antivirus
- **Q-06 Scan time.** The guide's text and table disagree at device Lv 10 (55 vs 53 min) and Lv 12 (48 vs 45 min).
  *To measure:* your device and the scan duration, with nothing else running.

### Proxy & Trace
- **Q-07 Trace vs Proxy odds.** How the unmask chance changes with the level gap. Equal levels are about 50%.
  *To measure:* every `[TRACED]` vs masked line in your victim log, with your Trace level.

### Keygen & Store prices
- **Q-09 Store prices outside Lv 15–25.** The price formula is exact from about Lv 15 to 25. It drifts below the
  guide's low-level prices, and above Lv 25 real prices run slightly higher (Keygen unlock fees on your Apps page imply
  +0.04% at Lv 30, +0.1% at Lv 33, +0.6% at Lv 42). Your Store on 2026-11-24 agrees: 11 real prices run +0.01% to
  +0.17% above the formula from Lv 28 to 35, and Rootkit Lv 7 is +1.9%.
  *To measure:* Store screen at low levels during the reboot run (one Store dump per few levels is enough).

### Wallet
- **Q-10 Cold storage cap at every level.** Known at Lv 1, 2, 5, 10, 20 and 30.
  *To measure:* your own Wallet shows `/ N max`; dump it once per level.
- **Q-11 Daily steal maximum.** A single steal has no limit, but there seems to be a cap per day. How much?
  Hard to pin down.

### Scanning
- **Q-12 Scan level window.** Exactly how far above and below your level a scan reaches.
  *To measure:* your level and the levels of everyone in a few scans.

### Leveling
- **Q-13 XP needed for every level.** Known at Lv 1, 2, 5, 10, 20, 30, 35, 38 and 44.
  *To measure:* own Home shows `xp / next`; dump it once per level.

---

## Things to watch for in the guide

- It says Siphon skims Spam collections and other income; in play only theft is skimmed (developer). It also contradicts itself on Antivirus scan times (Q-06).
- Its Spam earnings table has the wrong shape. The app uses our own measured model instead (17 real uploads).
- Its Store prices for low levels don't match the formula that's exact at Lv 15–25 (Q-09).
- It says rep falls off with the **software** gap (your offence against their defence), not the player level gap.

**Idea (not built):** many of these numbers (XP table, cold cap, Keygen fees) are on screens the app already
scrapes. A quiet "research log" could collect them automatically. It belongs on the to-do list if wanted.
