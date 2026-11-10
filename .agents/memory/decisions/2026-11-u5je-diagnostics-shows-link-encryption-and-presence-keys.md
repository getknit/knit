---
id: "2026-11.u5je"
slug: diagnostics-shows-link-encryption-and-presence-keys
title: "Diagnostics shows link encryption and presence keys"
date: 2026-11-10
topics: [diagnostics, crypto]
---

# ADR 2026-11.u5je — Diagnostics shows link encryption and presence keys

Status: Accepted (2026-11-10)

**What was observed.** Two shipped features had no place on the Diagnostics screen: sealed links (ADR 2026-11.8mhs)
and presence keys with the blinded advert (ADR 2026-11.xp6d). The screen's only trace of either was a
`PRESENCE_KEY_REFUSED` drop reason. Rows had drifted too. The Direct link plane read "2 nearby · 2 linked", where both
numbers were its link count and "nearby" was false, since a link is never near (ADR 2026-10.a5yj). A contact on a direct
link sat under *Reachable long-range* with no tag, because the row's tag knew only BLE, NAN, LoRa and Internet. The
Coded PHY row still said "experiment" though it has been on in release since 2026-10-08 (ADR 2026-10.yvn6). And the node
lists sat at the very bottom, under forty metric rows, with debug switches scattered between the planes and who they
reach.

**What changed.**

- **Order.** This device, then *Reports and logs* (the crash and latch rows when present, the field log row always),
  Mesh controls, Transports with the rows that explain a real condition, the four reach tiers (ADR 2026-09.2ajk), the
  Internet relays, Mesh metrics, and *Debug options* last, shown only where a debug row is offered.
- **The link chip.** Each plane publishes `MeshTransport.linkSecurity`, node id to `LinkSecurity` (`SEALED`, `PROVEN`,
  `LEGACY`) for each live link, read from `FramedLink.security`; a direct link's lane is always AES-GCM, so its links are
  `SEALED`. `CompositeMeshTransport.linkSecurityByKind` merges them by plane, and `DiagnosticsViewModel.rowLinkSecurity`
  narrows them to the planes a row's section claims, as `NodeInfo.transports` is narrowed: short-range links on a direct
  row, a direct link on a long-range row, nothing on the others. The weakest claimed link wins, so a row never reads
  safer than a plain link beside a sealed one. "Encrypted" carries a lock; "Not encrypted" is outlined and its
  description is neutral, because an unproven claim sits in a plane's links until its proven twin closes it (shzv), so
  the row cannot know the cause.
- **No link is no chip, never "Not encrypted".** A Wi-Fi Aware link lasts one sync, so most of the time a NAN-only row
  has none. Each plane publishes the security map from the same snapshot of its links as `neighbors`, under one small
  lock, so a stale snapshot can never land after a fresh one (the replace that swaps an unproven link for a sealed one
  moves the chip while the neighbour set stays the same).
- **"rotating ID" on the Bluetooth row.** `MeshTransport.advertBlinded`, carried as `TransportStatus.blindedAdvert`,
  comes from what `AdvertWriter` has installed while the radio is up: false on `up()` and `down()`, the installed form's
  on `install()`. It is not `AdvertStatus.blinding`, which is what the writer should air and stays true through adapter
  off, stop and a pending bring-up. It says the advert's form, not that the advert is live; the health dot says that.
  Release shows nothing today, because `BLIND_ADVERT` is off there.
- **Presence keys.** *This device* says whether an own key is held (`MeshController.ownPresenceKeyHeld`, a Boolean; the
  key and its generation stay in `MeshManager`). A Key chip marks every row whose node is a direct contact whose key we
  hold (`PresenceKeyRepository.observePeersWithKeys`, ids only, intersected with `DirectContacts.observe`), and Mesh
  metrics shows "held N of M", keys sent and stored, and the faults (`presenceMarkNotFull`, `bleTagsUnresolvable`,
  `bleTagsStale`, the blind-drop reasons) once they happen. A debug build adds the reader's counts from `AdvertDiag`:
  recognised by key, by claim, expected tags, and the form on air.

The alternative a reader reaches for first is the debug bridge's `AdvertDiag` and `CodedPhyDiag` side objects for the
link state too. They exist so a debug lever adds nothing to the seam; seal state is a shipped feature on three planes,
so it rides the seam as `freeLinked` does, defaulted for every plane that makes no link.

**What it costs, and what it does not cover.** Nothing on any of these flows routes, sends, relays or custodies; they are
presentation only, and the composite derives them from the children's own flows. The screen never shows a key, a tag, a
generation, a mint time or a rotation count: a generation is a mint time, and any of them dates a rotation, so a
removal or a block (ADR 2026-11.xp6d). `presenceKeyRotations` and `bleRotationsByCause` stay off the screen for that
reason. "Recognised by claim" is not "recognised by key", so the debug row keeps the two apart, and no release row claims
a per-node recognition the planes do not publish. The chip describes the data-path link only: cues and DM-form chat on
the Wi-Fi Aware message plane never ride it. `DiagnosticsLinksAndKeysTest`, the link and presence cases in
`DiagnosticsViewModelTest`, `CompositeMeshTransportTest.linkSecurityMergesTheChildrenByNodeAndPlane` and
`statusesCarryABlindedAdvert`, `NanLinkViewTest` and the Bluetooth plane's own tests keep it true.
