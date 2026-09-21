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
| [2026-09.26q3](decisions/2026-09-26q3-the-meshtastic-room-is-a-local-mirror-of-the-bound-board-s-slot-0.md) | The Meshtastic room is a local mirror of the bound board's slot 0 | lora, meshtastic, mesh |
| [2026-09.29dw](decisions/2026-09-29dw-the-boot-receiver-s-mesh-start-skips-the-process-state-pre-check.md) | The boot receiver's mesh start skips the process-state pre-check | reliability, service, android |
| [2026-09.2ajk](decisions/2026-09-2ajk-lora-reach-is-relay-reach.md) | LoRa reach is relay reach, and a custody re-serve is not presence | lora, mesh, ui |
| [2026-09.2v2t](decisions/2026-09-2v2t-a-phone-s-mesh-contribution-is-counted-at-the-hand-off.md) | A phone's mesh contribution is counted at the hand-off, once per message, and stays on the phone | ui, custody, privacy |
| [2026-09.37ce](decisions/2026-09-37ce-a-direct-transfer-s-bytes-are-sealed.md) | A direct transfer's bytes are sealed, not just sent over WPA2 | transfer, crypto |
| [2026-09.3yje](decisions/2026-09-3yje-the-open-to-chat-cue-introduces-strangers-only.md) | The open-to-chat cue introduces strangers only, gated on a two-way exchange | notifications, presence, data |
| [2026-09.4n5p](decisions/2026-09-4n5p-a-knit-board-answers-a-meshtastic-dm-once.md) | A Knit board answers a Meshtastic DM once | lora, meshtastic, provisioning |
| [2026-09.4tx5](decisions/2026-09-4tx5-a-blob-is-served-only-to-a-fresh-ask.md) | A blob is served only to a fresh ask, and never asked for while it is arriving | mesh, attachments, blob-exchange, bluetooth |
| [2026-09.535d](decisions/2026-09-535d-the-mesh-service-asks-for-the-location-foreground-type-on-the-location-tiers.md) | The mesh service asks for the location foreground type on the location tiers | reliability, service, android, permissions |
| [2026-09.54xg](decisions/2026-09-54xg-the-unused-app-switch-is-shown-where-the-battery-exemption-is.md) | The unused-app switch is shown where the battery exemption is | ui, onboarding, settings, permissions |
| [2026-09.5bqu](decisions/2026-09-5bqu-the-lora-plane-is-fully-quiescent-until-a-board-is-configured.md) | The LoRa plane is fully quiescent until a board is configured | lora, mesh, performance |
| [2026-09.5dt2](decisions/2026-09-5dt2-a-queued-lora-frame-is-re-asked-the-gates-it-passed-at-enqueue.md) | A queued LoRa frame is re-asked the gates it passed at enqueue | lora, airtime |
| [2026-09.66cw](decisions/2026-09-66cw-a-spool-bearer-token-is-stored-in-the-clear.md) | A spool bearer token is stored in the clear, and one canonical URL is what gets stored | spool, privacy, settings, data |
| [2026-09.6eb6](decisions/2026-09-6eb6-in-app-about-and-licenses.md) | In-app About and licenses: a hand-kept list pinned to the notices file and the release classpath | ui, build, release, settings |
| [2026-09.6gtm](decisions/2026-09-6gtm-the-lora-plane-is-introduced-at-2-5-0.md) | The LoRa plane is introduced at 2.5.0 | lora, release, mesh |
| [2026-09.6mj7](decisions/2026-09-6mj7-a-backup-is-one-sealed-file-under-a-recovery-key.md) | A backup is one sealed file under a recovery key, and a restore is a move | data, crypto, ui, backup |
| [2026-09.6nmy](decisions/2026-09-6nmy-a-frame-crosses-a-bluetooth-link-at-most-once.md) | A frame crosses a Bluetooth link at most once | bluetooth, fast-path, battery |
| [2026-09.6ww7](decisions/2026-09-6ww7-a-group-chat-says-lora-will-not-carry-it.md) | A group chat says LoRa will not carry it | lora, ui |
| [2026-09.7463](decisions/2026-09-7463-sealed-dm-form-chat-rides-the-targeted-coordination-plane-arm.md) | Sealed DM-form chat rides the targeted coordination-plane arm | mesh, nan, fanout |
| [2026-09.74fq](decisions/2026-09-74fq-open-to-chat-is-a-carried-profile-flag.md) | Open to chat is a carried profile flag, and the nearby cue is batched with per-person and hourly cooldowns | profile, wire, notifications, ui |
| [2026-09.7bu7](decisions/2026-09-7bu7-a-profile-is-custodied-wherever-it-is-delivered.md) | A profile is custodied wherever it is delivered, flooded or served | mesh, custody, profile, convergence |
| [2026-09.7c8n](decisions/2026-09-7c8n-gossip-pays-for-its-own-air.md) | Gossip pays for its own air, and a backfill round skips what it cannot afford | lora, airtime, reliability |
| [2026-09.7r4d](decisions/2026-09-7r4d-a-post-typed-in-the-bridged-room-is-the-same-frame-with-no-speaker.md) | A post typed in the bridged room is the same frame with no speaker | lora, meshtastic, mesh |
| [2026-09.7svb](decisions/2026-09-7svb-a-lora-rate-limiter-that-resets-on-launch-is-not-a-rate-limiter.md) | A LoRa rate limiter that resets on launch is not a rate limiter | lora, airtime, reliability |
| [2026-09.7uqe](decisions/2026-09-7uqe-a-file-offer-is-an-event-of-its-own.md) | A file offer is an event of its own, not a line in the chat | transfer, ui, notifications |
| [2026-09.7x8k](decisions/2026-09-7x8k-a-send-waits-for-the-card-its-link-is-fetching.md) | A send waits for the card its link is fetching, and a LoRa thread takes one like a photo | attachments, ui, network |
| [2026-09.9469](decisions/2026-09-9469-a-post-to-the-meshtastic-room-carries-no-author-name.md) | A post to the Meshtastic room carries no author name | lora, meshtastic, privacy |
| [2026-09.995c](decisions/2026-09-995c-a-peer-rename-notice-stores-both-names.md) | A peer rename notice stores both names | ui, data |
| [2026-09.9dnk](decisions/2026-09-9dnk-the-wedge-watchdog-s-tier-1-responder-refresh-is-capped-per-episode.md) | The wedge watchdog's Tier-1 responder refresh is capped per episode | mesh, nan, recovery |
| [2026-09.a8ud](decisions/2026-09-a8ud-supervised-and-managed-phones-are-named.md) | Supervised and managed phones are named, and a blocked grant points at whoever holds it | ui, onboarding, permissions, reliability |
| [2026-09.aa27](decisions/2026-09-aa27-a-room-delivery-tick-rides-a-frame-already-going-to-its-author.md) | A room delivery tick rides a frame already going to its author | receipts, mesh, lora |
| [2026-09.amzn](decisions/2026-09-amzn-a-spool-s-answers-are-bounded-by-what-the-client-can-track.md) | A spool's answers are bounded by what the client can track | spool, hardening |
| [2026-09.bgk3](decisions/2026-09-bgk3-an-unfulfillable-responder-request-is-re-filed-on-a-curve-and-given-up-on.md) | An unfulfillable responder request is re-filed on a curve and given up on | wifi-aware, reliability, mesh |
| [2026-09.bts9](decisions/2026-09-bts9-the-block-list-never-enters-custody.md) | The block list never enters custody: a blocker carries a blocked sender's frames like every other node | custody, convergence, moderation, privacy |
| [2026-09.cf7a](decisions/2026-09-cf7a-a-meshtastic-public-post-is-a-signed-attribution-in-its-own-room.md) | A Meshtastic public post is a signed attribution in its own room | lora, meshtastic, mesh |
| [2026-09.cq9z](decisions/2026-09-cq9z-bundled-models-are-leased.md) | Bundled models are leased, not held resident | moderation, ml, battery, reliability |
| [2026-09.dcah](decisions/2026-09-dcah-the-scope-table-derives-on-its-inputs-events.md) | The scope table derives on its inputs' events | spool, battery |
| [2026-09.e8yw](decisions/2026-09-e8yw-the-bytes-own-radio-arrival-is-the-recipient-s-deferral-evidence.md) | The bytes' own radio arrival is the recipient's deferral evidence, noted before they are stored | spool, attachments |
| [2026-09.emd7](decisions/2026-09-emd7-a-knit-board-tells-the-mesh-it-is-unmonitored.md) | A Knit board tells the mesh it is unmonitored | lora, meshtastic, provisioning |
| [2026-09.f69x](decisions/2026-09-f69x-a-start-into-a-demoted-foreground-service-re-claims-the-state-instead-of-timing.md) | A start into a demoted foreground service re-claims the state instead of timing out | reliability, service, android |
| [2026-09.fq6b](decisions/2026-09-fq6b-overscroll-and-ripple-take-their-colour-from-the-theme.md) | Overscroll and ripple take their colour from the theme | ui, theme |
| [2026-09.gc3m](decisions/2026-09-gc3m-battery-use-set-to-restricted-is-its-own-state.md) | Battery use set to Restricted is its own state, shown where the exemption is | ui, onboarding, settings, reliability |
| [2026-09.gdhp](decisions/2026-09-gdhp-every-sender-supplied-last-writer-wins-clock-is-clamped-to-the-skew-window.md) | Every sender-supplied last-writer-wins clock is clamped to the skew window | mesh, groups, profile, reactions |
| [2026-09.ggq4](decisions/2026-09-ggq4-a-heard-meshtastic-post-is-verified-against-the-board-key-in-its-author-s-profil.md) | A heard Meshtastic post is verified against the board key in its author's profile | lora, meshtastic, mesh |
| [2026-09.hd5n](decisions/2026-09-hd5n-the-chat-thread-reads-a-newest-anchored-window.md) | The chat thread reads a newest-anchored window, not the whole conversation | ui, data, perf |
| [2026-09.hknx](decisions/2026-09-hknx-the-two-profile-screens-share-a-section-vocabulary.md) | The two profile screens share a section vocabulary, and Save moves to the app bar | ui, profile |
| [2026-09.j8c7](decisions/2026-09-j8c7-a-photo-less-avatar-wears-its-node-id-s-hue.md) | A photo-less avatar wears its node id's hue, in a fixed palette shared with the notification shade | ui, identity, notifications |
| [2026-09.k68y](decisions/2026-09-k68y-diagnostics-reads-a-relay-s-build-from-its-source-offer.md) | Diagnostics reads a relay's build from its /source offer, not from HELLO | spool, diagnostics, ui |
| [2026-09.kb68](decisions/2026-09-kb68-a-lonely-node-relaxes-its-discovery-cadence.md) | A lonely node relaxes its discovery cadence | wifi-aware, battery, reliability |
| [2026-09.m7vn](decisions/2026-09-m7vn-settings-and-your-profile-are-two-screens.md) | Settings and your profile are two screens | ui, navigation, settings, profile |
| [2026-09.m8kc](decisions/2026-09-m8kc-an-initiate-that-costs-the-phone-its-wi-fi-is-given-up-on.md) | An initiate that costs the phone its Wi-Fi is given up on | wifi-aware, reliability, mesh |
| [2026-09.m9h8](decisions/2026-09-m9h8-material-you-is-opt-in.md) | Material You is opt-in, and green stays green | ui, theme, settings |
| [2026-09.mhs5](decisions/2026-09-mhs5-a-lora-packet-is-padded-past-the-firmware-s-signature-cliff.md) | A LoRa packet is padded past the firmware's signature cliff | lora, airtime, link |
| [2026-09.mjaj](decisions/2026-09-mjaj-the-group-seed-carries-the-founding-roster.md) | The group seed carries the founding roster | groups, roster, spool, wire |
| [2026-09.n752](decisions/2026-09-n752-a-link-preview-is-a-sender-fetched-card-riding-the-photo-path.md) | A link preview is a sender-fetched card riding the photo path | attachments, ui, wire, privacy, moderation, network |
| [2026-09.nzpr](decisions/2026-09-nzpr-onboarding-gates-on-the-radio-permissions-only.md) | Onboarding gates on the radio permissions only | ui, onboarding, permissions |
| [2026-09.p7j8](decisions/2026-09-p7j8-a-missing-ack-is-not-evidence-until-an-ack-could-have-arrived.md) | A missing ack is not evidence until an ack could have arrived, and it is read from both ends of the DM | spool, attachments |
| [2026-09.ptv8](decisions/2026-09-ptv8-a-missing-attachment-is-re-wanted-from-the-database-at-every-link-up.md) | A missing attachment is re-wanted from the database at every link-up, not only at startup | mesh, attachments, blob-exchange, lora |
| [2026-09.qgk4](decisions/2026-09-qgk4-the-startup-profile-ships-beside-the-baseline-one.md) | The startup profile ships beside the baseline one, from the same journey | performance, build, distribution |
| [2026-09.qq2r](decisions/2026-09-qq2r-a-file-is-an-ordinary-attachment-with-an-arbitrary-mime-and-a-sealed-name.md) | A file is an ordinary attachment with an arbitrary MIME and a sealed name | attachments, ui, wire, moderation |
| [2026-09.qsj6](decisions/2026-09-qsj6-a-heard-inconsistent-offer-is-news.md) | A heard inconsistent OFFER is news | lora, airtime, reliability |
| [2026-09.qtg9](decisions/2026-09-qtg9-an-unsent-draft-is-a-row-in-the-encrypted-database.md) | An unsent draft is a row in the encrypted database, handed to the composer once | ui, data, room, privacy |
| [2026-09.qztx](decisions/2026-09-qztx-a-publish-stamp-is-signed-once.md) | A publish stamp is signed once, under one lock, by whoever writes it | mesh, spool, profiles |
| [2026-09.rre4](decisions/2026-09-rre4-the-lora-backfill-serves-the-room-before-dms.md) | The LoRa backfill serves the room before DMs | lora, airtime, custody |
| [2026-09.sjaa](decisions/2026-09-sjaa-ble-side-channel.md) | BLE side channel: small floodable frames ride non-connectable extended-advertising pages | mesh, bluetooth |
| [2026-09.sre4](decisions/2026-09-sre4-a-resolved-meshtastic-author-s-avatar-opens-the-caveat.md) | A resolved Meshtastic author's avatar opens the caveat, not the profile | lora, meshtastic, ui |
| [2026-09.t8t8](decisions/2026-09-t8t8-an-offer-is-not-backfill-and-must-not-compete-with-it.md) | An OFFER is not backfill and must not compete with it | lora, airtime, reliability |
| [2026-09.tmbq](decisions/2026-09-tmbq-a-relay-invite-is-a-bearer-link.md) | A relay invite is a bearer link, applied on consent and never silently | spool, relays, ui, contacts |
| [2026-09.tss4](decisions/2026-09-tss4-a-shared-location-is-a-geo-uri-in-the-body.md) | A shared location is a geo URI in the body, read only when you send it | location, privacy, ui, permissions, moderation |
| [2026-09.u8qj](decisions/2026-09-u8qj-the-ble-side-scan-is-off-while-every-capable-peer-is-linked-and-nothing-streams.md) | The BLE side scan is off while every capable peer is linked and nothing streams | mesh, bluetooth |
| [2026-09.uc8p](decisions/2026-09-uc8p-a-profile-is-re-flooded-to-a-peer-once-per-seen-window.md) | A profile is re-flooded to a peer once per seen window | mesh, battery |
| [2026-09.un9n](decisions/2026-09-un9n-a-never-drawn-window-is-recovered-by-recreating-it.md) | A never-drawn window is recovered by recreating it | ui, android, resilience, back |
| [2026-09.ursc](decisions/2026-09-ursc-the-nearby-room-says-when-lora-airtime-is-spent.md) | The Nearby room says when LoRa airtime is spent | lora, ui |
| [2026-09.v5ck](decisions/2026-09-v5ck-light-and-dark-are-a-per-app-night-mode.md) | Light and dark are a per-app night mode | ui, theme, settings |
| [2026-09.v66c](decisions/2026-09-v66c-reactions-are-an-open-emoji-set-with-a-receive-side-length-cap.md) | Reactions are an open emoji set with a receive-side length cap | wire, ui, limits |
| [2026-09.v6fu](decisions/2026-09-v6fu-a-founding-member-who-left-rejoins-by-their-own-signed-frame.md) | A founding member who left rejoins by their own signed frame | groups, roster, crypto |
| [2026-09.vej5](decisions/2026-09-vej5-a-spool-is-connected-once-it-says-hello.md) | A spool is connected once it says hello, and a route that swallows the socket is unreachable | spool, ui, diagnostics |
| [2026-09.vybk](decisions/2026-09-vybk-a-room-flood-is-bounded-by-the-link-it-came-over-and-displaces-strangers-before.md) | A room flood is bounded by the link it came over and displaces strangers before contacts | mesh, retention, sybil |
| [2026-09.vztn](decisions/2026-09-vztn-the-mesh-graph-is-built-off-the-main-thread.md) | The mesh graph is built off the main thread; the service only starts it there | reliability, service, android |
| [2026-09.wa79](decisions/2026-09-wa79-a-scope-heals-when-something-changed.md) | A scope heals when something changed | spool, battery, reliability |
| [2026-09.wdfz](decisions/2026-09-wdfz-message-search-is-an-external-content-fts4-index-over-messages-body.md) | Message search is an external-content FTS4 index over messages.body | data, room, search, perf |
| [2026-09.wkbk](decisions/2026-09-wkbk-a-gateway-hands-a-targeted-tick-the-last-hop.md) | A gateway hands a targeted tick the last hop | receipts, mesh, lora |
| [2026-09.wnh6](decisions/2026-09-wnh6-search-is-one-screen-over-the-chat-list-s-own-universe.md) | Search is one screen over the chat list's own universe, and a hit opens the thread on one message | ui, search, navigation |
| [2026-09.wtmz](decisions/2026-09-wtmz-a-large-file-goes-off-the-mesh.md) | A large file goes off the mesh, over a Wi-Fi group the two phones raise | transfer, mesh, wifi |
| [2026-09.wuqj](decisions/2026-09-wuqj-an-alias-is-a-word-encoded-digest-prefix-that-grows-when-matched.md) | An alias is a word-encoded digest prefix that grows when matched | identity, ui, security |
| [2026-09.wx8e](decisions/2026-09-wx8e-the-commons-is-a-private-spool-s-group-chat.md) | The commons is a private spool's group chat | spool, commons |
| [2026-09.x52a](decisions/2026-09-x52a-the-meshtastic-room-is-a-switch.md) | The Meshtastic room is a switch, and off means unread | lora, ui, settings |
| [2026-09.xdm2](decisions/2026-09-xdm2-a-queued-snapshot-is-replaced-by-its-own-newer-copy.md) | A queued snapshot is replaced by its own newer copy | lora, airtime, mesh |
| [2026-09.xmte](decisions/2026-09-xmte-radio-evidence-for-an-attachment-deferral-must-name-a-short-range-plane.md) | Radio evidence for an attachment deferral must name a short-range plane | spool, attachments, receipts |
| [2026-09.y5f3](decisions/2026-09-y5f3-a-ride-hold-has-a-deadline.md) | A ride hold has a deadline, and the spool is the room tick's first way home | receipts, mesh, lora, spool |
| [2026-09.y8pu](decisions/2026-09-y8pu-a-lora-fan-out-nobody-heard-does-not-suppress-its-own-backfill.md) | A LoRa fan-out nobody heard does not suppress its own backfill | lora, custody, reliability |
| [2026-09.ypcc](decisions/2026-09-ypcc-a-cloned-identity-is-detected-from-its-own-profile-stamp.md) | A cloned identity is detected from its own profile stamp, and sign-out is a wipe | mesh, ui, backup |
| [2026-09.ywzn](decisions/2026-09-ywzn-a-blob-obtained-off-the-radios-serves-the-neighbours-that-asked-for-it.md) | A blob obtained off the radios serves the neighbours that asked for it | mesh, attachments, spool |
| [2026-09.z58t](decisions/2026-09-z58t-the-chat-list-reads-per-thread-summaries.md) | The chat list reads per-thread summaries, and an accepted thread has no retention cap | data, ui, perf |
| [2026-09.zapp](decisions/2026-09-zapp-a-photo-less-group-avatar-is-its-members-faces.md) | A photo-less group avatar is its members' faces, drawn the same in the shade | ui, identity, notifications |
| [2026-09.zkma](decisions/2026-09-zkma-the-bridge-offer-and-its-backfill-draw-from-one-ranked-list.md) | The bridge offer and its backfill draw from one ranked list | lora, bridge, custody |
| [2026-09.zu5t](decisions/2026-09-zu5t-content-capture-is-off.md) | Content capture is off | privacy, ui, performance |
