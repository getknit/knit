---
id: "056"
slug: the-key-bootstrap-gets-a-share-of-the-window
title: "The key bootstrap gets a share of the window, not an exemption from it"
date: 2026-08-28
topics: [lora, crypto, airtime]
---

# ADR 056 — The key bootstrap gets a share of the window, not an exemption from it

Status: Superseded by ADR 2026-10.rp3s (2026-10-29) — no profile rides the plane, so the BOOTSTRAP share is gone.

ADR 044 gave the LoRa plane an airtime governor and carved out one class from it: a `FrameClass.BOOTSTRAP`
frame — a `profile`, ours or a relayed one — was admitted unconditionally. The argument was sound. Nothing a
peer sends verifies without its author's profile, so a window that refuses the profile costs more airtime
than it saves: every frame that peer sends afterwards is undecodable and re-served forever.

The hole was that `admits` returned `true` while `record` still booked the cost. An exemption from admission
is not an exemption from spending, so profiles could — and did — drive `liveUsedMs` past `liveBudgetMs` on
their own, after which every DM, room post and tick on the plane was refused for up to a window.

**What the lab gateway had actually been doing.** A field test reported the budget reading 100 % after two
authored messages. The counters said why. Of 376 LoRa frames the Pixel 9 had ever sent, 63 were DM-form and
17 were gossip offers; the remaining **296 — 79 % — were profiles**, and all but one of them were fragmented,
which a single-packet room post never is. At ~4.75 s of air for a 453-B profile that is ~1400 s against ~240 s
for every DM the phone had ever sent. The second gateway showed the same shape at 59 %. The two authored
messages were ~6.5 s of a 45 s window; the rest was a class nobody was metering.

The mechanism is a dedup that outlives nothing. A relayed profile is gated only by the transport's
`sigSeen`, whose TTL is 10 minutes — the same 10 minutes as `MeshRouter`'s `SeenSet`. So a profile that keeps
arriving looks first-seen again on every lapse, re-fans, and rides LoRa every 10 minutes indefinitely.
`LoraFramePolicy.isFresh` exempts non-chat types by design (a profile's `sentAt` is a publish stamp up to 12 h
old), so age never stops it either.

**The decision.** Keep the exemption, bound it. `AirBucket` gains a third member, `BOOTSTRAP`, and
`AirBucket.defaultFor(klass)` pairs it with the class so no call site can book a profile against `LIVE` by
accident. `admits` judges a bootstrap frame against `BOOTSTRAP_SHARE` (0.25) of the allowance **alone**,
never against the total — it still rides when the window is otherwise spent, which is the whole point — while
every other class is judged against a total that now includes what the bootstrap spent. A quarter is two
profiles per 15-minute window and eight an hour, well past what a bootstrap needs given our own beacon's
5-minute floor, and it leaves three quarters of every window to traffic somebody is waiting for.

A refused profile is **deferred, not dropped**: `LoraPacePolicy.take` already skips an over-budget frame and
leaves it queued, and `BOOTSTRAP` is still the highest queue-shedding class, so the frame that loses its
window is first out of the next one. Dropping the key bootstrap is the failure the exemption existed to
prevent, and it is not what this trades away.

The backfilled profile is deliberately **not** in the new bucket: `serveOne` keeps passing `AirBucket.BRIDGE`,
because a re-served profile is history like everything else the bridge carries, and
`LoraFramePolicy.backfillRank` already puts profiles first within that share.

`AirtimeSnapshot` carries `bootstrapUsedMs`/`bootstrapBudgetMs`; the settings row's percentage and the chat
saturation notice sum all three buckets (the bootstrap's air is real air), and `…debug.LORA` reports
`bootstrapMs`/`bootstrapBudgetMs` beside the other two.

**The 10-minute re-fan itself is ADR 057**, landed straight after this one. The cap alone converts it from
"blanks the plane" into "spends its own quarter, forever", which is bounded but still mostly redundant.
