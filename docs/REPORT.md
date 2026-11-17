# FocusGate report

## What FocusGate does
- Sets a daily time limit for each of three apps: Instagram, Chrome, YouTube.
- When an app's time is up, it is blocked only while you are inside a saved zone (for example, your desk or home).
- Leaving the zone unblocks the app. The Blocked screen shows "You're outside your zone" and closes itself. Re-entering the zone blocks the app again.
- Two modes: Essentials (simple) and Insights (adds the decision feed and the GPS test).
- A reboot turns FocusGate off. It stays off until you tap Enable.
- "Disable now" (in the app or the notification) turns everything off at once.
- If the service crash-loops, FocusGate turns itself off.
- While disabled it does zero usage-stats reads and zero location requests.

## Final permissions
Taken from the aapt2 dump of app-debug.apk. These are exactly the permissions in the APK.

| Permission | Why |
|---|---|
| PACKAGE_USAGE_STATS | Read foreground time of the three apps. |
| POST_NOTIFICATIONS | Show the "FocusGate is on" notification with Disable now. |
| FOREGROUND_SERVICE | Keep the monitor running. |
| FOREGROUND_SERVICE_SPECIAL_USE | Foreground service type for the monitor. |
| FOREGROUND_SERVICE_LOCATION | Foreground service type that lets it read location. |
| SYSTEM_ALERT_WINDOW | Show the Blocked screen over another app. |
| ACCESS_FINE_LOCATION | Precise position to test whether you are inside a zone. |
| ACCESS_COARSE_LOCATION | Requested together with fine location (Android requires it). |
| app.focusgate.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION | Added by AndroidX for non-exported receivers. Not a user permission. |

Absent: INTERNET, ACCESS_NETWORK_STATE, ACCESS_BACKGROUND_LOCATION, RECEIVE_BOOT_COMPLETED, QUERY_ALL_PACKAGES, and any accessibility service.

## Third-party libraries touching user data
Only `com.google.android.gms:play-services-location:21.3.0` (fused location).

FocusGate sends nothing anywhere. However, Google Play Services' location service may use GPS, Wi-Fi, mobile signals and Google's servers to compute your position. This is the same as Google Maps. The GPS test screen has a "GPS only" source if you want to avoid it.

The merged manifest gains only a non-exported `GoogleApiActivity` and the `com.google.android.gms.version` meta-data.

## Data leaving the device
None from FocusGate. It has no internet permission. The Play Services caveat above applies to Google's own service, not to FocusGate.

## Data stored locally
One private file, `fg_store` (from `Store.kt`):
- Daily limit per app, and time used today per app, plus the day key.
- A usage cursor timestamp.
- Zones: id, name, centre latitude/longitude, radius. These are the only coordinates stored.
- Armed state, disarm reason, boot identity, armed-at time, restart times (for crash-loop detection), service-alive flag.
- UI settings: mode, theme, feed visible, onboarding done, crash banner.

No usage history and no location history are kept. Live positions exist in memory only. Backup is disabled.

## Verification evidence
- Last external build (`assembleDebug lintDebug testDebugUnitTest`): BUILD SUCCESSFUL, lint clean.
- Unit tests, from the result XMLs: 17 suites, 134 tests, 0 failures, 0 errors.
- Privacy audit: 0 high findings. M1 (misleading "nothing can leave your phone" text) and L1 (location-use wording) were fixed.
- Logic audit: 2 high, 1 medium, 1 low. All four were fixed (Blocked screen now closes on leaving the zone, zone-change snapshot mismatch, fused-location watchdog with GPS fallback, GPS test shows all-zone state).
- The fixes were not re-audited. They are pending the on-device test in `FINAL-PHONE-TEST.md`.
- Earlier device checks on an older build passed: timer block, Blocked screen, Go home, GPay, Disable/panic, reboot disarm, survival after Recents swipe and screen-off.

## Known limitations
- Indoors, GPS is accurate to about 5-15 m. A 10 m zone is the practical minimum, and it may overlap the next room.
- Detection takes about 1 s, so a blocked app can flash before the Blocked screen appears.
- Force-stopping the app or turning off location bypasses blocking (it fails open).
- Unblocking after you leave takes 2 outside readings.
- iQOO/Funtouch background restrictions can stop the service. Turn off "Pause app activity if unused" and allow background activity.
- Zone editing is not implemented. Delete the zone and add it again.

## Build and install
In an external PowerShell window:

```powershell
cd D:\Kirooo\Mods\FocusGate
$env:JAVA_HOME = "C:\Users\Anirudh\.jdks\jbr-21.0.11"
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest --no-daemon --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
