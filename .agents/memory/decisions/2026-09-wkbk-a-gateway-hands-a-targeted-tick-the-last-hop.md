---
id: "2026-09.wkbk"
slug: a-gateway-hands-a-targeted-tick-the-last-hop
title: "A gateway hands a targeted tick the last hop"
date: 2026-09-15
topics: [receipts, mesh, lora]
---

# ADR 2026-09.wkbk — A gateway hands a targeted tick the last hop

Status: Accepted (2026-09-15). Amends ADR 2026-09.y5f3 (its second route now reaches one hop further).

## What was observed

The mesh-in-a-box lab, 2026-09-14, four real stacks over a shared `FakeMeshtasticAir`
(`LoraPocketLabTest.aFarPocketsTickReachesABoardLessAuthorBehindTheGateway`, parked as issue #48).

Pocket A is Alice (board) and Bob (no board, linked to Alice); pocket B is Carol (board) and Dave. Bob
posts in the Nearby room. The post crosses exactly as ADR 044 intends — Alice's board fans it, Carol's
board hears it, Carol relays it to Dave over the link — and both far-pocket phones read it. **Neither
✓✓ ever comes back.**

Carol's tick is the one that should have. She is not linked to Bob, so it ride-holds, and at the 60 s
deadline (ADR 2026-09.y5f3 route 2) it leaves as LoRa's targeted `send:chat`: a `relay = false` frame
addressed to Bob. Alice's board hears it — and that is where it stops. `MeshRouter.scheduleRelay`
returns on `!wire.relay` ("point-to-point control frames propagate hop-by-hop, not flooded"),
`InboundPipeline.onDeliver` drops it because it is addressed to Bob and not to her, and a room tick
never escalates into custody (ADR 2026-09.aa27), so there is no second path behind the first. A
board-less author sitting **one link** behind a gateway never sees a far pocket's ✓✓ at all.

It was tempting to read this as an addressing bug or a gateway-role bug. It is neither. Every component
did exactly what it was told; nothing owned the last hop.

## What changed

**`MeshRouter.handOn`**: a point-to-point frame addressed to a peer this node holds a **live link** to is
sent over that link, once. The router already owns forwarding; this is the branch it was missing for the
frames the flood never carries. The gateway now does for the tick what it already does for the post that
earned it.

Five bounds, each one a rule the flood path already follows:

- **DM-form chat only** — the existing `shouldFastSend` predicate (`chat` + `recipientId` + no group), so
  this admits the sealed `CTL_RECEIPT` tick and any future sealed ctl DM. A `typing` cue is worthless a
  moment later; `blobreq`/`keyreq` name no recipient at all and propagate through their own handlers.
- **Split horizon** — never back at the hop that handed it to us.
- **The hop count**, capped to the local `DEFAULT_TTL` exactly as `scheduleRelay` caps it, so a forged
  `ttl` cannot outlive the dedup window.
- **A link, never a sighting** — `transport.neighbors`, not `reachable`. ADR 044's field lesson, and the
  same reading `LoraMeshTransport.fastSend` takes.
- **Once per frame**, from the `SeenSet` gate in `handleInbound` that got us there.

A frame addressed to *us* is never handed on, and the router needs no identity to know it: our own node
id is never in our own neighbor set.

**Where it could not live.** `InboundPipeline.onDeliver` looks like the obvious home — it is where
`fastSend`'s bridge hand-off and the fan-out siblings already sit — and it cannot work. Route 2's tick is
ADR 059's **unsigned v3** form, and `verifyInbound` admits an unsigned frame through one door only,
`isUnsignedTickShape`, which requires `recipientId == me`. At the gateway that is false, so `onDeliver`
returns at its verify gate before any forwarding code could run. That gate's own comment already says
where the job belongs: *"We still return normally so MeshRouter relays it onward."*

**What the alternatives were.** Option 2 in the issue — originate the LoRa tick with a hop budget of two
and let the ordinary relay carry the last hop — buys the fan-out that `relay = false` was chosen to avoid:
one named person is waiting, and every other phone in the pocket would hold a copy it cannot read. Option
3 was to accept it, which leaves a whole class of user (board-less, behind a gateway) permanently short
of far-pocket receipts. Escalating a room tick into custody stays rejected for aa27's reasons and y5f3's.

**The hand-on is a hand-off.** It reports through the router's existing `onRelayed`, so
`ContributionLedger` credits it under rules it already had: someone else's chat frame, not ours, not
addressed to us, sent to someone other than its author, memoized once per frame. It credits both
"passed along" and "handed straight to" — honest, because the frame went over a live link to its
addressee. Counted as `framesHandedOn` (Diagnostics' "Handed on" row, and `handedOn=` in the 60 s line).

**No wire change.** No field, no type, no capability bit. An old node simply does not hand on; an old
addressee receives an ordinary `relay = false` frame over a link, exactly as it does from a linked acker
today.

## What it costs

- **A handed-on frame may be one this node cannot verify.** The unsigned tick authenticates at its
  addressee and nowhere else, so the gateway forwards bytes it cannot read. That is the flood path's
  position too — an unverifiable frame is relayed, never delivered, because a relay that drops what it
  cannot read is a propagation black hole — and it buys an attacker no new reachability: the same
  attacker's flooded frames already reach Bob through Alice. The cost of a crafted frame is one unicast
  over a link, bounded by `IngressBudget` at the gateway and by the ratchet AEAD at the addressee.
- **Neither end custodies it** (`relay = false` fails `onDeliver`'s carry gate on both sides), so unlike
  y5f3's spool route this leaves no store to reconverge. `assertConverged`'s custody half is untouched.
- **The acker keeps re-sending.** There is no ack-of-ack, so Carol's owed entry lives out its doubling
  backoff until it ages out, whether or not the hand-on landed. Unchanged by this, and unchanged for any
  far peer.
- **In a dense pocket, several nodes may hand the same frame on**, each to the same addressee, whose
  `SeenSet` drops all but the first. In practice one node per pocket is both a board-holder and linked to
  the addressee, so the count is one.
- **Not covered: Dave's half.** An acker with **no board and no spool** has nowhere to send a tick at all
  — `AckSync.escalateRide` finds no link, no spool cover and no `reachable` author, and keeps waiting.
  Closing that needs a linked peer to accept a frame *for onward airing*, which is a second rule about
  spending someone else's airtime and a different decision from this one. y5f3's "not covered" stands.

Kept true by `MeshRouterTest.handsAPointToPointFrameTheLastHopToALinkedAddressee`,
`neverHandsAPointToPointFrameBackToTheHopItCameFrom`, `doesNotHandOnToAnAddresseeWeHoldNoLinkTo`,
`doesNotHandOnAFrameThatIsNotDmFormChat`, `doesNotHandOnOnceHopsReachLocalDefault`,
`aHandedOnFrameReportsTheAddresseeToOnRelayed`; and end to end by
`LoraPocketLabTest.aFarPocketsTickReachesABoardLessAuthorBehindTheGateway`, now un-ignored, whose oracle
is the plane the author records the tick on — the last hop was the link, not the air.

## Superseded in part by ADR 2026-10.rp3s (2026-10-29)

No build since airs a targeted room tick over LoRa, so the gateway's last hop has nothing of a new build's to hand on. `MeshRouter.handOn` stays as written: it moves frames over links only, costs no air, and still carries an older build's aired tick home.
