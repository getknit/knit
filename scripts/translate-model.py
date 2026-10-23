#!/usr/bin/env python3
"""Stage the translation spike's models for getknit.app, and check the app's pins against them.

The spike (ADR 2026-10.t2h6) downloads Mozilla's Firefox Translations models for English to Spanish and Spanish to
English file by file from getknit.app and checks every byte against the sizes and SHA-256s pinned in
`TranslateModels.kt` (app/src/main/java/app/getknit/knit/translate/TranslateModels.kt). This script is where those pins
come from, so nobody types a hash by hand:

  scripts/translate-model.py              print the Kotlin pins for the upstream files
  scripts/translate-model.py --check      exit 1 unless TranslateModels.kt pins exactly those files
  scripts/translate-model.py --out DIR    also write the files to DIR/<model id>/, ready to upload under
                                          https://getknit.app/models/translate/ (immutable: a new model is a new id)

Upstream is Mozilla's model registry (db/models.json in the bucket below), whose files live under one training run's
directory and never move. Each is fetched gzipped, checked against the SHA-256 pinned here, and unpacked: the phone
keeps and pins the unpacked bytes. The models are MPL-2.0; the licence comes from Mozilla's models repository, pinned,
and goes beside them, which with an unmodified file and a link to its source is what MPL asks of a redistributor.
Stdlib only.
"""

import argparse
import gzip
import hashlib
import re
import sys
import urllib.request
from pathlib import Path

BUCKET = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data"
LICENSE_URL = (
    "https://raw.githubusercontent.com/mozilla/firefox-translations-models/"
    "e7957fc407441a5e3e35bbcbf9d60d9b35764618/LICENSE"
)
LICENSE_SHA256 = "1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5"
# name: (model id, the training run's export directory, {unpacked file name: SHA-256 of its .gz}). Both are the
# registry's "base-memory" release (31.2M parameters, 4 decoder layers) as of 2026-10-22.
MODELS = {
    "en-es": (
        "mozilla-en-es-3b1c3995",
        "models/en-es/retrain_hr_fix_names_CUAEXUHoQum_cFqh-ZAryw/exported",
        {
            "model.enes.intgemm.alphas.bin": "fe4025fde24a4d5f80533cfb7acd53d0eb7f194dfc2d8f427f0d4563cfb6ac06",
            "lex.50.50.enes.s2t.bin": "37a067966f28ff0ba430fddb7412bc5b07afa2cd2fd5a46d9ad74045a1a06380",
            "vocab.enes.spm": "ae2760c04bc6eb5362b16e5c2f145c376bb04bac421c33ba3c0766805e6f03b3",
        },
    ),
    "es-en": (
        "mozilla-es-en-4aed7734",
        "models/es-en/retrain_hr_HbNjJ60BTwmVTbhfFxuduA/exported",
        {
            "model.esen.intgemm.alphas.bin": "54247f1e057180bca1d404dfd5c12836f65bc96cef7af98e59e55acb8bd093b1",
            "lex.50.50.esen.s2t.bin": "3d7856bfa3b12804bdc80f12fd828fd9062c462113ab73fbf87c5c3b68420813",
            "vocab.esen.spm": "1658b529b8858cf2e1e8cef2c4e2d69654d77b5f714ac52205864b336dfeb3ee",
        },
    ),
}

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "app/src/main/java/app/getknit/knit/translate/TranslateModels.kt"
# ktlint wraps a long pin over several lines, so any whitespace may sit between its parts.
PIN = re.compile(r'SttFile\(\s*"([^"]+)",\s*([0-9_]+),\s*"([0-9a-f]{64})",?\s*\)')


def fetch(url, sha256):
    with urllib.request.urlopen(url, timeout=600) as response:
        body = response.read()
    digest = hashlib.sha256(body).hexdigest()
    if digest != sha256:
        sys.exit(f"{url} has sha256 {digest}, not the pinned {sha256}")
    return body


def model_files(name, licence_body):
    """The files the app fetches, in its order: the model's, then the licence."""
    _, directory, gzipped = MODELS[name]
    out = {}
    for file, sha256 in gzipped.items():
        out[file] = gzip.decompress(fetch(f"{BUCKET}/{directory}/{file}.gz", sha256))
        print(f"  {name}: {file} {len(out[file]):,} B", file=sys.stderr)
    out["LICENSE"] = licence_body
    return out


def pins(files):
    return [(file, len(body), hashlib.sha256(body).hexdigest()) for file, body in files.items()]


def kotlin(pinned):
    return "\n".join(f'SttFile("{n}", {size:_}, "{sha}"),' for n, size, sha in pinned)


def pinned_in_kotlin(source, model_id):
    """The pins TranslateModels.kt gives the model whose id is [model_id]: the SttFile lines after its `id = "…"`."""
    at = source.find(f'id = "{model_id}"')
    if at < 0:
        return None
    following = source.find('id = "', at + 1)
    block = source[at : following if following >= 0 else len(source)]
    return [(n, int(size.replace("_", "")), sha) for n, size, sha in PIN.findall(block)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", choices=sorted(MODELS), help="one direction only (default: both)")
    parser.add_argument("--check", action="store_true", help="fail unless TranslateModels.kt pins these files")
    parser.add_argument("--out", help="write the files under OUT/<model id>/ for upload")
    args = parser.parse_args()

    licence_body = fetch(LICENSE_URL, LICENSE_SHA256)
    for name in [args.model] if args.model else sorted(MODELS):
        model_id = MODELS[name][0]
        files = model_files(name, licence_body)
        pinned = pins(files)
        print(f"// {name}: {model_id}")
        print(kotlin(pinned))

        if args.out:
            target = Path(args.out) / model_id
            target.mkdir(parents=True, exist_ok=True)
            for file, body in files.items():
                (target / file).write_bytes(body)
            print(f"wrote {len(files)} files to {target}: upload them to https://getknit.app/models/translate/{model_id}/")

        if args.check:
            found = pinned_in_kotlin(KOTLIN.read_text(), model_id)
            if found is None:
                sys.exit(f"{KOTLIN.relative_to(ROOT)} does not name the model id {model_id}")
            if found != pinned:
                sys.exit(f"{KOTLIN.relative_to(ROOT)} pins {found} for {model_id}, upstream has {pinned}")
            print(f"TranslateModels.kt pins for {model_id} match upstream")


if __name__ == "__main__":
    main()
