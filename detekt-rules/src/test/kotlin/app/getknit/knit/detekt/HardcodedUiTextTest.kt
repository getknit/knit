package app.getknit.knit.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

/** Parsed, not compiled: the snippets name Compose and notification APIs that are not on this module's test classpath. */
class HardcodedUiTextTest {
    private val rule = HardcodedUiText(Config.empty)

    private fun findings(code: String) = rule.lint(code, compile = false)

    private fun assertFlagged(code: String) = assertEquals(code, 1, findings(code).size)

    private fun assertClean(code: String) = assertEquals(code, 0, findings(code).size)

    @Test
    fun aLiteralInTextIsFlagged() {
        assertFlagged("@Composable fun f() { Text(\"Cancel\") }")
        assertFlagged("@Composable fun f() { Text(text = \"Cancel\", style = s) }")
        assertFlagged("@Composable fun f() { Text(style = s, text = \"Cancel\") }")
        assertFlagged("@Composable fun f(n: Int) { Text(\"\$n peers\") }")
        assertFlagged("@Composable fun f() { BasicText((\"Cancel\")) }")
    }

    @Test
    fun aLiteralTheUserHearsIsFlagged() {
        assertFlagged("@Composable fun f() { Icon(icon, contentDescription = \"Back\") }")
        assertFlagged("@Composable fun f() { Modifier.clickable(onClickLabel = \"Open\") { } }")
        assertFlagged("@Composable fun f() { Modifier.semantics { contentDescription = \"Unread\" } }")
        assertFlagged("@Composable fun f() { Modifier.semantics { stateDescription = \"On\" } }")
    }

    @Test
    fun aLiteralInANotificationOrAToastIsFlagged() {
        assertFlagged("fun f() { builder.setContentText(\"Without it a call can't ring\") }")
        assertFlagged("fun f() { builder.setContentTitle(\"Knit\").setOngoing(true) }")
        assertFlagged("fun f() { builder.addAction(R.drawable.ic, \"Reply\", intent) }")
        assertFlagged("fun f() { Toast.makeText(context, \"Copied\", Toast.LENGTH_SHORT).show() }")
        assertFlagged("fun f() { RemoteInput.Builder(KEY).setLabel(\"Reply\").build() }")
    }

    @Test
    fun eachBranchOfAChoiceIsRead() {
        assertEquals(2, findings("fun f() { b.setContentText(if (walkie) \"Knit walkie-talkie\" else \"Knit voice call\") }").size)
        assertEquals(2, findings("@Composable fun f() { Text(when (s) { A -> \"No answer\"; else -> \"Call ended\" }) }").size)
        assertFlagged("@Composable fun f() { Text(name ?: \"Unknown\") }")
        assertFlagged("fun f() { NotificationChannelCompat.Builder(ID, HIGH).setName(\"Calls\").build() }")
        assertClean("@Composable fun f() { Text(if (on) stringResource(R.string.on) else name) }")
    }

    @Test
    fun aResourceOrAValueIsClean() {
        assertClean("@Composable fun f() { Text(stringResource(R.string.cancel)) }")
        assertClean("@Composable fun f(name: String) { Text(name) }")
        assertClean("@Composable fun f(name: String) { Text(\"\$name\") }")
        assertClean("@Composable fun f(n: Int) { Text(\"\$n · \${n + 1}\") }")
        assertClean("@Composable fun f() { Text(\"%d\") }")
        assertClean("@Composable fun f() { Icon(icon, contentDescription = null) }")
        assertClean("fun f() { builder.setContentText(body) }")
        assertClean("fun f() { builder.addAction(action) }")
    }

    @Test
    fun aStringElsewhereIsClean() {
        assertClean("fun f() { Log.d(TAG, \"Cancel pressed\") }")
        assertClean("fun f() { val label = \"Cancel\" }")
        assertClean("@Composable fun f() { Box(Modifier.testTag(\"cancel\")) }")
        assertClean("fun f() { intent.putExtra(\"text\", \"Cancel\") }")
    }

    @Test
    fun aPreviewIsSampleData() {
        assertClean("@Preview @Composable fun cardPreview() { Text(\"Running late\") }")
        assertClean("@PreviewLightDark @Composable fun card() { Text(\"Running late\") }")
        assertClean("@Composable fun CardPreview() { Bubble { Text(\"Running late\") } }")
    }
}
