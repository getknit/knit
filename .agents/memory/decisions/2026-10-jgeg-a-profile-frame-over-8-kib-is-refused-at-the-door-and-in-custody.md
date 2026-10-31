---
id: "2026-10.jgeg"
slug: a-profile-frame-over-8-kib-is-refused-at-the-door-and-in-custody
title: "A profile frame over 8 KiB is refused at the door and in custody"
date: 2026-10-30
topics: [custody, security]
---

# ADR 2026-10.jgeg — A profile frame over 8 KiB is refused at the door and in custody

Status: Accepted (2026-10-30). Amends ADR 2026-10.mqzv (a tighter cap for one type, beside the byte cap), ADR
2026-09.7bu7 (the keyreq answer a profile rides) and ADR 2026-09.g64k (the kept proof of key). #234. A paired change:
knit-ios mirrors it in the same release (knit-ios #22); the constants are shared law.

**What was observed.** A code audit, not the field. An identity costs nothing to mint, and each one could make a phone
keep a pin, a peer row, a stored proof of key of up to the 512 KiB the radios accept (60 KiB in custody since ADR
2026-10.mqzv), a KeyExchange cache entry, and a profile photo of up to 8 MiB + 64 KiB, the attachment ceiling. The
largest honest profile is about a kilobyte, and every honest photo is a 256 × 256 JPEG.

**What changed.**

- **The cap.** A frame of type `profile` whose `signed` is longer than `DEFAULT_MAX_PROFILE_SIGNED_BYTES = 8_192` is
  refused; one of exactly the cap is kept. Only `signed` is counted, as for the byte cap (ADR 005: `sig` is a fixed
  64 bytes in production and empty in the rigs). The value lives in `ForwardRepository`'s companion and its
  `maxProfileSignedBytes` parameter, exposed as `ForwardStore.maxProfileSignedBytes`, and `MeshManager` hands that
  same value to the door, so a rig that lowers one lowers both.
- **The door.** The first statement of `InboundPipeline.verifyInbound` refuses an over-cap profile, before
  `verifierBundle` decodes the payload, before custody (`forwardSync.onSeen`), the fast and long-range fan-outs and
  `dispatchByType`. So the frame is never decoded, verified, custodied, pinned, cached or applied: no peer row, no
  `peer_profiles` row, no KeyExchange entry, no photo want. A profile is already outside the key-request and park sets,
  and the refusal returns before the missing-key branch, so it is not counted `NO_SENDER_KEY` either. Every source goes
  through it: link frames, the `relay = false` keyreq and digest key-ahead answers, and the spool
  (`ScopeSync.acceptClaimed` → `canCarry` → `MeshRouter.handleInbound`). It is not in `canCarry`, whose refusal
  quarantines a spool id; a spool profile over the cap is decoded there once and refused here. The sealed
  `CTL_PROFILE` never pins or caches, and custody's 60 KiB cap bounds it.
- **Custody refuses the same frames** right after the byte cap in `ForwardRepository.store`, on every origin, our own
  included: no write, no digest change, no eviction. That covers what never crosses the door (our own profile, rows
  from before the upgrade, the vector rig). A frame over both caps counts as the byte cap's, and the profile counter
  does not move: each refusal is a function of the frame alone, so the order only decides which reason is counted.
- **The sweeps delete what older builds kept.** `sweepExpired` deletes live rows of type `profile` whose `signed` is
  longer than the cap, beside the byte-cap delete, then rebuilds the digest; a function of the row alone, so every
  upgraded node deletes the same rows (the exception to the sweep being digest-neutral that ADR 2026-10.mqzv already
  takes). `sweepLocalStorage` deletes over-cap `peer_profiles` rows (`PeerRepository.deleteOversizeProfileFrames`):
  `KeyExchange` serves a kept proof to every key request, and the door would refuse it. The pin stays, as a contact
  card's does without a proof.
- **One counter.** `profileOversizeRefused` (`MeshMetrics`, the debug bridge's `metrics`), from the door and from
  custody; a door refusal never reaches custody, so each frame counts once. The log line names the frame id's first
  eight characters and the byte count, never the payload.
- **The photo ceiling.** `FileIntake.MAX_AVATAR_BYTES = 1_048_576`, knit-ios's `maxAvatarFileBytes`. A profile photo
  over it is never stored as a peer's face:
  - a pushed avatar is skipped at a header that declares more and aborted once its bytes pass it, keeping nothing;
  - a fetch made for a profile photo (`pullRelayAvatarIfNeeded`, from the cleartext and the sealed profile) carries the
    ceiling down to the link's intake. Neither signal that a photo is over it is authenticated (a `blobhas` size is a
    claim, and streamed bytes are hash-checked only at the end), so the refusal is keyed by holder. A holder that
    streams past the ceiling, or answers a size over it, is discarded and marked for that hash for the session: passed
    over like a failed holder and never asked for it again, by the re-ask round, the periodic re-want or a new want.
    The want goes on to the others, and ends like any want once no unmarked holder is left. Only when the signal comes
    from the want's author, the peer whose photo it is, is the hash refused as a profile photo for the session: the want
    ends and the asks sent to the others are cancelled. A hash also wanted as an attachment keeps the attachment
    ceiling. The receiver cannot stop a feed that is already running (a `blobcancel` drops only a queued copy, ADR
    2026-10.nsr7, and the stream cut is not built, #197), so those bytes cross and are discarded;
  - adoption is the backstop: `adoptAdvertisedAvatar` and the profile writers' `avatarAdoptable` refuse a held blob over
    the ceiling and release it.

  - a face a build before the ceiling adopted is cleared by `sweepLocalStorage` (`PeerRepository.clearAvatarsLongerThan`,
    one join on the held blob's length), its blob released and the hash refused for the session; the peer shows its
    monogram until it advertises another photo. knit-ios does the same (its ADR 2026-10.r88m).

  Each refusal counts `photosOverCeiling` (knit-ios's name): a holder passed over, a pushed avatar that reached the
  pipeline, a held copy refused before adoption, a face the sweep cleared. A pushed avatar the link aborts mid-stream
  never reaches the pipeline and is not counted. Group photos and attachments keep their ceilings.
- **The KeyExchange cache is bounded**: an LRU of about 1,024 entries, dropped with the peer `sweepCap` evicts, and it
  never caches or serves a profile over the cap. A miss refills from the pin store.
- **Startup collects blobs after the local sweep**, so a peer the sweep evicts has its photo collected on the same
  start.

**Why these numbers.** 8,192 is about 8× the largest honest profile, and 1 MiB is far above an honest photo:

| Built by the real path | Bytes | Limit |
| --- | ---: | ---: |
| cleartext profile, every field at its clamp (`CustodyHeadroomLabTest`) | 1,017 | 8,192 |
| sealed profile, four badges (a `CTL_PROFILE` DM, so the byte cap's) | 1,547 | 61,440 |
| knit-ios's largest profile | 949 | 8,192 |
| own photo, 1280² noise through `AvatarStore` (256 × 256, quality 90; `AvatarStoreSizeTest`) | 54,775 | 1,048,576 |

The cap equals `FastFrameCodec.MAX_INFLATED_BYTES` and `FrameTranscoder.MAX_BYTES`, so a profile over it could not
cross the fast or LoRa planes anyway, and it sits well below the 61,440 byte cap.

**What it costs.** None of these touches an honest frame or photo:

- Until a node has the cap, it custodies over-cap profiles and re-serves them at each digest exchange, and an upgraded
  node refuses them each time until their 24 h TTL: the window the byte cap and the roster gate had.
- In a mixed fleet an old node pins a sender whose profile is between 8 KiB and 512 KiB, and an upgraded one does not.
- A sender with no profile under the cap stays unpinned on upgraded nodes, so its other frames go uncarried, and each
  can trigger a key request whose answer is the same refused profile. The KeyExchange cooldown and the answer budgets
  bound that repeat.
- Over-cap profiles are still relayed, like any frame the door refuses: both routers relay after delivery, whatever it
  decided. Gating relay is knit-ios #24.
- A pin taken before the upgrade from an over-cap profile stays; only its stored proof goes.
- A want still asks a neighbour that lacks the photo to pull it through, and the `blobreq` cannot carry the ceiling
  (its fields are frozen), so that neighbour fetches the photo whole before it reaches us and we abort. That cost
  predates this change.
- A holder that lies about a photo's size, or streams junk past the ceiling, loses only its own turn for that photo
  until the next start; an honest holder still serves it. Only the photo's author can refuse its own photo.

Not covered, each its own change: blocking reaching presentation (knit-ios ADR 2026-10.5wgg keeps profiles and photo
fetches of a blocked sender on purpose), receive-time stamps for peer eviction (a new column), a cap on knit-ios's
unaccepted peers, and the photo want map's eviction order (#221).

**What keeps it true.** `vectors/custody-v1.json` (`limits.maxProfileSignedBytes` 1,024 in the rig, three appended
steps) and `vectors/keyed-v1.json` (`aliceProfileAtCap` and `aliceProfileOverCap`, 8,192 and 8,193 bytes of `signed`)
pin the rule for both ports. `InboundPipelineTest` replays the keyed frames through the door, `ForwardRepositoryTest`
pins the constant, the refusal and the sweep, `FileIntakeTest` and the `BlobExchange` tests the ceiling and the
once-per-holder fetch, `MaliciousForkLabTest` a mixed flood end to end, and `CustodyHeadroomLabTest` fails if an
honest profile or photo grows past its limit. The constants are shared law with knit-ios: changing one is a paired
change (`.agents/context/store-and-forward.md`).
