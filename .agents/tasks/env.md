# FocusGate environment findings (Windows, probed via PowerShell)

## Found
- Android SDK at C:/Users/Anirudh/AppData/Local/Android/Sdk
  - Dirs: build-tools, emulator, fonts, licenses, platform-tools, platforms, sources, .temp
  - platforms: android-35, android-37.0
  - build-tools: 34.0.0, 36.0.0 (aapt2.exe present in both)
  - platform-tools: adb.exe, fastboot.exe, sqlite3.exe
- Android Studio at C:/Program Files/Android/Android Studio
  - Bundled JBR: C:/Program Files/Android/Android Studio/jbr (bin/java.exe exists)
  - JBR version: OpenJDK 25.0.2 (2026-01-20)

## Missing
- No JDK on PATH (`java` not recognized); JAVA_HOME unset
- ANDROID_HOME and ANDROID_SDK_ROOT unset
- `gradle` not on PATH (no system Gradle; no gradlew exists yet, project is empty)
- `adb` not on PATH (exists only at SDK/platform-tools/adb.exe)
- cmdline-tools absent (no sdkmanager/avdmanager)
- Gradle wrapper jar/distribution not present (would need download; not done)

## Commands (PowerShell) for builds
```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME="C:\Users\Anirudh\AppData\Local\Android\Sdk"
# or local.properties: sdk.dir=C\:\\Users\\Anirudh\\AppData\\Local\\Android\\Sdk
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
.\gradlew.bat testDebugUnitTest
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt2.exe" dump badging app\build\outputs\apk\debug\app-debug.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices
```

## Which build steps can be run
- Only with env vars above AND a Gradle wrapper (gradlew.bat + wrapper jar) in the project:
  assembleDebug, lintDebug, testDebugUnitTest
- aapt2 manifest dump: runnable now on any built APK (aapt2 exists)
- adb install/logcat: runnable via full path (needs device)
- compileSdk 35 available locally (android-37.0 too); build-tools 34.0.0/36.0.0

## Which cannot (yet)
- Any Gradle build: no Gradle on PATH and no wrapper; the wrapper requires downloading a Gradle distribution (and AGP/dependencies from the network), which was not allowed in this step
- JBR is JDK 25: compatibility with the chosen Gradle/AGP version is unverified; must pick a Gradle version that supports running on JDK 25 (or confirm), not guessed here
- sdkmanager-based SDK installs (no cmdline-tools)

## Re-verified (2026-10-23)
- JDKs: Android Studio JBR C:/Program Files/Android/Android Studio/jbr = OpenJDK 25.0.2 (2026-01-20). C:/Users/Anirudh/.jdks/jbr-21.0.11 = JBR 21.0.11+1 (JDK 21, found). No JDK in Program Files/Java, Adoptium, Microsoft, Zulu, or ~/.gradle/jdks. JAVA_HOME unset; no java on PATH.
- SDK C:/Users/Anirudh/AppData/Local/Android/Sdk: platforms android-35, android-37.0; build-tools 34.0.0, 36.0.0; platform-tools (adb, fastboot, sqlite3).
- aapt2: SDK/build-tools/36.0.0/aapt2.exe and 34.0.0/aapt2.exe. apkanalyzer: not present (no cmdline-tools).
- Gradle not on PATH.
- Network: TCP 443 reachable to services.gradle.org and dl.google.com (build-time downloads OK).
- adb devices: empty list (no phone connected right now).
- Recommendation: run Gradle on JDK 21 (C:/Users/Anirudh/.jdks/jbr-21.0.11), not JBR 25; set JAVA_HOME to it.
