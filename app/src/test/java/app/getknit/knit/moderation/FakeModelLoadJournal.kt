package app.getknit.knit.moderation

import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.data.settings.ModelLoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [ModelLoadJournal] over a map, for [ModelLoadGuardTest] and [ModelLeaseTest]: what those assert is
 * *when* writes happen relative to the load, which a list of writes records exactly and a Preferences file
 * only obscures.
 *
 * [failOn] raises on `"read"` or `"write"` (the fail-open cases); [parkWrite] holds every write *after* it
 * has been recorded until it is completed, so a test can cancel a caller with the pending marker committed —
 * the shape a real DataStore produces when the cancellation lands after its `edit {}` resumed.
 */
internal class FakeModelLoadJournal(
    var failOn: String? = null,
    var parkWrite: CompletableDeferred<Unit>? = null,
) : ModelLoadJournal {
    val states = mutableMapOf<String, MutableStateFlow<ModelLoadState>>()
    val writes = mutableListOf<ModelLoadState>()

    private fun slot(model: String) = states.getOrPut(model) { MutableStateFlow(ModelLoadState.NONE) }

    fun state(model: String): ModelLoadState = slot(model).value

    override fun observeModelLoad(model: String): Flow<ModelLoadState> = slot(model)

    override suspend fun modelLoadState(model: String): ModelLoadState {
        if (failOn == "read") error("datastore unreadable")
        return slot(model).value
    }

    override suspend fun setModelLoadState(
        model: String,
        state: ModelLoadState,
    ) {
        if (failOn == "write") error("datastore unwritable")
        writes += state
        slot(model).value = state
        parkWrite?.await()
    }
}
