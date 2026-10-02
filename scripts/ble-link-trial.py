#!/usr/bin/env python3
"""Time how long two Android phones take to link over BLE after a stimulus, rep after rep.

    python3 scripts/ble-link-trial.py run --phone p7=SERIAL --phone p3=SERIAL --scenario return --toggle p3 \\
        [--reps 10] [--hold 10] [--settle 60] [--timeout 600] [--nan dark|asis] [--screen off|on|asis] \\
        [--screen-skip LABEL] [--hold-paused LABEL=SERIAL] [--cell pair] [--out DIR]
    python3 scripts/ble-link-trial.py summary DIR [DIR ...]

Scenarios:
- `drop`: `…debug.BLECAP --ei max 0` on the toggled phone sheds its links (once 20 s old), held for --hold seconds,
  then cleared. Both phones stay in each other's presence, so it times the dial and accept paths alone.
- `return`: `svc bluetooth disable` on the toggled phone, --hold seconds (default 10), then enable. The phone comes
  back inside the other's 90 s presence linger.
- `newcomer`: the same as `return` with a long hold (default 240 s), so the other phone has forgotten the toggled one
  and, if it is alone, relaxed its scan.
- `restart`: `…debug.PAUSE` then `…debug.RESUME` on the toggled phone, which stops the transports and starts them again.
  Use it for a phone whose Bluetooth must stay on, such as one with a watch paired.

--toggle takes a label, or `dialer` / `responder` (the larger node id dials, ADR 2026-09.shzv).

The oracle is each phone's own `BluetoothMeshTransport` log, streamed with `logcat -v epoch` for the whole run, so
nothing touches the untoggled phone between the stimulus and the link (a bridge call wakes the app, and on a screen-off
phone that would shorten the very wait being measured). Device clocks are put on the host clock with a min-RTT
`date` probe before each rep. `t0` is the toggled phone's own clock at the stimulus: the `date` stamped in the same
shell as `svc bluetooth enable`, the transport's `bt link cap=default` line for `drop`, and the host's call time for
`restart`.

Every rep is one JSON line in DIR/reps.jsonl; `summary` reads any number of run directories and groups by pair,
scenario, the toggled phone's role, screen, NAN and --cell. Driving a physical phone needs the maintainer's go-ahead
for the session (.agents/rules/devices.md); never toggle Wi-Fi on a network-adb phone, which is why NAN is taken
dark with the bridge's NANFAIL/NANSTORM instead (the `NANFAIL` bullet in .agents/context/debug-bridge.md).
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import signal
import statistics
import subprocess
import sys
import threading
import time
from bisect import bisect_left
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BRIDGE = ROOT / "scripts" / "bridge.sh"
TAG = "BluetoothMeshTransport"
LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]+?)\s*: (.*)$")

# The transport's own lines, as BluetoothMeshTransport.kt writes them. The iOS interop harness keys off some of the
# same text (`bt lonely dial`, `bt initiating to`), so these are a contract, not a convenience.
EVENTS = {
    "up": re.compile(r"^bt link up: (\S+) \((\d+) live\)"),
    "down": re.compile(r"^bt link down: (\S+) \((.*)\)$"),
    "eof": re.compile(r"^bt link down (\S+) \(eof\) streak=(\d+) retryMs=(-?\d+)"),
    "initiating": re.compile(r"^bt initiating to (\S+) \(psm (\d+) rssi=(-?\d+)"),
    "lonely": re.compile(r"^bt lonely dial (\S+) \(alone=(\d+)ms rssi=(-?\d+) dwell=(\d+)ms\)"),
    "connect_ok": re.compile(r"^bt connect ok (\S+) durMs=(\d+)"),
    "connect_failed": re.compile(r"^bt connect (\S+) failed reason=(\S+) durMs=(\d+).*streak=(\d+) retryMs=(-?\d+)"),
    "accepted": re.compile(r"^bt accepted client (\S+) \((.*?), sighted=(\w+)"),
    "refused": re.compile(r"^bt refused client (\S+) \((.*)\)"),
    "adapter": re.compile(r"^bluetooth adapter (on|off)$"),
    "cap": re.compile(r"^bt link cap=(\S+)"),
    "scan": re.compile(r"^bt scan → (floor|boost) \((.*)\)"),
    "lonely_scan": re.compile(r"^bt scan lonely: (.*)"),
    "state": re.compile(r"^bt state links=\[(.*?)\] reach=\[(.*?)\]"),
}
# Events about one peer carry its id as the first group; these are about the radio or the scan as a whole.
UNKEYED = {"adapter", "cap", "scan", "lonely_scan", "state"}


def log(msg: str) -> None:
    print(f"[{dt.datetime.now():%H:%M:%S}] {msg}", file=sys.stderr, flush=True)


class Phone:
    def __init__(self, label: str, serial: str) -> None:
        self.label, self.serial = label, serial
        self.node_id = ""
        self.name = ""
        self.offset = 0.0  # device clock minus host clock, seconds

    def sh(self, command: str, timeout: float = 30) -> str:
        """Runs `command` on the phone; one retry, since a network adb link can stall while the phone sleeps."""
        for attempt in range(2):
            try:
                r = subprocess.run(["adb", "-s", self.serial, "shell", command], capture_output=True, text=True,
                                   timeout=timeout, check=False)
                return r.stdout.replace("\r", "")
            except subprocess.TimeoutExpired:
                if attempt == 1:
                    raise
                if ":" in self.serial:
                    subprocess.run(["adb", "connect", self.serial], capture_output=True, timeout=15, check=False)
        return ""

    def bridge(self, action: str, *extras: str) -> dict:
        r = subprocess.run([str(BRIDGE), self.serial, action, *extras], capture_output=True, text=True, timeout=60,
                           check=False)
        if r.returncode != 0:
            raise RuntimeError(f"{self.label} {action}: {r.stderr.strip()}")
        return json.loads(r.stdout)

    def measure_offset(self, samples: int = 5) -> float:
        """The device clock's offset from the host's, from the probe with the shortest round trip."""
        best = None
        for _ in range(samples):
            t0 = time.time()
            out = self.sh("date +%s.%N", timeout=15).strip()
            t1 = time.time()
            try:
                dev = float(out)
            except ValueError:
                continue
            rtt = t1 - t0
            if best is None or rtt < best[0]:
                best = (rtt, dev - (t0 + t1) / 2)
        if best is None:
            raise RuntimeError(f"{self.label}: no clock reading")
        self.offset = best[1]
        return best[0]

    def to_host(self, device_ts: float) -> float:
        return device_ts - self.offset

    def power(self) -> dict:
        out = self.sh("dumpsys power | grep -m1 mWakefulness=; dumpsys deviceidle | grep -m1 mState=; "
                      "dumpsys battery | grep -E -m3 'AC powered|USB powered|level'; settings get global bluetooth_on")
        get = lambda pat: (m.group(1) if (m := re.search(pat, out)) else None)  # noqa: E731
        return {
            "wakefulness": get(r"mWakefulness=(\w+)"),
            "deepIdle": get(r"mState=(\w+)"),
            "lightIdle": get(r"mLightState=(\w+)"),
            "plugged": bool(re.search(r"(AC|USB) powered: true", out)),
            "battery": int(b) if (b := get(r"level: (\d+)")) else None,
            "bluetoothOn": out.strip().splitlines()[-1].strip() == "1" if out.strip() else None,
        }


class Tail(threading.Thread):
    """Streams one phone's transport log for the whole run, keeping parsed lines and writing the raw ones to disk.
    A dropped stream is resumed from the last timestamp seen, so a network adb blip leaves no hole."""

    def __init__(self, phone: Phone, path: Path) -> None:
        super().__init__(daemon=True)
        self.phone, self.path = phone, path
        self.lines: list[tuple[float, str, str]] = []  # (device ts, level, message), in log order
        self.stamps: list[float] = []  # the lines' device timestamps, for bisecting
        self.cond = threading.Condition()
        self.stopping = False
        self.proc: subprocess.Popen | None = None
        # Ten minutes back, so the first rep can tell from the last up/down or `bt state` line whether the pair is
        # linked; a short buffer (256 KiB on the Pixel 3) just holds less.
        self.since = f"{time.time() + phone.offset - 600:.3f}"

    def run(self) -> None:
        seen_at_edge: set[str] = set()
        edge = 0.0
        with open(self.path, "a", encoding="utf-8") as raw:
            while not self.stopping:
                cmd = ["adb", "-s", self.phone.serial, "logcat", "-v", "epoch", "-T", self.since, "-s", f"{TAG}:V"]
                self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                                             errors="replace")
                assert self.proc.stdout is not None
                for line in self.proc.stdout:
                    line = line.rstrip("\r\n")
                    m = LINE.match(line)
                    if not m:
                        continue
                    ts = float(m.group(1))
                    if ts < edge or (ts == edge and line in seen_at_edge):
                        continue  # the overlap a resumed stream replays
                    if ts > edge:
                        edge, seen_at_edge = ts, set()
                    seen_at_edge.add(line)
                    raw.write(line + "\n")
                    raw.flush()
                    with self.cond:
                        self.lines.append((ts, m.group(2), m.group(4)))
                        self.stamps.append(ts)
                        self.cond.notify_all()
                self.proc.wait()
                if not self.stopping:
                    self.since = f"{edge:.3f}" if edge else self.since
                    time.sleep(2)

    def stop(self) -> None:
        self.stopping = True
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()

    def events(self, start_host: float, end_host: float) -> list[tuple[float, str, tuple]]:
        """Parsed transport events between two host times, as (host ts, kind, groups)."""
        out = []
        with self.cond:
            snapshot = self.lines[bisect_left(self.stamps, start_host + self.phone.offset):]
        for ts, _, msg in snapshot:
            h = self.phone.to_host(ts)
            if h > end_host:
                break
            for kind, rx in EVENTS.items():
                if m := rx.match(msg):
                    out.append((h, kind, m.groups()))
                    break
        return out

    def wait_for(self, kind: str, peer: str | None, after_host: float, deadline_host: float):
        """The first `kind` event (about `peer`, when given) after `after_host`, or None at the deadline."""
        rx = EVENTS[kind]
        with self.cond:
            checked = bisect_left(self.stamps, after_host + self.phone.offset)
        while True:
            with self.cond:
                while checked < len(self.lines):
                    ts, _, msg = self.lines[checked]
                    checked += 1
                    h = self.phone.to_host(ts)
                    if h < after_host:
                        continue
                    if (m := rx.match(msg)) and (peer is None or m.group(1) == peer):
                        return h, m.groups()
                left = deadline_host - time.time()
                if left <= 0:
                    return None
                self.cond.wait(timeout=min(left, 1.0))

    def last_links(self) -> list[str] | None:
        """The link set the transport's last 60 s `bt state` line reported, if one has been seen."""
        with self.cond:
            for _, _, msg in reversed(self.lines):
                if m := EVENTS["state"].match(msg):
                    return [s.strip() for s in m.group(1).split(",") if s.strip()]
        return None


class Trial:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.phones = {}
        for spec in args.phone:
            label, serial = spec.split("=", 1)
            self.phones[label] = Phone(label, serial)
        if len(self.phones) != 2:
            sys.exit("exactly two --phone LABEL=SERIAL")
        self.paused = [Phone(*s.split("=", 1)) for s in args.hold_paused]
        self.paused_at = 0.0
        self.out = Path(args.out).expanduser()
        self.out.mkdir(parents=True, exist_ok=True)
        self.tails: dict[str, Tail] = {}
        self.nan_dark = False
        self.toggled: Phone | None = None
        self.other: Phone | None = None
        self.bt_off = False
        self.capped = False
        self.mesh_paused = False

    # --- setup and teardown -------------------------------------------------------------------------------------

    def setup(self) -> None:
        for p in self.phones.values():
            state = p.bridge("STATE")
            p.node_id, p.name = state["self"]["nodeId"], state["self"].get("name", "")
            if not p.sh("settings get global bluetooth_on").strip() == "1":
                sys.exit(f"{p.label}: Bluetooth is off; switch it on first")
            rtt = p.measure_offset()
            log(f"{p.label} {p.node_id} '{p.name}' offset={p.offset:+.3f}s rtt={rtt * 1000:.0f}ms")
        a, b = self.phones.values()
        dialer = a if a.node_id > b.node_id else b
        t = self.args.toggle
        if t in ("dialer", "responder"):
            self.toggled = dialer if t == "dialer" else (b if dialer is a else a)
        elif t in self.phones:
            self.toggled = self.phones[t]
        else:
            sys.exit(f"--toggle {t}: not a label, dialer or responder")
        self.other = b if self.toggled is a else a
        self.role = "dialer" if self.toggled is dialer else "responder"
        log(f"toggling {self.toggled.label} (the {self.role}); {dialer.label} dials")
        for p in self.phones.values():
            tail = Tail(p, self.out / f"logcat-{p.label}.txt")
            tail.start()
            self.tails[p.label] = tail
        self.hold_others_paused(force=True)
        if self.args.nan == "dark":
            for p in self.phones.values():
                p.bridge("NANFAIL", "--ei", "count", "1000")
                p.bridge("NANSTORM", "--ei", "count", "2", "--ez", "cycle", "true")
            self.nan_dark = True
            log("NAN dark on both (NANFAIL 1000 + a cycled storm)")
        meta = {
            "started": dt.datetime.now().isoformat(timespec="seconds"),
            "argv": sys.argv,
            "phones": {p.label: {"serial": p.serial, "nodeId": p.node_id, "name": p.name} for p in self.phones.values()},
            "toggled": self.toggled.label,
            "role": self.role,
            "builds": {p.label: self.build_of(p) for p in self.phones.values()},
        }
        with open(self.out / "meta.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps(meta) + "\n")

    def build_of(self, p: Phone) -> dict:
        out = p.sh("dumpsys package app.getknit.knit | grep -m2 -E 'versionName|lastUpdateTime'")
        build = dict(re.findall(r"(versionName|lastUpdateTime)=(.+)", out))
        try:
            phy = p.bridge("PHY")
            build["phy"] = {k: phy.get(k) for k in ("mode", "supported", "advert")} if phy.get("status") != "error" \
                else phy.get("message")
        except (RuntimeError, json.JSONDecodeError) as e:
            build["phy"] = str(e)
        build["blecap"] = p.bridge("BLECAP").get("cap")
        return build

    def hold_others_paused(self, force: bool = False) -> None:
        """Keeps the --hold-paused phones off the radios: PAUSE takes 15 or 60 minutes, so it is renewed."""
        if not self.paused or (not force and time.time() - self.paused_at < 40 * 60):
            return
        for p in self.paused:
            p.bridge("PAUSE", "--ei", "minutes", "60")
        self.paused_at = time.time()
        log(f"paused {', '.join(p.label for p in self.paused)} for 60 min")

    def restore(self) -> None:
        """Puts back everything the run changed. Safe to call twice."""
        t = self.toggled
        try:
            if t and self.bt_off:
                t.sh("svc bluetooth enable")
                self.bt_off = False
            if t and self.capped:
                t.bridge("BLECAP", "--ez", "clear", "true")
                self.capped = False
            if t and self.mesh_paused:
                t.bridge("RESUME")
                self.mesh_paused = False
            if self.nan_dark:
                for p in self.phones.values():
                    p.bridge("NANFAIL", "--ei", "count", "0")
                    p.bridge("NANSTORM", "--ei", "count", "2", "--ez", "cycle", "true")
                self.nan_dark = False
                log("NAN restored on both")
            for p in self.paused:
                p.bridge("RESUME")
            self.paused = []
        finally:
            for tail in self.tails.values():
                tail.stop()

    # --- one rep ------------------------------------------------------------------------------------------------

    def linked(self) -> bool | None:
        """Whether the pair holds a link now, from the last up/down event either side logged (or `bt state`)."""
        t, o = self.toggled, self.other
        last = None
        for tail, peer in ((self.tails[t.label], o.node_id), (self.tails[o.label], t.node_id)):
            for h, kind, g in tail.events(0, time.time() + 5):
                if kind in ("up", "down", "eof") and g[0] == peer and (last is None or h > last[0]):
                    last = (h, kind)
        if last:
            return last[1] == "up"
        links = self.tails[t.label].last_links()
        return None if links is None else o.node_id in links

    def ensure_linked(self) -> bool:
        if self.linked():
            return True
        t, o = self.toggled, self.other
        log("pair not linked; waiting for it")
        deadline = time.time() + self.args.relink_timeout
        while time.time() < deadline:
            hit = self.tails[t.label].wait_for("up", o.node_id, time.time() - 1, min(deadline, time.time() + 10))
            if hit or self.linked():
                return True
        log("still not linked: HEAL on both")
        for p in self.phones.values():
            p.bridge("HEAL")
        hit = self.tails[t.label].wait_for("up", o.node_id, time.time() - 1, time.time() + 120)
        if hit or self.linked():
            return True
        # A settled dialer that has lost the other phone from its presence floors its scan and may not sight it again
        # for tens of minutes; HEAL does not lift that. A Bluetooth cycle on the toggled phone drops it to hunting.
        log(f"still not linked: cycling Bluetooth on {t.label}")
        self.recoveries.append({"at": time.time(), "how": "bt-cycle", "on": t.label})
        t.sh("svc bluetooth disable; sleep 10; svc bluetooth enable")
        hit = self.tails[t.label].wait_for("up", o.node_id, time.time() - 1, time.time() + 120)
        return bool(hit or self.linked())

    def set_screen(self) -> None:
        mode = self.args.screen
        if mode == "asis":
            return
        key = "KEYCODE_SLEEP" if mode == "off" else "KEYCODE_WAKEUP"
        for p in self.phones.values():
            if p.label not in self.args.screen_skip:
                p.sh(f"input keyevent {key}")

    def clique(self, p: Phone) -> dict:
        s = p.bridge("STATE")
        bt = next((x for x in s.get("transports", []) if x.get("kind") == "Bluetooth"), {})
        return {"linked": bt.get("linked"), "nearby": bt.get("nearby"), "health": s.get("health"),
                "meshPaused": s.get("meshPaused")}

    def rep(self, n: int) -> dict:
        a = self.args
        t, o = self.toggled, self.other
        tt, ot = self.tails[t.label], self.tails[o.label]
        rec: dict = {"rep": n, "scenario": a.scenario, "cell": a.cell, "nan": a.nan, "screen": a.screen,
                     "toggled": t.label, "other": o.label, "role": self.role, "hold_s": a.hold,
                     "pair": "-".join(sorted(self.phones))}
        self.hold_others_paused()
        self.recoveries = []
        linked = self.ensure_linked()
        if self.recoveries:
            rec["recovered_before"] = self.recoveries
        if not linked:
            rec["outcome"] = "skipped: pair would not link before the rep"
            return rec
        self.set_screen()
        rec["before"] = {p.label: {**self.clique(p), **p.power()} for p in (t, o)}
        rec["rtt_ms"] = {p.label: round(p.measure_offset() * 1000) for p in (t, o)}
        rec["offset_s"] = {p.label: round(p.offset, 3) for p in (t, o)}
        log(f"rep {n}: settling {a.settle}s")
        time.sleep(a.settle)

        # Down. Nothing below touches the untoggled phone until the link is back or the rep times out.
        t_down = time.time()
        if a.scenario in ("return", "newcomer"):
            t.sh("svc bluetooth disable")
            self.bt_off = True
        elif a.scenario == "drop":
            t.bridge("BLECAP", "--ei", "max", "0")
            self.capped = True
            shed = tt.wait_for("down", o.node_id, t_down - 1, time.time() + 60)
            if not shed:
                rec["outcome"] = "skipped: the cap never shed the link"
                return rec
            rec["shed_s"] = round(shed[0] - t_down, 2)
        elif a.scenario == "restart":
            t.bridge("PAUSE", "--ei", "minutes", "15")
            self.mesh_paused = True
        log(f"rep {n}: {a.scenario} down on {t.label}, holding {a.hold}s")
        time.sleep(a.hold)

        # Up.
        if a.scenario in ("return", "newcomer"):
            out = t.sh("date +%s.%N; svc bluetooth enable").strip().splitlines()
            self.bt_off = False
            t0 = t.to_host(float(out[0])) if out else time.time()
        elif a.scenario == "drop":
            called = time.time()
            t.bridge("BLECAP", "--ez", "clear", "true")
            self.capped = False
            cap = tt.wait_for("cap", None, called - 1, time.time() + 10)
            t0 = cap[0] if cap else called
        else:
            t0 = time.time()
            t.bridge("RESUME")
            self.mesh_paused = False
        rec["t_down"], rec["t0"] = round(t_down, 3), round(t0, 3)

        deadline = t0 + a.timeout
        hits = {}
        first = None
        while time.time() < deadline and first is None:
            for p, tail, peer in ((t, tt, o.node_id), (o, ot, t.node_id)):
                hit = tail.wait_for("up", peer, t0 - 1, min(deadline, time.time() + 1))
                if hit:
                    first = hits[p.label] = hit[0]
                    break
        if first is not None:
            for p, tail, peer in ((t, tt, o.node_id), (o, ot, t.node_id)):
                if p.label not in hits:
                    hit = tail.wait_for("up", peer, t0 - 1, time.time() + 5)
                    if hit:
                        hits[p.label] = hit[0]
            rec["outcome"] = "linked"
            rec["linked_s"] = round(first - t0, 2)
            rec["link_up_s"] = {k: round(v - t0, 2) for k, v in hits.items()}
            log(f"rep {n}: linked in {rec['linked_s']}s")
        else:
            rec["outcome"] = "timeout"
            log(f"rep {n}: no link within {a.timeout}s")
        rec.update(self.decompose(t0, t_down, (first or time.time()) + 3))
        if first is None:
            # Wait out the stragglers so the next rep starts linked, and keep when it finally came.
            late = None
            for _ in range(int(a.relink_timeout / 10)):
                late = tt.wait_for("up", o.node_id, t0 - 1, time.time() + 10) or \
                    ot.wait_for("up", t.node_id, t0 - 1, time.time() + 0.1)
                if late:
                    break
            rec["late_linked_s"] = round(late[0] - t0, 2) if late else None
        return rec

    def decompose(self, t0: float, t_down: float, end: float) -> dict:
        """What each phone's transport did about the other between the stimulus and the link."""
        t, o = self.toggled, self.other
        rel = lambda h: round(h - t0, 2)  # noqa: E731
        d: dict = {"events": []}
        attempts, refusals, accepts = [], [], []
        first_init = None
        third: list[dict] = []
        for p, peer in ((t, o.node_id), (o, t.node_id)):
            for h, kind, g in self.tails[p.label].events(t_down - 1, end):
                keyed = kind not in UNKEYED
                if keyed and g[0] != peer:
                    # A dial to someone else holds the connect slot (one at a time), so in a clique it is the wait.
                    if h >= t0 and kind in ("initiating", "connect_ok", "connect_failed"):
                        third.append({"by": p.label, "at_s": rel(h), "kind": kind, "peer": g[0][:7],
                                      **({"durMs": int(g[1] if kind == "connect_ok" else g[2])}
                                         if kind != "initiating" else {})})
                    continue
                if kind == "state":
                    continue
                d["events"].append([rel(h), p.label, kind, *(g[1:] if keyed else g)])
                if h < t0:
                    if kind in ("down", "eof") and p is o and "other_drop_s" not in d:
                        d["other_drop_s"] = round(h - t_down, 2)
                    continue
                if kind == "adapter" and g[0] == "on" and p is t:
                    d.setdefault("adapter_on_s", rel(h))
                elif kind == "initiating":
                    if first_init is None:
                        first_init = {"by": p.label, "at_s": rel(h), "rssi": int(g[2])}
                elif kind == "lonely" and first_init is None:
                    first_init = {"by": p.label, "at_s": rel(h), "rssi": int(g[2]), "lonely": True}
                elif kind == "connect_ok":
                    attempts.append({"by": p.label, "at_s": rel(h), "ok": True, "durMs": int(g[1])})
                elif kind == "connect_failed":
                    attempts.append({"by": p.label, "at_s": rel(h), "ok": False, "reason": g[1], "durMs": int(g[2]),
                                     "retryMs": int(g[4])})
                elif kind == "refused":
                    refusals.append({"by": p.label, "at_s": rel(h), "why": g[1]})
                elif kind == "accepted":
                    accepts.append({"by": p.label, "at_s": rel(h), "verdict": g[1], "sighted": g[2] == "true"})
        d["third_party"] = third
        d["first_initiating"] = first_init
        d["attempts"], d["refusals"], d["accepts"] = attempts, refusals, accepts
        d["failed_dials"] = sum(1 for x in attempts if not x["ok"])
        return d

    def run(self) -> None:
        self.setup()
        path = self.out / "reps.jsonl"
        for n in range(1, self.args.reps + 1):
            rec = self.rep(n)
            with open(path, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec) + "\n")
        log(f"done: {path}")


# --- summary ------------------------------------------------------------------------------------------------------

def pct(xs: list[float], q: float) -> float:
    xs = sorted(xs)
    k = (len(xs) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def summary(dirs: list[str]) -> None:
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for d in dirs:
        for line in open(Path(d).expanduser() / "reps.jsonl", encoding="utf-8"):
            r = json.loads(line)
            key = (r["pair"], r["scenario"], f"{r['toggled']}({r['role']})", r["screen"], r["nan"], r["cell"])
            groups[key].append(r)
    hdr = f"{'pair':8} {'scenario':9} {'toggled':16} {'screen':6} {'nan':5} {'cell':7} {'n':>3} {'ok':>3} " \
          f"{'med':>6} {'p90':>6} {'max':>6} {'≤30s':>5} {'≤60s':>5} {'≤120s':>5} {'1st dial':>8} {'fails':>5}  reasons"
    print(hdr)
    for key, rs in sorted(groups.items()):
        ran = [r for r in rs if not r.get("outcome", "").startswith("skipped")]
        ok = [r["linked_s"] for r in ran if r.get("outcome") == "linked"]
        inits = [r["first_initiating"]["at_s"] for r in ran if r.get("first_initiating")]
        reasons = Counter(a["reason"] for r in ran for a in r.get("attempts", []) if not a["ok"])
        n = len(ran)
        within = lambda s: f"{sum(1 for x in ok if x <= s) / n:.0%}" if n else "-"  # noqa: E731
        stat = lambda f: f"{f:6.1f}" if ok else f"{'-':>6}"  # noqa: E731
        print(f"{key[0]:8} {key[1]:9} {key[2]:16} {key[3]:6} {key[4]:5} {key[5]:7} {n:3} {len(ok):3} "
              f"{stat(statistics.median(ok) if ok else 0)} {stat(pct(ok, 0.9) if ok else 0)} {stat(max(ok) if ok else 0)} "
              f"{within(30):>5} {within(60):>5} {within(120):>5} "
              f"{(f'{statistics.median(inits):8.1f}' if inits else f'{chr(45):>8}')} "
              f"{sum(r.get('failed_dials', 0) for r in ran):5}  {dict(reasons) or ''}"
              + (f"  skipped={len(rs) - n}" if len(rs) > n else ""))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--phone", action="append", required=True, help="LABEL=SERIAL, twice")
    r.add_argument("--scenario", choices=["drop", "return", "newcomer", "restart"], required=True)
    r.add_argument("--toggle", required=True, help="a label, dialer or responder")
    r.add_argument("--reps", type=int, default=10)
    r.add_argument("--hold", type=float, help="seconds down (drop 10, return 10, newcomer 240, restart 30)")
    r.add_argument("--settle", type=float, default=60, help="seconds linked and untouched before each stimulus")
    r.add_argument("--timeout", type=float, default=600)
    r.add_argument("--relink-timeout", type=float, default=600)
    r.add_argument("--nan", choices=["dark", "asis"], default="dark")
    r.add_argument("--screen", choices=["off", "on", "asis"], default="asis")
    r.add_argument("--screen-skip", action="append", default=[], help="a label whose screen is left alone")
    r.add_argument("--hold-paused", action="append", default=[], help="LABEL=SERIAL kept paused for the run")
    r.add_argument("--cell", default="pair", help="free label for the setting, such as pair or clique")
    r.add_argument("--out", default=f"~/knit-trials/{dt.date.today()}-ble-link")
    s = sub.add_parser("summary")
    s.add_argument("dirs", nargs="+")
    args = ap.parse_args()
    if args.cmd == "summary":
        summary(args.dirs)
        return
    if args.hold is None:
        args.hold = {"drop": 10, "return": 10, "newcomer": 240, "restart": 30}[args.scenario]
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
