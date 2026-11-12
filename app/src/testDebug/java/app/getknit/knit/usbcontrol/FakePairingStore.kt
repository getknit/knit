package app.getknit.knit.usbcontrol

/** The paired computers in memory, with the Keystore's two refusals to throw at a test. */
internal class FakePairingStore : PairingStore {
    var list: List<Pairing> = emptyList()

    /** Reads as [Stored.Unavailable] and refuses every write: the Keystore refused and proved nothing. */
    var unavailable = false

    /** Reads, but refuses to write. */
    var refuseWrites = false
    var writes = 0

    /** Runs once, right after the next [read] has taken its copy: another thread's change landing in that gap. */
    var afterNextRead: (() -> Unit)? = null

    override fun read(): Stored {
        val stored = if (unavailable) Stored.Unavailable else Stored.Ok(list)
        afterNextRead?.let {
            afterNextRead = null
            it()
        }
        return stored
    }

    override fun update(change: (List<Pairing>) -> List<Pairing>?): Updated {
        if (unavailable) return Updated.Unavailable
        val before = list
        val after = change(before) ?: return Updated.Done(before, before)
        if (refuseWrites) return Updated.Refused
        list = after
        writes++
        return Updated.Done(before, after)
    }
}
