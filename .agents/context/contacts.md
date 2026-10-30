# Contacts, peers and profiles — which decision governs which symbol

The symbol → ADR routing for who a peer is to this phone: presence tiers, labels, the profile flood, group photos,
contact cards and deep links, contact removal, and the supporter badge a profile carries.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching `badge/`, `ProfilePayload.badges` / `BadgeEntry` / `ProfileV2` label 8, `peer_badges` /
  `PeerBadgeEntity`, `data/settings/BadgeStore`, `OkHttpTicketIssuer.issueBadge`, `ui/components/BadgeVisuals` (`BadgeMark`) or
  `Avatar`'s `badge`:** READ `docs/SUPPORTER_BADGE.md` and ADR 2026-10.r99s (knit-spool-coordinator owns the
  credential's bytes and the route). The badge rides the two sealed profile layouts only, never `ProfileContent`; a
  receiver stores it apart and only a sealed profile replaces it; it is verified when drawn, never only on arrival,
  against the pinned keys in `BadgeKeys`. The
  coordinator's vectors are copied from its SHA, never recomputed. Never log or dump an `r`, a `sub` or a wrapper.
- **When touching a presence dot or an online / offline label** (Profile Details, Diagnostics' node
  sections, the Contacts dot): the four evidence tiers live in `ui/Reach.kt` — `Direct` is
  `MeshController.neighbors` (a short-range radio saw the peer's own radio), `Indirect` is
  `MeshController.heardIndirectly` (another phone handed us the peer's fresh, signed frame over the radio mesh —
  `mesh/RelayedPresence.kt`, ADR 2026-10.fw8g; presentation only, never a route), `LongRange` is the long-range
  reach set or a spool scope its peer recently pushed to (`mesh/spool/SpoolPresence.kt`, one function the dot
  reads at `PRESENCE_LINGER_MS` = 15 min and the mesh at `SPOOL_COVER_MS` = 15 min), `Known` is a bare profile row — and both labelled
  surfaces derive from `reachOf` so they cannot disagree. READ ADR 2026-09.2ajk before loosening any tier;
  the Contacts list still draws a binary dot from `neighbors` alone.
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
