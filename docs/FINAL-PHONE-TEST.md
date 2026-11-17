# FocusGate final phone test (iQOO 9, Android 14, Funtouch 14)

About 20-30 minutes. Do it in order. Write seconds and notes in each blank.

## 0. Prep (at your desk)
- [ ] Uninstall the old FocusGuard app.
- [ ] Install: `adb install -r D:\Kirooo\Mods\FocusGate\app\build\outputs\apk\debug\app-debug.apk`
- [ ] Open FocusGate.
- [ ] Zones: delete all existing zones.
- [ ] Set limits: Instagram 1 min, Chrome 2 min, YouTube 2 min.
- [ ] App info -> FocusGate: turn OFF "Pause app activity if unused".

Result / notes: ______________________________________

## 1. Setup
- [ ] Tap Enable. Grant each permission with the onboarding buttons.
- [ ] Notification "FocusGate is on" shows, with a Disable now action.

Result / notes: ______________________________________

## 2. Zone (at your desk)
- [ ] Zones -> add -> "Use my current location" -> radius 10 m -> save.

Result / notes: ______________________________________

## 3. GPS walk test (Insights -> GPS test)
- [ ] At desk: Source = Google fused, state = Inside. Wait 30 s.
- [ ] Walk at least 15 m outside the house. "Moved since" shows about 15 m.
- [ ] State switches to Outside. Seconds it took: ______
- [ ] Walk back to the desk. State switches to Inside. Seconds it took: ______
- [ ] If it does not move or switch: set Source = GPS only and repeat.
  - GPS only, Outside after ______ s, Inside after ______ s

Take screenshots of the Inside, Outside and "Moved since" readings.

Result / notes: ______________________________________

## 4. Timer + location block
- [ ] At desk, use Instagram for 1 min. Blocked screen appears.
- [ ] Walk outside. Blocked screen shows "You're outside your zone" and closes itself. Instagram is usable. Seconds: ______
- [ ] Walk back in. Instagram is blocked again. Seconds: ______
- [ ] Chrome: once its 2 min are up, repeat the in/out/in check once. Result: ______
- [ ] YouTube: once its 2 min are up, repeat the in/out/in check once. Result: ______

Result / notes: ______________________________________

## 5. Timer accuracy
- [ ] Compare FocusGate minutes with phone Settings > Screen time for Instagram.
  - FocusGate: ______ min   Screen time: ______ min

Result / notes: ______________________________________

## 6. GPay
- [ ] With FocusGate on, open GPay and make or view a payment.
- [ ] No accessibility warning appears.

Result / notes: ______________________________________

## 7. Disable now
- [ ] Tap Disable now in the app. No blocking, notification gone.
- [ ] Tap Enable again. Notification returns.
- [ ] Try Disable now from the notification too.

Result / notes: ______________________________________

## 8. Reboot
- [ ] Restart the phone.
- [ ] Instagram is not blocked and there is no FocusGate notification.
- [ ] Open FocusGate. It shows off.
- [ ] Enable works.

Result / notes: ______________________________________

## 9. Survival
- [ ] Swipe FocusGate away from Recents.
- [ ] Lock the screen for 5 min.
- [ ] Unlock and repeat step 4 quickly (inside -> blocked, outside -> unblocked).

Result / notes: ______________________________________

## 10. Privacy spot-check
- [ ] App info -> FocusGate -> Mobile data & Wi-Fi shows 0 B.

Result / notes: ______________________________________

## What to send back
- Screenshots of steps 3 and 4, and the seconds you noted.
- Any failure, with what you did just before it.
