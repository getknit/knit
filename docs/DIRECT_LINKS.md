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
It is our own ARQ, the spike's candidate: Kwik was weighed and set aside in ADR 2026-10.a5yj's draft stream amendment,
which stands only after the phones' throughput trial, and `LinkStream` is the seam either sits on. Until then these
clauses are not frozen for the iOS port.

| Clause | Rule |
| --- | --- |
| **L-6-1** | A DATA body is one segment: `flags(1) ‖ ack(4) ‖ limit(4) ‖ n(1) ‖ n × (start(4) ‖ end(4)) ‖ [seq(4) ‖ payload]`, integers unsigned big-endian. Flag `0x01` SEQ: the segment carries `seq` and a payload of 1 to 1096 bytes; without it the segment is a bare ack and ends after its ranges. Flag `0x02` ASK: answer with an ack at once. Other flag bits are ignored. `n` is at most 8. A malformed segment is dropped. |
| **L-6-2** | Every segment carries its sender's receive state: `ack`, the lowest `seq` it lacks; the `n` ranges `[start, end)` it holds above `ack`, highest first; and `limit`, the `seq` it will not take at or beyond. `limit` is the next `seq` the reader has not consumed plus 512, and a sender assumes 512 before it hears one. A sender takes the largest `limit` it has heard. |
| **L-6-3** | `seq` numbers whole segments from 0 for the link's life and never wraps: at 2³² the stream ends. A retransmission carries its first `seq`, sealed under a fresh lane counter (L-5-3). A whole datagram never exceeds 1200 bytes. |
| **L-6-4** | A receiver takes a segment below its `limit`, holds it until the segments before it arrive, and delivers in order. It acks at once a segment out of order, a duplicate or an ASK, else every second segment, or 20 ms after the first unacknowledged one. A bare ack is never acked. Reading a quarter of the window advertises the new `limit`. |
| **L-6-5** | A sender keeps at most its congestion window of segments in flight, and none at or past the peer's `limit`. A segment is lost once three transmissions sent after it are acknowledged, or when the retransmission timer (RFC 6298, from 1 s or the punch's round trip, 200 ms to 8 s, doubling per timeout) expires, which presumes every segment in flight lost. Lost segments go first. The window starts at 10 segments, grows by one per segment acknowledged up to the first loss and by one per window after, halves once per round of losses, and falls to 2 on a timeout. Sends are paced: at most 4 back to back (or 2 ms of the pace, if more), refilled at the window per smoothed round trip, ×2 while the window grows from its start and ×1.25 after. A sender stopped at the peer's `limit` with nothing in flight sends an ASK each timer period. |
| **L-6-6** | The stream is the link's: it ends when the link closes (a BYE, idle or silence), after what arrived is read. There is no half-close; closing the stream closes the link with a BYE. A segment counts as the link's data (L-4-2). |

## 7. Vectors

`vectors/wire-v1.json` carries a `LinkSignal` OFFER; the keyed vectors carry an L-5-1 derivation. Change them only
together with this file.
