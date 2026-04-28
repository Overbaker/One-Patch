# One Patch

> **Slap a patch on your screen.** Block accidental touches in any rectangular area, with one-tap quick switching and recordable one-handed-mode gestures.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![min SDK](https://img.shields.io/badge/minSdk-26-blue)](https://developer.android.com/studio/releases/platforms)
[![target SDK](https://img.shields.io/badge/targetSdk-34-brightgreen)](https://developer.android.com/about/versions/14)
[![APK Size](https://img.shields.io/badge/APK-1.5MB-success)](#)

A lightweight Android utility that lets you "patch over" a rectangular zone of your screen — touches inside the patch are silently absorbed; everything outside works normally. Perfect for blocking accidental palm contact, screen-edge mis-touches, sticky ads, or any UI region you wish would just stop reacting.

> **One Patch** (n.) — one rectangle, one tap, one quick gesture. Stick it on, peel it off, drag it anywhere.

---

## ✨ Features

- **Single rectangular patch**, freely draggable & resizable on real fullscreen
- **Floating action button** (FAB) — single-tap toggle, double-tap force-bypass + replay one-handed gesture
- **Record your own one-handed-mode gesture** once on your device, replay any time the FAB asks for it
- **Quick Toggle alias** — bind to Xiaomi 敲击背部 / 悬浮球 / 桌面快捷方式 for one-tap pause/resume
- **Visible runtime overlay** (12% blue tint + red border, toggleable) so you always know where the patch is
- **Survives Xiaomi one-handed mode** when Accessibility Service is enabled (uses `TYPE_ACCESSIBILITY_OVERLAY` trusted window)
- **Auto-bypass** when the system displaces the overlay (e.g. one-handed mode trigger), restores when display returns
- **Foreground service** for HyperOS / MIUI battery survival
- **No internet permission** — fully offline, zero telemetry
- **Material You** dynamic colors (Android 12+)
- **TalkBack-friendly** — custom accessibility action for the double-tap behavior
- **Tiny footprint**: ~1.5 MB release APK

## 📲 Install

Download the latest APK from [Releases](https://github.com/TennyDDDD/One-Patch/releases/latest) and `adb install` or open on device.

```bash
adb install -r app-release.apk
```

## 🛠 First-time setup

| Step | Action |
|---|---|
| 1 | Grant **"Display over other apps"** permission |
| 2 *(recommended)* | Enable **One Patch Accessibility Service** in *Settings → Accessibility → Installed services* — makes the patch immune to one-handed mode transform |
| 3 *(Xiaomi only)* | In *App info → Other permissions*, allow **"Show on lock screen"** & **"Display pop-up windows while running in background"**, set **Battery saver → No restrictions** |
| 4 *(optional)* | Tap **录制单手模式手势** in main settings to record your device's actual one-handed-mode trigger gesture for reliable replay |

## 🎯 How it works

```
┌────────────────────────────────┐
│  Your favorite app             │  ← receives all touches outside patch
│                                │
│   ┌──────────────┐             │
│   │  One Patch   │ ← 12% blue  │  ← this rectangle silently swallows
│   │              │   tint +    │     every DOWN/MOVE/UP/CANCEL
│   │              │   red border│
│   └──────────────┘             │
│                                │
│                          ●     │  ← floating action button
└────────────────────────────────┘    single-tap = toggle pause
                                      double-tap = trigger one-handed mode
```

Under the hood: a `TYPE_APPLICATION_OVERLAY` (or `TYPE_ACCESSIBILITY_OVERLAY` if a11y enabled) View with `onTouchEvent` returning `true`. No system hook, no root, no Xposed.

## 🎮 FAB interactions

| Action | Behavior |
|---|---|
| Single tap | Toggle the patch's bypass state (pause/resume blocking) |
| Double tap | Force bypass on + replay your recorded one-handed-mode gesture |
| Long-press drag | Reposition the FAB anywhere on screen |
| TalkBack custom action | Equivalent to double-tap (since system intercepts double-tap for focus-click) |

## 🤚 One-handed-mode gesture recording

Different OEM ROMs use different gestures to enter one-handed mode (HyperOS bottom-edge swipe, OneUI corner swipe, ColorOS three-finger pinch). Hardcoding any single gesture won't work everywhere. **One Patch lets you record your device's actual gesture once, then replays it on demand**:

1. Main → **录制单手模式手势** (Record one-handed gesture)
2. 3-2-1 countdown → demonstrate your gesture (single finger; can lift-and-repress for multi-stroke gestures, ≤ 60 s total)
3. Tap **完成** to save; instantly replayable via FAB double-tap or Quick Toggle alias

Coordinates are stored as absolute pixels with the recording-time display size, then rescaled at replay time, so the gesture survives display resolution / scaling changes.

## ⚠️ Known limitations (Android platform boundaries — not bugs)

- **Cannot block**: status bar, system gesture areas, IME (keyboard), or any app calling `HIDE_OVERLAY_WINDOWS` (banks, payment apps, password fields)
- **Android 13+**: user can stop the foreground service from Task Manager
- **Without Accessibility Service**: patch follows OEM display-area transforms (works but moves with content). Auto-bypass kicks in to mitigate
- **OEM gesture recognition**: Android `dispatchGesture` is best-effort; some ROMs filter accessibility-injected events near the navbar. Re-record if recognition is flaky

## 🔒 Privacy

One Patch **does not** collect, store, transmit, or sell any personal data. No internet permission is even declared. See [PRIVACY.md](PRIVACY.md).

## 🏗 Build from source

```bash
git clone https://github.com/TennyDDDD/One-Patch.git
cd One-Patch

# Generate your own signing key (one-time, BACK IT UP)
./scripts/gen-keystore.sh

# Configure signing
cp keystore.properties.template keystore.properties
# edit keystore.properties with your passwords

# Build
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Requirements: **JDK 17**, **Android SDK 34**, **Build-Tools 34.0.0**.

## 📜 License

[MIT](LICENSE) © 2026 TennyDDDD

---

*Built with multi-model collaborative AI workflow (Codex + Gemini + Claude). Spec-driven development via [OpenSpec](https://github.com/Fission-AI/OpenSpec).*
