---
id: "2026-09.7r4d"
slug: a-post-typed-in-the-bridged-room-is-the-same-frame-with-no-speaker
title: "A post typed in the bridged room is the same frame with no speaker"
date: 2026-09-04
topics: [lora, meshtastic, mesh]
---

# ADR 2026-09.7r4d — A post typed in the bridged room is the same frame with no speaker

Status: Superseded by ADR 2026-09.26q3 (2026-09-05) — a post leaves through the author's own board only; the `meshpost` frame is withdrawn. Was: Accepted (2026-09-04) — phase 2 (outbound) of work item #37, on ADR 2026-09.cf7a's phase 1.

**What was observed.** Phase 1 read the foreign mesh's public primary into its own room and stopped there,
with the composer replaced by a line saying Knit does not post to this channel — "reading a foreign public
channel and speaking on it are separate decisions, and only the first has been made." The lab trial then made
the first decision pay: on 2026-09-04 one real LongFast post ingested cleanly (`meshPostHeard 1 / Ingested 1`,
`refusedByReason {}`, custody converged across three devices at `liveFingerprint 5478559464770187787`, zero
airtime). The volume that was supposed to decide the outbound quota never materialised — a rural
neighbourhood carries roughly one post an hour — so the quota below is set from the *cost* side instead: what
a bridge may politely spend on a shared band, not what the room would fill.

**What changed.** A post typed in the room is an ordinary `meshpost` frame, authored and signed by the person
who wrote it, with `node` and `packetId` **absent**. `MeshPostContent.node == null` is the discriminator, and
almost the whole feature falls out of it:

- **No new frame type**, so no second entry on `FrameType.isCustodial` and no second ADR 006 divergence. The
  room routing, the 6 h custody bucket, the no-receipt rule and the "never back on the LoRa band" exclusions
  on all three paths already hold for `meshpost` — and not one of them would have held for a `chat` frame
  with a room field on it, which is the shape a reader reaches for first.
- **No UI branch.** `handleMeshPost` builds a `MeshPostOrigin` only when `node` is non-null, so an authored
  post writes no `origin*` columns — and every rule that asks whether a row is bridged already reads
  `originNode == null`: `mine`, the avatar, the chat list's `isOurs()`, the preview. It renders as an
  ordinary outgoing message with nothing added anywhere.
- **The gateway observes rather than is asked.** Any phone in the pocket may post; the frame floods over the
  short-range planes as it would anyway, and the ACTIVE gateway transmits the ones it sees arrive. §5's rule
  — *written here, or heard over a short-range plane* — is then one condition on `handleMeshPost`, not a
  second protocol. A post that arrived over LoRa was already on that band; one off a spool came from outside
  the neighbourhood entirely, and re-posting either makes every far pocket a repeater.

**One transmission, not two.** §5 has the post ride the Knit LoRa channel *as well*, so far pockets see it as
a verified Knit post; that costs about twice the air and needs LoRa eligibility to depend on frame content
rather than type. Instead `meshpost` stays off the LoRa plane exactly as phase 1 left it: a far pocket's board
hears the LongFast packet directly and mints its own row from it. One packet serves the neighbourhood, and
what a far pocket loses is only that the post is attributed to `Knit abcd` rather than to its author — honest
enough on a band where nothing is authenticated at all. This is the "single-copy variant" the work item
deferred as a later optimisation, taken now because it is *simpler* than the two-copy design rather than
harder.

**`Alice: hello`, and the consent that buys it.** ADR 049 keeps the user's display name off the public band —
the board is `Knit abcd` to everyone listening, never the person — and this is its single deliberate
exception, made per-room and per-user rather than by the board's standing broadcast. Without it every Knit
user behind one board is indistinguishable, which on a channel whose whole content is conversation makes the
bridge useless in the direction that matters. So: a first-use bottom sheet modelled on the Internet plane's
(`SettingsStore.meshtasticPostConsented`, one sticky bit, no paired kill switch because nothing here happens
unless somebody types), and the composer hint names the author — *Post as Alice* — so the exception is stated
before a word is typed rather than after it has left. The name rides **in the frame** rather than being
resolved by the gateway: a gateway that has never seen the author's profile would otherwise put a node id on
the air, and two gateways with different views of the directory would put different text there.

**The length rule lives in the composer, not only on the wire (2026-09-04, same day).** `PublicPostPolicy`
trims a long post to 200 bytes on its way to the air, and that trim is *silent* — worse, it happens on
whichever phone in the pocket owns the board, which under "the gateway observes rather than is asked" need
not be the phone that typed the post. So a sentence cut in half goes out under its author's name with
nothing on the author's screen ever having said so. The field now refuses the byte instead
(`PublicPostPolicy.bodyBudget`, the same arithmetic `onAirText` does, minus the `Alice: ` the author's own
name will occupy), counted in **UTF-8 bytes** because the frame is — `InputTransformation.maxLength` counts
UTF-16 units and would wave fifty emoji through a two-hundred-byte line — with a `183/193` counter over the
send button for the last 40 B, so the field is never just unresponsive. Both halves, because the cap is
short and surprising: a bare remainder says how much room is left without ever saying what the room was. The wire-side trim stays as the net under it. This also takes
the room out of `loraCarryFor`: it was falling through to the DM arm, so it wore the DM's 320-byte *hint*
and followed the private-messages-over-LoRa switch, neither of which governs anything here.

**And the composer accepts no pictures, which is a thing you have to say to the keyboard.** `sendPublicPost`
takes a string, so anything staged beside the draft is dropped at send — silently, again. Hiding the picker
was not enough: what advertises image support to the IME is the *presence* of a `contentReceiver`, so a
field that keeps one and refuses what arrives still offers Gboard's GIF and sticker tabs. The modifier is
left off entirely in this room, which is what turns those tabs into "images not supported here", and
`ChatViewModel.stage` refuses at the funnel for the route that does not pass the composer — the share sheet
lists the room, because sharing *text* into it is a fair thing to want. Link-preview cards are attachments
too, and are gated on the same flag.

**Two guards, one queue.** `boundSlotIsKnit` is untouched. It exists to keep Knit's cleartext frames off the
public channel *by accident* and reads an unknown channel table as "stay silent"; the outbound path is a
consented route *to* that channel and reads the same unknown table as "not the stock primary" — same shape,
opposite safe answer, so sharing code between them would make one of the two wrong (`OutboundFrame.Destination`
picks between them at the write). They do share the pacer: one duty-cycle ledger, one 3 s inter-packet gap,
one NAK back-off, one `queueFree`. A post that bypassed it would be a second transmitter on one radio.

**What it costs.** `AirBucket.PUBLIC` at 15 % of the window, charged against the total like every other
bucket, plus a 30 s per-gateway floor (`PUBLIC_POST_FLOOR_MS`) claimed at the *decision* rather than at the
write — otherwise a burst queues behind one inter-packet gap and goes out anyway. The floor binds first by
design: a refusal a user can predict beats one that depends on what the rest of the plane was doing, and a
whole pocket of Knit users stays one voice every 30 s rather than one each. A 200-byte post sits under the
2.8 firmware's signing cliff, so the board attaches 66 bytes Knit never asked for; ADR 2026-09.mhs5 pads
Knit's own frames past that cliff to dodge it, and a human-readable post cannot be padded, so the budget pays
for it (`LoraAirtime.timeOnAirMs` already prices it — `aPublicPostIsPricedWithTheSignatureTheFirmwareWillAddToIt`).

**A decoder flag day, which `docs/WIRE_COMPAT.md` had no precedent for.** Relaxing `node`/`packetId` from
required to optional means a phase-1 build fails `decodePayload` on an authored post and renders nothing. It
is **not** a divergence — custody and relay run outside `dispatchByType`, so the frame is still stored,
counted in the digest and forwarded — but it is a flag day on the decoder, and the rule this adds is that
those are different things. Confined to the lab fleet, which reflashes together, and to a plane that is
`BuildConfig.LORA_PLANE`-gated anyway.

**What it does not cover, and the trap.** There is **no per-post confirmation that a post reached the air**:
a phone with no gateway in its pocket posts into Knit and nothing goes out, silently. That is the price of
letting any phone post, and the fix — a gateway-minted air receipt — wants its own decision. `ack = false`
stays for both shapes deliberately: a ✓✓ here would mean another phone in this pocket holds it, never that it
reached the air, which is the one thing the sender actually wants to know. The consent bit is one-way, with
no settings toggle to revoke it. And the trap: `ConversationKind.MESHTASTIC` fell through both arms of
`MeshManager.sendTyping` and would have minted a **Nearby** typing cue the moment a composer existed here —
unreachable in phase 1 only because there was none. Guarded in both places now
(`theBridgedRoomNeverSendsATypingCue`), because the UI guard states the intent and the mint guard is the net
under it.

**The echo case is narrow on purpose.** `LongFastPolicy.Refusal.OWN_BOARD` refuses a packet from our own
board's node number — and *only* that, never every radio in `boardsHeard`, which §5 proposed. With one
transmission a far pocket's board is exactly how our post reaches those people, and `boardsHeard` includes the
far-pocket boards this one is LoRa-bridged to; refusing them would make our own posts invisible to the pockets
they were written for (`ourOwnBoardsTransmissionIsNeverReadBackInAsSomebodyElsesPost`).

**Two ACTIVE gateways is not the seconds-wide flap this ADR first claimed.** Verified on hardware the same
day: the lab pocket held two ACTIVE boards *indefinitely*, and the first outbound post went on the air twice,
each board then hearing the other's copy and re-ingesting it — one message rendered as three, with the user's
own words attributed back to them by two `Knit abcd` strangers. `OWN_BOARD` behaved correctly throughout; it
is scoped to our own board and cannot see a pocket-mate that also believes it is the gateway. The cause was a
starved gossip offer, fixed separately (ADR 2026-09.xdm2), and this feature is what made a latent election
failure expensive: phase 1 only *minted* on ACTIVE, so a stuck election produced duplicate frames that
collapsed on the derived id and nobody saw. So the standing residual is narrower but real — **outbound is only
as correct as the election** — and the air receipt is what would make a duplicate transmission visible rather
than merely wrong.
