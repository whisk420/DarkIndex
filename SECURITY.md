# Security & Privacy

DarkIndex uses two powerful Android permissions (Accessibility and "Display over other apps"). This page says
exactly what they are used for, what the app stores and where, and how to check every claim yourself, from the
source or from the release APK.

## What DarkIndex reads

- **Only HackEx 2** (`net.cncapps.hackex2`). When you tap the 📸 button, the app first checks which app is on
  screen. If it isn't HackEx 2, it stops right there: not a single text node is read, and you see
  *"Not HackEx 2 — nothing was read"*. Code: [`ScrapeGate.kt`](app/src/main/java/com/whisk/hackexlogger/ScrapeGate.kt),
  called at the top of `scrapeAndParse()` in
  [`ScraperAccessibilityService.kt`](app/src/main/java/com/whisk/hackexlogger/ScraperAccessibilityService.kt).
- **Only when you tap 📸.** The service ignores all accessibility events (`onAccessibilityEvent` is empty). It
  never runs in the background on its own.
- **Only visible text.** It never types, clicks, performs gestures or reads key presses.
- **Only the active window.** The accessibility config
  ([`accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml)) is limited to the
  HackEx 2 package and does not request `flagRetrieveInteractiveWindows`, so the service cannot list or read other
  apps' windows.

## What DarkIndex stores, and where

| Where | What | Who can read it |
|---|---|---|
| App-private storage (`targets.json`, preferences, `*.jsonl` logs) | Game data: in-game IPs, player names, wallet addresses, software levels, your own game profile | Only DarkIndex |
| App-private `raw_dumps/` (only if you turn on **Raw Dump Mode**, a debug tool, off by default) | The HackEx 2 screen text and layout for each 📸 tap, for troubleshooting | Only DarkIndex, until you copy or export a dump |
| `Downloads/DarkIndex/darkindex_database.json` | A full copy of the database, rewritten ~30 s after a scrape, so your data survives an uninstall | You, and any app you have given storage access to |
| Files you export (⋮ → Export Data, Raw Dumps → EXPORT ALL) | Wherever you choose to save them | Whoever you give them to |

Everything stored is HackEx 2 game data. The app does not collect your real name, contacts, location, accounts,
device identifiers or anything from other apps.

## What DarkIndex never does

- **No network.** The app has no `INTERNET` permission, so Android itself blocks every network connection.
  There are no analytics, crash reporting, ads or cloud SDKs.
- **No Android cloud backup.** `android:allowBackup="false"`, and the backup rules exclude every data domain from
  Google cloud backup ([`data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml),
  [`backup_rules.xml`](app/src/main/res/xml/backup_rules.xml)). A direct phone-to-phone transfer when you set up a
  new phone (cable or local Wi-Fi, no cloud) can still copy the app's data, so you don't lose it.
- **No screen text in Logcat.** Screen text is never written to the system log. Release builds also strip all
  debug and info logging.

## How these promises are enforced

- [`PrivacyGuardTest`](app/src/test/java/com/whisk/hackexlogger/PrivacyGuardTest.kt) fails the build if: any app other
  than HackEx 2 passes the gate; text is read before the gate; the accessibility config widens; a network
  permission or networking/analytics code appears; cloud backup is turned back on; or screen text is logged.
  It runs on every push and pull request (`tests.yml`) and before every release (`android.yml`).
- The release workflow checks the **final APK** (including every library's manifest) with `aapt2` and refuses to
  publish if it requests a network permission or allows backup.

## Verify a release yourself

1. **The APK came from this source.** Each release is built by GitHub Actions and carries a signed
   [build-provenance attestation](https://docs.github.com/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds):
   ```bash
   gh attestation verify DarkIndex.apk --repo whisk420/DarkIndex
   ```
   The release notes also give the APK's SHA-256 and the commit it was built from.
2. **The permissions.** With the Android SDK build tools:
   ```bash
   aapt2 dump permissions DarkIndex.apk
   ```
   Expect only `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `VIBRATE` and the
   `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that AndroidX adds; no `INTERNET`.
3. **On the phone.** Settings → Apps → DarkIndex → Permissions shows no network access, and Settings →
   Accessibility → DarkIndex shows what the service does.
4. **Build it yourself** (see the README): no keys or accounts needed.

## Reporting a problem

Open an issue on GitHub. If it's sensitive, say so in the issue without details and the maintainer will contact you.
