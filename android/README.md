# Parks n Peaks Spot Monitor — Android v1.0.22

Landscape tablet companion to Parks n Peaks Spot Monitor for Windows, developed by VK3FS.

## Features

- Worldwide Parks n Peaks feed, with Mode, Country, Program and Max Age filters.
- QRZ first-name lookup (optional, QRZ XML access required); user enters their own credentials on device.
- Wanted Stations visual and sound alerts, new-spot gong, master mute, subtle recent-spot highlighting.
- Local/UTC clocks, Day/Night themes, Screen On/Auto, HF Conditions and park/map links.
- Offline Help & About via the cyan Help link in the bottom-left footer.
- Landscape-only tablet layout with horizontal scrolling for smaller displays.

## Build from source

1. Open **this `android/` folder** in Android Studio (not the repository root).
2. Allow Gradle to sync and install Android SDK 35 / Android Gradle Plugin dependencies as prompted.
3. For a test APK use **Build > Generate App Bundles or APKs > Generate APKs**.
4. To distribute, use **Build > Generate Signed App Bundle / APK** and safely store your own signing keystore outside the repository.

Package ID: `au.com.vk3fs.spotmonitor`. Version name: `1.0.22`; version code: `22`. Minimum Android API level: `26`.

The supplied source was used for the tablet-tested development builds, but this GitHub-ready package has **not** been compiled or signed in this environment. Keep the same signing key for all public updates. Android will not install a release signed with a new key over a debug build signed with a different key; uninstalling the debug version may clear saved preferences and QRZ settings.

See [`../docs/android-installation.md`](../docs/android-installation.md) for end-user installation instructions.
