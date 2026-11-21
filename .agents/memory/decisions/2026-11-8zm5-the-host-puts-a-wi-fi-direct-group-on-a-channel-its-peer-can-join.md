---
id: "2026-11.8zm5"
slug: the-host-puts-a-wi-fi-direct-group-on-a-channel-its-peer-can-join
title: "The host puts a Wi-Fi Direct group on a channel its peer can join"
date: 2026-11-20
topics: [transfer, calls, wire]
---

# ADR 2026-11.8zm5 — The host puts a Wi-Fi Direct group on a channel its peer can join

Status: Accepted (2026-11-20; `transfer/DirectChannels`, `DirectChannelView`, `GroupChannel`, `DirectWifi.channels`
and `host(…, peerChannels)`, `AndroidDirectWifi`, `TransferPayload.ch`, `CallPayload.ch`, `CallDirect.clientChannels`,
`LiveTransfer.peerChannels`) — amends ADR 2026-09.wtmz, whose "pinning the channel was considered and declined"
no longer holds where the peer cannot join the host's channel

**Context:** in a device trial of peer updates (2026-11-20, then unmerged) every Wi-Fi Direct transfer from a Pixel 9 to
a Pixel 7 failed with `REASON_JOIN_FAILED`, and a plain 1 MB `…debug.XFER` failed the same way. It looked like an
update bug, then like a flaky join. Verbose supplicant logs showed a radio fact. The home access point had moved to
5500 MHz (channel 100, DFS). The host asked for `GROUP_OWNER_BAND_AUTO`, so its group followed its own Wi-Fi onto
that channel. The Pixel 9's supplicant allows Wi-Fi Direct on DFS channels (`p2p_dfs_chan_enable=1`), and the Pixel 7's does
not (`p2p_dfs: … is_dfs_allowed 0`): it scanned 5500, never saw the group, and gave up each attempt with
`FORMATION_FAILED`. The framework agrees with each supplicant. `WifiManager.getUsableChannels(…,
OP_MODE_WIFI_DIRECT_CLI)` on the Pixel 7 lists no DFS channel at all (2.4 GHz, 5180–5240, 5745–5825), while the
Pixel 9's lists 5500. ADR 2026-09.wtmz declined pinning because a group off the station's channel is "slow rather
than broken". For this pair the unpinned group is broken, and so is every Wi-Fi Direct feature: a Direct transfer,
a peer update, the direct leg of a call.

**Decision:** the joiner says where it can join, and the host puts the group there.

- **On the wire:** the receiver's ACCEPT, and the callee's RINGING or ANSWER that carries `dx = DIRECT_JOINING`,
  carry `ch`: the frequencies `getUsableChannels(WIFI_BAND_BOTH_WITH_DFS, OP_MODE_WIFI_DIRECT_CLI)` returns, packed
  as a bitmask over an append-only table of 41 frequencies (2.4 GHz channels 1–13, 5 GHz 36–64, 100–144 and
  149–177). The table may grow to 63 entries, so the mask never sets the sign bit.
- **The host's choice** is `DirectChannels.choose(hostSta, client, hostGo)`:
  - `Auto` (today's behaviour) when the peer can join on the host's own Wi-Fi channel, so a pair that shares a DFS
    channel keeps single-channel speed;
  - otherwise the best frequency both can use: 5 GHz off DFS (UNII-1, then UNII-3), then DFS, then 2.4 GHz's three
    clear channels;
  - `Band24` when they share none.
- **A peer that did not say** (an older build, or one below API 34, where `getUsableChannels` does not exist) gets
  `Auto`, unless the host's Wi-Fi sits on DFS. Then the host uses its first 5 GHz frequency off DFS, or 2.4 GHz when
  it cannot list its own: a slower group beats one nobody finds.
- **A pinned frequency costs one try, not the transfer:** a pinned group that does not form within `groupUpMs` is
  cancelled (`cancelConnect`), removed and raised once more on 2.4 GHz. `removeGroup` alone does not stop a creation
  still in progress, and the retry then met `BUSY` (a Redmi Note 13 on Device Farm, 2026-11-21). A refused pinned
  request is raised again on 2.4 GHz with nothing cleared: it started nothing, and a cancel could end another app's
  negotiation, since the phone has one P2P state machine. The choice and its reason are logged
  (`group channel=… sta=… peerChannels=… (reason)` under `KnitTransfer`).

The cost of the field, against the `wire-fields-byte-efficient` rule:

| Encoding | Bytes on the frame |
| --- | --- |
| `ch` bitmask, every table bit set | 12 (the key, 3, and a uint64, 9) |
| A list of frequencies, as `md` does | about 80–125 |
| Absent (an older build, API < 34, no Wi-Fi) | 0 |

No capability bit (ADR 2026-11.ejn8's rule): an older receiver sends no `ch` and gets the old group, and an older
host ignores the field. Not a paired change, since knit-ios has no Wi-Fi Direct.

The alternatives, and why each falls short:

- **Always pin a channel off DFS.** This fixes the pair, but it takes a Pixel 9 pair (or any pair that shares the
  access point's channel) off single-channel speed for nothing. wtmz measured that cost at about 9×.
- **Always 2.4 GHz.** Every phone joins it, but it was the slowest group measured (below).
- **Read the peer's supplicant flag.** No API exposes it, and the usable-channel list already reflects it.
- **The host tries AUTO, then re-hosts on failure.** That costs a whole `TransferDialer` round (four joins, about
  15 s) before anything changes. And the host learns of the failure only from a peer that gives up, which a call's
  callee never reports.

**Consequences / limits:**

- **Speed off the station's channel.** Measured 2026-11-20 with 250 MB Pixel 9 → Pixel 7, both stations on 5500:
  5180 MHz took 60.7 s (4.3 MB/s) and a forced 2.4 GHz group (2462) took 111.2 s (2.4 MB/s). Single-channel 5 GHz
  trials ran at about 70 MB/s. A pinned group works, but at roughly a sixteenth of the speed, because both radios
  time-share between the access point and the group. That is why `Auto` stays whenever the peer can use the host's
  channel. These numbers are that day's, on one pair and one access point.
- **API 29–33 receivers cannot say, and nor can some on 34+.** On Device Farm (2026-11-21) `getUsableChannels`
  answered nothing on two MediaTek phones, a Galaxy A16 on 34 and a Redmi Note 13+ on 35. For all of them only the
  host-on-DFS rule protects them, and they then get a group off DFS whether or not they need it. A host that cannot
  list its own channels falls back to 2.4 GHz.
- **What the farm's phones did** (`DirectGroupProbeTest`, 2026-11-21, eight phones from Android 10 to 17): a pinned
  5180 formed on 5180 everywhere, beside a station on 5220, 5765 or 5805 too; `Auto` followed the station; a frequency
  missing from the phone's own list was refused within about 2 s and the group moved to 2.4 GHz, except on the Redmi
  Note 13, which answered only by the 15 s timeout. The pair this ADR began with is not only Pixels': the Galaxy S25
  Ultra can host a group on DFS, and the Galaxy S26 cannot join one.
- **Stale or wrong answers.** The receiver's list is read at accept time. A list that turns out wrong (a regulatory
  domain change, a firmware that lies) costs the one 2.4 GHz retry, never a silent hang.
- **Untouched.** The joiner still finds the group by name and scans for it, so `READY` is unchanged, and so is the
  6 GHz band (outside the table, so never said).
- **Guards:**
  - `DirectChannelsTest` pins the policy on the two phones' real lists, the mask's round trip and the sign bit;
  - `TransferManagerTest` (`theReceiverSaysWhereItCanJoinAndTheHostHostsWithIt`,
    `aReceiverThatCannotSayLeavesTheChannelToTheHost`) and `CallManagerTest`
    (`theCalleeSaysWhereItCanJoinAndTheCallerHostsThere`) pin the plumbing;
  - `MessageContentTest.aJoinersChannelsCostAtMostTwelveBytesAndNothingWhenUnsaid` pins the cost;
  - golden vectors `messageContentTransferAcceptChannels` and `messageContentCallRingingChannels` pin the bytes.
  - `DirectChannelsTest` also sweeps `choose` over every kind of channel against these rules, freezes the table's
    bit for each frequency, and pins the 2.4 GHz retry (`DirectChannels.raise`);
  - the platform reads, and where each kind of group forms on a phone, are `DirectGroupProbeTest`'s, on Device Farm
    phones. `…debug.XFER` dumps `channels: {sta, client, go}` for a two-phone trial.
