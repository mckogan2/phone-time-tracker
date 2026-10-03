# Play Time ⏰🎮

A small Android app that gives each child their own daily game-time budget on a shared phone.

- **Each child has their own clock** (Or and Shahar start with 45 minutes a day).
- **Time only counts while a chosen game is on screen** and that child has tapped their name.
- **Pause and continue later.** Tap *Pause* (in the app or in the notification). The clock also stops on its own when the screen turns off or nobody has played a game for 5 minutes. The next time a game opens, the phone asks **"Who's playing?"**.
- **Time's up.** At 0:00 the game is covered by a "Time's up!" screen. The other child can still pick their name and play.
- **Warnings** at 5 minutes and 1 minute left.
- **Refills every day at midnight.**
- **Parent controls behind a PIN:** add/edit/remove children, change minutes, +15 min bonus, reset today, stop now, choose which apps count as games, and change the PIN.
- **Settings lock (optional):** opening the phone's Settings app (or the uninstall screen) asks for the parent PIN, so the kids can't switch the guard off.

## How it works

A normal app can't close other apps. Play Time uses an **Accessibility Service** (the "guard") that only looks at *which app* is in front, never at what's on screen. When a game is open without a selected child, or with no time left, the guard puts Play Time's own screen on top of it.

## Getting the app onto the phone

1. **Download the APK on the phone** (sign in to GitHub if asked):
   https://github.com/mckogan2/phone-time-tracker/releases/latest/download/PlayTime.apk
   (Backup: GitHub → **Actions** → latest *Build APK* run → **PlayTime-apk**, a zip to extract.)
2. **Install it.** Open `PlayTime.apk` on the phone and allow your browser/Files app to "install unknown apps". If it says **"App not installed"**, see *Troubleshooting* below.
3. **Android 13 and newer:** Settings → Apps → Play Time → ⋮ (top right) → **Allow restricted settings**. Without this, Android won't let you turn on the guard for an app that didn't come from the Play Store.
4. Open **Play Time** → **🔒 Parents** → create a 4-digit PIN.
5. Tap **Turn on guard** → find *Play Time* in the list → switch it **on**.
6. Tap **Allow notifications** (this shows the remaining time and the Pause button).
7. Tap **Choose games** and tick the games the kids play.
8. Switch on **Lock phone Settings with PIN**.
9. Recommended: Settings → Apps → Play Time → Battery → **Unrestricted**, so the phone doesn't shut the guard down. This matters most on Xiaomi, Huawei, Oppo and Samsung phones.

## Testing it at home

- [ ] Open a chosen game with nobody selected → "Who's playing?" appears.
- [ ] Tap **Or → Play** → the game comes back and the notification shows "Or is playing — 45 min left".
- [ ] Tap **Pause** in the notification → open the game again → it asks "Who's playing?" again.
- [ ] Parents → Edit Or → set to 1 minute → play → "Time's up, Or!" appears and Shahar can still play.
- [ ] Turn the screen off while playing → turn it on and open the game → it asks again.
- [ ] Open the phone's Settings app → it asks for the PIN.
- [ ] Next day, both clocks are back to 45:00.

## Troubleshooting: "App not installed"

- **An older Play Time is installed.** Settings → Apps → search "Play Time" → Uninstall, then install again. (Builds from before Oct 3, 2026 used a different app ID and signing key.)
- **Play Protect** may ask about an unknown app: choose *More details → Install anyway*.
- **Storage full:** free up some space.
- Samsung **Auto Blocker** (Settings → Security and privacy → Auto Blocker) must be off to install apps from outside the store.

Every build is signed with the same key (`app/playtime.keystore`), so new versions install as updates over the old one and keep your settings. Anyone who can see this repository could sign an app with that key; that's fine for a private family app, but keep the repository private.

## Known limits

- A child who knows how to boot into safe mode, or who factory-resets the phone, can get around it.
- Only the apps you choose as games are timed; other apps are not limited.
- If Android or the phone maker turns the guard off (for example after an app update), the kids' screen shows **"Setup needed"** and the parent screen shows **"Guard is off"**.

## Building it yourself

Open the project in Android Studio, or run `./gradlew assembleRelease` with the Android SDK installed (JDK 17+). The code is plain Kotlin with no extra libraries:

| File | What it does |
| --- | --- |
| `GuardService.kt` | Accessibility service: watches the foreground app, counts time, blocks games, Settings lock, notification |
| `Store.kt` | Children, daily usage, games, PIN (salted hash) — saved in SharedPreferences |
| `MainActivity.kt` | Kids' screen / "Who's playing?" / "Time's up" |
| `PinActivity.kt` | PIN pad (create, parent entry, Settings unlock) |
| `ParentActivity.kt` | Parent controls |
| `Ui.kt` | Small helpers for building screens in code |
