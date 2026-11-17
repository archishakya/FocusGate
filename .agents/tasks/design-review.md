# FocusGate DESIGN.md review (iteration 2)

## Summary

Reviewed `docs/DESIGN.md` (revision that answers the 18 iteration-1 findings) against the full spec and `env.md`. All 18 earlier findings are addressed, and every hard rule is met: there is one data gate, no accessibility service, no INTERNET, no RECEIVE_BOOT_COMPLETED, no background location, nothing exported except the launcher, backup is excluded, and no overlay is ever drawn. The API 34 FGS, BAL/SAW and POST_NOTIFICATIONS reasoning is correct. Usage counting, the decision function and table, hysteresis, fail-open and failsafe are specified to a level a coder can implement. The toolchain (JDK 21 + Gradle 8.11.1 + AGP 8.7.3 + Kotlin 2.0.21) is consistent with env.md. All required sections are present, including §17 Open questions.

Three MEDIUM defects remain. Two are code snippets that break at runtime if copied as written: the receiver `IntentFilter` and the implicit delete-intent `Intent`. The third is a dead "resume location checks" path after a degraded sticky restart. There are also 9 LOW findings. No web research was done (no web tool is available in this step); items that need device confirmation are recorded as LOW.

## Findings

1. **MEDIUM, §7 "Screen on/off receiver".** `IntentFilter(ACTION_SCREEN_ON, ACTION_SCREEN_OFF)` calls the `IntentFilter(String action, String dataType)` constructor. The second argument is parsed as a MIME type. `"android.intent.action.SCREEN_OFF"` has no `/`, so it throws `MalformedMimeTypeException`. That crashes `Engine.start()` on every arm, which in turn trips the crash-loop guard. JVM tests won't catch it, because `Engine` is the Android wrapper.
   Fix: replace the snippet with
   ```kotlin
   val filter = IntentFilter().apply {
       addAction(Intent.ACTION_SCREEN_ON)
       addAction(Intent.ACTION_SCREEN_OFF)
   }
   ContextCompat.registerReceiver(ctx, screenRx, filter, null, engineHandler, ContextCompat.RECEIVER_NOT_EXPORTED)
   ```

2. **MEDIUM, §7 "Notification → Dismissal".** The delete intent is `PendingIntent.getService(ctx, 2, Intent(ACTION_NOTIF_DISMISSED), FLAG_IMMUTABLE)`. That is an implicit service intent. When the PendingIntent fires, `startService` throws `IllegalArgumentException` ("Service Intent must be explicit", API 21+), and Android 14 also never delivers implicit intents to a non-exported component. The re-post never happens, so after a swipe the notification's "Disable now" panic action is gone for good, which defeats the stated Android 14 mitigation for failsafe (b).
   Fix: make it explicit, the same way the panic action is:
   ```kotlin
   PendingIntent.getService(ctx, 2,
       Intent(ctx, FocusService::class.java).setAction(ACTION_NOTIF_DISMISSED),
       PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   ```
   Add one rule to §7: every Intent used in a PendingIntent or `startService` names `FocusService::class.java` or an activity class explicitly. Add it to the §16 grep audit as well: no `Intent(ACTION_` without a component, except HOME/settings intents.

3. **MEDIUM, §7 "Starting it" vs "Sticky restart" vs §6 "Why no ACCESS_BACKGROUND_LOCATION".** After a sticky restart the service degrades to SPECIAL_USE only (`locationDegraded = true`) and its notification says "Open FocusGate to resume location checks". But `MainActivity.onResume` sends `ACTION_START` only "when armed but the service is not running". A degraded service *is* running, so opening the app never widens the types, and location stays UNKNOWN (fail open) until the next process death. The promise in the notification is false, and blocking inside zones silently stops working after any Funtouch kill plus sticky restart.
   Fix: in `MainActivity.onResume`, when armed, send `ACTION_START` if `!FocusService.isRunning || FocusService.locationDegraded || ServiceTypes.compute(sdk, locationGranted, hasZones) != FocusService.currentTypes`. In `ServiceStart.handle(ACTION_START)` with an existing engine, call `startForeground` with the full types. On success, clear `locationDegraded`, refresh the notification text and post an immediate tick. On `SecurityException`, keep it degraded. Add a `ServiceStartTest` case ("degraded engine + ACTION_START → startForeground with LOCATION, no second engine") and a §17 item: confirm on the device that calling `startForeground` again from the foreground re-grants while-in-use location to an FGS that was first started from the background. If it doesn't, fall back to `stopService` then `startForegroundService` from the activity.

4. **LOW, §10 "Every data call site sits behind it" / §16 audit.** Several system calls are made ungated, including while disarmed:
   - `LocationManager.isLocationEnabled` in `Permissions.kt`
   - `isProviderEnabled`
   - AppOps `unsafeCheckOpNoThrow`
   - `Settings.Global.BOOT_COUNT`
   - `PowerManager.isInteractive`
   - PackageManager installed/label/`getLaunchIntentForPackage` for the 3 apps

   §10 mentions only AppOps and `isLocationEnabled`. The spec says the gate is "checked before every UsageStats and location call", and the user said "should not read any app data (even the 3 blocking app)". An auditor needs an explicit, closed allow-list.
   Fix: add a "Permitted while disarmed (no user data)" table to §10 listing exactly these calls with a one-line reason each. State that `isProviderEnabled` is called only inside gated `GpsSource.start`, and that PackageManager lookups return only installed/label metadata, never usage. Make the §16 grep check that these calls appear only in the listed files.

5. **LOW, §12 storage / §10 disarm teardown.** `cursor_ts` and `armed_at_ts` stay in `fg_store.xml` after disarm. Both are tick-derived timestamps, so `cursor_ts` ≈ the last screen-on moment while armed. The spec says disarmed means "keep no usage data beyond stored minute counters", and arming resets both to `now` anyway. Separately, `Engine.stop()`'s final `apply()` runs on the engine thread *after* the `Disarmed` commit, so it can race the UI's disarmed-only rollover write.
   Fix: `Engine.stop()`'s final persist writes `used_ms_*` and `day_key` and **removes** `cursor_ts` and `armed_at_ts` in the same `Editor`. The UI's rollover write is allowed only when `FocusService.engine == null` (engine fully torn down), not merely `!DataGate.isOpen()`. In §12, mark `cursor_ts` and `armed_at_ts` as "present only while armed".

6. **LOW, §4 "Rebuilding foreground state after restart" vs §4 screen-off and §7 polling.** `Engine.start()` runs the 6 h state-only replay query right away. Sticky restarts on Funtouch often happen while the screen is off, which contradicts "While the screen is off nothing is counted and nothing is queried". The text also doesn't say that after the replay `openSince = cursorTs` for packages left open, or that the replay is skipped when the window is empty (fresh arm: `armedAtTs == cursorTs`).
   Fix: defer the replay to the first tick with the screen interactive (`pendingReplay = true` at start). Skip it when `begin >= cursorTs`. After the replay, set `openSince = cursorTs` for every non-empty set. Add both cases to `UsageAccumulatorTest`/`EngineCoreTest`.

7. **LOW, §4 screen-on re-sync.** After screen-off, `cursorTs` stays at the screen-off time, so the first screen-on query spans the whole off period and `accrualFloor = cursorTs`. A monitored RESUMED stamped during the off period (rare, e.g. a show-when-locked activity) would accrue the screen-off time.
   Fix: on `ACTION_SCREEN_ON`, set `cursorTs = max(cursorTs, screenOnTs - 2000)` without accruing (screen-off time is never counted by definition), then run the first tick with the usual `queryBegin()`. Add a test: RESUMED during screen-off accrues only from screen-on.

8. **LOW, §7 dispatch table.** `ACTION_TEST_BLOCK` with no engine is "ignored / unchanged", and `ACTION_NOTIF_DISMISSED` without an engine is unspecified. If either intent creates a fresh service instance (UI race, or a delete intent after the engine died), that instance has set `service_alive = true` in `onCreate`. It then lingers as a started, non-foreground service, and its later background kill is recorded as an unclean restart toward the crash-loop guard.
   Fix: add the row "`ACTION_TEST_BLOCK` / `ACTION_NOTIF_DISMISSED` with no running engine → `stopSelf(startId)`, return `START_NOT_STICKY`". The instance must not record an unclean restart, and `onDestroy` resets the marker. Add both to `ServiceStartTest`.

9. **LOW, §8 BlockedActivity.** There is no rule for disarm while the Blocked screen is showing, for example panic from the notification shade while blocked. The opaque Blocked screen stays up after FocusGate is off.
   Fix: `BlockedActivity` collects `ArmController.state` and calls `finish()` on any `Disarmed`. Non-test mode also finishes if the gate is closed in `onResume`.

10. **LOW, §9 per-app state / §5.** With **no zones configured** and 0 min left, `decide` returns `LIMIT_LOCATION_UNKNOWN`, so the copy reads "Time's up · location unclear, staying open". That is misleading in Essentials when the user simply has no zones.
    Fix: add `NO_ZONES` handling in the per-app state mapping (not in `decide`): if `zones.isEmpty()`, show "Time's up · no zones set, staying open". `decide` stays unchanged.

11. **LOW, §12 Backup.** `<exclude domain="root|file|database|sharedpref|external" path="."/>` is shorthand. Taken literally, the `domain` value is invalid, and lint (abortOnError) fails or the rule is ignored.
    Fix: specify the files exactly. `backup_rules.xml`:
    ```xml
    <full-backup-content>
      <exclude domain="root" path="."/><exclude domain="file" path="."/>
      <exclude domain="database" path="."/><exclude domain="sharedpref" path="."/>
      <exclude domain="external" path="."/>
    </full-backup-content>
    ```
    `data_extraction_rules.xml` has the same five `<exclude>` lines inside both `<cloud-backup>` and `<device-transfer>`, plus `device_root`, `device_file`, `device_database` and `device_sharedpref`.

12. **LOW, §2 wrapper generation / §16 step 5 / §13 copy / §16 manual tests.** Four small specification gaps:
    - (a) `gradle wrapper` run inside the project configures the build scripts, so it fails if they are incomplete or the plugins can't resolve yet. Run it in an empty temp directory, then copy `gradlew`, `gradlew.bat` and `gradle/wrapper/*` into the project (or generate the wrapper before writing the build scripts).
    - (b) The dependency allow-list ("only `androidx.*`, `org.jetbrains.*`, `listenablefuture`") may reject legitimate annotation-only artifacts such as `org.jspecify:jspecify`. Make the pass criterion "no `com.google.android.gms`, `com.google.firebase`, analytics/crash/ads groups; every other group listed in REPORT".
    - (c) `ACTION_MANAGE_OVERLAY_PERMISSION` ignores `package:` data on Android 11+, so it opens the list. The onboarding copy should say "Find FocusGate in the list and turn it on".
    - (d) The manual "Test blocking after swiping from Recents / after screen-off" step needs an exact procedure, because screen-off during a pending test is Inconclusive by design. Use: turn the screen off and on (or swipe FocusGate from Recents), reopen FocusGate, start the test, switch to another app, and wait ≥ 11 s. The definitive variant is a real block after a Recents swipe. Also replace `com.vivo.abe/.unifiedpower.HighPowerDetailActivity(?)` with the name without "(?)", since §17.1 already covers its unverified status.

## Verified assumptions

- env.md: JBR 21.0.11 exists at `C:/Users/Anirudh/.jdks/jbr-21.0.11`; JBR 25.0.2 is the Android Studio default; SDK android-35 and build-tools 34.0.0/36.0.0 are present; network to services.gradle.org and dl.google.com is OK. The design pins Gradle to JDK 21 via `org.gradle.java.home` and `JAVA_HOME`, which matches.
- The toolchain is internally consistent: Gradle 8.11.1 runs on Java 21 but not on Java 25. AGP 8.7.x needs Gradle ≥ 8.9 and JDK 17+ and supports compileSdk 35. AGP 8.7's default build-tools are 34.0.0 (installed). Compose BOM 2024.12.01 gives Compose 1.7.6 and Material3 1.3.1. core-ktx 1.15.0 needs compileSdk 35. The Kotlin 2.0.x compose compiler plugin replaces `kotlinCompilerExtensionVersion`. The KGP 2.0.21 × Gradle 8.11.1 tested-range caveat is recorded with a fallback.
- API 34: an FGS needs a manifest `foregroundServiceType` and the matching `FOREGROUND_SERVICE_*` permission. A location-type `startForeground` without a granted location permission, or from the background without while-in-use access, throws `SecurityException`. That is handled by the specialUse fallback.
- A foreground service alone is not a BAL exemption. A user-granted SYSTEM_ALERT_WINDOW is a documented BAL exemption. A direct `Context.startActivity` from the service doesn't involve the PendingIntent sender opt-in. Sending Home is also an activity start that needs the exemption.
- SAW also exempts FGS-start-from-background on Android 12–14, so a sticky restart can call `startForeground`. The Android 15 visible-overlay requirement is avoided by targetSdk 34.
- FGS notifications are user-dismissible on Android 14. POST_NOTIFICATIONS is required for the FGS notification to be visible on 33+.
- `ContextCompat.registerReceiver(Context, BroadcastReceiver, IntentFilter, String?, Handler?, Int)` exists. On API < 33 it adds `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which matches the documented library-added permission.
- `java.time` (API 26+), `AppOpsManager.unsafeCheckOpNoThrow` (API 29), `UsageEvents.Event.ACTIVITY_STOPPED` = 23 (API 29) and `Settings.Global.BOOT_COUNT` (API 24, public) all fit minSdk 29. `UsageEventsQuery.setPackageNames` is API 35 only, so the client-side filter is required.
- The decision function and table cover all six required scenarios by name. "Leave → unblocked / return → blocked" holds because nothing is latched.
- Every hard rule from the spec has a mechanism plus an execution-time check in §16: the gate, no accessibility, no INTERNET, no RECEIVE_BOOT_COMPLETED, no background location (justified in §6), exported surface, backup, no overlay (grep for `addView`/`TYPE_APPLICATION_OVERLAY`), `<queries>` with 3 packages and no QUERY_ALL_PACKAGES, and no logging.
- The required DESIGN sections are present: architecture, usage tracking, location/hysteresis, blocking, state transitions, zero-data gate, failsafe, Android 14 + Funtouch limitations (5 m GPS, ~1 s latency, force-stop, OEM kills), the permission list with justification, and Open questions (§17).
- All 18 iteration-1 findings are resolved in the text, as cross-checked against the previous `design-review.json`.

## Unverified / wrong assumptions

- **Wrong:** `IntentFilter(ACTION_SCREEN_ON, ACTION_SCREEN_OFF)` registers two actions (finding 1).
- **Wrong:** the implicit `Intent(ACTION_NOTIF_DISMISSED)` in a `getService` PendingIntent reaches `FocusService` (finding 2).
- **Wrong:** "Open FocusGate to resume location checks" resumes them. Opening the app doesn't re-promote a running degraded service (finding 3).
- **Unverified (device):** whether a repeated foreground `startForeground(…LOCATION)` regains while-in-use access for an FGS first started from the background (finding 3, add to §17).
- **Unverified (device), already in §17:** the Funtouch deep-link components, vivo's background pop-up gate together with SAW, sticky-restart behaviour, BOOT_COUNT readability, GPay with a bare SAW grant, Compose text with InitializationProvider removed, and UsageEvents completeness for PiP and Custom Tabs.
- **Unverified (build):** that the debugRuntimeClasspath contains no group outside the stated allow-list (finding 12b).

## Verdict

**CHANGES_REQUESTED.** There are 0 HIGH, 3 MEDIUM (findings 1–3) and 9 LOW findings. The MEDIUM findings are small, local fixes: two snippet corrections and one onResume/ACTION_START rule with a test.
