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
  byte-exact over the received `signed` bytes before any decrypt; the one unsigned `WireEnvelope` shape is v3's live-link tick
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
  gate. The gate still acks what it stops; for a DM to us that ack is bounded per era (the re-ack entry below, ADR
  2026-10.8hn6). A decrypt or verify failure never throws out of the inbound handler: `onDeliver` runs before the relay is
  scheduled. The guards are `runCatchingUnlessCancelled` (`mesh/MeshCoroutines.kt`), not `runCatching`: a caller's own
  cancellation is rethrown, never counted as `DECRYPT_FAILED` and never fed to `onRatchetFailure`, and the router runs
  an admitted delivery under `NonCancellable` so it never sees one (ADR 2026-10.f82h). `commitOpen` sets its in-memory
  stamps (`lastReplacementAt`, `confirmedAt`, `undecryptable`) only after `onOpened()` returns: a stamp ahead of a row
  write that rolls back starts a replacement floor for a frame that still opens as new.
- **When touching `RatchetEngine.touch`, `Ladder.openFresh`, `resolveSession` / `resolveRace` / `isRemnantOf` /
  `remnantCandidates`, `peerInitEphPub`, `allowReplacement`, `REPLACEMENT_MIN_INTERVAL_MS` /
  `RESET_REPLACEMENT_MIN_INTERVAL_MS` / `RatchetSessions.resetFloorMs`, or `IntroSync.onPeerFrameOpened`:** READ ADR
  2026-09.pz9g, ADR 023, ADR 2026-11.sfms (draft) and FSR §7.
  The race winner is the init whose initiator has the smaller node id, never the earlier stamp; a resolved init is
  remembered by its ephemeral, so a re-served copy is inert. A frame moves the era — DH-base adoption,
  `highestPeAcked`, an initiator's confirmation — only when it derives a fresh epoch under the active root (the ADR's
  `openNewEpoch` is today's `Ladder.openFresh`); a late race loser's root is a trailing read-only candidate that
  records nothing and is never anchored; `FLAG_RESET` is adopted or refused, never read without adoption. Whoever
  abandons a root era drops the receive state tied to it, at every site an era changes. An init is answered once
  per init, not once per peer per hour. A flagged init the floor alone refuses (`allowReplacement` false) is
  `RESET_FLOORED` only when nothing opens and its root is none we hold (sfms); the floor itself is unchanged, one
  number for the floor we apply and the one we model before we send (`resetFloorMs`, the lab's `LabLimits` knob).
  Regression: `RatchetEngineTest`'s pz9g cases, `IntroSyncTest`,
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
  `headerSane`, or a ratchet `DropReason`:** READ ADR 023, ADR 024, ADR 026, ADR 053 and ADR 2026-11.sfms (draft). The heuristic counts three
  **distinct** frame ids of `NO_SESSION`, `EPOCH_GONE` or `AEAD_FAIL` from a pinned, signature-verified sender, each
  from the era it would abandon (exact when we responded; when we initiated, five minutes of skew, and past 48 h of
  session age the gate opens), and fires at most once per 6 h. `DUPLICATE` and `BAD_HEADER` never trigger:
  a consumed index proves the frame already opened, and a burst of duplicates is almost always a re-send cadence
  bug (053's tick). Nor does `RATCHET_RESET_FLOORED` (the peer asked to re-root inside our floor), and while its
  frame is held a failing frame carrying the held init's ephemeral is the resetter's follow-up, gated ahead of
  `noteUndecryptable` beside `isLiveEvidence` and `retiredBase` (sfms). A reset repairs the channel, never the
  backlog.
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
  DUPLICATE outranks the rest but never stops the ladder early, save `RESET_FLOORED`, which outranks it (ADR
  2026-11.sfms: a flagged init is never read, so its frame's index proves nothing); a purging resolution still reads
  nothing stored.
  Regression: `RatchetEngineTest`'s #135 cases, `RatchetSessionsResetTest`, `RatchetSoakTest`'s reset,
  `SessionLabTest.oldEraDmsReadAfterTheReset…`.
- **When touching `RatchetSessions.sealResetDm`, `InboundPipeline.sendSessionReset` / `maybeRequestReset`,
  `MeshManager.seedSendFloorOpen`, `RatchetSessions.replacedAt`, or anything else that sends a DM session reset:** READ
  ADR 2026-09.qerd (with its 2026-11-01 amendment, draft) and `docs/FORWARD_SECRECY_RATCHET.md` §7. A reset the peer
  would refuse — a second root inside its one-minute floor — is never sealed: over our own unanswered init it *marks*
  that init (the reset ctl under it, v2, flagged) or declines, on the heuristic's word over an init answered under a
  minute ago it declines, and inside a minute of an answer to an init the peer took as a *replacement* (`replacedAt`:
  a reset or a marker, a rule-C reclaim, a race we won) it declines every cause but `AFTER_WIPE`, last, so the
  vectors' wording for the heuristic's decline holds. A first contact the peer established stamps nothing.
  The group seed floor counts only sends under the pairwise root held now, never `force`. Regression:
  `RatchetSessionsResetTest`, and `RestoreLabTest` under `scripts/lab-chaos.sh`.
- **When touching `FlooredResets`, `InboundPipeline.holdFlooredReset` / `dueFlooredResets`,
  `MeshManager.reopenFlooredResets`, `RatchetSessions.rootGeneration` / `resetFloorOpen`, `OpenOutcome.Failed.RESET_FLOORED`
  or `DropReason.RATCHET_RESET_FLOORED`:** READ ADR 2026-11.sfms (draft) and FSR §7. A peer reset our floor refused for
  an era we never held is held whole (signed frames only), one per peer, the newest `sentAt` winning, and dropped on
  any root change since the open (`rootGeneration`, read before the peek); the 60 s tick re-delivers it through
  `onDeliver` (`relay = false`) once `resetFloorOpen` says so on the open path's wall clock, never `clock()`, and after
  an adoption anchored on its ephemeral replays the resetter's rowless custodied DMs whose header carries that init,
  oldest first. In memory: a restart loses it with the floor. Regression: `RatchetEngineTest`'s #251 cases,
  `InboundPipelineTest`'s floored-reset cases, `ResetOverRelayLabTest.aResetBobsFloorRefusedIsReOpenedWhenTheFloorEndsAndItsDmLands`.
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
- **When touching the exists-gate's DM re-ack — `InboundPipeline.acknowledge`'s `reDelivery` / `answeredInEra` /
  `claimUnansweredDmAcks`, `InboundPipeline.sealDmReceipt`'s record, `MeshManager.flushDmAcks` / `flushHeldTicks` /
  `originateDeliveryTick`'s record, `DmReAckLedger` and its `claim`, `RatchetSessions.sealDmTagged` / `rootTagFor`:** READ ADR
  2026-10.8hn6 (draft), then ADR 018, ADR 039 §3 and §7, and ADR 2026-10.dmgw's 2026-10-29 amendment. A re-delivered DM
  to us draws a new sealed receipt only off LoRa (whose re-offer heals by re-acking), and only when the receipt we
  sealed for its id is under another session root than the one held now, has left our own custody (`ForwardStore.frame`,
  never `has`), or is older than its re-arm floor (15 min doubling to 4 h per same-era re-seal: the author custodies
  before it opens, so held is not applied). The era comes from the seal itself, under the ratchet lock, never a read
  after it; the memo records the DM's own id only, never the riding room ids. The bound holds while our receipts fit
  our own per-sender custody bucket. Both doors a re-delivery takes pass `reDelivery` (the exists-gate, and
  `loraDirectCopyTaken` for a marked signed copy), and a re-seal is an answer (`sealAnswer`, x9fa's 2026-10-29
  amendment, 8hn6's amendment): declined and held under our own unanswered reset (any reset stamp); only the
  exists-gate's unmarked re-delivery rides our own first-contact init (`rideOwnFirstContact`); a decline records nothing.
  Every signed DM ✓✓ records (#300, 8hn6's 2026-11-06 amendment): `sealDmReceipt`, and `originateDeliveryTick` for the
  coalescer's tick and a held flush, the ids sealed and never a rider or a compact tick's id. The coalescer's flush
  and a held flush ask the same rule by id, with the author's ratchet bit inside it, so a LoRa re-delivery keeps its
  compact re-ack and is bounded for the signed one. Every copy off LoRa asks, a first delivery too, on the plane and
  never on `reDelivery`. The author chooses the id, so the ledger and its claims are keyed by author and id, and the
  custody read takes only our own frame. Each seal claims the DM first (`DmReAckLedger.claim`, never a `Mutex`), reads
  the ledger second, and releases in a `finally` after it recorded or held: a record lands after the seal, and one root
  change starts two paths. A loser holds the id (`holdDeclinedTicks`, which retries past a detached set) and the claim's
  end flushes the hold (`contest`, `flushContestedTicks`), which asks the ledger, so a claim never ends a ✓✓. Android-only: a port without the answer gate keeps 8hn6's
  rule as written.
  Regression: `DmReAckLedgerTest`, `InboundPipelineTest`'s re-ack cases, `DmTickOnceLabTest`,
  `MaliciousForkLabTest.aCompactDmUnderAnotherAuthorsIdLeavesThatAuthorsReceiptAlone`,
  `CustodyLabTest.aDeliveredDmBackFilledToItsRecipientPastItsSeenWindowDrawsNoSecondReceipt`.
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
- **When touching `MessageContent.lang` / `reads` / `MessageContentV2` labels 18 / 19, `MessageEntity.sealedContent`'s
  `DeclaredLangs.of` mapping, or `InboundPipeline.plaintextContent`'s `lang` / `reads`:** READ ADR 2026-10.t2h6's
  2026-10-29 amendment and `docs/WIRE_COMPAT.md`'s declared-languages precedent. A sealed message's declaration comes
  from its plaintext alone, never the cleartext shell; `sealedContent` is its only builder and re-normalises off the row,
  so an English message by an English reader seals to its old bytes in both layouts and a ctl never carries one. Each is
  a `LanguageRegistry` index and a CBOR uint forever: a text or out-of-range value fails the plaintext decode
  (`DECRYPT_FAILED`, nothing committed), an unknown index reads as nothing. The registry is append-only, pinned by
  `vectors/wire-v1.json`'s `languageRegistry`. A label for translation and the composer's hint, never a moderation,
  trust, routing or crypto input.
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
- **When touching `RatchetEngine.SessionState.prevRootOwnInitAt`, `RatchetEngine.reclaim` / `reclaimsPrevRoot`,
  `OpenContext.sealedSinceLostRace`, `RatchetSessions.sealedSinceLostRace`, `OpenDelta.keepRecvTag` / `reclaimed`, the
  `ratchet_sessions.prevRootOwnInitAt` column, any write of `SessionState.prevRoot`, or `MeshMetrics.onRatchetReclaimed`
  (`ratchetSwappedBack`):** READ ADR 2026-10.4mjm (draft), then ADR 2026-09.pz9g's and ADR 2026-10.j67x's 2026-10-29
  amendments, ADR 026 and FSR §7. Rule C: a race loser's `prevRoot` that is its own unconfirmed init carries that init's
  own `establishedAt` (our clock); only the race-loser branch sets it, every other `prevRoot` write clears it (the
  replacement, the race win, `retirePrevRoot`, the swap, `RatchetSessions.reRoot`). A frame opening under it, on any
  rung, sealed by the peer as responder with `sentAt` more than the skew bound past the winning init's `at` (both the
  peer's clock; judged in `contextFor` so peek and commit agree), moves the loser back: confirmed initiator, its own
  `establishedAt` restored, the adopted root draining, the anchor kept, `lastResetSentAt` untouched, every receive row
  but the reclaimed root's purged. No `sentAt`, no swap (a walkie chunk). The freshness half is load-bearing: without it
  a pair whose winner restored splits. Residues: the cross-clock replacement door and a clock stepped back across a
  restore. Regression: `RatchetEngineTest`'s rule C cases, `RatchetSessionsReclaimTest`, `KnitDatabaseMigrationTest`
  27 → 28, `RestoreLabTest`'s two #189 scenarios, `LoraDirectLabTest.aLoraFirstContactLeavesNoInitInAnyTickAndSurvivesARestore`.
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
  adopting a race loser's stale flagged reset is #184. An init older than `prevRoot` still races and wins; rule C (ADR
  2026-10.4mjm) moves us back onto our reset once the peer answers it.
  Every other unconfirmed session exports nothing; a caller that wants confirmed peers reads `ExportedRoots.confirmed`
  (`broadcastSealedProfile`). Regression: `RatchetSessionsExportTest`, `ScopeSyncTest`'s carrier push,
  `ResetOverRelayLabTest.aResetToAPeerOnlyTheRelayReaches…`.
- **When touching `mesh/crypto/LinkKeys` / `LinkSignals`, `MeshManager.sendLinkSignal` / `linkSignalFrame`, or
  `InboundPipeline.handleLink` / `mayOpenLink`:** READ ADR 2026-10.a5yj and its 2026-10-27 amendment (draft), then
  `docs/DIRECT_LINKS.md` §2 and §5 (`.agents/context/direct-links.md` routes the rest of the link). A `link` signal is a
  raw `LinkSignal` sealed on the DM ratchet with `confirmedOnly`, after `ScopeSync.canCast` says a spool would take it —
  never by opening a session, never over our own unanswered reset, and no fallback. It is taken only from a spool's
  signed cast, from an accepted, unblocked sender over a session confirmed here, in a build that wires links; a failed
  open is never reset evidence. Lane keys come from both sides' fresh `k`, salted by the link id and bound to both ids
  in role order — never the pairwise root. Regression: `LinkKeysTest`, `LinkSignalsTest`, `InboundPipelineTest`'s link
  cases, `MeshManagerTest.aLinkSignalIsSealedOnly…`, `KeyedVectorTest`'s `linkKeys`.
- **When touching `mesh/crypto/LinkProof` / `LinkProver`, `LinkHandshake.dial` / `answer`, `mesh/link/LinkClaims`,
  `Protocol.CAP_LINK_PROOF` / `Protocol.hello`, the capabilities line of `InboundPipeline.storeProfileRow`, or
  `MeshManager.republishProfileIfCapabilitiesChanged`:** READ ADR 2026-11.264w (paired with knit-ios #25; the contract
  is byte-exact law). A link proof signs `"knit/link/v1/proof" ‖ role ‖ u32be(len) ‖ helloD ‖ u32be(len) ‖ helloR` over
  the HELLO bytes as received, dialer first whoever signs; verify rebuilds the bundle from the raw keys and requires its
  `NodeId` to be the HELLO's. A PROOF is never written toward a HELLO without the bit, and a bit-carrying HELLO without a
  valid nonce is a failed proof. Proof-capable means the stored profile carries the bit or a link proved the id this run;
  a peer's stored capabilities follow the presentation version, never the prekey watermark, and a build republishes its
  profile as an edit when its capabilities change, so an older frame never takes the bit back. Regression:
  `LinkProofTest`, `LinkHandshakeTest`, `LinkClaimsTest`, `KeyedVectorTest`'s link proof and `proofRejects`,
  `InboundPipelineTest`'s profile capability cases, `MeshManagerTest`'s capability republish.
- **When touching the self branch of `InboundPipeline.handleProfile` / `handleChat`, `PeerRepository.forgetSelf`,
  `RatchetSessions.forget`, or `GroupRepository.forgetSelf`:** a node never pins its own key and never holds a session
  with itself — a re-served copy of our own profile once pinned `peers[me]` and sealed every group's seed to self in a
  loop floored at 15 min (45763fb6), and the orphaned self session and seed rows outlived the fix (8333c858).
  `MeshLab.assertConverged` asserts no node holds either.
  <!-- doc-lint: allow openNewEpoch — the ADR's name for today's Ladder.openFresh -->

- **When touching `mesh/crypto/LoraDirectSeal`, the `knit/lora/v1/…` labels, `LoraDirectCrypto` / `LoraDirectInbox` /
  `LoraDirectSource`, `InboundPipeline.deliverLoraDirect` / `loraDirectCopyTaken` / `ratchetOnly`, the `lora_direct_seen`
  table (`LoraDirectSeenRepository`), `Protocol.CAP_LORA_DIRECT`, `MeshManager.offerLoraDirect` / `loraDirectSource` /
  `loraBoardMap` / `watchLoraPairs` / `bindLoraDirect`, `LORA_DIRECT_TICK_ACKS`, or `vectors/lora-direct-v1.json`:** READ
  ADR 2026-10.x9fa (its crypto half and its 2026-10-30 PKI amendment: the plaintext cap is 168 B, nine ids a tick) and
  `docs/SPOOL_PROTOCOL.md` C-3.5-5; for the board map's `loraKey` / `contact` / `contested`, ADR 2026-10.aajj (our board
  is taught a contact's key only when the profile binds it: CRC32 of the key is the node, nobody else claims it). The compact LoRa door is no `WireEnvelope`:
  a pair-sealed packet keyed by the static identity agreement under directional `knit/lora/v1/…` labels, the sender
  named by the pairTag index over pinned peers (never ourselves) and the AEAD, a `sentAt` outside 24 h back / 5 min
  forward refused at open, and no failure on it ever reset evidence. It takes a message, a `CTL_RECEIPT` or a
  `CTL_REACTION` and nothing else. A message it delivers is marked in `lora_direct_seen`, checked in `decryptAndDeliver`
  **before** the exists-gate: the signed copy then opens through the ratchet alone (peek + commit, writing only
  `ratchetDone` and shed inline acks), so no skipped key is left and an init carried only by LoRa is still processed; a
  failed ratchet-only open is `LORA_DIRECT_RATCHET_MISSED`, never `onRatchetFailure`. The signed copy's gate
  (`alreadyDelivered`, no lock) reads the row first, then the mark, then takes the exists-gate on the row it read: mark
  first, a compact commit between the reads acked a first contact's signed copy unopened (#261, x9fa's 2026-11-01
  amendment); never decide on the row alone, a mark with no row is a deleted message. A marked id is refused on the
  compact door whether or not its row exists (deleted stays deleted). Offers ride beside the signed copy only, toward a
  pinned profile with the bit, inside 23 h; the bit is ours only while the plane and its DM switch are on. Regression:
  `LoraDirectSealTest`, `LoraDirectVectorTest`, `InboundPipelineTest`'s compact-door cases, `MeshManagerTest`'s compact
  cases, `LoraDirectLabTest`.
- **When touching `RatchetSessions.sealAnswer` / `sealCtl` / `AnswerSeal`, what a ✓✓ or a DM reaction is sealed with, or
  `MeshManager.HeldReactions` / `flushHeldReactions`:** READ ADR 2026-10.x9fa's 2026-10-29 amendment. A ctl that answers
  a compact-delivered DM is sealed only under a session the peer holds (ours as responder, or ours once answered),
  decided under the session lock; otherwise it is declined, nothing persisted, and the caller sends the compact copy
  alone — never a cleartext or v1 fallback (ADR 018), never a fresh init. Every re-delivery's re-ack is an answer too,
  bounded per era by ADR 2026-10.8hn6's ledger. A ✓✓ is re-drawn by the signed copy's re-ack;
  a reaction is held until the root moves; a ✓✓ sealed by any path drops its ids from the hold, and the coalescer's
  flush seals only what 8hn6's rule finds unanswered. With no session it
  declines even on an undecodable prekey. The race winner's intro answer still carries its init, and so may a
  re-delivery's re-ack riding it (#189's two doors); both are closed at the restored loser by rule C (ADR 2026-10.4mjm,
  below), not here. Regression: `RatchetSessionsSealTest`'s answer cases, `MeshManagerTest`'s
  no-session tick and reaction cases, `RestoreLabTest` under `KNIT_LAB_BOARDS=1`.

- **When touching `MessageContent.CTL_PRESENCE_KEY` / `pk`, `PresenceKeyPayload`, `PresenceKeyFrame`, `PresenceKeyPolicy`,
  `PresenceKeyEngine` / `PresenceKeyEngineStore` (its `ownKey`, `departed`, `beginSession`), `OwnPresenceKey`,
  `PresenceKeyRepository` (its `markStore`, `presenceKeysFor`, `applyHeld`), `PresenceKeyDao.setCurrentMark`,
  `MeshTransport.onOwnPresenceKey` / `onOwnPresenceKeySource` / `onTagNextRecipients` / `onPresenceKeyPassRequester`,
  `MeshManager.hearsTagNext`, `PresenceKeyPolicy.departed` /
  `hearsTagNext`, `InboundPipeline.applyPresenceKey`, `RatchetSessions.OpenedUnder` / `OpenDelta.openedRootTag`,
  `MeshManager.sealPresenceKeys` / `sealLinkReset` / `watchPresenceKeys`, the link-owed resets (`SettingsStore.linkOwedResets`,
  `finishRestore`'s split, `sendSessionReset`'s `deliver`), or `DropReason.PRESENCE_KEY_REFUSED`:** READ ADR 2026-11.xp6d
  ("The presence key", "What it costs"), then ADR 2026-09.pz9g and ADR 2026-09.qerd. Keys go only to direct contacts
  (`contacts/DirectContacts`, read fresh), only sealed under a confirmed session (`confirmedOnly`, never an X3DH) and only
  point to point over a live link to a proof-capable id: signed `relay = false`, never flooded, custodied, cast or
  relayed, padded to one plaintext size. Delivery is a reconcile of owed against sent (generations and the seal's own root
  tag, per peer, cleared when the peer's last link drops), never a queue. The engine's one lock is taken outside any
  transaction and never by the inbound arm, which runs inside the ratchet commit (Room only) and files keys by the era the
  frame opened under: only an active-root open starts a new era (`OpenedUnder.active`, the pz9g predicate), a drain orders
  against what is held and never moves a tag, an equal key is the uncounted no-op, and a refusal is never reset evidence.
  Rotation is a pure function of the rows and a fresh direct-contact read: any shrink mints fresh, never rate-limited; a
  clock-or-mark advance at most once a day of elapsed time; a mint records its generation before its row. A restore clamps a
  last generation more than a year ahead and sends the direct contacts `wipedPeers` misses their `AFTER_WIPE` reset over
  the first link, sealed once and re-sent verbatim until the session confirms — never the flood. Keys of people who stop
  being direct contacts are deleted. A direct contact's keys are listed under its id whatever another peer holds: the tag
  binds that id (xp6d's owner-bound amendment). Minting and sending this phone's own keys ride `BuildConfig.PRESENCE_KEYS`
  (`MeshManager.sendsPresenceKeys` → `PresenceKeyEngine`'s `sendsOwnKeys`; on in every build as shipped, independent of
  writing tags, `BLIND_ADVERT`), while receiving, storing, resolving, leavers' deletion and link-owed resets run in every
  build whatever it says.
  The advertiser's stale mark asks for a pass through `onPresenceKeyPassRequester` (once per generation, the transport's
  latch): `MeshManager` launches one `reconcile("mark")` on the session, which reads the same persisted mark; the
  15-minute heal reconcile is the backstop.
  The advertiser's own key is `PresenceKeyEngine.ownKey`, published at the end of a settle under the lock, null from
  `beginSession` (every `MeshManager.start`, before the transport) until the session's first settle, compared by
  generation and key bytes so a mark, a grow or a peer key never restarts the advertiser; never a Room read (a block
  made while stopped would air under the leaver's key); null when a settle cannot reach nothing-due; read synchronously
  through `onOwnPresenceKeySource` where a tag is about to air. `TAG_NEXT` recipients are `MeshManager.hearsTagNext`: not
  blocked (node ids, and the ids a `peers` query keyed on the blocked device tags names), not `departed` (published
  before the key), in memory, no until both are read. No mark refusal names a generation. The contacts trigger
  (`watchPresenceKeys`) combines `DirectContacts.sets` (recomputed on every input emission, never deduplicated) with
  `PresenceKeyEngine.lastRead` (a flow: the set the last completed pass of any trigger acted on, set only after it
  settled and published, null after a pass that threw) and kicks while they differ, so a pass that ended behind the
  stores or failed kicks again; the kick is conflated and a loop of its own runs it. Never `DirectContacts.observe` as a trigger: its dedup
  compares with its own last emission, so a contact added and removed while one input's query lagged (a root pass
  meanwhile put her in the key's audience) left the fresh pair waiting for a heal (#285). `observe` is for the
  level-triggered key listing alone. The mark write (`markStore`) is never inside a
  transaction, commits only after an in-transaction `PRAGMA synchronous` reads FULL, and only while its generation is
  the newest activated (xp6d's step 5b key-side amendment). Never a key, a tag or a root tag in a log line beside a peer
  id. Regression: `PresenceKeyPolicyTest`, `PresenceKeyEngineTest`, `PresenceKeyRepositoryTest`, `InboundPipelineTest`'s
  presence-key cases, `GoldenVectorTest`'s `messageContentPresenceKey*`, `CompositeMeshTransportTest`'s own-key case,
  `PresenceKeyLabTest`, `PresenceKeyFeedLabTest` (the key handed to the radio and the `TAG_NEXT` predicate),
  `DirectContactsObserveFoldTest` and `MeshManagerTest`'s `aRemovalTheContactsFlowNeverSawStillRunsAPassThatRotates` (the
  contacts trigger).

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: in this plane the KDoc of
`RatchetEngine`, `RatchetSessions`, `GroupRatchetSessions`, `SessionTransactor` and `PendingGroupKeys` carries the
incident that shaped it.
