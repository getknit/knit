package app.getknit.knit.mesh.bluetooth

/**
 * The connect engine's in-flight bookings (`BluetoothMeshTransport.inFlight`, dial key to [DialPlan.InFlight]) as a dial
 * from a stopped session releases them (#292). Pure; the caller holds the lock that guards the map.
 */
internal object DialBookings {
    /**
     * Releases [dialKey]'s booking in [inFlight] only while it is still [booked] itself, compared by identity (#292's
     * review): [DialPlan.InFlight] is a data class, so a dial that outlived a stop and the next session's dial to the same
     * peer, kind and address hold equal records, and a release by `equals` would free the live session's booking and let
     * it dial twice (a stalled L2CAP connect can outlive a stop by about 21 s, 8fb6179a). True when it released.
     */
    fun releaseOwn(
        inFlight: MutableMap<String, DialPlan.InFlight>,
        dialKey: String,
        booked: DialPlan.InFlight,
    ): Boolean {
        if (inFlight[dialKey] !== booked) return false
        inFlight.remove(dialKey)
        return true
    }
}
