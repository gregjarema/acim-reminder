# A Course in Miracles

A small, personal Android app for practising *A Course in Miracles*, in two tabs:

- **Workbook** — reminds you to pause and practice the day's workbook lesson,
  and runs a gentle 5-minute meditation with a soft bell at the start and end.
- **Text** — Marianne Williamson's daily Text sessions, read at your own pace.

Both tabs play Marianne's video right in the app, and text everywhere is
selectable, so you can long-press any passage to copy it.

The two sides keep time differently, on purpose. The Workbook is **pinned to the
calendar** — one lesson per day, advancing at midnight, whether or not you open
it. The Text **waits for you** — it stays on the day you're up to until you mark
it read, so falling behind costs you nothing, and you can read three in one
sitting when you want to catch up.

---

## The workbook schedule

The lessons live in `app/src/main/assets/lessons.json`.

**You choose where to begin.** On first run the app asks: start at Lesson 1, or
pick up wherever you already are. Whatever you choose becomes *today*, and the
schedule advances one lesson per calendar day from there. Changed your mind, or
mistapped? **Jump to lesson…** on the Workbook tab re-anchors it at any time.

The list **cycles** — after Lesson 365 it wraps back to Lesson 1, so the
schedule never runs dry.

`DAY_ONE` / `DAY_ONE_NUMBER` in `Lessons.java` are only the built-in fallback,
used until you make that first choice.

Day 1 is special: it shows the Workbook **Introduction and Lesson 1 together**,
taken from Marianne's "Welcome" email.

**How the lesson list is built:** the raw lesson emails are saved under
`emails/`, and `python3 tools/finalize_lessons.py` parses them into
`app/src/main/assets/lessons.json` (recovering each Vimeo link and keeping the
italic passages). To rebuild after changing an email, rerun that script and
commit.

---

## The daily Text

The Text tab has **no schedule at all** — just a stored bookmark. It opens on
whatever day you were last on, and moves only when you tell it to:

- **Mark read** advances one day. Tap it three times and you've read three.
- **Previous** steps back, to re-read.
- **Jump to day…** opens the whole list, scrolled to where you are, so you can
  go anywhere without clicking through 400+ days.

Your place survives closing the app, rebooting, and installing a new build.

**How the Text list is built:** the readings come from the authoritative
members.marianne.com scrape kept outside this repo in `~/Documents/ACIM/Text`.
`python3 tools/build_text_days.py` turns it into
`app/src/main/assets/text_days.json`.

That script does one thing worth knowing about. Marianne's session videos are
**unlisted**, so `vimeo.com/1040972397` on its own is a dead link — it only
opens as `vimeo.com/1040972397/f69a55d4a3`, with the access hash. The scrape's
`days_canonical.json` keeps only the bare id, so the script recovers each hash
from the saved day pages in `.work/days_raw/`. That gives the Text videos the
same two-part URL the Workbook lessons already use, which is why the same inline
player handles both.

As more sessions are transcribed, rerun the script and commit — the app clamps
your stored day to whatever shipped, so a day that's out of range today simply
becomes reachable once it lands.

---

## Playing the videos

Both tabs play Marianne's video inline. Everything about how that works is
shaped by one constraint: **these videos are whitelisted to specific domains**,
so Vimeo's embed player refuses to run anywhere else. The app therefore loads
Vimeo's ordinary watch page in a WebView, and the page's own chrome is hidden
afterwards by walking up from the player element and hiding its siblings.

That constraint decides the rest:

- **Media buttons and lock-screen controls** work through a MediaSession in a
  foreground service. Its callbacks drive the video by injecting script into the
  page — since the watch page is the top-level document, the `<video>` element
  is reachable, and that's the only handle on playback there is.
- **Audio keeps playing** when you leave the app, because that service is of
  type `mediaPlayback`. Without one, Android may silence the app as soon as it
  leaves the screen.
- **Listen only** hides the picture and keeps the sound. There's no audio-only
  stream to switch to, so it shrinks the player to a 1px *invisible* box rather
  than removing it — a WebView with no size stops playing altogether.

---

## Saved passages

Highlight anything in the Workbook or the Text and choose **Save passage** from
the selection menu that appears — the same menu as Copy and Share, so there's no
new gesture. You can add a note while saving, and long-press a saved passage
later to edit that note or remove it.

They collect on the **Saved** tab in Course order — `T-13.II.5`, `W-104.3` —
not in the order you happened to save them. The chapter and section come from
the day's label; the paragraph is recovered by scanning back to the nearest
paragraph number in the reading itself.

Each saved passage keeps **its own copy of the text**. Anchoring by position in
the lesson would break every time the readings are rebuilt from source, which
happens whenever more Text days are transcribed.

**Copy all** puts the whole collection on the clipboard as plain text.

---

## What it does

- **Reminders:** every hour on the hour from **06:00 to 22:00** (17 times a day)
  your phone shows a notification whose headline *is* the idea of *today's*
  lesson. Where the lesson gives a fuller two-line form to hold during practice
  (e.g. Lesson 104's *"I seek but what belongs to me in truth, / And joy and
  peace are my inheritance."*), the reminder offers the whole verse.
- **Begin button on the notification:** starts the 5-minute meditation straight
  away, without opening the app.
- **Tap the notification itself:** opens the app to the full lesson, with a
  **Begin** button there too.
- **Watch today's video:** plays inline right above the lesson text, so you
  can watch (or just listen) while reading along — no browser or the Vimeo
  app opens.
- **The meditation:** a soft bell rings, a **live 5-minute countdown** ticks
  down — in your notification shade *and* right in the app, so you can see it
  in whichever place is in front of you — and the bell rings again at the
  end, even if your screen is off and your phone is idle. Tap the countdown
  in the app to stop early.
- **The Text tab:** the day you're up to, with Marianne's session video playing
  inline the same way, and the reading below it — headings, paragraph numbers
  and the FIP sentence numbers as superscripts. No timer here, and no
  notifications: the Text is there when you want it.
- **Copy anything:** text is selectable throughout, so you can long-press a
  passage in either tab and copy it out.

---

## How to install it on your phone

You don't need a computer. Everything is built for you automatically in the
cloud (GitHub Actions) and posted as a downloadable file (a "Release").

### 1. Download the app file (APK)

1. On your phone, open this repository on GitHub.
2. On the right-hand side (or under the "⋯" menu) tap **Releases**.
   Direct link: `https://github.com/theexperiencelab/acim-reminder/releases/latest`
3. Under the newest release, find the file **`acim-reminder.apk`** and
   tap it. It downloads to your phone.

### 2. Allow installing it

Because this isn't from the Play Store, Android will ask permission the first
time:

1. Open the downloaded `acim-reminder.apk` (tap it in your notifications
   or in **Files → Downloads**).
2. Android says it can't install from this source → tap **Settings**.
3. Turn on **Allow from this source** (this is the "install unknown apps"
   setting), then go **back** and tap **Install**.
4. Open the app.

> If Android's Play Protect pops up with a warning about an unknown app, choose
> **Install anyway** — it just means the app isn't from the store, which is
> expected for a personal sideloaded app.

### 3. The one-time setup inside the app

The app walks you through one step at a time, and won't let you skip:

1. **Allow notifications** — so you can actually see the reminders and the
   countdown. Tap **Allow**.
2. **Stop battery-optimising this app** — so Android doesn't put the app to
   sleep and swallow your reminders. This prompt fires by itself; choose
   **Allow** / **Don't optimise**.
3. **Where to start** — begin at Lesson 1, or tap *"I'm further along"* and pick
   the lesson you're actually on. That becomes today.

Then you land on the lesson. Leave the app; the reminders start arriving on the
hour.

(Exact alarms need no prompt — the app holds `USE_EXACT_ALARM`, which Android
grants at install.)

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
  MainActivity.java       the single screen, in two tabs: Workbook and Text
  Lesson.java             one workbook lesson
  Lessons.java            loads lessons.json; maps calendar dates to lessons
  TextDay.java            one day of the Text reading
  TextDays.java           loads text_days.json; remembers the day you're on
  Scheduler.java          arms the 16 daily reminder alarms
  ReminderReceiver.java   posts each hourly lesson reminder notification
  BeginReceiver.java      the notification's Begin button
  MeditationService.java  the 5-minute timer, countdown notification, bells
  BellPlayer.java         plays a bell as alarm audio
  EndBellReceiver.java    the exact alarm that fires the closing bell
  BootReceiver.java       re-arms reminders after a reboot
  Notify.java             notification channels
app/src/main/assets/      lessons.json (workbook) + text_days.json (Text)
app/src/main/res/raw/     the two bell sounds
tools/finalize_lessons.py builds lessons.json from the saved lesson emails
tools/build_text_days.py  builds text_days.json from the ACIM/Text scrape
.github/workflows/build.yml   builds the APK and posts the Release
```

The whole app is **debug-signed** by GitHub's build — no keys or accounts
required, which is exactly right for a personal, sideloaded app.
