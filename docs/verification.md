=== 1. preconditions ===
watch BLE: 9 connect events, bonded device: Amazfit T-Rex Pro
zepp pid: 16536
bridge listener: 1 enabled

=== 2. channel (phone vibration) ===
mId='nav_ride_v1', mName=Navigation alerts, mDescription=hasDescription , mImportance=4, mBypassDnd=false, mLockscreenVi

=== 3. live navigation -> what the watch receives ===
watching com.fathahnoor.amazfitnav for 18s

+----------------------------+
| APP nav_ride_v1           |
|                            |
|  ⬆ 89km                    |
|  Emerald Towers Apartment  |
+----------------------------+
   icon=RESOURCE  OK

done

=== 4. watch-feed MCP (tools/mcp_watch.py) ===
All five tools driven over real stdio JSON-RPC against the phone.

device_health:
  connected=true bridge_enabled=true app_installed=true doze_whitelisted=true

live card read back (real navigation, no replay):
  +--------------------------+
  | ↻ 550 m                  |
  | Sanggar Hurip            |
  +--------------------------+
  icon=RESOURCE channel=nav_ride_v1 kind=live -> OK
  source log: published: "550 m · Arrive 10:06 PM · At the roundabout,
               continue straight onto Jl. Sanggar Hurip"

replay (same formatter/channel as live nav):
  "In 300 m, turn left onto Jalan Sanggar Kencana XI", "300 m"
      -> title "⬅ 300 m", body "Sanggar Kencana XI"
  "Make a U-turn onto Jl. Sukarno Hatta", "1.4 km"
      -> title "↩ 1.4 km"

Replay path note: an adb broadcast (DebugReceiver) was tried first and never reached the
app - the system logged "Broadcast completed" while the receiver's own Log.i never
appeared (Transsion defers broadcasts to background apps). Switched to an explicit
activity intent handled by MainActivity.handleReplay, which works.
