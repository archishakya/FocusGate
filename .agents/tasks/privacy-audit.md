# FocusGate privacy & permissions audit

Static review only (terminal unavailable; no gradle run). Sources, manifest, merged manifest
(`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`) and build files read.

| # | Requirement | Result | Evidence |
|---|---|---|---|
| 1 | No network / analytics; only play-services-location | PASS (code) | `app/build.gradle.kts:56-70` deps: androidx, coroutines, `play.services.location`, junit only. Manifest `AndroidManifest.xml:13-14` removes INTERNET / ACCESS_NETWORK_STATE; aapt2 confirms no INTERNET. No `URL(`/`HttpURLConnection`/`Socket`/`WebView` in `src/main`. Merged manifest:80-88 adds only `GoogleApiActivity` (exported=false) and `gms.version` meta-data; no GMS providers/receivers/services, no Firebase. |
| 2 | No AccessibilityService | PASS | No match for `AccessibilityService` in `src/main`; no such service in merged manifest. |
| 3 | Disabled = zero data access | PASS | Only `queryEvents` call: `UsageEventSource.kt:18`, gated at :15 (`isOpen` + `armedAtTs` floor). Only location calls: `GpsSource.kt:125,142,195`, gated at `GpsSource.start` :68; every callback re-checks gate and calls `stop()` (:113,127,144,180,188,214). `LocationSession.start` gated :78, stops on Disarmed :116-118 and per-reading :93. `EngineCore.kt:117,164,172,198,369` gate before each read/start. `Engine.kt:62,123,191` gate checks; `Engine.stop` calls `gps.stop()` :215. `ArmController.disarm` closes gate first :87. Zone setup/GPS test are refused while disarmed (stricter than allow-list). |
| 4 | Only 3 packages processed; minimal persistence | PASS | `UsageEventSource.kt:24-25` discards non-monitored before any other field read. Store writes (`Store.kt:55,68-91,121-122,153-189`): allowances, per-app ms today + day key, cursor, zones, armed/boot/restart markers, UI prefs. No history. |
| 5 | No location history; transient only | PASS (code) | `GpsSource.kt:208-227` copies primitives, no `Location` field kept; no `lastLocation` seed (:139). `LocationSession` distance/moved/first fix memory only, cleared in `stop()` :218-229. `Engine.kt:80-83` drops lat/lon. Only coordinate persistence is zone centres `Store.kt:121`. No lat/lon in Notifications, DecisionFeed (`DecisionFeed.kt:4`). Sampling predicate `EngineCore.kt:195-196` (screenOn + zones + precise + FGS location type + ≤120 s left). |
| 6 | No sensitive logging | PASS | No `Log.`, `println`, `Timber`, `printStackTrace` in `src/main`. |
| 7 | Backup, exports, intents | PASS | `allowBackup=false` `AndroidManifest.xml:27`; `backup_rules.xml` and `data_extraction_rules.xml` exclude all domains. Merged manifest: only launcher `MainActivity` exported; startup provider / profileinstaller receiver removed (`AndroidManifest.xml:56-60`). No deep links/WebView/providers/servers. Service intents explicit (`ArmController.kt:77,93`, `Notifications.kt:44,50,57`); all PendingIntents `FLAG_IMMUTABLE` (`Notifications.kt:45,51,58`). Dynamic receiver `RECEIVER_NOT_EXPORTED` `Engine.kt:72`. |
| 8 | Permission timing & honest text | FAIL | Permission requests are user-tapped only (`Components.kt:109,132` invoked from buttons in Onboarding/Home/Insights/ZoneEditor/GpsTest). Privacy text overclaims, see M1. |
| 9 | No BOOT_COMPLETED / QUERY_ALL_PACKAGES / BACKGROUND_LOCATION | PASS | Merged manifest permissions lines 11-34; `<queries>` limited to 3 packages `AndroidManifest.xml:17-21`. |

## Findings

- M1 (MEDIUM, misleading text) `ui/screens/PrivacyScreen.kt:50` "No internet permission, so nothing can leave your phone." and `PrivacyScreen.kt:38` "Google Play Services' on-device fused location". FocusGate itself sends nothing, but Google fused location runs in Play Services, which may use Wi-Fi/cell scans and Google's network location service (Google Location Accuracy) and has its own network access. The headline guarantee no longer holds for the approved Play Services source.
  Fix: reword to e.g. "FocusGate has no internet permission and sends nothing. Location comes from Google Play Services when available; Play Services may use Wi-Fi and mobile networks under your phone's Google Location Accuracy setting. Use 'GPS only' if you want none of that." Drop "on-device" or qualify it; same in the `GpsSource.kt:36-37` KDoc.
- L1 (LOW, hygiene) `ui/screens/OnboardingScreen.kt:107` says location is "only used when an app has 2 minutes or less left and your screen is on", omitting zone setup and GPS test sampling (which `PrivacyScreen.kt:39-40` mentions). Fix: add "or while you set up a zone or run the GPS test".

## Unverified (needs device)
- Play Services does not itself add network traffic attributable to FocusGate's UID (check with a traffic monitor / `dumpsys netstats`).
- Location indicator disappears immediately on disarm, panic, screen off and leaving the GPS test / zone setup screens.
- No location indicator while armed with >2 min left on every app.
- Post-reboot and crash-loop paths leave the gate closed and do no queries (`ArmRestore` logic read, not run).
- Release-variant merged manifest (only debug was inspected).
