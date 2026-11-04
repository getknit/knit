---
id: "2026-11.xp6d"
slug: a-bluetooth-advert-names-a-rotating-tag
title: "A Bluetooth advert names a rotating tag, not the node id"
date: 2026-11-02
topics: [ble, mesh, privacy, wire, crypto]
---

# ADR 2026-11.xp6d — A Bluetooth advert names a rotating tag, not the node id

Status: Proposed (2026-11-02). Not built yet; this text has absorbed the design pass of 2026-11-02 (bluetooth, crypto,
security and wifi-aware reviewers) and the presence key's own design pass and follow-up the same day. knit-next #285; lead of a paired change with knit-ios. The Wi-Fi Aware half and
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

- **The tag.** 16 bytes (`BlindTag`): bits 0–19 fixed at `11001` ×4 (the `zzzz` prefix), then 108 bits of
  `HMAC-SHA256(presenceKey, label ‖ u64be(slot) ‖ u8(restart))`. A slot is 15 minutes of wall clock; `restart`
  (0–3) gives a fresh tag to a bring-up inside one slot. A contact computes each direct contact's expected tags for a
  window of slots (the last 8 hours and the next half hour) into a lookup table, so resolving a heard tag is one hash
  lookup and an unknown tag costs nothing. Revised 2026-11-02 after two review rounds: the first design minted a random
  nonce, so every unknown tag had to be HMAC'd against every key under a budget, and each guard on that budget (first
  come, second sighting, ranking, an LRU memo) gave a radio flooder a lever.
- **The marker.** `BleAdvertPayload.FLAG_BLINDED = 0x08` says bytes 1–16 are a tag; additive under the flags-byte
  rule (`docs/WIRE_COMPAT.md`). The digest cue is zeroed while blinding (no Bluetooth decision reads it, and unchanged
  it would link one tag to the next). A controller without extended advertising does not blind: its sets may share
  the host's address across a restart.
- **Rotation.** At the first wake after a slot boundary, and at each bring-up (the next restart index): stop the
  presence and Coded sets, then start both as fresh advertisers with the same new tag. An in-place
  `setAdvertisingData` keeps a set's address and would tie the two tags together. A rotation due during a Coded fast
  window or a range hunt waits for it, bounded. A slotted tag repeats whenever the same (slot, restart) is asked for
  twice (a fifth bring-up in one slot, a clock set back or corrected across a slot edge, a spoofed NITZ or GNSS time),
  so the advertiser (`TagSchedule`) keeps a persisted high-water mark per key generation, never airs a pair at or
  below it, and airs a fresh random prefixed tag that no contact resolves until the clock passes the mark.
- **Colliding keys.** A tag that two contacts' keys both list resolves to nobody (`ExpectedTagTable`), so a contact
  who passes on another contact's presence key as its own takes over nothing. The key store follows the same rule
  whatever the arrival order: a key equal to another peer's is kept for both, listed for neither and counted, never
  "first stored wins" (which would let a re-sealed copy that outran the owner's own ctl take the owner's tags).
- **A clock pushed ahead.** The advertiser's mark then sits in the future, and every tag until the clock passes it is
  a random one no contact resolves. When the mark is more than the window's ahead edge past the clock, the schedule
  says so and a new presence-key generation starts, with no mark; the key rotation's cadence treats a future-dated
  stamp as due. Recognition is lost for at most until that rotation lands, not for as long as the clock was pushed.
- **Who dials.** `AdvertOrder.iDial` decides every role before a link: against a clear advert a blinding phone dials
  and a clear one keeps the classic order; a clear phone never dials a blinded advert (a literal string comparison
  would let the 2⁻²⁰ of real ids that sort above a tag dial too, and both responders would refuse, so the pair would
  never link); between two blinded adverts the larger advertised form dials; a lonely dial goes only to a peer that
  would not dial us. The PHY steps of a live link (`CodedPhyPolicy.drives`) stay on the real
  ids from the HELLOs, or the two ends of an old↔new link would both step the PHY; who dials back or hunts after a
  drop at range (`fastAdvertAfterDrop`, `huntAfterDrop`, `coldHunt`) is a dial role and moves to `iDial`.
- **The handshake.** `Protocol.CAP_BLIND_ADVERT = 0x80000` ("I read tags"); no privacy decision rests on it. Two
  HELLO fields after the nonce: `t<32 hex>` (the sender's advertised form) and `a<32 hex>` (the form the dialer
  dialed; `a`, not `d`, so it never looks like the SSI's `|d<digest>`). The contract both platforms parse by: forms
  only behind a valid nonce, in any order after it, each in its exact shape, unknown fields skipped, and a letter that
  appears twice in that shape is absent. The link proof signs both HELLOs (ADR 2026-11.264w), which binds the fields
  to the proven id but does not prove the id owns the tag, so a field is a claim, weighed below, and only on a proven
  link; read before a proof it is a hint at most. A dial to a tag (`LinkHandshake.dialTag`) expects no id, requires a
  proof and takes the proven id; a failed one is keyed on the tag and the address, never on the unproven id its reply
  names. The responder refuses with no reply a dialer whose proven link it
  already holds when `a` names its own tag, and resends its tag over that link instead, so a missed announcement
  cannot close both links. While it blinds, it refuses with no reply a dialer without the bit, and boosts its scan to
  find that dialer's clear advert, since the socket gives no PSM or connectable address to dial back.
- **Rotation is announced.** `LinkFraming.Type.TAG_NEXT` (tag 9, 16 bytes) goes to every proven tag-aware link at
  least 30 s before a rotation, and to a link that comes up while one is due.
- **Aliases** (`AdvertAliases`). Resolve order: own tags, then the direct contacts' expected-tag table, then claims
  learned on links. A link, and a node id, holds one claim; the claim table never refuses a held link's claim and, when
  full, evicts the ended-link claim that expires soonest; the pending table, when full, evicts ended links'
  announcements before held links', and the void table evicts voids that unproven claimants made before those our own
  dials made, each then by soonest expiry; a link announces at most one `TAG_NEXT` per two minutes; a claim a key contradicts is dropped; two
  claimants void each other, and our own dial's proof lifts a void that unproven claimants made; a claim suppresses
  our dial only while its claimant's link is held; a dialer believes `t` only when it equals the `a` it dialed, and holds a different `t` after
  a tag dial pending as an announced tag (a rotation edge). A held link's claim and pending tag do not lapse while the
  link is up; they expire one tag lifetime after it ends. A responder takes the
  dialer's `t` as a hint: it attributes the tag to the link for presence and RSSI but never suppresses a dial
  `iDial` calls for. An honest hint names a peer we would not dial until our own next rotation; after it, half the
  time we dial a peer we hold, it refuses with no reply and resends its tag over the link, and the resend becomes a
  claim. A forged hint is dialed, and the proof that comes back voids it. Presence and every per-peer map are keyed by
  the resolved id where there is one, and a sighting's history (its dwell, its admission sighting) is keyed on the
  advertised form until it resolves, then carried over to the id; an unresolved answer never suppresses a dial. The
  published sets (`reachable`, `near`) carry resolved and linked peers only, so no tag becomes a met peer; an
  unresolved tag with the Aware flag enters the Aware candidate sets alone, so a dormant Aware plane still wakes at
  the first sighting.
- **The presence key.** A phone holds a previous, a current and a next key (32 random bytes each) in a local-only
  table that backups skip. A generation number is `max(last + 1, now)` on the sender's clock, overflow-checked, with a
  restored `last` more than a year ahead clamped; `last` rides backups, so a restore never reuses a number. Keys go
  only to direct contacts: the user's own accepts (less those a commons made, marked as such when made), verified
  peers and DM threads the user wrote in, minus blocked (`DirectContacts`). Never group co-members or commons posters,
  whose reach someone else grants. Rotation is a pure function of the persisted key rows and a fresh read of that set:
  any shrink of a key's audience (a removal, a block, an un-verify) mints a fresh current and next; weekly, and on a
  corrected clock or a stale advertiser mark (at most once a day), the next key becomes current; a new contact joins
  the next key's audience, and the current one's only within a day of its activation, so nobody added later can read
  back days of aired tags. Delivered by its own sealed ctl, `CTL_PRESENCE_KEY = 11` (named form only, padded to one
  size: every ctl 11 plaintext is 128 bytes, its CBOR names `g`, `k`, `ng`, `nk` and `p`, which an iOS port mirrors), only
  point to point over a live proven link: signed, `relay = false`, never flooded, custodied or cast. A
  flooded delivery would show every relay the user's direct contacts each week and, by who is missing from a
  rotation's burst, whom the user removed or blocked. What each linked contact is owed is reconciled against what it
  was sent, on link-up, key changes, session root changes and capability changes, and at every heal; the next key
  goes ahead, so a weekly rotation is seamless for a contact met during the week. A restore's session reset to a
  direct contact with no DM thread or shared group goes over the first link too, not the flood. A receiver stores up
  to three keys per peer, not in backups, each with the session root its frame opened under: a later generation is
  added, an equal one is a no-op, and a lower one is taken only when it opens under a new active root (a sender
  restored after its clock ran ahead). Only direct contacts' keys are listed; a key equal to another listed
  contact's, or to one of ours, is listed for no one and counted. The rotation on block makes a block more than
  presentation for this one key, an exception to ADR 2026-09.bts9 and ADR 2026-11.nsbf.
- **Staging.** Release N ships every reader and the keys, on two independent flags: `BuildConfig.PRESENCE_KEYS`
  (minting and sending a phone's own keys; on in every build) and `BuildConfig.BLIND_ADVERT` (writing tags; on in debug,
  with a runtime switch for the legacy harnesses; off in release). Release N+1 turns writing on, and contacts already
  hold each other's keys on its first day. Shipping keys first has a cost while links are read in the clear (#286): a
  ctl 11 at link-up tells a listener the two phones are direct contacts even if they never write, and the frame can be
  re-wrapped and flooded (item 14). The maintainer accepted that for the head start.

The alternatives: a second, blinded advert beside the clear one (twice the airtime and advertising sets, and no
privacy until the clear one stops); a random tag with no key (contacts would see each other only once linked); a
break (a partition for weeks across Play and F-Droid); a random-nonce tag searched against every key under a budget
(the first design, withdrawn: a flood of fresh tags always found a lever in the budget); and, for rotation during
sleep, a controller-timed set lifetime or an alarm per rotation, both declined in favour of no extra wakes.

**What it costs.**

- **What it does not stop.** A device that advertises as a Knit phone is dialed by every blinding phone in range and
  reads each one's node id and current tag in the HELLO, which is written before any proof. The HELLO crosses insecure
  L2CAP in the clear (#286). A phone's own frames on the side channel name it as author. The cleartext profile ties
  name to id. The Wi-Fi Aware SSI keeps the clear id until item 13. Every holder of the presence key can recognise the
  phone wherever they scan, and forge its presence, until the next rotation; a captured tag replays as "nearby" for its
  contacts for as long as its slot stays in their window (about 8 hours), longer for a contact whose clock an attacker
  sets back. The key's ctl and the restore's link-owed reset go `relay = false`, but that bit is outside the
  signature: the addressee, or a listener on a link read in the clear (#286), can re-wrap either as `relay = true`,
  and every relay then custodies and floods a signed frame saying the user wrote to that contact (parked as
  `docs/NEXT_WIRE_BREAK.md` item 14).
- **Clocks.** A contact resolves a tag only while its slot falls in the contact's window, so a phone whose wall clock
  is off by more than that is not recognised before a link; it still links as a stranger, and the link proves it.
  Where each clock sits in its slot decides the exact margin: a phone running ahead resolves up to between 30 and 45
  minutes ahead, and one running behind up to about 8 hours less however long its tag has been on air. A test of the
  margin pins both clocks' slot phase.
- **An idle phone keeps its tag.** Rotation waits for a wake, so a phone at rest with nothing waking it can air one
  tag for hours. The announced tag likewise lets a linked stranger follow the phone for one tag lifetime after it
  leaves, longer if it then sleeps.
- **Old builds, during the transition.** Every link between an old and a blinding phone is the blinding phone's dial.
  An old phone that hears a blinding phone one way, but is not heard, never links to it. On an old phone each
  rotation of each blinded neighbour adds a met-peer row, a phantom nearby entry beside the linked id, a reason to keep
  its Aware plane attached, and a chase it cannot end. An un-updated iPhone's head-start dial is refused once per rotation; after N+1 an
  un-updated backgrounded iPhone never links to a blinding Android, which is why N+1 waits on the iOS reader.
- **Strangers.** One appears in the nearby count only once linked. Its sighting no longer corroborates the Wi-Fi Aware
  watchdog's Tier-2 kill until it is linked or resolved, so a wedge beside strangers only (more than six phones, or
  failing Bluetooth dials) lasts until a contact or an old build comes near; ADR 2026-09.9dnk is amended when built.
- **Contacts.** One recognises the phone only once it holds the current key, and keys travel only over links: after
  a fresh rotation (a removal, a block), or a weekly one whose next key it never got, a contact recognises the phone
  only after their next link. A removed or blocked contact keeps recognising it until the advert moves to the new key.
  The key's ctl, though padded, tells a listener in radio range that two linked phones are direct contacts, until
  links are encrypted (#286). A direct contact with no DM thread or shared group who messages from afar after our
  restore is not read until its own reset heuristic fires or the two link. The link-owed reset goes only over a link whose peer proved its id (ADR
  2026-11.264w), so for a contact on a build without the link proof the restore split changes nothing: the pair heals
  through the reset heuristic as before. When it is the contact who restores, our reset in answer to its frames still
  floods: those frames already named the pair. What each contact was sent is remembered per contact and session root across link drops, so a
  relink with nothing changed sends nothing (a resend under a root the peer just abandoned would feed its reset
  heuristic). The price: a contact whose phone dies between opening the key and committing it, or a key lost in a
  dying link, holds no current key until the next generation, the next session root or this phone's restart, a week
  at most.
- **Commons accepts from before the mark.** Builds before this one accepted commons posters with no mark, so the first
  reader marks every accepted id still in a joined commons' member list. A poster from a commons the user left before
  upgrading has no member row left, so stays a direct contact; and a commons member the user had also accepted by hand
  before upgrading is marked, so stops being one until verified or written to. Both only on builds that showed the
  commons, which release does not.

Guards: `BlindTagTest`, `AdvertOrderTest` (a two-phone run across rotation edges and mixed builds),
`AdvertAliasesTest` (claims, the lookup table and its window, a flood that costs nothing), `BleAdmissionPolicyTest`, `LinkHandshakeTest` (tag dial with and without
a proof, legacy dialer refused, fields 4 and 5 ignored by an old parser), a unit test that a send racing a removal
never seals the new generation to the removed peer, a `blindTag` slot-construction vector (a negative slot, slot 0, all
four restarts; it lands before the iOS reader brief and release N), the advert, HELLO and ctl vectors (new entries,
none moved),
`DirectContactsTest`, `PresenceKeyPolicyTest`, `PresenceKeyLabTest` (direct contacts resolve; strangers, group
co-members and commons posters never do; removal, block and restore rotate; restores and resets on either side
re-deliver, with the link up throughout; a passive carrier holds and floods nothing naming a key's recipient; a clock 46 min ahead or 9 h behind, slot phase pinned, links as a stranger) ending in
`assertConverged`, and a `MaliciousForkLabTest` case
in which a linked Sybil claims other phones' tags.

## Amendment 2026-11-03: the reader's wiring (step 5a, draft)

Status: draft, with the reader's change; the caller decides whether it stands.

- **An unresolved form's key is a handle** (`FormKeys`, `~zzzz…<n>`), not the tag: it lives in a namespace no node id
  can take, and it is the redaction, so a log line, a collection's `toString()` or a debug read-back that prints a
  per-peer key never carries a tag. A form that resolves by a key or by our own dial's proof moves its entries to the id;
  a claim or a hint drops them and attributes from then on. A handle goes only with its entries (a tag flood past the
  table's cap evicts both, counted).
- **Suppression is read when the dial is decided**, never frozen at the sighting: a sighting stores what may suppress a
  dial on its answer (`BlePresenceTracker.Suppressor`): any link to the id for a clear advert, a proven link to the id
  for a key's answer, the claimant's own link (while it is the one held under the id) for a claim's, nothing for a
  hint's or an unresolved form's. A link proved after the sighting suppresses at once; a claimant that relinks does
  not, so the dial whose proof would void a false claim still goes.
- **Handshake events reach the resolver only for the registered link**: the transport drops `onDialed`, `onAnswered`
  and `onTagNext` on the worker unless the link is still the one held under its id, so none lands after its link's end
  (a tag dial on a dead link still settles its proof's rivals through `onDialProved`).
- **The dial key** (`DialKeys.dialKey`) is a clear advert's id, or a blinded one's handle and address. `inFlight`, the
  backoff and `beginConnect` book on it; a link's flap still backs off the id, and every candidate filter reads both. A
  failed dial to an unresolved form also advances its address's own streak (`DialKeys.addressKey`) and its handle's
  (`DialKeys.handleKey`), which an unresolved form reads too: an airer changing its tag from one address keeps backing
  off, one tag dialed at its 1M and Coded sets' two addresses keeps one streak, and a contact a key resolves at that
  address is not held back. The handle's streak is the tag's whatever the address, so a replayer airing an unresolved
  stranger's tag and refusing our dials can delay our dial to the real airer by up to the backoff cap, accepted.
- **Every key the store lists is listed** (up to three per contact, newest first, since the next key goes ahead of the
  weekly rotation); a tag heard under one caps every older one at a key generation from then, never retires it at
  once, since any other contact of the owner holds the owner's next key and could air one tag under it; a key the
  store drops is unlisted at once, and a newest key the feed replaced without naming it (the two-key form) still goes
  at a tag under its successor.
- **A contact's key churn is bounded where the feed meets the resolver** (`KeyChurnLimiter`): one gain per contact per
  minute and 24 a day, the newest held listing applied when the window frees, unlisting never delayed, and a contact
  that leaves the listing and returns within a day counted against what it gained before, so a contact streaming
  generations costs the table at most 420 HMACs a minute (three keys' tags), beside the window's own slide.
- **`CAP_BLIND_ADVERT` joins `LOCAL_CAPABILITIES`** with the reader. The mesh lab models a build from before it by
  withholding the bit from a node's profile (`MeshManager.withheldCapabilities`); the app withholds nothing.
- **2026-11-03 (maintainer): keys and writing are switched apart.** Holding keys back until the advert blinds or links
  are encrypted was weighed and declined; keys ship in N (see Staging). `PresenceKeyEngine`'s `sendsOwnKeys`
  (`MeshManager.sendsPresenceKeys`, `BuildConfig.PRESENCE_KEYS`) off mints, rotates and seals none, and a capability
  change asks no pass; leavers' stored keys are still deleted and a restore's link-owed resets still go. Own keys a build
  with it on minted are left, never sent. `BuildConfig.BLIND_ADVERT` gates the writer alone.

## Amendment 2026-11-03: the writer's key side (step 5b, Part A, draft)

Status: draft, with the key side of the writer's change; the caller decides whether it stands. The writer's own wiring
is its own section.

- **The advertiser's key is the engine's published value, never a Room read.** `PresenceKeyEngine.ownKey` (an
  `OwnPresenceKey`, whose `toString` shows the generation alone) is set at the end of each pass's settle, under the
  engine's lock, and goes back to null at every session start (`beginSession`, before the transport starts), so a
  removal or a block made while the mesh was stopped is a fresh pair before the first key the radio is handed: the
  transport starts before the first settle, and a Room read there would air a tag under the key the leaver holds.
  `MeshManager` hands the transport null before every `start`, then the engine's value and each change
  (`MeshTransport.onOwnPresenceKey`); the composite hands it to the Bluetooth plane alone. Equal values are no change:
  equality is the generation and the key bytes, so a mark write, a grow or a peer's key (rows that change, a current
  generation that does not) never restart the advertiser — a feed keyed on the rows would, through its own mark write.
  A build that sends no keys of its own publishes none, and neither does a settle that could not reach nothing-due (a
  fresh pair or a first mint with no generation left): the rows would still name the leaver's key as the current one.
  The collector is a dispatch behind the engine, so the transport also gets a synchronous read of the value
  (`MeshTransport.onOwnPresenceKeySource`) to check a tag against just before it airs.
- **`TAG_NEXT` goes to no one blocked or departed.** `MeshTransport.onTagNextRecipients` hands the transport
  `MeshManager.hearsTagNext`: not blocked (node ids and pinned device tags, kept in memory) and not in
  `PresenceKeyEngine.departed` — everyone a kept own key's audience names who is no longer a direct contact
  (`PresenceKeyPolicy.departed`; settled, the previous key's leavers: the departures behind the last fresh pair, gone at
  the next advance). The departures are published before the key they go with, so a send right after a fresh key reads
  the new set. The tag-blocked ids are re-read when a block setting or any peer's device tag changes, so a peer pinned
  later under a blocked tag is refused; an input that fails leaves the list unread. In-memory reads, answered at each
  send; no until the session has read both. A leaver who held only a discarded next key (never aired) is not named: no
  kept row records it.
- **The advertiser's mark write is durable and conditional** (`PresenceKeyRepository.markStore`, `PresenceKeyDao.setCurrentMark`):
  never inside a transaction (refused at once); `PRAGMA synchronous=FULL` outside one; then the write transaction, whose
  first statement reads `PRAGMA synchronous` on the committing connection and refuses anything but FULL, and whose update
  lands only while the generation is the newest activated one (a generation a fresh pair demoted marks nothing, so
  nothing airs under it); `NORMAL` again after, its failure counted. Not `wal_checkpoint`, not a separate file. No
  refusal names the generation (a dated line would date a removal or a block). STATE counts a commit refused for not
  running at FULL (`presenceMarkNotFull`, a fault) apart from a write refused for a key gone or demoted
  (`presenceMarkRefused`, a rotation's race); `presenceMarkNotFull == 0` with `presenceMarksDurable > 0` is the device
  check the JVM cannot make (framework SQLite in memory there, SQLCipher in WAL on a phone).

## Amendment 2026-11-03: the writer on the air (step 5b, Part B1, draft)

Status: draft, with the writer's Bluetooth half; the caller decides whether it stands. `TAG_NEXT` sent, admission and
dialing while blinded, and the carried fixes are Part B2's, so every rotation this part makes is unannounced on the air
(the announcement's reservation is made, and the tag it names is the one aired).

- **The gates.** The writer airs a tag only while `BuildConfig.BLIND_ADVERT` (passed in; nothing under `mesh/bluetooth/`
  reads `BuildConfig` for it), the runtime switch (`SettingsStore.debugBleBlindAdvert`, key `debug_ble_blind_advert`;
  unset reads `DEBUG_BLIND_ADVERT_DEFAULT`, **off** in a debug build until step 6's bridge action exists, the
  maintainer's call of 2026-11-03; a release build always reads on) and the controller's extended advertising (read with
  the adapter on, where `probeCoded` reads, and kept true for the life of the process once read true, so one transient
  false read at a bring-up cannot leave a session airing its id) all hold. A build without `BLIND_ADVERT` runs neither
  the slot turn nor the switch's collector. Off, my forms are
  clear and nothing below runs. The key gate is independent of the writer's: `PRESENCE_KEYS` mints and sends keys whether
  or not the advert blinds (so a debug phone with the switch off still sends ctl 11, and release N sends keys and airs its
  id), and a build that blinds with keys off has no own key and airs random tags.
- **One writer** (`AdvertWriter`). The presence set, the Coded set, the form they carry and the PSM they name are one
  snapshot, replaced whole by a rotation, a bring-up or a flip, under one lock every encode is made under. Every push
  (`readvertise` and its seven callers) builds from the snapshot's form, never from the node id while it is blinded; a
  payload whose bytes 1–16 are not the snapshot's tag is dropped and counted. While a rotation's tag is pending nothing
  starts or updates. No set changes form in place: a new form is new instances, and the replaced ones are retired
  (`BleAdvertiser.retire`: inert from then on, a late start of theirs stopped again). A bring-up is a rotation; an install
  after the radio went down starts nothing and opens no server.
- **The tag** (`TagChoice`). With no own key, a random prefixed tag; when the engine's generation (read synchronously)
  is not the one the transport holds, a random tag and the reservation dropped unaired; announced, exactly what
  `TagSchedule.take` returns; otherwise `next`. A mark store that throws gives a random tag, never the id. The session's
  first key after a bring-up that aired a random tag for want of one rotates at once (completing that bring-up); a key the
  bring-up already used is no change; any other change of the generation, a null among them, rotates at once and is
  never held. Every reservation is dropped on the worker step that receives a new key, and at adapter OFF. A stale mark
  asks the engine for one pass per generation; no transport-to-engine seam exists yet, so it is counted and the heal
  reconcile's 15-minute pass is the backstop.
- **The slot turn** (`AdvertRotation`) waits on the wall clock through an elapsed-aware wait (a privacy bound, never a
  `delay`, which stops in suspend), recomputed on `ACTION_TIME_CHANGED`, a key change, a bring-up and a flip. It
  reserves the coming slot's tag at the first wake at or after edge − 3.5 min, rotates at the first wake at or after
  max(edge, reserved + 30 s), and rotates at once, unannounced, on a wake past the edge with nothing reserved or a slot
  late. A rotation due at T waits for a fast Coded window, a range or cold hunt, a dial of mine in flight or an ACL
  edge's 2.5 s settle, at most until T + `fastHoldMs`, whatever opens after T. A clock set back below the slot on the air
  rotates at once.
- **The idle tag's longest life.** The wait adds no wakes, so a lone dozing phone keeps its tag until its first CPU wake
  after the edge: up to the Doze maintenance gap (about 6 h by default, a device property). A linked phone wakes at
  least for each re-offer, about 1–2 min past the edge.
- **A fresh PSM per rotation** (the maintainer, 2026-11-03; `PsmRotation`). Before the old sets stop, a `SecureRandom`
  count of 0–15 throwaway insecure L2CAP servers are opened and closed (AOSP's LE dynamic PSM allocator is round-robin
  from the last one, so a plain re-open is p + 1), then the new server opens and the fresh sets name it; the old server
  keeps accepting for Coded's dial watchdog plus 5 s (30 s), then closes. The residual: under the round-robin allocator
  the new PSM is the old one + 1 to + 16 (more only when another app allocates in between), so a scanner that sees one
  set go off the air and the next come on has about a 1-in-8 window to match them on the PSM, not one in 128; it helps in
  a crowd and little alone. When the new server will not open (`PSM_KEPT`, counted: the stack refused the listen), the
  new tag airs at the old PSM and that rotation is linkable by it. Accepted sockets outliving their server socket is to
  verify on the lab phones: a bluedroid-derived vendor stack may free an accepted connection when its listener closes,
  and the trial checks it: mainly a link accepted before a rotation, held past its server's close 30 s after the next one
  (on such a stack every accepted link would drop within about 15.5 min), and also a dial inside a rotation's grace held
  past 30 s; the fallback is to keep a retired server open until no link it accepted is live.
- **Fresh instances.** The presence set from `presenceParams()` (legacy, `TX_POWER_HIGH`); the old set stopped before
  the new one starts; the Coded set at the asked power on the interval a fast window wants; a slot or flip restart at
  least 2.5 s after an ACL edge (a key change is never held). The side channel rotates with them
  (`BleSideChannel.rotate`): a fresh set per slot, the page each slot had on the air is not aired again, and every frame
  already started is dropped from the queue (counted `SIDE_FRAME_DROPPED`), since its next part on a fresh address
  would carry the same fragment id as the part on the old one; at most one page dwell and one fragmented frame per slot
  per rotation, which the flood and the links carry. My forms (current, previous) swap as one snapshot as the new sets are asked to start, and the
  resolver's own tags take both. `FLAG_BLINDED` is set and the digest cue zeroed; the HELLO carries `t` (the tag on the
  air) while blinding.
- **Counts, never dated causes.** Rotations count by cause in STATE (`bleRotationsByCause`: slot, bring-up, fresh,
  flip) and the writer's other events in `bleAdvertWritesByEvent`, never in the field log and never in a log line beside
  a time, a generation or a slot: a dated "fresh" says when the user removed or blocked someone. The `bt state` line
  shows `psm=rotating` while blinding. The advertiser's own start and stop lines still date every restart in logcat, so
  an off-edge restart with no adapter edge is visible there; a bug report is the holder's to share.
- **What it does not stop** (additions). The presence and Coded sets are connectable, so the stack gives them resolvable
  private addresses: a device holding this phone's IRK (a bonded watch, headset or car) resolves every address to the
  phone whatever the tag. `AdvertisingSetParameters.Builder.setOwnAddressType(ADDRESS_TYPE_RANDOM_NON_RESOLVABLE)` would
  avoid that, but it is a `@SystemApi` (the API 37.2 stub), so an app cannot ask for it. The host's own RPA rides this
  phone's SCAN_REQs and CONNECT_INDs and rotates on the host's timer, not at our edge, so a sniffer that ties it to the
  presence set by co-location can bridge tags with it. The capabilities byte and the flags byte (`sideLive`, `aware`,
  `gattPeers`, besides `FLAG_BLINDED`) survive each rotation unchanged: per-build or per-phone constants for hours, they
  narrow a crowd for a scanner matching one set to the next.
- **Old builds** (addition). A shipped build keys sightings by bytes 1–16, never by the blinded phone's real id, so it
  scores the link to it at the floor (`neverSighted`, −90) and sheds it first at its six-link budget.

## Amendment 2026-11-03: announced rotations and the blinding rows (step 5b, Part B2, draft)

Status: draft, with the second half of the writer; the caller decides whether it stands. With it, B1's rotations are
announced.

- **`TAG_NEXT` sent** (`TagNextSends`), as a link record beside the keepalive (`FramedLink.sendTagNext`): never a frame,
  never in `DoorbellPolicy.rings`, so a rotation never rings a suspended iPhone. It goes only to a proven link whose HELLO
  carried `CAP_BLIND_ADVERT`, whose peer `MeshTransport.onTagNextRecipients` answers true at the send (not blocked, not
  departed; read at each send, never at link-up), and only while an own key is held (a random tag is never announced).
  Three sends: the lead announcement of the reserved tag at edge − 3.5 min (and the same tag to a link proved while it
  stands); the form just aired, once per link, after a rotation nobody was told of (a late wake, a key change, a flip);
  and the held-link row's resend. The receiver keeps one per link per two minutes and holds an announced tag pending,
  which any other form would replace; so the second and third go only when none went to that link inside two minutes,
  never inside two minutes before a due announcement, and never while an announcement stands. The announcement never
  moves for them, so a forged dial cannot push it out. Each refusal is counted.
- **The held-link row's non-silent bound.** At `RefuseHeld` the held link's silence is probed at once
  (`LinkSilence.onEdge`, never on a `CAP_DOORBELL` link, rate-limited by the probe's own state), so a held link dead on
  this side closes within its own probes and the dialer's next try is admitted; never a shorter wait of its own. Dials
  at any rate, forged ones too, start at most one probe per answer wait (`LinkSilence.onHeldDial`). A
  proven held link is still displaced only by a verified proof (R2). **An excluded peer's re-dials** (blocked, or one
  that left the own key's audience) take `RefuseHeld` with no resend, bounded by its own no-reply backoff, until its
  held link ends.
- **A peer's announced tag becomes the link's form at its edge** (a resend's at once), so both ends judge
  `dialsBackAfterDrop` on the same pair of forms. Only a record the resolver accepted (pending, or what a contact's key
  already says) is noted, one note and one wait per link (`LinkFormNotes`; a later record replaces it), and at the edge
  only if the tag still stands for the link: a fork flooding records at link rate costs one wait.
- **The blinding-only row** (`BleAdmissionPolicy.Verdict.RefuseLegacy`): while this phone blinds, a dialer whose HELLO
  lacks `CAP_BLIND_ADVERT` is refused first, with no reply and no HELLO written, counted apart; it buys a 60 s scan boost
  to find that dialer's clear advert at most once per five minutes phone-wide (`LegacyDialerBoost`), and one claimed id
  at most once per half hour under that, so an old phone that cannot be linked does not keep the scan boosted.
- **The chase's early end.** While blinding, the Aware→Bluetooth chase covers every foreign id, and a blinding peer
  among them never shows under its id: an unresolved handle first sighted (by capture stamp) after a chase began ends
  that chase, at most once per five minutes, counted; the ended chase stays ended while its id stays foreign. A link ends
  one as before. A hostile radio airing fresh fake tags ends live chases at most once per five minutes, which only delays
  a link to a stranger.
- **A dial to a superseded form** (the peer rotated since the dial began) is booked under that form's handle and
  address, which the peer's new form never reads, and never under the resolved id: it costs the new form no streak.
- **Key churn** (`KeyChurnLimiter`): a contact that returns to the listing with only keys it held when it left relists
  at most once a minute and never counts toward the daily cap.
- **A stale mark asks the engine for one pass per generation** through `MeshTransport.onPresenceKeyPassRequester`
  (Bluetooth only), which `MeshManager` answers with one `reconcile("mark")` on the session.
- **Counts.** `TagSchedule.Next.resolvable` false and `stale` true reach the field log as counts
  (`bleTagsUnresolvable`, `bleTagsStale`), never as dated events; the sends, refusals, probes and boosts are STATE counts
  (`bleAdvertWritesByEvent`); a send line names the recipient, never the tag.
- **What it does not stop** (addition). `TAG_NEXT` crosses insecure L2CAP in the clear, 3.5 minutes ahead of each edge,
  so a sniffer on a long-lived link follows every tag for as long as the link lasts, without catching a new HELLO. This
  is inside #286's accepted cost (the HELLO's `t` already names the tag).

## Amendment 2026-11-04: the debug bridge, the switch on, the harnesses (step 6, draft)

Status: draft, with step 6's code; the caller decides whether it stands.

- **The switch is on in debug builds.** `SettingsStore.DEBUG_BLIND_ADVERT_DEFAULT` is now true, so a debug build of this
  branch on a controller with extended advertising airs tags from its first bring-up. `…debug.ADVERT --ez blind false`
  stores it off (the advert goes clear at the next flip), `--ez clear true` puts the default back, and the reply's
  `switch.stored` shows a value a harness left behind. A release build still has no writer and reads the switch as on.
- **What the bridge may say.** `…debug.ADVERT` reports the gates (build flag, switch, extended advertising, a key held),
  the aired form (`clear` or `tag`), a per-process rotation count (`epoch`) and the last install's time, the turn (next
  edge, an announcement standing, a hold), the PSM while clear, each sighted peer's pre-link order (`iDial`, under its
  id or its handle) and each link's direction (`dialedByMe`). Every bridge reply is mirrored to logcat with a time, so
  neither reply carries a rotation cause, a tag source, `resolvable`/`stale`, a key generation, a tag or a handle beside
  an id: each of those, dated, would date a removal, a block or a clock move ("Counts, never dated causes", B1). A
  harness reads causes from `STATE`'s counts around the call. Extended advertising reads `null` until the adapter has
  been on (still sticky once true), and with no transport running both actions answer `status: ok` with the reason, so
  a harness can store the switch before the mesh starts.
- **No dated key lines in a release build** (security-reviewer, 2026-11-04, on Part A's code). The engine logged
  `presence key <step>: current g=<n>` at each new generation, `presence keys of <n> former direct contacts deleted` at
  each leaver sweep and `presence keys (<cause>): <n> frames…` at each pass that sent, all at Log.i with a time: a dated
  fresh pair, sweep or fan-out says when the user removed or blocked someone. They now go to the engine's per-decision
  trail with each pass's direct-contact count and whom keys were sealed to, and that trail goes to logcat only in a
  debug build (`BuildConfig.DEBUG`), for device trials, and to the mesh lab's reports. A release build logs none of it;
  the rotation stays counted. The maintainer accepted the debug cost (2026-11-04): a debug phone's logcat or bug report
  dates its removals and blocks, as the lab phones' have since Part A.
- **A forced rotation** (`…debug.ADVERT_ROTATE`, `AdvertRotation.Cause.FORCED`) is one unannounced rotation through
  the ordinary path, refused unless a tag is on the air with nothing pending, settling, announced or held, and none
  within the retired server's 30 s grace, so a loop cannot pile up servers or spend an announcement. It spends the slot's
  restart indexes durably: past four, every rotation or bring-up in that slot airs a tag no contact resolves until the
  edge, so a timed trial does not mix forced rotations with reps in one slot.
- **Who dials, for a harness.** Between two tags the larger tag dials, so the role turns every slot and at every
  bring-up (a Bluetooth toggle or a restart is one). `scripts/ble-link-trial.py` therefore records the role it observed
  (`bt connect ok` for the link that came back) and keeps the pre-stimulus `iDial` only as a prediction; `--toggle
  dialer|responder` needs clear adverts. A dial to an unresolved peer is logged under a handle, which the harness takes
  for the other phone only with every other Knit phone paused. `scripts/ble-pair-test.sh` turns the switch off on both
  emulators for the run and back to the default after (they air on real dongles) unless `KNIT_PAIR_BLIND=1`, since `BlePairTest.isSmaller` assumes the classic order and fails fast otherwise.
- **The knit-ios half** moves in a paired change: `interop.py` keys "the phone under test" off the bridge, not off advert
  bytes 1–16, takes the expected dialer from `iDial`, treats ` blind=legacy` from an un-updated iPhone as expected, and
  restores any switch it turned off; the iOS reader never caches a PSM per address across a tag change. Until it lands,
  a phone that harness drives with a debug build of this branch needs `ADVERT --ez blind false` first.
