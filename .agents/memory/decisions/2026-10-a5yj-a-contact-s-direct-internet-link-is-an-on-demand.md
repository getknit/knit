---
id: "2026-10.a5yj"
slug: a-contact-s-direct-internet-link-is-an-on-demand
title: "A contact's direct Internet link is an on-demand, long-range MeshTransport child"
date: 2026-10-18
topics: [mesh, spool, transport, privacy, wire]
---

# ADR 2026-10.a5yj — A contact's direct Internet link is an on-demand, long-range MeshTransport child

Status: Proposed (2026-10-18, draft on `exp/dht-links`; nothing built). Reads ADR 019 (the spool plane and why
`ScopeSync` is not a transport), ADR 2026-10.3tee (call signalling over sealed spool frames), ADR 2026-09.2ajk and
ADR 2026-10.fw8g (presence tiers), ADR 2026-10.kxnw (`CallLane`), ADR 2026-10.8t2y (the spool's gates) and ADR
2026-10.swvp (libwebrtc is `compileOnly`). It amends none of them until it is accepted.

Two contacts on two radio islands, each with an Internet route, already meet through a spool. Every frame goes up,
waits and comes down. One Internet-connected member bridges a whole island both ways (SPOOL_PROTOCOL §1.2), so
reach is not what is missing. Four things are:

- **Latency.** A pull round is not a link, so a frame takes the spool's pace, not the network's.
- **Bulk.** An attachment over a ticket's `am`, or on a connection whose files gate is locked, never goes up
  (ADR 2026-10.8t2y, ADR 2026-10.s3ep). Large files reach a contact only by Direct Transfer, which needs the
  two phones side by side.
- **Live audio with no radio path.** A walkie with no mesh path needs a spool that sells casts to the pair (§7.7).
- **Metadata.** The spool sees the timing and the size of everything the pair exchanges.

The call work showed that the pieces for a direct link already exist. Each spool's hello hands out STUN servers
(ADR 2026-10.xkp4), so both phones can learn their reflexive addresses. A sealed spool cast carries short-lived
signals between a pair without storing them, and `CallLane` is a sealed, counter-nonce, per-direction framing that
has been checked on a call's Wi-Fi Direct leg.

## What changes

**The link is a `MeshTransport` child, not a `ScopeSync` feature.** ADR 019 kept the spool plane out of the
transport seam because "a scope has no neighbors". A direct link to one contact does have a neighbour, and that is
the whole point. Under `CompositeMeshTransport`, an Internet-link child gets the existing custody convergence for
free: `ForwardSync`, `StoreDigest`, `BlobExchange` and `MeshRouter.handOn` all run over whatever the seam reports as
linked. Bridging needs nothing new either. A frame that arrives over the link is re-served into each end's radio
island by the ordinary path, exactly as a frame pulled from a spool is (§9.4). The child declares
`shortRange = false`, like LoRa, so siblings ignore its sightings, and it gets a fifth `TransportKind` so
Diagnostics and the field log can tell it apart.

**A contact first.** A link opens only to a peer with a confirmed DM session (`RatchetSessions.exportedRoots`), whom
this person has written to or verified. A contact accepted only through the commons does not qualify. The link hands
the peer this phone's IP address, but for a contact that adds little: they already know who this person is, and
usually roughly where they live. So the switch is an ordinary opt-in, off by default and per phone. Its copy says, in
one line, that contacts it links with can see the network it connects from. It is not a warning screen. Strangers are
a later mode with stricter rules (below), not ruled out.

**On demand, never standing.** A NAT mapping on cellular needs a keepalive every 15–30 s, and each one keeps the
cellular modem in a high-power state for seconds. A standing link to every contact would cost more than the spool
socket it was meant to beat. A link opens only when there is something to move:

- custody holds frames addressed to that contact, or to a group both are in, and no radio carries them;
- a blob for them is over the spool's `am` limit, or every connected spool's files gate is locked;
- a walkie session has no mesh path.

It closes after a short idle, and its last state is never resumed in the background. Low-power mode (ADR
2026-10.d44b) opens none, and Pause holds none.

**What crosses is the pair's own traffic.** By default a link carries the frames whose scope is the pair's DM or a
group both are in, plus the blobs those frames name. The digest it exchanges is filtered by the same rule at both
ends, so neither side reads the other's filtered-out frames as a gap. The Nearby room never crosses: it is a
proximity semantic, and the spool plane already excludes it for that reason (§1). A full custody bridge, carrying
strangers' sealed frames, is the stranger mode below.

**Presence stays honest.** A peer on the link reads as the long-range tier in `ui/Reach.kt`, the same as spool
presence. It is never `Direct`, never "nearby", and never "met" or "handed straight to" on Your mesh (ADR
2026-09.2v2t). `MeshController.neighbors` is the short-range set by ADR 2026-09.2ajk, so the composite keeps the
link's peers out of it the way it keeps LoRa's out.

## Strangers: a later mode that never names the phone

A stranger on the street already takes this phone's whole sealed custody store over the radios, and gives it theirs.
A stranger over a direct link would get the same sealed frames, so the content is no worse. What changes is what
surrounds those frames, and each difference has its own answer:

- **The physical bound goes.** Over the radios an observer must be within about 100 m, and only while there, so
  watching many people costs people or hardware in many places. Over the Internet one cheap crawler sees every phone
  that opted in, from anywhere, unseen.
- **A permanent identity meets a location history.** The node id is a long-lived key. A crawler that records node
  id → IP over time builds home, work, carrier and city, plus a home IPv6 prefix that barely changes. A web visit
  hands one IP to one chosen server, with no lasting identity unless the visitor signs in.
- **It needs a public directory.** Strangers can link only if phones publish addresses where strangers can look,
  whether on a spool or a DHT, and that directory is what a crawler harvests.
- **Exposure costs money.** Whoever holds the address can flood the phone and spend its cellular data, and the frame
  parser becomes reachable from the whole Internet, not just from people within radio range.

So stranger links are a separate opt-in from contact links, built only once all of these hold:

- the link authenticates with an ephemeral key, never the node id;
- the rendezvous keys rotate and cannot be linked to each other or to a node;
- IPv6 temporary addresses only;
- Wi-Fi only by default, with a per-link and a daily data cap and a rate limit at the socket, ahead of the parser.

One leak survives all of them. A peer can guess which frames in the bag are this phone's own (its profile, the
freshest authored frames) and tie them to the address. That is weaker than naming the node, but it is not nothing.
Only an onion route closes it, and Tor is still deferred (`.agents/memory/roadmap.md`). The copy for stranger mode
says this.

## Rendezvous: the spool now, a DHT later

**The offer and the answer ride a spool cast in the pair's DM scope** (§7.7, `ScopeFrames.castEligible`): signed,
sealed to the scope, never stored, never answered by the spool. They carry a fresh nonce and the candidates:

1. this phone's IPv6 global addresses, temporary ones preferred;
2. its IPv4 reflexive address, learnt from the hellos' STUN under `call/StunServers`' URL rule;
3. its host addresses, so two phones on one LAN link without the NAT.

A spool that will not take the cast (an `ent` spool without a ticket for bit 3, ADR 2026-10.8t2y) means no link
through that spool. Falling back to a sealed ctl in a chat frame would put a stale, address-bearing offer into
custody and onto the radios, so it is ruled out. A peer whose capability bit is absent is never offered a link.

**A DHT is the recorded design for phones with no spool, deferred until such phones ask for it.** A BitTorrent
Mainline DHT mutable item (BEP 44, Ed25519-signed) under a key derived from the pairwise root and an hour bucket can
hold the same sealed candidates. Rotating the key every bucket stops a crawler from linking one pair's announcements
across time. The phone performs lookups and puts only. Running as a full DHT node would mean answering strangers'
traffic around the clock, which no battery budget allows.

## The path and the lane

**UDP, IPv6 first, no TURN.** Both ends send to every candidate pair at once, IPv6 pairs first. On IPv6, sending at
the same time opens both stateful firewalls. IPv4 hole punching works through endpoint-independent NATs and fails
through symmetric ones, which much of cellular CGNAT is. A punch that has not answered in about ten seconds gives up,
and that contact backs off. The fallback is the spool, never a TURN relay: TURN is a spool with fewer guarantees and
its own credentials.

**The lane is `CallLane`'s shape over datagrams.** One AES-256-GCM key per direction comes from HKDF over the
pairwise root and both offers' nonces, under a label used for nothing else, so a lane key never proves a call, a
transfer or a scope. The nonce is the frame's counter, and the AAD binds both node ids. A small reliability layer
underneath (ordered delivery, acks, retransmission and a congestion window that backs off on loss) gives the link the
byte stream that framing on the radio links already expects.

**No libwebrtc and no new native code.** A WebRTC data channel would bring ICE for free, but release builds ship no
libwebrtc (ADR 2026-10.swvp), and making it `implementation` is ruled out. The release APK is also byte-reproduced by
F-Droid, so a native QUIC stack is out too. A pure-JVM QUIC library is the one alternative the spike weighs, on its
license and its reproducibility.

## Alternatives a reader reaches for first

- **Teach `ScopeSync` to talk to peers.** That puts a neighbour into the plane ADR 019 built around having none, and
  rebuilds custody convergence beside the copy the transports already share.
- **DHT first.** It adds a public network's crawler exposure and a battery cost in order to solve rendezvous, which the
  spool already solves for every phone that has one.
- **Open stranger links with the node id as the link identity.** That builds the crawler's node id → IP directory
  for it. Stranger links wait for the anonymous identity above.
- **Carry the Nearby room over links.** That turns "Nearby" into "anyone online".
- **TURN as the fallback.** That relays the same bytes the spool would, through a server that guarantees less.

## What it costs, and the traps

- **The peer learns this phone's IP address.** For a contact that is a small price, opt-in and stated in a line.
  For a stranger it is not small, which is why that mode waits for an identity that never names the node. The spool
  still sees that an offer was cast, and when.
- **The success rate is unknown.** Cellular-to-cellular IPv4 may mostly fail. The spike must record, per attempt,
  which candidate pair answered and on which network types, before anyone promises the feature.
- **Energy and data.** Keepalive energy is measured with `scripts/battery-trial.py` before the idle timeout is
  chosen. Filtered digests keep convergence traffic to the pair's scopes, but a large group's backlog over cellular is
  still real data.
- **The presence trap.** The first thing that breaks is a link peer leaking into `MeshController.neighbors` and
  showing as Direct. A test pins it.
- **The wire.** Offer and answer are two additive messages inside the sealed cast, plus a capability bit, under
  WIRE_COMPAT with golden vectors. The iOS port implements both as a paired change.
- **The tests owed.** A pure open/close policy with JVM tests. A mesh-lab scenario in which two radio islands with
  no spool path converge through one lab Internet link and end in `assertConverged`. A scenario in which the room and
  a stranger's frames stay on their island.

## Amendment 2026-10-27 — the signal's seal and the lane's keys (draft)

Status: draft from the crypto design pass on the Phase 0 spike; the caller decides whether it stands.

**The lane keys come from both signals, not from the pairwise root.** Each signal carries `k`, 32 bytes drawn fresh for
that attempt, and `key(dir) = HKDF-SHA256(ikm = kOffer ‖ kAnswer, salt = linkId, info = label ‖ offererId ‖
answererId, L = 32)` with the label `knit/link/v1/lane/o2a` or `…/a2o` and both node ids as canonical bytes in role
order (`mesh/crypto/LinkKeys`, `docs/DIRECT_LINKS.md` L-5-1). The text above said "HKDF over the pairwise root and both
offers' nonces". A drain window can hold two roots and the ends need not agree which one cast the signal, so naming a
root would have needed a rule for it; two fresh `k`s sealed under the pair's ratchet are already secret to the pair, and
no root or scope id means a lane key proves nothing about a call, a transfer, a scope or the session.

**The signal is sealed only under a confirmed session, and opened only over one.** The sender seals with
`RatchetSessions.sealDm(confirmedOnly = true)` and asks `ScopeSync.canCast` first, so a cast nobody takes and our own
unanswered init or reset never move the ratchet; a null seal is no attempt, never a fallback. The receiver takes a
signal only from a spool's cast, signed, from an accepted, unblocked sender whose session it holds confirmed, and only
in a build that wires links; an OFFER whose stamp is more than `MAX_FUTURE_SKEW_MS` from its own clock is dropped
after the open (counted `LINK_STALE`), and an ANSWER is judged by its link id alone (`docs/DIRECT_LINKS.md` L-2-4). A
failed open is never reset evidence (`onRatchetFailure(authenticated = false)`): a reset racing an OFFER leaves it
unreadable as a matter of course. Whether this person wrote to or verified the contact is the offerer's consent rule,
asked by the link layer before it offers, never at receipt, so the rule cannot differ between the two ends.

## Amendment 2026-10-27 — no standing keepalive

**A link sends no keepalive; it closes when idle, and only a held link keeps itself alive.** The text above sized the
cost of keeping a NAT mapping as "a keepalive every 15–30 s". Measured on two lab phones with the keepalive switched off
(`…debug.LINK --es op quiet` + `gaps`: after each idle gap, one echo out, then an echo asked of the peer after the same
silence on both ends), an idle path lived far longer: cellular ↔ home Wi-Fi over IPv6 survived 2 min both ways and was
dead at 4 min (the home router's IPv6 firewall), and cellular ↔ cellular (T-Mobile, IPv6) survived every gap up to
4 min. From 60 s of silence the first datagram came about 2 s late: the modem waking, not a lost path. The IPv4 path
(home NAT ↔ NAT64) was not measured; both runs came up over IPv6.

So a link that has carried no DATA either way for 60 s closes with a BYE (`LinkSession.IDLE_MS`), well inside the
shortest life measured, and nothing needs a keepalive while there is work: the work refreshes the mappings, and the
stream's own acks show a dead path. A live session that must outlast its pauses — a walkie, whose next burst cannot
wait out a fresh punch — **holds** its link (`DirectLinks.hold`): a keepalive after 90 s with nothing sent, under the
2 min the home router kept, and an end after 200 s hearing nothing. That is a quarter of the modem wake-ups a 20 s
keepalive costs, and only while the session is open. The keepalive battery trial the text above owed is replaced by
these measurements; the IPv4 path's mapping life is still owed before the numbers are relied on everywhere.

## Amendment 2026-10-27 — the stream is our own ARQ

Status: stands (2026-10-27). Measured on the lab phones over one home Wi-Fi and from Wi-Fi to T-Mobile LTE;
cellular to cellular is still to be run.

**A link's byte stream is a small ARQ of our own over the lane's DATA datagrams** (`mesh/inet/LinkArq`,
`docs/DIRECT_LINKS.md` §6): numbered segments of at most 1096 bytes in datagrams of at most 1200, every datagram
carrying the receiver's ack, its ranges and its window limit (512 segments), loss by three later transmissions acked or
time since it went, never by a timer (RFC 9002), and NewReno's AIMD over segments, paced across the round trip. A
retransmission is a fresh lane seal. It sits behind one seam, `LinkStream`, which yields the `LinkSocket` that
`FramedLink` and `LinkHandshake` already run over unchanged, so the choice below can be revisited without touching the
session or the framing.

**What it moves.** Two phones on one home Wi-Fi (`…debug.LINK --es op bulk`) moved 2–4 Mbit/s unpaced, losing about 1 %
on the air (no socket-buffer drops at either end) to windows sent back to back at an access point holding frames for a
phone in power save. Paced, 3.5–4.3 Mbit/s, one 16 MB run with no retransmission. What caps it is random air loss
(about 0.14 %) against a window that halves on each loss, at a round trip that rises from 14 ms idle to 70 ms under
load: enough for the links' work, and well past what Bluetooth carries. A gentler decrease or a delay-based controller
is the lever if a later need asks for more.

**Cellular: loss is judged from acks, never from a timer.** The first build timed out the way TCP's RFC 6298 timer does:
on expiry it presumed every segment in flight lost and dropped the window to two. P9 on Wi-Fi to P7 on T-Mobile LTE at
poor signal (round trip 400–500 ms) moved 1 MB in 86 s and 4 MB in 574 s (0.05–0.09 Mbit/s); 165 of the 181 segments it
sent again had not been lost, their acks only late — a cellular path's delay spikes fired the timer over and over, and
each expiry threw the window away. The stream now follows QUIC (RFC 9002): a segment is lost only when acks show it
(three later transmissions acknowledged, or 9/8 of a round trip since it went while a later one was), and the timer only
sends two probes that ask for an ack, doubling, leaving the window alone; only losses found after three expiries in a
row take it to its floor. On the JVM, acks stalled two seconds without loss now cost two probes per expiry and nothing
else, where the old timer presumed ~500 segments lost and finished at half the speed. The same pair over the same
carrier, the P7's reception better that afternoon (round trip 90–140 ms), then moved 1 MB at 3.4 Mbit/s and 4 MB at 4.3
and 1.7 Mbit/s with no more than two probe timeouts a run. The phones also showed the stream had sent its datagrams
after letting its lock go, so two threads' batches could cross on the wire and the receiver's ack for the later one
declare the earlier lost (17–34 spurious losses a 4 MB Wi-Fi run); it now sends under the lock, in the order it numbers
them (0–2).

**Kwik was weighed and set aside.** `tech.kwik:kwik` 0.12.1 (LGPL-3.0; agent15 LGPL-3.0, hkdf Apache-2.0, siphash
MIT) is pure Java, about 280 KB of dex after R8, and its R8 output was byte-identical across runs. On a desktop JVM it
moved 10 MB over loopback at 36–46 MB/s over a socket the caller had bound, with a pinned self-signed P-256 certificate.
Made to work here it would need:

- **A patched fork.** Its server, which one end of every link must be, builds a `MemoryWatcher` on
  `java.lang.management` (absent on Android) and calls `Duration.toSeconds()` (API 31, not backported by D8). Both are
  small patches, but an LGPL fork to carry and for F-Droid to build from source.
- **QUIC inside the lane.** Run bare, its server turns off connection migration, so a NAT that rebinds the peer ends
  the link, and its TLS authenticates one way: the server never asks for a client certificate and takes no external
  PSK. Both go away if a wrapper socket seals QUIC's datagrams in the lane and shows Kwik a fixed peer address while the
  session follows the peer underneath, but then its TLS is a second handshake round trip and a second key schedule
  over keys the signal already seals, in a stack its own readme calls unreviewed.

On Android alone that is about the effort of `LinkArq`. **What decides it is the iOS port**: knit-ios ports §6's six
clauses (about 600 lines), where QUIC would need a stack on iOS that interoperates with Kwik inside our lane, and
Network.framework's QUIC owns its own UDP socket and cannot ride a punched, sealed path. Two more weigh the same way:
Kwik also halves its window per loss, so on these phones it would meet the same ceiling, and the link's idle close and
answer budget need to know when real traffic moved, which is a few lines in a stream we own and awkward through a
library that runs its own threads and timers. Kwik's loss recovery is the model `LinkArq` follows; the seam stays so a
QUIC stream can replace it if knit-ios takes up a QUIC stack anyway, or the links come to need many streams.

**For Phase 1:** `FramedLink`'s HELLO and digest are DATA to the link, so once a transport runs over the stream every
link that comes up "carried data" and none idles out while `FramedLink` sends anything. The transport child must tell
the link when real traffic (a frame or a file) moved, and the idle close and the empty-answer budget count that instead.
