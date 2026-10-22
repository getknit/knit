package app.getknit.knit.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Words written straight into a place the user reads them: `Text("Cancel")`, `contentDescription = "Back"`, a
 * notification's `setContentText("…")`. A literal there never reaches a translator, and with `MissingTranslation` an
 * error in lint, a string resource is the only way a locale can be complete. Move it to `res/values/strings.xml` and
 * read it with `stringResource` (or `getString` outside composition).
 *
 * Matched on the tree alone, since detekt runs here without type resolution, so it catches the sinks it can name:
 * - the text argument of a call to one of [textCalls] (the first positional argument, or `text =`);
 * - a named argument, or a `semantics { }` assignment, called one of [textNames] (`contentDescription`, …);
 * - the argument of a call to one of [methods] (`setContentText`, `Toast.makeText`, …), at the position it names.
 *
 * A sink whose argument is an `if`, a `when` or an elvis is read through to each branch's literal. A literal counts
 * when its own characters hold a letter: `"·"`, `"$count"` and `"%d"` stay. A function annotated `@Preview`, or named
 * `…Preview`, is sample data and never shown in a build, so it is skipped. Text that reaches a
 * sink through a variable is out of the rule's reach; the resource idiom (`@StringRes` from the ViewModel) is the cure.
 */
class HardcodedUiText(
    config: Config,
) : Rule(
        config,
        "Text the user reads must come from a string resource, or no translation can carry it.",
    ) {
    @Configuration("Composable calls whose first positional argument, or `text =`, is shown as text.")
    private val textCalls: List<String> by config(listOf("Text", "BasicText"))

    @Configuration("Named arguments and semantics properties whose string the user reads or hears.")
    private val textNames: List<String> by config(
        listOf("contentDescription", "stateDescription", "onClickLabel", "onLongClickLabel", "paneTitle"),
    )

    @Configuration("Calls whose argument is shown to the user, as `name` (first argument) or `name:index` (0-based).")
    private val methods: List<String> by config(
        listOf(
            "setContentTitle",
            "setContentText",
            "setSubText",
            "setTicker",
            "setBigContentTitle",
            "setSummaryText",
            "bigText",
            "setShortLabel",
            "setLongLabel",
            "setDisabledMessage",
            "setLabel",
            "setName",
            "setDescription",
            "setTitle",
            "setMessage",
            "addAction:1",
            "makeText:1",
        ),
    )

    private val positions: Map<String, Int> by lazy {
        methods.associate { entry ->
            val name = entry.substringBefore(':')
            name to (entry.substringAfter(':', "0").toIntOrNull() ?: 0)
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        val args = expression.valueArguments
        when (callee) {
            in textCalls -> {
                val text = args.firstOrNull { it.name() == TEXT } ?: args.firstOrNull()?.takeIf { !it.isNamed() }
                text?.let(::check)
            }

            in positions -> {
                if (args.none { it.isNamed() }) args.getOrNull(positions.getValue(callee))?.let(::check)
            }
        }
        args.filter { it.name() in textNames }.forEach(::check)
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationToken != KtTokens.EQ) return
        val target = expression.left as? KtNameReferenceExpression ?: return
        if (target.getReferencedName() !in textNames) return
        expression.right?.let { report(it) }
    }

    private fun KtValueArgument.name(): String? = getArgumentName()?.asName?.asString()

    private fun check(argument: KtValueArgument) {
        argument.getArgumentExpression()?.let { report(it) }
    }

    private fun report(expression: KtExpression) {
        outcomes(expression).filter { wordy(it) && !inPreview(it) }.forEach { value ->
            report(
                Finding(
                    Entity.from(value),
                    "${value.text} is shown to the user but is not a string resource, so no translation can carry it. " +
                        "Move it to res/values/strings.xml and read it with stringResource (or getString).",
                ),
            )
        }
    }

    /** The string literals [expression] can evaluate to: itself, or the branches of an `if`, a `when` or an elvis. */
    private fun outcomes(expression: KtExpression?): List<KtStringTemplateExpression> =
        when (expression) {
            is KtStringTemplateExpression -> listOf(expression)
            is KtParenthesizedExpression -> outcomes(expression.expression)
            is KtIfExpression -> outcomes(expression.then) + outcomes(expression.`else`)
            is KtWhenExpression -> expression.entries.flatMap { outcomes(it.expression) }
            is KtBinaryExpression -> elvisOutcomes(expression)
            else -> emptyList()
        }

    private fun elvisOutcomes(expression: KtBinaryExpression): List<KtStringTemplateExpression> =
        if (expression.operationToken == KtTokens.ELVIS) outcomes(expression.left) + outcomes(expression.right) else emptyList()

    /** Whether the literal's own characters, outside its `$…` entries, hold a letter. */
    private fun wordy(template: KtStringTemplateExpression): Boolean =
        template.entries
            .filterIsInstance<KtLiteralStringTemplateEntry>()
            .any { entry -> entry.text.withoutFormatSpecs().any(Char::isLetter) }

    private fun String.withoutFormatSpecs(): String = replace(FORMAT_SPEC, "")

    private fun inPreview(expression: KtExpression): Boolean {
        var function = expression.getParentOfType<KtNamedFunction>(true)
        while (function != null) {
            val previewName = function.name?.endsWith(PREVIEW) == true
            val annotated = function.annotationEntries.any { it.shortName?.asString()?.contains(PREVIEW) == true }
            if (previewName || annotated) return true
            function = function.getParentOfType<KtNamedFunction>(true)
        }
        return false
    }

    private companion object {
        const val PREVIEW = "Preview"
        const val TEXT = "text"
        val FORMAT_SPEC = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]")
    }
}
