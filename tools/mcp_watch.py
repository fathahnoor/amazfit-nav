"""MCP server: live view of what the Amazfit watch receives.

The T-Rex Pro exposes no API (BLE, proprietary), so a literal screenshot is impossible.
What is observable - and what actually decides what the wearer sees - is the notification
Zepp relays: its title, body, icon type and channel. This server reads that from
`dumpsys notification` in real time, renders it in the watch's round layout, and flags
anything that would clip or read wrong.

Implements MCP over stdio (JSON-RPC 2.0, newline delimited) with no third-party deps.

Run:   python tools/mcp_watch.py
Config: .mcp.json (see docs/mcp-watch.md)
"""
import json
import os
import re
import subprocess
import sys
import time

PKG = "com.fathahnoor.amazfitnav"
TAG = "AmazfitNav"

# Round 454px face. Notification text is drawn at a fixed size by Amazfit firmware; a
# line of ~16 glyphs stays clear of the bezel, 24 survives at the centre. Body wraps but
# the circle eats the ends, which is why the payload keeps the body short.
TITLE_SAFE = 16
BODY_SAFE = 20
BODY_BUDGET = 24

ARROWS = "\u2190-\u21ff\u2b00-\u2bff\u25a0-\u25ff\u27a1"
# Hooked/circular arrows probe as blanks in the watch font (confirmed on device).
BAD_GLYPHS = "\u21a9\u21aa\u21bb\u21b0\u21b1\u21b2\u21b3"


def device():
    dev = os.environ.get("AMAZFIT_DEVICE")
    if dev:
        return dev
    try:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True,
                             timeout=10).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None


def sh(*args, timeout=20):
    dev = device()
    if not dev:
        return ""
    try:
        # dumpsys is not valid cp1252 on Windows; decode as UTF-8 and never fail.
        return subprocess.run(["adb", "-s", dev] + list(args), capture_output=True,
                              text=True, timeout=timeout, encoding="utf-8",
                              errors="replace").stdout or ""
    except (subprocess.TimeoutExpired, OSError):
        return ""


def card():
    """Current live navigation card, or None if the bridge is not showing one."""
    dump = sh("shell", "dumpsys", "notification", "--noredact")
    def posted(block):
        m = re.search(r"when=(\d{13})", block)
        return int(m.group(1)) if m else 0

    # Old probes linger under their own tags, and dumpsys order is not guaranteed: keep
    # the newest block of each kind, then the newest of the two. A probe (a deliberate
    # test) wins ties - it was posted by hand after the live card.
    live = probe = None
    for b in dump.split("NotificationRecord("):
        if f"pkg={PKG}" not in b:
            continue
        if "tag=nav-" in b:
            if live is None or posted(b) > posted(live):
                live = b
        elif "tag=probe" in b:
            if probe is None or posted(b) > posted(probe):
                probe = b
    kind = "live" if live else "probe"
    if live and probe:
        is_probe = posted(probe) > posted(live)
        b = probe if is_probe else live
        kind = "probe" if is_probe else "live"
    else:
        b = live or probe
    if b is None:
        return None
    title = re.search(r"android\.title=String \((.*?)\)\n", b)
    text = re.search(r"android\.text=String \((.*?)\)\n", b)
    channel = re.search(r"channel=(\S+)", b)
    icon = re.search(r"icon=Icon\(typ=(\w+)", b)
    when = re.search(r"when=(\d{13})", b)
    return {
        "title": title.group(1) if title else "",
        "body": text.group(1) if text else "",
        "channel": channel.group(1) if channel else "?",
        "icon": icon.group(1) if icon else "?",
        "kind": kind,
        "when": int(when.group(1)) if when else 0,
    }


def analyse(c):
    """Phone-side invariants the watch depends on. Each entry is a human-readable note."""
    w = []
    t, b = c["title"], c["body"]
    arriving = "Arriving" in t
    if not re.match("^[" + ARROWS + "]", t):
        w.append("title does not start with an arrow glyph")
    if t[:1] in BAD_GLYPHS:
        w.append(f"glyph {t[:1]!r} renders as a blank on the watch")
    if not arriving and not re.search(r"\d", t):
        w.append("no distance in title")
    if not arriving and not re.search(r"\d+\s?(m|km)\b", t):
        w.append("distance unit missing/imprecise")
    if len(t) > TITLE_SAFE + 4:
        w.append(f"title {len(t)} chars - wide for the round face")
    if len(b) > BODY_BUDGET:
        w.append(f"body {len(b)} chars > {BODY_BUDGET} - ends get cut by the bezel")
    if c["icon"] not in ("RESOURCE", "BITMAP"):
        w.append(f"unexpected small-icon type {c['icon']}")
    if c["kind"] == "probe":
        w.append("this is a test card, not live navigation")
    return w


def frame(c):
    if c is None:
        return "   (no navigation card - bridge is idle)"
    t, b = c["title"], c["body"]
    body = [b[i:i + BODY_SAFE] for i in range(0, len(b), BODY_SAFE)] or [""]
    inter = " " * 26
    lines = [
        "   " + " " * 10 + "\u2577 bezel",
        "  \u256d" + "\u2500" * 26 + "\u256e",
        f"  \u2502 {t[:26]:<24} \u2502",
    ]
    for ln in body[:3]:
        lines.append(f"  \u2502 {ln:<24} \u2502")
    lines += ["  \u2570" + "\u2500" * 26 + "\u256f", inter,
              f"  icon={c['icon']}  channel={c['channel']}  kind={c['kind']}"]
    warn = analyse(c)
    lines.append("  OK" if not warn else "  ! " + "\n  ! ".join(warn))
    return "\n".join(lines)


def timeline(limit=20):
    out = sh("logcat", "-d", "-s", TAG)
    rows = []
    for line in out.splitlines():
        m = re.match(r"(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) .*AmazfitNav.*?:\s*(.*)", line)
        if not m:
            continue
        msg = m.group(2).strip()
        if any(k in msg for k in ("published", "suppressed", "ignored",
                                  "navigation ended", "duplicate")):
            rows.append({"time": m.group(1), "event": msg})
    return rows[-limit:]


def health():
    dev = device()
    if not dev:
        return {"connected": False, "device": None}
    listeners = sh("shell", "settings", "get", "secure", "enabled_notification_listeners")
    return {
        "connected": True,
        "device": dev,
        "bridge_enabled": "amazfitnav" in listeners,
        "app_installed": PKG in sh("shell", "pm", "list", "packages"),
        "doze_whitelisted": "amazfitnav" in sh("shell", "dumpsys", "deviceidle", "whitelist",
                                               timeout=40),
        "maps_running": bool(sh("shell", "pidof", "com.google.android.apps.maps").strip()),
        "card": card(),
    }


TOOLS = [
    {
        "name": "watch_screen",
        "description": "Render what the watch is showing right now (the notification Zepp "
                       "relays), with clipping/format warnings.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "watch_wait",
        "description": "Block until the watch card changes (or timeout) and return every "
                       "distinct frame seen, to validate a live navigation run.",
        "inputSchema": {"type": "object", "properties": {
            "seconds": {"type": "integer", "default": 60, "maximum": 300}}},
    },
    {
        "name": "watch_timeline",
        "description": "Recent publish/suppress events from the bridge, newest last.",
        "inputSchema": {"type": "object", "properties": {
            "limit": {"type": "integer", "default": 20}}},
    },
    {
        "name": "watch_probe",
        "description": "Replay a nav card through the real publish pipeline (adb-only debug "
                       "receiver) and read back what the watch was handed.",
        "inputSchema": {"type": "object", "properties": {
            "instruction": {"type": "string",
                            "default": "Turn right onto Jl. Sudirman"},
            "distance": {"type": "string", "default": "200m"}},
            "required": []},
    },
    {
        "name": "device_health",
        "description": "Bridge wired correctly? Listener enabled, app installed, doze "
                       "whitelisted, current card.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


def call(name, args):
    if name == "watch_screen":
        c = card()
        return frame(c) + ("\n\nraw: " + json.dumps(c, ensure_ascii=False) if c else "")
    if name == "watch_wait":
        secs = min(int(args.get("seconds", 60) or 60), 300)
        end = time.time() + secs
        frames, last = [], None
        while time.time() < end:
            c = card()
            key = (c or {}).get("title", "") + "|" + (c or {}).get("body", "")
            if key != last:
                frames.append(f"[+{secs - int(end - time.time())}s]\n" + frame(c))
                last = key
            time.sleep(1)
        if not frames:
            return f"no change in {secs}s\n\n" + frame(card())
        return "\n\n".join(frames[-12:])
    if name == "watch_timeline":
        rows = timeline(int(args.get("limit", 20) or 20))
        return "\n".join(f"{r['time']}  {r['event']}" for r in rows) or "no events"
    if name == "watch_probe":
        ins = (args.get("instruction") or "Turn right onto Jl. Sudirman").replace('"', '')
        dist = (args.get("distance") or "200m").replace('"', '')
        # An explicit activity intent, not a broadcast: this firmware defers broadcasts
        # to background apps, so the replay would silently never run.
        sh("shell", f'am start -n {PKG}/.MainActivity \
--es replay "{ins}" --es distance "{dist}"')
        time.sleep(2.5)
        return frame(card())
    if name == "device_health":
        h = health()
        h["card"] = frame(h["card"]) if h.get("card") else None
        return json.dumps(h, indent=2, ensure_ascii=False)
    raise ValueError(f"unknown tool {name}")


def reply(rid, result=None, error=None):
    msg = {"jsonrpc": "2.0", "id": rid}
    if error:
        msg["error"] = {"code": -32000, "message": str(error)}
    else:
        msg["result"] = result
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stdin.reconfigure(encoding="utf-8")
    except Exception:
        pass
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue
        method, rid = req.get("method"), req.get("id")
        if rid is None:
            continue  # notification
        if method == "initialize":
            reply(rid, {"protocolVersion": "2024-11-05",
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "watchfeed", "version": "1.0"}})
        elif method == "tools/list":
            reply(rid, {"tools": TOOLS})
        elif method == "tools/call":
            p = req.get("params") or {}
            try:
                text = call(p.get("name"), p.get("arguments") or {})
                reply(rid, {"content": [{"type": "text", "text": text}]})
            except Exception as exc:  # surface the failure to the client, don't die
                reply(rid, {"content": [{"type": "text", "text": f"error: {exc}"}],
                            "isError": True})
        elif method == "ping":
            reply(rid, {})
        else:
            reply(rid, error=f"unknown method {method}")


if __name__ == "__main__":
    main()
