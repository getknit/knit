#!/usr/bin/env python3
"""Drive a release-shaped Knit APK past onboarding on an emulator, and fail if it crashes.

    python3 scripts/release-smoke.py APK [--serial emulator-5554] [--timeout 30]

Launching is not enough on its own. Three R8 defects have broken resource-XML vector drawables in minified builds: a
renamed platform interface the framework's parser no longer implemented (ADR 012), a `throw null` from a closed-world
inference that let onboarding render and died on the chat list (ADR 050), and a cast to a renamed
`XmlResourceParser` that stopped 2.11.0-alpha.1 at its first frame, onboarding's welcome page on a new install and the
chat list's room avatar on a phone already set up (GitHub #59). So this goes where people go:

1. a clean install (auto-restore off, so no backup skips onboarding);
2. onboarding: Get started, Skip for now, then the radio permissions granted with `pm grant` and Allow tapped (the
   request returns at once, so no system dialog), then Start meshing;
3. the chat list, which draws the room avatar;
4. the Nearby room: type a message, send it, see it in the transcript;
5. the overflow menu's Settings screen;
6. a cold restart back to the chat list, which must show the sent message.

Any `FATAL EXCEPTION` from the app in the crash buffer, at any step, fails the run and prints it; retrace it with the
build's mapping (`app/build/outputs/mapping/release/mapping.txt`). Elements are found by the Compose test tags the app
exposes as resource ids (`screen_onboarding`, `onboarding_next`, `chat_input`, …) and, where a screen has none, by the
English strings in app/src/main/res/values/strings.xml, with the app's locale pinned to en-US.

An unsigned APK (`app-release-unsigned.apk`) is aligned and signed with the debug key into a temporary copy. The
script uninstalls Knit first, which deletes its data, so it refuses any serial that is not `emulator-*`: the lab
phones hold organic state (.agents/rules/devices.md).
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PKG = "app.getknit.knit"
ACTIVITY = f"{PKG}/.MainActivity"
REPO = pathlib.Path(__file__).resolve().parent.parent
STRINGS = REPO / "app/src/main/res/values/strings.xml"
# Granted on the permissions page, not before launch: holding them is what makes KnitApp count a phone as onboarded.
RADIO_PERMISSIONS = ["BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "NEARBY_WIFI_DEVICES"]


class SmokeFailure(Exception):
    pass


def strings() -> dict[str, str]:
    out = {}
    for el in ET.parse(STRINGS).getroot().iter("string"):
        out[el.get("name")] = re.sub(r"\\(['\"@?])", r"\1", "".join(el.itertext()))
    return out


def sdk_tool(name: str) -> str:
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "~/Android/Sdk")
    def version(tool: pathlib.Path) -> list[int]:
        return [int(x) for x in re.findall(r"\d+", tool.parent.name)]

    found = sorted((sdk.expanduser() / "build-tools").glob(f"*/{name}"), key=version)
    if not found:
        sys.exit(f"release-smoke: {name} not found under {sdk}/build-tools (set ANDROID_HOME)")
    return str(found[-1])


def signed(apk: pathlib.Path, tmp: pathlib.Path) -> pathlib.Path:
    if subprocess.run([sdk_tool("apksigner"), "verify", str(apk)], capture_output=True, check=False).returncode == 0:
        return apk
    aligned, out = tmp / "aligned.apk", tmp / "smoke-signed.apk"
    subprocess.run([sdk_tool("zipalign"), "-f", "-p", "4", str(apk), str(aligned)], check=True)
    keystore = pathlib.Path("~/.android/debug.keystore").expanduser()
    subprocess.run([sdk_tool("apksigner"), "sign", "--ks", str(keystore), "--ks-pass", "pass:android",
                    "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey", "--out", str(out), str(aligned)],
                   check=True)
    print(f"signed {apk.name} with the debug key")
    return out


class Device:
    def __init__(self, serial: str, timeout: float):
        self.serial, self.timeout = serial, timeout

    def adb(self, *args: str, check: bool = True) -> str:
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, check=False)
        if check and r.returncode != 0:
            raise SmokeFailure(f"adb {' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def nodes(self) -> list[dict[str, str]]:
        self.adb("shell", "uiautomator", "dump", "/sdcard/knit-smoke.xml", check=False)
        raw = self.adb("exec-out", "cat", "/sdcard/knit-smoke.xml", check=False)
        try:
            return [n.attrib for n in ET.fromstring(raw).iter("node")]
        except ET.ParseError:
            return []

    def crash(self) -> str | None:
        log = self.adb("logcat", "-d", "-b", "crash", check=False)
        blocks = re.split(r"(?=^.*FATAL EXCEPTION)", log, flags=re.MULTILINE)
        hits = [b for b in blocks if "FATAL EXCEPTION" in b and f"Process: {PKG}," in b]
        return hits[0] if hits else None

    def wait(self, what: str, match) -> dict[str, str]:
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            if (c := self.crash()) is not None:
                raise SmokeFailure(f"the app crashed waiting for {what}:\n" + "\n".join(c.splitlines()[:40]))
            for n in self.nodes():
                if match(n):
                    return n
            time.sleep(1)
        raise SmokeFailure(f"timed out after {self.timeout:.0f} s waiting for {what}")

    def tap(self, node: dict[str, str]) -> None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node["bounds"]))
        self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tag(t: str):
    return lambda n: n.get("resource-id") == t


def text(t: str):
    return lambda n: n.get("text") == t


def desc_prefix(p: str):
    return lambda n: n.get("content-desc", "").startswith(p)


def run(dev: Device, apk: pathlib.Path) -> None:
    s = strings()
    room = s["nearby_title"] + ","
    restore = dev.adb("shell", "settings", "get", "secure", "backup_auto_restore", check=False).strip()
    dev.adb("shell", "settings", "put", "secure", "backup_auto_restore", "0", check=False)
    try:
        dev.adb("uninstall", PKG, check=False)
        dev.adb("install", str(apk))
        dev.adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
        dev.adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", "en-US", check=False)
        dev.adb("logcat", "-c", "-b", "all", check=False)
        dev.adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        print(f"ok  installed and launched {apk.name}")

        dev.wait("onboarding's welcome page", tag("onboarding_page_welcome"))
        dev.tap(dev.wait(s["onboarding_get_started"], tag("onboarding_next")))
        dev.wait("onboarding's name page", tag("onboarding_page_name"))
        dev.tap(dev.wait(s["onboarding_name_skip"], text(s["onboarding_name_skip"])))
        dev.wait("onboarding's permissions page", tag("onboarding_page_permissions"))
        for p in RADIO_PERMISSIONS:
            dev.adb("shell", "pm", "grant", PKG, f"android.permission.{p}", check=False)
        dev.tap(dev.wait(f"{s['onboarding_allow']} (Nearby devices)", tag("onboarding_grant")))
        dev.tap(dev.wait(f"{s['onboarding_start']}, enabled",
                         lambda n: tag("onboarding_start")(n) and n.get("enabled") == "true"))
        print("ok  onboarding")

        dev.tap(dev.wait("the chat list's Nearby row (the room avatar)", desc_prefix(room)))
        print("ok  chat list rendered")

        message = f"Smoke test {int(time.time())}"
        dev.tap(dev.wait("the message field", tag("chat_input")))
        dev.adb("shell", "input", "text", message.replace(" ", "%s"))
        dev.tap(dev.wait("Send", tag("chat_send")))
        dev.wait("the sent message in the transcript", text(message))
        print("ok  sent a message in Nearby")

        for _ in range(3):
            if any(desc_prefix(room)(n) for n in dev.nodes()):
                break
            dev.adb("shell", "input", "keyevent", "BACK")
            time.sleep(1)
        dev.tap(dev.wait(s["chat_more_options"], lambda n: n.get("content-desc") == s["chat_more_options"]))
        dev.tap(dev.wait(s["settings_title"], text(s["settings_title"])))
        dev.wait("the Settings screen", tag("screen_settings"))
        dev.adb("shell", "input", "keyevent", "BACK")
        print("ok  Settings")

        dev.adb("shell", "am", "force-stop", PKG)
        dev.adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        dev.wait("the chat list after a cold restart, showing the message",
                 lambda n: desc_prefix(room)(n) and message in n.get("content-desc", ""))
        if (c := dev.crash()) is not None:
            raise SmokeFailure("the app crashed:\n" + "\n".join(c.splitlines()[:40]))
        print("ok  cold restart")
    finally:
        if restore and restore != "null":
            dev.adb("shell", "settings", "put", "secure", "backup_auto_restore", restore, check=False)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk", type=pathlib.Path)
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--timeout", type=float, default=30.0, help="seconds to wait for each screen (default 30)")
    args = ap.parse_args()
    serial = args.serial
    if not serial:
        listing = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True).stdout
        attached = [line.split()[0] for line in listing.splitlines()[1:] if line.endswith("\tdevice")]
        emulators = [d for d in attached if d.startswith("emulator-")]
        serial = emulators[0] if len(emulators) == 1 else None
        if serial is None:
            sys.exit(f"release-smoke: pass --serial; attached emulators: {emulators or 'none'}")
    if not serial.startswith("emulator-"):
        sys.exit(f"release-smoke: {serial} is not an emulator; this uninstalls Knit and deletes its data")
    with tempfile.TemporaryDirectory() as tmp:
        apk = signed(args.apk, pathlib.Path(tmp))
        try:
            run(Device(serial, args.timeout), apk)
        except SmokeFailure as e:
            print(f"\nFAIL: {e}", file=sys.stderr)
            sys.exit(1)
    print("\nOK")


if __name__ == "__main__":
    main()
