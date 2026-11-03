package app.getknit.knit.ui.translate

import android.content.Context
import app.getknit.knit.R
import app.getknit.knit.call.SttModelState
import app.getknit.knit.transfer.ModelsWanted
import app.getknit.knit.transfer.TransferModels
import app.getknit.knit.transfer.TransferSink
import app.getknit.knit.transfer.TransferSource
import app.getknit.knit.translate.ModelArchive
import app.getknit.knit.translate.TranslateDirection

/**
 * [TransferModels] over this phone's translation models (ADR 2026-11.ejn8): a pin is a row of the generated table
 * ([TranslateDirection.ofPin]) or nothing, and the bundle and its staging are [TranslateModelStore]'s.
 */
class TranslateTransferModels(
    private val context: Context,
    private val store: TranslateModelStore,
) : TransferModels {
    override fun wanted(pins: List<Long>): ModelsWanted {
        val known = pins.mapNotNull(TranslateDirection::ofPin)
        // A direction already here, or one a download is fetching, is not taken again.
        val take = known.filter { store.state(it).value.let { s -> s !is SttModelState.Ready && s !is SttModelState.Downloading } }
        return ModelsWanted(
            take = take.map { it.pin },
            bytes = ModelArchive.size(TranslateModelStore.plan(take)),
            label = label(context, take.ifEmpty { known }),
        )
    }

    override fun bundle(pins: List<Long>): TransferSource? {
        val directions = pins.map { TranslateDirection.ofPin(it) ?: return null }
        return store.bundle(directions)
    }

    override suspend fun sink(pins: List<Long>): TransferSink? {
        val directions = pins.map { TranslateDirection.ofPin(it) ?: return null }
        return store.receiver(directions)
    }

    companion object {
        /** What a bundle is called on a card or a notification: the one direction by name, else how many. */
        fun label(
            context: Context,
            directions: List<TranslateDirection>,
        ): String {
            val locale = context.resources.configuration.locales[0]
            val one = directions.singleOrNull()
            return if (one != null) {
                context.getString(
                    R.string.translate_models_direction,
                    LanguageNames.title(one.from, locale),
                    LanguageNames.title(one.to, locale),
                )
            } else {
                context.resources.getQuantityString(R.plurals.translate_models_count, directions.size, directions.size)
            }
        }
    }
}
