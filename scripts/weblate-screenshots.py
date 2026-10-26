#!/usr/bin/env python3
"""Show Weblate's translators where each string lands: every Compose preview's English render, uploaded to the
`knit/app` component as a screenshot and linked to the strings it draws.

WHY THIS EXISTS. A translator on Weblate sees a string alone: `Rename` might be a button or a title, and nothing
says how much room it has. Weblate shows a string's screenshots beside it, and we already render every
`@Preview` to a reference PNG for the screenshot tests (.agents/context/testing.md). What was missing is which
strings each preview draws, and that is recorded exactly rather than guessed by OCR: `PreviewStringsTest` composes
every preview under Robolectric with a `Resources` that notes each string it is asked for.

WHAT IT DOES.
  1. `scripts/gen-screenshot-tests.py --check` (stale wrappers mean stale renders: stop), then `--manifest`.
  2. `./gradlew :app:testDebugUnitTest -Pknit.previewStrings=<dir> --tests '*PreviewStringsTest'`, which writes
     `<dir>/strings/<wrapper>.txt` (`--map <dir>` reuses an earlier run instead).
  3. Pairs each preview with its Light render
     (`app/src/screenshotTestDebug/reference/.../<Stem>ScreenshotsKt/<Wrapper>_Light_*_0.png`) and keeps only the
     keys Weblate has as source strings, which drops Material3's own and the `translatable="false"` ones.
  4. Reads Weblate's source strings and the component's screenshots, and plans the difference: a screenshot to
     create (named after the wrapper, e.g. `AboutScreen`), an image whose bytes changed, links to add or remove,
     and, with `--prune`, screenshots whose preview is gone.

A dry run unless `--apply`: anything that changes Weblate's state is confirmed with the maintainer first
(.agents/context/translations.md). It honours the API's rate limit (5,000 requests an hour on Hosted Weblate),
sleeping when it runs low, and every run is a fresh difference, so an interrupted `--apply` resumes by running it
again. The API key comes from wlc's configuration (`~/.config/weblate`, `[keys]`) and is never printed. The
references are Git LFS, so Weblate's own "screenshot file mask" stays empty: it would read pointer files.
Weblate refuses an image past 2,000 px a side, so the screen renders are scaled to fit, with Pillow; everything
else is stdlib. Tests: `python3 -m unittest scripts/test_weblate_screenshots.py`.

Usage: python3 -I scripts/weblate-screenshots.py [--apply] [--map DIR] [--only A,B] [--prune] [--verbose]
"""

from __future__ import annotations

import argparse
import collections
import configparser
import dataclasses
import functools
import io
import json
import os
import re
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REFERENCES = ROOT / "app/src/screenshotTestDebug/reference/app/getknit/knit/screenshot"
RENDER_DIR = ROOT / "app/build/preview-strings"
API = "https://hosted.weblate.org/api/"
LFS_POINTER = b"version https://git-lfs"
LOW_WATER = 50  # requests left in the window before it waits for the reset
MAX_SIDE = 2000  # Weblate refuses a screenshot wider or taller than this (`validate_bitmap`)


# ---- the previews --------------------------------------------------------------------------------------------


@dataclasses.dataclass
class Preview:
    wrapper: str
    png: Path | None  # its Light reference, or None when it has none
    keys: set[str] | None  # what it read, or None when the recorder wrote nothing for it


def light_png(references: Path, stem: str, wrapper: str) -> Path | None:
    """The one Light render of a wrapper. No wrapper name holds `_`, so the glob cannot reach a neighbour's."""
    found = sorted((references / f"{stem}ScreenshotsKt").glob(f"{wrapper}_Light_*_0.png"))
    if len(found) > 1:
        sys.exit(f"{wrapper}: {len(found)} Light renders ({', '.join(p.name for p in found)}); delete the stale one")
    return found[0] if found else None


def png_size(data: bytes) -> tuple[int, int]:
    """Width and height from a PNG's IHDR, which always follows the 8-byte signature."""
    return struct.unpack(">II", data[16:24])


@functools.cache
def upload_bytes(png: Path) -> bytes:
    """The render as Weblate will take it. A screen (1079×2339) is past `MAX_SIDE`, so it is scaled to fit, keeping
    its shape; Pillow is needed only for that, and its output is the same bytes run to run, which is what lets a
    later run tell a changed render from an unchanged one."""
    data = png.read_bytes()
    width, height = png_size(data)
    if max(width, height) <= MAX_SIDE:
        return data
    try:
        from PIL import Image
    except ImportError:
        sys.exit(f"{png.name} is {width}×{height}, past Weblate's {MAX_SIDE} px; scaling it needs Pillow (pip install pillow)")
    scale = MAX_SIDE / max(width, height)
    size = (min(MAX_SIDE, round(width * scale)), min(MAX_SIDE, round(height * scale)))
    out = io.BytesIO()
    with Image.open(io.BytesIO(data)) as image:
        image.resize(size, Image.Resampling.LANCZOS).save(out, "PNG", optimize=True)
    return out.getvalue()


def load_render(render_dir: Path, references: Path) -> list[Preview]:
    manifest = json.loads((render_dir / "manifest.json").read_text(encoding="utf-8"))
    previews = []
    for entry in manifest:
        recorded = render_dir / "strings" / f"{entry['wrapper']}.txt"
        keys = set(recorded.read_text(encoding="utf-8").split()) if recorded.exists() else None
        previews.append(Preview(entry["wrapper"], light_png(references, entry["stem"], entry["wrapper"]), keys))
    return previews


# ---- the plan ------------------------------------------------------------------------------------------------


@dataclasses.dataclass
class Shot:
    """A screenshot on Weblate: its id, name and the source units linked to it."""

    id: int
    name: str
    units: set[int]


@dataclasses.dataclass
class Plan:
    create: list[tuple[Preview, list[int]]] = dataclasses.field(default_factory=list)
    replace: list[tuple[Shot, Preview]] = dataclasses.field(default_factory=list)
    link: list[tuple[Shot, int]] = dataclasses.field(default_factory=list)
    unlink: list[tuple[Shot, int]] = dataclasses.field(default_factory=list)
    delete: list[Shot] = dataclasses.field(default_factory=list)
    orphans: list[Shot] = dataclasses.field(default_factory=list)  # gone from the code, kept without --prune
    skipped: dict[str, list[str]] = dataclasses.field(default_factory=lambda: collections.defaultdict(list))

    def requests(self) -> int:
        return (
            len(self.create)
            + sum(len(ids) for _, ids in self.create)
            + len(self.replace)
            + len(self.link)
            + len(self.unlink)
            + len(self.delete)
        )


def make_plan(
    previews: list[Preview],
    units: dict[str, int],
    shots: list[Shot],
    changed: set[str],
    prune: bool,
    only: set[str] | None = None,
) -> Plan:
    """What turns Weblate's screenshots into the previews': `units` maps a source key to its unit id, `changed`
    names the existing screenshots whose image differs from the render."""
    plan = Plan()
    by_name = {shot.name: shot for shot in shots}
    wanted = set()
    for preview in previews:
        wanted.add(preview.wrapper)
        if only is not None and preview.wrapper not in only:
            continue
        if preview.keys is None:
            plan.skipped["not recorded"].append(preview.wrapper)
            continue
        if preview.png is None:
            plan.skipped["no Light render"].append(preview.wrapper)
            continue
        ids = sorted(units[key] for key in preview.keys if key in units)
        shot = by_name.get(preview.wrapper)
        if not ids:
            plan.skipped["no translatable strings"].append(preview.wrapper)
            if shot is not None:  # it drew strings once; its screenshot now only misleads
                (plan.delete if prune else plan.orphans).append(shot)
            continue
        if shot is None:
            plan.create.append((preview, ids))
            continue
        if preview.wrapper in changed:
            plan.replace.append((shot, preview))
        plan.link += [(shot, unit) for unit in ids if unit not in shot.units]
        plan.unlink += [(shot, unit) for unit in sorted(shot.units - set(ids))]
    for shot in shots:
        if shot.name not in wanted and (only is None or shot.name in only):
            (plan.delete if prune else plan.orphans).append(shot)
    return plan


def coverage(previews: list[Preview], units: dict[str, int]) -> tuple[set[str], dict[str, int]]:
    """The source keys some preview draws, and the uncovered rest counted by their first word (`chat_`, `call_`)."""
    covered = {key for p in previews if p.keys and p.png for key in p.keys if key in units}
    uncovered = collections.Counter(key.split("_", 1)[0] for key in units if key not in covered)
    return covered, dict(uncovered.most_common())


# ---- Weblate -------------------------------------------------------------------------------------------------


def api_key(url: str) -> str:
    """The key wlc keeps for `url`. `[keys]` holds URLs as option names, so `:` must not split a line."""
    config = configparser.ConfigParser(delimiters=("=",), interpolation=None)
    config.read(os.path.expanduser("~/.config/weblate"))
    keys = config["keys"] if config.has_section("keys") else {}
    key = next((v.strip() for k, v in keys.items() if k.strip().rstrip("/") == url.rstrip("/")), None)
    if not key:
        sys.exit(f"no API key for {url} in ~/.config/weblate [keys]")
    return key


class Weblate:
    def __init__(self, url: str, key: str):
        self.url = url
        self.key = key
        self.sent = 0

    def _call(self, method: str, path: str, body: bytes | None = None, content_type: str | None = None):
        url = path if path.startswith("http") else self.url + path
        headers = {"Authorization": "Token " + self.key, "Accept": "application/json"}
        if content_type:
            headers["Content-Type"] = content_type
        for attempt in range(6):
            request = urllib.request.Request(url, data=body, method=method, headers=headers)
            try:
                with urllib.request.urlopen(request, timeout=120) as response:
                    self.sent += 1
                    self._pace(response.headers)
                    return response.read()
            except urllib.error.HTTPError as error:
                self.sent += 1
                if error.code == 429 and attempt < 5:
                    wait = int(error.headers.get("Retry-After") or error.headers.get("X-RateLimit-Reset") or 60)
                    print(f"  throttled; waiting {wait} s", file=sys.stderr)
                    time.sleep(wait + 1)
                    continue
                sys.exit(f"{method} {url}: {error.code} {error.read().decode(errors='replace')[:300]}")
        raise AssertionError("unreachable")

    @staticmethod
    def _pace(headers):
        remaining, reset = headers.get("X-RateLimit-Remaining"), headers.get("X-RateLimit-Reset")
        if remaining is not None and reset is not None and int(remaining) < LOW_WATER:
            print(f"  {remaining} requests left in this window; waiting {reset} s", file=sys.stderr)
            time.sleep(int(reset) + 1)

    def get(self, path: str):
        return json.loads(self._call("GET", path))

    def pages(self, path: str):
        while path:
            page = self.get(path)
            yield from page["results"]
            path = page.get("next")

    def raw(self, path: str) -> bytes:
        return self._call("GET", path)

    def form(self, path: str, fields: dict[str, str], image: Path | None = None):
        """POST multipart/form-data, which the screenshot endpoints take; `image` goes up as `upload_bytes` makes it."""
        boundary = uuid.uuid4().hex
        parts = []
        for name, value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        if image:
            head = (
                f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="{image.name}"\r\n'
                "Content-Type: image/png\r\n\r\n"
            )
            parts += [head.encode(), upload_bytes(image), b"\r\n"]
        parts.append(f"--{boundary}--\r\n".encode())
        reply = self._call("POST", path, b"".join(parts), f"multipart/form-data; boundary={boundary}")
        return json.loads(reply) if reply else None

    def delete(self, path: str):
        self._call("DELETE", path)


UNIT_ID = re.compile(r"/units/(\d+)/?$")
SCREENSHOT_ID = re.compile(r"/screenshots/(\d+)/?$")


def screenshot_id(created: dict) -> int:
    """A created screenshot's id: the reply to the POST carries its `url`, not always an `id`."""
    if "id" in created:
        return int(created["id"])
    match = SCREENSHOT_ID.search(str(created.get("url", "")))
    if not match:
        sys.exit(f"cannot read a screenshot id from {created!r}")
    return int(match.group(1))


def unit_id(ref) -> int:
    """A screenshot lists its units as URLs (or, defensively, ids or objects)."""
    if isinstance(ref, int):
        return ref
    if isinstance(ref, dict):
        return int(ref["id"]) if "id" in ref else unit_id(ref["url"])
    match = UNIT_ID.search(str(ref))
    if not match:
        sys.exit(f"cannot read a unit id from {ref!r}")
    return int(match.group(1))


# ---- running it ----------------------------------------------------------------------------------------------


def render(render_dir: Path) -> None:
    generator = ROOT / "scripts/gen-screenshot-tests.py"
    subprocess.run([sys.executable, generator, "--check"], check=True)
    render_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, generator, "--manifest", render_dir / "manifest.json"], check=True)
    strings = render_dir / "strings"
    for old in strings.glob("*.txt") if strings.exists() else []:
        old.unlink()
    print("Recording the strings each preview reads (PreviewStringsTest)…", flush=True)
    done = subprocess.run(
        [
            "./gradlew",
            ":app:testDebugUnitTest",
            f"-Pknit.previewStrings={render_dir}",
            "--tests",
            "app.getknit.knit.ui.preview.PreviewStringsTest",
            "--rerun",
            "-q",
        ],
        cwd=ROOT,
        check=False,  # a preview that fails still leaves what it read; named below
    )
    if done.returncode:
        if not any(strings.glob("*.txt")):
            sys.exit("Gradle failed before recording any preview (above)")
        print("  Gradle reported failing previews (above); what they read before failing is kept.", file=sys.stderr)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--apply", action="store_true", help="make the changes (default: print them)")
    parser.add_argument("--map", type=Path, help="reuse an earlier render directory instead of running Gradle")
    parser.add_argument("--only", help="comma-separated wrapper names to limit the run to")
    parser.add_argument("--prune", action="store_true", help="delete screenshots whose preview is gone")
    parser.add_argument("--verbose", action="store_true", help="list every change, not just the counts")
    parser.add_argument("--url", default=API, help=f"Weblate API URL (default {API})")
    parser.add_argument("--component", default="knit/app", help="PROJECT/COMPONENT (default knit/app)")
    args = parser.parse_args(argv)
    project, component = args.component.split("/")
    only = set(args.only.split(",")) if args.only else None

    render_dir = args.map or RENDER_DIR
    if not args.map:
        render(render_dir)
    previews = load_render(render_dir, REFERENCES)
    if only is not None and (unknown := only - {p.wrapper for p in previews}):
        sys.exit(f"--only names no preview: {', '.join(sorted(unknown))}")
    for p in previews:
        if p.png is not None and p.png.read_bytes()[: len(LFS_POINTER)] == LFS_POINTER:
            sys.exit(f"{p.png} is an LFS pointer: run `git lfs pull` first")

    weblate = Weblate(args.url, api_key(args.url))
    source = weblate.get(f"components/{project}/{component}/")["source_language"]["code"]
    units = {u["context"]: u["id"] for u in weblate.pages(f"translations/{project}/{component}/{source}/units/?page_size=1000")}
    shots = [
        Shot(s["id"], s["name"], {unit_id(u) for u in s.get("units") or []})
        for s in weblate.pages(f"components/{project}/{component}/screenshots/")
    ]
    by_name = {p.wrapper: p for p in previews}
    changed = {
        shot.name
        for shot in shots
        if shot.name in by_name
        and by_name[shot.name].png is not None
        and (only is None or shot.name in only)
        and weblate.raw(f"screenshots/{shot.id}/file/") != upload_bytes(by_name[shot.name].png)
    }
    plan = make_plan(previews, units, shots, changed, args.prune, only)

    covered, uncovered = coverage(previews, units)
    print(f"Previews: {len(previews)}; Weblate: {len(shots)} screenshots, {len(units)} source strings.")
    for reason, names in sorted(plan.skipped.items()):
        print(f"  skipped, {reason}: {len(names)}" + (f" ({', '.join(names)})" if len(names) <= 8 or args.verbose else ""))
    print(
        f"Plan: create {len(plan.create)}, replace image {len(plan.replace)}, link {len(plan.link) + sum(len(i) for _, i in plan.create)}, "
        f"unlink {len(plan.unlink)}, delete {len(plan.delete)} (about {plan.requests()} requests)."
    )
    if plan.orphans:
        print(f"  {len(plan.orphans)} screenshot(s) no preview draws any more, kept without --prune: {', '.join(s.name for s in plan.orphans)}")
    print(f"Coverage: {len(covered)} of {len(units)} source strings ({100 * len(covered) / max(len(units), 1):.1f} %) appear in a screenshot.")
    print("  uncovered, by first word: " + ", ".join(f"{k} {n}" for k, n in list(uncovered.items())[:15]))
    if args.verbose:
        for preview, ids in plan.create:
            print(f"  + {preview.wrapper}: {len(ids)} strings")
        for shot, _ in plan.replace:
            print(f"  ~ {shot.name}: new image")
        for label, pairs in (("link", plan.link), ("unlink", plan.unlink)):
            for shot, unit in pairs:
                print(f"  {label} {shot.name} ↔ unit {unit}")
        for shot in plan.delete:
            print(f"  - {shot.name}")

    if not args.apply:
        print("Dry run: nothing changed. Re-run with --apply to make these changes on Weblate.")
        return 0

    for n, (preview, ids) in enumerate(plan.create, 1):
        created = weblate.form(
            "screenshots/",
            {"name": preview.wrapper, "project_slug": project, "component_slug": component, "language_code": source},
            preview.png,
        )
        for unit in ids:
            weblate.form(f"screenshots/{screenshot_id(created)}/units/", {"unit_id": str(unit)})
        print(f"  created {preview.wrapper} ({n}/{len(plan.create)}, {len(ids)} strings)")
    for shot, preview in plan.replace:
        weblate.form(f"screenshots/{shot.id}/file/", {}, preview.png)
        print(f"  replaced {shot.name}'s image")
    for shot, unit in plan.link:
        weblate.form(f"screenshots/{shot.id}/units/", {"unit_id": str(unit)})
    for shot, unit in plan.unlink:
        weblate.delete(f"screenshots/{shot.id}/units/{unit}")
    for shot in plan.delete:
        weblate.delete(f"screenshots/{shot.id}/")
        print(f"  deleted {shot.name}")
    print(f"Done: {weblate.sent} requests.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
