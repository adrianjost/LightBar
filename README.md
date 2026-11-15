# LightBar

A small Android TV / Google TV overlay for dimming a handful of Home Assistant lights with the D-Pad of your remote, without leaving what you are watching.

LightBar is a heavily reduced fork of [QuickBars for Home Assistant](https://github.com/Trooped/QuickBars) by Trooped. It keeps the idea (an accessibility-service overlay talking to Home Assistant over WebSocket) and drops everything else: other entity types, multiple bars, camera PiP, notifications, backups, in-app purchases. It uses its own application ID (`dev.adrianjost.lightbar`), so it installs next to QuickBars.

## Controls

Press your trigger button to open the overlay.

| Mode | Key | Action |
| --- | --- | --- |
| Browse | D-Pad | Choose a light (Up/Down in the sidebar, Left/Right in the bottom bar) |
| Browse | OK | Adjust the selected light |
| Browse | Left/Right (sidebar only) | Shortcut: start adjusting brightness right away |
| Adjust | Left/Right | Tap: 1 % steps. Hold: keeps going, slow at the low end and fast higher up, stops at 1 % |
| Adjust | Up/Down | Switch between brightness and warmth (if the light supports color temperature) |
| Adjust | OK or Back | Done, back to choosing a light |
| Both | Hold OK | Turn the light on or off (tapping Left at 1 % also turns it off) |
| Browse | Back or trigger button | Close |

The overlay closes by itself after 10 seconds without input (configurable under Appearance: 5 s, 10 s, 30 s or never).

## Setup

1. Install the APK (`adb install app/build/outputs/apk/release/app-release.apk`) and open **LightBar**.
2. **Set up with your phone**: scan the QR code, enter your Home Assistant address and a long-lived access token (Home Assistant → your profile → Security). The same page lets you give the chosen lights short names later.
3. **Accessibility service**: enable LightBar. If your TV hides that settings page, use
   `adb shell settings put secure enabled_accessibility_services dev.adrianjost.lightbar/dev.adrianjost.lightbar.LightBarService`
   followed by `adb shell settings put secure accessibility_enabled 1`.
4. **Trigger button** and **gesture**: pick the remote button that opens the overlay. With *Single press* the button is taken over completely; with *Double press* and *Long press* the app underneath still sees the key.
5. Under **Appearance** pick the layout (right sidebar, left sidebar or bottom bar with compact cards), its position along that edge and the gap from the screen edge. Then add your **lights**. Each shown light has its own buttons: **Move** (OK to pick it up, Up/Down to carry it, OK to drop), **Updates** (Live sends while you slide; After 0.5 s / 1 s / 3 s waits until the value has rested that long, or until you confirm with OK or close the overlay; two commands to a light are never closer together than that time, the last one is delayed if needed) and **Remove**.

## Build

Needs JDK 17 and the Android SDK (platform 36).

```
./gradlew assembleRelease
```

## Code map

Everything lives in `app/src/main/java/dev/adrianjost/lightbar`:

- `LightBarService.kt`: accessibility service: trigger button, D-Pad handling, overlay window, commands
- `OverlayUi.kt`: the overlay (Jetpack Compose)
- `HaClient.kt`: Home Assistant WebSocket client
- `SettingsActivity.kt`: the single settings screen
- `PhoneSetupServer.kt`: local web page + QR code for entering URL and token on a phone
- `Model.kt`: light model and stored settings

## License

GPL-3.0, like the project it is forked from. See [LICENSE](LICENSE).
