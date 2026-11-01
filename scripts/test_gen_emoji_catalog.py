"""Unit tests for scripts/gen-emoji-catalog.py's parse and keyword rules. Run: python3 -m unittest scripts/test_gen_emoji_catalog.py"""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("gen_emoji_catalog", Path(__file__).with_name("gen-emoji-catalog.py"))
gen = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(gen)

SNIPPET = """\
# emoji-test.txt
# Date: 2025-08-04, 20:55:31 GMT
# Version: 17.0

# group: Smileys & Emotion

# subgroup: face-smiling
1F600                                                  ; fully-qualified     # 😀 E1.0 grinning face
263A FE0F                                              ; fully-qualified     # ☺️ E0.6 smiling face
263A                                                   ; unqualified         # ☺ E0.6 smiling face

# group: People & Body

# subgroup: hand-fingers-closed
1F44D                                                  ; fully-qualified     # 👍 E0.6 thumbs up
1F44D 1F3FB                                            ; fully-qualified     # 👍🏻 E1.0 thumbs up: light skin tone
1F468 200D 2764 FE0F 200D 1F468                        ; fully-qualified     # 👨‍❤️‍👨 E2.0 couple with heart: man, man
1F468 200D 2764 200D 1F468                             ; minimally-qualified # 👨‍❤‍👨 E2.0 couple with heart: man, man

# group: Component

# subgroup: skin-tone
1F3FB                                                  ; component           # 🏻 E1.0 light skin tone
1F9B0                                                  ; component           # 🦰 E11.0 red hair

# group: Flags

# subgroup: subdivision-flag
1F3F4 E0067 E0062 E0065 E006E E0067 E007F              ; fully-qualified     # 🏴󠁧󠁢󠁥󠁮󠁧󠁿 E5.0 flag: England

# Status Counts
# fully-qualified : 6
"""


# CLDR keys drop U+FE0F (smiling face is cp="☺"); the derived file carries the ZWJ and flag sequences.
CLDR_ANNOTATIONS = """<ldml><annotations>
<annotation cp="😀">face | Grin | grinning face</annotation>
<annotation cp="😀" type="tts">grinning face</annotation>
<annotation cp="☺">face | outlined | relaxed | smile</annotation>
<annotation cp="👍">+1 | hand | thumb | up | +1</annotation>
</annotations></ldml>"""
CLDR_DERIVED = """<ldml><annotations>
<annotation cp="😀">derived loses to the hand-curated file</annotation>
<annotation cp="👍🏻">+1 | hand | light skin tone | thumb | up</annotation>
<annotation cp="👨‍❤‍👨">couple | love | man</annotation>
<annotation cp="🏴󠁧󠁢󠁥󠁮󠁧󠁿">flag</annotation>
</annotations></ldml>"""
CLDR = gen.Cldr("release-48", (CLDR_ANNOTATIONS, CLDR_DERIVED))


class ParseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.entries, self.stats = gen.parse(SNIPPET.splitlines())

    def test_only_fully_qualified_lines_survive(self) -> None:
        self.assertEqual([e.name for e in self.entries], [
            "grinning face", "smiling face", "thumbs up", "thumbs up: light skin tone",
            "couple with heart: man, man", "flag: England",
        ])
        self.assertEqual(self.stats["unqualified"], 1)
        self.assertEqual(self.stats["minimally-qualified"], 1)

    def test_group_header_maps_to_the_enum_index(self) -> None:
        by_name = {e.name: e for e in self.entries}
        self.assertEqual(by_name["grinning face"].group, 0)
        self.assertEqual(by_name["thumbs up"].group, 1)
        self.assertEqual(by_name["flag: England"].group, 8)

    def test_skin_tone_modifier_sets_the_tone_flag(self) -> None:
        by_name = {e.name: e for e in self.entries}
        self.assertEqual(by_name["thumbs up"].tone, 0)
        self.assertEqual(by_name["thumbs up: light skin tone"].tone, 1)
        self.assertEqual(by_name["couple with heart: man, man"].tone, 0)

    def test_component_group_and_status_are_dropped(self) -> None:
        emojis = {e.emoji for e in self.entries}
        self.assertNotIn("\U0001F3FB", emojis)
        self.assertNotIn("\U0001F9B0", emojis)
        self.assertEqual(self.stats["component"], 2)

    def test_emoji_is_rebuilt_from_code_points(self) -> None:
        by_name = {e.name: e for e in self.entries}
        self.assertEqual(by_name["smiling face"].emoji, "☺️")
        self.assertEqual(by_name["flag: England"].emoji, "\U0001F3F4\U000E0067\U000E0062\U000E0065\U000E006E\U000E0067\U000E007F")

    def test_unknown_group_is_a_hard_error(self) -> None:
        with self.assertRaises(ValueError):
            gen.parse(["# group: Holograms", "1F600 ; fully-qualified # 😀 E1.0 grinning face"])

    def test_base_entries_get_lowercased_deduped_cldr_keywords(self) -> None:
        by_name = {e.name: e for e in gen.attach_keywords(self.entries, gen.cldr_keywords(CLDR.texts))}
        self.assertEqual(by_name["grinning face"].keywords, ("face", "grin"))  # name dropped, annotations win
        self.assertEqual(by_name["smiling face"].keywords, ("face", "outlined", "relaxed", "smile"))  # FE0F stripped
        self.assertEqual(by_name["thumbs up"].keywords, ("+1", "hand", "thumb", "up"))
        self.assertEqual(by_name["couple with heart: man, man"].keywords, ("couple", "love", "man"))
        self.assertEqual(by_name["flag: England"].keywords, ("flag",))

    def test_tone_variants_carry_no_keywords(self) -> None:
        by_name = {e.name: e for e in gen.attach_keywords(self.entries, gen.cldr_keywords(CLDR.texts))}
        self.assertEqual(by_name["thumbs up: light skin tone"].keywords, ())

    def test_a_base_entry_cldr_lacks_is_a_hard_error(self) -> None:
        with self.assertRaises(ValueError):
            gen.attach_keywords(self.entries, gen.cldr_keywords([CLDR_DERIVED]))

    def test_source_info_reads_version_date_and_hash(self) -> None:
        src = gen.source_info(SNIPPET)
        self.assertEqual(src.version, "17.0")
        self.assertEqual(src.date, "2025-08-04")
        self.assertEqual(len(src.sha256), 64)

    def test_render_emits_tab_separated_lines_under_a_comment_header(self) -> None:
        text, entries, _ = gen.render(SNIPPET, CLDR)
        lines = [ln for ln in text.splitlines() if ln and not ln.startswith("#")]
        self.assertEqual(len(lines), len(entries))
        self.assertEqual(lines[0], "😀\t0\t0\tgrinning face\tface|grin")
        self.assertEqual(lines[3], "👍🏻\t1\t1\tthumbs up: light skin tone")  # no keyword column, no trailing tab
        self.assertIn("GENERATED FILE", text)
        self.assertIn("Emoji 17.0 (2025-08-04)", text)
        self.assertIn("CLDR release-48 annotations/en.xml sha256", text)


if __name__ == "__main__":
    unittest.main()
