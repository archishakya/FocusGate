# Old app (FocusGuard) vs new app (FocusGate): location report

Read-only investigation. Nothing was built or run. Only the folder sources were read; the bundle file was not needed.
Old paths are under `D:/Kirooo/Mods/Instagram/app/src/main/java/com/antigravity/instablocker/` (abbrev. `OLD/`).
New paths are under `D:/Kirooo/Mods/FocusGate/app/src/main/java/app/focusgate/` (abbrev. `NEW/`).

## Summary

The old app was not a strict GPS-only app. It used Google Play Services Fused Location (HIGH_ACCURACY) plus the Geofencing API. Fused blends Wi-Fi, cell and GPS, so it gives a quick, stable fix indoors.

The old app's decision was very forgiving: one fix, a plain `distance <= radius` test, a 10 m minimum radius, no accuracy filter, no hysteresis, no staleness timeout. It also asked for a fresh fix every time Chrome was focused.

The new app is deliberately strict. It uses `GPS_PROVIDER` only, has a 5 m default radius with a 3 m minimum, discards fixes worse than 2r (min 10 m), needs 3 consecutive same-class readings, goes UNKNOWN after 20 s without a usable fix, and treats UNKNOWN as "allow". Sampling also cold-starts only once time runs out. At a desk, with indoor GPS at ±4 to ±10 m and 5 to 10 m of jitter, a 5 m zone cannot be classified reliably. That produces the "location unclear" results.

| Aspect | OLD (FocusGuard) | NEW (FocusGate) |
|---|---|---|
| API | Play Services `FusedLocationProviderClient` + `GeofencingClient` | Platform `LocationManager.GPS_PROVIDER` only |
| Request | HIGH_ACCURACY, 2000 ms, min 1 s, min distance 1 m | 1000 ms, 0 m |
| Sampling | Continuous while the FG service runs, plus `getCurrentLocation` on each Chrome focus | Only when screen on, zones exist and some app's time is ≤ 0. Cold start each time |
| Default / min radius | 10 m / 10 m (slider 10..250) | 5 m / 3 m (3..200) |
| Accuracy filter | None | Drop if acc > clamp(2r, 10, 100). For r=5 that is 10 m |
| Inside rule | One fix: `distance <= radius` | 3 consecutive INSIDE readings. A single reading with `d <= r` and usable accuracy |
| Outside rule | Anything else | 3 consecutive "all zones d > r + max(1, 0.2r) with usable accuracy" |
| No fix / poor fix | UNKNOWN only if no fix ever; otherwise keeps the last coords forever (never stale) | UNKNOWN after 20 s without a usable reading; then fail open |
| UNKNOWN | Treated as not inside, so allowed (but it effectively never happens) | "Location unclear, staying open" |
| Zone centre | One `getCurrentLocation` fix, saved immediately | Averaged ≥5 fixes, each ≤10 m, ≥10 s, spread ≤ max(3, mean acc), 90 s timeout |
| Background perms | FINE+COARSE+BACKGROUND, FGS type location | FINE+COARSE, FGS type location\|specialUse |

## Q1. OLD app: how it obtains location

All in `OLD/engine/GeofenceManager.kt`.

- **API:** Play Services. It uses `FusedLocationProviderClient`, `GeofencingClient` and `CancellationTokenSource`. It does not use `LocationManager`.
- **Continuous updates** (`startContinuousLocationUpdates`):
  ```kotlin
  LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
    .setMinUpdateDistanceMeters(1f).setMinUpdateIntervalMillis(1000L)
    .setGranularity(Granularity.GRANULARITY_FINE).setWaitForAccurateLocation(false)
  ```
  Each callback uses `result.lastLocation` and calls `stateManager.updateLocation(lat, lon)`. The accuracy value is never read.
- **On-demand fix** (`fetchFreshLocation`): `getCurrentLocation(PRIORITY_HIGH_ACCURACY, token)`. If it returns null, it falls back to `fusedLocationClient.lastLocation`. So cached fixes are accepted.
- **Geofencing API** (`registerGeofences`): ENTER, EXIT and DWELL transitions, `setNotificationResponsiveness(0)`, `setLoiteringDelay(100)`, `INITIAL_TRIGGER_ENTER|DWELL`. `OLD/service/GeofenceBroadcastReceiver.kt` takes `triggeringLocation` and feeds `updateLocation`. The inside/outside decision does not depend on the geofence events; it uses coordinates only.
- **When it samples:**
  - Always, from when the foreground service's `onCreate` runs until it is destroyed. No screen-on or limit-reached condition (`OLD/service/BlockerForegroundService.kt`).
  - Also at every Chrome focus: `InstagramAccessibilityService.kt` lines ~49-58 evaluate with the in-memory fix, then call `fetchFreshLocation` and re-evaluate and enforce.
  - Also from `MainActivity`, `PermissionsScreen` and the geofence receiver.
- **Service type and permissions:** `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)` when permission is held. The manifest declares FINE, COARSE, BACKGROUND_LOCATION, FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION and `foregroundServiceType="location"`.

Note: the old app's "GPS only" claim was not true. Fused HIGH_ACCURACY uses Wi-Fi/cell when it helps. I could not tell how much that helped the user at the desk. It is the most probable reason the old app felt quicker and steadier.

## Q2. OLD app: how it decides inside vs outside

- **Distance:** `GeofenceZone.distanceTo` in `OLD/data/model/GeofenceZone.kt` uses `Location.distanceBetween` (geodesic) to the zone centre.
- **Rule** (`BlockerStateManager.evaluateCondition`, `OLD/engine/BlockerStateManager.kt`): iterate active zones and set inside if `distanceToCenter <= zone.radiusMeters`.
- **Missing location:** `userLat == null` gives `LocationStatus.UNKNOWN`. That only happens before any fix since process start. Otherwise the last stored lat/lon is reused indefinitely (there is no timestamp or staleness).
- **No debouncing:** there is no accuracy filter, smoothing, hysteresis or delay.
- **Policy:** the decision is `remainingSeconds > 0` → ALLOWED. Otherwise `isInsideRestricted` → BLOCKED, else ALLOWED. UNKNOWN is not inside, so it is allowed (fail open), but in practice it is rare.
- **Flow to blocking:** `updateLocation` → `reevaluateState` → `_evaluationFlow`. `InstagramAccessibilityService.checkAndEnforceBlock` reads `evaluationFlow.value.status == BLOCKED`. It then does `GLOBAL_ACTION_HOME` and launches `BlockOverlayActivity`, with a 1.2 s debounce.
- **Consequence:** it is trivial to be "inside". Any fix within 10 m of the saved centre blocks, whatever its accuracy. A stale fix keeps blocking too. The cost is flapping and false positives. For a 10 m desk zone this was fine for the user.

## Q3. OLD app: default and minimum radius, and zone centre capture

- **Radius:** the default is 10 m (`GeofenceZone.radiusMeters: Float = 10f`). The slider is `valueRange = 10f..250f` (`OLD/ui/screens/LocationPickerScreen.kt` ~line 314). The minimum is therefore 10 m.
- **Centre capture:**
  - `LocationPickerScreen.kt` (~line 125) and `DashboardScreen.kt` (~line 258): "1-Tap Room Geofence Setup" calls `fetchFreshLocation`.
  - On success it saves immediately via `geofenceRepo.setPrimaryRoomLocation(lat, lon, radius = 10f)` (`GeofenceRepository.kt` line ~98).
  - That is a single fix. It is not averaged and it does not wait for accuracy. A fix with a 10 to 20 m error can become the centre, and the 10 m radius absorbs that error.

## Q4. NEW app pipeline: what is different, and why "location unclear"

Constants and conditions quoted from the code:

- **Provider** (`NEW/location/GpsSource.kt`): `lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, looper)`. It needs `isProviderEnabled(GPS_PROVIDER)`. There is no network or fused provider.
- **Sampling gate** (`NEW/core/EngineCore.kt`, ~line 195): `need = screenOn && hasZones && preciseLocationGranted && fgsHasLocationType && remaining.any { it <= 0 }`.
  - GPS only starts after some app's time has run out, so it cold-starts exactly when it is needed.
  - `evaluator.start(now)` sets `committed = UNKNOWN` unless carry-over applies. Carry-over is valid only if the previous session stopped less than 120 s earlier (`CARRY_OVER_MS`).
  - Until 3 usable readings agree, the state stays UNKNOWN, which means allowed ("location unclear, staying open").
  - `stopSampling` happens whenever the screen goes off, so the cost repeats.
- **Accuracy gate** (`ZoneRules.maxAccuracyM`): `clamp(2·r, 10, 100)`. For r=5 that is 10 m. A fix at ±11 or worse is UNUSABLE (see `ZoneEvaluator.classify`).
- **Classification** (`ZoneEvaluator.classify`):
  - `usable = accuracy > 0 && accuracy <= maxAccuracyM(r)`.
  - `inside` if usable and `d <= r`.
  - `allOutside` if every zone has `d > r + max(1, 0.2·r)` (exit margin; 1 m for r=5), or `d - accuracy > exit` when the fix is unusable.
  - Otherwise AMBIGUOUS if any zone is usable, else UNUSABLE.
  - AMBIGUOUS resets the streak; UNUSABLE is ignored.
- **Hysteresis:** `N = 3`. The state flips only after 3 consecutive same-class readings. Any AMBIGUOUS reading restarts the count. At r=5, a fix that is 5.01 to 6 m from centre resets everything.
- **Staleness:** `STALE_MS = 20_000`. `state(now)` calls `markUnavailable()` (UNKNOWN) if there was no usable or ambiguous reading for 20 s. Once accuracy exceeds 10 m for 20 s the state becomes UNKNOWN and the app fails open.
- **UNKNOWN and fail-open:** `AppState.TIMES_UP_UNKNOWN` → "time's up · location unclear, staying open" (`EngineCore.stateMessage`). Provider-off, permission loss or exceptions also give UNKNOWN immediately.
- **Zone centre** (`NEW/core/ZoneSettler.kt`): needs 5 fixes in a window, all ≤ 10 m (`GOOD_ACC_M`), ≥ 10 s elapsed, and spread ≤ `max(3, mean acc)`. Timeout is 90 s. "Save anyway" is allowed if the best mean ≤ 50 m, and the suggested radius is `max(radius, ceil(bestAccuracy))`. This is better than the old single fix, but it demands ≤ 10 m indoors, which is often impossible at a desk. The default radius is 5 m (`ZoneRules.RADIUS_DEFAULT`), minimum 3.

Most likely causes at a 4 to 10 m accuracy desk:

1. **Radius is smaller than GPS noise.** With r=5, a reading of ±4 to ±10 m is classified against a 5 m circle plus a 1 m exit margin. Typical indoor jitter is 5 to 10 m, so about half of readings land in 5 to 6 m (AMBIGUOUS, which resets the streak) or beyond 6 m (OUTSIDE). A streak of 3 is rarely achieved. The old app's 10 m radius and no streak avoids this.
2. **Accuracy gate vs weak indoor fixes.** The Android `accuracy` value is a 68% radius. Several fixes will exceed 10 m, which are UNUSABLE and never advance the streak. Twenty seconds without a usable one → UNKNOWN → "staying open".
3. **Cold start.** Sampling starts only after time runs out. Indoor GPS TTFF is 10 to 60+ s, and then 3 consecutive agreeing readings are needed. The user will often see "unclear" for the first minute at the desk.
4. **Platform-only GPS indoors.** The old app got Wi-Fi/cell assistance through Fused. The new one has nothing to help beyond raw GPS.
5. **Hysteresis (N=3) plus exit margin of only 1 m** means slow and flappy for small radii. This explains the user's "slow".

I could not verify actual fix quality on the iQOO 9. Funtouch OS battery management may also throttle `GPS_PROVIDER` when the screen is on and the app is in the background. I did not examine that.

## Q5. GPS test screen semantics (`NEW/ui/screens/GpsTestScreen.kt`, `NEW/location/LocationSession.kt`)

- **Big number "±N m"** is `ls.accuracyM`, the horizontal accuracy of the latest fix (68% confidence radius) as reported by Android. It is not the distance to the zone and not the zone radius. It changes with every fix and does not show your movement.
- **Inside / Outside / Unclear** comes from `ZoneEvaluator` in `LocationSession.onFix`. It uses `nearestZone(distancesM)`, i.e. only the single nearest zone, with that zone's `radiusM`. It runs `ev.onReading(listOf(near.first.radiusM), listOf(near.second), accuracyM, now)`. The screen does not tell which zone or radius.
- **"Consecutive k/3"** is `ev.streak`: the count of consecutive readings of the same class (INSIDE or OUTSIDE). The streak is not capped at 3, which is why it can read 26/3. It shows the state has been confirmed and has stayed so for 26 readings. It resets to 0 or 1 on an AMBIGUOUS or flipped reading. UNUSABLE readings (accuracy too poor) neither count nor reset, so the counter and the state can freeze while accuracy is bad.
- **Stuck on 4 m after walking 11 m away:** the displayed accuracy is only the latest fix's quality. The state only flips to Outside after 3 consecutive fixes with usable accuracy at d > r+margin. If fixes were AMBIGUOUS or UNUSABLE the state stays as it was (until the 20 s stale rule). I could not determine from code alone why it stayed at 4 for 3 minutes (possible cause: fixes not updating on the device, or a large zone radius). A debug line showing distance would settle it.
- **Extra info it could show** (no coordinates): the nearest zone name, its radius `r`, the current distance to the centre in metres (`near.second`, available in `onFix`), and the effective accuracy limit (`maxAccuracyM(r)`) with a "fix ignored: too inaccurate" flag. The zone name, radius and distance would need to be added to `LocationSession.UiState` and kept only transiently.

## Q6. Recommendation (minimal changes)

1. **Radius defaults and floor** (`NEW/core/ZoneRules.kt`): set `RADIUS_DEFAULT = 10` and `RADIUS_MIN = 8` (or 10, matching the old app). Zones below the GPS noise level cannot work. Update the zone editor copy and tests (`ZoneRulesTest`). Existing saved zones with smaller radii stay valid, so consider a one-time warning.
2. **Relax the accuracy gate** (`ZoneRules.maxAccuracyM`): use `max(20, 2·r)` clamped to 100, or ignore accuracy for INSIDE decisions. A fix is likely inside when `d <= r`, so a poor-accuracy fix should only block "OUTSIDE" claims. Concretely in `ZoneEvaluator.classify`, treat `d <= r` as INSIDE for any accuracy ≤ 30 m.
3. **Reduce hysteresis** (`ZoneEvaluator.N`): INSIDE should need 1 reading (like the old app), while OUTSIDE (the unblock direction, which is the user-unfriendly one only when wrong) needs 2 to 3. Keep the asymmetric rule: block fast, release after a couple of readings. Also raise the exit margin from `max(1, 0.2r)` to about `max(3, 0.3r)` so jitter near the edge does not reset the streak.
4. **Raise staleness** (`ZoneEvaluator.STALE_MS`): 20 s → 60 s, and when the state goes stale keep the last committed state while screen is on, instead of falling to UNKNOWN. That mirrors the old app's "keep last coordinates".
5. **Don't cold start only at limit time** (`EngineCore.kt`, `need` predicate ~line 195): start sampling slightly earlier, e.g. when any app has ≤ 2 min remaining, or keep the carry-over valid for 10+ minutes (`CARRY_OVER_MS`). This removes the 10 to 60 s "unclear" window. It costs a little battery and is the main privacy/battery tradeoff to decide.
6. **Use a last-known seed** in `GpsSource.start`: call `lm.getLastKnownLocation(GPS_PROVIDER)` if its age ≤ 60 s to start with a reading. Also consider requesting `LocationManager.FUSED_PROVIDER` (API 31+). It is part of the platform `LocationManager`, not Play Services, so it fits the "no GMS" rule. But it is only available if the OEM/GMS implements it, and on a GMS device it is backed by Play Services, with the Wi-Fi/cell assistance the user said they don't want. I could not verify its availability on the iQOO 9. Recommendation: keep GPS_PROVIDER as the only provider, per the user's wish.
7. **Zone setup** (`ZoneSettler`): relax `GOOD_ACC_M` 10 → 20 and `MIN_DURATION_MS` 10 s → 5 s, or accept a fix when mean accuracy ≤ 15 m. Indoor GPS at ±4 to ±10 m is fine, but anything stricter can leave the user in "Save anyway". Since the zone radius is ≥ 10 m the centre error is absorbed.
8. **GPS test screen**: add nearest zone name, radius, distance to centre (m) and cap the shown streak at 3 (e.g. "3/3 confirmed"), as described in Q5. Show "fix ignored: accuracy worse than X m" when UNUSABLE.
9. **Foreground service:** keep `foregroundServiceType="location"`. Add a battery optimisation exemption prompt for Funtouch OS (not code-verified in this investigation).

Risks:
- A smaller `N` and a larger radius raise false-positive blocks near the zone edge, such as the living room next to the desk. At 10 m the zone may overlap the adjacent room.
- Relaxing accuracy increases flapping. Mitigate with the asymmetric N and the larger exit margin.
- Earlier or continuous sampling uses more battery. No location history may be stored, so keep only the committed state and counters in memory.
- The privacy rules hold with all of the above: no INTERNET permission, no GMS, no logging of coordinates, and distances not persisted. Showing distance in the test screen must stay transient.

## Not determined

- Whether the old app's Fused fixes at the user's desk were GPS or Wi-Fi/cell-derived (no accuracy was logged).
- Why the test screen stayed at 4 m for 3 minutes after the user moved; this needs an on-device repro with distance displayed.
- Whether `FUSED_PROVIDER` exists on the iQOO 9 / Funtouch OS 14 without Play Services.
- Funtouch background-throttling behaviour. I did not run builds or tests, and `complete_code_bundle.md` was not consulted.
