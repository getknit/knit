# Crypto plane — which decision governs which symbol

The symbol → ADR routing for Knit's end-to-end crypto: the DM epoch ratchet and the group sender-key ratchet
(`mesh/crypto/ratchet/`, `data/ratchet/`), the envelope, sealing and signing (`mesh/crypto/` outside `scope/`),
session resets, group seeds and the roster they are sealed to (`mesh/PendingGroupKeys.kt`, the seed and roster
paths of `InboundPipeline` and `MeshManager`), and the intro answer (`mesh/IntroSync.kt`). The `crypto-owner`
reads it before it writes and the `crypto-reviewer` before it judges (both in `.agents/personas/`); `AGENTS.md`
points here instead of carrying these lines. Each entry names the symbols that trigger it, the ADRs to read
(`.agents/memory/decisions.md` maps an id to its file, amendments included) and, where there is one, the section of
`docs/FORWARD_SECRECY_RATCHET.md` (FSR) or `docs/GROUP_FORWARD_SECRECY.md` (GFS) that is the normative spec. The
mechanism in prose is `.agents/context/e2e-encryption.md`. An entry is an index into its ADRs, not a substitute:
where they disagree, the ADR wins and the entry is fixed.

Crypto-adjacent routing that lives in another subsystem's file under `.agents/context/` (`storage.md`, `spool.md`,
`contacts.md`, `blobs.md`), because that subsystem owns it: at-rest secrets (`data/crypto/`
— `DatabaseKey`, `SqlCipherKey`, `KeystoreSecret`, `IdentityKeyStore`'s load and mint; ADRs 2026-09.uzkm,
2026-10.47rw), the spool plane's scope crypto (`mesh/crypto/scope/`, `docs/SPOOL_PROTOCOL.md`), backup and restore
(ADR 2026-09.6mj7 — the reset set it hands this plane is qerd's, below), clone detection (2026-09.ypcc), the
carrier's kept profiles and key serving (`KeyExchange`, 2026-09.g64k), attachment identities (2026-10.mptm), the
✓✓'s way home (`AckSync`'s ride hold, 2026-09.y5f3), and `XeddsaVerify`, which checks Meshtastic signatures for the
LoRa plane (2026-09.ggq4).

## Entries

- **When touching `CryptoScheme`, `EncEnvelope.v`, `Protocol.CAP_RATCHET` / `CAP_CRYPTO_V3`, `RatchetCrypto.messageNonce`,
  `MessageContentV2`, `MessageCrypto`, or `InboundPipeline.verifyInbound` / `canCarry`:** READ ADR 005, ADR 016,
  ADR 059, FSR §5 and `docs/WIRE_COMPAT.md`. One Ed25519 signature authenticates every flooded frame, verified
  byte-exact over the received `signed` bytes before any decrypt; the one unsigned shape is v3's live-link tick
  (`relay = false`, DM-form chat to us from a pinned sender), checked before the plaintext branch and the exists-gate,
  delivered only if it opens as a `CTL_RECEIPT`, and never fed to the reset heuristic. The scheme is chosen per peer
  from the pinned profile's bits; v3 sends `nonce` and `keys` empty, never absent, because `canCarry` decodes the
  payload and every fielded build must still carry the frame. Resets, seeds and the group form stay v2. Inbound v1 is
  accepted forever. A changed byte is a vectors change (`vectors/README.md`, `KNIT_WRITE_VECTORS=1`).
- **When touching `decryptAndDeliver` / `decryptAndDeliverV2`, the pre-decrypt exists-gate, `peekOpen` /
  `commitOpen`, `SessionTransactor`, the `ratchetMutex` qualifier, or any `locked()` block in `RatchetSessions` /
  `GroupRatchetSessions`:** READ ADR 016, ADR 017 and `SessionTransactor`'s KDoc (commit 051284eb). Decrypt is
  two-phase — a lock-free peek decides (moderation, the seed park), then a re-open commits atomically with the message
  row — and the order is transaction outer, mutex inner, enforced inside both facades, never left to callers; one
  mutex serves both facades because seed adoption runs inside a DM commit. Deleting a used message key is safe only
  because a persisted frame never reaches decrypt again; a frame with no message row (a ctl, a tick) has no such
  gate. A decrypt or verify failure never throws out of the inbound handler: `onDeliver` runs before the relay is
  scheduled.
- **When touching `RatchetEngine.touch`, `Ladder.openFresh`, `resolveSession` / `resolveRace` / `isRemnantOf` /
  `remnantCandidates`, `peerInitEphPub`, `allowReplacement`, `REPLACEMENT_MIN_INTERVAL_MS` /
  `RESET_REPLACEMENT_MIN_INTERVAL_MS`, or `IntroSync.onPeerFrameOpened`:** READ ADR 2026-09.pz9g, ADR 023 and FSR §7.
  The race winner is the init whose initiator has the smaller node id, never the earlier stamp; a resolved init is
  remembered by its ephemeral, so a re-served copy is inert. A frame moves the era — DH-base adoption,
  `highestPeAcked`, an initiator's confirmation — only when it derives a fresh epoch under the active root (the ADR's
  `openNewEpoch` is today's `Ladder.openFresh`); a late race loser's root is a trailing read-only candidate that
  records nothing and is never anchored; `FLAG_RESET` is adopted or refused, never read without adoption. Whoever
  abandons a root era drops the receive state tied to it, at every site an era changes. An init is answered once
  per init, not once per peer per hour. Regression: `RatchetEngineTest`'s pz9g cases, `IntroSyncTest`,
  `SessionLabTest`, `CustodyLabTest.bothSidesSendWhileApartAndMerge`.
- **When touching `RatchetEngine.initiate` / `seal`'s new-epoch base, `RatchetEngine.prekeyBase`, `SessionState.peerBasePub`,
  or what `RatchetSessions.sealDm` / `sealResetDm` pass as the peer's prekey:** READ FSR §4 and §7, and ADR 2026-09.pz9g. Every
  `pe = 0` epoch is based on the prekey its init names: the receiver resolves the base by `init.pkid`, so `initiate`
  records that prekey as the session's base until the peer contributes an epoch (`touch` adopts it over the prekey),
  and an epoch based on the peer's newer prekey was unreadable for days (#174). A row initiated before that holds no
  base and seals a new epoch only while the pinned prekey is the init's; otherwise the boundary falls back to v1.
- **When touching what `RatchetSessions.sealDm` / `sealResetDm` return when they cannot seal, `RatchetCrypto.dh` /
  `acceptsPublicKey`, `MessageCrypto.seal`'s refusal, `MeshManager.recipientBundles` / `groupRatchetEligible`, or
  `InboundPipeline.verifiedPrekey`:** READ FSR §2.1. A signature proves a peer chose a key, not that X25519 takes it
  — a low-order point verifies, and Tink throws on every DH against it. The seal facades map Tink's
  `GeneralSecurityException` (only that) to "cannot seal" (`null`, `ResetSeal.NoPrekey`) with nothing persisted, so
  every caller takes its existing fallback (v1 chat, cleartext ctl and tick, v1 group message); a prekey
  `acceptsPublicKey` refuses is never pinned (#172). A member whose identity key it refuses is skipped by the v1 wrap
  like an unknown key (`recipientBundles`, with `MessageCrypto.seal` dropping a wrap that throws as the backstop) and
  makes its groups ineligible for the ratchet, so the rest of the group still gets the post; a DM to it parks
  `pendingKey`. The pin itself stands — its signing half is valid. A throw out of a seal crashed the chat screen and
  ended the neighbour watcher; a whole-message refusal stranded every group the member was in.
- **When touching `RESET_TRIGGERING_DROPS`, `InboundPipeline.onRatchetFailure` / `isLiveEvidence` /
  `maybeRequestReset`, `RatchetSessions.RESET_DISTINCT_FRAMES` / `RESET_MIN_INTERVAL_MS` / `STRANDED_TAIL_MS`,
  `headerSane`, or a ratchet `DropReason`:** READ ADR 023, ADR 024, ADR 026 and ADR 053. The heuristic counts three
  **distinct** frame ids of `NO_SESSION`, `EPOCH_GONE` or `AEAD_FAIL` from a pinned, signature-verified sender, each
  from the era it would abandon (exact when we responded; when we initiated, five minutes of skew, and past 48 h of
  session age the gate opens), and fires at most once per 6 h. `DUPLICATE` and `BAD_HEADER` never trigger:
  a consumed index proves the frame already opened, and a burst of duplicates is almost always a re-send cadence
  bug (053's tick). A reset repairs the channel, never the backlog.
- **When touching `RatchetSessions.retiredBase` / `isRetiredBase`, `RatchetStore.lowestLocalEpoch`, or the
  `EPOCH_GONE` half of `maybeRequestReset`:** READ ADR 2026-10.9wvr, then ADR 027. An `EPOCH_GONE` whose `pe` is
  below the lowest local epoch number we hold **and** below `highestPeAcked` is doomed and never reaches
  `noteUndecryptable`: the key went to retention behind an epoch the peer has sealed against since. A hole, a `pe`
  past our newest and `pe = 0` (the signed prekey, time-retained, #174) still count; the lowest *number* (a dead era's
  rows only lower it) and our own reset zeroing the ack keep the rule on the side of counting. Our own numbers only,
  no clock. Regression: `RatchetSessionsRetiredBaseTest`, `InboundPipelineTest`'s retired-epoch case,
  `SessionLabTest.framesSealedAgainstAnEpochTheCapRetiredNeverReset…` (responder and initiator). Accepted cost: a DM
  late behind live traffic on a cap-retired base is no longer recovered by a reset's re-seal; the cap retiring young
  epochs is the open follow-up (#180). Residue: past the 48 h escape a skewed, over-48 h custody tail failing any
  other way (`AEAD_FAIL`, `NO_SESSION`, `EPOCH_GONE` above our newest) can still re-root once per 6 h — ADR 026 is
  owed an amendment (#179).
- **When touching `RatchetDao.localEpochsNewestFirst` / `insertLocalEpoch`, the ratchet sweep, `PREV_ROOT_TTL_MS`,
  `MAX_EPOCH_*`, or a retention cap in `RatchetRepository` / `GroupRatchetRepository`:** READ ADR 027 and FSR §10 /
  GFS §10. Retention orders by mint time, never by epoch number (a reset restarts numbering at 1), and a re-minted
  number replaces the dead era's key. The convergence constants mirror custody's — 200 messages an epoch is the
  per-sender quota, 24 h the TTL, 48 h twice it — so they change together or not at all.
- **When touching `RatchetEngine.open`'s ladder (`Ladder`, `RootCandidate`), `RecvEpoch` / `SkippedKey`, the keys of
  `ratchet_recv_epochs` / `ratchet_skipped_keys`, or `RatchetCrypto.rootTag`:** READ ADR 2026-10.gcrd. A receive
  chain and a skipped key carry the tag of the root that derived it, and each root candidate reads only its own: a
  reset restarts the resetter's numbering, and an old-era frame drained under `prevRoot` after the replacement's
  purge used to plant a chain the new era's frames of that number were judged against (#135). When nothing opens,
  DUPLICATE outranks the rest but never stops the ladder early; a purging resolution still reads nothing stored.
  Regression: `RatchetEngineTest`'s #135 cases, `RatchetSessionsResetTest`, `RatchetSoakTest`'s reset,
  `SessionLabTest.oldEraDmsReadAfterTheReset…`.
- **When touching `RatchetSessions.sealResetDm`, `InboundPipeline.sendSessionReset` / `maybeRequestReset`,
  `MeshManager.seedSendFloorOpen`, or anything else that sends a DM session reset:** READ ADR 2026-09.qerd and
  `docs/FORWARD_SECRECY_RATCHET.md` §7. A reset the peer would refuse — a second root inside its one-minute
  floor — is never sealed: over our own unanswered init it *marks* that init (the reset ctl under it, v2,
  flagged) or declines, and on the heuristic's word over an init answered under a minute ago it declines.
  The group seed floor counts only sends under the pairwise root held now, never `force`. Regression:
  `RatchetSessionsResetTest`, and `RestoreLabTest` under `scripts/lab-chaos.sh`.
- **When touching what a committed reset re-seals — `MeshManager.resealRecentDmsTo`, its second pass in
  `reofferToNeighbors`, `RESEAL_WINDOW_MS` / `ResetReseal.SECOND_PASS_MS`, or `SeenSet.reopen`:** READ ADR 2026-10.dmgw
  and FSR §7. The re-seal keeps the original id, so it reaches only what it meets live past each router's 10 min
  dedup window (custody keeps the originals): it goes out once at the reset and once more 11 min later, for the ids
  still unacked, once per reset — the second pass never records an entry, and the drain is clock-driven, outside
  the link-set guards. Regression: `MeshManagerTest.aResetsResealGoesOutOnceMoreAfterTheDedupWindow`,
  `RestoreLabTest`'s relayed-restore scenarios.
- **When touching `GroupRatchetEngine` / `GroupRatchetSessions`, `MeshManager.sealGroupRatchet` / `groupKeyPayload` /
  `currentSeeds`, the seed outbox (`group_key_sends`, `CTL_GROUP_KEY_ACK`), `InboundPipeline.flushPendingGroupKeysFor`
  / `adoptGroupSeeds` / `maybeRequestGroupKey`, `CTL_GROUP_KEY_REQ`, `MAX_ADOPTIONS_PER_DAY`, or
  `replayCustodiedGroupFrames` / `replayCustodiedSeedDms`:** READ ADR 017 and GFS §3, §4 and §7. A seed rides a v2 ctl
  DM, never v1; the outbox is the source of truth and custody only accelerates. A group frame that lands before its
  seed is custodied by its receiver and no peer will re-serve it, so adoption replays our own custody (commit
  0528b0de, and 074449d5 for a seed lost to a restart). A key request never advances an epoch; the re-distribution
  floor is 15 min per (group, member, pairwise root); a session reset forces the flush past stale acks (8a102baf).
  Eligibility is all-or-nothing per message, and a blocked member still gets seeds (ADR 010).
- **When touching `GroupRatchetStore.retireSendChains` / `sendChains` / `purgeGroup`, `GroupRatchetEngine.SendChain.retired`
  / `isRetired`, `needsNewEpoch`, the group send-chain sweep, or the rekey in `GroupRepository.recordDeparture` /
  `recordRejoin` / `leave` / `delete`:** READ ADR 2026-10.xgwj and GFS §4 rules 4 and 5, §6.1. A forced rekey, a local
  leave and a delete all retire the chain: every seed and chain key goes in the same transaction, the newest epoch's
  number stays on a key-less row, and the next mint numbers past it — the same people re-created are the same group
  id. A receiver tells two mints of one number apart by `mintedAt` alone and drops the older as stale (#167), so
  numbering only rises, except after a wipe (or a restore), which takes the seed outbox too: the outbox counts acks by
  epoch. A retired newest row is never swept — a drop after a bound was built and withdrawn, because a late or stale
  ack of the dropped number hides the restarted epochs — and drains as the previous row once a mint follows; a left
  group keeps that one key-less row. A retired row is never re-served: filter on the empty seed, never on the count
  (a rule-2 chain keeps its seed). Regression: `GroupRatchetRepositoryTest`, `GroupMembershipLabTest.aLeaveRekeysSeed…`
  / `aRecreatedGroupsSeed…`.
- **When touching a group's roster — `reconcileGroup`/`vetRoster`, `groupleave`, `GroupRepository.recordDeparture`
  / `recordRejoin`, `PendingGroupKeys`, `GroupKeyPayload.group` / `pinRosterFromSeed`, or how a member learns
  of a new group:** READ `docs/GROUP_FORWARD_SECRECY.md` §1 (the pinned founding roster) and §6.1
  (leave-rekey), then ADR 2026-09.v6fu and ADR 2026-09.mjaj. A group's id is the hash of its member set, so
  "the same people" is always the same group; membership shrinks only by your own signed leave and grows only
  by your own signed rejoin — nobody can add or remove anyone else. The seed carries the founding roster, so a
  member with no row pins the group from the seed through `reconcileGroup`'s one door (the relay-only case,
  #47); a seed without one (an older build's) is parked, never consumed.
- **When touching `CTL_RECEIPT` / `CTL_REACTION` / `CTL_PROFILE`, `AckSync.sealTick`, `MeshManager.sealDeliveryTick` /
  `sealDeliveryTickEnvelope`, `InboundPipeline.sealDmReceipt`, or the sealed profile's fan-out:** READ ADR 018, ADR
  020, ADR 033 and ADR 053, then `docs/ENCRYPTED_RECEIPTS_REACTIONS.md`. A ctl never takes the v1 wrap (a pre-ratchet
  build would persist an empty bubble): its fallback is the legacy cleartext frame. A tick is sealed once, at `owe()`,
  and re-sent verbatim on a backoff — re-sealing per retry burns chain keys and starves the receiver's skipped-key
  budget. The sealed profile carries presentation fields only, ordered by the profile version, never `pubKey` or
  `prekey`, and never inserts a peer row.
- **When touching `MessageContent.styles` / `MessageContentV2` label 17, `MessageEntity.sealedContent`'s styles,
  `InboundPipeline.plaintextContent`'s styles or the normalise in `deliverChat`:** READ ADR 2026-10.p95v and
  `docs/WIRE_COMPAT.md`'s formatting precedent. A sealed message's styles come from its plaintext alone, never the
  cleartext shell; `sealedContent` is their only builder, so a re-seal carries them and a ctl never does. The value is a
  CBOR byte string forever: a wrong-typed `"s"` or label 17 fails the plaintext decode (`DECRYPT_FAILED`, nothing
  committed), malformed bytes inside it never throw. An unformatted message seals to the bytes it did before the field.
- **When touching `CTL_SCOPE_CONFIG`, `MessageContent.sc` / `ScopeConfigPayload`, `InboundPipeline.applyScopeConfig`
  / `commitGroupCtl`, `ScopeConfigPolicy`, `ScopeConfigRepository`, `MeshManager.sendScopeConfig`, `RatchetSessions.sealDm`'s
  `confirmedOnly`, or the scope-config row `GroupRepository.recordDeparture` drops:** READ ADR 2026-10.m4fk (draft),
  ADR 2026-10.8kfx and `docs/SPOOL_PROTOCOL.md` §5. A config is an ordinary custodied sealed ctl, DM or group form,
  applied inside the commit that opened it; the store keeps one row per (conversation, issuer) and reads the winner
  across them, so a group member's leave drops their row alone and two arrival orders of a config and a leave end on
  the same winner. The issuer is the authenticated sender; a group config needs `sc.groupId` equal to the frame's
  group and a sender in the roster who sealed it after their last leave. The version is a clock (minutes since
  2026-01-01) and is never a reason to refuse — a refusal is final for that frame, and both the held rows and the
  clock differ between phones: every version is held, and read once this phone's clock reaches it (`eligible`). A
  refusal is the chain-advancing no-op,
  counted as `SCOPE_CONFIG_REFUSED`, never reset evidence. The sender seals only under a confirmed session — never an
  X3DH, never our own unanswered reset (#90) — and never v1 or cleartext; the compact v3 layout refuses `sc`.
  Regression: `ScopeConfigPolicyTest`, `InboundPipelineTest`'s scope-config cases, `ScopeConfigLabTest`.
- **When touching `RatchetSessions.exportedRoots` / `rootChanges`, `RatchetCrypto`'s export functions, or what the
  spool's `ScopeRegistry` reads from the ratchet:** READ ADR 019 (FSR §8, GFS §8), ADR 032 and ADR 2026-09.dcah. Only
  `pairwiseRoot` exports leave the facade, under the ratchet mutex, confirmed sessions only — a raw root never does —
  with the one exception below. A root change is an event the scope table derives on; don't fire it on an unchanged
  root.
- **When touching `RatchetSessions.pendingResetInit` / `exports`, `ExportedRoots.pendingResetInit` / `confirmed`,
  `ScopeRoots.pendingResetInit`, `Scope.resetInit`, `ScopeFrames.mayRefill` / `ownResetInit`, the `prevRoot` slot
  `RatchetSessions.reRoot` fills over our own unanswered reset, or what `ScopeSync` pushes into a DM scope:** READ ADR 2026-10.j67x, then `docs/SPOOL_PROTOCOL.md` C-3.1-4/5 and FSR §8. Our own
  reset, unanswered, over a root it replaced (`weAreInitiator`, unconfirmed, `prevRoot` present, `lastResetSentAt ==
  establishedAt`) exports both roots while the replaced one drains, and the retiring scope takes exactly one refill:
  that reset, matched on `FLAG_RESET` and its init's ephemeral — never the session's other init-bearing frames (#90).
  A flagged reset of ours that is no longer the pending one goes into no DM scope at all (a confirmed winner would
  adopt it, a pair on two roots), and a second reset over our own keeps the drained root in the slot. While we wait,
  a peer init that derives our own `prevRoot` (`RatchetEngine.resolveSession`'s replaced-era check) on a frame that
  predates our session (`RatchetSessions.predates`, ADR 026's initiator rule) is read under it, read-only, never
  raced: the drained scope re-serves that era's init-bearing frames for 48 h. One sealed since still races. While
  the reset is unanswered the thread reads not covered (`ScopeStatus.pendingReset`, `coveredLabelsOf`). The winner
  adopting a race loser's stale flagged reset is #184.
  Every other unconfirmed session exports nothing; a caller that wants confirmed peers reads `ExportedRoots.confirmed`
  (`broadcastSealedProfile`). Regression: `RatchetSessionsExportTest`, `ScopeSyncTest`'s carrier push,
  `ResetOverRelayLabTest.aResetToAPeerOnlyTheRelayReaches…`.
- **When touching the self branch of `InboundPipeline.handleProfile` / `handleChat`, `PeerRepository.forgetSelf`,
  `RatchetSessions.forget`, or `GroupRepository.forgetSelf`:** a node never pins its own key and never holds a session
  with itself — a re-served copy of our own profile once pinned `peers[me]` and sealed every group's seed to self in a
  loop floored at 15 min (45763fb6), and the orphaned self session and seed rows outlived the fix (8333c858).
  `MeshLab.assertConverged` asserts no node holds either.
  <!-- doc-lint: allow openNewEpoch — the ADR's name for today's Ladder.openFresh -->

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: in this plane the KDoc of
`RatchetEngine`, `RatchetSessions`, `GroupRatchetSessions`, `SessionTransactor` and `PendingGroupKeys` carries the
incident that shaped it.
