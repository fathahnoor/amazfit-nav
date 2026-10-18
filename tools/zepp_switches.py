"""Pair Zepp's per-app switches with their labels and report/toggle them.

Reads a uiautomator XML dump. Rows are laid out as [label .... switch], so a switch is
matched to the text node whose vertical centre is closest within the same band.
"""
import html
import re
import sys

# Only these may deliver to the watch. "amazfitnav" is our own bridge: it has to be
# enabled or nothing reaches the wrist at all.
KEEP_ON = {"maps", "amazfitnav"}
KEEP_ON_TITLES = {"phone apps"}
# Switches that must be forced ON if Zepp has them off.
ENABLE = {"amazfitnav", "maps"}


def nodes(path):
    xml = open(path, encoding="utf-8", errors="replace").read()
    out = []
    for raw in re.findall(r"<node[^>]*>", xml):
        def attr(name, default=""):
            m = re.search(name + r'="([^"]*)"', raw)
            return html.unescape(m.group(1)) if m else default

        b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", raw)
        if not b:
            continue
        l, t, r, bo = map(int, b.groups())
        out.append({
            "text": attr("text"),
            "cls": attr("class").split(".")[-1],
            "checkable": attr("checkable") == "true",
            "checked": attr("checked") == "true",
            "l": l, "t": t, "r": r, "b": bo,
            "cx": (l + r) // 2, "cy": (t + bo) // 2,
        })
    return out


def plan(path):
    ns = nodes(path)
    labels = [n for n in ns if n["text"].strip() and not n["checkable"]]
    rows = []
    for sw in [n for n in ns if n["checkable"] and n["r"] - n["l"] > 20]:
        band = [l for l in labels if abs(l["cy"] - sw["cy"]) < 60 and l["l"] < sw["l"]]
        if not band:
            continue
        label = min(band, key=lambda l: abs(l["cy"] - sw["cy"]))["text"].strip()
        rows.append((label, sw))
    return rows


def main():
    path = sys.argv[1]
    apply = "--apply" in sys.argv
    for label, sw in plan(path):
        low = label.lower()
        keep = any(k in low for k in KEEP_ON) or low in KEEP_ON_TITLES
        enable = low in ENABLE
        if sw["checked"] and not keep:
            print(f"{'TAP OFF' if apply else 'would turn off'}: {label:34s} @ ({sw['cx']},{sw['cy']})")
        elif not sw["checked"] and enable:
            print(f"{'TAP ON' if apply else 'would turn on'}: {label:34s} @ ({sw['cx']},{sw['cy']})")
        elif sw["checked"]:
            print(f"keep ON  : {label}")
        else:
            print(f"already off: {label}")


if __name__ == "__main__":
    main()
