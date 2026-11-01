#!/usr/bin/env python3
"""Drive a release-shaped Knit APK past onboarding on an emulator, and fail if it crashes.

    python3 scripts/release-smoke.py APK [--serial emulator-5554] [--timeout 30] [--video FILE]

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
6. a cold restart back to the chat list, which must show the sent message;
7. a video shared into Knit (`ACTION_SEND`, as a gallery app does) and sent in Nearby: the share-target picker, the
   re-encode the composer runs at once (ADR 2026-10.hmfe), the staged video, Send, and the video bubble.

Step 7 exists because R8 9.4.24 merged Media3's `DefaultAssetLoaderFactory` into another class and cast its widened
field back to `android.media.metrics.LogSessionId`, a class API 29 and 30 do not have. 2.10.0 and 2.10.1 crashed on
every video send on Android 10 and 11 (ADR 2026-11.zmc6), and no step here sent one. A cast like that fails only below
the API level that adds its class, so run this on an API 29 emulator (minSdk) as well as a current one. The release
skill's CI mirror runs both. The video is `scripts/fixtures/smoke-video.mp4`, 3 s of H.264 and AAC like a phone's.
It is pushed to `Movies/`, found in MediaStore, and shared as that `content://` item with a read grant from the shell,
which owns it. That is how a gallery app hands one over, and it needs no storage permission in Knit. A `file://`
stream in Knit's own `Android/data` folder does not work on API 30+, where a folder the shell creates there is the
shell's and Knit gets EACCES.

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


def radio_permissions(sdk: int) -> list[str]:
    """The app's `requiredRadioPermissions` (ui/Permissions.kt), tiered by API level the same way.

    Granted on the permissions page, not before launch: holding them is what makes KnitApp count a phone as onboarded.
    """
    split_ble = ["BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT"]
    location = ["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"]
    if sdk >= 33:
        return ["NEARBY_WIFI_DEVICES", *split_ble]
    return [*split_ble, *location] if sdk >= 31 else location


VIDEO = REPO / "scripts/fixtures/smoke-video.mp4"
VIDEO_MEDIA = "content://media/external/video/media"
# A software encode on an emulator, then the room's NSFW screen over twelve frames: more than a screen's wait.
VIDEO_TIMEOUT = 180.0


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

    def wait(self, what: str, match, timeout: float | None = None) -> dict[str, str]:
        timeout = max(self.timeout, timeout or 0)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if (c := self.crash()) is not None:
                raise SmokeFailure(f"the app crashed waiting for {what}:\n" + "\n".join(c.splitlines()[:40]))
            for n in self.nodes():
                if match(n):
                    return n
            time.sleep(1)
        raise SmokeFailure(f"timed out after {timeout:.0f} s waiting for {what}")

    def tap(self, node: dict[str, str]) -> None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node["bounds"]))
        self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tag(t: str):
    return lambda n: n.get("resource-id") == t


def text(t: str):
    return lambda n: n.get("text") == t


def desc_prefix(p: str):
    return lambda n: n.get("content-desc", "").startswith(p)


def media_id(dev: Device, name: str) -> str | None:
    # One argv string through the device's shell: the double quotes keep the SQL's single quotes.
    where = f"\"_display_name='{name}'\""
    rows = dev.adb("shell", "content", "query", "--uri", VIDEO_MEDIA, "--projection", "_id", "--where", where,
                   check=False)
    found = re.search(r"_id=(\d+)", rows)
    return found.group(1) if found else None


def send_video(dev: Device, s: dict[str, str], room: str, video: pathlib.Path) -> None:
    name = f"knit-{video.name}"
    remote = f"/sdcard/Movies/{name}"
    if (stale := media_id(dev, name)) is not None:
        dev.adb("shell", "content", "delete", "--uri", f"{VIDEO_MEDIA}/{stale}", check=False)
    dev.adb("push", str(video), remote)
    # API 30+ indexes a file written through FUSE by itself; API 29 needs the scan asked for.
    dev.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{remote}",
            check=False)
    deadline = time.monotonic() + dev.timeout
    while (item := media_id(dev, name)) is None:
        if time.monotonic() > deadline:
            raise SmokeFailure(f"{remote} never appeared in MediaStore")
        time.sleep(1)
    # The URI is the intent's data as well as its EXTRA_STREAM: `am start` does not copy the stream into ClipData the
    # way an app's startActivity does, so without data the read grant would attach to nothing.
    uri = f"{VIDEO_MEDIA}/{item}"
    dev.adb("shell", "am", "start", "-W", "-n", ACTIVITY, "-a", "android.intent.action.SEND", "-d", uri, "-t",
            "video/mp4", "--grant-read-uri-permission", "--eu", "android.intent.extra.STREAM", uri)
    dev.wait("the share-target picker", tag("screen_share_target"))
    dev.tap(dev.wait("the picker's Nearby row", desc_prefix(room)))
    # The staged row's Remove button, not its `chat_staged_video` tag: the tag is newer than 2.10, and the mirror runs
    # this script on a hotfix branch's build too. While the video encodes, the composer shows a progress tile instead.
    remove = s["chat_remove_attachment"]
    dev.wait("the re-encoded video staged in the composer", lambda n: n.get("content-desc") == remove, VIDEO_TIMEOUT)
    dev.tap(dev.wait("Send", tag("chat_send")))
    dev.wait("the sent video in the transcript", tag("chat_video_bubble"))


def run(dev: Device, apk: pathlib.Path, video: pathlib.Path) -> None:
    s = strings()
    room = s["nearby_title"] + ","
    sdk = int(dev.adb("shell", "getprop", "ro.build.version.sdk").strip())
    restore = dev.adb("shell", "settings", "get", "secure", "backup_auto_restore", check=False).strip()
    dev.adb("shell", "settings", "put", "secure", "backup_auto_restore", "0", check=False)
    try:
        dev.adb("uninstall", PKG, check=False)
        dev.adb("install", str(apk))
        dev.adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
        dev.adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", "en-US", check=False)
        dev.adb("logcat", "-c", "-b", "all", check=False)
        dev.adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        print(f"ok  installed and launched {apk.name} on API {sdk}")

        dev.wait("onboarding's welcome page", tag("onboarding_page_welcome"))
        dev.tap(dev.wait(s["onboarding_get_started"], tag("onboarding_next")))
        dev.wait("onboarding's name page", tag("onboarding_page_name"))
        dev.tap(dev.wait(s["onboarding_name_skip"], text(s["onboarding_name_skip"])))
        dev.wait("onboarding's permissions page", tag("onboarding_page_permissions"))
        for p in radio_permissions(sdk):
            dev.adb("shell", "pm", "grant", PKG, f"android.permission.{p}", check=False)
        dev.tap(dev.wait(f"{s['onboarding_allow']} (the radio permissions)", tag("onboarding_grant")))
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
        print("ok  cold restart")

        send_video(dev, s, room, video)
        if (c := dev.crash()) is not None:
            raise SmokeFailure("the app crashed:\n" + "\n".join(c.splitlines()[:40]))
        print("ok  shared a video into Nearby and sent it")
    finally:
        if restore and restore != "null":
            dev.adb("shell", "settings", "put", "secure", "backup_auto_restore", restore, check=False)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk", type=pathlib.Path)
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--timeout", type=float, default=30.0, help="seconds to wait for each screen (default 30)")
    ap.add_argument("--video", type=pathlib.Path, default=VIDEO,
                    help=f"the video to share (default {VIDEO.relative_to(REPO)})")
    args = ap.parse_args()
    if not args.video.is_file():
        sys.exit(f"release-smoke: no video at {args.video}")
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
            run(Device(serial, args.timeout), apk, args.video)
        except SmokeFailure as e:
            print(f"\nFAIL: {e}", file=sys.stderr)
            sys.exit(1)
    print("\nOK")


if __name__ == "__main__":
    main()
