# Findings: how Zepp relays (and doesn't relay) notifications

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
  A freshly installed app can appear **off**, and that silently swallows everything.
* **"NOTIFY ONLY WHEN PHONE LOCKED"** is on by default; while it is on, notifications are
  only forwarded when the phone is locked. On a motorbike the phone is mounted with the
  screen on, so this must be **off**.
* **"ALLOW ALL"** off means "honour the per-app list", which is what we want.

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
| `android.text` / `bigText` | **null**, because Maps has no plain distance string |
| `android.progress` | progress inside the current leg (0 at nav start) |
| `android.progressMax` | total route length in metres (165 632 for a 165 km route) |
| `android.progressSegments` | `ArrayList<Bundle>`: `{length, colorInt, id, semanticStyle}` per leg |

`progressSegments[0].length` is the length of the leg we are on, so
**distance to the next turn = `segments[0].length - progress`**. On nav start Maps
reports `progress == 0` and the clamp in `MapsParser` keeps it sane; without the clamp
the very first instruction renders as "0 m".

## 5. Icons are not forwarded, text glyphs are

Zepp never calls `Notification.getSmallIcon()/getLargeIcon()` (zero occurrences in all
9 dex files); it re-parses the parcels into its own
`com.huami.watch.notification.data.NotificationData`, and on the watch the envelope is
the app's own icon. Sending per-maneuver vector drawables (nice big arrows) changes
nothing on the wrist.

What *does* work is the text itself, but only part of the set. A glyph probe containing
`← → ↑ ↓ ↗ ↖ ↘ ↙ ↰ ↱ ↪ ↩ ⬆ ⬅ ➡ ◀ ▶ ▲ ▼` was reported as fully rendered, yet the
U-turn card (`↩`, U+21A9) then showed a **blank** on the wrist. The hooked and circular
arrows (`↩ ↪ ↻ ↰ ↱`, U+21A9-U+21BB) are missing from the watch font; the plain arrows
(U+2190-U+2199) and the block arrows (U+2B05/2B06/2B07/27A1) do render. A U-turn is
therefore drawn as `⬇` and a roundabout as `⬆`. So the direction is drawn into the title:

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

## 8. The envelope header reads "APP / App" and cannot be changed from the app

Every relayed card on the watch is wrapped in a header showing an app badge and name.
For this bridge that header reads a generic **APP / App**, while WhatsApp shows its own
icon and name. Measured causes, in order:

* `android:icon` was missing from the manifest entirely; it was added and had no effect on the header.
* Zepp itself resolves the label correctly: its per-app allowlist lists this bridge as
  **"Navigasi"** (`re/z.xml`), straight from PackageManager.
* Zepp never reads `android.smallIcon` or `android.appInfo` off the notification (no such
  strings in any of the 9 dex files), so the header is not sourced from the notification.
* Re-registering the app in Zepp's allowlist (switch off, then on) does not change it.

Conclusion: the header comes from Zepp's own app metadata / the watch firmware, keyed by
package. A third-party APK cannot influence it; changing it would require shipping a
companion app the watch firmware already knows. Treat it as a hard limit.

## 9. Alerts: what actually buzzes

* The app declared no `android.permission.VIBRATE`, so every vibration path was dead. Set
  by hand this looks like "the channel has a pattern but nothing happens"; worse, an
  explicit `vibrate()` threw `SecurityException` and killed the publishing process, so
  the notification itself vanished as well.
* Fixed by declaring `VIBRATE`, creating a **versioned** channel (channel settings are
  immutable once created, so tuning a pattern needs a new id) and driving the buzz with
  `VibrationEffect` under `USAGE_NOTIFICATION`, which keeps it subject to Do Not Disturb.
* Vibration on the **watch** is a global Zepp/watch setting, not per app: WhatsApp
  notifications do not buzz the wrist either. Nothing in the payload influences it.
