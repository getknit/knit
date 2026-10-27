# Spool / internet-relay plane — which decision governs which symbol

The symbol → ADR routing for the internet-relay (spool) plane: scopes, relays and homes, tickets, uploads, the commons
room, relay invites, and how a Nearby-room ✓✓ gets home over it. The normative spec is `docs/SPOOL_PROTOCOL.md`.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

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
- **When touching relay invites — `getknit.app/r` / `knit://r`, `mesh/spool/RelayInvite`,
  `data/relay/RelayInviteApplier`, `ui/relay/RelayInviteSheet` / `RelayInviteInbox`, the relay row's
  Share / Copy, or the Add-contact preview's relay "Add":** READ `docs/RELAY_INVITE.md` (layout, the
  bearer-link trust rules, golden vectors, the daemon contract) and ADR 2026-09.tmbq. One unsigned link
  carries the relay URL *with* its `?k=` token and the commons secret; it is applied only through the
  sheet (host named, cost stated, the master switch's disclosure folded in), by the one applier both doors
  share — `acceptSpoolConsent()` stays the only consent write (ADR 063), a relay is matched by
  `SpoolUrl.redact`, and a different room secret at the same relay joins a second room beside the first,
  never a rotation (spec C-7.4-18): only the spool's HELLO says a room is gone.
- **When touching `data/commons/`, `mesh/spool/Commons*`, `ConversationKind.COMMONS`, `ui/commons/`, or the relay row's
  Join/Leave:** READ ADR 2026-09.wx8e and `docs/SPOOL_PROTOCOL.md` §7.4. The commons is a private relay's
  group chat: members' `profile` frames pin them through the ordinary door and make them accepted contacts,
  posts are a non-custodial `commons` frame that lives on the one spool that runs the room and never touches
  the radios (`ScopeSync`'s second door, `InboundPipeline.deliverCommons`), and a post ahead of its
  author's profile is parked, never quarantined. `Conversations.isPublicRoom` is true for it on purpose.
  **Before touching a commons reaction (`CommonsPost.reaction`, `sendCommonsReaction`, `applyCommonsReaction`,
  `CommonsStore.postReaction`), a commons attachment (`ChatContent.attachmentKey` / `attachmentName`,
  `CommonsStore.attachmentRefs`, `MessageDao.commonsAttachmentsMissing`, the commons arm of
  `ScopeSync.healAttachments`, `RelayFacts.textOnlyRooms`) or the rotated-invite probe (`ScopeSync.markStale`,
  `SpoolStatus.staleCommons`, `RelayFacts.staleRooms`):** READ ADR 2026-10.xhfr. A reaction rides the room and never
  `sendReaction` (its fallback flooded a private room's message in cleartext); an attachment is keyed like a group's,
  pushed only by its author and fetched only through the scope, so no radio path — a want, a tap, a fetch row — may
  treat a `c-` row like a Nearby one; and at a spool that predates `rooms` a commons SUB declares a TTL one
  millisecond short on purpose — once per connection for a room already stale, which takes no post while it stays so.
  **Before touching how a HELLO's rooms are read — `CommonsAdverts`, `SpoolHello.rooms` / `SpoolCommonsRoom`,
  `ScopeCrypto.commonsTag` / `Scope.commonsTag`, `SpoolStatus.adverts` / `rooms`, `RelayInviteApplier.Room.beside` or
  the relay screen's `RelayRoom` rows:** READ ADR 2026-10.wqu2. One spool may run several rooms, each named in its
  HELLO by an 8-byte tag of its scope id; `CommonsAdverts` is the one reader of `rooms` and `commons`, a spool that
  lists its rooms settles a stale room with no SUB, and nothing reads one room's name or `attach` for another's. An
  invite is never a rotation (C-7.4-18): never leave a room because another room's invite arrived at its relay.
  **Hidden in shipped builds** behind `BuildConfig.COMMONS` (on in debug, off in release, `-Pcommons=`
  overrides): the one seam is the `CommonsStore` the DI hands `MeshManager`, `InternetRelayViewModel` and
  `CommonsDetailsViewModel`, null while dark — don't add a second gate downstream of it; flip the release default
  when it is introduced.
- **When touching `ScopeSync.reconcile`, `Worker.stop`, or the worker coroutine that hands a pull to `deliver`:**
  READ ADR 2026-10.f82h. A reconcile that drops a relay cancels its worker mid-delivery as a matter of course; the
  delivery survives only because `MeshRouter.handleInbound` finishes what it admitted under `NonCancellable`. Don't
  move a delivery off that door, and don't bound it with a timeout that fires before custody, commit and receipt
  (`SpoolWorkerStopLabTest`).
- **When touching `mesh/crypto/scope/`, `mesh/spool/`, or the spool/internet-relay plane:** READ
  `docs/SPOOL_PROTOCOL.md` (the normative public spec; its §13 vectors are pinned by
  `ScopeVectorTest`/`SpoolRecordsTest` — change them only together), then the `ScopeSync` invariants in
  `.agents/rules/mesh.md`. The client plane carries DM **and group** scopes, off by default, with the
  relay/spool-list editor shipped (`ui/relay/`); the scope-config ctl is stored per issuer (`ScopeConfigPolicy`,
  `ScopeConfigRepository`; its crypto half is `.agents/context/crypto.md`'s scope-config entry) and issued by
  `ScopeConfigIssuer`. **Before touching `ScopeSpools` / `ScopePlacement`, `Scope.spools` / `belongsAt`, `ScopeConfigIssuer`,
  `HomeStore` / `HomeRefresher`, `PublicSpools`, `ScopeSync`'s `urls` or `onRelaysChanged`, or a card's `sp` order:**
  READ ADR 2026-10.8kfx and spec §5.1 — each phone lives on one home relay, a scope is synced at its own relays by the
  union rule, a config never makes a phone dial a relay of the peer's choosing, and nothing is issued while the home is
  `public-1`;
  and Tor is still deferred — CHECK `.agents/memory/roadmap.md` before building either, and the spec's Appendix A for
  what runs today. The plane also carries **attachments**
  (`mesh/spool/ScopeAttachments`, spec §4.5/§6.5/§7.3/§9.5) as a separate object class kept out of the
  scope digest on purpose. A group scope derives from the shared
  **group root** (`GroupKeyPayload.gr`, `mesh/spool/GroupRootPolicy`): any member may mint it, and its
  mint / gossip / adopt / departure-re-mint rules are spec §3.2 — read that before touching them. The
  reference daemon lives in the separate `knit-spool` repo. Before touching `SpoolStatus.connected`,
  `OkHttpSpoolDialer.failureReason`, the `no_hello` / `unreachable` verdicts, `InternetGate.routeChanges`,
  or the chat placeholder's `attachmentWait` line: READ ADR 2026-09.vej5 — connected is a completed hello,
  a route that swallows the socket is `unreachable`, and the chat names the *connected* relays only.
  Before touching `RECONCILE_INTERVAL_MS`, who calls `ScopeSync.onScopeTableChanged`, `RatchetSessions.rootChanges`
  or `IntroSync.onPairsChanged`: READ ADR 2026-09.dcah — the scope table derives on its inputs' events and the
  60 s poll is only the net under the calendar; a hook that fires on an unchanged input is the old poll back.
  Before touching `SpoolPow`, `ScopeSync`'s `stampFor` / `stampDemanded`, or what a request does on `err pow`: READ
  ADR 2026-10.rn9j — a stamp goes out only after a spool asked for one (C-8-4), its first ask is retried at once
  and never parked, and the miner allocates nothing per hash (`SpoolPowTest` pins it).
  Before touching `SpoolHello.ticketGates` / `entGates`, `SpoolConnection.gates` / `uploads`, `SpoolUploads`,
  `ScopeSync`'s `uploadsOn` / `spentUploads` / `LocalPass.LOCKED`, `HeldTicket.pseudonym`, `AttachmentRelay.Locked` or
  `RelayFacts.uploadRooms`: READ ADR 2026-10.8t2y (knit-spool ADR 2026-10.h6pt) — each gate is its own bit of
  `entGates` (C-7.6-30); an attachment of ours on a connection the files gate locks is settled with no `ahave` and no
  `aput` while fetching is untouched; a `budget` spends the ticket's pseudonym per relay host in memory, never in the
  verdict journal; and the sender's "Nearby only" reads `Locked` only when no connected relay takes this phone's
  uploads. The gate reading and the marker ship in every build; getting a ticket and the readouts stay behind
  `ANYWHERE_WIRED` (ADR 2026-10.swvp).
  Before touching a ticket's `am` (`SpoolTicket.am` / `maxChunks`, `SpoolConnection.uploadLimitChunks`,
  `LocalPass.TOO_LARGE`, `ChunkPut`, `RelayFacts.uploadRooms` / `UploadRoom`), free tickets (`mesh/spool/FreeTickets`,
  `TicketRefresher.uploadWanted` / `freeDueAt`, `data/relay/FreeUploads`, `FreeUploadsNotice`, the `spool_free_` keys,
  `free_issuers` / `free_issuer_am`) or `AttachmentStore.fitted`: READ ADR 2026-10.s3ep and `docs/VOICE_CALLS.md` §2.6
  (C-7.6-32 to C-7.6-38) — an attachment over `am` is never `aput` and `size` locks nothing; a free key asks only
  while an upload waits (no 6 h margin, the hour floor kept), only for a relay in use, and never once the person turned
  free tickets off (on by default; the notice informs, s3ep's amendment); it never rides a backup
  or asks for a badge; a photo is fitted at staging, to the hint only with a free key or none.
  Before touching `DeviceProof`, `DeviceKeys` / `DeviceKeyring`, `data/crypto/AndroidDeviceKeystore`,
  `IssuerAnswer.BoundElsewhere`, `TicketRefresher.move`, the `spool_device:` keys or the relay screen's Move it here: READ
  ADR 2026-10.wk3m and `docs/VOICE_CALLS.md` "Device binding" (C-7.6-39 to C-7.6-46) — every customer-key request carries
  a proof signed afresh, a Keystore refusal sends nothing (an unsigned request to a bound account parks it behind the
  issuer's cooldown), no pair is ever made over or deleted on the Keystore's word (the accepted pair, else the oldest,
  signs), and a move is only ever the person's.
