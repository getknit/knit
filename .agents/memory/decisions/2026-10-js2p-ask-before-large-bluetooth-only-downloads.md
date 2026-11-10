---
id: "2026-10.js2p"
slug: ask-before-large-bluetooth-only-downloads
title: "Ask before large Bluetooth-only downloads"
date: 2026-10-16
topics: [blobs, ui, wire]
---

# ADR 2026-10.js2p — Ask before large Bluetooth-only downloads

Status: Accepted (2026-10-16). Amends ADR 2026-10.f7gg and 2026-10.6e5t (a fast-only want asks and queries only
neighbours Wi-Fi Aware can carry the bytes from) and ADR 2026-09.qq2r (a photo or voice note of 1 MB or more now
declares its size too). One additive wire field (`BlobReqContent.fastOnly`), no capability bit, one vector added,
none moved. DB v22 (`messages.attachmentFetchApproved`). The iOS companion is owed (below). Device trial owed.

**What was observed.** Every received attachment was fetched the moment it was seen, over whichever plane a holder
served it on. A file of several MB that only Bluetooth peers held took minutes at the base pace (a 2 MB picture is
about 8 min at 4 KiB/s, `CarrierPullPolicy`'s own arithmetic), held the holder's shared Bluetooth feed budget the
whole time (ADR 2026-10.g24x), and the user never chose it. Over Wi-Fi Aware the same file takes seconds.

**What changed.**

1. **One rule, `mesh/BluetoothFetchGate.fastOnly`.** A received attachment whose sender-declared size is over the
   user's limit, not tapped, and not our own is *fast-only*: its fetch may ride Wi-Fi Aware alone. The fetch
   (`InboundPipeline.deliverChat`, `MeshManager.rewantMissingBlobs`) and the bubble (`ChatViewModel`'s
   `attachmentAwaitsTap`) all ask it. The bubble asks it of its own row and shows the card on a gated one; the fetch
   folds it over every row naming the hash (item 3), so another row naming it — ours, tapped, under the limit or
   declaring nothing — can fetch it while a gated row shows the card, which then gives way to the progress ring once
   bytes arrive. The limit is a setting —
   Never, over 1 MB (default) or over 5 MB (`SettingsStore.bluetoothDownloadLimit`, decimal megabytes as
   `Formatter.formatShortFileSize` prints them). The card's hint and the setting's subtitle name Wi-Fi Aware only on
   a phone that has it (`ui/WifiAwareCopy.kt`'s `rememberWifiAwareSupported`, the probe onboarding and Diagnostics
   read): the Moto G trial showed a phone without the plane being promised a fetch over it.
2. **Judged on the declared size only.** The size is the sender's label (never a bound, ADR 2026-10.y9qh); there is
   no other number before the fetch. A named file always declared one; `BluetoothFetchGate.declaresSize` now has a
   photo or voice note of 1 MB or more (`DECLARE_SIZE_FLOOR`, the smallest selectable limit) declare its plaintext
   length too, so an ordinary photo frame stays byte-for-byte what it was. An attachment with no declared size — an
   older sender, the iOS port today, a room post (the cleartext `ChatContent` has no size field) — fetches as before.
   The alternative, gating on a holder's `blobhas` size, was rejected: the UI could not show a card for a size only
   the exchange learned, and an older or iOS neighbour never answers.
3. **The tap is durable, and the rows are folded.** `MeshController.downloadAttachment` writes
   `messages.attachmentFetchApproved` on every row naming the hash, then wants it plain; a row that arrives later
   naming a tapped hash comes in tapped (`InboundPipeline.deliverChat`), or it would show the card again. The database
   is the source (`rules/mesh.md`): `rewantMissingBlobs` re-derives every want's flag on every call from
   `MessageDao.attachmentFetchRows(me)` — one entry per row, read by the `messages.attachmentHash` index v22 adds —
   folded per hash by `BluetoothFetchGate.fastOnly(rows, …)`:
   fast-only only when *every* row is (one row that is ours, tapped, under the limit or undeclared fetches it for all).
   Folding the largest size in SQL was rejected: a stranger's DM naming a contact's attachment hash with an inflated
   size would hold the contact's fetch while the contact's row, declaring nothing, showed no card to tap. A collector on
   the limit re-arms at once when the user changes it (its first value is the start's re-arm). Every re-arm and the tap
   run one at a time under `MeshManager.rewantLock`, since each reads the rows and the limit and then wants by what it
   read — a tick that read 1 MB applying after the switch to Never re-held the fetch, and one that read the rows before
   the tap undid it. The tap itself runs on the manager's scope, its approval written before the lock is taken, so
   leaving the chat while a re-arm holds the lock cannot cancel it. A delivery judges its row with no read at all when
   the row is not gated itself (it fetches the attachment for all), and with one read otherwise. In `BlobExchange` the latest local want sets the flag, except that the tap is sticky
   (`want(approved = true)`): a delivery that read its row before the tap and wants after it cannot re-hold it. A
   neighbour's ask sets the flag only on a want it creates; a want `reaskFromZero` re-creates after a sweep is
   fast-only until the next re-arm, never plain on a guess. Lifting the flag starts a fresh plain cycle at once.
4. **The asker** (`BlobExchange.decide`) considers, for a fast-only want, only neighbours whose `bulkRouteTo` is
   `Linked` or `Reachable`, and treats a `Reachable` one as large whatever the size (the holder arms, `BULK_GRACE_MS`).
   With none it asks nothing, stamps nothing, widens to nobody and pulls through nobody (`FastOnlyHeld`, logged once).
   Holder queries go only to eligible neighbours too: a Bluetooth-only neighbour's HAVE could never be acted on, and
   querying it every tick for as long as the tap is pending — possibly days — is a `blobhave` per neighbour per cycle
   for nothing. A pull-through on a neighbour's fast-only ask inherits the flag. A fast-only cycle that asked anyone
   and still wants the blob at its end is *dead* (`Want.dead`), so it backs off like a cycle every neighbour answered
   NONE (45 s, 90 s, 3 min, 6 min), and a holder's HAVE does not reset that, since a reachable holder answers HAVE every
   cycle while the link it needs never comes up — which otherwise cost a query, an armed ask and a data-path attempt on
   each side every 45 s, per held hash, until the tap. A Wi-Fi Aware link coming up is seen per holder: each newly
   linked eligible neighbour is forgotten as the cycle knew it (failed, answered, queried, asked blind) whatever else
   is outstanding — that neighbour alone: no fresh cycle and no cleared backoff, because Wi-Fi Aware links flap on every
   custody sync, and a reset per sync re-asked and re-armed every never-linking reachable holder at the sync rate. The
   holder our own ask is on is not an edge (its data path coming up is that ask being served). A neighbour already linked over
   Bluetooth that gains the fast link changes no neighbour set, so `CompositeMeshTransport.fastLinked` (the fast
   plane's own link set) drives `BlobExchange.fastLinkUp` at the edge (`MeshManager.watchFastLinks`), not the tick: an
   ephemeral data path may be gone in a minute. A neighbour that links over any plane is asked again as before. A holder that would refuse
   the asker (no Wi-Fi Aware route its side, the asymmetric sighting) still answers the query HAVE — the query names no
   flag — and the backoff is what bounds it.
5. **The wire.** `BlobReqContent.fastOnly`, null and omitted by default, so a plain ask is byte-identical
   (`blobReqContentFastOnly` vector added). **The holder** refuses a fast-only ask when it has no Wi-Fi Aware route to
   the asker (`FastOnlyRefused`, no serve memo stamped), never parks one behind a Bluetooth feed, and
   `CompositeMeshTransport.sendFastOnly` never hands it to the Bluetooth link holder: a live NAN link sends, else it
   arms, waits the grace and drops (`file route: … fast-only — dropped`). A dropped copy calls `FileMeta.onDropped`,
   which forgets the serve memo, so the asker's confirm query reads HAVE, not SENDING, for a copy that went nowhere.
6. **Pre-decrypt.** A frame addressed to us (our DM, a group we are in) is custodied before it is opened, so its size
   is still sealed: while a limit is set `onCarriedFrame` does not want it at all and leaves it to `deliverChat`, right
   behind (a want there only cost the Wi-Fi Aware neighbours one more query per incoming picture). One already stored —
   a re-served DM — is judged by its rows. One that never opens is the tick's (`MeshManager.unopenedForUs`), fast-only,
   and "addressed to us" there is the same signal custody used — the roster the frame itself carries
   (`CarriedAttachment.roster`, read off the stored signed bytes — loaded for group frames only) before our group rows: a
   member whose seed is late has no group row, and by rows alone the tick fetched a large group file over Bluetooth on
   every round. It folds as the rows do: fast-only only when every carried frame naming the hash is ours, so a
   stranger's DM to us naming a hash we carry for someone else can lift the gate on that pull, never impose it. One
   read of custody's rows per re-arm serves this and the carrier deferral. The carrier
   deferral (`heldForLinkedRecipients`, ADR 2026-10.g24x) reads the same roster: such a member was taken for a carrier
   deferring to its linked author, its own copy held back.
   A carrier's pull of someone else's frame, avatars, group photos and the spool fetch are unchanged.

**What it costs.** A frame parked on its keys no longer prefetches its blob over Bluetooth under the default limit;
the fetch starts when it decrypts. The #132 case (bytes landing before the row) now happens only with Wi-Fi Aware in
reach or no limit: `AttachmentLabTest` keeps it with its recipient's limit set to Never, and a sibling scenario pins
that under the default limit a large group file is asked for by nobody until its row exists, then fetched on the tap. A gated attachment with a reachable
holder that never links is asked a few times an hour (the backoff), not never. Held fast-only wants sit in
`BlobExchange`'s 256-entry memo until the tap; past 256 outstanding wants the eldest are evicted and the next re-arm
restores them, so eviction costs a fresh cycle, not a stranded blob — revisit if a phone ever holds that many. A want
`reaskFromZero` re-creates is fast-only until the next re-arm whether or not a row names the hash; a carrier's or an
avatar's then waits for that re-arm or the 30-min sweep (rare: a prefix outliving its want). No capability bit: an *older* Android holder the asker reaches over Wi-Fi Aware that fails
to raise the link within the grace still falls back to Bluetooth, as it always did; a `CAP_*` bit would close that.
While a `Reachable` holder is arming, the bubble already shows the card (no bytes are arriving yet); a tap then simply
lets Bluetooth carry it. Room posts stay ungated until `ChatContent` carries a size (a second additive field, with
its iOS pair). **iOS companion owed:** declare `attachmentSize` for a photo or voice note of 1 MB or more (else an
iPhone's large photo is never gated on Android), never serve a `fastOnly` ask over Bluetooth, and, for its own
recipients, do not ask until the tap. Guards: `BluetoothFetchGateTest`, `BlobExchangeTest`'s fast-only cases,
`CompositeMeshTransportTest`, `WireSerializationTest` / `GoldenVectorTest`, `MessageDaoTest`, `InboundPipelineTest`'s
fold and inheritance cases, `MeshManagerTest`'s re-arm and tap cases, the 23→24 migration test,
`ChatViewModelTest.aLargeReceivedAttachmentAwaitsTheTap…`, and `mesh/lab/LargeAttachmentLabTest` (Bluetooth-only held
then one copy after the tap; the tap racing the tick, no withdrawal; Linked and Reachable fetched untapped; undeclared
and Never fetched).

## Amendment 2026-10-19: the re-arm reads custody before the rows

**Observed** (#141's chaos sweep, finding 6; seeds 589344076–589344112). `rewantMissingBlobsLocked` read the rows, then
custody. Custody's per-frame read (`ForwardDao.carriedAttachments`) loads a room post's signed bytes, and so its
declared size, only while no message row names the hash (ADR 2026-10.hmfe), and `deliverChat` writes its row without
`rewantLock`. A row written between the two reads was in neither: the rows' read missed it, custody's brought no size,
and the post was wanted plain — the hold lifted and a `pull=3` ask went over Bluetooth before the tap (items 3 and 6).

**Decided.** Custody is read first (`forwardStore.attachmentsNeedingFetch`, then `carriedFrames`), the rows after, and
the wants are built as before, own rows first. That closes every insert before the last read; a room post inserted
after it is judged by the same declared size; a sealed frame's row after it is judged by its delivery's want, which
the re-arm no longer overrides (amended 2026-11-10, #291, below). Two gaps
the order cannot close are held rather than guessed: a room post a row covered when custody read it
(`CarriedAttachment.rowCovered`, projected beside the CASE) whose row was gone by the rows' read — a room cap's sweep —
and a carried hash whose frame was trimmed between custody's own two reads. While a limit is set both are fast-only
until the next re-arm, which reads them again. No wire, schema or convergence change: a projection, and the order of
three local reads. Guards: `LargeAttachmentLabTest`'s two between-the-reads scenarios (the hook
`MeshManager.betweenRewantReads`), `MeshManagerTest.aCarriedHashCustodyCouldNotJudgeBetweenTheReArmsReadsIsHeldUntilTheNext`,
`ForwardDaoTest`'s row-covered case.

## Amendment 2026-11-10: a re-arm keeps a want re-derived since its reads (#291)

**Observed** (#291; `StrandedAttachmentLabTest` about 1 run in 200, `RestoreLabTest`'s re-seal case 1 in 3,000 under
CPU load). The 2026-10-19 text held "a sealed frame's row after it" until the next re-arm, on the reasoning that no
fetch had started. It had. A delivery writes its row and wants the hash without `rewantLock`, re-deriving the flag
and the deadline from every row (`untilKnown`). When it landed between a re-arm's reads and its apply, the re-arm's
`unopenedForUs` had counted the still-unopened frame in custody as ours, and `recordWant`'s merge ("the latest want of
our own wins") turned the delivery's plain want fast-only again. With no Wi-Fi Aware holder `decide` held it: the
author's HAVE was recorded and nothing was asked. On a phone the next re-arm, at most the 60 s tick, re-read the row.
The lab has no tick, so it never re-read the row.

**Decided.** The re-arm takes a read mark (`BlobExchange.rearmMark`) before its first read, the limit included, and
passes it to `wantAll(…, readMark)`. A want of our own that re-derives from every row outside the re-arm (`want` with
`untilKnown`: a delivery, the tap) takes a stamp from the same counter (`Want.rederivedSeq`). In the merge, a want
stamped after the mark keeps its fast-only flag and its `until`. Its age and budget are refreshed as before. The next
re-arm, whose reads see the row, re-derives both. The mark is a sequence held under the exchange's lock, not a time:
`BlobExchange.now()` and the manager's clock need not be one clock, and two events can share a lab millisecond. A
want stamped at or below the mark was recorded before the re-arm's reads began. Its row was written before that
want, so the re-arm read the row and its verdict stands. A want stamped above the mark may have read a row the re-arm
missed.

Only a stamped verdict is ever kept. A later own want that does not stamp (a carried frame's, an avatar's) set the flag
last, so it clears the stamp, and the re-arm's verdict applies over it. A neighbour's ask never stamps and never
clears one: it moves no flag of ours. A carried frame's want (`onCarriedFrame`) takes no stamp and is not protected. It does not read every row: a frame
carried for somebody else is wanted plain with no read, while a hash our own rows name over the limit is fast-only by
the rows. Protecting it would let a carrier pull lift the user's limit on our own download for a pass, which is "plain
on a guess". The cost of leaving it out is one re-arm's hold, up to the 60 s tick, on a carrier pull. It is not always
a pull nobody watches: `carriedFastOnly` judges a frame addressed to us by our own rows once one exists. A second frame
naming the hash (a reused attachment identity's) that lands between a re-arm's mark and its apply, after the
delivery's stamp, clears that stamp. The re-arm then holds our own picture for that pass (#314).

In the same pass, `unopenedForUs` no longer counts a message request's group frame that names a third member as ours.
That frame is the third member's carrier copy, which `onCarriedFrame` pulls plain. Both sites now read one predicate,
`RequestHold.carriedForOthers` (the blobs-reviewer's N1 on #282), so the re-arm no longer re-holds that pull until the
frame opens.

**Limits.** A delivery that read the old Bluetooth limit and recorded its want after the limit collector's re-arm took
its mark keeps its stale verdict for one pass, up to the 60 s tick. A lowered limit lets one picture ride Bluetooth for
that pass; a raised one holds it fast-only for that pass. Before this amendment a delivery recorded after the apply did
the same, so the window grew from "after the apply" to "after the mark": bounded, and the same in kind. Accepted (both
reviewers of #291). To close it later, carry the limit a want was judged by on the want, and keep its verdict only when
that equals the re-arm's limit. Never move the stamp to the start of the delivery's read: the delivery's knowledge
includes the row it writes after that read, and #291 reopens. The tap is sticky and is unaffected. No wire, schema or convergence change: an in-memory counter and a local merge rule. Guards:
`BlobExchangeTest.aReArmThatReadBeforeADeliverysPlainWantLeavesItPlainAndItsDeadline` and its mirror
`aReArmWhoseReadsBeganAfterThePlainWantStillReDerivesIt`, `aCarriedWantAfterADeliveryClearsItsStampSoTheReArmAppliesOverIt`,
`neitherANeighboursAskNorACarriedWantStampsAgainstTheReArm`,
`RestoreLabTest.aReArmThatReadTheUnopenedFrameNeverReHoldsTheResealsPlainWant` (the seam
`MeshManager.beforeRewantApplies`), and `RequestHoldTest.aRequestGroupNamingAThirdMemberIsCarriedForOthersUntilItIsAccepted`.
