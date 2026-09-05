# Architecture decision records

The load-bearing decisions and *why* they hold, so future work stays consistent. One
file each, in [`decisions/`](decisions/) — this page is the router. Open the file whose
row matches; don't guess from the title alone. Supersede by writing a new ADR and
changing the old one's `Status:` — never by deleting it.

**This file is generated. Do not edit it by hand.** Add a decision with
`python3 scripts/adr.py new "<title>" --topics a,b`, write the body, then
`python3 scripts/adr.py index`.

**Two id forms, and both are permanent.** ADRs `001`-`067` predate the split into
one-file-per-decision and keep their sequence numbers, because `ADR NNN` is cited ~815
times in code comments, `docs/` and commit messages — including commits F-Droid pins,
which cannot be rewritten. Everything since is `YYYY-MM.suffix` (`ADR 2026-08.k3f9`),
minted at random, because the sequence number was itself the merge conflict: parallel
worktrees all read the same "next number" and all took it. Cite whichever form an ADR
carries; never renumber an old one.

87 decisions.

| ADR | Decision | Topics |
| --- | --- | --- |
| [001](decisions/001-di-is-koin.md) | DI is Koin, not Hilt | build, toolchain, di |
| [002](decisions/002-built-in-kotlin-is-overridden-to-2-4-0.md) | Built-in Kotlin is overridden to 2.4.0 (not AGP's bundled 2.2.10) | build, toolchain, kotlin |
| [003](decisions/003-two-radios-behind-one-meshtransport-seam.md) | Two radios behind one `MeshTransport` seam, no GMS/Nearby | mesh, architecture, radios |
| [004](decisions/004-two-plane-nan-design.md) | Two-plane NAN design (cue plane + ephemeral NDP) with an accept-any responder | mesh, nan, concurrency |
| [005](decisions/005-layered-opaque-cbor-wire-one-frame-signature.md) | Layered opaque-CBOR wire + one frame signature | wire, protocol, crypto |
| [006](decisions/006-convergent-custody-quota.md) | Convergent custody quota (frame-global `sentAt`, live-only, `ORIGIN_SELF` included) | custody, convergence, store-and-forward |
| [007](decisions/007-static-analysis-via-standalone-cli.md) | Static analysis via standalone CLI; Kover is the one plugin exception | build, toolchain, static-analysis |
| [008](decisions/008-db-v1-is-the-frozen-launch-baseline.md) | DB v1 is the frozen launch baseline — migrations mandatory from v1 | data, room, migrations |
| [009](decisions/009-one-shared-message-request-vs-accepted-predicate.md) | One shared "message request vs accepted" predicate (`Conversations.isAccepted`) | data, privacy, message-requests |
| [010](decisions/010-blocking-is-local-presentation-only.md) | Blocking is local presentation only — a blocked sender's broadcast/group message is still acked | privacy, custody, moderation |
| [011](decisions/011-static-analysis-room-schema-run-as-gradle-plugins.md) | Static analysis + Room schema run as Gradle plugins (supersedes 007) | build, toolchain, static-analysis |
| [012](decisions/012-r8-obfuscation.md) | R8 obfuscation (name mangling) enabled — the wire stays safe by construction | build, release, r8, wire |
| [013](decisions/013-accessibility-checks-run-via-compose-s-atf-integration.md) | Accessibility checks run via Compose's ATF integration, not Espresso | testing, a11y |
| [014](decisions/014-f-droid-ships-our-signed-apk.md) | F-Droid ships *our* signed APK (reproducible `Binaries:`), not an F-Droid-signed rebuild | release, fdroid, reproducible-builds |
| [015](decisions/015-qr-scanning-is-camerax-zxing-core.md) | QR scanning is CameraX + zxing core, not zxing-android-embedded | ui, contacts, camera |
| [016](decisions/016-dm-forward-secrecy-is-an-epoch-rekey-ratchet.md) | DM forward secrecy is an epoch-rekey ratchet (not Double Ratchet, not libsignal) | crypto, pfs, dm |
| [017](decisions/017-group-forward-secrecy-is-a-sender-key-ratchet-over-the-pairwise-dm-sessions.md) | Group forward secrecy is a sender-key ratchet over the pairwise DM sessions (not pairwise fan-out, not MLS-lite) | crypto, pfs, groups |
| [018](decisions/018-receipts-and-reactions-are-sealed-as-v2-ctl-frames.md) | Receipts and reactions are sealed as v2 ctl frames; the DM vaccine-purge is retired for the sealed era | crypto, wire, receipts |
| [019](decisions/019-the-internet-plane-is-a-scoped-custody-spool-protocol.md) | The internet plane is a scoped-custody spool protocol — M1 ships the public spec plus pure-crypto anchors, nothing else | spool, crypto, protocol |
| [020](decisions/020-profile-updates-are-sealed-as-a-v2-ctl.md) | Profile updates are sealed as a v2 ctl; the cleartext profile frame keeps first contact | crypto, wire, profile |
| [021](decisions/021-attachment-uploads-are-deferred-while-the-radios-still-carry-them.md) | Attachment uploads are deferred while the radios still carry them; the frame plane stays unconditional | spool, attachments |
| [022](decisions/022-the-cleartext-profile-frame-rides-the-spool-plane.md) | The cleartext profile frame rides the spool plane, and its version leaves `sentAt` | spool, profile, wire |
| [023](decisions/023-a-split-brain-ratchet-root-requests-a-reset.md) | A split-brain ratchet root requests a reset, like every other unreadable v2 DM | crypto, pfs, recovery |
| [024](decisions/024-the-reset-heuristic-only-counts-frames-from-the-era-it-would-abandon.md) | The reset heuristic only counts frames from the era it would abandon; an explicit reset is never a race remnant | crypto, pfs, recovery |
| [025](decisions/025-a-spool-s-advertised-limits-are-a-claim.md) | A spool's advertised limits are a claim, not a bound — the client's own request is the bound | spool, limits |
| [026](decisions/026-the-era-gate-is-single-clock-only-when-we-responded.md) | The era gate is single-clock only when we responded; the initiator half needs a local bound | crypto, pfs, recovery |
| [027](decisions/027-local-epoch-retention-orders-by-mint-time.md) | Local-epoch retention orders by mint time; a re-minted epoch number replaces the dead era's key | crypto, pfs, retention |
| [028](decisions/028-crash-reports-are-captured-on-device.md) | Crash reports are captured on-device, redacted in two phases, and handed over only by the user | privacy, crash-reports, support |
| [029](decisions/029-taking-a-photo-in-a-chat-is-an-in-app-camerax-surface.md) | Taking a photo in a chat is an in-app CameraX surface, entered by long-press, ingested in memory | ui, attachments, camera |
| [030](decisions/030-coordination-plane-compaction-is-transport-local.md) | Coordination-plane compaction is transport-local: compact framing + preset-dict deflate + ≤3-part fragmentation, capability-gated per peer | wire, mesh, compaction |
| [031](decisions/031-the-internet-relay-plane-ships-dark-behind-buildconfig-internet-plane.md) | The Internet-relay plane ships dark behind `BuildConfig.INTERNET_PLANE`, gated at `spoolEnabled` — not stripped | spool, release, flags |
| [032](decisions/032-the-scope-table-is-gated-on-a-confirmed-session.md) | The scope table is gated on a confirmed session, never on the Message Requests rule | spool, privacy, message-requests |
| [033](decisions/033-group-delivery-ticks-escalate-into-custody-when-the-author-is-absent.md) | Group delivery ticks escalate into custody when the author is absent, batched as one sealed ctl frame | custody, groups, receipts |
| [034](decisions/034-a-voice-note-is-an-ordinary-attachment-with-an-audio-mime.md) | A voice note is an ordinary attachment with an audio MIME | attachments, ui, audio |
| [035](decisions/035-an-attachment-s-mime-type-leaves-the-cleartext-frame.md) | An attachment's MIME type leaves the cleartext frame | attachments, wire, privacy |
| [036](decisions/036-per-member-group-delivery-is-a-local-acker-table.md) | Per-member group delivery is a local acker table; the tick's wire semantic is untouched | groups, data, receipts |
| [037](decisions/037-a-bundled-model-that-crashes-the-process-natively-is-latched-off.md) | A bundled model that crashes the process natively is latched off, on evidence rather than on failure | moderation, reliability, ml |
| [038](decisions/038-lora-range-extension-is-a-fast-plane-only-meshtransport-child-over-a-meshtastic.md) | LoRa range extension is a fast-plane-only `MeshTransport` child over a Meshtastic board (BLE GATT) | lora, mesh, architecture |
| [039](decisions/039-sealed-dms-ride-the-lora-plane-through-a-long-range-fan-out-seam.md) | Sealed DMs ride the LoRa plane through a long-range fan-out seam, re-offered on first hearing | lora, crypto, dm |
| [040](decisions/040-the-lora-plane-gets-a-face.md) | The LoRa plane gets a face: an arrival plane per message, a header glyph, a board-only picker | lora, ui |
| [041](decisions/041-the-board-s-battery-is-read-off-the-handshake-and-its-per-minute-telemetry.md) | The board's battery is read off the handshake and its per-minute telemetry, never polled | lora, telemetry |
| [042](decisions/042-contacts-at-a-distance.md) | Contacts at a distance: a signed contact card, the `CTL_PROFILE` intro, and an identity-derived pair scope | contacts, crypto, spool |
| [043](decisions/043-a-refused-foreground-start-retires-the-service-instead-of-crashing.md) | A refused foreground start retires the service instead of crashing, and the wedge cure asks first | reliability, service, android |
| [044](decisions/044-pockets-bridge-over-lora.md) | Pockets bridge over LoRa: an elected gateway, an airtime budget, and a gossiped custody window | lora, custody, airtime |
| [045](decisions/045-board-setup-is-one-step-that-stays-on-the-public-frequency.md) | Board setup is one step that stays on the public frequency, quiets the board, and stops it repeating | lora, ui, provisioning |
| [046](decisions/046-arrival-time-is-our-own-clock.md) | Arrival time is our own clock, stamped once inbound, and never backfilled | data, room, ui |
| [047](decisions/047-motion-is-a-vocabulary-in-one-file.md) | Motion is a vocabulary in one file, gated on the platform's own reduce-motion setting | ui, a11y, motion |
| [048](decisions/048-the-baseline-profile-is-a-committed-text-file.md) | The baseline profile is a committed text file; everything that produces it is quarantined behind a flag | build, release, perf |
| [049](decisions/049-a-board-set-up-for-knit-is-renamed-for-knit.md) | A board set up for Knit is renamed for Knit, from its own node number, and named back on restore | lora, provisioning |
| [050](decisions/050-the-broad-library-keeps-are-gone.md) | The broad library keeps are gone: R8 now optimizes 97% of the app, and the xmlpull duplication bites twice | build, release, r8, perf |
| [051](decisions/051-play-named-tink.md) | Play named Tink; the unbounded decode was ours, in the notifier, on peer-supplied bytes | security, release, images |
| [052](decisions/052-a-wi-fi-aware-attach-that-keeps-failing-is-a-binder-leak.md) | A Wi-Fi Aware attach that keeps failing is a binder leak, so the retry budget is a leak budget | mesh, nan, reliability |
| [053](decisions/053-the-sealed-delivery-tick-retries-on-a-backoff.md) | The sealed delivery tick retries on a backoff, because its retry cadence outran the dedup window | crypto, custody, receipts |
| [054](decisions/054-casual-texting-must-not-black-out-the-lora-plane.md) | Casual texting must not black out the LoRa plane: a recipient gate, a 15-minute window, and coalesced receipts | lora, custody, airtime |
| [055](decisions/055-the-attach-budget-s-refund-path-was-the-leak.md) | The attach budget's refund path was the leak: a bound is only worth what refunds it | mesh, nan, reliability |
| [056](decisions/056-the-key-bootstrap-gets-a-share-of-the-window.md) | The key bootstrap gets a share of the window, not an exemption from it | lora, crypto, airtime |
| [057](decisions/057-a-profile-is-fanned-once-per-publish.md) | A profile is fanned once per publish, and a lost one is repaired by the digest, not by repetition | profile, mesh, convergence |
| [058](decisions/058-a-name-is-a-label.md) | A name is a label; the alias is its discriminator | ui, contacts, identity |
| [059](decisions/059-crypto-scheme-v3.md) | Crypto scheme v3: the nonce is derived, the plaintext is compact, and the live-link tick is unsigned | crypto, wire, compaction |
| [060](decisions/060-the-fast-planes-carry-a-transcoding-of-the-signed-bytes.md) | The fast planes carry a transcoding of the signed bytes, rebuilt and verified at the receiver | wire, mesh, compaction |
| [061](decisions/061-a-coordination-plane-frame-is-a-sighting-of-the-hop-that-delivered-it.md) | A coordination-plane frame is a sighting of the hop that delivered it, never of its author | mesh, wire, presence |
| [062](decisions/062-a-spool-blob-our-custody-will-never-hold-is-accounted.md) | A spool blob our custody will never hold is accounted, not re-pulled | spool, custody, accounting |
| [063](decisions/063-a-relay-list-needs-two-verbs.md) | A relay list needs two verbs: the plane's switch says whether, each relay's says which | spool, ui, relays |
| [064](decisions/064-the-internet-plane-is-introduced-at-2-4-0.md) | The Internet plane is introduced at 2.4.0: the switch that hid it becomes the user's own | spool, release, ui |
| [065](decisions/065-room-3-arrives-as-a-package-move.md) | Room 3 arrives as a package move, and takes SQLCipher's driver with it | data, room, build |
| [066](decisions/066-a-status-notice-is-derived.md) | A status notice is derived, never carried — and it is furniture, not a message | ui, data, wire |
| [067](decisions/067-a-dedicated-lora-frequency-is-a-debug-only-second-bargain.md) | A dedicated LoRa frequency is a debug-only second bargain, and it is the *politeness* ceiling it lifts | lora, airtime, debug |
| [2026-09.2ajk](decisions/2026-09-2ajk-lora-reach-is-relay-reach.md) | LoRa reach is relay reach, and a custody re-serve is not presence | lora, mesh, ui |
| [2026-09.3yje](decisions/2026-09-3yje-the-open-to-chat-cue-introduces-strangers-only.md) | The open-to-chat cue introduces strangers only, gated on a two-way exchange | notifications, presence, data |
| [2026-09.6ww7](decisions/2026-09-6ww7-a-group-chat-says-lora-will-not-carry-it.md) | A group chat says LoRa will not carry it | lora, ui |
| [2026-09.74fq](decisions/2026-09-74fq-open-to-chat-is-a-carried-profile-flag.md) | Open to chat is a carried profile flag, and the nearby cue is batched with per-person and hourly cooldowns | profile, wire, notifications, ui |
| [2026-09.7r4d](decisions/2026-09-7r4d-a-post-typed-in-the-bridged-room-is-the-same-frame-with-no-speaker.md) | A post typed in the bridged room is the same frame with no speaker | lora, meshtastic, mesh |
| [2026-09.995c](decisions/2026-09-995c-a-peer-rename-notice-stores-both-names.md) | A peer rename notice stores both names | ui, data |
| [2026-09.cf7a](decisions/2026-09-cf7a-a-meshtastic-public-post-is-a-signed-attribution-in-its-own-room.md) | A Meshtastic public post is a signed attribution in its own room | lora, meshtastic, mesh |
| [2026-09.emd7](decisions/2026-09-emd7-a-knit-board-tells-the-mesh-it-is-unmonitored.md) | A Knit board tells the mesh it is unmonitored | lora, meshtastic, provisioning |
| [2026-09.mhs5](decisions/2026-09-mhs5-a-lora-packet-is-padded-past-the-firmware-s-signature-cliff.md) | A LoRa packet is padded past the firmware's signature cliff | lora, airtime, link |
| [2026-09.n752](decisions/2026-09-n752-a-link-preview-is-a-sender-fetched-card-riding-the-photo-path.md) | A link preview is a sender-fetched card riding the photo path | attachments, ui, wire, privacy, moderation, network |
| [2026-09.qq2r](decisions/2026-09-qq2r-a-file-is-an-ordinary-attachment-with-an-arbitrary-mime-and-a-sealed-name.md) | A file is an ordinary attachment with an arbitrary MIME and a sealed name | attachments, ui, wire, moderation |
| [2026-09.qsj6](decisions/2026-09-qsj6-a-heard-inconsistent-offer-is-news.md) | A heard inconsistent OFFER is news | lora, airtime, reliability |
| [2026-09.rre4](decisions/2026-09-rre4-the-lora-backfill-serves-the-room-before-dms.md) | The LoRa backfill serves the room before DMs | lora, airtime, custody |
| [2026-09.t8t8](decisions/2026-09-t8t8-an-offer-is-not-backfill-and-must-not-compete-with-it.md) | An OFFER is not backfill and must not compete with it | lora, airtime, reliability |
| [2026-09.un9n](decisions/2026-09-un9n-a-never-drawn-window-is-recovered-by-recreating-it.md) | A never-drawn window is recovered by recreating it | ui, android, resilience, back |
| [2026-09.ursc](decisions/2026-09-ursc-the-nearby-room-says-when-lora-airtime-is-spent.md) | The Nearby room says when LoRa airtime is spent | lora, ui |
| [2026-09.v66c](decisions/2026-09-v66c-reactions-are-an-open-emoji-set-with-a-receive-side-length-cap.md) | Reactions are an open emoji set with a receive-side length cap | wire, ui, limits |
| [2026-09.wuqj](decisions/2026-09-wuqj-an-alias-is-a-word-encoded-digest-prefix-that-grows-when-matched.md) | An alias is a word-encoded digest prefix that grows when matched | identity, ui, security |
| [2026-09.y8pu](decisions/2026-09-y8pu-a-lora-fan-out-nobody-heard-does-not-suppress-its-own-backfill.md) | A LoRa fan-out nobody heard does not suppress its own backfill | lora, custody, reliability |
| [2026-09.zu5t](decisions/2026-09-zu5t-content-capture-is-off.md) | Content capture is off | privacy, ui, performance |
