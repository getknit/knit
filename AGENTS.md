# AGENTS.md — Knit

Router for coding agents. Knit is an offline Android **mesh messenger** (Kotlin/Compose) that runs
**Wi-Fi Aware (NAN) + Bluetooth LE** simultaneously behind one `MeshTransport` seam
(`CompositeMeshTransport`), no Google Nearby / GMS. This file *points* to context — load a `.agents/`
file only when its trigger matches. Full design detail lives in `docs/`.

## Identity

You are a senior Android/Kotlin engineer on a deliberately bleeding-edge toolchain (AGP 9.3.0 /
Kotlin 2.4.0, Koin DI). Favor correctness, wire/convergence safety, and matching the surrounding style
over cleverness. Start with `.agents/context/architecture.md` for the subsystem map and data flow.

## Context routing

- **Before any build / dependency / tooling change:** READ `.agents/context/toolchain.md` — the
  bleeding-edge choices (Koin-not-Hilt, the Kotlin-2.4 override, detekt/ktlint/Kover/Room as Gradle
  plugins) are deliberate; don't "fix" them.
- **Before / after running Gradle:** obey `.agents/rules/build-and-test.md` (which task when, JDK 21,
  lockfile regen). Command list: `.agents/context/commands.md`.
- **Before touching `app/src/main/baseline-prof.txt`, the `:baselineprofile` module, or the
  `nonMinifiedRelease` build type:** READ `.agents/context/baseline-profile.md` — the generator is quarantined
  behind `-Pknit.baselineProfile=true` on purpose, so that the release build consumes a committed text file
  and stays byte-reproducible for F-Droid. Don't wire the plugin into `:app`.
- **Before touching release signing, `packaging`/`ndk` config, `.gitattributes`, or anything else that
  changes release-APK bytes:** READ `.agents/context/distribution.md` — Play and F-Droid ship different
  artifacts under different keys, and F-Droid byte-compares its own rebuild against ours, so the release
  build must not depend on the build machine (no NDK on the APK path, no foojay JDK download, no Git LFS).
- **When editing any Kotlin/Compose/data code:** obey `.agents/rules/coding.md`.
- **When adding to `CHANGELOG.md`'s `## Unreleased`, or writing a fastlane changelog:** obey
  `.agents/rules/changelog.md` — two sentences, about forty words, run through the `humanizer` skill.
  A `PreToolUse` hook blocks the edit otherwise. Shipped sections are a record; never restyle them.
- **When touching `mesh/`, `protocol/`, or `data/`:** obey `.agents/rules/mesh.md`, then READ the
  relevant reference — `.agents/context/mesh-transport.md` (radios / NAN / BLE),
  `.agents/context/wire-format.md` (CBOR wire), `.agents/context/store-and-forward.md` (custody /
  convergence), `.agents/context/e2e-encryption.md` (crypto). If a change can only be made by *breaking*
  the wire, don't — park it in `docs/NEXT_WIRE_BREAK.md` (the staging list, so a future break carries them
  all at once) and find the additive route per `docs/WIRE_COMPAT.md`.
- **When touching `mesh/bluetooth/BleSideChannel`, `SideCarousel`, `SideScanPolicy`, `SideCapableTracker`,
  `BleFastRoutePolicy`, `BleAdvertPayload`'s flags byte, or `BluetoothMeshTransport.fastFanout`/`fastSend`:**
  READ ADR 2026-09.sjaa and the "page carousel" section of `.agents/context/mesh-transport.md`. The BLE side
  channel carries `shouldFastFanout` frames on non-connectable extended-advertising pages — one
  `FastFrameCodec` unit per 236-B page, never a chain, never presence, never a DM-form frame — gated per
  peer on the advert's flags byte and dark in release behind `BuildConfig.BLE_SIDE_PLANE` until its device
  trial (CHECK `.agents/memory/roadmap.md`). `hasFastPlane` is now true for Bluetooth: the link copy the
  composite used to send lives inside the transport's `fastFanout`/`fastSend`; don't add it back upstream.
- **When touching `mesh/lora/` or `mesh/bluetooth/meshtastic/` (the LoRa/Meshtastic bridge):** READ
  `.agents/context/lora-bridge.md` — a Meshtastic board over BLE GATT extends the **Nearby room and 1:1
  DMs** over LoRa as a fast-plane-only `MeshTransport` child, shipped visible since 2.5.0 behind
  `BuildConfig.LORA_PLANE` and off until the user pairs a board (ADR 038 + 039, introduced by ADR
  2026-09.6gtm — it is no longer a gate that keeps anything out of shipped builds). `mesh/lora/` is
  pure/JVM-tested; the only `android.bluetooth.*` importer is `mesh/bluetooth/meshtastic/MeshtasticGatt`.
  **Before touching `DmAutoReplyPolicy`, `Destination.Reply`, `OutboundFrame.to`, or `MeshtasticLink.send`'s
  `to`:** READ ADR 2026-09.4n5p — a Meshtastic DM to a set-up board is answered once with a fixed unicast
  (once per sender per day, once per 30 s for anybody, the room's air share, never from a stock or a
  dedicated-slot board), and that reply is the only unicast the plane sends.
- **When touching `linkpreview/`, `net/`, `mesh/protocol/LinkPreviewBlob`, or anything that opens an
  Internet socket outside the spool plane:** READ ADR 2026-09.n752 (and 2026-09.7x8k: a send holds up to 5 s
  for the card its link is fetching, or the share sheet never carries one; a LoRa thread takes a card exactly
  as it takes a photo — no `loraCarry` gate on the fetch). A link preview is a card the
  **sender** fetches and sends as an ordinary attachment under its own MIME (no wire field, no DB change); the receiver
  never fetches, both ends screen the card's picture and text into one verdict, and the fetch is gated on
  `net/InternetGate` (a validated route, never the NAN link), bound to that `Network`, https-only, with a
  private-address DNS guard. `okhttp3` stays confined to the two files `rules/mesh.md` names.
- **When touching `transfer/`, `MeshTransport.pause`/`resume`, or anything that hands the Wi-Fi radio to a
  second role:** READ `.agents/context/direct-transfer.md` — a large file goes to one nearby contact over a
  Wi-Fi Direct group the two phones raise, never over the mesh and never custodied (ADR 2026-09.wtmz, the
  seal 2026-09.37ce, the surfaces 2026-09.7uqe). `TransferManager`/`TransferStream` are pure behind the
  `DirectWifi`/`TransferFiles`/`TransferSignals` seams, and `AndroidDirectWifi` is the one
  `android.net.wifi.p2p` importer. Wi-Fi Aware does **not** yield to our own P2P on Android 12+, so it must
  be paused explicitly — that is what `pause`/`resume` are for.
- **When touching `location/`, the chat overflow's "Send location" item, or anything that reads the device's
  position:** READ ADR 2026-09.tss4. A shared position is a `geo:` line in the message body (no wire field,
  no capability bit); it is read only between the pin tap and the send, by `ChatViewModel.startLocation`,
  the one collector of `LocationSource.fixes`, and `location/AndroidLocationSource` is the one
  `android.location` importer (detekt-enforced). Never ask for the grant at onboarding.
- **When touching `mesh/MeshService` (`onCreate` / `onStartCommand` / `postForeground`), `MeshService.start`,
  `canReclaimForegroundService`, or the `KnitApp` effects that start the mesh:** READ ADR 043 (a refused claim
  makes a stillbirth; the caller-side guard and the resume retry) and ADR 2026-09.f69x (every non-Stop start
  re-claims the foreground state — the system demotes a background-restricted app's service silently, and a
  `startForegroundService` into that instance arms a deadline nothing else would meet). `onCreate` claims the
  state before it resolves the graph; keep it that way. The deadline is 30 s on Android 15 (10 s before), and
  `getForegroundServiceType()` cannot tell you it was lost. `BootReceiver` starts through
  `MeshService.startFromBoot`, never `start` — ADR 2026-09.29dw: the pre-check reads process state, a
  receiver's is `IMPORTANCE_SERVICE`, and the boot exemption is the platform's. Regression:
  `MeshServiceForegroundReclaimTest`, `GraphlessProcessTest`, `MeshServiceStartTest`, `BootReceiverTest`.
  **Before touching the type bitmask `postForeground` claims (`meshForegroundServiceTypes`), the manifest's
  `<service>`, or `TransportHealth.ForegroundOnly` / `NanSessionFault` in `mesh/wifiaware/`:** READ ADR
  2026-09.535d. The service claims `location` on exactly the tiers where `requiredRadioPermissions` rides the
  location grant (29-32) — the Wi-Fi Aware publish/subscribe are gated on the foreground-only location app-op
  there, and only the *runtime* type bit lifts it (the manifest is a bound, never a grant; `0` silently drops
  it). The manifest's `FOREGROUND_SERVICE_LOCATION` lint is suppressed on purpose (Play's declaration form). A
  location refusal off screen is held as `ForegroundOnly` and retried on `heal()`, never torn down through
  `onSessionDead`.
- **When touching the responder's `onUnavailable`, `refileResponder`, `NanResponderPolicy`, or what refunds
  `responderRefusals` / `responderCycles` in `WifiAwareTransport`:** READ ADR 2026-09.bgk3. A verdict with a
  link, handshake or accept of ours live is the documented knock refusal — re-filed after the floor, never
  counted; a verdict with the interface free is about the request — counted, backed off, and at five in a row
  given up for a session cycle, three per episode, refunded only by the responder's `onAvailable` (never a
  fresh session or the availability edge: the cycle produces both). The tests are in `NanResponderPolicyTest`.
- **When touching `NanInitiatorPolicy`, the `TRANSPORT_WIFI` watch in `WifiAwareTransport` (`onStaLost` /
  `onStaAvailable`), `initiatorHeld` / `releaseInitiatorHold`, `NanInitiatorJournal`, or what `digestSyncWanted`
  / `bulkSyncWanted` gate on:** READ ADR 2026-09.m8kc. A Wi-Fi blip (lost → available in 15 s) within two minutes
  *after* an unlinked initiate of ours is a strike; three hold the initiator role — the responder, discovery,
  cues and the fast plane keep running — refunded only by an initiator link, the user's Try again, or the
  build+ROM stamp, never by `stop`, `heal`, a session cycle or the Aware edge; one probe initiate a day on the
  wall clock. The hold lives in those two gates so the wedge watchdog never counts a held peer as owed (Tier-2 is
  a process kill and the hold is journaled): never read `reconcileWanted` / `bulkWanted.isWanted` around them.
  Not a `NanConnectPolicy` streak. Tests: `NanInitiatorPolicyTest`.
- **When touching `legal/`, `ui/about/`, `app/src/main/assets/legal/`, `THIRD-PARTY-NOTICES.md`, or a shipped
  dependency:** READ ADR 2026-09.6eb6. The in-app Open-source licenses list is `legal/ThirdPartyNotices.kt`,
  pinned to the notices table by `ThirdPartyNoticesSyncTest` and to `app/gradle.lockfile`'s
  `releaseRuntimeClasspath` by `ReleaseClasspathNoticesTest` — a new shipped dependency is a row in both files
  (with the artifact prefixes that claim it) or a `NOT_SHIPPED` entry with its reason, and nothing else makes
  those tests pass. The license texts are committed copies (no Gradle task; `assets/legal/COPYING` is
  byte-pinned to root `COPYING`), and nothing from the build machine — git SHA, timestamp — goes into About:
  the release APK is byte-reproduced by F-Droid. No license plugin; `legal/InstallSource.kt` is the app's
  one installer read.
- **When touching `ui/onboarding/`, `ui/Permissions.kt`, `ui/BackgroundBattery.kt`, or `BootReceiver`'s start
  decision:** READ ADR 2026-09.nzpr. Entry is gated on `hasRadioPermissions` alone — the transports assume
  those grants (`@SuppressLint("MissingPermission")` is a lint suppression, not a runtime guard), so the mesh
  never starts without them. `POST_NOTIFICATIONS` and the battery exemption are optional rows on the
  permissions page (`MessageNotifier` self-checks before posting), and `SettingsStore.onboardingSeen` only
  picks which page a returning phone opens on, never whether onboarding shows. The name page writes through
  the shared `ui/components/DisplayNameField`; a grant added to `requiredRadioPermissions` re-wedges the
  front door.
  The battery row (here and in Settings) reads `ui/BackgroundBattery.kt`'s three-position enum, not the bare
  exemption — READ ADR 2026-09.gc3m before touching it: Restricted wins over a stale exemption, and no prompt
  of ours can lift it, so that state always lands on "Open settings".
  The unused-app row beside it reads `ui/UnusedAppPause.kt` (`isAutoRevokeWhitelisted`, API 30+, null below
  so the row hides) — READ ADR 2026-09.54xg: it has no prompt, its default is not an error (`quietHint`), and
  its callback is `openUnusedAppPauseSettings`, not `openSettings`, because Android 11 keeps the switch on a
  page of its own.
- **When touching `ui/DeviceSupervision.kt`, `ui/components/PermissionDeniedDialog`, the `settingsHint` of a
  permission row, or the location / mic / camera gates' denial copy:** READ ADR 2026-09.a8ud. A parent's or
  an administrator's denial (`POLICY_FIXED`) is indistinguishable from "don't ask again" from inside the
  app, so the only signal is *who administers the phone* — Family Link by its pinned profile-owner package,
  else any management signal — and every hint is conditional ("if Settings greys it out as Disabled by
  admin", the platform's own wording). Family Link
  can hold only the classic groups (never Nearby devices), so `supervisedHint` names a parent on the radio
  row only where Location is in it; a managed phone is named everywhere. Family Link's pauses and downtime
  leave the running foreground service alone (device-verified 2026-09-16) — don't add restart machinery
  for them; `android.app.admin.*` / `UserManager` stay confined to that one file (detekt).
- **When touching `data/draft/`, what the composer keeps between visits, or the chat list's `Draft: …`
  preview:** READ ADR 2026-09.qtg9. An unsent draft is a row in the encrypted DB (never the DataStore —
  it is message text), written debounced on the *application* scope, and handed to the composer exactly
  once by `consumeRestoredDraft()`; nothing is persisted before that hand-over, because the field reports
  itself empty the moment it composes. The list shows it in place of the preview only while
  `updatedAt` beats the newest message, and never touches the row's time or sort. Text only: the staged
  attachment, the reply quote and the mention bindings stay draft-local to the screen.
- **When touching `data/search/`, `messages_fts` / `MessageFtsEntity`, `ui/search/`, the chat route's
  `messageId` argument, or the shared list rules in `ui/ConversationTitles.kt` / `ui/contacts/ContactUniverse.kt`:**
  READ ADR 2026-09.wdfz (the FTS4 index: one bounded `MATCH` read, the `term*` builder, the rowid and
  no-VACUUM invariants) and ADR 2026-09.wnh6 (one screen over the chat list's own universe — its
  membership, titles, speaker and contacts rules live in those two shared files so search cannot drift
  from the list; a hit opens the thread through `chat/{id}?messageId=` and the quote-jump machinery,
  never a second thread view). "Search in this chat" is deferred — CHECK `.agents/memory/roadmap.md`.
- **When touching `ui/yourmesh/`, `mesh/ContributionLedger`, `data/settings/ContributionJournal`,
  `data/peer/MetPeer*`, `ForwardDao.observeCarriedForOthers`, or the `onRelayed` / `onServed` hooks in
  `MeshRouter` / `ForwardSync`:** READ ADR 2026-09.2v2t. The Your mesh screen's numbers are shown to the user
  as things their phone *did*, so a credit happens only at a hand-off (this phone sent someone else's chat
  frame to ≥ 1 peer), once per frame, never for our own frames or a DM addressed to us; "handed straight to"
  is a live-link send and is never worded "delivered". "Nearby" and "met" both derive from
  `MeshController.neighbors` (ADR 2026-09.2ajk) — don't add a second gate. Counters flush on the 60 s tick,
  never per frame, and nothing here leaves the phone.
- **When touching a presence dot or an online / offline label** (Profile Details, Diagnostics' node
  sections, the Contacts dot): the three evidence tiers live in `ui/Reach.kt` — `Direct` is
  `MeshController.neighbors` (a short-range radio saw the peer's own radio), `Relay` is the long-range reach
  set or a spool scope its peer recently pushed to (`mesh/spool/SpoolPresence.kt`, one function the dot reads
  at 45 min and the mesh at `SPOOL_COVER_MS` = 15 min), `Known` is a bare profile row — and both labelled
  surfaces derive from `reachOf` so they cannot disagree. READ ADR 2026-09.2ajk before loosening any tier;
  the Contacts list still draws a binary dot from `neighbors` alone.
- **When touching how a Nearby-room post's ✓✓ gets home** — `AckSync`'s ride hold / `RIDE_HOLD_MS`,
  `MeshTransport.coveredByInternet`, `ScopeSync.pushDirect` / `presentPeers`, `MeshRouter.handOn`, or
  `MeshManager.ownProfile`:
  READ ADR 2026-09.y5f3 (aa27's ride hold now has a 60 s deadline; the tick then goes to a spool the author
  was recently seen on — a signed `relay = false` frame pushed direct and accounted, never custodied on the
  acker — else over LoRa's targeted path; every DM-form frame to a spool-present peer stays off the board;
  a node's own profile has one set of bytes per publish stamp) and ADR 2026-09.wkbk (`MeshRouter.handOn` —
  a point-to-point frame addressed to a peer we hold a **live link** to takes that one hop, DM-form chat
  only, which is how a far pocket's tick reaches a board-less author behind the gateway; an acker with no
  board and no spool is still stranded). Then `docs/ENCRYPTED_RECEIPTS_REACTIONS.md`
  §5, spec §9.4 C-9.4-3, and `RoomTickPlanesLabTest` in `mesh/lab/` for the end-to-end shape.
- **When touching `ui/components/Avatar`, `ui/components/GroupAvatar`, `ui/theme/AvatarTint.kt`,
  `data/message/GroupFaces.kt`, `ui/util/ClusterGeometry.kt`, or the notification avatars in
  `notifications/NotificationAvatars`:** READ ADR 2026-09.j8c7. A photo-less avatar's colour is keyed on the
  **node id** (`avatarTintIndex`, pinned by `ColorSchemeTest`), from a static twelve-hue palette that
  `scripts/gen-avatar-palette.py` generates; under Material You `KnitTheme` harmonizes it toward the
  wallpaper's primary (a 15°-capped Oklch hue turn, tone kept), and the shade draws the same slot the same
  way. Pass a name as the key only for a face with no identity behind it. A photo-less **group** is a
  cluster of its other members (`groupFaceIds`: self out, by node id, at most four, at least two — else a
  people glyph on a disc tinted by the group id) laid out by `clusterCells`, which the shade draws from the
  same cells; renderers never re-sort — READ ADR 2026-09.zapp.
- **When touching a group's roster — `reconcileGroup`/`vetRoster`, `groupleave`, `GroupRepository.recordDeparture`
  / `recordRejoin`, `PendingGroupKeys`, `GroupKeyPayload.group` / `pinRosterFromSeed`, or how a member learns
  of a new group:** READ `docs/GROUP_FORWARD_SECRECY.md` §1 (the pinned founding roster) and §6.1
  (leave-rekey), then ADR 2026-09.v6fu and ADR 2026-09.mjaj. A group's id is the hash of its member set, so
  "the same people" is always the same group; membership shrinks only by your own signed leave and grows only
  by your own signed rejoin — nobody can add or remove anyone else. The seed carries the founding roster, so a
  member with no row pins the group from the seed through `reconcileGroup`'s one door (the relay-only case,
  #47); a seed without one (an older build's) is parked, never consumed.
- **When touching contact cards, the Add-by-link / share-link flow, deep links (`getknit.app/c`,
  `knit://`), or `mesh/IntroSync`:** READ `docs/CONTACT_CARD.md` (the card layout + golden vectors, the
  intro driver's rules, the assetlinks prerequisite) and `docs/SPOOL_PROTOCOL.md` §3.5 (the pair scope);
  decision record ADR 042. The card is versioned by `v` and additive under the WIRE_COMPAT rules; import
  never sets `verified`.
- **When touching relay invites — `getknit.app/r` / `knit://r`, `mesh/spool/RelayInvite`,
  `data/relay/RelayInviteApplier`, `ui/relay/RelayInviteSheet` / `RelayInviteInbox`, the relay row's
  Share / Copy, or the Add-contact preview's relay "Add":** READ `docs/RELAY_INVITE.md` (layout, the
  bearer-link trust rules, golden vectors, the daemon contract) and ADR 2026-09.tmbq. One unsigned link
  carries the relay URL *with* its `?k=` token and the commons secret; it is applied only through the
  sheet (host named, cost stated, the master switch's disclosure folded in), by the one applier both doors
  share — `acceptSpoolConsent()` stays the only consent write (ADR 063), a relay is matched by
  `SpoolUrl.redact`, and a different room secret at the same relay is a rotation.
- **When touching `data/commons/`, `mesh/spool/Commons*`, `ConversationKind.COMMONS`, or the relay row's
  Join/Leave:** READ ADR 2026-09.wx8e and `docs/SPOOL_PROTOCOL.md` §7.4. The commons is a private relay's
  group chat: members' `profile` frames pin them through the ordinary door and make them accepted contacts,
  posts are a non-custodial `commons` frame that lives on the one spool that runs the room and never touches
  the radios (`ScopeSync`'s second door, `InboundPipeline.deliverCommonsPost`), and a post ahead of its
  author's profile is parked, never quarantined. `Conversations.isPublicRoom` is true for it on purpose.
  **Hidden in shipped builds** behind `BuildConfig.COMMONS` (on in debug, off in release, `-Pcommons=`
  overrides): the one seam is the `CommonsStore` the DI hands `MeshManager` and `InternetRelayViewModel`,
  null while dark — don't add a second gate downstream of it; flip the release default when it is introduced.
- **When touching `mesh/crypto/scope/`, `mesh/spool/`, or the spool/internet-relay plane:** READ
  `docs/SPOOL_PROTOCOL.md` (the normative public spec; its §13 vectors are pinned by
  `ScopeVectorTest`/`SpoolRecordsTest` — change them only together), then the `ScopeSync` invariants in
  `.agents/rules/mesh.md`. The client plane carries DM **and group** scopes, off by default, with the
  relay/spool-list editor shipped (`ui/relay/`); the scope-config ctl and Tor are still deferred — CHECK
  `.agents/memory/roadmap.md` before building either, and the spec's Appendix A for what runs today. The plane also carries **attachments**
  (`mesh/spool/ScopeAttachments`, spec §4.5/§6.5/§7.3/§9.5) as a separate object class kept out of the
  scope digest on purpose. A group scope derives from the shared
  **group root** (`GroupKeyPayload.gr`, `mesh/spool/GroupRootPolicy`): any member may mint it, and its
  mint / gossip / adopt / departure-re-mint rules are spec §3.2 — read that before touching them. The
  reference daemon lives in the separate `knit-spool` repo. Before touching `SpoolStatus.connected`,
  `OkHttpSpoolDialer.failureReason`, the `no_hello` / `unreachable` verdicts, `InternetGate.routeChanges`,
  or the chat placeholder's `attachmentWait` line: READ ADR 2026-09.vej5 — connected is a completed hello,
  a route that swallows the socket is `unreachable`, and the chat names the *connected* relays only.
- **When writing or running tests, or checking accessibility:** READ `.agents/context/testing.md` (unit +
  Robolectric Room + the **mesh-in-a-box** multi-node JVM scenarios in `mesh/lab/` + seeded UI / FTL +
  black-box UIAutomator + the accessibility/ATF suite that mirrors the Play pre-launch report). A change to
  what two nodes exchange — a new ctl frame, a custody rule, a roster or key path — gets a `mesh/lab/`
  scenario ending in `assertConverged`, not only a single-SUT test against mocked repos.
- **When driving the app on a device:** obey `.agents/rules/devices.md` first, then use
  `.agents/context/debug-bridge.md`.
- **Before an architectural choice:** CONSULT `.agents/memory/decisions.md` — a generated router table
  over one-file-per-decision ADRs in `.agents/memory/decisions/`; open the files whose row matches, don't
  work from the titles. For what's deliberately deferred, CHECK `.agents/memory/roadmap.md`.
- **For maintainer-only workflows a public clone doesn't include** (release testing on physical devices,
  soak/convergence trials, store/marketing capture — and more over time): a local, gitignored **`.private/`
  overlay** may be present. If `.private/AGENTS.md` exists, load it as a nested router (nearest-wins); it is
  absent from public clones.

## Capabilities

- RUN skills in `.agents/skills/` — `kotlin-patterns` (idiomatic Kotlin), `material-3` (Compose M3),
  and `dotagents-standard` (maintain this AGENTS.md router / `.agents/` layout). Skills are vendored in
  the repo (real files under `.agents/skills/`, surfaced to Claude Code via `.claude/skills/` symlinks),
  so cloners get them without any global install.
- ADD a durable decision with `python3 scripts/adr.py new "<title>" --topics a,b`, write the body it
  scaffolds, then `python3 scripts/adr.py index`. Never hand-edit `.agents/memory/decisions.md` (generated)
  and never pick an ADR number: ids are minted `YYYY-MM.suffix` so parallel worktrees can't collide, while
  `001`-`067` keep their sequence forever. Update `.agents/memory/roadmap.md` as deferred scope ships.
- If a task needs context this router doesn't point to, treat the missing routing as a bug — do the work,
  then add the routing line here.
