---
id: "039"
slug: sealed-dms-ride-the-lora-plane-through-a-long-range-fan-out-seam
title: "Sealed DMs ride the LoRa plane through a long-range fan-out seam, re-offered on first hearing"
date: 2026-08-24
topics: [lora, crypto, dm]
---

# ADR 039 — Sealed DMs ride the LoRa plane through a long-range fan-out seam, re-offered on first hearing

Status: Accepted (2026-08-24; `MeshTransport.longRangeFanout`, `mesh/lora/`, `FarPeerFrameSource`,
`SettingsStore.loraDmEnabled`)

ADR 038 shipped the LoRa bridge carrying only the cleartext Nearby room and deferred DMs: a Nearby-only LoRa
peer showed up in the contact picker, and a DM to it stranded in custody until radio or spool contact. This
lands 1:1 DMs on the plane. A DM is a `chat` frame with a recipient and no group, sealed under the v2
ratchet, and it was refused in exactly two places — the transport-agnostic `shouldFastFanout` (which excludes
DM-form chat from *every* fast plane, kept that way by ADR 030) and `LoraFramePolicy`. Everything else already
fit: the epoch ratchet is loss-tolerant by design (independent epochs, ≤ 200 skipped keys per epoch), X3DH
needs only the pinned profile the beacon already carries, the ✓✓ is itself a sealed DM-form frame, and a
100-char DM compacts to 387 B (2 LoRa packets; 439 B with the X3DH init that rides every frame until the
first reply — pinned in `CoordinationPlaneSizeBudgetTest`; the 3-packet ceiling is ≈ 400 characters).
Decisions worth not relitigating:

1. **A separate seam, not a relaxed `shouldFastFanout`.** `MeshTransport.longRangeFanout` is a defaulted
   no-op honoured only by a plane with **no data path at all** (`shortRange = false`, `neighbors` always
   empty), for which it is the only path a frame can take. `CompositeMeshTransport` forwards it to every child
   with **no** `send(wire, null)` fallback (the router's flood already carries a DM over a link child's links —
   `fastFanout`'s fallback would double-flood every DM over BLE). `shouldLongRangeFanout` admits exactly the
   DM-form chat `shouldFastFanout` excludes, and both call sites (`originateSigned`, `onDeliver`) call it
   beside the old predicate. ADR 030's "relax `shouldFastFanout` to a size probe" one-liner was rejected on
   purpose: it would route DMs through Wi-Fi Aware's `emitFastWire` to every cue-only peer too, a NAN
   airtime change nobody asked for. The NAN and BLE planes are byte-for-byte unchanged.
2. **Broadcast at the Meshtastic layer, still** (`to = 0xFFFFFFFF`). The recipient may be board-less behind
   another board-holder — the far phone's router relays a broadcast over its BLE/NAN clique, which a
   Meshtastic unicast would not reach — no nodeNum↔nodeId map exists, and `want_ack` would hit the session's
   Routing `error_reason == NONE`-treated-as-NAK path. Unicast + link-layer acks stay a later optimization.
3. **The DM form is admitted opaque — all of it.** A DM, its sealed `CTL_RECEIPT`/`CTL_REACTION`, a session
   reset, a group-key seed/req/ack and an escalated group tick are wire-indistinguishable (ADR 016/018), so
   every one rides and none is singled out; the transport cannot tell them apart and must not try. What stays
   out is group-*form* chat (`group != null`) and the cleartext `groupupdate`/`groupleave` frames — the plane
   carries no group conversation, so those would only burn airtime. The delivery receipt therefore crosses for
   free: `InboundPipeline.acknowledge` originates it `relay = true`, and it re-runs on every re-delivery via
   the pre-decrypt exists-gate, which is how a tick lost over LoRa heals when the DM is re-offered.
4. **The targeted path stays strict; the re-offer is a private path.** `LoraFramePolicy`'s TARGETED rule is
   unchanged (`receipt`, or `chat && !relay && recipientId == to` — AckSync's sealed tick), so no `fastSend`
   caller (`AckSync`, `sendTyping`) gains a new frame class; `LoraFramePolicyTest` still pins that a
   `relay = true` DM never rides it. The re-offer enqueues through its own path inside `LoraMeshTransport`
   (decode → DM-form to the peer → sig-dedup → class DM).
5. **The queue sheds by class** (`FrameClass`: BOOTSTRAP > DM > ROOM). When full, `LoraPacePolicy` evicts
   the oldest whole frame of the lowest class present — the newcomer included, so a room post alone at the
   bottom is `REFUSED` rather than evicting a DM, and nothing ever evicts the profile bootstrap (038's
   "profile is never dropped" was only ever a comment over a label-blind FIFO). Dequeue stays FIFO.
6. **A freshness gate on the fan-out paths, the room included.** A `chat`/`reaction` whose `sentAt` is more
   than 15 min old is a custody re-serve (the router's SeenSet lapses at 10 min, so a fresh flood never looks
   this old) and stays custody's business — without it a newcomer's whole backfill re-fanned over the air,
   twelve frames at a time. Profiles (their `sentAt` is the publish stamp, up to 12 h old) and receipts are
   exempt, as are the targeted path (AckSync's verbatim 24 h retries) and the re-offer. The gate reads an
   injected wall clock: the transport's own `clock` is `elapsedRealtime` (pacing, dedup, linger) and is not
   comparable to a frame's epoch `sentAt`. A peer whose clock lags by more than the window keeps its fresh
   frames off LoRa only — there is no past-side skew clamp anywhere, and none is added.
7. **A bounded, sender-driven re-offer instead of custody sync.** The plane still has no `neighbors`, so
   `ForwardSync`'s digest exchange never runs over it. Instead, on first hearing a peer (once per 45-min
   linger window), after the beacon, the transport pulls `FarPeerFrameSource.framesFor(peer)` — `MeshManager`
   answers with the newest 4 live custody frames addressed to it (`ForwardStore.liveFramesTo`, an indexed
   query with a default over `liveFrames` so the fakes need nothing), minus our own frames the peer already
   acked (`MessageDao.unackedDmsTo`; an own frame with no unacked row is either delivered or a sealed ctl) —
   re-wrapped verbatim like a custody re-serve and enqueued class DM. A peer another plane carries
   (`foreignReachable`) is skipped: custody syncs to it for real there. ≤ 4 frames × ≤ 3 packets per
   sighting; a re-offer that lands after the receiver's SeenSet lapsed hits the exists-gate and re-draws the
   receipt (one chain key, bounded). Best-effort by construction: a DM outside the newest four, or one that
   missed both the live flood and a sighting, still waits for radio or spool contact.
8. **The first-hearing beacon needs a 60-s gap** (a relaxation of 038 §6; session-up keeps the 5-min floor,
   one timestamp, two gaps). A peer that just appeared has demonstrably never heard us, and without a periodic
   beacon this is the only way a late arrival learns our key: A beaconed two minutes ago, B just came up — A
   must speak again or B's parked frames (`PendingInbound`, 2 min) expire, and with them B's first DM.
9. **Metadata exposure is the price; a default-on toggle is the control.** Content stays end-to-end sealed,
   but a DM's cleartext `senderId`/`recipientId`, timing and size now travel on a public-PSK rendezvous
   channel at kilometre range, where the radios exposed them at ~50 m. `SettingsStore.loraDmEnabled`
   (default on, gated on `BuildConfig.LORA_PLANE`) rides into `LoraConfig.dms` and is applied inside the
   transport — the fan-out and the re-offer refuse DM-form when it is off while the room keeps riding —
   so `MeshManager`/`InboundPipeline` stay plane-agnostic and a `longRangeFanout` call is a cheap no-op.
   Each side gates its own sends. The confidentiality fix for the metadata remains the deferred private PSK.
10. **Not a wire change; custody untouched.** `sig`/`signed` still pass through `FastFrameCodec` byte-exact;
    no new frame type, field, ctl code or capability bit. Nothing new is stored and no custody rule changes,
    so the content digest's inputs are identical on every node as before (ADR 006).
   <!-- doc-lint: allow liveFramesTo — retired by ADR 2026-10.a4pm (see the amendment below) -->

Counters: `loraDmSent`/`loraDmReceived` (DM-form, sealed ctl included — the transport cannot tell),
`loraReoffered`, and `loraSuppressed` now actually counts (dedup-window and stale suppressions).

Honest residuals (accepted): the re-offer targets only the peer that was heard — a board-less recipient
behind another board-holder gets live DMs via that phone's relay but no re-offer (no routing table; the
"true DM routing" deferral); a peer that only listens never triggers a re-offer or a beacon exchange (the
periodic beacon stays deferred); after a session reset custody keeps the first-stored ciphertext
(`ForwardSync.onSeen` early-returns on `has(id)`), so a re-offer can serve bytes a wiped peer cannot open
until the fresh seal reaches it another way — airtime, not correctness; ~400-char steady / ~335-char
first-message ceiling, and a DM with an image arrives as text plus a loading placeholder until a radio or
spool path exists (`blobreq` never rides LoRa); sealed group machinery crosses opaquely although group chat
does not; and airtime is roughly SMS pace — ~2 packets per DM plus ~2 per receipt at ~2.5 s each, ~1–2
DMs/min sustained under the EU 868 duty cycle (the board's DUTY_CYCLE NAK already backs the pacer off).
Scheme + device bring-up: `context/lora-bridge.md`.

## Amendment 2026-10-10 — a DM waits for its recipient to be heard (ADR 2026-10.a4pm)

§7's re-offer is now the delivery path for most DMs to a quiet peer. A DM-form fan-out rides only to a recipient
in `reachable`, keyed on the originator's `FanoutHint`: our own DM (`CHAT`) may go out as the one probe per
recipient per 15 min, as may a linked pocket-mate's relayed frame at the gateway; other relayed frames are held;
our own ✓✓ (`TICK`) and sealed ctls (`CONTENT`, the post-reset re-seal included) are not gated, since nothing could
re-offer them. The recipient's ✓✓ makes it heard, and the first hearing re-offers what
was held. §7's "newest 4, minus our acked" is split: our own unacked DMs, chosen by id off `unackedDmsTo`, up to 6
and spent from `LIVE`; then relayed frames, up to 4, on `BRIDGE` (`LIVE` for a linked pocket-mate's). One frame is
re-offered at most once an hour, and a re-offer lifts a copy the bridge still has queued onto its own bucket. The
old single read let our acked DMs and own sealed ctls take the slots before the filter ran.

## Superseded in part by ADR 2026-10.rp3s (2026-10-29)

§8 (the first-hearing profile beacon) is gone: no profile rides the plane. The DM form, the long-range seam and the first-hearing re-offer of our own DMs stay until Stage 2; the re-offer of relayed frames is gone.
