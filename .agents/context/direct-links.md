# Direct links between contacts — which decision governs which symbol

The symbol → ADR routing for direct Internet links: `mesh/inet/`, the `link` signal, the lane and the build flag. The
normative description is `docs/DIRECT_LINKS.md`; the mechanism's decision is ADR 2026-10.a5yj (Accepted 2026-10-28; off in release until Phase 3 flips it).

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
- **When touching `LinkDemand`, `LinkDemandPolicy`, `MeshLinkPending`, `LinkHolderProof`, `DirectLinks.lastUpAt`,
  `MeshManager.attachmentStuck` / `proveStuckOverLinks`, `ScopeSync.refusesUpload`, `MessageDao.unackedDmsBetween` /
  `ownDmAttachmentsBetween` /
  `ownGroupPostsBetween` or `MessageReceiptRepository.ackersOf`:** READ ADR 2026-10.a5yj's "spool first" amendment and
  `docs/DIRECT_LINKS.md` L-7-7. The spool goes first; a link is offered only for a message of ours a contact has not
  ticked in 20 s (last 15 min), or at once for a DM attachment no spool carrying the pair will take and not proved held,
  while no radio links them and no link has been up since it was sent — never a hold on the spool's copy (link first was
  weighed and set aside). Read only while the link plane
  runs; low-power mode offers and answers none. The sender proves a stuck photo over the link: each demand round asks
  every link-only contact (`MeshManager.proveStuckOverLinks`), through `HolderAskBatch` and `BlobExchange.confirmHeld`,
  about our DM attachments to them that are still `attachmentStuck`, read over the custody
  TTL rather than the 15-min window — never a memo of what was asked, never a count (FETCHING is asked again), never a
  group's picture, and never an attachment that is not stuck, so a sender's `DirectLink` holder row means the link
  carried it. The spool plane's next look (`reopenUploads`, the hourly recheck) then settles it PROVED (ADR 2026-10.vy27).
- **When touching `FrameType.LINK`, `LinkSignal` / `LinkCand`, `MeshManager.sendLinkSignal` / `canSignalLink`,
  `ScopeSync.sig` / `canSig`, `LinkPeers.canSignal`, `LinkOutcome.NO_SIG`, `InboundPipeline`'s `link` arm or
  `onLinkSignal`, `mesh/crypto/LinkKeys` or the `link` half of `ScopeFrames.sigEligible`:** READ ADR 2026-10.a5yj (its
  lane-key and 2026-10-28 signal-lane amendments), `docs/DIRECT_LINKS.md` §2 and §5 and spec §7.8 (C-7.8-13,
  C-7.8-17). The signal rides spool sigs alone and is signed (`MeshRouter` never relays or hands one on): never a
  cast — the cast set is the walkie's `talk` — so no ticket decides who links, and a spool without `sig` carries no
  offer (NO_SIG). Sealed on the DM ratchet with `confirmedOnly` — never under our own unanswered reset, and nothing is
  sealed while no sig lane would take it — and a failed open is never reset evidence. Lane keys come from both sides' fresh `k`, bound to the attempt id and both ids
  by role, never from the pairwise root. One lane per attempt, its counter shared by every path; a retry is a new id.
- **When touching `di/DirectLinkWiring` (`directLinkModule`, `onLinkSignal`, `MeshLinkPeers`),
  `SettingsStore.directLinksEnabled` / `KEY_DIRECT_LINKS`, `InternetRelayViewModel.setDirectLinks` /
  `InternetRelayUiState.directLinks`, `MeshManager.mayOfferLink`, `DropReason.LINK_STALE` or `…debug.LINK`:** READ ADR
  2026-10.a5yj. The switch is off by default and per phone (`direct_links_` is backup-transient); off or paused, every
  link closes and nothing is offered or answered. Its row is on the relays screen, drawn only while the relays are on
  (a link's offer rides them) and only in a build with links; it writes straight through, with no sheet, because its
  line is the disclosure (a contact Knit tries to connect with learns the phone's IP address). The copy never says
  every phone can start a link, nor names the cast ticket that decides it today. `mayOfferLink` is the
  offerer's consent rule (a confirmed session, a contact written to or verified, the peer's bit, not blocked) and is
  never asked at receipt. The links' graph is resolved late from the mesh's inbound hook, and the mesh's half
  (`MeshLinkPeers`) late from the links, so neither graph needs the other built first.
- **When touching `DirectLinks.raise` / `HOLD_TTL_MS` / `offeredLately`, `LinkSession.holdFor`, `DirectLinkTransport.holdLink`,
  `di/DirectLinkWiring`'s `walkieHold`, `TalkLink.DIRECT_LINK`, `TalkPath.DIRECT_LINK` or the link half of
  `MeshManager.castsTalk`:** READ ADR 2026-10.a5yj's walkie amendment, `docs/DIRECT_LINKS.md` L-7-8 and
  `docs/VOICE_CALLS.md` C-3.11-6/8/10. A walkie call raises and holds a link only while the radios have no path to the
  peer (`MeshManager.talkRadioPath`) — never in place of a radio, and with no spool-first wait — and the hold is a
  deadline each 10 s ask renews, never a flag a hang-up must clear. Both ends offer at the ring, so crossing is the
  rule: the lower id drops a higher id's offer within `ANSWER_WAIT_MS` of the link it offered coming up
  (`DirectLinks.offeredLately`, L-2-5) — answering it would close a link the peer holds, without a BYE — and an offer
  superseded while it gathered never leaves. A chunk crosses a link alone and uncast; the
  listener counts it as the speaker's own way (`viaRelay` false), never a relay's, or every burst over a link would
  turn the cast on. It rides the one ordered stream: a lane datagram for `talk` is the named fix if a cellular trial
  shows bursts stalling, never a second stream.
- **When touching `shownPlane`, `planeGlyph` / `deliveryLabel`'s `DirectLink` arms, `BlobRepository.observeLinkHolders`,
  `AttachmentHolderDao.observeOnPlane` or the `chat_status_*_link` strings:** READ ADR 2026-10.a5yj's 2026-10-28
  amendment. A link shows its own face (two arrows, "over a direct link"), never the Internet's: a link on one network
  never leaves it. A file shows the link when the bytes' proof (ADR 2026-10.vy27) came over one — the recipient's own,
  or on our send the DM peer's — whatever plane its message crossed; a group send of ours never does. A sender's link
  row has two writers, `LinkHolderProof` (a photo no spool takes) and the push-half deferral's proof round (a photo held
  back while the link is up), so it means a link confirmed the hold, not always that it carried the bytes.
