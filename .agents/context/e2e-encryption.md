# End-to-end encryption (implemented)

DMs and group chats are E2E-encrypted; the broadcast "Nearby" room stays plaintext by design (no fixed
recipient set). Scheme (static keys, no ratchet): a per-message random content key AES-256-GCM-encrypts
the `MessageContent` (body + mentions + attachment refs) into an `EncEnvelope` carried inside the
encrypted `ChatContent.enc` payload, and the content key is wrapped (Tink HPKE/X25519) to each recipient.
Identity keypairs live in `IdentityKeyStore` (AndroidKeyStore-wrapped, **outside** the destructively-
migrated DB), advertised via `ProfileContent.pubKey`, pinned TOFU into `PeerEntity.pubKey`, and confirmed
out of band via the safety-number/QR screen (`PeerEntity.verified`). Image attachments are encrypted to
a per-attachment key and content-addressed by ciphertext hash, so `BlobExchange`/`BlobStore` are
unchanged. **Decrypt/verify failures must never throw out of the inbound handler** — `onDeliver` runs
before the router schedules the relay, so a throw would stop forwarding (see `MeshManager.decryptAndDeliver`).

**One signature authenticates every flooded frame** (encrypted *and* plaintext: broadcast `chat`,
`profile`, `groupupdate`, `groupleave`, `reaction`, `receipt`). `WireEnvelope.sig` is raw Ed25519 over
`WireEnvelope.signed` (the canonical `RelayEnvelope` CBOR, which includes the encrypted `ChatContent.enc`
for a DM/group message), and `MeshManager.verifyInbound` (the gate at the top of `onDeliver`) verifies it
**byte-exact over the received `signed` bytes** — no re-encode — and drops any that fail, closing the gap
where a relay could forge a frame (e.g. a profile with a different name) under another node's `senderId`.
Verification reuses the key path (`peers.find(senderId).pubKey` → `PublicKeyBundle.verifier()`, guarded
by `NodeId.fromPublicKeyBundle == senderId`); a `profile` uses the `pubKey` in its own `ProfileContent`
payload since first contact precedes any pin. `blobreq` stays unsigned. Same no-throw contract applies:
`verifyInbound` swallows failures and returns false (drop locally) so the router still relays. The old
separate envelope signature (`MessageCrypto.signingBytes`/`verifyEnvelope`) is gone — subsumed by the one
frame signature; `MessageCrypto.open` now only unwraps + AES-decrypts. `EncEnvelope.v`/`MessageContent.v`
gate the crypto-scheme/content-schema versions in `MeshManager.decrypt` (unknown ⇒ drop locally + count,
but still relay — a delivery gate, never a relay gate; see `docs/WIRE_COMPAT.md`).

Still deferred for E2E (see `memory/roadmap.md`): forward secrecy / a ratchet (static keys only),
encrypting reactions/receipts (signed now, but still flood as cleartext metadata), and encrypting the
broadcast room.
