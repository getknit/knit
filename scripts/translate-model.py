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
# Each architecture's decoder depth, which slimt must be told at load (it cannot read it from the file; told the wrong
# count, it loads and writes nonsense). Every one has six encoder layers (TranslateDirection.ENCODER_LAYERS). The
# weights name their own layers (`decoder_l2_…`), and model_files() refuses a model whose names disagree with this.
DECODER_LAYERS = {"base-memory": 4, "tiny": 2}
ENCODER_LAYERS = 6
# name: (model id, the training run's export directory, architecture, {unpacked file name: SHA-256 of its .gz}). Each is
# the registry's release for its direction: "base-memory" (31.2M parameters) for all but uk-en, whose only release is
# "tiny" (as of 2026-10-31). Portuguese is one model, "pt", for Brazil and Portugal alike. A new language: --suggest,
# then paste.
MODELS = {
    "de-en": (
        "mozilla-de-en-3e6f7c2c",
        "models/de-en/retrain_hr2_WZOW24i4QJmIib61LeHfwg/exported",
        "base-memory",
        {
            "model.deen.intgemm.alphas.bin": "38b245bcfc23f6d61fd98a187436ea1320a38f41896df411b103df3e83f51681",
            "lex.50.50.deen.s2t.bin": "231e9caf62e0e867775d5f4c1f4cfae21e37c70d9bf2bd628fa468aeeb2ba869",
            "vocab.deen.spm": "88dcd7fdbf20dc60837ce837244d0d55b20f7079f2cbd2ba8e63530632028310",
        },
    ),
    "en-de": (
        "mozilla-en-de-8df29d94",
        "models/en-de/retrain_hr_fix_names_SCgGhxUPQ2WAECHLRtzrMg/exported",
        "base-memory",
        {
            "model.ende.intgemm.alphas.bin": "e3a74458faba3ab2752ed8355c5e3979f13395279c8fc1bd3f54a3c2316e4975",
            "lex.50.50.ende.s2t.bin": "0370d79cda6fed5dfa8f9b2a0009459bd2766893825bb5b8b5114169d18da6c4",
            "vocab.ende.spm": "3b2ddbfac73f994f151a8994447d6b3d41ab0d3435da56f79c233f34d03e45c2",
        },
    ),
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
    "en-fr": (
        "mozilla-en-fr-6322e296",
        "models/en-fr/retrain_hr_NLIxDbE1TBGyOTI-zwZagw/exported",
        "base-memory",
        {
            "model.enfr.intgemm.alphas.bin": "22cdaf3dc9a1da6e5818b5576737182467f3cd97e6fd6f94e4273eb7b56d1e3f",
            "lex.50.50.enfr.s2t.bin": "055987f0b585b61c4914fb4469e2392fda0b25d3f7784275974f920e7b631997",
            "vocab.enfr.spm": "5dcadaad8191fb4eb7677bce07098a20221dec95976a4db796f3a17cc82b4541",
        },
    ),
    "en-pt": (
        "mozilla-en-pt-07892fd2",
        "models/en-pt/retrain_hr_fix_names_Vnb0RUXTTd67hR-oLHM3eg/exported",
        "base-memory",
        {
            "model.enpt.intgemm.alphas.bin": "afb5ae08a2e40b622cd191584cca1df00721fea43de85cec74191fc180370f08",
            "lex.50.50.enpt.s2t.bin": "265cff4ca31d3fc95628181de98cf668868b44903d69680284dc456aa4191545",
            "vocab.enpt.spm": "c2fbaf26f10563c77fa95cca465ad3666f61dd08bfaef5bb170abd582901f389",
        },
    ),
    "en-uk": (
        "mozilla-en-uk-ed8bb4a8",
        "models/en-uk/docmt-vocab_a2OVwdI-Sg-SBZ_0Joqb0w/exported",
        "base-memory",
        {
            "model.enuk.intgemm.alphas.bin": "a832a434ce97764c285dd9f7c193d0c283b80a61bf93fbafdcb8a6bd02273e12",
            "lex.50.50.enuk.s2t.bin": "0c368488f43c5165c341b5112a02e42a1fabcc7bd0103d56262da025a9b3196f",
            "vocab.enuk.spm": "3e8e291d2c09f9ef98bace26e8092269807bbdad69f367d9a11b5c7c2cc009bb",
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
    "fr-en": (
        "mozilla-fr-en-15f997bc",
        "models/fr-en/retrain_hr_EFgIftH_RrCyzl5gjemVNg/exported",
        "base-memory",
        {
            "model.fren.intgemm.alphas.bin": "06b1eeedd3944260d00a393d12ec7192490c5b7c930b97212faf4d5f5c90a2ee",
            "lex.50.50.fren.s2t.bin": "395aa7767220e1bcfc085f2b2787ff98005d0075e55451f1e0832885e3d9642a",
            "vocab.fren.spm": "8d15b219ffd32327b4cabf0d94a05a4fecb8923a4f386badbcbe5e86ede453a7",
        },
    ),
    "pt-en": (
        "mozilla-pt-en-7b854f1e",
        "models/pt-en/retrain_hr_drxrs5bGSsOWvfK9lyZISw/exported",
        "base-memory",
        {
            "model.pten.intgemm.alphas.bin": "f2d2bb46d452cef561d35504bd198fa6c6ffc47187ee987406a2ac05d9c3935f",
            "lex.50.50.pten.s2t.bin": "b31185389533a97177cbf68ea756b78bfbaa24d31a865d72c6ed203fa414b2f6",
            "vocab.pten.spm": "28c9eb4fb13f8eb661f6bb098560a1bf368d25ff890275028ea9d4f3f97b0416",
        },
    ),
    "uk-en": (
        "mozilla-uk-en-2e7b8bc9",
        "models/uk-en/spring-2024_WEPBsc-zQWSDn34b5KFscw/exported",
        "tiny",
        {
            "model.uken.intgemm.alphas.bin": "7cbdd1d664b22e757320ba47f79c7f658d3a581383e1f73868fce83b3fcad3d1",
            "lex.50.50.uken.s2t.bin": "86af22d2c43268f87fc5b5ba32485d25defceba5ed38195b27e6c25c44c07ab9",
            "vocab.uken.spm": "7dbbba94effd24a118ade251686c0cea991217c29123362b36f56ea5f2de6362",
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


def layers(weights, side):
    """How many `<side>_l<N>_` layers a Marian model's tensor names count: its depth, read from the weights."""
    return len(set(re.findall(rb"\b" + side.encode() + rb"_l(\d+)_", weights)))


def check_depth(name, architecture, weights):
    """Exit unless the weights have the encoder and decoder depths the app will tell slimt for [architecture]."""
    expected = DECODER_LAYERS.get(architecture)
    if expected is None:
        sys.exit(f"{name}: no decoder depth known for architecture {architecture!r}; bench it, then add it to DECODER_LAYERS")
    encoder, decoder = layers(weights, "encoder"), layers(weights, "decoder")
    if (encoder, decoder) != (ENCODER_LAYERS, expected):
        sys.exit(f"{name}: the weights have {encoder} encoder and {decoder} decoder layers, not the {ENCODER_LAYERS} and "
                 f"{expected} that {architecture!r} tells slimt")


def model_files(name, licence_body):
    """The files the app fetches, in its order: the model's, then the licence."""
    _, directory, architecture, gzipped = MODELS[name]
    out = {}
    for file, sha256 in gzipped.items():
        out[file] = gzip.decompress(fetch(f"{BUCKET}/{directory}/{file}.gz", sha256))
        print(f"  {name}: {file} {len(out[file]):,} B", file=sys.stderr)
        if file.startswith("model."):
            check_depth(name, architecture, out[file])
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
        for other in entries:
            if other is not entry:
                print(f"# {name}: skipped {other['architecture']}, {other.get('releaseStatus')}, "
                      f"comet22 {other.get('metrics', {}).get('flores200-plus', {}).get('comet22')}")
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
