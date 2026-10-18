"""Toggle one app's switch in Zepp's notification allowlist.

Usage: python tools/zepp_toggle.py <label-substring> <on|off> [--find]

Dumps the visible list, finds the row whose label contains the substring and whose
switch state is not the requested one, and taps it. Run it while the Zepp app list is
on screen (Device -> Notifications and reminders -> Notifications).
"""
import subprocess
import sys

from zepp_switches import plan

from mcp_watch import device as _pick_device
DEVICE = _pick_device()


def sh(*args):
    return subprocess.run(["adb"] + (["-s", DEVICE] if DEVICE else []) + list(args),
                          capture_output=True, text=True).stdout


def main():
    needle = sys.argv[1].lower()
    want_on = sys.argv[2].lower() == "on"

    sh("shell", "uiautomator", "dump", "/data/local/tmp/z.xml")
    xml = sh("shell", "cat", "/data/local/tmp/z.xml")
    open("re/z.xml", "w", encoding="utf-8").write(xml)

    for label, sw in plan("re/z.xml"):
        if needle in label.lower():
            state = "on" if sw["checked"] else "off"
            print(f"found '{label}' currently {state}")
            if sw["checked"] == want_on:
                print("already in the wanted state")
                return
            sh("shell", "input", "tap", str(sw["cx"]), str(sw["cy"]))
            print(f"tapped switch at ({sw['cx']},{sw['cy']})")
            return
    print(f"label containing '{needle}' not on screen; scroll and retry")
    sys.exit(1)


if __name__ == "__main__":
    main()
