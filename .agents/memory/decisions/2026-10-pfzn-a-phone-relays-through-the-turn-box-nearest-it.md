---
id: "2026-10.pfzn"
slug: a-phone-relays-through-the-turn-box-nearest-it
title: "a phone relays through the TURN box nearest it"
date: 2026-10-25
topics: [turn, calls, spool, anywhere]
---

# ADR 2026-10.pfzn — a phone relays through the TURN box nearest it

Status: Accepted (2026-10-25). The client half of knit-spool-coordinator ADR 2026-10.w7hc, built against the
nearest-turn contract (revision 2) and specified in `docs/SPOOL_PROTOCOL.md` §7.6 (S-7.6-48 to C-7.6-54). The spool
half is knit-spool ADR 2026-10.u5f4. Calls ship in release (ADR 2026-10.swvp), so this runs in every build with live
calls.

**What was observed.** A TURN box holds what its quota allows, and each relaying phone takes 2 to 4 allocations
of it. A second box adds capacity only if each phone allocates on one box. libwebrtc allocates on every TURN URL it is
handed, so listing both boxes in one credential (which the spool already allowed) would mirror the load instead of
splitting it. And where a spool is says nothing about where the phone is.

**What changed.**

- **The spool names its servers.** HELLO `turnHosts` lists each server's entry, the `host:port` of its UDP URL.
  `SpoolConnection.turnHosts` keeps at most four entries a probe could dial, without repeats. `turnreq.host` names
  one, and only to a spool that listed it, so a `turnreq` to an older spool is the same bytes as before, pinned in
  `SpoolConnectionTest`. A credential's server is the entry of its own UDP URL (`TurnHosts`). That rule mirrors
  knit-spool's `TurnUris` exactly: the port as written (`03478` stays `03478`), a case-sensitive scheme, and no
  entry where no host can be read. A credential then matches the entry its spool listed byte for byte.
- **The phone measures, in the background.** `TurnMeasure` measures the union of `turnHosts` over the connected
  spools that would issue this phone TURN (`SpoolStatus.turnForUs`).
  - **How:** three 20-byte STUN Binding requests per entry, 200 ms apart, each waited on for 1 s, over UDP on the
    gate's validated default network (`UdpTurnProbe`), only to an address `PublicAddressPolicy` calls public.
  - **When:** on a new default network (`InternetGate.routeChanges`), and when a hello brings an entry not yet
    measured (`ScopeSync`'s `onTurnHosts`, fired as a session's hello and ticket settle and as it ends). Never while
    a call is being set up.
  - **Never:** no timer, and nothing at all with no target, so the egress check's fresh install sends nothing.
  - **Kept:** `TurnRanking` holds one score per entry for the current network. The scores drop on a network change,
    while a refusal keeps its 5 minutes.
  - **Ranked by:** ascending score; within 10 ms of the best, the spool's order; silent or unmeasured entries next;
    refused ones last.
- **One server per call.** `CallManager.oneServer` hands libwebrtc the one credential `TurnRanking.choose` picks
  whenever any credential came from a spool that names its servers. A reused live credential goes through the same
  choice, so a reuse takes the server the ranking picks now. Spools that name none keep C-7.6-12's up to two. Each
  `TurnServer` carries its spool's list and its asking order, which `MeshManager` lost before.
- **The fallback happens only before the description leaves.** The alternatives a reader would reach for first are
  `setConfiguration` with the next server, and an ICE restart before connect. libwebrtc m150 (branch-heads/7871; the
  webrtc-sdk fork's `m150_release` is identical on these paths) rules out both:
  - `setConfiguration` with new servers only sets needs-ICE-restart. A live allocator session read its servers once,
    in `GetPortConfigurations`, and every later `DoAllocate` reuses them.
  - An answerer cannot restart, because `GetOptionsForAnswer` ignores the local restart flag.
  - C-3.8-2 and C-3.8-4 allow no restart before connect, and every build since 2.10.0 would drop one.

  So on a 486 or 508 (`MediaEvent.TurnRefused`, from `onIceCandidateError`), the server is marked whenever the
  refusal comes. If it comes before this side's OFFER or ANSWER is sent, and the description holds no relay
  candidate, `CallManager.afterRefusal` opens a fresh session on the next server: a credential in hand, else
  `TurnSource.turnServerFor`, a `turnreq` naming it. It closes the refused session, drops that session's events, and
  sends the fresh session's description as the ordinary gen-0 one. This happens once per call, on each side alone.
  The refusal lands a few hundred ms into gathering (the relay phase starts 50 ms in, then a 401 and the 486), well
  inside C-3.6-1's hold.

**What it costs.**

- A refused side's description leaves about one gather window late: about 1.5 s, plus a `turnreq` when no
  credential is in hand.
- A refusal after the description leaves only marks the server. The call then relies on the peer's relay or a direct
  path.
- A phone that would ask for TURN shows each listed box its address once per network change, calling or not.

**Where it is logged.** Logcat (`KnitCall`, `ScopeSync`) and the field log name a server by its slot in its spool's
list, never by its host:

- `turn: slot N of M, score X ms` or `unmeasured`, as a call chooses its server;
- `call <id>: turn slot N of M refused (486) before our offer; gathering on slot K of M`, on a fallback;
- `call_turn`, one field-log line per call;
- `webrtc: candidate error` names only the kind of server now.

**The trap.** Server 1 is not "the nearest". It is the box an old client gets, and what a tie or an empty ranking
gives. The debug bridge's `turnpin` puts a slot first whatever its score or refusal, so the lab trial can make a call
try the refusing box first. It lives in memory and in the debug build only.

**Kept true by** `TurnHostsTest`, `StunBindingTest`, `TurnRankingTest`, `TurnMeasureTest`, `SpoolTurnRecordsTest`
(`helloSpoolTurnHosts` and `turnreqHost`, copied from knit-spool `ee3497bd`), `SpoolConnectionTest`, `ScopeSyncTest`,
`CallManagerTest`'s one-server and refusal cases, and `FieldEventSchemaTest`. The trial owed is two coturn boxes and a
Pixel on Wi-Fi and on LTE, with the fallback forced on a box set to refuse.
