"""Unit tests for scripts/hci-bridge.py's isolation (--isolate). Run: python3 -m unittest scripts/test_hci_bridge.py

Pure byte work, so no Bumble and no dongle: a Bridge with recording sinks, fed the HCI packets a host and a
controller would send.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("hci_bridge", Path(__file__).with_name("hci-bridge.py"))
bridge_mod = importlib.util.module_from_spec(_SPEC)
sys.modules["hci_bridge"] = bridge_mod  # dataclasses look their module up by name
assert _SPEC.loader is not None
_SPEC.loader.exec_module(bridge_mod)

CMD, EVT, LE_META = 0x01, 0x04, 0x3E
PARTNER = bytes.fromhex("c1c2c3c4c5c6")
PHONE = bytes.fromhex("d1d2d3d4d5d6")


class Sink:
    def __init__(self):
        self.packets: list[bytes] = []

    def on_packet(self, packet: bytes) -> None:
        self.packets.append(packet)


def presence_ad(uuid: int, payload: bytes = b"\x01\x02\x03") -> bytes:
    """Flags, then Service Data (16-bit UUID) the way BleAdvertiser builds Knit's presence advert."""
    service = uuid.to_bytes(2, "little") + payload
    return bytes([2, 0x01, 0x06, 1 + len(service), 0x16]) + service


def ext_report(address: bytes, data: bytes, event_type: int = 0x0013) -> bytes:
    """One LE Extended Advertising Report: the 24 bytes up to the data length, then the data."""
    return (event_type.to_bytes(2, "little") + bytes([1]) + address + bytes([1, 0, 0xFF, 0x7F, 0xC4])
            + bytes(2) + bytes([0]) + bytes(6) + bytes([len(data)]) + data)


def ext_event(*reports: bytes) -> bytes:
    params = bytes([0x0D, len(reports)]) + b"".join(reports)
    return bytes([EVT, LE_META, len(params)]) + params


def legacy_event(address: bytes, data: bytes) -> bytes:
    report = bytes([0x00, 1]) + address + bytes([len(data)]) + data + bytes([0xC4])
    params = bytes([0x02, 1]) + report
    return bytes([EVT, LE_META, len(params)]) + params


def ext_adv_data(data: bytes) -> bytes:
    """LE Set Extended Advertising Data, handle 1, complete."""
    params = bytes([1, 0x03, 0x01, len(data)]) + data
    return bytes([CMD, 0x37, 0x20, len(params)]) + params


class RemapUuidsTest(unittest.TestCase):
    def test_service_data_and_uuid_lists_are_moved(self):
        data = presence_ad(0xFE30) + bytes([5, 0x03, 0x0F, 0x18, 0x38, 0xFE])
        out, hit = bridge_mod.remap_uuids(data, bridge_mod.ISOLATED_UUIDS)
        self.assertTrue(hit)
        self.assertEqual(out, presence_ad(0xFE20) + bytes([5, 0x03, 0x0F, 0x18, 0x28, 0xFE]))

    def test_an_unrelated_uuid_and_payload_bytes_are_left_alone(self):
        # The payload happens to hold Knit's UUID bytes; only the structure's UUID field is a UUID.
        data = presence_ad(0x180F, payload=b"\x30\xfe\x38\xfe")
        out, hit = bridge_mod.remap_uuids(data, bridge_mod.ISOLATED_UUIDS)
        self.assertFalse(hit)
        self.assertEqual(out, data)

    def test_a_structure_cut_short_and_zero_padding_are_survived(self):
        padded = presence_ad(0xFE30) + bytes(10)
        out, hit = bridge_mod.remap_uuids(padded, bridge_mod.ISOLATED_UUIDS)
        self.assertTrue(hit)
        self.assertEqual(out, presence_ad(0xFE20) + bytes(10))
        cut = presence_ad(0xFE30, payload=bytes(200))[:40]
        out, hit = bridge_mod.remap_uuids(cut, bridge_mod.ISOLATED_UUIDS)
        self.assertTrue(hit)
        self.assertEqual(len(out), 40)


class IsolateTest(unittest.TestCase):
    def setUp(self):
        self.host, self.controller = Sink(), Sink()
        self.bridge = bridge_mod.Bridge(self.host, self.controller, None, trace=False, shim=False, isolate=True)
        bridge_mod.say = lambda line: None

    def test_the_hosts_advert_goes_out_under_the_isolated_uuid(self):
        self.bridge.from_host(ext_adv_data(presence_ad(0xFE30)))
        self.assertEqual(self.controller.packets, [ext_adv_data(presence_ad(0xFE20))])

    def test_a_partners_report_reaches_the_host_under_knits_uuid(self):
        self.bridge.from_controller(ext_event(ext_report(PARTNER, presence_ad(0xFE20))))
        self.assertEqual(self.host.packets, [ext_event(ext_report(PARTNER, presence_ad(0xFE30)))])

    def test_a_lab_phones_knit_advert_never_reaches_the_host(self):
        self.bridge.from_controller(ext_event(ext_report(PHONE, presence_ad(0xFE30))))
        self.bridge.from_controller(legacy_event(PHONE, presence_ad(0xFE30)))
        self.assertEqual(self.host.packets, [])
        self.assertEqual(self.bridge.counts["adv_isolated"], 2)

    def test_a_partners_uuid_less_fragment_passes_once_its_head_was_heard(self):
        tail = ext_report(PARTNER, b"\x00" * 7, event_type=0x0000)
        self.bridge.from_controller(ext_event(tail))
        self.assertEqual(self.host.packets, [])  # nothing heard from it yet
        self.bridge.from_controller(ext_event(ext_report(PARTNER, presence_ad(0xFE20), event_type=0x0020)))
        self.bridge.from_controller(ext_event(tail))
        self.assertEqual(len(self.host.packets), 2)
        self.assertEqual(self.host.packets[1], ext_event(tail))

    def test_a_mixed_event_keeps_only_the_partners_report(self):
        self.bridge.from_controller(ext_event(ext_report(PHONE, presence_ad(0xFE30)),
                                              ext_report(PARTNER, presence_ad(0xFE20))))
        self.assertEqual(self.host.packets, [ext_event(ext_report(PARTNER, presence_ad(0xFE30)))])

    def test_a_malformed_report_is_dropped(self):
        broken = ext_event(ext_report(PARTNER, presence_ad(0xFE20)))[:-4]
        self.bridge.from_controller(broken)
        self.assertEqual(self.host.packets, [])

    def test_without_isolate_everything_passes_unchanged(self):
        plain = bridge_mod.Bridge(self.host, self.controller, None, trace=False, shim=False)
        plain.from_host(ext_adv_data(presence_ad(0xFE30)))
        plain.from_controller(ext_event(ext_report(PHONE, presence_ad(0xFE30))))
        self.assertEqual(self.controller.packets, [ext_adv_data(presence_ad(0xFE30))])
        self.assertEqual(self.host.packets, [ext_event(ext_report(PHONE, presence_ad(0xFE30)))])


def command(op: int, params: bytes) -> bytes:
    return bytes([CMD, op & 0xFF, op >> 8, len(params)]) + params


def ext_create(filter_policy: int) -> bytes:
    """LE Extended Create Connection on 1M, through the accept list (1) or to an address (0)."""
    peer = bytes(7) if filter_policy else bytes([1]) + PARTNER
    block = bytes.fromhex("6000300018002800" "0000f40100000000")
    return command(0x2043, bytes([filter_policy, 1]) + peer + bytes([0x01]) + block)


def adv_enable(on: int, *handles: int) -> bytes:
    return command(0x2039, bytes([on, len(handles)]) + b"".join(bytes([h, 0, 0, 0]) for h in handles))


def command_status(op: int, status: int) -> bytes:
    return bytes([EVT, 0x0F, 4, status, 1, op & 0xFF, op >> 8])


def command_complete(op: int, status: int) -> bytes:
    return bytes([EVT, 0x0E, 4, 1, op & 0xFF, op >> 8, status])


def connection_complete(status: int, role: int = 0) -> bytes:
    """LE Enhanced Connection Complete, handle 1, a 50 ms interval and a 5 s timeout."""
    p = (bytes([0x0A, status, 1, 0, role, 1]) + PARTNER + bytes(12) + (40).to_bytes(2, "little") + bytes(2)
         + (500).to_bytes(2, "little") + bytes([0]))
    return bytes([EVT, LE_META, len(p)]) + p


class AdvertArbiterTest(unittest.TestCase):
    """Zephyr empties an accept-list dial's filter when an advert set turns on, so the bridge holds those enables
    until the dial ends."""

    def setUp(self):
        self.host, self.controller = Sink(), Sink()
        self.bridge = bridge_mod.Bridge(self.host, self.controller, None, trace=False)
        bridge_mod.say = lambda line: None
        # Names come from Bumble's tables, which these tests run without.
        bridge_mod.op_name = lambda op: f"0x{op:04x}"
        bridge_mod.status_name = lambda status: f"0x{status:02x}"

    def dial(self, filter_policy: int = 1) -> None:
        self.bridge.from_host(ext_create(filter_policy))
        self.bridge.from_controller(command_status(0x2043, 0))

    def test_an_enable_during_an_accept_list_dial_waits_for_the_link(self):
        self.dial()
        self.bridge.from_host(adv_enable(1, 0))
        self.assertNotIn(adv_enable(1, 0), self.controller.packets)
        self.assertEqual(self.host.packets[-1], command_complete(0x2039, 0))  # the host is answered at once
        self.bridge.from_controller(connection_complete(0))
        self.assertEqual(self.controller.packets[-1], adv_enable(1, 0))
        answered = len(self.host.packets)
        self.bridge.from_controller(command_complete(0x2039, 0))
        self.assertEqual(len(self.host.packets), answered)  # the bridge's own enable is answered to the bridge

    def test_a_cancelled_dial_lets_the_enable_go(self):
        self.dial()
        self.bridge.from_host(adv_enable(1, 0))
        self.bridge.from_host(command(0x200E, b""))
        self.bridge.from_controller(command_complete(0x200E, 0))
        self.bridge.from_controller(connection_complete(0x02))
        self.assertEqual(self.controller.packets[-1], adv_enable(1, 0))

    def test_several_enables_go_out_as_one_naming_each_set_once(self):
        self.dial()
        for handles in ((0,), (0,), (1,)):
            self.bridge.from_host(adv_enable(1, *handles))
        self.bridge.from_controller(connection_complete(0))
        self.assertEqual(self.controller.packets[-1], adv_enable(1, 0, 1))
        self.assertEqual(self.bridge.counts["adverts_held"], 3)

    def test_a_disable_during_the_dial_goes_through_and_drops_the_held_set(self):
        self.dial()
        self.bridge.from_host(adv_enable(1, 0))
        self.bridge.from_host(adv_enable(0, 0))
        self.assertEqual(self.controller.packets[-1], adv_enable(0, 0))
        self.bridge.from_controller(command_complete(0x2039, 0))
        sent = len(self.controller.packets)
        self.bridge.from_controller(connection_complete(0))
        self.assertEqual(len(self.controller.packets), sent)

    def test_a_dial_to_an_address_leaves_enables_alone(self):
        self.dial(filter_policy=0)
        self.bridge.from_host(adv_enable(1, 0))
        self.assertEqual(self.controller.packets[-1], adv_enable(1, 0))

    def test_after_a_refused_dial_enables_pass(self):
        self.bridge.from_host(ext_create(1))
        self.bridge.from_controller(command_status(0x2043, 0x0B))
        self.bridge.from_host(adv_enable(1, 0))
        self.assertEqual(self.controller.packets[-1], adv_enable(1, 0))

    def test_without_the_shim_enables_pass(self):
        plain = bridge_mod.Bridge(self.host, self.controller, None, trace=False, shim=False)
        plain.from_host(ext_create(1))
        plain.from_controller(command_status(0x2043, 0))
        plain.from_host(adv_enable(1, 0))
        self.assertEqual(self.controller.packets[-1], adv_enable(1, 0))


if __name__ == "__main__":
    unittest.main()
