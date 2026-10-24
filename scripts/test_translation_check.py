"""Unit tests for scripts/translation-check.py. Run: python3 -m unittest scripts/test_translation_check.py"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("translation_check", Path(__file__).with_name("translation-check.py"))
check = importlib.util.module_from_spec(_SPEC)
sys.modules["translation_check"] = check  # dataclasses look their module up by name
assert _SPEC.loader is not None
_SPEC.loader.exec_module(check)

HEAD = '<resources xmlns:tools="http://schemas.android.com/tools">\n'
TAIL = "</resources>\n"

ENGLISH = """
    <string name="app_name">Knit</string>
    <string name="greeting">Hello, %1$s</string>
    <string name="call_diag_ice" translatable="false">ICE</string>
    <string name="relays_add">Add a relay</string>
    <string name="action_ok">OK</string>
    <plurals name="messages">
        <item quantity="one">%1$d message</item>
        <item quantity="other">%1$d messages</item>
    </plurals>
    <plurals name="chats" tools:ignore="ImpliedQuantity">
        <item quantity="one">One chat</item>
        <item quantity="other">%1$d chats</item>
    </plurals>
"""

GOOD_PT = """
    <string name="app_name">Knit</string>
    <string name="greeting">Olá, %1$s</string>
    <string name="relays_add">Novo retransmissor</string>
    <string name="action_ok">Certo</string>
    <plurals name="messages">
        <item quantity="one">%1$d mensagem</item>
        <item quantity="many">%1$d mensagens</item>
        <item quantity="other">%1$d mensagens</item>
    </plurals>
    <plurals name="chats" tools:ignore="ImpliedQuantity">
        <item quantity="one">Uma conversa</item>
        <item quantity="many">%1$d conversas</item>
        <item quantity="other">%1$d conversas</item>
    </plurals>
"""

GLOSSARY = "en\tkeep\tpt-BR\nKnit\tyes\t\nrelay\t\tretransmissor\n"


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.res = Path(self.dir.name)
        (self.res / "values").mkdir()
        (self.res / "values/strings.xml").write_text(HEAD + ENGLISH + TAIL, encoding="utf-8")
        self.glossary = self.res / "glossary.tsv"
        self.glossary.write_text(GLOSSARY, encoding="utf-8")

    def tearDown(self):
        self.dir.cleanup()

    def findings(self, body: str, folder: str = "values-pt-rBR") -> list[tuple[str, str, str]]:
        path = self.res / folder / "strings.xml"
        path.parent.mkdir(exist_ok=True)
        path.write_text(HEAD + body + TAIL, encoding="utf-8")
        source = check.load_entries(self.res / "values/strings.xml")
        return [(f.name, f.level, f.message) for f in check.check_locale(source, path, check.load_glossary(self.glossary))]

    def errors(self, body: str) -> list[str]:
        return [f"{name}: {message}" for name, level, message in self.findings(body) if level == "error"]

    def warnings(self, body: str) -> list[str]:
        return [f"{name}: {message}" for name, level, message in self.findings(body) if level == "warning"]

    def test_a_complete_translation_is_clean(self):
        self.assertEqual([], self.findings(GOOD_PT))

    def test_a_missing_string_is_an_error(self):
        errors = self.errors(GOOD_PT.replace('    <string name="greeting">Olá, %1$s</string>\n', ""))
        self.assertEqual(["greeting: missing (lint: MissingTranslation)"], errors)

    def test_an_extra_string_and_a_translated_untranslatable_one_are_errors(self):
        body = GOOD_PT + '    <string name="gone">Sumiu</string>\n    <string name="call_diag_ice">ICE</string>\n'
        errors = self.errors(body)
        self.assertIn("gone: not in the English (lint: ExtraTranslation)", errors)
        self.assertTrue(any(e.startswith("call_diag_ice: translated") for e in errors), errors)

    def test_a_dropped_or_renumbered_placeholder_is_an_error(self):
        self.assertEqual(1, len(self.errors(GOOD_PT.replace("Olá, %1$s", "Olá"))))
        self.assertEqual(1, len(self.errors(GOOD_PT.replace("Olá, %1$s", "Olá, %2$s"))))

    def test_a_double_percent_counts_as_a_placeholder(self):
        source = check.placeholders("Downloading… %1$d%%")
        self.assertEqual(["%%", "%1$d"], source)
        self.assertEqual([], check.placeholders("100 % de"))

    def test_an_unescaped_apostrophe_and_a_leading_at_are_errors(self):
        self.assertEqual(1, len(self.errors(GOOD_PT.replace("Olá, %1$s", "Olá d'água, %1$s"))))
        self.assertEqual([], self.errors(GOOD_PT.replace("Olá, %1$s", "Olá d\\'água, %1$s")))
        self.assertEqual(1, len(self.errors(GOOD_PT.replace(">Certo<", ">@Certo<"))))

    def test_plural_quantities_follow_the_language(self):
        no_many = GOOD_PT.replace('        <item quantity="many">%1$d mensagens</item>\n', "")
        self.assertEqual(["messages: quantities ['one', 'other'], the language wants ['many', 'one', 'other']"],
                         self.errors(no_many))
        german = GOOD_PT.replace('        <item quantity="many">%1$d mensagens</item>\n', "").replace(
            '        <item quantity="many">%1$d conversas</item>\n', "")
        self.assertEqual([], [f for f in self.findings(german, "values-de") if f[1] == "error"])

    def test_an_unknown_language_is_a_warning_not_a_guess(self):
        warnings = [f for f in self.findings(GOOD_PT, "values-xx") if f[1] == "warning"]
        self.assertTrue(any("no plural categories known for 'xx'" in f[2] for f in warnings), warnings)

    def test_a_plural_item_with_a_foreign_placeholder_is_an_error(self):
        body = GOOD_PT.replace('<item quantity="many">%1$d mensagens</item>', '<item quantity="many">%2$d mensagens</item>')
        self.assertTrue(any(e.startswith("messages[many]: placeholders") for e in self.errors(body)))

    def test_a_lost_tools_ignore_is_an_error(self):
        body = GOOD_PT.replace(' tools:ignore="ImpliedQuantity"', "")
        self.assertEqual(['chats: lost tools:ignore="ImpliedQuantity"'], self.errors(body))

    def test_an_empty_translation_is_an_error(self):
        self.assertEqual(["greeting: empty"], self.errors(GOOD_PT.replace("Olá, %1$s", "")))

    def test_one_that_drops_the_count_is_a_warning(self):
        body = GOOD_PT.replace('<item quantity="one">%1$d mensagem</item>', '<item quantity="one">Uma mensagem</item>')
        self.assertTrue(any(w.startswith("messages[one]: drops the count") for w in self.warnings(body)))

    def test_text_left_in_english_is_a_warning_but_kept_terms_are_not(self):
        warnings = self.warnings(GOOD_PT.replace(">Certo<", ">OK<").replace("Olá, %1$s", "Hello, %1$s"))
        self.assertEqual(["greeting: same as the English"], warnings)  # "OK" has no three-letter word; Knit is kept

    def test_a_short_label_that_more_than_doubles_is_a_warning(self):
        body = GOOD_PT.replace(">Certo<", ">Confirmar e continuar<")
        self.assertTrue(any(w.startswith("action_ok: short label grew") for w in self.warnings(body)))

    def test_a_glossary_term_with_none_of_its_renderings_is_a_warning(self):
        body = GOOD_PT.replace("Novo retransmissor", "Novo servidor")
        self.assertEqual(["relays_add: glossary: 'relay' is usually 'retransmissor' here"], self.warnings(body))

    def test_locale_folders_map_to_locales_and_qualifiers_do_not(self):
        self.assertEqual("pt-BR", check.locale_of("values-pt-rBR"))
        self.assertEqual("es", check.locale_of("values-es"))
        self.assertEqual("sr-Latn", check.locale_of("values-b+sr+Latn"))
        self.assertIsNone(check.locale_of("values-night"))
        self.assertIsNone(check.locale_of("values-v31"))

    def test_main_exits_one_on_an_error_and_zero_on_warnings(self):
        target = self.res / "values-pt-rBR/strings.xml"
        target.parent.mkdir()
        argv = ["--res", str(self.res), "--glossary", str(self.glossary), "--locale", "pt-BR"]
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            target.write_text(HEAD + GOOD_PT.replace(">Certo<", ">OK, then<") + TAIL, encoding="utf-8")
            self.assertEqual(0, check.main(argv))
            target.write_text(HEAD + GOOD_PT.replace("Olá, %1$s", "Olá") + TAIL, encoding="utf-8")
            self.assertEqual(1, check.main(argv))
            self.assertEqual(2, check.main(["--res", str(self.res), "--locale", "fr"]))


if __name__ == "__main__":
    unittest.main()
