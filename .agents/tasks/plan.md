# FocusGate Implementation Plan

Source of truth: `d:/Kirooo/Mods/FocusGate/docs/DESIGN.md` (§1–§17) **plus its §18 binding addendum**. The 12 findings in `d:/Kirooo/Mods/FocusGate/.agents/tasks/design-review.md` (iteration 2) override the DESIGN text wherever they conflict; each one is mapped to a plan item in the table in §0.3. Environment: `d:/Kirooo/Mods/FocusGate/.agents/tasks/env.md`.

Project root: `D:/Kirooo/Mods/FocusGate` (written `<R>` below). Kotlin sources: `<R>/app/src/main/java/app/focusgate/` (written `<K>`). JVM tests: `<R>/app/src/test/java/app/focusgate/core/` (written `<T>`).

## 0. Ground rules

### 0.1 For the coder
- Single Gradle module `:app`, single process, no git, no worktree. Always use absolute paths under `D:/Kirooo/Mods/FocusGate`.
- Write files with the file-writing tool, not PowerShell `Set-Content`/`Out-File`. Windows PowerShell 5.1 writes a UTF-8 BOM, and a BOM at the start of `gradle.properties` or `local.properties` corrupts the first key.
- Every item ends in a buildable state. Run the item's Verify command before you start the next item.
- Run Gradle in PowerShell with this prefix, and pass a timeout of 1800000 ms on the first build:
  ```
  $env:JAVA_HOME="C:\Users\Anirudh\.jdks\jbr-21.0.11"; $env:ANDROID_HOME="C:\Users\Anirudh\AppData\Local\Android\Sdk"; Set-Location D:\Kirooo\Mods\FocusGate
  ```
  The prefix is written `<ENV>` below.
- Code rules that hold everywhere:
  - No `android.util.Log`, `println`, `printStackTrace`, `System.out` or `System.err` anywhere in `app/src/main`, in either build type.
  - No `+` or range versions.
  - No dependency outside AndroidX, Compose, Kotlin and kotlinx (JUnit is test-only).
  - No `WindowManager.addView` and no `TYPE_APPLICATION_OVERLAY`.
  - No AccessibilityService.
  - Every Intent used in a PendingIntent, `startService` or `startForegroundService` names its component class explicitly (finding 2).
- Record evidence as you go in `<R>/.agents/tasks/verification.md` (format in §6). Do not claim a check passed without its pasted output.
- **Anti-stall:**
  - If a Gradle or download command hangs for more than 10 minutes, or the same build error survives two fix attempts, stop. Write the blocker into verification.md, then call send_message with severity `info` to the parent describing it. If it is unrecoverable, use `error`.
  - Don't do open-ended web research; at most ~5 lookups, official docs only.
  - Never wait for user input except through an explicit send_message `warning` question.
- **Time budget** (≤ 3 h total):
  - items 1–2: 20 min
  - core logic + tests (items 3–8): 55 min
  - gate/storage/sources/service/blocking (items 9–12): 45 min
  - UI/onboarding/Insights (items 13–16): 45 min
  - final verification (item 17): 15 min
  
  If you are running late, don't skip any spec feature silently. Finish the item you're on, run item 17, and list everything unfinished in verification.md under "NOT DONE".

### 0.2 Decisions made in this plan (the DESIGN leaves these open)
- **Wrapper bootstrap uses the Gradle 8.10.2 that is already cached** at `C:\Users\Anirudh\.gradle\wrapper\dists\gradle-8.10.2-bin\a04bxjujx95o3nb99gddekhwo\gradle-8.10.2` (found during exploration; Gradle 8.10.2 runs on JDK 21). Its `wrapper` task generates a wrapper that **pins 8.11.1**, so there's no manual zip download. The DESIGN pin is unchanged. The manual zip path is the fallback.
- **AGP 8.7.3 and KGP 2.0.21 are already in `~/.gradle/caches/modules-2`**, as are most androidx/compose groups. The first build should mostly resolve offline-fast.
- **`EngineCore` gets the gate as a constructor lambda** `gateOpen: () -> Boolean`. Production passes `DataGate::isOpen`. That way `DataGateTest`/`EngineCoreTest` can close the gate without global state.
- **PackageManager metadata calls live in one new file, `system/InstalledApps.kt`.** These are `getApplicationInfo`, `getApplicationLabel` and `getLaunchIntentForPackage`, and only for the 3 packages. With one file, the finding-4 allow-list grep is checkable.
- **The `BOOT_COUNT` read lives in `arm/ArmController.kt`** (`readBootId()`), and nowhere else.
- **Zone JSON (org.json) is parsed only in `data/Store.kt`.** Validation rules (name, radius, lat/lon, count) are pure in `core/ZoneRules.kt`, so the JVM tests never touch `org.json` (it's a stub on the JVM).
- **Engine snapshots are published to `FocusService.snapshots: MutableStateFlow<EngineSnapshot>`** (a companion object). `EngineSnapshot` is defined in `core/Model.kt`.
- **`MainActivity` uses `launchMode="singleTask"`**, so notification and "paused" taps reuse the one instance.
- **`dependenciesInfo { includeInApk = false; includeInBundle = false }`.** This drops the Google-encrypted dependency metadata block from the APK, which keeps the APK free of anything that isn't the app.
- **Restructuring: none.** It's one Gradle project in one directory and one sequential coder session, and the existing `build-loop` (coder → parallel privacy and logic audits → aggregator writing `review.json`) fits it as is.

### 0.3 Design-review iteration-2 findings → plan items (all binding)

| Finding | What to do | Item |
|---|---|---|
| 1 IntentFilter | `IntentFilter().apply { addAction(SCREEN_ON); addAction(SCREEN_OFF) }` + `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` | 11 |
| 2 explicit intents | delete intent `Intent(ctx, FocusService::class.java).setAction(ACTION_NOTIF_DISMISSED)`, `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`; grep audit | 11, 17 |
| 3 re-promote degraded FGS | `MainActivity.onResume` rule + `ServiceStart` ACTION_START on existing engine re-calls startForeground; test | 7, 13 |
| 4 permitted-while-disarmed allow-list | table in §5.4 below; grep audit | 9, 17 |
| 5 disarm cleanup | `Engine.stop()` writes `used_ms_*`/`day_key` and removes `cursor_ts`/`armed_at_ts` in one Editor; UI rollover write only if `FocusService.engine == null` | 9, 11, 13 |
| 6 deferred replay | `pendingReplay`, first interactive tick, skip if `begin >= cursorTs`, `openSince = cursorTs` after; tests | 4, 8 |
| 7 screen-on re-sync | `cursorTs = max(cursorTs, screenOnTs - 2000)` without accrual; test | 4, 8 |
| 8 stray instances | TEST_BLOCK/NOTIF_DISMISSED with no engine → `stopSelf(startId)`, NOT_STICKY, no unclean-restart record; tests | 7 |
| 9 Blocked finishes on disarm | collect `ArmController.state`, `finish()` on Disarmed; onResume gate-closed check (non-test) | 12 |
| 10 NO_ZONES copy | per-app state mapping `TIMES_UP_NO_ZONES` → "Time's up · no zones set, staying open" | 3, 13 |
| 11 exact backup XML | files in item 2 | 2 |
| 12a wrapper in temp dir | item 1 | 1 |
| 12b dependency criterion | item 17 V9 | 17 |
| 12c overlay copy | "Find FocusGate in the list and turn it on" | 15 |
| 12d Test-blocking procedure, vivo name | REPORT text input recorded in verification.md; `com.vivo.abe/.unifiedpower.HighPowerDetailActivity` (no "(?)") | 9, 17 |

## 1. Toolchain bootstrap (exact)

Pins (DESIGN §2, unchanged):

| Item | Version |
|---|---|
| JDK running Gradle | JBR 21.0.11, `C:/Users/Anirudh/.jdks/jbr-21.0.11` (exists, re-checked) |
| Gradle wrapper | 8.11.1 `-bin` |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` 2.0.21 |
| Compose BOM | 2024.12.01 |
| core-ktx | 1.15.0 |
| activity-compose | 1.9.3 |
| lifecycle | 2.8.7 |
| coroutines-android | 1.9.0 |
| junit | 4.13.2 |
| SDK levels | compileSdk 35, targetSdk 34, minSdk 29, Java/Kotlin target 17 |

The only allowed fallback is wrapper 8.9, and only if KGP 2.0.21 × Gradle 8.11.1 fails.

Wrapper steps (PowerShell, in this order, **before** the build scripts exist; finding 12a):
1. Set `$env:JAVA_HOME="C:\Users\Anirudh\.jdks\jbr-21.0.11"`.
2. Create a temp dir: `$tmp = Join-Path $env:TEMP "fg-wrapper-gen"`, then `New-Item -ItemType Directory -Force $tmp`.
3. Get the official checksum:
   ```
   Invoke-WebRequest -UseBasicParsing -Uri https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256 -OutFile "$tmp\g.sha256"
   $sha = (Get-Content "$tmp\g.sha256" -Raw).Trim()
   ```
   `$sha` must be 64 hex characters. If it isn't, stop and report.
4. Write a one-line `"$tmp\settings.gradle.kts"` containing `rootProject.name = "wrapper-gen"` (with the file tool), so Gradle doesn't search parent directories.
5. Generate the wrapper:
   ```
   & "C:\Users\Anirudh\.gradle\wrapper\dists\gradle-8.10.2-bin\a04bxjujx95o3nb99gddekhwo\gradle-8.10.2\bin\gradle.bat" -p $tmp wrapper --gradle-version 8.11.1 --distribution-type bin --gradle-distribution-sha256-sum $sha --no-daemon
   ```
6. Copy `gradlew`, `gradlew.bat`, `gradle\wrapper\gradle-wrapper.jar` and `gradle\wrapper\gradle-wrapper.properties` from `$tmp` into `<R>` (same relative paths). Open `<R>/gradle/wrapper/gradle-wrapper.properties` and confirm it has `distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip` and `distributionSha256Sum=<sha>`.
7. **Fallback**, only if step 5 fails:
   1. Download `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` to `$tmp`.
   2. Check that `(Get-FileHash -Algorithm SHA256 $zip).Hash` equals `$sha` (case-insensitive).
   3. `Expand-Archive` it, then run its `bin\gradle.bat` with the same arguments.
8. `Remove-Item -Recurse -Force $tmp`. This deletes only that temp dir.
9. Run `<ENV>; .\gradlew.bat --version`. This downloads 8.11.1 and verifies the sha. Expect `Gradle 8.11.1` and `Launcher JVM: 21.0.11`.

Fixed config files (contents are config, so they're given exactly):
- `<R>/gradle.properties`:
  ```
  org.gradle.java.home=C:/Users/Anirudh/.jdks/jbr-21.0.11
  org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
  android.useAndroidX=true
  android.nonTransitiveRClass=true
  kotlin.code.style=official
  ```
- `<R>/local.properties`: `sdk.dir=C:/Users/Anirudh/AppData/Local/Android/Sdk`
- `<R>/settings.gradle.kts`:
  - `pluginManagement { repositories { google(); mavenCentral() } }`
  - `dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }`
  - `rootProject.name = "FocusGate"`
  - `include(":app")`
  
  If plugin resolution fails *only* because a plugin marker is missing, adding `gradlePluginPortal()` to `pluginManagement` is allowed (build-time only). Record it in verification.md.
- `<R>/gradle/libs.versions.toml`:
  - `[versions]` with the pins above.
  - `[libraries]`:
    - `androidx-core-ktx`
    - `androidx-activity-compose`
    - `androidx-lifecycle-runtime-ktx`
    - `androidx-lifecycle-runtime-compose`
    - `androidx-lifecycle-viewmodel-compose`
    - `androidx-compose-bom`
    - `androidx-compose-ui` (`androidx.compose.ui:ui`)
    - `androidx-compose-ui-graphics`
    - `androidx-compose-foundation` (`androidx.compose.foundation:foundation`)
    - `androidx-compose-material3` (`androidx.compose.material3:material3`)
    - `androidx-compose-material-icons-extended` (`androidx.compose.material:material-icons-extended`)
    - `kotlinx-coroutines-android`
    - `junit`
  - `[plugins]`:
    - `android-application` (`com.android.application`, agp)
    - `kotlin-android` (`org.jetbrains.kotlin.android`, kotlin)
    - `kotlin-compose` (`org.jetbrains.kotlin.plugin.compose`, kotlin)
- `<R>/build.gradle.kts`: the three plugins, each `apply false`.
- `<R>/app/build.gradle.kts`:
  - plugins: android-application, kotlin-android, kotlin-compose
  - `android {`
    - `namespace = "app.focusgate"`, `compileSdk = 35`
    - `defaultConfig { applicationId = "app.focusgate"; minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "1.0" }`
    - `buildTypes { release { isMinifyEnabled = false } }`
    - `compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }`
    - `buildFeatures { compose = true }`
    - `packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }`
    - `lint { abortOnError = true; checkDependencies = false; warningsAsErrors = false; lintConfig = file("lint.xml") }`
    - `dependenciesInfo { includeInApk = false; includeInBundle = false }`
  - `}`
  - `kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }`
  - Dependencies:
    - `implementation(platform(libs.androidx.compose.bom))` plus the libraries listed above
    - `testImplementation(libs.junit)`
    - No `ui-tooling` and no `ui-test-manifest`.
- `<R>/app/lint.xml`: exactly the DESIGN §2 block (ignore `ExpiredTargetSdkVersion`, `ExpiringTargetSdkVersion`, `OldTargetApi`, with the comment).

## 2. Exact file list

Paths are absolute under `D:/Kirooo/Mods/FocusGate`, grouped by responsibility. The "Android?" column says whether a file may import `android.*`.

### Build/config

| File | Responsibility |
|---|---|
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` | wrapper (item 1) |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`, `gradle/libs.versions.toml` | build config |
| `app/build.gradle.kts`, `app/lint.xml` | module config, lint suppressions |

### Resources (`app/src/main/`)

| File | Responsibility |
|---|---|
| `AndroidManifest.xml` | §3 below |
| `res/values/strings.xml` | `app_name` = FocusGate (UI copy lives in Kotlin) |
| `res/values/colors.xml` | launcher background, blocked window background |
| `res/values/themes.xml` | `Theme.FocusGate` (parent `android:Theme.Material.Light.NoActionBar`, transparent status/nav bars); `Theme.FocusGate.Blocked` (opaque `windowBackground`, `windowIsTranslucent=false`) |
| `res/xml/backup_rules.xml` | finding 11, exact: `<full-backup-content>` with 5 excludes (root, file, database, sharedpref, external; `path="."`) |
| `res/xml/data_extraction_rules.xml` | `<data-extraction-rules>` with `<cloud-backup>` and `<device-transfer>`, each containing excludes for root, file, database, sharedpref, external, device_root, device_file, device_database, device_sharedpref (`path="."`) |
| `res/drawable/ic_launcher_foreground.xml` | vector shield |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | adaptive icon (background color + foreground) |
| `res/drawable/ic_stat_shield.xml` | white vector notification icon |

### Kotlin `<K>`, pure (no `android.*` imports, JVM-tested)

| File | Responsibility |
|---|---|
| `core/Model.kt` | `MonitoredEvent`, `EventType {RESUMED, PAUSED, STOPPED}`, `UsageRead {Ok(events), Failed, Refused}`, `ZoneState`, `Decision`, `Reason`, `Verdict`, `Zone`, `ArmState`/`DisarmReason`, `BootId`, `AppState {HAS_TIME, TIMES_UP_OUTSIDE, TIMES_UP_UNKNOWN, TIMES_UP_NO_ZONES, BLOCKED}`, `TestBlockState {Idle, Pending, Waiting, Fired, Passed(waitedS), Failed, Inconclusive}`, `EngineSnapshot` (per-app used/remaining/state, zone state, consecutive count, last accuracy (no coordinates), flags `locationDegraded`/`blockLaunchHealthy`/`locationErrors`, feed list, test state), `TickOutcome {Continue(snapshot), RequestDisarm(reason)}`, `PersistedUsage(dayKey, usedMs[3], cursorTs)` |
| `core/MonitoredApps.kt` | list of `MonitoredApp(appId: Int, pkg, label)` for Instagram, Chrome and YouTube; `indexOf(pkg)`; `isMonitored(pkg)` |
| `core/Decision.kt` | `decide`, `safeDecide` (DESIGN §5 verbatim), plus `appState(remainingMs, zone, hasZones, isForegroundBlocked)` mapping including `TIMES_UP_NO_ZONES` (finding 10) |
| `core/UsageAccumulator.kt` | §4 foreground model, tick algorithm, rollover via `java.time` with injected `ZoneId`, `markOwnUiShown`, state-only replay mode, screen-off close, screen-on re-sync (finding 7), `queryBegin()`, dirty/persist bookkeeping |
| `core/Geo.kt` | haversine meters |
| `core/ZoneRules.kt` | pure validation: name 1–24 trimmed, radius 3–200 (default 5), finite lat/lon in range, ≤ 10 zones; `maxAccuracyM`, `exitMargin` |
| `core/ZoneEvaluator.kt` | §6 per-reading classification, N=3 hysteresis, 20 s staleness, 120 s carry-over |
| `core/ZoneSettler.kt` | §6 settle rule (≥ 10 s, last 5 ≤ 10 m, spread ≤ max(3, meanAcc), 1/acc² weighted mean, 90 s timeout, "save anyway" ≤ 50 m) |
| `core/BootCheck.kt` | `isSameBoot(stored: BootId?, now: BootId)` with the 3 rules |
| `core/CrashLoopGuard.kt` | restart list (≤ 5), trip at ≥ 3 in 10 min, encode/decode as a string |
| `core/ArmRestore.kt` | pure `restore(stored: ArmState, storedBoot: BootId?, nowBoot): RestoreResult {OpenGate(armedAtTs), PersistReboot, StayDisarmed}` |
| `core/DecisionFeed.kt` | `ArrayDeque` ring buffer, capacity 50, entries `(minuteOfDay, appId?, message)`, `clear()` |
| `core/ServiceTypes.kt` | `FgsType {SPECIAL_USE, LOCATION}`; `compute(sdkInt, preciseGranted, hasZones): Set<FgsType>`; `degraded(sdkInt)` |
| `core/ServiceStart.kt` | `ServicePort` interface; `handle(action: String?, startId: Int, port): StartReturn {STICKY, NOT_STICKY}`; the full §7 dispatch table plus findings 3 and 8 |
| `core/EngineCore.kt` | interfaces `UsageReader`, `LocationReader`, `Blocker`, `PermissionProbe`, `PresenceProbe`, `ConfigSource`, `Clock`; class `EngineCore(gateOpen, …)` with `tick()`, `onScreenOff()`, `onScreenOn(ts)`, `onLocationReading(distances, accuracy)`, `onLocationUnavailable()`, `markOwnUiShown(ts)`, `onOwnUiStopped()`, `requestTestBlock()`, `flagNotificationRepost()`, `persistIfDue(): PersistedUsage?`, `stop(): PersistedUsage` |
| `gate/DataGate.kt` | DESIGN §10 `object DataGate` verbatim (`isOpen`, `armedAtTs`, internal `open`/`close`) |

### Kotlin `<K>`, Android

| File | Responsibility |
|---|---|
| `FocusGateApp.kt` | `Application.onCreate` → `Store.init(this)`; `ArmController.restoreOnProcessStart(this)`; creates notification channels (no data access) |
| `data/Store.kt` | the single `SharedPreferences` `fg_store` (MODE_PRIVATE); typed getters/setters for exactly the §12 keys; zone JSON encode/decode with `ZoneRules` re-validation and a `zonesCorrupt` flag; `commit()` for arm/disarm, `apply()` for counters; `writeUsage(PersistedUsage)` (one Editor); `writeUsageAndClearCursor(PersistedUsage)` (finding 5, one Editor) |
| `data/UsageEventSource.kt` | **the only `UsageStatsManager` call site.** `read(begin, end): UsageRead`. First statement: `if (!DataGate.isOpen() \|\| begin < DataGate.armedAtTs()) return UsageRead.Refused`. The manager is fetched inside, never cached. It filters `MonitoredApps` packages and event types 1/2/23 immediately; `null`/exception → `Failed` |
| `location/GpsSource.kt` | **the only `requestLocationUpdates`/`isProviderEnabled` call site.** `start(looper, zones, sink)`: first statement is the gate check, then the FINE permission check (lint `MissingPermission`), `isProviderEnabled(GPS)`, then `requestLocationUpdates(GPS_PROVIDER, 1000L, 0f, listener, looper)`. The listener's first statement is the gate check (else `stop()`); it computes haversine distances per zone, passes `(distances, accuracy)` and drops the `Location`. `stop()` is ungated |
| `location/LocationSession.kt` | UI-scoped session (main looper) on top of `GpsSource`. Mode `ZoneSetup` (feeds `ZoneSettler`; exposes ±m, steady k/5, settled centre kept only in memory) or `GpsTest` (own `ZoneEvaluator` for the nearest zone; exposes ±m, inside/outside/unclear, consecutive k/3, seconds since the last fix). Stops on disarm (observes `ArmController.state`) |
| `service/FocusService.kt` | thin adapter implementing `ServicePort`; companion `@Volatile engine`, `isRunning`, `locationDegraded`, `currentTypes`, `snapshots: MutableStateFlow<EngineSnapshot>`; `service_alive` marker and `instanceRecorded`; action constants; `onDestroy` posts `Engine.stop()` and joins ≤ 500 ms |
| `service/Engine.kt` | HandlerThread `fg-engine`; wraps `EngineCore`; 1000 ms poll only while interactive; screen receiver (finding 1); `PowerManager.isInteractive`; adapters (UsageEventSource → UsageReader, GpsSource → LocationReader, BlockLauncher → Blocker, Permissions → PermissionProbe, UiPresence → PresenceProbe, Store → ConfigSource); `RequestDisarm` → `stopping = true` + `mainHandler.post { ArmController.disarm(reason) }`; persists via Store; deferred replay flag (finding 6); 60 s AppOps check |
| `service/Notifications.kt` | channels `status` (LOW) and `alerts` (DEFAULT); builds the FGS notification (private, public version "FocusGate is on", content → MainActivity, action "Disable now" → explicit `ACTION_PANIC`, explicit delete intent per finding 2, `setOngoing`, `FOREGROUND_SERVICE_IMMEDIATE`); the minimal "FocusGate is off" notification; crash-loop / paused / block-launch-failed alerts (≤ 1/h) |
| `service/BlockLauncher.kt` | debounce 1500 ms, `startActivity(BlockedActivity, EXTRA_APP_ID)` with NEW_TASK\|CLEAR_TOP\|NO_ANIMATION, Home fallback, test-mode launch (`EXTRA_TEST=true`), catches ActivityNotFound/Security/Runtime |
| `arm/ArmController.kt` | `object`; `state: StateFlow<ArmState>`; `restoreOnProcessStart(ctx)`, `arm(ctx): ArmResult {Ok, Missing(list)}`, `disarm(ctx, reason)`, all main-thread; `readBootId(ctx)` (the only BOOT_COUNT read); ordering per §9/§10 |
| `system/Permissions.kt` | status checks: AppOps `unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS)`, POST_NOTIFICATIONS, FINE vs COARSE-only, `Settings.canDrawOverlays`, `LocationManager.isLocationEnabled`, battery-optimisation ignored (`PowerManager.isIgnoringBatteryOptimizations`); settings intents (usage access, app notification settings, overlay, location source settings, battery list) |
| `system/OemSettings.kt` | Funtouch deep-link chains exactly as DESIGN §15 (vivo name without "(?)"), each try/catch, fallback app details, then `Settings.ACTION_SETTINGS` |
| `system/InstalledApps.kt` | the only PackageManager call site: `isInstalled(pkg)`, `label(pkg)`, `launchIntent(pkg)`, for `MonitoredApps` packages only |
| `ui/UiPresence.kt` | `@Volatile mainResumed`, `lastOwnActivityEventElapsed` |
| `ui/MainActivity.kt` | `enableEdgeToEdge`, `setContent { FocusGateTheme { AppShell() } }`; `onResume`: UiPresence, `markOwnUiShown`, AppOps check (→ disarm PERMISSION_LOST), the finding-3 ACTION_START rule; `onPause`/`onStop`: UiPresence and `engine.onOwnUiStopped()` |
| `ui/BlockedActivity.kt` | blocked and test modes, "Go home"/Back, "Back to <app>" when OUTSIDE, `isResumed` static, `markOwnUiShown`, finish on Disarmed (finding 9) |
| `ui/AppViewModel.kt` | exposes Store-backed settings (allowances, zones, mode, theme, feed toggle, banners), arm state, engine snapshot, permission health; display-only rollover, with the write only when disarmed **and** `FocusService.engine == null` (finding 5); actions forward to ArmController/Store and signal the engine |
| `ui/AppShell.kt` | `Screen` sealed class + `rememberSaveable` back stack + `BackHandler`; Scaffold with top bar (title, Essentials/Insights segmented toggle, Settings icon) and the persistent bottom Enable / "Disable now" pill |
| `ui/theme/Theme.kt` | violet/lime palette, light/dark schemes, shapes (24 dp), typography; `FocusGateTheme(themePref)` |
| `ui/components/Components.kt` | `ProgressRing`, `AppCard`, `StatusCard`, `PanicPill`, `PermissionRow`, `SectionCard` (icons always carry a `contentDescription`, touch targets ≥ 48 dp) |
| `ui/screens/HomeScreen.kt` | status card + per-app rings and plain status lines; Insights sections when in Insights mode |
| `ui/screens/AllowancesScreen.kt` | per-app stepper + slider 0–600, default 30 |
| `ui/screens/ZonesScreen.kt` | list, delete with confirmation, add; "Turn FocusGate on to use location" when disarmed |
| `ui/screens/ZoneEditorScreen.kt` | name, radius slider 3–200 + numeric field (default 5), "Use my current location" (LocationSession ZoneSetup), honesty copy, Save/Cancel, 90 s not-settling path |
| `ui/screens/InsightsScreen.kt` | live status, Decision Feed (Show/Hide, hidden by default), diagnostics with Fix buttons, "Test blocking" control and result, entry to the GPS test |
| `ui/screens/GpsTestScreen.kt` | LocationSession GpsTest, started `ON_START` and stopped `ON_STOP`/on leave; no coordinates or distances |
| `ui/screens/PrivacyScreen.kt` | §12 stored-data table, §14 permissions with reasons, "No internet permission…", GPS honesty copy |
| `ui/screens/SettingsScreen.kt` | theme System/Light/Dark, mode, links to Privacy and onboarding/permissions |
| `ui/screens/OnboardingScreen.kt` | step list per DESIGN §13, one button per step, `ON_RESUME` re-check, manual confirmations for the OEM steps |

### JVM tests `<T>` (JUnit 4)

`DecisionTest.kt`, `UsageAccumulatorTest.kt`, `EngineCoreTest.kt`, `DataGateTest.kt`, `ServiceStartTest.kt`, `ServiceTypesTest.kt`, `BootCheckTest.kt`, `ArmRestoreTest.kt` (the DESIGN's `ArmControllerLogicTest` cases), `ZoneEvaluatorTest.kt`, `ZoneSettlerTest.kt`, `CrashLoopGuardTest.kt`, `MonitoredAppsTest.kt`, `ZoneRulesTest.kt`, `Fakes.kt` (fake `UsageReader`/`LocationReader`/`Blocker`/`PermissionProbe`/`PresenceProbe`/`ConfigSource`/`Clock`/`ServicePort` recording every call and every `begin`).

### Evidence

`<R>/.agents/tasks/verification.md`.

## 3. Manifest (`<R>/app/src/main/AndroidManifest.xml`)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-feature android:name="android.hardware.location.gps" android:required="false" />

    <queries>
        <package android:name="com.instagram.android" />
        <package android:name="com.android.chrome" />
        <package android:name="com.google.android.youtube" />
    </queries>

    <application
        android:name=".FocusGateApp"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="false"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:supportsRtl="true"
        android:usesCleartextTraffic="false"
        android:theme="@style/Theme.FocusGate"
        tools:targetApi="34">

        <activity android:name=".ui.MainActivity" android:exported="true"
            android:launchMode="singleTask" android:screenOrientation="portrait"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity android:name=".ui.BlockedActivity" android:exported="false"
            android:taskAffinity="app.focusgate.blocked" android:excludeFromRecents="true"
            android:launchMode="singleInstance" android:noHistory="false"
            android:screenOrientation="portrait" android:theme="@style/Theme.FocusGate.Blocked" />

        <service android:name=".service.FocusService" android:exported="false"
            android:foregroundServiceType="location|specialUse">
            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Per-app screen-time limiter: detects when a user-chosen app is in the foreground to enforce the user's own daily limit" />
        </service>

        <!-- Transitive components removed: no providers/receivers in the merged manifest -->
        <provider android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup" tools:node="remove" />
        <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" tools:node="remove" />
    </application>
</manifest>
```

Expected in the merged manifest:
- the 8 permissions above
- plus `app.focusgate.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (a signature `<permission>` and its `<uses-permission>`, from androidx.core)

Must be absent: INTERNET, ACCESS_NETWORK_STATE, ACCESS_BACKGROUND_LOCATION, RECEIVE_BOOT_COMPLETED, QUERY_ALL_PACKAGES, WAKE_LOCK, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, USE_FULL_SCREEN_INTENT, BIND_ACCESSIBILITY_SERVICE, and any `<provider>` or `<receiver>`.

If the merger reports another transitive receiver or provider, remove it the same way and record it in verification.md.

## 4. Implementation order

- [ ] 1. **Bootstrap the Gradle wrapper and a minimal app that builds.**
      Do §1 steps 1–9. Then write all §1 config files, a placeholder manifest (application + MainActivity only, final attributes), `res/values/{strings,colors,themes}.xml`, the launcher icon files, and `ui/MainActivity.kt` showing one Compose `Text("FocusGate")`.
      Files: wrapper files, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/lint.xml`, `app/src/main/AndroidManifest.xml`, `res/**`, `<K>/ui/MainActivity.kt`.
      Verify: `<ENV>; .\gradlew.bat --version` gives Gradle 8.11.1 / JVM 21.0.11. `<ENV>; .\gradlew.bat assembleDebug` gives BUILD SUCCESSFUL and `app\build\outputs\apk\debug\app-debug.apk` exists. Record both in verification.md (V1). If KGP×Gradle fails, switch the wrapper to 8.9 (re-run step 5 with `--gradle-version 8.9` and its sha) and record why.

- [ ] 2. **Finish the manifest and backup XML.**
      Write the full §3 manifest. Keep the service/BlockedActivity entries commented out until their classes exist in items 11–12, then uncomment them; or create empty stub classes now. Stubs are simpler: `FocusService` returning `START_NOT_STICKY`, an empty `BlockedActivity`, an empty `FocusGateApp`. Write the exact backup XML (finding 11).
      Files: `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, stub `FocusGateApp.kt`, `service/FocusService.kt`, `ui/BlockedActivity.kt`.
      Verify: `<ENV>; .\gradlew.bat assembleDebug lintDebug` succeeds. `app\build\outputs\logs\manifest-merger-debug-report.txt` shows the provider and receiver removed.

- [ ] 3. **Pure model, apps, decision and the required decision-table test.**
      `core/Model.kt`, `core/MonitoredApps.kt`, `core/Decision.kt` (DESIGN §5 verbatim, plus `appState` with `TIMES_UP_NO_ZONES`, finding 10).
      Tests:
      - `DecisionTest`: rows 1–10, with the six required names `thirtyLeftHome_allowed`, `thirtyLeftOutside_allowed`, `zeroHome_blocked`, `zeroOutside_allowed`, `zeroLeaveHome_unblocked`, `zeroReturnHome_blocked`, plus a test for the `appState` NO_ZONES mapping.
      - `MonitoredAppsTest`.
      Verify: `<ENV>; .\gradlew.bat testDebugUnitTest --tests "app.focusgate.core.DecisionTest" --tests "app.focusgate.core.MonitoredAppsTest"` passes, and the report `app\build\test-results\testDebugUnitTest\TEST-app.focusgate.core.DecisionTest.xml` shows `failures="0"` and ≥ 11 tests.

- [ ] 4. **UsageAccumulator + tests (rollover, no double counting, process death).**
      Implement DESIGN §4 exactly, plus findings 6 and 7:
      - `replayStateOnly(events, cursorTs)`: skip if `begin >= cursorTs`; set `openSince = cursorTs` for non-empty sets afterwards.
      - `onScreenOn(ts)`: `cursorTs = max(cursorTs, ts - 2000)` with no accrual.
      - `closeAllNoAccrue()`
      - `queryBegin(armedAtTs)`: `max(armedAtTs, cursorTs - 2000)`
      - `replayBegin(armedAtTs)`: `max(armedAtTs, cursorTs - 6h)`
      - `markOwnUiShown(ts)`; `latestResumedTs`; `foregroundPkg()`; dirty flags; `toPersisted()` / `fromPersisted()`

      Constructor takes a `ZoneId`.
      Tests: `UsageAccumulatorTest` with every §16 bullet, plus:
      - deferred replay: skipped when the window is empty; `openSince = cursorTs` after the replay
      - RESUMED during screen-off accrues only from screen-on
      Verify: `<ENV>; .\gradlew.bat testDebugUnitTest --tests "app.focusgate.core.UsageAccumulatorTest"` passes, 0 failures.

- [ ] 5. **Location math: Geo, ZoneRules, ZoneEvaluator, ZoneSettler + tests.**
      DESIGN §6 exactly: `maxAccuracyM = clamp(2r, 10, 100)`, `exitMargin = max(1, 0.2r)`, N=3, ambiguous resets, unusable ignored, 20 s stale → UNKNOWN, 120 s carry-over with `lastUsableTs = restartTs`, `clearCarryOver()`.
      Tests: `ZoneEvaluatorTest` (all §16 bullets, plus the end-to-end decision rows 5/6 through the evaluator with 3 consistent readings), `ZoneSettlerTest`, `ZoneRulesTest`.
      Verify: `<ENV>; .\gradlew.bat testDebugUnitTest --tests "app.focusgate.core.Zone*"` passes.

- [ ] 6. **Failsafe logic: BootCheck, CrashLoopGuard, ArmRestore, DecisionFeed + tests.**
      Tests:
      - `BootCheckTest`: all 5 §16 cases.
      - `CrashLoopGuardTest`: 3 in 10 min trips; 3 over 11 min doesn't; arm clears; both signals on one instance = 1 entry. The once-per-instance flag is modelled by a tiny pure `RestartRecorder` in the same file.
      - `ArmRestoreTest`: Armed + new boot → PersistReboot; Disarmed(PANIC) + new boot → stays PANIC; Armed + same boot → OpenGate(storedArmedAtTs); missing boot id → PersistReboot.
      - A feed capacity test, inside `MonitoredAppsTest` or a small `DecisionFeedTest`.
      Verify: `<ENV>; .\gradlew.bat testDebugUnitTest` passes.

- [ ] 7. **ServiceTypes + ServiceStart dispatch + tests.**
      `ServiceStart.handle` implements the §7 table plus:
      - finding 3: ACTION_START with an existing engine → `port.startForeground(full types)`; on success `setDegraded(false)`, `refreshNotification()`, `engineTick()`; on SecurityException stay degraded; never a second engine
      - finding 8: TEST_BLOCK/NOTIF_DISMISSED with no engine → `stopSelf(startId)`, NOT_STICKY, no unclean-restart record
      - sticky `null` path: record once, crash-loop check (trip → `disarm(CRASH_LOOP)`, stop, no engine), full types → SecurityException retry with the degraded types and `locationDegraded = true` → other exception → stopSelf without disarm (+ paused alert)
      - gate closed → minimal notification + stop

      Tests: `ServiceTypesTest` (API 34 ± location; API 29–33 → LOCATION or empty, never SPECIAL_USE) and `ServiceStartTest`, covering every §16 case plus the degraded re-promotion case and both finding-8 cases.
      Verify: `<ENV>; .\gradlew.bat testDebugUnitTest --tests "app.focusgate.core.Service*"` passes.

- [ ] 8. **DataGate + EngineCore + tests (the zero-data gate core).**
      Add `gate/DataGate.kt` (§10 verbatim) and `core/EngineCore.kt` per §7 tick steps 1–5:
      - Gate check first; if closed, return without calling any reader.
      - `pendingReplay` on start, run on the first tick while the screen is on (finding 6).
      - Need-location predicate → `LocationReader.start/stop`.
      - Own-UI guard before `Blocker.block`.
      - Pending block verify (no extra query; Home fallback; `blockLaunchHealthy`).
      - Test-blocking state machine (§13 steps 1–7).
      - Error streak (10 → `RequestDisarm(CRASH_LOOP)`); `Failed` + AppOps revoked → `RequestDisarm(PERMISSION_LOST)`.
      - Notification-repost flag.
      - Feed entries on state changes only.
      - Persist cadence (15 s / session close / crossing 0 / screen off / stop).
      - `stop()` clears all in-memory state and returns the final `PersistedUsage`.
      Every call into a reader is preceded by `gateOpen()`.
      Tests:
      - `EngineCoreTest`: all §16 bullets, including lost STOPPED + Blocked shown → no relaunch, and the query floor (every recorded `begin >= armedAtTs`).
      - `DataGateTest`: gate closed → zero reader calls over many ticks and screen events; gate closed mid-session → an in-flight reading is ignored and the next tick makes no calls; `stop()` clears state; `DataGate.open/close` semantics, including `armedAtTs() == Long.MAX_VALUE` while closed.
      Verify: `<ENV>; .\gradlew.bat testDebugUnitTest` passes (all core tests); record the totals (V3).

- [ ] 9. **Storage, arm controller and system helpers.**
      - `data/Store.kt` (exact §12 keys; `writeUsageAndClearCursor` per finding 5).
      - `arm/ArmController.kt`:
        - `arm` re-checks preconditions (Usage Access + notifications; SAW → warning flag only), then `commit` Armed + boot id + `armed_at_ts = cursor_ts = now` + cleared restart list, then `DataGate.open`, then `ContextCompat.startForegroundService(explicit FocusService ACTION_START)`.
        - `disarm` runs `DataGate.close()` FIRST, then `commit` Disarmed(reason), then `stopService`.
        - `restoreOnProcessStart` uses `ArmRestore`.
      - `system/Permissions.kt`, `system/OemSettings.kt`, `system/InstalledApps.kt`.
      - `FocusGateApp.kt` (real).

      Permitted while disarmed (finding 4). Only these calls may run with the gate closed, and only in these files:

      | Call | File | Why it's not user data |
      |---|---|---|
      | `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS)` | `system/Permissions.kt` | own permission state |
      | `checkSelfPermission` / `Settings.canDrawOverlays` / `isIgnoringBatteryOptimizations` | `system/Permissions.kt` | own permission state |
      | `LocationManager.isLocationEnabled` | `system/Permissions.kt` | global switch state, no location |
      | `LocationManager.isProviderEnabled` | `location/GpsSource.kt`, inside gated `start` only | provider switch state |
      | `Settings.Global.BOOT_COUNT` | `arm/ArmController.kt` | boot counter |
      | `PowerManager.isInteractive` | `service/Engine.kt` | screen state (engine exists only while armed) |
      | PackageManager `getApplicationInfo`/`getApplicationLabel`/`getLaunchIntentForPackage` for the 3 packages | `system/InstalledApps.kt` | install/label metadata, never usage |
      Verify: `<ENV>; .\gradlew.bat assembleDebug testDebugUnitTest` succeeds.

- [ ] 10. **Data sources: UsageEventSource, GpsSource, LocationSession.**
      As in the §2 file table. Gate check as the literal first statement of `read`, `start` and the listener callback. `UsageEvents.Event` is reused and non-monitored events are skipped with `continue` before any field other than `packageName` is read. `GpsSource` keeps no `Location` past the callback. `LocationSession` refuses to start when the gate is closed and stops on Disarmed.
      Verify: `<ENV>; .\gradlew.bat assembleDebug lintDebug` succeeds (no `MissingPermission`/`NewApi` errors).

- [ ] 11. **Foreground service, engine, notifications, blocking launcher.**
      - `service/FocusService.kt` (real `ServicePort` adapter; `ServiceCompat.startForeground` with mapped `ServiceInfo` bits; the 2-argument form when the type set is empty; `service_alive` marker; `onDestroy` → `Engine.stop()`, join ≤ 500 ms, clear the statics, `snapshots.value = Idle`).
      - `service/Engine.kt`:
        - Receiver per finding 1, exactly: `IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }` + `ContextCompat.registerReceiver(ctx, rx, filter, null, engineHandler, ContextCompat.RECEIVER_NOT_EXPORTED)`.
        - `stop()` uses `Store.writeUsageAndClearCursor` (finding 5).
      - `service/Notifications.kt` (explicit panic and delete intents per finding 2).
      - `service/BlockLauncher.kt`.
      Verify: `<ENV>; .\gradlew.bat assembleDebug lintDebug testDebugUnitTest` succeeds.

- [ ] 12. **BlockedActivity.**
      - Opaque full-screen Compose UI.
      - Blocked mode: copy, "Go home" + Back → HOME + finish + `markOwnUiShown`. When the snapshot zone state is OUTSIDE: "You're outside your zones, so <App> is open again" + "Back to <App>" via `InstalledApps.launchIntent`.
      - Test mode: "Test worked… Waited N s" + Done.
      - `isResumed` static and UiPresence updates.
      - Finding 9: collect `ArmController.state` and `finish()` on Disarmed; in `onResume`, non-test mode finishes if `!DataGate.isOpen()`.
      Verify: `<ENV>; .\gradlew.bat assembleDebug lintDebug` succeeds.

- [ ] 13. **UI shell, theme, Essentials screens, panic pill.**
      - `ui/theme/Theme.kt`, `ui/components/Components.kt`, `ui/AppShell.kt`, `ui/AppViewModel.kt`.
      - Screens: `HomeScreen.kt`, `AllowancesScreen.kt`, `SettingsScreen.kt`.
      - Full `MainActivity.kt`. Its `onResume` (finding 3): when armed, send explicit `ACTION_START` if `!FocusService.isRunning || FocusService.locationDegraded || ServiceTypes.compute(sdk, precise, hasZones) != FocusService.currentTypes`. When adding the first zone or granting location, send `ACTION_START` again.
      - Big always-visible "Disable now" pill (56 dp, power icon, single tap → `disarm(PANIC)`, snackbar "FocusGate is off") / green "Enable" pill.
      - Banners: crash-loop, permission-lost, "off after restart", zones corrupt, "Location is off…", "Blocking may not open without 'Display over other apps'".
      - Essentials shows no technical text.
      Verify: `<ENV>; .\gradlew.bat assembleDebug lintDebug` succeeds.

- [ ] 14. **Zones list and editor.**
      `ZonesScreen.kt`, `ZoneEditorScreen.kt`:
      - "Use my current location" uses LocationSession ZoneSetup and shows live ±m and "steady k/5". It's disabled with "Turn FocusGate on to use location" when disarmed.
      - Location permission is requested here (FINE + COARSE together); approximate-only → "Precise location needed for zones".
      - The 90 s not-settling path + "Save anyway" (≤ 50 m, radius pre-set to `max(r, ceil(best))`).
      - Radius defaults to 5 m; honesty copy verbatim from §6.
      - Delete confirmation.
      - After save/delete, notify the engine (`requestImmediateTick`; EngineCore re-reads `ConfigSource` and clears carry-over when the zones change).
      Verify: `<ENV>; .\gradlew.bat assembleDebug lintDebug` succeeds.

- [ ] 15. **Onboarding and Privacy.**
      - `OnboardingScreen.kt`: DESIGN §13 steps 1–6 with one button each, copy verbatim. Step 3 copy adds "Find FocusGate in the list and turn it on" (finding 12c). OEM steps use manual confirmation. Shown only when Enable is tapped with missing preconditions; nothing is requested at first launch (first launch shows the welcome card + Enable pill). POST_NOTIFICATIONS uses the runtime request, with the settings fallback when permanently denied.
      - `PrivacyScreen.kt`: §12 table, §14 table, no-internet line, GPS honesty copy, limitations summary (§15).
      Verify: `<ENV>; .\gradlew.bat assembleDebug lintDebug` succeeds.

- [ ] 16. **Insights mode and self-tests.**
      - `InsightsScreen.kt`:
        - live zone/per-app state
        - Decision Feed (Show/Hide, default hidden, persisted `feed_visible`, entries from the snapshot, never coordinates)
        - diagnostics rows with Fix buttons (Usage Access, Notifications, Precise location, Display over other apps, Battery, Autostart, Background pop-ups, High background power via `OemSettings`), service running, armed since (time only), boot-check state, `blockLaunchHealthy`, `locationDegraded`, location errors
        - "Test blocking" (armed only): explicit `ACTION_TEST_BLOCK` via `startService` while running; shows state from the snapshot or the last result
      - `GpsTestScreen.kt` (armed only; LocationSession GpsTest bound to `ON_START`/`ON_STOP` via `LifecycleEventObserver` + `DisposableEffect`; big ±m, Inside/Outside/Unclear, Consecutive k/3, seconds since the last fix, hint copy).
      Verify: `<ENV>; .\gradlew.bat assembleDebug lintDebug testDebugUnitTest` succeeds.

- [ ] 17. **Full verification and evidence.** Run every check in §5 and record each in verification.md. Fix failures and re-run. Leave the APK in place.

## 5. Verification (run in item 17; paste the commands and key output into verification.md)

All commands use `<ENV>`. APK: `D:\Kirooo\Mods\FocusGate\app\build\outputs\apk\debug\app-debug.apk`. aapt2: `$a="$env:ANDROID_HOME\build-tools\36.0.0\aapt2.exe"`.

- **V1 Toolchain.** `.\gradlew.bat --version`. Expect Gradle 8.11.1 (or 8.9 with the reason recorded) and JVM 21.0.11.
- **V2 Build.** `.\gradlew.bat clean assembleDebug`. Expect BUILD SUCCESSFUL. Record the APK size.
- **V3 Unit tests.** `.\gradlew.bat testDebugUnitTest`. Expect BUILD SUCCESSFUL.
  - Summarise `app\build\test-results\testDebugUnitTest\*.xml` with `Select-String 'testsuite name=.*tests="(\d+)".*failures="(\d+)".*errors="(\d+)"'` per class.
  - Every class has 0 failures and 0 errors.
  - The six required DecisionTest names are present.
- **V4 Lint.** `.\gradlew.bat lintDebug`, which is what `lint` runs for the default variant. Running `.\gradlew.bat lint` too is acceptable.
  - Expect BUILD SUCCESSFUL.
  - Record the error/warning counts from `app\build\reports\lint-results-debug.txt` (or the html/xml), and list every suppression (`lint.xml` and every `tools:ignore`/`@SuppressLint`) with its reason.
- **V5 Merged manifest.** Locate it with `Get-ChildItem app\build\intermediates -Recurse -Filter AndroidManifest.xml | ? { $_.FullName -match 'merged_manifest' }`. On AGP 8.7 it's expected at `app\build\intermediates\merged_manifest\debug\processDebugMainManifest\AndroidManifest.xml`. Record the actual path and paste the file. Assert:
  - exactly the 8 permissions + DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
  - no INTERNET / ACCESS_NETWORK_STATE / ACCESS_BACKGROUND_LOCATION / RECEIVE_BOOT_COMPLETED / QUERY_ALL_PACKAGES / WAKE_LOCK / USE_FULL_SCREEN_INTENT / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
  - 0 `<provider`, 0 `<receiver`, 0 `accessibility`
  - `exported="true"` only on MainActivity
  - `allowBackup="false"`
  - 3 `<package` entries in `<queries>`
- **V6 APK permissions.** `& $a dump permissions $apk`. Expect the same permission set as V5 and nothing else.
- **V7 APK manifest tree.** `& $a dump xmltree --file AndroidManifest.xml $apk`, saved to `app\build\fg-xmltree.txt`. Assert:
  - no `E: provider`, no `E: receiver`, no `accessibility`
  - per `E: activity`/`E: service`: `android:exported` is `0xffffffff` only for MainActivity and `0x0` elsewhere
  - `android:allowBackup` is `0x0`
  - `foregroundServiceType` is present
  
  Also run `& $a dump badging $apk`; record package/sdk lines and confirm `targetSdkVersion:'34'`.
- **V8 Source greps** over `app\src\main`, using `Get-ChildItem app\src\main -Recurse -Include *.kt,*.xml | Select-String -Pattern '<p>'`:
  - (a) Logging: `'\bLog\.|println|printStackTrace|System\.out|System\.err'` → 0.
  - (b) Network/overlay/accessibility: `'WebView|ServerSocket|java\.net|HttpURLConnection|addView|TYPE_APPLICATION_OVERLAY|AccessibilityService|firebase|com\.google\.android\.gms'` → 0.
  - (c) `import android` in `<K>\core` and `<K>\gate` → 0.
  - (d) Explicit intents (finding 2): `'Intent\(\s*(Intent\.)?ACTION_|Intent\(\s*"'`. Every hit must be HOME (`ACTION_MAIN` + `CATEGORY_HOME`), a `Settings.*`/`android.settings.*` intent, or an `OemSettings` intent built with an explicit `ComponentName`. List every hit with a verdict. Every `PendingIntent.get*` and every `startService`/`startForegroundService` hit names `::class.java`.
  - (e) Disarmed allow-list (finding 4): `'unsafeCheckOpNoThrow|isLocationEnabled|isProviderEnabled|BOOT_COUNT|isInteractive|getApplicationInfo|getApplicationLabel|getLaunchIntentForPackage|getPackageInfo'`. Each hit is only in the file the item-9 table allows.
- **V9 Dependencies** (finding 12b). `.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath > app\build\fg-deps.txt`. Extract the unique groups with `Select-String '--- ([\w.\-]+):' | % { $_.Matches[0].Groups[1].Value } | Sort -Unique`.
  - PASS if there's no `com.google.android.gms`, `com.google.firebase`, or analytics/crash/ads group.
  - List every group (for REPORT). `junit` must not appear in the runtime classpath.
- **V10 Gate audit.** List every call site of `'UsageStatsManager|USAGE_STATS_SERVICE|queryEvents|queryUsageStats|queryAndAggregateUsageStats|UsageEvents'` and `'LocationManager|LOCATION_SERVICE|requestLocationUpdates|getLastKnownLocation|getCurrentLocation|removeUpdates'` with file:line, in a table in verification.md. For each data-reading call, quote the enclosing function's first statement and confirm it is the `DataGate` check. Expect:
  - UsageStats only in `data/UsageEventSource.kt`
  - location data APIs only in `location/GpsSource.kt` (`isLocationEnabled` in `system/Permissions.kt`, a non-data check)
  - `getLastKnownLocation`/`getCurrentLocation` → 0
  
  Then confirm reachability: `UsageEventSource`/`GpsSource` are constructed only in `service/Engine.kt` and `location/LocationSession.kt` (grep `UsageEventSource(`, `GpsSource(`), Engine only in `FocusService` via `ServiceStart` after a gate-open check (quote the lines), and `DataGateTest`/`ServiceStartTest` pass (V3 reference).
- **V11 Findings checklist.** One line per finding 1–12 with file:line evidence (pairs with the §0.3 table).
- **V12 Device** (optional, only if `& "$env:ANDROID_HOME\platform-tools\adb.exe" devices` lists the phone): `adb install -r $apk`, launch, confirm there is no crash at start (Compose text renders with InitializationProvider removed, DESIGN §17.13). Otherwise record "device not connected, on-device checks UNVERIFIED".
- **V13 Manual phone tests for REPORT** (not executed here). Copy DESIGN §16 "Manual phone tests" into verification.md, with the finding-12d procedure for "Test blocking": turn the screen off and on (or swipe FocusGate from Recents), reopen FocusGate, start the test, switch to another app (not a payment app), wait ≥ 11 s, and the result must read "Waited N s" with N ≥ 11. The definitive variant is a real block after a Recents swipe. The final-report step uses this.

## 6. Evidence file `<R>/.agents/tasks/verification.md`

Structure:
- Header: date, JDK, Gradle/AGP/Kotlin actually used.
- One section per V1–V13, each with: exact command, exit code, trimmed key output (≤ 40 lines; the full output stays in `app\build\…` files, give their paths), and the result PASS / FAIL / UNVERIFIED (with the reason).
- "Deviations from DESIGN" (e.g. wrapper fallback, extra `tools:node="remove"`, extra lint suppressions), each with a reason.
- "NOT DONE", if anything.

Never write PASS without pasted evidence.

## Open questions / verify on device

1. Funtouch deep-link components for Autostart / background pop-ups / high background power (DESIGN §15) are unverified on the I2017. The fallbacks exist; confirm on the device.
2. Whether vivo's "Display pop-up windows while running in background" plus SAW allows `startActivity` from the FGS, including after a Recents swipe. Confirm with "Test blocking" and a real block.
3. Funtouch sticky-restart behaviour, and whether a foreground re-`startForeground` with LOCATION re-grants while-in-use location to an FGS first started from the background (finding 3). If it doesn't, the fallback is `stopService` then `startForegroundService` from the activity; it's not implemented unless the device shows it's needed.
4. GPay with FocusGate armed and the bare SAW grant (the main acceptance test). If it fails, revoke SAW only and retest.
5. `Settings.Global.BOOT_COUNT` readability on Funtouch (the fallback covers it).
6. Indoor GPS at the user's desk: if accuracy never drops below 10 m, a 5 m zone never reads INSIDE, so the app fails open (no blocking). Use 10–15 m.
7. UsageEvents completeness for the 3 apps (YouTube PiP, Chrome Custom Tabs).
8. `stopSelf()` without `startForeground` on a gate-closed sticky restart (DESIGN §17.12).
9. KGP 2.0.21 × Gradle 8.11.1 is outside the tested range (the fallback is 8.9). Lint checks beyond the three suppressed may be fatal on AGP 8.7.3; suppress narrowly and record them.
10. Whether lint flags `fullBackupContent` together with `allowBackup="false"` as an error. Expected warning-level at most; if fatal, keep both attributes and suppress that one ID with a reason.
