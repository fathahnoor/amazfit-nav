"""Live inspector for what the watch actually receives.

The T-Rex Pro has no accessible API (BLE, proprietary protocol), so a real screenshot is
impossible. What we *can* do is read the notification exactly as Zepp hands it to the
watch, render it in the watch's layout and flag anything that would clip or look wrong.

Usage:
    python tools/watchfeed.py [seconds]

Prints a frame every 2 s plus a line for every notification the bridge publishes.
"""
import re
import subprocess
import sys
import time

DEVICE = "SERIAL_REMOVED"
PKG = "com.fathahnoor.amazfitnav"
BODY_BUDGET = 24          # chars that survive the round display


def sh(*args, timeout=15):
    try:
        # dumpsys is not valid cp1252 on Windows; decode as UTF-8 and never fail.
        return subprocess.run(["adb", "-s", DEVICE] + list(args),
                              capture_output=True, text=True, timeout=timeout,
                              encoding="utf-8", errors="replace").stdout or ""
    except (subprocess.TimeoutExpired, OSError):
        return ""


def current_card():
    dump = sh("shell", "dumpsys", "notification", "--noredact")
    blocks = dump.split("NotificationRecord(")
    for b in blocks:
        if f"pkg={PKG}" not in b or "tag=nav-" not in b:
            continue
        title = re.search(r"android\.title=String \((.*?)\)\n", b)
        text = re.search(r"android\.text=String \((.*?)\)\n", b)
        channel = re.search(r"channel=(\S+)", b)
        icon = re.search(r"icon=Icon\(typ=(\w+)", b)
        return {
            "title": title.group(1) if title else "",
            "body": text.group(1) if text else "",
            "channel": channel.group(1) if channel else "?",
            "icon": icon.group(1) if icon else "?",
        }
    return None


def render(card):
    t = card["title"]
    b = card["body"]
    warn = []
    if len(b) > BODY_BUDGET:
        warn.append(f"body {len(b)} chars > {BODY_BUDGET} -> will clip")
    if not re.match(r"^[\u2190-\u21ff\u2b00-\u2bff\u25a0-\u25ff\u27a1]", t):
        warn.append("no arrow glyph in title")
    arriving = "Arriving" in t
    if not arriving and not re.search(r"\d", t):
        warn.append("no distance in title")
    if not arriving and "m" not in t and "km" not in t:
        warn.append("distance unit missing")
    lines = [
        "+----------------------------+",
        f"| {'APP':<4}{card['channel'][:14]:<16}      |",
        f"|                            |",
        f"|  {t:<24}  |",
        f"|  {b:<24}  |",
        "+----------------------------+",
        f"   icon={card['icon']}  " + ("OK" if not warn else "; ".join(warn)),
    ]
    return "\n".join(lines)


def main():
    # The Windows console is cp1252 by default and cannot print arrow glyphs.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 30
    end = time.time() + seconds
    last = None
    print(f"watching {PKG} for {seconds:.0f}s\n")
    while time.time() < end:
        card = current_card()
        if card and card != last:
            print(render(card))
            print()
            last = card
        time.sleep(2)
    print("done")


if __name__ == "__main__":
    main()
