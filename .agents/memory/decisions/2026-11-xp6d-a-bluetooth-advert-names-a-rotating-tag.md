---
id: "2026-11.xp6d"
slug: a-bluetooth-advert-names-a-rotating-tag
title: "A Bluetooth advert names a rotating tag, not the node id"
date: 2026-11-02
topics: [ble, mesh, privacy, wire, crypto]
---

# ADR 2026-11.xp6d — A Bluetooth advert names a rotating tag, not the node id

Status: Proposed (2026-11-02). Not built yet; this text has absorbed the design pass of 2026-11-02 (bluetooth, crypto,
security and wifi-aware reviewers). knit-next #285; lead of a paired change with knit-ios. The Wi-Fi Aware half and
the cleanup are `docs/NEXT_WIRE_BREAK.md` item 13.

**What was observed.** Bytes 1–16 of the BLE presence advert (`BleAdvertPayload`) are the node id, raw. The advert
repeats about once a second whenever the mesh runs, and the bytes survive address rotation, Bluetooth toggles and
restarts, so a stock scanner app recognises the same phone everywhere it goes. The cleartext profile flood ties the id
to a name, so one Knit run near a person is enough to name them in every later scan. The cost had been accepted
without a decision. It is not a wire break to remove it from the Bluetooth advert, because of two rules every shipped
build already has:

- An old build's ordinary dial goes only to a smaller advertised id (`DialPlan.ordinaryCandidates`, `promotable`:
  `localId > nodeId`). A tag whose base32 form starts `zzzz` sorts above every real id but about 2⁻²⁰ of them.
- An old responder admits a dialer it has never sighted, whatever the id order (`BleAdmissionPolicy.decide`, ADR
  2026-09.shzv and 2026-09.kwq2). It only ever sights a blinded phone's tag, never its real id.

So a blinding phone that dials every peer advertising a clear id links to old builds with no service-UUID bump and no
second advert. Three shipped paths do reach a tag, and each expects the tag back in the HELLO and fails on
`WrongPeer`: an old Android's lonely dial (ADR 2026-09.hj4a: alone 180 s, dial a larger id), and the iOS port's
foreground head-start dial and background seeking dial.

**What changed.**

- **The tag.** 16 bytes (`BlindTag`): bits 0–19 fixed at `11001` ×4 (the `zzzz` prefix), a 44-bit nonce from
  `SecureRandom`, then 64 bits of `HMAC-SHA256(presenceKey, "knit/advert/v1/tag" ‖ bytes 0..7)`. A nonce rather than
  a time slot, so no two clocks are ever compared. `BlindTag.matches` checks the prefix first and never throws.
- **The marker.** `BleAdvertPayload.FLAG_BLINDED = 0x08` says bytes 1–16 are a tag; additive under the flags-byte
  rule (`docs/WIRE_COMPAT.md`). The digest cue is zeroed while blinding (no Bluetooth decision reads it, and unchanged
  it would link one tag to the next). A controller without extended advertising does not blind: its sets may share
  the host's address across a restart.
- **Rotation.** At the first wake after a jittered 600–900 s lifetime on the elapsed clock, and at each bring-up: stop
  the presence and Coded sets, then start both as fresh advertisers with the same new tag. An in-place
  `setAdvertisingData` keeps a set's address and would tie the two tags together. A rotation due during a Coded fast
  window or a range hunt waits for it, bounded.
- **Who dials.** `AdvertOrder.iDial` decides every role before a link: against a clear advert a blinding phone dials,
  otherwise the classic order holds; between two blinded adverts the larger advertised form dials; a lonely dial goes
  only to a peer that would not dial us. The PHY driver of a live link (`CodedPhyPolicy.drives`) stays on the real
  ids from the HELLOs, or the two ends of an old↔new link would both step the PHY.
- **The handshake.** `Protocol.CAP_BLIND_ADVERT = 0x80000` ("I read tags"); no privacy decision rests on it. Two
  HELLO fields after the nonce: `t<32 hex>` (the sender's advertised form) and `d<32 hex>` (the form the dialer
  dialed). The link proof signs both HELLOs (ADR 2026-11.264w), which binds the fields to the proven id but does not
  prove the id owns the tag, so a field is a claim, weighed below. A dial to a tag (`LinkHandshake.dialTag`) expects
  no id, requires a proof and takes the proven id. The responder refuses with no reply a dialer whose proven link it
  already holds when `d` names its own tag, and resends its tag over that link instead, so a missed announcement
  cannot close both links. While it blinds, it refuses with no reply a dialer without the bit, and boosts its scan to
  find that dialer's clear advert, since the socket gives no PSM or connectable address to dial back.
- **Rotation is announced.** `LinkFraming.Type.TAG_NEXT` (tag 9, 16 bytes) goes to every proven tag-aware link at
  least 30 s before a rotation, and to a link that comes up while one is due.
- **Aliases** (`AdvertAliases`). Resolve order: own tags, then direct contacts' keys, then claims learned on links,
  then caches (positive and negative, cleared when keys change), under a per-second HMAC budget. A link holds one
  claim; a claim a key contradicts is dropped; two claimants void each other; a dialer believes `t` only when it
  equals the `d` it dialed. Presence and every per-peer map are keyed by the resolved id where there is one. The
  published sets (`reachable`, `near`) carry resolved and linked peers only, so no tag becomes a met peer; an
  unresolved tag with the Aware flag enters the Aware candidate sets alone, so a dormant Aware plane still wakes at
  the first sighting.
- **The presence key.** 32 random bytes per generation, kept in a local-only table that backups skip; the generation
  number is `max(last + 1, now)` on the sender's clock, and `last` rides backups, so a restore never reuses a number.
  It goes only to direct contacts: accepted one-to-one chats and verified peers, never group co-members, who could be
  added by someone else. It rotates on a removal, a block, a restore, any shrink of that set, and weekly with the
  signed prekey. Delivered by its own sealed ctl, `CTL_PRESENCE_KEY = 11` (named form only), on confirmed sessions
  only, owed per (peer, pairwise root, generation) so that any new root re-sends it, and checked against the
  generation's audience at sealing time. Not on the sealed profile, which goes to every confirmed session, strangers
  included. A receiver stores a 32-byte key from any peer with a row (`peer_presence_keys`) and uses only its direct
  contacts' keys. The rotation on block makes a block more than presentation for this one key, an exception to ADR
  2026-09.bts9 and ADR 2026-11.nsbf.
- **Staging.** Release N ships every reader and the keys, with writing tags behind `BuildConfig.BLIND_ADVERT` (on in
  debug, with a runtime switch for the legacy harnesses; off in release). Release N+1 turns writing on.

The alternatives: a second, blinded advert beside the clear one (twice the airtime and advertising sets, and no
privacy until the clear one stops); a random tag with no key (contacts would see each other only once linked); a
break (a partition for weeks across Play and F-Droid); and, for rotation during sleep, a controller-timed set lifetime
or an alarm per rotation, both declined in favour of no extra wakes.

**What it costs.**

- **What it does not stop.** A device that advertises as a Knit phone is dialed by every blinding phone in range and
  reads each one's node id and current tag in the HELLO, which is written before any proof. The HELLO crosses insecure
  L2CAP in the clear (#286). A phone's own frames on the side channel name it as author. The cleartext profile ties
  name to id. The Wi-Fi Aware SSI keeps the clear id until item 13. Every holder of the presence key can recognise the
  phone wherever they scan, until the next rotation, and a captured tag replays as "nearby" for its contacts within
  that key's generation (at most a week).
- **An idle phone keeps its tag.** Rotation waits for a wake, so a phone at rest with nothing waking it can air one
  tag for hours. The announced tag likewise lets a linked stranger follow the phone for one tag lifetime after it
  leaves, longer if it then sleeps.
- **Old builds, during the transition.** Every link between an old and a blinding phone is the blinding phone's dial.
  An old phone that hears a blinding phone one way, but is not heard, never links to it. On an old phone each
  rotation of each blinded neighbour adds a met-peer row, a phantom nearby entry beside the linked id, an Aware wake,
  and a chase it cannot end. An un-updated iPhone's head-start dial is refused once per rotation; after N+1 an
  un-updated backgrounded iPhone never links to a blinding Android, which is why N+1 waits on the iOS reader.
- **Strangers.** One appears in the nearby count only once linked. Its sighting no longer corroborates the Wi-Fi Aware
  watchdog's Tier-2 kill until it is linked or resolved, so a wedge beside strangers only (more than six phones, or
  failing Bluetooth dials) lasts until a contact or an old build comes near; ADR 2026-09.9dnk is amended when built.
- **Contacts.** One recognises the phone only once it holds the current key, and a removed or blocked contact until
  the rotation lands.

Guards: `BlindTagTest`, `AdvertOrderTest` (a two-phone run across rotation edges and mixed builds),
`AdvertAliasesTest` (claims, budget, caches), `BleAdmissionPolicyTest`, `LinkHandshakeTest` (tag dial with and without
a proof, legacy dialer refused, fields 4 and 5 ignored by an old parser), a unit test that a send racing a removal
never seals the new generation to the removed peer, the advert, HELLO and ctl vectors (new entries, none moved),
`PresenceKeyLabTest` (direct contacts resolve; strangers and group co-members never do; removal, block and restore
rotate; restores and resets on either side re-deliver) ending in `assertConverged`, and a `MaliciousForkLabTest` case
in which a linked Sybil claims other phones' tags.
