# AmazfitNav — Google Maps turn-by-turn on an Amazfit T-Rex Pro

Riding a motorbike, you should not have to stop, unlock the phone and squint at Google
Maps. This project mirrors each maneuver to the watch:

```
➡ 200m                 <- large top line: direction arrow + distance to the turn
Sudirman               <- second line: street name
```

Phone and watch buzz together on every maneuver, and nothing else is allowed to reach
the watch.

## How it works

```
Google Maps ──notification──► AmazfitNav (listener) ──new notification──► Zepp ──BLE──► T-Rex Pro
   (ongoing, silent,              parses maneuver,                     relays any
    no plain distance)            distance + street                     allowed app
```

* `NavListenerService` (a `NotificationListenerService`) watches for Maps' navigation
  notification, identified by channel `1_foreground_1` / category `navigation`.
* `MapsParser` extracts the maneuver (`android.title`), the ETA (`android.subText`) and
  the **distance to the next turn** (`progressSegments[0].length - progress`).
* `Maneuver` maps the guidance text to an arrow glyph and to a raster icon.
* `Republisher` posts an ordinary, non-ongoing notification (fresh tag each time) that
  Zepp relays, with `IMPORTANCE_HIGH` + a vibration pattern so the phone buzzes too.
* `MediaBridge` is an optional fallback that publishes the maneuver as fake media
  metadata for Zepp's music widget.

## Setup (what is already configured on this phone)

1. Install `build/amazfitnav-debug.apk` and grant notification access
   (`adb shell cmd notification allow_listener com.fathahnoor.amazfitnav/com.fathahnoor.amazfitnav.NavListenerService`).
2. In Zepp: *Device → Notifications and reminders → Notifications*
   * **PHONE APPS** on, **ALLOW ALL** off
   * **Maps off**, **Navigasi/AmazfitNav on** (the bridge replaces Maps' raw card)
   * **NOTIFY ONLY WHEN PHONE LOCKED off** — the phone is on the bars with the screen on
   * every other app off (see `tools/zepp_sweep.sh`)
3. Keep the phone out of battery restrictions:
   `adb shell cmd deviceidle whitelist +com.fathahnoor.amazfitnav`

## Building (no Gradle, no network)

```
./build.sh          # aapt2 + javac + d8 + zipalign + apksigner -> build/amazfitnav-debug.apk
```

Icons are generated as PNGs by `tools/GenIcons.java` (`javac -d build/icongen
tools/GenIcons.java && java -cp build/icongen GenIcons app/res/drawable-xxhdpi`) because
Zepp cannot load a VectorDrawable for a relayed notification.

## Tools

| tool | purpose |
|---|---|
| `tools/watchfeed.py [secs]` | renders the card the watch receives, flags clipping |
| `tools/zepp_switches.py <dump.xml>` | lists Zepp's per-app switches with labels |
| `tools/zepp_sweep.sh [n]` | disables every app in Zepp except Maps/Navigasi |
| `tools/zepp_toggle.py <label> on\|off` | flips one app in the allowlist |
| `tools/tap_text.py "<text>"` | taps a UI node by label (used for the Zepp UI) |
| `tools/uidump.py <xml>` | prints a uiautomator dump |

## Known limits (verified, not guesses)

* **Font size cannot be changed.** The watch firmware renders notification text at a
  fixed size; the bridge only controls *what* is written and on which line.
* **No colour, no emoji.** Colour emoji do not render on the T-Rex Pro, so a green
  arrow is impossible; monochrome arrows (`➡ ⬅ ⬆ ↗ ↖ ↩ ↻ ◎`) do render.
* **No watch screenshots.** The watch exposes no API (BLE, proprietary protocol); the
  closest is `tools/watchfeed.py`, which shows the exact payload Zepp sends.
* Zepp labels unknown third-party apps as **"App"** with a generic badge; the app label
  in our manifest does not change that line.
* The bridge only works while Zepp has it enabled in the per-app allowlist.

See [docs/findings.md](docs/findings.md) for the reverse-engineering notes.
