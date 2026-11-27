# TODO

Open ideas and their plans. Owned by the developer: Claude adds ideas here and only implements them when asked.
Bugs are fixed on the fly, not listed here. When an item is done, move it to the Done log as one line and record
any lasting facts in CLAUDE.md or docs/game.md.

Rebuilt on 2026-11-22 from the open items recorded in the docs and code (the earlier list was never saved).

## Open

### Your profile from own-log lines
`Purchased Keygen LvN`, `Upgraded network to <name>` and `Upgraded device to <name>` are parsed but ignored. Plan: in
`ScrapePipeline`, when the log is your own, feed them to `PlayerProfileRepository` (Store stores NEXT levels, so store
N + 1; hardware via `HardwareCatalog`). Only move levels forward. Fixtures exist: own logs of Raw Dumps #78 and #144
(2026-11-27) have all three lines. Botnet purchases and Keygen unlocks write no log line, so those still need the
Store or Apps page.

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
- 2026-11-27: The Scan screen stores every unmasked row (FW, and ENC when shown), Raw Dumps #24 and #153.
- 2026-11-24: B12 fixed: the Bypass screen no longer stores the Apex `(+3)` (Raw Dump #30), so it isn't counted twice.
- 2026-11-24: Your own Apps page feeds your installed software levels (`OwnAppsParser`, Raw Dump #29), also read from the Spam/Siphon earnings pages.
- 2026-11-24: Privacy hardening: HackEx 2 only (`ScrapeGate`), no screen text in Logcat, cloud backup off, `SECURITY.md`, `PrivacyGuardTest`, release APK permission check + provenance attestation.
- 2026-11-22: Database moved to `targets.json`, immutable records, repository split (scrape thread 28 ms → 5 ms).
- 2026-11-22: CLAUDE.md split into CLAUDE.md, docs/game.md and docs/history.md; GEMINI.md retired.
