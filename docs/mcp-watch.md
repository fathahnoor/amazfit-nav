# Watch-feed MCP (`tools/mcp_watch.py`)

A small MCP server that lets an agent see, in real time, **exactly what the watch is handed**,
so a payload can be validated without a human squinting at their wrist.

## Why not a screenshot

The T-Rex Pro exposes no API to the phone: Zepp speaks a proprietary BLE protocol, there is no
"take screenshot" command, and rooting the watch is out of scope. What *is* observable (and what
actually decides what the wearer sees) is the notification Zepp relays: its title, body, small
icon type and channel. That is what this server reads, rendered in the round face's layout.

## Tools

| Tool | What it answers |
| --- | --- |
| `watch_screen` | What the watch shows right now, as a frame + format warnings. |
| `watch_wait` | Block until the card changes; returns every distinct frame (validates a live run). |
| `watch_probe` | Replay `{instruction, distance}` through the real formatter, then read the result back. |
| `watch_timeline` | Recent `published` / `suppressed` / `navigation ended` events from the bridge. |
| `device_health` | Listener granted, app installed, doze whitelist, current card. |

The frame flags anything the earlier sessions learned the watch punishes: a missing arrow glyph,
a missing distance unit, a body that overruns the round bezel, a missing icon.

## Registering it

Project-local config is in [.mcp.json](../.mcp.json). Any MCP client that reads that file picks
the server up as `watchfeed`. Manual JSON-RPC over stdio works too:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"watch_screen","arguments":{}}}' \
  | python tools/mcp_watch.py
```

Requirements: `adb` on PATH, one authorised device. Set `AMAZFIT_DEVICE` to pin a serial
(otherwise the first `device` from `adb devices` is used).

## The replay path

`watch_probe` fires `am start -n com.fathahnoor.amazfitnav/.MainActivity --es replay "…" --es distance "…"`.
`MainActivity` posts that through `NavListenerService.probe`, then closes itself. It uses the same
formatter and the same channel as live navigation.

An explicit activity intent is used rather than a broadcast because this Transsion firmware
defers broadcasts to background apps, so a broadcast replay silently never ran (verified: the
system logged `Broadcast completed` while the receiver's own `Log.i` never appeared).

Probe cards live under their own notification tags, so they never cancel a live navigation card.

## Limits

- It shows what the **phone hands the watch**, not pixels. A glyph that the *phone* renders fine
  can still be missing from the watch's font; only the wearer can confirm that (as happened with
  emoji arrows rendering as blanks).
- Icon *rendering* is Zepp's call; the server can only report the icon type.
