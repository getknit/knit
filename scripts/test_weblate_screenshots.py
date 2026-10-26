"""Unit tests for scripts/weblate-screenshots.py. Run: python3 -m unittest scripts/test_weblate_screenshots.py"""

from __future__ import annotations

import importlib.util
import json
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("weblate_screenshots", Path(__file__).with_name("weblate-screenshots.py"))
sync = importlib.util.module_from_spec(_SPEC)
sys.modules["weblate_screenshots"] = sync  # dataclasses look their module up by name
assert _SPEC.loader is not None
_SPEC.loader.exec_module(sync)

UNITS = {"about_title": 1, "action_back": 2, "chat_send": 3, "chat_retry": 4}


def preview(wrapper, keys, png=Path("x.png")):
    return sync.Preview(wrapper, png, None if keys is None else set(keys))


class LightPngTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.refs = Path(self.tmp.name)
        self.dir = self.refs / "AboutScreenshotsKt"
        self.dir.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def touch(self, name):
        (self.dir / name).write_bytes(b"png")

    def test_picks_the_light_render_among_the_variants(self):
        for variant in ("Light_88ea03ac", "Dark_96ac018f", "LongText_82637082", "Rtl_b15e33b4"):
            self.touch(f"AboutScreen_{variant}_0.png")
        self.assertEqual(sync.light_png(self.refs, "About", "AboutScreen").name, "AboutScreen_Light_88ea03ac_0.png")

    def test_a_longer_wrapper_sharing_the_prefix_is_not_matched(self):
        self.touch("AboutScreenLicenses_Light_88ea03ac_0.png")
        self.assertIsNone(sync.light_png(self.refs, "About", "AboutScreen"))

    def test_two_light_renders_stop_the_run(self):
        self.touch("AboutScreen_Light_88ea03ac_0.png")
        self.touch("AboutScreen_Light_91aceacd_0.png")
        with self.assertRaises(SystemExit):
            sync.light_png(self.refs, "About", "AboutScreen")

    def test_load_render_marks_what_the_recorder_missed(self):
        self.touch("AboutScreen_Light_88ea03ac_0.png")
        render = self.refs / "render"
        (render / "strings").mkdir(parents=True)
        manifest = [
            {"wrapper": "AboutScreen", "class": "a.AboutScreenKt", "method": "AboutScreenPreview", "screen": True, "stem": "About"},
            {"wrapper": "Gone", "class": "a.GoneKt", "method": "GonePreview", "screen": False, "stem": "About"},
        ]
        (render / "manifest.json").write_text(json.dumps(manifest))
        (render / "strings/AboutScreen.txt").write_text("about_title\naction_back\n")
        about, gone = sync.load_render(render, self.refs)
        self.assertEqual(about.keys, {"about_title", "action_back"})
        self.assertIsNotNone(about.png)
        self.assertIsNone(gone.keys)
        self.assertIsNone(gone.png)


class PlanTest(unittest.TestCase):
    def test_a_new_preview_is_created_with_its_translatable_keys_only(self):
        plan = sync.make_plan([preview("About", ["about_title", "m3c_snackbar_pane_title"])], UNITS, [], set(), prune=False)
        self.assertEqual([(p.wrapper, ids) for p, ids in plan.create], [("About", [1])])
        self.assertEqual(plan.requests(), 2)

    def test_an_existing_screenshot_is_relinked_not_recreated(self):
        shot = sync.Shot(7, "Chat", {3, 2})
        plan = sync.make_plan([preview("Chat", ["chat_send", "chat_retry"])], UNITS, [shot], set(), prune=False)
        self.assertEqual(plan.create, [])
        self.assertEqual([(s.id, u) for s, u in plan.link], [(7, 4)])
        self.assertEqual([(s.id, u) for s, u in plan.unlink], [(7, 2)])

    def test_a_changed_image_is_replaced(self):
        shot = sync.Shot(7, "Chat", {3})
        plan = sync.make_plan([preview("Chat", ["chat_send"])], UNITS, [shot], {"Chat"}, prune=False)
        self.assertEqual([s.id for s, _ in plan.replace], [7])
        self.assertEqual(plan.link + plan.unlink, [])

    def test_a_gone_preview_is_deleted_only_with_prune(self):
        shot = sync.Shot(9, "Removed", {1})
        kept = sync.make_plan([], UNITS, [shot], set(), prune=False)
        self.assertEqual((kept.delete, [s.id for s in kept.orphans]), ([], [9]))
        pruned = sync.make_plan([], UNITS, [shot], set(), prune=True)
        self.assertEqual([s.id for s in pruned.delete], [9])

    def test_previews_with_nothing_to_show_are_skipped_with_a_reason(self):
        previews = [preview("Avatar", []), preview("Dialog", None), preview("New", ["chat_send"], png=None)]
        plan = sync.make_plan(previews, UNITS, [], set(), prune=False)
        self.assertEqual(plan.create, [])
        self.assertEqual(
            dict(plan.skipped),
            {"no translatable strings": ["Avatar"], "not recorded": ["Dialog"], "no Light render": ["New"]},
        )

    def test_a_skipped_preview_never_counts_as_gone(self):
        shot = sync.Shot(5, "Dialog", {3})
        plan = sync.make_plan([preview("Dialog", None)], UNITS, [shot], set(), prune=True)
        self.assertEqual((plan.delete, plan.unlink), ([], []))

    def test_only_limits_creates_and_deletes_to_the_named_previews(self):
        previews = [preview("About", ["about_title"]), preview("Chat", ["chat_send"])]
        shots = [sync.Shot(9, "Removed", {1})]
        plan = sync.make_plan(previews, UNITS, shots, set(), prune=True, only={"Chat"})
        self.assertEqual([p.wrapper for p, _ in plan.create], ["Chat"])
        self.assertEqual(plan.delete, [])


class CoverageTest(unittest.TestCase):
    def test_counts_source_keys_drawn_by_a_preview_with_a_render(self):
        previews = [preview("About", ["about_title", "not_on_weblate"]), preview("Chat", ["chat_send"], png=None)]
        covered, uncovered = sync.coverage(previews, UNITS)
        self.assertEqual(covered, {"about_title"})
        self.assertEqual(uncovered, {"chat": 2, "action": 1})


def png(width, height):
    """A valid PNG of one colour, written without Pillow."""
    row = b"\x00" + b"\xff\x00\x00" * width
    raw = zlib.compress(row * height)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", raw) + chunk(b"IEND", b"")


class UploadBytesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        sync.upload_bytes.cache_clear()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, data):
        path = Path(self.tmp.name, name)
        path.write_bytes(data)
        return path

    def test_a_render_within_the_limit_goes_up_as_it_is(self):
        data = png(400, 2000)
        self.assertEqual(sync.png_size(data), (400, 2000))
        self.assertEqual(sync.upload_bytes(self.write("component.png", data)), data)

    @unittest.skipUnless(importlib.util.find_spec("PIL"), "Pillow not installed")
    def test_a_screen_render_is_scaled_to_fit_keeping_its_shape(self):
        scaled = sync.upload_bytes(self.write("screen.png", png(1079, 2339)))
        self.assertEqual(sync.png_size(scaled), (923, 2000))
        sync.upload_bytes.cache_clear()
        self.assertEqual(sync.upload_bytes(self.write("again.png", png(1079, 2339))), scaled)


class UnitIdTest(unittest.TestCase):
    def test_reads_a_unit_url_an_id_or_an_object(self):
        self.assertEqual(sync.unit_id("https://hosted.weblate.org/api/units/217490720/"), 217490720)
        self.assertEqual(sync.unit_id(12), 12)
        self.assertEqual(sync.unit_id({"id": 3, "url": "x"}), 3)


if __name__ == "__main__":
    unittest.main()
