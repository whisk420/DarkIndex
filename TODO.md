# TODO

Open ideas and their plans. Owned by the developer: Claude adds ideas here and only implements them when asked.
Bugs are fixed on the fly, not listed here. When an item is done, move it to the Done log as one line and record
any lasting facts in CLAUDE.md or docs/game.md.

Rebuilt on 2026-11-22 from the open items recorded in the docs and code (the earlier list was never saved).

## Open

### Thin out MainActivity and OverlayService
Both are about 650 lines. Plan: move menu actions (export, import, maintenance, wipe) and the toolbars into small
helpers. Readability only; needs on-device testing.

## Done
- 2026-11-28: Your own log keeps the profile current: Keygen purchases and device/network upgrades, forward only unless the reboot lines are on screen (Raw Dumps #121, #144).
- 2026-11-28: Dropped (developer): Spam tracker, unknown-hardware fallbacks (every name in 679 targets parses), the Downloads-backup switch.
- 2026-11-27: The Scan screen stores every unmasked row (FW, and ENC when shown), Raw Dumps #24 and #153.
- 2026-11-24: B12 fixed: the Bypass screen no longer stores the Apex `(+3)` (Raw Dump #30), so it isn't counted twice.
- 2026-11-24: Your own Apps page feeds your installed software levels (`OwnAppsParser`, Raw Dump #29), also read from the Spam/Siphon earnings pages.
- 2026-11-24: Privacy hardening: HackEx 2 only (`ScrapeGate`), no screen text in Logcat, cloud backup off, `SECURITY.md`, `PrivacyGuardTest`, release APK permission check + provenance attestation.
- 2026-11-22: Database moved to `targets.json`, immutable records, repository split (scrape thread 28 ms → 5 ms).
- 2026-11-22: CLAUDE.md split into CLAUDE.md, docs/game.md and docs/history.md; GEMINI.md retired.
