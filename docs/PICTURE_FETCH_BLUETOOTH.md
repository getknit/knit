# How a picture is fetched over Bluetooth

**Status:** describes `main` at `3890f16b` plus ADR 2026-10.nsr7's phase 1 (2026-10-07). Since 2026-10-08 the holder
query, the link boost and the feed window are on in release builds; [`BLUETOOTH_PLANE.md`](BLUETOOTH_PLANE.md) covers
the feed window, which replaces the budgets below for a peer that reports its progress. The decisions behind it:

- ADR 2026-09.4tx5: serve only a fresh ask.
- ADR 2026-10.f7gg: ask the likeliest holder first.
- ADR 2026-10.6e5t: the holder query.
- ADR 2026-10.8jwn: one pace budget, split.
- ADR 2026-10.2da4: the link boost.
- ADR 2026-10.g24x: the linked recipient goes first, and the holder feeds one copy at a time, plus its settling-pace
  amendment.
- ADR 2026-10.nsr7: the receiver reports its progress, and the holder times its parked asks by it.

This page explains the logic as it stands. The ADRs hold the evidence and the rejected options.

An attachment is a content-addressed blob. A chat frame names it by hash, and the bytes travel separately: a peer that
wants them asks a neighbour that holds them, and the holder streams the file down its link. Nothing is pushed. Over
Wi-Fi Aware a file rides a fast data path. Over Bluetooth every copy competes for a few KiB/s, so on Bluetooth the
mesh controls **who asks**, **who is served first**, and **how fast each link is fed**.

## The rules ADR g24x and nsr7 added

1. **A carrier leaves a DM's picture to its recipient.** A phone that custodies a DM for someone else (a carrier)
   normally pulls the attachment too, so a late joiner can still get it. When the DM's recipient is linked to the
   carrier, the carrier waits up to 10 min instead. The recipient is fetching the picture right now, and a carrier
   pulling beside it is one more copy the author has to feed at the same time.
2. **The author's HAVE decides the fetch.** A receiver asks its neighbours who holds the blob. When the author answers
   HAVE, the receiver asks the author at once, without waiting for the other answers, unless a neighbour still to
   answer could offer a better plane.
3. **A holder feeds one Bluetooth copy of a blob at a time.** A second ask for a blob that is already streaming to
   someone else is parked, however long that feed takes. It is served when the feed ends, stalls (15 s with no
   progress) or runs past its deadline (its first measured ETA × 1.25 + 30 s). The fastest waiting asker goes next. An
   asker whose own copy would end before the feed does, and would save itself more than it costs the feeds it splits
   the budget with, goes beside it.
4. **Each link earns its own budget.** A link that has earned the boost feeds at the boosted budget, and one that has
   not feeds at the base. One slow link, such as an iPhone's, no longer holds every other link to the base.
5. **The receiver reports its progress.** Every 2 s of chunks, a file's receiver sends its sender a `blobprog`: the
   bytes it holds and the ms since the header, by its own clock. Not to an iPhone yet: its port does not take the type. That is what the sender's ETA, stall and deadline are
   measured from, because the Bluetooth stack's queue hides the drain from the sender.

## A DM photo to a linked recipient

Alice sends Bob a photo. Bob and Carol are both linked to Alice and to each other. Carol carries the DM, but it is
not for her.

```mermaid
sequenceDiagram
    autonumber
    participant A as Alice (author)
    participant B as Bob (recipient)
    participant C as Carol (carrier)

    A->>B: chat frame naming the blob hash
    A->>C: the same frame, flooded
    Note over C: custodies the frame (onCarriedFrame)<br/>CarrierPullPolicy.defers: a DM, Bob linked,<br/>carried under 10 min. Pull held back<br/>(carrierPullsDeferred +1)
    Note over B: wants the blob (BlobExchange.want)
    par holder query
        B->>A: blobhave [hash]
    and
        B->>C: blobhave [hash]
    end
    A-->>B: blobhas HAVE (size, load)
    Note over B: answersIn: the author said HAVE and no<br/>neighbour still to answer rides a better plane,<br/>so ask now without waiting for Carol
    B->>A: blobreq [hash]
    C-->>B: blobhas NONE (arrives later, changes nothing)
    Note over A: onRequest, then serveOrWant:<br/>not in flight to Bob, no other feed to park<br/>behind, not in the serve memo: serve
    A->>A: composite file route: no live Wi-Fi Aware link,<br/>no arm, so the Bluetooth link holder
    A->>B: FILE_HEADER, then FILE_CHUNKs paced, then FILE_END
    Note over A,B: the screen-lit side asks CONNECTION_PRIORITY_HIGH,<br/>the link reports 15 ms or less: earned,<br/>so the feed goes at 18 KiB/s
    Note over B: rx … in ~24 s for 410 KB, stored after screening

    loop every 60 s tick and every link-up
        Note over C: rewantMissingBlobs: still held back<br/>while Bob is linked and the frame is under 10 min old
    end
    Note over C: Bob's link goes, or 10 min pass
    C->>A: blobhave, then blobreq (or to Bob, who now holds it too)
    A-->>C: one copy, later, for a late joiner
```

If Bob is linked to Carol but not to Alice, Bob's own ask reaches Carol. `onRequest` on Carol then pulls the blob
through on Bob's behalf, and the hold never applies to that pull.

## A group photo several members want

Alice posts a photo to a group. Bob and Carol are both linked to her alone. Both get her HAVE within milliseconds and
ask her within milliseconds of each other.

```mermaid
sequenceDiagram
    autonumber
    participant B as Bob
    participant A as Alice (holder)
    participant L as Alice's link to Bob (FramedLink)
    participant C as Carol

    B->>A: blobreq [hash]
    A->>L: sendFile: queued, the writer takes it
    Note over L: first chunk paced at the base (not earned yet)<br/>boost asked, txPaced = 1
    C->>A: blobreq [hash] (about 50 ms later)
    Note over A: parkBehindFeed: Carol's copy would share the<br/>Bluetooth budget, fileFeed(Bob, hash) is live, and<br/>her copy would not end before Bob's does
    A->>A: park Carol's ask (blobAsksParked +1)
    C->>A: blobhave [hash]
    A-->>C: blobhas SENDING, eta = Bob's feed's ETA + her own copy
    Note over C: holds on Alice until that eta + 30 s,<br/>rather than ask anyone else
    Note over L: the link earns the boost about 0.6 s in, and the<br/>second chunk is paced at 18 KiB/s
    loop every 2 s of chunks
        B->>A: blobprog (held, ms since the header)
        A->>L: onProgress: measured rate, ETA, deadline
    end
    L->>A: onFeedChanged at the second chunk (settled) and the first report (measured): re-read, Carol stays parked
    loop park poll, 1–10 s (the net under the edge)
        A->>L: feed? live (moving, inside its deadline), so Carol stays parked
    end
    L-->>B: FILE_END
    L->>A: onFeedChanged, then fileFeedsChanged, then feedsChanged()
    A->>A: serveParked: no live copy fed elsewhere now,<br/>so unpark the fastest parked asker, Carol
    A->>C: one copy, at the full budget
```

Because a parked ask reads SENDING, Carol's holder query does not move on to another holder while she waits. Once Bob
holds the photo, he is a second source for anyone else.

With three askers, the third waits behind the second feed in turn. An iPhone's feed, which never earns the boost, is
the case the "beside" rule is for. Behind its 100 s copy, an Android asker whose 33 s copy would end first goes beside
it, and every slower asker still waits. It goes only when what it saves beats what the running feeds lose: while its
copy runs, each of them gets a smaller share and ends later.

## A holder's handling of one ask

```mermaid
stateDiagram-v2
    [*] --> Received: blobreq from a neighbour
    Received --> Wanted: not held, so want it (pull through for the asker)
    Received --> Ignored: a copy already queued or streaming to this peer (fileInFlightTo)
    Received --> Parked: parkBehindFeed true
    Received --> Ignored: served recently to this peer (serve memo, 45 s)
    Received --> Serving: otherwise, sendFile

    Received --> ServingBeside: a live feed ahead, but its own copy would end first, for less than it saves
    Parked --> Parked: a re-ask or a query renews it, and its query reads SENDING with an eta
    Parked --> Serving: feed ended, stalled or past its deadline (edge or poll), fastest first
    Parked --> ServingBeside: on a feed change, its own copy would now end first, for less than it saves
    Parked --> Dropped: asker unlinked, or not renewed for 45 s past what its eta promised
    Serving --> [*]
    ServingBeside --> [*]
    Dropped --> [*]
    Ignored --> [*]
    Wanted --> [*]
```

A fresh ask is parked only when all of the following hold (`parkBehindFeed`):

- **A session to serve it later.** There is a live timer scope.
- **Not already covered.** The serve memo does not already cover the ask.
- **The copy rides the shared Bluetooth budget** (`ridesSharedBudget`):
  - **Live Wi-Fi Aware link:** never parked.
  - **Wi-Fi Aware reachable but not linked:** parked only below 128 KiB on a link that isn't slow. A larger blob is
    one the composite would raise a Wi-Fi Aware link for.
  - **Bluetooth only:** parked only if the link is not slow (Coded).
- **Another neighbour is being fed the same blob live** (`liveFeeds`, which calls `fileFeed`), and the asker's own copy,
  at the rate its link is expected to take, would not end before every live feed does (`waitsBehindFeed`). The rate
  (`expectedFileRate`) is its share of the pace, boosted only when the link will earn the boost (it earned within
  5 min, or this side is lit), times the shortfall its last measured stream showed against its pace (`drainRatio`).

```mermaid
flowchart TD
    Q["fileFeed(peer, hash)"] --> K{"link on Coded?"}
    K -- yes --> N["null: Coded pace sits outside the split"]
    K -- no --> P{"hash queued or streaming on this link?"}
    P -- no --> N2["null"]
    P -- "queued, nothing streaming" --> T1["live, no ETA yet: the writer is about to take it"]
    P -- "streaming it" --> M{"a progress report measured a rate?"}
    M -- yes --> RM["ETA = bytes the receiver lacks ÷ measured rate"]
    M -- no --> S{"fewer than 2 chunks paced?"}
    S -- yes --> R1["ETA by max(pace, settling pace)"]
    S -- no --> R2["ETA by pace"]
    RM --> D["deadline, set once: first measured ETA × 1.25 + 30 s;<br/>moved back if the sender's own split cuts the pace"]
    R2 --> D2["no report on this stream for 15 s: deadline from the pace"]
    D --> L{"stalled 15 s, or past the deadline?"}
    D2 --> L
    R1 --> L
    L -- yes --> NL["not live: holds nobody<br/>(never while the writer waits on its own pace)"]
    L -- no --> LV["live: the asker parks unless its own copy ends first"]
```

The **settling pace** is the share the link will feed at once it has earned the boost. It is nonzero only while the
link has a boost that can earn, which means all of these:

- an Android peer (an iPhone link has no boost);
- `BLE_LINK_BOOST` and `LinkTuneDiag.boostOn` on;
- the link's tuner has not given up;
- no Bluetooth audio is streaming.

It lasts until the stream's second chunk is paced, and stands only until the first progress report measures a rate. It is counted in chunks, not time, because that pace is read after a
`delay`, which stops while the CPU sleeps. This covers the moment both receivers ask the author together: the first
chunk is paced before the link has answered the boost, so a 245 KB photo would otherwise read as a minute from its end
and the second ask would be fed in parallel.

## A carrier's pull decision

```mermaid
stateDiagram-v2
    [*] --> Carried: custodied a chat frame naming a blob we lack
    Carried --> Skipped: carrier blob budget full
    Carried --> Copied: a plaintext we already hold (ADR mptm)
    Carried --> Deferred: every frame naming it is a DM, its recipient linked, carried under 10 min
    Carried --> Pulling: otherwise (group, room, or recipient not linked)

    Deferred --> Deferred: 60 s tick or link-up re-reads custody (heldForLinkedRecipients)
    Deferred --> Pulling: recipient unlinked, or 10 min passed
    Deferred --> Pulling: the recipient asks us (onRequest pulls it through)
    Deferred --> Purged: a cleartext receipt purges the frame

    Pulling --> Held: want, then holder query, then fetch, then stored after screening
    Held --> [*]
    Purged --> [*]
    Skipped --> [*]
    Copied --> [*]
```

A sealed receipt is opaque to a carrier and purges nothing, so for an Android DM the 10 min window or the unlink is
what ends the hold. A recipient seen only over LoRa never holds anything back, because the hold reads the live
neighbour set.

## How a receiver picks whom to ask

```mermaid
stateDiagram-v2
    [*] --> Querying: want(hash), blobhave to every neighbour that carries CAP_BLOB_HAVE
    Querying --> Asking: the best so far rides a live Wi-Fi Aware link
    Querying --> Asking: the best so far is the author, no unanswered neighbour on a better plane
    Querying --> Asking: every answer is in, best by plane then load
    Querying --> Round: no one capable, f7gg's round (author, then Wi-Fi Aware, then reachable, then source hop)
    Asking --> Arriving: FILE_HEADER in (fileArrivingFrom, arrivingFiles)
    Asking --> Confirming: silent past the grace, re-query that holder
    Confirming --> Asking: still HAVE or SENDING, held to its ask's floor or a SENDING eta + 30 s, whichever is later
    Confirming --> Querying: NONE or link gone, move on
    Arriving --> [*]: stored, then fileTaken
    Round --> Arriving
```

The plane tiers, best first: fast link (Wi-Fi Aware linked), fast reach (Wi-Fi Aware reachable), Bluetooth link, slow
link (Coded), sending, blind, held.

**A large attachment is not fetched over Bluetooth until the user taps it** (ADR 2026-10.js2p). One its sender
declared over the user's "Ask before large Bluetooth downloads" limit (1 MB by default) is wanted *fast-only*: the
diagram above runs over the neighbours on the two Wi-Fi Aware tiers alone, every ask says `fastOnly`, and with neither
in reach nobody is queried or asked — no blind round, no pull-through over Bluetooth. A cycle that asked a reachable holder
and still wants the bytes backs off as a dead one does, until a Wi-Fi Aware link comes up. A holder serves such an ask over a
live Wi-Fi Aware link or one it raises, and otherwise drops it rather than feed it to Bluetooth. "Tap to download" wants
it plain, and from there it is fetched as this page describes. A room post declares its size too (ADR 2026-10.hmfe: an
attachment of 1 MB or more says so in the clear `ChatContent`), so a large room picture or video is held the same
way — by custody's want, which runs ahead of the delivery's, and by the tick's re-arm of a carried room post that no
row names yet, both judged by the size the post declares. A post that declares none (an older build, the iOS port) is
never held.

**A pull-through carries a hop budget and a deadline** (#195, ADR 2026-10.2drc). When no neighbour holds the blob, the
want asks the ones that lack it to pull it through, and each of them wants it in turn. Every ask says how many phones
deep it may still go: 3 from a want of our own, one less from each phone that echoes it, and at 0 a phone only queries
and fetches. So a holder four links away is still reached, and the echoes of one want stay within three rings. An ask
keeps a want alive only when it carries at least as much budget as the want itself, so echoes cannot keep each other
alive, or their root once it stops. Every ask also carries a deadline: for a DM's picture, the DM's custody window, the
24 h in which the mesh carries the DM itself. Past it nobody pulls the picture through or wants it for someone else. A
pull never needed a carrier, so the cost is a far end two or more hops away, reached again once the two ends link
directly. An older build's ask, and an iPhone's today, carry neither field: here they only ever make a fetch, and never
keep one alive. The iPhone itself still echoes without bound until its port carries the fields. Past two
cycles without a lead, a cycle that asked an iPhone blind and brought nothing backs off too. A want dead for a day, or
past its own deadline from its fifth dead cycle, backs off from 6 min into hours, unless it is fast-only.

## How fast a link is fed

The pace of a Bluetooth file is per link, read before every chunk (`BluetoothMeshTransport.paceFor`):

```text
budget(link) = 18 KiB/s  if the link has earned the boost and the phone has ≤ 4 LE connections
               12 KiB/s  if it has earned it with more than 4
                4 KiB/s  otherwise (the base)
share(link)  = budget(link) / number of links feeding a file now (1M/2M only)
chunk        = 2 s of the share, capped at 16 KiB
```

A Coded link keeps its own pace outside the split. A link to a peer that reports its progress is clocked by the feed
window instead (ADR 2026-10.5buf), and falls back to this pace when the receiver goes silent for 3 s. A link's boost
works like this:

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Asked: a file starts either way and this screen is lit, ask HIGH (and MTU 517 once)
    Idle --> Listening: a file starts and this screen is dark, attach and only listen
    Asked --> Earned: the link reports an interval ≤ 15 ms
    Listening --> Earned: the peer's ask lands, reported ≤ 15 ms
    Earned --> Unearned: a slower interval is reported
    Unearned --> Earned: ≤ 15 ms again
    Asked --> Released: 15 s with nothing streaming either way, or screen off
    Earned --> Released: 15 s with nothing streaming either way, or screen off
    Unearned --> Released: 15 s with nothing streaming either way, or screen off
    Released --> Idle
```

Earning takes about 0.6 s on the lab Pixels, and the pace a feed reports moves at its next chunk, about 2 s after the
first. An iPhone link never boosts (its doorbell owns the parameters). Release builds ship with `BLE_LINK_BOOST` on since
2026-10-08.

## Bounds and costs

| Bound | Value | What it costs |
|---|---|---|
| Carrier hold (`CarrierPullPolicy.DEFER_MS`) | 10 min | A carrier linked to the recipient takes its own copy up to 10 min late. Every held pull lapses at once afterwards. |
| Progress report (`PROGRESS_EVERY_MS`) | 2 s | One small `blobprog` per incoming file every 2 s of chunks. A stall sends nothing. |
| Stall (`FeedMeter.STALL_MS`) | 15 s | A feed with no progress this long holds nobody: no report moved, or, before this stream's first report, no write went through. Never while the writer waits on its own pace. |
| Deadline | first ETA × 1.25 + 30 s | Set once, moved back if the sender's own split cuts the pace or its pace wait overslept. A feed past it holds nobody. An 8 MiB file at 18 KiB/s gets about 10 min. |
| Park poll | 1–10 s | Wakes when the soonest stall or deadline is due, at least every 10 s; a feed's end is served on its edge. |
| Asker's hold on a SENDING eta (`HOLD_SLACK_MS`, `HOLD_CAP_MS`) | eta + 30 s, at most 15 min | A holder that lies about its eta can hold an asker for up to 15 min. |
| Settle (`SETTLE_CHUNKS`) | 2 chunks | A link that never earns holds a parked ask for about one base chunk (2 s of feeding) on the guess. |
| Parked blobs (`MAX_PARKED_BLOBS`) | 64 | Past it, an ask is served rather than parked. |
| Serve memo (`SERVE_MEMO_MS`) | 45 s | A parked ask not renewed (by an ask or a query) for this long past its eta's promise is dropped. |

## Device results (2026-10-06, Wi-Fi Aware off)

- **DM, lit P3 to P7, with P8 and P9 as carriers:** five reps, each held in 27–31 s. Each was one copy at 18 KiB/s
  (23.8 s for 410 KB), and both carriers deferred.
- **Group, P3 the author, with the P9, P7 and P8 asking:** the later asks parked at once and stayed parked across the
  polls. Each `blob unpark` landed 3–4 ms after the previous feed's `file … in`. The third asker reached its 30 s
  bound mid-second-feed.
- **Dark P8 author on the chunk-counted build (before nsr7):**
  - **Lit asker first:** the next ask is unparked 7 ms after the feed ends.
  - **First copy to a dark pair:** the parked asks are released 2–3 s after their park.
  - **Boost off:** nothing parks.
  - **The open cost:** a dark pair's base-pace feed beside a lit one slows the lit copy from about 17 s to about 48 s.
    And a non-member carrier pulls a group photo at once, often first. Since ADR 2026-10.hr9f a non-member is also
    back-filled a group's frames by any holder, and pulls their photos at once when the author is not linked to it
    (`CarrierPullPolicy` holds the pull only while the author is), behind the members (nsr7) and inside
    `CARRIER_BLOB_BUDGET_BYTES`.
- **The iPhone shape, before nsr7:** a DM from the dark P7 to the lit P3 passed 5/5 at 35–40 s. A group photo the
  iPhone also asked for was fed four ways at once and took 138 s. That run is what nsr7 fixes; its device cells are
  owed.

## Where it lives

| Piece | Code |
|---|---|
| Carrier hold | `mesh/CarrierPullPolicy`, `InboundPipeline.onCarriedFrame`, `MeshManager.heldForLinkedRecipients`, `ForwardStore.carriedAttachments` |
| Query, choice and asks | `BlobExchange.want`, `onHaveQuery`, `onHaveAnswer`, `decide`, `answersIn` |
| Serve and park | `BlobExchange.onRequest`, `serveOrWant`, `parkBehindFeed`, `waitsBehindFeed`, `serveParked`, `feedsChanged`, `etaFor` |
| Is a copy being fed | `MeshTransport.fileFeed` / `expectedFileRate`, `BluetoothMeshTransport.fileFeed` / `boostedShare`, `FramedLink.feed`, `FeedMeter` |
| Progress reports | `FramedLink.onRxProgress` → `MeshTransport.fileProgress` → `BlobExchange.onFileProgress` → `blobprog` → `onProgressReport` → `FramedLink.onProgress` |
| Feed-change edge | `FramedLink.onFeedChanged`, then `MeshTransport.fileFeedsChanged`, then `MeshManager`'s collector |
| Pace and boost | `BluetoothMeshTransport.paceFor`, `CodedPhyPolicy.pace` / `budget`, `BleLinkBoost`, `LinkBoostPolicy` |
| File route | `CompositeMeshTransport.sendFile` (Wi-Fi Aware live link, then arm at 128 KiB or more, then Bluetooth) |
| Counters | `MeshMetrics.files()`: `blobAsksParked`, `blobProgressSent`, `blobProgressTaken`, `carrierPullsDeferred`; the debug bridge's `STATE` |
| Lab | `PictureFanoutLabTest`, `AttachmentLabTest`, `BlobHaveLabTest`, `BlobFirstAskLabTest` |
