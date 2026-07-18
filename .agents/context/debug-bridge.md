# Driving the app on a device (debug builds)

> **First obey `rules/devices.md`:** never drive a non-emulator (physical) device without the user's
> explicit go-ahead for that specific session. This file is the *how*; that rule is the *whether*.

Debug builds carry three affordances so an agent can drive the send→verify loop **without** screenshots
or hunting the (unlabeled, state-dependent) send button's pixel bounds. All are **debug-only** — the
bridge receiver and its manifest entry live in `app/src/debug/` (so the release APK has neither), and the
route extra is gated on `BuildConfig.DEBUG`. `app/build.gradle.kts` is untouched.

## Headless bridge

`app/src/debug/.../debug/DebugBridgeReceiver.kt` — an exported `BroadcastReceiver` that calls
`MeshManager` directly and returns JSON. Fire with `am broadcast` (target the package with
`-p app.getknit.knit`); the reply prints on stdout as `Broadcast completed: … data="{…}"` and is also
logged one-line under tag `KnitBridge` (`adb logcat -d -s KnitBridge:I`). **A new action must be added in
*two* places** — the `when` in `DebugBridgeReceiver` *and* the `<intent-filter>` in
`app/src/debug/AndroidManifest.xml`; a package-targeted broadcast for an action missing from the filter is
silently not delivered (the receiver never runs, and you get `Broadcast completed: result=0` with no
`data=` and nothing under `KnitBridge`). Actions:

- `…debug.SEND` — `--es text <body>` + a target: `--es conv <id>` (`nearby` room, a peer node id for a
  DM, or a `g-…` group id) or `--es to <peerNodeId>` (DM shorthand). No target ⇒ broadcast room. Text is
  passed verbatim — spaces/emoji survive (unlike `adb shell input text`) **provided you quote for the
  on-device shell**: `adb` re-parses the command on the device, so a bare `--es text "hi there"` is
  word-split and truncated to `hi`. Wrap the whole remote command in double quotes and single-quote the
  value (see the example).
- `…debug.SENDIMG` — sends a real **image attachment** with no UI (a locked device can't drive the photo
  picker): `--es path <file the app can read>` plus the same `conv`/`to` targeting as SEND and optional
  `--es text`. Stage the file into the app's own storage first (scoped storage — the app can't read
  /sdcard paths): `adb push img.jpg /data/local/tmp/ && adb shell "cat /data/local/tmp/img.jpg | run-as
  app.getknit.knit sh -c 'cat > files/img.jpg'"`, then pass `--es path /data/data/app.getknit.knit/files/img.jpg`.
  Runs the production pipeline (AttachmentStore.ingest → sendChat), and the reply carries the attachment
  `hash` to poll for on receivers.
- `…debug.STATE` — self id/name, transport health, reachable peers, and mesh metrics. Add `--es conv <id>`
  to also dump that thread's latest messages (`--ei limit N`, default 20), each with its `received`
  delivery tick — this is how you **verify receipt on the other device without a screenshot**.
- `…debug.STORE` — dumps the store-and-forward carry set (the **live** rows are the id set the cue-plane
  content digest is folded over; expired-unswept rows are digest/quota/serve-invisible residue awaiting the
  sweep), for diagnosing why two nodes never converge their digests (the churn from a carried-set delta):
  `digestVersion` (what the transport actually cues, read via the same lazy-folding `StoreDigest.current()`),
  `allFingerprint`/`liveFingerprint` (the digest recomputed over all rows vs. non-expired rows — the
  invariant is **`digestVersion == liveFingerprint`, always**; a mismatch is an in-memory-digest drift bug,
  while `allFingerprint` legitimately lags by the expired residue until the sweep), `counts`,
  `expiredIds`, the full `allIds`, and capped per-row detail (`--ei limit N`, default 100). Diff `allIds`
  across devices to find the stranded frame(s): `… STORE | sed -n 's/.*data="//;s/"$//p' | jq -r '.allIds[]'
  | sort` per device, then `comm`/`diff` the files. **`liveFingerprint` matching across devices = converged**
  (`allFingerprint` is NOT fleet-comparable at a TTL boundary — soak oracles must compare `liveFingerprint`).
- `…debug.REACT` — `--es id <messageId> --es emoji <emoji>`. `…debug.HEAL` — nudge rescan/re-advertise.
- `…debug.FLAGMSG` — injects one inbound message **the text moderator flagged** (the UI collapses it behind a
  tap-to-reveal) as the newest row of `--es conv <id>` (default `nearby`), from `--es from <peerNodeId>`
  (default a synthetic sender) with body `--es text <body>`. The radio-less build never receives a real
  flagged message and the marketing seed carries none, so this is the only way to drive the
  `moderation_text_hidden` reveal path (used by `uiauto/ModerationRevealUiAutomatorTest`).

```
# send on A, then confirm it landed on B — no UI, no screenshots. Outer quotes matter: adb re-parses
# on the device, so quote the whole command and single-quote the text (a bare --es text is word-split).
adb -s A shell "am broadcast -a app.getknit.knit.debug.SEND  -p app.getknit.knit --es text 'hi there 😀' --es conv nearby"
adb -s B shell  am broadcast -a app.getknit.knit.debug.STATE -p app.getknit.knit --es conv nearby
# → data="{…,"messages":[{"from":"<A>","body":"hi there 😀","received":…}]}"
```

## Stable resource-ids

The root sets `testTagsAsResourceId` (in `KnitApp`), so `Modifier.testTag`s surface in `uiautomator dump`
as `resource-id="<tag>"` (the bare tag — some Android/uiautomator versions prefix it
`app.getknit.knit:id/<tag>`, so a matcher should accept either form). Tagged so far: `chat_input`, `chat_send`, `chat_row_<conversationId>` (e.g.
`chat_row_nearby`), `chatlist_fab`, `contacts_fab`, `contact_<nodeId>`, `onboarding_grant`,
`onboarding_start`, `profile_name`, `profile_status`, `profile_save`, `chat_group_avatar` (opens group
details), plus screen-root tags on the otherwise-untagged destinations — `screen_diagnostics`,
`screen_blocked_users`, `screen_verify`, `screen_donate`, `screen_share_target`, `screen_profile_details`.
Use these when you must drive the real UI; add more with the same snake_case, screen-prefixed convention.

**Popups don't inherit `testTagsAsResourceId`.** A Compose `DropdownMenu` / `AlertDialog` renders in a
separate window whose semantics root is *not* the `KnitApp` node that sets `testTagsAsResourceId`, so a
`testTag` inside a menu or dialog does **not** surface as a `resource-id` to `uiautomator dump`. Drive
popup contents by their **text** (menu items, dialog titles) or **class** (an editable field is
`android.widget.EditText`) instead — and match a confirm button by *exact* text when its label is a
substring of the dialog title (e.g. the "Block" button under a "Block this person?" title).

## Cold-start navigation

`adb shell am start -n app.getknit.knit/.MainActivity --es demo_route chat/<id>` opens a thread directly
(`chat/nearby`, `chat/<nodeId>`, `chat/g-…`). Cold-start only; for a running instance tap a `chat_row_*`
element instead.
