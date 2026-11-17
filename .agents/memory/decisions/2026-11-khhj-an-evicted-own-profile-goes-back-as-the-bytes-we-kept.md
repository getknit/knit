---
id: "2026-11.khhj"
slug: an-evicted-own-profile-goes-back-as-the-bytes-we-kept
title: "an evicted own profile goes back as the bytes we kept, never re-minted"
date: 2026-11-17
topics: [mesh, profiles, custody]
---

# ADR 2026-11.khhj — an evicted own profile goes back as the bytes we kept, never re-minted

Status: Accepted (2026-11-17)

**What was observed.** The mobility simulator (`mesh/sim/ParkWalkSimTest`, 48 walkers, 10 minutes, walk only,
2026-11-16, #335) filled every custody store to its 1,000-row cap within 6 to 9 minutes and lost 150 to 234 DMs for
good: they left every store, often their sender's own first, and never arrived. It read as custody being too small for
a crowd. The counters named something else. Of 96,016 v3 seals, 90,517 were the sealed profile fan-out
(`ctlDmSealedBy[PROFILE_FANOUT]`), after 3,919 republishes, every one counted `profileRepublishedBy[NO_CUSTODY_ROW]` and
none `PRESENTATION_CHANGED`. Nothing in any profile had changed.

The loop: `MeshManager.watchProfileChanges` judges every settings write, to any key (a read-state stamp, a ledger
flush), against the profile frame our own custody held for the current stamp, and since ADR 2026-09.qztx's amendment
a missing row counts as a change. Custody's quotas evict our own frames like anyone's (ADR 006, ADR 2026-10.7mqm). So
once our row was evicted, the next settings write minted a version, flooded it and sealed it to every confirmed session
(the fan-out dedupes on the version). Those frames made our sender bucket the largest everywhere, our profile its oldest
frame, and so the next eviction took the profile again — and the DMs that only custody could still deliver with it.

**What changed.** Our own current profile frame is kept outside custody, byte for byte: `SettingsStore.ownProfileFrame`
(`own_profile_frame_sig`, `own_profile_frame_signed`), written by `signOwnProfileLocked` under `profileLock` beside the
stamp it is keyed by.

- The watcher judges the frame we published (`MeshManager.publishedPresentsOtherThan`): the kept frame when its id is
  the current stamp's, else custody's row (the first start of a build that keeps it), else `NO_CUSTODY_ROW`, which still
  publishes. An eviction is no longer a change; an edit still is, and publishes once with its sealed copies.
- `signOwnProfileLocked`, the one place a profile is signed: custody's row for the current stamp when there is one (kept
  too, when its bytes differ from the kept frame's); else the kept frame when it is the current stamp's, re-custodied
  as it was signed; only else a fresh signing, which is kept. A re-store is not a fresh signing, so the capabilities
  check that makes a fresh signing an edit (ADR 2026-11.264w) does not run on it, as it never ran on a held row.
- The watcher never re-stores. Our custody keeps the same quota rules as everyone else's: the callers that need the
  bytes (the start's seed, the first-contact push, the reachable reflood, the LoRa beacon) re-store them through
  `ownProfile()`, and a full store trims the frame again as the oldest of the largest bucket, as every peer does.

This supersedes qztx's "Not covered" residual. A re-signing after an eviction read the settings as they stood: an
edit still waiting for the watcher, or a prekey or capability the envelope reads, made a second set of bytes under an
id peers already hold — the one-id-two-blobs spool scope ADR 2026-09.y5f3 exists to prevent. The kept bytes cannot
differ.

The alternatives, from the issue:

- **A fingerprint of the presentation**, as `BadgeJournal.publishedFingerprint` keeps one. It stops the mint, but the
  signer would still re-sign an evicted stamp from live settings; with an edit pending it would have to mint the edit
  itself, and the sealed copy would wait for the next heal.
- **Keeping our own profile out of the quotas' reach.** Our store would then differ from every peer's at the cap's
  edge, which the frame-global eviction rule (ADR 006, ADR 2026-10.7mqm) exists to prevent.
- **Skipping only the sealed fan-out on an unchanged presentation.** Every settings write would still flood a
  cleartext frame under a new version.

**What it costs.** About 500 bytes in the settings file, and one settings write per fresh signing. The keys are
backup-transient (`SettingsKeys.TRANSIENT_PREFIXES` lists `own_profile_frame`, never `own_profile`: the carried
`own_profile_capabilities` starts with that): a restored phone's first start mints a fresh stamp in `finishRestore`
before anything reads them. An evicted profile stays out of every full store until a caller re-stores it or the 12 h
republish mints a fresh stamp; a newcomer still learns the key from a carrier's kept signed profile (ADR 2026-09.g64k)
or the first-contact push.

The trap: don't judge an edit by what custody holds, and don't re-sign a stamp whose frame was once signed. A new
reader of our own published profile reads the kept frame.

Kept true by `MeshManagerTest.aSettingsWriteWithOurProfileGoneFromCustodyMintsNothing`,
`anEvictedProfileGoesBackByteForByteThoughThePrekeyMoved`,
`anEvictedProfileGoesBackUnderItsOwnStampThoughTheRecordedCapabilitiesMoved` and
`aCustodiedRowBecomesTheKeptFrameAndItsEvictionMintsNothing` (all four fail on the pre-fix code),
`ProfileUpdateLabTest.anEvictedOwnProfileIsNotRepublishedByASettingsWrite`, `SettingsSnapshotTest`'s
`dropsTheKeptProfileFrameAndCarriesTheProfileCapabilities`, and the simulator's `profileRepublishedBy[NO_CUSTODY_ROW]`,
which should read 0.
