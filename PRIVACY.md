# DeadZone — Privacy Policy

**Last updated**: 2026-04-28

## Summary in one sentence

**DeadZone collects, stores, transmits, and sells absolutely nothing. It cannot — it has no internet permission.**

## What data does the app access?

| Data | Used? | Where it goes |
|---|---|---|
| Personal identifiers (name, email, phone) | ❌ Never requested | — |
| Location | ❌ Never requested | — |
| Contacts / SMS / Call logs | ❌ Never requested | — |
| Photos / Files / Media | ❌ Never requested | — |
| Microphone / Camera | ❌ Never requested | — |
| Touch coordinates inside the dead zone | ✅ Discarded immediately, never recorded | Black-holed — `onTouchEvent` returns `true` and the event is gone |
| Dead zone rectangle (x, y, width, height) | ✅ Stored locally on your device only | `SharedPreferences` in app's private sandbox |
| Crash reports / analytics | ❌ Not implemented | — |

## Permissions explained

| Permission | Why we need it |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the transparent overlay rectangle on top of other apps to absorb touches |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the overlay alive in the background under aggressive OEM battery policies (Xiaomi HyperOS / MIUI) |
| `POST_NOTIFICATIONS` (Android 13+) | Show the persistent notification with the "Stop blocking" quick action |
| `BIND_ACCESSIBILITY_SERVICE` *(optional, user-enabled)* | Obtain `TYPE_ACCESSIBILITY_OVERLAY` so the dead zone stays at fixed physical coordinates instead of being transformed by Xiaomi one-handed mode. **The accessibility service in this app does not read any screen content, does not listen to any accessibility events, and does not perform any gestures.** |

## Network usage

The app does **not** declare `INTERNET` permission. No network call is possible.

## Third-party SDKs

**None.** Only Android Jetpack and Material Components — no analytics, no ads, no crash reporting, no telemetry.

## Children's privacy

The app collects no data. It is suitable for users of all ages.

## Open source

Source code is publicly available at [github.com/overbaker/deadzone](https://github.com/overbaker/deadzone). You can audit every line.

## Contact

Issues / questions → [github.com/overbaker/deadzone/issues](https://github.com/overbaker/deadzone/issues)
