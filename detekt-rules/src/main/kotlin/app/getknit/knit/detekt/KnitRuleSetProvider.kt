package app.getknit.knit.detekt

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/** Knit's own detekt rules, configured under `knit:` in `config/detekt/detekt.yml`. */
class KnitRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("knit")

    override fun instance() =
        RuleSet(
            ruleSetId,
            mapOf(
                RuleName("BarePeriodicLoop") to ::BarePeriodicLoop,
                RuleName("HardcodedUiText") to ::HardcodedUiText,
            ),
        )
}
