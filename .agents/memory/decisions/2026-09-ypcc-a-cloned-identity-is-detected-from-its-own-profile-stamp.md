---
id: "2026-09.ypcc"
slug: a-cloned-identity-is-detected-from-its-own-profile-stamp
title: "A cloned identity is detected from its own profile stamp, and sign-out is a wipe"
date: 2026-09-20
topics: [mesh, ui, backup]
---

# ADR 2026-09.ypcc — A cloned identity is detected from its own profile stamp, and sign-out is a wipe

Status: Accepted (2026-09-20). GitLab work item 80, found while writing ADR 2026-09.6mj7. Code:
`mesh/CloneWatch`, the self branch of `InboundPipeline.handleProfile`, `data/settings/CloneWatchSettings`,
`ui/chatlist/CloneBanner`, the Settings clone row, `ui/signout/`. No wire change, no schema change.

**What was observed.** A backup (ADR 2026-09.6mj7) restored onto two phones leaves both running one
identity, and the mesh has no multi-device concept: the node id is the hash of the key, so a contact
cannot tell the two apart and verification stays green for both; the two never link (every transport
drops its own node id at discovery) and meet each other's traffic only as *self frames* relayed through
a third node; a peer holds one ratchet session per node id, so its DMs reach whichever phone reset last,
the other fails `AEAD_FAIL` until its own three-frame-plus-six-hour reset (ADR 023/024) wins the session
back, and so on. Degraded, not corrupting — wiping one phone heals the other within a reset cycle — and
until now the only mitigation was the restore copy asking the user to stop using the old phone.

**What changed.** The evidence was already on the inbound path. Every profile frame this phone publishes
carries `SettingsStore.profilePublishedAt` as its id and `sentAt` (`MeshManager.currentProfileEnvelope`;
`nextPublishStamp` only moves up), and `verifyInbound` admits a self frame by checking it against our own
bundle, so a `profile` under our node id is signed by our key or dropped. `handleProfile`'s self branch —
which already refused to pin the row — now hands the frame's stamp to `CloneWatch`, and **a stamp past our
own is a frame this phone never minted**: a twin. Everything at or below the stamp is our own history,
re-served by a peer's custody after a wipe, and never evidence. The verdict is one pure function
(`CloneWatch.isEvidence`, pinned by `CloneWatchTest`); the watch stores the sighting as
`SettingsStore.cloneSeenAt`, and the chat-list banner and the Settings row show while it is past
`cloneDismissedAt`. Both stamps are phone-local (`SettingsKeys.TRANSIENT_PREFIXES`), so a backup taken
under the banner cannot plant it on the next phone.

The work item also named a DM-form self frame we hold no record of. It was left out on purpose: the
record is the database, and a `DatabaseKey` wipe empties custody and messages while the DataStore — and
the stamp — survive, so every send of the last day, re-served by a peer, would then be a false twin. The
stamp lives in the file that survives and has no such hole. Its cost is latency on the far side: the
twin sees *us* only once we publish a stamp newer than its own, which each phone does every twelve hours
anyway. So a detection floods our profile once (`MeshManager.broadcastProfile`, through the watch's
`onDetected`) — the other phone then sees a newer stamp within a contact, and its own flood finds us
already lit. The flood fires on the not-visible → visible edge only, never while the banner is up, so two
twins cannot bounce: A lights and floods, B lights and floods, A records and stays quiet.
`CloneLabTest` runs the whole shape — two `LabNode`s over one identity (`MeshLab.node(sameIdentityAs)`),
a third node that can hold only one of them at a time, both twins lit, one retired, the survivor and the
third converging — and the counter-flood is also what puts the survivor's own presentation back in front
of the third node.

Two bounds keep it honest. Evidence is gated on `restorePending`: until `finishRestore` bumps the stamp
past everything the old phone published, the backup's stale stamp would make the old phone's last
republishes look like a twin. And a dismissal ("The other phone is gone", persisted as `cloneDismissedAt`)
is undone only by a frame stamped *after* it — a twin still publishing brings the banner back, a custody
re-serve of what it published before the user wiped it does not. Both are needed for the honest move:
restore, keep the old phone on for a day, wipe it, dismiss once.

**Sign out here** is `ActivityManager.clearApplicationUserData()` — the platform's own "Clear storage":
every private file (the wrapped identity and database keys, the database, the DataStore, the blobs, any
restore staging), the runtime grants, the notifications and the process. The reader's first reach is the
restore choreography in reverse — stage a marker, relaunch through `RestartActivity`, delete
`identity.key` and `db.key` before Koin — and it was rejected twice over: a file list for "everything of
the old identity" is a list to keep (the blobs are message content), and a phone that keeps its radio
grants lands on the chat list with a blank name, because the front door routes on those grants alone
(ADR 2026-09.nzpr) and teaching it a second rule was not worth a sign-out. Losing the grants is what makes
the next open a fresh install's onboarding, which is the promise the restore copy makes — a move, not a
copy — enforceable from either side. The pre-wipe checklist is `BackupViewModel.confirmRestore`'s (plain
`stopService`, notifications, conversation shortcuts), pinned by `SignOutTest`.

**What it costs, and the traps.** A twin heard only over a LoRa board is invisible: `LoraMeshTransport`
drops its own node id at ingest, before the router, and the watch never sees the frame. The stamp bound
is a clock comparison between the two phones — a twin whose clock runs far behind detects the other and
is not detected by it until its own clock catches up, and an old phone whose clock ran far ahead of the
restore can trip the new one once (the dismissal covers it). The counter-flood bumps `profileVersion`
along with the stamp; the content is unchanged, so `peerPresentationNotices` writes nothing, but it is one
extra flood per lighting. Real multi-device — a device id in the profile, per-device sessions — is a wire
break and sits in `docs/NEXT_WIRE_BREAK.md`. Owed: the device trial (two lab Pixels restored from one
backup, a third phone as the contact, the twins never in each other's range).
