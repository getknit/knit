package app.getknit.knit.ui.util

import android.content.Context
import app.getknit.knit.R

/*
 * A list the user reads, joined with the language's own separator (`list_join`: ", " in English, "、" in Chinese)
 * rather than a hard-coded ", ". Pairwise, left to right, so a translator words one join and every length follows.
 */

/** [items] joined by `list_join`; "" for none, the item itself for one. */
fun joinedList(
    context: Context,
    items: List<String>,
): String = items.reduceOrNull { joined, next -> context.getString(R.string.list_join, joined, next) }.orEmpty()
