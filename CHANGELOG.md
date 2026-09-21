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
  updated: 2026-09-16T07:24:35Z
  coverage: partial
  canonical: https://github.com/getknit/knit/blob/main/CHANGELOG.md
  locale: en
  older: https://github.com/getknit/knit/releases/tag/v2.1.0
---

# Knit changelog

## Unreleased

### Added

- On a phone a parent or an organisation manages, Settings and Diagnostics now say so, and a permission they
  turned off names who can allow it instead of sending you to a switch you can't flip. Voice notes get the
  same Open settings dialog as the location pin.
- Scroll up through a chat and a small arrow now sits in the bottom corner to take you straight back to the
  newest message. It shows once you are a few messages up and goes away when you reach the bottom.
- Android takes an app's permissions back after a few months without use, and Knit would then have to ask
  for them again. The permissions page and Settings now show that switch beside the battery row, with a
  button to the page where it turns off.
- A Meshtastic user who messages your Knit board directly now gets one automatic reply saying nobody reads
  it and where to find Knit. The board answers each person once a day, and only if you set it up for Knit.
- Settings now has Backup and restore: your identity, contacts, groups and messages go into one encrypted
  file locked by a recovery key Knit shows once. Restore it on a new phone from the welcome screen, and use
  the backup on one phone at a time.
- If the same backup ends up on two phones, each one now says so and offers Sign out here, which clears this
  phone so it starts over with a new identity. Dismiss the notice instead if the other phone is already gone.
- The mesh notification now offers Pause 15 min and Pause 1 hour beside Stop, so another app can have
  Bluetooth for a while without you switching Knit off. The chat list shows when the pause ends, and the mesh
  comes back by itself.

### Changed

- Your phone spends less battery on a quiet mesh. Each message crosses a Bluetooth link once rather than
  twice, and a phone left alone with the screen off stops waking its Wi-Fi Aware radio every few seconds.
- A relay you have set up asks for far less while nothing is happening, and one that has gone quiet is
  tried every fifteen minutes rather than every minute. On mobile data your phone now checks in with it
  every four minutes, though the relay still checks in on its own schedule.
- Knit loads its on-device content filter when you open the app or someone comes into range, rather than
  on every background restart. A message that arrives twice is checked once.
- Knit unloads its on-device content filter after ten minutes without a message and loads it again for the
  next one, so the app holds about thirty megabytes less while nothing is happening. The first check after
  a quiet spell takes a second longer.
- A relay you have set up now learns about a new contact, or a chat that just connected, as it happens
  rather than up to fifteen seconds later. Idle, your phone looks over what it shares with the relay once
  a minute instead of four times.

### Fixed

- A photo sent over Bluetooth could cross the air three times, because every nearby phone offered its copy
  while the first was still arriving. A phone now asks for a picture only while nothing is on the way, and a
  neighbour sends one only when asked.
- A phone on a relay with nearby phones could get a message by radio and from the relay a split second
  apart, and took the second copy as proof a neighbour had passed it on. It then skipped its own hop, and
  the phone behind it waited for the next re-offer.
- On some phones the Wi-Fi dropped every time Knit tried to open a Wi-Fi Aware connection to a neighbour.
  After the third drop Knit stops opening them from that phone and says so in Diagnostics, where you can let
  it try again; nearby phones can still connect to it.
- A public Wi-Fi that Android accepted but that silently blocked your relay left it showing as connected
  while nothing got through. The relay now reads as unreachable, and Knit reconnects as soon as your phone
  moves to another network.
- A photo waiting in a chat only ever said it would appear when a nearby device had it, even when your
  relays were live. It now also says whether a relay can bring it, or that the connected relays carry
  messages only.
- Your phone could still upload a photo to your relay right after the phone next to you passed it over
  Bluetooth or Wi-Fi, depending on which arrived first, the message or the photo. It now holds that upload
  until you are apart.
- A name or status you set moments after meeting someone could show up blank, or as the old one, on their
  phone until the next day. It reaches them right away now, and a relay no longer gets stuck syncing on it.
- A link shared into Knit from another app usually went out without its preview, because you tapped Send
  before the preview had loaded. Sending now waits a few seconds for it.
- With a LoRa board paired, Nearby and direct messages never got a link preview at all. They now attach
  one the way they attach a photo, and someone reading over the board alone sees the link without it.
- A group started while one of its members could only be reached through your relay never showed up on
  their phone until they met someone by radio. Now the group and its first message arrive over the relay
  like any other chat.
- On Android 10, 11 and 12, Knit stopped looking for phones over Wi-Fi as soon as you left the app. It
  keeps looking in the background now, and tells you when Android will only allow that with Knit open.
- Unless you had turned off battery optimisation for Knit, the mesh stayed down after a reboot until you
  next opened the app. It now comes back at boot either way.
- Now and then Android refused to let a phone accept Wi-Fi connections from its neighbours, and Knit asked
  again hundreds of times a second until something else reset the radio. It now waits between tries and
  resets the radio itself after a few.
- On a slower phone, Knit could hang for twenty seconds after a reboot or a background restart, and Android
  offered to close it. The mesh now starts without holding up the app.
- A phone drifting at the edge of Wi-Fi range made yours resend your profile every half minute, and the
  phones that heard it threw every copy away. It now goes out to each phone once, and again only after ten
  minutes.

## [2.6.0](https://github.com/getknit/knit/releases/tag/v2.6.0) — 2026-09-16T07:24:35Z

> Search every chat, send big files phone to phone, and see what your mesh carried.

### Added

- Knit now supports Material You dynamic colors, taking its palette from your wallpaper the way Android's
  own apps do. The Dynamic colors switch in Settings stays off until you turn it on, on Android 12 or
  newer, and the online dot and verified shield stay green either way.
- You can send where you are from the menu at the top of a chat. Knit reads your position only between
  that tap and the send, and the other phone gets a card that opens in any maps app. Android asks for the
  permission the first time you use it, never before.
- Settings now lets you set Knit to light or dark instead of following your phone. It follows your phone
  until you pick one, and the choice appears on Android 12 or newer.
- A message you start typing and don't send is still there when you come back to that chat, and the chat
  list shows it as "Draft:" until you send it. Every chat keeps its own.
- Send someone nearby a file that is too big to attach, from the menu in a direct message. The two phones
  connect to each other over Wi-Fi for the transfer, and the file arrives in their Downloads folder.
- Tap the magnifier at the top of the chat list to find a chat, a contact, or a message by what it said.
  Open a message from the results and its chat lands on that message. A stranger's message request stays
  out of the results until you accept it.
- The Support Knit screen now offers GitHub Sponsors alongside Ko-fi and Liberapay. Pick whichever you
  already have an account on.
- Tap the nearby count above the chat list for a new Your mesh screen: messages your phone passed along or
  handed straight to the person they were for, what it is carrying right now, and how many people it has
  met. Every number is counted on your phone and stays there.
- Tap a relay invite link and Knit adds that relay and turns relays on for you, once you have read what the
  relay will see. A contact card that names a relay you don't use now offers to add it the same way.
- Diagnostics now shows which version a connected relay is running. A relay that publishes nothing about
  itself, including one that is not Knit's own relay software, shows no version.
- Settings has a new menu with an About screen and the open-source libraries Knit is built from; tap one to
  read its license, even offline. About also shows the version, the build and where Knit was installed from,
  with a Copy button for bug reports.

### Changed

- Setting up Knit is now three pages: what it does, your name, and the permissions it needs. Only the
  nearby-devices permission gets you in; you can leave notifications and background battery use for later.
- Direct messages and group chats now keep every message, however long they run. Knit used to drop the
  oldest once a chat passed 5,000, and the Nearby room and a paired radio's channel still keep only the last
  30 days.
- The chat list now opens and updates without going back through every message you have stored. A phone
  with years of history gets to its chats as fast as a new one.
- Your name, photo and status moved to their own screen. Settings still sits in the chat list's menu, and
  the first row on it opens your profile.
- A contact's profile now opens with whether they're online, verified, blocked or open to chat, and Message
  sits at the top rather than below the technical details. Under that it lists the groups you share and when
  you first met.
- Your own profile is now grouped under What people see and This device, and Save moved up to the top bar.
  The Open to chat switch still takes effect the moment you flip it.
- Menus, cards, dialogs and sheets now match the rest of the app. They had been drawing a grey-purple from
  Material's defaults that never sat right next to Knit's coral.
- A file, link or location card in a message now carries its own colours instead of the bubble's. The same
  card had looked different depending on whether you sent the message or received it.
- On Android 11 and older, a list pulled past its end now glows Knit's coral. It had been a flat grey that
  belonged to nothing else on the screen.
- A contact with no photo now gets their own colour behind their initial, and Knit picks the same one on
  every phone and in your notifications. A chat list full of people without photos no longer looks like a
  row of identical grey circles.
- A group with no photo now shows the people in it, up to four faces in one circle, where a grey icon used
  to sit. Its notifications carry the same faces, and a group with only one other person gets a coloured
  group icon instead.
- Enter in the message field now starts a new line instead of sending. The send button sends, so you can
  write a message across several lines before you tap it, and on a hardware keyboard Ctrl+Enter sends too.
- The menu that pops up over a text field no longer offers Autofill. Android puts it on any box that could
  hold a saved password or address, and nothing you type into Knit is one.
- A stranger who floods the Nearby room can no longer push out what you and your contacts posted there:
  their oldest posts go first, and each stranger keeps at most 200. A phone in range can also only pass Knit
  so many room posts a minute.
- Settings and the permissions page now say when Knit's battery use is set to Restricted, and that the mesh
  stops as soon as you leave the app. Both offer the system page where you can change it.

### Fixed

- Diagnostics now lists Wi-Fi Aware on a phone that has no such radio, or one that needs Android 12 for it,
  and says which. That row used to be left out, which looked the same as Wi-Fi being switched off.
- A LoRa radio you never paired now reads "Not in use" in Diagnostics, and a paired one that is out of reach
  says so. Both used to sit under the same grey dot with "0 heard".
- Knit no longer repeats over your LoRa radio a message that Bluetooth or Wi-Fi has already delivered. If
  you have a board paired, its limited airtime now goes to the messages that actually need the range.
- Restarting Knit no longer makes a paired LoRa radio repeat what it has already sent. Knit now remembers
  how much airtime it has spent, so a restart can't hand the radio a fresh allowance to burn through.
- Leave a group and start it again with the same people, and the others get your messages once more. The
  chat shows you rejoined, and nobody but you can put you back in.
- Someone two phones away from you now shows up as nearby as soon as they arrive. Before, the phone in the
  middle sometimes held on to their details, so you saw them only after a minute or once they had sent
  something.
- Knit no longer crashes if Android is backing it up at the moment the mesh tries to restart. The restart
  now waits for the backup to finish, and the mesh comes back on its own rather than staying off until you
  next open Knit.
- The delivered tick on a Nearby post now reaches you over a paired LoRa radio or a relay, so you no longer
  wait for the other phone to be back on Bluetooth or Wi-Fi. Knit gives a reply a minute to carry it first,
  then sends it on its own.
- A delivered tick from someone out of range now reaches you even if you have no LoRa radio of your own. A
  phone beside you that has one passes it the last step, instead of dropping it as it used to.
- If you use a relay, Knit no longer spends your LoRa radio's airtime on direct messages the relay is already
  carrying to that person. The radio still carries the Nearby room, and messages to anyone the relay can't
  reach.
- A new group's first message now opens even when Knit was shut down on your phone between the key arriving
  and the group itself. It used to stay unreadable for up to fifteen minutes after you came back.
- A contact you could only reach through an Internet relay or a LoRa board showed as Offline on their
  profile. The status line under their name now says Reachable via relay, and Online still means your own
  radios can see them.
- A contact whose phone dated a profile, reaction, group name or photo far in the future could keep that
  version stuck on your phone for good. Knit now caps such dates at a few minutes past its own clock, the
  way it already does for messages.
- A relay that sends junk or stops answering after it connects no longer ties Knit up for minutes at a
  time or keeps a photo from ever arriving through it. Relays and Diagnostics now say what went wrong
  instead of showing it as connected.
- A phone in a group chat kept sending itself a sealed key message every hour or two, and every phone
  around it carried the copies. Knit no longer mistakes its own profile for a contact's, which is what
  started the loop.
- Knit no longer crashes when you open it again within a minute of leaving, if you have set its battery
  use to Restricted. Android stops the mesh when a restricted app leaves the screen, and opening Knit
  starts it again.
- Blocking someone no longer makes your phone keep re-syncing with the phones around it for as long as the
  block stands, which was costing radio time and battery. Anything they sent while blocked stays hidden
  after you unblock them.
- Adding a relay now works whether you type its address as wss:// or WSS://. Knit used to reject the
  capitalised one the same way it rejects an address it cannot use, with nothing to tell the two apart.
- A photo from the Nearby room that reached you over a LoRa radio while no other phone was near now loads
  once one comes back into range. It used to sit loading until you restarted Knit.
- A photo that reached you through a relay now goes on to the phone next to you that asked for it. That
  phone used to wait until it thought to ask again, which could be half an hour.
- A photo sent through a relay now goes up as soon as it's clear the radios didn't deliver it, instead of
  up to fifteen minutes later. One the two phones already passed over Bluetooth or Wi-Fi is no longer
  uploaded to the relay as well.
- Unpairing a LoRa board now always reaches your contacts. It sometimes didn't, so their phones could go
  on treating that radio's posts as yours and sending you messages through it.

## [2.5.1](https://github.com/getknit/knit/releases/tag/v2.5.1) — 2026-09-11T07:59:17Z

> A LoRa radio that stopped sending, and a new group's first message that never arrived.

### Fixed

- A paired LoRa radio no longer stops sending until you reopen Knit. Messages could pile up unsent while the
  radio still showed as connected, and nothing moved again until the app was restarted.
- The first message in a new group now reaches every member. It used to get lost on a member's phone, and
  the sender never saw a tick, because Knit sent the key for it a moment before it sent the group itself.

## [2.5.0](https://github.com/getknit/knit/releases/tag/v2.5.0) — 2026-09-08T07:54:21Z

> A Meshtastic radio for reaching far past Wi-Fi and Bluetooth, and files of any kind.

### Added

- Pair a Meshtastic radio and Knit will use it. The Nearby room and your direct messages then reach contacts
  well past where Wi-Fi and Bluetooth give out.
- Turn off the Meshtastic room in LoRa settings if you'd rather not read your radio's own channel. It leaves
  the chat list and goes quiet, and your board carries only Knit's own messages.
- A contact's post in the Meshtastic room now shows a shield when their radio signed it. On radios running
  firmware 2.8 or newer, a post can be up to 166 bytes, the most the radio can still sign.
- A contact's profile now shows the LoRa radio they say they use. The Meshtastic room prints the same number
  under a heard post, so you can compare the two.
- Paste a link into a message and, when your phone is online, a small card with the page's title and picture
  goes along with it. Off by default in Settings, since fetching the card shows the website your IP address.
- Tell people nearby you're open to chat, from Settings. When someone in radio range has said the same and
  you two have never messaged each other, you both get one nudge to say hi in the Nearby room, and their
  profile shows the flag.
- Send any kind of file in a direct message or group chat, under the same 8 MB limit photos already
  have. A received file shows its name and size, and saves wherever you point Android's file picker;
  the Nearby room stays photos only.
- React with any emoji. Long-press a message and tap the "+" beside the quick reactions to browse or
  search the whole set, and the quick row starts remembering what you actually use instead of the same
  six forever.
- A group chat now shows which members you've verified. A shield sits beside their name on every message they
  send, the same one a direct message shows in its header.

### Changed

- A chat with a lot of messages no longer opens on "No messages yet". Faint placeholder bubbles fill the
  thread while Knit loads it, so a long conversation stops looking like an empty one.
- A long chat now opens as fast as a short one. Knit loads the newest messages first and adds older ones as
  you scroll back, so a thread with thousands in it no longer stalls on the way in.
- Aliases are three words now, like ReallyJoyfulFerret. Settings and a contact's profile show the new one.
- Knit no longer feeds what is on screen to Android's content-capture service (the "app content"
  suggestions). An end-to-end-encrypted messenger has no business handing its screen text to another
  process, and long lists got smoother for it.
- Chats now say what happened in them. A quiet centered line marks a contact changing their name ("Sam is
  now Sam Vimes") or photo and a group being renamed or created, with no notification, no unread badge and
  no bump up your chat list.
- Adding someone from a contact link now says they have to add yours as well. The Add-by-link screen and
  the waiting line on their profile both carry the note, so a one-sided add no longer looks like a bug.

### Fixed

- Knit no longer gets stuck showing a blank screen. Opening it at the moment it was closing could leave it
  drawing nothing, with no way out but force-stopping it; it now notices and rebuilds its own screen.
- Phones that only relayed your messages no longer count as nearby. The online dot, chat list, group
  picker and nearby count now show only what your own radios can currently see.
- A Nearby room message sent while two radios were out of range of each other now arrives when they come
  back into range. It used to wait for the phones to get close enough for Wi-Fi or Bluetooth instead.
- Messages waiting on your Meshtastic radio now survive a dropped Bluetooth connection. Knit used to
  discard them one at a time while the phone reconnected, so a message could vanish without ever being sent.
- Message details for a room post names more of the people who got it. The list now says it may not be
  everyone who received the post.
- A message that has to travel between two Meshtastic radios now goes out as soon as there's room on the
  air. It could sit for the better part of an hour while a larger one blocked the queue ahead of it.

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
