# Wire vectors

Byte-exact fixtures that every Knit port is tested against. The Android tests here and the iOS port's tests
(`knit-ios`) read the same files, so a change that moves a byte fails on both sides the same day.

| File | Written by | Checked by | What it pins |
| --- | --- | --- | --- |
| `wire-v1.json` | `GoldenVectorTest` | `GoldenVectorTest`, knit-ios | The definite-length CBOR of one fixed instance of every wire type, and the raw-key bundle probe with its node id |
| `keyed-v1.json` | `KeyedVectorTest` | `KeyedVectorTest`, knit-ios | Two identities from fixed keys, the profile and room post Alice signs, a v1 DM she seals to Bob, their safety number, and the HELLO, DIGEST, custody fingerprint and advert |
| `ios-emitted-v1.json` | knit-ios | `IosEmittedVectorTest` | Carol's identity, the profile and room post she signs on iOS, a v1 DM she seals to Bob, and their safety number |

## Regenerating

Only an intended wire change regenerates a file, and the diff shows every byte that moved. An additive
change adds entries and moves none (`docs/WIRE_COMPAT.md`).

```sh
KNIT_WRITE_VECTORS=1 ./gradlew :app:testDebugUnitTest --rerun \
  --tests 'app.getknit.knit.mesh.protocol.GoldenVectorTest' \
  --tests 'app.getknit.knit.mesh.protocol.KeyedVectorTest'
```

- **Signed frames are rebuilt, not stored.** Tink's Ed25519 is deterministic (RFC 8032), so Alice's profile
  and room post are signed again on every run and compared byte for byte.
- **The sealed DM is stored.** A v1 DM draws a fresh content key, nonce and HPKE ephemeral key, so the pinned
  one was sealed once and the tests prove Bob can open it. Write mode keeps it while it still opens.
- **`ios-emitted-v1.json` comes from the iOS port.** knit-ios writes it with `KNIT_WRITE_VECTORS=1` and
  copies it here with `scripts/sync-vectors.py --export`. Never edit it by hand: a failure in
  `IosEmittedVectorTest` means the two encoders disagree, and the fix belongs in whichever one is wrong.

## Reading them from another port

- Hex is lowercase, and base64 is the standard alphabet with padding, as `ProfileContent.pubKey` carries it.
- Verify a signature; never compare one. CryptoKit randomizes Ed25519 signatures, so two valid signatures
  over the same bytes can differ.
- The identities' private keys are here so every port can open the DMs. They are test keys and nothing else.
