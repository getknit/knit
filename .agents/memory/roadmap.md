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
  token is decoupled from its enum constant name (`FileKind.wire`). See decisions ADR 012. The broad library
  `{ *; }` keeps are **no longer deferred**: ADR 050 dropped the Tink / ARSCLib / apksig ones (97% rates,
  dex 9.9 → 6.0 MB) and added `scripts/r8-dex-gate.sh` to CI so a library's consumer keep rule can't quietly
  undo it. Still pinned by choice: `net.zetetic.**`, `org.tensorflow.lite.**`, `org.xmlpull.v1.**`.
- **Forward secrecy for DMs is implemented** — the epoch-rekey ratchet (crypto scheme v2,
  `docs/FORWARD_SECRECY_RATCHET.md`; ADR 016): X3DH-style bootstrap off a signed prekey published in the
  profile, per-epoch X25519 rekeying, session state in the `ratchet_*` tables, capability-gated dual-stack
  (v1 static wrap remains for groups and pre-ratchet peers, and inbound v1 is accepted forever). Also
  supplies the `pairwiseRoot` export the internet-relay scope derivation consumes
  (`docs/SPOOL_PROTOCOL.md` §3, `ScopeCrypto`).
- **The spool (internet-relay) protocol is specified** — `docs/SPOOL_PROTOCOL.md` (ADR 019, public,
  normative) plus the pure reference implementation and vector anchors (`mesh/crypto/scope/`
  `ScopeCrypto`/`SpoolPow`, `mesh/spool/` `SpoolRecords`; API-only, zero runtime consumers). Names
  committed: spool / scope / `ScopeSync` / `knit-spool` (AGPL-3.0).

## Still deferred (by design)

- **The LoRa (Meshtastic-over-BLE) plane is hidden in shipped builds** (2026-08-24, ADR 038) —
  `BuildConfig.LORA_PLANE` true in debug, false in release/staging, `-PloraPlane=true|false` overrides.
  It gates the LoRa child in `CompositeMeshTransport`, the `lora` settings route + Profile row, and
  `SettingsStore.loraEnabled`. The code is **not** stripped (R8 prunes the `if (LORA_PLANE)` branches).
  MVP shipped: `mesh/lora/` (pure, JVM-tested end-to-end over a fake board/air) + `mesh/bluetooth/meshtastic/`
  (the GATT client, device-verified only). Carries the Nearby-room broadcast subset (chat, reaction, ✓✓ tick,
  profile) and, since ADR 039 (2026-08-24), **sealed 1:1 DMs** — the whole DM form, receipts/reactions/ctl
  included — via `MeshTransport.longRangeFanout`, with class-aware queue shedding, a 15-min freshness gate,
  a bounded re-offer of carried DMs to a peer first heard, and a default-on `loraDmEnabled` switch (the
  metadata-exposure control). Group chat/meta, typing and files stay refused. **Still owed:** the
  **two-phone device trial** (pair both boards, verify a Nearby post + ✓✓ + reaction cross, then a DM + its
  ✓✓ + a reply, and a DM sent while the far board was off landing via the re-offer once it returns — all
  with the phones out of BLE/NAN range) — the GATT layer has no host test. **Knit-provisioned channel
  SHIPPED** (2026-08-24): "Set up Knit channel" (or `…debug.LORAPROV`) writes the derived `KnitChannel` as a
  secondary channel over the Meshtastic admin API — the user no longer hand-configures the boards;
  region/modem-preset still set once at flash. **Board setup REWORKED** (2026-08-26, ADR 045): a single
  "Set up this board for Knit" writes the Knit channel into a free secondary slot, stretches the board's
  node-info / position / telemetry broadcasts and sets `rebroadcast_mode = LOCAL_ONLY`, all as one
  read-modify-write admin transaction, with a Restore that puts the board's own values back and switches the
  plane off (`BoardQuiet`, `spliceVarintFields`, `SettingsStore.loraBoardSetup`, `…debug.LORAPROV [--es mode
  restore]`). There is deliberately **no lighter mode and no hand-set channel index**: a board is set up for
  Knit or it is a stock Meshtastic node. The board's **primary is never touched**, which keeps it on the
  public frequency where stock nodes repeat Knit's packets for free — the reason the frequency-move design
  was reverted before shipping. **Still owed:** the on-hardware trial in `context/lora-bridge.md` — the
  frequency must be *unchanged*, the battery row must survive the telemetry stretch, and a stock node between
  two boards should extend the range.
  **The Meshtastic room is a local mirror of the paired board's slot 0** (2026-09-05, ADR 2026-09.26q3,
  superseding 2026-09.cf7a and 2026-09.7r4d): whatever the user set slot 0 to — preset, name or key — is read
  into `Conversations.MESHTASTIC` as rows on this phone and this phone only, and a post typed there leaves
  through this phone's own board on channel 0 as `TEXT_MESSAGE_APP` carrying the words alone — no author
  name (2026-09-05, ADR 2026-09.9469, which withdrew ADR 049's single exception once the board became the
  identity), still behind the first-use consent sheet. Nothing crosses Knit's mesh: no frame,
  no custody, no fan-out, no gateway election on either direction; the `meshpost` type is withdrawn and
  burned. A heard post is lined up with a contact through the bound board's node number the profile now
  carries (`ProfileContent.loraNode` → `peers.loraNode`), resolved once at ingest and frozen on the row
  (`messages.originPeerId`) so a board changing hands never re-attributes history; still an unverified match,
  rendered as such. Metered by `AirBucket.PUBLIC` (15 % of the window) plus a 30 s per-board floor, and every
  refusal is shown at the composer with the draft kept. **Still owed:** the on-hardware trial in
  `context/lora-bridge.md` (two boards, one board-less phone: the board-less phone sees nothing, a contact's
  post wears their name off the node number alone, a second post inside 30 s is refused, the title follows a
  preset change), and
  **signature-backed confidence SHIPPED** (2026-09-05, ADR 2026-09.ggq4): the "no verdict" premise was wrong
  — both lab tags hand the phone `MeshPacket.xeddsa_signed` and the signature itself — so the profile now
  carries the board's key (`ProfileContent.loraKey`, ninth additive profile field, only while the board
  signs), the phone verifies a heard post against it (`mesh/crypto/XeddsaVerify`, the firmware's own
  `from ‖ id ‖ portnum ‖ payload`) and freezes one of four verdicts on the row (`messages.originSigned`, DB
  v12); a verified match wears a shield and opens the profile directly, a mismatch is drawn as a stranger,
  and on a signing board the composer caps a post at the 166-byte text cliff so every post leaves signed.
  Verified on the two lab phones the same day (`context/lora-bridge.md`): a post each way arrived
  `signed=true boardVerified=true` and verified, a 166-byte post included. **Still owed:** the
  `packet_signature_policy` decision — `BALANCED` is safe for the padded plane, `STRICT` loses it, and the
  recommendation is now `BALANCED`; seeding our own board with a contact's key (`AdminMessage.add_contact`,
  66) so `BALANCED`/`STRICT` receivers keep the contact's posts and a stale NodeDB key cannot blackhole
  them; the `heartbeat{nonce=1}` NodeInfo ping after a bind, so peers learn a fresh board's key at once; a
  warning for the downgrade shape — an unsigned post under 166 B from a contact whose profile advertises a
  key; and a mismatch / board-only verdict seen on hardware, which needs a third signer on the mesh.
  **The setup also marks the board unmonitored** (2026-09-01, ADR 2026-09.emd7): `User.is_unmessagable`
  rides the same `set_owner` as the rename, so other people's clients stop offering a board whose inbound
  path keeps only `PRIVATE_APP` as a message target; Restore clears it, and firmware older than 2.6.9 is
  left alone because it drops the field and would leave the setup looking permanently unfinished.
  **Still owed:** the device half — on a 2.6.9+ board, confirm the Meshtastic app shows the node as
  unmonitored after a setup and messagable again after a Restore, and that a pre-2.6.9 board is never
  prompted to finish a setup it has already finished.
  **A dedicated-frequency setup EXISTS but is DEBUG-ONLY** (2026-08-31, ADR 067): `ProvisionMode.SetupDedicated`
  pins `lora.channel_num` to a slot derived from the Knit channel name, and `LoraAirtime` then drops the 10 %
  politeness ceiling (the region's legal duty cycle still stands), for the isolated-fleet case where there is
  no Meshtastic neighbourhood to borrow relaying from. Refused outside US/ANZ, whose bands are the only ones
  `LoraSlot` states exactly. **Still owed before it could ever ship to release:** an on-hardware two-board
  trial on a dedicated slot (nothing has been run on real radios yet), a story for a half-converted fleet —
  a board left on the shared slot is silently unreachable and looks identical to being out of range — and a
  decision on whether the 0.5 safety factor is still right once no third party is repeating us.
  Still deferred: a **user-set/shared private PSK** (the shipped
  channel is a public rendezvous; with DMs aboard it is also what would hide their metadata — needs
  out-of-band PSK sharing, QR/URL — and, since the name feeds the slot hash, a private deployment would also
  land on its own frequency), a **periodic self-profile beacon** (a peer that only listens never
  triggers a beacon exchange or a re-offer), **Meshtastic unicast + `want_ack`** for DMs (needs a
  nodeNum↔nodeId map and a Routing `NONE`-is-success branch), **re-offer beyond the heard peer** (a
  board-less recipient behind another board-holder — the "true DM routing" deferral), an **in-app scan + bond flow**
  (`MeshtasticScanner`/`MeshtasticBonder` are written but unwired — device-only verifiable, and the scan must
  go through `BleConnectArbiter`; the picker filters bonded devices instead, ADR 040), and a **per-message
  `loraTooBig` marker** (no persisted evidence; ADR 040's composer hint covers the sending side). **The plane's
  UI SHIPPED** (2026-08-25, ADR 040): `DeliveryPlane.LoRa` + bubble glyph, the header glyph, the board-only
  picker with a channel verdict, the LoRa-only DM notice and the long-message composer hint; the board's
  battery in the status + Profile rows followed (ADR 041). See
  `context/lora-bridge.md`. **Bridging between mesh pockets SHIPPED** (2026-08-25, ADR 044): a `LoraCtl`
  gossip OFFER (tag `0x10`, ≤ 48 id prefixes, one packet), a gateway election off `foreignReachable` that
  closes the **multi-board-per-clique** deferral above, an airtime governor reading the board's region and
  modem preset, and digest-driven backfill of what a far gateway's offer shows it lacks — behind
  `SettingsStore.loraBridgeEnabled` (default on). Live traffic already crossed before this and was not
  rebuilt. **Still owed:** the **four-device two-pocket trial** in `context/lora-bridge.md`. **The backfill
  no longer suppresses itself** (2026-09-02, ADR 2026-09.y8pu): `serveOne` consulted the same 10-min `sigSeen`
  set the fan-out spends, so a frame fanned out of range was skipped by the one path that could repair it —
  field-observed as a Nearby-room post that never arrived after the boards came back into range. Still owed:
  the two-board confirmation on hardware. **Airtime shaping
  SHIPPED** (2026-08-27, ADR 054): the recipient gate (a DM-form frame to a linked peer or to self never rides
  the board), a 15-min budget window at the same 5 %, a `TICK` class that sheds first and never spends a
  window's tail, coalesced DM receipts (`DmAckCoalescer`, ≤ 45 s hold, one tick per burst) piggybacked on a
  reply behind `CAP_INLINE_ACK`, and the saturated-chat notice. **Still owed:** its three-phone trial
  (`context/lora-bridge.md`). **Meshtastic 2.8 caught up with** (2026-08-31): `LoraAirtime` now charges for
  the 66-byte XEdDSA signature 2.8 bolts onto any broadcast under 165 B (gated on the board's firmware),
  `ModemPreset` names codes 9–16 (`LongTurbo` is 2.8's new US default and is deaf to `LongFast`), `LoraRegion`
  names the duty-limited regions that were collapsing into `OTHER`'s 100 % (`EU_866` 2.5 %, `EU_N_868` 10 %,
  `TH` 10 %), and the LoRa screen warns on a preset mismatch beside the renamed-primary warning. **Bench-verified on a
  Heltec V4 / 2.8.0.7239fe8 (2026-08-31):** a wiped US board really does come up `LONG_TURBO`; the signature
  cliff is at **exactly 165 B** and `LoraAirtime` now matches the firmware's own `Packet TX:` figure to
  **≤ 1 ms** across 140–231 B; the payload cap is **still 231**; and ADR 045's provisioning transaction is
  intact, with `Config.lora` byte-identical before and after (its "never writes the radio" promise, on
  hardware). **The cliff is now a saving rather than only a tax** (2026-09-01, ADR 2026-09.mhs5): a frame's last
  packet is grown to 166 B so the board sends it unsigned — a few bytes of pad instead of 66 of signature,
  ~20 % off both the one-packet tick and the room post. Legal only on a **deflated** body, and since the
  frames with most to gain *store* (the ADR 060 transcoder already took the compressible keys out), a stored
  one-packet frame is re-deflated first for a measured +5 B whenever `LoraAirtime` prices the result cheaper.
  Not covered: fragmented stored frames, and `LoraCtl` offers (whose byte-identity the gossip suppression
  depends on). **Device-verified the same day** on the Heltec / 2.8.0.7239fe8, in two halves: the codec pads a
  real room post (`lora pad fanout:chat +46B`, `loraPadded` 0 → 1, no NAK), and a 166-byte payload leaves the
  board **unsigned** — read off a *second* board over the air as `Lora RX … encrypted len=190` / `Packet RX:
  1262ms`, against `len=255` / `1655ms` at 165 B. (That `encrypted len` line is a better instrument than
  `Packet TX:`: `len = payload + 24` unsigned, +66 signed, so it reads the signature off directly.) **Still owed:** a decision on `packet_signature_policy` (defaults to `COMPATIBLE`, so nothing is
  broken — but a user who picks `STRICT` now loses *every* frame, not just those over the cliff);
  `BoardName.stock`, which
  computes the fallback name from the node number while 2.8's default name is still MAC-derived; a second
  board for the receive half (partly answered — the USB board turned out to be a separate node that does hear
  the phone's board, though nothing on this mesh runs anything but the `COMPATIBLE` signature policy); and
  **one packet observed being both padded and unsigned** — the two halves above were measured separately
  because ADR 044's pocket election put the phone `PASSIVE` before they could be caught together. Full write-up in the private overlay. Still deferred
  here: an **IBLT/Bloom offer body** (48 prefixes is a window — the upgrade if a busy pocket's oldest frames
  start falling off it), **acknowledged backfill** (a served frame lost to the air waits for the next round),
  and **faster passive-to-active takeover** when an active gateway's phone dies without leaving
  `foreignReachable` (the 45-min `STALE_MS` is the whole blind spot today).

- ~~**The spool plane is hidden in shipped builds**~~ (2026-08-22, ADR 031) — **introduced at 2.4.0**
  (2026-08-31, ADR 064): `BuildConfig.INTERNET_PLANE` now defaults true in release and staging too, so a
  shipped build seeds the default relay, shows the Profile row and the `relays` route, and lets
  `SettingsStore.spoolEnabled` mean what the user stored. `-PinternetPlane=false` puts a build back in
  the dark state. The user-facing default did **not** move: the plane is visible and switched **off**,
  behind the consent sheet. The three device trials below (group two-island, attachment deferral,
  contact-card intro) were **not** complete at the flip — see ADR 064 for what was, and for the residual
  risk that carries.

- **The spool plane beyond the spec** — everything that makes the protocol run, in order: ~~the
  `knit-spool` reference daemon + conformance suite~~ (**done 2026-08-16** in the `knit-spool`
  repo — full v1 daemon with SQLite persistence, rate limits, watermark, ops surface, plus the
  22-check TAP conformance CLI; its implementation pass fed eight semantic clarifications back
  into `docs/SPOOL_PROTOCOL.md` §6.2/§6.4/§7.1/§7.2/§12, no wire or vector change — ADR 019
  amendment); ~~the client `ScopeSync` plane~~ (**MVP done 2026-08-16**, `mesh/spool/` — DM scopes
  only, off by default, OkHttp behind the `SpoolLink` seam, the §9.1 heal loop, §9.3 quarantine,
  §9.4 bridge into `handleInbound`, metrics + Diagnostics rows + the `…debug.SPOOL` bridge action;
  ADR 019's M3 amendment records the four shape decisions). **What the client plane still
  owes**, roughly in order:
  - **the scope-config ctl** — `CTL_SCOPE_CONFIG = 7` / `MessageContent.sc` / `ScopeConfigPayload`
    with LWW on `(version, issuer)`. The one *wire* change the plane needs, so it lands additively
    per `docs/WIRE_COMPAT.md` with golden vectors and a precedent entry. Until it ships, the spool
    list is a device setting and bounds are §12 constants in `ScopeRegistry`.
  - ~~a spool-list editor~~ (**done 2026-08-16** — `ui/relay/InternetRelayScreen`, route `relays`,
    reached from a Profile summary row. **The switch is un-gated**: `BuildConfig.DEBUG` is gone from
    `ProfileScreen`, because the hard prerequisite is now met — a release user can edit or remove the
    seeded default. Ships with it: a one-time consent sheet (`SettingsStore.spoolConsented` /
    `acceptSpoolConsent`, which records consent and enables in one write), per-relay health rows off
    `SpoolStatus`, and the shared `SpoolUrl` validator so the editor refuses at entry exactly what
    `OkHttpSpoolDialer` refuses at dial time. ADR 019's M6 amendment records the UX rules.)
  - ~~a switch per relay~~ (**done 2026-08-30** — ADR 063. `SettingsStore.spool_urls_disabled` holds
    the parked subset, and the composed `activeSpoolUrls` is the single seam every consumer reads,
    the way `spoolEnabled` already gated the plane. No wire, DB or protocol change.)
  - ~~a validated-Internet `ConnectivityManager` seam~~ (**shipped 2026-09-03** as `net/InternetGate`
    for link previews, ADR 2026-09.n752 — `ACCESS_NETWORK_STATE` is declared and `rules/mesh.md` names
    the second `ConnectivityManager` user; **`ScopeSync` does not consume it yet** and still reconnects
    on backoff, so wiring the plane onto the gate is the remaining half); the Tor SOCKS toggle (the
    preview fetcher's `OkHttpPreviewFetcher.bound` is the second place a proxy would go);
    per-**conversation** opt-out (deliberately **not** built — which
    conversations ride the plane is all-or-nothing by product decision, 2026-08-16, and the consent
    sheet says so. Note the axis: ADR 063 ships a per-**relay** switch, which chooses *which third
    party* carries, not *which conversations* do — that decision is untouched).
  ~~Then: group scopes~~ (**done 2026-08-16** — the `GroupKeyPayload.gr` wire field, `group_roots`
  at DB v3, `GroupRootPolicy`/`GroupRootStore`, group scopes in `ScopeRegistry`/`ScopeFrames`, and
  the mint/gossip/adopt/re-mint wiring in `MeshManager`/`InboundPipeline`. The spec's §3.2 was
  amended in the same pass: **any member** may mint version 1, damped by preferred-minter-plus-grace
  rather than restricted to the creator — ADR 019's M4 amendment records why, plus the two mandatory
  adoption bounds and the never-rate-limit-adoption rule). Still owed on the group half: nothing
  structural, but it has **not been exercised on devices** — the lab bridge trial (two islands, one
  real spool, a departure rotating the scope) is the outstanding verification.
  ~~Then: sealed attachments over spools~~ (**done 2026-08-16** — spec §4.5/§6.5/§7.3/§9.5, the
  `ScopeCrypto` chunk seal + keyed `aid`, `mesh/spool/ScopeAttachments`, five records, and the
  attachment pass in `ScopeSync`; `knit-spool` gained both stores, the server handlers and four
  conformance checks. **No mesh wire change, no capability bit, no DB migration** — the cleartext
  `ChatContent.attachmentHash` of the DB v19 precedent is the whole reference a
  fetcher needs — the mime rode alongside it until ADR 035 withdrew it, and the fetcher now resolves the
  type from its own decrypted row. ADR 019's M5 amendment records the five shape decisions). Still owed on the
  attachment half: **persisted partial downloads** (they are in memory today, so a process death
  mid-transfer refetches — the upload half already resumes off the spool's bitmap), and the same
  two-island device trial the group half is waiting on.

- **Attachment uploads are deferred while the radios carry them, SHIPPED 2026-08-17** (ADR 021,
  `mesh/spool/AttachmentDeferPolicy`, spec §9.5's MAY + §10): an attachment we authored, whose
  recipient acked it, waits while that peer is still on `MeshTransport.reachable`, so a photo that
  already crossed a radio link is not copied to a relay as well. Deliberately **attachments only** —
  gating frames would make the scope digest a function of local mesh state and it would never converge
  again — and deliberately a **delay, not a veto**: it re-opens on the sighting expiring and ends 2 h
  before the frame leaves custody. Groups never defer (the sealed group tick flips on the first
  member's receipt). Counted as `spoolAttachDeferred` in Diagnostics and the `SPOOL` bridge. Still
  owed: the same two-island trial — send a photo co-located (expect the deferred counter climbing and
  no `aput`), separate the devices, expect the upload within one 60 s heal round.

- **Sealed profile updates SHIPPED 2026-08-16** (ADR 020, was never a roadmap item — the gap surfaced in
  field testing after M5): `CTL_PROFILE = 8` carries name/status/avatar to established contacts inside v2
  chat, so profile changes now cross the Internet plane and stay off the cleartext plane for
  ratchet-capable peers. Avatars ride the carrying frame's cleartext `attachmentHash` (the DB v19
  precedent), and group photos needed no wire change since `groupupdate` was already scope-carried. The
  cleartext `profile` frame keeps first contact permanently — it is self-certifying and cannot be
  encrypted — so ADR 018's "last cleartext flooded metadata" goal is advanced, not finished.

- **Contacts at a distance SHIPPED 2026-08-25** (ADR 042, `docs/CONTACT_CARD.md`): a signed contact
  link (share/copy on the Verify screen; import by tapping it, sharing it to Knit, or pasting it on the
  Add-by-link screen), the `CTL_PROFILE` intro driven by `IntroSync`, and the identity-derived **pair
  scope** (spec §3.5) so a pair that has only exchanged cards meets at a spool before a session exists.
  **Still owed:** the two-device trial (both import, out of radio range, one shared spool — expect
  `introsSent ≥ 1` both sides within ~2 heal rounds, `confirmed: true` in `…debug.RATCHET`, the same DM
  scope id in `…debug.SPOOL`, the pair scope gone ≤ 48 h later; then the LoRa variant), the
  `getknit.app` assetlinks + `/c` landing page (out of repo — until then Android 12+ opens the https link
  in the browser; `knit://` and share-to-Knit work regardless). **Deferred, by design:** the **one-sided
  invite** (a *profile-only* token-derived rendezvous plus a contact-request inbox — needs per-token
  caps, revoke, expiry, and the "other link holders can see who requested" caveat); a **prekey in the
  card** gated on `iat < 7 d` (seal at import, reach a LoRa listen-only peer; a stale prekey wedges
  silently at `EPOCH_GONE`); **node-id-only import** over the radios via `KeyExchange.want`; **session
  recovery over the pair scope** for existing contacts (needs a probing strategy — no `unsub` record,
  `maxScopes` pressure); a chat-thread intro notice (the profile status line covers it; the pair scope
  already reads as relay-covered).

- **Same-name disambiguation follow-ups** (ADR 058 shipped the `Name (Alias)` label, 2026-08-28; ADR
  2026-09.wuqj made the alias a 24-bit digest token that grows when matched, 2026-09-03): an
  **impersonation warning** when a non-contact adopts a contact's or your own name (the label makes it
  visible; nothing yet says so); **tinting the tokens past the first** in `PeerNameText`, so a label that
  grew because an alias was matched looks different from one that merely carries its alias; a
  **node-id-derived avatar hue** in-app (`ui/components/Avatar` is one fixed tint — the notification hue
  already keys on the identity); **last-seen pruning** of the collision universe (a stranger seen once can
  suffix a contact indefinitely — needs a `lastSeen` column); and resolving `ReplyRef.authorId` through the
  directory instead of rendering the sender's snapshot.

- **Audio and file moderation** — voice notes (ADR 034) and arbitrary files (ADR 2026-09.qq2r) ship
  **unscreened**: no on-device model classifies speech, nothing at all classifies a PDF or an archive, and
  the app has no cloud option, so `MODERATION_NONE` is the honest verdict and the screening hooks skip both
  by MIME. Mitigated rather than solved: neither is offered in the Nearby room (the one surface
  that floods unencrypted to strangers), app packages are refused on send and archives/executables are
  confirmed before saving, and block-sender plus the ADR 009 request gate are the remedies. The MIME-keyed
  skip itself is no longer a way past the classifier — `ingestFile` sniffs the bytes' own signature and
  routes a real image back through the image pipeline, and the receive side screens every keyed
  attachment's decrypted plaintext MIME-blind.
  If a small on-device speech classifier ever becomes practical, the hook point already exists —
  `InboundPipeline.onObtained` decrypts a landed attachment and is where the waveform derivation runs, so a
  verdict would cache under the same content hash the image path uses and the bubble's tap-to-reveal
  collapse would need no new UI. Gap recorded in `docs/CONTENT_MODERATION.md` §7.

- **Voice notes and files in the Nearby room** — deliberately not built, for the reason above. Reversing it
  is one flag each (`MessageInput`'s `voiceEnabled` / `fileEnabled`), and neither should be reversed without
  an answer to "what screens it". The room is the *only* thing that hides the file item: gating its
  visibility on the recipient's `CAP_FILES` was tried and reverted, because the bit arrives with the peer's
  next profile frame and until then the feature was indistinguishable from unbuilt (ADR 2026-09.qq2r).
  Capability belongs on the send, where it can explain itself. Two other things now rest on the room carrying only images:
  `MeshBlobStore.saveIncoming`'s screening skip, and `docs/NEXT_WIRE_BREAK.md`'s first parked item.

- **File attachment previews** — files ship with a typed icon, a name and a size, no thumbnail
  (ADR 2026-09.qq2r). The two cheap wins were left on the table deliberately: **video/audio poster frames**
  need only `MediaMetadataRetriever` over the existing `ByteArrayMediaSource` (already in the tree from the
  voice-note work — no disk, no new dependency), while a **PDF first page** needs `PdfRenderer`, which
  demands a *seekable* file descriptor and so needs `StorageManager.openProxyFileDescriptor` to stay off
  disk under ADR 029. `FileAttachmentBubble` is the one place to change; the row shape already reserves the
  slot.

- **Opening a received file in another app** — not built. It needs a content provider whose `openFile`
  serves decrypted bytes (the same proxy-descriptor machinery a PDF preview wants), because ADR 029's
  invariant forbids the plaintext staging file the obvious `FileProvider` route would need. Saving through
  the storage picker is the only exit today. **Before building it:** the provider must never expose an
  install-capable grant for a package, or the platform's unknown-sources gate stops being the last word.

- **Startup profile (`app/src/main/startup-prof.txt`)** — the baseline profile landed (ADR 048) but the
  startup-profile half did not. It reorders dex so startup code sits together, which is a real additional
  win on cold launch, and it is the same collection run (`includeInStartupProfile = true`). Deferred only
  because it changes dex layout and so needs its own pass against F-Droid's byte-comparison before shipping.

- **BLE promotion gate on A2DP audio** — the adaptive scan throttle now drops the **scan** to its floor
  while streaming (`ScanDemandPolicy` / the demand-gated `scanLoop`), but **connects** are still not gated
  on `contended` (it remains diagnostic-only for the connect path). **Note before building it:** since
  ADR 034, *playing a voice note* also trips `AudioManager.isMusicActive`, so `contended` now goes true for
  a few seconds of local speaker playback that contends for nothing. Harmless while the flag is
  instrumentation-only; gating connects on it as-is would stall the mesh every time someone listens to a
  message. The gate needs to distinguish a real A2DP route from any active stream.
- **Connectionless BLE side-channel for small frames** — the BLE analogue of the NAN coordination/fast-fanout
  plane: carry small floodable frames (broadcast chat, receipts, reactions, typing) over BLE **extended
  advertising** so they bypass an in-flight L2CAP file transfer entirely instead of head-of-line-queuing
  behind it on the one ordered stream. The shipped `TransferPacePolicy` feed-cap (`FramedLink.paceBytesPerSec`)
  *mitigates* the stall by pacing the blob feed below link capacity; this would *structurally* split
  interactive frames from bulk. DMs stay on L2CAP. See knit/knit-next#13. The frame-codec half now
  exists (2026-08-21): `mesh/link/FastFrameCodec` (compact `0x03` / fragment `0x04`, ADR 030) is
  transport-neutral by design — this item still needs the ext-adv carrier plus its cap gate (BLE
  adverts carry the low 8 capability bits, which covers `CAP_FAST_COMPACT = 0x20`).
- **Frame compaction: what round 2 (ADR 060, the `0x05` transcoder) left** — round 1 (ADR 059, crypto v3)
  and round 2 (ADR 060: a schema-aware re-encoding of `signed` the receiver rebuilds byte-exact before
  verifying) both landed 2026-08-29; measured after: signed v3 ✓✓ tick **221 B, one packet at 228/231/255**,
  unsigned tick 157, sealed reaction 229 with 👍 (one at 231/255; 261 with the longest RGI emoji sequence and
  290 at the `TextLimits.REACTION` cap — two packets on every plane, never three), 40-char DM 244 (one NAN message, two LoRa
  packets), 100-char DM 304 (two — the structural floor, sig 64 + ids 48 + ek 32 + ct 124), profile 352
  (3 → 2 parts at 228), 12-ack tick 409 (3 → 2). Still owed: **(a) the LoRa gate before that plane ships to
  release** — today every LoRa frame the transcoder reproduces rides `0x05` (a flag-day, acceptable only while
  `LORA_PLANE` is debug-only); the gate is "every peer heard on the plane within the 45-min linger advertised
  `CAP_FRAME_TRANSCODE` through the profile frame it beacons here, newest-`sentAt`-wins, closed when no one is
  heard" (~20 lines in `LoraMeshTransport.onFramePacket`/`recomputeReachable`/`encodeOrNull`), or a capability
  byte on a new `LoraCtl` kind; residuals either way: an unheard far-pocket old build on a rebroadcasting
  channel, a downgraded peer until its older profile arrives. (The second flag-day this gate briefly owned —
  the `meshpost` custodial type, ADR 2026-09.cf7a — was withdrawn before shipping by ADR 2026-09.26q3; the
  Meshtastic room no longer puts anything on Knit's mesh.) The gate also owns a budget: `LoraSizeHint`'s
  `DM_BODY_BYTES` (320) is honest only in the `0x05` form — a budget DM carrying its four inline acks is 596 B
  against the 681-B ceiling there, but **675–683 B untranscoded** — so whatever falls back to `0x03` for an
  ungated peer must drop that budget ~20 B or accept a `loraTooBig` on a tenth of the DMs the composer just
  promised would fit (`CoordinationPlaneSizeBudgetTest` pins both budget tests on the transcoded form for
  exactly that reason); **(b) the compact group form as v4** (derived nonce + labeled plaintext for `g`,
  roster-gated on every member's pinned capability; ~12 + 20–40 B a post,
  no packet-count change on its own — v3 is the DM form by executable rule, ADR 059 amendment); **(c)** seed
  `profileVersion` from the wall clock on first run, so a reinstalled peer's stale `CAP_CRYPTO_V3` /
  `CAP_RATCHET` pin is cleared by its next profile rather than by its edit count climbing back; **(d)** re-tune
  `INLINE_ACK_BYTES` (23) for the 17-B compact ack; **(e)** `docs/NEXT_WIRE_BREAK.md` item 8 — make the
  transcoder's layout the canonical signed form at the break, reclaiming what a re-encode cannot (the 7-B
  nonce from the stored form, the text ids inside sealed payloads, the millisecond clock). Rejected on
  measurement, don't retry: `ek` elision (ratchet advance rule 2 rekeys on every conversational turnaround),
  any further dictionary work (`DICT_V2` bought 16 B on the profile, zero margin), dropping the signature on
  *flooded* frames (custody and relays verify `senderId`), and a typed `@CborLabel` mirror for the transcoder
  (it could not pass unknown fields through, and `fastFanout` re-fans frames other builds originated).
- **Crypto hardening that is policy, not scheme** (from the 2026-08-29 pre-release review of v3, ADR 059
  amendment) — neither needs a version number, so neither rode v3: (1) mark a frame *seen* only after it
  verifies — today a forged frame carrying a real id shadows the genuine one for the SeenSet window (10 min,
  every frame type, pre-existing); (2) shorten the DM epoch cadence (`RatchetEngine.MAX_EPOCH_AGE_MS` = 24 h
  bounds post-compromise recovery to a day; `ek` already rides every frame, so a shorter cadence costs
  nothing on the wire — check the epoch-row/TTL budget first). Rejected outright for the mesh: post-quantum
  KEM material (ML-KEM-768 keys/ciphertexts are 1.1–1.2 kB), key-committing AEAD, header encryption, length
  padding (label 12 exists if ever wanted), tag truncation.
- **True DM routing** — DMs still flood; only the addressed recipient delivers/acks. Store-and-forward now
  *carries* undelivered DMs (`context/store-and-forward.md`), but there is still no routing table.
- **Group key-gap retransmit (v1-fallback residual only)** — the group ratchet's outbox +
  key-request loop subsumed this for ratchet-capable groups (docs/GROUP_FORWARD_SECRECY.md §7); the
  original gap — a member whose key arrives later never gets a re-seal — persists only for groups
  still pinned at v1 by a pre-ratchet member, and shrinks as capability floods.
- **E2E hardening (what remains)** — encrypting the broadcast room (its fate is a deliberate
  separate decision — an Internet-wide plaintext room is a different product question), and the
  **attachment MIME on the blob transfer**. The flooded-frame half shipped 2026-08-23 (ADR 035: a sealed
  frame names the ciphertext hash and nothing else, so a relay or carrier no longer learns photo-vs-voice
  from the frame). The residual is `LinkFraming.FileHeaderWire.mime`: `BlobExchange.onRequest` serves a
  blob to **any** neighbour that asks, so a carrier that actually pulls the bytes still learns the type.
  **Before building it:** `mime` is a required non-null `String` under `encodeDefaults = true` and
  `decodeFileHeader` returning null sets `rxAborted = true`, so *omitting* it hard-breaks blob transfer
  against deployed builds — substitute a constant instead (`image/jpeg` is already the universal fallback
  at `ScopeSync.FALLBACK_MIME`, `MeshBlobStore.fileFor` and `AVATAR_MIME`, so old builds degrade by
  nothing). Do **not** gate it on a capability bit: `Protocol.capabilities` is unauthenticated advert data,
  so gating a privacy control on the carrier's own claim hands the adversary the off switch. The knock-on
  this used to carry is **already closed**: knit/knit-next#30 (fixed 2026-08-23) moved
  `MeshBlobStore.saveIncoming`'s screening skip off the header entirely — it reads
  `messages.attachmentMimeForHash` plus `attachmentKeyForHash`, and `BlobExchange.onReceived` now re-serves
  the stored mime rather than the wire's — so substituting a constant here can no longer weaken screening.
  Receipts and reactions shipped sealed 2026-08-15 as v2 ctl frames (ADR 018,
  docs/ENCRYPTED_RECEIPTS_REACTIONS.md — DM vaccine-purge retired for the sealed era; the residual is
  the cleartext fallback toward pre-ratchet peers, counted by `receiptsSealedFallback`/
  `reactionsSealedFallback`). Group delivery ticks escalate into custody since 2026-08-22 (ADR 033 —
  batched `MessageContent.acks` toward an absent capable author; the residual is the
  never-escalating cleartext/broadcast tick, by design). (Group forward secrecy shipped as the v2 group form — the sender-key
  ratchet over the pairwise sessions, ADR 017, docs/GROUP_FORWARD_SECRECY.md; it also supplies the
  per-sender `epochSeal` export reserved for the spool plane's `sealv = 2` extension; the shared
  group root is now specified by `docs/SPOOL_PROTOCOL.md` §3.2, client machinery deferred with the
  group-scope milestone above.) See `context/e2e-encryption.md`.
