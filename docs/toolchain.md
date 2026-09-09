# Reproducible Android build

Virkey uses Android Gradle Plugin **8.13.2**, Gradle **8.13**, Kotlin and the Compose compiler plugin **2.2.21**, and Java **17**. The Android SDK configuration is API **36**, build tools **35.0.0**, and minimum Android API **28**. The APK version is **0.1.0** (`versionCode=1`).

From PowerShell in the project directory:

```powershell
.\scripts\setup-toolchain.ps1
.\scripts\build.ps1
```

The setup script installs its JDK and SDK under the ignored `.tools` directory. It changes no system or user environment variables. The SDK installer displays the Google SDK license if it has not already been accepted; the person running setup must review and answer that prompt. The script does not auto-accept terms.

The build script runs `assembleDebug`, `testDebugUnitTest`, and `lintDebug` by default. Pass specific tasks if needed:

```powershell
.\scripts\build.ps1 assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Android Studio can also open this folder; configure its Gradle JDK as Java 17 and SDK as `.tools/android-sdk`, or use an existing compatible SDK. Outside the helper script, set `ANDROID_HOME` or your untracked `local.properties` to the SDK directory. The release variant is deliberately unsigned; production signing credentials must never be committed.

## Version and download evidence

- [Android Gradle Plugin 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes): Gradle 8.13, JDK 17, default build tools 35.0.0, support through API 36.1.
- [Kotlin release history](https://kotlinlang.org/docs/releases.html): Kotlin 2.2.21 is a stable bug-fix release.
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom): Compose libraries use BOM `2025.10.01`; Kotlin 2.x uses the matching Kotlin Compose compiler plugin.
- [Microsoft OpenJDK downloads](https://learn.microsoft.com/en-us/java/openjdk/download): JDK 17.0.20.1 Windows x64 ZIP, SHA-256 `3d9006956fc8af5601cd24ffc4f468bef48279c7ebd8171b9bdf90d0aabfbf1f`.
- [Android command-line tools](https://developer.android.com/studio#command-line-tools-only): Windows `15859902` ZIP, SHA-256 `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`.
- [Gradle official checksums](https://gradle.org/release-checksums/): Gradle 8.13 binary ZIP SHA-256 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`; wrapper JAR SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.

Build plugin and library artifacts were checked against Google Maven and Maven Central. Runtime dependencies are pinned; Compose UI, foundation, and Material 3 versions are selected by the pinned BOM.
