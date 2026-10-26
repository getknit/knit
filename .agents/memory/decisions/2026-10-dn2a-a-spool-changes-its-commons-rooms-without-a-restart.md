---
id: "2026-10.dn2a"
slug: a-spool-changes-its-commons-rooms-without-a-restart
title: "A spool changes its commons rooms without a restart, closing only a changed room's members"
date: 2026-10-25
topics: [spool, commons]
---

# ADR 2026-10.dn2a — A spool changes its commons rooms without a restart, closing only a changed room's members

Status: Accepted (2026-10-25) — spec only; `docs/SPOOL_PROTOCOL.md` §7.4 (S-7.4-3 amended, S-7.4-21 new), the
§7.1 close-code line, the §7.4 intro and rotation notes, Appendices A and B. The spool half is knit-spool
ADR 2026-10.hs75. The client needs nothing new.

**What was observed.** Removing a member from a commons room means rotating its invite (§7.4, "What it is not"),
and knit-spool read its rooms at boot (its ADR 2026-10.5cwn), so a rotation was a restart. A restart reconnects
every member of every room and every private conversation on the spool, to change one room. The spec assumed the
same: S-7.4-3 said every room "exists from the spool's boot", the §7.4 text said the id is computed "at boot", and
the rotation note named "a restart without the room's setting" as the operator slip a later connection recovers
from. Nothing in the spec said what a live connection sees when a spool's rooms change under it, because nothing
could change them.

**What changed.**

- **S-7.4-3** now says a room exists before any HELLO advertises it. That is what the rule was for: no creation
  gates for a room's members, and no shedding, from the first HELLO that lists it. Boot was only when that
  happened.
- **S-7.4-21** lets a spool start running a room, stop running one, or change one's entry while it runs. A HELLO is
  sent once per connection (B-7.1-5) and never revised, so a spool that stops running a room, or changes the bounds
  or `attach` it advertised for one, closes `1001` every connection subscribed to that room's scope, and should
  close no other for that change. Their next HELLO says the room is gone, or what it now is. A room it stops
  running is an ordinary scope from then on. A name change alone need not close anyone.
- **The §7.1 close-code line** names `1001`, which spools already sent when going away, and says it is not a
  refusal.

The alternatives:

- **Close every connection.** A restart by another name: the cost this exists to remove.
- **Close nobody.** A rotated room's members, the removed one among them, keep talking in the old scope until
  each reconnects on its own: there is no unsubscribe record, and the scope lives on as an ordinary one. Rotating
  to remove someone would then not remove them for as long as their connection lasts.
- **A new record or close code telling a client its rooms changed.** Every client would need it, and nothing
  needs it: the reference client already reconnects on any close and reads its rooms from each HELLO
  (`CommonsAdverts`, built per HELLO by `SpoolConnection`), so a `1001` already does the job. Re-sending HELLO
  mid-connection is out, since B-7.1-7 makes a later HELLO malformed.

A room's members are exactly the connections subscribed to its scope, because nobody else holds the id, so the
close reaches them and nobody else. A bounds or `attach` change closes them too: those are what a member read from
its HELLO and keeps to (C-7.4-17, C-7.4-14), so a connection told the old values should not keep them.

**What it costs.** A room added while connections are up is invisible to each of them until it reconnects. A
member handed the new room's invite meanwhile finds the tag absent from that connection's HELLO, which the
reference client reads as stale for that connection only (`ScopeSync.staleRooms`), and opens on the next. A
rotation still reconnects all of a room's members at once, within about 1–1.75 s of the close under the
reference client's own backoff (1 s plus up to 750 ms of jitter). That is the room's population, not the spool's, and the spec does not ask a
spool to spread it. No record, derivation or §13 vector changed, so no vector test moves. The knit-spool tests
named in its ADR 2026-10.hs75 are what keep S-7.4-21 true. On this side the client's reconnect-on-any-close is
the one assumption, and it is in `OkHttpSpoolDialer`, not in a test that names this rule.
