---
id: "2026-10.5buf"
slug: a-bluetooth-file-feed-is-clocked-by-its-receiver-s-reports
title: "A Bluetooth file feed is clocked by its receiver's reports"
date: 2026-10-07
topics: [ble, mesh, blobs]
---

# ADR 2026-10.5buf — A Bluetooth file feed is clocked by its receiver's reports

Status: Accepted (2026-10-07), behind `BuildConfig.BLE_FEED_WINDOW`, on in release since 2026-10-08 ahead of its device A/B, and on in
the first release that carries nsr7's `blobprog`. Builds ADR
2026-10.nsr7's phase 2. For the links it covers, it replaces ADR 2026-10.8jwn's budget and its split, and ADR
2026-10.2da4's earned budgets; the boost's interval ask stays. No wire change.

**What was observed.** On 2026-10-07 an A/B ran v2.8.0 against HEAD 3a6f5d08 on the P3, P8 and P9, Wi-Fi Aware on and
off, two reps per cell. It showed that a fixed pace is wrong both ways:

- **v2.8.0's 28 KiB/s overfeeds a slow pair.** 8jwn measured 1M drains of 4.8–11 KB/s, and texts waited 50–280 s
  behind a photo.
- **Some pairs drain more than that.** In the A/B's T8 cell, where every phone was dark and the P8 sent, the same
  28 KiB/s delivered a 341 KB photo in about 12 s.
- **HEAD's 4 KiB/s base, the safe floor for chat, is slow.** It took 111 s for that cell. The boost's 18 KiB/s rescues
  only a link that a lit side earns, and release keeps the boost dark (`BLE_LINK_BOOST`). A release cut from HEAD
  would feed every Bluetooth file at the base.

The receiver's progress reports (`blobprog`, nsr7) already measure what the air drains.

**What changed.**

1. **The feed is a window, not a pace (`mesh/link/FeedWindow`), sized as BBR sizes one.**
   - **What it holds back:** the bytes written and not yet reported held: the stack's queue, the air, and what landed
     since the last report. It writes while that is under `rate × (rtt + QUEUE_MS)`, `QUEUE_MS` being 250 ms, from
     `MIN_WINDOW` (4 KiB) to `MAX_WINDOW` (64 KiB); 8 KiB before a rate. Nothing but a report frees it, so a sender whose
     CPU sleeps (a dark phone) is woken by the reports it waits on.
   - **The drain (`rate`):** Δheld ÷ Δms between reports, on the receiver's clock, over at least `MIN_SAMPLE_MS`
     (250 ms; a reader catching up a burst reports bytes ms apart, which would read MB/s). The largest of the last
     twelve samples, a few seconds of them, up to a 64 KiB/s ceiling. Four, about a second, let a feed that was briefly
     the limit (a dark sender waking late for a report) drag its own window down in a spiral (device sweep 6: 20 → 4.5
     KB/s on a link that drained 15). A link that really slowed is read from the round trip: a chunk back after half
     again what it should have taken (the round trip, the queue, and the bytes ahead of it at the drain) waited behind
     a growing queue, and the filter keeps only what it measures next. Judged by the bytes ahead, not the round trip
     alone, which a faster earlier file can leave short (the reviewer's fifth pass).
   - **The round trip (`rttMs`):** from a chunk's write to the first report holding it, on the sender's clock; the
     smallest in the last 10 s (`RTT_WINDOW_MS`), compared raw so a sample clamped to the 3 s ceiling never renews it.
     A sample taken behind the standing queue counts the queue, so the smallest is the one that does not; when none
     smaller has come for 10 s, the window drops to one smallest chunk's room for a round (`probingRtt`) and the first
     chunk back, with at most one chunk ahead of it, is the round trip whatever it reads. That is how the estimate rises
     when the link slows mid-file, at about 12% of a 900 ms link's drain. The round trip is the link's, kept across its
     files: a file fed in full leaves its tail in the stack's queue (ADR 2026-10.nxfb), and taken fresh each stream the
     estimate grew with every file sent back to back until it locked at 3 s. A PHY step forgets it (`forgetRtt`). It
     can lag a rise between files (a peer's screen going off lets the link boost go, with no PHY step) until the probe.
   - **Why the round trip, and not the gap between reports:** a window covering only the queue and the report gap
     (W4–W6, below) gave a lit sender to a dark receiver, whose reports came back slowly, half the link (device sweep 4:
     2.9 and 4.0 KB/s on a link that drained 5.3 and 8.8). A window over a measured round trip without the minimum and
     the probe (a shelved rewrite) grew by the standing queue every round, to a 3 s queue at 10 KB/s.
   - **A queue standing past two windows at a report also clears the drain's filter**, keeping the latest sample: the writer
     stops at one window, so it fires when the window shrinks under a queue sized for the old one. One window fired on
     nearly every report of a lit sender, since what a report finds unheld is the queue plus the round trip's bytes
     plus the writer's last chunk (the reviewer's port: 60–80% of its drain).
   - **Chunks:** about 250 ms of the rate each, 2–16 KiB.
   - **A receiver that reports every two seconds** (its window switch off, or a build that predates the window) never
     reports the starting window, and a window waiting on it ran in three-second fits below the paced base. Two report
     gaps of 1.9 s or more in a row, each covering more than one chunk, pace the stream, until it answers chunk by chunk
     again (the dozing-receiver amendment below). A windowed receiver
     reports every chunk, so its gaps are the chunks' own, long on a slow link or across a probe; read by time alone
     they paced a slow link's window away (the reviewer's fourth pass).
2. **Which links.** The window covers a 1M/2M link to an Android peer carrying `CAP_BLOB_HAVE`
   (`BluetoothMeshTransport.windowed`).
   - **Kept on today's `CodedPhyPolicy.pace`:** an iPhone (`DoorbellPolicy.serves`), since nothing reports to it and
     its own reports are not measured yet; a Coded link, which waits on its walk trial (windowed since the amendment
     of 2026-10-08 (2), below); and an older build. A build
     that carries `CAP_BLOB_HAVE` but predates `blobprog` (origin/main at 403cd861) costs only a 3 s stall on the
     first 8 KiB, then the pace.
   - **That pace is also the fallback:** a windowed feed whose receiver has been silent for `NO_REPORT_MS` (3 s) is
     paced, and goes back to the window at its next report.
3. **No split.** A windowed link ignores the 8jwn split and the boost budgets. One controller's shared air shows up
   as each link's own lower drain, which its window follows. A windowed feed is not counted in a paced feed's split
   either (`pacedFeedingLinks`): it takes none of the budget, and counted there it halved an iPhone's pull beside it.
   - **The boost's interval ask stays:** it is what raises a drain, and the window takes the rise by itself.
4. **Reports on every chunk while the window's switch is on**, once a sender's smallest chunk (2 KiB) has landed since
   the last report (`RxReports.WINDOWED_EVERY_BYTES`); also every 500 ms (`WINDOWED_EVERY_MS`), and **once more 500 ms
   after chunks no later report carried** (the trailing report, a backstop). A chunk is about a quarter second of the
   drain, so about four reports a second at any rate, about 500 B/s of reverse air. Between two dark phones only a
   Bluetooth packet wakes either CPU, so a report on the chunk itself is the one that always comes: an 8 KiB rule left a
   smaller window waiting out the sender's 3 s silence every few seconds (device sweep 1). A windowed sender writes nothing more until it hears,
   so without these a fast pair's starting window landed inside one interval and the feed waited out its 3 s of
   silence on every stream (the bluetooth-reviewer's finding). The byte rule runs on the reader, awake for the chunk;
   the trailing report is a `delay` a suspending dark receiver may sleep through, and then the sender's next chunk
   wakes it, after the sender's 3 s silence. A stall still sends nothing. With the switch off, as in release, a receiver reports every 2 s of chunks, unchanged: `FeedMeter`'s settle
   (`STEADY_REPORTS`), smoothing and first-rate deadline count reports, and are tuned to two seconds of them. The
   receiver keys this on its own switch, so a trial sets the switch on both phones (`ble-drain-trial.py` and the A/B
   driver do). A windowed sender to a 2 s reporter (a phone left on `pace`, or a build with the switch off) gets its
   window back only at a 2 s report or its 3 s silence, so its feed runs in fits. Flipping a default cannot change
   builds already installed, so **`blobprog` (nsr7) and the window ship in the same release** (decided 2026-10-07):
   the release that first carries nsr7 turns `BLE_FEED_WINDOW` on, every install of it reports at the window's
   cadence, and none reports every 2 s to a later sender that windows. The window's device A/B is therefore a gate on
   that release, not only on the flag.
5. **The rest of nsr7 reads the window.**
   - **ETAs:** the writer notes the window's rate as its pace, moving it only past a quarter, since a pace move resets
     the meter's rate.
   - **`fileFeed`:** reads a windowed feed without the boost's settle.
   - **`expectedFileRate`:** answers the link's last measured drain (`FeedMeter.lastRate`), shared among the feeds
     going beside it.
   - **`FeedMeter` samples about every two seconds, however often the receiver reports** (`MIN_SAMPLE_MS` = 2 s, on
     the receiver's clock). Its settle, smoothing and first-rate deadline count samples, tuned to nsr7's two-second
     reports; fed a windowed receiver's report on every chunk, a single quarter-second sample could set a deadline that
     lapses mid-file and serves a second copy beside it (a side note, 2026-10-07). A paced receiver's reports are two
     seconds apart already, so release reads them as before.
6. **A report, or a frame queued to go out, wakes a waiting writer** (`FramedLink.awaitReport`), so a window freed by a
   report fills at once and a frame waits for no window. The frame matters most when two windowed feeds cross on one
   link: each side's reports on the other's feed are frames, and held behind its own wait they would starve the other's
   window. The fallback's pace wait ends early on a report only (`awaitPaceOrReport`), never on a frame's wake, or the
   pace would follow the link's frames (the reviewer's second pass: 41 chunks where the pace allows 4, pinned by
   `FramedLinkTest.framesQueuedDuringAWindowedFeedsFallbackDoNotHurryItsPace`).

**Device sweeps (2026-10-07, P9 → P8, both screens off, boost off, a 400 KB random file, `ble-drain-trial.py`).**

- **Sweep 1, the first build** (a window of 500 ms of the drain alone, reports on 8 KiB): 4.1–4.7 KB/s against
  the 4 KiB/s pace's 4.1, and once 9.9. The sender's CPU slept, so it wrote only at a report; the window, refilled
  once a report, capped the feed at window ÷ gap and sank to its floor. A 250 or 1000 ms queue did no better.
- **Sweep 2, W3** (the report gap in the window, reports on every chunk): 9.4, 4.6 and 8.6 KB/s at 250 ms, 8.0 and
  6.5 at 500 ms, against the link's unbounded 9.0 and 6.2. The slow rep sat at the floor once the link stepped to a
  255 ms interval.
- **Sweep 3, W4** (the 8 KiB floor kept, and a growth rule that never fired; the standing check at one window),
  window and unbounded reps alternating: 9.2/9.8, 5.0/4.0, 5.1/4.6 and 8.0/8.6 KB/s. The window ran at about the
  link's drain in every pair, dark to dark.
- **Sweep 4, W6** (W4 with the standing check at two windows and the dead rule gone), the P9 lit → the P8 dark:
  2.9/5.3, 4.0/8.8 and 7.6/5.1 KB/s. The window covered the report gap but not the round trip, and a dark receiver's
  reports came back slowly. Both lit with the boost asked: 4.3/8.0; the boost was not earned.
- **Sweep 5, W7** (BBR's minimum round trip and probe, before the fourth review's fixes): the P9 lit → the P8 dark,
  7.7/7.9, 3.8/2.6 and 5.6/8.7 KB/s; both dark, 4.8/6.4 and 8.0/8.4. Texts behind the window took 2–5 s, behind
  the unbounded file 20–154 s.
- **Sweep 6, W9** (the fourth review's fixes; a four-sample drain filter), window/unbounded: the P9 lit → the P8
  dark 6.6/7.3 and 7.1/8.6; both dark 6.1/8.2 and 4.7/4.5; the P8 dark → the P9 lit, never run on a window build
  before, 9.1/15.2 and 17.7/22.3 KB/s. The 74% and 60% reps spiralled: the rate fell while the feed was the limit.
- **Sweep 7, W10** (this design: twelve samples, and the round trip's slowdown signal): both dark 4.7/4.9 and 3.9/2.5;
  the P8 dark → the P9 lit 12.0/12.6 and 11.2/13.5 KB/s, 83–154% of the link. Texts behind the window took about 2 s
  (once 6.8), behind the unbounded file 18–159 s. The lit → dark cell was not re-run on W10 (W9: 91% and 83%).
- **Not run yet:** a link slower than 1.5 KB/s, two feeds crossing on one link, files back to back, the boost earned
  (no rep earned it: `earned=false` throughout), and the full A/B against v2.8.0 on the P3, P8 and P9.
- **Text latency.** The P8 and P9 had the Internet (spool) plane on, so every DM text of sweeps 1–3 went by the relay
  in about 2 s and timed nothing (spoolPushed 75 on the P9). With it off, texts behind an unbounded file waited
  19–58 s, and behind W4's window about 2 s, the harness's own floor. `ble-drain-trial.py` now refuses to run with
  the plane on, and the A/B driver switches it off.
- **The simulation** (`FeedWindowTest`, with a receiver that reports as `RxReports` does and a sender that sleeps or
  not): a lit sender whose reports take 900 ms to come back gets 85% or more of 6, 10 and 20 KB/s, with the queue
  under 2 s; a dark one, 85% of 6 KB/s less the probe; a round trip rising 50 → 900 ms mid-file is followed.

**The alternative a reader would reach for first:** put 28 KiB/s back, or raise the base. Every fixed number is a
guess at one link, and these links ran from 4.8 to more than 28 KB/s across one lab in one afternoon. The measured
drain is the only number that is right on each of them.

**Not taken.**

- **A BBR-style probing cycle** (pace gain 1.25 and 0.75). The report's reset of the estimate already probes, and
  the device data does not ask for more yet.
- **The window on Coded:** it waited on the walk trial, and was taken by the amendment of 2026-10-08 (2).

**What it costs.**

- **Air and wakes:** about four reports a second per incoming file while the switch is on (about 500 B/s of reverse
  air), up from one every two seconds; each wakes the sender's CPU.
- **A text's wait:** a text sent behind a file waits up to the window ahead of it, about a quarter second of the drain
  plus the report gap, or the 4 KiB floor on a slow link (about 0.8 s at 5 KB/s), plus one chunk. Measured on the
  phones: about 2 s, the harness's floor.
- **A late report:** a report queued behind the receiver's own outbound file overstates the queue, so the feed holds
  back, never overfeeds.
- **A lie:** a lying receiver can raise its own feed only to the ceiling and `MAX_WINDOW`, about a second of queue at
  64 KiB/s. It can slow its own feed, which costs no one else.
- **The trap:** a test clock that jumps past the writer's wait reads as the writer asleep (`FeedWindow.slept`), not the
  receiver silent. `FramedLinkTest`'s silent-receiver case runs on a real clock for that reason.

**Tests.**

- **Unit:** `FeedWindowTest` simulates a link draining at a set rate with lagged reports: steady at 20 KB/s, slow
  at 4.8 KB/s and fast at 40 KB/s, each found and kept within about a second of queue; a drain that falls to 6 KB/s;
  a report 900 ms late. It also pins the bounds: the starting window, silence, the writer's sleep, an overclaim, a
  reordered report, a resume's offset, the extrapolation bound, and chunk sizes.
- **`FramedLinkTest`:** `aWindowedFeedHoldsAtItsWindowUntilAReportFreesIt` and
  `aWindowedFeedWhoseReceiverIsSilentIsPacedUntilItReports`.

**Device A/B owed** (P3/P8/P9, two reps, the 2026-10-07 harness with its text probes). One HEAD debug build, switched
by `…debug.LINKTUNE --es feed window|pace` and `--ez boost`:

| Arm | Build | Feed | Boost |
|---|---|---|---|
| A | v2.8.0 | its pace | none |
| C | HEAD | the caps | off (a release cut today) |
| D | HEAD | the window | off (the candidate) |
| E | HEAD | the window | on |

- **Pass, D against A:** pictures held as fast or faster in every cell, T8 included. Texts behind a photo: median
  ≤ 5 s, max ≤ 15 s. No more copies per delivery.
- **Before the arms:** pick `QUEUE_MS` with `scripts/ble-drain-trial.py --feed window --window-queue-ms 250|500|1000`
  on one link, reading the receiver's `rx … in` line, the texts, and the `bt file window` line.
- **Flipping the release default** is a separate commit after the A/B.

## Amendment 2026-10-08: a dozing receiver gets its window back

**What was observed.** On 2026-10-08 the P3, lit, posted a group picture to the dark P8 and P9, with the boost off and
no orphan connections (#142; run D2, 6 reps). The P3's snoop showed its link to the P9 skipping 41–68% of connection
events, at 2.4–9 KiB/s, while its link to the P8 drained 17–19 KiB/s at the same moments. With the P9's screen kept on
(run D3, 3 reps), the same link drained 13.6–14.5 KiB/s and the P9 held the picture in 17–28 s, against 48–167 s
dark. A dark receiver dozing drains slower, and its reports come back late, several chunks at a time.

In D2's second rep those late reports read as a two-second reporter's, and the rest of the picture went at the 4 KiB/s
pace. That cost little while the P9 dozed. But the paced chunks were the pace's 8 KiB, one every 2 s, so a receiver
woken mid-file, its user picking it up to look, still answered two seconds apart and stayed paced to the end.

**What changed.**

- **Paced chunks are small while the receiver is taken for a two-second reporter:** `FeedWindow.pacedChunkBytes`
  gives `MIN_CHUNK_BYTES` (2 KiB), so a receiver reporting every chunk answers at the paced spacing. That spacing
  follows 8jwn's split of the base among the links feeding at once: about 0.5 s with one feeder, 1 s with two (D2's
  group picture), and 2 s or more with four or more, past `SLOW_REPORT_MS`, where a woken receiver never earns the
  window back.
- **Three such answers in a row give the window back** (`RECOVER_REPORTS`): each holds exactly one new chunk, under
  `SLOW_REPORT_MS` after the last on the receiver's clock and no sooner than half the paced chunks' spacing (about
  250 ms at the base). A true two-second reporter reports once every two seconds of its own clock
  (`RxReports.EVERY_MS`), so it never earns one. The floor keeps out a dozing receiver working through a backlog,
  whose per-chunk reports come milliseconds apart (the bluetooth-reviewer's finding). Only a chunk written after the
  latch counts: the window's chunks still out when it trips arrive before any paced chunk has set the floor (its
  second pass).
- **Recovery assumes a lit sender.** A dark sender's paced waits stretch while its CPU sleeps, which pushes the
  receiver's gaps past `SLOW_REPORT_MS`.
- **The feed logs its switches:** `bt file window paced: two-second reporter|paced: receiver silent|back → <node>`,
  on each change of what it says, including a reason that changes while the feed stays paced (silent, then taken for a
  two-second reporter); a stream starts as `back` and does not log it.
- **Checked on the phones (2026-10-08 05:08, P3 → P9 with the P9 on `--feed pace`):** `paced: receiver silent`, `back`,
  then `paced: two-second reporter`, and no `back` for the 11 s the P9 kept reporting every 2 s. Switched to per-chunk
  reports, it logged `back` 3.1 s later. The P9 stayed dark, so the rest of the picture went at its dozing drain.

**What it costs.** A dozing receiver can flap: a run of on-time reports gives the window back, and two late batches
take it away again. Each switch moves the meter's pace and the split of any paced link beside it. Smaller paced
chunks add a record header every 2 KiB instead of every 8 KiB, and wake a writer paced against a true two-second
reporter twice a second instead of every two.

**Open: what made D2's reports late and several chunks each.** A receiver reporting every chunk sends its first late
report with one chunk in it, which cannot meet the latch (`landed > 1`). So D2's reports were merged or lost on the way.
Candidates, none proven: the receiver's `rxProgress` flow (16 deep, `DROP_OLDEST`, `BluetoothMeshTransport`) behind a
stalled collector, `BlobExchange.onFileProgress` returning early while the peer is briefly not a neighbour, or a failed
send. Until that is known, how often a dozing receiver flaps has no bound in code; fixing a drop would remove the false
latch at its source.

**Not changed.** The stall, the deadline and the asker order already scale with a slow drain: late reports of 2–3 s
never reach `FeedMeter.STALL_MS`, the deadline comes from a measured ETA, and `expectedFileRate` puts a dozing
receiver measured slow behind a lit one.

**Tests.** `FeedWindowTest.aDozingReceiverWokenMidFileGetsItsWindowBack`, `aTwoSecondReporterStaysPacedInSmallChunks`,
`anOnTimeRunBrokenByALateReportStartsOver`, `aDozingReceiverWorkingThroughItsBacklogDoesNotWinTheWindowBack`,
`theWindowsChunksStillOutWhenTheFeedIsPacedAreNoAnswer`, `aReportThatProvesNoNewChunkIsNotOnTime`;
`FramedLinkTest.aFeedTakenForATwoSecondReporterIsPacedInSmallChunksAndWinsItsWindowBack`.

**Device check owed.** The D2 cell (the P3 lit posting to the group, the P8 and P9 dark, boost off). Wake the P9
during a feed that logged `paced: two-second reporter` and read `bt file window back` within about 4 s (two feeders,
1 s paced spacing, three answers), then its
drain return to the lit-receiver level. While the P9 stays dark, `back` should not fire; count each `back`/`paced`
pair per rep if it does, and count the P9's sent `blobprog` reports against the P3's taken ones per stream to settle
the open question. A second check pairs a windowed sender with a receiver on `--feed pace`: one
`paced: two-second reporter` and no `back` for the rest of the file.

## Amendment 2026-10-08 (2): the window on Coded

**Why now.** The window was kept off Coded only until yvn6's walk trial, which has run. Coded's fixed pace is wrong both
ways, as 1M's was. At close range S=8 drains about 2.7 KB/s (yvn6's 22 kbps at HIGH), and the 1 KiB/s pace held a
photo to a third of that, the cost #114 accepted. At the far edge a drain under 1 KiB/s is overfed for the whole file:
at 0.8 KB/s a 200 KB photo leaves about 40 KB in the stack's queue by its end, a text behind it nearly a minute.

**What changed.**

- **A Coded link to a peer that reports is windowed** (`BleFeedPolicy.windowed` no longer reads the PHY). Coded's pace
  (1 KiB/s in 2 KiB chunks) is its fallback for a silent receiver, and stays the feed of an iPhone and an older build.
- **The floors are counted in the link's smallest chunk** (`FeedWindow.minChunk`, carried by
  `PaceConfig.windowChunkBytes` and read before every chunk): the window never under two, four before the first report,
  starting chunks of two, a probe's room of one, and the paced chunks of a two-second reporter one. On 1M and 2M that is
  2 KiB, every number as before; on Coded 1 KiB (`PhyTuning.codedWindowChunkBytes`, `…debug.PHY --ei
  codedWindowChunk`). The 1M floor at the edge's 1.1 KB/s held a frame about 3.5 s, and 7.4 s at a file's start.
- **Never under 1 KiB** (`FeedWindow.SMALLEST_CHUNK_BYTES`). A dark receiver reports only on the record its reader
  wakes for, and then only once 2 KiB came since its last report (`RxReports.WINDOWED_EVERY_BYTES`) or 500 ms passed.
  Two 512 B chunks, the whole floor, landed inside 500 ms and unreported, and the feed waited out its 3 s silence (the
  simulation, at 2.7 KB/s). Two chunks of 1 KiB always make a report. The receiver's rule is unchanged.
- **A step to or from Coded forgets the drain as well as the round trip, and probes** (`FeedWindow.forgetLink(drain
  = true)`, from `FramedLink.forgetDrain(newAir)`). The filter keeps the largest of its samples, so a 1M rate kept past
  a step to Coded sized the window for 1M over Coded's longer round trip. The probe holds one chunk's room until a
  chunk written after the step comes back, so the round trip reads the new air, not the backlog 1M left, and the drain
  is sampled afresh from there. Until the receiver has reported all 1M wrote, the silence waits three chunks' longest
  crossing (about 10 s): 1M's chunks, 5–7 KB at 20–29 KB/s, take 4.6–6.5 s each to cross Coded, and a 3 s silence
  paced on every one of them. Only a chunk written with at most one smallest chunk ahead ends a probe, so a paced chunk
  that waited behind a backlog cannot set the round trip (both the bluetooth-reviewer's second pass). In the
  simulation, from 8 and 20 KB/s down to 1.1: with the drain kept, 8 s and 62 s of queue still stood 20 to 50 s after
  the step, and from 20 KB/s the feed paced on silence; forgotten, 2.7 s, at most 5.5 and 10.9 s across the step (most
  of it the backlog 1M left, which drains at Coded's rate whatever the window does), no silence, and the round trip
  read 1.0 s within 20 s. A step between 1M and 2M (the stack takes 2M on its own) forgets the round trip alone, as
  before: the filter follows a halving by itself (the bluetooth-reviewer's finding).
- **At the far edge a chunk's own crossing sets the silence and the two-second gap** (`FeedWindow.recordsMs`). A
  receiver reports a chunk only once all of it has crossed, and under about 0.7 KB/s a 1 KiB chunk outlasts the 3 s
  silence, under 0.54 KB/s two outlast the 1.9 s gap: every chunk tripped the paced fallback, and a feed latched as a
  two-second reporter (a dozing receiver reading two chunks a wake, a phone in a pocket at range) could never answer on
  time, so it stayed on the 1 KiB/s pace that overfeeds that drain (the bluetooth-reviewer's finding). The silence is
  now at least three chunks' time at the measured rate, the gap two, each chunk counted at most 3.4 s (a 1 KiB chunk at
  0.3 KB/s, `MAX_CHUNK_CROSSING_MS`): one low sample, a link that nearly stalled for a moment, otherwise held a
  receiver whose reports stopped unpaced for minutes (the second pass). On 1M and 2M the constants stand at any drain
  over about 2 KB/s.
- **`minChunk` is held to 1–8 KiB:** past `FILE_CHUNK_BYTES` ÷ 2 the starting chunk would pass the record's ceiling.

**The simulation** (`FeedWindowTest`, its receiver now optionally holding a chunk only once all of it crossed, and
reporting only on a record when dark; reverse lag 150 ms):

| Drain | Throughput | Queue, Coded's 1 KiB floor | Queue, 1M's 2 KiB floor | Fixed 1 KiB/s pace |
|---|---|---|---|---|
| 0.4 KB/s | 100–108% | 5.0–5.5 s | | grows all file |
| 0.8 KB/s | 99% | 3.5–3.7 s | 4.8–5.0 s | grows all file |
| 1.1 KB/s | 99% | 2.5–2.7 s (start 3.7) | 3.4–3.6 s (start 7.4) | about 1.9 s, 93% of the drain |
| 2.7 KB/s | 98–100% | 1.0–1.1 s | 2.0–2.2 s | under 0.8 s, 38% of the drain |

A lit receiver, whose trailing report fires between records, reads the drain high on Coded's whole-record steps: at
2.7 KB/s its queue reached 2.2–2.5 s, at 94–97% of the drain.

**What it costs.**

- **Paced at the far edge:** while a feed is paced (a silent receiver, or one latched), Coded's fallback pace, 1 KiB/s,
  runs over a drain under it; the scaled gap lets a latched feed answer and win the window back within three chunks.
  A file's first chunk, sent before any rate is measured, still trips the 3 s silence once under about 0.7 KB/s.
  Capping the fallback at the measured drain was not taken: it would move 1M's dozing receivers too, whose pacing the
  dozing-receiver amendment measured on the phones.
- **Slower to give up on a receiver gone silent:** up to about 10 s, not 3, while a step's backlog is out or the drain
  reads under about 2 KB/s; the pace it falls back to would overfeed such a link anyway.
- **At the edge's 1.1 KB/s a text waits a little longer:** about 2.7 s, 3.7 s at a file's start, against about 1.9 s
  behind the fixed pace's one chunk. A smaller floor would need the receiver to report every record, more reverse air.
- **Reverse air:** at the edge about one report a second, where 2 KiB records brought one every two; near, about 1.3 a
  second, as before. A report is about 125 B of S=8 air back; whether that takes from the forward drain is unmeasured.
- **Not changed, left for after the trial:** `fileFeed` still answers nothing for a Coded link (nsr7's parked asks
  never wait behind a Coded feed), `expectedFileRate` answers Coded's pace, and the split and `feedingLoad` leave Coded
  out. With the window they could read the measured drain.

**Tests.** `FeedWindowTest.onCodedTheWindowRunsAtTheDrainWithAShortQueue`,
`codedsSmallerChunksKeepItsQueueShorterThanThe1mFloorWould`, `aStepToCodedMidFileStartsTheWindowOverAtTheSlowerAir`,
`aStepToCodedProbesUntilAChunkWrittenAfterItComesBack`, `aChunkThePaceSlippedInBehindTheBacklogDoesNotEndTheProbe`,
`atTheFarEdgeASlowChunkIsNoSilenceAndNoTwoSecondReporter`, `oneLowSampleCannotHoldASilentReceiverUnpacedForLong`,
`at1mDrainsTheSilenceAndTheTwoSecondGapAreTheConstants`, `theSmallestChunkSizesTheWindowsFloors`,
`aDozingReceiverOnCodedWinsItsWindowBackAtCodedsPace`, and `theRoundTripIsTheLinksAndOutlivesTheStreamUntilAPhyStep`
(a 1M ↔ 2M step keeps the drain);
`FramedLinkTest.aWindowedFeedOnCodedStartsInItsSmallerChunks`; `BleFeedPolicyTest`'s gate and Coded pace. Each new
rule was checked by removing it: the drain kept past a step, the probe, the probe's one-chunk-ahead rule, the
backlog's silence, the floor not passed to the window, the floors fixed at 1M's, the silence and the gap held to their
constants, and the crossing's cap each fail their test. The simulation's writer now takes the paced fallback as
`FramedLink` does, and the Coded runs assert it never did once steady.

**Desk trial, 2026-10-08 16:10–17:03** (e8458feb; the P9 → the P8 a metre apart, both `…debug.PHY --es mode coded`, the
link on Coded S=8 at −60 to −67 dBm, Wi-Fi Aware and the spool off, the P3, P7 and Moto paused, boost off, 200 KB random
files, `scripts/ble-drain-trial.py`):

| Cell | Drain, KB/s | Held | Texts behind it |
|---|---|---|---|
| Fixed 1 KiB/s pace (4 reps, dark and lit receiver) | 1.02–1.03 | 198–200 s | 0.6–3.9 s |
| Window, both dark, the P8 also linked to an iPhone | 1.79, 1.87 | 111, 117 s | 1.6–3.2 s |
| Window, both dark, the P8 linked to the P9 alone | 2.39, 2.61 | 80, 88 s | 1.6–4.8 s |
| Unbounded (`--ei codedPace 0`), the same | 2.47, 2.67 | 78, 84 s | 23–78 s |
| Unbounded, earlier the same hour | 3.54, 3.60 | 58, 59 s | 32–54 s |
| Window, receiver lit (its screen kept dozing) | 2.75 | 76 s | 1.6–3.1 s |

- **The window drains 97–98% of what the air gave an unbounded feed in the paired reps,** 2.3–2.6× the fixed pace,
  with texts on time where the unbounded feed held them a minute. The air itself moved between 2.4 and 3.6 KB/s within
  the hour. An iPhone the receiver also held cut the window to about half that, and the fixed pace not at all.
- **A longer queue gains nothing:** `--window-queue-ms` 750 drained 2.03 and 2.49 KB/s with texts up to 7.6 s, and 500
  drained 2.24. `QUEUE_MS` stays 250 on Coded.
- **The silence fallback fires one to four times a file,** each back at the next report 0.5–2.6 s later, at drains
  where three chunks take about a second: the link stalled for 3 s or more now and then, with the receiver dark or
  lit. Each costs one 2 KiB paced chunk.
- **A step mid-file** (the link held on 1M, both lit, boost earned at 15 ms and 20–31 KB/s, then `mode coded` 9.6 s
  in): 1M's backlog was all reported 5 s after the step with no silence while it crossed, and the window probed. The
  probe's first chunk then took about 7 s (the one silence of the step, 8.4 s after it), so the round trip read its
  3 s ceiling until the next probe brought it to 1.5 s (22 s after the step) and 0.9 s (28 s). The queue stayed under
  about 4 KiB, about a second of the drain; texts took 1.6–3.2 s, and 7.1 s for the one sent into the stall. In a
  rep where the link dropped and came back on 1M, the stepper's own step to Coded mid-resume probed in 445 ms with no
  silence.
- **One link drop:** at −63 dBm on Coded, 76 s into a file, the P8 lost its links to the P9 and to the iPhone at the
  same instant (both `eof`), 7 s after it entered Doze; the link came back on 1M after 55 s and the file resumed.

**Device checks still owed** (two of the P7, P8 and P9: the Pixel 3 answers a Coded ask with nothing).

- **Desk (done above; kept for a re-run):** both pinned `…debug.PHY --es mode coded`, `scripts/ble-drain-trial.py --feed window` against `--feed pace`,
  the receiver lit, then dark. Read the receiver's `rx … in` line, the texts, and the `bt file window rate=… rtt=…`
  line. Pass: the window drains at least what the pace does, texts within about 3 s (4 s at a file's start), and no
  `paced: receiver silent` once steady.
- **A step mid-file (done once above; the stall after the step wants a second rep):** a photo on a lit, boosted 1M
  link, then `--es mode coded`. Pass: at most one
  `bt file window paced: receiver silent` across the step, the queue `queued` ÷ rate implies under 12 s across it and
  3 s within 30 s, and `rtt=` under 3000 within about 10 s.
- **A walk** with the receiver dark in a pocket until the drain falls under 1 KB/s: no `paced: two-second reporter`
  without a later `back`, and texts behind the photo under about 5 s.
- **The experiment on at 1M** with a stack-driven 2M step mid-file: `bt file window rate=` does not drop to the
  fallback.

## Amendment 2026-10-09: an iPhone that reports is windowed

**What changed.** `BleFeedPolicy.windowed` no longer excludes an iPhone (`DoorbellPolicy.serves`): a file to any peer
carrying `CAP_BLOB_HAVE` is windowed, as item 2 kept for Android. This is the Android half of a paired change. The iOS
half reports on every chunk, as Android does, and meters its own feeds; the two ship in the same release (item 4's rule
that no phone reports every 2 s to a sender that windows it). The link boost keeps its iPhone exclusion.

**Against an iPhone build without that half.** Today's port reports every 2 s, so the window falls back as it does for
an older Android build: about 3 s waiting on the first 8 KiB, then `paced: two-second reporter` for the rest of the
stream, in 2 KiB chunks (four times the writes of the plain pace, not less throughput). `expectedFileRate` also scores
that feed as windowed, at its last drain, rather than as its share of the paced budget, so nsr7's park and ETA can
over-promise behind it. Both go away with the iOS half; neither is worth a gate of its own on a port with no installed
base.

**Device trial owed.** A P9 feeding a 410 KB photo to the iPhone on the companion build, lit and dark, once with a
second Android link feeding. Pass: the window holds (`back`, no `paced` past the start), the receiver's drain is at
least the paced base, texts behind it within 5 s. Then lock the iPhone mid-file. Pass: `paced: receiver silent`, the
link survives, and the file completes after the next ring.

## Amendment 2026-10-27: only a report that lands a chunk is heard

**What changed.** `FeedWindow.report` counts as hearing from the receiver (`heardAt`, which `pacing`'s silence counts
from) only when the report holds a chunk whole for the first time (`landed > 0`), and only such a report may reset the
drain filter on a standing queue or a slowed round trip. Everything else a report moves is unchanged.

**Why.** `blobprog` is unsigned and point to point, so any linked peer can send any `held`. A peer repeating one value,
or adding a byte to it, every 3 s or less kept the silence fresh and the window full: the feed waited on `awaitReport`
for the link's life, holding `txInProgress`, the stash behind it and the link boost (#149). Now it is paced after the
silence and the file finishes. A whole chunk, not just more bytes, because a byte-at-a-time trickle would still hold the
window shut. An honest report always lands one: Android (this build and 2.9.0) and iOS report only after a whole
`FILE_CHUNK` record landed and the bytes grew, and the sender writes one record per chunk, so an honest `held` always
ends on a chunk boundary. The one honest report that lands none races the sender's `wrote` after a returned write; its
ack was already lost, and its near-zero sample would have reset the drain on a standing queue.

**Deferred.** The slowest a peer can now hold a feed is one acknowledged chunk per silence, which is also how an honest
slow Coded receiver looks. Bounding a stream overall (#149's option 2, the meter's deadline times a small factor) is
left for its own change.

**Device trial owed.** A debug-bridge or knit-ios harness peer answering a photo's stream with a fixed
`blobprog held=0` every 1 s, a P9 sending. Pass: `bt file window paced: receiver silent` within about 3 s, and the
sender's `file <KIND>/<hash> … window` line completes.
