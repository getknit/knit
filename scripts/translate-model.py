#!/usr/bin/env python3
"""Generate the app's table of translation models, stage the models for getknit.app, and check the pins.

The app (ADR 2026-10.t2h6) downloads Mozilla's Firefox Translations models, each direction to or from English, file by
file from getknit.app and checks every byte against the sizes and SHA-256s pinned in the generated
`TranslateDirections.kt` (app/src/main/java/app/getknit/knit/translate/TranslateDirections.kt). This script writes that
file, so nobody types a hash by hand:

  scripts/translate-model.py              print the generated file for the upstream files
  scripts/translate-model.py --write      write it over TranslateDirections.kt
  scripts/translate-model.py --check      exit 1 unless TranslateDirections.kt is byte for byte what --write would write
  scripts/translate-model.py --out DIR    also write the files to DIR/<model id>/, ready to upload under
                                          https://getknit.app/models/translate/ (immutable: a new model is a new id)
  scripts/translate-model.py --suggest uk print MODELS entries for en-uk and uk-en from the registry, to paste below

Upstream is Mozilla's model registry (db/models.json in the bucket below), whose files live under one training run's
directory and never move. Each is fetched gzipped, checked against the SHA-256 pinned here, and unpacked: the phone
keeps and pins the unpacked bytes. The models are MPL-2.0; the licence comes from Mozilla's models repository, pinned,
and goes beside them, which with an unmodified file and a link to its source is what MPL asks of a redistributor.
Stdlib only.
"""

import argparse
import gzip
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

BUCKET = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data"
LICENSE_URL = (
    "https://raw.githubusercontent.com/mozilla/firefox-translations-models/"
    "e7957fc407441a5e3e35bbcbf9d60d9b35764618/LICENSE"
)
LICENSE_SHA256 = "1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5"
# Each architecture's decoder depth, which slimt must be told at load (it cannot read it from the file; told the wrong
# count, it loads and writes nonsense). Every one has six encoder layers. "tiny" joins once a desktop bench confirms it.
DECODER_LAYERS = {"base-memory": 4}
# name: (model id, the training run's export directory, architecture, {unpacked file name: SHA-256 of its .gz}). Both
# are the registry's "base-memory" release (31.2M parameters) as of 2026-10-22. A new language: --suggest, then paste.
MODELS = {
    "en-es": (
        "mozilla-en-es-3b1c3995",
        "models/en-es/retrain_hr_fix_names_CUAEXUHoQum_cFqh-ZAryw/exported",
        "base-memory",
        {
            "model.enes.intgemm.alphas.bin": "fe4025fde24a4d5f80533cfb7acd53d0eb7f194dfc2d8f427f0d4563cfb6ac06",
            "lex.50.50.enes.s2t.bin": "37a067966f28ff0ba430fddb7412bc5b07afa2cd2fd5a46d9ad74045a1a06380",
            "vocab.enes.spm": "ae2760c04bc6eb5362b16e5c2f145c376bb04bac421c33ba3c0766805e6f03b3",
        },
    ),
    "es-en": (
        "mozilla-es-en-4aed7734",
        "models/es-en/retrain_hr_HbNjJ60BTwmVTbhfFxuduA/exported",
        "base-memory",
        {
            "model.esen.intgemm.alphas.bin": "54247f1e057180bca1d404dfd5c12836f65bc96cef7af98e59e55acb8bd093b1",
            "lex.50.50.esen.s2t.bin": "3d7856bfa3b12804bdc80f12fd828fd9062c462113ab73fbf87c5c3b68420813",
            "vocab.esen.spm": "1658b529b8858cf2e1e8cef2c4e2d69654d77b5f714ac52205864b336dfeb3ee",
        },
    ),
}

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "app/src/main/java/app/getknit/knit/translate/TranslateDirections.kt"
REGISTRY = f"{BUCKET}/db/models.json"
# ktlint_official's line limit: a pin longer than this is wrapped one argument per line, as ktlintFormat would.
MAX_LINE = 140


def fetch(url, sha256):
    with urllib.request.urlopen(url, timeout=600) as response:
        body = response.read()
    digest = hashlib.sha256(body).hexdigest()
    if digest != sha256:
        sys.exit(f"{url} has sha256 {digest}, not the pinned {sha256}")
    return body


def model_files(name, licence_body):
    """The files the app fetches, in its order: the model's, then the licence."""
    _, directory, _, gzipped = MODELS[name]
    out = {}
    for file, sha256 in gzipped.items():
        out[file] = gzip.decompress(fetch(f"{BUCKET}/{directory}/{file}.gz", sha256))
        print(f"  {name}: {file} {len(out[file]):,} B", file=sys.stderr)
    out["LICENSE"] = licence_body
    return out


def pins(files):
    return [(file, len(body), hashlib.sha256(body).hexdigest()) for file, body in files.items()]


def kotlin_pin(name, size, sha, indent):
    pad = " " * indent
    line = f'{pad}SttFile("{name}", {size:_}, "{sha}"),'
    if len(line) <= MAX_LINE:
        return line
    inner = " " * (indent + 4)
    return f'{pad}SttFile(\n{inner}"{name}",\n{inner}{size:_},\n{inner}"{sha}",\n{pad}),'


def kotlin_direction(name, pinned):
    model_id, _, architecture, _ = MODELS[name]
    if architecture not in DECODER_LAYERS:
        sys.exit(f"{name}: no decoder depth known for architecture {architecture!r}; bench it, then add it to DECODER_LAYERS")
    source, target = name.split("-")
    files = "\n".join(kotlin_pin(n, size, sha, 20) for n, size, sha in pinned)
    return f"""    val {source.upper()}_{target.upper()} =
        TranslateDirection(
            from = TranslateLang("{source}"),
            to = TranslateLang("{target}"),
            id = "{model_id}",
            baseUrl = "https://getknit.app/models/translate/{model_id}/",
            files =
                listOf(
{files}
                ),
            decoderLayers = {DECODER_LAYERS[architecture]},
        )
"""


def kotlin_file(directions):
    names = sorted(directions)
    body = "\n".join(kotlin_direction(n, directions[n]) for n in names)
    every = ", ".join(f"{n.split('-')[0].upper()}_{n.split('-')[1].upper()}" for n in names)
    return f"""// Generated by scripts/translate-model.py from Mozilla's model registry (ADR 2026-10.t2h6). Do not edit: change the
// script's MODELS and run `scripts/translate-model.py --write`.
package app.getknit.knit.translate

import app.getknit.knit.call.SttFile

/** Every direction a phone can download, each pinned by size and SHA-256 (generated; see [TranslateDirection]). */
object TranslateDirections {{
{body}
    val ALL: List<TranslateDirection> = listOf({every})
}}
"""


def suggest(lang):
    """MODELS entries for en-LANG and LANG-en from the registry, the released one first, with their .gz SHA-256s."""
    with urllib.request.urlopen(REGISTRY, timeout=120) as response:
        registry = json.load(response)["models"]
    for name in (f"en-{lang}", f"{lang}-en"):
        entries = registry.get(name)
        if not entries:
            print(f"# {name}: not in the registry")
            continue
        released = [e for e in entries if (e.get("releaseStatus") or "").startswith("Release")]
        entry = (released or entries)[0]
        directory = entry["files"]["model"]["path"].rsplit("/", 1)[0]
        print(f"# {name}: {entry['architecture']}, {entry.get('releaseStatus')}, "
              f"comet22 {entry.get('metrics', {}).get('flores200-plus', {}).get('comet22')}")
        model_id = f"mozilla-{name}-{entry['files']['model']['uncompressedHash'][:8]}"
        print(f'    "{name}": (\n        "{model_id}",\n        "{directory}",\n        "{entry["architecture"]}",\n        {{')
        for role in ("model", "lexicalShortlist", "vocab", "srcVocab", "trgVocab"):
            if role not in entry["files"]:
                continue
            path = entry["files"][role]["path"]
            with urllib.request.urlopen(f"{BUCKET}/{path}", timeout=600) as response:
                digest = hashlib.sha256(response.read()).hexdigest()
            print(f'            "{path.rsplit("/", 1)[1].removesuffix(".gz")}": "{digest}",')
        print("        },\n    ),")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true", help="write TranslateDirections.kt")
    parser.add_argument("--check", action="store_true", help="fail unless TranslateDirections.kt is what --write writes")
    parser.add_argument("--out", help="write the files under OUT/<model id>/ for upload")
    parser.add_argument("--model", choices=sorted(MODELS), help="with --out: stage one direction only")
    parser.add_argument("--suggest", metavar="LANG", help="print MODELS entries for en-LANG and LANG-en, then stop")
    args = parser.parse_args()

    if args.suggest:
        suggest(args.suggest)
        return

    licence_body = fetch(LICENSE_URL, LICENSE_SHA256)
    directions = {}
    for name in sorted(MODELS):
        files = model_files(name, licence_body)
        directions[name] = pins(files)
        if args.out and (args.model is None or args.model == name):
            model_id = MODELS[name][0]
            target = Path(args.out) / model_id
            target.mkdir(parents=True, exist_ok=True)
            for file, body in files.items():
                (target / file).write_bytes(body)
            print(f"wrote {len(files)} files to {target}: upload them to https://getknit.app/models/translate/{model_id}/")

    generated = kotlin_file(directions)
    if args.write:
        KOTLIN.write_text(generated)
        print(f"wrote {KOTLIN.relative_to(ROOT)}")
    elif args.check:
        if not KOTLIN.exists() or KOTLIN.read_text() != generated:
            sys.exit(f"{KOTLIN.relative_to(ROOT)} is not what --write would write: run scripts/translate-model.py --write")
        print(f"{KOTLIN.relative_to(ROOT)} matches upstream")
    elif not args.out:
        print(generated, end="")


if __name__ == "__main__":
    main()
