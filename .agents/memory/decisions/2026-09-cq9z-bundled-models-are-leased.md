---
id: "2026-09.cq9z"
slug: bundled-models-are-leased
title: "Bundled models are leased, not held resident"
date: 2026-09-20
topics: [moderation, ml, battery, reliability]
---

# ADR 2026-09.cq9z — Bundled models are leased, not held resident

Status: Accepted (2026-09-20; `moderation/ModelLease`, `TfLiteModels`, `ModelLease.DEFAULT_IDLE_MS` = 10 min,
`TfLiteModels.INFERENCE_THREADS` = 2). Work item #68, the half of the 2026-09-18 battery review that
`6c3d95d8` (the warm-up move) left.

**What was observed.** Both on-device classifiers built a bare `Interpreter(model)` with default options over
`assets.open(asset).readBytes()` copied into `ByteBuffer.allocateDirect`, and never closed it: about 32 MB
resident in the foreground-service process for as long as it lived, for a text model that runs once per
message and an image model that runs once per photo. Worse than it reads, because on ART a direct buffer
is a `byte[]` on the **Java heap** — the 14.6 MB and 17.4 MB flatbuffers were charged to the heap, and
`readBytes()` held a second copy while each filled, a ~30 MB spike per model against the Dalvik limit. A
low-memory kill then costs a service restart, which re-runs the start-time orphan GC
(`MeshManager.resumePendingFetches`) and the SQLCipher key derivation.

**What changed.**

1. **The engine is leased.** `ModelLease<T>` owns what both moderators duplicated — the mutex, the
   attempted flag, the nullable engine — and adds a reaper on the app scope that closes the interpreter
   after ten minutes without a use and drops the lease back to unloaded, so the next classify reloads.
   Three states: *unloaded*, *resident*, and *spent* (a load that returned nothing: a missing asset, a Java
   failure, a model ADR 037 has latched off). Spent is permanent for the process and arms no reaper, which
   is how the poison-pill survives an unload: only a real engine is ever released, and the reload runs the
   same guarded `buildEngine()`, so the journal decides again. Every load now brackets a marker, not only
   the first in a process; a reload that faults natively latches exactly as a first load does.
2. **The model is mapped, not copied.** `TfLiteModels.mapAsset` is the `FileUtil.loadMappedFile` idiom
   without the `tensorflow-lite-support` dependency (a new shipped artifact is notices rows, a lockfile regen
   and an F-Droid scanner re-check under ADR 2026-09.6eb6, for one function): `openFd` + `FileChannel.map`.
   It works because `noCompress "tflite"` keeps both entries **Stored** in the APK — that line is now
   load-bearing, and a compressed asset degrades to allow-all through the same `FileNotFoundException`
   catch as a missing one. TFLite reads the flatbuffer in place, so the mapping lives in `Engine` /
   `Loaded` beside the interpreter. The Java-heap half and the spike are gone regardless of the lease; the
   pages are file-backed and clean, so the kernel can drop them under pressure without killing anyone.
   The tokenizer's 1.9 MB JSON is deflated and stays `readBytes()` (it is parsed into objects anyway), and
   is unloaded with the engine — a reload re-parses it.
3. **Options are explicit.** `setNumThreads(2)` sizes both the interpreter and XNNPACK's pool: two halves
   the wall time of an ALBERT pass over 128 tokens for about the same energy, and more only makes the
   slowest core the wall time on a big.LITTLE part. `setUseXNNPACK(true)` is most likely the runtime's own
   Android default, so it is documentation more than behaviour — but it can change the kernel path on an
   unfamiliar SoC, which is the fault ADR 037 exists for, and the version-code half of its stamp gives every
   device a fresh attempt on the release that ships this.
4. **Two holes the reload would have made chronic, closed.** `classify` set `loaded = true` *before*
   `buildEngine()`, which suspends inside the guard's two DataStore round trips; a caller cancelled there (Back
   out of a chat, a link-preview fetch timing out) left screening silently off for the rest of the process —
   once per process before, once per reload after. The lease sets the flag only after the load returns, so
   a cancellation leaves the next caller free to try. And the guard wrote `pendingSince` *outside* its
   `try/finally`: a cancellation after that write committed left the marker standing, and the next load —
   now in the same process — read its own leftover as an unexplained death and counted it; two would have
   latched a healthy phone with no process death at all. The write moved inside the `try`.

**What the alternative was.** Gating the release on "no peer nearby" would never stall the inbound path
while a clique is linked — and would never release in the lab, which is always linked, so the battery night
could not measure the win. Rejected with the user (2026-09-20): the unload is by idle alone, and the
first-peer warm-up became edge-triggered (`MeshService.warmModelOnFirstPeer` fires on every none→some
transition of `neighborCount`) so a phone that let go while alone is warm when company arrives. A longer
window (30 min) was offered and declined for the same reason.

**What it costs.** A message after a quiet stretch pays the map + build + first inference again — on the
send path the ON_RESUME warm-up hides it, on the inbound path it is inline in the router's single
collector (`MeshManager.isTextFlagged`), stalling both radios for about a second once per idle cycle where
it used to be once per process. A peer that stays through the release is not an edge, so that message
reloads inline. The composer's 5 s card hold (ADR 2026-09.7x8k) can now lose a card to a cold image reload
on a link that was already slow; the message sends card-less, as it does for any slow link. Nothing waits
on warmth: the image screen runs inside the 30 s fetch budget. A `MappedByteBuffer` cannot be unmapped from
Java; after `close()` the mapping goes when the buffer is collected, and its pages were never dirty.

**The trap.** `Interpreter` is not thread-safe and `close()` frees the native handles, so the reaper closes
**under the lease's mutex** — never call `close()` from anywhere else. The reaper sleeps on `delay` and
re-checks the wall clock, clamped to the idle window so a clock stepped backwards cannot park it for hours;
in a test the clock must be the scheduler's (`{ testScheduler.currentTime }`) or it re-sleeps forever, and
the lease must ride `backgroundScope` or `runTest` waits ten minutes. A test that builds a moderator on
`runBlocking`'s scope does the same on a device (`ToxicityInstrumentedTest` gives it its own scope).
`ModelLoadGuard.clear()` still "takes effect on the next start": a latched model was never loaded, so its
lease is spent, and spent never reloads.

Pinned by `ModelLeaseTest` (the state machine on virtual time, both cancellation shapes, the reload through
a real guard, a latched model across the window), `ModelLoadGuardTest` (the cancelled pending write),
`MlTextModeratorWarmUpTest` (a failed load is not retried after the window), and on hardware by
`ToxicityInstrumentedTest` / `NsfwInstrumentedTest` (map → classify → `unload()` → reload, same verdict).
`…debug.MODEL` reports `resident` and `lastUsedAt` per model and takes `--ez unload true`. Amends ADR 037's
"nothing reloads inside a running app".

**Device-verified 2026-09-20 on the Pixel 3 (arm64, API 31, live on the lab mesh with three neighbours).**
Text model resident after the ON_RESUME warm-up: Native Heap 106 MB, PSS 320 MB; both resident after a
photo through `…debug.SENDIMG`: 137 MB / 371 MB; after `--ez unload true`: 20.6 MB / 253 MB — about 117 MB
of native heap back, most of it XNNPACK's packed weights rather than the 32 MB the flatbuffers alone
suggested (`.apk mmap` keeps the clean mapped pages until GC). A text send then reloaded through the guard
(`pendingSince` 0, `fails` 0, a real score logged). Left alone, the reaper released the text model on its
own at t+10:01 after the last classify (polled once a minute; `lastUsedAt` never moved), Native Heap 17 MB,
PSS 197 MB. The same run on an x86_64 API 34 AVD: 154 → 30 MB native. ADR 037's acceptance re-run on the
AVD after the try-block move: `segv` latches on the next launch, `kill` three times never does.
