---
id: "2026-09.9469"
slug: a-post-to-the-meshtastic-room-carries-no-author-name
title: "A post to the Meshtastic room carries no author name"
date: 2026-09-05
topics: [lora, meshtastic, privacy]
---

# ADR 2026-09.9469 — A post to the Meshtastic room carries no author name

Status: Accepted (2026-09-05; amends ADR 2026-09.26q3's outbound half, and withdraws ADR 049's one
exception). `PublicPostPolicy.onAirText`, `PublicChannelSink.postToPublicChannel`, the composer hint and
the consent sheet's scope line.

**What was observed.** A post left as `Alice: hello`. That prefix was written for the bridged design ADR
2026-09.7r4d described, where the pocket's ACTIVE gateway put *everybody's* posts on the air through one
board: with a whole clique behind `Knit a7c3`, the line was the only thing that told two Knit speakers
apart, and it was worth suspending ADR 049's rule that a human name never rides on the public band. ADR
2026-09.26q3 took that design out a day later — the room is a local mirror, each user posts through the
board paired to their own phone — and the prefix outlived the reason for it. The identity it was standing
in for is now carried twice over without it: a Knit reader lines `packet.from` up with the contact whose
profile claims that node number (`ProfileContent.loraNode` → `PeerRepository.findByLoraNode`), and a stock
client shows the board's own `Knit abcd`. What the prefix still did was put a real display name in
cleartext on a frequency whose traffic public MQTT servers store and index, once per post, for people the
user will never meet.

**What changed.** `onAirText(body)` — the body, trimmed to the 200-byte client convention, and nothing
else. `postToPublicChannel` takes only the body, so no name reaches the seam to be forgotten about later;
`MeshManager.sendPublicPost` no longer reads the display name at all. The composer hint is the destination
("Post to the radio channel") rather than the author, the draft's hard cap is the whole
`MAX_ON_AIR_BYTES` now that nothing shares the line, and the consent sheet's third line says what is
actually true: the name stays off the air, but the board's own does not, and anyone already holding the
user's contact card reads that back as them.

`displayBody` goes with it. It was the exact inverse of the prefix — strip a resolved contact's own
`"Sam: "` so the bubble did not say the name twice — and with no producer left it is a rule that edits
somebody else's words on a hunch: a stranger who *types* "Sam: hi" on their own radio would have had it
rewritten. The alternative a reader reaches for is keeping it as a compatibility stripper for older
senders. There are none worth the rule: the plane is `BuildConfig.LORA_PLANE`, debug-only, so the only
Knit boards on the air are lab boards, and the cost of leaving it in is permanent and points at content.
The lab's existing rows do show `Alice: hello` under Alice until they age out, which is the whole of what
this breaks.

**What it costs.** Two Knit users behind one board are indistinguishable again — but there is no such
shape any more: a phone posts through its own board or it cannot post. The residual worth naming is the
one ADR 2026-09.26q3 already carries and this narrows: attribution now rests *entirely* on a self-asserted
node number, with no name on the line to corroborate it, so a spoofed `from` puts words under a contact's
name with nothing to contradict it. The unverified styling and the room strip are still the only defence,
and Meshtastic 2.8's XEdDSA signature is still the roadmap item that would end it. Pinned by
`PublicPostPolicyTest` (the words alone, on air and at the cap), `LoraMeshTransportTest` (what the board is
handed), `MeshManagerTest.aQueuedPublicPostIsHandedToTheBoardAsTypedAndStoredAsOurOwnRow`,
`ChatViewModelTest.aHeardPostIsShownWordForWord`, and `ChatMeshRoomTest`'s hint and disclosure tests.
