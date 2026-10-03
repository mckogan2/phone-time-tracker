# Play Time ⏰🎮

A small Android app that gives each child their own daily game-time budget on a shared phone.

- **Each child has their own clock** (set during the first-run setup, 45 minutes a day by default).
- **Time only counts while a game is on screen** and that child has tapped their name.
- **Every game is timed automatically.** Apps that are marked as games (most games from the Play Store) are timed by default, including games installed later. Parents can untick a game or add apps that aren't marked as games.
- **Pause and continue later.** Tap *Pause* (in the app or in the notification). The clock also stops on its own when the screen turns off or nobody has played a game for 5 minutes. The next time a game opens, the phone asks **"Who's playing?"**.
- **Time's up.** At 0:00 the game is covered by a "Time's up!" screen. The other child can still pick their name and play.
- **Warnings** at 5 minutes and 1 minute left.
- **Refills every day at midnight.**
- **Parent controls behind a PIN:** add/edit/remove children, change minutes, +15 min bonus, reset today, stop now, choose which apps count as games, and change the PIN.
- **Time bubble on the game:** a small "⏸ 12 min" pill in the child's color shows the minutes left; tap it to pause, drag it out of the way. It turns red in the last minute.
- **Tap your avatar to play:** big round avatars on the kids' screen; one tap starts.
- **Parent playing:** Parents → 15/30/60 min during which your own games on this phone aren't blocked or counted.
- **Today summary:** Parents → Kids shows how long each child played today and on which games (across all family phones).
- **Tidy parent screen:** Kids · Games · Settings tabs; permissions fold into "✅ All set".
- **"Who's playing" protection** (Parents → choose one): off, a **secret animal** each child finds among 6 shuffled ones, a **secret 3-digit number**, or **parent approves** each time. Stops a child from using a sibling's time.
- **Family sync (optional):** the other parent's Android phone shares the same children, settings and daily time. Each family is separate; phones join with a 6-letter family code. No account or sign-in needed.
- **Hebrew and English** (right-to-left in Hebrew). Follows the phone's language; Parents → 🌐 Language switches just Play Time.
- **Settings lock (optional):** opening the phone's Settings app (or the uninstall screen) asks for the parent PIN, so the kids can't switch the guard off.

<div dir="rtl">

## בעברית 🇮🇱

**זמן משחק** נותן לכל ילד תקציב יומי משלו למשחקים בטלפון משותף (45 דקות ביום כברירת מחדל). הזמן נספר רק כשמשחק פתוח והילד לחץ על השם שלו. אפשר לעצור באמצע ולהמשיך אחר כך, והזמן מתחדש כל יום בחצות.

**התקנה:**
1. פתחו בטלפון את הקישור והתקינו: https://github.com/mckogan2/phone-time-tracker/releases/latest/download/PlayTime.apk
2. פתחו את **זמן משחק**. בפעם הראשונה תופיע הגדרה קצרה: **הקמת משפחה חדשה** (קוד הורים ← הוספת הילדים ← קוד לטלפון של ההורה השני) או **הצטרפות למשפחה שלי** (מקלידים את הקוד מהטלפון של ההורה השני).
3. תחת **הרשאות**, לחצו **הפעלה** בכל שורה: גישה לנתוני שימוש, הצגה מעל אפליקציות אחרות, התראות, סוללה ללא הגבלה. כשהשתיים הראשונות פועלות יופיע **✅ השומר פועל**.
4. תחת **משחקים** בדקו את הרשימה (🤖 = נמצא אוטומטית). חסר משחק? **בחירת משחקים**.
5. הפעילו **נעילת הגדרות הטלפון בקוד**.
6. שפה: **🌐 שפה** במסך ההורים (או לפי שפת הטלפון).
**חדש:** בועת זמן על המשחק (לחיצה = הפסקה, גרירה = הזזה), לחיצה על העיגול של הילד כדי לשחק, "הורה משחק" ל-15/30/60 דקות, וסיכום "היום" — כמה כל ילד שיחק ובאילו משחקים.

7. **מי משחק — הגנה:** בחרו תמונה סודית, מספר סודי או אישור הורה, וקבעו לכל ילד את הסוד שלו ב"עריכה".
8. **סנכרון משפחתי:** בטלפון אחד "יצירת משפחה" ← מופיע קוד בן 6 תווים. בטלפון השני "הצטרפות למשפחה" ← מקלידים את הקוד. הזמן של הילדים משותף לשני הטלפונים.

</div>

## How it works

A normal app can't close other apps. Play Time runs a small background "guard" that checks once a second *which app* is in front (using **Usage access**; it never sees what's on screen). When a game is open without a selected child, or with no time left, the guard covers it with Play Time's own screen (using **Display over other apps**).

Play Time deliberately does **not** use an Accessibility Service: phones (Samsung with Play Protect, for example) refuse to install apps from outside the Play Store that ask for Accessibility access.

## Getting the app onto the phone

1. **Download the APK on the phone** (sign in to GitHub if asked):
   https://github.com/mckogan2/phone-time-tracker/releases/latest/download/PlayTime.apk
   (Backup: GitHub → **Actions** → latest *Build APK* run → **PlayTime-apk**, a zip to extract.)
2. **Install it.** Open `PlayTime.apk` on the phone and allow your browser/Files app to "install unknown apps". If it says **"App not installed"**, see *Troubleshooting* below.
3. Open **Play Time**. The first time, a short setup asks:
   - **Start a new family**: create a 4-digit parent PIN → add your children and their daily minutes → choose whether the other parent's phone will join (shows a code).
   - **Join my family**: type the code from the other parent's phone (Parents → Family sync → Add another phone). The children, PIN and settings come from that phone.

   Setup ends in **🔒 Parents** for the next steps.
4. Under **Permissions**, tap **Turn on** for each item and come back:
   - **Usage access**: find *Play Time* in the list → allow.
   - **Display over other apps**: switch it on.
   - **Notifications**: allow (shows the time left and the Pause button).
   - **Battery: unrestricted**: allow, so the phone doesn't switch the guard off.

   When the first two are on, the screen shows **✅ Guard is on**.
5. Under **Games**, check the list: games marked 🤖 were found automatically. If a game is missing, tap **Choose games** and tick it.
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

## Updates

Parents → Settings → **🔄 Update app** downloads the newest `PlayTime.apk` in the browser; tap it and choose **Install**. Settings, children and family stay.

How it works: every build is also published (as `v1.0.<build number>`) to the public, code-free repo [`mckogan2/play-time-releases`](https://github.com/mckogan2/play-time-releases), because this code repo is private. One-time setup for the owner:
1. Create the public repo `play-time-releases` (with a README).
2. Create a fine-grained token: only that repo, **Contents: Read and write**, 1-year expiry.
3. Save it here as the Actions secret `RELEASES_TOKEN`. Until then the publish step is skipped.

**עדכון:** הורים ← הגדרות ← **🔄 עדכון האפליקציה** ← לוחצים על הקובץ שירד ← "התקנה". ההגדרות נשמרות.

## Family sync

**For a family:** on the first phone, Parents → **Family sync** → **Create family**. A 6-letter code appears (valid 24 hours). On the other phone: Parents → **Join family** → type the code. The second phone takes the family's children and settings. Time used on either phone counts for both. Works offline and catches up when back online. **Leave family** turns sharing off again (the phone keeps a copy of the children).

**For the app owner (one time):** sync needs one Firebase project that serves all families. Each family only ever sees its own data (see `firestore.rules`).

1. Go to https://console.firebase.google.com → **Create a project** (e.g. "play-time"). Google Analytics is not needed.
2. In the project: **Add app → Android**. Package name: `com.mckogan.playtime`. Register, then **download `google-services.json`**.
3. **Build → Authentication → Get started → Sign-in method → Anonymous → Enable → Save.**
4. **Build → Firestore Database → Create database** → Production mode → pick a location near you (e.g. `europe-west`) → Create.
5. In Firestore → **Rules**: replace everything with the contents of [`firestore.rules`](firestore.rules) → **Publish**.
6. Put `google-services.json` in the `app/` folder of this repository. The next build includes sync. (The values in it are not secret: access is controlled by the rules and the family codes.)

Until step 6, the app builds and works normally; the Family sync section just says it isn't available yet.

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
| `KidCheckActivity.kt` | "Is it really you?" check: secret animal, secret number or parent PIN |
| `Sync.kt` | Family sync with Firebase (Firestore + anonymous sign-in) |
| `BootReceiver.kt` | Restarts the guard after a reboot or update |
| `Store.kt` | Children, daily usage, games, PIN (salted hash) — saved in SharedPreferences |
| `MainActivity.kt` | Kids' screen / "Who's playing?" / "Time's up" |
| `PinActivity.kt` | PIN pad (create, parent entry, Settings unlock) |
| `ParentActivity.kt` | Parent controls |
| `Ui.kt` | Small helpers for building screens in code |
