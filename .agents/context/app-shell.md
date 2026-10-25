# App shell and shared surfaces — which decision governs which symbol

The symbol → ADR routing for the screens and surfaces around the mesh: onboarding and permissions, device
supervision, About and licenses, Your mesh, avatars and conversation shortcuts.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching `legal/`, `ui/about/`, `app/src/main/assets/legal/`, `THIRD-PARTY-NOTICES.md`, or a shipped
  dependency:** READ ADR 2026-09.6eb6. The in-app Open-source licenses list is `legal/ThirdPartyNotices.kt`,
  pinned to the notices table by `ThirdPartyNoticesSyncTest` and to `app/gradle.lockfile`'s
  `releaseRuntimeClasspath` by `ReleaseClasspathNoticesTest` — a new shipped dependency is a row in both files
  (with the artifact prefixes that claim it) or a `NOT_SHIPPED` entry with its reason, and nothing else makes
  those tests pass. The license texts are committed copies (no Gradle task; `assets/legal/COPYING` is
  byte-pinned to root `COPYING`), and nothing from the build machine — git SHA, timestamp — goes into a
  release's About: the release APK is byte-reproduced by F-Droid, so only the debug variant fills
  `BuildConfig.GIT_SHA`. No license plugin; `legal/InstallSource.kt` is the app's one installer read.
- **When touching `ui/onboarding/`, `ui/Permissions.kt`, `ui/BackgroundBattery.kt`, or `BootReceiver`'s start
  decision:** READ ADR 2026-09.nzpr. Entry is gated on `hasRadioPermissions` alone — the transports assume
  those grants (`@SuppressLint("MissingPermission")` is a lint suppression, not a runtime guard), so the mesh
  never starts without them. `POST_NOTIFICATIONS` and the battery exemption are optional rows on the
  permissions page (`MessageNotifier` self-checks before posting), and `SettingsStore.onboardingSeen` only
  picks which page a returning phone opens on, never whether onboarding shows. The name page writes through
  the shared `ui/components/DisplayNameField`; a grant added to `requiredRadioPermissions` re-wedges the
  front door.
  The battery row (here and in Settings) reads `ui/BackgroundBattery.kt`'s three-position enum, not the bare
  exemption — READ ADR 2026-09.gc3m before touching it: Restricted wins over a stale exemption, and no prompt
  of ours can lift it, so that state always lands on "Open settings".
  The unused-app row beside it reads `ui/UnusedAppPause.kt` (`isAutoRevokeWhitelisted`, API 30+, null below
  so the row hides) — READ ADR 2026-09.54xg: it has no prompt, its default is not an error (`quietHint`), and
  its callback is `openUnusedAppPauseSettings`, not `openSettings`, because Android 11 keeps the switch on a
  page of its own.
- **When touching `ui/DeviceSupervision.kt`, `ui/components/PermissionDeniedDialog`, the `settingsHint` of a
  permission row, or the location / mic / camera gates' denial copy:** READ ADR 2026-09.a8ud. A parent's or
  an administrator's denial (`POLICY_FIXED`) is indistinguishable from "don't ask again" from inside the
  app, so the only signal is *who administers the phone* — Family Link by its pinned profile-owner package,
  else any management signal — and every hint is conditional ("if Settings greys it out as Disabled by
  admin", the platform's own wording). Family Link
  can hold only the classic groups (never Nearby devices), so `supervisedHint` names a parent on the radio
  row only where Location is in it; a managed phone is named everywhere. Family Link's pauses and downtime
  leave the running foreground service alone (device-verified 2026-09-16) — don't add restart machinery
  for them; `android.app.admin.*` / `UserManager` stay confined to that one file (detekt).
- **When touching `ui/yourmesh/`, `mesh/ContributionLedger`, `data/settings/ContributionJournal`,
  `data/peer/MetPeer*`, `ForwardDao.observeCarriedForOthers`, or the `onRelayed` / `onServed` hooks in
  `MeshRouter` / `ForwardSync`:** READ ADR 2026-09.2v2t. The Your mesh screen's numbers are shown to the user
  as things their phone *did*, so a credit happens only at a hand-off (this phone sent someone else's chat
  frame to ≥ 1 peer), once per frame, never for our own frames or a DM addressed to us; "handed straight to"
  is a live-link send and is never worded "delivered". "Nearby" and "met" both derive from
  `MeshController.neighbors` (ADR 2026-09.2ajk) — don't add a second gate. Counters flush on the 60 s tick,
  never per frame, and nothing here leaves the phone.
- **When touching `ui/components/Avatar`, `ui/components/GroupAvatar`, `ui/theme/AvatarTint.kt`,
  `data/message/GroupFaces.kt`, `ui/util/ClusterGeometry.kt`, or the notification avatars in
  `notifications/NotificationAvatars`:** READ ADR 2026-09.j8c7. A photo-less avatar's colour is keyed on the
  **node id** (`avatarTintIndex`, pinned by `ColorSchemeTest`), from a static twelve-hue palette that
  `scripts/gen-avatar-palette.py` generates; under Material You `KnitTheme` harmonizes it toward the
  wallpaper's primary (a 15°-capped Oklch hue turn, tone kept), and the shade draws the same slot the same
  way. Pass a name as the key only for a face with no identity behind it. A photo-less **group** is a
  cluster of its other members (`groupFaceIds`: self out, by node id, at most four, at least two — else a
  people glyph on a disc tinted by the group id) laid out by `clusterCells`, which the shade draws from the
  same cells; renderers never re-sort — READ ADR 2026-09.zapp.
- **When touching `notifications/ConversationShortcuts`, `ConversationShortcutSync`, `ConversationFaces`,
  `ui/OfferedConversations`, `Notifier.retainConversations`, the `ConversationGate` checks in `KnitApp` /
  `NotificationActionReceiver`, or anything that publishes, removes or opens a conversation shortcut:** READ
  ADR 2026-10.jbsa. The launcher's conversation shortcuts are derived from the chat list's universe
  (`visibleConversations`), never hooked per removal path: a pass removes a gone thread's live copy, disables
  a pinned one, enables one that came back and refreshes a changed face by the fingerprint in its extras — and
  never creates one (only a notification's push does; a push stamped after the pass read its inputs wins). The
  messages table is read inside a pass, never watched, and the peer and group tables only for the shortcuts'
  own rows (`ShortcutWatch`, `IN (:ids)`) — never `observeDirectory`, which every profile frame re-runs. A
  route or an inline Reply under a thread the list no longer offers opens the chat list or sends nothing.
  Robolectric's shortcut shadow drops `removeLongLivedShortcuts`: test on `FakeShortcutStore`. Regression:
  `ConversationShortcutSyncTest`, `ConversationShortcutsPlanTest`.
