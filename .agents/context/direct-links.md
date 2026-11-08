# Direct links between contacts — which decision governs which symbol

The symbol → ADR routing for direct Internet links: `mesh/inet/`, the `link` signal, the lane and the build flag. The
normative description is `docs/DIRECT_LINKS.md`; the mechanism's decision is ADR 2026-10.a5yj (Accepted 2026-10-28; on in release since 2.11.0, its 2026-11-08 amendment, and off in the slim APK).

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed.

## Entries

- **When touching `mesh/inet/` (`DirectLinks`, `LinkSession`, `LinkLane`, `PunchPolicy`, `LinkGather`, `StunMapped`,
  `LinkDatagrams`), `net/AndroidLinkDatagrams`, `AndroidInternetGate.ownAddresses`, `di/DirectLinkWiring`
  (`DIRECT_LINKS_WIRED`, `DIRECT_LINK_CAPABILITIES`), `Protocol.CAP_INET_LINK` or `BuildConfig.DIRECT_LINKS`:** READ ADR
  2026-10.a5yj and `docs/DIRECT_LINKS.md`. Contacts only, an opt-in, on demand, never standing; UDP with IPv6 first and no
  TURN — a punch that finds no path gives up in ten seconds and the frames keep riding the spool. No standing keepalive
  (the a5yj 2026-10-27 amendment, and its idle-wait amendment): `LinkSession.IDLE_MS` (180 s) closes an idle link,
  which sends `HOLD_KEEPALIVE_MS` keepalives only within that wait (its 3 min outlast the 2-min path life measured), as a
  held one (`DirectLinks.hold`, a live session) does for as long as it is held — don't keep a link past its idle wait,
  or shorten the keepalive, without new mapping-life measurements. A dead path is a question unanswered
  (`LinkSession.SILENCE_MS` from the first DATA sent after the last datagram heard), never mere quiet, so the first
  frame after a quiet spell waits out its own answer. A contact is answered as often as it uses the links; only answers that came to nothing
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
  `DirectLinks` drains it. **Before touching `LinkArq.QUEUE_TARGET_MS` / `queueBound` / `sendLimit` / `roomToWrite` /
  the delivery-rate sampling, or `DirectLinkTransport.LINK_CHUNK_BYTES`:** READ a5yj's 2026-10-30 amendment. What is in
  flight and queued to send is held to about a second of the measured delivery rate, so a frame written behind a file
  never waits for the whole window (a slow cellular uplink's deep buffer held texts back for minutes); the smallest round
  trip is the stream's life's, so the bound cannot creep on its own queue. Don't swap in `FramedLink`'s feed window: it
  is Bluetooth-sized (64 KB/s, 64 KB) and would cap a link at a seventh of its Wi-Fi rate.
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
  files are use; digests and handshakes keep nothing open, and nor does a frame handed back to the phone that wrote it
  (#235, logged `(not use)`). A frame crosses a link once, either way: the router's flood and the DM fast path both
  hand a DM to its addressee's link, so the link keeps the Bluetooth plane's `LinkCrossings` memo, forgotten with the
  link (2026-10-29, every DM had crossed twice).
- **When touching `LinkDemandPolicy.evict` / `EVICT_IDLE_MS`, `DirectLinks.idleSince` / `close(reason)`,
  `LinkSession.idleSince`, `LinkSession.EndReason.YIELDED`, `LinkPeers.queuedAhead`, `ScopeSync.sigQueuedAhead`, the
  `link_try` `sent` / `queued` fields or `ScopeSync.sig`'s log line:** READ ADR 2026-10.a5yj's idle-wait amendment. A
  link gives up its slot only when every slot is taken, a contact waits, and it has been idle a minute or more — never
  a held one or one moving a file. Every offer of ours records where its seconds went and what it queued behind; one the
  spools refused after it gathered is a NO_SIG line with its `queued`, never silence.
- **When touching `LinkDemand`, `LinkDemandPolicy`, `MeshLinkPending`, `LinkHolderProof`, `DirectLinks.lastUpAt`,
  `MeshManager.attachmentStuck` / `proveStuckOverLinks`, `ScopeSync.refusesUpload`, `MessageDao.unackedDmsBetween` /
  `ownDmAttachmentsBetween` /
  `ownGroupPostsBetween` or `MessageReceiptRepository.ackersOf`:** READ ADR 2026-10.a5yj's "spool first" amendment and
  `docs/DIRECT_LINKS.md` L-7-7. The spool goes first; a link is offered only for a message of ours a contact has not
  ticked in 20 s (last 15 min), or at once for a DM attachment no spool carrying the pair will take and not proved held,
  while no radio links them (L-7-11's free link for a held want aside) and no link has been up since it was sent — never
  a hold on the spool's copy (link first was weighed and set aside). Read only while the link plane
  runs; low-power mode offers and answers none. The sender proves a stuck photo over the link: each demand round asks
  every link-only contact (`MeshManager.proveStuckOverLinks`), through `HolderAskBatch` and `BlobExchange.confirmHeld`,
  about our DM attachments to them that are still `attachmentStuck`, read over the custody
  TTL rather than the 15-min window — never a memo of what was asked, never a count (FETCHING is asked again), never a
  group's picture, and never an attachment that is not stuck, so a sender's `DirectLink` holder row means the link
  carried it. The spool plane's next look (`reopenUploads`, the hourly recheck) then settles it PROVED (ADR 2026-10.vy27).
- **When touching `DirectLinks.onRouteChanged` / `lastUpAt` / `lostMidFile`, `LinkPort.onDefaultNetwork`,
  `LinkSession.endedBusy` / `EndReason.ROUTE`, `InternetGate.routeChanges` as the links collect it, or the lab's
  `FakeLinkNet.Phone.move` / `darkAfter` and `LabNode.switchNetwork` / `darkenNetworkAfter`:** READ ADR 2026-10.a5yj's
  network-change amendment and `docs/DIRECT_LINKS.md` L-4-2, L-7-7. A link's socket stays on the network it was bound
  to, so a new default network closes every link whose port is off it (`ROUTE`) — never every link, which a network
  blinking out of validation and back would cost. A link that ended `SILENT` or `ROUTE` with a file crossing it is not
  counted as up, so the demand offers for the file again (at once after a `ROUTE`; after a `SILENT`, on the backoff
  counted over such losses in a row) — never on a count that a clean end or a new network does not clear. The sender's
  "crossing" ends when its last chunk is written to the stream, not when it is acknowledged; the receiver's want covers
  that last second.
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
- **When touching `LinkSignal.PHASE_DECLINE`, `DirectLinks.declinedAt` / `declining` / `DECLINES_PER_HOUR`,
  `LinkOutcome.DECLINED` / `BUDGET`, `LinkWhy`, `LinkDemandPolicy.FILE_WINDOW_MS` / `WANT_WAIT_MS` / `offers`,
  `MeshManager.attachmentWantedSince`, `MessageDao.peerDmAttachmentsArrivedBetween` or `PunchPolicy.MAX_BACKOFF_MS`:** READ ADR
  2026-10.a5yj's decline amendment and `docs/DIRECT_LINKS.md` L-2-6, L-7-7 and L-7-9. Only the person's switch declines
  (low-power and a pause pass, saying nothing); a decline is never backed off and holds until something newer than it,
  never on a timer. A file no spool carries is offered for custody's 24 h at a backoff capped at 30 min; a message keeps
  its 15 min. The receiving end's WANT reads the blob exchange's own want from when a link could first carry it
  (`Want.linkableSince`, the a5yj 2026-10-31 amendment), so a download the person's limits hold back on a metered network
  never calls a link, and a link that came and went while it was held never stops one later. Every offer not answered is a `link_try` line on the answering end, and every offer of ours names
  its `why`.
- **When touching `LinkSignal.m`, `DirectLinks.freeLinkTo` / `raiseBulk` / `mayRaiseBulk` / `meteredAt` /
  `METERED_RETRY_MS` / `passMetered`, `LinkOutcome.METERED`, `LinkWhy.BULK`, `MeshTransport.freeLinked` / `freeLinkTo` /
  `raiseFreeLink` / `freeLinkMay` / `freeLinkComing`, `DirectLinkTransport.raiseFreeLink`, `CompositeMeshTransport.fileChildFor` /
  `freeLinkFor` / `awaitPlaneThenSend` / `fastLinked`, `LinkDemandPolicy.Pending.beside`, `MeshLinkPending`'s
  `fastHeld` or `MeshManager.attachmentFastHeld`:** READ ADR 2026-10.a5yj's beside-a-radio amendment (#236) and
  `docs/DIRECT_LINKS.md` L-2-7, L-7-11. Beside a radio a link carries attachments, never frames, and only a **free** one:
  neither end's network metered when its OFFER and ANSWER were signed, read once there and never mid-link — never a
  same-LAN test (a hairpin answers first as often as not, `HOST` covers carrier NAT and Tailscale space, and routed
  subnets or bridged WANs cannot be seen). A metered end beside a radio declines with `m`, which is never a decline
  (L-2-6): the contact's links stay on. The sender raises one (`BULK`) for a blob of 128 KiB or more the link would
  carry to that peer, after Wi-Fi Aware will not arm, and waits 10 s for it; the receiver raises one only for a want
  held for a fast route, at once. A copy the link refuses goes to the radio, never dropped (a fast-only one excepted).
- **When touching `PunchPolicy.preferred` / `adopts` / `inPrefix` / `UPGRADE_WINDOW_MS` / `STICKY_MS`,
  `LinkSession.pathKind` / `follow` / `upgrade` / `onPathUpgrade`, `LinkPort.onLink`, `AndroidInternetGate.OwnAddress`'s
  `prefixLength`, `FieldEvent.LinkEnd.path` or `FakeLinkNet.lanDown`:** READ ADR 2026-10.a5yj's beside-a-radio amendment
  and `docs/DIRECT_LINKS.md` L-4-3. Up goes at the first ACK, as before — never a wait for a better path — and a link
  that came up on a path leaving the phone's network probes its preferred targets (a host one, an IPv6 one inside this
  network's prefix) for one second more and moves to the first that answers; on one, it follows a datagram from
  elsewhere only after 3 s of nothing from a preferred one. `link_try`'s `path` is the first answer, `link_end`'s the
  path the data took.
- **When touching `AttachmentRelay.marksNotRelayed`, `ChatUiState.relayDirectLinkNote`, `BlobRepository.observeHolders`,
  `AttachmentHolderDao.observeOnPlanes` or the `chat_relay_not_relayed` / `chat_relay_direct_link_note` strings:** READ ADR
  2026-10.a5yj's amendment on the "Not on relays" marker. The marker names the relays, never "nearby" (a link can carry
  the file); the dialog names a link only in a DM whose peer takes links while our switch is on; and the marker goes
  once the DM peer proves it holds the file, never in a group.
- **When touching `shownPlane`, `planeGlyph` / `deliveryLabel`'s `DirectLink` arms, `BlobRepository.observeLinkHolders`,
  `AttachmentHolderDao.observeOnPlane` or the `chat_status_*_link` strings:** READ ADR 2026-10.a5yj's 2026-10-28
  amendment. A link shows its own face (two arrows, "over a direct link"), never the Internet's: a link on one network
  never leaves it. A file shows the link when the bytes' proof (ADR 2026-10.vy27) came over one — the recipient's own,
  or on our send the DM peer's — whatever plane its message crossed; a group send of ours never does. A sender's link
  row has two writers, `LinkHolderProof` (a photo no spool takes) and the push-half deferral's proof round (a photo held
  back while the link is up), so it means a link confirmed the hold, not always that it carried the bytes.
