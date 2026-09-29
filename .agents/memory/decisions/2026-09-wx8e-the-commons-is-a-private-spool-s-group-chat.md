---
id: "2026-09.wx8e"
slug: the-commons-is-a-private-spool-s-group-chat
title: "The commons is a private spool's group chat"
date: 2026-09-12
topics: [spool, commons]
---

# ADR 2026-09.wx8e — The commons is a private spool's group chat

Status: Accepted (2026-09-12) — branch `feat/spool-commons`; spec `docs/SPOOL_PROTOCOL.md` §7.4.
**Hidden in shipped builds** (2026-09-12): `BuildConfig.COMMONS` is true in debug and false in release
(`-Pcommons=true|false` overrides either way, defaults in source for F-Droid), the posture the two planes
began under (ADR 064, ADR 2026-09.6gtm). The gate is the `CommonsStore` the DI hands `MeshManager` and
`InternetRelayViewModel` — null while dark, so no room is derived into the scope table, subscribed, posted
to or listed, the relay row draws no Join / Leave line, and `NotificationChannels` registers no room
channel; the debug bridge refuses a join. Not a code strip. Flip the release default when the feature is
introduced; see `.agents/memory/roadmap.md`. *Amended by ADR 2026-09.adgd (2026-09-29): a member is accepted
on their first sighting in a room only, so a user's Remove contact survives the restart re-pull; the new
`SettingsStore.unaccept` is that removal's alone.*

**What was observed.** `knit-spool` 0.2.0 shipped a **commons** — one operator-declared shared scope per
spool, joined by an invite `knit-commons:v1:<32 B>` whose hash is the scope id and whose secret the spool
never sees — and deferred the content-key derivation and the whole client half to a spec section (§7.4)
that did not exist. The first client design read it as a public room of strangers: self-certifying posts
carrying their author's key bundle and a self-asserted name, no peer rows, nobody a contact. That was the
wrong product. A spool with a commons is a **private, focused instance** — a household, a team, one
organisation — and its commons is that instance's group chat with every peer on the relay: the operator
mints the invite for people they know, so the invite *is* the trust boundary.

**What changed.** The commons is a room whose authors are pinned peers like a group's, without a group's
roster: whoever holds the invite is in.

- **Profiles pin members.** A member's cleartext `profile` frame rides the commons scope (the §4.4 argument:
  self-certifying, so a scope grants a sender nothing a flood does not) through the ordinary door —
  `canCarry` → `MeshRouter.handleInbound` → `handleProfile` — so it pins, stores the prekey, pulls the
  avatar, and custodies and relays exactly as a profile bridged from any other scope. The own profile is
  the push set's one profile (`ScopeFrames.pushableToCommons`: our frames only), kept live by the existing
  12 h republish, and re-stamped at once when a busy room count-evicts it (`onOwnProfileTombstoned`) —
  the old bytes are tombstoned for the room's TTL and could never be re-pushed.
- **Posts are a new non-custodial type**, `commons`, wrapping an ordinary `ChatContent` behind the room's
  32-byte scope id (`CommonsPost`; mentions and reply quotes come free, `enc` stays null — every member
  holds the room key, so there is nobody to seal for). They live on the spool and enter through their own
  door (`InboundPipeline.deliverCommonsPost` → `deliverChat(plane = Internet, ack = false)`), never
  `originate`d, custodied or relayed. The alternative — bridging posts onto the radios like every other scope —
  is the `meshpost` mistake (ADR 2026-09.26q3): a custodial frame folds into a content digest every node must
  compute alike, and a room only some nodes are in cannot be one. The type is additive: an older build meets a
  stray copy on a link as an unknown type, relays it and delivers nothing.
- **Authentication is the pinned key**, through the same `canCarry` every frame meets. That gate folds "no key
  yet" and "bad signature" into one `false`, and a backlog listing is unordered, so a post pulled ahead of
  its author's profile is **parked, not quarantined** (`hasKey` seam; one retry at the end of the round, then
  `MAX_PARK_ROUNDS = 8` heal rounds before it is written off).
- **Members become contacts.** Every frame pulled from a room names its author a member
  (`commons_members`) and `SettingsStore.accept`s them — the same act as importing their contact card
  minus the card: they sit in Contacts, their DMs skip the requests inbox, their peer row is protected from
  the sweep. DM bootstrap over the spool reuses `IntroSync` and the §3.5 pair scope: a background sweep
  (`wantIfRoom`, never evicting) connects members eight at a time under `MAX_PENDING`, and a member's own
  first DM to another registers the intro with urgency (`wantCommonsIntro`).
- **One relay.** A commons is the first scope with spool affinity (`Scope.spoolUrl`): subscribed at the
  relay that runs it and only once that relay's HELLO advertises `commons` — a SUB anywhere else would read
  as an unknown scope and meet the PoW gate. No PoW is ever mined for it; the spool's pinned bounds
  (echoed in the `digest`) replace what the scope table guessed; a tombstoned own id is dropped from the
  local fold so the room converges. Joined and left from the relay's own row in the Internet-relay editor.
- **Keys.** `scopeId = SHA-256("knit/spool/v1/commons" ‖ secret)` exactly as the daemon; seal keys
  `HKDF(secret, "knit/spool/v1/commons-key", 64)`, the first derivation under the transport-plane prefix
  because the daemon owns the id half. Vectors pinned in `ScopeVectorTest` and §13.

**What it costs.** DB v13 (`commons`, `commons_outbox` — the exact signed bytes the deterministic seal
re-seals each round, since custody is off-limits — and `commons_members`); one mesh frame type; a fourth
`ConversationKind`; auto-acceptance of every member (there is no `unaccept`, so leaving a room keeps its
people as contacts — on purpose); a restart re-pull of the whole room (≤ `maxFrames` opens, silent on the
exists-gate). Delivery ticks are the **group's**: a post is acknowledged with the delay-tolerant tick that
batches and escalates into one sealed receipt DM to the author, riding that pair's DM scope over the relay —
never a receipt frame in the room, where N per post would evict a 500-frame room's posts. So ✓✓ arrives from
a member once a session with them exists (the intro sweep's job), and a chatty room shares each pair's DM
scope with their receipts, the trade groups already make.

**What it does not cover.** Attachments (`hello.commons.attach` is read and refused for now — the
`ScopeAttachments` commons arm is the follow-on), reactions, typing, a members list, deep-linked invites,
carrying posts over the local radios when the Internet is down, and detecting a rotated invite (the id is
never advertised, so a stale room simply goes silent; `err pow` is the only tell on a mining spool).

**Device-verified 2026-09-12** (Pixel 9 Pro XL + Moto G, a `knit-spool` 0.2.0 on the LAN with
`SPOOL_COMMONS_ID`, PoW 8 bits): the client's derived scope id matched the daemon's; both phones subscribed
the room at the LAN relay only, with no PoW stamp; each pinned the other from the room and recorded it a
member; a post crossed in under 10 s each way under the author's real name and avatar, with a reply quote,
in neither custody store; a force-stop re-pulled the room with no duplicate rows and the scope converged
(`17 = 17`, `invalid 0`); leave emptied the thread and rejoin restored it. The daemon's 27 conformance checks
(three commons) pass. Two things the trial found: Room's `@Upsert` logs the unique-key exception it recovers
from on every member sighting (now `REPLACE`), and a room left and rejoined on the same connection kept its
old accounted set and never re-pulled — a departed scope now drops every per-scope set and the connection's
SUB record (`Worker.forgetScopesNotIn`), so the rejoin re-subscribes and pulls the room afresh.

**The trap.** `ScopeSync.Worker.accept`'s existing path is the wrong door for a post: `deliver` is
`MeshRouter.handleInbound`, which relays. And `Conversations.isPublicRoom` now says yes for a `c-` id —
which is what routes room moderation, volume retention and never-a-request — so anything that used it to
mean "Nearby" must not. Kept true by `CommonsSyncTest` (the convergence harness — nothing crosses the
mesh, so there is no `mesh/lab/` scenario), `ScopeFramesTest`'s commons arm, `FrameTypeTest`'s
non-custodial pin, and `MessageRetentionTest`.
