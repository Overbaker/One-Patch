## 📲 Install

```bash
adb install -r deadzone-<VERSION>.apk
```

Or download the APK below and open it on your Android device.

## 🛠 First-time setup

1. Grant **Display over other apps** permission
2. *(Recommended)* Enable **DeadZone Accessibility Service** in *Settings → Accessibility* — locks the dead zone to physical screen coordinates, immune to one-handed mode
3. *(Xiaomi only)* Allow **Background popup** + set **Battery saver → No restrictions**

## 🔒 Verify the APK signature

```bash
apksigner verify --print-certs deadzone-<VERSION>.apk
# Expect SHA-256: <fill-in-after-first-release>
```

## 🐛 Known limitations

- Cannot block status bar / IME / system gesture areas / payment apps (`HIDE_OVERLAY_WINDOWS`)
- Android 13+ user can stop the foreground service from Task Manager

## 📜 License

[MIT](https://github.com/overbaker/deadzone/blob/main/LICENSE)
