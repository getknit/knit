#!/usr/bin/env python3
"""Run the translation spike's bench on phones and put the numbers side by side (ADR 2026-10.t2h6).

For each device: push the corpus (scripts/translate-corpus/), side-load the models if --stage is given (what
`scripts/translate-model.py --out` wrote), run the debug bridge's `TRANSLATE bench` both ways, pull each direction's
JSON lines, and print one row per device and direction: the cold load, p50/p95 by message length, the memory one
loaded direction holds, and how many lines the identifier was unsure of, lost a protected span or came out looping.

With --desktop, the same corpus also goes through the knit-translate-native repository's desktop bench (the same
native core; its README says how to build it), found through --native (default: a knit-translate-native checkout
beside this one), and the quality sheet puts each phone's translation beside the desktop's, flagging any line where
they differ. It is how a bump of that library is checked against the version before it.

  scripts/translate-bench.py --serial pixel-9-pro-xl.home:5555 --stage /tmp/stage --out /tmp/bench --desktop

Obey .agents/rules/devices.md before pointing this at a physical phone. Stdlib only.
"""

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BRIDGE = ROOT / "scripts/bridge.sh"
CORPUS = ROOT / "scripts/translate-corpus"
NATIVE = ROOT.parent / "knit-translate-native"
BENCH = "translate-native/build/desktop/knit-translate-bench"
REMOTE = "/data/local/tmp/translate"
PACKAGE = "app.getknit.knit"
# model id and file stem per direction, keyed "<from>-<to>": the pins' own (translate-model.py MODELS). Each runs the
# corpus of its source language, scripts/translate-corpus/<from>.txt.
DIRECTIONS = {
    "es-en": ("mozilla-es-en-4aed7734", "esen"),
    "en-es": ("mozilla-en-es-3b1c3995", "enes"),
}
POLL_S = 2
BENCH_TIMEOUT_S = 1800


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, check=True).stdout


def bridge(serial, *extras):
    out = subprocess.run([str(BRIDGE), serial, "TRANSLATE", *extras], capture_output=True, text=True, check=False)
    if out.returncode != 0:
        sys.exit(f"{serial}: bridge failed: {out.stderr.strip()}")
    return json.loads(out.stdout)


def sideload(serial, stage):
    adb(serial, "shell", "mkdir", "-p", REMOTE)
    adb(serial, "push", f"{stage}/.", f"{REMOTE}/")
    adb(serial, "shell", "chmod", "-R", "a+rX", REMOTE)
    bridge(serial, "--es", "op", "sideload", "--es", "dir", REMOTE)
    deadline = time.time() + 300
    while time.time() < deadline:
        models = bridge(serial)["models"]
        if all(m["state"] == "ready" for m in models.values()):
            return
        if any(m["state"] == "failed" for m in models.values()):
            sys.exit(f"{serial}: side-load failed: {models}")
        time.sleep(POLL_S)
    sys.exit(f"{serial}: side-load did not finish")


def bench(serial, pair, runs):
    source, target = pair.split("-")
    reply = bridge(serial, "--es", "op", "bench", "--es", "file", f"{REMOTE}/corpus/{source}.txt", "--es", "from", source,
                   "--es", "to", target, "--ei", "runs", str(runs))
    if reply.get("status") != "ok":
        sys.exit(f"{serial}: bench refused: {reply}")
    remote_out = reply["out"]
    deadline = time.time() + BENCH_TIMEOUT_S
    while time.time() < deadline:
        state = bridge(serial).get("bench") or {}
        if state.get("status") == "done":
            return state, remote_out
        if state.get("status") == "error":
            sys.exit(f"{serial}: bench failed: {state}")
        time.sleep(POLL_S)
    sys.exit(f"{serial}: bench did not finish")


def desktop(bench, stage, pair):
    model_id, stem = DIRECTIONS[pair]
    source = pair.split("-")[0]
    d = Path(stage) / model_id
    out = subprocess.run(
        [str(bench), "translate", str(d / f"model.{stem}.intgemm.alphas.bin"), str(d / f"vocab.{stem}.spm"),
         str(d / f"lex.50.50.{stem}.s2t.bin"), "4", str(CORPUS / f"{source}.txt")],
        capture_output=True, text=True, check=True,
    ).stdout
    return {int(n): text for n, _, text in (line.split("\t", 2) for line in out.splitlines())}


def row(device, summary):
    ms = summary["ms"]
    cell = lambda b: f"{ms[b]['p50']}/{ms[b]['p95']}" if b in ms else "-"
    return (f"| {device} | {summary['direction']} | {summary['loadMs']} | {cell('short')} | {cell('medium')} | "
            f"{cell('long')} | {summary['nativeHeapDeltaKb'] // 1024} | {summary['pssDeltaKb'] // 1024} | "
            f"{summary['unsure']}/{summary['lines']} | {summary['spansLost']} | {summary['unusable']} |")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", action="append", required=True, help="adb serial (repeat for several devices)")
    parser.add_argument("--stage", help="the models staged by translate-model.py --out, to side-load first")
    parser.add_argument("--runs", type=int, default=1, help="times through the corpus (timings only count them all)")
    parser.add_argument("--out", required=True, help="where the pulled JSON lines and the quality sheet go")
    parser.add_argument("--desktop", action="store_true", help="compare with knit-translate-native's desktop bench")
    parser.add_argument("--native", default=str(NATIVE), help="the knit-translate-native checkout --desktop uses")
    args = parser.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    reference = {}
    if args.desktop:
        bench = Path(args.native) / BENCH
        if not args.stage or not bench.exists():
            sys.exit(f"--desktop needs --stage and a built {bench}")
        reference = {pair: desktop(bench, args.stage, pair) for pair in DIRECTIONS}

    table = [
        (
            "| device | direction | load ms | short p50/p95 ms | medium p50/p95 ms | long p50/p95 ms | heap MB | PSS MB "
            "| unsure | spans lost | looping |"
        ),
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    sheet = []
    for serial in args.serial:
        model = adb(serial, "shell", "getprop", "ro.product.model").strip()
        adb(serial, "shell", "mkdir", "-p", f"{REMOTE}/corpus")
        adb(serial, "push", f"{CORPUS}/.", f"{REMOTE}/corpus/")
        adb(serial, "shell", "chmod", "-R", "a+rX", REMOTE)
        if args.stage:
            sideload(serial, args.stage)
        for pair in DIRECTIONS:
            source = pair.split("-")[0]
            summary, remote_out = bench(serial, pair, args.runs)
            local = out / f"{model.replace(' ', '_')}-{pair}.jsonl"
            adb(serial, "pull", remote_out, str(local))
            table.append(row(model, summary))
            corpus = [line for line in (CORPUS / f"{source}.txt").read_text().splitlines() if line.strip()]
            for line in local.read_text().splitlines():
                r = json.loads(line)
                ref = reference.get(pair, {}).get(r["n"])
                sheet.append({
                    "device": model, "from": source, "to": pair.split("-")[1], "n": r["n"], "ms": r["ms"], "source": corpus[r["n"] - 1],
                    "phone": r["out"], "desktop": ref, "same": ref is None or ref == r["out"], "pick": r["pick"],
                    "spansKept": r["spansKept"], "usable": r["usable"],
                })
    (out / "quality.json").write_text(json.dumps(sheet, ensure_ascii=False, indent=1))
    differ = [s for s in sheet if not s["same"]]
    print("\n".join(table))
    print(f"\n{len(sheet)} lines in {out / 'quality.json'}; {len(differ)} differ from the desktop reference")


if __name__ == "__main__":
    main()
