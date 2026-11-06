# Wire vectors

Byte-exact fixtures that every Knit port is tested against. The Android tests here and the iOS port's tests
(`knit-ios`) read the same files, so a change that moves a byte fails on both sides the same day.

| File | Written by | Checked by | What it pins |
| --- | --- | --- | --- |
| `wire-v1.json` | `GoldenVectorTest` | `GoldenVectorTest`, knit-ios | The definite-length CBOR of one fixed instance of every wire type (and a second where an optional field changes what it means, such as a resumed `blobreq`), the raw-key bundle probe with its node id, and `languageRegistry`, the append-only list a chat frame's declared `lang` / `reads` index into (`GoldenVectorTest` fails unless it is a prefix of `LanguageRegistry.CODES`); a direct link's `LinkSignal` OFFER (experimental, ADR 2026-10.a5yj); a direct link's `LinkSignal` OFFER and DECLINE (experimental, ADR 2026-10.a5yj) |
| `keyed-v1.json` | `KeyedVectorTest` | `KeyedVectorTest`, knit-ios | Two identities from fixed keys, the profile and room post Alice signs, a v1 DM she seals to Bob, their safety number, the HELLO, DIGEST, file headers (whole and resumed), custody fingerprint and advert, her profile padded to exactly the profile cap and one byte over it (`aliceProfileAtCap`, `aliceProfileOverCap`, each with its `signedBytes`, #234: the first pins, the second is refused before it is decoded; replayed through the pipeline by `InboundPipelineTest`), a direct link's lane keys (`linkKeys`: both `k`s, the link id, Alice offering, Bob answering → the o2a and a2o keys, `docs/DIRECT_LINKS.md` L-5-1), and a link proof (ADR 2026-11.264w, under `link`: Alice dials Bob with nonces `00..0f` and `10..1f`, both HELLOs at caps `40009` — `helloProofDialer` and `helloProofReply` with their records, `proofTranscriptDialer` and `proofTranscriptResponder`, `proofDialer` and `proofResponder` as records Android rebuilds byte for byte (Tink is deterministic; iOS checks header and keys and verifies the signature), and `proofRejects`, each a record that must fail against the dialer's transcript with its reason), and blinded adverts (ADR 2026-11.xp6d, under `blind`, built by `BlindVectors`: Alice's and Bob's presence keys; `slotOf` and `window` cases; `tags` at slot −1, slot 0, all four restarts of one slot, Bob's, and `aliceKeyUnderBob` (Alice's key under Bob's id, a different tag from `aliceNowRestart0`, so no port can leave the owner out), each with the name and raw 16 bytes of its owner (`owner`, `ownerId`), its 44-byte MAC input and MAC, so a port checks every step of the layout; `hasPrefix` cases; `link`, Alice dialing Bob's tag with nonces `40..4f` and `50..5f` at caps `c0009`, both tagged HELLOs, the transcripts and both PROOF records, and `toldRewritten`, Bob's proof refused over a reply whose `t` was changed; `helloFields`, the `t` / `a` parse contract case by case, a duplicated exact-shape letter read as absent; `advert`, Alice's blinded service data (her tag, caps `c0009` of which the advert carries the low byte, PSM `0x83`, `FLAG_BLINDED` and `FLAG_AWARE`, the digest cue zeroed) with its parse, a `FLAG_BLINDED` advert over prefixless bytes that a reader drops, and a clear advert carrying her tag's bytes that stays a clear id; `tagNext`, the `TAG_NEXT` record (type 9, exactly 16 bytes) naming her next slot's tag, and a 15-byte one a reader refuses as a framing error) |
| `ratchet-v1.json` | `RatchetVectorTest` | `RatchetVectorTest`, knit-ios | The forward-secrecy ratchet's known answers for the iOS port (companion change A9): X3DH, epoch derivation, the message chain, the v3 nonce and header binding, prekey signing, the root tag, the group form's chain and a fixed-IV AES-GCM seal, walked through one first exchange with every key draw recorded (`kats`), the signed ratchet profiles with the prekey pin each leaves (`profiles`, replayed through `InboundPipeline` by `InboundPipelineTest`), and DM transcripts through the session layer: whole Alice–Bob exchanges (`dm`: first contact and the epoch rules; the ctl plane and the unsigned tick, replayed through the pipeline by `InboundPipelineTest`; both-initiate races; resets, their drain windows and floors; the re-seal pair) and single frames a receiver refuses (`negative`, the pipeline-layer ones replayed by `InboundPipelineTest`), and the sender-key group form end to end (`group`: seeds with their roster, group frames in and out of order, acks and key requests, root gossip, a restore's re-mint, a retired chain, and two mints of one epoch whose skipped keys share an index, each spent by its own mint (#177), each replayed through the pipeline by `InboundPipelineTest`), and Carol's — the iPhone's — sessions with Bob and Alice (`carol`: both-initiate races she wins and loses in every arrival order, #83's late loser included, and a plain first contact each way, with every draw listed so iOS can replay her seals byte for byte), laid out as `transcriptFormat` says |
| `name-key-v1.json` | `NameKeyVectorTest` | `NameKeyVectorTest`, `ArtTextSemanticsTest` (on ART), knit-ios | Not a wire fixture: the display-name collision key `NameKey.of`, `NameKey.isBlankName` and `TextLimits.cleanReceived` (ADR 2026-10.2kfr) — the 73-entry lookalike fold, the dropped invisible ranges, the limits, and cases with each answer. Non-ASCII is written as `\uXXXX` escapes so the invisible code points show in a diff; every case is inside Unicode 11 |
| `custody-v1.json` | `CustodyVectorTest` | `CustodyVectorTest`, knit-ios (`CustodyVectorTests`, over both stores) | Custody's keep-and-evict rule (ADR 006, ADR 2026-10.7mqm), replayed through one store with small limits: each sender, group and room bucket's newest-N, the global cap's largest-sender-first with its tie-breaks, a frame evicted by its own write, expired residue counted nowhere, the byte cap on a frame's `signed` (#227, checked first; padded frames carry `bodyLength` and every step the `signedBytes` a port must reproduce), the dead-on-arrival and future-window refusals and the group gate, the profile cap on a `profile` frame's `signed` (#234, `limits.maxProfileSignedBytes`, right after the byte cap; a padded profile's payload is the padded chat payload, which custody never decodes), with each step's answer, evictions and live set. Not a wire fixture: a convergence one, since two ports that keep different custody never agree on a digest |
| `lora-direct-v1.json` | `LoraDirectVectorTest` | `LoraDirectVectorTest`, knit-ios | The compact LoRa DM's seal (ADR 2026-10.x9fa): Alice's and Bob's pair secret from the identity X25519 keys, each direction's `knit/lora/v1/…` key, nonce key and tag derivations, and one Alice-to-Bob packet with its nonce and tag |
| `ios-emitted-v1.json` | knit-ios | `IosEmittedVectorTest` | Carol's identity, the profile and room post she signs on iOS, a v1 DM she seals to Bob, their safety number, and her link proofs (`link`, ADR 2026-11.264w: Carol dialing Alice and Alice dialing Carol, both HELLOs at caps `40009` with Carol's nonce `20..2f` and Alice's `30..3f`, the transcript and Carol's PROOF record, verified and never rebuilt since CryptoKit randomizes, and refused under the other role) |
| `ios-emitted-v1.json`, its ratchet frames and `ratchet` | knit-ios | `IosEmittedVectorTest`, `InboundPipelineTest` | What the iPhone's engine sends with the ratchet on (companion change A10): her ratchet profile with its prekey and capabilities, pinned through `InboundPipeline`; a v3 session with Bob (the opening DM, inline acks, a sealed receipt, profile, reaction and retraction); seeds for a group of the three, one opening a session with Alice; and a group-form message and reaction. Each frame's `sealed` block states its header and the exact plaintext it opens to. `ratchet` lists the readers' prekeys (A9's), Carol's draws, Bob's one reply on a fixed draw, and the steps each reader replays through the session layer |

## Regenerating

Only an intended wire change (for `custody-v1.json`, an intended change to custody's rule, ADR 006) regenerates a file, and the diff shows every byte that moved. An additive
change adds entries and moves none (`docs/WIRE_COMPAT.md`). One deliberate move on record: binding the owner's node id
into the tag MAC (ADR 2026-11.xp6d) moved every tag-derived byte under `keyed-v1.json`'s `blind`, entries no release
had carried, so no reader was left behind by it.

```sh
KNIT_WRITE_VECTORS=1 ./gradlew :app:testDebugUnitTest --rerun \
  --tests 'app.getknit.knit.mesh.protocol.GoldenVectorTest' \
  --tests 'app.getknit.knit.mesh.protocol.KeyedVectorTest' \
  --tests 'app.getknit.knit.mesh.protocol.RatchetVectorTest' \
  --tests 'app.getknit.knit.data.forward.CustodyVectorTest' \
  --tests 'app.getknit.knit.NameKeyVectorTest'
```

- **Signed frames are rebuilt, not stored.** Tink's Ed25519 is deterministic (RFC 8032), so Alice's profile
  and room post are signed again on every run and compared byte for byte.
- **The ratchet transcripts are rebuilt, not stored.** `RatchetVectorTest` injects every key pair and v2 IV
  the engines draw from a seed plan the file records per party, in order, so each transcript seals the same
  bytes on every run; building one also asserts that every open gives the outcome the file states.
- **The sealed DM is stored.** A v1 DM draws a fresh content key, nonce and HPKE ephemeral key, so the pinned
  one was sealed once and the tests prove Bob can open it. Write mode keeps it while it still opens and
  re-encodes to its own bytes, and reseals it when either fails.
- **`ios-emitted-v1.json` comes from the iOS port.** knit-ios writes it with `KNIT_WRITE_VECTORS=1` and
  copies it here with `scripts/sync-vectors.py --export`. Never edit it by hand: a failure in
  `IosEmittedVectorTest` means the two encoders disagree, and the fix belongs in whichever one is wrong.

## Reading them from another port

- Hex is lowercase, and base64 is the standard alphabet with padding, as `ProfileContent.pubKey` carries it.
- Verify a signature; never compare one. CryptoKit randomizes Ed25519 signatures, so two valid signatures
  over the same bytes can differ.
- The identities' private keys are here so every port can open the DMs. They are test keys and nothing else.
- `ratchet-v1.json`'s `floorsBefore` (in `transcriptFormat`) lists the in-memory floors as of its last
  regeneration, and is not exhaustive until the next: the 2026-11-01 amendment to ADR 2026-09.qerd added
  `replacedAt` (an answer to an init the peer took as a replacement) and ADR 2026-11.sfms a held, floored peer
  reset, and no transcript step exercises either. `docs/FORWARD_SECRECY_RATCHET.md` §7 is the full rule.
