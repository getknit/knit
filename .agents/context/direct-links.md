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
  TURN — a punch that finds no path gives up in ten seconds and the frames keep riding the spool. No standing keepalive
  (the a5yj 2026-10-27 amendment): `LinkSession.IDLE_MS` closes an idle link, and only a held one
  (`DirectLinks.hold`, a live session) sends `HOLD_KEEPALIVE_MS` keepalives — don't add a keepalive back without new
  mapping-life measurements. A contact is answered as often as it uses the links; only answers that came to nothing
  (no path, or a link that carried no data) are budgeted, `DirectLinks.EMPTY_ANSWERS_PER_HOUR` per contact — never a
  flat answer cap, which would starve a busy chat now that idle links close — and a blocked contact is never answered. A peer's candidates
  are checked before anything is sent to them (`PunchPolicy.sane`, at most eight), the reflexive address is learnt on
  the very socket that punches, and a field-log line names a candidate's kind, never its address.
- **When touching `LinkArq`, `LinkStream`, `LinkSession.attach` or `DirectLinks.measureBulk` / `onStream`:** READ ADR
  2026-10.a5yj and `docs/DIRECT_LINKS.md` §6. The stream is our own ARQ over the link's DATA datagrams, behind the
  `LinkStream` seam so a QUIC stream could replace it without touching `FramedLink` (which runs over it unchanged) or
  the session; the a5yj stream amendment chose ours on the iOS port's cost and names what would reopen it. A
  retransmission is a fresh lane seal, never a datagram sent twice. A segment is the link's data for the idle close, so
  a stream that moves nothing lets its link idle out. While no transport takes a link's stream (`onStream` unset),
  `DirectLinks` drains it.
- **When touching `DirectLinkTransport`, `LinkPairFilter` / `LinkScope`, `TransportKind.DirectLink`,
  `MeshTransport.viaInternet`, `CompositeMeshTransport.shortRangeNeighbors` / `shortRangeLinked`,
  `DeliveryPlane.DirectLink`, `LinkSession.noteUse` / `dataIsUse`, `DirectLinks.noteUse` or `di/directLinkPlane`:**
  READ ADR 2026-10.a5yj and `docs/DIRECT_LINKS.md` §7. A link is the composite's last child and not a radio: never
  nearby, met, a radio path (`MeshManager.talkRadioPath`) or Your mesh credit, and never the mesh's health. It carries
  the pair's own traffic only, filtered at the link in both directions — the room, a stranger's DM this phone carries
  or a group the two do not share never cross — and a file only when it is an attachment addressed to the peer or our
  own avatar. A blob is the pair's when ANY conversation naming it is addressed to both ends
  (`LinkScope.addresseeSetsOf`, `MeshManager.blobAddressees`): a forwarded picture keeps its hash (ADR 2026-10.mptm),
  so never read the first row. A refusal by shared groups read over `DirectLinkTransport.GROUPS_REREAD_MS` ago is
  decided again on a fresh read, so a group made since never waits a re-offer round for its first post. Only frames and
  files are use; digests and handshakes keep nothing open.
- **When touching `LinkDemand`, `LinkDemandPolicy`, `MeshLinkPending`, `DirectLinks.lastUpAt`,
  `MessageDao.unackedDmsBetween` / `ownGroupPostsBetween` or `MessageReceiptRepository.ackersOf`:** READ ADR
  2026-10.a5yj's "spool first" amendment and `docs/DIRECT_LINKS.md` L-7-7. The spool goes first; a link is offered only
  for a message of ours a contact has not ticked in 20 s (last 15 min), no radio links them and no link has been up since
  it was sent — never a hold on the spool's copy (link first was weighed and set aside). Read only while the link plane
  runs; low-power mode offers and answers none.
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
