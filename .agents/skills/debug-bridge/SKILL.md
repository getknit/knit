---
name: debug-bridge
description: Drive and verify Knit on a device or emulator through the headless debug bridge (`am broadcast` to `app.getknit.knit.debug.<ACTION>`, replies as JSON) — send a message on one phone and confirm it landed on another without screenshots, read mesh state, check custody / spool / ratchet convergence, run a LoRa, direct-transfer or Wi-Fi Aware fault trial on a locked lab device, or post a fixture notification for a UI test. Use whenever a task says run it on a device, check it on the phones, lab trial, device trial, soak, convergence check, `KnitBridge`, or names any `…debug.<ACTION>`; and before adding a bridge action.
---

# Debug bridge

Debug builds carry an exported receiver (`app/src/debug/.../debug/DebugBridgeReceiver.kt`) that calls the
mesh directly and answers in JSON, so an agent can drive the send→verify loop with no UI and no screenshots.
This skill is the procedure. The per-action extras and reply fields are in
`.agents/context/debug-bridge.md`, and this file points into it rather than copying it.

## Gate 0: whether you may

Obey `.agents/rules/devices.md`. An emulator is fair game. A physical phone, including every lab phone on
network adb, needs the user's go-ahead **for this task, every time**. Being reachable in `adb devices` is not
permission, and neither is a yes from an earlier task. Never toggle Wi-Fi over adb on a lab phone. Always pin
the target with `-s <serial>` or `ANDROID_SERIAL`: several devices are usually attached.

## Gate 1: a debug build that matches

- The bridge exists only in debug builds. A release or `nonMinifiedRelease` install answers nothing.
- Install with `./gradlew :app:installDebug`. Don't `adb install` the `intermediates/apk` file, which AGP 9
  never rewrites.
- A lab phone may run a feature branch with a newer Room schema than yours, and installing over it half-runs
  the app. Check what the device has before you install, and say what you replaced.
- After an install the package is in the stopped state. `scripts/bridge.sh` delivers to it anyway
  (`-f 0x20`). A hand-written `am broadcast` without that flag gets an empty reply until the app is opened.

## The call

```sh
scripts/bridge.sh <serial|-> <ACTION> [am-extras…]      # JSON on stdout; "-" = $ANDROID_SERIAL
scripts/bridge.sh emulator-5554 STATE | jq '{self, health, reachable}'
scripts/bridge.sh - SEND --es text 'hi there 😀' --es conv nearby
```

The script single-quotes every extra for the device shell, adds `-f 0x20` and `-p app.getknit.knit`, pulls
the `data="…"` out of `Broadcast completed`, and falls back to the receiver's `KnitBridge:I` log mirror
(only lines newer than the call). It exits 1 with a hint on stderr when nothing parsable came back. A
refusal is still a reply, though: a missing extra answers `{"status":"error","message":"missing 'path'
extra"}` with exit 0, so check `.status` (`jq -e '.status == "ok"'`) before reading fields. Extras use `am`'s
own flags: `--es` string, `--ei` int, `--ez` bool.

If you must write the raw form, quote the whole remote command and single-quote the values. adb re-parses
on the device, so a bare `--es text "hi there"` arrives as `hi`:

```sh
adb -s A shell "am broadcast -f 0x20 -a app.getknit.knit.debug.SEND -p app.getknit.knit --es text 'hi there' --es conv nearby"
```

## The core loop

1. `STATE` on each device: note `self.nodeId` and confirm the other side is in `reachable[]`. A peer that
   isn't there is a radio problem, not a delivery one. Read `transports[]` (per-radio `health`, `linked`,
   `nearby`) first, then `meshEnabled`, `meshPaused` and `meshStartDeferred`, any of which leaves a mesh
   that looks alive with no peers.
2. Act on A, e.g. `SEND --es text '<unique body>' --es conv <nearby | peerNodeId | g-…>`. Use a body you
   can grep for (prefix it `soak ` or `burst ` so `PURGE` can clean it up later).
3. Verify on B with `STATE --es conv <id>`: the body appears in `messages[]`. Then, if the test is about
   the ✓✓, read A's own row (`mine: true`) until its `received` flips: that flag is the delivery tick
   coming home to the sender, not something the receiver's row carries. Poll in a bounded loop. Never
   trust one bare `sleep`: delivery order and timing across radios are not promised.

```sh
for i in $(seq 1 30); do
  scripts/bridge.sh B STATE --es conv nearby | jq -e --arg b "$BODY" '.messages[] | select(.body == $b)' && break
  sleep 2
done
```

## Which oracle answers which question

| Question | Action | Read |
|---|---|---|
| Did it arrive? | `STATE --es conv <id>` on the receiver | the body in `messages[]` |
| Did the ✓✓ get home? | `STATE --es conv <id>` on the sender | its own row's (`mine: true`) `received` |
| Did a reaction or typing cue land? | `STATE` on the receiver | `messages[].reactions`; `typing` |
| Do two nodes carry the same custody? | `STORE` on each | `liveFingerprint` equal across devices (never `allFingerprint`); `digestVersion == liveFingerprint` on each |
| Which frame is stranded? | `STORE` | `allIds` sorted per device, then `comm` between devices |
| Is the Internet plane converged? | `SPOOL` | per scope `local == spool`, `invalid` 0, `lastError` empty, `connected` |
| Is a DM session healthy? | `RATCHET` | `hasSession` and `confirmed`; `peerPrekeyPinned` before any reset |
| LoRa range or airtime? | `LORA` | `loraSent`/`loraReceived`, `loraNakByReason` |
| Why didn't a transfer start? | `XFER` | `refusal` **first**, then the live transfers |
| Is the mesh paused? | `STATE` | `meshPaused` / `meshPausedUntil` |

Each row's caveats (the `retiring` and `accounted` spool fields, the TTL edge on `allFingerprint`) are in
the context doc. Read its bullet before you call a result converged or diverged.

## Action index

Extras and reply fields: the action's bullet in `.agents/context/debug-bridge.md` under *Headless bridge*.

- **Messaging:** `SEND`, `SENDIMG` (stage the file into app storage with `run-as` first), `REACT`,
  `TYPING`, `MKGROUP`, `LEAVE`, `PURGE` (clean soak traffic off every phone).
- **Mesh state:** `STATE`, `STORE`, `HEAL`, `PAUSE`, `RESUME`, `BLECAP`, `PHY` (the Coded PHY experiment).
- **Planes:** `SPOOL`, `COMMONS`, `LORA`, `LORATX`, `LORAPROV`, `XFER`.
- **Identity, crypto, data:** `INTRO` (contact cards), `RATCHET`, `BACKUP`.
- **Fault injection and trials:** `NANFAIL`, `NANSTORM`, `NANREFUSE`, `NANICM`, `NANDIAL`, `NANINIT`,
  `NANMSG`, `MODEL`. Each has a negative control in its bullet; run it too.
- **UI-test fixtures:** `FLAGMSG`, `MSGNOTIF`, `REQNOTIF`, `REVIEW`.
- **Media and packaging probes:** `WEBPPROBE`, `WEBPCONV`, `WEBPCHECK`, `SHAREAPK`.

## When it answers nothing

- `result=0` with no `data=`, and nothing under `KnitBridge` usually means the action isn't in the debug
  manifest's `<intent-filter>`, or is misspelt. It can also mean a non-debug build. Without `-f 0x20` it can
  be a stopped package.
- A text cut off at its first space means it went through a raw `adb shell` without the inner quotes.
- `missing 'path' extra`, or a path the app can't read: scoped storage means the app can't read `/sdcard`
  or `/data/local/tmp`. Copy the file in with
  `adb shell "cat /data/local/tmp/x | run-as app.getknit.knit sh -c 'cat > files/x'"` and pass
  `/data/data/app.getknit.knit/files/x`.
- `error` from `COMMONS` means the build hides the commons (`BuildConfig.COMMONS`).
- A reply that looks healthy while nothing moves: read the oracle's caveat fields (`lastError`,
  `refusal`, `declined`). Most gates in the mesh return silently, and these fields exist to tell them apart.

## When the UI is unavoidable

Use the stable resource-ids, cold-start navigation (`--es demo_route chat/<id>`), and the rule that popups,
dialogs and bottom sheets don't surface their test tags. These are the *Stable resource-ids* and *Cold-start
navigation* sections of the context doc. Prefer a bridge action over driving a sheet. `XFER` exists partly
so nobody has to drive the consent sheet.

## Adding an action

1. A branch in the `when` in `DebugBridgeReceiver` and an `ACTION_` constant.
2. An `<action>` in the receiver's `<intent-filter>` in `app/src/debug/AndroidManifest.xml`. Without it the
   broadcast is never delivered, and nothing errors.
3. A bullet in `.agents/context/debug-bridge.md` covering the extras, the reply fields, what the oracle is,
   and the negative control if it injects a fault.
4. A word in this skill's action index.

Check for drift after any change. This must print nothing:

```sh
comm -3 <(grep -oE '"app\.getknit\.knit\.debug\.[A-Z]+"' app/src/debug/java/app/getknit/knit/debug/DebugBridgeReceiver.kt | tr -d '"' | sed 's/.*\.//' | sort -u) \
        <(grep -oE 'debug\.[A-Z]+' .agents/context/debug-bridge.md | sed 's/.*\.//' | sort -u)
```
