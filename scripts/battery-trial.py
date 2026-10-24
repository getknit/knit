#!/usr/bin/env python3
"""Measure what a phone's battery pays for each mesh posture, block after block, with adb off the air.

    python3 scripts/battery-trial.py check --phone p3=SERIAL
    python3 scripts/battery-trial.py run --phone p3=SERIAL [--arms shipped,nanoff,nanoff-cap2] [--cycles 2] \\
        [--block 120] [--settle 5] [--first-settle 20] [--doze off|asis] [--min-level 25] [--out DIR]
    python3 scripts/battery-trial.py summary DIR [DIR ...]

Phase 0 of ADR 2026-10.d44b (low-power mode). Each arm is a posture the debug build can already take with no new
code: Wi-Fi Aware off through `…debug.NANOFF`, a link cap through `…debug.BLECAP`, and Instant Communication Mode
off through `…debug.NANICM` (`icmoff`, refused below API 33: the plane never asks for ICM there, so the arm would run
as shipped). Since ADR 2026-10.486r a lone phone asks for no ICM at all, so on a lone phone `icmoff` and `shipped`
measure the same thing; the split needs a phone with a peer. Every `NANICM` send also carries `--es band auto`, so a
band pinned by an earlier trial cannot leak into an arm (an older build ignores the extra). The `lowpower` arm is Phase 1's posture as built, through `…debug.LOWPOWER`. Arms run in turn, `--cycles` times over (A B C A B C), so a drift in the room or the battery lands on
every arm alike.

One block is: connect, apply the arm, disconnect; wait `--settle` minutes (the links shed, the plane goes); connect,
read the fuel gauge's charge counter and reset batterystats, disconnect; wait `--block` minutes; connect, read the
counter again and dump batterystats. The figure is the **whole phone's** mean current over the window, from the
charge counter (µAh), because the power model under-bills radio work: on the P3 the Bluetooth HAL's kernel wakelock
holds the CPU up for Knit's links and is billed to uid 0, not to the app. The arms differ only in Knit's posture, so
the difference between arms is what the posture costs. batterystats' own estimate for Knit's uid, the CPU's awake
fraction and the Bluetooth wakelocks are kept beside it as the explanation.

**adb must be off the air during a window.** A network-adb host's 1 s TCP keepalive wakes a phone's AP about once a
second, and Android Studio's `process-tracker --interval 1000` polls /proc at 1 Hz; either swamps what is measured.
So the run disconnects between boundaries, kills any `process-tracker` on the phone at each boundary, and flags a
window whose shell uid (2000) received more Wi-Fi packets than a boundary's own traffic explains, or whose history
shows a screen wake or a charger. Quit Android Studio's device connection to the phone, and keep other sessions off
it, for the whole run. A USB serial is refused: USB charges the phone.

Before `run`: the phone unplugged (the run waits for that, so it can be started while charging), screen off, airplane
mode with Wi-Fi and Bluetooth back on if it has no SIM, and nothing else of the lab driving it (knit-ios's
`scripts/interop.py` wakes the screen; the run flags a window it overlapped). `--doze off` holds Doze off for the run
(`dumpsys deviceidle disable`, restored after) so a still phone stands in for a carried one; `asis` measures the
still phone. The run stops before the battery reaches `--min-level`, ahead of the 15 % at which `PowerState.batteryLow`
changes the cadences. Everything it changed is put back at the end, or on Ctrl-C.

Every block is one JSON line in DIR/blocks.jsonl, with the raw batterystats and the transports' log for the window
beside it; `summary` groups any number of run directories by arm. Driving a physical phone needs the maintainer's
go-ahead for the session (.agents/rules/devices.md), and Wi-Fi is never toggled on a network-adb phone.
"""

from __future__ import annotations

import argparse
import datetime as dt
import gzip
import json
import re
import signal
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BRIDGE = ROOT / "scripts" / "bridge.sh"
PKG = "app.getknit.knit"
CHARGE_SYSFS = "/sys/class/power_supply/battery/charge_counter"
LOG_TAGS = ["BluetoothMeshTransport", "WifiAwareTransport", "MeshService", "MeshManager", "SwitchableTransport"]
ICM_MIN_SDK = 33  # WifiAwareTransport.instantModeAvailable: ICM is API 33 (TIRAMISU) and the chip's own answer

# The postures Phase 0 can take on a debug build as it is. `cap` None clears the debug link cap (6 links, as shipped).
ARMS = {
    "shipped": {"nanOff": False, "cap": None},
    "icmoff": {"nanOff": False, "cap": None, "icm": False},
    "nanoff": {"nanOff": True, "cap": None},
    "cap2": {"nanOff": False, "cap": 2},
    "nanoff-cap2": {"nanOff": True, "cap": 2},
    "nanoff-cap1": {"nanOff": True, "cap": 1},
    # Phase 1's posture as built (`…debug.LOWPOWER`): Wi-Fi Aware off through the release path, no motion heal.
    "lowpower": {"nanOff": False, "cap": None, "lowPower": True},
}

# A window's shell uid sees our own boundary traffic (the connect before the dump, 365-1367 packets on the P3's first
# night, more when a reconnect retries) and nothing else; a host left connected adds its keepalive, about one a second,
# and wakes the CPU on each, so it also shows as a train of ~600 wakes per 10 minutes in the window's history.
SHELL_PACKETS_CLEAN = 2000
# An ambient-display (Doze) pulse for a notification lights the screen about 5 s without a wake.
SCREEN_PULSE_S = 30


def log(msg: str) -> None:
    print(f"[{dt.datetime.now():%H:%M:%S}] {msg}", file=sys.stderr, flush=True)


def duration_s(text: str) -> float:
    """batterystats' `1h 2m 3s 456ms` as seconds."""
    units = {"d": 86400, "h": 3600, "m": 60, "s": 1, "ms": 0.001}
    return sum(float(n) * units[u] for n, u in re.findall(r"(\d+)(ms|d|h|m|s)\b", text))


class Phone:
    def __init__(self, label: str, serial: str) -> None:
        self.label, self.serial = label, serial
        self.network = ":" in serial
        self.uid = ""

    def adb(self, *args: str, timeout: float = 30) -> subprocess.CompletedProcess:
        return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout, check=False)

    def connect(self) -> None:
        """Brings a network-adb phone back. A wedged transport answers ping but no shell, so it is dropped and redialed."""
        if not self.network:
            return
        for attempt in range(6):
            self.adb("connect", self.serial, timeout=20)
            try:
                if self.sh("echo up", timeout=15).strip() == "up":
                    return
            except subprocess.TimeoutExpired:
                pass
            self.adb("disconnect", self.serial, timeout=15)
            time.sleep(5 + 10 * attempt)
        raise RuntimeError(f"{self.label}: no adb after 6 attempts")

    def disconnect(self) -> int:
        """Leaves the phone alone: Studio's poller is killed on the way out (Studio starts a new one whenever it sees
        the phone), then the transport is dropped. Returns how many pollers were killed."""
        killed = self.kill_trackers()
        if self.network:
            self.adb("disconnect", self.serial, timeout=15)
        return killed

    def sh(self, command: str, timeout: float = 60) -> str:
        r = subprocess.run(["adb", "-s", self.serial, "shell", command], capture_output=True, text=True,
                           timeout=timeout, check=False)
        return r.stdout.replace("\r", "")

    def bridge(self, action: str, *extras: str) -> dict:
        r = subprocess.run([str(BRIDGE), self.serial, action, *extras], capture_output=True, text=True, timeout=60,
                           check=False)
        if r.returncode != 0:
            raise RuntimeError(f"{self.label} {action}: {r.stderr.strip()}")
        reply = json.loads(r.stdout)
        if reply.get("status") == "error":
            raise RuntimeError(f"{self.label} {action}: {reply.get('message')}")
        return reply

    def charge_uah(self) -> int | None:
        """The fuel gauge's charge counter, µAh: sysfs when the shell may read it, else BatteryService's copy."""
        out = self.sh(f"cat {CHARGE_SYSFS} 2>/dev/null; dumpsys battery | grep -m1 'Charge counter'")
        for line in out.splitlines():
            line = line.strip()
            if line.isdigit():
                return int(line)
            if m := re.search(r"Charge counter: (\d+)", line):
                return int(m.group(1))
        return None

    def battery(self) -> dict:
        out = self.sh("dumpsys battery; dumpsys power | grep -m1 mWakefulness=; dumpsys deviceidle | grep -m1 mState=")
        get = lambda pat: (m.group(1) if (m := re.search(pat, out, re.MULTILINE)) else None)
        return {
            "plugged": bool(re.search(r"(AC|USB|Wireless) powered: true", out)),
            "usb": bool(re.search(r"USB powered: true", out)),
            "level": int(v) if (v := get(r"^\s*level: (\d+)")) else None,
            "voltageMv": int(v) if (v := get(r"^\s*voltage: (\d+)")) else None,
            "tempDeciC": int(v) if (v := get(r"^\s*temperature: (\d+)")) else None,
            "wakefulness": get(r"mWakefulness=(\w+)"),
            "deepIdle": get(r"mState=(\w+)"),
            "lightIdle": get(r"mLightState=(\w+)"),
        }

    def mesh(self) -> dict:
        try:
            s = self.bridge("STATE")
        except (RuntimeError, json.JSONDecodeError, subprocess.TimeoutExpired) as e:
            return {"error": str(e)}
        return {
            "self": (s.get("self") or {}).get("nodeId"),
            "health": s.get("health"),
            "meshEnabled": s.get("meshEnabled"),
            "meshPaused": s.get("meshPaused"),
            "transports": [{k: t.get(k) for k in ("kind", "health", "linked", "nearby")} for t in s.get("transports", [])],
            "reachable": len(s.get("reachable") or []),
        }

    def tracker_ages(self) -> list[int]:
        """How long each running `process-tracker` has been up, seconds (toybox `ETIME` is `[[dd-]hh:]mm:ss`)."""
        ages = []
        for line in self.sh("ps -A -o ETIME=,ARGS= | grep process-tracker | grep -v grep").splitlines():
            if m := re.match(r"\s*(?:(\d+)-)?(?:(\d+):)?(\d+):(\d+)\s", line):
                d, h, mi, sec = (int(g or 0) for g in m.groups())
                ages.append(((d * 24 + h) * 60 + mi) * 60 + sec)
        return ages

    def kill_trackers(self) -> int:
        """Android Studio's App Inspection leaves `process-tracker --interval 1000` running; it polls /proc at 1 Hz."""
        out = self.sh("for p in $(pgrep -f process-tracker); do kill $p && echo $p; done")
        return len([l for l in out.split() if l.isdigit()])

    def snapshot(self) -> dict:
        """The counter first, so the reading sits as close to the boundary as one shell call allows."""
        t0 = time.time()
        charge = self.charge_uah()
        t1 = time.time()
        return {"host": (t0 + t1) / 2, "chargeUah": charge, **self.battery()}


def uid_section(text: str, uid: str) -> str:
    """One uid's block of the per-uid statistics: from its `  u0a207:` header to the next two-space header."""
    m = re.search(rf"^  {re.escape(uid)}:\n((?:(?:    |\t).*\n|\n)*)", text, re.MULTILINE) if uid else None
    return m.group(1) if m else ""


def parse_batterystats(text: str, uid: str) -> dict:
    get = lambda pat: (m.group(1) if (m := re.search(pat, text, re.MULTILINE)) else None)
    on_batt = re.search(r"Time on battery: (.+?) \(.*?\) realtime, (.+?) \(", text)
    real, up = (duration_s(on_batt.group(1)), duration_s(on_batt.group(2))) if on_batt else (None, None)
    wakelocks = {name: duration_s(t) for name, t in re.findall(r"Kernel Wake lock (\S+): (.+?) \(\d+ times\)", text)}
    shell = re.search(r"Wi-Fi network: .*?\(packets (\d+) received, (\d+) sent\)", uid_section(text, "2000"))
    knit_uid = uid_section(text, uid)
    # `UID u0a207: 1.2 ( cpu=… )` on the P3; API 37 prints `UID u0a360: 7.11 bg: 7.11`, with no parts.
    knit = re.search(rf"UID {re.escape(uid)}: ([\d.]+)(?: \((.*?)\))?", text) if uid else None
    knit_cpu = re.search(r"Total cpu time: u=(.+?) s=(.+?)\s*$", knit_uid, re.MULTILINE)
    global_bt = re.search(r"^      bluetooth: ([\d.]+) apps: ([\d.]+)", text, re.MULTILINE)
    return {
        "realtimeS": real,
        "uptimeS": up,
        "awakeFrac": (up / real) if real else None,
        "dischargeMah": int(v) if (v := get(r"^  Discharge: (\d+) mAh")) else None,
        "screenOnS": duration_s(v) if (v := get(r"^  Screen on: (.+?) \(")) else 0.0,
        "screenWakes": len(re.findall(r"screenwake=", text)),
        "pluggedEvents": len(re.findall(r"\+plugged", text)),
        # The Bluetooth HAL's kernel wakelock: the P3's Qualcomm HAL, the Pixel 8's Broadcom one.
        "halBluetoothLockS": wakelocks.get("hal_bluetooth_lock", wakelocks.get("bthal_wakelock")),
        "qupUartS": next((v for k, v in wakelocks.items() if "qup_uart" in k), None),
        "shellPacketsRx": int(shell.group(1)) if shell else 0,
        "knitEstMah": float(knit.group(1)) if knit else None,
        "knitEstParts": dict(re.findall(r"(\w+)=([\d.e-]+)", knit.group(2))) if knit and knit.group(2) else {},
        "knitCpuS": (duration_s(knit_cpu.group(1)) + duration_s(knit_cpu.group(2))) if knit_cpu else None,
        "knitBtScanS": duration_s(v.group(1)) if (v := re.search(r"Bluetooth Scan \(total blamed realtime\): (.+?) \(", knit_uid)) else None,
        "knitBtRx": v.group(1) if (v := re.search(r"Bluetooth network: (\S+) received, (\S+) sent", knit_uid)) else None,
        "knitBtTx": v.group(2) if v else None,
        "knitWifiPacketsRx": int(v.group(1)) if (v := re.search(r"Wi-Fi network: .*?\(packets (\d+) received", knit_uid)) else 0,
        "cellularMah": float(v) if (v := get(r"Cellular Battery drain: ([\d.]+)mAh")) else None,
        "wifiMah": float(v) if (v := get(r"WiFi Battery drain: ([\d.]+)mAh")) else None,
        "wifiPacketsRx": int(v) if (v := get(r"Wifi packets received: (\d+)")) else None,
        "wakeupsUnknown": int(v) if (v := get(r"Wakeup reason unknown: .+? \((\d+) times\)")) else None,
        "btGlobalMah": float(global_bt.group(1)) if global_bt else None,
        "btAppsMah": float(global_bt.group(2)) if global_bt else None,
    }


def screen_touched(st: dict) -> bool:
    """A window someone woke the screen in, or that stayed lit; a few seconds' ambient pulse is neither."""
    return st["screenOnS"] > SCREEN_PULSE_S or st["screenWakes"] > 0


class Trial:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        label, serial = args.phone.split("=", 1)
        self.p = Phone(label, serial)
        self.arms = [a.strip() for a in args.arms.split(",") if a.strip()]
        unknown = [a for a in self.arms if a not in ARMS]
        if unknown:
            raise SystemExit(f"unknown arm(s) {unknown}; known: {', '.join(ARMS)}")
        self.out = Path(args.out).expanduser()
        self.dozed_off = False
        self.logcat_size: str | None = None  # the main ring's size before the run, as `-G` takes it
        self.restored = False

    def host_harness(self) -> list[str]:
        r = subprocess.run(["pgrep", "-af", "interop.py"], capture_output=True, text=True, check=False)
        return [l for l in r.stdout.splitlines() if "pgrep" not in l]

    def preflight(self) -> dict:
        p = self.p
        p.connect()
        if not p.network:
            raise SystemExit(f"{p.label}: {p.serial} is a USB serial, and USB charges the phone; use its network serial")
        # `userId=` through API 36, `appId=` from API 37 (the Pixel 8).
        p.uid = (re.search(r"(?:userId|appId)=(\d+)",
                           p.sh(f"dumpsys package {PKG} | grep -m1 -E 'userId=|appId='")) or [None, ""])[1]
        uid = f"u0a{int(p.uid) - 10000}" if p.uid.isdigit() and int(p.uid) >= 10000 else p.uid
        p.uid = uid
        build = dict(re.findall(r"(versionName|lastUpdateTime)=(.+)",
                                p.sh(f"dumpsys package {PKG} | grep -m2 -E 'versionName|lastUpdateTime'")))
        flags = p.sh(f"dumpsys package {PKG} | grep -m1 pkgFlags=")
        info = {
            "phone": p.label,
            "serial": p.serial,
            "sdk": p.sh("getprop ro.build.version.sdk").strip(),
            "model": p.sh("getprop ro.product.model").strip(),
            "build": build,
            "debuggable": "DEBUGGABLE" in flags,
            "uid": uid,
            "chargeUah": p.charge_uah(),
            "airplane": p.sh("settings get global airplane_mode_on").strip(),
            "wifiOn": p.sh("settings get global wifi_on").strip(),
            "bluetoothOn": p.sh("settings get global bluetooth_on").strip(),
            "dozeWhitelisted": PKG in p.sh("dumpsys deviceidle whitelist"),
            "trackers": p.sh("pgrep -af process-tracker").strip().splitlines(),
            "harness": self.host_harness(),
            **p.battery(),
            "mesh": p.mesh(),
        }
        if not info["debuggable"]:
            raise SystemExit(f"{p.label}: not a debug build, so no bridge")
        if info["chargeUah"] is None:
            raise SystemExit(f"{p.label}: no charge counter readable")
        if "icmoff" in self.arms and int(info["sdk"] or 0) < ICM_MIN_SDK:
            raise SystemExit(f"{p.label}: API {info['sdk']} has no Instant Communication Mode, "
                             "so 'icmoff' would run as shipped")
        return info

    def apply(self, arm: str) -> dict:
        a = ARMS[arm]
        nan = self.p.bridge("NANOFF", "--ez", "off", "true" if a["nanOff"] else "false")
        cap = self.p.bridge("BLECAP", "--ez", "clear", "true") if a["cap"] is None \
            else self.p.bridge("BLECAP", "--ei", "max", str(a["cap"]))
        applied = {"nanOff": nan.get("off"), "nanPresent": nan.get("present"), "cap": cap.get("cap")}
        # Low-power mode only when an arm of the run uses it: an older build has no LOWPOWER action.
        if any(ARMS[x].get("lowPower") for x in self.arms):
            low = self.p.bridge("LOWPOWER", "--ez", "on", "true" if a.get("lowPower") else "false")
            applied["lowPower"] = low.get("lowPower")
        # Instant Communication Mode, only while Aware runs: NANICM cycles the live session, and on asks for the
        # hardware's own answer, so an arm without the key leaves it as the build ships it.
        if not a["nanOff"] and not a.get("lowPower"):
            applied["icm"] = self.p.bridge(
                "NANICM", "--ez", "on", "false" if a.get("icm") is False else "true", "--es", "band", "auto",
            )
        return applied

    def sleep(self, minutes: float, what: str) -> None:
        end = time.time() + minutes * 60
        log(f"{what}: {minutes:g} min, adb off the air until {dt.datetime.fromtimestamp(end):%H:%M}")
        while (left := end - time.time()) > 0:
            time.sleep(min(left, 300))

    def wait_unplugged(self) -> None:
        deadline = time.time() + self.args.wait_unplug * 60
        while (b := self.p.battery())["plugged"]:
            if time.time() > deadline:
                raise SystemExit(f"{self.p.label}: still plugged in after {self.args.wait_unplug} min")
            log(f"{self.p.label} is on a charger at {b['level']} %: unplug it to start (waiting)")
            time.sleep(60)
        log(f"{self.p.label} unplugged at {self.p.battery()['level']} %")

    def screen_off(self) -> bool:
        if self.p.battery()["wakefulness"] == "Awake":
            self.p.sh("input keyevent KEYCODE_SLEEP")
            return True
        return False

    def block(self, n: int, cycle: int, arm: str, settle: float) -> dict | None:
        p = self.p
        rec: dict = {"n": n, "cycle": cycle, "arm": arm, "posture": ARMS[arm]}
        b = p.battery()
        if (b["plugged"] and not self.args.allow_plugged) or (b["level"] or 0) < self.args.min_level:
            log(f"stopping before block {n}: plugged={b['plugged']} level={b['level']} (min {self.args.min_level})")
            return None
        rec["screenForcedOff"] = self.screen_off()
        rec["applied"] = self.apply(arm)
        rec["meshAtApply"] = p.mesh()
        rec["trackersKilled"] = p.disconnect()
        self.sleep(settle, f"block {n} ({arm}) settle")

        p.connect()
        s0 = p.snapshot()
        p.sh("dumpsys batterystats --reset >/dev/null")
        dev_t0 = p.sh("date +%s.%N").strip()
        rec["start"] = s0
        rec["harnessAtStart"] = self.host_harness()
        rec["trackersKilled"] += p.disconnect()
        self.sleep(self.args.block, f"block {n} ({arm}) window")

        reconnected = time.time()
        p.connect()
        s1 = p.snapshot()
        p.sh("dumpsys batterystats > /data/local/tmp/knit-bs.txt")
        rec["end"] = s1
        since = time.time() - reconnected + 5
        rec["trackersInWindow"] = [a for a in p.tracker_ages() if a > since]
        rec["harnessAtEnd"] = self.host_harness()
        rec["meshAtEnd"] = p.mesh()
        text = p.sh("cat /data/local/tmp/knit-bs.txt; rm -f /data/local/tmp/knit-bs.txt", timeout=180)
        with gzip.open(self.out / f"batterystats-{n:02d}-{arm}.txt.gz", "wt", encoding="utf-8") as f:
            f.write(text)
        rec["stats"] = parse_batterystats(text, p.uid)
        tags = " ".join(f"{t}:V" for t in LOG_TAGS)
        logs = p.sh(f"logcat -d -v epoch -T {dev_t0.split('.')[0]}.000 -s {tags}", timeout=120)
        (self.out / f"logcat-{n:02d}-{arm}.txt").write_text(logs, encoding="utf-8")
        rec["logLines"] = logs.count("\n")

        hours = (s1["host"] - s0["host"]) / 3600
        rec["hours"] = hours
        if s0["chargeUah"] is not None and s1["chargeUah"] is not None and hours > 0:
            rec["usedMah"] = (s0["chargeUah"] - s1["chargeUah"]) / 1000
            rec["meanMa"] = rec["usedMah"] / hours
        st = rec["stats"]
        rec["flags"] = [f for f, bad in [
            ("plugged", s1["plugged"] or st["pluggedEvents"] > 0),
            ("screen", screen_touched(st)),
            ("adb", (st["shellPacketsRx"] or 0) > SHELL_PACKETS_CLEAN),
            ("harness", bool(rec["harnessAtStart"] or rec["harnessAtEnd"])),
            ("tracker", bool(rec["trackersInWindow"])),
            ("paused", bool((rec["meshAtEnd"] or {}).get("meshPaused"))),
        ] if bad]
        return rec

    def run(self) -> None:
        self.out.mkdir(parents=True, exist_ok=True)
        info = self.preflight()
        info["args"] = vars(self.args)
        with open(self.out / "meta.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps(info) + "\n")
        log(f"{self.p.label}: {info['model']} API {info['sdk']} {info['build'].get('versionName')} "
            f"(installed {info['build'].get('lastUpdateTime')}), {info['level']} %, uid {info['uid']}")
        if info["trackers"]:
            log(f"{self.p.label}: Android Studio pollers running; each boundary kills them: {info['trackers']}")
        if m := re.search(r"main: ring buffer is (\d+) ?([KM])i?B", self.p.sh("logcat -g")):
            self.logcat_size = m.group(1) + m.group(2)
            self.p.sh("logcat -G 16M")
            log(f"{self.p.label}: logcat ring 16M for the run (was {self.logcat_size})")
        if not self.args.allow_plugged:
            self.wait_unplugged()
        if self.args.doze == "off":
            self.p.sh("dumpsys deviceidle disable >/dev/null")
            self.dozed_off = True
        n = 0
        for cycle in range(1, self.args.cycles + 1):
            for arm in self.arms:
                n += 1
                settle = self.args.first_settle if n == 1 else self.args.settle
                rec = self.block(n, cycle, arm, settle)
                if rec is None:
                    return
                with open(self.out / "blocks.jsonl", "a", encoding="utf-8") as f:
                    f.write(json.dumps(rec) + "\n")
                st = rec["stats"]
                log(f"block {n} {arm}: {rec.get('meanMa', float('nan')):.1f} mA over {rec['hours']:.2f} h, "
                    f"awake {100 * (st['awakeFrac'] or 0):.0f} %, knit est {st['knitEstMah']} mAh, "
                    f"level {rec['end']['level']} %, flags {rec['flags'] or 'none'}")

    def restore(self) -> None:
        if self.restored:
            return
        self.restored = True
        try:
            self.p.connect()
            self.p.bridge("NANOFF", "--ez", "off", "false")
            self.p.bridge("BLECAP", "--ez", "clear", "true")
            if any(ARMS[a].get("icm") is False for a in self.arms):
                self.p.bridge("NANICM", "--ez", "on", "true", "--es", "band", "auto")
            if any(ARMS[a].get("lowPower") for a in self.arms):
                self.p.bridge("LOWPOWER", "--ez", "on", "false")
            if self.dozed_off:
                self.p.sh("dumpsys deviceidle enable >/dev/null")
            if self.logcat_size:
                self.p.sh(f"logcat -G {self.logcat_size}")
            log(f"{self.p.label}: restored (Wi-Fi Aware on, link cap cleared{', Doze back on' if self.dozed_off else ''});"
                " adb left connected")
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            log(f"{self.p.label}: RESTORE FAILED ({e}): put back NANOFF off, BLECAP clear and"
                f"{' dumpsys deviceidle enable' if self.dozed_off else ''} by hand")


def summary(dirs: list[str]) -> None:
    by_arm: dict[str, list[dict]] = defaultdict(list)
    for d in dirs:
        path = Path(d).expanduser() / "blocks.jsonl"
        meta = [json.loads(l) for l in (path.parent / "meta.jsonl").read_text(encoding="utf-8").splitlines()]
        uid = meta[-1].get("uid", "") if meta else ""
        for line in path.read_text(encoding="utf-8").splitlines():
            rec = json.loads(line)
            raw = path.parent / f"batterystats-{rec['n']:02d}-{rec['arm']}.txt.gz"
            if raw.exists():
                with gzip.open(raw, "rt", encoding="utf-8") as f:
                    rec["stats"] = parse_batterystats(f.read(), uid)
                rec["flags"] = [f for f in rec["flags"] if f not in ("screen", "adb")] \
                    + (["screen"] if screen_touched(rec["stats"]) else []) \
                    + (["adb"] if (rec["stats"]["shellPacketsRx"] or 0) > SHELL_PACKETS_CLEAN else [])
            by_arm[rec["arm"]].append(rec)
    row = "{:<12} {:>3} {:>3}  {:>8} {:>6}  {:>6}  {:>7}  {:>8}  {:>7}  {:>7}  {:>6}  {}"
    print(row.format("arm", "n", "ok", "mA", "sd", "awake", "btLock", "knit mA", "cell mA", "wifi mA", "links", "flags"))
    for arm, recs in by_arm.items():
        clean = [r for r in recs if not r["flags"] and r.get("meanMa") is not None]
        ma = [r["meanMa"] for r in clean]
        per_h = lambda key, rows=clean: [r["stats"][key] / r["hours"] / 36 for r in rows if r["stats"].get(key) is not None]
        awake = [100 * r["stats"]["awakeFrac"] for r in clean if r["stats"].get("awakeFrac") is not None]
        knit = [r["stats"]["knitEstMah"] / r["hours"] for r in clean if r["stats"].get("knitEstMah") is not None]
        cell = [r["stats"]["cellularMah"] / r["hours"] for r in clean if r["stats"].get("cellularMah") is not None]
        wifi = [r["stats"]["wifiMah"] / r["hours"] for r in clean if r["stats"].get("wifiMah") is not None]
        links = [t["linked"] for r in clean for t in (r.get("meshAtEnd") or {}).get("transports", [])
                 if t.get("kind") == "Bluetooth" and t.get("linked") is not None]
        flags = sorted({f for r in recs for f in r["flags"]})
        mean = lambda xs, fmt: (fmt.format(statistics.fmean(xs)) if xs else "-")
        print(row.format(arm, len(recs), len(clean), mean(ma, "{:.1f}"),
                         f"{statistics.stdev(ma):.1f}" if len(ma) > 1 else "-", mean(awake, "{:.0f}%"),
                         mean(per_h("halBluetoothLockS"), "{:.0f}%"), mean(knit, "{:.2f}"), mean(cell, "{:.1f}"),
                         mean(wifi, "{:.1f}"), mean(links, "{:.1f}"),
                         ",".join(flags) or "-"))
    print("\nmA: the whole phone's mean current, from the charge counter, over flag-free blocks; awake: the CPU's uptime "
          "share; btLock: hal_bluetooth_lock held, % of the window; knit mA: batterystats' estimate for Knit's uid; cell mA "
          "and wifi mA: the model's modem and Wi-Fi drain (on the P3 Wi-Fi Aware's work is billed as modem time).")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check")
    c.add_argument("--phone", required=True, help="LABEL=SERIAL (a network serial)")
    r = sub.add_parser("run")
    r.add_argument("--phone", required=True, help="LABEL=SERIAL (a network serial)")
    r.add_argument("--arms", default="shipped,nanoff,nanoff-cap2", help=f"comma-separated, from {', '.join(ARMS)}")
    r.add_argument("--cycles", type=int, default=2)
    r.add_argument("--block", type=float, default=120, help="minutes measured per block")
    r.add_argument("--settle", type=float, default=5, help="minutes between applying an arm and measuring it")
    r.add_argument("--first-settle", type=float, default=20, help="the first block's settle: the gauge after a charge")
    r.add_argument("--doze", choices=["off", "asis"], default="off")
    r.add_argument("--min-level", type=int, default=25)
    r.add_argument("--wait-unplug", type=float, default=600, help="minutes to wait for the charger to come off")
    r.add_argument("--out", default=f"~/knit-trials/{dt.date.today()}-battery")
    r.add_argument("--allow-plugged", action="store_true", help="a smoke test of the harness: every block is flagged")
    s = sub.add_parser("summary")
    s.add_argument("dirs", nargs="+")
    args = ap.parse_args()
    if args.cmd == "summary":
        summary(args.dirs)
        return
    if args.cmd == "check":
        args.arms, args.out = "shipped", "/dev/null"
        print(json.dumps(Trial(args).preflight(), indent=2))
        return
    trial = Trial(args)

    def bail(signum, _frame):
        log(f"signal {signum}: restoring")
        trial.restore()
        sys.exit(130)

    signal.signal(signal.SIGINT, bail)
    signal.signal(signal.SIGTERM, bail)
    try:
        trial.run()
    finally:
        trial.restore()


if __name__ == "__main__":
    main()
