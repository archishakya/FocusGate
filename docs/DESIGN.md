# FocusGate — Technical Design

## 1. Overview & goals

FocusGate is a sideloaded, fully offline, single-module native Android app (Kotlin + Jetpack Compose). It gives each monitored app its own daily allowance: Instagram (`com.instagram.android`), Chrome (`com.android.chrome`) and YouTube (`com.google.android.youtube`). The list is data-driven, so more apps can be added later. While an app has time left it is always allowed. Once its allowance reaches 0 it is blocked only while the phone is inside one of the user's restricted zones (for example Home). Leaving the zone unblocks it immediately, and coming back blocks it again.

Foreground detection and usage counting use `UsageStatsManager.queryEvents` only. There is no AccessibilityService and no persistent overlay. Blocking launches FocusGate's own full-screen `BlockedActivity` (or sends Home as a fallback), and only while a blocked app is in the foreground. That is why Google Pay and banking apps keep working.

The target device is an iQOO 9 5G (I2017) on Android 14 (API 34) with Funtouch OS 14.

Goals, in priority order:
1. Never break the rest of the phone (GPay, banking). Fail open on any doubt.
2. While disarmed, access zero data: no UsageStats queries, no location, no service, no receivers. One gate enforces this (§10).
3. Count screen time correctly per app, including day rollover, an app still open at query time, and process death without double counting.
4. Make location-gated blocking honest about GPS limits (5 m default radius, hysteresis, fail open).
5. Make it recoverable. After a reboot the blocker stays off until the user re-enables it. Panic is always reachable. A crash loop disarms automatically.
6. Privacy that can be checked in the APK: no INTERNET, nothing exported except the launcher, no providers, no logging, backup disabled.

Non-goals: tamper resistance against a determined user (force-stop, revoking Usage Access and uninstall all bypass it, see §15), Play Store distribution, tablets or landscape, and multi-user or work profiles.

## 2. Toolchain

The stack is locked once this design is approved. Every version is pinned exactly in `gradle/libs.versions.toml`, with no `+` and no ranges.

| Item | Version | Notes |
|---|---|---|
| JDK running Gradle | JBR 21.0.11 at `C:/Users/Anirudh/.jdks/jbr-21.0.11` | Found in env.md. The Android Studio JBR 25.0.2 is **not** used, because Gradle 8.11.1 does not support running on Java 25. |
| Gradle wrapper | 8.11.1 (`-bin` distribution) | Supports running on Java 21. AGP 8.7 needs Gradle 8.9 or newer. |
| Android Gradle Plugin | 8.7.3 | Supports compileSdk 35. Its default build-tools are 34.0.0, which are installed. |
| Kotlin (`org.jetbrains.kotlin.android`) | 2.0.21 | |
| Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) | 2.0.21 | Since Kotlin 2.0 this ships with Kotlin, so there is no separate `kotlinCompilerExtensionVersion`. |
| Compose BOM `androidx.compose:compose-bom` | 2024.12.01 | Brings Compose UI 1.7.6 and Material3 1.3.1 |
| `androidx.compose.ui:ui`, `ui-graphics`, `foundation`, `material3`, `material-icons-extended` | from the BOM | No `ui-tooling` or `ui-test-manifest`, because they add exported debug activities. |
| `androidx.core:core-ktx` | 1.15.0 | needs compileSdk 35 |
| `androidx.activity:activity-compose` | 1.9.3 | `enableEdgeToEdge()` |
| `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose` | 2.8.7 | |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | StateFlow for UI. The engine thread does not use coroutines. |
| `junit:junit` | 4.13.2 | **testImplementation only**, not packaged in the APK |

SDK levels: `compileSdk = 35`, `targetSdk = 34`, `minSdk = 29`. targetSdk 34 is chosen deliberately because it matches the device's API 34 runtime exactly and avoids opting into Android 15 behaviour changes that cannot be tested on this phone. These include SYSTEM_ALERT_WINDOW (SAW) requiring a visible overlay before starting an FGS from the background, and the forced edge-to-edge changes. minSdk 29 gives `ACTIVITY_STOPPED` UsageEvents and `AppOpsManager.unsafeCheckOpNoThrow`. Java/Kotlin bytecode target is 17 (`compileOptions` 17, `kotlin { compilerOptions { jvmTarget = JVM_17 } }`). There is no Gradle toolchain auto-provisioning, so no foojay plugin.

Build plumbing:
- `gradle.properties`: `org.gradle.java.home=C:/Users/Anirudh/.jdks/jbr-21.0.11`, `android.useAndroidX=true`, `kotlin.code.style=official`, `org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8`, and `android.nonTransitiveRClass=true`. The build scripts also set `$env:JAVA_HOME` to the same JDK, so that the wrapper launcher itself also runs on 21.
- `local.properties`: `sdk.dir=C\:\\Users\\Anirudh\\AppData\\Local\\Android\\Sdk`.
- Wrapper generation (no Gradle installed):
  1. Download `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` and its `.sha256` to `%TEMP%`, and verify the hash.
  2. Extract the zip and run `gradle.bat wrapper --gradle-version 8.11.1 --distribution-type bin` in the project with JAVA_HOME set to JDK 21.
  3. Put `distributionSha256Sum=<official sha>` in `gradle-wrapper.properties`.
  4. Delete the temp files.
- Repositories: `google()` and `mavenCentral()` only, declared in `settings.gradle.kts` (`dependencyResolutionManagement` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`).
- `buildTypes.release`: `isMinifyEnabled = false` for this project, because only the debug APK is a deliverable. `buildConfig = false` is not needed. Debug and release share the same code, and there is no logging in either.
- Lint: `lint { abortOnError = true; checkDependencies = false; warningsAsErrors = false; lintConfig = file("lint.xml") }`. Expected warnings such as `GradleDependency` and `LockedOrientationActivity` are acceptable. `ProtectedPermissions` is suppressed only on the `PACKAGE_USAGE_STATS` element via `tools:ignore`.
- `app/lint.xml` ships from the start. The machine date is 2026-10-23, and lint derives the Play target-SDK deadline from the current date, so the fatal `ExpiredTargetSdkVersion` would otherwise fail `lintDebug` for targetSdk 34:
  ```xml
  <lint>
    <!-- Sideloaded only, never on Play; targetSdk 34 deliberately matches the API 34 device -->
    <issue id="ExpiredTargetSdkVersion" severity="ignore"/>
    <issue id="ExpiringTargetSdkVersion" severity="ignore"/>
    <issue id="OldTargetApi" severity="ignore"/>
  </lint>
  ```
  REPORT lists these three suppressions and the reason.
- KGP 2.0.21 is fully tested only up to Gradle 8.8. Gradle 8.11.1 is expected to work, possibly with deprecation warnings, which are acceptable. If the build fails on a KGP/Gradle incompatibility, the only allowed fallback is Gradle **8.9** (the AGP 8.7 minimum), with the other pins unchanged (§17).

Build commands (PowerShell):
```
$env:JAVA_HOME="C:\Users\Anirudh\.jdks\jbr-21.0.11"
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

## 3. Architecture

There is a single Gradle module, `:app`, with application id and namespace `app.focusgate`. Everything runs in a single process (no `android:process`), so the in-memory gate (§10) is shared by the UI and the service.

Packages under `app/src/main/java/app/focusgate/`:

| Package | Contents | Android imports? |
|---|---|---|
| `core` | `EngineCore.kt` (pure tick logic over the `UsageReader`, `LocationReader`, `Blocker`, `PermissionProbe` and `Clock` interfaces, so it can be tested with fakes), `ServiceStart.kt` (pure `onStartCommand` dispatch over a `ServicePort` interface, §7), `ServiceTypes.kt` (pure; returns a set of `FgsType { SPECIAL_USE, LOCATION }` per SDK, which `FocusService` maps to `ServiceInfo` bits), `ArmRestore.kt` (pure process-start restore decision used by `ArmController.restoreOnProcessStart`), `Decision.kt` (`decide`, `safeDecide`), `UsageAccumulator.kt`, `ZoneEvaluator.kt`, `Geo.kt` (haversine), `ZoneSettler.kt`, `BootCheck.kt`, `CrashLoopGuard.kt`, `MonitoredApps.kt`, `DecisionFeed.kt` (ring buffer), `Model.kt` (data classes and enums) | **None.** Pure Kotlin and JVM unit-testable. A grep audit asserts there is no `import android` in `core/`. |
| `gate` | `DataGate.kt`: the single gate (§10) | none |
| `data` | `Store.kt` (SharedPreferences persistence), `UsageEventSource.kt` (the only `UsageStatsManager` call site) | yes |
| `location` | `GpsSource.kt` (the only `LocationManager` call site), `LocationSession.kt` (UI-scoped sessions for zone setup and the GPS test) | yes |
| `service` | `FocusService.kt`, `Engine.kt`, `Notifications.kt`, `BlockLauncher.kt` | yes |
| `arm` | `ArmController.kt`: the only owner of armed/disarmed transitions | yes |
| `system` | `Permissions.kt` (status checks and settings intents), `OemSettings.kt` (Funtouch deep links and fallbacks) | yes |
| `ui` | `MainActivity.kt`, `BlockedActivity.kt`, `AppViewModel.kt`, `theme/`, `screens/` (Home, Apps, Zones, ZoneEditor, Insights, Privacy, Settings, Onboarding) | yes |
| root | `FocusGateApp.kt` (`Application`) | yes |

Components (manifest):
- `ui.MainActivity`: the launcher, and the **only** exported component (`exported="true"` with a MAIN/LAUNCHER intent filter and no other filters). Portrait.
- `ui.BlockedActivity`: `exported="false"`, `taskAffinity="app.focusgate.blocked"`, `excludeFromRecents="true"`, `launchMode="singleInstance"`, `noHistory="false"`, portrait, `theme=@style/Theme.FocusGate.Blocked` (opaque, not translucent).
- `service.FocusService`: `exported="false"`, `foregroundServiceType="location|specialUse"`, with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Per-app screen-time limiter: detects when a user-chosen app is in the foreground to enforce the user's own daily limit"/>`.
- No manifest receivers and no providers. The `ProfileInstallReceiver` (exported, from profileinstaller) and `androidx.startup.InitializationProvider`, both pulled in transitively by Compose and lifecycle, are removed with `tools:node="remove"` in the app manifest. FocusGate does not use `ProcessLifecycleOwner`, and EmojiCompat falls back gracefully when it is not initialised.

Data flow:
```
MainActivity/ViewModel ──(ArmController.arm/disarm)──► Store + DataGate
        ▲  StateFlow<EngineSnapshot>                      │ open?
        │                                                 ▼
FocusService ── owns ──► Engine (HandlerThread "fg-engine")
                           ├─ every 1 s while screen on: UsageEventSource.read(cursor, now) ─► UsageAccumulator
                           ├─ remaining per app ─► need location? ─► GpsSource start/stop ─► ZoneEvaluator
                           ├─ foreground monitored pkg + safeDecide(remaining, zoneState)
                           └─ BLOCKED ─► BlockLauncher (BlockedActivity, else Home)
```

Threading:
- The engine runs on one `HandlerThread("fg-engine")`. The poll `Runnable`, the `LocationListener` (registered with that thread's `Looper`) and the screen on/off `BroadcastReceiver` (registered with that thread's `Handler`) all run on it. All mutable engine state is confined to that thread, so there are no locks.
- The engine publishes an immutable `EngineSnapshot` to a `MutableStateFlow`. It contains per-app used/remaining/state, zone state, the consecutive count, service health and the feed. Compose collects it with `collectAsStateWithLifecycle`.
- `DataGate` uses `@Volatile` fields and can be read from any thread. Only `ArmController` writes them, and every `ArmController` entry point (`restoreOnProcessStart`, `arm`, `disarm`) runs on the main thread. `FocusGateApp.onCreate` does not touch `DataGate` directly; it calls `ArmController.restoreOnProcessStart()` (§10).
- **Engine-initiated disarms.** The engine thread never calls `ArmController` directly. When `EngineCore` returns `TickOutcome.RequestDisarm(reason)` (crash-loop error streak, Usage Access lost), the Android `Engine` sets `stopping = true` (no further ticks are scheduled, the in-flight tick does nothing more), and does `mainHandler.post { ArmController.disarm(reason) }`. `disarm` then closes the gate and stops the service on the main thread, and `onDestroy` (main thread) joins the engine thread, so there is never a self-join. There is no `Application`-level uncaught-exception handler: an uncaught exception kills the process, and the crash is detected on the next service start through the `service_alive` marker (§11c), on the main thread.
- **UI → engine signals.** Activities reach the running engine through `FocusService.engine` (a `@Volatile` static reference set after the engine starts and cleared in `onDestroy`). The only calls are `markOwnUiShown(ts)` (§4), `onOwnUiStopped()` / `requestTestBlock()` (§13) and `requestImmediateTick()`. Each just posts to the engine handler; if no engine exists it is a no-op.
- `UiPresence` (`ui/UiPresence.kt`) holds two `@Volatile` values updated by `MainActivity` and `BlockedActivity`: `mainResumed: Boolean` and `lastOwnActivityEventElapsed: Long` (`SystemClock.elapsedRealtime()` at each `onResume`, `onPause`, `onStop` and `finish`). It contains no data about other apps.
- `Store` writes use one `SharedPreferences.Editor` per logical update. Arm and disarm use `commit()` (synchronous), and counters use `apply()`.
- UI-scoped `LocationSession` (zone setup and the GPS test) runs on the main looper and is tied to the screen's lifecycle.

## 4. Usage tracking

Source: `UsageStatsManager.queryEvents(begin, end)`. API 35's `UsageEventsQuery.setPackageNames` is not available on API 34, so the system returns every package's events. `UsageEventSource.read()` iterates with `getNextEvent` into a reused `UsageEvents.Event`. It immediately `continue`s past any event whose `packageName` is not in `MonitoredApps.packages`, and past any event type other than `ACTIVITY_RESUMED` (1), `ACTIVITY_PAUSED` (2) and `ACTIVITY_STOPPED` (23). Nothing about non-monitored events is stored, counted or copied. Each surviving event becomes `MonitoredEvent(pkg: String, activityKey: String, type: RESUMED|PAUSED|STOPPED, ts: Long)`. `activityKey` is `className ?: ""` and lives in memory only. The returned list goes straight to the pure `UsageAccumulator`. This is the only place in the codebase that touches `UsageStatsManager`, and its first statement is the gate check (§10).

Foreground model (monitored events only): for each package the accumulator keeps `resumed: MutableSet<String>` of activity keys, plus `openSince: Long?`. On RESUMED, the key is added, and if the set was empty, `openSince = max(ts, accrualFloor)`. On PAUSED or STOPPED, the key is removed, and if the set becomes empty, `accrue(pkg, openSince, ts)` runs and `openSince = null`. A package is in the foreground when its set is non-empty. If several are (split screen), the "foreground monitored package" is the one with the latest RESUMED timestamp. This needs no events from other apps: when the user switches from Instagram to GPay, Instagram itself emits PAUSED/STOPPED. The accumulator also keeps `latestResumedTs[pkg]`. `activityKey = className` collapses two live instances of the same activity class (for example Chrome in multi-window), so the first PAUSED closes the session early; this is a known undercount in the fail-open direction (§15) and is not corrected.

**Own-UI reset (lost STOPPED protection).** A STOPPED event can be lost (the monitored app crashes in the foreground, or a replayed RESUMED has no matching close). Without a correction the engine would keep believing, say, Instagram is in front while the user is in GPay, and relaunch the Blocked screen over GPay. FocusGate uses its *own* activity lifecycle as a privacy-neutral "something else is on top" signal:
- `BlockedActivity.onResume`, `MainActivity.onResume` and the "Go home" button call `FocusService.engine?.markOwnUiShown(System.currentTimeMillis())` (same wall clock as UsageEvents timestamps). It is posted to the engine thread and runs `EngineCore.markOwnUiShown(ts)`.
- `markOwnUiShown(ts)` sets `ownUiShownTs = max(ownUiShownTs, ts)`. For every monitored package whose `latestResumedTs < ts`, it closes the session at `ts` (`accrue(pkg, openSince, max(openSince, ts))`, so nothing already accrued is re-added) and clears its `resumed` set.
- From then on, a RESUMED event with `ts <= ownUiShownTs` (for example one re-delivered by the 2 s overlap) updates nothing: it neither reopens the session nor changes `latestResumedTs`. A package is foreground again only after a **new** RESUMED with `ts > ownUiShownTs`.
- `EngineCore` passes a package to the `Blocker` only if `latestResumedTs[pkg] > ownUiShownTs`. This guard lives in `EngineCore`, not in `BlockLauncher`, so it is JVM-tested.
- Cost: if the user runs FocusGate and a monitored app side by side in split screen, the monitored app is treated as closed until its next RESUMED, which undercounts (fail open).
- In the normal case (complete events) the signal is redundant: switching to the Blocked screen already produces the monitored app's own PAUSED.

Persisted bookkeeping lives in `Store`:
- `dayKey: Long` (local `LocalDate.toEpochDay()`)
- `usedMs[pkg]: Long`, one value per monitored app, which is the "today's minutes" counter at ms resolution
- `cursorTs: Long`, the wall-clock ms up to which usage has been accrued
- `armedAtTs: Long`

`usedMs` and `cursorTs` are always written in the **same** `Editor` (atomic per SharedPreferences semantics).

Tick algorithm (`UsageAccumulator.advance(events, now, zone)`, pure, `ZoneId` injected):
1. **Clock sanity.** If `now < cursorTs` (the wall clock moved back), set `cursorTs = now`, close all open sessions without accruing, and stop.
2. **Rollover.** `todayStart = LocalDate(now, zone).atStartOfDay(zone)`. Midnight is computed with `java.time`, which is DST-safe. Events are processed in timestamp order. `accrue(pkg, from, to)` splits `[from, to)` at every local midnight it crosses. It adds only the part inside the current `dayKey`. When a segment starts in a newer day, it sets `dayKey` to that day and resets every `usedMs` to 0 first. So a session that spans midnight gives its pre-midnight part to the old (discarded) day and its post-midnight part to the new day. On every tick, even with no events and no open session, if `today > dayKey` the counters are reset to 0 and `dayKey = today`. The reset happens **only when `today > dayKey`**. If `today < dayKey` (a time-zone change westward across midnight, or a manual clock change), the counters and `dayKey` are kept unchanged until the local date catches up, so a time-zone change never gives free time. In that state `accrue` treats every segment dated on or before `dayKey` as belonging to `dayKey` and adds it to the kept counters; only a segment in a day *after* `dayKey` triggers a reset.

Writer ownership: while the engine runs (armed), the engine is the **sole writer** of `used_ms_*`, `day_key` and `cursor_ts`. The UI applies the same rollover rule only for display (it shows 0 used when `today > dayKey`), without writing. The UI writes the reset to `Store` only when disarmed (`!DataGate.isOpen()`), on the main thread, which needs no query. That keeps the UI correct after midnight in both states with no concurrent writers.
3. **Events.** Feed events with `ts` in `[cursorTs, now]` through the foreground model. `accrualFloor = cursorTs`, so nothing before the cursor is ever accrued.
4. **Still open at query time.** For every package with `openSince != null`: `accrue(pkg, openSince, now)`, then `openSince = now`. The app that is open right now is therefore counted up to `now` on every tick.
5. `cursorTs = now`. The query window for the next tick starts at the new cursor, with a 2 s overlap for late-delivered events: **`begin = max(armedAtTs, cursorTs - 2000)`**. This one formula (`EngineCore.queryBegin()`) is used for every query: tick, screen-on re-sync and replay (the replay uses `max(armedAtTs, cursorTs - 6 h)`). No query ever starts before `armedAtTs`, so not even the overlap reads events from the disarmed period. Defence in depth: `UsageEventSource.read(begin, end)` returns `UsageRead.Refused` without calling the system if `begin < DataGate.armedAtTs()` (§10). Events with `ts < cursorTs` are applied to the foreground model only and are never accrued, so the overlap cannot double count.

Persistence cadence: the accumulator marks itself dirty. The engine persists `(dayKey, usedMs[*], cursorTs)` in one `apply()` when any of these happens:
- a session closes
- an app crosses remaining ≤ 0
- 15 s have passed since the last persist
- the screen turns off
- the user disarms
- `onDestroy` runs

Usage is never written more than once every 15 s during steady use.

Process death without double counting, and without loss:
- **Invariant:** time in `[a, b)` is added to `usedMs` only together with moving `cursorTs` from `a` to `b`, and both are persisted atomically. If the process dies after the last persist, the in-memory delta and the in-memory cursor are both lost, and the next run re-derives `[persistedCursor, now)` from the system's event log. That is exactly once.
- **Rebuilding foreground state after restart:** on `Engine.start()` with an existing cursor, i.e. a sticky restart while armed, the engine runs one replay query on `[max(armedAtTs, cursorTs - 6 h), cursorTs)` in **state-only** mode, with no accrual, to rebuild the `resumed` sets as of the cursor. Normal accrual then continues from `cursorTs`. The lookback never reaches before `armedAtTs`, so data from a disarmed period is never read. If an app was opened more than 6 h before the cursor and is still open, it is treated as closed until its next RESUMED. That undercounts, which is the fail-open direction.
- **On arm:** `cursorTs = armedAtTs = now`, with empty sets and no lookback query. The user is in FocusGate when tapping Enable, so no monitored app is in the foreground. Time while disarmed is therefore never counted (§10).

Screen off is a re-sync point. On `ACTION_SCREEN_OFF` the engine runs one final tick, which accrues open sessions up to `now`. It then closes all sessions without accruing further, clears the sets, persists, and stops polling. While the screen is off nothing is counted and nothing is queried. On `ACTION_SCREEN_ON`, the first tick queries `[max(armedAtTs, cursorTs - 2 s), now]`. PAUSED/STOPPED events from the screen-off period are ignored, because the sets are empty. The RESUMED that the top activity emits after unlock reopens its session. This bounds any drift from a lost STOPPED event (for example, a monitored app crashing in the foreground) to one screen-on period.

Remaining time: `remainingMs = allowanceMin * 60_000 - usedMs`, computed on every tick. When the allowance changes mid-day it takes effect immediately. The UI shows `ceil(remainingMs / 60_000)` clamped at 0 ("12 min left"), or "Time's up" when `remainingMs <= 0`. The allowance range is 0 to 600 min, the default is 30, and 0 means "always at limit".

## 5. Decision function

Pure Kotlin in `core/Decision.kt`:
```kotlin
enum class ZoneState { INSIDE, OUTSIDE, UNKNOWN }   // UNKNOWN = no zones / no permission / no fix / stale / inaccurate
enum class Decision { ALLOWED, BLOCKED }
enum class Reason { TIME_LEFT, LIMIT_INSIDE_ZONE, LIMIT_OUTSIDE_ZONE, LIMIT_LOCATION_UNKNOWN, ERROR_FAIL_OPEN }
data class Verdict(val decision: Decision, val reason: Reason)

fun decide(remainingMs: Long, zone: ZoneState): Verdict = when {
    remainingMs > 0            -> Verdict(ALLOWED, TIME_LEFT)
    zone == ZoneState.INSIDE   -> Verdict(BLOCKED, LIMIT_INSIDE_ZONE)
    zone == ZoneState.OUTSIDE  -> Verdict(ALLOWED, LIMIT_OUTSIDE_ZONE)
    else                       -> Verdict(ALLOWED, LIMIT_LOCATION_UNKNOWN)   // fail open
}

/** Fail-open wrapper used by the engine. Any Throwable from computing inputs or deciding returns ALLOWED. */
inline fun safeDecide(inputs: () -> Pair<Long, ZoneState>): Verdict =
    try { val (r, z) = inputs(); decide(r, z) } catch (t: Throwable) { Verdict(ALLOWED, ERROR_FAIL_OPEN) }
```
The engine calls the decision only for the current foreground monitored package, and only while armed. Disarmed means the engine does not exist (§10), so "disarmed → allowed" is structural rather than a table row. Zone state is global, meaning inside *any* zone, so leaving or entering changes the result for every app at its limit on the next tick. There is no latch and no "blocked for the rest of the day" state. The verdict is recomputed every tick from current inputs. That is what makes "leave → unblocked, return → blocked" work.

Decision table (each row is a unit test, `DecisionTest`):

| # | remaining | zone state | expected |
|---|---|---|---|
| 1 | 30 min | INSIDE (home) | ALLOWED (TIME_LEFT) |
| 2 | 30 min | OUTSIDE | ALLOWED (TIME_LEFT) |
| 3 | 0 | INSIDE (home) | **BLOCKED** (LIMIT_INSIDE_ZONE) |
| 4 | 0 | OUTSIDE | ALLOWED (LIMIT_OUTSIDE_ZONE) |
| 5 | 0, sequence INSIDE → OUTSIDE | | BLOCKED then ALLOWED (leave home → unblocked) |
| 6 | 0, sequence OUTSIDE → INSIDE | | ALLOWED then BLOCKED (return home → blocked) |
| 7 | 0 | UNKNOWN | ALLOWED (fail open) |
| 8 | negative (overrun) | INSIDE | BLOCKED |
| 9 | 1 ms | INSIDE | ALLOWED |
| 10 | inputs lambda throws | — | ALLOWED (ERROR_FAIL_OPEN) |

Rows 5 and 6 are also run end to end through `ZoneEvaluator` with N consistent synthetic readings, to prove the hysteresis output feeds the decision correctly.

## 6. Location zones & accuracy-aware hysteresis

**Zones.** `Zone(id: String /*UUID*/, name: String, lat: Double, lon: Double, radiusM: Int)` are stored in `Store` as one JSON array string, built with the platform `org.json`. Validation is done in `ZoneEditor` before saving, and `Store` re-checks it when loading:
- **Name:** required, trimmed to 1–24 characters. Invalid input shows an inline error and Save stays disabled.
- **Radius:** an integer from 3 to 200 m, default **5**. The slider snaps to 1 m, and a text field is clamped to the range.
- **Coordinates:** finite, with lat in [-90, 90] and lon in [-180, 180].
- **Count:** at most 10 zones.
- On load, invalid entries are dropped. If the JSON is corrupt, the zone list becomes empty, so location is never INSIDE and the app fails open. A "Zones couldn't be read" banner appears.

**Source.** `GpsSource` (the only location call site) uses Google Play Services' fused location when available (it may use Wi-Fi, mobile signals and Google's servers; AUTO falls back to platform GPS if fused gives no fresh fix within 20 s, or for 30 s) (`play-services-location` 21.3.0, the same service Google Maps uses): `FusedLocationProviderClient.requestLocationUpdates` with `PRIORITY_HIGH_ACCURACY`, interval 2000 ms, min interval 1000 ms, min distance 0, `GRANULARITY_FINE`, `waitForAccurateLocation(false)`, plus one `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` on start. If `GoogleApiAvailability` reports Play Services unavailable, or the request fails, it falls back to platform `LocationManager` `GPS_PROVIDER` at 1000 ms / 0 m. The platform `FUSED_PROVIDER` is only a GPS-test option. The location computation happens in Play Services' own process (`com.google.android.gms`); FocusGate has no internet permission and sends nothing. `lastLocation` / `getLastKnownLocation` are not used. **Freshness:** fix age is `SystemClock.elapsedRealtimeNanos() - Location.elapsedRealtimeNanos`; fixes older than 10 s are dropped, and an identical position re-delivered with the same or an older timestamp is ignored (`FixFreshness`, JVM-tested). Location is requested only when the engine's need-location predicate holds (armed, screen on, zones, precise permission, an app ≤ 2 min left) or while the zone setup / GPS test screens are open. Geofencing and `NETWORK_PROVIDER` are not used. If GPS is disabled (`isProviderEnabled == false`), or location permission is missing or only approximate, the zone state is `UNKNOWN`. The result is "fail open" plus a diagnostics entry, plus an Essentials status line: "Location is off, so apps stay open after time's up".

**Why ACCESS_FINE_LOCATION.** Android 12+ "approximate" (COARSE) location is deliberately coarsened to a grid of roughly 1.6–3 km, so a 3–200 m zone cannot be evaluated with it. The manifest declares both FINE and COARSE, which Android 12+ requires when requesting FINE. If the user grants only Approximate, FocusGate treats location as unavailable and shows "Precise location needed for zones".

**Why no ACCESS_BACKGROUND_LOCATION.** All location access happens either:
- (a) in a `location`-type foreground service that was started while FocusGate's activity was visible, which is Android's "while-in-use" access and is exempt from needing background location, or
- (b) in a UI-scoped session while the zone or GPS-test screen is open.

If the service is restarted from the background (sticky restart after the process was killed), it may not get while-in-use access (§7). In that case it runs without location (fail open) and posts "Open FocusGate to resume location checks". Asking for "Allow all the time" would add a much more invasive grant just to cover that edge case, so it is not requested.

**When sampling runs (monitoring).** The engine calls `GpsSource.start()` only when all of these hold:
- `DataGate.isOpen()`
- the screen is interactive
- at least one zone exists
- precise location is granted
- the FGS currently has the location type
- **some monitored app has `remainingMs <= 0`**

Sampling starts the moment the first app crosses 0, on the same tick. It stops (`removeUpdates`) when any of these becomes false, on screen off, and when the user disarms. There is no pre-warming.

**Per-reading classification** (`ZoneEvaluator.onReading(distanceToEachZoneM, accuracyM, nowMs)`, pure). The `Location` object is used only inside the callback to compute haversine distances to each zone centre (`core/Geo.kt`, a pure function). After that, only `(distances, accuracy)` reach the evaluator, and the `Location` reference is dropped.
- `maxAccuracyM(zone) = clamp(2 × radius, 10, 100)`. For the default 5 m radius this is 10 m.
- A reading is **usable** for a zone if `accuracy <= maxAccuracyM(zone)` and `accuracy > 0`.
- For each zone, `exitMargin = max(1.0, 0.2 × radius)` m. For 5 m this is 1 m.
- **INSIDE-candidate:** some zone is usable and has `d <= radius`.
- **OUTSIDE-candidate:** every zone is either usable with `d > radius + exitMargin`, or unusable but with `d - accuracy > radius + exitMargin`, meaning confidently outside even with a poor fix.
- **Ambiguous:** neither.
- **Unusable:** no zone is usable and the reading is not confidently outside.

**Hysteresis.** `N = 3` consecutive consistent readings, at roughly 1 Hz, so about 3 s.
- `committed: ZoneState` starts at `UNKNOWN`. `candidate` and `streak` are also kept.
- A reading whose class differs from `candidate` resets `streak = 1` with the new candidate. A matching class increments `streak`.
- Ambiguous readings reset `streak` to 0. Unusable readings neither count nor reset.
- `committed` flips to the candidate when `streak >= N`.
- The evaluator keeps no history beyond `(candidate, streak, lastUsableTs, committed, committedAt)`. Readings are discarded immediately, which is the "hysteresis window" and is exactly N booleans' worth of state.

**Fail-open thresholds.**
- If there has been no usable reading for **20 s** (`lastUsableTs` older than 20 s, or no usable reading in the first 20 s of a session), `committed` becomes `UNKNOWN`.
- Provider disabled or permission lost means `UNKNOWN` immediately.
- Any exception in the callback or evaluator also means `UNKNOWN` (logged nowhere, and counted in diagnostics as "location errors: n").
- UNKNOWN decides ALLOWED.

**Session carry-over.** When sampling stops (screen off, or the need-location predicate going false), the engine keeps `carried = committed` and `stoppedAt = now` in memory. When sampling restarts at `restartTs`:
- If `carried != UNKNOWN` and `restartTs - stoppedAt <= 120 s`, the evaluator starts with `committed = carried`, `candidate = carried`, `streak = 0` and **`lastUsableTs = restartTs`**. The carried state therefore holds for at most 20 s while fresh readings arrive, and after that the normal rules apply: N consistent readings confirm or flip it, and 20 s with no usable reading makes it UNKNOWN.
- Otherwise (more than 120 s, or carried UNKNOWN) it starts with `committed = UNKNOWN`, `streak = 0`, `lastUsableTs = restartTs`.
- Carry-over is cleared on disarm and when zones change.

This avoids a free window of a few seconds after every brief unlock without extending the staleness rule. Example: screen off 60 s while INSIDE → INSIDE is kept for up to 20 s with no readings, then UNKNOWN. Screen off 130 s → starts UNKNOWN.

**Decision feed.** On a zone flip the engine adds an entry such as "Inside a zone (3/3 readings, ±6 m)" or "Location unclear: allowing". It never includes coordinates or distances.

**"Use my current location" (zone centre).** `LocationSession` is UI-scoped: it starts when the user taps the button on the zone editor and stops `ON_STOP`, on Back, on Save or on Cancel. It requires `DataGate.isOpen()`, so FocusGate must be armed (§10). It requests GPS at 1 Hz. The UI shows live "±X m" accuracy and an "N/5 steady" counter, but never coordinates.

`ZoneSettler` (pure) declares the fix **settled** when all of these hold:
- at least 10 s have passed since the first fix
- the last 5 fixes each have accuracy ≤ 10 m
- their spread is ≤ max(3 m, mean accuracy), where spread is the maximum haversine distance of any of the 5 from their accuracy-weighted mean (weights 1/acc²)

The centre is that weighted mean.

If the fix has not settled within **90 s**, the UI says "GPS isn't settling (best ±X m). Go near a window, or save with a bigger radius". "Save anyway" is enabled only if the best 5-fix mean accuracy is ≤ 50 m, and the radius field is pre-set to `max(radius, ceil(bestAccuracy))`.

Coordinates exist only in that session's memory until Save writes the zone centre. Cancel discards them.

**Honesty copy.** This appears in the zone editor, the GPS test, the Privacy screen and §15: "Phone GPS is usually 3–10 m outdoors and worse indoors. With a 5 m zone, being near the edge can flip in and out. If accuracy shows more than ~10 m, use 10–15 m."

## 7. Foreground service

`FocusService` (`exported=false`, `foregroundServiceType="location|specialUse"`) exists only while armed.

**Starting it.** `ArmController.arm()` and `MainActivity.onResume()` (when armed but the service is not running) call `ContextCompat.startForegroundService(ctx, Intent(ACTION_START))`. Both happen while the activity is visible, which satisfies the Android 12+ background-FGS-start rule and Android 14's while-in-use rule for the `location` type.

**`onCreate`** does only three things, on the main thread: the unclean-restart check (`service_alive` marker, §11c), `ArmController.restoreOnProcessStart()`-equivalent BootCheck (a no-op if the Application already ran it this process), and `service_alive = true`. It creates **no engine** and queries nothing.

**`onStartCommand` dispatch** is implemented by the pure `core/ServiceStart.handle(action, port)` over a `ServicePort` interface (gate state, startForeground, engine factory, disarm, stop), so it is JVM-tested. `FocusService` is a thin adapter.

| Intent | Gate open? | Steps | Returns |
|---|---|---|---|
| `ACTION_PANIC` (from the notification, sent with `PendingIntent.getService` to the running FGS) | any | `ArmController.disarm(PANIC)` (main thread; closes the gate, commits, tears down the engine if present) → `stopForeground(STOP_FOREGROUND_REMOVE)` → `stopSelf()`. No engine is created. | `START_NOT_STICKY` |
| `ACTION_START` (sent with `startForegroundService` from a visible activity) | yes | `startForeground(types)` with the fallbacks below. **Only after it returns successfully**: if `engine == null`, `engine = Engine(...).also { it.start() }`; if an engine already exists, only widen the types and post an immediate tick (idempotent). | `START_STICKY` |
| `ACTION_START` | no (race with a disarm) | `startForeground` with the minimal "FocusGate is off" notification (required after `startForegroundService`), then `stopForeground(REMOVE)` and `stopSelf()`. No engine. | `START_NOT_STICKY` |
| `null` (sticky restart) | yes | record the unclean restart (at most once per instance, §11c); then same as `ACTION_START` | `START_STICKY` |
| `null` | no | `stopSelf()`. No engine. | `START_NOT_STICKY` |
| `ACTION_TEST_BLOCK` (from the Insights screen via `startService`, only when the service is running) | yes, and engine running | `engine.requestTestBlock()` (§13) | `START_STICKY` |
| `ACTION_TEST_BLOCK` | otherwise | ignored | unchanged |
| `ACTION_NOTIF_DISMISSED` (delete intent) | yes, engine running | `engine.flagNotificationRepost()` | `START_STICKY` |

If `startForeground` throws, the engine is never constructed, so no usage or location read can happen in a service that never became foreground (`ServiceStartTest`).

**Types per SDK** (`ServiceTypes.compute(sdkInt, locationGranted, hasZones)`, pure):
- API ≥ 34: `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, plus `or FOREGROUND_SERVICE_TYPE_LOCATION` if precise location is granted and at least one zone exists. Without location permission, the location type is not requested, because Android 14 throws `SecurityException` for a location FGS without a location permission.
- API 29–33: `FOREGROUND_SERVICE_TYPE_LOCATION` if precise location is granted and a zone exists, else `0`. `SPECIAL_USE` does not exist there. With `0`, the 2-argument `startForeground(id, notification)` is called; otherwise `ServiceCompat.startForeground(this, id, notification, types)`. This does not affect the API 34 target device but keeps minSdk 29 honest.
- **Promotion while foreground:** when the user grants location or adds the first zone in the UI, the activity sends `ACTION_START` again, and the service calls `startForeground` with the widened types. This is allowed because the app is visible at that moment.
- **Sticky restart** (`intent == null`, `START_STICKY` after the process was killed): try the full types. On `SecurityException`, retry with `SPECIAL_USE` only (API 34; `0` on 29–33) and set `locationDegraded = true`. The result is location `UNKNOWN`, which fails open, and the notification text reads "Open FocusGate to resume location checks". On `ForegroundServiceStartNotAllowedException` or any other exception, call `stopSelf()` without disarming, so the armed flag persists. The service is restarted from `MainActivity.onResume`. Tapping the "FocusGate paused" notification, if one can be posted (§11), also restarts it via the activity. These are not logged. The sticky restart is recorded for crash-loop detection at most once per service instance (§11c).

A stale sticky restart while disarmed (after a reboot was detected or after a disarm) stays inert: the gate is closed, so the dispatch table stops the service without creating an engine.

**Permissions (API 34)**:
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SPECIAL_USE`
- `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS`, requested at runtime during onboarding before arming. On 33+ an FGS without it runs with a hidden notification, so the panic action would be unreachable. Arming therefore requires it.

**Notification.**
- **Channel:** `status`, IMPORTANCE_LOW, no sound, no badge. A separate channel `alerts` (IMPORTANCE_DEFAULT) is used for the crash-loop and paused messages.
- **Content:** "FocusGate is on" with a short line such as "Instagram 12 min · Chrome 40 min · YouTube time's up". The text updates only when a displayed minute value or state changes, at most once every 10 s.
- **Lock screen:** `VISIBILITY_PRIVATE`, with a public version that reads only "FocusGate is on".
- **Content intent:** opens `MainActivity` (`PendingIntent.getActivity`, `FLAG_IMMUTABLE`).
- **Action:** **"Disable now"** uses `PendingIntent.getService(ctx, 1, Intent(ctx, FocusService::class.java).setAction(ACTION_PANIC), FLAG_IMMUTABLE)`. The service is already a running FGS and the user is interacting with the notification, so delivery is not restricted.
- `setOngoing(true)` and `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`.
- **Dismissal (Android 14).** On API 34 the user can swipe away an FGS notification despite `setOngoing(true)` (except on the lock screen), which removes the "Disable now" action. The notification sets `setDeleteIntent(PendingIntent.getService(ctx, 2, Intent(ACTION_NOTIF_DISMISSED), FLAG_IMMUTABLE))`. The service forwards it to the engine, which sets `repostNotification = true`; the next tick re-posts the same notification with `NotificationManager.notify(NOTIF_ID, ...)` and clears the flag. The in-app "Disable now" pill (§11b) remains the always-reachable panic control regardless.

**Screen on/off receiver.** The engine registers it at runtime in `Engine.start()` with `ContextCompat.registerReceiver(ctx, rx, IntentFilter(ACTION_SCREEN_ON, ACTION_SCREEN_OFF), null, engineHandler, ContextCompat.RECEIVER_NOT_EXPORTED)`. System broadcasts are still delivered to a not-exported receiver. It is unregistered in `Engine.stop()`. The initial state comes from `PowerManager.isInteractive`. There are no manifest receivers.

**Polling.** While the screen is interactive and the gate is open, `tick()` is posted every **1000 ms** (`postDelayed` on the engine handler and re-armed at the end of each tick). Screen off removes the callbacks and stops GPS. No wakelock is used. When the screen is off the engine is idle apart from the registered receiver. A zone flip or a location-permission change posts an immediate extra tick, so the result applies at once rather than up to 1 s later.

Tick, after `DataGate.isOpen()` (if closed: set `stopping = true`, schedule nothing and return; the gate only closes inside `ArmController.disarm`, which is already tearing the service down on the main thread, so the engine thread never calls `stopSelf` or `ArmController` itself, §3):
1. `UsageEventSource.read(queryBegin(), now)` with `queryBegin() = max(armedAtTs, cursorTs - 2000)` (§4) → `UsageAccumulator.advance(...)`. `read` returns `UsageRead.Ok(events)` (an empty list is a normal result), `UsageRead.Failed` (the system returned `null` or threw), or `UsageRead.Refused` (gate closed or `begin < armedAtTs`).
   - **On `Failed`, or any exception in this step:** first call `PermissionProbe.usageAccessAllowed()` (AppOps `unsafeCheckOpNoThrow`, not a data read). If it is not `MODE_ALLOWED`, return `RequestDisarm(PERMISSION_LOST)` without counting an error. Otherwise increment `consecutiveErrors`, **skip steps 2–3 for this tick** (no block, no GPS start/stop change), and publish the snapshot with `ALLOWED (ERROR_FAIL_OPEN)` for the foreground monitored package, if any.
   - **On `Refused`:** stop the tick (no accrual, no cursor move, no error count). With the gate closed this is followed by teardown.
   - **On `Ok`:** reset `consecutiveErrors = 0`.
2. Compute the per-app `remainingMs`, then evaluate the "need location" predicate (§6) and start or stop `GpsSource`.
3. If there is a foreground monitored package that passes the own-UI guard (§4), `safeDecide { remaining(pkg) to zoneEvaluator.state(now) }`. On BLOCKED, call `BlockLauncher.block(pkg)` (§8). Also run the pending block-launch verification and the pending "Test blocking" check (§8, §13).
4. Publish the snapshot. Update the notification if its text changed or `repostNotification` is set. Persist if due (§4). Add feed entries on per-app state changes only, not on every tick.
5. If `consecutiveErrors >= 10` (about 10 s of continuous failure), return `RequestDisarm(CRASH_LOOP)` **immediately**. This is a separate rule from the restart-based crash-loop guard in §11c and does not add an entry to it. The Android `Engine` handles every `RequestDisarm` by stopping its own scheduling and posting `ArmController.disarm(reason)` to the main thread (§3).

The AppOps permission check also runs every 60 s and on every `MainActivity.onResume`, with the same `PERMISSION_LOST` outcome.

## 8. Blocking

**Launch path.** `BlockLauncher.block(pkg)` runs on the engine thread from the service context:
1. **Debounce.** It skips if `BlockedActivity.isResumed`, a `@Volatile` static set in `onResume` and cleared in `onPause`, is true, or if the last launch was less than 1500 ms ago.
2. **Launch.** `ctx.startActivity(Intent(ctx, BlockedActivity::class.java).putExtra(EXTRA_APP_ID, appId).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_NO_ANIMATION))`. `appId` is the index in `MonitoredApps`, not a package string.
3. **Verify.** The launch records `pendingVerify = (pkg, launchedAt)`. The first regular tick at least 1500 ms later checks it using the foreground state that tick already derived; there is **no extra UsageEvents query**, so the verify path adds no call site and inherits the `max(armedAtTs, …)` window. If the package is still the foreground monitored package (and passes the own-UI guard) and `BlockedActivity.isResumed` is false, it falls back to `startActivity(Intent(ACTION_MAIN).addCategory(CATEGORY_HOME).addFlags(FLAG_ACTIVITY_NEW_TASK))`.
4. **If both fail to take effect.** This can be an exception, or no change after another 1500 ms. The engine sets `blockLaunchHealthy = false` and shows a diagnostic: "Pop-ups from background are blocked, so turn on 'Display pop-up windows while running in background'". It posts at most one `alerts` notification per hour saying "FocusGate can't open the block screen. Tap to fix". Tapping opens the Insights diagnostics.

**Android 14 background activity launch (BAL).** A foreground service alone does **not** exempt an app from the background-activity-start restrictions. Of the documented exemptions, the only one usable here without an AccessibilityService is **"the app has been granted SYSTEM_ALERT_WINDOW by the user"**. FocusGate therefore declares `SYSTEM_ALERT_WINDOW` and asks for it ("Display over other apps") during onboarding, with an honest explanation. It is **strongly recommended, not required** to arm: without it, `ArmController.arm()` succeeds with `blockLaunchHealthy = false`, and Home and diagnostics show "Blocking may not open without 'Display over other apps'. Test blocking will tell you." This keeps an A/B path open if GPay ever objects to the bare SAW grant (§17). **It is used only as the BAL exemption: FocusGate never adds an overlay window.** There are no `WindowManager.addView` or `TYPE_APPLICATION_OVERLAY` calls anywhere, and an audit grep checks for both. Sending Home is also an activity start, so it needs the same exemption.

Alternatives rejected:
- A full-screen-intent notification is limited to calling and alarm use on Android 14 and only takes over when the device is locked.
- `performGlobalAction(HOME)` needs an AccessibilityService, which is forbidden.
- A persistent overlay is forbidden.

On Funtouch OS there is a second, vendor-level gate: **"Display pop-up windows while running in background"**. Without it, vivo silently drops background starts even with SAW. It is surfaced in onboarding and diagnostics (§15) and checked with the "Test blocking" self-test.

**PendingIntent BAL opt-in.** None is needed. The Blocked screen and Home are started with a direct `Context.startActivity` from the service, not by sending a PendingIntent, so Android 14's sender opt-in (`ActivityOptions.setPendingIntentBackgroundActivityStartMode`) does not apply. FocusGate's PendingIntents are only:
- the notification content intent, an activity started by a user tap, which is always allowed
- the "Disable now" action, a service rather than an activity

Neither relies on BAL privileges.

**BlockedActivity.**
- Opaque and full-screen (edge-to-edge) with a big icon.
- Text: "Instagram's done for today here". A subline: "0 min left · You're inside a zone. Step outside or come back tomorrow."
- One primary button, **"Go home"**, which runs `startActivity(HOME)` and `finish()`.
- Back does the same as "Go home". There is no other button, no hidden gesture, no "5 more minutes" and no disable option on this screen. The way out is the app or the notification panic action, both visible.
- If the zone flips to OUTSIDE while it is showing, the screen updates to "You're outside your zones, so Instagram is open again" with a "Back to Instagram" button that launches the package via `packageManager.getLaunchIntentForPackage`. This is why `<queries>` is needed. The engine does not auto-close it, because the user chooses.
- `onResume` calls `markOwnUiShown(now)` (§4), updates `UiPresence`, and marks success for a pending "Test blocking" self-test (§13).
- `excludeFromRecents` and its own task affinity mean it never sits in Recents. If the user reopens Instagram from Recents, the next tick, within about 1 s, launches the Blocked screen again.

**Only while a blocked app is in the foreground.** The engine calls `block()` only for the current foreground monitored package, only when its verdict is BLOCKED, and only if a RESUMED for that package was seen after the last `markOwnUiShown` (§4). With complete events, `BlockedActivity` on top means the blocked app emitted PAUSED/STOPPED and is no longer foreground. With a **lost** STOPPED, `BlockedActivity.onResume` (and "Go home") calls `markOwnUiShown`, which closes the stale session; the package cannot be blocked again until it emits a new RESUMED, which only happens when the user really reopens it. So there is no relaunch loop over GPay or any other app, whether or not events are complete (`EngineCoreTest`: "lost STOPPED + Blocked shown → no relaunch, no further accrual"). Nothing is drawn over any other app, ever.

**Why GPay and banking apps are unaffected.** The old breakage came from an enabled AccessibilityService, which payment and banking apps detect and refuse to run with. FocusGate has:
- no AccessibilityService
- no overlay windows, so nothing obscures touches and `filterTouchesWhenObscured` and tapjacking checks never trip
- no VPN and no device admin
- no effect on any package other than the three monitored ones

The SAW *grant* itself is not a drawn overlay. Whether GPay inspects the bare SAW grant is verified on the device (§17).

## 9. State machine & transitions

**Arm state.** Persisted by `Store` via `ArmController` and mirrored into `DataGate`:
```
ArmState = Armed(bootCount: Int?, bootEpochMs: Long, armedAtTs: Long)
         | Disarmed(reason: USER | PANIC | REBOOT | CRASH_LOOP | PERMISSION_LOST | NEVER_ARMED)
```

| From | Event | To | Side effects |
|---|---|---|---|
| Disarmed(*) | User taps **Enable** with the required preconditions met: Usage Access and notifications. SAW is strongly recommended (without it: armed with `blockLaunchHealthy = false` and a warning, §8). Location is optional and only needed for zones. | Armed(current boot id) | `commit()` the armed flag, boot id, `armedAtTs = cursorTs = now`, and an empty crash-loop list. `DataGate.open(armedAtTs)`. Start the FGS from the activity. |
| Armed | User taps **Disable** in the app | Disarmed(USER) | `DataGate.close()` **first**, then `commit()`, then `stopService()` → engine teardown (§10) |
| Armed | **Panic** (in-app big button or notification action) | Disarmed(PANIC) | same as above, plus the in-app snackbar "FocusGate is off" |
| Armed | App process starts (`FocusGateApp.onCreate` → `ArmController.restoreOnProcessStart()`) or service onCreate, and `BootCheck` says rebooted | Disarmed(REBOOT) | gate stays closed. Home shows "FocusGate is off after restart. Tap Enable." `REBOOT` is persisted only if the stored state was Armed; an existing Disarmed reason is kept. |
| Armed | Crash loop: 3 unclean restarts in 10 min (§11c, detected in service `onCreate`, main thread) **or** 10 consecutive tick errors (§7 step 5, posted from the engine to the main thread) | Disarmed(CRASH_LOOP) | `alerts` notification and in-app banner "FocusGate turned itself off after repeated errors" |
| Armed | Usage Access revoked: AppOps check on every `MainActivity.onResume`, every 60 s in the engine, and on every failed usage read (§7 step 1). AppOps is a permission check, not a data read. | Disarmed(PERMISSION_LOST) | banner "Usage Access was turned off". Engine-detected cases are posted to the main thread (§3). |
| any | Day rollover | unchanged | counters reset (§4) |

Preconditions are re-checked in `ArmController.arm()` itself, not only in the UI. If any is missing, `arm()` returns `ArmResult.Missing(list)` and the UI routes to onboarding.

**Boot id** (`core/BootCheck.kt`, pure): `isSameBoot(stored: BootId, now: BootId): Boolean`.
- `BootId(bootCount: Int?, bootEpochMs: Long, elapsedAtSave: Long)`.
- `bootCount` comes from `Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT)` (API 24+), read in a try/catch. A `SettingNotFoundException` or `SecurityException` gives `null`.
- `bootEpochMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()`.

Rules, in order:
1. If both bootCounts are non-null: same boot iff they are equal.
2. Fallback: if `now.elapsed < stored.elapsedAtSave`, it is a new boot, because elapsedRealtime is monotonic within a boot.
3. Fallback: same boot iff `|now.bootEpochMs - stored.bootEpochMs| <= 120_000`. This tolerates NTP and small clock adjustments. A large manual clock change gives a false "rebooted", which disarms, and that is the safe direction.

`elapsedAtSave` is refreshed on every persist while armed. A missing or corrupt stored boot id means "not the same boot", so the app disarms. There is no `RECEIVE_BOOT_COMPLETED`. Nothing of FocusGate runs after a reboot until the user opens it, and sticky services do not survive reboots.

**Per-app state.** Recomputed every tick and never persisted. It drives the copy and the rings.
- `HAS_TIME`: remaining > 0, so "Instagram: 12 min left"
- `TIMES_UP_OUTSIDE`: allowed, so "Time's up · open here (outside zones)"
- `TIMES_UP_UNKNOWN`: allowed (fail open), so "Time's up · location unclear, staying open"
- `BLOCKED`: only meaningful while that app is in the foreground. Otherwise it is shown as "Time's up · blocked in zones".

**Zone state.** `UNKNOWN ⇄ INSIDE ⇄ OUTSIDE` via the N-reading hysteresis (§6). Any state goes to `UNKNOWN` on staleness (20 s), provider off, permission loss, sampling stop (subject to the 120 s carry-over) and disarm.

**Engine lifecycle.** `NotCreated → Running(screenOn|screenOff) → TornDown`. `Running` exists only between `Engine.start()`, which follows a gate check, and `Engine.stop()`.

## 10. Zero-data gate

**The single gate.**
```kotlin
object DataGate {                         // gate/DataGate.kt
    @Volatile private var armed = false
    @Volatile private var floorTs = Long.MAX_VALUE
    fun isOpen(): Boolean = armed         // THE gate function; nothing else decides data access
    fun armedAtTs(): Long = floorTs       // lower bound for every UsageEvents query (MAX_VALUE while closed)
    // Both writers are called only by ArmController, on the main thread:
    internal fun open(armedAt: Long) { floorTs = armedAt; armed = true }   // after commit()
    internal fun close() { armed = false; floorTs = Long.MAX_VALUE }        // BEFORE persisting/stopping
}
```
`FocusGateApp.onCreate` calls `ArmController.restoreOnProcessStart()`, which is the only process-start path into the gate. It loads the `Store` and runs `BootCheck`. If the stored state is Armed and it is the same boot, it calls `DataGate.open(storedArmedAtTs)`. If the stored state is Armed and it is a new boot, it persists `Disarmed(REBOOT)` and leaves the gate closed. If the stored state is already Disarmed, it keeps the existing reason and leaves the gate closed. That happens before any activity or service code runs.

**Every data call site sits behind it.** The privileged data APIs (`UsageStatsManager.queryEvents` and `LocationManager.requestLocationUpdates`) appear in exactly two files:
- `data/UsageEventSource.kt`
  - `read(begin, end): UsageRead`: the first line is `if (!DataGate.isOpen() || begin < DataGate.armedAtTs()) return UsageRead.Refused`. Only then does it call `context.getSystemService(UsageStatsManager::class.java)`. The manager is obtained lazily *inside* the gated function and is never cached in a field. A `null` result from `queryEvents` or any exception returns `UsageRead.Failed` (§7 step 1).
- `location/GpsSource.kt`
  - `start(listener)`: the first line is `if (!DataGate.isOpen()) return false`. It then calls `requestLocationUpdates`.
  - `onLocationChanged`: the first line is `if (!DataGate.isOpen()) { stop(); return }`. A fix that was already in flight is discarded unread.
  - `stop()`: `removeUpdates` is ungated, because it must always be able to tear down.
- `LocationSession`, used for zone setup and the GPS test, uses `GpsSource` and therefore goes through the same gate. It also observes `ArmController.state` and stops at once if the app disarms mid-session.

Usage Access *status* checks use `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, ...)`. That is a permission check, not a data read, so it is allowed while disarmed.

**Why the code paths are unreachable while disarmed.** These are structural guarantees, with the gate as defence in depth:
1. `UsageEventSource` and `GpsSource` instances are created only by `Engine`, apart from `LocationSession`'s `GpsSource`. The `Engine` is constructed only in `FocusService.onStartCommand` (`ACTION_START` or sticky `null`), only when `DataGate.isOpen()` is true, and only *after* `startForeground` returned successfully (§7 dispatch table). Otherwise the service stops and no engine is created. `onCreate` never creates it.
2. The UI never touches either source for usage. It displays `Store` counters and the engine's `StateFlow`, which goes empty when the engine is torn down.
3. The zone-setup and GPS-test buttons are disabled while disarmed, with the copy "Turn FocusGate on to use location". This literally satisfies "disarmed ⇒ no location sampling", and `GpsSource.start` refuses anyway.
4. The "Test blocking" self-test needs the running service, so it is armed-only too.

**Disarm teardown order** (`ArmController.disarm(reason)` runs on the main thread only; engine-initiated disarms are posted there, §3):
1. `DataGate.close()`, which takes effect immediately. Any tick already running on the engine thread sees a closed gate at its next gated call, and its results are not acted on, because each tick re-checks the gate before `BlockLauncher`.
2. `Store.commit(Disarmed(reason))`.
3. `context.stopService(FocusService)`. Its `onDestroy` posts `Engine.stop()` to the engine thread and then joins it with a timeout of at most 500 ms. On timeout the gate is already closed, so any late work is a no-op. `Engine.stop()` does the following:
   - remove the poll callbacks
   - `GpsSource.stop()`
   - `unregisterReceiver(screenRx)`
   - persist the counters already computed in memory (`apply()`), with no final query
   - clear the resumed sets, `openSince`, the zone evaluator, carry-over, the decision feed and the snapshot flow (set to `Idle`)
   - `quitSafely()` the HandlerThread
4. The service calls `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`, which removes the notification.

After a disarm the only usage data left is the per-app `usedMs` counter in `Store`.

**Audit hooks.** These are run in verification:
- `grep -rn "UsageStatsManager\|queryEvents\|queryUsageStats"` matches only `UsageEventSource.kt`.
- `grep -rn "LocationManager\|requestLocationUpdates\|getLastKnownLocation"` matches only `GpsSource.kt` and `Permissions.kt`. The latter is used only for `isLocationEnabled`, a settings-state check with no location data.
- Each matching function's first statement is the gate check.
- `DataGateTest`, a JVM test with fake sources, shows that with the gate closed `Engine.tick()` makes 0 calls to the fake usage and location sources, and that `GpsSource`-equivalent fakes drop in-flight fixes.

## 11. Failsafe

**(a) Reboot.** The armed flag and boot id are checked in `BootCheck` (§9) on every process start and in `FocusService.onCreate`. A mismatch gives `Disarmed(REBOOT)`. There is no `RECEIVE_BOOT_COMPLETED`, so the app is inert after a reboot until the user opens it and taps Enable.

**(b) Panic.**
- A large red **"Disable now"** pill sits in the persistent bottom area of `MainActivity`'s scaffold. It shows on every screen while armed, has a 56 dp height and a power icon, needs no confirmation, and runs `disarm(PANIC)` on a single tap.
- The FGS notification has the "Disable now" action.
- While disarmed, the same spot shows a green "Enable" pill.

**(c) Crash loop.** `CrashLoopGuard` (pure) is given a list of restart timestamps persisted in `Store` (at most 5 longs) and `now`. It returns `tripped` if **≥ 3 unclean restarts are recorded within 10 min**.

An "unclean restart" is recorded **at most once per service instance**, guarded by an in-memory `instanceRecorded` flag on the `FocusService` object. Either of these signals records it, and the second signal on the same instance is ignored:
- `FocusService.onCreate` when the persisted `service_alive` marker is still `true`, meaning the previous instance never reached `onDestroy` (OEM kill, process crash)
- the first `onStartCommand` of the instance with a `null` intent, which is a sticky restart

So one OEM kill followed by a sticky restart, which produces both signals, is exactly **one** entry. Two Funtouch "clear all" swipes in 10 minutes are two entries and do not trip the guard; a third does. The marker is set `true` in `onCreate` and `false` in `onDestroy`. A user arm clears the list.

The guard is evaluated in `onCreate` / `onStartCommand` on the main thread, so it calls `ArmController.disarm(CRASH_LOOP)` directly, then stops the service without creating an engine. The separate **error-streak rule** (10 consecutive tick errors, §7 step 5) is an immediate `disarm(CRASH_LOOP)` posted from the engine thread to the main thread; it does not add a restart entry.

On a `CRASH_LOOP` disarm FocusGate posts an `alerts` notification "FocusGate turned itself off after repeated errors. Open to turn it back on." if notifications are allowed, and shows a persistent in-app banner until it is dismissed. There is no `Application`-level uncaught-exception handler: an uncaught exception kills the process, the `service_alive` marker stays `true`, and the next service instance records the single entry. No stack trace or message is ever stored.

**(d) Fail open in the decision path.** `safeDecide` catches every Throwable (§5). Each tick body runs in a try/catch, and an exception skips enforcement for that tick. `BlockLauncher` catches `ActivityNotFoundException`, `SecurityException` and `RuntimeException`. Each failure stays local, and nothing blocks when in doubt.

**(e) Location unavailable leads to allow.** The zone state becomes `UNKNOWN` (§6) and the decision is ALLOWED (row 7).

If a sticky restart cannot become foreground (§7), the service stops itself while staying armed. If notifications are allowed, it posts an `alerts` notification "FocusGate paused. Tap to resume". The tap opens `MainActivity`, whose `onResume` restarts the FGS. The OEM killing the service is the same as fail open. The armed flag stays set, and the next app open restarts the service.

## 12. Privacy & storage

**Stored.** One private SharedPreferences file, `fg_store.xml`, in credential-encrypted app storage. It holds exactly these keys:

| Key | Type | Purpose |
|---|---|---|
| `allowance_min_<appId>` | Int ×3 | user allowance |
| `used_ms_<appId>` | Long ×3 | today's usage counter (single value per app) |
| `day_key` | Long | local epoch day the counters belong to |
| `cursor_ts` | Long | accrual cursor (bookkeeping timestamp, no package or usage content) |
| `zones` | String (JSON) | name, centre, radius per zone, which are the only coordinates ever stored |
| `armed`, `disarm_reason` | Boolean, String | arm state |
| `boot_count`, `boot_epoch_ms`, `elapsed_at_save`, `armed_at_ts` | Int?/Long | boot id (§9) |
| `restart_ts` | String (≤5 longs) | crash-loop guard |
| `service_alive` | Boolean | unclean-exit marker |
| `ui_mode` | String | ESSENTIALS / INSIGHTS |
| `theme` | String | SYSTEM / LIGHT / DARK |
| `feed_visible` | Boolean | Decision Feed toggle (default false) |
| `onboarding_done`, `crash_banner` | Boolean | UI flags |

Not stored: events, sessions, per-hour data, history, location readings, accuracy history, foreground-app history and the decision feed. The feed is an in-memory `ArrayDeque` capped at 50 entries. Entries are `(timeOfDay, appId, short message)` with no coordinates or distances, and the feed is cleared on disarm and lost on process death.

**Backup.** The manifest sets `android:allowBackup="false"`, `android:fullBackupContent="@xml/backup_rules"` and `android:dataExtractionRules="@xml/data_extraction_rules"`. Both rule files exclude every domain:
- `backup_rules`: `<exclude domain="root|file|database|sharedpref|external" path="."/>`
- `data_extraction_rules`: the same domains inside both `<cloud-backup>` and `<device-transfer>`

**Exported surface.**
- Only `MainActivity` is exported, through its launcher filter.
- `BlockedActivity` and `FocusService` are `exported="false"`.
- There are no receivers or providers in the merged manifest, because the transitive ones are removed (§3).
- There are no deep links, no WebView (grep for `WebView`), no sockets or servers (grep for `ServerSocket` and `java.net`) and no `INTERNET`.
- `androidx.core` adds a signature-level `app.focusgate.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` to the merged manifest. That is expected and harmless, and it is documented in REPORT.

**No logging.** `src/main` has zero calls to `android.util.Log`, `println`, `printStackTrace` or `System.out`/`System.err`. A grep and an expected-zero count enforce this. There are no logging wrappers, so no debug build can leak coordinates, usage values or package details. Exception messages that the code constructs never include such values. If the app crashes, the platform `AndroidRuntime` crash log contains only the stack trace, a residual that is noted in REPORT.

**Not collected.** No screenshots, screen recording, keystrokes, notification listener, clipboard, other-app content, contacts, accounts or credentials. No analytics, crash reporting, ads or Firebase. Dependencies are limited to AndroidX, Compose, Kotlin and `play-services-location` (fused location only, §6), plus JUnit for tests only. No downloadable fonts, because the GMS font provider is not used.

## 13. UI/UX

**Shell.**
- One `MainActivity` with `enableEdgeToEdge()`, portrait, Material3 and Material icons (extended).
- Navigation is a simple in-app back stack: `rememberSaveable` of a `Screen` sealed class, with `BackHandler`. There is no navigation library.
- Top bar: the title, an **Essentials / Insights** segmented toggle (default Essentials, persisted) and the Settings icon.
- Bottom: the persistent Enable / Disable-now pill (§11).
- **Theme:** a custom palette with a violet primary and a lime accent, rounded 24 dp cards and generous spacing. The switch is System / Light / Dark. Dynamic color is not used, to keep the look consistent.
- Short friendly copy, sentence case.
- **Accessibility:** a `contentDescription` on every icon-only button, 48 dp minimum touch targets, text that scales with the user's font size, and status never conveyed by colour alone (always text plus icon).

**Essentials (default).**
- **Home:** a status card with a big shield icon, "FocusGate is on" or "off" and one plain line ("Instagram: 12 min left"). Below it is one card per monitored app with a progress ring showing used/allowance, the app label and the remaining time ("Time's up · blocked at Home"). The labels are fixed strings in `MonitoredApps` (Instagram, Chrome, YouTube). An app that is not installed is resolved via `<queries>` and shown as "not installed".
- **Allowances:** a per-app minutes stepper and slider (0–600, default 30).
- **Zones:** a list (name, radius) with delete (confirmation dialog). Add opens the **Zone editor**: name, a radius slider from 3 to 200 with a 5 m default and a numeric field, "Use my current location" (live ±m and "steady 3/5", §6), the honesty note and Save.
- Nothing technical appears in this mode.

**Insights (Essentials plus the following).**
- **Live status:** zone state (Inside, Outside or Unclear) and per-app state (§9).
- **Decision Feed:** a Show/Hide switch, *hidden by default*, listing the last ≤ 50 in-memory entries ("14:02 · Instagram · blocked: inside a zone"). It never shows coordinates.
- **Diagnostics:**
  - permission health rows, each with a status icon and a **Fix** button: Usage Access, Notifications, Location (precise), Display over other apps, Battery (unrestricted), Autostart, background pop-ups, high background power (§15)
  - "Service running", "Armed since" (time only) and "Restart status: same boot / disarmed after restart"
  - block-launch health and location-degraded flags
- **Self-test 1, "Test blocking":** armed only. It must test a genuine *background* launch, so it avoids two false passes: a launch while FocusGate is still on screen (a foreground launch), and a launch inside AOSP's ~10 s background-activity-start grace period after a FocusGate activity was resumed or finished.
  1. Tapping it sends `ACTION_TEST_BLOCK` to the service. The engine enters `TestPending(armedAt)`, and the UI says "Switch to another app now (not a payment app). The test runs a few seconds after you leave."
  2. The countdown starts only at `MainActivity.onStop`, which calls `engine.onOwnUiStopped()` and records `leftAt`.
  3. Each tick checks the pending test. It fires only when **all** hold: `UiPresence.mainResumed == false`, `now - leftAt >= 5 s` (the spec's 5 s delay, as a minimum), and `elapsedRealtime - UiPresence.lastOwnActivityEventElapsed >= 11 s`. It keeps waiting while these are not met, for up to 60 s after `leftAt`.
  4. On firing, `BlockLauncher` launches `BlockedActivity` in test mode, showing "Test worked. Pop-ups from background are allowed. Waited N s after you left FocusGate." and a "Done" button that just finishes it, returning to the previous app.
  5. **Failed:** `BlockedActivity` has not resumed within 3 s of the launch, or the launch threw. The next app open shows "Test failed: enable 'Display pop-up windows while running in background' and 'Display over other apps'" with Fix buttons.
  6. **Inconclusive:** the user returns to FocusGate before it fires, the screen turns off, or 60 s pass without the conditions holding. The result shown is "Inconclusive: switch to another app and wait".
  7. The result is kept in memory only. Test mode does not depend on any app's usage or zone state, and is the only case where the Blocked screen can appear over a non-monitored app, because the user asked for it.

  The definitive check remains a real block after swiping FocusGate from Recents (§16, §17).
- **Self-test 2, "GPS accuracy":** a full screen, armed only. A `LocationSession` runs only while this screen is resumed (started `ON_START`, stopped `ON_STOP` and on leave). It shows:
  - big live "±6 m"
  - Inside, Outside or Unclear for the nearest zone, via its own `ZoneEvaluator`
  - "Consecutive: 2/3"
  - seconds since the last fix
  - the hint "Let it settle ~1 min. If it stays above 10 m, use a 10–15 m zone."

  It shows no coordinates and no distances, and stores nothing. This is the user's tool for checking 5 m accuracy (walk test in REPORT).

**Privacy screen** (reached from Settings and onboarding): exactly the §12 table in plain language, each permission from §14 with its reason, and "FocusGate has no internet permission and never sends your data anywhere", followed by a note that Google Play Services' location service (used to find the position) may use GPS, Wi-Fi, mobile signals and Google's servers, and FocusGate only receives the result.

**Onboarding.** This runs on first Enable, not at first launch. Nothing is requested at first launch, which shows a welcome card and the Enable pill. Tapping Enable while preconditions are missing opens a step list. Each step has an honest explanation and **one button**, and steps tick off as they are granted (re-checked `ON_RESUME`):
1. **Usage Access:** "Android calls this a system-wide permission. FocusGate only reads Instagram, Chrome and YouTube, only while it's on, and has no internet, so the data can't leave your phone." Opens `Settings.ACTION_USAGE_ACCESS_SETTINGS`.
2. **Notifications:** "Needed for the always-on 'FocusGate is on' notice with a Disable button." Runtime `POST_NOTIFICATIONS`, falling back to `ACTION_APP_NOTIFICATION_SETTINGS` if it is permanently denied.
3. **Display over other apps:** "Lets FocusGate open its block screen when a timed-out app opens. It never draws over other apps." Opens `ACTION_MANAGE_OVERLAY_PERMISSION` with `package:` data.
4. **Background pop-ups (iQOO):** the vivo deep link (§15). This step cannot be detected, so the user confirms it manually, and "Test blocking" verifies it after arming.
5. **Battery / Autostart (iQOO)** (§15), with manual confirmation.
6. **Location (optional, only when adding the first zone):** "Precise location tells FocusGate if you're inside a zone. Only used when an app's time is up and your screen is on." Runtime FINE+COARSE, with an approximate-only grant handled as in §6.

Required for arming: steps 1–2. Step 3 is strongly recommended: the user can skip it with a clear warning ("Blocking may not open without this"), and FocusGate arms with `blockLaunchHealthy = false` (§8). Steps 4–5 are recommended, with warnings. Step 6 is needed only for zones.

## 14. Permission list

| Permission | Type | Why | Requested when |
|---|---|---|---|
| `PACKAGE_USAGE_STATS` (`tools:ignore="ProtectedPermissions"`) | special app access (Usage Access) | Detects which of the 3 monitored apps is in the foreground and counts their minutes via UsageEvents. Read only while armed, and only events of the 3 packages are processed. | onboarding step 1 |
| `POST_NOTIFICATIONS` | runtime (33+) | Visible FGS notification with the "Disable now" panic action, plus crash-loop alerts | onboarding step 2 |
| `FOREGROUND_SERVICE` | normal | Runs the monitor as a foreground service | install-time |
| `FOREGROUND_SERVICE_SPECIAL_USE` | normal (34+) | FGS type for usage monitoring (no standard type fits) | install-time |
| `FOREGROUND_SERVICE_LOCATION` | normal (34+) | FGS type required to read location from the service with while-in-use access | install-time |
| `SYSTEM_ALERT_WINDOW` | special app access | **Only** the Android 10+ background-activity-start exemption, so the service can open the Blocked screen or send Home. No overlay window is ever drawn. Strongly recommended, not required to arm (§8). | onboarding step 3 |
| `ACCESS_FINE_LOCATION` | runtime | Precise location to evaluate 3–200 m zones (coarse is about 2 km) | when adding the first zone |
| `ACCESS_COARSE_LOCATION` | runtime | Must be requested together with FINE on 31+. Not useful on its own. | same dialog |

Deliberately **absent**, with a check in the merged manifest and aapt2: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_BACKGROUND_LOCATION`, `RECEIVE_BOOT_COMPLETED`, `QUERY_ALL_PACKAGES`, `BIND_ACCESSIBILITY_SERVICE` (and no accessibility service), `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the list screen `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` is used instead, which needs no permission), and `USE_FULL_SCREEN_INTENT`.

Library-added and expected: `app.focusgate.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature, from androidx.core). From Play Services (location source, §6): the non-exported `com.google.android.gms.common.api.GoogleApiActivity` and the `com.google.android.gms.version` meta-data. `INTERNET` and `ACCESS_NETWORK_STATE` carry `tools:node="remove"` in the app manifest so no library can add them; FocusGate itself has no internet permission, and location is computed by Play Services (the same service Google Maps uses), which may use Wi-Fi, mobile signals and Google's servers.

`<queries>` contains three `<package>` entries, for `com.instagram.android`, `com.android.chrome` and `com.google.android.youtube`. It exists only for installed checks, labels and the "Back to app" launch intent.

## 15. Android 14 + Funtouch OS 14 limitations

These are stated honestly in-app (Privacy and Insights) and in REPORT.

- **GPS accuracy at 5 m.** Phone GPS is typically 3–10 m outdoors and often 10–30 m or unavailable indoors. At a 5 m radius the edge flickers. Hysteresis (N=3, 1 m exit band) damps this, and readings above 10 m are ignored. When readings are unusable for 20 s the app fails open, so **deep indoors at home a timed-out app may stay open**. The fix is a 10–15 m radius, or placing the centre where GPS is good, near a window. The GPS accuracy self-test shows which applies.
- **Location latency.** When an app hits 0 inside a zone, GPS has to acquire a fix (cold start indoors can take 30 s or more) plus about 3 s of hysteresis before blocking starts. During that time the app is allowed (fail open). The 120 s carry-over avoids repeating this after every brief screen-off.
- **~1 s polling latency.** A blocked app can be visible for up to about 1–2 s, which is the poll interval plus the activity launch, before the Blocked screen covers it. That happens after opening it from Recents or after unlocking.
- **Force-stop bypass.** Force-stopping FocusGate, revoking Usage Access, overlay or location, or uninstalling it stops enforcement. This is by design, since the failsafe takes priority over tamper resistance. Revoking Usage Access is detected and disarms with a message. A force-stop leaves the state armed with the service gone, and the next app open restarts it.
- **Dismissible FGS notification.** On Android 14 the user can swipe away the "FocusGate is on" notification (except on the lock screen), which hides its "Disable now" action. FocusGate re-posts it on the next tick via its delete intent (§7), and the in-app "Disable now" pill is always reachable.
- **Multi-instance activities undercount.** Usage sessions are keyed by activity class name, so two simultaneous instances of the same activity (for example Chrome in multi-window) collapse into one key, and the first PAUSED ends the session early. Similarly, running FocusGate side by side with a monitored app in split screen makes that app count as closed until its next RESUMED (§4 own-UI reset). Both undercount, which is the fail-open direction, and are accepted.
- **OEM background kills.** Funtouch can kill the process, especially after "clear all" in Recents, even with an FGS. A `START_STICKY` restart may be delayed or suppressed. After a restart from the background the location type may be unavailable, which degrades to fail open with the "Open FocusGate to resume location checks" notification. Mitigation is to ask the user to allow Autostart, set background power to "Allow high background power consumption" or unrestricted, set battery optimisation to "Don't optimise", and lock FocusGate in Recents (manual instruction, no API).
- **Funtouch settings deep links** (`OemSettings.kt`). Each is tried in order with `startActivity` in a try/catch for `ActivityNotFoundException` and `SecurityException`, and the first success wins. No `<queries>` entries are added for these packages, because `startActivity` of an explicit component does not need package visibility. The component names are **unverified on this device** (§17):
  - **Autostart:** `com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`, then `com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager`, then `com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity`, then fallback app details (`ACTION_APPLICATION_DETAILS_SETTINGS`, `package:app.focusgate`).
  - **Display pop-up windows while running in background:** `com.vivo.permissionmanager/.activity.SoftPermissionDetailActivity` with extra `packagename=app.focusgate`, then fallback app details. In-app instruction text: "App info → Permissions → Display pop-up windows while running in background → Allow".
  - **Background power:** `com.vivo.abe/.unifiedpower.HighPowerDetailActivity`(?), then `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, then app details. Instruction: "Battery → Background power consumption management → FocusGate → Allow high background power consumption".
  - The final fallback is always `Settings.ACTION_SETTINGS`. These states cannot be detected, so diagnostics show "Can't check, tap to open" plus the result of the "Test blocking" self-test.
- **Approximate location** is handled as unavailable (§6). **GPS off** is shown as a status, and the app fails open.

## 16. Test strategy

**JVM unit tests** (`app/src/test/java/app/focusgate/core/`, JUnit 4.13.2). All of them cover `core/` pure code with an injected `ZoneId` and clock.
- `DecisionTest`: table rows 1–10 (§5), including the required six scenarios by name: `thirtyLeftHome_allowed`, `thirtyLeftOutside_allowed`, `zeroHome_blocked`, `zeroOutside_allowed`, `zeroLeaveHome_unblocked`, `zeroReturnHome_blocked`.
- `UsageAccumulatorTest`:
  - a single session's open and close
  - still open at query time (accrues to `now` each tick, and the sum over ticks equals wall time)
  - multi-activity handoff (A paused, B resumed)
  - two apps in sequence
  - **rollover**: a session from 23:50 to 00:20 gives 20 min today and resets the counters, and a no-event tick after midnight resets them too
  - DST day in `Europe/Berlin` (a fixed `ZoneId`)
  - **process death**: persist, then a simulated crash with un-persisted ticks, then a fresh accumulator from the persisted state, then a replay with a lookback in state-only mode. The total equals the ground truth exactly, with no double count and no loss.
  - a 2 s overlap re-feeding the same events causes no double count
  - clock moved backwards
  - screen-off close and re-sync
  - events before `armedAtTs` are never accrued
  - **backward time-zone change**: `today < dayKey` keeps the counters and `dayKey`; only `today > dayKey` resets
  - `markOwnUiShown(ts)` closes sessions whose latest RESUMED is before `ts`, and a re-delivered RESUMED with `ts <= ownUiShownTs` does not reopen them
- `EngineCoreTest` (fake `UsageReader`, `LocationReader`, `Blocker`, `PermissionProbe`, `Clock`):
  - **lost STOPPED + Blocked shown → no relaunch, no further accrual**: Instagram RESUMED with no close, limit reached inside a zone, one block, then `markOwnUiShown`; the following ticks (including ones that re-deliver the old RESUMED in the overlap) make zero further `block()` calls and add zero ms; a new RESUMED blocks again
  - **query floor**: the fake reader records every `begin`; across arm, first tick, steady ticks, screen-off/on re-sync, block verify and sticky-restart replay, every `begin >= armedAtTs`, and no extra query is made for the verify
  - read `Failed` with Usage Access still allowed → steps 2–3 skipped, `ALLOWED (ERROR_FAIL_OPEN)` published, error counted; 10 in a row → `RequestDisarm(CRASH_LOOP)`
  - read `Failed` with Usage Access revoked → `RequestDisarm(PERMISSION_LOST)` and no error counted
  - "Test blocking": does not fire while `mainResumed`, before 5 s after `leftAt`, or within 11 s of the last own-activity event; fires once all hold; returning first → Inconclusive; 60 s → Inconclusive
  - notification delete intent → re-post on the next tick
- `ServiceStartTest` (fake `ServicePort`):
  - `ACTION_START` with gate open and `startForeground` throwing → engine factory never invoked, so zero usage reads
  - `ACTION_START` with gate closed → minimal startForeground, stop, no engine
  - `ACTION_PANIC` → disarm(PANIC), stopForeground(REMOVE), stop, `START_NOT_STICKY`, no engine
  - `null` intent with gate open → one unclean-restart entry, then start; `null` with gate closed → stop
  - `ACTION_TEST_BLOCK` without an engine → ignored
  - a second `ACTION_START` with a running engine → no second engine
- `ServiceTypesTest`: API 34 with and without location; API 29–33 gives `LOCATION` or `0`, never `SPECIAL_USE`
- `BootCheckTest`:
  - same count, same boot
  - different count, rebooted
  - null counts with elapsed decreased, rebooted
  - null counts with epoch drift ≤ 120 s, same, and > 120 s, rebooted
  - corrupt or missing stored value, rebooted
- `ZoneEvaluatorTest`:
  - N−1 readings don't flip, the Nth does
  - an ambiguous reading resets the streak
  - unusable readings are ignored
  - 20 s staleness gives UNKNOWN
  - confident-outside with a poor fix
  - multiple zones (any inside)
  - carry-over: screen off 60 s while INSIDE → restarts INSIDE with `lastUsableTs = restartTs`, kept ≤ 20 s with no readings, then UNKNOWN; N fresh OUTSIDE readings flip it normally
  - carry-over: screen off 130 s → starts UNKNOWN
- `ZoneSettlerTest`: settles, does not settle when the spread is large, times out, and computes the weighted mean.
- `CrashLoopGuardTest`: 3 within 10 min trips, 3 over 11 min doesn't, arming clears the list, and one kill that produces both signals (`service_alive` still true *and* a `null` intent) on the same instance records exactly 1 entry, so two such kills do not trip.
- `ArmControllerLogicTest` (pure restore logic): stored Armed + new boot → persist `REBOOT`; stored Disarmed(PANIC) + new boot → reason stays `PANIC`; stored Armed + same boot → gate opened with the stored `armedAtTs`.
- `DataGateTest`: an `Engine` core with fake `UsageReader` and `LocationReader` interfaces makes zero calls while the gate is closed. In-flight fixes are dropped once the gate closes, and teardown clears state. To make this testable, `Engine`'s tick logic sits in a pure `EngineCore` class that receives the interfaces. The Android `Engine` wraps it with the Handler, receiver and service.
- `MonitoredAppsTest`: the filter accepts only the 3 packages.

**Build and static verification** (by execution, in IMPLEMENT/REVIEW):
1. `.\gradlew.bat assembleDebug lintDebug testDebugUnitTest` with JAVA_HOME set to JDK 21. All must succeed, and the reports go in `app/build/reports/`.
2. Merged manifest: find `AndroidManifest.xml` under `app/build/intermediates/merged_manifest*/debug/`. Then assert:
   - there is no `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_BACKGROUND_LOCATION` or `RECEIVE_BOOT_COMPLETED`
   - there is no `<provider>`, no `<receiver>` and no `accessibilityservice`
   - every activity and service is `exported="false"` except `MainActivity`
   - `allowBackup="false"`
   - there are exactly 3 `<package>` queries
3. `aapt2 dump permissions app-debug.apk` and `aapt2 dump xmltree --file AndroidManifest.xml app-debug.apk` (build-tools 36.0.0) give the same assertions against the APK.
4. Greps over `app/src/main` (expected-zero or allow-list):
   - `Log\.`, `println`, `printStackTrace`, `System.out`, `System.err` must give 0.
   - The `UsageStatsManager` and `LocationManager` call sites must match only the files allowed in §10.
   - `WebView`, `ServerSocket`, `java.net`, `addView`, `TYPE_APPLICATION_OVERLAY`, `AccessibilityService`, `firebase`, `gms` must give 0.
   - `import android` in `core/` must give 0.
5. Dependency check: `.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath` shows only `androidx.*`, `org.jetbrains.*` and `com.google.guava:listenablefuture`. That last one is the empty stub pulled in by AndroidX and is acceptable. There is nothing from `com.google.android.gms` or `com.google.firebase`.

**Manual phone tests** (listed in REPORT):
- GPay opens and pays with FocusGate armed.
- "Test blocking" works, including after swiping FocusGate from Recents and after a screen-off. Switch to a non-payment app and wait; the result must say "Waited N s" with N ≥ 11.
- Definitive background-launch check: swipe FocusGate from Recents, then open a timed-out app inside a zone; the Blocked screen must appear.
- If GPay fails with FocusGate armed: revoke only "Display over other apps", keep FocusGate armed, and retest GPay; record the result.
- Real block: set Instagram to 1 min, use it up at the Home zone, and it is blocked. Walk out and it is allowed, walk back and it is blocked.
- GPS walk test at the desk: let it settle for about 1 min, watch the accuracy, walk 5–6 m away and back, near the window and deeper in the room. If accuracy is above about 10 m, use a 10–15 m radius.
- Reboot: confirm FocusGate is disarmed until Enable.
- Disable: confirm there is no notification, no running service (`adb shell dumpsys activity services app.focusgate` is empty) and no location indicator.
- Midnight rollover, observed the next morning (counters back to 0).

## 17. Open questions / verify on device

1. **Funtouch deep-link components** (§15) are unverified on the I2017. Confirm which ones open, and keep the fallbacks.
2. **Background pop-up gate.** Confirm that vivo's "Display pop-up windows while running in background", together with SAW, lets `startActivity` from the FGS succeed, including after swiping from Recents. Use the "Test blocking" self-test.
3. **Sticky restart behaviour on Funtouch.** Is the FGS restarted after an OEM kill, and does `startForeground` with the `location` type throw `SecurityException` on that restart (which degrades to specialUse)? Watch the Insights diagnostics.
4. **GPay with the bare SAW grant and an FGS running.** Expected to be fine, since no overlay or accessibility service is involved. This is the main manual acceptance test.
5. **`Settings.Global.BOOT_COUNT` readability** on Funtouch. The fallback covers it, and diagnostics show which method is in use.
6. **Indoor GPS at home.** If fixes never get under 10 m at the user's desk, the fail-open rule means no blocking there. The user may need a 10–15 m radius. To confirm with the GPS test.
7. **UsageEvents on Funtouch** emit `ACTIVITY_RESUMED/PAUSED/STOPPED` for the 3 apps (including YouTube PiP and Chrome Custom Tabs, which are attributed to the host app). Verify via the Insights per-app state.
8. **Lint on AGP 8.7.3.** `app/lint.xml` already ignores `ExpiredTargetSdkVersion`, `ExpiringTargetSdkVersion` and `OldTargetApi` (§2). `LockedOrientationActivity` should be a warning only. If another check is fatal, suppress it narrowly in `lint.xml` with a reason, and list it in REPORT.
9. **"Test blocking" vs the BAL grace period on Funtouch.** The self-test waits ≥ 11 s after FocusGate's last activity event to stay outside AOSP's ~10 s grace period. Funtouch may use a different window. The definitive check is a real block after a Recents swipe (§16).
10. **GPay and the bare SAW grant.** If GPay refuses to work while FocusGate is armed, revoke only SAW and retest (SAW is not required to arm, §8). This tells whether the SAW grant or something else is the cause.
11. **KGP 2.0.21 with Gradle 8.11.1** is outside KGP's fully tested range. If the build fails on that pairing, fall back to Gradle 8.9 with all other pins unchanged.
12. **Stopping before `startForeground` on a sticky restart.** On a `null`-intent restart with the gate closed, the service calls `stopSelf()` without `startForeground`. This is expected to be fine because a sticky restart is not a `startForegroundService` call. Confirm on the device that it causes no "did not call startForeground" crash. If it does, apply the same minimal-notification-then-stop path used for `ACTION_START`.
13. **Compose text with `InitializationProvider` removed.** EmojiCompat is not initialised and is expected to fall back gracefully. Confirm at first run that text renders and there is no startup crash.

## Revision notes

These respond to `.agents/tasks/design-review.md` (iteration 1). All 18 findings are addressed; none is backlogged or ignored.

| Finding | Resolution | Where |
|---|---|---|
| M1 stale foreground relaunches Blocked over GPay | Added `EngineCore.markOwnUiShown(ts)`, called from `BlockedActivity.onResume`, `MainActivity.onResume` and "Go home". It closes sessions whose latest RESUMED is before `ts` and ignores RESUMED events with `ts <= ownUiShownTs`. `EngineCore` blocks only if a RESUMED was seen after the last mark. Test: "lost STOPPED + Blocked shown → no relaunch, no further accrual". | §4, §8, §16 |
| M2 2 s overlap reads pre-arm events | Every query uses `begin = max(armedAtTs, cursorTs - 2000)` (replay: `- 6 h`). `DataGate` carries `armedAtTs()`, and `UsageEventSource` returns `Refused` if `begin < armedAtTs`. Block verify no longer queries; it reuses the next tick's state. Test: every recorded `begin >= armedAtTs`. | §4, §7, §8, §10, §16 |
| M3 crash-loop definitions conflict / double count | One unclean-restart entry per service instance (`instanceRecorded`), trip at 3 in 10 min. The 10-error streak is a separate immediate `CRASH_LOOP` disarm, removed from the §11 list. Test: one kill with both signals = 1 entry. | §7, §9, §11, §16 |
| M4 disarm thread unspecified | The engine never calls `ArmController`. It returns `RequestDisarm`, stops scheduling and posts `ArmController.disarm` to the main thread. The uncaught-exception handler was removed; crashes are detected via `service_alive` on the main thread. | §3, §7, §10, §11 |
| M5 carry-over vs 20 s staleness | On restart within 120 s with a carried state: `committed = candidate = carried`, `streak = 0`, `lastUsableTs = restartTs`. Tests for 60 s and 130 s off. | §6, §16 |
| M6 "Test blocking" false pass | Countdown starts at `MainActivity.onStop`. Fires only when MainActivity is not resumed, ≥ 5 s after leaving and ≥ 11 s after FocusGate's last activity event, waiting up to 60 s. Otherwise Inconclusive. Shows "Waited N s". §17 notes that the definitive check is a real block after a Recents swipe. | §13, §16, §17 |
| M7 FGS start vs engine start | `onCreate` does the marker check and BootCheck only. A pure `ServiceStart` dispatch table covers PANIC / START / null / TEST_BLOCK / NOTIF_DISMISSED. The engine is created only after `startForeground` succeeds. Test: no usage read when `startForeground` throws. | §7, §10, §16 |
| L1 lint `ExpiredTargetSdkVersion` | `app/lint.xml` ignores `ExpiredTargetSdkVersion`, `ExpiringTargetSdkVersion` and `OldTargetApi`, with a reason. REPORT will note it. | §2, §17 |
| L2 dismissible FGS notification | `setDeleteIntent` → re-post on the next tick. Documented in §15. The in-app panic pill stays always reachable. | §7, §15 |
| L3 FGS types on API 29–33 | Pure `ServiceTypes.compute`: API ≥ 34 uses `SPECIAL_USE (| LOCATION)`; 29–33 uses `LOCATION` or none (2-arg `startForeground`). | §3, §7, §16 |
| L4 backward time-zone reset | Reset only when `today > dayKey`. Test added. | §4, §16 |
| L5 UI rollover races the engine | While armed the engine is the sole writer of `used_ms_*`, `day_key` and `cursor_ts`. The UI rollover is display-only, and it writes only when disarmed. | §4 |
| L6 two `DataGate.open` callers | `FocusGateApp` calls `ArmController.restoreOnProcessStart()`. `REBOOT` is persisted only if the stored state was Armed. Test added. | §3, §9, §10, §16 |
| L7 Usage Access revoked → wrong reason | A `null` read is `Failed`. On a failure, AppOps is checked first → `PERMISSION_LOST` without counting an error. | §7, §9, §16 |
| L8 SAW hard-required, no GPay A/B | SAW is strongly recommended. Without it, FocusGate arms with `blockLaunchHealthy = false` and a warning. Added a manual test and an §17 item: revoke SAW only and retest GPay. | §8, §9, §13, §14, §16, §17 |
| L9 KGP 2.0.21 × Gradle 8.11.1 | Pins kept. The fallback is Gradle 8.9 only. | §2, §17 |
| L10 activityKey collision | Documented as a known fail-open undercount. No code change. | §4, §15 |
| L11 tick-step-1 failure semantics | On a failure: skip steps 2–3, publish `ALLOWED (ERROR_FAIL_OPEN)` and increment `consecutiveErrors`. | §7 |

---

## 18. Binding addendum: apply design-review iteration 2 findings

The design loop ended at iteration 2 with CHANGES_REQUESTED (0 HIGH, 3 MEDIUM, 9 LOW). The user chose to continue without a third design pass. **All 12 findings in `d:/Kirooo/Mods/FocusGate/.agents/tasks/design-review.md` (iteration 2) are binding for the plan and code steps. Where this document conflicts with a finding, the finding's "Fix" wins.**

The planner must include each fix as an explicit task, and the coder must implement it with the listed tests:

1. **MEDIUM, screen receiver `IntentFilter`.** Use `IntentFilter().apply { addAction(ACTION_SCREEN_ON); addAction(ACTION_SCREEN_OFF) }` and `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`. Never use the two-string constructor.
2. **MEDIUM, explicit Intents everywhere.** Every PendingIntent or `startService` Intent names `FocusService::class.java` or an activity class explicitly. The delete intent is `Intent(ctx, FocusService::class.java).setAction(ACTION_NOTIF_DISMISSED)` with `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`. Add a grep audit for any `Intent(ACTION_` without a component, excluding HOME and settings intents.
3. **MEDIUM, re-promote a degraded location FGS.** When armed, `MainActivity.onResume` sends `ACTION_START` if the service isn't running, is `locationDegraded`, or its computed types differ from the current ones. `ACTION_START` on an existing engine calls `startForeground` with the full types. On success it clears the degraded flag, refreshes the notification and ticks. On `SecurityException` it stays degraded. Add a `ServiceStartTest` case. Add a §17 device question, with a fallback of stopService followed by startForegroundService.
4. **LOW, "Permitted while disarmed (no user data)" allow-list table** in §10 covering isLocationEnabled, isProviderEnabled (gated GpsSource only), AppOps check, BOOT_COUNT, isInteractive, and PackageManager installed/label/launch-intent metadata. The grep audit enforces the files these calls may appear in.
5. **LOW, disarm cleanup.** `Engine.stop()` writes `used_ms_*` and `day_key`, and removes `cursor_ts` and `armed_at_ts` in the same Editor. UI rollover writes are allowed only when `FocusService.engine == null`.
6. **LOW, defer the restart replay.** Run it on the first interactive-screen tick. Skip it if `begin >= cursorTs`. After the replay, `openSince = cursorTs`. Add tests.
7. **LOW, screen-on re-sync.** `cursorTs = max(cursorTs, screenOnTs - 2000)` without accrual. Add a test that a RESUMED during screen-off accrues only from screen-on.
8. **LOW, stray service instances.** `ACTION_TEST_BLOCK` or `ACTION_NOTIF_DISMISSED` with no engine calls `stopSelf(startId)` and returns START_NOT_STICKY. It doesn't count as an unclean restart. Add tests.
9. **LOW, BlockedActivity finishes on any `Disarmed` state** and in onResume when the gate is closed (non-test mode).
10. **LOW, `NO_ZONES` copy.** Show "Time's up · no zones set, staying open". This lives in the state mapping only; `decide` is unchanged.
11. **LOW, exact backup XML** as given in finding 11, for both `backup_rules.xml` and `data_extraction_rules.xml`.
12. **LOW, process items:**
    - (a) Generate the Gradle wrapper in an empty temp directory, then copy it in.
    - (b) Dependency check criterion: no GMS, Firebase, analytics, crash or ads groups, and list every other group in REPORT.
    - (c) Overlay onboarding copy: "Find FocusGate in the list and turn it on".
    - (d) Use the exact manual "Test blocking" procedure from finding 12(d). Remove the "(?)" from the vivo component name.

The post-code privacy and logic audits must check each of these 12 items.
