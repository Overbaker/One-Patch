# Changelog

All notable changes to DeadZone will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.0.0] - 2026-04-28

### Added
- Single rectangular dead zone with drag/resize directly on real fullscreen
- `TYPE_APPLICATION_OVERLAY` runtime overlay (12% blue tint + red border)
- Optional `TYPE_ACCESSIBILITY_OVERLAY` trusted-window mode (immune to Xiaomi one-handed transform)
- Foreground service with `specialUse` type (Android 14 compliant)
- Persistent notification with one-tap stop action
- Material You dynamic colors
- Xiaomi/HyperOS permission guide (background popup, autostart, battery)
- Fullscreen edit activity for direct on-screen rectangle drawing

### Security
- No `INTERNET` permission declared
- No third-party SDK / analytics / telemetry
- Accessibility service does not read screen content or listen to events
