# TODO

Open ideas and their plans. Owned by the developer: Claude adds ideas here and only implements them when asked.
Bugs are fixed on the fly, not listed here. When an item is done, move it to the Done log as one line and record
any lasting facts in CLAUDE.md or docs/game.md.

Rebuilt on 2026-11-22 from the open items recorded in the docs and code (the earlier list was never saved).

## Open

### Scan screen: store every row
`BypassScreenParser` stores only the first unmasked row of `// SCAN RESULTS` (docs/game.md §4, fixture
`scan_2026-11-22.txt`). Plan: parse every `<ip> FW Lv.N [ENC Lv.N] >` row, skip masked rows, upsert each IP's
FW (and ENC when shown, Apex/Null Route only). Needs a real dump of a scan without ENC to confirm the default row.

### Your profile from own-log lines
`Purchased Keygen LvN` and `Upgraded network to <name>` are parsed but ignored. Plan: in `ScrapePipeline`, when the
log is your own, feed them to `PlayerProfileRepository` (Store stores NEXT levels, so store N + 1; network via
`HardwareCatalog`). Only move levels forward. Needs fixtures with both lines.

### Apps screen as a source for your installed levels
Only the Store feeds your software levels. The own Apps screen shows installed levels directly, plus
`LOCKEDLVL N available` for downloaded-but-not-activated copies (docs/game.md §2, "Economy & progression").
Plan: classify the own Apps screen, store installed + 1 so it matches the Store's NEXT convention. Needs a dump.

### Verify the Apex bonus isn't counted twice (B12)
`updateBypasserLevel` stores `Using Lv + (+N) + 1` from the Bypass screen, and `getEffectiveBypasserLevel` adds +3
again for an Apex device. Store scrapes store the level without the bonus, so the two sources disagree. Plan: confirm
in-game what `(+3)` includes, then store the base level only.

### Spam tracker
Planned alongside the Siphon tracker (SPAM EARNINGS is already parsed, not stored). Plan: track known Spam
deployments and alert when one disappears, reusing `SiphonTracker`'s full-scroll logic.

### Unknown device and network fallbacks
Unknown hardware names fall back to upload tier 3 and a 1.0× network (B7, marked " ?" on cards). Plan: add each
newly logged name to `HardwareCatalog` from real dumps; consider showing "unknown" instead of an estimate.

### Optional Downloads auto-backup
The `Downloads/DarkIndex/` copy is readable by any app with storage access (flagged by an external privacy review,
2026-11-24). Plan: a Settings switch (default ON, so nothing changes for existing users) that skips the mirror;
SECURITY.md already documents the file.

### Thin out MainActivity and OverlayService
Both are about 650 lines. Plan: move menu actions (export, import, maintenance, wipe) and the toolbars into small
helpers. Readability only; needs on-device testing.

## Done
- 2026-11-24: Privacy hardening: HackEx 2 only (`ScrapeGate`), no screen text in Logcat, cloud backup off, `SECURITY.md`, `PrivacyGuardTest`, release APK permission check + provenance attestation.
- 2026-11-22: Database moved to `targets.json`, immutable records, repository split (scrape thread 28 ms → 5 ms).
- 2026-11-22: CLAUDE.md split into CLAUDE.md, docs/game.md and docs/history.md; GEMINI.md retired.
