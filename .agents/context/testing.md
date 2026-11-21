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
6. **Compose preview screenshot tests** (`app/src/screenshotTest/`) render every `@Preview` through layoutlib
   on the host JVM and diff it against a committed PNG — no device, under a minute. See below.

> Wi-Fi Aware needs physical devices — an emulator can't do NAN, whatever you do about Bluetooth. Use
> `FakeLoopTransport` for logic tests and two physical Wi-Fi-Aware-capable phones (e.g. Pixels) for real
> discovery → data path → relay.

> **`UncaughtExceptionsBeforeTest` names the wrong test.** kotlinx-coroutines-test's `ExceptionCollector`
> starts catching *every* uncaught coroutine exception in the JVM the first time any `runTest` runs, never
> turns off, and hands what it caught to the *next* `runTest` — so a Robolectric test whose service or fake
> throws inside an unhandled `launch` (a Koin definition its module never provided, resumed on the
> `Dispatchers.Unconfined` scope) fails whichever `runTest` class the fork runs after it, and only when a
> `runTest` ran *before* it. A single-class run is green; CI's shared fork is not. Read the failure's
> **Suppressed:** cause for the real origin (the HTML report keeps it; the console line does not), and
> reproduce with three classes: any `runTest` class, the suspect, the victim (the `ImageScreeningServiceTest`
> case of 2026-09-19 was `MeshServiceForegroundReclaimTest` raising `neighborCount` without an
> `MlTextModerator`).

## Unit tests that hold on a slow runner

Every JVM test outside `mesh/lab/` (`app/src/test`, `wear/src/test`) is reviewed by the `unit-test-reviewer` persona
against this catalogue, and a Stop hook asks for it. CI's GitLab runner is one vCPU and 2 GB (two
`Dispatchers.Default` threads), GitHub's four vCPU, and both run the suite one fork at a time, a fresh one every 100
classes (`forkEvery` in `app/build.gradle.kts`: MockK keeps every mock a fork makes, so one fork for all ~700 classes
ran out of heap): an ordering a workstation always wins is lost there one run in thirty. The workstation runs four
forks side by side instead (`maxParallelForks`: one per 6 cores, at most 4, and 1 wherever `CI` is set; #346), so a
full local run takes about 4.5 minutes, not 13. The forks split the classes, so a class runs after different
neighbours than on CI: to replay CI's order for a fork-order failure such as `UncaughtExceptionsBeforeTest` above, pass
`-Pknit.testForks=1`. And a test binds an ephemeral port and writes only files its own class names, because two
classes sharing a fixed port or file now run at the same time. The watch's tests run in
their own job (GitHub `wear-tests`, GitLab
`test:wear`, `-Pknit.wear=true :wear:testDebugUnitTest`). Most of the incidents below never reproduced locally (`cd16429a`: 25
green runs; `5626c3e5`: green for almost every draw), so a test is judged by reading it, and
`scripts/unit-stress.sh --tests '<Class>'` (copies side by side in separate JVMs, `--quota` for one throttled core)
is the second opinion, never the proof. Slow the test with `--quota`; never load the host to crowd it unless the
user authorizes that run (`rules/build-and-test.md`, "Never load the machine").

**Flake shapes.** Each is a pair *observes → assumes*: what the test thread can see, and what it then acts as if
had happened.

1. **Late subscriber.** `.first()` / `collect` on a `SharedFlow` without replay, started after the act. Code that
   resumes off a `withContext(IO)` emits on another thread and can beat it (`ChatViewModelTest`, `146a343e`).
   Subscribe first: `async(UnconfinedTestDispatcher(testScheduler)) { flow.first() }`, or a `backgroundScope`
   collector launched before the act.
2. **A sibling not yet listening.** Awaiting one coroutine `start()` launched says nothing about its siblings. An
   edit written before `watchProfileChanges` subscribed arrived as its initial value and `drop(1)` swallowed it
   (`MeshManagerTest`, `cd16429a`). Gate on a readiness signal: `subscriptionCount` of a flow only that collector
   reads.
3. **One effect read as proof of the next.** A row, counter or state seen, then a later effect of the same work
   (a mock call, a second write) assumed done: the session row was gone but `groups.forgetSelf` not yet called (CI
   job 6252, `3031fe60`). Await the effect itself: `coVerify(timeout = AWAIT_MS)`, a poll on its own row. The
   negative form is vacuous the same way: `coVerify(exactly = 0)` or "nothing was sent" read before the work could
   have run passes whatever the code does; put a barrier first (a later positive effect of the same input).
4. **A derived flow read before it re-derives.** An upstream `MutableStateFlow` written, then a `stateIn` /
   `shareIn` projection or a collector-fed cache read at once (`PeerRepositoryTest`'s anchors, `8fdf8916`). Await
   the derived value: `observe().first { … }`.
5. **A passing state the worker may skip.** An intermediate edge (busy, a progress step, a queue length) that a
   fast worker passes before the reader looks, or a slow one has not reached. A gone file was taken and skipped
   before the enqueue's own report read the state, so the rising edge was never seen (`FramedLinkTest`, CI job
   8771, `72684a86`). Hold the system in the state deterministically (there, a file larger than the pipe), assert,
   then let it go.
6. **Randomness or jitter at its default.** A rig that builds a component with a `random` / jitter seam left at the
   default runs on a schedule that moves from run to run: the Trickle timer's OFFER landed inside a 4 s window on
   the 2.4.0 tag (`LoraMeshTransportTest`, `5626c3e5`). Pin every such seam in the rig (`random = { 0 }`), seed any
   `Random` the test draws its data from.
7. **Real time as a sync point or a budget.** A `delay` / `Thread.sleep` before an assertion; a `withTimeout`
   sized for a workstation; a production TTL shrunk so far that load expires it (`TransferManagerTest`'s 400 ms
   offer TTL failed with every core pinned). Inject the clock (`clock: () -> Long`, `7ab3bdd7`) and run on virtual
   time; where real threads are unavoidable, poll the condition under a bound sized for the one-vCPU runner
   (`MeshManagerTest.AWAIT_MS`, 10 s), and make the timeout's failure carry evidence (`Side.trace`, `e4f93517`).
8. **Host resources shared with other forks.** A fixed port, or one inside Linux's ephemeral range, collides with
   another fork's outbound sockets (`c5f4c099`: `FakeDirectWifi.freeLoopbackPort`); `::1` is missing on some
   runners (`f3138a6a`, `e4f93517`: `assumeTrue(FakeDirectWifi.ipv6LoopbackUsable())`); a path outside a
   `TemporaryFolder`; the default `Locale` / `TimeZone`, which only the screenshot tasks pin.
9. **A leak into the next class.** An exception in a scope `runTest` does not own (the manager's own
   `SupervisorJob`, an app scope on `Dispatchers.Unconfined`) is caught by kotlinx-coroutines-test and fails the
   *next* `runTest` class (the `UncaughtExceptionsBeforeTest` note above; a Koin definition the path resolves but the
   module lacks). A scope cancelled but not joined before `db.close()` throws on the closed pool into whichever
   class runs next (`LabNode.shutdown`, `a7a85f80`). `startKoin` without `stopKoin`, `Dispatchers.setMain` without
   `resetMain`, `mockkObject` / `mockkStatic` without `unmockkAll`, a static or `object` written and not restored.
10. **Virtual time the code never sees.** Code that hops to a hard-coded `Dispatchers.IO` / `Default` escapes the
    test scheduler: `advanceUntilIdle()` does not wait for it and `advanceTimeBy` does not move its `delay`. Inject
    the dispatcher, or treat it as real-thread code (shape 7). A collector that never ends goes in
    `backgroundScope`, never `launch`. Under Robolectric the main looper is paused and `SystemClock` is its own
    clock: a post runs only on `shadowOf(getMainLooper()).idle()`, and `SystemClock.elapsedRealtime()` stands still
    while a real-time wait polls it.
11. **A "deflake" that hides a race.** A widened timeout, a retry, a sleep, or an oracle relaxed to a sum or a `>=`
    with no named interleaving. Twice the flake was production code: `TransferListener` set `soTimeout` outside
    its `try` on a socket the winner had closed (`a7a85f80`), and a profile stamp was signed twice (ADR
    2026-09.qztx). Fix the code and add a test that forces the interleaving through a seam that holds **each**
    party until the other has arrived. A latch on the second arrival alone does nothing when the second never
    arrives: `TransferListenerTest` as first committed held only the second listener at its injected clock, the
    winner cancelled that listener's coroutine before it was dispatched, and the pre-fix code still passed 3 to 7
    runs in 30 on one core (2026-10-17, the unit-test-reviewer's dry run).

**Well conceived.** A test that cannot fail is worse than none, because it reads as coverage.

- **It fails without the change, on every run.** A regression test goes red on the pre-fix code; walk it against
  the old lines before trusting it. A test that drives real threads is red or green by the schedule, so one red run
  clears nothing (30 of 30 red on free cores, 23 to 27 of 30 on one core, above): run it there many times on one core with
  `scripts/unit-prefix.sh --class <Class>` (the tests as written now, the main code at `--base`, default `HEAD`, in a
  throwaway worktree) and expect red on every run.
- **Its assertions can fail.** Not inside a callback or `collect` that may never run, not under a `runCatching` or
  a `catch (e: Exception)` that swallows the `AssertionError`, not in a coroutine on a scope the test does not
  join, not a tautology (`size >= 0`, a value compared with itself).
- **It tests the behaviour, not the mock.** No asserting a stub's own answer back; a `relaxed = true` mock that
  answers `false` / empty can skip the very branch under test, so stub what the path reads; prefer the observable
  outcome to a `verify` of the call sequence.
- **Real collaborators where the bug lives.** SQL through `RoomDbTest` (real DAO SQL, finding #5), never a fake
  DAO; the repo's fakes (`FakeLoopTransport`, `FakeKeystoreCipher`, `FakeDirectWifi`)
  before a mock with behaviour scripted into it; what two nodes exchange through a `mesh/lab/` scenario ending in
  `assertConverged`.
- **The cheapest level that can see the bug.** Plain JVM over Robolectric where the code allows (each Robolectric
  class costs a fork's metaspace and trips the Gradle 9.5 result race); a ViewModel test over a Compose one for
  state. A host test proves nothing about an Android API level: the host JDK has `Character.isEmoji*`, Android has
  it from API 36, and lint (`NewApi`) is the only net.
- **Expected values from outside the code.** Not computed by the function under test; golden vectors written by
  `KNIT_WRITE_VECTORS=1`, never by hand; a boundary from the production constant (`DEFAULT_TTL_MS + 1`), a literal
  only where the number is itself the contract.
- **The edge and its neighbour.** The case the change is about, and the one just inside it.
- **One behaviour, named as the sentence it proves** (`aFileWhoseSourceIsGoneStillEndsItsFeedingEdge`), with a
  comment for the why that is not in the name, and a failure message that carries the evidence.
- **Skips are honest.** `@Ignore` names its issue; `assumeTrue` is for a host capability, never a condition the
  code is supposed to meet.
- **Seams are constructor parameters with a production default** (`clock`, `random`, `newPort`, a dispatcher),
  not reflection or widened visibility.

## Instrumented tests that hold on an emulator

`app/src/androidTest/` is reviewed by the same `unit-test-reviewer` persona, against the shapes and rules above and
the five below (I1–I5), which a JVM test never meets. CI runs it on headless aosp-atd emulators (the advisory "Instrumented
tests" job): one Gradle call per package, `ui` and `uiauto` from a seeded build, `data`, `moderation`, `notifications`
and `text` from an unseeded one, `a11y` seeded on API 34. `blepair` needs a second phone and never runs there.
`transfer` (`DirectGroupProbeTest` and `DirectGroupBackgroundProbeTest`, ADR 2026-11.8zm5) needs a phone's Wi-Fi Direct, which no emulator has: it runs only
on Device Farm phones, from an unseeded build, as the package `app.getknit.knit.transfer`, and skips in a seeded one,
whose default `notPackage app.getknit.knit.uiauto` filter would otherwise take it in.

- **I1. The app runs beside the test.** The instrumentation process is the app's: `KnitApplication.onCreate` starts
  Koin and launches background work (`startCallsIfWired`, the relay seed, the commons rename) that builds the graph,
  opens the real database and mints under the live Keystore aliases while the test runs. `SandboxContext` moves
  `filesDir`, `noBackupFilesDir` and the database path; it does not move a Keystore alias or a Koin single. Two
  `KeystoreSecret`s that both find no key both generate, and the later one replaces the earlier's key:
  `BackupRoundTripTest`'s read-back lost that race to the app's own `DatabaseKey` (CI run 40166809040,
  `56fe6d539`). Settle the app's own instance first, through `GlobalContext.get().get<…>()` (its lock waits out a
  mint already running), or use an alias of the test's own when the live one is not the point.
- **I2. Every test is a first run.** The orchestrator with `clearPackageData=true` gives each test a fresh, empty app,
  so a first-run path (a mint, a wipe, the seed) runs before every test, not once per suite. Nothing one test writes
  reaches the next; a test that needs state builds it.
- **I3. Seeded and unseeded are different apps.** `BuildConfig.SEED_DEMO` is inlined into the test DEX, a seeded start
  mints its own identity and fills the database, and CI picks a test's build by its package. A test that needs a
  clean slate skips in a seeded build (`assumeFalse(SEED_DEMO)`, run 38830030015) and must sit in a package the
  unseeded pass runs; the seeded suite fails loudly on a mis-build (`check(SEED_DEMO)`), never skips.
- **I4. Emulator time.** A software-rendered emulator on a shared runner is slower than any phone the suites were
  written on: a Keystore call or a SQLCipher keying can take a second, and `KeystoreSecret`'s retries add two.
  Wait on the condition (`waitUntil`, a polled read) under a bound sized for the emulator, never a `sleep`, and
  widen a race window's estimate to match.
- **I5. A skip reads green.** The report lists an `assume*` skip as a `<failure>`, and Gradle counts it as skipped.
  Several skip in CI by design (no clips pushed, no `videoPath`, the notification shade on a headless emulator). A
  test the change is about must not be one of them, or the change has no CI coverage: name where it does run (FTL,
  one lab phone by hand under `rules/devices.md`).

**Codec and hardware suites.** The platform codecs and hardware the JVM tests replace with Robolectric's have suites of
their own, for the class of #353 (an HEVC encoder two phones lacked, found only there). Each makes its input on the
device from a known signal and checks what comes out against it:

- `data.VoiceAudioInstrumentedTest`: a voice note's length, waveform and speech samples on the AAC decoder, from notes
  the phone's own AAC encoder made (`AudioFixtures`: ADTS as `VoiceRecorder` writes it, and a picked M4A). It found the
  waveform's empty bars on short notes; `VoiceAudioTest` pins the bucketing on the JVM since.
- `data.ImagePipelineInstrumentedTest`: `AttachmentStore`'s picker door in the app's own graph (I1): a sideways JPEG and a
  sideways HEIC with a GPS position come out upright with no metadata, a small upright JPEG passes through with its
  pixels, a transparent PNG becomes a WebP that keeps its alpha, a GIF a smaller animated WebP. The HEIC is
  `assets/codec/camera-sideways.heic`, written by `scripts/gen-heic-fixture.py` with the turn in EXIF and no `irot`
  box, as Samsung and Xiaomi cameras write it.
- `data.webp.WebpTranscodeInstrumentedTest`: a GIF (`Movie`) and an APNG (`AnimatedFrames`, the compositor) become an
  animated WebP that `ImageDecoder` plays at its size and play count and whose first frame it draws, and every later
  frame, decoded out of the app's own demux, shows the source frame's colour (`AnimationFixtures`, which writes the GIF
  and assembles the APNG itself).
- `ui.voice.VoiceRecorderInstrumentedTest`: `MediaRecorder` from the microphone into the pipe, and the microphone freed
  on every exit, by the audio service's count of active recordings (since Android 10 a second capture starts beside a
  leaked one, so opening it again proves nothing).
- `ui.call.WalkieOpusInstrumentedTest`: the walkie's Opus encoder and decoder, a known tone through `TalkRecorder.nextFrom`
  back out of `TalkPlayer`'s tap at its pitch and length, every packet a 20 ms frame and one size under CBR; then a
  burst from the microphone.
- `ui.camera.PhotoCaptureInstrumentedTest` and `ui.scan.QrScanInstrumentedTest`: CameraX capture through the photo
  screen's `jpegBytes`, and analysis frames through the scanner's `scan`, both `internal` for this, with a code drawn
  into a frame laid out as the camera's are. The emulator's analysis frames carry no row padding, so that case means
  something only on a phone.

None needs the seed or a clean slate, so they run in whichever pass takes their package: CI's unseeded pass for `data`,
its seeded pass for `ui`, and every package FTL and Device Farm take (`notPackage app.getknit.knit.uiauto`), which is
where they meet real phones' codecs. The microphone and camera suites grant their permission with `GrantPermissionRule`
and fail, never skip, on a device without the hardware (I5): the managed emulators have audio in and an emulated back
camera. A chat's file bubble draws no preview, so `PdfRenderer` has nothing to test.

There is no stress script for instrumented tests. The reviewer reads them and names the run the change owes, one class per Gradle
call (under the orchestrator a comma-separated list ran only the first):
`./gradlew :app:pixel7api33DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`, with
`-PseedDemo=true` for a seeded package. It never runs one on the lab phones.

## Mesh in a box (`app/src/test/…/mesh/lab/`)

`MeshLab` runs **N complete, real Knit stacks in one JVM** — the real `MeshManager` (so the real
`InboundPipeline`, `MeshRouter`, `ForwardSync`, `KeyExchange`, `AckSync`, both ratchets), the Room-backed
repositories over Robolectric's in-memory SQLite, a real `IdentityKeyStore` over an in-memory secret, a
DataStore-backed `SettingsStore` — linked through `LabTransport`, an in-process `MeshTransport` with no radio.
Bare, it is a link plane with no fast plane (one flood copy per link); with `pages = LabPages()` it is the
Bluetooth plane as shipped, side channel included (below). Either way a pipe carries a frame once, through
the same `LinkCrossings` memo the phone keeps (`dupSkipped` on the transport lists what it refused), on the
node's lab clock: its window is the router's seen window, so the two lapse together under a calendar jump.
A pipe's send never waits on the far router; with `LabLimits.inboundSlots` (`MeshLab.PHONE_INBOUND_SLOTS`) a link
frame that finds the far router's queue full waits on its pipe for a slot and is marked crossed only once handed over,
as a phone link's reader does, while a page takes a slot in a lane of its own or is dropped (`inboundWaited`,
`inboundHeld`, `inboundRefused`; ADR 2026-11.43f7).
The only doubles are the leaves with a hardware or UI side (notifier, tflite text moderators, image moderator).
The wiring mirrors `di/AppModule` + `di/MeshModule` by hand, so a constructor change is a compile error here
rather than a silently narrower rig.

**The link door** (#313, `LabDoor`). `MeshLab.link` registers a link with no handshake. `MeshLab.doorLink(a, b)`
runs each end's real link handshake first, over an in-memory socket pair with bounded reads (`LinkHandshake`'s
sealed form toward a build whose advert seals, the plain form with §7 otherwise, each refusal counted through
`LinkSealPolicy`), registers only when both halves linked, and then sends every frame on that link through the
sockets each half registered (through the seal on a sealed link, with `armFailureCount` armed). `LabLimits.linkDoor`
gives a node an older build's door (`LinkDoor.PROOF` for v2.11.x, `PLAIN` for v2.10.x), re-stated from the release
branches, because `LinkHandshake` writes this build's caps only; the mesh above the door stays this build's.
`dialRaw(target) { … }` writes a hostile dialer's raw records at a node's accept, and
`LabTransport.tamperNext` rewrites one raw write on a live door link. The door does not run `LinkClaims` or the
planes' backoffs, and files and custody digests still cross as objects. Scenarios: `LinkDoorLabTest`,
`MaliciousForkLabTest`'s seal cases.

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
  there, not in individual scenarios. It also checks every pair's profile and DM session; a scenario whose sender spares
  sessions a sealed profile (an audience-only mint with a MeshCore key claimed, ADR 2026-11.gsqu Stage 5b-1) passes
  `sealedTo` (holder, viewer → the newest version sealed that viewer), which accepts the presentation at that version,
  every visible field equal; without it the profile leg stays exact. `createGroup` mirrors `ContactsViewModel.createGroup` (the ViewModel
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
- **`MaliciousForkLabTest` is the modified-client set** (the malicious-fork review): one frame no build sends per case,
  and what the mesh must not do with it. A fork's identities are `LabSybil`s (fresh keys, a self-certifying profile,
  frames they sign themselves), flooded by a real node with `LabNode.flood`; `LabNode.floodSigned` sends a frame the
  node signs itself whatever it says. A crafted DM or group frame must be sealed (`LabSybil.sealedNoise`): custody
  carries those forms only sealed (`InboundPipeline.canCarry`), so a plaintext one never reaches the rule a scenario
  means to test. Its custody cases (ADR 2026-10.7mqm) size the stores so the cap binds the way the field's does
  (five-plus buckets deep): a cap only one bucket deep levels honest senders too, and a node hung off one corner of a
  triangle waits out suppressed relays until the 60 s re-offer, so link it to every corner.
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
  for the first DM to land before the reply; the DM scope derives on the confirmation itself (ADR
  2026-09.dcah — a scope a scenario waits more than the 60 s poll for is a missing hook) and a worker that
  missed an event waits for its own 60 s tick, hence `MeshLab.SPOOL_AWAIT_MS`; and the oracle found a real
  divergence — the LoRa beacon re-signing the node's own profile under the same id while a settings write
  was landing (two blobs, one id, a scope that never converges; `MeshManager.ownProfile()` is the fix).
  `awaitDmScope`'s failure message lists both custodies row by row for the next one of those.
- **MeshCore's air physics are virtual-time JVM tests, not lab scenarios** (ADR 2026-11.gsqu's Stage 5c-2a amendment).
  `MeshcoreAirRig` runs the production stack per node (`MeshcoreSession` dialling a fake board, the hooks, a
  `LoraMeshTransport` at its own default pace) over a `FakeMeshcoreAir` given a `MeshcoreChannel`; `MeshcoreAirPhysicsTest`
  runs the cases the lab's wall clock cannot: a forger for the whole fragment window (the per-side cap is a ratio of the
  window to the airtime, so the fake's own airtime is never shortened), the overtake behind a repeater, the replay once the
  ring forgets, the Q13 PATH and the drifts, a DM through a repeater. Order a race with `air.hold` / `air.release` and a
  synchronous `air.listeners` entry installed before the send, never with a repeater draw; pin a draw only with
  `channel.pinDraw`. A board with no power answers a dial after its window (the session's background mode redials at once on
  a timeout, so an instant answer spins it). The ✓✓-under-a-board-switch case is `MeshcoreFlushSwitchTest`, over a fake
  `BoardLink` that gates the send. A lab node on the fake takes `quickMeshcoreAir` (Stage 3b figures ÷ 10) on a serial
  context the lab owns.
- **A MeshCore lab node** (ADR 2026-11.gsqu's Stage 5c-2b amendment): `lab.node(name, meshcore = lab.meshcoreAir())` (or
  `meshcoreAir(channel = true)` for the firmware's channel at a fast preset, which `hold` / `release`, `evictRing`, the
  listeners and the Q13 PATH need). The plane is the production child, never the boards sweep's; boot returns at Ready with
  the record's consent (the first boot runs the real setup) and the claim minted — since Stage 5d the board's bind of the
  node id too (`MeshcoreSession(ownNode = { nodeId })`, the fake board signs it as it signs the consent challenge), since a
  key without one claims nothing. A node with no board on the air claims one through `LabNode.driveMeshcoreBoard(seed)`,
  which signs the bind with `MeshcoreBindFixture`; a forger seals another node's claim through
  `MeshManager.meshcoreClaimForTest`. Edit the topology only through
  `air.meshcoreEdit { … }` (the air's serial context) and read it through `air.snapshot { … }`; `MeshLab.meetOverMeshcore`
  makes nodes contacts over a link and parts them, `awaitAcquainted` then `awaitTaught` each such pair (a link alone teaches
  nothing; taught on the board, its route read, and the plane holding the peer direct with its pair seal's index built,
  `LabNode.meshcoreReadyFor` — a restart rebuilds both after its Ready), and `LabNode.awaitMeshcoreBack` waits out a board
  that went away (`LabLimits.meshcoreBackoffMs` shortens its redial). Three clocks: the session's is monotonic, so a
  jump moves the plane's decisions (the re-probe gap, the fragment window, a hold) and never the session's waits. Wait on
  the receiving side's event — `loraBoundPartsTaken` at the receiver's plane, `meshcoreRouteReads`, a counter or the board
  read back (`meshcoreCommands`, `meshcoreWrites`) — never on `air.airings`, which is written before the receiver's board,
  session or plane has run. `MeshcoreRoomLabTest`, `MeshcoreDirectLabTest`, `MeshcoreProfileLabTest`,
  `MeshcorePublicLabTest` and `MeshcoreMixedFleetLabTest` (a Meshtastic and a MeshCore fleet on two airs, Stage 8) are the
  set. Lose one flight's board ack with `FakeMeshcoreAir.loseAck`'s `(acker, sender, attempt)` key and a one-shot guard;
  wait for a post's hearing to be judged on `loraHearingsJudged` before reading that it lifted nothing. The last is the Public room (ADR 2026-11.gsqu's Stage 6 amendment, part 6c): the node
  wires the room's hooks as `di/MeshModule` does, `LabNode.postPublic` posts as the composer does, and a
  `StockMeshcoreNode` posts plain `name: text` on slot 0 under any name (it waits for a free packet: a burst past the
  board's pool is refused and never airs). Await a heard post at `LabNode.meshcorePostsHandled` (the pipeline returned),
  since a duplicate writes and notifies nothing. Take a board away with `LabNode.meshcoreOutOfRange`, never `powered =
  false` across a calendar jump: a reboot's pre-boot term holds the pacer for up to an hour. A mirror room takes no
  receipts, so its own rows fail the oracle's tick leg: run the oracle on Nearby over a link. And on a node with a board,
  `awaitAcquainted` does not wait for its manager's link-up pass to finish: a frame count read right after a meeting can
  still move from that pass, so compare what crossed or what a node without a board originated. `MeshcoreForgetLabTest`
  (Stage 7a) forgets through `LabNode.forgetMeshcore`, wired as `di/LoraWiring.meshcoreForget` is; the withdrawal reaches
  the contact over a link only (a profile never rides the board), so link before awaiting their view of the key.
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
  `setAvatar` (`ProfileViewModel`), `block` / `unblock` / `accept`, `removeContact` (the real
  `ContactRemover`, ADR 2026-09.adgd), `sendImage` (bytes straight into the
  blob store under their hash — `AttachmentStore.ingest` collapses every picture into one placeholder hash
  under Robolectric's legacy graphics), `mintCard` / `importCard` (`ContactCards` + `ContactImporter`),
  `resetSession`, `heal()` (runs the heartbeat basket and returns once it has run to its end — `healsCompleted`), `sweepExpired()` (the TTL sweep the prune
  loop and the heartbeat run — custody, parked frames, key and blob wants — awaited, for after a clock jump),
  `wipeCustody`. Readers beside them:
  `reactions`, `group` / `groupShape`, `attachmentHash` / `attachmentHeld` / `attachmentScreened` /
  `attachmentPlain`, `peer` / `presentationOf`, `session`, `selfAddressedCustody`, `notices`, `rowsIn`,
  `custodiedChatsFrom`, `scopeStatus`, `loraLine`, `isContact` / `isRequest` (the picker's and the requests
  inbox's rules). If a ViewModel's write sequence changes, change the mirror.
- **`LabTransport` stages a received file in the receiver's own directory** (`File(dir, "rx")`) before
  emitting it: `MeshBlobStore.saveIncoming` deletes what it reads, and two receivers of one blob handed the
  sender's single temp path raced for it, the loser dropping the blob silently. It also has a per-pipe loss
  knob (`lossy(to) { drop }`) and a `held(to)` peek so a scenario can wait for a relayed frame before releasing.
  Files have their own hold (`holdFiles(to)` / `heldFiles(to)` / `releaseFiles(to)`, ADR 2026-09.4tx5): a
  parked file is staged with its header across, so the receiver reports it in `arrivingFiles()` (at zero
  bytes, no total) and the sender in `fileInFlightTo` — the slow BLE transfer a scenario can hold a re-ask
  against. `stackFiles(to)` parks the next files fed but unseen — neither arriving nor in flight — the photo a
  holder that cannot see its own stack has fed behind the one streaming in (ADR 2026-10.nxfb); arm `holdFiles`
  first. `dropFiles(to)` loses what is parked with the link still up (a push the link took and never delivered, #258;
  `PeerFaceLabTest`). `fileArrivingFrom` reads whether a held, arriving file from that peer is parked. A delivered or released
  file stays in `arrivingFiles()`, landing, until the receiver's consumer has stored it (`fileTaken`, #138), so
  await `blobs.exists(hash)` before asserting a hash has left it. `LabNode.reoffer()` runs
  one 60 s re-offer round now, the tick a lab clock jump never wakes. Every `sendFile`
  is logged in `files` as `to kind key` (the phone's `file …` line: one copy per (hash, link)), and
  `digestsSent` names whom a node advertised its digest to. A link-up pass sends it after the blob re-arm and
  re-ask, so a re-link "as the 60 s re-offer would" waits on it before asserting what the re-ask did not send; the
  key re-ask, the tick re-offer and the seed flush come after it, and `awaitNeighborsHandled()` waits for the whole
  pass (#201). `awaitDigestsAnswered()`
  waits until a node has answered every digest a peer advertised to it (the reply's frames are sent), so with every
  far end's `awaitInboundDrained()` after it the exchange has landed: read custody agreement there, not on the first
  poll that agrees, when the cap binds. A reply computed before an eviction is still on its way when the stores
  first agree, and the frame it carries trims a store past the window (#141). `sent` records the
  peer as `nodeId.take(6)`, not the full id.
- **A cut transfer is `streamFiles(to, untilBytes)` plus `lab.unlink`** (ADR 2026-10.wtyc). A streamed hold
  feeds the receiver's real `FileIntake` the header and the blob up to the mark, then parks (the receiver's
  `arrivingFiles()` reports the real count); `releaseFiles` lands the rest, and `disconnect` — what `lab.unlink`
  runs — cuts it, so the receiver's `PartialBlobs` (`LabNode.partials`, purged by `start` as on a phone) keeps
  the prefix. A resumed send (any `FileMeta.offset`) is spliced by the same intake, so the lab runs the
  production splice; only the slicing is the lab's. Plain `holdFiles` parks stay zero-byte and keep nothing on
  a cut. `ignoreOffsets` makes a holder an older build (whole files, no offset in the header) and
  `corruptFiles` a holder with wrong bytes; `blobAsks` records every `blobreq` that crossed (to, hash, offset);
  `files` lines end ` from <offset>` on a resume (recorded once the send commits), so an exact match still counts
  whole copies; the asks do not. A want step that lands after a link comes up and before the node's link-up pass
  (a late re-arm, a tick) asks the newcomer, and the pass's `BlobExchange.linked` forgets that ask and asks again,
  which the holder's memo answers with nothing (#141, chaos seed 141714776): read `blobAsks` with a run of equal
  offsets counted once (`AttachmentResumeLabTest.askedOffsets`), and await the ask you need, not a count. A
  link-up's own re-ask is answered before a scenario could arm after it: arm in
  `lab.link(a, b) { … }`, which runs once the pipes exist and before either end lists the link. And a carried
  attachment's `blobreq` leaves from the custody step, before the frame is opened, so a holder serving it is no
  evidence the recipient handled the frame: await the author's receipt before the link goes.
- **A direct link has its own knobs** (`LabDirectLink.kt`, 2026-10-27). `lab.directLink(a, b)` returns the link's
  handle; `hold(from = b)` parks every datagram `b` sends on it — its asks, answers and the stream's acks — until
  `lab.cutDirectLink(a, b)`, which drops them and ends both streams, then waits as `unlink` does (neither plane holds
  the link, both managers acted on it). There is no release: a link comes back as a fresh `directLink`, as a re-punch
  would. The link plane's files sent are `LabNode.linkFiles` (`to kind key`, the radio's `files` line, kept across
  restarts), its frames handed out for a linked peer `LabNode.linkFrames` (`linkUp to id`, `linkUp` being
  `LabNode.linkUps` at the send: count a custody push's crossings per link-up there, since the receiver's seen window
  hides a repeat), and the digests it took `LabNode.linkDigestsFrom` (on arrival, ahead of the composite's merge, so
  never proof one was answered). Its blob asks are `LabNode.linkAsksOut` (`linkUp to id`, as sent) and
  `LabNode.linkAsksIn` (`from id`, as handed up): an ask that crossed can still wait in the holder's router, so await
  it handled (`blobAsksHandled`) before a cut and a jump past `SERVE_MEMO_MS`, or it lands after the jump and is served
  again (`DirectLinkAttachmentLabTest.awaitAsksAnswered`). `LabNode.linkFileGate` holds a file the link landed before the manager takes it — still landing on
  the plane (#138) — so a scenario can stop a node mid-hand-off: `restart { linkFileGate.release() }`.
  `DirectLinkAttachmentLabTest` is the set (a DM photo over a link, proof and spool pushes). A composite merges its
  radio sets (`shortRangeNeighbors`, `shortRangeReachable`) on collectors of their own, so `awaitLinksActedOn` waits
  for those too: read stale under chaos, a radio neighbour that had just linked ranked as a link's in a blob ask, and a
  peer the radio had lost was stamped a radio sighting after a calendar jump, holding a photo for the whole ack grace.
- **A node on the lab's Internet runs the real `DirectLinks`** (`LabInternet.kt`, 2026-10-28). `lab.node(…, spool =
  FakeSpool(cast = …, stun = listOf(LabInternet.STUN_URL)), internet = FakeLinkNet.Nat.X)` puts the node behind its
  own NAT of kind `X` on one lab-wide `FakeLinkNet`: offers and answers cast through the relay, the punch, the lane,
  and the stream handed to its link plane, as a phone does. `LabNode.links` is that node's `DirectLinks` (`offer`,
  `lastTries`, `links`), `LabNode.demand` its `LinkDemand`, never started (a scenario runs `round()` after advancing
  the clock past the tick wait), `linksOn` its switch and `linkLog` what the links logged. The punch, its give-up and
  the idle close read the node's clock, so `lab.clock.advance(LinkSession.IDLE_MS)` closes an idle link at the
  session's next tick (≤ 5 s), and a punch meant to fail ends at its next probe round on a jump of
  `PunchPolicy.GIVE_UP_MS` taken once the offerer's first probe is out (`LabNode.linkDatagramsSent` moved), rather than
  sitting out its ten seconds; the streams' ARQ reads a monotonic clock, which no jump moves. `FakeSpool.mute()` keeps
  the relay connected and fanning out casts while it takes nothing else — a link that is the only path. The set is
  `InternetLinkLabTest`, and `LinkDownloadLimitLabTest` for the download limit over a link (`LabNode.metered` is the
  phone's network as `InternetGate.isMetered` reads it, for its blob exchange and its links' `m` alike), and
  `LinkBesideRadioLabTest` for a free link beside a radio (#236): `LabInternet.phone(nat, external)` puts two nodes
  behind one NAT on one LAN, where `FakeLinkNet` routes their host candidates and hairpins their reflexive ones, and
  `FakeLinkNet.lanDown` isolates the LAN's clients so only the hairpin answers.
- **Wait for the frame, not for the state that precedes it.** A settings or DB write the scenario can see
  lands *before* the frame it triggers reaches the transport (`broadcastProfile` bumps the version, then
  stamps, signs and floods), so "the version moved" is not "the frame is parked": `ProfileUpdateLabTest`
  released one profile short on GitHub (2026-09-16). Poll `held(to).count { it.isProfileFrom(…) }` (or
  `isRoomPostFrom` / `isChatFrom`) before a `release` whose batch the scenario asserts on. The user-level
  sends (`sendDm`, `sendGroup`, `react`, `createGroup`) hand their frames to the transport before they
  return — `MeshRouter.sendOwn` awaits `transport.send` — so those need no wait; the profile edits
  (`setDisplayName`, `setStatus`, `setOpenToChat`, `setAvatar`) go through the manager's watcher, so
  `LabNode` waits for the publish itself (the version moved and custody holds the frame under the new
  stamp) before returning, and a no-op edit returns at once. A `RoomTickPlanesLabTest` node once minted
  its rename's frame after the scenario had already cut the link.
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
  `distributeGroupSeed` is inline — to the transport before returning (but see the composite caveat below); the fake air decides loss inside the
  board's `send`, before the `lora tx` line a scenario polls; `skipCovered` runs at enqueue, so `loraSkipped*`
  moves before `sendChat` returns; `onFrameReplayed` and `onKeyRecovered` precede the replayed delivery; a
  `groupleave` envelope carries no `group`, so `sortedBy { isGroupFrame() }` does put the leave first. And
  `published`'s custody row is durable: `broadcastProfile` custodies the new stamp under the lock before it
  originates, so a link cut in the microseconds before `transport.send` strands nothing the next exchange
  cannot serve (a transport-level flood record would not do — the composite fans a flood per neighbour and
  never hands a board node's flood to the child at all). One latent gap: after a `restart()` across a jump
  past the 12 h republish, boot's wait passes on the old row before the seed runs, and an edit made next can
  be folded into the seed's republish with no version bump, so `published` would time out; no scenario edits
  there today.
- **On a composite node a send has a second, launched copy.** A node with a board or a link plane beside its radio
  runs its manager on `CompositeMeshTransport`, whose `hasFastPlane` is true, so `MeshManager.originateWire` also calls
  `fastSend` (a DM-form chat) or `fastFanout` (room posts, custodial ctl). For a child with no fast plane of its own (the
  radio `LabTransport` without `pages`) the composite *launches* a second `child.send`, which can reach the pipe after
  the user-level send returned. A lost frame records no crossing, so that copy goes through once the loss is cleared:
  `ReofferLoopLabTest` cleared `lossy` right after `sendDm` and failed 4 of 4 boards shards (2026-11-01). On a phone the
  two copies are one write on a live link and a harmless retry otherwise. Keep a loss or a hold on until past the
  assertion it protects; never clear it straight after the send. A *relayed* frame has one too: the composite re-fans
  what it delivers (`InboundPipeline.onDeliver` → `fastFanout`) as a launched flood over every pipe, the author's
  included. The receiver's crossing memo skips the copy toward the author, unless a calendar jump past the seen window
  lands first: then it crosses back and re-stamps the author's memo, and the author's own serve is skipped as a repeat
  (#316, `TimeLabTest`).
- **A boards sweep's one air joins every island.** `KNIT_LAB_BOARDS=1` puts every board on one shared air, so a
  scenario whose subject is two islands no radio reaches (`DirectLinkLabTest`, `InternetLinkLabTest`,
  `LinkDownloadLimitLabTest`) builds those nodes `board = false`: otherwise a DM rides the air beside the link the
  scenario is about, and a stranger's room post heard over it parks for a key no island can bring (ADR 2026-10.rp3s).
  So does a scenario that parks a carrier's pipe as a node's only way in (`MaliciousForkLabTest`'s `line`, 2026-11-14):
  the author's compact DM rides the air around the hold, so the fork's copy under its id never lands first (3 of 3
  boards runs), and the carrier's own posts fan out over it in real time, after a jump past the seen window. So does
  a scenario whose subject is one link's crossing memo across a jump
  (`TimeLabTest.aFrameThatCrossedALiveLinkCrossesItAgainPastTheSeenWindow`): the relayed re-fan above crosses the
  lapsed memo, and the post's board copy, queued behind the receiver's own delivery, can arrive past the jump and
  satisfy the oracle with no link crossing at all. And so does one that re-links across a jump with a frame it must
  not see again (`TimeLabTest.aDmReceivedWhileBlockedStaysUnseenAfterTheUnblock`): the composite's launched `fastSend`
  copy of the DM, stalled past the re-link that forgets both memos, crosses the fresh pipe into the lapsed window.
- **Behind a composite, a drain is counted at the router** (2026-11-01). A node with a board or a direct link plane
  runs its manager on `CompositeMeshTransport`, whose `merge` takes each plane's frames on a channel, so the plane's
  `emit` returns when the frame is queued, not when it is handled. `awaitInboundDrained` then counted a frame
  handled while the router still had it queued. The radio's count now moves at the router: `MeshRouter.onHandled`, by
  way of `MeshManager.onInboundHandled`, calls `LabTransport.frameHandled` for each of the radio's frames once
  `handleInbound` returns (`handledDownstream`, set at the boot), and its `awaitDigestsAnswered` likewise at the
  manager (`MeshManager.onDigestAnswered` → `LabTransport.digestAnswered`). Both match the radio's frames by the plane
  the composite stamps (`kind` / `via` equal to the radio's own, `TransportKind.Other`), so a new composite child must
  report a kind of its own or it is counted as the radio. `awaitNeighborsHandled` is still early behind the
  composite's `combine`. Before it, a boards sweep failed two ways. A
  `lab.unlink` cut the link while Bob still had Alice's DM queued, so his ✓✓ was sealed after the cut, and Alice's
  reset overtook it (#260's gap; `ScopeConfigLabTest`, 6 of 8 shards). And `AvatarCeilingLabTest`'s `cycle` jumped
  the calendar while a holder still had Bob's ask queued, so Bob asked again and was served two copies.
- **Two legitimate orderings are not a state to await.** `AttachmentLabTest`'s #79 scenario awaited Carol's
  copy of the blob and then asserted she never served Bob — but Bob then asked every neighbour at once, and whether
  Carol holds the bytes when his ask reaches her is a race the mesh does not decide (her pull is two hops and
  a file write; his ask is one hop behind a DM decrypt). On one slow core her serve of that *fresh* ask —
  correct under ADR 2026-09.4tx5 — read as the wanter push (GitLab job 4939). Where a scenario's assertion
  presumes an order, pin it: `hold` the frame that would let the other order happen (Alice's frame to Carol,
  so Bob's ask is the first Carol hears of the hash — the ask is emitted inside `onDeliver`, before the relay
  is scheduled), and release it once the assertion is in. (Since #136, ADR 2026-10.f7gg, Bob's first ask goes to
  Alice alone, and that scenario counts asks instead; a blob round's grace is wall time, so the lab lengthens it
  — `LabLimits.firstAskGraceMs` — and widens a round with a clock jump and a tick, never by waiting it out.)
  The holder query (ADR 2026-10.6e5t) is off in the lab unless a node boots with `LabLimits(blobQuery = true)`;
  every lab node advertises `CAP_BLOB_HAVE` and answers, except one booted with `answersHolderQueries = false` (the
  iOS port). A querying node's choice waits for every queried neighbour's answer (`LabLimits.queryWaitMs`, 15 s), so
  a query parked on a `hold`-ed pipe stalls the asker's choice for that long: link the asker once the state it
  should find is in place (`BlobHaveLabTest`), and move a FETCHING recheck or a cycle with a clock jump and a tick.
  `LabTransport.blobQueries` / `blobAnswers` record both frames on the sender, as `blobAsks` does. A holder parks an
  ask behind another copy's feed for as long as it is live (ADR 2026-10.g24x, 2026-10.nsr7), and a `holdFiles` pipe is
  a feed that never ends, so `LabTransport.fileFeed` reads a held head as stalled `LabLimits.feedStallMs` (2 s) after
  it was first read; a scenario about parking boots its holder with a long one
  (`PictureFanoutLabTest`). A carrier linked to a DM's recipient takes no copy of its picture until a jump past
  `CarrierPullPolicy.DEFER_MS` and a tick.
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
  `ForwardRepository`'s constructor) and the LoRa linger sweep (`loraLingerSweepMs`, which also runs the re-probe).
  Give every node in a scenario the same custody numbers — the digest is folded over what they keep.
- **The second batch of classes** (2026-09-14, 45 scenarios): `GroupMembershipLabTest` (leave-rekey, the
  self-rejoin of ADR 2026-09.v6fu, the rejoiner's seed rejoining them itself since ADR 2026-09.mjaj — it used
  to park under "sender departed" — the leave and rejoin as LWW clocks that outlive their notice lines (the
  sweep may take those), a member who missed the leave,
  concurrent renames), `ReactionLabTest` (DM / group / room forms, a retraction
  crossing its reaction, a reaction ahead of its DM, a group reaction founding the group), `ProfileUpdateLabTest`
  (rename to a contact and a stranger, a re-served older profile, status + flag surviving a sealed update, an
  avatar), `AttachmentLabTest` (image DM, carried bytes, room image screening, group photo, a straggler
  still pulling a new group photo, a group photo pull lost to a restart),
  `KeyExchangeLabTest` (`keyreq` + `PendingInbound`, `pendingKey` retransmit), `BlockAndRequestLabTest`,
  `SessionLabTest` (both-initiate in both orders, a forced reset, old-era custody after a reset, the group
  key request), `RestartLabTest` (sender / carrier / recipient-with-a-park / both sides), `TopologyLabTest`
  (diamond, five-node line, ring partition, eight in a room), `CustodyQuotaLabTest` (per-sender, per-group,
  full store from the largest sender, the room quota and the cap converging within three seen windows), `InternetPlaneLabTest` (the two-island group + departure, a group founded while a member is relay-only
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
  targets read *then*. In a scenario that unlinks a node, sends and re-links, a relay filed before the link
  came back can count the newcomer among its targets and hand it a frame custody has already evicted
  (`CustodyQuotaLabTest` flaked exactly so). A count of the carrier's relays since a baseline is no sync:
  a relay of an earlier frame still in its jitter when the baseline was read lands inside the count. Wait on
  the store instead (`CustodyQuotaLabTest.awaitCustody`: custody holding exactly the newest frames), and re-link
  with the air losing the carrier's relays (`connect(…, lossy = { it.hops > 0 })`). What the newcomer must get
  then crosses as a custody serve, re-wrapped at hop 0, on the next digest exchange. When a late relay must
  only come *after* something rather than never, hold the pipe inside the link instead
  (`MeshLab.link(a, b) { a.transport.hold(b.transport) }`: `arm` runs before either end lists the link, so
  no relay can name the peer ahead of the hold); `CustodyLabTest`'s receipt-first walk uses both shapes.
- **First contact by board alone never happens**: no profile rides the board since ADR 2026-10.rp3s, so a stranger
  heard only over LoRa is never shown. LoRa scenarios meet everyone by radio first (`meetThenSplit`), then cut the
  cross-pocket links. A board airs only its own phone's frames, so a scenario asserting what crossed reads the
  author's own board (`loraAired`, `loraTx`), never a pocket-mate's.
- **Boards in every scenario is a manual sweep** (ADR 2026-10.x9fa): `KNIT_LAB_BOARDS=1` gives every node a board on
  one shared `boardsAir` (a `quickAir`, and a DM ✓✓ hold capped at 1 s), so a scenario written for links alone also runs with compact DMs and ticks
  racing its signed copies — the restore and session shapes that first showed the answer gate's need. A node that must
  stay board-less in that sweep (one whose subject is a signed-only path, or a carrier) says `lab.node(name,
  board = false)`. CI runs it three times (GitHub `mesh-lab-boards`, GitLab `test:mesh-lab-boards`) through
  `-Pknit.labBoards=true`, which sets the variable on the Test task as a declared input and leaves out the classes
  `app/build.gradle.kts` lists as not board-ready yet (`labClassesNotBoardReady`); take a class off that list once it
  passes. By hand, after a change to the crypto or LoRa planes: `scripts/lab-chaos.sh --boards --tests SessionLabTest
  --tests RestoreLabTest --tests LoraDirectLabTest --runs 10`. `--boards` passes the property to the chaos shards, so
  they leave out the same classes, and its stamp records the mode, so `--reuse` never answers a boards request from a
  plain sweep or the reverse. `KNIT_LAB_BOARDS=1` in the environment counts as `--boards`. A LoRa scenario's own air is
  `MeshLab.quickAir()` — the destination board acks in 50 ms and an unanswered unicast retries every 300 ms, the
  firmware's minute compressed — and its boards come up provisioned on `MeshLab.KNIT_SLOT`, since a compact DM is never
  sent from slot 0. `FakeMeshtasticAir` runs a want_ack unicast on its link's scope and only once `send` returned, so a
  board's outcome never reaches the transport before the flight it names.
- **A DM that arrived over the board is ticked through `DmAckCoalescer`'s 45 s hold** (ADR 054). A LoRa DM
  scenario whose oracle waits for that tick boots its nodes with `LabLimits(dmAckHoldMs = 1_000)` and keeps the
  default oracle timeout, never a 60 s one.
- **Every sealed frame carries the epoch key**, so an absent recipient opens whatever survives a custody
  quota without the conversation's first frames and asks for no reset — the quota is loss, never a wedge
  (`CustodyQuotaLabTest`; the "evicted init needs a reset" premise was wrong).
- **The calendar is a knob too** (`LabClock`, 2026-09-14): every node reads one shared clock the lab owns,
  and `lab.clock.advance(ms)` moves it for all of them at once — so nodes still agree with each other (the
  property `spaced {}` and ADR 2026-09.gdhp rely on) while a scenario steps past the router's 10-min seen
  window, the 24 h custody TTL, the 12 h profile republish or the 48 h intro grace (`TimeLabTest`). A jump
  moves *decisions* (expiry, floors, LWW, tombstones, presence, the pipes' crossing memo), never *schedulers* —
  every periodic loop is `delay()`-based and blind to it — so the scenario pokes the work itself: `node.heal()`
  (fire-and-forget: await the effect), `sweepLocalStorage()`, `manager.refreshRelays()`, a re-link or
  `reoffer()` for the digest exchange, or a fresh send. A frame that crossed a live link goes back over it
  past a jump (`TimeLabTest.aFrameThatCrossedALiveLinkCrossesItAgainPastTheSeenWindow`), as long as no node's
  `LabClock.skew` changed with its links up and no launched copy of it is still in flight (a composite node's re-fan,
  #316: build the pair `board = false`). Where no poke reaches the timer, the node boots with it shortened
  instead of the scenario sitting it out in real time: `LabLimits.calendarRounds()` cuts the Internet plane's timed round (60 s) and
  attachment re-look (15 s) to a second, `dmAckHoldMs` the LoRa DM ✓✓ hold (45 s, ADR 054). Both stay at the
  field values by default, so the other spool scenarios still converge on the events, and a lost wake shows as
  a slow scenario rather than a pass. A timer no poke reaches without a jump is cut alone: attachment presence is
  only asked (`ahave`), so a first look before the upload lands waits out the re-look, and
  `RelayScreeningLabTest` shortens `spoolAttachmentRetryMs` only, keeping the timed round at the field value. An oracle that needs the jump goes after it: `assertConverged`'s
  attachment half waits for bytes a deferral holds until the grace a jump ends (2026-10-04, a 60 s scenario
  that was waiting out `ACK_GRACE_MS` on the wall clock). Forward only. The seam is `MeshManager(clock = …)` plus the one-liners that hand the same
  clock to `ForwardSync` (load-bearing — its `now` is what `ForwardRepository.store`'s dead-on-arrival and
  future-skew guards compare a frame's `sentAt` against), `KeyExchange`, `BlobExchange`, `PendingInbound`,
  `PendingGroupKeys` and the router's `SeenSet`; production passes the wall clock everywhere. **Not** the
  ratchet `now` in `InboundPipeline`'s two open paths (`peekOpen`/`commitOpen`, which stamp a receive
  epoch's `lastUsedAt` and a skipped key's `createdAt`): those stay on the wall clock, because the 226-test
  pipeline rig pins the ADR 023–027 era semantics against a fixed clock and eleven of its cases read the
  ratchet's real time. The cost is a bound on the jump: **keep it under 48 h**, or `heal()`'s
  `ratchet.sweep(clock())` reaps receive epochs and skipped keys stamped a real day ago as though they
  were two days stale. Two of the basket's steps are themselves behind a floor on that clock (ADR 2026-09.6st4):
  the three key-retention sweeps and the group-custody replay run on a `heal()` only once
  `HealFloors.RETENTION_SWEEP_MS` / `GROUP_REPLAY_MS` (an hour) has passed since the last run, and the node's start
  stamps both — so a scenario that wants either from a heal jumps the clock past the hour first (every existing
  `heal()` caller already jumps ≥ 12 h; `LabNode.heal()` still returns on `healsCompleted`, floored or not). A
  profile republish seeds custody rather than flooding, so a scenario that `heal()`s
  past `PROFILE_REPUBLISH_MS` re-links before its oracle. `LabClock.skew(name, ms)` is the one seam to a *disagreeing*
  clock, for a far-future-frame scenario (a skew past `Protocol.MAX_FUTURE_SKEW_MS` trips the custody
  refusal and `clampFuture`). Side effects of a long jump: `IngressBudget` refills, the send epoch rotates
  on the next seal (`MAX_EPOCH_AGE_MS`), a 7 d jump rotates the prekey and refloods the profile on `heal()`,
  `deleteOrphans` reaps at 24 h, `sweepRetention` drops unaccepted request threads at 7 d.
- **Order is a knob.** `alice.transport.hold(bob.transport)` parks what Alice sends Bob;
  `release(bob.transport) { reorder }` delivers it in the order you choose — how "custody serves the two in
  either order" becomes a deterministic case. Partition (group frames first, say) rather than blindly
  reverse: the intro driver's own `CTL_PROFILE` DM can land in the window.
- **A send never waits on the far router** (2026-11-14). A pipe hands a frame into the far node's inbound queue,
  which has no bound, as the queue a phone's link keeps on the way out has none (`FramedLink.send`). With its old
  bound of 1,024 a `send` into a full queue waited for the far router, and two routers each sending the other a
  tick waited on each other for good: a mobility simulation's 48-walker gather left 15 to 21 DMs of 270 unread in
  a linked recipient's queue (`InboundBurstLabTest`). `LabNode.burstSigned` puts thousands of frames at one node with one
  chaos lag ahead of the lot (`LabTransport.sendBurst`), for a scenario about how many wait there and not how they
  interleave. The lab drops nothing there either. `BluetoothMeshTransport` and
  `WifiAwareTransport` hand the router frames with `tryEmit` into a buffer of 256, so a frame that finds it full
  is lost. `LabLimits.inboundSlots` plays that for a node (`MeshLab.PHONE_INBOUND_SLOTS`: the plane's 256, the frame
  the composite's `merge` holds, its channel of 64 and the frame in hand), counted in `LabTransport.inboundRefused` and pinned by
  `InboundBurstLabTest`. A plain lab link has no airtime, so a burst reaches the queue faster than a radio could bring
  it: what a bounded node drops is an upper bound on what a phone would. On the simulator's `ble1m` air the queue
  never held more than two frames (the Bluetooth result under "Mesh simulator"): the backlog waits on the links' air,
  not in the router. A fast plane (Wi-Fi Aware) is not modelled yet.
- **An air link has a rate** (`LinkAir`, `lab.link(a, b, air = …)`, `linkAll(…, air = …)`). Without one, the default
  every scripted scenario keeps, a send hands the frame to the far end before it returns. With one, the pipe queues
  what it is handed, as `FramedLink.send` does, and a writer per direction airs the queue in order at the link's rate.
  A node's `NodeAir` (`LabLimits.nodeAirBytesPerSecond`) is one radio shared by all its air links, in and out. The link
  and each radio are queues of their own (`AirLedger`): a record is through once all of them have served it, so a radio
  is never held idle for a neighbour's busy one (a slot booked jointly dragged a dense gather to a third of its rate;
  `AirLedgerTest`). Slots are reserved on `System.nanoTime`, never the jumpable lab clock. Frames and custody digests are aired (one pending digest
  per link, replaced in place); files still land at once. The memo mark and the enqueue are one step under the pipe's
  air lock, and a cut (`disconnect`, or a `connect` that replaces the pipe) takes that lock before either memo is
  forgotten. It loses every queued record and the one airing, counted in `LabTransport.aired` (`cut`, `cutKeys`), and
  nothing is delivered once it returns. `awaitInboundDrained` waits out what is on the air toward a node
  (`airInboundNow`); `awaitAirDrained(to)` waits for what one link had queued when called. `unlink`, `restart`,
  `restoreFromBackup` and `pauseMesh` refuse an air link, since whether its queue drains or is cut would be a race:
  cut it with `transport.disconnect` and say so. Pinned by `LinkAirLabTest`; the simulator's `ble1m` radio uses it.
- **Restart is real.** `node.restart()` tears the live stack down and rebuilds it over the same identity, DB
  and settings — every in-memory structure (`PendingInbound`, `PendingGroupKeys`, ratchet caches, seen set)
  starts empty.
- **A pause keeps the process.** `node.pauseMesh()` / `resumeMesh()` are the notification's Pause and Resume (ADR
  2026-09.wz99): the same `MeshManager` stops and starts again, and every in-memory structure the process holds
  (`BlobRepository`'s stored-this-process marks, `BlobExchange`'s memos) carries over, which a `restart()` cannot
  show. The links go as a restart's do, the direct links stay held off, and `MeshLab.link` refuses a paused node.
  `resumeMesh` returns once the planes are collected, with the start's sweeps still running (`startSweepsDone`).
  `MeshPauseLabTest` is the home for what one process carries across a pause. The pausing node's own departure hooks
  may or may not run before its session is cancelled (production's `applyPause` races the same way), and a direct-link
  peer's close is not awaited; a scenario whose oracle depends on either must pin it itself.
- **A departure is observed, not timed.** `neighbors` is a conflating `StateFlow`, so a link taken down and
  brought back while `MeshManager.watchNeighbors` is still inside its body is a link that never went down to
  it — no newcomer, no profile push, no digest exchange. `LabTransport` records the generation each collector
  was last handed, and `unlink` / `restart` return only once every collector has been handed the departure
  (`awaitNeighborsObserved`); the 100 ms `SETTLE_MS` stays only for a node with a board, whose manager reads
  the composite's own `StateFlow` on top. Don't reintroduce a bare `delay` for this.
- **`awaitAcquainted` means the whole link-up.** Pinned keys *and* custody parity among the nodes, then every node's
  newcomer hooks returned, then what they sent handled. On a node without a board, a scenario may cut, hold or count
  on the link the moment it returns; on a node with a board the newcomer-hooks wait returns early (see the MeshCore lab
  bullet), so count only what crossed or what a node without a board did. `RoomTickPlanesLabTest` cut the link on the pins alone and, on one throttled core, stranded two of
  Alice's profile stamps on her side with only the air left to carry them (the LoRa gossip is minutes); the oracle
  then waited on custody forever. Parity alone was not enough either (#201): `watchNeighbors` runs the key re-ask,
  the tick re-offer and the group-seed flush after the digest, and a flush still running re-sent a seed into
  `SessionLabTest`'s hold and stamped the seed floor (#141's chaos seed 804399168). So `awaitAcquainted` waits for
  `awaitNeighborsHandled()` on every node first, then has every node answer its digests (`awaitDigestsAnswered()`)
  and drain its inbound, again until a pass hands none of them a new frame or digest, within `AWAIT_MS` (an inline
  answer, such as a DM receipt or a digest reply's frames, lands too). The digest half came from the 20-run sweep of
  this change: a link-up digest's reply reads the store when it is computed, so one a stall held back served
  `ReofferLoopLabTest` the DM it sent next into a pipe it had just made lossy (seed 297158421; 2 in 200 runs on the
  parent tree, none on this one). Launched sends (a relay in its jitter, a debounced tick) are not covered. A node
  with a board gains nothing from these waits: its manager reads the composite's `combine` and `merge`, so they
  return early, and it waits for its board to hold the radio's links instead.
  It also waits for every node's delayed presence-key pass (`awaitPresencePasses()`, ADR 2026-11.xp6d): the link-up
  pass runs `LabLimits.presenceLinkUpDelayMs` after the neighbour pass, and a restore's link-owed reset rides it, so a
  scenario that held or counted the link straight after acquaintance could catch or strand it. A node whose link-up
  wait is at least `AWAIT_MS` (the mid-link capability case) is skipped on purpose.
- **The CI runner is one slow vCPU**, and every relay jitter, tick and seal ordering a scenario assumed on a
  24-core box is randomized there. Reproduce a CI-only flake with the throttled loop, not on all cores:
  `systemd-run --user --scope -q -p CPUQuota=50% taskset -c 0 ./gradlew :app:testDebugUnitTest --tests '…' --rerun --no-daemon`
  in a loop, tallying the per-class XMLs; isolated classes flake less than the whole package, so validate
  with `app.getknit.knit.mesh.lab.*` three or four times over. The throttle slows the test; never crowd the host with background load instead
  unless the user authorizes that run (`rules/build-and-test.md`, "Never load the machine").
- **The lint comes before the chaos** (`scripts/lab-lint.py`, 2026-10-05). The flake shapes the source text
  shows are found in a second, not a sweep: a `delay`/`sleep`/`withTimeout`/`lab.settle()` used as a sync point, a
  `tryAwait` whose Boolean nobody reads, a pipe held again after a `release` that let go of it (a wait in between
  widens the gap, it does not close it; a loop's next pass counts), a `release { … }` that may drop a frame (anything
  but `reversed`/`sorted*`, a matched filter/filterNot pair or a partition whose halves are both used) with no
  re-link before `assertConverged`, a direct `transport.disconnect`, a relay-decision count
  (`framesRelayed`/`framesOriginated`/`framesSuppressed`) read inside a wait (#119), clock jumps past 48 h — or one it
  cannot size — before a `heal()`, `restart()` or `restoreFromBackup()` (each runs the ratchet sweep), an `@Ignore`
  with no `#NN`, a scenario that never reaches `assertConverged` (a helper in its file counts) and a class without
  the chaos rule. `--rules` explains each. It blanks comments and strings first, so prose never matches. A deliberate
  finding is allowed where it stands with its reason, `// lab-lint: allow <rule> — <why>`, on the line or in the
  comment block (KDoc included) above it or above the statement it wraps, and the reviewer checks the reason: a
  wrong one hides a flake (the first review, 2026-10-05, turned six of nine oracle allows into oracles —
  per-pocket for LoRa, `carriers` on a line, `custodyAcross` past the `forgetCustody` seam). It runs as a
  `PostToolUse` hook on every scenario edit (findings come back at once), first in `lab-chaos.sh` (exit 4,
  `--no-lint` skips) and as the enforced `lab-lint` job on both CIs; its tests are `scripts/test_lab_lint.py`.
  Validated on history: the 14 dropped awaits `49c0d381` fixed, the relay-count waits `7552268f` replaced and the
  re-hold gap `a8c5b978` closed are what it names on their parents. A new rule needs a pattern that is wrong
  wherever it appears, or an allow that says why it is not.
- **Chaos mode: the slow runner on the workstation** (`LabChaos.kt`, 2026-09-24). `-Pknit.labChaos=<seed|random>`
  runs every node's session dispatcher, settings scope and Room queries through a dispatcher that lags some
  dispatches a few ms, jitters the sender around each pipe delivery and the collectors before each frame, and
  preempts the fast path's worker now and then — with a **heavy tail**: at the seed's own rate (2–15 ‰ of
  draws) one coroutine stalls 40–400 ms while the rest carry on, the one-vCPU runner's signature. Uniform
  noise alone (the first cut) reproduced nothing; the tail is what flips orders. Validated 2026-09-24 against
  the two latest flake fixes reverted — the heal basket outliving `unlink`'s settle (never reproduced by ten
  throttled runs) and job 5268's queued blob ask: 4 of 6 scenarios failed within 10 seeds with the CI
  signatures, and the fixed tree passed all 6 on the same 20 seeds. It never changes what a pipe means (a `send` still returns
  after the far end has the frame, in order; a hold holds), so a chaos failure is a schedule a slow CI runner
  can produce too: a latent flake, not an artefact. `-Pknit.labChaosRuns=<n>` repeats each scenario n times
  in one JVM on consecutive seeds, each with a fresh `MeshLab` — `scripts/lab-chaos.sh --tests '<Class>'
  [--runs 20] [--seed S]` wraps it and prints each failure with its seed. The script splits the seeds across
  parallel test JVMs (`--shards`, default a quarter of the cores, ~2.5 GB of free memory each; the
  `labChaosShard<i>` tasks `-Pknit.labChaosShards` registers in `app/build.gradle.kts`), since a chaos JVM is
  mostly asleep in its own stalls: `SessionLabTest` at 5 runs went from 115 s to 49 s on five. It stamps each
  sweep with a fingerprint of the tree it compiled (HEAD plus every uncommitted byte under `app/`, `gradle/` and
  the root build files, kept in the git dir), and `--reuse` answers from that stamp when the tree has not moved
  and it covered the asked-for patterns with at least as many runs (and the same `--boards` mode) — the reviewer's
  second sweep of the same tree. It also names as `slow` every scenario that spends more than 6 s a run past its node
  boots per node it built (the rule prints a `MESHLAB-RUN … ms= bootMs= nodes=` line per passing run; boots are timed
  in `MeshLab.node` and `LabNode.restart`): work grows with the nodes and a timer sat out does not, so the
  eight-node flood's 28 s a run is 3.5 s a node while a two-node scenario sitting out a 15 s timer is past 7. That
  one is waiting out a field timer (the bullet on the calendar above). Boots are about a tenth of a chaos sweep's
  time (measured 2026-10-04), so shortening them would buy little. A failing sweep's failing-class XMLs (each
  holds `MeshLab.report`, the warnings and every node's counters) and its Gradle log are copied to
  `<git dir>/lab-chaos/kept/<time>-seed<S>/`, the last ten failing sweeps: the next sweep wipes the shard results,
  and a 1-in-36 failure may not come back on demand (#137, #138 lost theirs that way). A seed replays a
  distribution of delays, not a schedule (the threads still race for the draws), so re-run a failing seed a few
  times. Every
  `*LabTest` carries `@get:Rule val chaos = LabChaos.rule()` (`LabChaosCoverageTest` fails a class without
  it), and a failure names its seed in stderr, a suppressed cause and `MeshLab.report`. The one `mesh/` seam is
  `MeshManager(sessionDispatcher = …)`, `Dispatchers.Default` in production. Run it on every lab test you
  write or touch; the `mesh-lab-reviewer` agent (`.agents/personas/`) runs it as part of its review, and a
  Claude Code Stop hook (`.claude/hooks/review-gate.sh`) asks for that review whenever the lab's uncommitted
  diff moves more than 40 lines past what the last review stamped, touches a file it did not see, or sits on a
  newer commit (`--stamp`, the reviewer's last step, stamps the tree its `--begin` recorded) — so applying its
  findings asks nothing. It asks for the test reviewers only after the subsystem reviewers have stamped, and a
  re-review with no commit since the stamp is a follow-up pass over the delta it writes
  (`.agents/rules/review-rounds.md`).
- **What the first chaos sweep taught** (2026-09-24, nine failures, triage in `.agents/memory/roadmap.md`):
  a frame sent into a link that is going down reaches the far side only through the relaunch's digest exchange,
  which leaves it to the 60 s timer if it runs before the frame exists — `restart()` and
  `restoreFromBackup()` therefore wait for every peer's inbound to drain (`LabTransport.awaitInboundDrained`:
  each frame handed to the router has finished `handleInbound`), not just for the departure, and so does
  `unlink` for both ends since #137 (a receipt sealed after the cut missed the re-link's digest exchange). A stack custodies its own frame
  *before* it floods it since #141 (`originateWire`): flooding first let a link raised between the two miss both;
  `LabCustodyGate` holds a node's custody writes to stage that window. A scenario that holds it releases it before
  it ends: an admitted delivery runs under `NonCancellable` (ADR 2026-10.f82h), so one parked at the gate outlives a
  stop and holds the session-scope join. Stop the manager, release, then restart (`SpoolWorkerStopLabTest`);
  `MeshLab.close` releases every gate first as a net. Such a delivery may still read what the app scope serves (the
  name-clash fade's `LabelAnchors.current`, a `WhileSubscribed` share), and the node's one scope stands in for both
  the app scope and the session's parent, so `LabNode.shutdownLive` joins the session `stop()` cancelled before it
  cancels the rest: cancelling both at once left the delivery on a share whose producer was gone, and the join
  failed `session scope still running` (`SideChannelLabTest`, 3–7 in 80 seeds; production never cancels its app
  scope). A fixture scope standing in for the app scope outlives every `NonCancellable` delivery. `hold`/`release`
  flip `holding` under the batch's lock, so a frame sent during a release is never parked into a batch
  already taken. A stranger on the pages is a stranger only while the first-sighting profile floods cannot
  reach it — they page out unawaited by `awaitAcquainted` — so make `pages.lossy` eat them. A spool scope's
  `accountedCount` can already hold a frame the pair exchanged while linked (link copy delivered, spool copy
  accepted before its custody write): read a baseline, never an absolute band. And a race the chaos finds may
  be the mesh's — four of the nine were (#83–#86); a repro that fails on HEAD stays under `@Ignore("#NN: …")`.
- **What the second sweep taught** (2026-09-25): `onDeliver` custodies **before** it dispatches, so custody
  parity says nothing about a handler having run — await `awaitInboundDrained()` before flipping what the
  handler reads (a block, a setting). Custody parity also does not mean a node's **own** link-up hooks ran (the
  other end's digest can settle it); its digest to that peer means its blob re-ask did (`transport.digestsSent`),
  and `awaitAcquainted` now waits for the whole pass (#201). `broadcastProfile` bumps the version before it signs, and
  pushes and refloods move `framesOriginated`, so neither is the publish event: `published` waits for the
  custody row under the new stamp, written before the frame is originated. (A flood record on `LabTransport`
  would miss a board node: the composite never hands its child a null-target send.) Where the mesh legitimately
  takes one of two routes (Bob answers Alice's intro instead of sending his own), assert on what both routes
  produce. #87 came out of this sweep.
- **What the overnight soak taught** (2026-09-28, whole-package sweeps on fresh seeds over two worktrees,
  single-JVM, three-fork and one- or two-core-throttled). A board node's manager runs on the composite's
  `combine … stateIn`, a copy of the link set that trails the radio and conflates on its own, and
  `awaitNeighborsObserved` cannot see it. After an `unlink`, `AckSync` still read the departed peer from
  `neighbors.value` and "sent" a tick into no child (#96), and an unlink and re-link inside the composite's lag
  was no newcomer to the manager, so no blob re-ask ran. `unlink` now waits for the composite's merged set and
  for `MeshManager.linksActedOn`. `link` is not awaited, so `awaitAcquainted` also waits for each board to hold
  the radio's links: a DM sent before that met a board that saw no link and aired it. A message row is written
  before its tick is sealed (`deliverChat`, then `acknowledge`), so a row count is not proof the tick left; await
  the author's receipts before a link goes. Three mesh bugs came out of it: #96 (a live-link tick lost with its
  link is never sent again), #97 (DataStore 1.2.1 drops the update for a `data` collector that subscribes
  mid-write: the "profile edit was never published" setup failure, and the most frequent failure in a sweep until
  DataStore moved to 1.3.0-alpha03, 2026-09-28; its repro `DataStoreSubscribeRaceTest` now runs) and #98 (a profile frame and an avatar file landing together overwrite each other's peer-row
  write).
- **Time is real.** `MeshManager.start` builds its session on `Dispatchers.Default`, so scenarios run under
  `runBlocking` and poll, never virtual time. `MeshLab.await` **fails the scenario** where the wait runs out
  (with every node's counters and sends); `tryAwait` is the Boolean form for a site that words its own
  `assertTrue` message. A bare `await` whose result was dropped used to let the scenario carry on — the
  reply sent before the first DM landed became the both-initiate race the fixture was written to avoid,
  and the failure surfaced as the oracle's, a step later and under the wrong name (2026-09-18). `node()` returns only once the router is
  collecting `inbound` — a `SharedFlow` with no replay drops what is emitted before that, and a link brought
  up too early would lose the profile push. A boot (and a `restart`) waits for every plane beside the radio too, a
  board's and a direct link's (`LabCollectors`, `LabBoardPlane`, `LabLinkPlane`): the composite subscribes each plane
  on a `merge` coroutine of its own, and a file the link plane landed before its subscriber was dropped (a chaos stall
  of ~400 ms after a restart, 2026-10-29). lab-lint's `plane-collectors-at-boot` reads `MeshLab.kt` for a composite
  child no `while` wait reads `.collecting`; that a plane's wrapper counts each shared flow it hands up is left to review. Bring a multi-hop topology up with `linkAll` so no relay fires in
  the gap between two links. Boot costs ~1 s per node (Tink keygen + Room + DataStore); the scenarios
  themselves run in a few hundred ms. **A calendar jump past the router's 10-min `SeenSet` with a relay still in
  flight** (a copy in its 0–150 ms jitter when `awaitCustodyParity` returned) turns that copy into a re-delivery,
  and a re-delivered DM mints a fresh ✓✓ — an extra frame the scenario never sent, aired or held after the split.
  Cut the link (`lab.unlink`, which drains inbound) *before* `lab.clock.advance`, so a late copy lands inside the
  window and is deduplicated; the LoRa pocket scenarios then shorten `LabLimits.loraLingerSweepMs` so the boards
  re-read their heard sets after the jump (2026-10-10, seeds 630240221/630240248).
- **A failed `awaitAcquainted` prints every node's router counters** (`originated / delivered / relayed /
  deduped / suppressed / drops`) — read `suppressed` first; that is how the same-neighbor overhear bug showed.
- **CI runs the package three times in a row in its own job** (`mesh-lab` in `.github/workflows/ci.yml`,
  `test:mesh-lab` in `.gitlab-ci.yml`), on top of the one pass inside the full unit suite, plus once under
  chaos on GitHub (`mesh-lab-chaos`, seed = the run id, advisory until the first sweep's findings are
  triaged — CHECK `.agents/memory/roadmap.md`): this is the one
  suite where real time and scheduling decide the outcome, so a 1-in-N flake is a bug — both of the
  2026-09-11 findings started as one. Reproduce a CI flake locally with the same loop:
  `for i in 1 2 3; do ./gradlew :app:testDebugUnitTest --tests 'app.getknit.knit.mesh.lab.*' --rerun; done`.
- Robolectric, so the Gradle 9.5 result-serialization race applies: tally the per-class XMLs under
  `app/build/test-results/testDebugUnitTest/` after `rm -rf`-ing the directory, not the console summary.

## Mesh simulator (`app/src/test/…/mesh/sim/`, a spike)

`MeshSim` puts lab nodes in a field and lets where they stand decide their links. A scripted lab scenario
waits for the mesh between its steps; the simulator never does, so it asks the other question: what the mesh
delivers, and how fast, while contacts come and go the way people's do. It is a measurement and no check.

- **Three pure parts, then the driver.** `Mobility` is where each walker is at a simulated time
  (`RandomWaypoint`: an open park, friends arriving together and wandering off). `DiscRadio` turns distance
  into links, with a dial time and a drop range past the dial range so links do not flap. `Workload` is what
  the walkers send, drawn ahead from the seed. `MeshSim` applies each radio step to the lab's transports at
  once (`lab.link`, `LabTransport.disconnect`, none of `lab.unlink`'s settling) and reads delivery off the
  recipient's own row (`arrivedAt` less `sentAt`).
- **Run it with `-Pknit.meshSim=<seed>`**, which every other run leaves out:
  `scripts/gradle-turn.sh ./gradlew :app:testDebugUnitTest --tests 'app.getknit.knit.mesh.sim.ParkWalkSimTest'
  -Pknit.meshSim=1`. Always name `--tests`: the property alone runs the whole suite with the outing in it.
  `-Pknit.meshSimWalkers`, `-Pknit.meshSimMinutes` and `-Pknit.meshSimHeap` size it.
  `-Pknit.meshSimInbound=phone` (or a count) bounds every router's inbound queue as a phone's is
  (`LabLimits.inboundSlots`), and `-Pknit.meshSimSettleMinutes=<n>` reads custody across the field every 15 s
  after the gather until the stores match, then names what is still odd: late or stuck. With a bound, the
  `inbound queues` lines count how often a link's reader waited for a slot, the most frames one node's links held
  back at once (sampled every 250 ms) and the pages dropped; a `probes:` line times eight fresh DMs sent over
  links the gather raised, the wait behind the backlog (#331). A DM still missing at the
  end gets a `lost:` line: how many stores held it over time, how often it crossed to its recipient and how many
  page copies a full queue dropped, and how many writes toward it were skipped as already crossed.
  `-Pknit.meshSimGather=false` ends the outing at the walk: no gather, settle or oracle, so it measures and is never
  red, and every link goes down before the report reads (file suffix `-walk`). Every outing reads every store once a
  minute (`CustodyPressure`): rows by kind (workload DMs pending and delivered, other recipient-addressed chat, room
  posts, profiles), the age of each store's oldest row, and what left since the last sample. A `custody pressure`
  line per sample goes into the progress file as it is taken, so a run cut short keeps it. The summary counts the
  undelivered DMs that lost copies (at least, sampled a minute apart), their sender's own copy among them, and the ones
  custody had held that no store held at a sample. `counters that moved since the walk began` sums every counter of
  the routers' `frames`, `keys`, `seals` and `receipts` metrics over the field by reflection (`RouterCounters.kt`),
  so a new counter in those groups shows up unasked. The
  report lands in `app/build/mesh-sim/`: contacts made and how long they lasted, DM delivery and latency by
  how the two ends stood at the send (linked, a path apart, partitioned), room reach, frames per plane of the
  router, custody rows, and what the run cost the machine.
- **Compare seeds against seeds, with the summary.** Every report ends in a `summary:` block of `key=value` lines
  (`SimSummary.kt`): the scenario, then the headline numbers of the lines above it. `scripts/sim-compare.py` reads
  any number of reports or directories (`label=dir` names a variant), groups them by the outing's shape and
  prints every key as `mean [min..max]` per variant, side by side. `-Pknit.meshSimOut=<absolute dir>` sends the
  report elsewhere (keep long runs on the data drive), `-Pknit.meshSimTag=<word>` ends its file name, and a run
  never overwrites an earlier report: the same outing again writes `-2`. An outing is never up to date and never
  comes from Gradle's cache.
- **A red run is a finding, not a flake.** It fails when the machine let scheduled sends fall past the end of
  the run (an unfair run), when a DM is still missing once everyone has gathered, or when the lab's oracle
  does not converge after it. The report is in the failure's message.
- **Time is real: a simulated minute is a minute.** Every protocol timer in the stacks is a `delay`, so
  walkers that moved faster than the timers ran would change the ratio under test. `LabClock` cannot help: it
  moves decisions, never schedulers.
- **A seed fixes the world, not the schedule.** The walks, the links and the sends repeat; the threads under
  them race as they always do. Compare builds over several seeds, never one run with another.
- **A crowd costs little until custody fills** (seed 1, 2026-11-14). Twelve walkers kept 0.16 cores busy,
  twenty-four 0.33, forty-eight between one and two, each node a few MB of heap, the world never late. At
  forty-eight the stores held about 700 of custody's 1,000 rows after two minutes; at a hundred they sat at
  the cap, the machine spent fifteen cores in `ForwardDao.evictFairest`, and the run was stopped. Read the
  custody line before the delivery lines, and pin a big run to half the machine: `taskset -c 0-11
  scripts/gradle-turn.sh ./gradlew --no-daemon …`.
- **Forty-eight walkers fill custody, and the gathered field converges late, not stuck** (seeds 1 to 3,
  2026-11-15, unbounded queues). Every DM arrived, and every store came to hold the same 1,000 ids, custody's
  cap, about 93% of them chat frames, 60 to 106 s after the gather. The last gap was each store short one id at
  the cap's edge, which a re-offer round or two closed. The oracle's 120 s has little to spare.
- **A phone's inbound queue freezes the gather** (seeds 1 and 2, 2026-11-15, `-Pknit.meshSimInbound=phone`).
  The walk lost a few hundred frames on a few nodes and delivered as before; the gather lost 48,000 to 76,000,
  and custody across the field stood still for about ten minutes before it converged (541 and 1,142 s after the
  gather). A frame the far end's queue dropped has crossed the link at both ends, so `LinkCrossings` skips its
  re-serve over that link for `SeenSet.DEFAULT_TTL_MS`: the invariant its KDoc states (the far end handed every
  suppressed frame to its router) does not hold on a plane that drops (#331). Each run left one DM undelivered
  after the gather. A traced rerun of seed 2 showed how one is lost for good: all seven copies that reached its
  recipient were dropped at the full queue, every re-serve after them was skipped as already crossed, and within
  72 s of the gather custody's cap had evicted it from every store, the sender's own included. When the window
  lapsed nobody held it. Wi-Fi Aware drops the same way but does not skip by crossings (#333).
- **With #331's waiting readers the gather converges as if unbounded** (seeds 1 to 3, 2026-11-16, on the fix
  `2d1d6b236`, `-Pknit.meshSimInbound=phone`). Nothing was dropped; readers waited 61,000 to 176,000 times over the
  outing, and one node's links held at most 4,300 to 5,800 frames back (6,100 in a rerun of seed 1), 3 to 6 s after the gather's links came
  up. Every DM arrived, the stores matched 64 to 66 s into the settle, which starts once every DM has arrived
  (unbounded: 60 to 106 s), and the oracle passed. A rerun of seed 1 timed up to eight fresh DMs sent over links
  the gather raised: seven were in by 2 to 4 s, one by 23 s, read on the settle's 15 s poll. The lab holds that backlog in the
  receiver's pipe inboxes; on a phone it sits in the senders' queues and the stack, if Android's credits push
  back, which only a device trial can tell.
- **Custody fills in minutes, and our own profile's eviction floods it** (walk-only, 10 minutes, 2026-11-16, #335).
  Every store reached custody's 1,000-row cap within 6 to 9 minutes, after which a store held about the last 6
  minutes of traffic. At 24 walkers that cost one DM. At 48 the walk originated about 108,000 frames for about
  1,400 DMs, recipient-addressed chat other than the workload's DMs filled 85% of every store, and 150 to 234 DMs
  left every store, often their sender's own first, and never arrived. The counters named the flood: of 96,016
  v3 seals, 90,517 were the sealed profile fan-out, after 3,919 republishes, every one because our own custody no
  longer held our profile frame (the profile watcher then read a missing custody row as a change, so the next
  settings write to any key minted a version and sealed it to every confirmed session; it judges the frame kept in
  settings since ADR 2026-11.khhj). Signed DM ✓✓ re-seals were
  95, all because our last receipt had left custody. The counters behind it: `seals.ctlDmSealedBy`,
  `seals.profileRepublishedBy`, `receipts.receiptsDmSealedBy`, `receipts.receiptsReSealedBy`.
- **With the kept profile frame, an eviction mints nothing** (walk-only, 48 walkers, 10 minutes, 2026-11-17, ADR
  2026-11.khhj). On one tree, seed 1 before and after the fix: `profileRepublishedBy[NO_CUSTODY_ROW]` 3,514 → 0,
  `ctlDmSealedBy[PROFILE_FANOUT]` 80,465 → 0 (no heal ran in the walk), frames originated 81,187 → 5,760, DMs that
  left every store and never arrived 151 → 52, DMs arrived by the walk's end 56% → 84%. Seed 2 after the fix: 0 and 0,
  6,069 originated, 73 lost, 78% arrived. Every store still reaches the cap in 3 to 5 minutes; recipient-addressed
  chat other than the workload's DMs (receipts, intros) is now 52 to 60% of it, and what is lost is that pressure on
  partitioned DMs.
- **A gather after a long walk did not settle in the lab before #335's fix** (48 walkers, 10 minutes, 2026-11-16).
  About 12,900 live ids met 1,000 rows per store, the nodes re-served what the receivers then evicted on about ten
  cores for nine hours, and the settle's own reads starved; the run was killed. After the fix it settles at once (the
  baseline below): every store already holds the same 1,000 ids, and what the walk lost stays lost.
- **The baseline on main `c1dcef23d`** (2026-11-17, 48 walkers, phone inbound queue, seeds 1–3 unless named; reports and
  `run-baseline.sh` in `/media/walter/data/knit-sim/baseline-c1dcef23d/`, compare with `scripts/sim-compare.py`). Means
  with [min..max]:

  | Outing | DMs arrived (in run / by end) | DM p50 / p90 | Lost for good | Custody at cap (first / all) | After the walk |
  | --- | --- | --- | --- | --- | --- |
  | 10-minute walk, no gather | 82% [78..85] / same | 44 s / 131 s | 67 [53..76] | 180 s / 380 s | – |
  | Same, unbounded queue (seed 1) | 84% / same | 43 s / 140 s | 54 | 180 s / 360 s | – |
  | 2-minute walk, gather, 20-min settle | 76% [69..85] / 100% | 0 s / 20 s | 0 | 179 s / 179 s | parity in 50 s [45..60], probes ≤12 s [4..27], oracle passes |
  | 10-minute walk, gather, 30-min settle | 82% [78..85] / 95% [94..96] | 45 s / 132 s | 67 [50..76] | 180 s / 380 s | parity at once; 68 [52..76] DMs never arrive (red); probes ≤17 s [14..22] |

  The walk originates about 6,000 frames for about 1,475 DMs; the gather's links hold back up to about 7,500 frames on
  one node (5,100 after a 2-minute walk). The DMs a long gathered outing never delivers are the ones custody evicted
  from every store during the walk: the same count as the walk alone loses. The seed-1 unbounded run matches the
  2026-11-17 post-fix walk (5,769 frames originated against 5,760, 54 lost against 52, 84% arrived). These links have
  no airtime; the `ble1m` result below is the comparison.
- **`-Pknit.meshSimRadio=ble1m` gives the links a Bluetooth 1M radio** (`BleRadio`). The disc's ranges are kept. Each
  approach draws its own dial time, log-normal around the measured 4.3 s relink median and clamped to 0.3–30 s. A node
  holds at most six links, nearest pairs first, with no eviction. Every link airs at 9 KB/s (the HCI-snoop drain at the
  default interval, ADR 2026-10.2da4), and each phone's radio at 36 KB/s for all its links in and out. The boot, the
  walk and the gather all link with that air. The report gains `air` lines and `air_*` summary keys: records and bytes
  aired, records a cut lost or a closing link refused, each record's wait from send to delivery (as the upper bound of
  a power-of-two bucket) and the deepest queue and backlog one link held in that part of the run (`AirStats.peaksFromNow`).
  `disc`, the default, has no air. `ble1m-slow` is the same radio at the slowest 1M drain the field measured (5 KB/s a
  link, 20 KB/s a radio; ADR 2026-10.8jwn's trial): a bound from below, beside `ble1m`'s from above. `MeshSimAirTest`
  pins that the boot's, the walk's and the gather's links all get the radio's air.
- **On `ble1m` air the backlog moves from the router to the links, and the long gather settles** (`383c4c2`,
  2026-11-17, the baseline's matrix with `-Pknit.meshSimRadio=ble1m`; reports and `run-ble1m.sh` in
  `/media/walter/data/knit-sim/ble1m-own-queues/`). Means with [min..max]:

  | Outing | DMs arrived (in run / by end) | DM p50 / p90 | Lost for good | Custody at cap (first / all) | After the walk |
  | --- | --- | --- | --- | --- | --- |
  | 10-minute walk, no gather | 71% [68..74] / same | 78 s / 190 s | 56 [26..82] | 220 s / 460 s | – |
  | Same, unbounded queue (seed 1) | 74% / same | 66 s / 190 s | 26 | 180 s / 420 s | – |
  | 2-minute walk, gather, 20-min settle | 69% [64..75] / 100% | 0 s / 19 s | 0 | 200 s / 255 s | parity in 132 s [121..138], probes ≤3 s [2..4], oracle passes |
  | 10-minute walk, gather, 30-min settle | 71% [68..74] / 95% [93..97] | 78 s / 191 s | 60 [30..87] | 220 s / 460 s | parity in 289 s [192..348]; 60 [30..87] DMs never arrive (red); probes ≤10 s |

  On the walk, DM p50 / p90 by reach go from 0 / 0 s to 0 / 0.3 s direct, 0.2 / 14 s to 3.3 / 75 s over a path and
  61 / 142 s to 101 / 208 s partitioned. Frames originated per DM barely move (3.8 against 4.1). The phone's inbound
  queue held at most two frames, where the baseline's gather piled up 7,500 at one node (#331's 4 to 6 thousand): the
  backlog waits on the air instead, at most about 1,050 records (40 s) on one link in the walk and 1,700 (65 s) in the
  gather. A record waits p50 ≤8 s and at worst under 65 s in the walk, p50 ≤65 s and at worst under 9 minutes in the
  gather (power-of-two buckets). A cut loses about 27% of what the walk airs (85,000 of 316,000 records), and custody
  re-serves it. Custody still reaches its cap in every run, about a minute later than the baseline's. About 87% of
  what either gather airs reaches a node that already has it (`framesDeduped` against the records aired), consistent
  with each missing row coming from most of a node's six neighbours: roughly one useful copy in eight. The baseline
  pays nothing for those duplicates; here the settle spends its 2 to 6 minutes on them. Read every number as an upper
  bound on a phone's: the ledger is fluid (a radio serves its links in any proportion, with no connection events), so
  a `ble1m` result says the mesh can do this well at this rate, not that a phone mesh does. A first run whose ledger
  booked the link and both radios jointly settled neither gather; `AirLedgerTest` pins why it was wrong.
- **On `ble1m-slow` air the long gather never settles** (`d0c78a3`, 2026-11-17, the same matrix with
  `-Pknit.meshSimRadio=ble1m-slow`; reports and `run-ble1m-slow.sh` in `/media/walter/data/knit-sim/ble1m-slow/`).
  The contacts are `ble1m`'s to the link: only the rate differs. Means with [min..max]:

  | Outing | DMs arrived (in run / by end) | DM p50 / p90 | Lost for good | Custody at cap (first / all) | After the walk |
  | --- | --- | --- | --- | --- | --- |
  | 10-minute walk, no gather | 61% [60..63] / same | 112 s / 254 s | 18 [11..24] | 240 s / 420 s (one run never all) | – |
  | Same, unbounded queue (seed 1) | 63% / same | 102 s / 251 s | 16 | 240 s / 420 s | – |
  | 2-minute walk, gather, 20-min settle | 66% [62..74] / 100% | 0.1 s / 19 s | 0 | 280 s / 420 s | parity in 265 s [245..291], probes ≤5 s [4..6], oracle passes |
  | 10-minute walk, gather, 30-min settle | 60% [58..63] / 95% [94..95] | 110 s / 258 s | 18 [17..20] | 240 s / 500 s | no parity in 30 min, 82,000 [57,000..98,000] rows still missing; 18 [17..20] DMs never arrive (red); probes ≤18 s [16..21] |

  Partitioned DMs drop from 66% to 55% in the walk (p50 139 s against 102 s); direct ones keep arriving but wait up to
  4 s (p90). A cut throws away about as many records as the walk airs (216,000 against 226,000; `ble1m` 85,000 against
  316,000): most links drop before their queue drains. In the long gather the missing rows fall for about four minutes,
  then hover for the rest of the settle while every store sits at its cap, and one link's backlog grows to 11 minutes
  (about 9,600 records); a record waits p50 ≤9 minutes. Its 180-second count of missing DMs (64) is mostly lateness here:
  43 to 59 of them arrive during the settle. Fewer DMs are lost for good than on `ble1m` (18 against 56 in the walk)
  although senders evict more of their own (408 against 279). A guess, not yet tested: slower spreading leaves the
  stores holding different rows, so a DM one store evicts survives at another. The 10-minute gather is red on every
  radio (68 DMs on the baseline, 60 on `ble1m`, 18 here): custody's cap, not the air. Between the two rates, the walk
  delivers 61% to 71% of DMs inside 10 minutes; both ledgers are fluid, so neither is a phone's floor.
- **How the walkers move is the scenario's to say** (`-Pknit.meshSimMotion`, `knit.meshSimGroups`,
  `knit.meshSimFriends`; `Motion.of`). `rwp`, the default, is random waypoint: each walker alone once its group has
  left the gate. `groups` is `GroupWalk`, Reference Point Group Mobility. Each group's point walks a random waypoint
  0.04 m/s slower per member past the first. Its members walk side by side 0.8 m apart across the heading and stand
  within 3 m of the point, and nobody leaves their group. `groups=street` draws the walking groups' sizes from a weekday
  street (46/37/10/4/3% of people alone, in twos and so on to fives; Moussaïd et al. 2010); a number fixes them.
  `friends=circles` pairs the walking groups into circles of friends who came separately. The whole circle meets at boot
  and pins each other's keys, then only the walking groups stay linked, and a share of a walker's DMs go to its
  circle's other group (`knit.meshSimAwayShare`, a half unless set: a guess no study gives, so read a circle's delivery
  at more than one share); the default DMs only the walker's own group. Every draw comes from a `SimSeeds` stream keyed by its
  purpose, so a new draw shifts no other. `Mobility.present` lets a model take a walker out of the field: its links drop
  and nobody dials it. A model's arithmetic is `StrictMath`, never `Math`. `MobilityTest` pins the first outings' walk,
  dial and workload draws through all of it; `MeshSimBootTest` pins the circle's boot. The research behind it is the
  "Mesh Sim Pedestrian Models" report (`/media/walter/data/knit-sim/reports/`).
- **Walking in groups moves the DMs to direct and cuts the room's reach** (`e3ab305` plus the away-share setting,
  2026-11-17). Each row is one seed-1 walk: 48 walkers, 10 minutes, the phone's queue, `ble1m`, no gather. Reports and
  `run-p0.sh` are in `/media/walter/data/knit-sim/mobility-p0/`. `rwp` reproduces `ble1m`'s W seed-1 report to the
  contact (608 made, p50 25 s, degree 1.64, largest component 10.9) and to the DM reach mix, and so do its delivery and
  room numbers. The three `groups` walks share one link log whatever the friends, as they must.

  | Walk | DMs direct / path / partitioned | DMs in run | DM p50 / p90 | Room reach | Lost for good | Contacts, degree |
  | --- | --- | --- | --- | --- | --- | --- |
  | `rwp` | 146 / 91 / 1,152 | 74% | 66 s / 191 s | 74% | 24 | 608, 1.6 |
  | `groups` | 1,389 / 0 / 0 | 99% | 0 s / 3 s | 42% | 0 | 501, 3.3 |
  | `groups`, circles at 0.5 | 710 / 29 / 650 | 75% | 0.1 s / 184 s | 43% | 21 | 501, 3.3 |
  | `groups`, circles at 0.2 | 1,138 / 3 / 248 | 89% | 0 s / 24 s | 43% | 18 | 501, 3.3 |
  | `groups=street`, circles at 0.5 | 398 / 38 / 964 | 72% | 55 s / 225 s | 62% | 43 | 639, 2.3 |

  The degree counts a group's own links, which never drop. Walking in threes, the walkers meet fewer others (501
  contacts against 608), and a room post reaches 42% of them against 74%. A DM to another group in the circle arrives
  less often than `rwp`'s partitioned ones (49% against 69% in the run) and later (p50 123 s against 93 s). A circle's
  delivery follows its away share (75% at 0.5, 89% at 0.2), so it is the share's number, not the park's. On a street's
  sizes 46% of people walk alone, and a walker with no companions DMs only away, so 72% of DMs leave the group whatever
  the share. Those groups mix more (639 contacts, room 62%) and lose the most DMs for good (43). Direct DMs wait longer
  in a group (p90 3 to 6 s against 0.6 s). A cut loses about four times `rwp`'s records (214,000 to 236,000 against
  56,000), most likely because two groups parting drop up to nine links at once. One seed each: a direction, not yet a
  measurement.
- **The field alone runs in seconds** (`FieldSimTest`, `FieldRun`). The groups, the motion, the radio and the workload
  are `SimWorld`'s, built from the scenario and the seed only. The radio's links depend on where the walkers stand and
  on nothing the mesh does, so a run without stacks makes the outing's exact links. It is gated like the outings and
  takes the same properties: `scripts/gradle-turn.sh ./gradlew :app:testDebugUnitTest --tests
  'app.getknit.knit.mesh.sim.FieldSimTest' -Pknit.meshSim=1 -Pknit.meshSimMinutes=240`. Four hours of 48 walkers take
  about 1.5 s. Its report is `field-s<seed>-w<walkers>-m<minutes>`. It classes each DM by how its two ends stood when it
  was sent, but delivers nothing. `knit.meshSimTrace=<file>` replays a BonnMotion `.movements` file (`TraceMobility`):
  straight lines between waypoints, and each walker present only from its first waypoint to its last.
  `scripts/sim-trace.py` turns a Jülich PeTrack file into one. `motion=rwp-steady` starts random waypoint from its
  long-run state (Navidi and Camp 2004), with no gates and no groups; it is the baseline the literature analysed.
- **The instruments measure the field as the proximity traces measured theirs** (`FieldMeter`). Both runs report
  them. Each contact log becomes per-pair intervals (`PairLog`), and `ContactStats` reads each log the way Chaintreau
  et al. did. The logs are:
  - `link`, the radio's links;
  - `near30`, everyone within 30 m every 500 ms (the truth a scanner samples);
  - three `VirtualScanner` views of that truth: `imote` (a 5 s inquiry every 120 s ± 12 s at 30 m, mutual, as Haggle
    ran), `scan300` (Reality Mining's and Copenhagen's 5 minutes at 10 m) and `scan20` (SocioPatterns' 20 s at 1.5 m).

  A trace is compared with its scanned log, never with `link`, since the gap between `near30` and `link` is what the
  dial and the cap cost. Per log, the `<log>_` keys are:
  - contacts a pair-hour, and contact and gap p50 and p90 (a gap is an inter-contact time, one sample per gap);
  - the gaps' CV (1 for an exponential);
  - `ict_tail_llr`: above the median, the mean log-likelihood ratio of a power law over an exponential, both fitted by
    maximum likelihood (Clauset et al. 2009);
  - the share of pairs that ever met;
  - `halves_r`, the correlation across all pairs between contact time in the first and second halves (about 0 when
    pairs are alike, about 1 when friends stay together).

  The scanners record one sighting as a contact of no length, and a pair's gap is never shorter than a period plus
  its jitter. Beside the logs come `cap_time_pct` (walker-time at the radio's link budget), the group pairs' time
  within 25 m, partings beyond 35 m per pair-hour and rejoins within 25 m, and `dm_pairs_ever_linked_pct`.
  `FieldMeterTest` pins the arithmetic and the scanner's catch rate for a pass-by (25 s of 120 at each end: 37% for
  either end, 4% mutual). It also replays the fixture `juelich-2018crowdqueue-030.movements.gz` through `ble1m`. That
  fixture is CC BY 4.0, run 030 of the Wuppertal 2018 entrance queue (75 people, 65 s); its README carries the
  attribution. The test then holds the baseline to its failures.
- **Random waypoint shows the failures it should** (Phase 1, 2026-11-18). The field run reproduces `ble1m`'s W seed-1
  outing to the contact and to the DM reach mix (608 made, 146 / 91 / 1,152). Four hours, 48 walkers, `ble1m`, three
  seeds, in `/media/walter/data/knit-sim/phase1/h4/`:

  | Motion | Link contacts | Gaps p50, CV, tail LLR | Halves r | Contacts a pair-hour, iMote / `near30` | Group pairs within 25 m | At the budget |
  | --- | --- | --- | --- | --- | --- | --- |
  | `rwp` | 15,031 | 711 s, 0.9, −0.1 | 0.0 | 0.6 / 4.3 | 3% | 0% |
  | `rwp-steady` | 15,037 | 704 s, 0.9, −0.1 | 0.0 | 0.5 / 4.3 | 2% | 0% |
  | `groups` | 10,835 | 911 s, 0.9, −0.1 | 1.0 | 0.5 / 3.9 | 100% | 6.7% |
  | `groups=street`, circles | 14,101 | 746 s, 0.9, −0.1 | 1.0 | 0.6 / 4.2 | 100% | 4.3% |

  Every log of every motion has exponential gaps: walking groups still wander as random-waypoint units, so only a
  model with places and paths (Phase 2) can give the heavy-tailed head every human trace has. Walking groups do make
  uneven pairs, though those pairs are only the groups' own. Every pair meets within four hours (100% met), against
  10 to 20% of pairs a week on a campus. An iMote sees about one contact in eight that the 500 ms truth holds, and
  median contacts of 19 s (single sightings). In the Jülich queue, phones sit at the six-link budget 91% of the time.
- **The published traces are tables, read one side at a time** (`ReferenceTable`, 2026-11-18). The tables cover
  Haggle Infocom 2005 and 2006 (`imote`) and Reality Mining (`scan300`). `scripts/sim-reference.py` derives them from
  the CRAWDAD copies, which live in `/media/walter/data/knit-sim/traces/` and are never redistributed. Each table counts
  gaps and contact lengths on a log grid; the test resources' README cites them.
  - Each trace is read as its files record it: "A saw B", one side, never matched with B's runs. The iMotes' clocks
    were set by hand and are not trusted below five minutes, and the archive's own gaps of one, two and three scan
    periods contradict its stated end-of-contact rule.
  - `FieldMeter` scores each scanner's `sightings()` against them by KS distance (the `<scanner>_ks_<table>_*` keys).
    Gaps run from five scan periods to half the run, contact lengths up to half the run. Each run value is weighted
    1 / (T − x), because a short run holds short gaps whole more often than long ones.
  - The derivation agrees with Chaintreau et al.: 0.194 Infocom 2005 runs an ordered pair-hour is their 4.6 contacts
    a pair a day. Every trace's gap tail is heavy (LLR 0.37, 1.08, 0.11), where random waypoint's is −0.1.
  - The two conferences lie 0.12 apart in gaps and 0.09 in contact lengths. Four hours of each Phase 1 motion
    (`phase1/h4-ks/`) score:

    | Against | Gaps | Contact lengths |
    | --- | --- | --- |
    | Infocom 2005 | 0.19–0.21 | 0.42 |
    | Infocom 2006 | 0.31–0.32 | 0.37 |
    | Reality Mining | 0.26–0.31 | 0.51–0.53 |

    Walking groups move no number: the scanners read only where walkers stand, and the groups' pairs are too few to
    shift the sightings of everyone else. These are yardsticks for Phase 2, not bands; pinning bands is Phase 5's.
- **`motion=park` walks a synthetic park** (`ParkLayout`, `ParkMobility`, Phase 2, 2026-11-18). The default field is
  1 km (`knit.meshSimFieldM`).
  - **The park:** a looped lattice of paths at about 90 m a hectare (Helsinki's), six gates, and 20 places a km²
    (benches, amenities, lawns) with Pareto popularity.
  - **The groups:** each plans 3–6 places (SLAW's subset) and visits them by LATP (`knit.meshSimLatp`, a = 2). They
    follow the paths or cut across, pause by a truncated power law (β 1.8, 30 s to 1 h), and leave when a lognormal
    visit budget (median 45 min) runs out.
  - **Budgets** apply only to outings that don't gather (`knit.meshSimGather=false`).
  - **Arrivals** spread over `knit.meshSimArrivalMinutes`.
  - **Excursions:** members of a group wander 10–50 m on their own excursions (`knit.meshSimExcursions`, 2 an hour,
    a guess).
  - **The walking gather:** `knit.meshSimGather=walk` walks every motion to a meeting point. The park walks along its
    paths to its most popular lawn; the other motions walk straight to the field's centre. Groups stand on a Vogel
    spiral 10 m apart, and the radio raises the links, budget and all, rather than the ring laying them.
    `gather_connected_s` and `gather_meshes` report how it went.
  - **Calibrated, not emergent:** the off-path shares are tuned to their targets (more than 15 m from a path; runners
    13% from Korpilo et al., walkers 20% and dog walkers 25% guessed), and groups stay together by construction. So
    `ParkMobilityTest`'s checks of both only guard the calibration.
  - **What the park does on its own** (`phase2/field/`, 4 h, `ble1m`, 3 seeds):
    - Contacts over 5 minutes, companions and shared stops, are 3–10% of the park's contacts and none of random
      waypoint's.
    - Pass-bys of 10–40 s are 58–63% of the park's link contacts, but 79% of random waypoint's, so they don't tell
      the two apart.
    - Pairs are uneven: halves r 0.92–0.97 with everyone staying, 0.28–0.34 with visits.
    - Contact lengths land far closer to the iMote traces: KS 0.24–0.28 against Infocom 2005, where random waypoint
      at 1 km scores 0.45.
    - **Gaps stay exponential** (near30 tail LLR −0.13 to −0.26). Their KS against Infocom 2005 is 0.35–0.37,
      against random waypoint's 0.38–0.42; 0.21 with visits (one seed's iMote LLR is +0.08).
    - So paths, places and pauses give the long contacts and the uneven pairs, but not the heavy-tailed gaps.
  - **A 30-minute park outing with stacks strands DMs at custody's cap** (seed 1, 48 walkers, circles, `ble1m`, ring
    gather; `phase2/outings/`). The red is the mesh's, not the harness's.
    - Of 4,342 DMs, 3,174 were sent while sender and recipient were out of reach of each other.
    - Every node's custody sat at 1,000 rows at the walk's end.
    - 1,192 DMs were never delivered, held by no store, the sender's included.
    - Delivery: 99% of DMs between linked friends arrived in the run, but only 27% of the others (62% by the end).
    - The same outing with `gather=walk` walks the same walk (545 contacts, the same DMs). Everyone reached the
      meeting point 1,608 s after the walk ended, and the radio's links made one mesh of all 48 at 1,498 s.
    - It still strands DMs: 66% had arrived by the end against the ring's 72%, and 1,473 were missing against
      1,192. The eviction happens in the walk, so how the outing gathers doesn't save those DMs.
- **What the walkers send is the scenario's to say** (`Workload`, `GroupChats`, 2026-11-18). The default is a stress
  load no study gives:
  - **Rates:** each walker DMs every 20 s on average and posts in the Nearby room every 60 s
    (`knit.meshSimDmSeconds`, `knit.meshSimRoomSeconds`; 0 sends none).
  - **Group chats:** `knit.meshSimGroupSeconds` gives each party of three or more one (`Workload.parties`: a circle
    under `friends=circles`, else a walking group). Each member posts on its own stream, so DMs and room posts don't
    move. The boot opens each chat while the party is linked, and every member posts once, so each holds every chain
    before the walk. The oracle checks each chat's thread, and a post that misses a member turns the outing red.
  - **Backlog:** `knit.meshSimBacklog` gives each walker that many messages, sent while the boot links its party. They
    follow the outing's own mix and stay out of the workload's numbers, so every store starts the walk holding its
    party's history (`custody_rows_at_walk_*`).
  - **Custody:** group posts are a class of their own in the custody lines. They are never acked, so only the 24 h TTL,
    the per-group quota (200) and the cap take them out.
  - **The boot waits out the backlog's airtime** (`MeshSim.BACKLOG_SETTLE_MS`, 125 ms a message and other member). Over
    `ble1m`'s 9 KB/s links a three's 900 messages take about 32 s to agree, past the lab's 30 s await: a fixed wait
    failed the boot at a backlog of 100. Linked stores over their quotas do agree, one of them meeting the frames
    newest first (`CustodyQuotaLabTest.linkedStoresKeepASendersNewestWhateverOrderTheFramesCameIn`).
- **What the workload sweeps found** (2026-11-18, 48 walkers, `motion=park groups=street friends=circles`, `ble1m`,
  the phone queue, seed 1 unless named; reports in `/media/walter/data/knit-sim/workload/`):
  - **Rate barely matters on a 10-minute park walk.** At DM gaps of 20, 60, 180, 600 and 1,800 s (room 3×, seeds 1–3),
    DMs in the run stay 24–48% and partitioned ones 0–21% at every rate: the walk's length and topology bound
    delivery, not custody. Only seed 1 at 20 s reached the cap (7 of 48 stores at 480 s); mean rows grow about 60 a
    minute there, and 68–81 rows are a floor of profiles, intros and receipts. The walk uses 0.06–0.27 cores.
  - **A ring gather fills every store.** At a 60 s DM gap the walk-only run stays under the cap, but the gather's
    merged field does not: 45 of 48 stores at the cap a minute in, parity 289 s after it (pass).
  - **Group chats deliver, and settle once the seen window lapses.** Posts every 60 s: 100% reached their members by
    the end. The field held 1,011 live ids against the 1,000-row cap. A 10-minute settle ended at the floor (every
    store 11 short, 22 ids split; `unsettled`). With a 30-minute settle, every store held the same 1,000 at 675 s and
    the oracle passed. A store refuses a frame it evicted for the router's 10-minute seen window, so the overflow waits
    that out.
  - **A backlog starts the walk full.** 100 or 300 messages each: 346–1,000 rows at the walk's start, 46 of 48 stores
    at the cap a minute in, the backlog 98–99% of custody and the walk 1.2–1.3 cores. DMs fare as without one (31% in
    the run, none lost for good), but a few partitioned ones miss the gather's 180 s and arrive in the settle, which
    then read as stranded. The verdict now judges what the settle left missing, so these outings fail only on
    `unsettled`: 1,344–1,410 live ids, not settled within the 10-minute settle (a longer one not yet run).
- **A sidewalk pass-by** (`PassBy`, `PassBySim`, `PassBySimTest`, 2026-11-19). Pairs on lanes of their own walk past
  each other from out of reach, each walker bringing `knit.meshSimPassDms` DMs and `knit.meshSimPassPictures` photos
  (half for the walker it passes, half for a contact at home it carries for). A pass converges when each holds what the
  other brought for it, and carries what the other brought for its home, within a tick of the drop.
  - **The knobs:**
    - `knit.meshSimRangeM` and `knit.meshSimDropM` set any run's range; the drop is 1.4 × range when unset, and 25/35
      stays the outings' default. The pass-by defaults to 15 m, a phone in a pocket.
    - Also `knit.meshSimPairs`, `…Speed` (1.3 m/s), `…LateralM` and `…PictureKb` (200).
  - **Files are aired** (`LinkAir`, phase 2): a photo queues on the link's one stream at its rate, and a cut loses it
    whole. Partial-blob resume is not modelled, so this errs slow.
  - **`ble1m+nan`** (`NanRadio`) is Bluetooth as `ble1m` with a Wi-Fi Aware data path beside it.
    - Discovery is log-normal around `knit.meshSimNanDiscoverMs` (4 s, screen off). The data path comes up 0.7–1.3 s
      later, or never at `knit.meshSimNanFail` (0.1, a guess). It runs at 1 MB/s on its own radio and lasts at most
      30 s.
    - While Aware sees the peer with no path up, a holder of a photo of 128 KiB or more waits up to 10 s for it
      (`LabTransport.armFile`, as the composite arms).
    - Aware's range was never measured: `knit.meshSimNanRangeM` defaults to Bluetooth's.
  - **What it found** (15 m, 1.3 m/s, 20 DMs each, seeds 1–3, 20 pairs a run, so 60 passes a setting;
    `/media/walter/data/knit-sim/passby/`):

    | Radio | What each brings | Converged in range | Text alone in range | Dial p50 |
    | --- | --- | --- | --- | --- |
    | `ble1m` | DMs only | 53/60 (88%) | 88% | 4.0–4.5 s |
    | `ble1m` | 5 photos | 0/20 (seed 1, before the clock fix) | not measured | 4.0 s |
    | `ble1m+nan`, 4 s discovery | 5 photos | 38/60 (63%; 75, 75, 40) | 100% | 3.5–4.0 s |
    | `ble1m+nan`, 1 s discovery | 5 photos | 55/60 (92%; 95, 95, 85) | 100% | 2.0–2.5 s |

    - **Text over Bluetooth is decided by the dial.** Every failure there never linked: its dial outlasted about 10 s
      in range. Once linked, text crossed by 1.5 s at p90.
    - **Photos need Aware, and need it before Bluetooth's link.** The passes that fall short are those where
      Bluetooth linked before Aware found the peer, or Aware never came up. Then the first photo asked for goes over
      Bluetooth, about 22 s for 200 KB, and the link drops first.
      - In the lab the photos behind it also wait behind it on the pair's one pipe. On a phone they would go over
        Aware beside it, but the one on Bluetooth still misses the pass.
      - Faster discovery (a screen on) moves the share from 63% to 92%.
    - **The field said always**, with Aware on, at 1.3 m/s, about 20 messages and 5 photos each. That fits the 1 s run
      better than the 4 s one, so the 4 s screen-off median is the draw to doubt first.
- **Full stores and custody's exchanges** (`CarriedLoad`, `ExchangeTally`, `LabNode.carry`, `LabTransport.airTaps`,
  2026-11-20).
  - **A carried load fills a store with others' mail.** `CarriedLoad` signs sealed DMs from free identities
    (`LabSybil`) to recipients who never appear, so nobody in the run opens or delivers one. It writes them straight
    into custody (`LabNode.carry`, through custody's own caps and digest but past the router), each author's profile
    first. The DMs are the size of a real DM between two of the run's nodes, measured on two sizer nodes that never
    walk.
    - Pass-by: `knit.meshSimPassLoad` frames from `knit.meshSimPassAuthors` (5) authors of each walker's own,
      interleaved with its own DMs by arrival.
    - Outings: `knit.meshSimLoad` frames from a pool of `knit.meshSimLoadAuthors` (20) shared by the whole field, at
      the boot's end. No two walkers carry the same frame.
    - Each author's share must stay under the 200-a-sender quota.
  - **Exchanges are read off the air.** Every air link tells its node's `airTaps` of each record it delivers.
    `ExchangeLedger` (pure, `ExchangeLedgerTest`) reads them in delivery order. An exchange opens when a digest lands
    at a peer, and the peer's frames to the asker until the asker's next digest are its answer.
    - Per run, and per pair in the pass-by: digests and their share of the air, exchanges answered and those that
      moved nothing new, frames re-sent to a node that already had them off the air, and each new frame's fate
      (reached its addressee, held at the end, not kept).
    - Re-sends split by the envelope's hops as aired: hop 0 is a custody answer, a key served (`KeyExchange.serve`) or an author's own send
      (`frames_resent_served_*`), hop 1 or more a router relay (`frames_resent_relayed_*`, `WireEnvelope.relayed`).
    - Also the gap from an exchange that moved nothing to the asker's next digest to that peer (`reask_after_empty_*`).
      Only an outing shows it, since a pass is too short for a second digest.
    - Summary keys end in `_pass` or `_outing`. An outing reads its tally once its links are down, since a frame still
      queued at its receiver would read as not kept. Only air links are seen, so `disc` reports none.
- **What it leaves out.** One link a pair, no path loss and none of the transports' own dial policies: `LabTransport`
  stands in for the whole radio, so the topology is the geometry's (and, under `ble1m`, a nearest-first budget's), not
  what Bluetooth or Wi-Fi Aware would have formed. `ble1m+nan` carries both planes on one pipe at the faster rate, so a
  file already on Bluetooth holds up the ones behind it. `disc` has no link budget, and `ble1m`'s is a measured rate,
  not yet a function of distance. Walkers move in an open square, alone or in groups that never part: no
  paths, arrivals, crowds or transit yet.

## JVM Room/DAO + migration tests (Robolectric)

`app/src/test/java/app/getknit/knit/data/` runs the **real** DAO SQL — the eviction/orphan/GC queries that
hand-written fake DAOs could only *mirror* (finding #5 in `docs/ARCHITECTURE_REVIEW.md`) — on the JVM
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
  to the rule above; it does not license a `KnitDatabase.build()` call in a Robolectric test. The backup
  round trip (`BackupRoundTripTest`: real writer → stager → applier) and `DatabaseKeyTest` (the
  wipe-on-proven-loss, never-on-refusal contract and the TEE key after a StrongBox refusal, each injected by the
  debug `FaultyKeystoreCipher` over the real Keystore) run there too, through `SandboxContext`, which moves `filesDir`,
  `noBackupFilesDir` and the database path under a temp root, so the live aliases are reused but the
  installed app's files are never read or written, except that `BackupRoundTripTest` lets the app's own
  `DatabaseKey` mint first (shape I1 under "Instrumented tests that hold on an emulator"). Run them on the managed emulator
  (`:app:pixel8api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…`), one class per
  run: under the orchestrator, a comma-separated class list ran only the first. Their JVM half is
  `RestoreStagerTest`, every refusal over a stand-in wrap (`RestoreStager`'s `secretAt` seam), because
  Robolectric has no AndroidKeyStore — and, for the verdicts themselves (ADR 2026-10.47rw), `KeystoreSecretTest`,
  `DatabaseKeyRecoveryTest`, `IdentityKeyStoreRecoveryTest` and `BackupWriterKeystoreTest` over
  `FakeKeystoreCipher`: real JCE AES-GCM behind the `KeystoreCipher` seam, with scripted lookups, unwraps and key
  generations (a StrongBox that refuses; `StrongBoxFirstTest` holds the StrongBox-then-TEE rule itself).
  Pass `sleep = {}` to a `KeystoreSecret` under test, or every refusal costs the real two seconds of retries.
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
  open time (caught here in CI), never silently wipes. The DB is at **v29** today, with twenty-eight migrations
  (`MIGRATION_1_2` … `MIGRATION_28_29`) and a `KnitDatabaseMigrationTest` case for each, plus a current-schema
  smoke test whose `CURRENT_VERSION` must be bumped by hand (`SqlCipherDriverUpgradeTest` reads
  `KNIT_DB_SCHEMA_VERSION`). Keep the version count down while a branch is
  unreleased — fold its schema churn into one bump before merging, the way `MIGRATION_9_10` carries the whole
  LoRa bridge, `MIGRATION_10_11` the whole drafts feature and `MIGRATION_21_22` everything after 2.9.0 (once
  v22…v27) — because a shipped migration can never be merged away afterwards, and a branch that mints its own
  numbers strands every lab device the moment it opens a database from the other branch. Fold up to the last
  release's version: check `git show v<last>:app/src/main/java/app/getknit/knit/data/KnitDatabase.kt`.
  **Folding a bump the lab has already installed needs a way back down.** `drafts.updatedAt` was briefly its
  own v12; collapsing it into v11 left the test phones holding a database whose `user_version` was *higher*
  than the app's, which Room throws on at every open rather than shrugging off. The way back is a registered
  downgrade `Migration(higher, lower)` — Room takes a descending path — and when the fold changed no columns
  its body is empty, because Room hashes schema *content* rather than version numbers, so only the stamp
  moves. Ship it, install across the fleet, then delete it; the alternative is wiping the drawer. The v12 → v11
  shim did exactly that on 2026-09-10 and is gone, and so is the v27 → v22 one (2026-10-18 to 2026-10-24),
  which was registered beside `ALL`, outside it, and landed on the current version, since Room never descends past
  its target. Before writing
  one, read the identity hash each lab phone holds out of its installed APK's dex (the `identityHash` of every
  `<version>.json`, grepped in `classes*.dex`) and check the fold's regenerated JSON carries the same hash: an
  empty shim is only right from a version whose schema the fold reproduces exactly. **An FTS table's sync triggers are the migration's job
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

## Compose preview screenshot tests (`app/src/screenshotTest/`)

Google's Compose Preview Screenshot Testing plugin (`com.android.compose.screenshot`, **alpha** — a standing
stable-only exception, ADR 2026-10.gtmm) renders each `@PreviewTest` function through layoutlib on the host JVM
and compares it, pixel for pixel, with a PNG committed under `app/src/screenshotTestDebug/reference/`.

- **Run:** `./gradlew :app:validateDebugScreenshotTest` (report:
  `app/build/reports/screenshotTest/preview/debug/index.html`; rendered and diff images under
  `app/build/outputs/screenshotTest-results/preview/debug/`). After an intended UI change,
  `./gradlew :app:updateDebugScreenshotTest` re-renders the references — review the PNG diff before committing
  it, because that is the review the test exists for. Never edit a PNG by hand.
- **Memory:** layoutlib leaks native memory the heap cap can't bound (~6 MiB RSS a preview; the full run peaks near
  8 GiB). GitLab's `test:screenshots` therefore runs `-Pknit.screenshotShard=<i>/<n>` shards, one `./gradlew` and
  one fork each; the shards split the preview files by `@PreviewTest` count (`app/build.gradle.kts`). Raise the shard
  count, not the heap, when a shard nears the runner's container limit.
- **Every public `@Preview` in main is a test, generated.** `python3 scripts/gen-screenshot-tests.py` writes
  one file per UI package (`ui.chat` -> `ChatScreenshots.kt`) of one-line `@PreviewTest` wrappers that call the
  main `*Preview()` — the plugin only runs previews in the `screenshotTest` source set, and calling main's keeps
  the sample data in one place. `--check` fails when a preview has no test, so after adding, renaming or
  removing a preview run the script, then `update`. Don't hand-edit the generated files; the judgement calls
  (which previews draw a screen, a kept `heightDp`, the large-font extras, the exclusions) are tables at the
  top of the script. Only `ScreenshotPreviews.kt` is hand-written: `@ComponentShots` (content-sized, light +
  dark) and `@ScreenShots` (a fixed 411×891 dp canvas, light + dark; a `Scaffold` fills whatever it is given,
  so a screen needs a fixed size). The wrapper's `@Preview`s decide size, theme and font scale; the main
  preview's annotation is ignored. A `private` preview stops the script — make it public, the convention.
- **A reference PNG is keyed by the wrapper's function name, the preview's `name` and a hash of its
  parameters.** Renaming either, or changing a `@Preview` parameter, orphans the old file: delete
  `app/src/screenshotTestDebug/reference/` and run `update` (it rewrites every image; git shows only the real
  changes). Keep spaces out of preview names (they land in the file name).
- **The render is one frame.** Anything a preview's content gets on a later frame is missing from the image: a
  `LaunchedEffect` fade-in, a `produceState` result, a size from `onSizeChanged`. `EmptyState` and
  `EncryptionSection`'s QR start settled under `LocalInspectionMode` for this (as `Motion.kt` already reads
  it); `AvatarCropDialog`'s photo still waits on its measured viewport, so its image checks the dialog chrome
  only. A `Popup` is a separate window the capture leaves out, so `ReactionPicker` is excluded.
- **Determinism is the preview's job, plus one pin.** Previews read `PREVIEW_NOW`, never the wall clock, and
  render through `KnitPreview` (static colour scheme — `dynamicColor = false`). Some format an absolute time
  with the JVM's zone and locale (the pause deadline, the message-details stamps, the profile and Your mesh
  dates — rendered under another `TZ`, twelve images moved), so `app/build.gradle.kts` pins `UTC` / `en-US` on
  both screenshot tasks. Layoutlib bundles its own fonts and Noto Color Emoji, so the images are
  host-independent: references rendered under `Asia/Kolkata` validated under `America/Los_Angeles`,
  `Pacific/Kiritimati`, `de_DE` and `ja_JP`.
- **Every subject also renders in two pseudolocales, light only:** `LongText` (en-XA, each string accented and
  padded to about 40 % longer) and `Rtl` (ar-XB, mirrored and laid out right to left), from the debug build's
  `isPseudoLocalesEnabled`. They are how a layout is checked against translated text before any translation
  exists; `context/translations.md` says what to look for. Only string resources change, not a preview's sample
  data. Every `@Preview` in `ScreenshotPreviews.kt` and the script's tables names its locale (`ENGLISH`,
  `LONG_TEXT`, `RIGHT_TO_LEFT`): layoutlib resolves an unnamed one to whichever locale's resources it finds, so an
  unnamed Light rendered as en-XA. A new multipreview must name one too. The references only record what each
  locale looks like: a clipped label passes once its image is committed, so the review of a new subject's
  `LongText` and `Rtl` images is the check.
- **The layout audit finds what the references only record.** `python3 scripts/preview-layout-audit.py` (`--only
  <wrappers>` to narrow it) composes every preview under Robolectric (`PreviewClippingTest`, run only under
  `-Pknit.previewLayout=<dir>`) on a 360×640 dp phone, the same in en-XA, and 411×891 at font scale 1.5, and lists
  each text or scrolling viewport cut off with no scroll to reach it, each text whose layout loses lines or width
  with no ellipsis, and each pair of texts drawn over each other. A screen preview gets the variant's whole frame, a
  component only its width (its screenshot is content-sized too); a dialog is its own window and goes unaudited. It
  was built for GitHub #62 (the caller card's last line squeezed to 18 of 60 px), which it flags on the pre-fix code.
  A text is judged by its lines where its box is wider than they are (a wrapped label fills its width, and a segmented
  button shifts that box 1 dp past its edge for the check). **It is a gate:** GitHub CI's `preview-layout` job fails
  on any finding, and on a preview left without a result (a test JVM that died). A preview it cannot judge goes in the
  script's `SKIP` with its reason; a skipped name that no longer exists fails the run. It only sees each preview's
  sample data, so a screen whose previews use short names needs a worst-case preview to be checked at all.
- **Both preview harnesses size windows as a phone does** (`RequestedWindowSize`, in `PreviewClippingTest` and
  `PreviewStringsTest`). Robolectric otherwise gives a `wrap_content` window the whole display, so `ViewRootImpl`
  measures a dialog at two widths on every traversal, and a text field re-measured at each requests the next one: a
  dialog with a field never idles (`AppNotIdleException` after 60 s). A new Robolectric test that composes a dialog
  with a text field needs the same rule.
- **Layoutlib's framework is not the device's.** `Formatter.formatShortFileSize` below a kilobyte renders its
  template: `(123) (B)` under en-US, the raw `${NUMBER} ${UNIT}` with no locale named (`MetricsSectionEmpty`'s
  "Bytes sent"). It is a renderer quirk the reference captures, not an app bug; 2.5 MB renders normally.
- **Failures read two ways:** `Size Mismatch` (a layout change moved a content-sized image's bounds — no diff
  image) or `Image does not match … Difference: x%` with a diff PNG that marks the changed pixels. Any
  difference fails; there is no tolerance configured (`testOptions.screenshotTests.imageDifferenceThreshold`
  would set one).
- **The references are Git LFS objects** — the repo's only LFS rule (`.gitattributes`), allowed because no
  release task reads them (`context/distribution.md`). Without git-lfs a clone holds pointer files, and both
  screenshot tasks refuse up front with "is a Git LFS pointer" rather than failing every comparison: install
  it, then `git lfs install && git lfs pull`. With `core.hooksPath=.githooks` the LFS hooks come from that
  directory; `pre-push` is the one that uploads the objects.
- **Scale and cost:** 254 subjects, 1,013 images (about 59 MB in LFS). A warm `validate` takes about two minutes.
  Renaming a preview re-keys its images without new LFS storage, since an unchanged PNG keeps its object id.
- **The Light references are also Weblate's screenshots.** `scripts/weblate-screenshots.py` uploads each one with
  the strings its preview reads (`PreviewStringsTest`), so translators see where a string lands. GitHub CI's
  `weblate-screenshots` job re-syncs them after every push to `main` that `screenshot-tests` validates, pruning a
  renamed or removed preview's old one; the "Screenshots" bullet in `context/translations.md` has the rest.
- **The render fork runs on a 2 GiB heap** (`app/build.gradle.kts`): the renderer keeps ~1.2 MiB of every
  preview reachable, so a heap too small for the run dies with an OutOfMemoryError (as a few failed renders) near
  its end. 1 GiB held 467 images and ran out at 929. Raise the heap if the preview count outgrows it; don't reach
  for `forkEvery`, which leaves only the last fork's results in the report.
- **CI runs it on both mirrors:** GitHub's `screenshot-tests` job (enforced and a required check since
  2026-10-26) and GitLab's `test:screenshots` (on skuld, still advisory). Each is the only job that fetches the LFS
  objects — GitHub through an `actions/cache` keyed on their ids, then `git lfs pull`; GitLab by setting
  `GIT_LFS_SKIP_SMUDGE` back to `0` against the pipeline-wide `1`, so `build:release` there builds from pointers as
  F-Droid does. A failure uploads the report, renders and diffs. The worry was that the references come from a
  workstation without AVX-512 and Skia's rasterizer can take another SIMD path on a runner CPU; GitHub's runners
  rendered them identically 15 times, and the one failure was a stale reference. The wrapper check (`gen-screenshot-tests.py --check`) is enforced
  already: GitHub's `generated-assets`, GitLab's `verify:screenshot-wrappers`.

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
- **HyperOS refuses the launch unless the test grants it.** Both bases start `MainActivity` from the app's own
  process while another app holds the screen. AOSP allows that for an instrumented app; HyperOS and MIUI refuse it as a
  background start (`MIUILOG- Permission Denied Activity`), and `ActivityScenario.launch` then waits with no timeout.
  `BackgroundStarts.allow()` sets MIUI's undocumented app op 10021 to `allow` before every launch on a Xiaomi device and
  logs what `appops get` reads back under the `BackgroundStarts` tag. A new launch path calls it too.
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
  `a11y-<screen>.txt` file via `TestStorage` — each finding names its check and its element (test tag or
  class, plus screen bounds). FTL collects them; locally AGP pulls them, because the runner argument
  `useTestStorageService` is set, into
  `app/build/intermediates/managed_device_android_test_additional_output/…/pixel8api34DebugAndroidTest/`
  (`connected_android_test_additional_output/` for a connected device), beside AGP's per-test
  `logcat-<test>.txt`. Suppress a known-acceptable
  finding with `setSuppressingResultMatcher(...)`; widen the gate with `setThrowExceptionFor(...)`.
- **Contrast needs real pixels, so screenshot capture is hardware-gated.** ATF's text/image contrast checks
  read a screenshot; on the headless emulator `UiAutomation.takeScreenshot()` races the UI thread and
  returns null → ATF NPEs. So `setCaptureScreenshots(!isEmulator())`: **off on the emulator** (contrast
  reports NOT_RUN; structural checks — labels, touch targets, traversal, duplicate/redundant descriptions —
  still fully run) and **on for real hardware**, so contrast actually runs on the Firebase Test Lab
  physical-device pass (and Play's pre-launch report covers it too).
- **Three contrast misreads are suppressed, exactly** (`ContrastMisreads` in the test): a text-contrast
  finding on an emoji (the glyph's artwork is not a text colour), on a node with a disabled ancestor (WCAG
  exempts inactive controls, and Compose's label node does not carry the button's disabled state), or on an
  editable field whose estimated foreground is the theme `outline` (the border, not the text). Text drawn on
  a tinted fill takes `ui/theme/ReadableColor.kt`'s `readableOn`, which is how the chat bubbles' coral names,
  mentions and links clear 4.5:1 in light mode and under Material You.
- **Run locally** (headless emulator, no physical device):
  `./gradlew :app:pixel8api34DebugAndroidTest -PseedDemo=true
  -Pandroid.testInstrumentationRunnerArguments.package=app.getknit.knit.a11y`.
- **Run on FTL**: the `a11y` package runs on an **API-34+** Firebase Test Lab device; it also rides the default
  FTL run but *skips* on the API 29/33 devices. (Maintainer runner lives in the `.private/` overlay.)

When driving the emulator over `adb`: the soft keyboard overlaps via `adjustResize`, so read element
coordinates from `uiautomator dump` rather than guessing; seed the photo picker by `screencap`-ing
into `/sdcard/Pictures` if you need an image to select. For the headless debug bridge (send/verify without
screenshots), see `context/debug-bridge.md`.

## Egress check (`scripts/egress-check.sh`)

The README promises that a fresh install makes no network calls at all; this script is that sentence as a test. It
installs the staging APK (release with R8, debug-signed; `--variant debug` for the debug build) on an emulator it
boots on an adb server of its own, grants what onboarding asks for, leaves every setting at its default, opens the
app, sends it home and back, and idles with the mesh service up (`--soak`, 180 s by default). It fails if the
kernel's per-UID counters (`dumpsys netstats` after a forced poll) charge a single byte to Knit's UID, or if a
two-second sample of `/proc/net/{tcp,udp}{,6}` catches one of its sockets open to anything but loopback; a DNS
lookup counts, because netd charges the resolver's socket to the asking app. An emulator has no Wi-Fi Aware and no
real Bluetooth, so no mesh traffic can be IP there: every byte it counts was headed for the Internet.

A self-test pings the host from the shell UID first and refuses to pass if the counters missed it. `--control`
(debug builds) then drops the preconfigured relay, switches the relay plane on towards a closed port on the host
(`10.0.2.2:9`) through `…debug.SPOOL` and expects the dial to be counted, which can take up to the plane's 60 s
reconcile tick. Run it after adding anything that can open an Internet socket: a new opener behind
`net/InternetGate`, a dependency that phones home, or a default that switches a plane on. It never runs on a phone:
a `--serial` that is not an emulator is refused.

## A main thread the system cannot see stalled

To reproduce an ANR from a stall the platform doesn't know about (a vendor freezer, #248), stop the process with
`adb shell "run-as app.getknit.knit kill -STOP <pid>"` and resume it with `-CONT`. That works on a debug build
with no root. `am freeze --sticky` is the platform's own freezer, and AMS does not run the "executing service"
timeout against a process it froze itself, so a negative control under it stays green (verified on API 37,
2026-11-02). Read the result with `dumpsys activity exit-info app.getknit.knit` (`reason=6 (ANR)`) and the
`ActivityManager: ANR in` line. ADR 2026-11.82s7 has the trial.

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

## Real BLE from an emulator, with the HCI tapped (`emulator-hci-bridge.sh`)

The other route. The dongle stays on the host, and the emulator's Bluetooth HAL is pointed at a Bumble bridge
instead of netsim (`emulator -packet-streamer-endpoint localhost:8877`). So the app, the AOSP host stack and the
controller are all real, and every HCI packet between them passes through `scripts/hci-bridge.py`. The
controllers are knit-ios's two nRF52840 dongles (Zephyr `hci_usb`, `firmware/knit-hci`). Spiked 2026-10-04 on
`Knit_Mesh_BT` and dongle B: the emulator joined the lab mesh, linked knit-peer on dongle A, and DMs crossed both
ways.

- **What it buys over passthrough and a phone:** no root image, no firmware push, no rebind. Also:
  - a live btsnoop (`build/hci-bridge/latest/hci.btsnoop`, read with `btmon -r`) and a one-line-per-event log
    (links, PHY, data length, parameters, every command the controller refused);
  - faults armed at run time (`ctl refuse | cut | deafen`). `ctl refuse 0x2039 0x0d --param0 0x01 --count 0`
    held for 45 s showed ADR 2026-10.9utz's keeper at +2.5, +5 and +10 s from the controller's side.
- **Its first findings:**
  - GD pauses and resumes every advertising set around each accept-list write (9utz's reading, live).
  - Zephyr refuses a second accept-list dial while a link it dialed is up (`0x0b`). See the limits in the
    script's docstring.
- **The BR/EDR shim and both arbiters are load-bearing.** Without the shim, GD crash-loops on an LE-only
  controller (`controller.cc:330 read_local_name_complete_handler`, then `btm_sec.cc` "only controllers with
  SSP"). Without the scan arbiter, GD's dial-while-scanning gets Zephyr's `0x0c`, and the emulator never dials.
  Without the advert arbiter, an accept-list dial goes deaf whenever GD turns its advert back on mid-dial (the pair
  section below). All three are in the script's docstring.
- **The dongles are knit-ios's rig** (its `.agents/rules/devices.md`): A is knit-peer's default adapter, B its
  second peer. While the bridge holds one, BlueZ has no such adapter. Check that no knit-ios run uses it, and
  give it back with `down`, which resets the controller before letting go: a dongle released mid-stream wedged
  until a replug on 2026-10-04.
- **Pairing it with knit-peer** (knit-ios `.build/out/Products/Debug-linux-x86_64/knit-peer`, its own `--instance`)
  is the phone-free cross-platform check. Give the peer an id above the emulator's (`keygen --above`), so the peer
  dials and the one-dial limit never bites. Then `deny-link` every other node it hears, as `interop.py` does.
- **The emulator still hears the lab phones and the iPhone** and links them like any node, so it pulls their
  custody. Driving none of them, it needs no go-ahead. `KNIT_HCI_ISOLATE=1` (the bridge's `--isolate`) makes it hear
  only another isolated emulator instead: the next section.
- **Don't `pkill -f` the bridge or the peer's feed from an agent shell.** The pattern matches the shell's own
  command line and kills it. Use the pidfile.

## Two emulators meshing over real Bluetooth (`ble-pair-test.sh`)

`scripts/ble-pair-test.sh all` boots two emulators, one on each nRF52840 dongle through the bridge above, installs,
runs the `blepair/` classes on both at once, and tears down. (`up [a|b]` / `install` / `run` / `down` run the steps
one at a time; `KNIT_PAIR_CLASSES=<fqcn>[#method],…` runs a subset.) The classes run in one order on both sides,
each on the shared `BlePairTest` base:

- `BlePairMessagingTest`: the link, a DM with its ✓✓, a Nearby post, a quoted reply, a reaction, typing, a DM at
  `TextLimits.MESSAGE` in more than ASCII, and a formatted DM and post whose styles land as sent (ADR 2026-10.p95v).
- `BlePairAttachmentTest`: a photo, and a file through the file door under its own name.
- `BlePairProfileTest`: a new display name, and a profile photo the peer fetches and holds.
- `BlePairGroupTest`: one side starts a group the way Contacts does, and both post in it.
- `BlePairLinkTest`, last: a cut the stack heals by its own dial, and messages written while out of range that
  wait in the outbox and land on return.

Each side asserts what it sees itself. The first full green run (2026-10-05, 14 of 14) took about two minutes:
linked in 17 s, typing in 40 ms, photos in 5.4 s each way, a name change in under 0.1 s, a profile photo in 12 s,
relinked 14.6 s after the cut. Each run writes `build/ble-pair/<stamp>/`: per side, the instrumentation output,
logcat and that side's bridge log and btsnoop.

- **Isolation is the bridge's job, not the app's.** `--isolate` moves Knit's 16-bit UUIDs (0xFE30 presence, 0xFE38
  side pages) to 0xFE20/0xFE28 on the way to the air and back on the way in. It drops every report except a
  partner's, before the host sees it, so the APK under test is the ordinary debug build. Both sides fail the
  test if any other node ever shows up in `neighbors` (`@After`). Pinned by `scripts/test_hci_bridge.py`.
- **The pair has an adb server of its own** (5038), and its consoles sit on 5602/5604, past 5585, where an adb
  server's emulator scan stops. Android Studio's adb client floods the shared 5037 server while these emulators are
  on it. It opened 12 k sockets within a minute, pegged the server and every adb call timed out, and a 5037
  server Studio restarted found the emulators by its scan and brought the flood back. The lab phones also live on
  5037, so the pair's `install` (`adb install` of the Gradle outputs, never `installDebug`) can't reach one.
- **Lean on purpose: `Knit_BLE_Pair`** (`ble-pair-test.sh avd` creates it): `aosp_atd` API 34, 2 cores, 1.5 GB,
  booted one at a time, `nice 10`. Two `google_apis` API 36 emulators at 4 cores each, cold-booting at once,
  took the host to load 32. The AVD boots `-read-only`, so both run from it at once.
- **Every run starts both apps clean** (`pm clear`). A read-only AVD booted twice also boots its userdata twice, so
  an app installed in the AVD itself would come up as one identity on two phones. `KNIT_PAIR_KEEP=1` skips it, and
  `run` refuses a pair with one node id.
- **`am instrument` kills the app when its last test returns,** taking its mesh with it. The first run's cut test
  failed this way: one side finished, and the ✓✓ it still owed the other died with it. So every test ends in a
  rendezvous (a DM each way), and each class lingers 5 s before it returns. A new test keeps that shape: both sides
  run the same steps, the run tag goes in every body, and one side alone does anything one-sided (the cut goes to
  the smaller id, which never dials). The cut reaches the bridge through `adb reverse` of the bridge's control port.
- **The classes skip without `knitBlePeer`,** so `connectedDebugAndroidTest` and Firebase Test Lab pass straight
  over them. **Once the pair loses each other** (no link, or a rendezvous that never comes back), every later test
  skips, naming the test it happened in: a dead link otherwise sits out a 3-minute timeout per test.
- **"Out of range" is two bridge faults, not one.** The dialer's bridge deafens *and* refuses every dial (`refuse
  0x2043 0x0c`). `deafen` only hides adverts from the host: the controller's initiator still hears them, and a dial
  from the dialer's cached sighting relinked 11 s into a 40 s "outage" on the first run.
- **Between runs the apps keep meshing.** The system restarts the sticky `MeshService` after `am instrument` kills
  the process, so a pair left up relinks by itself; `run` starts clean anyway.
- **Dials that never landed were Zephyr's; the bridge's advert arbiter now holds the cause off.** From 2026-10-05,
  a GD dial from one emulator to the other either connected within seconds or timed out at Knit's 12 s watchdog run
  after run, with no connection attempt (no 0x3e), aimed at the address the peer was airing. It survived bridge
  restarts, a replug and a dongle swap. On 2026-11-11 it passed for a checkout fault (runs from the main checkout
  failed while fresh worktrees passed) until a main-checkout run passed too. The cause: GD turns its own advert off,
  dials through the accept list, and turns the advert on again 3 ms later. Zephyr's advert enable empties the
  accept list the running dial matches against (the code path is in the script's docstring). So the dial landed
  only when the initiator armed its radio filter first: runs went bimodal, and one direction could work in the
  same minute the other did not. `scripts/dongle-dial.py` never met it, since it dials an address and airs no
  advert of its own. Replayed on the bare dongles with raw HCI, GD's order connected 0/4 and 3/4, the advert turned
  on before the dial 8/8, and a dial without the accept list 4/4; PHYs and the peer's advert interval changed
  nothing. The tests still wait up to 5 min for the link. If dials stop landing again, tally each side's dials
  from its capture (`btmon -r build/hci-bridge/latest-<d>/hci.btsnoop`: an Extended Create Connection, then an
  Enhanced Connection Complete with `Success` or `0x02`) and read `adverts_held` in `ctl status`.
- **`KNIT_PAIR_DIALER=a|b` picks the dialing side.** With the blind switch off, the larger node id dials, and every
  `run` mints fresh ids, so which dongle dials is a coin toss. A side holding the larger id while its own dials
  time out also refuses the other side's working dials ("bt refused client … sighted=true"), so the pair never
  links. `KNIT_PAIR_DIALER=b scripts/ble-pair-test.sh all` mints fresh identity pairs (`pm clear`) until side b
  holds the larger id, up to 12 tries, then runs. With `KNIT_PAIR_KEEP` it refuses a pair the wrong way round
  instead of minting. It came in for the dial fault above, before its cause was found; it still helps only when one
  side's dials fail, which the btsnoop tally tells apart.
- **`ctl dial-phys 0x01`** limits a dial's initiating PHYs (an experiment knob: 1M-only dials relinked no better).

