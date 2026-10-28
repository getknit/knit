# Direct links between contacts

Status: experimental, Phase 0 spike (ADR 2026-10.a5yj, Proposed), behind `BuildConfig.DIRECT_LINKS` (`-PdirectLinks=`,
on in debug, off in release). Android-first: a peer without `Protocol.CAP_INET_LINK` is never offered a link. This file
is the normative description the iOS port reads; the clauses below are numbered so a test or a review can name them.

A direct link is a UDP path between two contacts who each have an Internet route. Its offer and answer are cast through
a spool in the pair's DM scope (`docs/VOICE_CALLS.md` §2.7), its datagrams are sealed per direction, and it is opened on
demand and closed when idle. It carries the pair's own traffic only (a later phase); the Nearby room never crosses.

## 1. Who may link

| Clause | Rule |
| --- | --- |
| **L-1-1** | A phone offers a link only to a contact with a **confirmed** DM session (`RatchetSessions.exportedRoots()` with `confirmed`, never our own unanswered reset), whom this person wrote to or verified, whose pinned profile carries `CAP_INET_LINK`. Never to itself. |
| **L-1-2** | A phone answers an offer only while its own switch is on, from an accepted contact whose session is confirmed and who is not blocked (a blocked contact's signal is dropped unopened). It answers a contact as often as the contact uses the links: what is budgeted is an answer that came to nothing — a punch that found no path, or a link that closed without carrying data — at most six an hour per contact, and one answer per contact is in flight at once. An offer it does not answer gets no reply at all. |
| **L-1-3** | Nothing about a link is sent while the cast lane cannot carry it (`ScopeSync.canCast`): no seal, no ratchet step. There is no fallback to a stored frame. |

## 2. The signal

| Clause | Rule |
| --- | --- |
| **L-2-1** | A signal is a `link` frame (`FrameType.LINK`): signed, addressed to the peer, its payload a `LinkSignal` sealed on the DM ratchet with `confirmedOnly` (laid out as a `talk` payload: `TalkSeal`, or a `ChatContent` for a header that form cannot carry). |
| **L-2-2** | It is cast only (C-7.7-13): never sent over the radios, custodied, parked, relayed or handed on. A copy that arrives from anywhere but a spool is dropped. An unsigned one is refused. A failed open is never evidence for a session reset. |
| **L-2-3** | `LinkSignal { ph, id, k, c, nt, ct }`: `ph` 1 OFFER, 2 ANSWER; `id` 16 random bytes naming the attempt; `k` 32 random bytes drawn fresh for that attempt; `c` the sender's candidates; `nt` its network (0 other, 1 Wi-Fi, 2 cellular); `ct` the sender holds a cast ticket. |
| **L-2-4** | An OFFER is cast once per `id`; a retry is a new `id` and a new `k`. The contact answers one `id` once, echoing it; the offerer takes the first ANSWER that opens under a pending `id` and ignores the rest. An answer is judged by `id` alone, never by `sentAt`; an offer is answered only while its `sentAt` is within `MAX_FUTURE_SKEW_MS` of now. |
| **L-2-5** | Offers that cross: the lower node id's offer stands; the higher id drops its own and answers. |

## 3. Candidates

| Clause | Rule |
| --- | --- |
| **L-3-1** | `LinkCand { a, p, t }`: `a` 4 or 16 address bytes, `p` the UDP port, `t` the kind — 0 IPv6 temporary, 1 IPv6, 2 host (a LAN IPv4), 3 IPv4 reflexive. |
| **L-3-2** | A phone lists the addresses of the one socket it will punch from: its global IPv6 addresses (temporary first), its LAN IPv4 address, and the reflexive address a STUN Binding **sent from that socket** to a connected spool's STUN server returned. |
| **L-3-3** | A receiver sends only to candidates in their kind's own address space (IPv6 global unicast; a host candidate private; a reflexive one public; no loopback, link-local or multicast), at most eight, IPv6 first, then host, then reflexive. |

## 4. The punch and the path

| Clause | Rule |
| --- | --- |
| **L-4-1** | Both ends send a PROBE to every target every 200 ms for two seconds, then every 500 ms, and answer every PROBE that opens with an ACK to its source. The link is up at the first ACK that opens. A PROBE that opens from an address the peer never listed makes that address a target. No answer in 10 s and the attempt ends; the offerer waits 1 min, doubling to 6 h, before the next, and starts over on a new default network. |
| **L-4-2** | Each end sends to the source of the last datagram that opened (other than a PROBE once up). A link sends no keepalive: one that has carried no DATA either way for 60 s closes with a BYE, and one that hears nothing that opens for 65 s ends. A **held** link (a live session that must outlast its pauses) instead sends a keepalive after 90 s with nothing sent and ends after 200 s hearing nothing. A BYE ends either at once. The numbers sit inside the shortest idle-path life measured (2–4 min behind a home router's IPv6 firewall; at least 4 min cellular to cellular). |

## 5. The lane

| Clause | Rule |
| --- | --- |
| **L-5-1** | Keys: `HKDF-SHA256(ikm = kOffer ‖ kAnswer, salt = id, info = label ‖ offererId ‖ answererId, L = 32)`, label `knit/link/v1/lane/o2a` for the offerer's sending key and `knit/link/v1/lane/a2o` for the answerer's, node ids as their 16 canonical bytes in role order. |
| **L-5-2** | A datagram is `version(1) = 1 ‖ counter(8, big-endian) ‖ AES-256-GCM(plaintext) ‖ tag(16)`; nonce = 4 zero bytes ‖ counter; AAD = version ‖ `knit/link/v1` ‖ the two node ids' canonical bytes in byte order ‖ `id`. |
| **L-5-3** | A sender's counter starts at 0 and only rises, shared by every path of the attempt; it never seals two datagrams under one counter. A receiver opens a counter once, and refuses one more than 1024 below the highest it opened. A datagram that fails is dropped silently. |
| **L-5-4** | Plaintext: one type byte, then its body — 1 PROBE (8-byte nonce), 2 ACK (the nonce echoed), 3 KEEPALIVE, 4 BYE, 5 DATA. An unknown type is read as a keepalive. |

## 6. The stream

A link carries one ordered byte stream, the one `FramedLink` runs over on a radio's socket, in DATA bodies (L-5-4).
It is our own ARQ, chosen over a QUIC stack (Kwik) in ADR 2026-10.a5yj's stream amendment; `LinkStream` is the seam
either sits on. These clauses freeze for the iOS port with the rest of this file at the spike's go/no-go.

| Clause | Rule |
| --- | --- |
| **L-6-1** | A DATA body is one segment: `flags(1) ‖ ack(4) ‖ limit(4) ‖ n(1) ‖ n × (start(4) ‖ end(4)) ‖ [seq(4) ‖ payload]`, integers unsigned big-endian. Flag `0x01` SEQ: the segment carries `seq` and a payload of 1 to 1096 bytes; without it the segment is a bare ack and ends after its ranges. Flag `0x02` ASK: answer with an ack at once. Other flag bits are ignored. `n` is at most 8. A malformed segment is dropped. |
| **L-6-2** | Every segment carries its sender's receive state: `ack`, the lowest `seq` it lacks; the `n` ranges `[start, end)` it holds above `ack`, highest first; and `limit`, the `seq` it will not take at or beyond. `limit` is the next `seq` the reader has not consumed plus 512, and a sender assumes 512 before it hears one. A sender takes the largest `limit` it has heard. |
| **L-6-3** | `seq` numbers whole segments from 0 for the link's life and never wraps: at 2³² the stream ends. A retransmission carries its first `seq`, sealed under a fresh lane counter (L-5-3). A whole datagram never exceeds 1200 bytes. |
| **L-6-4** | A receiver takes a segment below its `limit`, holds it until the segments before it arrive, and delivers in order. It acks at once a segment out of order, a duplicate or an ASK, else every second segment, or 20 ms after the first unacknowledged one. A bare ack is never acked. Reading a quarter of the window advertises the new `limit`. |
| **L-6-5** | A sender keeps at most its congestion window of segments in flight, and none at or past the peer's `limit`. Loss is judged from acks only (RFC 9002): a segment sent before an acknowledged one is lost once three transmissions sent after it are acknowledged, or once 9/8 of the larger of the smoothed and the latest round trip has passed since it went. The probe timer — the smoothed round trip, plus four deviations (at least 10 ms), plus 20 ms of ack delay, from 1 s before a round trip is known, 100 ms to 8 s, counted from the last segment sent and doubling per expiry — declares nothing lost: it sends 2 segments past the window and the pace (the oldest outstanding one again, then a new one or the next oldest), each with ASK. Lost segments go first. The window starts at 10 segments, grows by one per segment acknowledged up to the first loss and by one per window after, halves once per round of losses, and falls to 2 when losses are found after 3 probe timeouts in a row. Sends are paced: at most 4 back to back (or 2 ms of the pace, if more), refilled at the window per smoothed round trip, ×2 while the window grows from its start and ×1.25 after. A sender stopped at the peer's `limit` with nothing in flight sends an ASK each probe period. |
| **L-6-6** | The stream is the link's: it ends when the link closes (a BYE, idle or silence), after what arrived is read. There is no half-close; closing the stream closes the link with a BYE. A segment counts as the link's data (L-4-2). |

## 7. The link as a transport

An up link's stream is one more child of the composite (`DirectLinkTransport`), last in preference, so a peer a radio
also links is served by the radio. It is not a radio: its peers are never nearby, never met and never a radio path, it
earns no Your mesh credit, and it has no say in the mesh's health.

| Clause | Rule |
| --- | --- |
| **L-7-1** | Over the stream run the radios' own framing: a two-way HELLO (`LinkHandshake`), the offerer writing first, then `FramedLink`'s records. A HELLO that names anyone but the link's peer ends the link, as does a handshake not finished in 15 s. |
| **L-7-2** | A frame crosses a link, either way, only when it is addressed between the two; or either one's profile, or a ratcheted DM between them (the spool's DM rule); or a frame of a group both are in, by the spool's group rule against its founding roster; or a frame addressed between two members of such a group (a sender-key seed, a delivery tick, and — sealed alike, never told apart — their DMs to each other); or a key request naming only the two and their shared groups' members; or (outgoing) a blob control frame whose every hash belongs to a conversation addressed to the peer. Anything else is dropped where it is, on the way out or on the way in. |
| **L-7-3** | A file goes out over a link only when it is an attachment of a conversation addressed to the peer, or the sender's own avatar. |
| **L-7-4** | Only a frame or a file sent or taken is use (L-4-2's DATA, under a transport): the handshake, a digest and the stream's own segments are not, so a link that only keeps itself in sync idles out, and an answer whose link moved no frame came to nothing (L-1-2). |
| **L-7-5** | A message that arrived over a link records its plane as `DeliveryPlane.DirectLink` (6). The chat shows it as the link's own, never as over the Internet, since a link between two phones on one network never leaves it: two vertical arrows, "Delivered/Arrived over a direct link". A file shows the link whenever its proof of bytes (L-7-6) came over one, on the recipient its own and on our DM send the peer's, whatever plane its message crossed (ADR 2026-10.a5yj's 2026-10-28 amendment). |
| **L-7-6** | An attachment a link delivered, hash-checked and stored, or the peer's answer over a link to the holder query for one, is proof of bytes as a radio's is (`docs/SPOOL_PROTOCOL.md` C-9.5-8, ADR 2026-10.vy27): a DM photo so proved never goes up to a spool. While the link is up its peer counts as in sight for the spool's push-half deferral (`docs/SPOOL_PROTOCOL.md` §9.5), so a DM photo sent to it waits for that proof rather than going up at once — through the ack grace and one proof round on the link's evidence alone, and for as long as the link is up when a radio already carried the message (§9.5's second half); the round after the link goes, it is pushed. A blob counts as the pair's when any conversation naming it is addressed to both ends, since a forwarded picture keeps its hash (ADR 2026-10.mptm). The query goes over a link only for an attachment it carries (L-7-2), and a link's peer is still asked for the bytes after every radio holder. |
| **L-7-7** | The spool first, a link as the last resort: a link is offered only when something of ours to a contact, sent in the last 15 min, has no spool path — a DM, or a post in a group they are in, that has gone 20 s without their delivery tick, or at once a DM attachment that every connected spool carrying the pair refuses to take (no attachments there, over its budget or the ticket's chunk limit, our uploads locked or spent) and whose hold is not proved — while no radio links them and no link to them has been up since it was sent. One offer per contact at a time, at most two links up or offered at once, the longest-waiting contact first; a refusal that sent nothing (not qualified, no cast) rests that contact a minute, and a contact whose punch failed or who did not answer rests on the attempt backoff (`PunchPolicy.backoffMs`). Low-power mode offers and answers none. A spool this phone holds open still carries every message as before: a link adds a path, it never holds one back. The sender proves such a photo over the link: every demand round (10 s) while the link plane runs, it sends the holder query (L-7-6) to each contact a link holds and no radio does, for each DM attachment of ours to them sent within the custody TTL (24 h) that every spool carrying the pair still refuses and that is not proved held, read afresh from the database each time. It stops at the proof, when the link goes or at the end of that TTL, never after a count: a FETCHING answer is asked again. A group's picture is never asked about. |

## 8. Vectors

`vectors/wire-v1.json` carries a `LinkSignal` OFFER; the keyed vectors carry an L-5-1 derivation. Change them only
together with this file.
