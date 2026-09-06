---
id: "2026-09.ggq4"
slug: a-heard-meshtastic-post-is-verified-against-the-board-key-in-its-author-s-profil
title: "A heard Meshtastic post is verified against the board key in its author's profile"
date: 2026-09-05
topics: [lora, meshtastic, mesh]
---

# ADR 2026-09.ggq4 — A heard Meshtastic post is verified against the board key in its author's profile

Status: Accepted (2026-09-05) — amends ADR 2026-09.26q3 (the "resolved contact is not a verified one"
paragraph now has an exception) and ADR 2026-09.sre4 (the caveat covers the *unverified* match only).

**What was observed.** ADR 2026-09.26q3 lined a heard post up with a contact through the node number
their profile claims (`ProfileContent.loraNode`) and called the match what it was — self-asserted — leaving
"signature-backed confidence" on the roadmap with a premise attached: that Meshtastic 2.8 signs its broadcasts
but "hands the phone no verdict", so using the signature would mean decoding it blind. The premise was wrong.
Read against the firmware the lab boards run (`v2.8.0.47db0e3`, and the `7239fe8` alpha before it), the
phone API carries all four fields: `Data.xeddsa_signature` (10, the 64-byte signature itself),
`MeshPacket.xeddsa_signed` (22, the board's own verdict, computed on receive against the key its NodeDB
holds and never trusted off the air), `NodeInfo.has_xeddsa_signed` (14) and `DeviceMetadata.has_xeddsa`
(14). The scheme is XEdDSA (Signal): the board's Curve25519 key signs as an Ed25519 key with the sign bit
normalised away (`CryptoEngine::curve_to_ed_pub`, `y = (u − 1)/(u + 1)`), the signature is a standard
`R ‖ s`, and the signing input is `LE32(from) ‖ LE32(id) ‖ LE32(portnum) ‖ payload`
(`CryptoEngine::buildSigningBuffer`), so a signature cannot be re-attributed, replayed under another packet
id or moved to another port. On 2.8 a node number *is* the CRC32 of its public key (`NodeDB.cpp`,
`my_node_num = crc32Buffer(public_key)`); both Knit boards check out, the pre-2.8 nodes on the local mesh do
not. And the signable size is a cliff on the encoded `Data`: 165 B for a Knit frame (`PRIVATE_APP`, a
two-byte portnum, the figure ADR 2026-09.mhs5 measured) and **166 B** for ordinary text (a one-byte portnum).

Two things were measured rather than read. A 33-minute serial capture on the USB board `!e681a7c3` (a
`FromRadio` raw parser — the serial CLI decodes none of these fields) caught one inbound frame from the
other Knit board, `!64761b18`, arriving `sig=64B xeddsa_signed=True`, and the signature verified off-board
under OpenSSL's Ed25519 with the key converted as above — every tamper of `from`, `id`, `portnum` or a
payload byte rejected. And the lab board's own keypair, read over serial, confirmed the conversion on a key
whose Edwards image needed the negation branch. That mesh had exactly two signers among 133 nodes: the two
Knit boards. The rest are still on 2.7.

**What changed.** The profile carries the board's key beside its number, and the phone checks the
signature against it. `ProfileContent.loraKey` / `ProfilePayload.loraKey` (base64 of the 32 raw bytes,
the `pubKey`/`avatarHash` string convention) and `ProfileV2` label 7 (raw bytes) — the ninth additive
profile change (`docs/WIRE_COMPAT.md`), nullable, elided while unbound *and* while the board does not sign
(a key that never signs verifies nothing, and a pre-2.8 user's profile should not grow by 54 B for it). It
rides all three layouts under the presentation watermark like `loraNode`, and the frame's own Ed25519
signature covers it, which is the whole binding: the *user* is asserting "this is my radio's key". The board
reports both in one `BoardBinding` at `Ready` (`LoraMeshTransport.onBoardBound`), the settings write both in
one edit (`SettingsStore.setLoraBoard`) so the profile republishes once, and `MeshManager` folds the pair
into one arm of its five-flow combine. `peers.loraKey` stores the claim (DB v10) and `messages.originSigned`
freezes what the signature proved, judged once in `InboundPipeline.deliverMeshPost` beside the contact
resolution and never again — a contact re-keying their board later does not re-judge what their old board
said. Four values, an append-only registry on `MessageEntity`: `ORIGIN_UNSIGNED` (no signature, or nothing
to check it against), `ORIGIN_SIGNED_BY_BOARD` (our own board's `xeddsa_signed`: the radio that has been
using this number sent it — no Knit identity behind it), `ORIGIN_SIGNED_BY_CONTACT` (verified on this phone
under the key the resolved contact's own profile names — the only value that changes the styling) and
`ORIGIN_SIGNATURE_MISMATCH` (the number resolved to a contact with a key, the packet was signed, and it
failed: some other radio is on their number, so the row is **not attributed** and is drawn as a stranger
that says so). `mesh/crypto/XeddsaVerify` does the check — a dozen lines of `BigInteger` for the
conversion and Tink's `Ed25519` primitive over the firmware's exact input, never throwing — and the sink
carries the payload *as heard* (`MeshPost.payload`) beside the readable body, because the body is trimmed
and clamped and is not what was signed.

The UI follows the registry. A `CONTACT` post takes a Knit author's styling: the name in the primary colour,
a shield beside it (the DM header's verified glyph at label size — Meshtastic's own apps draw a shield on a
signed broadcast), and an avatar that opens the profile directly, by peer id. Everything else keeps ADR
26q3's unverified styling and ADR sre4's caveat-first avatar; `BOARD` adds the word "signed" to the
provenance line, `MISMATCH` the words "signature doesn't match" on a stranger's bubble, and `UNSIGNED` adds
nothing — **unsigned is never evidence**: pre-2.8 radios never sign and a post past the cliff cannot be. The
room's strip now says "names not verified unless marked", and the caveat says what it can — that the post
carried no signature Knit could check against that profile.

The other half is the composer. On a board that signs (`LoraFacts.signs`, off `LoraAirtime.signing`, which
now takes the firmware's own `has_xeddsa` ahead of the version parse) the Meshtastic room caps a post at
`PublicPostPolicy.MAX_SIGNED_TEXT_BYTES` — 166, `MeshtasticProto.maxSignedPayload(PORT_TEXT_MESSAGE)`,
the same formula that gives the bench-verified 165 for `PRIVATE_APP` — so every post Knit sends leaves
signed; on an older board the 200-byte client convention stands. `postToPublicChannel` trims to the same
budget (`onAirBudget(signing)`), so a draft typed while the facts still said 200 never crosses the cliff
unsigned. **Knit's own frames are untouched**: ADR 2026-09.mhs5's padding, `LoraFrameCodec`,
`LoraAirtime.padTo` and `MAX_SIGNED_PAYLOAD` (still 165) do exactly what they did.

The alternatives, and why they are not this: **trust the board's verdict alone** — its key for a number is
learned off the air, first-contact from a signed NodeInfo whose CRC32 matches, else TOFU from an unsigned
one, and nothing binds it to a Knit identity; it is carried as the weaker `BOARD` signal and no more.
**Verify on the board instead**, by seeding it with a contact's key (`AdminMessage.add_contact`, 66) — a
real lever, since a board holding the right key also keeps a `BALANCED`/`STRICT` receiver from dropping the
contact's posts, but it is a second key store to keep consistent and is deferred to the roadmap. **Drop
`loraNode` for CRC32(key)** — pre-2.8 boards have MAC-derived numbers and no signatures, and every shipped
receiver matches by the number. **A 32-bit binding through the number alone** — a forger needs ~2³²
keygens to land on a contact's number, hours on a GPU; the full key is what makes it a key.

**What it costs, and the trap.** 54 B on the cleartext profile while a signing board is bound
(`CoordinationPlaneSizeBudgetTest`: the max-size profile transcodes to 432 B, still two LoRa packets at the
ESP32 cap with 16 B to spare; the max-size intro to 600 B, still three — its legacy `0x03` form crossed the
three-packet ceiling by 3 B, which the LoRa plane has not sent since the ADR 060 flag-day, so that test now
measures the transcoded form), DB v10, one profile field. What it proves is bounded and the strings say
so: the *radio* named in a contact's profile transmitted the words, not that the person did — a shared,
borrowed or stolen board signs for whoever holds it, and so does any phone bonded to it, and the board's
private key is readable over its admin paths. The trap is on the board side: a signed packet that fails
against the key in the *receiving* board's NodeDB is dropped before the phone ever sees it, whatever the
policy — so a stale or planted key for a contact's number (an unsigned NodeInfo the board took on trust)
makes their posts vanish, undiagnosably from Knit. `packet_signature_policy` is still the user's: `BALANCED`
is safe for the plane (Knit's padded frames could never have been signed, so it never drops them),
`STRICT` loses every Knit frame. Left unflagged on purpose: an unsigned post from a contact whose profile
advertises a key — a downgrade shape a 2.8 board never produces under 166 B, but one a long post or a mixed
mesh produces honestly. Pinned by `XeddsaVerifyTest` (the on-air vector, the lab key's conversion, the
text-port vector, every tamper), `MeshtasticProtoTest` (the four fields, the 63-byte non-signature, the
166/165 cliff), `InboundPipelineTest` (each of the four verdicts, the frozen verdict across a re-key, the
mismatch that is not the blocked contact's post, the key on the peer from both profile paths),
`MeshManagerTest` (the key beside the number, one flood), `GoldenVectorTest` (three vectors),
`FrameTranscoderTest`, `KnitDatabaseMigrationTest` (11 → 12), `LoraMeshTransportTest` (the key reported only
by a signing board; the trim at 166 and at 200), `ChatViewModelTest` and `ChatMeshRoomTest` (the shield, the
direct tap, the words on the provenance line, the 166-byte cap).

The ledger prices the room's posts at the text port's own cliff too (`LoraAirtime.admits`/`record`
take `signedUpTo`): a post at the 166-byte cap is signed air, and was being booked as an unsigned packet
one byte past the Knit-frame cliff.

**Verified.** Unit vectors as above, including a genuine board-made signature. **On hardware, 2026-09-05**
(Pixel 7 "Alex" on `!64761b18`, Pixel 9 "Walter" on `!e681a7c3`, both Heltec V4 / 2.8.0.47db0e3, in the
same room): a post in each direction arrived `signed=true boardVerified=true` on the far phone's transport
log and counted `meshPostMatched 1 / meshPostVerified 1 / meshPostSignatureMismatch 0`; each phone's room
drew the other's name in the primary colour with the shield, the provenance line without a "signed" word,
and the strip reading "names not verified unless marked" — beside a two-hour-old post from the same contact,
heard before this build, still muted and shield-less (the migration's `ORIGIN_UNSIGNED`). A post of exactly
166 bytes left the Pixel 7 as `lora tx public 166B` and arrived signed and verified (`meshPostVerified 2`),
so the text cliff is where the arithmetic put it. Not exercised on hardware: a mismatch and a board-only
verdict (this mesh has no third signer — its other 131 nodes are still on 2.7).
