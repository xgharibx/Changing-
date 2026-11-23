#!/usr/bin/env python3
"""Tiny adb UI driver for slow/CPU-emulated devices: dump the accessibility tree, find nodes, tap them.
Usage: adbui.py dump OUT.xml | tap --text TEXT | tap --id TAG | wait --text TEXT [--timeout S] | texts"""
import re, subprocess, sys, time, argparse, xml.etree.ElementTree as ET
ADB = "/opt/android-sdk/platform-tools/adb"
def sh(*a): return subprocess.run([ADB, *a], capture_output=True, text=True).stdout
def dump(path="/tmp/ui.xml"):
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml"); xml = sh("shell", "cat", "/sdcard/ui.xml")
    open(path, "w").write(xml); return ET.fromstring(xml) if xml.strip().startswith("<?xml") else None
def find(root, text=None, rid=None, contains=False):
    for n in root.iter("node"):
        t, r = n.get("text", ""), n.get("resource-id", "")
        if (text and ((text in t) if contains else t == text)) or (rid and r.endswith(rid)) or (text and n.get("content-desc") == text): return n
def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds"))); return (x1 + x2) // 2, (y1 + y2) // 2
if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("cmd"); ap.add_argument("out", nargs="?"); ap.add_argument("--text"); ap.add_argument("--id"); ap.add_argument("--contains", action="store_true"); ap.add_argument("--timeout", type=int, default=90)
    a = ap.parse_args()
    if a.cmd == "dump": dump(a.out); print("saved", a.out)
    elif a.cmd == "texts":
        r = dump(); print(" | ".join(n.get("text") for n in r.iter("node") if n.get("text")))
    elif a.cmd in ("tap", "wait"):
        end = time.time() + a.timeout
        while time.time() < end:
            r = dump()
            n = r is not None and find(r, a.text, a.id, a.contains)
            if n is not None and n is not False:
                if a.cmd == "tap": x, y = center(n); sh("shell", "input", "tap", str(x), str(y))
                print(a.cmd, "ok", a.text or a.id); sys.exit(0)
            time.sleep(4)
        print(a.cmd, "TIMEOUT", a.text or a.id); sys.exit(1)
