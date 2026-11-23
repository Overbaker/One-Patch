# Contributing to One Patch

Contributions, OEM ROM compatibility reports, and pull requests are welcome!

## Local Build

Requires **JDK 17**, **Android SDK 34**, and **Build-Tools 34.0.0**:

```bash
./gradlew :app:assembleDebug
./gradlew test
```

## Good Areas to Help

- **OEM ROM testing**: HyperOS, OneUI, ColorOS, Pixel/AOSP gesture recording & overlay behavior.
- **HyperOS one-handed SurfaceFlinger transform**: See [#1](https://github.com/Overbaker/One-Patch/issues/1) if you discover a reliable non-root technique on newer HyperOS builds.
- **Translations & Accessibility**: TalkBack labels and localization improvements.

## Privacy Invariant

PRs must **never** add `INTERNET` permissions, analytics SDKs, or network calls. One Patch stays 100% offline.
