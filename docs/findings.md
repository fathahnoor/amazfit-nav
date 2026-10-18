# Findings — how Zepp relays (and doesn't relay) notifications

Everything here was verified on the target device (Infinix X6873, Android 16 / SDK 36,
Zepp `com.huami.watch.hmwatchmanager` 10.9.0-play, Amazfit T-Rex Pro bonded over BLE),
mostly over ADB, with static analysis of the pulled Zepp APK (184 MB, 9 dex files,
R8-obfuscated).

## 1. The notification path exists and works

`com.xiaomi.hm.health.ui.smartplay.NotificationAccessService` (a
`NotificationListenerService`) receives every posted notification and relays it to the
watch. Its `onNotificationPosted` delegates into an R8-obfuscated dispatcher
(`Lwyn;.run`, a `packed-switch` over event types).

## 2. The famous "Blocked onNotificationPosted" log is NOT the forwarder

```
E music : Blocked onNotificationPosted StatusBarNotification(pkg=... flags=... category=navigation)
```

This line appears for *every* notification, including our own ordinary one, and even
when `AudioManager.isMusicActive()` is false. Bytecode of the branch (classes.dex,
`Lwyn;.run`, offset `0x0093`):

```
00ac: invoke-virtual AudioManager.isMusicActive()Z
00b0: if-nez v6, 00c5              # not blocked path
00b4: const-string "Blocked onNotificationPosted "
00c0: Log.e("music", ...)          # tag is literally "music"
```

so it belongs to the **media/metadata sync** worker that builds
`com.huami.watch.companion.mediac.MediaInfo`, not to notification delivery. The
empirical proof: a plain notification from a freshly written app (no music playing)
logs the same "Blocked" line **and still arrives on the watch**.

Lesson: don't trust the log text; verify on the wrist.

## 3. What actually gates delivery

* **Per-app allowlist** in Zepp (`Device → Notifications and reminders → Notifications`),
  backed by a Room DB (`smartplay/appnotify/room/ApplicationDatabase`). Rows are
  `Allow/deny` per package; the master "PHONE APPS" toggle must stay on.
  A freshly installed app can appear **off** — that silently swallows everything.
* **"NOTIFY ONLY WHEN PHONE LOCKED"** — on by default; while it is on, notifications are
  only forwarded when the phone is locked. On a motorbike the phone is mounted with the
  screen on, so this must be **off**.
* **"ALLOW ALL"** — off means "honour the per-app list", which is what we want.

## 4. Google Maps' navigation notification

```
pkg=com.google.android.apps.maps  id=1  channel=1_foreground_1 ("Navigation")
flags=ONGOING_EVENT|ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE|LOCAL_ONLY|PROMOTED_ONGOING|SILENT
category=navigation  vis=PUBLIC  template=Notification$ProgressStyle
```

Extras (captured live):

| key | meaning |
|---|---|
| `android.title` | maneuver, e.g. "Head toward Jl. Sanggar Kencana XI" |
| `android.subText` | ETA, e.g. "Arrive 11:46 PM" |
| `android.text` / `bigText` | **null** — Maps has no plain distance string |
| `android.progress` | progress inside the current leg (0 at nav start) |
| `android.progressMax` | total route length in metres (165 632 for a 165 km route) |
| `android.progressSegments` | `ArrayList<Bundle>`: `{length, colorInt, id, semanticStyle}` per leg |

`progressSegments[0].length` is the length of the leg we are on, so
**distance to the next turn = `segments[0].length - progress`**. On nav start Maps
reports `progress == 0` and the clamp in `MapsParser` keeps it sane; without the clamp
the very first instruction renders as "0 m".

## 5. Icons are not forwarded, text glyphs are

Zepp never calls `Notification.getSmallIcon()/getLargeIcon()` (zero occurrences in all
9 dex files) — it re-parses the parcels into its own
`com.huami.watch.notification.data.NotificationData`, and on the watch the envelope is
the app's own icon. Sending per-maneuver vector drawables (nice big arrows) changes
nothing on the wrist.

What *does* work is the text itself: a glyph-probe notification containing
`← → ↑ ↓ ↗ ↖ ↘ ↙ ↰ ↱ ↪ ↩ ⬆ ⬅ ➡ ◀ ▶ ▲ ▼` rendered **every** candidate correctly on the
T-Rex Pro. So the direction is drawn into the title:

```
↱ 200 m            <- title: arrow + distance (first line renders in full)
Jl. Sudirman       <- body: street only (the round face clips long lines)
```

Long bodies are visibly cut off left/right because the display is round, which is why
the body is trimmed to ~22 chars.

## 6. Relays can swallow very rapid updates

A burst of 8 notifications posted 3.5 s apart under the *same* notification key did not
arrive; a single one did. The bridge therefore publishes under a fresh tag
(`nav-1`, `nav-2`, …) and cancels the previous one, so each maneuver is a brand-new
notification from Zepp's point of view.

## 7. Environment constraints hit while building this

* Laptop has no internet → no Gradle/jadx; the app is built with `aapt2` + `javac` +
  `d8` + `zipalign` + `apksigner` (`build.sh`), and RE was done with `dexdump`.
* No `zip` binary → `classes.dex` is added with Python's `zipfile`.
* `javac` on Windows needs Windows-style classpath entries (`cygpath -w`), otherwise
  POSIX paths silently produce "package android.widget does not exist".
