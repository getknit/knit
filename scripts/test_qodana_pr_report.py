"""Unit tests for scripts/qodana-pr-report.py. Run: python3 -m unittest scripts/test_qodana_pr_report.py"""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("qodana_pr_report", Path(__file__).with_name("qodana-pr-report.py"))
report = importlib.util.module_from_spec(_SPEC)
sys.modules["qodana_pr_report"] = report
assert _SPEC.loader is not None
_SPEC.loader.exec_module(report)

REPO = "getknit/knit"
SHA = "a" * 40
MAIN_SHA = "b" * 40
RUN = "https://github.com/getknit/knit/actions/runs/1"


def finding(fp: str, *, state: str = "new", uri: str = "app/src/main/X.kt", line: int = 12,
            rule: str = "UnusedSymbol", text: str = "Unused", severity: str = "High") -> dict:
    return {
        "ruleId": rule,
        "level": "warning",
        "message": {"text": text},
        "locations": [{"physicalLocation": {"artifactLocation": {"uri": uri}, "region": {"startLine": line}}}],
        "partialFingerprints": {"equalIndicator/v1": fp},
        "baselineState": state,
        "properties": {"qodanaSeverity": severity},
    }


def sarif(*found: dict) -> dict:
    return {"runs": [{"results": list(found)}]}


def build(pr: list[dict], main: list[dict] | None):
    return report.build(pr, main, repo=REPO, sha=SHA, main_sha=MAIN_SHA if main is not None else None, run_url=RUN)


class IntroducedTest(unittest.TestCase):
    def test_baseline_and_main_findings_are_not_the_pull_requests(self):
        pr = [finding("base", state="unchanged"), finding("drift"), finding("mine")]
        main = [finding("base", state="unchanged"), finding("drift")]
        self.assertEqual([r["partialFingerprints"]["equalIndicator/v1"] for r in report.introduced(pr, main)], ["mine"])

    def test_without_main_the_baseline_alone_decides(self):
        pr = [finding("base", state="unchanged"), finding("drift"), finding("gone", state="absent")]
        self.assertEqual(len(report.introduced(pr, None)), 1)
        _, _, summary = build(pr, None)
        self.assertFalse(summary["compared"])

    def test_a_finding_main_has_and_the_pull_request_lacks_is_resolved(self):
        pr = [finding("kept")]
        main = [finding("kept"), finding("fixed"), finding("old", state="absent")]
        self.assertEqual(report.resolved_count(pr, main), 1)

    def test_moved_finding_matches_on_its_fingerprint(self):
        self.assertEqual(report.introduced([finding("same", line=90)], [finding("same", line=12)]), [])


class SanitiseTest(unittest.TestCase):
    def test_message_cannot_open_markup(self):
        hostile = "see [here](https://evil.example) @maintainer ![x](y) <img src=x> | cell `code`"
        comment, _, _ = build([finding("m", text=hostile)], [])
        row = next(line for line in comment.splitlines() if line.startswith("| High"))
        for opener in ("](", "@m", "<img", "https://", "`code"):
            self.assertNotIn(opener, row.split("|", 4)[4])
        self.assertEqual(row.count("|"), 5)  # the message added no cell

    def test_bad_paths_and_rules_are_dropped(self):
        pr = [finding("p", uri="../../etc/passwd", rule="x`y"), finding("q", uri="/abs/X.kt"),
              finding("r", uri="a b/[x](y).kt")]
        comment, annotations, _ = build(pr, [])
        self.assertEqual(annotations, [])
        self.assertEqual(comment.count("(no file)"), 3)
        self.assertIn("`unknown`", comment)
        self.assertNotIn("passwd", comment)

    def test_malformed_repo_or_sha_is_refused(self):
        with self.assertRaises(ValueError):
            report.build([], [], repo="a/b/c", sha=SHA, main_sha=None, run_url=RUN)
        with self.assertRaises(ValueError):
            report.build([], [], repo=REPO, sha="HEAD", main_sha=None, run_url=RUN)

    def test_garbage_sarif_reads_as_no_results(self):
        for garbage in ([], {"runs": "x"}, {"runs": [None, {"results": [1, "x"]}]}):
            self.assertEqual(report.results(garbage), [])


class OutputTest(unittest.TestCase):
    def test_table_links_the_head_and_orders_by_severity(self):
        pr = [finding("low", severity="Low", uri="b.kt"), finding("crit", severity="Critical", uri="a.kt", line=3)]
        comment, annotations, summary = build(pr, [])
        self.assertTrue(comment.startswith(report.MARKER))
        self.assertIn(f"[`a.kt:3`](https://github.com/{REPO}/blob/{SHA}/a.kt#L3)", comment)
        self.assertLess(comment.index("a.kt"), comment.index("b.kt"))
        self.assertEqual([a["annotation_level"] for a in annotations], ["failure", "notice"])
        self.assertEqual(summary, {"new": 2, "resolved": 0, "compared": True, "annotated": 2})

    def test_rows_and_annotations_are_capped(self):
        pr = [finding(str(i), line=i + 1) for i in range(report.MAX_ANNOTATIONS + 5)]
        comment, annotations, _ = build(pr, [])
        self.assertEqual(len(annotations), report.MAX_ANNOTATIONS)
        self.assertEqual(sum(line.startswith("| High") for line in comment.splitlines()), report.MAX_ROWS)
        self.assertIn(f"…and {len(pr) - report.MAX_ROWS} more.", comment)

    def test_no_new_findings(self):
        comment, annotations, _ = build([finding("x")], [finding("x")])
        self.assertIn("no new findings", comment)
        self.assertEqual(annotations, [])


class LoadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def test_reads_the_top_level_log_from_the_actions_zip(self):
        archive = self.tmp / "qodana-report.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("/report/results/qodana.sarif.json", json.dumps(sarif(finding("wrong"))))
            z.writestr("/qodana.sarif.json", json.dumps(sarif(finding("right"))))
        for path in (archive, self.tmp):  # the zip, or the directory download-artifact put it in
            [r] = report.results(report.load_sarif(path))
            self.assertEqual(report.fingerprint(r), "right")

    def test_oversized_log_is_refused(self):
        archive = self.tmp / "qodana-report.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("/qodana.sarif.json", b" " * (report.MAX_SARIF_BYTES + 1))
        with self.assertRaises(ValueError):
            report.load_sarif(archive)

    def test_main_writes_the_three_files(self):
        (self.tmp / "pr").mkdir()
        (self.tmp / "pr" / "qodana.sarif.json").write_text(json.dumps(sarif(finding("x"))))
        out = self.tmp / "out"
        rc = report.main(["--pr", str(self.tmp / "pr"), "--main", str(self.tmp / "missing"), "--main-sha", MAIN_SHA,
                          "--repo", REPO, "--sha", SHA, "--run-url", RUN, "--out", str(out)])
        self.assertEqual(rc, 0)
        self.assertFalse(json.loads((out / "summary.json").read_text())["compared"])
        self.assertEqual(len(json.loads((out / "annotations.json").read_text())), 1)
        self.assertIn("could not be loaded", (out / "comment.md").read_text())


if __name__ == "__main__":
    unittest.main()
