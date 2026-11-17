# FocusGate verification evidence

## Iteration
- Type: implement (no review.json present at start)
- Status: in progress

## Chunk 4 (items 13-16): WRITTEN, NOT YET BUILT

The terminal was unavailable, so nothing in this chunk has been compiled, linted or run. Every API was checked against the existing sources by reading, not by building.

Files (all under app/src/main/java/app/focusgate/ui/):
- theme/Theme.kt
- components/Components.kt (cards, ring, PowerPill, banners, SegmentedToggle, SubScreen, permission requesters)
- AppViewModel.kt (Store-backed state, buildRows, promoteIfNeeded for finding 3, display-only rollover for finding 5)
- AppShell.kt (comma-joined back stack in rememberSaveable, BackHandler, always-visible Enable / Disable now pill)
- MainActivity.kt (edge-to-edge synced to the theme, onResume re-promotion rule, AppOps check, onStop notifies the engine)
- screens/HomeScreen.kt, AllowancesScreen.kt, SettingsScreen.kt (item 13)
- screens/ZonesScreen.kt, ZoneEditorScreen.kt (item 14)
- screens/OnboardingScreen.kt, PrivacyScreen.kt (item 15)
- screens/InsightsScreen.kt, GpsTestScreen.kt (item 16)

No dependencies, manifest or core/gate/service/arm files were changed.

NOT DONE / to check after the build:
- Nothing compiled. Expect possible small import or signature fixes.
- Zone editing is add + delete only (no edit of an existing zone).
- Onboarding has 7 cards (DESIGN step 5 is split into background power and autostart). Manual "I've done this" confirmations are not persisted across process death.
- Foreground "Disable now" pill is hidden on onboarding and the new-zone screen only.
- Service-running row reads FocusService.isRunning at composition time (not a flow).
- Device checks (OEM deep links, background launch test, GPS) are still open.

Run: .\gradlew.bat assembleDebug lintDebug testDebugUnitTest --no-daemon --console=plain

## Chunk 4 (items 13-16): BUILD PASS, verified 2026-10-23
- The user ran the external build: `assembleDebug lintDebug testDebugUnitTest` → BUILD SUCCESSFUL in 1m 2s. Lint is clean and the unit tests pass.
- Not done: editing an existing zone (add/delete only).
- Not yet verified on the device: the whole UI and the service behaviour.

## Location fix round: WRITTEN, NOT YET BUILT

The terminal was unavailable, so nothing has been compiled, linted or run.

Changed files (app/src/main/java/app/focusgate/...):
- core/ZoneRules.kt: RADIUS_MIN 3→5, RADIUS_DEFAULT 5→10, RADIUS_WARN_BELOW=10, INSIDE_MAX_ACCURACY_M=30; maxAccuracyM = clamp(max(20, 2r), 20, 100); insideMaxAccuracyM = max(30, maxAccuracyM); exitMargin = max(3, 0.3r); radiusWarning(); sanitize raises legacy 3–4 m zones to 5 m instead of dropping them.
- core/ZoneEvaluator.kt: N_INSIDE=1, N_OUTSIDE=2 (replaces N=3); STALE_MS 20 s→60 s (isStale, UI only); HOLD_MS=300 s (last committed state held, then UNKNOWN); CARRY_OVER_MS 120 s→600 s; AMBIGUOUS/UNUSABLE never change the committed state; d ≤ r with accuracy ≤ 30 m counts as INSIDE.
- core/EngineCore.kt: WARM_START_MS=120 s; need-location starts when any app has ≤ 2 min left (still screen on + zones + permission, gate checked first); feed text shows "confirmed" instead of k/N.
- core/ZoneSettler.kt: WINDOW 5→3, GOOD_ACC_M 10→20, MIN_DURATION_MS 10 s→5 s.
- location/GpsSource.kt: FUSED_PROVIDER (API 31+, hasProvider + enabled, SDK_INT guarded) + GPS_PROVIDER at 1 s; GPS alone if fused missing; NETWORK_PROVIDER skipped; last-known seed ≤ 60 s posted to the looper (primitives only); providersLabel/lastFixProvider strings; gate check still the first statement.
- location/LocationSession.kt: UiState adds zoneName, radiusM, distanceM, provider, ignored, holding (memory only).
- ui/screens/GpsTestScreen.kt: nearest zone name + radius, live distance to centre, source, "Confirmed"/"Checking k/2", "Reading ignored: too inaccurate", hold notice.
- ui/screens/ZoneEditorScreen.kt: default 10 m, warning "Below 10 m, GPS indoors may be unreliable", steady k/3, new honesty copy.
- ui/screens/ZonesScreen.kt: same warning on zones under 10 m.
- ui/screens/OnboardingScreen.kt: background pop-ups now Optional ("Only if your phone has it"); Autostart copy "Settings > search Autostart (may not exist on all phones)"; tip to turn off "Pause app activity if unused"; location copy (≤ 2 min left).
- ui/screens/PrivacyScreen.kt: 5 m minimum, warm-start copy.

Tests (app/src/test/java/app/focusgate/core/):
- ZoneEvaluatorTest rewritten: singleInsideReading_commitsInside, insideCountsUpTo30mAccuracy, twoOutsideReadings_unblock_oneDoesNot, ambiguousDoesNotResetCommitted_butBreaksOutsideStreak, unusableReadingsIgnored, holdsLastStateUpToWindow_thenUnknown, noUsableReading_staysUnknown, markUnavailable_failsOpenImmediately, thresholds, carryOver (hold, 9 vs 11 min), endToEnd rows 5/6.
- ZoneSettlerTest: locksWithThreeFixesAt15m, doesNotSettle_beforeFiveSeconds, doesNotSettle_above20m (others updated).
- ZoneRulesTest: radius range 5..200, default 10, radiusWarning_below10, sanitize_keepsLegacySmallZones_raisedToMinimum.
- EngineCoreTest: singleInsideReading_blocks_twoOutsideReadings_unblock, warmStart_samplingBeginsAtTwoMinutesLeft_notBefore, warmStart_gateClosed_zeroLocationCalls.
- DataGateTest unchanged (gate closed ⇒ zero location/usage calls).

Privacy: manifest and dependencies untouched (no INTERNET, no GMS, no AccessibilityService). Distance is only in in-memory UI state; no coordinates logged or stored beyond zone centres.

Not committed: the terminal (and so git) was unavailable.

Run: .\gradlew.bat assembleDebug lintDebug testDebugUnitTest --no-daemon --console=plain

## Play Services fused round: WRITTEN, NOT YET BUILT

The terminal was unavailable, so nothing has been compiled, linted or run. Not committed.

Dependency: com.google.android.gms:play-services-location:21.3.0 (exact; the Gradle cache could not be listed with the file tools, which only cover the workspace, so the brief's fallback version was used). Added via libs.versions.toml (`playServicesLocation`) and app/build.gradle.kts. No other dependencies.

Changed files:
- gradle/libs.versions.toml, app/build.gradle.kts
- app/src/main/AndroidManifest.xml: `<uses-permission INTERNET tools:node="remove">` and `ACCESS_NETWORK_STATE tools:node="remove"` (defensive; guarantees no library adds them). Expected from Play Services in the merged manifest: non-exported `com.google.android.gms.common.api.GoogleApiActivity` and `com.google.android.gms.version` meta-data. Check the merged manifest after the build for any other provider/receiver.
- location/FixFreshness.kt (new): pure filter; age from fix elapsedRealtimeNanos; drops > 10 s and same-position re-deliveries with same/older timestamp.
- location/GpsSource.kt: modes AUTO (Google fused → GPS fallback via GoogleApiAvailability check or request failure), GOOGLE_FUSED, GPS_ONLY, PHONE_FUSED. Fused: HIGH_ACCURACY, 2000/1000 ms, 0 m, GRANULARITY_FINE, waitForAccurateLocation false, getCurrentLocation on start. No lastLocation/getLastKnownLocation seed. Gate check first in start and every callback; per-session token drops late callbacks; exposes lastFixTimeElapsedMs and per-source fixCounts (memory only). Engine call unchanged (defaults to AUTO).
- location/LocationSession.kt: GpsTest mode carries the source; real fix age; fixCounts; movedM from the session's first fix (transient); zone setup uses the same AUTO source and freshness filter.
- ui/screens/GpsTestScreen.kt: Auto / Google fused / GPS only chips (test only), "Moved since you opened this screen: X m", fixes per source.
- ui/screens/PrivacyScreen.kt, docs/DESIGN.md §6/§12/§14: Play Services on-device fused location text, no internet permission, request conditions.

Tests: app/src/test/java/app/focusgate/location/FixFreshnessTest.kt (age from fix time, stale dropped, fresh kept, duplicate with old timestamp dropped). DataGateTest untouched.

Run: .\gradlew.bat assembleDebug lintDebug testDebugUnitTest --no-daemon --console=plain

## Session paused 2026-10-23 (resume here)
- **State:** the Play Services fused round (play-services-location 21.3.0, freshness filter, GPS test screen diagnostics) is WRITTEN, NOT BUILT.
- **Next steps for the user:**
  1. Run the external build: `assembleDebug lintDebug testDebugUnitTest`.
  2. Run `aapt2 dump permissions` on app-debug.apk and confirm there is NO INTERNET permission.
  3. Install with `adb install -r`.
  4. Do the walk test from the desk to outside (≥15 m) on the GPS test screen. Check source = Google fused and that "Moved since…" shows ≈15 m. If not, compare with GPS only.
- **Already verified on the device (earlier build):**
  - Timer block, Blocked screen and Go home
  - GPay works (its warning came from the old FocusGuard app's accessibility service; the user should uninstall it)
  - Disable/panic, reboot disarm, Test blocking (background launch OK), survival after Recents swipe and screen-off
- **Open:**
  - Location accuracy (the main issue)
  - Funtouch onboarding copy (updated, not re-verified)
  - Zone edit not implemented (add/delete only)
- **Remaining after location works:** final verification (V1-V13), privacy audit (Opus 5.5), logic audit (GPT 5.6 Terra), final REPORT.md (Sonnet 5.5).
- **Environment:** the Kiro terminal tool is unreliable, so the user runs builds in external PowerShell with JAVA_HOME=C:\Users\Anirudh\.jdks\jbr-21.0.11.

## Play Services fused round: BUILD PASS, verified 2026-10-24
- The user ran the external build: BUILD SUCCESSFUL in 6m 16s, lint clean, unit tests pass.
- `aapt2 dump permissions` on app-debug.apk lists exactly: PACKAGE_USAGE_STATS, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, FOREGROUND_SERVICE_LOCATION, SYSTEM_ALERT_WINDOW, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, app.focusgate.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (AndroidX internal).
- None of these are present: INTERNET, ACCESS_NETWORK_STATE, ACCESS_BACKGROUND_LOCATION, RECEIVE_BOOT_COMPLETED, BIND_ACCESSIBILITY_SERVICE.
- Pending: device walk test.

## Audit fix round: WRITTEN, NOT YET BUILT
Terminal unavailable: not compiled, not tested, not committed.
- Logic HIGH 1 (Blocked screen stays after leaving zone) -> `EngineSnapshot.releases(appId)` in core/Decision.kt (true for HAS_TIME / TIMES_UP_OUTSIDE, not UNKNOWN); ui/BlockedActivity.kt shows "You're outside your zone, enjoy X" and finish()es after 1 s (non-test mode only). Tests: AuditFixTest.blockedApp_releasedAfterTwoOutside_thenInsideBlocksAgainOnce, unknownDoesNotReleaseBlockedScreen.
- Logic HIGH 2 (zone snapshot mismatch) -> GpsSink.onReading carries the zone list used; EngineCore.onLocationReading(…, forZones) drops readings whose zones != config.zones(); EngineCore.onZonesChanged stops and immediately restarts an active session (or stops if no zones / screen off); LocationSession drops readings for another zone set (GpsTestScreen already restarts on zone change). Tests: AuditFixTest.zoneChangeWhileSampling_restartsLocation_andRejectsStaleZoneReadings, zonesRemovedWhileSampling_stopsLocation.
- Logic MEDIUM 3 (no fused watchdog) -> location/FusedWatchdog.kt (20 s first fix, 30 s gap, checked every 5 s on the session looper); GpsSource AUTO tears down fused and starts GPS_PROVIDER; GPS failure -> onUnavailable (fail open). Cancelled in teardown/stop; DataGate first in runnable. Tests: FusedWatchdogTest.*, AuditFixTest.bothProvidersFail_failsOpen.
- Logic LOW 4 (GPS test nearest-zone) -> LocationSession evaluates all zones (engine-equivalent); screen labels "Nearest zone (diagnostic)". UI only, no unit test.
- Freshness boundaries -> FixFreshness rejects timestamp <= 0. Tests: FixFreshnessTest.exactlyTenSecondsAccepted_oneMsMoreRejected, zeroOrNegativeTimestampRejected, futureTimestampCountsAsFresh.
- Multi-zone end-to-end -> AuditFixTest.multiZone_anyInsideBlocks_allOutsideReleases.
- Privacy M1 -> PrivacyScreen headline + Play Services explanation; "on-device" removed from PrivacyScreen, GpsSource KDoc, DESIGN.md §6/§12/§14.
- Privacy L1 -> OnboardingScreen location step adds "or while you set up a zone or run the GPS test".
Run: .\gradlew.bat assembleDebug lintDebug testDebugUnitTest --no-daemon --console=plain

## Audit fix round: BUILD PASS, verified 2026-10-24
- The user ran the external build: BUILD SUCCESSFUL in 3m 27s, lint clean. No test suite has failures or errors.
- New suites: AuditFixTest 6/6, FusedWatchdogTest 2/2, FixFreshnessTest 7/7.
- Pending: device walk test (Google fused movement, auto-dismiss of the Blocked screen on leaving the zone), then the final REPORT.md.

Final docs written: docs/REPORT.md, docs/FINAL-PHONE-TEST.md
