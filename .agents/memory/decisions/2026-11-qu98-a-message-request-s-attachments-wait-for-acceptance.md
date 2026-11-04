---
id: "2026-11.qu98"
slug: a-message-request-s-attachments-wait-for-acceptance
title: "a message request's attachments wait for acceptance"
date: 2026-11-03
topics: [blobs, messages, security]
---

# ADR 2026-11.qu98 — a message request's attachments wait for acceptance

Status: Accepted (2026-11-03)

**Context:** ADR 009 made "message request vs accepted" one shared predicate (`Conversations.isAccepted`) and used
it only for presentation: a stranger's first DM or group is stored and acknowledged silently. Every attachment want
ignored it, so any stranger could make us fetch and store files before anyone had looked at the request (#282, H7 of
the malicious-fork review). The bound was 100 pending threads of 50 messages, each file up to 8 MiB + 64 KiB, kept
7 days. Reading `main` `4d6b5e4f1` on 2026-11-03 found six doors:

- the delivery (`deliverChat` → `pullOrDescribe`);
- the database re-arm's row half (`MeshManager.rewantMissingBlobsLocked` over `attachmentsNeedingFetch`);
- custody of our own inbound frames (ADR 018). `onCarriedFrame` and the re-arm's carried half pulled them, and
  `carrierOnlyBlobBytes` stops counting a blob once a row names it, so the carrier budget never bounded them;
- the echo: after `CarrierPullPolicy.DEFER_MS`, an honest carrier's pull-through ask (ADR 2026-10.2drc) made us fetch
  from the stranger;
- the spool's `ScopeSync.healAttachments` (#290);
- the group photo a stranger's group frame decides, wanted with no ceiling.

The request inbox renders no attachment and offers no "Tap to download", so the fetch bought the user nothing.

**Decision:** an attachment is held while every row naming it is a held row: not ours, not tapped, and in a
conversation `Conversations.isAccepted` does not accept. The pure `RequestHold` decides it, and every want site and
the echo ask it:

- the fold is "one row that would fetch it fetches it for all", as in `BluetoothFetchGate` (ADR 2026-10.js2p), so a
  contact's copy of the same hash is still fetched. The author hint asked first and the pull-through deadline come
  from the releasing rows only, so a held stranger row is never asked first and cannot stretch a contact's deadline;
- the hold covers only our own pull of a request's bytes for ourselves. A custodied DM to us, or a frame of a group
  whose roster is only us and its sender, is held by the same rule before it is opened. We are a member when the
  roster's members or departed name us or we hold the group, never by the stranger's roster alone. Its own sender
  counts as a group sender, mirroring what `deliverChat` reads after the persist, so a contact's new group is pulled
  at once;
- anything else goes by the carrier rules: a frame we carry for someone else, and a frame of a request group whose
  roster names anyone besides us and the sender, which an accepted member behind us may be waiting on. The latter is
  pulled as a carrier even under a Bluetooth limit, since no row of ours will fetch it. A blob that only held rows
  name counts toward the carrier budget and is evicted as carrier bytes, so a request row cannot shelter bytes a
  carried frame pulled. Kotlin picks the request conversations with `AcceptanceSnapshot`, and
  `BlobRepository.requestOnlyBlobs` lists their blobs in chunks of bound ids, so the accepted set never enters SQL and
  no number of requests reaches SQLite's variable cap; the storage sweep drops those no carried frame pins, and the
  rows still name the hash, so the next database re-arm after Accept (the tick or a link-up) fetches it again. The listing and the drop keep every guard the orphan sweep
  keeps (our own avatar, a blob stored this process, a group photo), and the drop re-reads the rows inside its write,
  since a stranger can name our public avatar hash in a DM. Conversations accepted between the listing and the drop
  are taken out of it. Our own avatar never counts toward the budget;
- the echo declines a held hash through the existing `EchoDeclined` path, including a photo only unaccepted groups
  decide, and wants any group photo under the avatar ceiling. It reads custody as well as rows, so a stranger's row
  naming a hash we carry for others never stops that relay. The verdict is memoised per hash for one
  `BlobExchange.ROUND_MS` in a 256-entry LRU and cleared on acceptance; the re-arm never reads the memo;
- the spool drops a held hash from its attachment candidates silently: it is not pending, so no retry is scheduled,
  and it is not settled, so acceptance can still fetch it;
- a group photo is held until the group is accepted, and is capped at `FileIntake.MAX_AVATAR_BYTES` either way;
- the re-arm builds the acceptance snapshot inside its locked pass, after the custody read, never cached. Its own
  collector on `acceptedConversations` and the verified ids re-arms at once, so Accept, Profile → Message, a contact
  import and a verification fetch without waiting for the 60 s tick. Replying and a known sender joining a group are
  caught by the tick.

The alternative the issue offered was a small byte budget per stranger. It keeps the fetch, and so the radio time,
the storage and the stranger's ability to make us ask on their behalf, all for an attachment nobody is shown before
acceptance. Folding acceptance into custody or relay was never on the table: ADR 009 forbids it, and acceptance is
local to the recipient. Custody, relay, acks, digests and `CarrierPullPolicy`'s inputs are unchanged. Only our own
pull of bytes addressed to us waits.

**Consequences / limits:**

- A request accepted after the 24 h custody window gets its attachment from the author alone, who keeps it under
  their own row, the next time they are reachable. A spool copy past the relay's retention is gone.
- A carrier linked to a held recipient still defers its own pull for up to `DEFER_MS` (ADR 2026-10.g24x), expecting
  the recipient to fetch. The carriers' copies of a request's attachment therefore arrive up to 10 min late.
- Acceptance becomes visible: the stranger sees its attachment fetched once the user accepts, and a neighbour can
  tell from a declined echo that some unaccepted request names a hash. Both say little a reply would not.
- The re-arm cannot see who decided a group's photo, since no column records it. A photo a contact set by a group
  update, with no message from a known sender, waits until a known sender posts or the group is accepted. The
  delivery that carries the update does count its sender.
- The echo's own fetch is bounded by the pull-through budget (ADR 2026-10.2drc), not the carrier budget, as before.
  What it lands is evicted by the storage sweep if only a request's rows come to name it.
- The re-arm's `unopenedForUs` still counts a request group's frame that names a third member, before we open it,
  as a frame to us. Under a Bluetooth limit it makes that carrier pull fast-only until the frame opens. A re-arm
  that read before a delivery's row can likewise turn the delivery's plain want fast-only for up to one tick. Both
  are #291.
- Bytes that do land under a request row, as a carrier pull or an echo, are still described and screened before
  acceptance, though never shown in the request inbox.
- Not covered: an unasked push (#277) and the relay scope (#290). The user's own tap (`downloadAttachment`) is consent
  and is not held. Peer faces keep their own bounds.
- Guards:
  - `RequestHoldTest`, including `aGroupFrameListingUsOnlyAsDepartedOrHeldAsOurGroupIsOurs` and
    `aGroupFrameNamingAThirdPartyIsCarriedForThem`;
  - `BlobDaoTest`'s `requestBlobCandidates` case, and `BlobRepositoryTest`'s
    `a request's bytes a frame no longer carries are listed and dropped`,
    `our own avatar named by a stranger's request is neither request bytes nor dropped`,
    `a blob this process stored is not dropped as a request's`,
    `a row that fetches it for us since the listing keeps the blob`,
    `a conversation accepted since the listing keeps its bytes` and
    `request ids past one bound list are listed in chunks` (40,000 ids, past SQLite's variable cap);
  - `InboundPipelineTest`'s `aStrangersDmAttachmentIsStoredAndAckedButNeverFetched`,
    `aRequestsAttachmentAContactAlsoSentIsFetchedFromTheContact`,
    `onCarriedFrameHoldsAStrangersDmToUsAndWantsOneItCarriesForSomebodyElse`,
    `onCarriedFrameHoldsAStrangersGroupFrameListingUsOnlyAsDeparted`,
    `onCarriedFramePullsAStrangersGroupPictureNamingAThirdMemberAsTheirCarrier`,
    `onCarriedFramePullsAThirdMembersPictureAsACarrierUnderABluetoothLimit`,
    `aRequestsBytesCountAgainstTheCarrierBudget`, `anAcceptedDmsDeadlineIgnoresAHeldRequestsRowNamingTheSameFile` and
    `aStrangersGroupPhotoIsHeldUntilTheGroupIsAcceptedThenPulledUnderTheFaceCeiling`;
  - `MeshManagerTest`'s `theReArmHoldsAMessageRequestsRowsAndItsFramesToUsAndAsksTheRest`,
    `acceptingARequestFetchesItsAttachmentWithoutATick`,
    `anAskForAParkedRequestsAttachmentNoRowNamesIsHeldByItsCustodiedFrames`,
    `aStrangersRequestNamingAThirdPartysPictureNeverStopsOurCarrierWork`,
    `theReArmWantsOnlyAnAcceptedGroupsPhotoAndUnderTheFaceCeiling`,
    `anAskForAGroupsPhotoIsHeldUntilTheGroupIsAcceptedAndCappedAfter` and
    `theRequestSweepRereadsAcceptanceJustBeforeItDrops`;
  - `BlobHolderQueryTest`'s `anAskForAMessageRequestsAttachmentMakesNoEchoAndAnyOtherDoes` and
    `anAskForAGroupsPhotoIsEchoedUnderTheFaceCeiling`, and `ScopeSyncTest`'s request-hold case;
  - `BlockAndRequestLabTest`'s `aRequestsPictureWaitsForTheAcceptThenCrossesEachLinkOnce` (stranger – carol – dave: no
    blob, want or copy at carol past `DEFER_MS`, then one copy per link after acceptance and `assertConverged`),
    `aStrangersPairGroupPictureWaitsForTheAccept`,
    `aStrangersGroupPictureReachesAMemberWhoKnowsThemThroughOneWhoNeverAccepted` and
    `aStrangersRequestNamingABystandersPictureNeverStopsTheirCarrier`;
  - `MaliciousForkLabTest`'s `aGroupListingUsOnlyAsDepartedIsStillARequestsAndHeld`,
    `aRequestsRowsDoNotLiftTheCarrierBudget`, `anAskForARequestGroupsPhotoMakesNoWantUntilTheGroupIsAccepted` and
    `aRequestNamingOurOwnPhotoNeverDropsIt`.
- Lab nodes are strangers to one another unless a scenario calls `MeshLab.befriend`, so a scenario that sends a
  picture between people who know each other befriends them first. `befriend` awaits each node's acceptance re-arm
  (`MeshMetrics.Files.acceptanceRearms`), first the collector's opening round and then the one its accept starts, so
  no re-arm is left in flight into the scenario's first send (#291). An id already accepted is skipped, since its
  accept starts no round.
