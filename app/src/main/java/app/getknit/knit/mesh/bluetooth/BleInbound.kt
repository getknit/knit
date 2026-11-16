package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.InboundFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.merge

/**
 * The Bluetooth plane's queue toward its router, in two lanes (#331, ADR 2026-11.43f7). Pure, so `LinkInboundTest` reads
 * it as the plane does.
 *
 * [links] is the links' lane: each link's reader hands its frames in through a [LinkInbound], and waits for room when it
 * is full. [page] is the side channel's lane, which never waits: a scan callback cannot. One shared queue would shut pages
 * out while any reader waits, since a [MutableSharedFlow] gives every freed slot to its waiting emitters before a
 * `tryEmit`, so one linked peer flooding its link would starve every page for as long as the flood lasted, and pages from
 * senders we hold no link to are the ones custody cannot repair (the security-reviewer's finding). With a lane of its
 * own a page is refused only when its own [pageSlots] are full.
 *
 * [flow] merges the two through a rendezvous, so the plane queues no more than the two lanes hold, and a router that is
 * behind takes from each lane in turn: the merge's two collectors wait on one channel, which serves its senders in order.
 */
class BleInbound(
    linkSlots: Int = LINK_SLOTS,
    pageSlots: Int = PAGE_SLOTS,
) {
    /** The links' lane: a reader that finds it full waits ([LinkInbound]). */
    val links = MutableSharedFlow<InboundFrame>(extraBufferCapacity = linkSlots)

    private val pages = MutableSharedFlow<InboundFrame>(extraBufferCapacity = pageSlots)

    /** What the plane's `inbound` is: both lanes, a frame at a time. */
    val flow: Flow<InboundFrame> = merge(links, pages).buffer(Channel.RENDEZVOUS)

    /** Hands a page's frame in, or false when the pages' lane is full: it never waits, and never on a link. */
    fun page(frame: InboundFrame): Boolean = pages.tryEmit(frame)

    companion object {
        /** The links' lane: the 256 the plane's single queue held before #331. */
        const val LINK_SLOTS = 256

        /** The pages' lane: the side carousel's capacity, which is what a peer can page at once. */
        const val PAGE_SLOTS = 32
    }
}
