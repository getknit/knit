---
id: "2026-10.97vs"
slug: typing-indicators-ride-the-spool-s-signal-lane
title: "Typing indicators ride the spool's signal lane"
date: 2026-10-28
topics: [spool, wire, privacy, settings]
---

# ADR 2026-10.97vs — Typing indicators ride the spool's signal lane

Status: Accepted (2026-10-28). The client half of the signal lane, specified in `docs/SPOOL_PROTOCOL.md` §7.8
(S-7.8-1 to C-7.8-16). The spool half is knit-spool ADR 2026-10.2q49, whose `0.8.0-dev.2` first carries it. Reads ADR
2026-10.fw8g and ADR 2026-09.2ajk (what counts as presence), ADR 2026-09.sjaa (the BLE side channel typing cues
already take) and ADR 2026-10.a5yj (proposed: direct links, whose offers are this lane's next kind).
<!-- doc-lint: allow 2026-10.a5yj — proposed, its file lives on exp/dht-links until that branch merges -->

**What was observed.** A typing cue reached only a peer the radios could reach. `MeshManager.sendTyping` signs a
`relay = false` frame and hands it to `fastSend` / `fastFanout`, so a contact met only over the Internet never saw
one, and a message from them arrived with no warning at all. The spool plane could carry it in two shapes, and both
were wrong:

- **A stored frame.** `typing` is not custodial (`isStorable` is false), and each cue would cost a digest entry, an
  eviction and a pull per member per heal round: one every 8 s per typist pushes a scope's history out.
- **A cast (§7.7).** The cast set is a walkie's `talk` frame on a DM scope alone, and a spool that takes tickets
  gates casts behind one (S-7.7-6). Typing is messaging, which stays free, so a gate there would make every free
  phone's conversations quieter for nothing sold.

**What changed.**

- **A lane of its own.** HELLO `sig: {maxBytes, rate}` (declared last in `SpoolHello`, after `turnHosts`) and a `sig`
  record both ways, a cast's shape with no ticket rule (S-7.8-4) and its own budgets. `SpoolConnection.canSig` /
  `sigOut` keep the advertised rate in a bucket of their own (`SendBudget`, burst 4×), clamp `maxBytes` to
  `maxRecord` and `rate` to 100, read a `rate` of 0 as no `sig` (S-7.8-1), and skip a sig while a blob's worth is
  queued on the socket, as a cast does.
- **A seal of its own.** `ScopeCrypto.sealSig` / `openSig`, `0x05` with `knit/scope/v1/snonce` and
  `knit/scope/v1/sig`: the cast's padded construction, now one private helper both lanes call, so a cast's bytes did
  not move (`CastSealTest`). Nothing opens as another class (`SigSealTest`).
- **The signal set.** `ScopeFrames.sigEligible`: a `typing` cue on the pair's own DM scope in the direction of
  travel, or on a group scope for that group (`TypingContent.groupId`, where every port carries it) from a
  founding-roster member. Never a pair scope or the commons, never an envelope roster, never a payload that will not
  decode. `openSig` returns a `ttl = 0`, `relay = false` wire, which `handleSig` runs through `canCarry` and hands to
  the router, exactly the cast path: never custodied, accounted, quarantined or taken as presence, and a failure
  remembered nowhere. The router keeps the first copy by id over the radios and every spool (C-7.8-15).
- **Sending.** `sendTyping` sends the same signed frame as a sig, beside the radio copy, for a DM or a group, through
  `ScopeSync.sig`: every connected spool that advertised `sig` and answered the SUB of the conversation's current scope
  (never a retiring one). The 8 s throttle in `ChatViewModel.onUserTyping` is unchanged.
- **One receive rule for every plane.** `InboundPipeline.handleTyping` now drops a cue from a blocked sender and one
  in a conversation that is still a message request, whichever plane it came over. A radio cue from a blocked
  sender used to be tracked and was only hidden by the chat screen's own filter.
- **The switch.** Settings › Typing indicators (`SettingsStore.typingIndicatorsEnabled`, on by default, rides a
  backup). Off, `sendTyping` sends nothing on any plane, the pipeline records nothing, and `ChatViewModel` hides a cue
  already showing at once rather than at its 12 s hold.

**What it costs.**

- A spool now sees when someone in a DM or group scope is typing (§10.1): about one 285-byte sig every 8 s per typist,
  with the subscribers' IPs it already saw for that scope. The switch is the control.
- A released client (2.10.0) that shares a scope with a new one receives sigs it cannot read. Its
  `SpoolConnection.onMessage` drops an unknown `t` (`else -> Unit`) without a strike or a close, and the 256-record
  inbox absorbs a cue every 8 s with room to spare, so it keeps working.
- Not covered: call signalling over `sig` is deferred (the maintainer, 2026-10-28) and call signals stay stored DMs;
  link OFFER/ANSWER join on `exp/dht-links` once this merges (ADR 2026-10.a5yj); no ticket bit, PoW stamp or
  per-scope budget gates a sig.
  <!-- doc-lint: allow 2026-10.a5yj — proposed, its file lives on exp/dht-links until that branch merges -->
- The trap: a new signal kind is a revision of `sigEligible` and of C-7.8-13, never a widening of a cast or a stored
  frame. Keep `isCustodial` out of the set, or a custodial frame dodges custody and the digest. Pinned by
  `ScopeFramesTest`'s signal-set matrix, `SpoolConnectionSigTest`, `ScopeSyncTest`'s sig cases, `SigSealTest`, and the
  §13 vectors `helloSpoolSig` / `sigRecord` (copied from knit-spool 0e0336df in `SpoolRecordsTest`) and `sigSeal`
  (`ScopeVectorTest`).
