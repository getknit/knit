package app.getknit.knit.mesh

import app.getknit.knit.data.settings.BlockListState
import kotlinx.coroutines.flow.MutableStateFlow

/** [BlockListState] in memory, as the rigs that build a pipeline or a manager without a DataStore need it. */
class MemoryBlockListState : BlockListState {
    override val owedFaces = MutableStateFlow(emptySet<String>())
    override val actedOn = MutableStateFlow<Set<String>?>(null)

    override suspend fun oweFace(
        nodeId: String,
        hash: String,
    ) {
        owedFaces.value = owedFaces.value + "$nodeId $hash"
    }

    override suspend fun settleFace(entry: String) {
        owedFaces.value = owedFaces.value - entry
    }

    override suspend fun setActedOn(ids: Set<String>) {
        actedOn.value = ids
    }
}
