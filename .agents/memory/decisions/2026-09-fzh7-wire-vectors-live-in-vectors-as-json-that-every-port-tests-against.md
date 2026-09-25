---
id: "2026-09.fzh7"
slug: wire-vectors-live-in-vectors-as-json-that-every-port-tests-against
title: "wire vectors live in vectors/ as JSON that every port tests against"
date: 2026-09-25
topics: [wire, testing, ios]
---

# ADR 2026-09.fzh7 — wire vectors live in vectors/ as JSON that every port tests against

Status: Accepted (2026-09-25)

**What was observed.** `GoldenVectorTest` pinned 57 codec fixtures as a hex map inline in Kotlin, and nothing
pinned a keyed output: no signature over known keys, no sealed DM, no safety number. The iOS port
(`knit-ios`) could only copy the fixtures by parsing the Kotlin source with a script, and it had no Kotlin
answer to check its safety number against. That mattered, because `SafetyNumber` joins its two halves with
a raw NUL byte that most editors show as nothing. Nothing ran the other direction either: no test here would
notice an iOS frame that Tink cannot verify or kotlinx re-encodes differently.

**What changed.** The expected bytes moved to JSON in a repo-root `vectors/` directory. `GoldenVectorTest`
builds its fixtures as before and compares them with `vectors/wire-v1.json`. `KeyedVectorTest` pins two
identities from fixed keys, the profile and room post Alice signs (Tink's Ed25519 is deterministic, so they
are re-signed on every run), a v1 DM she sealed to Bob once, their safety number, and the HELLO, DIGEST,
custody fingerprint and advert. `IosEmittedVectorTest` checks `vectors/ios-emitted-v1.json`, which knit-ios
writes: each frame must verify with Tink, re-encode with kotlinx to the bytes that were signed, and pin or
open like an Android frame. `KNIT_WRITE_VECTORS=1` rewrites the files this repo owns, and the diff shows
every byte that moved. Keeping the Kotlin maps and having each port parse them was the obvious alternative;
it ties every other port to Kotlin's syntax and gives it no way to hand frames back.

**What it costs.** A wire change now edits JSON through write mode rather than pasting hex, and a moved
vector fails the iOS port the same day, which is the point. The pinned DM is one Alice sealed once, because
v1 draws a fresh key, nonce and ephemeral per message; write mode keeps it while it still opens. The private
keys in `keyed-v1.json` are test keys and nothing else. `GoldenVectorTest` fails when the file names a
fixture it does not build or misses one it does, and `vectors/README.md` says who writes each file.
