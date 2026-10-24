"""Unit tests for scripts/fieldlog.py's `check` and `pair`. Run: python3 -m unittest scripts/test_fieldlog.py

Each case writes two (or three) phones' logs from one timeline in absolute milliseconds, each rebased to its own first
line and headed with that line's hour, as `FieldLogDocument` writes them, so the offset `pair` has to find is known.
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("fieldlog", Path(__file__).with_name("fieldlog.py"))
fieldlog = importlib.util.module_from_spec(_SPEC)
sys.modules["fieldlog"] = fieldlog  # dataclasses look their module up by name
assert _SPEC.loader is not None
_SPEC.loader.exec_module(fieldlog)

HOUR = 3_600_000
MIN = 60_000
S = 1_000
BASE = 1_790_000_000_000 // HOUR * HOUR  # an hour's start, in ms since the epoch


class Phone:
    """One phone's field log, built from absolute times and written as the export writes it."""

    def __init__(self, name: str, first: int, clock_error: int = 0) -> None:
        self.name = name
        self.first = first
        # A clock set wrong: every stamp this phone writes, its header's hour included, is off by this much.
        self.error = clock_error
        self.lines: list[tuple[int, dict]] = [(first, {"e": "mesh_on", "vc": 1})]

    def at(self, when: int, kind: str, **fields) -> None:
        self.lines.append((when, {"e": kind, **fields}))

    def recording(self, start: int, end: int) -> None:
        """The 5-minute Bluetooth sample a running mesh writes, over [start, end]."""
        for when in range(start, end + 1, 5 * MIN):
            self.at(when, "bt_scan", links=1, le=1, sighted=1, hunting=False, coded="OFF")

    def write(self, folder: Path) -> Path:
        lines = sorted(self.lines, key=lambda line: line[0])
        start = lines[0][0] + self.error
        hour = datetime.fromtimestamp(start // HOUR * HOUR / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        body = [
            "# knit field log, schema 1",
            f"# device: {self.name}",
            f"# from: {hour} (rounded down to the hour)",
            f"# span: {(lines[-1][0] - lines[0][0]) // MIN} min; t = ms since the first line",
        ]
        import json

        for when, event in lines:
            body.append(json.dumps({"t": when + self.error - start, **event}, separators=(",", ":"), ensure_ascii=False))
        path = folder / f"{self.name}.jsonl"
        path.write_text("\n".join(body) + "\n", encoding="utf-8")
        return path


def call(
    caller: Phone,
    callee: Phone,
    caller_names_callee: str,
    callee_names_caller: str,
    at: int,
    transit: int = 300,
    setup: int = 2_000,
    talk: int | None = 40_000,
    reason: str = "USER",
    route: tuple[str, str] | None = ("SRFLX", "RELAY"),
) -> None:
    """One live call [caller] places at [at]: both sides' start and end lines, and each side's selected pair."""
    connected = at + setup if talk is not None else None
    end = (connected + talk) if talk is not None and connected is not None else at + 45 * S
    for phone, peer, out, began, ended, by_peer in (
        (caller, caller_names_callee, True, at, end, False),
        (callee, callee_names_caller, False, at + transit, end + transit, True),
    ):
        phone.at(began, "call_start", peer=peer, out=out, walkie=False)
        line = {
            "peer": peer,
            "out": out,
            "walkie": False,
            "reason": reason,
            "last": "Connected" if connected is not None else ("Ringing" if out else "Alerting"),
            "life": ended - began,
            "byPeer": by_peer,
            "missed": False,
            "degraded": False,
            "restarts": 0,
            "drops": 0,
            "relay": False,
            "direct": False,
            "directUp": False,
            "unreached": False,
            "unproven": False,
        }
        if connected is not None:
            # Each side's ICE connects within a round trip of the other's.
            mine = connected + (0 if out else 40)
            line["setup"] = mine - began
            line["talk"] = ended - mine
            if route is not None:
                local, remote = route if out else (route[1], route[0])
                phone.at(mine + 100, "call_route", local=local, remote=remote, proto="UDP", net="WIFI", direct=False, seq=1)
        phone.at(ended, "call_end", **line)


class CheckTest(unittest.TestCase):
    def test_a_relay_must_be_its_pseudonym_and_never_a_peer_s_or_its_host(self):
        ok = {"t": 1, "e": "spool_up", "relay": "r0a1b2c3d", "hello": 640, "fails": 0, "pow": 0, "attach": True,
              "ent": False, "turn": False, "cast": False}
        self.assertEqual([], fieldlog.validate(ok))
        self.assertTrue(fieldlog.validate({**ok, "relay": "p0a1b2c3d"}))
        self.assertTrue(fieldlog.validate({**ok, "relay": "relay.example"}))

    def test_the_lora_board_link_lines_are_known_kinds(self):
        for line in (
            {"t": 1, "e": "lora_up", "dial": 4200, "tries": 1, "mtu": 512, "region": "EU_868", "relink": 95000},
            {"t": 2, "e": "lora_down", "reason": "GATT_DROP", "status": 8, "life": 600000},
            {"t": 3, "e": "lora_fail", "reason": "DIAL_TIMEOUT", "streak": 2, "wait": 20000},
            {"t": 4, "e": "lora_role", "role": "PASSIVE", "pocket": 2, "gateways": 1},
            {"t": 5, "e": "net", "route": "CELLULAR", "restricted": False},
        ):
            self.assertEqual([], fieldlog.validate(line), line)

    def test_the_battery_line_is_a_known_kind_and_takes_no_other_field(self):
        line = {"t": 1, "e": "battery", "usage": "Restricted", "exempt": True, "bucket": "RESTRICTED"}
        self.assertEqual([], fieldlog.validate(line))
        self.assertEqual(["battery: unexpected field 'oem'"], fieldlog.validate({**line, "oem": "MIUI"}))


    def test_the_wifi_aware_icm_lines_are_known_kinds(self):
        for line in (
            {"t": 1, "e": "nan_icm", "on": True, "band": "B24", "why": "STA_CHANNEL", "sta": 2437},
            {"t": 2, "e": "nan_icm", "on": False, "why": "AUDIO"},
            {"t": 3, "e": "nan_icm_lit", "lit": 95000, "up": 300000, "configs": 7},
        ):
            self.assertEqual([], fieldlog.validate(line), line)
        self.assertEqual(["nan_icm: unexpected field 'ssid'"],
                         fieldlog.validate({"t": 1, "e": "nan_icm", "on": False, "why": "LONELY", "ssid": 3}))


    def test_the_wifi_aware_dormancy_and_switch_lines_are_known_kinds(self):
        for line in (
            {"t": 1, "e": "nan_dormant", "on": True, "why": "IDLE"},
            {"t": 2, "e": "nan_dormant", "on": False, "why": "SIGHTING"},
            {"t": 3, "e": "aware_switch", "on": False},
        ):
            self.assertEqual([], fieldlog.validate(line), line)
        self.assertEqual(["aware_switch: unexpected field 'why'"],
                         fieldlog.validate({"t": 1, "e": "aware_switch", "on": True, "why": "SWITCH"}))


class PairTest(unittest.TestCase):
    def setUp(self) -> None:
        self.folder = Path(tempfile.mkdtemp())

    def logs(self, *phones: Phone) -> list:
        return [fieldlog.read(phone.write(self.folder)) for phone in phones]

    @staticmethod
    def offset(a: Phone, b: Phone) -> int:
        """
        The true offset: B's t + d = A's t. Each export rebases its own first line, so a phone's clock error cancels out
        of its `t` and moves only its header's hour.
        """
        return b.first - a.first

    def test_one_shared_call_lines_the_two_logs_up_and_names_each_tester(self):
        alice = Phone("alice", BASE + 12 * MIN + 3_100)
        bob = Phone("bob", BASE + 35 * MIN + 19_400)
        call(alice, bob, "p0000000b", "p0000000a", at=BASE + 40 * MIN)
        a, b = self.logs(alice, bob)

        p = fieldlog.pair_logs(a, b)

        self.assertTrue(p.accepted)
        self.assertFalse(p.weak, "the reasons agree and the routes mirror")
        self.assertEqual(("p0000000b", "p0000000a"), (p.peer_a, p.peer_b))
        self.assertEqual(1, len(p.calls))
        self.assertLess(abs(p.d - self.offset(alice, bob)), 100, p.d)

    def test_repeated_calls_of_one_length_pair_one_to_one_not_shifted_by_one(self):
        alice = Phone("alice", BASE + 5 * MIN)
        bob = Phone("bob", BASE + 50 * MIN)
        for i in range(3):
            call(alice, bob, "p0000000b", "p0000000a", at=BASE + 55 * MIN + i * 60 * S, talk=30_000)
        a, b = self.logs(alice, bob)

        p = fieldlog.pair_logs(a, b)

        self.assertEqual(3, len(p.calls))
        self.assertFalse(p.ambiguous)
        self.assertLess(abs(p.d - self.offset(alice, bob)), 100, p.d)
        for x, y in p.calls:
            self.assertLess(abs(x.start - (y.start + p.d)), 1_000, "each call met its own other side")

    def test_a_call_the_other_phone_never_logged_says_whether_it_was_recording(self):
        alice = Phone("alice", BASE + 1 * MIN)
        bob = Phone("bob", BASE + 2 * MIN)
        bob.recording(BASE + 2 * MIN, BASE + 30 * MIN)
        call(alice, bob, "p0000000b", "p0000000a", at=BASE + 5 * MIN)
        # Two calls that never reached Bob: one while his log was recording, one after it stopped.
        for when in (BASE + 15 * MIN, BASE + 50 * MIN):
            alice.at(when, "call_start", peer="p0000000b", out=True, walkie=False)
            alice.at(
                when + 15 * S,
                "call_end",
                peer="p0000000b",
                out=True,
                walkie=False,
                reason="NO_ROUTE",
                last="Calling",
                life=15 * S,
                byPeer=False,
                missed=False,
                degraded=False,
                restarts=0,
                drops=0,
                relay=False,
                direct=False,
                directUp=False,
                unreached=True,
                unproven=False,
            )
        a, b = self.logs(alice, bob)

        text = "\n".join(fieldlog.report(fieldlog.pair_logs(a, b)))

        self.assertEqual(1, text.count("logged no call: it never arrived"), text)
        self.assertEqual(1, text.count("was not recording then"), text)

    def test_a_call_with_no_end_line_on_one_side_still_pairs_and_says_so(self):
        alice = Phone("alice", BASE + 1 * MIN)
        bob = Phone("bob", BASE + 3 * MIN)
        call(alice, bob, "p0000000b", "p0000000a", at=BASE + 10 * MIN, reason="NO_ROUTE")
        # Bob's app dies in the call: his end line never comes, and his next mesh session starts after it.
        bob.lines = [line for line in bob.lines if line[1]["e"] != "call_end"]
        bob.at(BASE + 12 * MIN, "mesh_on", vc=1)
        a, b = self.logs(alice, bob)

        p = fieldlog.pair_logs(a, b)

        self.assertEqual(1, len(p.calls))
        self.assertIsNone(p.calls[0][1].end)
        self.assertIn("NO END LINE", "\n".join(fieldlog.report(p)))

    def test_with_no_call_in_common_the_links_between_the_two_phones_line_them_up(self):
        alice = Phone("alice", BASE + 4 * MIN)
        bob = Phone("bob", BASE + 9 * MIN)
        for i, kind in enumerate(("bt_up", "bt_down", "bt_up", "nan_up", "nan_down")):
            when = BASE + 20 * MIN + i * 3 * MIN
            alice.at(when, kind, peer="p0000000b")
            bob.at(when + 400, kind, peer="p0000000a")
        a, b = self.logs(alice, bob)

        p = fieldlog.pair_logs(a, b)

        self.assertTrue(p.accepted)
        self.assertEqual((0, 5), p.evidence[:2])
        self.assertLess(abs(p.d - self.offset(alice, bob)), EDGE_SLACK, p.d)

    def test_a_phone_whose_clock_is_hours_off_is_still_paired_and_flagged(self):
        alice = Phone("alice", BASE + 10 * MIN)
        bob = Phone("bob", BASE + 20 * MIN, clock_error=3 * HOUR)
        call(alice, bob, "p0000000b", "p0000000a", at=BASE + 25 * MIN)
        a, b = self.logs(alice, bob)

        p = fieldlog.pair_logs(a, b)

        self.assertTrue(p.accepted)
        self.assertFalse(p.windowed)
        self.assertLess(abs(p.d - self.offset(alice, bob)), 100, p.d)

    def test_calls_with_other_people_at_other_times_pair_nothing(self):
        alice = Phone("alice", BASE + 1 * MIN)
        bob = Phone("bob", BASE + 2 * MIN)
        carol = Phone("carol", BASE + 3 * MIN)
        dave = Phone("dave", BASE + 4 * MIN)
        call(alice, carol, "p000000c0", "p000000a0", at=BASE + 10 * MIN, talk=20_000)
        call(dave, bob, "p000000b0", "p000000d0", at=BASE + 30 * MIN, talk=90_000)
        a, b = self.logs(alice, bob)

        found, alone = fieldlog.pair_all([a, b])

        self.assertEqual([], found)
        self.assertEqual(2, len(alone))

    def test_two_calls_at_one_moment_go_to_the_testers_who_were_in_each(self):
        # A scripted session: Alice calls Bob while Carol calls Dave, at the same instant and for the same length.
        # Only the true pairs mirror each other's route, and each pseudonym goes to one pairing.
        alice = Phone("alice", BASE + 1 * MIN)
        bob = Phone("bob", BASE + 2 * MIN)
        carol = Phone("carol", BASE + 3 * MIN)
        dave = Phone("dave", BASE + 4 * MIN)
        call(alice, bob, "p0000000b", "p0000000a", at=BASE + 10 * MIN, talk=30_000, route=("SRFLX", "RELAY"))
        call(carol, dave, "p0000000d", "p0000000c", at=BASE + 10 * MIN, talk=30_000, route=("HOST", "HOST"))
        # Alice and Dave are tried first: on timing alone, Alice's call fits Dave's as well as Bob's.
        logs = self.logs(alice, dave, bob, carol)

        found, alone = fieldlog.pair_all(logs)

        pairs = sorted(sorted((p.a.device, p.b.device)) for p in found)
        self.assertEqual([["alice", "bob"], ["carol", "dave"]], pairs)
        self.assertEqual([], alone)

    def test_one_call_is_the_other_side_of_one_call_only(self):
        alice = Phone("alice", BASE + 1 * MIN)
        bob = Phone("bob", BASE + 2 * MIN)
        nobody = Phone("nobody", BASE)
        # Alice dials Bob twice within seconds, both unanswered; only the second reached him.
        call(alice, nobody, "p0000000b", "p00000000", at=BASE + 10 * MIN, talk=None, reason="TIMEOUT", route=None)
        call(alice, bob, "p0000000b", "p0000000a", at=BASE + 10 * MIN + 5 * S, talk=None, reason="TIMEOUT", route=None)
        a, b = self.logs(alice, bob)

        p = fieldlog.pair_logs(a, b)

        self.assertEqual(1, len(p.calls))
        self.assertIn("A only", "\n".join(fieldlog.report(p)))


EDGE_SLACK = 1_000


if __name__ == "__main__":
    unittest.main()
