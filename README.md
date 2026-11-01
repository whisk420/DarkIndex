# HackExLogger

Vibe-coded to the moon and back, just like the game.

**HackExLogger** is an open-source companion and organization tool for the mobile game **HackEx 2**. It allows players to track, search, validate, and export virtual in-game target intelligence (such as IP addresses, player names, software levels, and in-game crypto wallet addresses) gathered during gameplay.

---

## Features

- **On-Demand Game Screen Scraper**: Captures visible text from the HackEx game interface when manually triggered via a floating quick-action overlay button.
- **In-Game Overlay Toolbar**: Floating camera button and target validation controls overlay the game window for fast target clipboard copying and validation tracking.
- **Local On-Device Database**: Persists collected target intelligence locally on-device using Android `SharedPreferences` with thread-safe access.
- **Regex Game Text Parser**: Extracts in-game target profiles, software inventories, and activity logs using pattern matching tailored to HackEx UI text layouts.
- **Search & Filtering**: Quick filter target records by virtual IP address, username, software name, or software level.
- **JSON Import & Export**: Export your target database to JSON or import target bundles using Android's Storage Access Framework.

---

## How to Install (Sideloading)

Because HackExLogger is not distributed via the Google Play Store and utilizes Android's Accessibility Services for screen scraping, you must manually install it (sideload) and explicitly grant it permissions.

1. **Download the APK**: Navigate to the [Releases page](https://github.com/whisk420/HackExLogger/releases) on GitHub and download the latest `.apk` file to your Android device.
2. **Enable "Install Unknown Apps"**: Tap the downloaded APK to open it. If your phone prompts you that your browser/file manager is not allowed to install unknown apps, tap **Settings** and toggle **"Allow from this source"**.
3. **Bypass Play Protect Warning**: Google Play Protect may show an "Unsafe app blocked" warning because the developer is unknown. Tap **More details** (or the small arrow) and select **Install anyway**.
4. **Allow Restricted Settings (Crucial for Android 13+)**: Modern Android versions block accessibility services for sideloaded apps by default. To unlock it:
   - Go to your phone's **Settings** -> **Apps** -> **HackExLogger**.
   - Tap the **3 vertical dots (⋮)** in the top-right corner of the app info screen.
   - Tap **Allow restricted settings** and confirm with your PIN or fingerprint.
   - *(If you skip this step, the Accessibility service will be greyed out when you try to enable it).*
5. **Open HackExLogger**: Launch the app and follow the in-app prompts to grant the final "Display over other apps" and "Accessibility" permissions.
6. **Manually Enable Accessibility (If needed)**: If the app does not automatically take you to the correct Accessibility menu, or if the camera icon fails to appear after granting permissions, you can manually enable it by going to your phone's **Settings** -> **Accessibility** -> **Downloaded Apps** (or **Installed Services**) -> **HackExLogger** and toggling it **ON**.

---

## How to Build Yourself

If you are a developer, security-conscious, or simply want to tinker with the code, you can easily build the app from source:

1. **Prerequisites**: Ensure you have [Android Studio](https://developer.android.com/studio) and Git installed on your computer.
2. **Clone the Repository**:
   ```bash
   git clone https://github.com/whisk420/HackExLogger.git
   ```
3. **Open the Project**: Launch Android Studio, select **Open**, and select the `HackExLogger` directory you just cloned.
4. **Sync Gradle**: Let Android Studio automatically download the required Gradle dependencies and sync the project structure.
5. **Build and Install**:
   - **Run on Device**: Connect your Android device via USB (with USB Debugging enabled), select `standaloneDebug` or `googlePlayDebug` in Android Studio's **Build Variants** panel, and click the green **Play/Run** button.
   - **Generate APKs via Command Line**:
     - **Standalone (100% Offline)**: `./gradlew assembleStandaloneDebug`
     - **Google Play (Opt-In Cloud Backup)**: `./gradlew assembleGooglePlayDebug`
     - **Both Variants**: `./gradlew assembleDebug`

---

## System Permissions Explained

HackExLogger requires specific Android permissions to provide overlay controls and read in-game screen text:

| Permission | Purpose |
| :--- | :--- |
| `SYSTEM_ALERT_WINDOW` ("Display over other apps") | Renders the floating quick-action overlay camera button and validation toolbar over the HackEx game window. |
| `FOREGROUND_SERVICE` | Keeps the floating overlay service active during gameplay sessions. |
| `BIND_ACCESSIBILITY_SERVICE` | Used strictly as an **on-demand UI screen reader** to read visible text node elements on screen when the user manually taps the floating overlay button while playing HackEx. |

---

## Architecture Overview

```
com.whisk.hackexlogger
 ├── MainActivity                   # Dashboard for database browsing, searching, and JSON backup/restore.
 ├── ScraperAccessibilityService    # Accessibility Service that reads active window text nodes upon user trigger.
 ├── OverlayService                 # Foreground Service presenting floating UI widgets over the game interface.
 ├── DatabaseManager                # Thread-safe repository for persisting and merging target records on-device.
 ├── HackExParser                   # Regex pattern matcher for extracting in-game stats, software, and logs.
 ├── TargetAdapter                  # RecyclerView adapter for rendering target cards and headers.
 ├── TargetRecord & ConsoleBundle   # Data models and JSON export/import schema definitions.
```

---

## How It Works

1. **Enable Permissions**: Launch HackExLogger and ensure the overlay and accessibility permissions are enabled (either via the popup prompt or manually in Settings).
2. **Open HackEx**: Launch the HackEx game app. A small floating camera button will appear on your screen.
3. **Capture Data**: Navigate to a target's profile, software page, wallet screen, or log screen in HackEx, and tap the floating camera button.
4. **View & Search**: Return to HackExLogger to view parsed target stats, search software levels, or export your target database as a JSON bundle.

---

## Privacy & Build Flavors

All target data created or parsed by HackExLogger is stored **100% locally on your device** inside private app storage by default. No external telemetry or third-party servers exist.

HackExLogger provides two build variants:
- **`standalone`**: 100% offline build. Excludes all Google Play Services dependencies and omits internet permissions entirely.
- **`googlePlay`**: Includes optional Google Account sign-in under Settings for user-controlled cloud backup directly to your personal Google Account storage space with zero third-party server involvement.

---

## License

This project is open-source and available under the [MIT License](LICENSE).
