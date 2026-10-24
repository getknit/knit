#!/usr/bin/env python3
"""Validate and summarise Knit field logs (ADR 2026-10.a239, docs/FIELD_LOG.md).

    python3 scripts/fieldlog.py check LOG [LOG …]
    python3 scripts/fieldlog.py summary LOG [LOG …] [--json]
    python3 scripts/fieldlog.py pair LOG LOG [LOG …]

LOG is the `.jsonl` a user shared from Diagnostics → Field log. `check` reads every line against the schema
and exits non-zero on anything a phone should never have written (an unknown kind, a field outside its kind's
set, a string where a number belongs, a node-id-shaped token). `summary` merges any number of logs, from any
number of phones, and prints the tables the open radio questions ask for:

- Bluetooth relink time (`bt_up.relink`) and link lifetime (`bt_down.life`) by device and drop reason;
- dial time and failed dials by reason;
- link RSSI by PHY from the 5-minute samples, and the RSSI each PHY step happened at;
- Wi-Fi Aware data-path lifetimes and session cycles;
- message latency per plane, as each phone summarised it;
- the LoRa board link: dial and relink time, tries before each up, link life and failed attempts by reason, role
  flips, and the board's own channel use;
- the Internet route, and each relay's hello time, session life and failed dials by reason, and its `err` codes;
- voice calls (a build with calls): how each ended and from which phase, ring and setup times, the media's round
  trip and loss by route, the microphone's health, Wi-Fi Direct legs, walkie bursts, and calls with no end line.

`pair` puts two or more phones' logs on one clock with no notes from the testers. It finds the offset between every
two logs from the calls they share (a caller's and a callee's sides begin, connect and end together) and the radio
links between the two phones, works out which pseudonym in each log is the other tester, and prints one merged timeline
per shared call. For each of a log's calls the other side never logged, it says whether that phone was recording then.

Peers are per-log pseudonyms; a peer is keyed by (log, pseudonym), and only `pair` matches one log's pseudonym to
another log, from what both phones logged at the same moments. Standard library only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

SCHEMA = 1

# kind -> the fields it may carry (beyond "t" and "e"); None means any counter name (the `counters` kind).
KINDS: dict[str, set[str] | None] = {
    "mesh_on": {"vc"},
    "mesh_off": set(),
    "power": {"screen", "charging", "low"},
    "battery": {"usage", "exempt", "bucket"},
    "radio": {"radio", "health", "linked", "nearby", "contended", "held"},
    "counters": None,
    "bt_up": {"peer", "dialed", "dial", "sighted", "links", "relink"},
    "bt_down": {"peer", "reason", "life", "links", "phy", "rssi"},
    "bt_fail": {"peer", "reason", "dur", "streak"},
    "bt_phy": {"peer", "coded", "rssi"},
    "bt_scan": {"links", "le", "sighted", "hunting", "coded"},
    "bt_orphan": {"peer", "linked", "armed", "tries", "cleared", "age"},
    "bt_link": {"peer", "age", "tx", "rx", "phy", "rssi", "interval", "mtu", "earned"},
    "bt_peer": {"peer", "linked", "rssi", "rssi1m", "rssiCoded", "codedLag"},
    "nan_up": {"peer", "initiator", "links"},
    "nan_down": {"peer", "initiator", "life", "clean"},
    "nan_cycle": set(),
    "nan_icm": {"on", "band", "why", "sta"},
    "nan_icm_lit": {"lit", "up", "configs"},
    "low_power": {"on", "why"},
    "lora": {
        "role", "snr10", "rssi", "queued", "heard", "boards", "airUsed", "airBudget", "gateways", "pocket", "sighted",
        "covered", "free", "battery", "powered", "chUtil10", "airTx10",
    },
    "lora_up": {"dial", "tries", "mtu", "region", "preset", "slot", "hops", "xeddsa", "relink"},
    "lora_down": {"reason", "status", "life"},
    "lora_fail": {"reason", "status", "streak", "wait"},
    "lora_role": {"role", "pocket", "gateways"},
    "net": {"route", "restricted"},
    "spool_up": {"relay", "hello", "fails", "pow", "attach", "ent", "turn", "cast"},
    "spool_down": {"relay", "reason", "code", "life", "ours"},
    "spool_fail": {"relay", "reason", "code", "dur", "streak", "retryAfter"},
    "spool_err": {"relay", "code"},
    "spool": {
        "relay", "up", "scopes", "converged", "local", "remote", "invalid", "fails", "pow", "ticket", "uploads", "reason",
    },
    "latency": {"dir", "plane", "n", "p50", "p90", "max"},
    "call_start": {"peer", "out", "walkie"},
    "call_end": {
        "peer", "out", "walkie", "reason", "last", "life", "byPeer", "missed", "degraded", "ring", "answer", "setup",
        "talk", "restarts", "drops", "relay", "direct", "directUp", "turn", "stun", "unreached", "unproven",
    },
    "call_route": {"local", "remote", "proto", "net", "direct", "seq"},
    "call_media": {
        "lost", "rtt", "jitter", "buffer", "lossIn10", "lossOut10", "lossCall10", "send", "recv", "pkts", "mic",
        "heard", "local", "remote", "proto", "relayProto", "net", "direct", "pairs", "pending", "micPath", "micHealth",
    },
    "call_direct": {"host", "end", "group", "lane", "life", "tries"},
    "talk": {"dir", "chunks", "ms", "sent", "path", "held", "lost", "wait", "spread"},
    "walkie_end": {
        "burstsOut", "burstsIn", "chunksOut", "failed", "chunksIn", "lost", "transit", "jitter", "hand", "frameOut",
        "frameIn",
    },
}

# Summary sections shown as a count of lines, and as the values themselves, rather than as percentiles.
COUNTED = {"nan cycles", "call routes", "call mic", "call deaf readings", "lora roles", "spool errs", "net routes"}
LISTED = {"call drops / restarts"}

PSEUDONYM = re.compile(r"^p[0-9a-f]{8}$")
RELAY = re.compile(r"^r[0-9a-f]{8}$")
ENUM = re.compile(r"^[A-Za-z0-9_]+$")
NODE_ID = re.compile(r"(?<![a-z2-7])[a-z2-7]{26}(?![a-z2-7])")


@dataclass
class Log:
    path: Path
    header: dict[str, str] = field(default_factory=dict)
    events: list[dict] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)

    @property
    def device(self) -> str:
        return self.header.get("device", "?")


def read(path: Path) -> Log:
    log = Log(path)
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line:
            continue
        if line.startswith("# "):
            key, sep, value = line[2:].partition(": ")
            if sep:
                log.header[key] = value
            elif line.startswith("# knit field log, schema "):
                log.header["schema"] = line.rsplit(" ", 1)[-1]
            continue
        where = f"{path.name}:{number}"
        if NODE_ID.search(line):
            log.problems.append(f"{where}: a node-id-shaped token")
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            log.problems.append(f"{where}: not JSON")
            continue
        log.problems.extend(f"{where}: {p}" for p in validate(event))
        log.events.append(event)
    if log.header.get("schema") not in (None, str(SCHEMA)):
        log.problems.append(f"{path.name}: schema {log.header['schema']}, this reader knows {SCHEMA}")
    return log


def validate(event: dict) -> list[str]:
    problems = []
    kind = event.get("e")
    if not isinstance(event.get("t"), int):
        problems.append("no integer t")
    if kind not in KINDS:
        return problems + [f"unknown kind {kind!r}"]
    allowed = KINDS[kind]
    for name, value in event.items():
        if name in ("t", "e"):
            continue
        if allowed is not None and name not in allowed:
            problems.append(f"{kind}: unexpected field {name!r}")
        if isinstance(value, str):
            ok = {"peer": PSEUDONYM, "relay": RELAY}.get(name, ENUM).match(value)
            if not ok:
                problems.append(f"{kind}.{name}: {value!r} is neither a pseudonym nor a constant")
        elif not isinstance(value, (bool, int)):
            problems.append(f"{kind}.{name}: {type(value).__name__}")
    return problems


def percentiles(values: list[float]) -> str:
    if not values:
        return "—"
    s = sorted(values)

    def at(p: int) -> float:
        return s[(len(s) - 1) * p // 100]

    return f"n={len(s)} p50={at(50):g} p90={at(90):g} max={s[-1]:g}"


def route(e: dict) -> str:
    """A call's selected pair as a call_route or call_media line names it: `RELAY/SRFLX CELLULAR`, `… direct`."""
    pair = f"{e.get('local', '?')}/{e.get('remote', '?')}"
    return f"{pair} {e.get('net', '?')}" + (" direct" if e.get("direct") else "")


def summarise(logs: list[Log]) -> dict:
    out: dict = defaultdict(lambda: defaultdict(list))
    for log in logs:
        dev = log.device
        # Calls that began and have no end line yet: a process death in the call, or one still up at export.
        open_calls = 0
        # The last nan_icm_lit line per device, for the lit duty between two samples.
        icm_lit: dict = {}
        for e in log.events:
            kind = e["e"]
            if kind == "bt_up":
                if "relink" in e:
                    out["bt relink s"][dev].append(e["relink"] / 1000)
                if "dial" in e:
                    out["bt dial s"][dev].append(e["dial"] / 1000)
            elif kind == "bt_down":
                out["bt life s"][f"{dev} {e['reason']}"].append(e["life"] / 1000)
            elif kind == "bt_fail":
                out["bt fails"][f"{dev} {e['reason']}"].append(e["dur"] / 1000)
            elif kind == "bt_link" and "rssi" in e:
                out["bt link rssi"][f"{dev} {e.get('phy', '?')}"].append(e["rssi"])
            elif kind == "bt_orphan":
                outcome = "cleared" if e["cleared"] else "kept"
                out["bt orphan s"][f"{dev} {outcome} linked={e['linked']} armed={e['armed']} tries={e['tries']}"].append(e["age"] / 1000)
            elif kind == "bt_phy" and "rssi" in e:
                out["bt step rssi"][f"{dev} {'to CODED' if e['coded'] else 'up'}"].append(e["rssi"])
            elif kind == "nan_down":
                out["nan life s"][f"{dev} {'clean' if e['clean'] else 'reset'}"].append(e["life"] / 1000)
            elif kind == "nan_cycle":
                out["nan cycles"][dev].append(1)
            elif kind == "nan_icm":
                asked = e.get("band", "?") if e["on"] else "off"
                out["nan icm verdicts"][f"{dev} {asked} {e['why']}"].append(1)
            elif kind == "nan_icm_lit":
                # Cumulative per plane start: the duty is the difference between two lines of one run.
                before = icm_lit.get(dev)
                icm_lit[dev] = e
                if before and e["up"] > before["up"] and e["lit"] >= before["lit"]:
                    out["nan icm lit % (our estimate)"][dev].append(
                        round(100 * (e["lit"] - before["lit"]) / (e["up"] - before["up"]), 1)
                    )
            elif kind == "lora_up":
                if "dial" in e:
                    out["lora dial s"][dev].append(e["dial"] / 1000)
                if "relink" in e:
                    out["lora relink s"][dev].append(e["relink"] / 1000)
                out["lora tries before up"][dev].append(e["tries"])
            elif kind == "lora_down":
                out["lora life s"][f"{dev} {e['reason']}"].append(e["life"] / 1000)
            elif kind == "lora_fail":
                out["lora fail wait s"][f"{dev} {e['reason']}"].append(e.get("wait", 0) / 1000)
            elif kind == "lora_role":
                out["lora roles"][f"{dev} {e['role']}"].append(1)
            elif kind == "lora" and "chUtil10" in e:
                out["lora board channel use %"][dev].append(e["chUtil10"] / 10)
            elif kind == "battery":
                out["battery standing"][f"{dev} {e['usage']} exempt={e['exempt']} {e['bucket']}"].append(1)
            elif kind == "net":
                out["net routes"][f"{dev} {e['route']}"].append(1)
            elif kind == "spool_up":
                out["spool hello s"][f"{dev} {e['relay']}"].append(e["hello"] / 1000)
            elif kind == "spool_down":
                out["spool life s"][f"{dev} {e['relay']} {e['reason']}"].append(e["life"] / 1000)
            elif kind == "spool_fail":
                out["spool fails s"][f"{dev} {e['relay']} {e['reason']}"].append(e["dur"] / 1000)
            elif kind == "spool_err":
                out["spool errs"][f"{dev} {e['relay']} {e['code']}"].append(1)
            elif kind == "latency":
                out["latency s"][f"{dev} {e['dir']} {e['plane']}"].append(
                    f"n={e['n']} p50={e['p50'] / 1000:g} p90={e['p90'] / 1000:g} max={e['max'] / 1000:g}"
                )
            elif kind == "call_start":
                open_calls += 1
            elif kind == "call_end":
                if "last" in e:
                    open_calls = max(0, open_calls - 1)
                what = f"{dev} {'walkie' if e['walkie'] else 'live'} {'out' if e['out'] else 'in'}"
                out["call ends s"][f"{what} {e['reason']} from {e.get('last', 'never rang')}"].append(e["life"] / 1000)
                if "ring" in e:
                    out["call ring s"][dev].append(e["ring"] / 1000)
                if "setup" in e and "answer" in e:
                    out["call answer→connected s"][f"{what} direct={e['directUp']}"].append((e["setup"] - e["answer"]) / 1000)
                if e["drops"] or e["restarts"]:
                    out["call drops / restarts"][what].append(f"{e['drops']}/{e['restarts']}")
            elif kind == "call_route":
                out["call routes"][f"{dev} {route(e)}"].append(e["seq"])
            elif kind == "call_media":
                where = f"{dev} {route(e)}"
                if "rtt" in e:
                    out["call rtt ms"][where].append(e["rtt"])
                if "lossIn10" in e:
                    out["call loss in %"][where].append(e["lossIn10"] / 10)
                if "lossOut10" in e:
                    out["call loss out %"][where].append(e["lossOut10"] / 10)
                if "micHealth" in e:
                    out["call mic"][f"{dev} {e.get('micPath', '?')} {e['micHealth']}"].append(1)
                if e.get("pkts") == 0 and not e["lost"]:
                    out["call deaf readings"][dev].append(1)
            elif kind == "call_direct":
                out["call direct life s"][f"{dev} {'host' if e['host'] else 'join'} {e['end']}"].append(e["life"] / 1000)
                if "lane" in e:
                    out["call direct lane up s"][f"{dev} {'host' if e['host'] else 'join'}"].append(e["lane"] / 1000)
            elif kind == "talk" and e["dir"] == "OUT" and e["chunks"]:
                out["talk out sent %"][f"{dev} {e.get('path', '?')}"].append(100 * e.get("sent", 0) / e["chunks"])
            elif kind == "talk" and e["dir"] == "IN":
                heard = e["chunks"] + e.get("lost", 0)
                if heard:
                    out["talk in lost %"][dev].append(100 * e.get("lost", 0) / heard)
                if "wait" in e:
                    out["talk in wait ms"][dev].append(e["wait"])
        if open_calls:
            out["calls with no end line"][dev].append(open_calls)
    return out


# ---- pair: two phones' logs on one clock, with no notes from the testers ----------------------------------------
#
# A log's `t` counts from its own first line and its header gives only that line's hour, so two logs differ by an
# unknown offset `d` (`t` on A = `t` on B + d), within an hour of their headers' difference. The two phones log the
# same moments, though: a caller's call and the callee's begin within a signal's transit of each other, reach ICE
# connected within a round trip, and end within a transit; a radio link between them comes up and goes down on both at
# once. Each phone also names the other by one pseudonym for the whole log. `pair` tries every caller/callee pairing of
# two logs' calls (and, with none, their link edges) as the anchor for `d`, keeps the one most other events agree with,
# and needs no note from anyone.

START_TOL_MS = 20_000  # an OFFER through a spool or a flood, against the callee's alert
END_TOL_MS = 20_000  # the END, CANCEL or DECLINE in transit
CONNECTED_TOL_MS = 3_000  # both sides' ICE connected, a round trip and the two clocks' drift apart
EDGE_TOL_MS = 3_000  # a link's HELLO completes on the dialler and the acceptor a moment apart
WINDOW_SLACK_MS = 5 * 60_000  # beyond the header's hour: the two phones' clocks may disagree
COVER_MS = 6 * 60_000  # a recording phone writes a line at least every 5 min (the Bluetooth sample)


@dataclass
class Call:
    """One call as one log saw it. `end` is None for a call whose end line never came (its process died)."""

    peer: str
    out: bool
    walkie: bool
    start: int
    end: int | None
    connected: int | None
    line: dict | None  # the call_end line, None for an open call
    lines: list[dict] = field(default_factory=list)  # the call's own media, route, leg and walkie lines

    @property
    def last(self) -> int:
        return self.end if self.end is not None else self.start

    def describe(self) -> str:
        kind = "walkie" if self.walkie else "live"
        way = "out" if self.out else "in"
        if self.line is None:
            return f"{kind} {way}, NO END LINE (the app died in the call, or it was still up at export)"
        e = self.line
        how = f"{e['reason']} from {e.get('last', 'never rang')}" + (", ended by the peer" if e.get("byPeer") else "")
        times = " ".join(f"{k}={e[k] / 1000:g}s" for k in ("ring", "answer", "setup", "talk") if k in e)
        return f"{kind} {way}, {how}" + (f", {times}" if times else "")


CALL_LINES = {"call_route", "call_media", "call_direct", "talk", "walkie_end"}
EDGE_KINDS = {"bt_up": ("bt", "up"), "bt_down": ("bt", "down"), "nan_up": ("nan", "up"), "nan_down": ("nan", "down")}


def calls_of(log: Log) -> list[Call]:
    """Every call in [log], its start line matched to its end line; a start with no end is an open call."""
    calls: list[Call] = []
    pending: list[Call] = []
    current: Call | None = None
    for e in log.events:
        kind = e["e"]
        if kind == "mesh_on":
            # A new session: a call still open before it never ended in this process.
            calls.extend(pending)
            pending = []
            current = None
        elif kind == "call_start":
            current = Call(e["peer"], e["out"], e["walkie"], e["t"], None, None, None)
            pending.append(current)
        elif kind == "call_end":
            began = e["walkie"] and not e.get("degraded")
            match = None
            if "last" in e:
                for i in range(len(pending) - 1, -1, -1):
                    c = pending[i]
                    if c.peer == e["peer"] and c.out == e["out"] and c.walkie == began:
                        match = pending.pop(i)
                        break
            call = match or Call(e["peer"], e["out"], e["walkie"], e["t"] - e["life"], None, None, None)
            call.walkie = e["walkie"]
            call.start = e["t"] - e["life"]
            call.end = e["t"]
            call.connected = e["t"] - e["talk"] if "talk" in e else None
            call.line = e
            calls.append(call)
            current = pending[-1] if pending else None
        elif kind in CALL_LINES and current is not None:
            current.lines.append(e)
    calls.extend(pending)
    return sorted(calls, key=lambda c: c.start)


def edges_of(log: Log) -> list[tuple[int, tuple[str, str], str]]:
    return [(e["t"], EDGE_KINDS[e["e"]], e["peer"]) for e in log.events if e["e"] in EDGE_KINDS]


def start_of(log: Log) -> int | None:
    """The header's hour, in ms since the epoch, or None without one."""
    raw = log.header.get("from", "").split(" ")[0]
    if not raw:
        return None
    from datetime import datetime

    try:
        return int(datetime.fromisoformat(raw.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None


def window(a: Log, b: Log) -> tuple[int, int] | None:
    """The offsets the two headers allow: their hours' difference, give or take an hour and the clocks' slack."""
    sa, sb = start_of(a), start_of(b)
    if sa is None or sb is None:
        return None
    centre = sb - sa
    reach = 3_600_000 + WINDOW_SLACK_MS
    return centre - reach, centre + reach


# A reflexive address one side learned from its STUN server, the other from the connectivity checks.
REFLEXIVE = {"SRFLX": "REFLEXIVE", "PRFLX": "REFLEXIVE"}


def reflexive(ice_type: str) -> str:
    return REFLEXIVE.get(ice_type, ice_type)


def agrees(a: Call, b: Call) -> int:
    """
    0 to 2: the two sides name the same end reason, and their first selected pairs mirror each other (A's local is
    B's remote and back, a reflexive address either way round).
    """
    score = 0
    if a.line is not None and b.line is not None and a.line["reason"] == b.line["reason"]:
        score += 1
    ra = next((e for e in a.lines if e["e"] == "call_route"), None)
    rb = next((e for e in b.lines if e["e"] == "call_route"), None)
    if ra is not None and rb is not None:
        mine = (reflexive(ra["local"]), reflexive(ra["remote"]))
        theirs = (reflexive(rb["remote"]), reflexive(rb["local"]))
        if mine == theirs:
            score += 1
    return score


def same_call(a: Call, b: Call, d: int) -> bool:
    """Whether A's [a] and B's [b] are one call's two sides under offset [d]."""
    if a.out == b.out or a.walkie != b.walkie:
        return False
    if abs(a.start - (b.start + d)) > START_TOL_MS:
        return False
    if a.end is not None and b.end is not None and abs(a.end - (b.end + d)) > END_TOL_MS:
        return False
    return a.connected is None or b.connected is None or abs(a.connected - (b.connected + d)) <= CONNECTED_TOL_MS


@dataclass
class Pairing:
    a: Log
    b: Log
    d: int
    peer_a: str  # how A names B
    peer_b: str  # how B names A
    calls: list[tuple[Call, Call]]
    edges: int
    spread: int  # the matched anchors' residuals, max minus min, ms
    runner_up: tuple[int, int, int] = (0, 0, 0)
    windowed: bool = True
    # Every call each log holds, the matched ones included: the same objects, so the unmatched can be told apart.
    all_a: list[Call] = field(default_factory=list)
    all_b: list[Call] = field(default_factory=list)

    @property
    def confirmations(self) -> int:
        """Matched calls whose two sides also agree on what nothing in the matching read: the reason, the route."""
        return sum(agrees(x, y) for x, y in self.calls)

    @property
    def evidence(self) -> tuple[int, int, int]:
        return len(self.calls), self.edges, self.confirmations

    @property
    def ambiguous(self) -> bool:
        return self.runner_up == self.evidence

    @property
    def accepted(self) -> bool:
        calls, edges, _ = self.evidence
        return not self.ambiguous and (calls >= 1 or edges >= 3)

    @property
    def weak(self) -> bool:
        """One call and nothing else: two unrelated calls of one length placed at one moment would look the same."""
        calls, edges, confirmations = self.evidence
        return calls == 1 and edges == 0 and confirmations == 0


def score(a: Log, b: Log, ca: list[Call], cb: list[Call], ea, eb, d: int, pa: str, pb: str, windowed: bool) -> Pairing:
    """Every event of A's calls with [pa] and B's with [pb] that offset [d] lines up, one to one, then [d] refined."""

    def match(d: int):
        pairs, taken = [], set()
        for x in (c for c in ca if c.peer == pa):
            for i, y in enumerate(cb):
                if i not in taken and y.peer == pb and same_call(x, y, d):
                    pairs.append((x, y))
                    taken.add(i)
                    break
        edges, used, residuals = 0, set(), []
        for ta, kind, peer in ea:
            if peer != pa:
                continue
            for i, (tb, kb, qb) in enumerate(eb):
                if i not in used and qb == pb and kb == kind and abs(ta - (tb + d)) <= EDGE_TOL_MS:
                    edges += 1
                    used.add(i)
                    residuals.append(ta - (tb + d))
                    break
        for x, y in pairs:
            if x.connected is not None and y.connected is not None:
                residuals.append(x.connected - (y.connected + d))
        return pairs, edges, residuals

    pairs, edges, residuals = match(d)
    if residuals:
        # The tight anchors, connected moments and link edges, put the offset where their median says.
        d += sorted(residuals)[len(residuals) // 2]
        pairs, edges, residuals = match(d)
    spread = max(residuals) - min(residuals) if residuals else 0
    return Pairing(a, b, d, pa, pb, pairs, edges, spread, windowed=windowed, all_a=ca, all_b=cb)


def candidates(ca: list[Call], cb: list[Call], ea, eb, bounds: tuple[int, int] | None) -> list[tuple[int, str, str]]:
    """Offsets worth scoring: every caller/callee pair of calls, else the busiest bucket of link-edge differences."""

    def inside(d: int) -> bool:
        return bounds is None or bounds[0] <= d <= bounds[1]

    out = []
    for x in ca:
        for y in cb:
            if x.out == y.out or x.walkie != y.walkie:
                continue
            d = x.connected - y.connected if x.connected is not None and y.connected is not None else x.start - y.start
            if inside(d):
                out.append((d, x.peer, y.peer))
    if out:
        return out
    buckets: dict[tuple[int, str, str], int] = defaultdict(int)
    for ta, kind, pa in ea:
        for tb, kb, pb in eb:
            d = ta - tb
            if kb == kind and inside(d):
                buckets[(round(d / EDGE_TOL_MS), pa, pb)] += 1
    return [(k[0] * EDGE_TOL_MS, k[1], k[2]) for k, n in sorted(buckets.items(), key=lambda kv: -kv[1])[:50] if n >= 2]


def pair_logs(a: Log, b: Log) -> Pairing | None:
    """A and B on one clock, or None when nothing the two logged lines up."""
    ca, cb, ea, eb = calls_of(a), calls_of(b), edges_of(a), edges_of(b)
    best = None
    for bounds, windowed in ((window(a, b), True), (None, False)):
        scored = [score(a, b, ca, cb, ea, eb, d, pa, pb, windowed) for d, pa, pb in candidates(ca, cb, ea, eb, bounds)]
        if not scored:
            continue
        scored.sort(key=lambda p: (p.evidence, -p.spread), reverse=True)
        best = scored[0]
        others = [
            p for p in scored if (p.peer_a, p.peer_b) != (best.peer_a, best.peer_b) or abs(p.d - best.d) > START_TOL_MS
        ]
        best.runner_up = others[0].evidence if others else (0, 0, 0)
        # A phone whose clock is set wrong falls outside the headers' window: look again without it.
        if best.accepted or bounds is None:
            break
    return best


def covered(log: Log, t: int) -> bool:
    """Whether [log] was recording at its own time [t]: a line within COVER_MS before it and one after."""
    before = any(t - COVER_MS <= e["t"] <= t for e in log.events)
    after = any(t <= e["t"] <= t + COVER_MS for e in log.events)
    return before and after


def fields(e: dict) -> str:
    return " ".join(f"{k}={v}" for k, v in e.items() if k not in ("t", "e", "peer"))


def label(log: Log) -> str:
    return f"{log.path.name} ({log.device})"


def report(p: Pairing) -> list[str]:
    """The pairing's header, one merged timeline per shared call, then the calls with no other side."""
    a, b = p.a, p.b
    calls, edges, _ = p.evidence
    lines = [
        f"A = {label(a)}, B = {label(b)}",
        f"  B's t + {p.d / 1000:.3f} s = A's t; A names B {p.peer_a}, B names A {p.peer_b}",
        f"  lined up by {calls} call(s) ({p.confirmations} confirmation(s): the same reason, mirrored routes) and "
        f"{edges} link edge(s), anchors within {p.spread} ms"
        + ("" if p.windowed else "; outside the headers' hours, so one phone's clock is off")
        + ("; WEAK: one call and nothing else agrees, check it" if p.weak else "")
        + (f"; AMBIGUOUS, the next offset fits as well {p.runner_up}" if p.ambiguous else ""),
    ]
    for n, (x, y) in enumerate(sorted(p.calls, key=lambda xy: xy[0].start), start=1):
        caller = "A" if x.out else "B"
        lines.append(f"\ncall {n}: {caller} called, at A's t={x.start / 1000:.1f} s")
        lines.append(f"  A: {x.describe()}")
        lines.append(f"  B: {y.describe()}")
        lo = min(x.start, y.start + p.d) - 5_000
        hi = max(x.last, y.last + p.d) + 5_000
        merged = [(e["t"], "A", e) for e in a.events if lo <= e["t"] <= hi and is_about(e, p.peer_a)]
        merged += [(e["t"] + p.d, "B", e) for e in b.events if lo <= e["t"] + p.d <= hi and is_about(e, p.peer_b)]
        for t, side, e in sorted(merged, key=lambda m: m[0]):
            lines.append(f"  {(t - x.start) / 1000:+9.3f}  {side}  {e['e']:<11} {fields(e)}")
    matched_a = {id(x) for x, _ in p.calls}
    matched_b = {id(y) for _, y in p.calls}
    for side, held, other, peer, off, matched in (
        ("A", p.all_a, b, p.peer_a, -p.d, matched_a),
        ("B", p.all_b, a, p.peer_b, p.d, matched_b),
    ):
        for c in held:
            if c.peer != peer or id(c) in matched:
                continue
            seen = "was recording then and logged no call: it never arrived" if covered(other, c.start + off) else (
                "was not recording then"
            )
            lines.append(f"\n{side} only, at {side}'s t={c.start / 1000:.1f} s: {c.describe()}; the other phone {seen}")
    return lines


def is_about(e: dict, peer: str) -> bool:
    """A line the merged timeline shows: a call line, or a link line naming the other tester."""
    if e["e"] in CALL_LINES or e["e"] in ("call_start", "call_end"):
        return e.get("peer", peer) == peer
    return (e["e"] in EDGE_KINDS or e["e"] in ("bt_phy", "bt_fail")) and e.get("peer") == peer


def pair_all(logs: list[Log]) -> tuple[list[Pairing], list[Log]]:
    """
    Every two logs that line up, and the logs that line up with none. A pseudonym names one person, so where two
    pairings claim the same log's same pseudonym (two testers calling at the same moment), the better-evidenced keeps it.
    """
    tried = []
    for i, a in enumerate(logs):
        for b in logs[i + 1 :]:
            p = pair_logs(a, b)
            if p is not None and (p.accepted or (len(logs) == 2 and p.evidence != (0, 0, 0))):
                tried.append(p)
    found, claimed = [], set()
    for p in sorted(tried, key=lambda p: (p.evidence, -p.spread), reverse=True):
        names = {(id(p.a), p.peer_a), (id(p.b), p.peer_b)}
        if names & claimed:
            continue
        claimed |= names
        found.append(p)
    paired = {id(x) for p in found for x in (p.a, p.b)}
    alone = [log for log in logs if id(log) not in paired]
    return found, alone


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("check", "summary", "pair"):
        p = sub.add_parser(name)
        p.add_argument("logs", nargs="+", type=Path)
        if name == "summary":
            p.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    logs = [read(path) for path in args.logs]

    if args.cmd == "pair":
        if len(logs) < 2:
            parser.error("pair needs two logs or more")
        found, alone = pair_all(logs)
        for p in found:
            print("\n".join(report(p)))
            if not p.accepted:
                print("  (low confidence: shown because only these two logs were given)")
            print()
        for log in alone:
            print(f"{label(log)}: lines up with no other log given (no shared call or radio link)")
        return 0 if found else 1

    if args.cmd == "check":
        bad = [p for log in logs for p in log.problems]
        for problem in bad:
            print(problem)
        events = sum(len(log.events) for log in logs)
        print(f"{len(logs)} log(s), {events} event(s), {len(bad)} problem(s)", file=sys.stderr)
        return 1 if bad else 0

    table = summarise(logs)
    if args.json:
        print(json.dumps({k: dict(v) for k, v in table.items()}, indent=2, default=str))
        return 0
    for section, rows in table.items():
        print(f"\n{section}")
        for key in sorted(rows):
            values = rows[key]
            if section == "latency s":
                shown = values[0]
            elif section in COUNTED:
                shown = f"{len(values)}"
            elif section in LISTED:
                shown = " ".join(str(v) for v in values)
            elif section == "calls with no end line":
                shown = f"{sum(values)}"
            else:
                shown = percentiles(values)
            print(f"  {key:<40} {shown}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
