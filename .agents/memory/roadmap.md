# Roadmap / out of scope (deferred, by design)

What's deliberately deferred, and what has since shipped. Update this as scope lands (the BLE + digest-pull
notes below moved from "deferred" to "implemented" — that evolution is why this is memory, not a static
doc). **Don't start a deferred item without explicit direction.**

## Already shipped (was deferred)

- **The Bluetooth LE plane is implemented** (`mesh/bluetooth/`) and runs *simultaneously* with Wi-Fi Aware
  behind `CompositeMeshTransport` (wired in `di/MeshModule.kt`): BLE advertise/scan presence + persistent
  L2CAP CoC data links, *preferred* over NAN's ephemeral NDP, with per-peer escalating connect backoff and
  A2DP-audio instrumentation. It is a co-plane, **not** a fallback, and BLE-capable devices use it
  regardless of Wi-Fi Aware support.
- **Digest/pull anti-entropy** — the cue-plane `StoreDigest`/`DigestTracker` + the data-path
  `LinkFraming.Type.DIGEST` id-diff (`docs/DIGEST_PULL_REATTACH.md`).
- **Inbound key-request** for a frame received from a not-yet-pinned sender (the inbound complement of
  retransmit-on-key-arrival) — now `KeyExchange`; see `context/store-and-forward.md`.
- **R8 obfuscation (name mangling)** is enabled on release/staging (was shrink + optimize only, behind
  `-dontobfuscate`). The wire stays safe by construction — kotlinx.serialization compile-time descriptors +
  the frozen wire/identity DTOs pinned unrenamed in `keepRules/knit-r8.keep` — and `FileKind`'s file-header
  token is decoupled from its enum constant name (`FileKind.wire`). See decisions ADR 012. Deferred: tighten
  the broad library `{ *; }` keeps to minimal targeted keeps (bigger size win, larger test surface).

## Still deferred (by design)

- **BLE promotion gate on A2DP audio** — the adaptive scan throttle now drops the **scan** to its floor
  while streaming (`ScanDemandPolicy` / the demand-gated `scanLoop`), but **connects** are still not gated
  on `contended` (it remains diagnostic-only for the connect path).
- **Connectionless BLE side-channel for small frames** — the BLE analogue of the NAN coordination/fast-fanout
  plane: carry small floodable frames (broadcast chat, receipts, reactions, typing) over BLE **extended
  advertising** so they bypass an in-flight L2CAP file transfer entirely instead of head-of-line-queuing
  behind it on the one ordered stream. The shipped `TransferPacePolicy` feed-cap (`FramedLink.paceBytesPerSec`)
  *mitigates* the stall by pacing the blob feed below link capacity; this would *structurally* split
  interactive frames from bulk. DMs stay on L2CAP. See knit/knit-next#13.
- **True DM routing** — DMs still flood; only the addressed recipient delivers/acks. Store-and-forward now
  *carries* undelivered DMs (`context/store-and-forward.md`), but there is still no routing table.
- **Group key-gap retransmit** — the group analogue of the DM `flushPendingFor`: a group message already
  floods to the members whose keys are known, so reaching a member whose key arrives *later* needs a fresh
  re-seal, not custody.
- **E2E hardening** — forward secrecy / a ratchet (static keys only), **encrypting** reactions/receipts
  (they are signed now but still flood as cleartext metadata), and encrypting the broadcast room. See
  `context/e2e-encryption.md`.
