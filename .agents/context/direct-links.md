# Direct links between contacts — which decision governs which symbol

The symbol → ADR routing for direct Internet links: `mesh/inet/`, the `link` signal, the lane and the build flag. The
normative description is `docs/DIRECT_LINKS.md`; the mechanism's decision is ADR 2026-10.a5yj (Proposed — a spike).

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed.

## Entries

- **When touching `mesh/inet/` (`DirectLinks`, `LinkSession`, `LinkLane`, `PunchPolicy`, `LinkGather`, `StunMapped`,
  `LinkDatagrams`), `net/AndroidLinkDatagrams`, `AndroidInternetGate.ownAddresses`, `di/DirectLinkWiring`
  (`DIRECT_LINKS_WIRED`, `DIRECT_LINK_CAPABILITIES`), `Protocol.CAP_INET_LINK` or `BuildConfig.DIRECT_LINKS`:** READ ADR
  2026-10.a5yj and `docs/DIRECT_LINKS.md`. Contacts only, an opt-in, on demand, never standing; UDP with IPv6 first and no
  TURN — a punch that finds no path gives up in ten seconds and the frames keep riding the spool. A peer's candidates
  are checked before anything is sent to them (`PunchPolicy.sane`, at most eight), the reflexive address is learnt on
  the very socket that punches, and a field-log line names a candidate's kind, never its address.
- **When touching `FrameType.LINK`, `LinkSignal` / `LinkCand`, `MeshManager.sendLinkSignal`, `InboundPipeline`'s `link`
  arm or `onLinkSignal`, `mesh/crypto/LinkKeys` or the `link` half of `ScopeFrames.castEligible`:** READ ADR 2026-10.a5yj
  (and its lane-key amendment once recorded), `docs/DIRECT_LINKS.md` §2 and §5 and `docs/VOICE_CALLS.md` §2.7. The
  signal is cast-only and signed (`MeshRouter` never relays or hands one on), sealed on the DM ratchet with
  `confirmedOnly` — never under our own unanswered reset, and nothing is sealed while no spool would take the cast — and
  a failed open is never reset evidence. Lane keys come from both sides' fresh `k`, bound to the attempt id and both ids
  by role, never from the pairwise root. One lane per attempt, its counter shared by every path; a retry is a new id.
- **When touching `di/DirectLinkWiring` (`directLinkModule`, `onLinkSignal`, `MeshLinkPeers`),
  `SettingsStore.directLinksEnabled` / `KEY_DIRECT_LINKS`, `MeshManager.mayOfferLink`, `DropReason.LINK_STALE` or
  `…debug.LINK`:** READ ADR 2026-10.a5yj. The switch is off by default and per phone (`direct_links_` is
  backup-transient); off or paused, every link closes and nothing is offered or answered. `mayOfferLink` is the
  offerer's consent rule (a confirmed session, a contact written to or verified, the peer's bit, not blocked) and is
  never asked at receipt. The links' graph is resolved late from the mesh's inbound hook, and the mesh's half
  (`MeshLinkPeers`) late from the links, so neither graph needs the other built first.
