"""Tap the first UI node whose text starts with the given prefix.

Reads the current uiautomator dump from the device, so coordinates are always fresh.
"""
import html
import re
import subprocess
import sys
import time

from mcp_watch import device as _pick_device
DEVICE = _pick_device()


def sh(*args):
    return subprocess.run(["adb"] + (["-s", DEVICE] if DEVICE else []) + list(args),
                          capture_output=True, text=True).stdout


def main():
    prefix = sys.argv[1]
    wait = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    sh("shell", "uiautomator", "dump", "/data/local/tmp/t.xml")
    xml = sh("shell", "cat", "/data/local/tmp/t.xml")
    for raw in re.findall(r"<node[^>]*>", xml):
        m = re.search(r'text="([^"]*)"', raw)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw)
        if not (m and b):
            continue
        text = html.unescape(m.group(1))
        if text.strip().startswith(prefix):
            l, t, r, bo = map(int, b.groups())
            x, y = (l + r) // 2, (t + bo) // 2
            if wait:
                time.sleep(wait)
            sh("shell", "input", "tap", str(x), str(y))
            print(f"tapped '{text[:40]}' at ({x},{y})")
            return
    print(f"no node starting with '{prefix}'")
    sys.exit(1)


if __name__ == "__main__":
    main()
