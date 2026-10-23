# The next wire break — staging list

A parking lot for changes that are **only** possible (or only clean) at a coordinated wire break, so that
if a break ever happens they all ride it together. Nothing here is scheduled. Adding an item costs
nothing; doing one costs a mesh partition, so the list exists precisely so that no single item has to
justify a break on its own.

Read `docs/WIRE_COMPAT.md` first — it defines what a break *is*, the additive rules that make most changes
not need one, and the precedent record. This file is the complement: the debt those rules have
deliberately accumulated.

**Adding to this list:** one entry per change, with *what*, *why it is parked*, *what to do at the break*,
and anything that would bite. If an item turns out to be doable additively after all, delete it from here
and record it as a precedent in `WIRE_COMPAT.md` instead — that is the better outcome every time.

## What a break costs now (read before scheduling one)

The pre-1.0 alpha breaks (DB v19 / v21 / v22) were cheap: no installed base, and every schema bump wiped
destructively. Since the v1 launch baseline neither is true.

- **It partitions the fielded mesh.** There is no version negotiation that can route around a break; a
  build across the break simply does not see the other side. That is by design (a hard partition at
  discovery beats silent mis-decoding), and it is the whole cost.
- **The partition outlives the release.** Knit ships from two channels under two keys (Play and F-Droid,
  `.agents/context/distribution.md`), and F-Droid's rebuild-and-publish lag plus users who update on their
  own schedule mean the two halves of the mesh drift apart for *weeks*, not hours. Whatever the break is,
  the window where a Play user and an F-Droid user cannot see each other is the real deployment risk.
- **It is no longer a wipe.** From DB v1 on, every `@Database` bump ships a tested `KnitMigrations` entry
  (`KnitDatabaseMigrationTest` fails the build on a missing one). A wire break does **not** license a
  destructive DB fallback — if the break re-mints identities or invalidates pins, that has to be a written
  migration, not `fallbackToDestructiveMigration`.
- **The spool plane is not automatically broken with it.** `ScopeFrames.seal` seals the whole `signed`
  blob opaquely, so a mesh-layer field change is invisible to a spool. But scope ids derive from node
  identity — anything that changes the `NodeId` derivation or the published key bundle re-mints every
  scope, and `docs/SPOOL_PROTOCOL.md` §13 vectors + `knit-spool`'s `SpecVectorTest` have to move in the
  same pass.

## The lockstep checklist (any break)

Current values, for the diff you will write:

| Marker | Where | Now |
| --- | --- | --- |
| Wi-Fi Aware service name | `WifiAwareTransport.SERVICE_NAME` | `_knitmesh1._tcp` |
| BLE service UUID | `BleConstants.SERVICE_UUID` | `0xFE30` |
| Protocol version | `Protocol.VERSION` | `1` |
| DB version | `KnitDatabase` `version` | `11` (migrate forward, never wipe) |

Plus, every time: regenerate `GoldenVectorTest` fixtures; check whether `ScopeVectorTest` /
`SpoolRecordsTest` move (and if so, regenerate `knit-spool`'s in the same commit); update
`docs/WIRE_COMPAT.md` with a precedent entry; CHANGELOG.

**Open question, not yet answered:** the LoRa plane's rendezvous channel (`KnitChannel`, derived from
`INFO = "knit/lora/channel/psk/v1"`) is a *fourth* discovery marker and is not on the checklist above,
because a break's effect there has never been worked out. Frames cross a Meshtastic channel as the same
`WireEnvelope` bytes, so a break does not partition boards at discovery the way NAN/BLE do — the far side
receives the frame and fails to decode it. Decide, at the break, whether the channel's `INFO` version
bumps too (partitioning cleanly, at the cost of every user re-provisioning their board).

---

## Candidates

### 1. Remove `ChatContent.attachmentMime`

**What.** Delete the field from `ChatContent` (`mesh/protocol/Wire.kt`) outright, along with the
compatibility shims that exist only to tolerate it: `InboundPipeline.plaintextContent`'s substitution of
the sealed `MessageContent.attachmentMime` over the cleartext shell, and `ScopeAttachments.refFor`'s
fallback to an older peer's cleartext hint.

**Why it is parked.** ADR 035 stopped *populating* the field on sealed frames — a DM/group frame now names
the ciphertext hash and nothing else — but the field itself stays, because un-populating is additive and
removal is not. Two things still read it: an older peer still *sends* it (and we must not mis-decode what
it sends), and the plaintext broadcast room still fills it, having no seal to move the value into.

**At the break.** Remove it. The room half is the only real decision: room attachments would lose their
declared type, and the answer is that they do not need one — the composer does not offer voice notes in the
room (ADR 034), so a room attachment is an image, and `image/jpeg` is already the universal fallback at
`ScopeSync.FALLBACK_MIME`, `MeshBlobStore.fileFor` and `AVATAR_MIME`. Removing the field also retires the
one fingerprint ADR 035 introduced — `attachmentHash` present with `attachmentMime` absent currently marks
a patched build.

**Still true after arbitrary files (ADR 2026-09.qq2r), and deliberately so.** Files widened the type set the
app can send, which is exactly the change that could have invalidated "a room attachment is an image" — so
they are not offered in the room, for the same reason voice notes are not (nothing on the device screens
one, and the room floods unencrypted to strangers). The composer's file item is off whenever
`ChatUiState.isRoom`, the same one-flag shape as `voiceEnabled`. If that is ever reversed, this item's
justification goes with it — and so does `MeshBlobStore.saveIncoming`'s screening skip, which relies on the
identical assumption.

**No longer true after link-preview cards (ADR 2026-09.n752), so the justification is withdrawn.** The
room now originates a second attachment kind on purpose: a link-preview card is a container blob typed
`LinkPreviewBlob.MIME`, and in the room that type rides `ChatContent.attachmentMime` — the field is
load-bearing again, since a receiver keys the card bubble (and the card-aware screen in
`MeshBlobStore.saveIncoming`, which *opens* a card rather than skipping it) on that value. Removing the
field at a break therefore needs a replacement discriminator for the room, such as a one-byte attachment
`kind`, before the room could tell a card from a photo. The item stays parked; only its "the room needs no
type" argument is gone. The cleanup of `plaintextContent`'s substitution and `refFor`'s fallback still
stands as written.

**Watch out.** `MessageContent.attachmentMime` (inside the ciphertext) is a *different* field and stays —
it is where the real value has lived since ADR 035. Do not let a mechanical rename or a broad grep take
both. `FastFrameCodec`'s `DICT_V1` contains the literal string `"attachmentMime"`; the dictionary is frozen
and stays byte-identical regardless (a stale dictionary entry costs a little compression, nothing else) —
unless the break is also the moment to mint `DICT_V2`, which is additive by `dictId` and needs no break.

### 2. Remove `LinkFraming.FileHeaderWire.mime`

**What.** Drop `mime` from the blob-transfer file header (`mesh/link/LinkFraming.kt`).

**Why it is parked.** It is the residual ADR 035 could not reach: `BlobExchange.onRequest` serves a blob to
any neighbour that asks, so a carrier that actually pulls the bytes still learns photo-vs-voice from the
header. It cannot be removed additively — `mime` is a required non-null `String` under
`encodeDefaults = true`, and `decodeFileHeader` returning null makes `FileIntake` skip the whole file, so omitting it
hard-breaks blob transfer against every deployed build.

**At the break.** Remove the field. If the interim mitigation the roadmap prescribes has already shipped
(substituting a constant so the header says nothing), this is deleting a field that carries no information;
if it has not, this supersedes it.

**Watch out.** Do **not** gate this on a capability bit at the break or before it. `Protocol.capabilities`
is unauthenticated advert data, so gating a privacy control on the carrier's own claim hands the adversary
the off switch. The screening knock-on is already closed (knit/knit-next#30): `MeshBlobStore.saveIncoming`
reads `messages.attachmentMimeForHash` + `attachmentKeyForHash`, not the header.

### 3. Make `ProfileContent.version` required and drop the `sentAt` fallback

**What.** Make `version` non-null and delete every `content.version ?: env.sentAt` read.

**Why it is parked.** ADR 022 split the profile's LWW version off the envelope `sentAt` (which was also the
custody expiry key, so an unedited profile fell out of custody entirely). The compatibility shape is
additive but **not symmetric**: a new build reads `version ?: sentAt`, which is exact for an old peer, but
an *old* build has no fallback — it reads the new publish stamp as the version, jumps its watermark ahead,
and then rejects sealed `CTL_PROFILE` updates whose real `payload.version` is a smaller number. That
asymmetry was accepted while the plane is testers-only; it does not want to be permanent.

**At the break.** Required field, fallback deleted — every peer across the break sends a real version.

### 4. Retire crypto scheme v1

**What.** Drop `EncEnvelope.v = 1` (the static-key per-recipient HPKE wrap, `keys`/`WrappedKey`) on both
send and receive, and with it: the `CAP_RATCHET`-plus-`prekey` send-time gate and its demote-to-v1 path,
the group send's every-member eligibility check, the `receiptsSealedFallback` / `reactionsSealedFallback`
cleartext fallbacks, and the v1-only group key-gap retransmit residual.

**Why it is parked.** Inbound v1 is accepted forever by design, and v1 is still what an outbound message
demotes to when any recipient (or, for a group, any single member) is pre-ratchet. The dual stack is the
largest single simplification the break would buy, and it cannot be taken while any peer might be v1-only.

**At the break.** A break already partitions off every pre-ratchet build, which is exactly the precondition.
Bump `EncEnvelope.MAX_SUPPORTED_VERSION` behaviour deliberately (a *minimum* supported version is the new
concept here — see item 6) and keep the unknown-version rule intact: drop locally, count, still relay and
carry (`canCarry` never looks at `v`).

### 5. Retire the cleartext `receipt` / `reaction` frame types

**What.** Stop accepting `FrameType.RECEIPT` / `FrameType.REACTION` inbound, and remove the cleartext
senders.

**Why it is parked.** ADR 018 sealed both as v2 ctl frames (`CTL_RECEIPT = 5`, `CTL_REACTION = 6`), but the
cleartext types stay accepted forever so pre-ratchet peers still work, and they are the last flooded
plaintext metadata about who is reading and reacting to what. They are also the only remaining trigger for
the cleartext DM vaccine-purge, so retiring them changes a custody path, not just a parser.

**At the break.** Retire them with item 4 (same precondition, same argument). The `type` strings are
**burned forever** afterwards — rule 3, never recycled.

**Watch out.** `FrameType.isReplayable` / `isCustodial` are fixed lists on deployed builds; removing a type
from them changes what a *carrier* stores. Re-read the ADR 006 convergence argument before touching either
list, and check `ENCRYPTED_RECEIPTS_REACTIONS.md` §4 for the form-keyed custody rule that replaced the
purge.

### 6. Retire the legacy `0x01` fast-frame framing and the `CAP_FAST_COMPACT` gate

**What.** Make the compact framing (`0x03` / fragment `0x04`, `mesh/link/FastFrameCodec`) the only
coordination-plane encoding, and stop consulting `Protocol.CAP_FAST_COMPACT` when picking one.

**Why it is parked.** `0x01` is documented as kept forever precisely so a sender can always fall back for a
peer that does not advertise the bit. Once no such peer exists, the fallback and its gate are dead weight
on every send path.

**At the break.** Drop the `0x01` writer and reader. `0x02` stays burned either way. If item 8 has landed
the canonical form is already compact, so the `0x03` deflate and the `0x05` transcode (ADR 060) retire in
the same pass and the fast planes carry the signed bytes as-is. Note this is
the one item on the list that is *purely* cleanup — it buys no capability and no privacy, so it should ride
a break that is happening anyway and never motivate one.

### 7. Decide what `Protocol.MIN_SUPPORTED` means

**What.** It is `1` and unused — "reserved for future route-around". A break is when it either gains a
meaning (refuse/deprioritize a peer below it) or gets deleted.

**Why it is parked.** Deciding it costs nothing today because nothing reads it; the reason to decide it *at*
a break is that a break is the first time two protocol versions exist in the field on purpose, and items 4
and 5 both want a way to say "below this, do not bother".

**Watch out.** It is derived from the *unauthenticated* advert (`Protocol.parse`), so it can only ever be a
routing/degradation hint — never a trust or security input. Anything that gates a privacy or integrity
property on it repeats the mistake item 2 warns about.

### 8. Make the transcoder's byte layout the canonical signed form

**What.** The schema-aware `0x05` transcoder exists (ADR 060, `mesh/link/FrameTranscoder`); the break is
where its layout stops being a transport-local re-encoding and becomes what
`WireCodec` emits and signs: integer map keys (`@CborLabel` + `preferCborLabelsOverNames`, kotlinx ≥ 1.11),
raw 16-byte ids wherever a `FrameId`/`NodeId` rides as text today (`RelayEnvelope.id`/`senderId`/
`recipientId`, `ReceiptContent.ackId`, `ReactionContent.messageId`, `GroupInfo.members`/`createdBy`/
`departed`, `KeyReqContent.nodeIds`, `Mention.nodeId`, `ReplyRef.messageId`/`authorId`), `sentAt` in
seconds, and a DM's `recipientId` as a short prefix rather than the full id.

**Why it is parked.** Every one of these changes the signed bytes, so none is additive — and the transcoder
gets nearly all of the byte savings *without* the break, which is exactly why a break should never be
scheduled for this alone. What the transcoder cannot reach is what a re-encode must reproduce exactly: the
full 16-B `recipientId` (a 4-B prefix would make an old relay read the DM as a room post, since
`recipientId == null && group == null` *is* the room), the millisecond `sentAt`, and the text form of
every id nested inside a content payload. Measured 2026-08-29: the break buys margin on the frames that
matter (a tick's floor drops from ~135 B to ~110, a reaction's from ~218 to ~190, a 40-char DM's from ~220
to ~190); it does **not** buy a one-packet 100-char DM, whose floor (sig 64 + ids 48 + ek 32 + ct 124)
stays ~285 B under any layout.

**At the break.** Switch `WireCodec` to the compact layout, regenerate `GoldenVectorTest`, and retire
`0x03`/`0x05` alongside `0x01` (item 6). The `MessageContent` half — int labels and raw ids *inside* the
ciphertext — is **not** on this list: it shipped additively as the v3 compact plaintext (ADR 059),
discriminated by `EncEnvelope.v = 3` (a reserved label-0 version rides inside it — `MessageContent.v` is
never emitted, so it could not gate) behind `CAP_CRYPTO_V3`, so the break only has to move what is outside
the seal. One thing the break *can* reclaim that ADR 059 could not: v3 carries `nonce` as an empty byte
string (7 B) because `canCarry` decodes the payload on every fielded build; once every carrier is past the
break the field can go (ADR 060's transcoder already elides it *on the air* — the break reclaims it from the
stored and signed form).

**Watch out.** The recipient prefix changes what a relay sees: `isBroadcastRoom` (`LoraFramePolicy`,
`FrameFanout`, `InboundPipeline`) keys on `recipientId == null`, and `ForwardStore`'s DM custody rule
keys on the full id. `ScopeFrames.seal` seals `signed` opaquely, so the spool plane is unaffected — but
`docs/SPOOL_PROTOCOL.md` §13 vectors carry frame bytes and move with `GoldenVectorTest`.

### 9. Multi-device: a device id under the identity

**What.** Let one person run one identity on two phones: a device id in the profile beside the key, one
ratchet session per (peer, device) instead of per node id, group sender keys minted per device, and
custody that can tell "this phone sent it" from "the other phone did".

**Why it is parked.** Nothing on the wire names a device. A node id *is* the hash of the key, every session
and sender key is keyed on it, and every transport drops its own node id at discovery — so two phones on
one identity never link, meet each other's frames only as self frames through a third node, and keep
resetting each other's DM sessions. ADR 2026-09.ypcc detects the state after the fact from the profile
stamp and offers a sign-out; it does not make the state work, because a session keyed on anything but the
node id is a different ratchet header, which is a different `EncEnvelope`, which is the break.

**At the break.** A device id in `ProfileContent` and the ratchet header; sessions and sender keys keyed
on (node, device); `finishRestore` stops resetting every peer, since a restore then adds a device instead
of replacing one. Retire the clone watch with it — the stamp comparison is only sound while one node id is
one phone.

**Watch out.** The self-frame paths (`verifierBundle`'s self branch, `handleProfile`'s self guard,
`handleChat`'s self-to-self drop) all assume "our node id" means "this phone"; each becomes "our node id,
another device" and has to deliver rather than drop.

### 10. Bind a frame id to its author

**What.** Derive a frame id from its sender — e.g. a hash over the sender's node id and the random part — so
that a receiver can tell from the id alone whose frame it names, and refuse a frame whose id does not derive
from its `senderId`.

**Why it is parked.** A `FrameId` is 128 random bits bound to nobody, and the stores are first-write-wins by
id (the `SeenSet`, custody, `messages`). ADR 2026-10.gpa3 judges a quoted reply against the original held under
the quoted id, and checks author and thread because the id alone proves nothing — but a squatter who stores
a frame under someone else's id first still owns that id on that phone, so the real reply's quote reads
"unavailable" there. Minting bound ids is additive (an id stays an opaque string to every node); *refusing*
unbound ones, which is what closes the squat, partitions every build that still mints random ids.

**At the break.** Mint bound ids everywhere (Android, iOS, the spool's own records if any name frames), and
drop a frame whose id does not derive from its signed `senderId` at `verifyInbound`, before dedup. The shape half
is already in: every door refuses an id that is not the shape its type is minted with (`FrameId.admits`, ADR
2026-10.9qr4), and a profile's id is already bound to its sender that way. Keep a bound id in the `FrameId` form,
16 derived bytes as `FrameId.forMeshPost` derives its own, and every fielded gate admits it, so minting stays
additive; any other form is a new shape that every build before it drops.

**Watch out.** Locally minted ids are not frame ids and keep their own shapes: status notices
(`leave:…`, `rejoin:…`), `FrameId.forMeshPost` for heard Meshtastic posts. The quote check already requires the
quoted author to match, so a bound id changes nothing there except that a squatted id can no longer be stored.

### 11. Take a group's roster, name and photo out of the cleartext envelope

**What.** `RelayEnvelope.group` is a cleartext `GroupInfo` on every group frame: the founding roster
(`members`, `departed`, `createdBy`), the shared `name` and the decided `photoHash` / `photoUpdatedAt`. A
`groupupdate` (`MeshManager.sendGroupUpdate`) is nothing *but* that field, so a rename or a new photo is
legible to every relay and carrier. The break moves all of it inside the seal, and leaves only a form marker
in the clear (so `recipientId == null && group == null` still *is* the room) plus whatever tag the
group's frames are filed by (below).

**Why it is parked.** `GroupInfo.members` is a non-null `List`, so a frame without it does not decode on any
fielded build, and every fielded member pins a first-seen group from the frame's roster
(`InboundPipeline.vetRoster`). Two things also make stripping `members` alone pointless:

- **The id is the roster.** `Conversations.groupIdFor` is an unkeyed SHA-256 over the sorted member set
  under a public salt (`GROUP_ID_SALT`). A relay sees every `senderId`, and a group has at most eight
  members (`GroupInfo.MAX_MEMBERS`), so the member set falls out of the cleartext id by hashing
  combinations of the node ids it has heard. Hiding the roster means a cleartext tag that is not derivable
  from the member set.
- **Scheme v1 names every member.** A v1 group frame carries one `WrappedKey` per member, each tagged with
  a cleartext `to`. Until item 4 retires v1, a mixed-capability group leaks its roster whatever happens to
  `GroupInfo`, so this item rides with item 4 or not at all.

The cleartext roster was never there for the vaccine purge (group frames were never vaccine-purged: no
single recipient, no per-member ack). It was there for custody targeting and first-sight reconciliation,
and both have since gained another route:

- **Custody targeting is an optimisation.** `ForwardSync.onNeighborAdded` offers a carried group frame only
  to a roster member. Without the roster a carrier offers it to any neighbour, as it does a room post. The
  live flood already sends every group frame to non-members, so this costs custody re-push airtime, not
  correctness.
- **First sight has a sealed door.** Since ADR 2026-09.mjaj the seed carries the founding roster inside the
  DM ratchet (`GroupKeyPayload.group`, pinned through `pinRosterFromSeed`), so a member no longer needs the
  frame to learn the group. The frame's copy is the fallback for seeds from builds without that field, which
  the break partitions off.
- **Routing reads the form, not the members.** `FrameFanout`, `LoraFramePolicy`, `IngressBudget` and the
  spool's `ScopeFrames` key on `group != null` and sometimes `group.id`; none of them reads `members`.

**At the break.**

1. **Seal the roster, name and photo.** The founding roster travels in the seed (already true) and in the
   sender-key plaintext of each group frame, and `vetRoster` runs on the sealed copy. Name and photo keep
   their last-writer-wins clocks (ADR 2026-09.nxcq) inside the seal; a `groupupdate` becomes a sealed group
   frame rather than a bare envelope. `groupIdFor` and its "the same people are the same group" rule stay,
   computed inside the seal and never put on the wire.
2. **Decide the cleartext tag.** Something has to let custody's group queries (`ForwardStore.liveGroupChatFrames`),
   `ForwardRepository`'s `groupId` column and the spool's `ScopeFrames` / `ScopeAttachments` file a frame
   under its group. The candidates, none chosen yet:
   - **No tag.** A member finds the chain by `senderId` and `GroupRatchetHeader.se`, with at most a few trial
     decrypts when it shares several groups with that sender. Custody's per-group replay and the spool's
     per-group filing have to work from the opened frame instead.
   - **A tag keyed by the group root** (`GroupKeyPayload.gr`). Unguessable, but it changes at every
     departure re-mint (`docs/SPOOL_PROTOCOL.md` §3.2), so a group's frames are linkable only within one
     root era, and custody must accept a group under more than one tag.
3. **Settle carried attachments from the row.** The carrier's attachment pull decides "is this group
   frame ours" from the frame's roster (`ForwardRepository.carriedAttachments`, the `member` flag in
   `InboundPipeline.onCarriedFrame`, ADR 2026-10.js2p). After the break it reads the local group row, which
   the seed now pins before the first frame can be opened.
4. **Drop the custody filter.** `ForwardSync`'s member-only offer goes; group frames are offered like room
   posts, bounded by the same TTL and quota they already have.

**Watch out.** This hides who is *in* a group, not who *talks* in it. `senderId` stays cleartext on every
frame, `GroupRatchetHeader` still shows per-sender send counts and epoch cadence, and a departure is still a
signed `groupleave` that names its group, followed by a burst of N−1 seed DMs (the rekey fingerprint
`docs/GROUP_FORWARD_SECRECY.md` §9 accepts). Sealing `groupleave` is its own decision: the signed leave is
what lets a member refuse a forged departure, and moving it inside the seal moves that check with it.
Re-read ADR 2026-09.v6fu before touching either. The roster cap is a bound on storage and fan-out, so
inbound first sight still enforces `MAX_MEMBERS`, now on the sealed roster.

### 12. Carry DMs and groups for 7 days, with quotas sized to match

**What.** Raise the mesh custody TTL (`ForwardRepository.DEFAULT_TTL_MS`) from 24 h to 7 days, and raise the
count bounds beside it (`DEFAULT_MAX_ROWS` 1,000, `DEFAULT_MAX_PER_SENDER` / `DEFAULT_MAX_PER_GROUP` /
`DEFAULT_MAX_BROADCAST` 200 each). Today a contact who is out of reach for more than a day never gets a DM
unless a spool carries it; a week covers the weekly meeting, the weekend away and the phone left in a drawer.

**Why it is parked.** Nothing on the wire changes, and every build decodes the same frames, so this is not
a break by `WIRE_COMPAT.md`'s definition. It is parked because the TTL and the quotas are
**convergence-critical** (`.agents/context/store-and-forward.md`, "A bounded custody quota must be
*convergent*"): the content digest folds each node's live set, and the cue plane brings up an NDP whenever two
digests differ. A 7-day node holds frames that a 24-hour node refuses as dead on arrival, so the two digests
differ as long as there is any traffic in the 1–7 day band, and every mixed pair churns NDPs for the whole
rollout window. That window is weeks long (see "What a break costs now"). A break partitions the two
populations, so the lockstep comes free. The additive route would be a capability-gated second digest (a new
node folding a 24-hour digest for old peers). That costs scarce cue-plane bytes and only buys the transition,
so it is not worth building unless this item has to ship without a break.

**The TTL alone buys little.** Every bucket is trimmed to its newest N by `(sentAt, id)`, so a sender who
sends more than 200 custodial frames a week loses their oldest frames to the count bound long before day 7.
Only quiet senders benefit unless the quotas grow too. At constant traffic, holding 7× the window needs
roughly 7× the rows. Size the new bounds from a measured mean carried-row size and from `…debug.STORE` counts
on the lab and field phones, not from a guess. The SQLCipher file grows with the global cap, and so does every
digest exchange's re-serve burst to a newcomer (worst over BLE and Coded PHY). Fix the numbers before the
break, in this entry.

**At the break.** Move every constant that is defined as a multiple of the custody TTL or a copy of a quota,
on Android and iOS (`knit-ios`'s `KnitStore/Custody/CustodyLimits.swift`) in the same release:

- **Custody.** `DEFAULT_TTL_MS` and the four count bounds. `ForwardSync.ACK_TOMBSTONE_TTL_MS` must stay
  ≥ the TTL, or a delivered DM's still-circulating copy is re-stored after its tombstone lapses. It is an
  in-memory `SeenSet`, so 7 days of tombstones is a memory bound to check, not just a number to change.
  `PendingInbound.MAX_PER_SENDER` mirrors the per-sender quota.
- **Ratchet retention (crypto-owner territory).** `RatchetRepository.SKIPPED_TTL_MS` / `RECV_EPOCH_TTL_MS`,
  `GroupRatchetRepository.SKIPPED_TTL_MS` and `RatchetSessions.STRANDED_TAIL_MS` are all "2× custody" (48 h).
  If they stay at 48 h, a re-served frame aged 2–7 days is undecryptable, so custody carries it for nothing.
  Worse, `STRANDED_TAIL_MS` is the bound past which an unreadable frame counts as live reset evidence (ADR
  026), so an old-era remnant arriving on day 3 would trigger spurious session resets. If they move to 14
  days, receive keys live 14 days instead of 2, which is a real forward-secrecy cost. Decide it with
  crypto-reviewer, and record it in an ADR. `MAX_EPOCH_AGE_MS` in `RatchetEngine` / `GroupRatchetEngine` is a
  rotation cadence that can stay at 24 h, but its doc comment ("no legitimately re-deliverable frame belongs to
  an older epoch") stops being true. A 7-day backlog then spans about 7 epochs, not about 2, so the
  skipped-key work bound beside `MAX_EPOCH_MESSAGES` needs to be reargued.
- **Spool.** `ScopeRegistry.DEFAULT_TTL_MS` is 48 h by definition as 2× custody (`docs/SPOOL_PROTOCOL.md`
  §12.2, the 24–48 h band §9.6 / ADR 062 is written around, and the §3.5 intro re-send floor of 20 h "under the
  24 h custody TTL"). Keeping 2× means 14-day scopes and 7× the operator's storage. Keeping 48 h means the
  spool holds less than custody, which flips §9.6's reasoning. Either way the spec, its changelog and
  `knit-spool`'s daemon defaults move in the same pass.
- **Derived, check only.** `CallManager`'s `TOMB_KEEP_MS`, `ContributionLedger`, `PullThroughGate`,
  `AttachmentDeferPolicy` and the carrier's DM attachment pull window in `InboundPipeline` (`sentAt +
  DEFAULT_TTL_MS`, #195) all derive from the constant. That last one means a carrier pulls a DM's attachment
  for 7 days, so `CARRIER_BLOB_BUDGET_BYTES` (128 MiB) becomes the binding bound. Check that it is the bound
  you want.

**Watch out.** `forward_store.expiresAt` is stamped at store time from the local constant
(`sentAt + ttl`), so rows stored before the upgrade keep their 24-hour expiry while the same frame stored
after the upgrade gets 7 days. That is a digest divergence on exactly the frames that straddle the
release. The break's DB migration must restamp `expiresAt` from `sentAt` and the row's TTL class. Leave the
broadcast room's 6-hour `DEFAULT_BROADCAST_TTL_MS` alone unless there is a separate reason: it is ambient,
unencrypted chatter to strangers with no ack to purge it, and a week of it would crowd addressed traffic
out of the global cap. Like every custody rule change, this one needs a `mesh/lab/` scenario that ends in
`assertConverged` across a frame older than 24 hours, and `liveFingerprint` must match across the lab phones
after the trial.

---

## Deliberately *not* on this list

- **Reclaiming capability bits or ctl values.** `CAP_E2E` / `CAP_GROUPS` / `CAP_REACTIONS` /
  `CAP_STORE_FORWARD` are universal and now diagnostic-only. A break
  makes recycling them *technically* safe, and it is still the wrong move: the registries are append-only so
  that no one ever has to reason about which build's meaning applies, and a spare bit costs nothing. Keep
  spending new numbers.
- **Anything reachable additively.** The `CTL_SCOPE_CONFIG = 7` ctl (shipped additively, 2026-10-23), new frame
  types, new nullable fields, a `DICT_V2` under a fresh `dictId` — all of these ship without a break, and
  putting them here would be a category error. If an item on this list turns out to be one of these, that is
  a win: move it out.

## Open questions

- **Encrypting the broadcast room** is a deliberate open product question, not a wire question (an
  Internet-wide plaintext room is a different product). If it were ever answered "yes", `ChatContent`
  collapses to `enc` + `attachmentHash` and items 1 and 5 change shape — so the product answer should come
  first, and if it is close, a break should wait for it.
- **The LoRa channel marker**, per the checklist section above.
