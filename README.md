# ACIM Reminder

A small, personal Android app that reminds you to pause and practice your
*A Course in Miracles* workbook lesson, and runs a gentle 5-minute meditation
with a soft bell at the start and end.

It follows a **per-day schedule** across the **complete 365-lesson workbook**:
each calendar day shows that day's lesson — with Marianne Williamson's video
playing right in the app and the lesson text (italic emphasis preserved) —
advancing automatically at midnight and cycling round the year.

---

## The daily schedule

The lessons live in `app/src/main/assets/lessons.json`. The date-to-lesson
mapping is anchored in `Lessons.java`:

- `DAY_ONE` = the calendar day the sequence starts on (currently **2026-07-20**)
- `DAY_ONE_NUMBER` = the lesson shown that day (**98**, so Lesson 99 falls on 2026-07-21)

Each following day advances one lesson, and the list **cycles** — after Lesson
365 it wraps back to Lesson 1, so the schedule never runs dry. **To shift the
whole schedule by a day, change `DAY_ONE`.**

Day 1 is special: it shows the Workbook **Introduction and Lesson 1 together**,
taken from Marianne's "Welcome" email.

**How the lesson list is built:** the raw lesson emails are saved under
`emails/`, and `python3 tools/finalize_lessons.py` parses them into
`app/src/main/assets/lessons.json` (recovering each Vimeo link and keeping the
italic passages). To rebuild after changing an email, rerun that script and
commit.

---

## What it does

- **Reminders:** every hour on the hour from **06:00 to 22:00** (17 times a day)
  your phone shows a notification titled **"Time to practice"** with the line
  of *today's* lesson.
- **Begin button on the notification:** starts the 5-minute meditation straight
  away, without opening the app.
- **Tap the notification itself:** opens the app to the full lesson, with a
  **Begin** button there too.
- **Watch today's video:** plays inline right above the lesson text, so you
  can watch (or just listen) while reading along — no browser or the Vimeo
  app opens.
- **The meditation:** a soft bell rings, a **live 5-minute countdown** ticks
  down in your notification shade, and the bell rings again at the end — even if
  your screen is off and your phone is idle.

---

## How to install it on your phone

You don't need a computer. Everything is built for you automatically in the
cloud (GitHub Actions) and posted as a downloadable file (a "Release").

### 1. Download the app file (APK)

1. On your phone, open this repository on GitHub.
2. On the right-hand side (or under the "⋯" menu) tap **Releases**.
   Direct link: `https://github.com/theexperiencelab/acim-reminder/releases/latest`
3. Under the newest release, find the file **`acim-reminder-lesson99.apk`** and
   tap it. It downloads to your phone.

### 2. Allow installing it

Because this isn't from the Play Store, Android will ask permission the first
time:

1. Open the downloaded `acim-reminder-lesson99.apk` (tap it in your notifications
   or in **Files → Downloads**).
2. Android says it can't install from this source → tap **Settings**.
3. Turn on **Allow from this source** (this is the "install unknown apps"
   setting), then go **back** and tap **Install**.
4. Open the app.

> If Android's Play Protect pops up with a warning about an unknown app, choose
> **Install anyway** — it just means the app isn't from the store, which is
> expected for a personal sideloaded app.

### 3. The one-time setup inside the app

When you first open the app you'll see an **"One-time setup"** card with up to
three buttons. Tap each one and accept:

1. **Allow notifications** — so you can actually see the reminders and the
   countdown. Tap **Allow**.
2. **Allow exact alarms** — so reminders fire at the exact minute. On most
   phones this is already on (nothing to do); if the button appears, tap it and
   enable **"Allow setting alarms and reminders."**
3. **Stop battery-optimising this app** — so Android doesn't put the app to
   sleep and swallow your reminders. Tap it and choose **Allow** / **Don't
   optimise**.

When all three are done the card says **"Setup complete — you're all set."**

That's it. Leave the app; the reminders will start arriving on the hour.

> **Tip — bell volume:** the bells deliberately use your phone's **Alarm**
> volume (not media volume), which is what lets the closing bell ring reliably
> when your screen is off. If you can't hear the bell, turn up the Alarm volume.

---

## Trying it right now

You don't have to wait for the top of the hour. Open the app and tap
**"Begin 5-minute meditation"** — the opening bell rings, the countdown starts,
and the closing bell rings five minutes later. You can lock your phone and put
it in your pocket; the end bell will still ring.

---

## Swapping the bell sound later

The two bells live here:

```
app/src/main/res/raw/bell_start.wav
app/src/main/res/raw/bell_end.wav
```

To use your own sound, replace either file with your own audio (keep the same
file name — `.wav`, `.mp3`, or `.ogg` all work), commit the change, and the next
build will include it. The two current bells were generated by
`tools/make_bells.py` if you ever want to regenerate or tweak them.

---

## Notes for Android 17

This app was built and checked against the current Android 17 (API 37) rules,
then set to **target API 36 (Android 16)** for one practical reason: the Android
17 build platform isn't published to the Android build tools yet, so nothing can
compile against it. Targeting 36 builds today and runs perfectly on Android 17.

- **Exact alarms:** it uses `USE_EXACT_ALARM` (the "alarm clock app" permission),
  which is granted automatically at install, so reminders can fire through Doze.
- **Background audio:** Android 17 tightened background audio, but the strictest
  "while-in-use" muting rule only applies to apps that *target* Android 17. By
  targeting 16, this app is exempt from that rule and stays compliant through the
  meditation's foreground service. The bells are still tagged as **alarm** audio,
  which is why the closing bell rings even with the screen off — and why the bell
  follows your **Alarm** volume.
- **Foreground service:** the meditation runs as a `mediaPlayback` foreground
  service, and the end bell is triggered by an explicit exact alarm rather than an
  in-app timer the system could pause.

No conflicts were found with what this app needs to do. When the Android 17 build
platform is published, bumping the target to 37 is a one-line change.

---

## For the curious: how the project is laid out

```
app/src/main/java/com/acimreminder/app/
  MainActivity.java       the single screen: lesson text, Begin, setup card
  Lesson.java             the hardcoded Lesson 99 text
  Scheduler.java          arms the 16 daily reminder alarms
  ReminderReceiver.java   posts each "Time to practice" notification
  BeginReceiver.java      the notification's Begin button
  MeditationService.java  the 5-minute timer, countdown notification, bells
  BellPlayer.java         plays a bell as alarm audio
  EndBellReceiver.java    the exact alarm that fires the closing bell
  BootReceiver.java       re-arms reminders after a reboot
  Notify.java             notification channels
app/src/main/res/raw/     the two bell sounds
.github/workflows/build.yml   builds the APK and posts the Release
```

The whole app is **debug-signed** by GitHub's build — no keys or accounts
required, which is exactly right for a personal, sideloaded app.
