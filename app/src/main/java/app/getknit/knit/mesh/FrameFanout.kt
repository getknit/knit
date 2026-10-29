package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope

// Package-internal frame-propagation knobs shared by MeshManager's outbound origination (originateSigned)
// and InboundPipeline's inbound relay (onDeliver/onCarriedFrame). Lifted to top level so a single
// definition is reachable from both classes without either depending on the other's companion.

/**
 * Whether [env] should *also* ride the [MeshTransport.fastFanout] coordination-plane fast path — a
 * best-effort fan-out to every neighbor at once with **no data path** — on top of the normal flood and
 * store-and-forward custody. It's for small, flood-to-everyone frames: the plaintext broadcast room plus the
 * cleartext metadata frames (reactions, delivery receipts, group roster updates/leaves, profiles) — exactly
 * the non-chat [FrameType.isCustodial] types. In an idle cue-driven mesh no NDP is up, so a plain flood
 * reaches the ≤1 live neighbor (usually zero); the fast path delivers to every neighbor at once and custody
 * backstops any peer that was away. Reused on both origination (`originateSigned`) and relay (`onDeliver`),
 * so a frame hops the mesh at message-plane speed rather than only one hop from the originator. E2E DM/group
 * *chat* frames are excluded (the broadcast-only arm): they carry wrapped keys and won't fit the ~255 B
 * channel, so they ride the NDP flood + custody. The transport still size-gates (no-op if a frame won't
 * fit), and the receiver's SeenSet dedups any copy that also arrives over the flood/custody backstop.
 */
internal fun shouldFastFanout(env: RelayEnvelope): Boolean =
    when (env.type) {
        FrameType.CHAT -> env.recipientId == null && env.group == null

        // broadcast room only (DM/group are E2E)
        else -> FrameType.isCustodial(env.type) // reaction/receipt/group-*/profile; blobreq/keyreq excluded
    }

/**
 * Whether [env] should *also* ride [MeshTransport.fastSend] — the **targeted** coordination-plane sibling of
 * [shouldFastFanout], admitting exactly the DM-form chat frames that one excludes. Targeted, never fanned:
 * one send to the addressee, so a sealed frame is not sprayed at neighbors with no business holding it and
 * the ~255 B channel carries one copy rather than N.
 *
 * The size reasoning [shouldFastFanout] gives for excluding this form ("won't fit the ~255 B channel") does
 * not survive contact with the rest of the tree: `AckSync` already sends **sealed receipts** over
 * [MeshTransport.fastSend], and a sealed receipt is wire-indistinguishable from a real DM (ADR 016/018) —
 * same type, same recipient, same sealed payload. The transport compacts and fragments to
 * `FastFrameCodec.MAX_PARTS`, so the real budget is ~753 B, which covers a short-to-medium DM; anything
 * larger fails the encoder's size gate and no-ops back onto custody, exactly as today.
 *
 * Why it matters: with no NDP and no Bluetooth a node's broadcast room chat still flows over this plane
 * while every DM, group-key seed and `CTL_GROUP_KEY_REQ` — all this same form — sits undeliverable in its
 * own custody with the peer sitting in `cueTarget` the whole time (three-Pixel capture, 2026-09-07;
 * ADR 2026-09.9dnk). The LoRa plane, which has no data path at all, used to admit the same set through a
 * long-range fan-out of its own; since ADR 2026-10.x9fa it carries none of these frames — our own DMs, reactions
 * and ✓✓ leave it as compact pair-sealed unicasts their sender offers — so this is the one targeted predicate.
 *
 * Best-effort and unacknowledged, like the rest of the fast path — a latency layer over the NDP flood +
 * custody, never a replacement for it, and the receiver's SeenSet drops whichever copy loses the race.
 */
internal fun shouldFastSend(env: RelayEnvelope): Boolean = env.type == FrameType.CHAT && env.recipientId != null && env.group == null

/**
 * Pull-time soft cap on bytes held *purely* to custody other peers' images (a carried frame references
 * them but no local message does — see [app.getknit.knit.data.blob.BlobDao.carrierOnlyBlobBytes]). Our
 * own/received images are uncapped (kept via their message row); this bounds only the altruistic relay
 * footprint. Because these blobs are NOT folded into the content digest, this is a purely local knob and
 * need not match across nodes, so it can later be made adaptive to free storage without any convergence
 * risk. Read inbound by `InboundPipeline.onCarriedFrame` and outbound by `MeshManager.resumePendingFetches`.
 */
internal const val CARRIER_BLOB_BUDGET_BYTES = 128L * 1024 * 1024
