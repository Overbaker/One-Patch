<p align="center">
  <img src="assets/hero-banner.svg" alt="One Patch — 1.5 MB offline Android touch-blocking overlay with recordable one-handed gesture replay" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/Overbaker/One-Patch/releases/latest"><img src="https://img.shields.io/github/v/release/Overbaker/One-Patch?color=38BDF8" alt="Latest Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(minSdk%2026)-22C55E" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/APK-~1.5%20MB-F43F5E" alt="APK ~1.5 MB" />
  <img src="https://img.shields.io/badge/Internet%20Permission-None-10B981" alt="No Internet Permission" />
</p>

<p align="center">
  <a href="https://github.com/Overbaker/One-Patch/releases/latest"><strong>Download APK</strong></a> ·
  <a href="#quick-setup"><strong>Setup</strong></a> ·
  <a href="#fab--gesture-replay"><strong>Controls</strong></a> ·
  <a href="PRIVACY.md"><strong>Privacy</strong></a> ·
  <a href="https://github.com/Overbaker/One-Patch/discussions"><strong>Discussions</strong></a>
</p>

---

**One Patch** is a lightweight (**~1.5 MB**), **100% offline** Android utility (Kotlin, no root, zero internet permission) that places a draggable, resizable touch-blocking rectangle over any screen zone — ideal for cracked-screen ghost touches, palm contact, or accidental edge taps — with a one-tap floating toggle and **recordable one-handed-mode gesture replay**.

## How It Works

<p align="center">
  <img src="assets/how-it-works.svg" alt="How One Patch blocks touches inside a draggable overlay rectangle with floating FAB controls" width="100%" />
</p>

## Quick Setup

Download the latest APK from [**Releases**](https://github.com/Overbaker/One-Patch/releases/latest) (or `adb install -r app-release.apk`):

| Step | Action |
|---|---|
| **1** | Grant **"Display over other apps"** permission |
| **2** *(recommended)* | Enable **One Patch Accessibility Service** (*Settings → Accessibility → Installed services*) for trusted `TYPE_ACCESSIBILITY_OVERLAY` |
| **3** *(Xiaomi / HyperOS)* | Allow **"Show on lock screen"** + **"Display pop-up windows while running in background"** and set **Battery saver → No restrictions** |
| **4** *(optional)* | Tap **录制单手模式手势** to record your device's exact OEM one-handed trigger gesture once |

## FAB & Gesture Replay

| Action | Behavior |
|---|---|
| **Single tap** | Toggle patch bypass (pause / resume touch blocking) |
| **Double tap** | Force bypass on + replay your recorded one-handed gesture |
| **Long-press drag** | Move the FAB anywhere on screen |
| **Quick Toggle alias** | Bind to Xiaomi 敲击背部 / 悬浮球 / launcher shortcut |
| **TalkBack action** | Accessible custom action equivalent to double-tap |

> **Why recordable gestures?** HyperOS (bottom-edge swipe), OneUI (corner swipe), and ColorOS (three-finger pinch) all trigger one-handed mode differently. Record once (3-2-1 countdown, up to 60 s) — coordinates rescale automatically across display density/resolution changes.

## Key Highlights

- **Zero Telemetry / Fully Offline**: No `INTERNET` permission declared ([PRIVACY.md](PRIVACY.md)).
- **Material You & HyperOS Survival**: Dynamic colors (Android 12+), foreground service, auto-bypass when the display shifts.
- **No Root / No Xposed**: Pure `TYPE_APPLICATION_OVERLAY` / `TYPE_ACCESSIBILITY_OVERLAY`.

<details>
<summary><strong>Known Android platform boundaries & HyperOS notes</strong></summary>

- **Cannot block**: status bar, system gesture bars, IME keyboard, or apps calling `HIDE_OVERLAY_WINDOWS` (banking/payment/password fields).
- **Android 13+**: users can stop the foreground service from Task Manager.
- **HyperOS one-handed mode ([#1](https://github.com/Overbaker/One-Patch/issues/1))**: Xiaomi HyperOS applies a SurfaceFlinger display-area transform that shifts even `TYPE_ACCESSIBILITY_OVERLAY` and Android 14 `attachAccessibilityOverlayToDisplay` surfaces. **Workaround (default)**: keep `屏蔽区物理坐标锁定` off so the patch auto-bypasses during one-handed mode and resumes on fullscreen.

</details>

<details>
<summary><strong>Build from source (JDK 17 · Android SDK 34)</strong></summary>

```bash
git clone https://github.com/Overbaker/One-Patch.git && cd One-Patch
./scripts/gen-keystore.sh
cp keystore.properties.template keystore.properties   # fill in passwords
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

</details>

<details>
<summary><strong>FAQ</strong></summary>

- **Does One Patch work for ghost touches on a cracked screen?** Yes — resize and drag the patch directly over the broken digitizer region to absorb spurious taps while keeping the rest of the screen usable.
- **Does it require root or internet access?** Neither. It uses standard Android overlay/accessibility APIs and declares zero internet permissions.

</details>

---

Licensed under **MIT** ([LICENSE](LICENSE)). See [CONTRIBUTING.md](CONTRIBUTING.md) for contributions and device reports.
