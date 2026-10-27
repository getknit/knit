#!/usr/bin/env python3
"""Turn a pull request's Qodana run into a PR comment and check-run annotations (.github/workflows/qodana-report.yml).

    python3 -I scripts/qodana-pr-report.py --pr PR_RESULTS [--main MAIN_RESULTS --main-sha SHA]
        --repo OWNER/REPO --sha HEAD_SHA --run-url URL --out DIR

WHY THIS EXISTS. ci.yml's Qodana job runs on a pull request with the read-only token GitHub gives a fork, so the
action cannot post its comment or its annotations there. The job uploads its results instead, and the report
workflow, which runs from the default branch with a token that can write, reads them with this script and posts.
Nothing from the pull request runs: its results are data, and this script is the default branch's.

WHAT IT REPORTS. The findings the pull request introduces: those outside the committed baseline (`qodana.sarif.json`,
which the run is given with --baseline) that main's own latest run does not have either, matched on Qodana's
`equalIndicator/v1` fingerprint. The committed baseline drifts behind main between `scripts/qodana.sh --baseline`
runs, so the baseline alone would hand a contributor every finding main picked up since. Without main's results it
falls back to the baseline alone and says so.

UNTRUSTED INPUT. A fork controls its run's results completely, so every string that reaches the comment is
sanitised here: paths and rule ids must match a strict pattern or are dropped, and message text is entity-encoded so
it cannot open a link, a mention, an image or markup. Archives are size-capped before they are read.

PR_RESULTS and MAIN_RESULTS are each a `qodana.sarif.json`, the `qodana-report.zip` the action uploads, or the
directory actions/download-artifact extracted it into. DIR gets `comment.md`, `annotations.json` (a check run's
`output.annotations`, at most 50) and `summary.json`.

Stdlib only. Tests: `python3 -m unittest scripts/test_qodana_pr_report.py`.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

MARKER = "<!-- qodana-report -->"
SARIF_NAME = "qodana.sarif.json"
MAX_SARIF_BYTES = 64 * 1024 * 1024  # main's whole-tree run is ~2 MB
MAX_ROWS = 30  # the comment's table; the artifact has the rest
MAX_ANNOTATIONS = 50  # one checks.create call's limit
MAX_MESSAGE = 200

PATH_RE = re.compile(r"[A-Za-z0-9._/-]{1,300}")
RULE_RE = re.compile(r"[A-Za-z0-9_.-]{1,100}")
REPO_RE = re.compile(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
SHA_RE = re.compile(r"[0-9a-f]{40}")

SEVERITY_ORDER = {"Critical": 0, "High": 1, "Moderate": 2, "Low": 3, "Info": 4}
LEVEL_SEVERITY = {"error": "High", "warning": "Moderate", "note": "Low"}
ANNOTATION_LEVEL = {"Critical": "failure", "High": "warning", "Moderate": "warning"}


def load_sarif(path: Path) -> dict:
    """The SARIF log from a .json, the action's .zip, or a directory holding either."""
    if path.is_dir():
        candidates = [path / SARIF_NAME, *sorted(path.glob("*.zip"))]
        found = next((c for c in candidates if c.is_file()), None)
        if found is None:
            raise FileNotFoundError(f"no {SARIF_NAME} or .zip in {path}")
        path = found
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            # The action stores entries with absolute names; the top-level log is "/qodana.sarif.json", and the
            # report's copy under report/results/ is the same file again.
            entry = next((i for i in archive.infolist() if i.filename.lstrip("/") == SARIF_NAME), None)
            if entry is None:
                raise FileNotFoundError(f"no {SARIF_NAME} at the top of {path}")
            if entry.file_size > MAX_SARIF_BYTES:
                raise ValueError(f"{SARIF_NAME} in {path} is {entry.file_size} bytes, over {MAX_SARIF_BYTES}")
            with archive.open(entry) as stream:
                data = stream.read(MAX_SARIF_BYTES + 1)
    else:
        if path.stat().st_size > MAX_SARIF_BYTES:
            raise ValueError(f"{path} is over {MAX_SARIF_BYTES} bytes")
        data = path.read_bytes()
    if len(data) > MAX_SARIF_BYTES:
        raise ValueError(f"{SARIF_NAME} is over {MAX_SARIF_BYTES} bytes")
    return json.loads(data)


def results(sarif: dict) -> list[dict]:
    runs = sarif.get("runs") if isinstance(sarif, dict) else None
    if not isinstance(runs, list):
        return []
    out = []
    for run in runs:
        found = run.get("results") if isinstance(run, dict) else None
        if isinstance(found, list):
            out.extend(r for r in found if isinstance(r, dict))
    return out


def _location(result: dict) -> tuple[str, int]:
    try:
        physical = result["locations"][0]["physicalLocation"]
        uri = physical["artifactLocation"]["uri"]
        line = physical.get("region", {}).get("startLine", 0)
    except (KeyError, IndexError, TypeError):
        return "", 0
    return (uri if isinstance(uri, str) else ""), (line if isinstance(line, int) and line > 0 else 0)


def fingerprint(result: dict) -> str:
    """Qodana's own baseline key, so a finding moved by an edit above it still matches."""
    prints = result.get("partialFingerprints")
    if isinstance(prints, dict) and isinstance(prints.get("equalIndicator/v1"), str):
        return prints["equalIndicator/v1"]
    uri, _ = _location(result)
    try:
        snippet = result["locations"][0]["physicalLocation"]["region"]["snippet"]["text"]
    except (KeyError, IndexError, TypeError):
        snippet = ""
    return f"{result.get('ruleId')}|{uri}|{snippet}"


def present(result: dict) -> bool:
    """A finding the run saw. `absent` marks a baseline entry the run no longer has."""
    return result.get("baselineState") != "absent"


def introduced(pr: list[dict], main: list[dict] | None) -> list[dict]:
    """The pull request's findings outside the baseline and, when main's results are known, not on main."""
    outside = [r for r in pr if present(r) and r.get("baselineState") != "unchanged"]
    if main is None:
        return outside
    on_main = {fingerprint(r) for r in main if present(r)}
    return [r for r in outside if fingerprint(r) not in on_main]


def resolved_count(pr: list[dict], main: list[dict] | None) -> int:
    if main is None:
        return 0
    in_pr = {fingerprint(r) for r in pr if present(r)}
    return len({fingerprint(r) for r in main if present(r)} - in_pr)


def safe_path(uri: str) -> str | None:
    if not PATH_RE.fullmatch(uri) or uri.startswith("/") or ".." in uri.split("/"):
        return None
    return uri


def safe_rule(rule: object) -> str:
    return rule if isinstance(rule, str) and RULE_RE.fullmatch(rule) else "unknown"


def severity(result: dict) -> str:
    props = result.get("properties")
    named = props.get("qodanaSeverity") if isinstance(props, dict) else None
    if named in SEVERITY_ORDER:
        return named
    return LEVEL_SEVERITY.get(result.get("level"), "Info")


def message_text(result: dict) -> str:
    message = result.get("message")
    text = message.get("text") if isinstance(message, dict) else None
    text = " ".join(text.split()) if isinstance(text, str) else ""
    return text if len(text) <= MAX_MESSAGE else text[: MAX_MESSAGE - 1] + "…"


def inert(text: str) -> str:
    """Markdown that renders as the text and nothing else: every character but a letter, digit or space is an
    entity, so no link, autolink, mention, image, table cell or HTML can open."""
    return "".join(c if c.isalnum() or c == " " else f"&#{ord(c)};" for c in text)


def build(
    pr: list[dict], main: list[dict] | None, *, repo: str, sha: str, main_sha: str | None, run_url: str
) -> tuple[str, list[dict], dict]:
    if not REPO_RE.fullmatch(repo) or not SHA_RE.fullmatch(sha):
        raise ValueError("repo or sha is malformed")
    if main_sha is not None and not SHA_RE.fullmatch(main_sha):
        raise ValueError("main sha is malformed")
    new = sorted(introduced(pr, main), key=lambda r: (SEVERITY_ORDER[severity(r)], _location(r)))
    fixed = resolved_count(pr, main)

    if main is not None:
        against = f"Compared with main at `{main_sha[:9]}`." if main_sha else "Compared with main's latest run."
        if fixed:
            against += f" It also clears {fixed} finding{'s' * (fixed != 1)} main has."
    else:
        against = (
            "Main's own results could not be loaded, so this lists every finding outside the committed baseline "
            "(`qodana.sarif.json`); some may already be on main."
        )
    if new:
        head = f"### Qodana: {len(new)} new finding{'s' * (len(new) != 1)}"
    else:
        head = "### Qodana: no new findings"
    lines = [MARKER, head, "", f"{against} Qodana is advisory: nothing here blocks the merge.", ""]

    if new:
        lines += ["| Severity | Where | Inspection | Message |", "|---|---|---|---|"]
        for r in new[:MAX_ROWS]:
            uri, line = _location(r)
            path = safe_path(uri)
            if path:
                anchor = f"#L{line}" if line else ""
                where = f"[`{path}{f':{line}' if line else ''}`](https://github.com/{repo}/blob/{sha}/{path}{anchor})"
            else:
                where = "(no file)"
            lines.append(f"| {severity(r)} | {where} | `{safe_rule(r.get('ruleId'))}` | {inert(message_text(r))} |")
        if len(new) > MAX_ROWS:
            lines += ["", f"…and {len(new) - MAX_ROWS} more."]
        lines.append("")
    lines.append(f"<sub>[Run]({run_url}) · the full report is its `qodana-report` artifact</sub>")

    annotations = []
    for r in new:
        uri, line = _location(r)
        path = safe_path(uri)
        if not path or len(annotations) == MAX_ANNOTATIONS:
            continue
        sev = severity(r)
        annotations.append(
            {
                "path": path,
                "start_line": line or 1,
                "end_line": line or 1,
                "annotation_level": ANNOTATION_LEVEL.get(sev, "notice"),
                "title": f"{safe_rule(r.get('ruleId'))} ({sev})",
                "message": message_text(r) or "Qodana finding",
            }
        )
    summary = {"new": len(new), "resolved": fixed, "compared": main is not None, "annotated": len(annotations)}
    return "\n".join(lines) + "\n", annotations, summary


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pr", type=Path, required=True)
    parser.add_argument("--main", type=Path)
    parser.add_argument("--main-sha")
    parser.add_argument("--repo", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--run-url", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    if not args.run_url.startswith("https://github.com/"):
        parser.error("--run-url must be a github.com URL")

    pr = results(load_sarif(args.pr))
    main_results = None
    if args.main is not None:
        try:
            main_results = results(load_sarif(args.main))
        except (OSError, ValueError, zipfile.BadZipFile) as e:
            print(f"main's results unreadable, falling back to the baseline: {e}", file=sys.stderr)
    comment, annotations, summary = build(
        pr, main_results, repo=args.repo, sha=args.sha, main_sha=args.main_sha if main_results is not None else None,
        run_url=args.run_url,
    )
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "comment.md").write_text(comment, encoding="utf-8")
    (args.out / "annotations.json").write_text(json.dumps(annotations), encoding="utf-8")
    (args.out / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
