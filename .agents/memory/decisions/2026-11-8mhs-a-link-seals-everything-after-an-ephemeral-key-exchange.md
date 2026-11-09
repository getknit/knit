---
id: "2026-11.8mhs"
slug: a-link-seals-everything-after-an-ephemeral-key-exchange
title: "A link seals everything after an ephemeral key exchange"
date: 2026-11-08
topics: [mesh, security]
---

# ADR 2026-11.8mhs — A link seals everything after an ephemeral key exchange

Status: Accepted (2026-11-08). Lead of a paired change with knit-ios (knit-ios #51), for knit-next #286 option 1; it
closes #274's relayed handshake for every sealed link. The contract is shared law, byte for byte, and iOS mirrors it.

**What was observed.** Every Bluetooth and Wi-Fi Aware link travelled in the clear. Bluetooth links use insecure
L2CAP, and every Wi-Fi Aware data path is keyed by one PMK built into the app. A radio in range read all of it without
linking: the HELLO with the node id, the PROOF that ties the id to its keys, the DIGEST, every frame's envelope (who
talks to whom), and the cleartext frames. The link proof (ADR 2026-11.264w) bound the id to its key but not to the
channel, so a device in range of both peers could forward a real handshake and sit in the middle of a proven link.

**What changed.** Two upgraded builds run an ephemeral X25519 exchange before anything else. Everything after it
rides AES-256-GCM under keys from that exchange, and the PROOF signs both ephemeral keys.

- **The bit.** `Protocol.CAP_LINK_SEAL = 0x100000`, the next free bit, in `LOCAL_CAPABILITIES` (now `0x1C2FFF`),
  carried only beside `CAP_LINK_PROOF`. It rides the HELLO, the profile and the Wi-Fi Aware SSI. The profile's copy is
  informational: nothing decides from it. The capability republish of 264w bumps the profile once after the upgrade.
- **The advert flag.** `BleAdvertPayload.FLAG_LINK_SEAL = 0x10`, bit 4 of byte 23, set in every advert exactly when
  the HELLO carries the bit, in the clear and blinded forms and on the Coded set alike. It is a fact about the build,
  where the byte's other bits are facts about the controller. Every shipped parser tests only the bits it knows. The
  SSI has no flags byte; there the bit in the SSI's caps is the signal.
- **The dial decision.** A dialer opens with the sealed form exactly when the advert it dials from says the responder
  can seal: the flags byte of the Bluetooth advert heard at the address dialled (service data, or a cached GATT
  payload), or the SSI caps on Wi-Fi Aware. That reverses, for this one decision, the rule that the SSI's caps never
  decide the handshake; the proof decision still reads the reply's caps. Nothing else feeds it: not the stored
  profile, not a memory of earlier links. Direct links never seal (their lane is already encrypted, ADR 2026-10.a5yj).
- **The records.** `LinkFraming.Type.KEY_SHARE`, tag 10: exactly 32 bytes, a fresh X25519 public key per connection
  attempt from `SecureRandom`. `LinkFraming.Type.SEALED`, tag 11: `[0x0B][u32be len][ct ‖ tag(16)]`, `len` 17 to
  65,552 (plaintext 1 to 65,536), the 5 header bytes as AAD. Both lengths are checked from the header before the
  payload. Every shipped build throws on both tags and drops the link, so they are written only on a link opened in
  the sealed form, which only a peer advertising the flag is dialled with. Tag 12 stays unknown.
- **The exchange** (`LinkHandshake`). D writes its KEY_SHARE and reads R's; R, reading a KEY_SHARE as the first
  record, writes its own at once, before any admission decision. From then on every byte each side writes is inside
  SEALED frames. Inside the seal the link-hello-proof exchange runs unchanged — the same steps, R1-R4, claims and
  counters — except that both sealed HELLOs must carry `CAP_LINK_PROOF`, `CAP_LINK_SEAL` and a valid nonce (so a sealed
  link is always a proven one), and the PROOF signs the v2 transcript. R's refusal still closes without a reply HELLO,
  after its KEY_SHARE: D reads "KEY_SHARE, then nothing", or nothing where the KEY_SHARE belongs, as today's no-reply
  refusal. A first record that is a HELLO is
  the plain form, exactly as before. Each wait is bounded on its own (Bluetooth: one watchdog per stage; Wi-Fi Aware:
  the per-read `soTimeout`).
- **The keys** (`LinkSeal`). `ss = X25519(own ephemeral, peer's)`, refused when Tink rejects the point or `ss` is all
  zero; `kD2R` / `kR2D = HKDF-SHA256(ikm = ss, salt = eD ‖ eR, info = "knit/link/v1/seal/d2r" | "…/r2d", L = 32)`. D
  seals with `kD2R`. The private halves, `ss` and the keys live in memory for the link's life; an ephemeral derives
  once.
- **The frames.** The nonce is `00000000 ‖ u64be(n)`, `n` the count of SEALED frames that side has written, implicit
  on the wire, so a reordered, replayed, dropped or reflected frame fails to open. A side closes rather than seal frame
  2^48. The plaintexts, concatenated, are exactly today's record stream; frame boundaries carry no meaning.
- **The sealing socket** (`SealedLinkSocket`), the precedent of direct links' `LinkArq`: a `LinkSocket` whose streams
  are the inner record stream, so `LinkHandshake` and `FramedLink` run on it unchanged. It seals each flush (a full
  64 KiB buffer seals early; an empty flush seals nothing), takes the counter under its lock before sealing, and dies
  on a write error, so no counter is ever sealed twice. Its `close()` never takes that lock: a stalled Bluetooth write
  is ended only by a close (#203). Below it the raw socket's `SocketStamps` and `LinkSilence` keep counting the
  ciphertext bytes, so silence and stall detection see the same liveness. `FramedLink.pendingDigest` swaps a digest
  on plaintext, before sealing.
- **The v2 transcript** (`LinkProof`): `"knit/link/v2/proof" ‖ role ‖ eD ‖ eR ‖ u32be(len hD) ‖ hD ‖ u32be(len hR) ‖
  hR`. The PROOF payload is unchanged. The v2 label keeps the forms apart: a v1 signature never verifies on a sealed
  link nor a v2 one on a plain link, so a relay can't open the sealed form toward one peer and the plain form toward
  the other. Plain links keep v1 byte for byte; v2.11.0 depends on it.
- **No plain link between two sealing builds** (contract §7). On a link opened in the plain form, when both HELLOs
  carry `CAP_LINK_SEAL`, R refuses before replying and D refuses after reading the reply, writing no PROOF. It runs
  before R1 and the admission refusals and counts once. The advert flag is unauthenticated, but the HELLO bits are
  signed: a relay that strips the bit from a HELLO breaks the PROOF, and one that strips the flag from an advert gets no
  link at all. One that strips both HELLOs' proof bit too gets a plain unproven link only where R1 lets one through:
  between phones that don't yet hold each other's profile (below).
- **Failed seals.** Another record where R's KEY_SHARE belongs, a KEY_SHARE of the wrong length or a rejected point, any record
  other than SEALED after the exchange, a frame out of range or failing to open, an inner tag 10 or 11, a first inner
  record that isn't a HELLO, or a sealed HELLO without both bits and a nonce. In the handshake it closes the link (D
  backs off as for a failed HELLO; R without a backoff); on a live link it closes the link. A seal fault never feeds
  `LinkClaims`; a PROOF that fails inside the seal counts `linkProofFailed` only. An EOF, a raw read error or a timeout
  is never a failed seal, on either side, at any stage, mid-frame included: it is today's no-reply refusal, HELLO
  timeout or link drop. A busy responder closes before it reads a byte, so counting it would make the counter read
  contention; `linkSealFailed` counts only bytes that arrived and were wrong, and a clean run leaves it at zero.
- **Counters** `linksSealed`, `linkSealFailed`, `linkSealRefused` (`MeshMetrics`, `metricsJson`; knit-ios reports the
  same keys). Nothing logs keys, secrets, nonces or plaintext.

The alternatives were #286's options 2-4 (a PMK per link, a PMK per responder, Android's Wi-Fi Aware pairing): each
covers one radio only, and none closes the relayed handshake on Bluetooth. A link key with a MAC per record (264w's
named follow-up) is what this is, with encryption added for the same round trip.

**What it costs, and what it does not cover.**

- Links with v2.10.x and v2.11.0 stay plain: their HELLO, PROOF and envelopes stay readable, and v2.11.0 links stay
  relayable (264w's accepted cost). The exposure ends for each pair once both phones run this build. A relay can still
  pose as a v2.10.x build under an id the victim does not yet know as proof-capable; R1 and R4 bound that as before.
- Sizes and timing still show: there is no padding.
- Outside a link stays readable: the advert and SSI (#285 covers the id there), the iPhone's GATT payload, Wi-Fi
  Aware follow-up messages (cues and fast frames), Bluetooth side-channel pages and the doorbell write.
- A phone that links on purpose still learns the node id: sealing stops listening, not linking.
- Every inbound dial costs R one X25519 key, one DH and a 37-byte write before admission, including dials R then
  refuses. A refused dialer learns only R's ephemeral key.
- Link-up takes one more round trip: KEY_SHARE, KEY_SHARE, HELLO, HELLO+PROOF, PROOF. An iPhone suspended between
  stages has two windows to lose a link in. Bluetooth's in-flight slot (and so the scan pause) holds one more round
  trip, at most 5 s more on a failure.
- A stale flag costs a dial. A peer that downgraded inside the presence linger throws on tag 10 and closes: D sees no
  reply and backs off, as for any refusal. A peer that upgraded inside it is dialled plain and refused under §7. Android forgets
  a cached GATT payload once when a plain dial made from it drew no reply, our HELLO carried the bit and the payload
  lacked the flag, so the next dial reads it fresh rather than waiting out the iPhone's address rotation
  (`DialPlan.forgetsGattPayload(HANDSHAKE)` stays false for every other handshake failure). The reverse is not
  forgotten: a cached payload still carrying the flag after its owner stopped sealing draws sealed dials with no reply
  until an existing rule forgets it (the address rotates, the payload goes unheard, or a dial fails at connect).
  Forgetting on a sealed dial's silence would fire on every busy, suspended or already-linked iPhone and spend the GATT
  read budget. Only a downgraded iPhone hits it.
- On Wi-Fi Aware a `SealFailed` or `SealRefused` dial takes the growing claim-refused wait (`NanClaimRefusalPolicy`),
  lifted by a fresh discovery handle, which is what an upgrade or downgrade brings. No KEY_SHARE is indistinguishable
  from a busy responder closing the socket, so it keeps the flat retry. A stale sealed dial toward an old pure responder
  leaves it idle until the flat retry gives up inside the linger.
- Wi-Fi Aware's handshake reads are bounded per read, not per stage, so a peer that drips a byte every few seconds
  holds the accept slot; the sealed form adds one wait to that existing gap (#279).
- A relay can deny a sealed link — strip flags, drop frames — as a jammer can. It can't read it or write into it, and
  between two phones that hold each other's profile, or that proved each other this run, it can't downgrade it: R1 refuses the bit-less HELLO it would have
  to forge. At a first meeting it can strip the flag and both HELLOs' proof and seal bits and pose each phone as a
  v2.10.x build, which links plain and unproven: the legacy pose of the first cost above, bounded by R1 and R4.
- The fixed Wi-Fi Aware PMK stays. It still keeps the data path off open air, and it now protects legacy links only.
- A future iOS writer that blinds its GATT payload (`NEXT_WIRE_BREAK.md` item 13) would hide this flag from today's
  readers, which read nothing from a blinded payload; that design has to carry the flag some other way.
- No rekeying: the 2^48 frame cap closes a link first.

The mesh lab has no radio layer (`LabTransport` registers links without a handshake), so no lab scenario covers the
seal. `LinkHandshakeTest` over pipes (both forms, both roles, every failed seal, §7 both ways, old-build-shaped peers),
`SealedLinkSocketTest`, `LinkSealTest`, `LinkProofTest`, `LinkFramingTest`, `BleAdvertPayloadTest`, the planes' dial
and accept decision tests, and the device trials (T1-T6) do. #286's `assertConverged` scenario waits for a socket mode
in the lab. `vectors/keyed-v1.json` → `link.seal` pins the key shares, keys, sealed HELLOs, both transcripts and PROOF
frames, the splits and the rejects; `ios-emitted-v1.json` → `link.seal` (knit-ios `68ce9f4`) pins Carol's key shares,
keys, sealed HELLOs and transcripts byte for byte, and her randomized sealed PROOFs, which `IosEmittedVectorTest`
opens and verifies and checks fail under the other role and the v1 label.
