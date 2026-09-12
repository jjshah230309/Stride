# Stride

A GPS run and ride tracker for iPhone with spoken splits, segments, and a full
training log. Everything runs on the phone. No account, no server, no paywall.

Built as a native SwiftUI app with no third-party dependencies. Four tabs —
Today, Record, Progress, Routes — in a fixed light palette: off-white pages,
near-black forest-green hero cards, and a lime accent for every primary action.

The app icon is the same week-bar motif that appears on the Today screen: four
leaning capsules, deep green on lime. It ships in all three iOS appearances —
light, dark and tinted — and is regenerated from the palette by
`Tools/make-icon.py`, so changing a colour there and re-running is all it takes.

---

## Installing it on your iPhone without a paid developer account

You do not need the $99/year Apple Developer Program. A **free Apple ID** is
enough. The one catch is that free signing expires after **7 days**, after which
you plug the phone in and press Run again (your data survives — it is an upgrade
install, not a fresh one).

### 1. Install Xcode

Xcode is free from the Mac App Store. It is a large download (roughly 10–17 GB),
so start it before anything else:

```
open "macappstore://apps.apple.com/app/xcode/id497799835"
```

Once installed, open it once and let it finish installing components.

### 2. Add your Apple ID to Xcode

Xcode → Settings (⌘,) → **Accounts** → **+** → Apple ID → sign in with your
normal Apple ID. A team called **"<your name> (Personal Team)"** appears. That is
the free signing team.

### 3. Open the project

```
open ~/Stride/Stride.xcodeproj
```

### 4. Set the signing team

In the left sidebar click the blue **Stride** project icon → select the **Stride**
target → **Signing & Capabilities** tab.

- Tick **Automatically manage signing**
- **Team**: choose your Personal Team

If you see *"Failed to register bundle identifier"*, the ID is already taken by
someone. Change **Bundle Identifier** to something unique — for example
`com.yourname.stride2` — and it will register.

### 5. Turn on Developer Mode on the iPhone

Plug the iPhone into the Mac with a cable. Tap **Trust** on the phone.

On the phone: **Settings → Privacy & Security → Developer Mode → on**, then
restart the phone when it asks. (This setting only appears once a Mac with Xcode
has been connected.)

### 6. Build and run

In Xcode's toolbar, click the device selector (next to the Stride scheme) and
pick your iPhone. Then press **⌘R**.

The first build takes a couple of minutes.

### 7. Trust the app on the phone

The first launch will fail with *"Untrusted Developer"*. On the phone go to
**Settings → General → VPN & Device Management**, tap your Apple ID under
*Developer App*, and tap **Trust**.

Press **⌘R** in Xcode again, or just tap the Stride icon on the home screen.

### Keeping it alive past 7 days

The 7-day expiry is Apple's, not this app's, and it applies to every free-account
sideloading method — Xcode, AltStore, SideStore, Sideloadly, all of them. Nothing
can extend it. What you *can* do is make the renewal happen by itself.

**Free, automatic (recommended).** `Tools/stride-refresh.sh` rebuilds and
reinstalls Stride using Apple's own command-line tools, over Wi-Fi, without
opening Xcode. A launchd agent runs it twice a day, so the signature is never
more than half a day old. Your activities survive — it is an upgrade install, not
a fresh one.

Set it up once, after you have run Stride from Xcode at least once:

```
~/Stride/Tools/install-auto-refresh.sh
```

For it to work over Wi-Fi, tick **Connect via network** for your iPhone in
Xcode → Window → Devices and Simulators. Otherwise it refreshes whenever the
phone happens to be plugged in.

If the phone has not been reachable for five days, you get a notification saying
how long is left. Logs are in `.build/refresh.log`. To turn it off:
`~/Stride/Tools/uninstall-auto-refresh.sh`.

**Paid, no refreshing at all.** The Apple Developer Program is 99 USD a year and
raises the signing period from 7 days to a full year. Nothing in the project
changes — pick the paid team instead of the Personal Team in Signing &
Capabilities, build once, and forget about it until next year. It also lifts the
three-app limit.

**AltStore / SideStore.** Both re-sign sideloaded apps on a schedule the same way
the script above does. SideStore can refresh from the phone alone after a
one-time setup with a computer, which is useful if the Mac is often off. They
still sign with your free Apple ID, so it is still a 7-day cycle underneath.

**TrollStore.** Genuinely permanent, no signing at all — but the CoreTrust bug it
relies on was fixed in iOS 17.0.1, so it only works on iOS 17.0 and earlier.

---

## Signing notes

The project already carries the development team (`DEVELOPMENT_TEAM` in the build
settings), so it builds and signs without you touching Signing & Capabilities.
If you ever regenerate the project file, that setting is lost and the device
build fails with *"Signing for Stride requires a development team"* — put it back
rather than re-picking the team by hand.

**HealthKit works on a free personal team.** This was an open question when the
app was first written; it is now confirmed. A signed device build carries the
`com.apple.developer.healthkit` entitlement under a Personal Team certificate.

Verified on Xcode 26.6 — both of these produce zero errors and zero warnings:

```bash
xcodebuild -project Stride.xcodeproj -scheme Stride -destination 'generic/platform=iOS Simulator' build
```

```bash
xcodebuild -project Stride.xcodeproj -scheme Stride -destination 'generic/platform=iOS' -allowProvisioningUpdates build
```

The resulting bundle is signed with the HealthKit entitlement, `UIBackgroundModes`
of `location` and `audio`, and every usage description in place.

---

## First run

Stride asks for three things:

| Permission | Why | Optional? |
|---|---|---|
| Location (choose **Always**) | Recording your route with the screen off | Required |
| Motion & Fitness | Barometric elevation and running cadence | Recommended |
| Apple Health | Heart rate in, workouts out | Optional |

Audio needs no permission — splits are spoken over your music, ducking it
briefly rather than stopping it, and it works with the phone locked.

---

## Apple Watch heart rate

Stride reads heart rate from Apple Health, which is where your Watch puts it.
There is one thing you have to do, and one honest limitation behind it.

**Start a workout on the Watch when you start one here.** Any workout type will
do. The Watch only measures heart rate every few seconds while one of its own
workout sessions is running; the rest of the time it takes a reading every
several minutes to save power. That is the Watch's behaviour, not a setting, and
no iPhone app can reach across and change it. Without a workout on the wrist you
will get a handful of readings across an hour rather than a continuous trace.

With a Watch workout running, heart rate appears live on the Record screen
labelled **Apple Watch**, and the Record screen tells you if it is not arriving.

**Filling in after saving.** The Watch syncs its samples to the phone in bursts,
so the live figure lags and a recording often ends with gaps. When you save,
Stride re-reads the whole activity window from Health and attaches every sample
the Watch actually recorded to the matching point on your track, then recomputes
everything that depends on it — splits, zones, Relative Effort, calories and
training load. Readings already captured live from a chest strap are left alone,
and a genuine dropout is left empty rather than filled with a stale number.

Heart rate is taken from the best source available:

| Source | When it is used |
|---|---|
| Bluetooth chest strap | Whenever one is connected — the most responsive, and no Watch workout needed |
| Apple Health | Whatever your Watch has synced across, live and again on save |

A live reading older than 45 seconds is treated as stale and dropped rather than
left frozen on screen pretending to be current.

Stride also writes finished activities back to Health as a proper workout with
its GPS route, so they show up in Fitness and count towards your rings.

## File formats

Activities import and export in all three formats the sport world uses:

| | Import | Export |
|---|---|---|
| **GPX** | yes | yes |
| **TCX** | yes | yes |
| **FIT** | yes | yes |

Export from any activity's ⋯ menu. Import from the **+** on Today, or in bulk
from **You → Data → Import activity files** — a whole folder from a Strava,
Garmin, Wahoo or Coros export can be selected at once.

The format is worked out from the contents, not the filename: FIT is recognised
by its signature bytes and the XML formats by their root element, so a file
saved with the wrong extension still imports. Anything unrecognised is refused
outright rather than half-imported, and the Data screen reports which files
failed and why.

FIT is a binary format, so it is worth saying how it was checked:

- The checksum matches the published **CRC-16/ARC** check value (`0xBB3D` for
  the standard test input), which is what other tools verify against.
- Files written by Stride are read back correctly by **fitparse**, an
  independent parser — sport, session totals, positions, heart rate, cadence
  and power all decode, and it accepts the checksum.
- The reader is tested against a file built by a different writer using shapes
  Stride never produces: big-endian byte order, fields in an unusual order,
  omitted fields, developer fields, unknown message types, and a local message
  type redefined halfway through.
- Round trips are lossless within the format's own precision: positions to
  about 9 mm, altitude to its 0.2 m step, distance exact.

Both FIT and TCX carry heart rate, cadence and power, so an import from a watch
arrives complete. GPX carries them too through the Garmin extensions Stride
reads and writes.

## Where your data lives

Everything is plain JSON inside the app's own Documents folder on the phone:

```
activities.json          summaries, splits, best efforts, zone times
segments.json            your segments
segment-efforts.json     every effort on every segment
routes.json  gear.json  goals.json  settings.json
Tracks/<uuid>.json       the full GPS track per activity
Photos/                  photos attached to activities
```

Nothing is ever sent anywhere. The folder is browsable from the Files app under
**On My iPhone → Stride**, and is included in an encrypted iPhone backup.

### Does a rebuild lose anything?

No. Re-signing and reinstalling with the same bundle identifier and the same
signing team is an *upgrade* install: iOS keeps the app's container untouched.
The weekly refresh — by hand or through `Tools/stride-refresh.sh` — never touches
your training. Even letting the signature lapse is harmless; the app refuses to
launch, but the data sits there waiting until you re-sign.

Three things *do* clear it, and only three:

1. **Deleting the app** from the home screen. iOS destroys the container.
2. **Changing the signing team** — free Personal Team to a paid account, or a
   different Apple ID. The `application-identifier` entitlement is
   `<TeamID>.<bundle id>`, so a new team makes it a different app to iOS; the
   install is refused until you delete the old one.
3. **Changing the bundle identifier** after you have started using it.

Take a backup before doing any of those.

### Backup and restore

**You → Data → Back up now** writes one file holding every activity with its
complete GPS track, plus segments, efforts, routes, gear, goals and settings.
Send it to iCloud Drive, or AirDrop it to the Mac. Restoring offers **Merge**
(adds only what is missing, safe to run twice) or **Replace everything**.

**Automatic backups** run on a schedule you choose — daily, every three days,
weekly, fortnightly, monthly or quarterly. Stride checks each time it opens, and
if one is due it writes it in the background. Backups land in **On My iPhone →
Stride → Backups** in the Files app, so you can drag them to iCloud Drive or a
Mac whenever you like. It keeps the last 3, 5, 10 or 20 and deletes the rest.
**You → Data → Saved backups** lists them with share, restore and delete.

The settings file is decoded leniently: a section added in a later version can
never make an older settings file unreadable.

The format is newline-delimited JSON — a header, then one line per activity —
written and read a line at a time, so a library of long rides never has to fit in
memory. A truncated or partly damaged file still restores everything up to the
break.

Photos are deliberately left out to keep the file small; the originals are still
in your photo library.

If a data file is ever unreadable, Stride moves it aside under a `.damaged-…`
name instead of starting empty and overwriting it, and says so on the Data
screen. Your bytes survive a bad decode.

## What it does

**Recording**
Run, trail run, treadmill, walk, hike, ride, gravel, MTB, e-bike, indoor ride ·
GPS with accuracy filtering and jump rejection · barometric elevation · auto-pause ·
laps · countdown · crash recovery (a flushed snapshot every 10 seconds) ·
background recording with the screen locked · live map

**Voice coach**
Splits by distance or time, or both · 17 announceable metrics you pick and reorder
per sport · three delivery styles (Brief, Natural, Detailed) · a written preview
of the exact sentence you will hear, generated by the same code that speaks it ·
target pace alerts · heart-rate zone alerts · optional encouragement · start,
pause, lap, halfway and finish announcements · live segment call-outs · voice,
speed, pitch, volume, music ducking

The numbers are turned into the words a person says — "four thirty-two", not
"four minutes thirty-two seconds"; "one fifty-four", not "one hundred and fifty
four" — and the phrases are joined with real pauses through SSML rather than
strung together behind commas.

Stride always picks the best voice installed, and the picker labels each one
Standard, Enhanced or Premium. Standard voices are the flat, robotic ones, so
installing a better voice is the single biggest improvement available — more than
any setting in the app. Extra voices are downloaded from the accessibility
settings in iOS, in the section for reading text aloud; Apple has renamed and
moved that screen between releases, so the app deliberately does not quote a menu
path at you. If no better voice is installed, the voice settings screen says so.

**Analysis**
Per-km and per-mile splits with grade-adjusted pace · elevation, heart rate,
pace, power and gradient streams · best efforts from 400 m to marathon ·
heart-rate, power and pace zones · Relative Effort · normalised power ·
estimated cycling power from physics (gravity, rolling resistance, drag,
acceleration) · estimated running power · calories from heart rate

**Strava subscription features, rebuilt locally**
Segments you carve out of any activity, matched automatically against your whole
history · personal leaderboards and PRs · **live segments** with real-time
"5 seconds ahead of your best" spoken as you race · Fitness & Freshness
(CTL/ATL/TSB) with form guidance · training log calendar · goals (weekly,
monthly, yearly; distance, time, elevation, count) · matched runs across the same
route · personal heatmap · route builder with path snapping · power curve and
estimated FTP · race predictions · gear tracking with retirement reminders

**Data**
Import and export **GPX, TCX and FIT** · CSV export · Apple Health read and write ·
Bluetooth heart-rate straps

---

## What genuinely cannot be rebuilt

These parts of Strava are its servers and its users, not its app:

- **Global segment leaderboards** — KOM/QOM lists need everyone else's efforts.
  Stride ranks you against yourself.
- **The global heatmap** — built from billions of uploads. Stride's heatmap is
  built from yours.
- **The social feed, kudos, comments, clubs, challenges** — there is no network
  of other users here.
- **Beacon live tracking** — needs a server to relay your position to someone
  else's phone.
- **Strava's own route suggestions and popularity routing** — same reason.

Also worth being honest about: power on rides without a power meter is a physics
estimate, not a measurement, and calories and Relative Effort are models. Treat
them as consistent trends rather than absolute truth. That is equally true of
Strava's versions.
