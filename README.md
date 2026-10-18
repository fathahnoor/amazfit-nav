# ⌚ AmazfitNav

**Google Maps turn-by-turn on your wrist — no unlocking, no squinting, no stopping.** 🏍️💨

![platform](https://img.shields.io/badge/platform-Android%2026%2B-3ddc84?logo=android&logoColor=white)
![watch](https://img.shields.io/badge/watch-Amazfit%20T--Rex%20Pro-00c2ff)
![build](https://img.shields.io/badge/build-aapt2%20%2B%20javac%20%2B%20d8-ffb000)
![dependencies](https://img.shields.io/badge/dependencies-zero-27c93f)
![license](https://img.shields.io/badge/license-MIT-blue)

> Riding a motorbike in traffic, your phone lives in your pocket and your eyes belong on the road.
> This little bridge mirrors each maneuver to the watch, so a glance is all it takes. 👀

```
+--------------------------+
| APP                      |
|                          |
|           ➡ 200 m        |   <- big line: arrow + distance to the turn
|          Sudirman        |   <- second line: the street
+--------------------------+
```

One buzz when the maneuver changes. One more at 150 m out. Then silence. 🔕

## 🤔 The problem

Google Maps already shows a perfect instruction — it just shows it on a screen you cannot look at
while a truck is merging into your lane. 🚚

Amazfit watches *can* relay phone notifications through Zepp, so the obvious move is "just let Maps
through". It does not work:

- 🚫 Maps' card is an **ongoing, foreground-service, silent** notification. Relays swallow it.
- 🚫 Maps' card has **no distance string at all** (`android.text` is `null`), so a relayed card cannot
  even tell you how far the turn is.
- 🚫 Maps rewrites that notification **every second**, and relays drop bursts like a hot potato.

So this project reads the raw notification, digs the number out of `progressSegments`, and posts a
brand-new ordinary notification that Zepp happily forwards. 🕵️

## ✨ What you get

| | |
|---|---|
| ➡ **Arrow + metres on one big line** | a short title (the inspector budgets 16 chars), so the round bezel never clips it |
| 🛣️ **Street name on the second line** | `Jl.` stripped, trimmed to 24 chars, `…` if it overruns |
| 📳 **Phone buzz on maneuver change** | and exactly one 150 m pre-turn ping |
| 🔇 **No vibration storm** | distance ticks update the card silently |
| 🧭 **Real U-turn detection** | English *and* Indonesian guidance strings |
| 🎯 **"Arriving" only when true** | Maps fakes it at route start, so it needs < 80 m of route left |
| 🧹 **Anti-jitter filter** | Maps' 43 km → 15 m glitches never reach your wrist |
| 🤖 **Agent-friendly** | an MCP server shows the exact payload the watch receives |

## 🧠 How it works

```
Google Maps ──notification──► AmazfitNav ──────new notification──────► Zepp ──BLE──► T-Rex Pro
 (ongoing, silent,      (NotificationListenerService:  (ordinary, fresh tag,     (relays any
  no plain distance)     parse maneuver + distance)     high importance + buzz)   allowed app)
```

- 🕵️ **`NavListenerService`** — a `NotificationListenerService` that watches the stream for Maps'
  navigation notification (package + channel `1_foreground_1` / category `navigation`).
- 🧮 **`MapsParser`** — pulls the maneuver (`android.title`), the ETA (`android.subText`) and the
  **distance to the next turn**: `progressSegments[0].length - progress`. That subtraction is the
  whole trick of the project. 🎩
- 🧭 **`Maneuver`** — maps guidance text to a glyph + a raster icon, matching English and Indonesian
  words (`turn right`, `belok kanan`, `putar balik`, …).
- 📣 **`Republisher`** — posts an ordinary, non-ongoing notification under a **fresh tag** every time
  (`nav-1`, `nav-2`, …), so Zepp sees a new notification instead of an update it might swallow.
- 🎵 **`MediaBridge`** — optional fallback: publishes the maneuver as fake media metadata for Zepp's
  music widget.
- 📊 **`MainActivity`** — diagnostics plus buttons to probe, cycle arrows, test glyphs and colours.

## 🗺️ The arrow alphabet

Only glyphs that were **verified to render on the watch** are used. The watch font is picky: some
arrows that look perfectly normal on the phone come out as empty holes on the wrist. 🕳️

| Glyph | Maneuver |
|---|---|
| ⬆ | continue straight · at a roundabout, keep going |
| ↗ ↖ | keep right · keep left |
| ➡ ⬅ | turn right · turn left |
| ⬇ | **U-turn** (borrowed from *down*, see battle scars) |
| ⬆ Arriving | destination card (shown when no distance is left) — only believed under 80 m of route |

There is no colour and no emoji on the watch — colour emoji simply do not render, so the arrow has
to live in the **text**. 🖤

## 🚀 Quick start

**1. Get the APK** — build it (below) and install:

```bash
adb install -r build/amazfitnav-debug.apk
```

**2. Grant notification access** — open the app and tap **“1. Grant notification access”**,
allow *AmazfitNav*, and it should now show `GRANTED`. 🔓
(Or in one line on a dev phone:
`adb shell cmd notification allow_listener com.fathahnoor.amazfitnav/com.fathahnoor.amazfitnav.NavListenerService`.)

**3. Tell Zepp to let it through** — *Zepp → Device → Notifications and reminders → Notifications*:

- 📱 **PHONE APPS** = on
- 🚫 **ALLOW ALL** = off (honour the per-app list)
- 🗺️ **Maps** = **off** — the bridge replaces its raw, distance-less card
- ✅ **Navigasi / AmazfitNav** = **on**
- 🔓 **NOTIFY ONLY WHEN PHONE LOCKED** = off — the phone sits mounted with the screen on

**4. Keep the OS from killing it** 🔫 (optional but strongly recommended):

```bash
adb shell cmd deviceidle whitelist +com.fathahnoor.amazfitnav
```

**5. Tap “2. Send probe notification”** and look at your wrist. If a card appears, you are done. 🎉

Then: open Maps, start a route, and never look at the phone again. 😎

### 🛠️ Requirements

- 📱 Android phone, API 26+ (tested on Android 16 / SDK 36, Infinix X6873)
- ⌚ Amazfit watch relayed by **Zepp** (tested on a T-Rex Pro over BLE)
- 🗺️ Google Maps with turn-by-turn navigation
- 🔋 Battery optimisation off for AmazfitNav, notification access granted, DND respected

## 🔨 Build it yourself (offline, zero dependencies)

No Gradle. No Maven. No internet. The whole thing compiles with the Android SDK tools you already
have. 😌

```bash
./build.sh     # aapt2 → javac → d8 → zip → zipalign → apksigner  ⇒ build/amazfitnav-debug.apk
```

Needs JDK 17 and `$ANDROID_SDK_ROOT` (build-tools 35/36 + `platforms/android-36`). Icons are
rasterised PNGs made by `tools/GenIcons.java`, because a relayed notification cannot rely on a
`VectorDrawable`. 🎨

## 🧰 Toolbox

All scripts live in [`tools/`](tools) and need nothing but `adb` and Python. 🐍

| Tool | What it does |
|---|---|
| 🖥️ `tools/watchfeed.py [secs]` | render the card the watch receives, in the round face's layout, and flag clipping |
| 🔍 `tools/zepp_switches.py <dump.xml>` | list Zepp's per-app switches with human labels |
| 🧹 `tools/zepp_sweep.sh [n]` | disable every app in Zepp except Maps/Navigasi |
| 🎚️ `tools/zepp_toggle.py <label> on\|off` | flip one entry in the allowlist |
| 👆 `tools/tap_text.py "<text>"` | tap a UI node by its label (built for Zepp's UI) |
| 🌳 `tools/uidump.py <xml>` | pretty-print a `uiautomator` dump |
| 🤖 `tools/mcp_watch.py` | **MCP server** for agents — see below |
| 🎨 `tools/GenIcons.java` | generate the maneuver PNGs |

## 🤖 Agent-friendly: the watch-feed MCP server

Want to know *exactly* what your wrist is being handed without a human squinting at it? Start
[`tools/mcp_watch.py`](tools/mcp_watch.py) (already registered in [`.mcp.json`](.mcp.json)) and an
agent can:

| Tool | Answers |
|---|---|
| `watch_screen` | what the watch shows right now, as a frame + format warnings |
| `watch_wait` | block until the card changes — validates a live ride |
| `watch_probe` | replay `{instruction, distance}` through the real formatter, then read it back |
| `watch_timeline` | recent `published` / `suppressed` / `navigation ended` events |
| `device_health` | listener granted? app installed? doze whitelisted? |

Details in [docs/mcp-watch.md](docs/mcp-watch.md). 🧪

## 🩹 Battle scars: what the watch refuses

Everything here was measured on hardware, not guessed. Full notes in
[docs/findings.md](docs/findings.md). 📓

| ❌ Tried | 💥 Reality |
|---|---|
| Big vector arrow icons per maneuver | Zepp never reads `smallIcon`/`largeIcon` (0 hits across 9 dex files) — the watch keeps the app's envelope icon |
| `↩ ↪ ↻ ↰ ↱` for U-turn / roundabout | missing from the watch font → **blank holes**. Hence ⬇ for U-turn, ⬆ for roundabouts |
| Colour, green accents, emoji | watch renders monochrome text only |
| Bigger font | firmware-fixed size; the bridge only controls *what* is written |
| Renaming the watch card header | stays **APP / App** — Zepp labels the app “Navigasi” correctly in its own list, so the header comes from Zepp/firmware, keyed by package ⚰️ |
| Watch vibration from the payload | global Zepp/watch setting — even WhatsApp does not buzz the wrist |
| One notification, updated in place | relays swallow rapid updates to the same key → a **fresh tag** per maneuver |
| “Just declare the channel pattern” | the app had no `VIBRATE` permission: `vibrate()` threw `SecurityException` and **killed the process**. Fixed with the permission + `VibrationEffect` + a versioned channel id |

Bonus truths: 🥇 the dreaded `E music: Blocked onNotificationPosted` log is a red herring (it belongs
to media sync, not delivery), and ~24 chars of body is the most a round bezel will show before it
gets sliced off. 🔪

## 🛣️ Roadmap & ideas

- 🚗 **More navigation sources** — Waze, OsmAnd, Here, Apple Maps on iPhone (spoiler: no listener
  there, something else would be needed).
- ⌚ **More watches** — every Amazfit/Zepp OS model has its own font coverage. A matrix of "which
  glyphs render" per model would be gold. 🏆
- 🧪 **Unit tests for the pure logic** — `MapsParser` and `Maneuver` are plain Java with no Android
  dependencies; a tiny hand-rolled harness in `tools/` would do.
- 🔊 **Optional spoken ETA** — “200 m, Sudirman” as a TTS aside while music keeps playing.
- 🌏 **More languages** — `Maneuver` matches words; adding locales is a small, clean PR.
- 🎵 **Retire or harden `MediaBridge`** — it works, but nobody has stress-tested it in a car.

## 🤝 Contributing — you're invited 🎉

**This project only exists because one person refused to keep pulling over.** 😤 The hard part is
done — the plumbing works — and now the interesting part is open to everyone. You do not need a
T-Rex Pro to help, and you do not need to understand BLE. 🧠

### 🟢 No watch needed

- 🌏 add a locale to `Maneuver` (`turn right`, `belok kanan`, `rechts abbiegen`, …)
- 🧪 write tests for `MapsParser` / `Maneuver` — they are pure Java, easy to run
- ✂️ improve road extraction from guidance strings (`Jl.` → `Jl`, abbreviations, roundabout exits)
- 📝 improve the docs, translate them, fix a typo in this README
- 🧩 clean up or extend the `tools/` scripts

### 🟡 Helpful if you own an Amazfit

- ⌚ **glyph coverage report** for your model: which arrows render, which are blank
- 📸 **real screenshots** of the card on the wrist (huge gap in this README!)
- 📳 confirm whether watch vibration works on your model and which setting turns it on
- 🚗 field-test on a longer ride and report anything odd

### 🔴 For the curious hackers

- 🕵️ find out where Zepp/firmware gets the card header, and whether a companion-app route exists
- 📡 a `custom` / media-session notification path that *does* deliver per-maneuver icons
- 🎧 make MediaBridge survive real playback properly

### 🧭 How to contribute

```bash
git clone https://github.com/fathahnoor/amazfit-nav.git
cd amazfit-nav
./build.sh            # must succeed — the build is offline and dependency-free
```

1. 🍴 Fork, then branch off `main` (`feat/waze-support`, `fix/roundabout-label`, …).
2. 🔨 Keep the build offline. **No Gradle, no new runtime dependencies** — that constraint is the
   soul of this project (it was written on a laptop with no internet).
3. ⌚ If your change touches what the watch displays, test it on a real watch and paste the
   `tools/watchfeed.py` frame into the PR. Claims like “it renders fine” need hardware. 🔬
4. 🧹 Keep the code style: plain Java, small classes, comments that explain *why* (the reasons are
   the valuable part).
5. 📨 Open a PR with: what changed, **what you saw on the watch**, and your phone/watch/Zepp version.

Not sure where to start? Open an issue that says so — questions are welcome, and a working
reproduction beats a perfect write-up. 💬

### 🐛 Found a bug?

Please include: phone model + Android version, watch model, Zepp version, and the output of
`python tools/mcp_watch.py` (or `adb logcat -s AmazfitNav`) for the card that misbehaved. That
report is worth ten guesses. 🎯

## 📜 License

MIT — see [LICENSE](LICENSE). Ride safe, and don't look down for too long. 🛵💨
