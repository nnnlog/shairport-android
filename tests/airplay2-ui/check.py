#!/usr/bin/env python3
"""On an unlocked device with the APK installed: check.py open|closed.

open: a device that lets apps bind UDP ports 319 and 320 (Android 17); the AirPlay 2 switch is
enabled. closed: one that doesn't; set airplay_2=true via adb with the receiver stopped first: the
UI must turn it off, not merely disable it, and say why.
Uses adb's ANDROID_SERIAL when set. Never plays audio.
"""
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SUMMARY = {
    "open": "Multiroom, buffered audio.",
    "closed": "Unavailable: this device doesn't let apps use UDP ports 319 and 320,"
    " which AirPlay 2's timing (NQPTP) needs.",
}
assert len(sys.argv) == 2 and sys.argv[1] in SUMMARY, __doc__
ports_open = sys.argv[1] == "open"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)


adb("shell", "input", "keyevent", "KEYCODE_HOME")
adb("shell", "am", "start", "-n", "com.hkfuertes.shairport/.MainActivity")
for _ in range(10):
    time.sleep(1)
    adb("shell", "uiautomator", "dump", "/data/local/tmp/shairport-airplay2-ui.xml")
    tree = ET.fromstring(adb("shell", "cat", "/data/local/tmp/shairport-airplay2-ui.xml"))
    parents = {child: parent for parent in tree.iter() for child in parent}
    row = next((e for e in tree.iter("node") if e.get("text") == "AirPlay 2"), None)
    assert row is not None, "Unlock the device and leave the top of Shairport settings visible"
    while row is not None:
        switches = [e for e in row.iter("node") if e.get("class") == "android.widget.Switch"]
        if len(switches) == 1:
            break
        row = parents.get(row)
    assert row is not None, "AirPlay 2 switch not found"
    switch = switches[0]
    if (switch.get("enabled") == "true") == ports_open and (ports_open or switch.get("checked") == "false"):
        assert any(e.get("text") == SUMMARY[sys.argv[1]] for e in row.iter("node")), "Wrong subtitle"
        print("AirPlay 2 port gating and subtitle: OK (" + sys.argv[1] + ")")
        break
else:
    raise AssertionError("AirPlay 2 was not enabled with the ports open, or off and disabled with them closed")
