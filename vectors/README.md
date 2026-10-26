# Wire vectors

Byte-exact fixtures that every Knit port is tested against. The Android tests here and the iOS port's tests
(`knit-ios`) read the same files, so a change that moves a byte fails on both sides the same day.

| File | Written by | Checked by | What it pins |
| --- | --- | --- | --- |
| `wire-v1.json` | `GoldenVectorTest` | `GoldenVectorTest`, knit-ios | The definite-length CBOR of one fixed instance of every wire type (and a second where an optional field changes what it means, such as a resumed `blobreq`), and the raw-key bundle probe with its node id |
| `keyed-v1.json` | `KeyedVectorTest` | `KeyedVectorTest`, knit-ios | Two identities from fixed keys, the profile and room post Alice signs, a v1 DM she seals to Bob, their safety number, and the HELLO, DIGEST, file headers (whole and resumed), custody fingerprint and advert |
| `ratchet-v1.json` | `RatchetVectorTest` | `RatchetVectorTest`, knit-ios | The forward-secrecy ratchet's known answers for the iOS port (companion change A9): X3DH, epoch derivation, the message chain, the v3 nonce and header binding, prekey signing, the root tag, the group form's chain and a fixed-IV AES-GCM seal, walked through one first exchange with every key draw recorded (`kats`), the signed ratchet profiles with the prekey pin each leaves (`profiles`, replayed through `InboundPipeline` by `InboundPipelineTest`), and DM transcripts through the session layer: whole Alice–Bob exchanges (`dm`: first contact and the epoch rules; the ctl plane and the unsigned tick, replayed through the pipeline by `InboundPipelineTest`; both-initiate races; resets, their drain windows and floors; the re-seal pair) and single frames a receiver refuses (`negative`, the pipeline-layer ones replayed by `InboundPipelineTest`), and the sender-key group form end to end (`group`: seeds with their roster, group frames in and out of order, acks and key requests, root gossip, a restore's re-mint, a retired chain, and two mints of one epoch whose skipped keys share an index, each spent by its own mint (#177), each replayed through the pipeline by `InboundPipelineTest`), and Carol's — the iPhone's — sessions with Bob and Alice (`carol`: both-initiate races she wins and loses in every arrival order, #83's late loser included, and a plain first contact each way, with every draw listed so iOS can replay her seals byte for byte), laid out as `transcriptFormat` says |
| `custody-v1.json` | `CustodyVectorTest` | `CustodyVectorTest`, knit-ios: pending | Custody's keep-and-evict rule (ADR 006, ADR 2026-10.7mqm), replayed through one store with small limits: each sender, group and room bucket's newest-N, the global cap's largest-sender-first with its tie-breaks, a frame evicted by its own write, expired residue counted nowhere, the dead-on-arrival and future-window refusals and the group gate, with each step's answer, evictions and live set. Not a wire fixture: a convergence one, since two ports that keep different custody never agree on a digest |
| `ios-emitted-v1.json` | knit-ios | `IosEmittedVectorTest` | Carol's identity, the profile and room post she signs on iOS, a v1 DM she seals to Bob, and their safety number |
| `ios-emitted-v1.json`, its ratchet frames and `ratchet` | knit-ios | `IosEmittedVectorTest`, `InboundPipelineTest` | What the iPhone's engine sends with the ratchet on (companion change A10): her ratchet profile with its prekey and capabilities, pinned through `InboundPipeline`; a v3 session with Bob (the opening DM, inline acks, a sealed receipt, profile, reaction and retraction); seeds for a group of the three, one opening a session with Alice; and a group-form message and reaction. Each frame's `sealed` block states its header and the exact plaintext it opens to. `ratchet` lists the readers' prekeys (A9's), Carol's draws, Bob's one reply on a fixed draw, and the steps each reader replays through the session layer |

## Regenerating

Only an intended wire change (for `custody-v1.json`, an intended change to custody's rule, ADR 006) regenerates a file, and the diff shows every byte that moved. An additive
change adds entries and moves none (`docs/WIRE_COMPAT.md`).

```sh
KNIT_WRITE_VECTORS=1 ./gradlew :app:testDebugUnitTest --rerun \
  --tests 'app.getknit.knit.mesh.protocol.GoldenVectorTest' \
  --tests 'app.getknit.knit.mesh.protocol.KeyedVectorTest' \
  --tests 'app.getknit.knit.mesh.protocol.RatchetVectorTest' \
  --tests 'app.getknit.knit.data.forward.CustodyVectorTest'
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
