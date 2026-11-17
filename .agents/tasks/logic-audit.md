# FocusGate timer and location logic audit

**Verdict: CHANGES_REQUESTED.** The timer accounting path is coherent for the examined event, restart, rollover, and screen-state flows. The location evaluator implements the requested asymmetric rules and fails open when data is unavailable. Two current paths can nevertheless leave a user blocked after leaving a zone or evaluate a changed zone set against distances for the previous zone set.

## HIGH

### 1. Leaving a zone does not actually dismiss the blocking screen — **confirmed**

**Evidence:** `app/src/main/java/app/focusgate/core/EngineCore.kt:171-189` recomputes an OUTSIDE verdict as allowed and launches only when the current verdict is BLOCKED. `app/src/main/java/app/focusgate/ui/BlockedActivity.kt:65-75` responds to `snap.zone == OUTSIDE` by continuing to render `BlockedContent`; `BlockedContent` at `:146-153` shows a “Back to <app>” button but never calls `finish()`. The activity only finishes on disarm (`:52-56`), Go home, Back, or the explicit Back-to-app button.

**Scenario:** Instagram reaches its limit inside Home and `BlockedActivity` is foregrounded. After two confirmed outside readings, the engine correctly changes the decision to ALLOWED, but the opaque activity remains on top indefinitely. The user cannot return to Instagram until manually pressing “Back to Instagram” (or Back/Home), so the required “leaving zone unblocks quickly” behavior is not achieved even though the decision state is allowed.

**Fix:** When a non-test blocked activity observes an `OUTSIDE` snapshot, finish it (optionally after a short UI acknowledgement), or have the engine issue an explicit unblock signal that the activity consumes. Keep the current foreground re-check so a new inside decision can launch it again.

### 2. Editing zones during active sampling can compare new radii with old-zone distances — **confirmed**

**Evidence:** `app/src/main/java/app/focusgate/service/Engine.kt:82-85` starts `GpsSource` with a one-time `Store.zones()` list. `app/src/main/java/app/focusgate/location/GpsSource.kt:214-236` closes over that list and produces distances in its order. On every callback, `EngineCore.onLocationReading` instead obtains current `config.zones()` and passes only its radii alongside those distances (`core/EngineCore.kt:272-279`). `EngineCore.onZonesChanged` merely clears carry-over and marks the evaluator unavailable (`:286-289`); it does not stop and restart the existing `GpsSource` session.

**Scenario:** With an app at its limit and sampling active, the user adds, removes, reorders, or changes the radius of a zone. The next callback still contains distances for the old list, while evaluation uses the new list's radii. Equal list sizes can therefore classify a distance to a removed/reordered centre as though it belonged to a different zone; size changes fall to UNKNOWN because the list lengths differ. The former can block or allow incorrectly until sampling is restarted.

**Fix:** Make a zone change stop the active location session and immediately start a new one when the need-location predicate remains true. Alternatively, attach a zone-set version/identity list to each callback and reject callbacks that do not match the current configuration. Do not pair distances and radii from separate snapshots.

## MEDIUM

### 3. Play Services has no watchdog fallback after a successful registration that subsequently yields no fixes — **likely**

**Evidence:** `location/GpsSource.kt:116-154` falls back to platform GPS only from `requestLocationUpdates(...).addOnFailureListener` or from a synchronous setup exception. A successful registration has no deadline, last-fix watchdog, or provider-health callback that switches to `GPS_PROVIDER` when neither `getCurrentLocation` nor the update callback produces a usable fix. `ZoneEvaluator.state` eventually returns UNKNOWN after the five-minute hold (`core/ZoneEvaluator.kt:63-66`), which is correctly fail-open but is not GPS fallback.

**Scenario:** Play Services is installed and accepts the request but is stalled by device/OEM behavior, disabled sensors, or a transient internal failure. The engine remains attached to Google fused and does not try platform GPS, so an at-limit app stays allowed once location becomes UNKNOWN even if platform GPS could provide fixes.

**Fix:** Track the elapsed time since a delivered fresh fix in `GpsSource` and, for AUTO mode, tear down Google fused and start GPS after a bounded no-fix interval. Preserve fail-open behavior if GPS also cannot start. This needs device validation because the task did not provide a reproducible fused-registration-without-callback failure.

## LOW

### 4. The GPS test’s nearest-zone result is intentionally not the engine’s multi-zone verdict — **confirmed**

**Evidence:** The engine passes all zone distances to `ZoneEvaluator` (`EngineCore.kt:272-279`), where any inside zone wins and OUTSIDE requires all zones to be outside (`ZoneEvaluator.kt:106-126`). The diagnostic session reduces each fix to `nearestZone(distancesM)` before invoking its own evaluator (`location/LocationSession.kt:137-143`).

**Scenario:** With overlapping or nearby zones, the GPS test can display the nearest zone’s state while the engine is inside another zone. This does not alter enforcement, but it can mislead debugging of multi-zone behavior.

**Fix:** Label the test result explicitly as “nearest zone diagnostic”, or evaluate and display the all-zone engine-equivalent state alongside the nearest-zone distance.

## Timer / usage findings

No timer-blocking defect was confirmed in the inspected code. `UsageAccumulator` tracks per-package resumed activity sets, accrues open sessions through each tick, splits accrual at local midnights, and treats overlap events before `cursorTs` as state-only (`core/UsageAccumulator.kt:52-83, 121-151`). The persisted counter and cursor are written together (`data/Store.kt:65-80`), and sticky restart replay rebuilds state without accrual before normal reading resumes (`core/EngineCore.kt:142-166`). Screen-off takes a final tick, closes all sessions without further accrual, and screen-on advances the cursor without counting the off interval (`core/EngineCore.kt:250-268`). The bounded two-second screen-on overlap can count up to two seconds after screen-on even when the matching RESUMED happened just before the transition; the implementation and existing test describe this deliberate resynchronization bound rather than an unbounded screen-off count.

## Location / decision / failsafe findings

The requested evaluator policy is implemented: one INSIDE commits immediately, two OUTSIDE readings commit, exit margin is `max(3, 0.3r)`, and the evaluator uses “any inside” / “all outside” across the complete supplied list (`core/ZoneRules.kt:41-47`, `core/ZoneEvaluator.kt:106-126`). Decision policy blocks only a non-positive allowance while INSIDE (`core/Decision.kt:6-10`).

`GpsSource` uses Play Services fused as AUTO primary, filters fixes by their elapsed-realtime timestamp, and falls back to GPS when setup fails (`location/GpsSource.kt:64-102, 116-154, 214-236`). It does not retain a `Location`; duplicate suppression drops only identical coordinates with the same/older timestamp (`location/FixFreshness.kt:14-25`). No retained-coordinate cache or enforcement-path nearest-zone selection explains the reported 2→6 m movement; the changed-zone snapshot mismatch in finding 2 can associate a moving fix with stale centres.

Data access gates are present before UsageStats reads and location start/callbacks (`data/UsageEventSource.kt:14-15`, `location/GpsSource.kt:66-70, 215-219`), and `EngineCore.tick` exits before reader calls when the gate is closed (`core/EngineCore.kt:103-106`). Reboot restoration disarms an armed state on a changed/missing boot identity, panic closes the gate before stopping the service, usage read failures fail open before the crash-loop threshold, and service-alive restart recording can auto-disarm (`arm/ArmController.kt:43-65, 91-103`; `service/FocusService.kt:29-37`; `core/EngineCore.kt:118-137`).

## Missing tests

- Zone changes while sampling: start with two zones, mutate/reorder/remove them while the location reader remains active, then prove old callbacks cannot be evaluated against the new configuration and that sampling restarts with the new snapshot.
- Unblock UI lifecycle: drive a blocked foreground activity from INSIDE to confirmed OUTSIDE and assert the activity finishes (or the adopted explicit-unblock behavior occurs) without a button press; also assert a subsequent return inside can block again without a launch loop.
- AUTO provider liveness: simulate fused registration success followed by no callbacks, verify timed fallback to GPS, and verify both-provider failure produces UNKNOWN/fail-open.
- Freshness boundaries: exact 10-second age acceptance, future/zero `elapsedRealtimeNanos` handling, and interleaved fused/GPS callbacks during fallback so an old callback from the removed source cannot update the active session.
- End-to-end engine multi-zone tests: prove any-zone-inside blocks and all-zones-outside releases through `EngineCore`, not only through `ZoneEvaluator.classify`.
- Timer integration around screen transitions: test the production `EngineCore.onScreenOff/onScreenOn` sequence with a RESUMED timestamp in the two-second overlap and document/assert the permitted bounded post-screen-on accrual.

## Unverified (needs device)

- Whether iQOO 9/Funtouch permits Google fused registration yet stops delivering fresh callbacks while platform GPS remains usable; this determines how often finding 3 appears in practice.
- Whether Play Services fused fixes now move by the expected >=15 m on the affected device. The source requests high accuracy at 2 s / 1 s minimum intervals with zero minimum distance, and it does not preserve a previous `Location`; an on-device GPS test is needed to distinguish provider output from zone-centre capture error.
- Whether the saved zone centre and radius used in the reported walk match the intended desk location. The diagnostic reports nearest-zone distance from the current callback but cannot establish that the persisted centre was captured accurately.
- Background/OEM behavior after screen-off/on and sticky service restart, including whether Android retains while-in-use location access; the code intentionally fails open when it cannot sample.
