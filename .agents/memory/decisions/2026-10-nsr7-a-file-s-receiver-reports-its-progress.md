---
id: "2026-10.nsr7"
slug: a-file-s-receiver-reports-its-progress
title: "A file's receiver reports its progress, and the holder times parked asks by it"
date: 2026-10-07
topics: [ble, mesh, blobs]
---

# ADR 2026-10.nsr7 — A file's receiver reports its progress, and the holder times parked asks by it

Status: Accepted (2026-10-07). Amends ADR 2026-10.g24x (item 4, one Bluetooth copy of a blob at a time, and its
settling-pace amendment). Phase 1 is built; phase 2 is built as ADR 2026-10.5buf. A wire addition under the existing
`Protocol.CAP_BLOB_HAVE` bit (ADR 2026-10.6e5t), with no new bit: none of 6e5t, g24x or this has been pushed. The iOS port
owes the receiving half as a companion change.

**What was observed.** ADR g24x parks a second ask for a blob behind another copy of it being fed over Bluetooth. It only
parked behind a feed expected to end within 30 s, and served any parked ask beside the feed once it had waited 30 s
(`PARK_MAX_MS`). Both rules guessed. A feed's end was estimated from the pace the sender writes at, but the stack's
send queue hides the drain from the sender (ADR 2026-10.8jwn).

The device runs of 2026-10-06 showed what that cost:

- **Big files never parked.** At 18 KiB/s, 30 s is about 540 KB, so a photo bigger than that, or any 8 MiB file (about
  7.6 min), was always fed in parallel.
- **An iPhone feed let everyone in.** Its link never earns the boost, so a 410 KB photo read as about 100 s from its
  end. The next asker was "too slow to wait behind" and served beside it, and so was every asker after. The P7 fed
  four copies of one photo at once. The budget split by count cut the lit P3's earned 12 KiB/s to 3 KiB/s, and it
  took 138 s (reps 65–67).
- **The third asker never waited its turn.** The 30 s bound counted from its park, so with three askers the third was
  always served beside the second feed.

The estimates were not the failure in rep 65: the iPhone's feed really was about 100 s from its end. The failure was a
rule that turned one slow copy into four. Measuring is what lets the rule wait for as long as a feed really takes.

**What changed (phase 1).**

1. **The receiver reports.** While a file streams in on a Bluetooth link, `FramedLink` notes every
   `PROGRESS_EVERY_MS` (2 s) of chunks how much of the blob it holds. `held` is the absolute length, a resumed stream's
   kept prefix included. `ms` is the time since its header, by the receiver's clock. `BluetoothMeshTransport.fileProgress`
   carries the note up. `BlobExchange.onFileProgress` sends it back to the sender as a `blobprog` frame
   (`BlobProgressContent`: hash, held, ms). The frame is unsigned and point-to-point like `blobhave`, and goes only to a
   neighbour whose link carries `CAP_BLOB_HAVE`, and not to an iPhone (`CAP_DOORBELL`): no iPhone measures its feeds
   yet, so a report to one is air for nothing. The iOS companion change (knit-ios ADR 2026-10.ds45) takes the type
   quietly, reports as a receiver under the same gate, and honours `eta` as an asker; the gate stays until an iPhone
   meters its own feeds. An iOS build from before ds45 treats the type as an unverifiable signed frame and, from an
   unpinned sender, sends key requests, which is why the gate came first. An Android build that does not know the type
   refuses it as an unsigned frame and drops it. A stall sends nothing, which is how the sender sees one.
2. **The sender measures.** `blobprog` reaches `MeshTransport.onFileProgress`, and the link streaming that key takes it
   (`FramedLink.onProgress`). The rate is the change in `held` over the change in the receiver's `ms`, smoothed 3:1, so
   a report queued behind the receiver's own file does not read as a slow drain. `FramedLink.feed(key)` answers with a
   `FileFeed`:
   - **ETA:** what is left at the measured rate, or, before the first report, at the pace estimate (the settling pace
     for its first two chunks, g24x's amendment). That settling read needs only that the link can earn the boost: a
     dark holder's link earns when its lit receiver asks, which the link's history may not show yet. While the link
     can earn and has not, the settle lasts up to eight chunks (about 16 s at the base), and a rate measured in that
     gap does not displace it: on 2026-10-07 a dark receiver's link took 10 s to earn, and read at the base its 2 MiB
     feed looked 17 min long and let the next asker beside it. A pace move resets the measured rate until the next
     report.
   - **Stalled:** no progress for `STALL_MS` (15 s). Once this stream has a report, progress is a report that moved;
     before that, a write that went through (an iPhone or an older build never reports, and a stream queued behind
     another's undrained tail cannot be reported yet).
   - **Deadline:** set once, when the first rate is measured, or from the pace when the stream has had no report for
     `STALL_MS`, to ETA × 1.25 + 30 s. It moves back when this sender's own pace for the stream drops below what it
     was set at, which happens when another feed starts and splits the budget.
   - **The writer asleep:** its pace `delay` stops while the CPU sleeps. Whatever a wait runs past what it asked for,
     under way or done (`FeedMeter.pausing` … `resumed`), counts toward neither the stall nor the deadline. Pacing
     itself is no exemption: a receiver that stops reading with its link up stalls in 15 s, not once the stack's queue
     fills and a write blocks.

   A feed that is stalled or past its deadline no longer holds anyone. `FileFeed.recheckMs` says when either can next
   come due, and the holder's poll wakes then, between 1 s and 10 s. A copy queued with nothing streaming yet is live
   only while the writer gets records through: one blocked on a frame's write stalls after 15 s too. A measured ETA
   counts from the last report, less the time since it.

   The link also pokes the holder (`MeshTransport.fileFeedsChanged`, formerly `fileFeedsEnded`) whenever a feed's
   reading changes: its stream ends, its pace settles at the second chunk or moves, or its first report measures it.
   The holder re-reads its parked asks then, so an ask that may now go beside does not wait for the poll (device rep
   72: the P3 went beside 11 s after its park, though the feed showed its pace about 2 s in).
3. **Parking waits for the feed, however long it takes.** An ask whose copy would share the Bluetooth budget is parked
   while any other neighbour's copy of the blob is live, with no bound of its own (`PARK_MAX_MS` is gone). The one
   exception is an asker whose own copy, at the rate its link is expected to take, would end before every live feed of
   the blob does, and whose saving beats what the running feeds lose. That asker goes beside them: behind the iPhone's
   100 s feed, the P3's 33 s copy goes, and the P8's and P9's park. The loss: the budget splits one more way while the
   asker's copy runs, so each of the `n` feeds on the plane ends about own ÷ (n + 1) later. The saving: the last of the
   blob's `k` feeds' end plus the asker's copy at the share it would then get, less its copy now. It goes when
   `max ETA − own × k ÷ (n + 1) > own × n ÷ (n + 1)`, which with one feed is the same as ending first (device rep 72:
   the lit P3 saved about 54 s and the dark P8's feed lost about 24 s).

   The expected rate (`MeshTransport.expectedFileRate`) is the share the link's pace would give it: boosted only when
   the link will earn the boost (it has earned within 5 min, or this side is lit and asks), else the base. That share
   is multiplied by the shortfall the link's last measured stream showed against its own pace (`FramedLink.drainRatio`,
   forgotten on a PHY step). A raw measured rate is a split share, not the link's. The shortfall is read per report,
   against the pace, only from a report two or more after the pace last changed or the writer overslept: a pace that
   rose late in a stream, or a sleeping writer, would read as a slow air.
4. **Fastest first.** When a feed ends or changes (the link's poke), stalls or passes its deadline (the poll), the
   parked ask whose link is expected to feed fastest goes next. Ties go to the oldest.
5. **The asker is told how long.** A SENDING answer to a parked or in-flight asker carries `eta`, the ms until its copy
   should have landed (`BlobHasItem.eta`). The asker holds on that holder until then (taken as at most 15 min) plus
   30 s, rather than until its ask's 45 s floor. The holder keeps the parked ask that long (`ParkedAsk.until`), set
   at the park and renewed by a query from the asker as a re-ask does: an asker held on another holder may not query
   this one again before its turn (device cell (c): dropped 4 s before the feed ended, the asker came back 10 s
   late).

**Amendment (2026-10-07, after device cell (d)): members first, and an asker never waits out a stream for nothing.**
After the Bluetooth cut, the P8 took 20 min to get the file. Its only link back was to the P7, which was still fetching
the file itself; the P8's ask found it lacking. The P7 was also streaming the P8 an older carrier copy at about 2 KB/s.
The asker waits out any stream from the holder it asked (ADR 2026-10.6e5t: its copy may be queued behind it), so it sat
12 min there while the P3 held the file and fed nothing. Then it parked 287 s behind a carrier's lapsed pull at the
P3. Three changes:

1. **An ask that found its neighbour lacking the blob is let go** (`BlobExchange.askedLacking`). A neighbour that lacks
   a blob remembers no asker, so nothing of ours is queued behind what it streams. The test is an answer saying
   FETCHING or NONE that came in after our ask. The fetch is dropped without marking the neighbour failed, so its
   later HAVE asks it again, this time for real.
2. **A stream with long to run gives way to an idle holder** (`idleHolderPast`). When the file the asked neighbour
   streams us has more than `STREAM_SWITCH_MS` (30 s) left, the asker moves on to another linked neighbour that said
   HAVE with load 0 and streams us nothing. The time left comes from `FramedLink.rxEtaMs` (rate since its header)
   through `MeshTransport.fileArrivingEtaMs`. A copy already queued at the first neighbour still comes, so this can
   cost a second copy; a 30 s wait is worth that risk. While the fetch waits like this, a neighbour that said NONE is
   queried again `FETCHING_RECHECK_MS` (10 s) after its answer (`lackedWhileStuck`): an answer is otherwise final for
   the fetch, since a cycle opens only with no ask outstanding. In the iPhone smoke of 2026-10-07 the P9 answered NONE
   0.4 s before its own fetch began, held the photo 40 s later, and the P8 sat 8 min behind a DM file never learning it.
3. **Members before carriers at the holder** (`addressees`, `MeshManager.addresseesOf`). A blob's addressees come
   from the message naming it: a group's members, or a DM's two ends; they are unknown for a room or a relay. A member
   waits only behind members' copies and goes beside a carrier's. A carrier waits behind any copy and never goes
   beside one. Among parked asks a member goes first, then the fastest, then the oldest.

Tests: `BlobHolderQueryTest`'s `anAskThatFoundItsNeighbourLackingTheBlob…`, `aFetchLeavesAHolderWhoseOtherStream…`,
`aNeighbourThatLackedTheBlobIsQueriedAgain…`, `aMemberGoesBesideACarriersCopy…` and
`aParkedMemberIsServedBeforeAFasterParkedCarrier`; each fails with its change
switched off. Device check owed: cell (d)'s cut again. Pass: the cut receiver holds the file within about 2 min of its
relink, and no file arrives twice after a `blob fallback … streaming another file`.

**Amendment (2026-10-07): an asker withdraws its ask (`blobcancel`).** Nothing could withdraw an ask, so moving on
to a second holder risked a second copy: the reason ADR 6e5t's #140 amendment holds an asker to its floor, and the
risk in item 2 above. A `blobcancel` frame (`BlobCancelContent`: the hash; unsigned, point-to-point, under
`CAP_BLOB_HAVE`; to an iPhone too since 2026-10-07, once its port took the type in knit-ios ADR 2026-10.ds45, while
`blobprog` stays off iPhones) withdraws one.

- **The holder** (`BlobExchange.onCancel`) drops the ask if it is parked. It takes one copy queued to the asker off the
  link if that copy has not started (`MeshTransport.cancelQueuedFile` → `FramedLink.cancelQueued`; the writer skips
  it, logging `blob withdrawn … by <id> before it streamed`, kept off the `file ` prefix that counts a holder's serves). It then forgets the serve stamp, so a later ask is
  served. A copy already streaming goes on.
- **The asker** sends one when a fetch falls back from a neighbour its ask is on (grace, floor, confirm, or a busy
  stream with an idle holder waiting), and to every other neighbour it asked once the blob lands. Each ask is
  withdrawn once (`Want.withdrawnAt`), and only one that may have queued or parked a copy: never one on a neighbour
  whose answer saying it lacked the blob came at or after that ask (it left nothing queued), and never the first pick's when a blind round widens, since widening gives
  up on nobody. The device rerun showed the cost of withdrawing at a widening: an author's parked ask was dropped, and
  the asker came back 28 s late.
- **What is left:** a race, where the busy holder starts our copy before the cancel arrives, sends a second copy. So
  does any copy already streaming.
- **Not changed:** the hold floors. Shortening them now that a fallback is safe is a separate decision, once device
  runs show cancels landing.

Tests: `BlobHolderQueryTest`'s `aCancelDropsAParkedAskAndAQueuedCopy` and `aBlobThatLandsWithdrawsTheOtherAsks`,
the busy-stream switch's cancel, and `FramedLinkTest.aQueuedFileThePeerWithdrawsIsSkippedButAStreamingOneIsNot`; each
fails with the cancel switched off. Counters: `blobCancelsSent`, `blobCancelsTaken`. Vector: `blobCancelContent`. The
iOS port takes the type quietly and sends it (knit-ios ADR 2026-10.ds45), so Android sends it to an iPhone too; an
iPhone build older than that drops it, or asks an unpinned sender for its key, and its queued copy comes as before.

**Not taken.**

- **A link-level record instead of a frame.** `LinkFraming.read` throws on an unknown record type, so an older peer, or
  an iPhone that carries the bit and not the record, would drop the link. An unknown unsigned frame is only dropped.
- **Measuring on the sender.** Android exposes no queued-byte count on an L2CAP socket, and write blocking says nothing
  while the feed stays below the drain. The receiver's count is the only one that sees the air.
- **A new capability bit.** It would burn a bit for a feature not yet shipped. The lab mismatch (an iPhone with the bit
  and no `blobprog`) costs only its feeds being judged the old way: by pace and write staleness.

**Phase 2 (built since as ADR 2026-10.5buf, behind `BLE_FEED_WINDOW`, on in release since 2026-10-08): replace the pace caps with an in-flight window.** Bytes written minus bytes the
receiver reports is the stack's queue plus the air. Writing only while that is under about 0.5 s of the measured drain
would bound a text's wait behind a file at about 0.5 s, on any link speed. It would replace the 4/12/18 KiB/s budgets
and the cross-link split with each link's own self-clocked drain. The boost ask and a hard ceiling would stay, and
today's pace would stay for peers that never report.

It needs reports about every 0.5 s rather than 2 s, smoothing against connection-event noise, and care with reports
that queue behind the receiver's own stream. A/B it against today's caps with `scripts/ble-drain-trial.py`, using text
latency during a feed and the snoop's queue depth.

**What it costs.**

- **Air:** one `blobprog` of about 120 bytes every 2 s per incoming file.
- **A promise that may break:** a SENDING answer's `eta` is a promise the holder may break (a deadline passed, a link
  cut). The asker then falls back as before, after the promise plus 30 s.
- **A liar:** a holder lying about `eta` can hold an asker for up to 15 min, where a lie used to cost one grace.
- **A lying receiver:** since the amendment of 2026-10-27, a receiver's `held` is clamped to what its sender wrote and
  each sample capped at 64 KiB/s, so a liar can still pin its own link's `lastRate` at that ceiling, and with it the
  next feed's `expectedFileRate` to it, so its next ask is ordered as the fastest and may be fed beside a live feed.
  It cannot read a feed as ended early, nor overflow the rate.
- **Release:** none of this is behind a build flag. `CAP_BLOB_HAVE` is in every build's capabilities, so release builds
  report progress and park without a bound of their own, with the boost off (`BLE_LINK_BOOST`): the pace's base share
  and the feeds' measured rates decide everything.

**Tests.**

- **Unit:** `FeedMeterTest`, `FramedLinkTest`'s feed and report cases, and `BlobHolderQueryTest`'s park cases: parked
  with no bound, beside a slower feed, fastest first, served on a stall or a deadline, and the eta answer with the
  asker's hold.
- **Vectors:** `GoldenVectorTest`'s `blobProgressContent` and `blobHasContentSendingEta`.
- **Lab:** `PictureFanoutLabTest` (the lab reads a held pipe as a feed that stalls after `LabLimits.feedStallMs`).

**Device cells owed** (Wi-Fi Aware off, boost on).

- **Reps 65–67 again:** the dark P7 posts a 410 KB photo to the P3 (lit), the iPhone and the P7, with the P8 and the
  P9 as carriers. Pass: at most two copies at once (the iPhone's and the P3's), the P3 holds it in under 45 s, and every
  other ask is served after a feed's end, a stall or a deadline.
- **A big file:** a 2–8 MiB file to two Android askers. Pass: the second parks for the whole first feed, about
  size ÷ rate, and is served within about 2 s of its end.
- **A stall:** cut the first receiver's Bluetooth mid-feed. Pass: the parked ask is served within about 15 s. Then
  freeze the receiver's reader with its link up (`kill -STOP` on a debuggable build). Pass: served about 15 s after its
  last report, without waiting for the sender's write to block.
- **Fresh links, and a split mid-feed:** the big-file cell over freshly made links (toggle Bluetooth on the askers), once
  more with a different photo to a third peer started mid-feed. Pass: no `blob unpark` before the first feed's
  `file … in`.
- **Two photos in a row** from one holder to one peer, with a second peer asking for the second. Pass: no `blob unpark`
  for the second photo before its feed ends.
- **Back to back:** reps 65–67 run three times in a row with each carrier's screen state recorded, and once with the P8
  and the P9 dark. Pass as above in every rep.

**Amendment (2026-10-09): `blobprog` to an iPhone.** `onFileProgress` no longer excludes an iPhone (`CAP_DOORBELL`):
the iOS port is building the half item 1 waited for, a receiver that reports on every chunk and a sender that meters
its feeds by the reports, and this is the Android half of that paired change. Until the iOS half ships, each report to
an iPhone is about 500 B/s of reverse air it only counts (ds45). An iPhone build from before ds45 (knit-ios 38f4fe1)
treats the frame as signed and may ask an unpinned sender for its key, so a lab iPhone must be on ds45 or later before
this build meets it. A new capability bit is still not taken, for the reason above. Device trial owed: the iPhone
feeding a photo to a P9. Pass: the iPhone counts about 4 `blobprog`/s and acts on them (its meter or ETA in its log),
with no key requests and no rings on the P9's state line.

**Amendment (2026-10-27): a report is held to what was written (#158).** `FeedMeter.report` took a receiver's `held`
as it came: a value past the stream overflowed `(held - fromHeld) * 1000` near `Long.MAX_VALUE`, and any `held` past
the length left nothing to deliver, so the feed read as live with an ETA of 0, a third asker parked behind it, and its
rate became the link's `lastRate`. `FramedLink.feedChunk` now counts each chunk into the meter (`FeedMeter.wrote`)
beside the window's, under the same lock, and `report` clamps `held` into the stream's offset … offset + written,
as `FeedWindow.report` already did. Clamped, never refused: `report` still returns true for the stream going now,
since `FramedLink.onProgress` feeds the window only then. The clamp alone did not bound the rate, because a report's
`ms` is the peer's clock (silent through a feed, then one report of everything at 2 s); so each sample is also capped,
before smoothing, at `FeedWindow.CEILING_BYTES_PER_SEC`. Honest feeds never reach it: the meter is fed only by a
Bluetooth link's reports, a paced feed's budget tops out at `BOOST_PACE_BYTES_PER_SEC` (18 KiB/s), and a windowed
feed's rate is already held under the same ceiling. The residual is in the costs above. No wire, vector or schema
change; an Android-only bound, so nothing to pair.
