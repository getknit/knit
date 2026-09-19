# Testing & verifying changes

## Verification ladder

1. `./gradlew :app:testDebugUnitTest` for mesh/protocol/data logic — now including **Robolectric +
   in-memory Room** tests that execute the real DAO SQL (see below), and the **mesh-in-a-box** scenarios
   (`mesh/lab/`, below) that run several complete real stacks against each other in one JVM.
2. Emulator smoke test for UI/startup (launch, Koin init, screen rendering, no crash) — the app
   runs fine on an emulator, and it can even join the *real* BLE mesh there if you pass a USB Bluetooth
   dongle through to it (see **Real BLE from an emulator** below); its Bluetooth is otherwise a simulation
   that no physical phone can reach.
3. Two physical phones for discovery → connect → relay and profile/avatar exchange.
4. **Seeded UI instrumentation suite** (`app/src/androidTest/…/ui/`) for populated-screen rendering across
   devices/API levels — locally on an emulator (`:app:connectedDebugAndroidTest -PseedDemo=true`) or on
   Firebase Test Lab physical devices. See below. A **black-box UIAutomator** twin
   (`…/uiauto/`) covers the system shade + process lifecycle — see below.
5. **Accessibility (ATF) suite** (`app/src/androidTest/…/a11y/`) runs Google's Accessibility Test Framework —
   the same checks the Play Console pre-launch report runs — on API 34+. See below.

> Wi-Fi Aware needs physical devices — an emulator can't do NAN, whatever you do about Bluetooth. Use
> `FakeLoopTransport` for logic tests and two physical Wi-Fi-Aware-capable phones (e.g. Pixels) for real
> discovery → data path → relay.

> **`UncaughtExceptionsBeforeTest` names the wrong test.** kotlinx-coroutines-test's `ExceptionCollector`
> starts catching *every* uncaught coroutine exception in the JVM the first time any `runTest` runs, never
> turns off, and hands what it caught to the *next* `runTest` — so a Robolectric test whose service or fake
> throws inside an unhandled `launch` (a Koin definition its module never provided, resumed on the
> `Dispatchers.Unconfined` scope) fails whichever `runTest` class the fork runs after it, and only when a
> `runTest` ran *before* it. A single-class run is green; CI's one fork is not. Read the failure's
> **Suppressed:** cause for the real origin (the HTML report keeps it; the console line does not), and
> reproduce with three classes: any `runTest` class, the suspect, the victim (the `ImageScreeningServiceTest`
> case of 2026-09-19 was `MeshServiceForegroundReclaimTest` raising `neighborCount` without an
> `MlTextModerator`).

## Mesh in a box (`app/src/test/…/mesh/lab/`)

`MeshLab` runs **N complete, real Knit stacks in one JVM** — the real `MeshManager` (so the real
`InboundPipeline`, `MeshRouter`, `ForwardSync`, `KeyExchange`, `AckSync`, both ratchets), the Room-backed
repositories over Robolectric's in-memory SQLite, a real `IdentityKeyStore` over an in-memory secret, a
DataStore-backed `SettingsStore` — linked through `LabTransport`, an in-process `MeshTransport` with no radio.
Bare, it is a link plane with no fast plane (one flood copy per link); with `pages = LabPages()` it is the
Bluetooth plane as shipped, side channel included (below). Either way a pipe carries a frame once, through
the same `LinkCrossings` memo the phone keeps (`dupSkipped` on the transport lists what it refused).
The only doubles are the leaves with a hardware or UI side (notifier, tflite text moderators, image moderator).
The wiring mirrors `di/AppModule` + `di/MeshModule` by hand, so a constructor change is a compile error here
rather than a silently narrower rig.

It exists for the bug that lives **between** two nodes — A's real send order meeting B's real state — which
neither `MeshManagerTest` (real sender, recording transport, mocked repos) nor `InboundPipelineTest` (real
receiver, hand-built frames, mocked repos) can see, because each half is checked against a stand-in for the
other. The founding case is cf94a06 (seed-before-roster: the creator floods a group's sender-key seed before
the frame that carries its roster, and the member's DM ratchet consumed it for good): `GroupFirstMessageLabTest`
fails on the fix's parent commit with the bug's exact signature (creator has the message, members don't) and
passes on the fix — verified 2026-09-11. Its first run also found a real router bug: a **second copy of a frame
from the same neighbor** counted as an overhear and cancelled the relay of a newcomer's profile past the first
hop (fixed in `MeshRouter.countOverheard`, pinned by `MeshRouterTest`).

- **Write a scenario at user level and end it in the oracle.** `node("alice")`, `link`/`linkAll`/`unlink`,
  `awaitAcquainted`, `sendDm`/`createGroup`/`sendGroup`, then `assertConverged(nodes, atLeast) { thread }`,
  four checks in order, each awaited then asserted with a per-node listing: **messages** (same decrypted set
  on every node, none on `pendingKey`), **ticks** (every message a node authored is acked by every other
  node — the ADR 018 sealed receipt, a second protocol under every message), **custody** (`liveFingerprint`
  parity across the nodes plus any `carriers = listOf(bob)` that relayed but are not party to the thread — the
  soak oracle for the "digests diverge, NAN churns" class), and no parked seed that never replayed. The
  oracle is what catches the bug the scenario's author did not think to assert; add a per-node invariant
  there, not in individual scenarios. `createGroup` mirrors `ContactsViewModel.createGroup` (the ViewModel
  needs a Main dispatcher) — if that body changes, change both.
- **The group-tick debounce is shortened, not bypassed.** A group tick toward an absent author batches 45 s
  before escalating into custody (`AckSync.TICK_BATCH_DEBOUNCE_MS`); a tick crossing a relay would never
  land inside the await, so `MeshManager` takes `tickDebounceMs` and the lab passes 300 ms. Same path, same
  frames — only the policy number moves. Production wiring takes the default.
- **`CustodyLabTest` is the store-and-forward set:** a DM and a whole new group (seed + roster) reaching a
  member who was away, through a carrier the sender has since left; and a partition where both sides send
  (and one founds a group) before the merge.
- **`RoomFloodLabTest` is the Sybil-flood set:** a stranger's twenty room posts against a node whose room keeps
  ten (the sweep keeps a contact's and the user's own, trims the stranger to the per-stranger cap), and a line
  Mallory–Alice–Bob where Alice's per-link `IngressBudget` is five (Bob holds the same five, Alice's
  `INGRESS_REFUSED` counter reads fifteen, Bob's own post still crosses). `LabLimits` shrinks the policy numbers
  per node — same rules, same paths. Two traps: a **room** tick toward an author who is not a live neighbor
  never escalates into custody (ADR 2026-09.aa27) and, over links alone, has nowhere else to go — its ride
  deadline (ADR 2026-09.y5f3) sends it over a spool or a fast plane, and a `LabTransport` line has neither
  (the side channel's pages do not change this: a tick is DM-form and `BleFastRoutePolicy.send` never pages
  one) — so a link-only room scenario that ends in the full oracle still needs a triangle, not a line, or its
  tick check waits forever; and the sweep runs at boot and on the 10-min loop, so a scenario calls
  `node.sweepLocalStorage()` itself.
- **`RoomTickPlanesLabTest` is the long-range set** (ADR 2026-09.y5f3): a node can boot with a board on a
  shared `FakeMeshtasticAir` (`lab.node(name, air = air)` — the **real** `LoraMeshTransport`, composed with
  the radio through the real `CompositeMeshTransport`, so the covers, the election inputs and the fast-plane
  dispatch are production's) and with a relay (`spool = FakeSpool()` — the real `ScopeSync` over the
  in-process spool; the node opts in through the same two settings the relay editor writes). Three
  scenarios: a room tick to an author only the board can hear goes out over the board once the ride hold
  (`LabLimits.rideHoldMs`, 300 ms by default) runs out; a room tick rides the instant receipt for a DM that
  came off the relay, with the DM's LoRa copy never sent (`loraSkippedInternet`); and a room tick with no
  ride is pushed straight into the relay, custodied nowhere on the acker (`receiptsSpooled`, the scope's
  `accountedCount`), with a re-link before the oracle because the author custodies what it pulled and the
  acker never does — one ordinary re-serve, not a second send. Traps the suite hit on the way in: presence
  is stamped only on a frame the spool *pulls*, and both nodes already hold each other's link-phase frames,
  so `meetOnTheRelay` DMs once more with the air made lossy (`air.lossy`) — the relay is then the only path;
  a both-initiate DM race can leave a responder's session unconfirmed for a long time, so the fixture waits
  for the first DM to land before the reply; the DM scope derives on the 15 s reconcile and a worker that
  missed an event waits for its own 60 s tick, hence `MeshLab.SPOOL_AWAIT_MS`; and the oracle found a real
  divergence — the LoRa beacon re-signing the node's own profile under the same id while a settings write
  was landing (two blobs, one id, a scope that never converges; `MeshManager.ownProfile()` is the fix).
  `awaitDmScope`'s failure message lists both custodies row by row for the next one of those.
- **`SideChannelLabTest` is the BLE side-channel set** (ADR 2026-09.sjaa, 2026-09-17): `lab.node(name, pages =
  pages)` puts the node's radio on a shared `LabPages` — one radio range, joined the way a board joins a
  `FakeMeshtasticAir` — and `LabTransport` then declares `hasFastPlane` and routes `fastFanout`/`fastSend`
  through the **real** `BleFastRoutePolicy`: the link copy to every linked peer over the same held/lossy pipe
  as the flood, plus a page every other member hears, run through the real `FastFrameCodec` (size-gated at
  three `PAGE_BYTES` pages, `pages.tooBig` for the rest) and delivered with `fromNodeId` = the **author**;
  `fastSend` is the pipe only. Four scenarios: a line where a node two hops out hears the author's page and
  the neighbour's link copy as two hop ids (so `countOverheard` can cancel its relay, and the fast path's
  link copy — asserted `via=fast` in `sent` — is what still reaches a deaf far end; the relay race itself is
  the router's jitter and not asserted); an acquainted node with no link at all hearing the room from the
  page alone; a stranger on the pages parking the post for a key and replaying it when its first link brings
  one; and the negative space — a DM never pages, an oversized post rides the links. Three things the pages
  taught: **a page bypasses `hold`** (the point of the channel — a scenario that wants a frame stranded on a
  paged node makes `pages.lossy` eat it too); **pages do not converge custody** — DM-form frames (a room
  tick among them) ride links only, so a linkless listener holds one row fewer than the clique until a link's
  digest exchange heals it, and a page scenario re-links before the full oracle exactly as
  `RoomTickPlanesLabTest` does; and **a page-first hearing turns the relay around** — the hop is unknown, so
  the router relays to every link but the author's, back over the link the copy would have come by (SeenSet
  absorbs it; an airtime cost on a paged clique, not a divergence). `heardOnPages` per transport,
  `pages.aired` / `tooBig` / `missed` are the diagnostics.
- **The oracle has seven parts now** (2026-09-14): messages, then reactions parity (the same (reactor, emoji)
  set under every converged message), attachment parity (the bytes held, content-equal, unflagged, on every
  node), group parity for a group thread (roster, departed, name, photo — never `nameUpdatedAt`, which the
  renamer stamps from the wall clock and everyone else from the frame), ticks, custody, profile parity (every
  node's `PeerEntity` for a peer against that peer's own settings and profile version), session parity (a
  pair holding a DM session on both sides holds *one*: both confirmed, equal root and era, opposite roles),
  and the per-store invariants (no parked seed or parked-for-key frame that never replayed, no self row, no
  custody row a sender addressed to themselves). A node whose message set legitimately differs from the
  parties' — a leaver, a blocker, an author two hops from a room's readers — rides as a `carriers = listOf(…)`
  entry: it takes part in the custody and per-store checks and nothing else.
- **`LabNode` mirrors the ViewModels' writes** for the ops the first batch lacked: `react` (`ChatViewModel.react`),
  `leaveGroup` / `renameGroup` / `setGroupPhoto` (`GroupDetailsViewModel`), `setStatus` / `setOpenToChat` /
  `setAvatar` (`ProfileViewModel`), `block` / `unblock` / `accept`, `sendImage` (bytes straight into the
  blob store under their hash — `AttachmentStore.ingest` collapses every picture into one placeholder hash
  under Robolectric's legacy graphics), `mintCard` / `importCard` (`ContactCards` + `ContactImporter`),
  `resetSession`, `heal()` (runs the heartbeat basket and returns once it has run to its end — `healsCompleted`), `sweepExpired()` (the TTL sweep the prune
  loop and the heartbeat run — custody, parked frames, key and blob wants — awaited, for after a clock jump),
  `wipeCustody`. Readers beside them:
  `reactions`, `group` / `groupShape`, `attachmentHash` / `attachmentHeld` / `attachmentScreened` /
  `attachmentPlain`, `peer` / `presentationOf`, `session`, `selfAddressedCustody`, `notices`, `rowsIn`,
  `custodiedChatsFrom`, `scopeStatus`, `loraLine`. If a ViewModel's write sequence changes, change the mirror.
- **`LabTransport` stages a received file in the receiver's own directory** (`File(dir, "rx")`) before
  emitting it: `MeshBlobStore.saveIncoming` deletes what it reads, and two receivers of one blob handed the
  sender's single temp path raced for it, the loser dropping the blob silently. It also has a per-pipe loss
  knob (`lossy(to) { drop }`) and a `held(to)` peek so a scenario can wait for a relayed frame before releasing.
- **Wait for the frame, not for the state that precedes it.** A settings or DB write the scenario can see
  lands *before* the frame it triggers reaches the transport (`broadcastProfile` bumps the version, then
  stamps, signs and floods), so "the version moved" is not "the frame is parked": `ProfileUpdateLabTest`
  released one profile short on GitHub (2026-09-16). Poll `held(to).count { it.isProfileFrom(…) }` (or
  `isRoomPostFrom` / `isChatFrom`) before a `release` whose batch the scenario asserts on. The user-level
  sends (`sendDm`, `sendGroup`, `react`, `createGroup`) hand their frames to the transport before they
  return — `MeshRouter.sendOwn` awaits `transport.send` — so those need no wait; the profile edits
  (`setDisplayName`, `setStatus`, `setOpenToChat`, `setAvatar`) go through the manager's watcher, so
  `LabNode` waits for the publish itself (version moved, `framesOriginated` moved) before returning, and
  a no-op edit returns at once. A `RoomTickPlanesLabTest` node once minted its rename's frame after the
  scenario had already cut the link.
- **A link is presented on both ends before either end is woken.** `LabTransport.connect` publishes the two
  ends one after the other, and on one slow core the first end's reaction — its profile push, its custody
  digest — reached the second end before the second publish; the second end's manager answered through the
  composite, which asked `neighbors.value` for a child holding the link, found none and dropped the serve on
  the floor (the throttled whole-package loop failed `RoomTickPlanesLabTest`'s custody parity three runs in
  three, 2026-09-16; only the 60 s re-offer would have repeated it). `neighbors.value` now reads a `current`
  set written on both ends at pipe creation; collectors still see every publish, in order, through the
  `links` flow. The pipe map is a `ConcurrentHashMap` for the same reason — the scenario thread links and
  unlinks while the workers are inside `send`.
- **A hold re-applied after a `release` leaves a gap.** Between `release` returning and the next `hold`, a
  delivered frame's whole answer can cross on one slow core (a key request and the served key:
  `RestartLabTest` meant to strand the key and found it delivered, GitLab job 4494). `release(to,
  keepHolding = true) { … }` delivers the batch and keeps parking what follows.
- **The state a scenario can read is not the event it needs** — the 2026-09-16 audit's catalogue, after the
  three fixture races above and ADR 2026-09.qztx all had that one shape. Every `*LabTest` was read against
  one question: what does the thread observe, and what does it then assume has happened? Five more sites
  bypassed the helpers and now wait for the event itself. None of them reproduced in ten throttled
  whole-package runs (five on the unfixed tree, at 50 % and 25 % of one core): each is a single descheduling
  between a `send` or a PUT returning and the counter or row written after it, so they are fixed by
  reasoning, and a green loop is not evidence such a window is closed.
  - `heal()` returned the moment the basket was *launched*, and `TimeLabTest` re-linked in the same breath.
    The republished profile is seeded into custody, never flooded (`republishProfile`), so a link-up whose
    digest exchange ran before that row existed left it to the 60 s re-offer, past every await. `LabNode.heal`
    now waits for `healsCompleted` (`MeshMetrics.onHealCompleted`, the basket's last line — the one seam this
    needed in `mesh/`).
  - The contribution credit is booked *after* the serve's `send` returns (`ForwardSync.onDigest` →
    `onServed`), so Carol's row — and the oracle over it — is readable before Bob's ledger moves; the met set
    is written by its own `neighbors` collector. `ContributionLabTest` awaits both.
  - The avatar bytes are a `blobreq` round trip that starts when the profile row lands; `ProfileUpdateLabTest`
    read `blobs.exists` straight after profile parity.
  - `receiptsSpooled`, `spoolAccounted` and `receiptsRidden` are bumped after the push or seal whose result
    Alice has already pulled off the relay (`RoomTickPlanesLabTest`).
  - `await(1) { decrypted(…).size }` on a thread that already holds messages passes on its first poll:
    `TimeLabTest`'s post-reset reply could seal before Bob had applied the reset. Await the message by text.
  Read as safe, with the reason, so the next audit need not re-derive them: a DM's row and its ratchet
  commit share one transaction (`commitOpen` inside `withWriteTransaction`), so "the row is readable" does
  mean "the session is confirmed" and `await decrypted → reply` is a reply, not a both-initiate; the block
  list is a fresh `first()` per frame; every user-level send hands its frames — group seeds included,
  `distributeGroupSeed` is inline — to the transport before returning; the fake air decides loss inside the
  board's `send`, before the `lora tx` line a scenario polls; `skipCovered` runs at enqueue, so `loraSkipped*`
  moves before `sendChat` returns; `onFrameReplayed` and `onKeyRecovered` precede the replayed delivery; a
  `groupleave` envelope carries no `group`, so `sortedBy { isGroupFrame() }` does put the leave first. And
  `published`'s "originated moved" is durable: `broadcastProfile` custodies the new stamp under the lock
  before `sendOwn` counts it, so a link cut in the microseconds before `transport.send` strands nothing the
  next exchange cannot serve (a transport-level dispatch counter would not do — the composite fans a flood
  per neighbour and never hands a board node's flood to the child at all).
- **A held frame that is filtered out at `release` is lost, like a dropped packet** — and the digest exchange
  repairs it only on the next link-up or the 60 s re-offer. A scenario that drops held frames re-links before
  the oracle (`KeyExchangeLabTest`, `SessionLabTest`'s key request), as `RoomTickPlanesLabTest` already did.
- **A first sealed frame provokes a second one.** The tick a node sends for a stranger's post is its first
  sealed frame to that author, so it carries the X3DH init, and the author answers it with a sealed profile
  (`IntroSync.onPeerFrameOpened`) — a second chat frame from the same sender that a relay carries some jitter
  after the post. Name the frame a hold waits for (`WireEnvelope.isRoomPostFrom`, not "a chat frame from
  alice"), and where the scenario pins *how many* frames parked, make the pipe `lossy { it.relay }` from the
  release to the re-link: the flood relays (the answer) are the air's to lose, the point-to-point served key
  still crosses. `KeyExchangeLabTest` flaked 1-in-8 on one core before that (job 4354: two frames released;
  locally: two parked).
- **`LabLimits` carries the custody bounds** (`custodyTtlMs`, `custodyMaxRows`, `custodyMaxPerSender`, …,
  `ForwardRepository`'s constructor) and the LoRa Trickle interval (`loraGossipMinMs` / `loraGossipMaxMs`).
  Give every node in a scenario the same custody numbers — the digest is folded over what they keep.
- **The second batch of classes** (2026-09-14, 45 scenarios): `GroupMembershipLabTest` (leave-rekey, the
  self-rejoin of ADR 2026-09.v6fu, the rejoiner's seed rejoining them itself since ADR 2026-09.mjaj — it used
  to park under "sender departed" — the `left:` notice as an LWW clock, a member who missed the leave,
  concurrent renames), `ReactionLabTest` (DM / group / room forms, a retraction
  crossing its reaction, a reaction ahead of its DM, a group reaction founding the group), `ProfileUpdateLabTest`
  (rename to a contact and a stranger, a re-served older profile, status + flag surviving a sealed update, an
  avatar), `AttachmentLabTest` (image DM, carried bytes, room image screening, group photo),
  `KeyExchangeLabTest` (`keyreq` + `PendingInbound`, `pendingKey` retransmit), `BlockAndRequestLabTest`,
  `SessionLabTest` (both-initiate in both orders, a forced reset, old-era custody after a reset, the group
  key request), `RestartLabTest` (sender / carrier / recipient-with-a-park / both sides), `TopologyLabTest`
  (diamond, five-node line, ring partition, eight in a room), `CustodyQuotaLabTest` (per-sender, per-group,
  full store), `InternetPlaneLabTest` (the two-island group + departure, a group founded while a member is relay-only
  (#47, ADR 2026-09.mjaj), ADR 032's receive-only scope, spec §9.3 quarantine, ADR 042's card intro, a relay
  dropping every socket, ADR 020 over the relay) and
  `LoraPocketLabTest` (two pockets, ADR 054's gate, y8pu's backfill, the election, a DM through the bridge).
- **A scenario that fails on HEAD for a reason that is a design decision, not a bug, stays in the suite
  under `@Ignore("#NN: …")`**, naming its GitLab work item, with the finding in its KDoc, so the acceptance
  test exists before the decision does. Three sat there as of 2026-09-14: the attachment deferral judged in
  the round the send itself triggers, before the ack it needs can exist (#46); a group founded across the
  relay never delivering its roster to the relay-only member — root adoption needs the row, and the roster
  rides the scope the root derives (#47, decided 2026-09-18 as ADR 2026-09.mjaj: the seed carries the
  roster, and its scenario now runs); and a far pocket's room tick stopping at the gateway's board (#48).
  The first one decided, #45 (a blocker refusing custody of the blocked sender's frames), became ADR
  2026-09.bts9 and its scenario now runs; #49 (a `relay = false` key-request-served profile deduping its own
  custody re-serve for the seen window) closed in the clock tier. Un-ignoring one can retire a park another
  scenario waited on: #47 reshaped two (`GroupMembershipLabTest`'s rejoiner seed, `GroupFirstMessageLabTest`'s
  restart), because the lab runs the real `MeshManager` and every seed it emits now carries the roster.
- **Neither long-range plane carries a third party's DM-form frames** — alice↔bob's ticks and seeds are in
  no scope of carol's, and a DM-form frame to a linked peer never rides the board — so custody agrees across
  a relay or across two pockets only once the parties meet by radio again. A spool scenario with three nodes
  re-links before the oracle (as the two-island one does); a two-pocket LoRa scenario runs the oracle per
  pocket and asserts the far pocket's tick explicitly. The board-less reader in the far pocket can never
  tick the author at all: that is the residual ADR 2026-09.y5f3 names, not a scenario bug.
- **The router's relay jitter is real time the lab cannot pin** (`MeshRouter.jitter` is not reachable
  through `MeshManager`): a first-seen frame's relay fires 0–150 ms later, later still under load, with
  targets read *then*. A scenario that unlinks a node, sends, and re-links must wait for the carrier's relay
  decisions (`framesRelayed` moved by the number sent) before the link comes back, or the router hands the
  newcomer frames custody has already evicted (`CustodyQuotaLabTest` flaked exactly so, twice).
- **First contact by board alone is minutes, not seconds**: a passive gateway fans nothing, a superseded
  profile fan-out is dropped when the election flips, and the bridge budget (`BRIDGE_SHARE` of the 15-min
  window) serves about one profile per window per gateway. LoRa scenarios meet everyone by radio first
  (`meetThenSplit`), then cut the cross-pocket links; the slow path is the clock tier's to pin. And a Trickle
  interval of a second spends the window on offers alone, so the tick that follows is deferred
  (`loraTickDeferred`) — four to eight seconds is the lab's setting.
- **A DM that arrived over the board is ticked through `DmAckCoalescer`'s 45 s hold** (ADR 054), which the
  lab does not shorten; a LoRa DM scenario passes `timeoutMs = 60_000` to the oracle.
- **Every sealed frame carries the epoch key**, so an absent recipient opens whatever survives a custody
  quota without the conversation's first frames and asks for no reset — the quota is loss, never a wedge
  (`CustodyQuotaLabTest`; the "evicted init needs a reset" premise was wrong).
- **The calendar is a knob too** (`LabClock`, 2026-09-14): every node reads one shared clock the lab owns,
  and `lab.clock.advance(ms)` moves it for all of them at once — so nodes still agree with each other (the
  property `spaced {}` and ADR 2026-09.gdhp rely on) while a scenario steps past the router's 10-min seen
  window, the 24 h custody TTL, the 12 h profile republish or the 48 h intro grace (`TimeLabTest`). A jump
  moves *decisions* (expiry, floors, LWW, tombstones, presence), never *schedulers* — every periodic loop is
  `delay()`-based and blind to it — so the scenario pokes the work itself: `node.heal()` (fire-and-forget:
  await the effect), `sweepLocalStorage()`, `manager.refreshRelays()`, a re-link for the digest exchange, or
  a fresh send. Forward only. The seam is `MeshManager(clock = …)` plus the one-liners that hand the same
  clock to `ForwardSync` (load-bearing — its `now` is what `ForwardRepository.store`'s dead-on-arrival and
  future-skew guards compare a frame's `sentAt` against), `KeyExchange`, `BlobExchange`, `PendingInbound`,
  `PendingGroupKeys` and the router's `SeenSet`; production passes the wall clock everywhere. **Not** the
  ratchet `now` in `InboundPipeline`'s two open paths (`peekOpen`/`commitOpen`, which stamp a receive
  epoch's `lastUsedAt` and a skipped key's `createdAt`): those stay on the wall clock, because the 226-test
  pipeline rig pins the ADR 023–027 era semantics against a fixed clock and eleven of its cases read the
  ratchet's real time. The cost is a bound on the jump: **keep it under 48 h**, or `heal()`'s
  `ratchet.sweep(clock())` reaps receive epochs and skipped keys stamped a real day ago as though they
  were two days stale. A profile republish seeds custody rather than flooding, so a scenario that `heal()`s
  past `PROFILE_REPUBLISH_MS` re-links before its oracle. `LabClock.skew(name, ms)` is the one seam to a *disagreeing*
  clock, for a far-future-frame scenario (a skew past `Protocol.MAX_FUTURE_SKEW_MS` trips the custody
  refusal and `clampFuture`). Side effects of a long jump: `IngressBudget` refills, the send epoch rotates
  on the next seal (`MAX_EPOCH_AGE_MS`), a 7 d jump rotates the prekey and refloods the profile on `heal()`,
  `deleteOrphans` reaps at 24 h, `sweepRetention` drops unaccepted request threads at 7 d.
- **Order is a knob.** `alice.transport.hold(bob.transport)` parks what Alice sends Bob;
  `release(bob.transport) { reorder }` delivers it in the order you choose — how "custody serves the two in
  either order" becomes a deterministic case. Partition (group frames first, say) rather than blindly
  reverse: the intro driver's own `CTL_PROFILE` DM can land in the window.
- **Restart is real.** `node.restart()` tears the live stack down and rebuilds it over the same identity, DB
  and settings — every in-memory structure (`PendingInbound`, `PendingGroupKeys`, ratchet caches, seen set)
  starts empty.
- **A departure is observed, not timed.** `neighbors` is a conflating `StateFlow`, so a link taken down and
  brought back while `MeshManager.watchNeighbors` is still inside its body is a link that never went down to
  it — no newcomer, no profile push, no digest exchange. `LabTransport` records the generation each collector
  was last handed, and `unlink` / `restart` return only once every collector has been handed the departure
  (`awaitNeighborsObserved`); the 100 ms `SETTLE_MS` stays only for a node with a board, whose manager reads
  the composite's own `StateFlow` on top. Don't reintroduce a bare `delay` for this.
- **`awaitAcquainted` means the whole handshake.** Pinned keys *and* custody parity among the nodes — a
  scenario may cut a link the moment it returns. `RoomTickPlanesLabTest` cut the link on the pins alone and,
  on one throttled core, stranded two of Alice's profile stamps on her side with only the air left to carry
  them (the LoRa gossip is minutes); the oracle then waited on custody forever.
- **The CI runner is one slow vCPU**, and every relay jitter, tick and seal ordering a scenario assumed on a
  24-core box is randomized there. Reproduce a CI-only flake with the throttled loop, not on all cores:
  `systemd-run --user --scope -q -p CPUQuota=50% taskset -c 0 ./gradlew :app:testDebugUnitTest --tests '…' --rerun --no-daemon`
  in a loop, tallying the per-class XMLs; isolated classes flake less than the whole package, so validate
  with `app.getknit.knit.mesh.lab.*` three or four times over.
- **Time is real.** `MeshManager.start` builds its session on `Dispatchers.Default`, so scenarios run under
  `runBlocking` and poll, never virtual time. `MeshLab.await` **fails the scenario** where the wait runs out
  (with every node's counters and sends); `tryAwait` is the Boolean form for a site that words its own
  `assertTrue` message. A bare `await` whose result was dropped used to let the scenario carry on — the
  reply sent before the first DM landed became the both-initiate race the fixture was written to avoid,
  and the failure surfaced as the oracle's, a step later and under the wrong name (2026-09-18). `node()` returns only once the router is
  collecting `inbound` — a `SharedFlow` with no replay drops what is emitted before that, and a link brought
  up too early would lose the profile push. Bring a multi-hop topology up with `linkAll` so no relay fires in
  the gap between two links. Boot costs ~1 s per node (Tink keygen + Room + DataStore); the scenarios
  themselves run in a few hundred ms.
- **A failed `awaitAcquainted` prints every node's router counters** (`originated / delivered / relayed /
  deduped / suppressed / drops`) — read `suppressed` first; that is how the same-neighbor overhear bug showed.
- **CI runs the package three times in a row in its own job** (`mesh-lab` in `.github/workflows/ci.yml`,
  `test:mesh-lab` in `.gitlab-ci.yml`), on top of the one pass inside the full unit suite: this is the one
  suite where real time and scheduling decide the outcome, so a 1-in-N flake is a bug — both of the
  2026-09-11 findings started as one. Reproduce a CI flake locally with the same loop:
  `for i in 1 2 3; do ./gradlew :app:testDebugUnitTest --tests 'app.getknit.knit.mesh.lab.*' --rerun; done`.
- Robolectric, so the Gradle 9.5 result-serialization race applies: tally the per-class XMLs under
  `app/build/test-results/testDebugUnitTest/` after `rm -rf`-ing the directory, not the console summary.

## JVM Room/DAO + migration tests (Robolectric)

`app/src/test/java/app/getknit/knit/data/` runs the **real** DAO SQL — the eviction/orphan/GC queries the
`FakeForwardDao`/`FakeReactionDao` only *mirror* (finding #5 in `docs/ARCHITECTURE_REVIEW.md`) — on the JVM
under Robolectric 4.17, plus a `MigrationTestHelper` harness. They run inside the normal
`:app:testDebugUnitTest` (and CI `test:unit`), no device. The wiring is non-obvious and load-bearing — read
before "simplifying":

- **The in-memory test DB skips SQLCipher.** `RoomDbTest` builds via `Room.inMemoryDatabaseBuilder(...)` with
  **no** `setDriver`, so it uses Robolectric's framework SQLite — no passphrase, no `libsqlcipher.so`.
  The eviction/GC SQL runs identically (SQLCipher only encrypts at rest). Never call `KnitDatabase.build()` in
  a *unit* test. Call `suspend` DAO methods inside `runTest { }`.
- **The one place SQLCipher itself is under test is `androidTest`.** `SqlCipherDriverUpgradeTest`
  (`app/src/androidTest/java/app/getknit/knit/data/`) opens a real encrypted database, because
  `KnitDatabase.build()` installs SQLCipher through `setDriver(SQLCipherDriver(...))` — Room 3 deletes
  `openHelperFactory`, so that is the only remaining seam, and "the driver reads what the old factory wrote"
  is not something ADR 008 lets us assume. It covers both a factory-written database reopened through the
  driver and a v1-era database walking the whole `KnitMigrations` chain under it. It uses a throwaway db
  name and a literal passphrase — it never touches the real `knit.db` or `DatabaseKey`. That is the carve-out
  to the rule above; it does not license a `KnitDatabase.build()` call in a Robolectric test.
- **`robolectric.properties` forces `application=android.app.Application`.** The real `KnitApplication.onCreate`
  starts Koin, whose static `GlobalContext` isn't reset between tests → `KoinApplicationAlreadyStartedException`
  on the 2nd test. DAO tests bypass Koin, so a plain Application is correct. `sdk=36` deliberately trails
  compileSdk 37.1. Robolectric 4.17 *does* ship a 37 runtime, but **`sdk=37` still fails**: API 37 drops
  `android.hardware.input.InputManager.getInstance()`, which Espresso calls from `onIdle`, so every
  Compose-UI Robolectric test throws (233 of them, measured). Re-test when compose-ui-test/Espresso
  catch up.
- **Robolectric 4.17 needs JPMS `--add-opens` flags.** `testOptions.unitTests.all { jvmArgs(...) }` in
  `app/build.gradle.kts` carries Robolectric's published JDK-17+ list verbatim. Without it *every*
  Robolectric test dies in `setUpApplicationState` with "Failed to interact with raw FileDescriptor
  internals" — `AndroidInterceptors` reflects into `jdk.internal.access`, which `java.base` does not open
  to the unnamed module. 4.16.x needed none of it; SDK 37's `ApplicationSharedMemory` is what walks into
  the interceptor. Diff the list against robolectric.org/getting-started on the next bump.
- **`exportSchema = true`** on `KnitDatabase` + the Room 3 Gradle plugin's
  `room3 { schemaDirectory("$projectDir/schemas") }` emit
  `app/schemas/app.getknit.knit.data.KnitDatabase/<version>.json` (checked in). Regenerate by clearing
  `app/schemas/` and rebuilding after any `@Database` version bump (KSP caching can otherwise skip
  re-export) — then `git checkout -- app/schemas/` to restore versions 1..N-1, which Room never
  regenerates. Only the current version is exported; the rest are history these tests read.
- **`MigrationTestHelper` reads the schema from *debug*-variant assets** —
  `sourceSets["debug"].assets.srcDir("schemas")` in `app/build.gradle.kts`. Robolectric serves the merged
  **debug** assets (unit tests run against debug) but **not** the `test` source set's own assets; release APKs
  never carry the schema. It uses **`AndroidSQLiteDriver`** (the Robolectric-shadowed engine) — the
  connection-returning API needs a `SQLiteDriver`, and `BundledSQLiteDriver` can't load its Android native
  `.so` on the host JVM. Under Room 3 `createDatabase` / `runMigrationsAndValidate` are **suspend**, so every
  case here runs inside `runTest { }` — and `Migration.migrate` is suspend too (`KnitMigrations`). **DB v1 is the frozen launch baseline** — there is **no** destructive fallback: from
  v1 forward every `@Database` bump MUST add a tested `Migration` to `KnitMigrations` (`data/KnitMigrations.kt`,
  appended to `ALL`) and a from→to case to `KnitDatabaseMigrationTest` — a missing migration throws at
  open time (caught here in CI), never silently wipes. The DB is at **v12** today, with eleven migrations
  (`MIGRATION_1_2` … `MIGRATION_11_12`) and a `KnitDatabaseMigrationTest` case for each, plus a current-schema
  smoke test whose hardcoded `version` must be bumped by hand — as must `SqlCipherDriverUpgradeTest`'s
  `CURRENT_VERSION`, which has now gone stale twice. Keep the version count down while a branch is
  unreleased — fold its schema churn into one bump before merging, the way `MIGRATION_9_10` carries the whole
  LoRa bridge and `MIGRATION_10_11` the whole drafts feature — because a shipped migration can never be merged
  away afterwards, and a branch that mints its own numbers strands every lab device the moment it opens a
  database from the other branch.
  **Folding a bump the lab has already installed needs a way back down.** `drafts.updatedAt` was briefly its
  own v12; collapsing it into v11 left the test phones holding a database whose `user_version` was *higher*
  than the app's, which Room throws on at every open rather than shrugging off. The way back is a registered
  downgrade `Migration(higher, lower)` — Room takes a descending path — and when the fold changed no columns
  its body is empty, because Room hashes schema *content* rather than version numbers, so only the stamp
  moves. Ship it, install across the fleet, then delete it; the alternative is wiping the drawer. The v12 → v11
  shim did exactly that on 2026-09-10 and is gone. **An FTS table's sync triggers are the migration's job
  here:** Room drops every `room_fts_content_sync_*` trigger before a migration and re-creates its own after,
  but `MigrationTestHelper`'s delegate does neither, so `MIGRATION_11_12` creates them itself (verbatim from
  `12.json`'s `contentSyncTriggers`) or a migrated test file never indexes a write. A bump need not
  add columns: `MIGRATION_9_10` also re-indexes `messages` without moving a row, and its case asserts the
  index *shape* via `PRAGMA index_info` because column order is what decides whether SQLite can skip a sort. `EXPLAIN QUERY PLAN` is **not** reachable from these
  tests — the Android driver routes only `SEL`/`PRA`/`WIT` prefixes as row-returning, and Room 3 dropped
  `openHelper` — so index *shape* is as close as a host test gets to proving an index is used. (The pre-1.0 alpha builds churned
  through destructive v2…v22 bumps that rode the wire/crypto breaks; that history is collapsed — see
  `docs/WIRE_COMPAT.md` for the break record.)
- After adding a test dep, **regenerate the lockfile** (`:app:dependencies --write-locks`, all configs) — see
  the lockfile rule in `rules/build-and-test.md`.

## Seeded UI instrumentation suite + Firebase Test Lab

`app/src/androidTest/java/app/getknit/knit/ui/` is a Compose/Espresso instrumentation suite that hunts
**device- and API-specific UI quirks** on real hardware. Because the mesh radios can't work on a Firebase
Test Lab (FTL) datacenter device (no peers, no NAN), the suite runs the app against the **demo-seeded,
radio-less build** (`-PseedDemo=true`): the no-op `DemoTransport` replaces the radios, `DemoSeeder` populates
Room through the real repositories, onboarding is skipped, and `MeshService` never starts — so every screen
renders fully populated and deterministic (the "hiking" theme; seeded ids `samr1v00`/`danich01`/… and the
existing `testTag`s are the assertion anchors). Graceful **radio-absent** behaviour is verified separately by
the JVM tests (`CompositeMeshTransportTest`, `RadioWarningTest`, `OnboardingScreenContentTest`), not here.

- **Run locally** (emulator is fine — no real mesh needed):
  `./gradlew :app:connectedDebugAndroidTest -PseedDemo=true` (target one device with `ANDROID_SERIAL=…`).
- **Run on FTL**: the seeded app + androidTest APKs (`-PseedDemo=true` for both) run on Firebase Test Lab
  physical devices via `gcloud firebase test android run --use-orchestrator`. The maintainer's runner
  scripts, device matrix, Firebase project, and budget live in the local `.private/` overlay (absent in
  public clones).
- **Isolation is Android Test Orchestrator + `clearPackageData=true`** (`testOptions.execution` +
  `androidTestUtil` orchestrator/test-services in `app/build.gradle.kts`): each test runs in a fresh,
  data-wiped process, so the identity + DB regenerate and the seed re-runs every time.
- **The seed also arms the two flag-gated planes.** `DemoPlanes` (debug-only) consents to and fills the
  Internet-relay list and binds a Knit-provisioned LoRa board, so `relays`, `lora`, the chat header's
  globe/board glyphs and the plane marks on a bubble all render against something. Neither opens a socket
  nor dials hardware: the spool statuses are pinned via `MeshManager.seedDemoSpools`, and the board is
  `DemoLoraPlane`, a `LoraPlaneStatus` that only reports. It also seeds one unaccepted stranger DM + group
  (so the Requests inbox and its chat-list badge are populated) and one blocked peer. A seeded build is
  therefore **not** a clean-slate build — if you are asserting on an empty state, seed it yourself.
- **`BuildConfig.SEED_DEMO` is compile-time-inlined into the androidTest DEX**, so the **test** APK must also
  be built with `-PseedDemo=true` (the gradle/FTL commands above pass it to both). `SeededUiTest` fails loudly
  with a `check(SEED_DEMO)` if not — never gate the suite on `Assume.assumeTrue(SEED_DEMO)` (a mis-build would
  silently skip everything green).
- **Screenshots**: every test captures one (pass or fail) via `UiAutomation` + test-services `TestStorage`
  (`androidx.test.services:storage`), which FTL collects as a per-device output and AGP mirrors locally under
  `app/build/outputs/connected_android_test_additional_output/`. This is the scoped-storage-safe path (no
  `WRITE_EXTERNAL_STORAGE` on any API); do **not** switch to `testlab-instr-lib`'s `FirebaseScreenCaptureProcessor`,
  which hardcodes a `/sdcard/screenshots` write that only FTL's device env grants (it silently no-ops on a
  plain emulator).
- **Gotchas that bit us:** the seed is async, so `waitUntil` for content (never assert on immediate launch);
  assert on the **newest/on-screen** message (a `LazyColumn` won't compose off-screen items, and an
  image-attachment message's async height throws off the auto-scroll on some devices — assert a freshly-sent
  echo instead); a text send cold-loads the tflite moderator, so give the echo a generous timeout; and
  `ProfileViewModel` one-shot-reads the display name in `init`, racing the seed (test the edit round-trip, not
  the pre-loaded name).

## Black-box UIAutomator suite

`app/src/androidTest/java/app/getknit/knit/uiauto/` (base `SeededUiAutomatorTest`) is the **UIAutomator**
twin of the Compose suite: it drives the *real running app* through the accessibility / resource-id layer
instead of the in-process semantics tree, so it can reach what Compose testing can't — the **system
notification shade** and **process lifecycle** (Home / Recents / Back / rotation). Same demo-seeded,
radio-less build (`-PseedDemo=true`); `SeededUiAutomatorTest` shares `SeededUiTest`'s contract
(`requireSeededBuild`, `launch(route)` via the `demo_route` extra, `TestStorage` screenshots).

- **Selectors ride the same `testTag`s.** `testTagsAsResourceId` is set at the NavHost root, so a Compose
  `testTag` surfaces to UIAutomator as a `resource-id`. Compose exports it **unqualified**
  (`resource-id="chat_input"`), so `SeededUiAutomatorTest.byTag()` uses a tolerant `By.res(Pattern)` that
  accepts an optional `pkg:id/` prefix. Screens without tags (Diagnostics, request rows' inner text) match
  by `waitText`/`waitDesc`.
- **Popups don't inherit `testTagsAsResourceId`.** A Compose `DropdownMenu`/`AlertDialog` is a separate
  window, so a `testTag` inside one does **not** surface as a `resource-id` — drive menu items and dialog
  buttons by their (localized) **text**, an editable field by its `android.widget.EditText` class, and a
  confirm button whose label is a substring of the dialog title by *exact* text (`requireExactText`, e.g.
  "Block" under "Block this person?"). This is why the overflow-nav / group-management / requests-block tests
  select popups by text, not tag.
- **`UiDevice.executeShellCommand` word-splits and does not honour quotes** (unlike an `adb shell "…"` that
  the device re-parses). A `--es text 'two words'` reaches the app as just `'two`, so a debug broadcast fired
  from within a test must use single-token extras (see `ModerationRevealUiAutomatorTest`).
- **Coverage today:** the seeded core flows + DM send (`SeededFlowsUiAutomatorTest`), process lifecycle
  (`LifecycleUiAutomatorTest`), the notification-shade→requests flow (`MessageRequestNotificationUiAutomatorTest`),
  overflow-menu navigation to the untagged screens (`OverflowNavigationUiAutomatorTest`), contacts→DM/group
  creation (`ContactsFlowUiAutomatorTest` — note the picker lists only *established* contacts: explicitly-accepted (a card import or an accepted request) ∪ accepted-DM ∪
  group co-member ∪ verified, so Nearby-only strangers never appear), group rename/leave
  (`GroupManagementUiAutomatorTest`), the in-app requests badge + block path (`RequestsInboxUiAutomatorTest`),
  and the received-flagged tap-to-reveal (`ModerationRevealUiAutomatorTest`, via the `FLAGMSG` debug seam).
- **Isolated FTL target.** On Firebase Test Lab this package is run **on its own**
  (`--test-targets "package app.getknit.knit.uiauto"`) and **excluded** from the default Compose run
  (`notPackage app.getknit.knit.uiauto`) so black-box system-UI flakiness never reddens it. Both use the
  one seeded androidTest APK — the split is a runtime filter. (Maintainer runners live in the `.private/` overlay.)
- **Run locally**: `./gradlew :app:connectedDebugAndroidTest -PseedDemo=true
  -Pandroid.testInstrumentationRunnerArguments.package=app.getknit.knit.uiauto` (drop the `-P…package` arg
  to run everything). `@After` force-stops the app so a bare run (no orchestrator) still isolates.
- **The message-request notification seam.** The radio-less build never runs `InboundPipeline` (the only
  caller of `Notifier.notifyMessageRequests`) and seeds no requests, so the debug bridge action
  **`REQNOTIF`** (`DebugBridgeReceiver`) writes synthetic unaccepted inbound DMs and posts the real
  heads-up: `adb shell am broadcast -p app.getknit.knit -a app.getknit.knit.debug.REQNOTIF --ei count 1`.
  The test grants `POST_NOTIFICATIONS` via `pm grant` (API 33+ only; install-time granted on 29–32), fires
  the seam, then drives the shade → tap → Requests inbox → Accept. The inbox rows carry
  `request_row_<id>` / `request_accept_<id>` tags.

## Accessibility (ATF) suite

`app/src/androidTest/java/app/getknit/knit/a11y/` (`AccessibilityInstrumentedTest`) runs Google's
**Accessibility Test Framework (ATF)** — the same framework the Play Console **pre-launch report** runs —
against every seeded screen, so a11y regressions (missing labels, sub-48dp touch targets, low text/image
contrast, bad traversal order) fail locally before upload. It reuses `SeededUiTest` (deep-link each screen
via `demo_route`, await the seed, then audit) and adds one dependency, `ui-test-junit4-accessibility`, which
pulls ATF transitively: `compose.enableAccessibilityChecks(validator)` +
`compose.onRoot().tryPerformAccessibilityChecks()`.

- **API-34 floor.** The Compose ATF integration is `@RequiresApi(34)`, so the suite is gated with
  `@SdkSuppress(minSdkVersion = 34)` — it **skips** (not fails) on the API 29/33 matrix devices, and
  `@SdkSuppress` also satisfies lint's `NewApi`; do **not** add `@RequiresApi` in a test (lint's
  `UseSdkSuppress` rejects it). The default managed device `pixel7api33` is too old, so a new **`pixel8api34`**
  managed emulator (`aosp-atd`) runs it headless.
- **Severity policy: errors fail, warnings logged, all findings reported.** The `AccessibilityValidator`
  throws (fails the test) only for `AccessibilityCheckResultType.ERROR`; an `addCheckListener` logs every
  WARNING/INFO to logcat under tag `A11y`, and each test writes its actionable findings to a
  `a11y-<screen>.txt` file via `TestStorage` (collected by FTL, mirrored locally under
  `app/build/.../additional_output/` — plus AGP's per-test `logcat-<test>.txt`). Suppress a known-acceptable
  finding with `setSuppressingResultMatcher(...)`; widen the gate with `setThrowExceptionFor(...)`.
- **Contrast needs real pixels, so screenshot capture is hardware-gated.** ATF's text/image contrast checks
  read a screenshot; on the headless emulator `UiAutomation.takeScreenshot()` races the UI thread and
  returns null → ATF NPEs. So `setCaptureScreenshots(!isEmulator())`: **off on the emulator** (contrast
  reports NOT_RUN; structural checks — labels, touch targets, traversal, duplicate/redundant descriptions —
  still fully run) and **on for real hardware**, so contrast actually runs on the Firebase Test Lab
  physical-device pass (and Play's pre-launch report covers it too).
- **Run locally** (headless emulator, no physical device):
  `./gradlew :app:pixel8api34DebugAndroidTest -PseedDemo=true
  -Pandroid.testInstrumentationRunnerArguments.package=app.getknit.knit.a11y`.
- **Run on FTL**: the `a11y` package runs on an **API-34+** Firebase Test Lab device; it also rides the default
  FTL run but *skips* on the API 29/33 devices. (Maintainer runner lives in the `.private/` overlay.)

When driving the emulator over `adb`: the soft keyboard overlaps via `adjustResize`, so read element
coordinates from `uiautomator dump` rather than guessing; seed the photo picker by `screencap`-ing
into `/sdcard/Pictures` if you need an image to select. For the headless debug bridge (send/verify without
screenshots), see `context/debug-bridge.md`.

## Real BLE from an emulator (USB passthrough)

`scripts/emulator-ble-mesh.sh` puts a **real** BLE radio behind an emulator, so it joins the actual mesh
alongside the lab phones — advertising, L2CAP CoC connects, frames, the lot. Useful when you want a third
node, or a node you can drive with `adb`/screenshots, without a third handset. Verified 2026-08-30 on the
Edimax BT-8500 (`7392:c611`, RTL8761BU) against two lab Pixels.

The built-in emulator Bluetooth is **netsim/rootcanal**, a pure simulation: two emulators on one host see
each other through it, a physical phone never can. The only route to real RF is
[USB passthrough](https://source.android.com/docs/automotive/start/passthrough) —
`emulator -usb-passthrough vendorid=…,productid=…`, which QEMU turns into a `usb-host` device on its
`qemu-xhci`. It lands in the guest's own BT stack because
`android.hardware.bluetooth-service.default` binds a **kernel** HCI device (mgmt socket + `HCI_CHANNEL_USER`,
`waitHciDev` taking the exact index or the next larger), not a QEMU serial port.

- `host` prints the two sudo commands the host needs (a udev rule for the USB node, and an unbind from the
  host's own `btusb`); `up` launches the AVD; `bootstrap` is once per AVD; `setup` is once per boot;
  `status` shows the hci wiring, the adapter address and the mesh state; `down` gives the dongle back.
- **`-feature -BluetoothEmulation`** or `bt_vhci_forwarder` supplies a virtual controller that shadows the
  real one. Both still exist as separate `hci*` nodes — `status` labels which is which.
- **The image has to be rootable *and* complete.** Play-Store images can't `adb root`, so no firmware push
  and no `dmesg`. `aosp_atd` roots fine but ships **no SystemUI and no launcher**: its window is permanently
  black and only instrumentation can drive it (`pm path com.android.systemui` comes back empty — that is
  the tell, not a GPU problem). `google_apis` is the one that is both; the AVD here is `Knit_Mesh_BT`
  (`system-images;android-36;google_apis;x86_64`).
- **Realtek firmware is the trap.** The SDK images carry `btusb`/`btrtl` but no `rtl_bt/*`, and QEMU's
  attach resets the chip to ROM (`lmp_subver=8761`), so the host's own patch does not carry over.
  `bootstrap` stages `rtl8761bu_fw.bin` + `_config.bin` from the host's `/lib/firmware/rtl_bt` (zstd) into
  `/vendor/firmware/rtl_bt` over `adb remount` — which needs `-writable-system` and only arms overlayfs on
  the *next* boot. It then persists in that AVD.
- **Failure signature to recognise:** the HAL logs `opening hci interface 0` → `waiting for hci interface 0`
  and never `found`, and the adapter sits in `BLE_TURNING_ON` with a null address. That is the controller
  never having been opened — firmware missing, or the driver not rebound — because a controller whose
  `hci_dev_open` failed is never announced on the mgmt socket, even though `/sys/class/bluetooth/hciN`
  exists. `dmesg | grep RTL:` is the ground truth; `RTL: fw version 0x…` means it came up.
- **Per boot, all three reset** (this is what `setup` does): `firmware_class.path`, `setenforce 0` (the
  kernel reading `vendor_file` is denied and neither `firmware_file` nor `vendor_fw_file` exists in this
  policy, so relabelling is not an option), and the `btusb` unbind/bind — the rebind being what actually
  opens the controller. The hci index climbs on each rebind (hci0 → hci2 → …); harmless.
- **BLE only.** No Wi-Fi Aware, so the composite transport runs BLE-only and NAN reports unavailable.
- Test with an **unseeded** debug APK: a `-PseedDemo=true` build fakes its peers, so `debug.STATE` will
  cheerfully report a mesh that isn't there.
