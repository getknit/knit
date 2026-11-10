# Contacts, peers and profiles — which decision governs which symbol

The symbol → ADR routing for who a peer is to this phone: presence tiers, labels, the profile flood, group photos,
contact cards and deep links, contact removal, and the supporter badge a profile carries.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching `badge/`, `ProfilePayload.badges` / `BadgeEntry` / `ProfileV2` label 8, `peer_badges` /
  `PeerBadgeEntity`, `data/settings/BadgeStore`, `OkHttpTicketIssuer.issueBadge`, `ui/components/BadgeVisuals` (`BadgeMark`,
  `NameBadgeMark`) or `PeerNameText`'s `badge`:** READ `docs/SUPPORTER_BADGE.md` and ADR 2026-10.r99s (knit-spool-coordinator owns the
  credential's bytes and the route). The badge rides the two sealed profile layouts only, never `ProfileContent`; a
  receiver stores it apart and only a sealed profile replaces it; it is verified when drawn, never only on arrival,
  against the pinned keys in `BadgeKeys`. The
  coordinator's vectors are copied from its SHA, never recomputed. Never log or dump an `r`, a `sub` or a wrapper.
- **When touching a presence dot or an online / offline label** (Profile Details, Diagnostics' node
  sections, `Avatar`'s `online` dot on the chat list, the DM header, contacts and group and commons members): the four evidence tiers live in `ui/Reach.kt` — `Direct` is
  `MeshController.neighbors` (a short-range radio saw the peer's own radio), `Indirect` is
  `MeshController.heardIndirectly` (another phone handed us the peer's fresh, signed frame over the radio mesh —
  `mesh/RelayedPresence.kt`, ADR 2026-10.fw8g; presentation only, never a route), `LongRange` is the long-range
  reach set or a spool scope its peer recently pushed to (`mesh/spool/SpoolPresence.kt`, one function the dot
  reads at `PRESENCE_LINGER_MS` = 15 min and the mesh at `SPOOL_COVER_MS` = 15 min), `Known` is a bare profile row — and both labelled
  surfaces derive from `reachOf` so they cannot disagree. READ ADR 2026-09.2ajk before loosening any tier;
  every avatar dot is still binary, from `neighbors` alone (`ChatListViewModel.onlineIds`,
  `ChatViewModel.peerOnline`, the contacts and member rows' `online`).
- **When touching `identity/PeerLabels`' `stale` rule, `data/LabelAnchors`, `data/LabelFade`, `peers.lastSeenAt`,
  `PeerRepository.noteSeen` / `flushSeen`, or `mesh/HeardFrom`:** READ ADR 2026-10.k8ge (and ADR 058 /
  2026-09.wuqj for the labels themselves). A stranger not heard from for a week *gives way* — still labelled apart,
  starting from its whole-universe token count, but no longer suffixing anyone; anchors (accepted, written-to,
  group co-members, verified, self) never go stale, and a new contact signal goes into `labelAnchors` too
  (`LabelAnchorsTest`). The stamp is buffered from `noteVerified` and banked on the metrics tick, never written on
  the inbound path, and `lastSeenAt` is presentation only — never a presence tier, a route, custody or eviction.
- **When touching `NameKey`, `NameKey.isBlankName`, `TextLimits.cleanReceived`, `normalizeSingleLine`'s
  `ART_WHITESPACE_RUN`, or `groupNameForDisplay`:** READ ADR 2026-10.2kfr (and ADR 2026-10.swst for the whitespace
  sets). The key folds the 73 UTS #39 single-letter lookalikes before lowering and drops Cf/Mn/Me, the
  Default_Ignorable ranges and U+2800; display-blank uses the key, wire-blank still uses `isBlank`. A received
  profile name/status is cleaned at store, a group name only where it is drawn (the stored name is what we sign).
  knit-ios mirrors all of it: `vectors/name-key-v1.json` (`NameKeyVectorTest`, and `ArtTextSemanticsTest` on ART)
  is the pin, and every case stays inside Unicode 11.
- **When touching `MeshManager.watchReachable`, its `flooded` memo / `refloodKey`, or `PROFILE_REFLOOD_MIN_MS`:**
  READ ADR 2026-09.uc8p. The first-sighting profile flood is the NAN-only key bootstrap; it goes to a peer once
  per (frame id, peer) per `SeenSet.DEFAULT_TTL_MS` — the receivers' own window, so every copy it withholds is
  one they would drop — and a newcomer the 30 s floor skipped is never memoed. Keep `ackSync.onReachable` ahead
  of every throttle (ADR 2026-09.y5f3). Regression: `MeshManagerTest.aLingerFlap…` / `aProfileEditIsReflooded…`.
- **When touching a group's photo — `groupPhotoDecision` / `photoWins` / `settleArrivedGroupPhoto` in
  `InboundPipeline`, `GroupEntity.photoHash` / `photoShownHash`, `GroupDao.awaitingPhoto` /
  `photoHashesNeedingFetch`, `BlobRepository.dropRefusedGroupPhoto`, or `GroupDetailsViewModel`'s set path:**
  READ ADR 2026-09.nxcq. `photoHash` is the photo this device decided on — stored the moment it is heard, and
  the only one `toGroupInfo` advertises — and `photoShownHash` the one that renders, once its bytes are local
  and screened; every surface reads the shown one, and a frame carrying the shown one brings #108 back. A clock
  tie keeps the held photo. The want is the row (no in-memory map), and a refused photo stays decided and keeps
  its verdict so nothing pulls it again. Regression: `AttachmentLabTest`'s two group-photo-pull scenarios.
- **When touching a peer's photo — `PeerEntity.advertisedAvatar` / `advertisedKnown`, `PeerDao.awaitingAvatar` /
  `facesNeedingFetch` / `clearDanglingFaces` / `advertisedUnknown`, `InboundPipeline.adoptAdvertisedAvatar` /
  `pointPeerAtAvatar` (`FaceGate`) / `onAvatarReceived` / `backfillAdvertisedAvatars` / `releasedByProfile`,
  `BlobRepository.dropRefusedFace` / `refuseAsPhoto`, `photo_refusals`, `MeshManager.rewantFaces` /
  `sendAvatarIfNeeded` / `sentAvatarHashes`, or `BlobExchange.wantFaces` / `claimPush`:** READ ADR 2026-11.4ckq (and ADR
  2026-09.nxcq, whose split it applies). `advertisedAvatar` is the photo the newest profile decided on, recorded at once
  by every profile writer under the presentation watermark, blocked or not; `avatarHash` is the one that renders, bytes
  held and screened. A renderer never reads the advertised one (#108's trap for peers). The want is the row: a landing
  blob is adopted by the rows advertising it, a push only by a row that advertises it or does not know, and a face is
  written only while its bytes are held, read in the write's transaction (`pointPeerAtAvatar`, #269), and the database
  re-arm wants every advertised photo not held for a bounded set of peers (contacts, co-members, conversations, verified,
  heard within a day; those with a reason ahead of the merely heard, 64 at most, fetch only, a quarter of the want table
  left to everything else, never before the start's sweeps). A refused photo keeps its
  verdict (or its over-ceiling refusal) while a row advertises it, never its bytes. A linked author is asked alone
  until NONE, its link going or its push's serve-memo floor, and its push stamps that memo. Regression:
  `PeerFaceLabTest`, `BlockAndRequestLabTest`, `InboundPipelineTest`'s advertised-photo cases.
- **When touching contact cards, the Add-by-link / share-link flow, deep links (`getknit.app/c`,
  `knit://`), or `mesh/IntroSync`:** READ `docs/CONTACT_CARD.md` (the card layout + golden vectors, the
  intro driver's rules, the assetlinks prerequisite) and `docs/SPOOL_PROTOCOL.md` §3.5 (the pair scope);
  decision record ADR 042. The card is versioned by `v` and additive under the WIRE_COMPAT rules; import
  never sets `verified`.
- **When touching `contacts/ContactRemover`, the profile's Remove contact (`ContactRemoval`),
  `SettingsStore.unaccept`, `IntroSync.cancel` / `MeshController.cancelIntro`, `contactStanding` /
  `groupsAcceptedOnlyThrough` in `ui/contacts/ContactUniverse.kt`, or what `MeshManager.onCommonsMember`
  accepts:** READ ADR 2026-09.adgd. A contact is derived, so removal clears our own signals — a group that
  rested on the peer is accepted first, the accept is cleared last, the DM thread goes — and never the peer
  row, the session, custody or the block list; nothing is sent. A group co-member stays a contact (only
  their own leave shrinks a roster), and a commons member is accepted on first sighting only. Regression:
  `ContactRemoverTest`, `ContactUniverseTest`, `BlockAndRequestLabTest`.
- **When touching `contacts/DirectContacts` (`directContactIds`), `SettingsStore.acceptFromCommons` /
  `commonsAccepts` / `backfillCommonsAccepts`, or which `settings.accept` a new path calls:** READ ADR 2026-11.xp6d
  ("The presence key"). The presence key goes to direct contacts only: the user's own accepts, verified peers and DM
  threads they wrote in, less blocked node ids and blocked device tags — never group co-members, and never a commons
  poster, whose accept is marked as the commons' in the same edit. A path that accepts on the user's behalf calls
  `accept` (which clears the mark); one that accepts for someone else's act must not. `DirectContacts.sets` is the change
  signal (recomputed on every input emission, never deduplicated); `observe` is `sets` deduplicated, a value for a
  level-triggered reader, and may drop a set another reader saw (the key engine's trigger, `crypto.md`). Regression:
  `DirectContactsTest`, `DirectContactsObserveFoldTest`, `SettingsStoreTest`, `MeshManagerTest`'s commons-member cases.
- **When touching blocking — `SettingsStore.blockedNodeIds` / `blockedDeviceTags` / `block` / `unblock`,
  `data/settings/BlockListStore`, `mesh/BlockListWatch`, `InboundPipeline.rewantOwedFaces` /
  `settleBlockMovedMidProfile`, `InboundPipeline.onBlocked` / `reapplyHeldProfile` / `caughtByDeviceTag` /
  `applyDeviceTagBlockContinuity`, the `blocked` argument of `storeProfileRow`, or the Blocked screen
  (`ui/blocked/`):** READ ADR 010, ADR 2026-09.bts9 and ADR 2026-11.nsbf. A block is presentation only: never custody,
  relay or a key want. It holds the person's name, status and flag as they were, draws their face as the monogram and
  never wants, adopts or keeps their photo; everything that is not presentation (pin, prekey, `updatedAt`, LoRa fields)
  moves as for anyone. The block is decided before the row is written, outside its transaction. An unblock applies the
  held `peer_profiles` frame with no notice and brings the face back from its advertised hash, owed in DataStore
  (`owedFaces`) and re-wanted on every database re-arm until it shows; the watch diffs against the list it last acted
  on (`actedOn`), recording a block before its hook and an unblock after it (#270), and the storage sweep is the
  backstop for a block made on any path or recorded and not yet acted on. Our own profile and photo still
  go to a blocked neighbour. A paired change with knit-ios. Regression: `InboundPipelineTest`'s block cases, `BlockListWatchTest`, `MeshManagerTest`'s block-list
  cases, `BlockAndRequestLabTest`.
