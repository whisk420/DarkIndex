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

### Uploads & downloads
- **Q-03 ⭐ Download times.** How long a download takes by software level and network. First reading (2026-11-27): Lv5 ≈ 343 s, Lv4 ≈ 194 s solo on Ghost Link III (from the Processes page's time left, divided by the measured +75% concurrency).
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

### Wallet
- **Q-10 Cold storage cap at every level.** Known at Lv 1, 2, 3, 5, 6, 7, 8, 10, 20 and 30. Targets' wallet pages show `<cold> / <cap> max` for the target's level, so any target's Home + Wallet fills a level.
  *To measure:* your own Wallet shows `/ N max`; dump it once per level.
- **Q-11 Daily steal maximum.** A single steal has no limit, but there seems to be a cap per day. How much?
  Hard to pin down.

### Scanning
- **Q-12 Scan level window.** Exactly how far above and below your level a scan reaches.
  *To measure:* your level and the levels of everyone in a few scans.

### Leveling
- **Q-13 XP needed for every level.** Known at Lv 1–8, 10, 20, 30, 35, 38 and 44. Targets' Home screens show `xp / next` too, so any target fills a level.
  *To measure:* own Home shows `xp / next`; dump it once per level.

---

## Things to watch for in the guide

- It says Siphon skims Spam collections and other income; in play only theft is skimmed (developer). It also contradicts itself on Antivirus scan times (Q-06).
- Its Spam earnings table has the wrong shape. The app uses our own measured model instead (17 real uploads).
- It says rep falls off with the **software** gap (your offence against their defence), not the player level gap.

**Idea (not built):** many of these numbers (XP table, cold cap, Keygen fees) are on screens the app already
scrapes. A quiet "research log" could collect them automatically. It belongs on the to-do list if wanted.
