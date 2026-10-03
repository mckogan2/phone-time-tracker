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

A normal app can't close other apps. Play Time runs a small background "guard" that checks once a second *which app* is in front (using **Usage access**; it never sees what's on screen). When a game is open without a selected child, or with no time left, the guard covers it with Play Time's own screen (using **Display over other apps**).

Play Time deliberately does **not** use an Accessibility Service: phones (Samsung with Play Protect, for example) refuse to install apps from outside the Play Store that ask for Accessibility access.

## Getting the app onto the phone

1. **Download the APK on the phone** (sign in to GitHub if asked):
   https://github.com/mckogan2/phone-time-tracker/releases/latest/download/PlayTime.apk
   (Backup: GitHub → **Actions** → latest *Build APK* run → **PlayTime-apk**, a zip to extract.)
2. **Install it.** Open `PlayTime.apk` on the phone and allow your browser/Files app to "install unknown apps". If it says **"App not installed"**, see *Troubleshooting* below.
3. Open **Play Time** → **🔒 Parents** → create a 4-digit PIN.
4. Under **Permissions**, tap **Turn on** for each item and come back:
   - **Usage access**: find *Play Time* in the list → allow.
   - **Display over other apps**: switch it on.
   - **Notifications**: allow (shows the time left and the Pause button).
   - **Battery: unrestricted**: allow, so the phone doesn't switch the guard off.

   When the first two are on, the screen shows **✅ Guard is on**.
5. Tap **Choose games** and tick the games the kids play.
6. Switch on **Lock phone Settings with PIN**.

## Testing it at home

- [ ] Open a chosen game with nobody selected → "Who's playing?" appears.
- [ ] Tap **Or → Play** → the game comes back and the notification shows "Or is playing — 45 min left".
- [ ] Tap **Pause** in the notification → open the game again → it asks "Who's playing?" again.
- [ ] Parents → Edit Or → set to 1 minute → play → "Time's up, Or!" appears and Shahar can still play.
- [ ] Turn the screen off while playing → turn it on and open the game → it asks again.
- [ ] Open the phone's Settings app → it asks for the PIN.
- [ ] Restart the phone → the "Play Time is on" notification comes back.
- [ ] Next day, both clocks are back to 45:00.

## Troubleshooting: "App not installed"

- **Test versions installed?** Uninstall "Play Time (test A/B)" first.
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
| `GuardService.kt` | Foreground service: watches the foreground app (Usage access), counts time, covers games (overlay), Settings lock, notification |
| `BootReceiver.kt` | Restarts the guard after a reboot or update |
| `Store.kt` | Children, daily usage, games, PIN (salted hash) — saved in SharedPreferences |
| `MainActivity.kt` | Kids' screen / "Who's playing?" / "Time's up" |
| `PinActivity.kt` | PIN pad (create, parent entry, Settings unlock) |
| `ParentActivity.kt` | Parent controls |
| `Ui.kt` | Small helpers for building screens in code |
