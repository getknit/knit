---
id: "2026-09.4n5p"
slug: a-knit-board-answers-a-meshtastic-dm-once
title: "A Knit board answers a Meshtastic DM once"
date: 2026-09-19
topics: [lora, meshtastic, provisioning]
---

# ADR 2026-09.4n5p — A Knit board answers a Meshtastic DM once

Status: Accepted (2026-09-19)

**What was observed.** ADR 2026-09.emd7 marks a set-up board `is_unmessagable`, and stopped there. The
mark is a `NodeInfo` hint: it reaches a neighbour only on the board's own node-info broadcast, which the
same setup quiets to 6 h, and only clients that honour the flag (Android/iOS 2.6.9+) grey the node out.
Everybody else — an older client, a web client, a neighbour who cached the node before the setup ran —
still sees `Knit abcd` as a node to message. Their DM arrives, the firmware's routing layer ACKs it, and
`LoraMeshTransport.onLoraPacket` dropped it: a unicast `TEXT_MESSAGE_APP` on index 0 fell into the room's
branch and out again as `PublicChannelPolicy.Refusal.NOT_BROADCAST`, counted and unread. The sender's app
showed a **delivered** tick against words nobody would ever see, and they had no way to learn why. The
mark says what the board is to the clients that ask; nothing said it to the person who did not.

**What changed.** A text addressed to the board itself is now routed, ahead of the room's branch and on
the portnum and the address alone, to `LoraMeshTransport.onDirectMessage`, which answers it once with a
fixed line (`DmAutoReplyPolicy.TEXT`, 149 bytes of ASCII): the node is unmonitored, nobody reads messages
sent to it, it is part of a Knit mesh, and where Knit lives. The reply is the room's own write —
`Destination.Reply`, index 0, `TEXT_MESSAGE_APP`, the shared pacer, `AirBucket.PUBLIC` — with the
sender's node number in `to`, which is all a unicast needs: the firmware's `Router::perhapsEncode` sees a
unicast on channel 0 and encrypts it to that node's public key when it holds one (it does, having just
decrypted their DM), else to the primary's PSK on a board too old for PKI. `MeshtasticLink.send` and
`OutboundFrame` gained the address; nothing else on the plane sets it.

Why the routing is on `to` and never the channel index: on 2.5+ firmware a DM is PKI and `perhapsDecode`
reports it on index 0 whatever slot the sender used, while a pre-PKI board reports the slot it decrypted
on; and current firmware refuses a channel-keyed ("legacy") DM outright, so the only DM a modern board
hands us is a PKI one. The reply therefore always goes on index 0 — reply in kind — rather than on the
index the packet arrived with.

**Politeness is two caps, and the persistence of one.** `DmAutoReplyPolicy` answers a sender **once per
24 h** (`PER_SENDER_MS`, a `SeenSet` keyed by `!hex` node id, 256 entries) and anybody **once per 30 s**
(`FLOOR_MS`, the public post's own floor on its own timestamp — an auto-reply must never make the user's
next post wait, nor the other way round). The floor is asked *before* the sender is stamped, so a sender
refused for the floor has not had their answer and their next message earns it; a sender who has been
answered gets silence for the day, which is also what breaks a loop with a neighbour's bot that answers
our answer. The per-sender memory rides `LoraPlaneSnapshot.autoReplied` (ADR 2026-09.7svb's seam),
because the board replays its queue on reconnect and a restart is a reconnect: without it the same
message was answered twice. The air is the room's share (`PUBLIC`, 15 % of the window), asked at the
decision like `postToPublicChannel` does, so a reply the window will not carry is `NO_AIR` now rather than
queued to look sent — and priced as a signed post, which over-charges a PKI unicast (12 B of overhead, not
66) by the conservative error.

**The gates are the room's plus one.** `DEDICATED` (ADR 067: no public radio can hear a pinned board, so
a text addressed to it is another pinned Knit board's, and the line would tell a Knit user their own node
is unmonitored), `KNIT_ON_PRIMARY` (no primary to answer on), and **`NOT_SET_UP`**: the board must carry
the Knit channel on its bound slot. The alternative — answer from any paired board, since Knit reads none
of its DMs either — was rejected because the setup's confirmation sheet (`lora_setup_confirm_body`) is
where the user agreed to their board saying it is unmonitored; a paired stock board never showed it and
stays as quiet as a stock board. No settings switch: the reply says exactly what the mark the user
consented to says, carries no name and no hint of who paired the board (ADR 049's rule for the public
frequency), and costs one packet a day per curious neighbour.

**What it costs.** ~1.5 s of air at LongFast per reply, out of the room's share. A sender with no public
key on file (a pre-PKI neighbour on a modern board — refused at decode, so unreachable in practice) gets a
`NAK` (`PKI_UNKNOWN_PUBKEY`) and is counted, not retried. The text is fixed English; localising it would
mean threading a resource through a pure package for a line whose two nouns are a product name and a
URL.

**What it does not cover.** A neighbour who DMs the board while Knit is not connected to it — the
Meshtastic app on the phone reads that DM itself, and answers nothing. The room switch
(`loraRoomEnabled`) does not gate the reply: the switch is about what this phone *shows*, and a DM is
never shown either way.

**The trap the next person will hit.** `boundSlotIsKnit` reads an **empty** channel table as "don't
suppress", which is right for Knit's own frames (silence is the safe answer there) and wrong here (a
transmission on the user's behalf); `autoReplyRefusal` reads it as `NOT_SET_UP` on purpose. And the
`FakeMeshtasticLink`'s own node number is `1u` in the transport rig — a fabricated DM `from = 0x1u` is
`OWN_BOARD`, not a stranger.

Regressions: `DmAutoReplyPolicyTest` (the caps, the restore, the bound), `LoraMeshTransportTest`'s
`aMeshtasticDmToTheBoardIsAnsweredOnceAndNeverEntersTheRoom`, `aBurstOfDmsIsOneReplyAtATime`,
`aBoardOnADedicatedSlotAnswersNoDm`, `aBoardKnitNeverSetUpAnswersNoDm`,
`aDmTheRoomsAirShareCannotCarryIsRefusedNotQueued`, `theSendersAnsweredOutliveTheProcess`. Counters:
`autoReplyHeard` / `autoReplySent` / `autoReplyRefusedByReason` in `…debug.LORA`. Not yet seen on
hardware: a device trial needs a third, stock node to DM a Knit board — CHECK `roadmap.md`.
