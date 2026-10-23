---
id: "2026-10.8kfx"
slug: a-phone-lives-on-one-home-relay-and-a-conversation-names-its-relays
title: "A phone lives on one home relay and a conversation names its relays"
date: 2026-10-23
topics: [spool, wire, settings]
---

# ADR 2026-10.8kfx — A phone lives on one home relay and a conversation names its relays

Status: Accepted (2026-10-23; phases 1 to 3 of knit-spool-coordinator ADR 2026-10.txjf. The wire phase is ADR
2026-10.m4fk. `PublicSpools`, `HomeStore`, `HomeRefresher`, `AndroidPublicSpoolDirectory`, `ScopeSpools` /
`ScopePlacement`, `Scope.spools`, `ScopeConfigIssuer`, `ContactCards.mint`'s order; spec `docs/SPOOL_PROTOCOL.md`
§5.1).

**What was observed.** Every DM, pair and group scope was subscribed at every relay a phone held. `Scope.belongsAt`
bound only a commons to one relay. That is one house relay, `public-1`, holding every conversation of every user. The
coordinator plans to scale out to `public-1..N`, which needs each conversation to live somewhere in particular, and
both ends of it to know where without asking a server.

**What changed.**

- **A home.** Each phone lives on one public relay, picked from a directory by a weighted rendezvous hash of its node
  id (§5.1, vectors in `PublicSpoolsTest`).
  - An install that predates homes keeps `public-1` (`HomeStore.keepLegacyHome`).
  - A phone moves only when its home drops out of the directory or is exactly `draining`.
  - The directory read is behind `BuildConfig.SPOOL_DIRECTORY`, off in every build until the coordinator says the
    endpoint is live. It runs at most daily, and only while the plane is on, so the egress check holds.
- **A conversation names its relays.** It does so in its scope config, and `ScopeSpools` is the one union rule both
  the scope table and the issuer read:
  - **A DM:** the config's relays this phone may dial, `public-1` while there is no config or the peer never sent one
    ("declared"), the home, and the person's own relays.
  - **A group:** the same without the home, `public-1` kept while any other member is undeclared.
  - **The privacy rule:** a config URL is dialled only if it is a public relay the directory still lists, or a relay
    the person already added, so a peer cannot point this phone at a relay of the peer's choosing.
  - **Opting out:** a phone whose person parked or removed `public-1` uses no public relay at all.
  - **Workers:** `ScopeSync` runs one per relay in the person's own relays, the home, and every scope's relays, and
    the ticket refresher's "relays in use" follows that set.
- **When to issue.** A DM config names exactly two relays: the issuer's home first, then the peer's home, read from
  the first relay of the newest config the peer issued itself (`ScopeConfigRepository.newestOf`), else `public-1`. It
  is issued in two cases:
  - when the standing config does not name this phone's home;
  - when it names a gone or draining relay that is neither home (`dmNamesStale`, an old home after a move onto the
    peer's).

  A group config names one relay:
  - the creator issues its home when it creates the group off `public-1`;
  - any member moves it to its own home after a random wait of up to 3 minutes once that relay drains, skipped if a
    newer config landed;
  - the standing config's issuer re-sends it when a member rejoins.

  `ScopeConfigIssuer` sweeps on every input change and every 30 minutes, never on the inbound path.
- **Nothing is lost between the row and the flood.** Every issue is written to `HomeStore.owed` before
  `sendScopeConfig` reserves its row and cleared after the flood, and anything owed is sent again at the next version.
  That closes ADR 2026-10.m4fk's open window. The same set carries a re-send after a DM root change (a config sealed in
  the old era may never open). A send that seals nothing waits 10 minutes or the next root change, because its own
  reservation and release are a change of the winners and would wake the next sweep at once.

**The alternatives a reader reaches for first.**

- **A DM config that swaps the old home for the new and keeps the rest.** This was the first draft. The legacy
  `public-1` entry a first issue names for an old peer then stays forever, and a move onto a relay the config already
  named leaves the old home in it while it drains. Naming the two homes, each read from its owner's own config,
  prunes both and never ping-pongs: each side writes the same set from the same rows.
- **Re-issuing whenever the config differs from the set this phone would write.** That ping-pongs when two
  directories disagree: one phone sees the peer's home gone, falls back to `public-1`, and the peer answers by naming
  its home again.
- **A member join re-sent by every member who saw it.** That is one ctl per member per join. A group's roster is fixed
  by its id (`Conversations.groupIdFor`), so the only join is a rejoin, and only the standing config's issuer re-sends.

**What it costs and does not cover.**

- **Nothing moves today.** Every home is `public-1` and the directory read is off, so a phone issues nothing and
  dials exactly what it did. `ScopeSpoolsTest.aPhoneHomedOnPublicOneIssuesNothing…`, `ScopeConfigIssuerTest`'s first
  case and the unchanged lab suite hold that.
- **"Declared" is per peer, not per conversation.** A member who rejoins a group while the standing config's issuer is
  not running counts as declared and holds no group config. It may sit on `public-1` while the others have left it,
  until the next config. The issuer's member baseline is in memory, so a group created while the mesh was down owes
  nothing and stays on `public-1`, which still works and only fails to offload.
- **The seeded `public-1` stays in the relay list.** It is what marks "this phone uses the public relays", and the
  base dials the home in its place. Its retirement comes with the relay-list rework.
- **Still waiting:**
  - a pair scope reading a card's `sp[0]`: a card leads with the home, but a pair scope meets at `public-1` and both
    homes;
  - bounds read from a config;
  - mailbox connections and casts on the callee's home.

  All wait until a `public-2` is in sight.

Regressions: `PublicSpoolsTest`, `HomeStoreTest`, `HomeRefresherTest`, `ScopeSpoolsTest`, `ScopeConfigIssuerTest`,
`ScopeSyncTest`'s "a scope is subscribed at its own relays alone", `ContactCardsTest.theHomeLeadsTheRelays…`,
`ContactImporterTest.aPublicRelayOnACard…`, and `HomeSpoolsLabTest` (a DM across two homes leaves `public-1`; a home
that drains moves and the conversation leaves it).
