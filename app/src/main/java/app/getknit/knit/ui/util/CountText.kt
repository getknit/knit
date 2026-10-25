package app.getknit.knit.ui.util

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/*
 * A count a screen can show at 0 takes a string of its own for 0. Android picks a plural's form by the language's
 * CLDR rule, and Portuguese files 0 under `one` beside 1 ("0 pessoa por perto" where a Brazilian writes "0 pessoas"),
 * French too; nothing in a `<plurals>` can say otherwise, so each language words its zero string for 0. A count that
 * is never 0 where it is shown (guarded, or replaced by an empty state) needs neither.
 */

/** [plural] for [count], with [count] as its argument, or [zero] (also given [count]) when [count] is 0. */
@Composable
fun countText(
    @PluralsRes plural: Int,
    @StringRes zero: Int,
    count: Int,
): String = if (count == 0) stringResource(zero, count) else pluralStringResource(plural, count, count)

/** The words beside a count drawn on its own, where [plural] and [zero] take no argument: [zero]'s when [count] is 0. */
@Composable
fun countLabel(
    @PluralsRes plural: Int,
    @StringRes zero: Int,
    count: Int,
): String = if (count == 0) stringResource(zero) else pluralStringResource(plural, count)
