# AmazfitNav — Google Maps turn-by-turn on Amazfit T-Rex Pro

Goal: while riding a motorbike, glance at the **T-Rex Pro** instead of stopping to
unlock the phone and read Google Maps.

Chain: `Google Maps (Android 16 / Infinix X6873)` → `Zepp app` → `BLE` → `Amazfit T-Rex Pro`

## Baseline findings (verified over ADB)

| Check | Result |
|---|---|
| `adb devices` | `SERIAL_REMOVED` device (Infinix X6873, Android 16 / SDK 36, `ro.debuggable=0`, not rooted) |
| Zepp app | `com.huami.watch.hmwatchmanager` **10.9.0-play** running (pid present) |
| Watch | `F3:2C:26:3C:06:C0` "Amazfit T-Rex Pro" bonded, **BLE connected** |
| Google Maps | `com.google.android.apps.maps` installed |
| Zepp notification access | **NOT in `settings get secure enabled_notification_listeners`** → mirroring currently dead |

## Google Maps navigation notification (ground truth)

```
pkg=com.google.android.apps.maps id=1 channel=1_foreground_1 (name "Navigation", group NAVIGATION, imp=3)
flags=ONGOING_EVENT|ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE|LOCAL_ONLY|PROMOTED_ONGOING|SILENT
category=navigation   vis=PUBLIC   template=Notification$ProgressStyle
android.title     = "Head northwest on Jl. Emerald Towers Apartment"   <-- maneuver
android.subText   = "Arrive 11:26 PM"                                   <-- ETA
android.text      = null / bigText = null
android.progress  = 0 / progressMax = 165447 / progressSegments = ArrayList(9 Bundles)
actions           = ["Exit navigation"]
```

Implications for mirroring:
* it is **ongoing + foreground-service** (`PROMOTED_ONGOING`) — the class of
  notification most relay apps drop;
* it is **SILENT** and has no normal text payload (`text`/`bigText` are null):
  a naive mirror shows an empty body;
* the maneuver lives in `android.title`; distance data is only inside
  `ProgressStyle` segments.

## Toolchain constraints
* Laptop has **no internet** (example.com/github/maven/gradle all `000`).
* Available offline: JDK 17, Android SDK platform `android-36`,
  build-tools `35.0.0`/`36.1.0` (`aapt2`, `dexdump`, `d8`, `apksigner`).
* No `jadx`/`gradle`/`apktool` → RE done with `dexdump` + aapt2; app built with
  raw `aapt2/javac/d8/apksigner` instead of Gradle.

See [docs/findings.md](docs/findings.md) for the evolving log.
