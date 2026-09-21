---
id: "037"
slug: a-bundled-model-that-crashes-the-process-natively-is-latched-off
title: "A bundled model that crashes the process natively is latched off, on evidence rather than on failure"
date: 2026-08-24
topics: [moderation, reliability, ml]
---

# ADR 037 — A bundled model that crashes the process natively is latched off, on evidence rather than on failure

`MlTextModerator` and `NsfwImageModerator` absorb every *Java* failure a TFLite load can produce — two
nested `runCatching` layers, down to `UnsatisfiedLinkError` and `OutOfMemoryError` — and degrade to
allow-all. A **native** crash inside the interpreter cannot be caught at all: it takes the process, and
`CrashHandler` never sees it (ADR 028 says so in its own KDoc). The toxicity warm-up runs from
`KnitApplication.onCreate` on every launch, so on a device where that reproduces the app is in a launch
loop with **no way out**: open, wait five seconds, die, repeat — no crash report, and clearing app data
does not help, because the trigger is the bundled asset plus the device, not stored state. That is
hypothesis 2 of getknit/knit#9 (LineageOS 23.2 / Android 16, "crash ~10 s after opening"); unconfirmed,
but the only startup-path failure that produces exactly that report, and the one the app could not survive.

So: `moderation/ModelLoadGuard` records a marker before the first touch of a model and clears it after,
and a launch that finds the marker still set knows the previous process died in there. A latched model is
simply not loaded, and the moderator degrades to the state it already reaches with missing assets.

**The marker means "the process died in there", not "the load failed".** This is the decision everything
else follows from. The clear-side runs in a `finally` under `NonCancellable`, so a load that returned
nothing, threw, or was cancelled clears it just the same. Without that the mechanism latches itself off
during ordinary use, three ways: a build shipped without the models takes the "no asset" path on *every*
launch; a Java-level load failure is caught and looks identical; and backing out of a chat while the 17 MB
image model loads cancels `viewModelScope` mid-flight. Only the `finally` makes the marker mean one thing.

**The exit record is a false-positive filter first, an accelerator second.** A process death mid-load that
has nothing to do with the model leaves identical evidence. `5da5601` fixed a Java
`ForegroundServiceDidNotStartInTimeException` that fired at ~10 s on slow devices — five seconds *after*
this marker goes down — so a bare counter would have latched the classifier off for a reason twice removed
from moderation. `crash/ProcessExitReasons` reads
`ActivityManager.getHistoricalProcessExitReasons(pkg, 0, 1)` (the follow-on ADR 028 named) and classifies
three ways: a native fault latches on the first strike; an *explained* exit — Java crash, ANR, low memory,
force-stop, package update — is discarded **without counting**; anything else counts toward `MAX_FAILS`.
Reading only the newest record is sound because the app declares no `android:process` anywhere, so it
always describes the process immediately before this one; and `exit.at >= pendingSince` keeps an older,
unrelated crash from being credited to this attempt. API 30+, so on our minSdk 29 it degrades to counting.

**`reason` alone is not enough — `status` decides the signalled case.** `WifiAwareTransport` kills its own
process on a NAN wedge, which surfaces as `REASON_SIGNALED` status 9 (SIGKILL), indistinguishable by
reason from a real SIGSEGV (status 11). Reading the status separates them, and it is the hedge that
matters most here: if a ROM's debuggerd never files the tombstone that produces `REASON_CRASH_NATIVE`, the
fault-signal arm still catches the crash. #9 is a LineageOS build — "the ROM does it differently" is the
premise, not an edge case.

**`MAX_FAILS` is 2.** The asymmetry is lopsided: a wrong latch is visible, resettable, and clears itself on
the next version bump, while a missed one leaves the app unusable. With every ordinary cause already
filtered out, two consecutive *unexplained* deaths inside a sub-three-second window is evidence enough —
and it halves how many times a genuinely affected user watches the app die. It is only ever reached on API
29 or when the platform returns no usable record.

**DataStore, not a `CrashStore`-style file.** `edit {}` writes a scratch sibling, `fsync`s it and renames
before it resumes (`datastore-core` 1.2.1 `FileStorage.kt`; its own `TODO(b/151635324)` notes the
*directory* is unsynced, which would matter for a power cut, not a process death) — so awaiting it before
the load is a real barrier. `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` already
exclude `datastore/` in all three sections, so the backup argument that justified ADR 028's
`noBackupFilesDir` does not apply. Room would engage ADR 008 — a `@Database` bump and a tested migration —
for two Longs and a String. `data/settings/ModelLoadJournal` is the narrow seam (the `InboundSettings`
precedent) that keeps the state machine's test on plain JVM.

**The stamp is version code + OS fingerprint**, and the OS half is the one that earns its place: a ROM
update is exactly the event that might fix a SoC/driver fault, and without it a user who updates
LineageOS stays latched off until Knit ships a new version code. It is passed in as a value, so
`ModelLoadGuard` holds no `android.os.Build` reference.

**Deliberately not done.** No `warmUp()` for `NsfwImageModerator` "for symmetry" — that would move a 17 MB
load onto the launch path, which is precisely what `5da5601` moved off it. No lexical fallback for
`ScopedTextModerator.direct` when latched: profanity-in-private-only-sometimes is a worse product state
than the one being fixed, and it would make moderation policy depend on an invisible flag.

**What it does not claim, stated because the UI must not overclaim either.** It catches the launch-loop
shape — a fault on the *first* touch of a model. A native crash on the five-hundredth inference leaves no
marker and will recur; that is not a launch loop, and latching on it would assert far more than the
evidence supports. And the honest cost of a latch: the Nearby room keeps its word-list pass, but
`ScopedTextModerator.direct` is the bare ML classifier, so **DMs and groups lose text screening entirely**,
and images lose NSFW screening. That is not a new hole — a missing asset already produces it — but it is
now a sticky, user-chosen-recoverable state rather than a transient one, so Diagnostics says which
screening stopped, and the reset dialog says it takes effect on the next start (the moderator latches
`loaded` in memory, so nothing reloads inside a running app). *Amended by ADR 2026-09.cq9z (2026-09-20): a
loaded engine is now released after ten idle minutes and re-acquired through this same guard, so every
load brackets a marker, not only the first; a latched or failed attempt is still permanent for the process,
which is what keeps the reset's "next start" true.*

Verification is a build flag, not a runtime seam: `-PmodelFaultOnLoad=segv|kill` raises the fault inside
the guard, defaults off in build-script source (so F-Droid's `-P`-less rebuild stays byte-identical) and is
forced off in `release`. The two are not interchangeable, and not in the way first assumed: only `segv`
produces the native-crash evidence that latches, while `kill` is the **negative control** — on API 30+ a
SIGKILL is classified *explained*, so it must never latch however often it happens. The counting arm is
only reachable where the platform returns no usable record at all (API 29, or an unclassified reason).
`…debug.MODEL` dumps the journal *and* the platform's exit record, which is where you find out what the
target ROM actually reports.

Verified on a Pixel 9 Pro XL (Android 17 / SDK 37), which is where the `status` decision earned itself.
A healthy launch completes and closes its marker (`pendingSince:0, fails:0`). `-PmodelFaultOnLoad=segv`
produces `reason=5 (APP CRASH(NATIVE)) status=11` — *both* arms match on this ROM — and the very next
launch comes up latched, alive, with the fault still armed, because a latched model is never loaded and
the injection is never reached. `-PmodelFaultOnLoad=kill` produces `reason=2 (SIGNALED) status=9` three
times running with `fails` staying 0: had `REASON_SIGNALED` alone been read as a native fault, three
ordinary kills would have disabled moderation on a phone with nothing wrong with it. With the model
latched, a word-list hit in the Nearby room is still blocked (nothing stored or transmitted), and the
Diagnostics row appears under "Problem reports" **with no crash row above it** — the pairing a
`lastCrash`-keyed section header would have hidden — and disappears live when the reset is confirmed.

Tests: `ModelLoadPolicyTest` (the decision table, pure JVM), `ModelLoadGuardTest` (write-before-load
ordering, and that a no-asset / throwing / cancelled load all clear the marker), `SettingsStoreTest`
(per-model round-trip), `MlTextModeratorWarmUpTest` (latched ⇒ allow-all, loader untouched),
`DiagnosticsScreenContentTest` (the latch row renders **with no crash report**, the pairing that a
`lastCrash`-keyed section header would have hidden). Docs: `docs/CONTENT_MODERATION.md` §8.
