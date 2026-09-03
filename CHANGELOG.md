---
changelog: "0.1"
product:
  name: Knit
  vendor: Knit
  homepage: https://getknit.app
  id: knit
  description: Offline, serverless, end-to-end-encrypted mesh messenger for Android.
  platforms: [android]
  category: Communication
document:
  updated: 2026-08-31T05:12:27Z
  coverage: partial
  canonical: https://github.com/getknit/knit/blob/main/CHANGELOG.md
  locale: en
  older: https://github.com/getknit/knit/releases/tag/v2.1.0
---

# Knit changelog

## Unreleased

### Added

- Tell people nearby you're open to chat, from Settings. When someone in radio range has said the same and
  you two have never messaged each other, you both get one nudge to say hi in the Nearby room, and their
  profile shows the flag.
- Send any kind of file in a direct message or group chat, under the same 8 MB limit photos already
  have. A received file shows its name and size, and saves wherever you point Android's file picker;
  the Nearby room stays photos only.
- React with any emoji. Long-press a message and tap the "+" beside the quick reactions to browse or
  search the whole set, and the quick row starts remembering what you actually use instead of the same
  six forever.

### Changed

- Aliases are three words now, like ReallyJoyfulFerret. Settings and a contact's profile show the new one.
- Knit no longer feeds what is on screen to Android's content-capture service (the "app content"
  suggestions). An end-to-end-encrypted messenger has no business handing its screen text to another
  process, and long lists got smoother for it.
- Chats now say what happened in them. A quiet centered line marks a contact changing their name ("Sam is
  now Sam Vimes") or photo and a group being renamed or created, with no notification, no unread badge and
  no bump up your chat list.

### Fixed

- Knit no longer gets stuck showing a blank screen. Opening it at the moment it was closing could leave it
  drawing nothing, with no way out but force-stopping it; it now notices and rebuilds its own screen.
- Phones that only relayed your messages no longer count as nearby. The online dot, chat list, group
  picker and nearby count now show only what your own radios can currently see.

## [2.4.0](https://github.com/getknit/knit/releases/tag/v2.4.0) — 2026-08-31T05:12:27Z

> Voice notes, and optional Internet relays for when nobody is in radio range.

### Added

- Knit puts less on the air. Between two current phones the delivered tick — for a private message, a
  reaction, a nearby-room or group post — now crosses the Wi-Fi Aware fast lane in one packet instead of
  two, a short message likewise, and a profile in two instead of three. The frame travels in a compact
  form the receiver unpacks before checking the signature, so nothing about what is signed changes, and
  an older phone keeps receiving exactly what it did.
- Two people with the same name are now told apart. When someone nearby uses a name Knit already knows,
  both show their alias after it — the two-word name Knit gives every device, like "Sam (JoyfulFerret)" —
  in the room, the chat list, Contacts, group members and notifications, and it goes away again when the
  names differ. Your own alias is now shown in Settings under your name, and on a contact's profile, so you
  can tell someone which Sam you are. Typing an alias after "@" finds the right person to mention.
- Add a contact from a link. Too far apart to scan each other's code? Share your contact link (menu ›
  Add contact › Share link) over any messenger, and add someone from theirs — tap it, share it to
  Knit, or paste it into Add contact. Each of you ends up with the other's key pinned and in Contacts, no
  message needed; with Internet relays on, the two of you also meet at a relay before you have ever
  exchanged a message. A link is not a scan: compare safety numbers over a call if it matters who is on
  the other end.
- Voice notes. Hold the microphone in a chat to record, slide up to keep recording hands-free, and slide
  away to cancel. You hear the recording back before deciding to send it, and can add text to it like any
  other attachment. Voice notes travel exactly the way photos do — encrypted end to end, carried by nearby
  phones when you are out of range of each other, and by your Internet relays if you have turned them on.
  They are available in direct messages and group chats. They are not offered in the Nearby room, because
  Knit checks images for explicit content before they reach you and there is no equivalent check it can run
  on speech, so it does not put unchecked audio in front of strangers.
- Group delivered-ticks (the ✓✓ on your own group messages) now survive you being away: members'
  receipts are batched, encrypted, and carried by the mesh — and the Internet relays, where enabled —
  until they reach you, just like the messages themselves.
- Knit can now use Internet relays, which you turn on yourself. Knit remains a messenger that works
  with no internet and no account, but a message to somebody out of radio range used to wait for the
  two phones, or a chain of phones between them, to meet. With relays on, Knit parks a sealed copy on
  a relay server, which hands it over the next time the other phone connects. A one-time explanation
  spells out what a relay can see — your IP address, when you send, and roughly how much — and what it
  cannot: your messages or photos, who you are talking to, or who else is in a group.
- A Settings screen for relays lists the ones you use, adds and removes them, and reports each one as
  connected, still connecting, or why it refused. Knit ships with one relay already listed and unused;
  it stays inert until you turn the feature on, and removing it sticks.
- Each relay also has its own switch, so you can stop using one without deleting it. Deleting forgets
  its address and any access token it came with, which for a private relay cannot be undone; switching
  one off just stops sending to it. The main switch still decides whether sealed copies leave your phone
  at all — a relay's own only narrows which of them carry.
- Group chats travel over relays as well as direct messages, without anyone learning from the relay
  that a group exists or who is in it.
- Photos travel over relays too, in sealed pieces. A photo waits while a phone that can carry it is
  still in radio range, so an ordinary in-person exchange never touches a relay. Where a relay cannot
  take it — no photo support, or the photo is larger than any relay in your list will hold — the
  message says "Nearby only" rather than reporting a failure: it still arrives over Wi-Fi or Bluetooth
  as it always did.
- Changes to your name, status, or photo now reach people you have only ever met across a relay. They
  previously spread by radio alone, so such a contact kept whatever it saw when you were last in
  range.
- A conversation says how it is being carried. A globe appears beside the double tick when the other
  phone answered over the internet, and beside a message that arrived that way; the connection header
  says when the radios are dark and a relay is carrying the conversation. The Nearby room is exempt by
  design — it is public and unencrypted, so it is never uploaded, and says so.
- Diagnostics gains an Internet relays section: what has been sent and received over them, photo
  pieces moved, photos left to the radios, and any errors a relay reported.
- A fresh install now says what to do next. The chat list always has the Nearby room in it, so it never
  looked empty even when there was nothing to open; a short card under that row now names the two ways
  in — broadcast to everyone in range, or add one person. It disappears on its own as soon as you have
  a conversation to open.

### Changed

- Adding someone is one screen again. "Verify contact" and "Add by link" were two entries in the menu that
  did halves of the same job; they are now a single **Add contact** screen — your code to be scanned, a
  scanner for theirs, your link to send, and a box to paste the link they sent you. Nothing about how a
  contact is added changed: a scan still verifies the key on the spot, and a link still asks you to compare
  safety numbers before you trust who is on the other end.

- Knit is a smaller download and starts faster. The build strips out the parts of its bundled libraries
  the app never actually calls — about four megabytes of code that was being shipped and loaded for no
  reason. Nothing about what Knit does changed; there is just less of it to install and less for your
  phone to hold in memory while it runs.

- Nearby phones relaying your messages no longer learn what kind of attachment one carries. A photo and a
  voice note used to be told apart by a label sent alongside them in the clear; that label now travels
  encrypted with the message, where only the person you sent it to can read it. Relays still see roughly
  how large an attachment is, which they need in order to carry it for you.

### Fixed

- The last fix for phones that shut Knit down seconds after opening did not hold on the phone it was
  written for. Knit was slowing its retries down correctly, then throwing that away every time the phone
  announced anything about Wi-Fi Aware — and a phone that cannot run Wi-Fi Aware announces something after
  every refusal, so the two fed each other and Knit ended up asking hundreds of times a second. It now
  tells a radio genuinely coming back from a phone just repeating itself, never asks more than once every
  three seconds no matter what, stops entirely well before Android would step in, and remembers that it
  gave up so a restart does not start the whole thing over. Bluetooth carries the mesh throughout.
- Knit no longer gets stuck restarting on a phone where the on-device checking model won't run. The model
  that screens messages and photos runs in native code, and on unusual hardware it could close the app a
  few seconds after opening, every time, with nothing to show for it. Knit now notices that pattern, turns
  that model off, and carries on — Diagnostics says so, and offers to try it again. While it is off,
  messages in the Nearby room are still checked against the word list; direct messages, group chats and
  photos are not checked.
- Knit no longer crashes when Android restarts the mesh out of sight. Phones that run short of memory —
  and some that simply prune background apps hard — stop Knit while it is off screen and start it again a
  moment later. Since Android 12 that restart is not allowed to put the mesh notification back up, and Knit
  was treating the refusal as a crash. It now stands down quietly and picks the mesh up again the next time
  you open the app or restart your phone. To have the mesh survive on its own instead, turn on **Allow
  background battery use** during setup or from Profile.
- A relay you have configured can no longer flood the app's memory. Knit now holds only what it
  actually asked a relay for, ignores anything else the relay volunteers, and treats the relay's
  stated size limits as a claim to be capped rather than a fact. Relays were always untrusted with
  the contents of your messages; now they are untrusted with how much of your phone they can use.
- A notification can no longer be used to exhaust your phone's memory. When someone messaged you, Knit
  opened their profile photo at whatever size the picture claimed to be — and a picture that arrives from
  someone else is theirs to choose. A tiny file can unpack into hundreds of megabytes. Knit now opens every
  photo at the size it actually needs, and remembers the ones it has already opened, so a busy conversation
  does the work once instead of once per message.
- Opening "New message" no longer flashes "no contacts yet" before your contacts appear. The list takes
  about a second to read on a cold start, and the screen could not tell "still loading" from "nothing
  here", so it drew the empty state over the transition. It now shows placeholder rows that fade into the
  real ones.
- Phones a few miles away, and phones with no Wi-Fi Aware radio at all, no longer show up as directly
  connected over Wi-Fi Aware. A relayed message carries its original sender's name, and Knit was crediting
  that name with the link the message actually arrived on. It now credits the phone that put the message
  on the air, which is the only sender Wi-Fi Aware really tells it about.
- Knit no longer re-downloads the same messages from a relay over and over. Relays hold a sealed copy
  longer than a phone keeps one, so for the back half of that window Knit kept asking for messages it had
  already delivered and thrown away, on every reconnect, and never counted a conversation as caught up.
  It now remembers that it dealt with them.

## [2.3.1](https://github.com/getknit/knit/releases/tag/v2.3.1) — 2026-08-27T19:50:27Z

> One fix, for phones that were being shut down seconds after Knit opened.

### Fixed

- Knit no longer disappears seconds after you open it on a phone whose Wi-Fi chip will not run Wi-Fi
  Aware. Some phones report Wi-Fi Aware as available and then refuse to hand it over while Wi-Fi is
  connected, and Knit kept asking every three seconds, forever — each attempt costing the system a
  little memory it never got back. Android eventually stopped the app for asking too often: within a
  minute of opening it, over and over, with nothing in the logs to explain why. Knit now slows those
  attempts down and gives up on a radio that keeps refusing, then picks it up again if Wi-Fi Aware
  becomes usable. Bluetooth carried the mesh the whole time, which is why messages still went through.

## [2.3.0](https://github.com/getknit/knit/releases/tag/v2.3.0) — 2026-08-24T05:56:14Z

> Forward secrecy for direct messages and group chats, encrypted delivery receipts and reactions,
> taking a photo without leaving the chat, and a crash report you can read before deciding to send it.

### Added

- Direct messages between updated phones are now forward-secret. Each conversation derives its own key
  material from a prekey published in your profile, then re-keys as the conversation goes back and
  forth, and drops the old keys. Someone who records traffic today and gets hold of a phone later
  cannot read the earlier messages.
- Group chats gain the same property. Every member drives their own key chain and hands its current
  seed to the others inside encrypted direct messages. A group's info screen says whether the group is
  running the new scheme or still waiting on somebody to update.
- Delivery receipts and reactions are encrypted. Until now they crossed the mesh readable by any phone
  relaying them, so an onlooker could tell when a message reached you, and who reacted to which
  message with what. They now travel sealed and look like ordinary messages on the wire.
- You can take a photo without leaving the chat. Long-press the attach button — the round button beside
  an empty message field — and a viewfinder opens in place of the conversation. The shot is sent as an
  attachment without ever being written to your gallery or handed to another app.
- Long-pressing a message offers "Message info": who sent it, when, whether it has been delivered and
  how it travelled, and the full list of who reacted with each emoji, which the reaction tally on the
  bubble does not show.
- Message info also says *when* a message reached you, on your own phone's clock, beside the time its
  author says they sent it. The gap between the two is how long the mesh took to carry it — sometimes
  seconds, sometimes hours if it came the long way round through someone else's phone. A direct message
  you sent shows the moment it landed on theirs. Messages already on your phone have no arrival time and
  never will: Knit wasn't watching for it then, and would rather say nothing than guess.
- Knit records its own crashes on the device. A Diagnostics row shows the report, with identifiers,
  file paths and contact names stripped out of it, and you decide what happens next — copy it, share
  it, or open a prefilled bug report. Nothing is uploaded, and there is no code in Knit that could
  upload it.

### Changed

- Chats with a phone on an older version keep working, on the previous encryption scheme. A group
  falls back the same way while any one member has not updated, and moves over on its own once
  everyone has.
- The Profile entry in the overflow menu is now called Settings, since the screen also holds settings
  that are not about you, such as content filtering.

### Fixed

- Saving a received photo to your gallery now saves the photo. Attachments in direct messages and
  group chats are stored encrypted, and the export wrote those encrypted bytes to Pictures/Knit under
  an image name — an unopenable file, reported as a success.
- Editing your name, status or photo now reaches the other phone straight away. An edit made soon
  after first contact was re-sent under a label the mesh had already seen, so it was discarded on
  arrival and stayed invisible for up to twelve hours.

## [2.2.3](https://github.com/getknit/knit/releases/tag/v2.2.3) — 2026-08-13T02:38:41Z

> A themed launcher icon, and a launch screen that follows the system light/dark theme.

### Added

- The launcher icon supplies a monochrome layer, so on Android 13 and newer it takes part in themed-icon
  colour schemes instead of falling back to a generic shape.

### Fixed

- Starting Knit on a dark-themed device no longer flashes a near-white screen before the app draws. The
  launch window and splash background now track the system theme, in lockstep with the in-app colours.

## [2.2.2](https://github.com/getknit/knit/releases/tag/v2.2.2) — 2026-07-31T22:22:03Z

> Fixes a crash in "Scan their code".

### Fixed

- "Scan their code" no longer crashes when the camera opens. The scanner has been rebuilt on CameraX;
  the library it used before could crash the app on devices whose camera reports one frame size and
  delivers another, which is why it worked on some phones and not others. Decoding now treats any
  unexpected frame as a frame without a code in it.
- Declining the camera permission, or running on a device with no camera, now shows an explanation
  instead of a blank screen. Showing your own code still works either way.

## [2.2.1](https://github.com/getknit/knit/releases/tag/v2.2.1) — 2026-07-23T03:08:44Z (routine)

> The first release available on F-Droid. A build-only change with no code or feature differences
> from 2.2.0.

The release APK no longer embeds Android's "dependency metadata" signing block, which F-Droid does
not permit and which was not reproducible. The app behaves identically to 2.2.0; upgrading matters
only if you want the build F-Droid distributes.

## [2.2.0](https://github.com/getknit/knit/releases/tag/v2.2.0) — 2026-07-22T22:20:29Z

> The first release published on F-Droid, and the first built reproducibly: F-Droid rebuilds Knit
> from source, byte-compares its result against ours, and ships ours.

### Added

- The Support Knit screen offers Liberapay alongside Ko-fi.

### Changed

- Release builds are reproducible. F-Droid rebuilds this release from source and byte-compares it
  against the APK on Knit's GitHub Releases page, then distributes ours — so one signed APK serves
  F-Droid, a direct download, and Knit's own offline "share the app" feature alike, and a phone
  handed Knit over the mesh can still take updates normally.

## About this file

This heading does not match the release-heading grammar, so a consumer skips it, the same way it
skips `## Unreleased` above.

New entries are held to `.agents/rules/changelog.md`: two sentences, about forty words, run through the
`humanizer` skill. Sections below `## Unreleased` are a published record and are never restyled to it.

This changelog follows the provisional changelog standard drafted at
[whatsnew.fyi](https://whatsnew.fyi/product/knit) — YAML frontmatter, one `##` heading per release
newest first, and [Keep a Changelog](https://keepachangelog.com)'s six categories. Releases before
2.2.0 are on the [releases page](https://github.com/getknit/knit/releases), which is what
`document.older` points at.
