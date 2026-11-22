# DarkIndex: history

Fixed bugs and known-wrong statements in the old GEMINI.md reference, kept so the same mistakes aren't made again. Moved out of CLAUDE.md on 2026-11-22.

## 1. Bugs found in the 2026-11-19 review

Found in the 2026-11-19 review. All fixed except where noted; kept as history so the same mistakes aren't reintroduced.

| # | Status | Issue |
|---|---|---|
| B1 | Fixed | Upload estimates and Good Spam used the NEXT network instead of the current one |
| B2 | Fixed | Import dropped raw ingests (final save overwrote them); targets are now saved before ingests run |
| B3 | Fixed | Downloads auto-backup broke after a reinstall forced by CI's throwaway signing key; one permanent key now |
| B4 | Fixed | Wipe Database had no confirmation |
| B5 | Fixed | Log-only screens fell to UNKNOWN; classifier now matches log headers and lines |
| B6 | Mitigated | Spam payout re-fitted to 17 in-game uploads (2026-11-22, CLAUDE.md §3.7): the gap is victim − Spam level, not your level; over-levelled Spam earns less; Lv17 caps on victims Lv27+. Only Spam 13+ above the victim still estimated (not worth measuring) |
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

## 2. GEMINI.md drift (don't trust these GEMINI statements)
- Theft clusters are split on a 1 h gap, not 24 h.
- Good Spam is the upload-time formula, not either of GEMINI's two hardware formulas.
- Siphons: there's no 15-min TTL.
- The Recon/Advanced Targeting switch is on MainActivity, not Settings.
- There are 10 screen types, not 7.
- The cubic price formula is Keygen's, not "offensive" (docs/game.md §3).
- "Proxy/Trace 1.00x" conflicts with the guide (0.85×).
- GEMINI's prestige network names are wrong (docs/game.md §2).
- Test counts are now 80 + 3. `ExampleUnitTest` no longer exists.
- Files GEMINI omits: `GoodSpamUtils`, `NetworkSpeedUtils`, `PlayerProfileRepository`, and tests `GoodSpamTest`, `ScreenClassifierTest`, `StoreScreenParserTest`, `OwnHomeScreenParserTest`, `PlayerIntelligenceTest`, `PlayerProfileRepositoryTest`, `TargetAdapterTest`, `FakeSharedPreferencesTest`.
- GEMINI's "Raider I–III upload identically" claim is **correct** (developer-confirmed). The guide's per-device multipliers are process speed, not upload defence.

---
