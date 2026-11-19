---
id: "2026-11.f4d3"
slug: a-meshcore-board-s-name-is-its-owner-s-handle-and-post-consent-follows-it
title: "a MeshCore board's name is its owner's handle and post consent follows it"
date: 2026-11-18
topics: [lora, meshcore, privacy]
---

# ADR 2026-11.f4d3 — a MeshCore board's name is its owner's handle and post consent follows it

Status: Accepted (2026-11-18). Amends ADR 2026-11.gsqu: its setup's `Knit abcd` name write, the name half of
`knitBoard`, and its Stage 6 rule that a post goes out only under Knit's name (lora B1, security B2). ADR 049 still
governs Meshtastic boards, which keep `Knit abcd` / `Knit`.

**What was observed.** On 2026-11-18 a lab phone's user renamed their bound Heltec V4 in the stock MeshCore app. At
Knit's next Ready the session logged `meshcore reconcile dropped: board not set up for Knit`. `knitLooking` /
`knitBoard` required the board's own name to equal `MeshcoreNames.forKey(key)`. So the rename switched off every DM
(`NO_RECORD`), pin, contact teach, removal, path reset, the Ready reconcile and the Public channel (`NOT_SET_UP`), and
nothing on screen said so: the radio screen still read "Set up for Knit". Knit offered no way to rename the board
itself, so a user who wanted a handle on the MeshCore network had only the move that broke Knit.

The name had been doing two jobs:

- **The gate.** It looked like consent. Stage 5b-2 part 2 had already made the persisted setup record the consent (a
  board's name and slot are a claim any client can write), so in the gate the name added nothing the slot and record
  do not cover. `decideConsent` needs the record's digest, the bond and the signed proof, and is read before the first
  Ready.
- **The privacy promise of the Public channel.** That job was real. The firmware writes `"<node_name>: "` in front of
  every channel text post. The Stage 6 consent sheet told the user their posts go out as `Knit abcd: …`. The name
  check is what kept that sentence true when another client renamed the board, possibly to the user's real name.

**What changed.**

- **The board's name is its owner's handle.** Setup writes `Knit abcd` only when this phone holds no record for the
  board (a fresh board, or a reflash's `DifferentBoard`). A re-setup of a board it already set up keeps whatever name
  the board carries. Knit never writes over a name it did not write. A name set in the stock app is adopted at the
  next read of `SELF_INFO`. A new `MeshcoreSession.rename` writes `SET_ADVERT_NAME` from the radio screen, read back
  and published, without touching the setup record: `priorName` stays the name the board had before Knit.
- **The gate is the slot and the consent.** The name leaves `knitBoard`. `MeshcoreDetail.namedDefault` is a byte
  comparison with `forKey(key)`, used for display and the bridge only.
- **A name that would garble a post does not post.** `MeshcoreNames.check` refuses an empty name, more than 31 B, a
  NUL, a C0/C1 control or bidi override, invalid UTF-8, or a `": "`, which makes every receiver split early. One
  definition serves the rename dialog and the post (`NAME_UNPOSTABLE`), so a name another app wrote is refused for
  posting rather than aired.
- **Post consent follows the handle** (replacing B2's mechanism). `SettingsStore.meshcorePostConsentCustomName` is one
  bit, mirroring `meshcorePostConsentPosition`. A consent counts only while the live name is the default or the bit
  is set. Otherwise the sheet re-arms and shows the live prefix. The sheet sets the bit when it was shown with a
  custom name, and so does Knit's rename dialog, which carries the same disclosure. An outside rename never sets it.
  The bit covers one board's names away from its default, and ends when that stops being the case
  (security-reviewer, code pass):
  - the board is seen under its `Knit abcd` default again (`meshcoreCustomNameConsentReset`, on the app's scope): Knit's
    "Use Knit abcd", a setup that names a new board, or another app's rename back;
  - a Forget (`MeshcoreForgetPolicy.forget`) or a bind of another MeshCore board (`BoardBindPolicy.bind`).

  The next name away from the default then asks again, under that name. The post consent itself stands.
  The chain from consent to air has no gap Knit can close:
  - the composer's consent decision passes the name it saw to `MeshcorePublicChannel.post`, which refuses
    `NAME_CHANGED` when the face's differs;
  - the frame carries the name bytes it was queued under;
  - `sendPublic`'s write-time `SELF_INFO` read refuses `NAME_CHANGED` when the board's name differs. That read and the
    channel-text write are two round trips, and the firmware has no compare-and-write: a rename another client lands
    between them (milliseconds) still airs under the new name. The check is not atomic and nothing here makes it so.
- **The prefix comes from the frame's own name.** Cap (`channelTextCap`), price (`OutboundFrame.price` for
  `Destination.Public`, so admission and booking agree) and the own row's `PublicPostKey` all derive from the bytes
  the frame carries. They are never derived from `forKey`, and the name never goes through a `String` round trip, so
  a name cut mid-character still keys as receivers read it. The composer's fallback cap is the floor,
  `MAX_TEXT_LEN − 33` = 127 B.
- **Peer contacts.** `isKnitRecord` drops the name only from the reconcile's `verified` / `overwriteWanted` and
  `runTeach`'s overwrite decision. A peer's handle, written into its taught contact by a heard advert (lora N4), no
  longer costs up to 16 flash writes a Ready. Restore's identification, the erase and the teach read-back keep the
  name.
- **The disclosure says where the handle goes.** The radio screen's dialog names three places, all in the clear and
  all logged by servers that listen:
  - the board's Bluetooth name `MeshCore-<name>`, which the firmware sets at boot, so from its next restart;
  - adverts, which companion v1.18.0 sends only on a client's `CMD_SEND_SELF_ADVERT` (Knit sends none) or the
    board's button, never on a timer;
  - every Public post.

  It adds a position line when the board shares one. The name screen of ADR 2026-11.y4pc runs on a name typed there.

The alternatives:

- **Keep the name in the gate and add a rename button.** A reader reaches for this first. It still breaks the moment
  the user uses the stock app, which is what they did.
- **Re-assert Knit's name at every Ready.** This fights the user's own tool on their own board.
- **Per-handle consent.** Remembering a digest of each consented name would close the residual below. A name is low
  entropy, so that digest could not live in plaintext DataStore and would need the SQLCipher `meshcore_setup` row,
  which means a schema bump. The maintainer chose the bit (2026-11-18).

**What it costs.**

- **A rename from one custom name to another outside Knit is not re-asked.** This is the maintainer's accepted
  residual: from a name the sheet or the dialog covered to another, with the board never back at its default in
  between. The MeshCore Public composer always shows "Posting as `<handle>`" above the field, so the prefix is in front
  of the user while they write every post.
- **A rename no longer hands the board back.** Before, a stock-app rename made Knit inert. Now the next Ready's
  reconcile removes untaught contacts as it always does on a Knit board. Restore (Forget) is the only way back.
- **A handle stays on the board after Forget.** Restore puts `priorName` back only while the board reads `Knit abcd`.
  It reports any other name as left.
- **The handle is the user's on the public band and over Bluetooth, for as long as it stands.** Knit holds it in
  memory only (`MeshcoreHandle`, redacted `toString`s). It never goes into DataStore, a backup, a log line, the field
  log or the bridge's reply (`named: default|custom|unpostable` only).

Guards:

- `MeshcoreSessionTest`: an outside rename leaves the board set up; every `rename` outcome; a re-setup keeps the
  handle; a rename mid-queue ends that post at the write.
- `MeshcoreTraitsTest`: prefix, cap and price over 1, 9 and 31 B; admission equals booking after a rename.
- `MeshcorePublicPostTest` / `MeshcoreHooksTest`: the refusal order, `NAME_CHANGED` and `NAME_UNPOSTABLE`.
- `MeshcoreContactsTest`: a renamed taught contact stays verified.
- `ChatViewModelTest`: consent re-arms for a custom name until the bit is set; a post carries the handle it was
  consented under; clash keys from the live handle.
- `ChatMeshRoomTest`: the sheet's accept reports a custom name; the composer line.
- `LoraRadioViewModelMeshcoreTest` / `LoraRadioMeshcoreScreenTest`: the name row, the dialog, the name screen, the
  bit set on save only.
- `MeshcorePublicLabTest.aPostUnderTheOwnersHandleKeepsOneIdItsEchoDedupsAndItCarriesTheNamesCap`.
- `MeshcoreDirectLabTest.boardsRenamedInAnotherAppStayKnitsAndCarryADm`.
