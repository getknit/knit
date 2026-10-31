---
id: "2026-10.mqzv"
slug: custody-refuses-a-frame-whose-signed-bytes-exceed-60-kib
title: "Custody refuses a frame whose signed bytes exceed 60 KiB"
date: 2026-10-28
topics: [custody, security]
---

# ADR 2026-10.mqzv — Custody refuses a frame whose signed bytes exceed 60 KiB

Status: Accepted (2026-10-28). Amends ADR 006 and ADR 2026-10.7mqm: a byte cap among custody's admission refusals,
and a sweep delete. #227. A paired change: knit-ios mirrors it in the same release (knit-ios #34).

**What was observed.** A code audit, not the field. Custody counts frames and never bytes. Its 1,000 rows could hold
about 500 MB of frames near the 512 KiB the radios accept, from a few pinned identities, and every replay that reads
whole rows pulled that much out of the store at once: the digest answer, the group custody replays, the start replay
and the spool push (`ForwardStore.liveFrames`, `liveGroupChatFrames`, `liveFramesToFromOthers`).

**What changed.**

- **The cap.** `ForwardRepository.store` refuses a frame whose `signed` is longer than `maxSignedBytes`,
  `DEFAULT_MAX_SIGNED_BYTES = 61_440` (60 KiB). A frame of exactly the cap is kept. Only `signed` is counted, the
  frame-global, byte-identical part (ADR 005). `sig` is not: it is 64 bytes in production and empty in the vector rigs,
  so counting it would make one vector step mean two things. The refusal returns false with no write, no digest change
  and no eviction, like the other refusals, and counts `custodyOversizeRefused`. It applies to our own frames too
  (ADR 006). No composer check is added: no honest frame reaches the cap.
- **First among the refusals.** It runs before dead-on-arrival, the future window and the group gate, and before the
  `ChatContent` decode for the attachment hash, so an oversize frame is never decoded. Every refusal is a function of
  the frame alone, so the order decides only which reason is counted: a frame both oversize and naming a foreign
  roster counts as oversize, and `groupCustodyRefused` doesn't move.
- **The sweep deletes old over-cap rows.** `sweepExpired` also deletes every row whose `signed` is longer than the cap
  (`ForwardDao.deleteLongerThan`), then rebuilds the digest as it already did. Without it a node upgraded while holding
  oversize rows keeps them for up to the 24 h TTL, its digest differs from every upgraded peer's, and it re-serves those
  frames at each exchange to peers that refuse them. The delete reads the row alone, so every upgraded node deletes the
  same rows, and once the old rows are gone it finds nothing. These are live rows, so this delete is the one exception
  to the sweep being digest-neutral (work item #8). Startup is not reordered to sweep before the first store, and the
  cap is not added to the live queries: a length filter on every quota, digest and replay query would make each query
  it missed a permanent divergence.
- **Delivery and relay are unchanged.** `ForwardSync.onSeen` is the only caller of `store` and returns `Unit`;
  `MeshRouter.handleInbound` relays whatever custody answered. `MaliciousForkLabTest.anOverCapFloodIsRefusedByEveryStoreAndStillDeliveredAndRelayed`
  pins it: an over-cap room post is read on both nodes, and the over-cap DMs Bob refuses still reach Alice, who
  refuses them too.

**Why 60 KiB.** It sits above every frame an honest composer builds and below the spool's ceiling.

- The largest honest frames, built by the real send path at every clamp (`CustodyHeadroomLabTest`), measured in
  `signed` bytes:

  | Frame | `signed` bytes |
  | --- | ---: |
  | sealed DM | 13,709 |
  | group frame, 8 members | 15,335 |
  | sealed profile, four badges | 1,547 |
  | cleartext profile | 1,017 |
  | room post, 1,000 mentions | 56,144 |

  Each holds a 2,000-unit CJK body, a video poster at `VideoFrames.POSTER_MAX_BYTES` beside a 128-character attachment
  name, a 120-unit reply snippet under a 32-unit author and `StyleSpans.MAX_SPANS` styles. The DM and group frames
  mention their members; the room post binds a one-character name per person, 1,000 of them with real 26-character
  ids, which is as many as its body holds. Room-post mentions have no count clamp, so the room post is the one
  outlier, at 91% of the cap, and the cap still admits it; the next largest uses a quarter of it. A DM this long carries no inline acks (`MeshManager.inlineAcksFor`'s budget).
- A spool's `maxBlob` is 64 KiB, and a sealed frame blob is `signed + 93` (`ScopeCrypto`), so a spool carries `signed`
  up to 65,443. Custody admits nothing the Internet plane couldn't push.
- Worst case, 1,000 × 60 KiB is about 60 MiB, down from about 500 MB.

**What it costs.** None of these touches a frame under the cap, and no honest frame is over it.

- An oversize frame is never back-filled by a digest re-serve to a late joiner.
- An oversize group frame that arrives before its seed is lost to that member: the group replay reads only custody.
- An oversize frame of our own is never pushed to a spool.
- Until every node in reach has the cap, a node without it re-serves its oversize frames at each digest exchange and
  the upgraded node refuses them each time, the same upgrade window ADR 2026-10.7mqm's group gate had.
- Once per node, between the upgrade and its first sweep, an over-cap row taken before the upgrade still counts toward
  the quotas. A store in that window can evict one frame that an already-swept peer keeps. The peer re-serves it, and
  nothing is stranded.

Not covered: a byte budget on the global cap or eviction by bytes, and reading the replays in pages (#227's other
options). Revisit if 60 MiB is still too much. A door cap on profile frames is a separate, tighter change (#234):
ADR 2026-10.jgeg, 8 KiB of `signed`, refused at the door and in custody.

**What keeps it true.** `vectors/custody-v1.json` (`limits.maxSignedBytes`, each step's `signedBytes`, three byte-cap
steps) pins the rule for every port; `ForwardRepositoryTest` pins the constant, the refusal, its counter and the sweep;
`CustodyHeadroomLabTest` fails if an honest frame grows past the cap. The constant is shared law with knit-ios:
changing it is a paired change, like the TTLs (`.agents/context/store-and-forward.md`).
