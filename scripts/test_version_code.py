"""Unit tests for scripts/version-code.py (ADR 2026-11.7t9z). Run: python3 -m unittest scripts/test_version_code.py"""

from __future__ import annotations

import importlib.util
import re
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("version_code", Path(__file__).with_name("version-code.py"))
vc = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(vc)

GRADLE = Path(__file__).resolve().parent.parent / "app" / "build.gradle.kts"


class VersionCodeTest(unittest.TestCase):
    def test_the_adr_table(self):
        table = {
            "2.10.2": 21_002_900,
            "2.10.9": 21_009_900,
            "2.11.0-alpha.3": 21_100_003,
            "2.11.0-beta.1": 21_100_101,
            "2.11.0": 21_100_900,
            "21.0.2": 210_002_900,
        }
        for name, code in table.items():
            self.assertEqual(vc.version_code(name), code, name)

    def test_a_hotfix_sorts_below_every_pre_release_of_the_next_line(self):
        self.assertLess(vc.version_code("2.10.99"), vc.version_code("2.11.0-alpha.0"))
        self.assertLess(vc.version_code("2.99.99"), vc.version_code("3.0.0-alpha.0"))

    def test_a_stable_sorts_above_its_own_pre_releases_in_stage_order(self):
        order = ["2.11.0-alpha.0", "2.11.0-alpha.99", "2.11.0-beta.0", "2.11.0-rc.99", "2.11.0", "2.11.1-alpha.0"]
        codes = [vc.version_code(n) for n in order]
        self.assertEqual(codes, sorted(codes))
        self.assertEqual(len(set(codes)), len(codes))

    def test_look_alike_versions_do_not_collide(self):
        self.assertNotEqual(vc.version_code("2.10.2"), vc.version_code("21.0.2"))
        self.assertNotEqual(vc.version_code("2.1.10"), vc.version_code("2.11.0"))

    def test_every_code_stays_below_the_watch_range(self):
        self.assertLess(vc.version_code("99.99.99"), 1_000_000_000)

    def test_refuses_what_it_cannot_place(self):
        for name in ["2.11", "2.11.0-dev.1", "2.11.0-alpha", "2.11.0-alpha.100", "100.0.0", "2.100.0", "v2.11.0"]:
            with self.assertRaises(ValueError, msg=name):
                vc.version_code(name)

    def test_gradle_uses_the_same_weights(self):
        # The build computes the code itself (it cannot run this script); pin its formula to this one's.
        body = GRADLE.read_text()
        self.assertIn("major * 10_000_000 + minor * 100_000 + patch * 1_000 + stage * 100 + n", body)
        self.assertRegex(body, re.compile(r'"alpha" to 0,\s*"beta" to 1,\s*"rc" to 2'))
        self.assertIn("val stableStage = 9", body)


if __name__ == "__main__":
    unittest.main()
