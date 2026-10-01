# The contact card

**A device's identity as a signed, shareable link — the QR code at a distance.**

|                    |                                                                                              |
|--------------------|----------------------------------------------------------------------------------------------|
| Layout version     | 1                                                                                            |
| Status             | Shipped 2026-08-25 (ADR 042)                                                                 |
| Reference          | `mesh/crypto/ContactCard.kt` (codec), `contacts/ContactCards` (minter), `contacts/ContactImporter` (import rules), `mesh/IntroSync` (the handshake driver) |
| Executable anchors | `ContactCardTest` (golden vectors below), `ContactImporterTest`, `ContactCardsTest`, `IntroSyncTest`, `ScopeVectorTest` (the pair scope) |

## 1. What it is for

Two people who can only pass each other a short string — an SMS, an e-mail, a call, another messenger —
pin each other's key and become contacts without a chat message and without a camera. Each shares their
card; each imports the other's. Import pins the key, makes the peer a contact, and starts the **intro**:
a sealed `CTL_PROFILE` DM whose X3DH init rides every copy until the peer answers. The peers' ratchet
sessions confirm — the responder's on receipt, the initiator's on the answer — and from that moment the
ordinary spool DM scope exists on both sides (`docs/SPOOL_PROTOCOL.md` §3.1). Until then, when both run
the Internet plane, the pair meets at a **pair scope** derived from the two identities (§3.5 there).
Over the radios and LoRa the same intro rides as any DM would; the driver only decides *when* to send.

Precedent: Briar's "add contact at a distance" (exchange `briar://` links, then rendezvous).

## 2. Layout

```
Signed { body: bstr, sig: bstr }              // the house CBOR: definite-length, unknown keys ignored,
Body   { v: 1, id: str, pk: bstr(64),        //   defaults omitted; bstr = CBOR byte string
         name?: str, sp?: [str], iat: int }
sig    = Ed25519(IK_sign, "knit/card/v1" ‖ body)
```

- `id` — the 26-char base32 node id; `pk` — the raw `sigPub ‖ hpkePub` (the `PublicKeyBundle` proto's two
  keys, in that order). Self-certifying: `NodeId.fromPublicKeyBundle(bundle(pk)) == id`.
- `name` — the owner's display name, single-line, ≤ 32 chars; a stored profile name always outranks it
  on import. `sp` — up to 3 relay URLs the owner uses, never with a `?k=` bearer token. `iat` — issue
  time, epoch millis.
- The `body` bytes are opaque end to end: a parser verifies `sig` over exactly the bytes it received and
  never re-encodes them (the `WireEnvelope.signed`/`sig` discipline), so a field added to `Body` is
  additive under `ignoreUnknownKeys`. `v` exists for the change that rule cannot absorb.

Text forms, all accepted by `ContactCard.parse`: `https://getknit.app/c#<base64url(Signed)>` (the card
rides the fragment, which a browser never sends to the server), `knit://c/<…>`, the bare base64url
string, and the legacy QR code `knit-id:v1:<nodeId>:<bundle>` (no name, no relays, self-certification
only). A link is found anywhere in pasted text. A minimal card is ~180 bytes (~240-char link); one with a
name and a relay ~230 bytes.

## 3. Trust

A card is authentic (the signature and the self-certifying id make a forged or edited card impossible
without the key) but the channel it arrived over is not: an SMS can be spoofed and the name is chosen by
whoever sent the card. So an import **pins + accepts but never verifies**; the safety number is shown
at import and on the profile for the pair to compare over a call, and the preview also shows the alias
derived from the key (ADR 058) — and the `Name (Alias)` form if the card's name is one this device already
knows another identity by. A differing key for a node id already
pinned is refused, never swapped in. Relay hints are displayed, never applied *silently* — adding a relay
hands it every scope id and IP this device has, so a hint's inline **Add** goes through the relay invite
sheet (`docs/RELAY_INVITE.md`, ADR 2026-09.tmbq): the host named, the cost stated, the disclosure folded in
the first time, nothing stored before the tap.

## 4. The intro driver (`IntroSync`)

- **Send when sealable, re-send on a floor.** The intro goes out the moment the peer's pinned profile
  carries `CAP_RATCHET` and a prekey — from a radio flood, a LoRa beacon, `KeyExchange`, or a pull from
  the pair scope — and again every 20 h while the session stays unconfirmed (under the 24 h custody TTL,
  so a live copy always exists). A re-send carries the same init; the peer's engine treats it as resolved.
- **Answer an unconfirmed peer.** A v2 frame whose header still carries the X3DH init proves its sender
  has seen nothing of ours; one sealed frame back (at most hourly per peer) confirms them. An init that
  opens before the sender's profile has pinned its prekey (a spool listing is unordered, so a pulled intro
  can overtake it) is owed, and answered when that profile pins. This also cures the older gap where a
  wiped initiator stayed unconfirmed until the responder edited its profile.
- **Grace.** After our own session confirms, the pair scope stays subscribed and pushed into for 48 h so
  the peer — who holds no DM scope yet — can still pull the answer; then it is dropped.
- State is two sets in the settings store (`pending_intros`, `intro_grace`, `"<peerId>|<millis>"`);
  at most 8 intros pending at once, oldest evicted.

## 5. Golden vectors

Pinned by `ContactCardTest` (rows are add-never-move). Keys: Ed25519 from seed `fixture(32, 1)`, X25519
scalar `fixture(32, 2)`, where `fixture(n, seed)[i] = (7·i + seed) mod 256`.

```
nodeId      = 4cgq2pnhh6p3j3afwsue3chrpi
bundle      = omZzaWdQdWJYIOQDCZjP1a0XI8Fp+VaqC564YZtZkr1hLCr0KOvHn43wZ2hwa2VQdWJYIHPnmXHJEQApcjYyqAtwe/T2LBJXYzRuHocY1sDcw6o6
fullCard    = name "Ann", sp ["wss://lax.spool.getknit.app/spool/v1"], iat 1756100000000:
              omRib2R5WKimYXYBYmlkeBo0Y2dxMnBuaGg2cDNqM2Fmd3N1ZTNjaHJwaWJwa1hA5AMJmM_VrRcjwWn5VqoLnrhhm1mSvWEsKvQo68efjfBz55lxyREAKXI2MqgLcHv0
              9iwSV2M0bh6HGNbA3MOqOmRuYW1lY0FubmJzcIF4JHdzczovL2xheC5zcG9vbC5nZXRrbml0LmFwcC9zcG9vbC92MWNpYXQbAAABmN-3eQBjc2lnWECn4VUyYreX
              sIMy1qJZ0Lan7cjpXGgbCijTCfSD68vSkGUrLis_L5ybjYzE86B8AR6MbQdZ3f6f62UsQPQyRPoH
minimalCard = no name, no sp, iat 0:
              omRib2R5WGijYXYBYmlkeBo0Y2dxMnBuaGg2cDNqM2Fmd3N1ZTNjaHJwaWJwa1hA5AMJmM_VrRcjwWn5VqoLnrhhm1mSvWEsKvQo68efjfBz55lxyREAKXI2MqgLcHv0
              9iwSV2M0bh6HGNbA3MOqOmNzaWdYQLL2FdNZ6dxdvl8HfEBnbj8yNsSoGoCPELMEIXuO6kA04Ra6SeNRPkpg1UGmp1ZQgU3qjVOpM6JdH3puOvfihQA
```

## 6. Out of repo

`https://getknit.app/.well-known/assetlinks.json` must list `app.getknit.knit` with **both** signing
certificates (Play App Signing, and the distribution key F-Droid/GitHub/offline-share installs carry) for
the `https` link to open the app on Android 12+; until then an unverified link opens in the browser, so
the `/c` landing page must build the `knit://c/<fragment>` link client-side ("Open in Knit") beside the
install links, and must not redirect `/c` to `/c/`; the relay invite's `/r` (`docs/RELAY_INVITE.md` §6)
needs the same treatment. Lab devices (debug-signed) never verify — use
`adb shell pm set-app-links --package app.getknit.knit 2 getknit.app` or the `knit://` form.

## 7. Deferred

A one-sided invite (a token-derived, profile-only rendezvous plus a contact-request inbox), a prekey in the
card (gated on `iat`, so an importer can seal the intro without waiting for the peer's profile), a
node-id-only import over the radios (`KeyExchange.want`), and session recovery over the pair scope for
existing contacts — all recorded in `.agents/memory/roadmap.md`.
