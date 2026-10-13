# Scroll Starva — Release & APK Build Guide

This document contains complete instructions on how and where to build APKs and App Bundles for **Scroll Starva**.

---

## 1. Where to Find Built Artifacts

After running Gradle build tasks, your output files will be created at these exact locations:

| Build Type | Command | Output File Location | Use Case |
| :--- | :--- | :--- | :--- |
| **Debug APK** | `./gradlew assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` | Quick local device testing |
| **Release APK** | `./gradlew assembleRelease` | `app/build/outputs/apk/release/app-release.apk` | Sideloading release builds |
| **Release Bundle (`.aab`)** | `./gradlew bundleRelease` | `app/build/outputs/bundle/release/app-release.aab` | Google Play Store Submission |

---

## 2. Terminal Commands to Build

### A. Build Debug APK
```bash
./gradlew assembleDebug
```

### B. Build & Install Directly to Connected Phone
```bash
./gradlew installDebug
adb shell am start -n com.scrollstarva.app/.MainActivity
```

### C. Build Release App Bundle (`.aab` for Play Store)
Ensure environment variables for signing are set:
```bash
export SCROLL_STARVA_RELEASE_STORE_FILE="/path/to/release.keystore"
export SCROLL_STARVA_RELEASE_STORE_PASSWORD="your_password"
export SCROLL_STARVA_RELEASE_KEY_ALIAS="scrollstarva"
export SCROLL_STARVA_RELEASE_KEY_PASSWORD="your_password"

./gradlew bundleRelease
```

---

## 3. Google Play Store Submission Notes

- **App Bundle**: Upload `app/build/outputs/bundle/release/app-release.aab` under **Testing $\rightarrow$ Internal testing** in [Google Play Console](https://play.google.com/console).
- **Accessibility Declaration**: Declare `android.permission.BIND_ACCESSIBILITY_SERVICE` for Digital Wellbeing / De-addiction. Provide an unlisted YouTube video URL demonstrating the onboarding disclosure.
- **Privacy & Package Scoping**: Scroll Starva dynamically configures `serviceInfo.packageNames` to restrict accessibility event processing strictly to selected social apps.
