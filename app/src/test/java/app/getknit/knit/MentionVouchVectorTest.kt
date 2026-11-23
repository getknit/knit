package app.getknit.knit

import app.getknit.knit.identity.Alias
import app.getknit.knit.mesh.protocol.VectorFiles
import app.getknit.knit.ui.chat.EVERYONE_NAME
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * `vectors/mention-vouch-v1.json`: which of a received row's mentions its bubble styles (ADR 2026-11.p6dk), and the
 * labels the rule reads them against. Nothing here crosses the wire; two ports that answer differently draw a
 * different highlight over the same message, so knit-ios checks every label against its own index and runs every case
 * through its own rule and span function.
 *
 * Built from [MentionVouchCases] through `vouchedMentions` and `mentionSpans` on every run and compared byte for byte;
 * `KNIT_WRITE_VECTORS=1` rewrites it, and refuses to write an answer the cases do not state. Each directory's index is
 * its peers plus `me` as self, with Content filtering off and no stale ids. Non-ASCII is written as `\uXXXX` escapes,
 * so the lookalike and the invisible mark read in a diff.
 */
class MentionVouchVectorTest {
    @Test
    fun theFileIsWhatTheCodeAnswers() {
        assumeFalse(VectorFiles.writing)
        assertEquals(VectorFiles.render(build(), asciiOnly = true), VectorFiles.text(FILE))
    }

    @Test
    fun write() {
        assumeTrue(VectorFiles.writing)
        VectorFiles.write(FILE, build(), asciiOnly = true)
    }

    private fun build(): JsonObject =
        buildJsonObject {
            put("description", "Which received mentions a bubble styles, and the labels they are read against (ADR 2026-11.p6dk)")
            put("maxTokens", Alias.MAX_TOKENS)
            put("everyone", EVERYONE_NAME)
            putJsonObject("me") {
                put("nodeId", MentionVouchCases.W)
                put("name", MentionVouchCases.MY_NAME)
            }
            putJsonObject("directories") {
                for (dir in MentionVouchCases.DIRECTORIES) put(dir.name, directoryJson(dir))
            }
            putJsonArray("cases") { for (case in MentionVouchCases.CASES) add(caseJson(case)) }
        }

    private fun directoryJson(dir: MentionVouchCases.Directory): JsonObject =
        buildJsonObject {
            putJsonArray("peers") {
                for ((id, name) in dir.peers) {
                    addJsonObject {
                        put("nodeId", id)
                        put("name", name)
                    }
                }
            }
            putJsonArray("unknown") { for (id in dir.unknown) add(id) }
            putJsonObject("labels") {
                for (id in dir.labelled) put(id, dir.peerDirectory.label(id).text)
            }
        }

    /** One case as the code answers it; refuses an answer the case does not state, so a write never pins a wrong one. */
    private fun caseJson(case: MentionVouchCases.Case): JsonObject {
        val vouched = case.vouched()
        val kept = case.mentions.indices.filter { i -> vouched.any { it === case.mentions[i] } }
        val spans = case.spans()
        check(kept == case.kept) { "${case.label}: keeps $kept, the case states ${case.kept}" }
        check(spans.map { case.body.substring(it.start, it.end) } == case.styled) { "${case.label}: styles $spans" }
        return buildJsonObject {
            put("label", case.label)
            put("directory", case.directory.name)
            put("thread", case.thread.wire)
            if (case.roster == null) {
                put("roster", JsonNull)
            } else {
                putJsonArray("roster") { for (id in case.roster) add(id) }
            }
            put("sender", case.sender)
            put("body", case.body)
            putJsonArray("mentions") {
                for (m in case.mentions) {
                    addJsonObject {
                        put("nodeId", m.nodeId)
                        put("name", m.name)
                    }
                }
            }
            putJsonArray("kept") { for (i in kept) add(i) }
            putJsonArray("spans") {
                for (span in spans) {
                    addJsonArray {
                        add(span.start)
                        add(span.end)
                    }
                }
            }
        }
    }

    companion object {
        const val FILE = "mention-vouch-v1.json"
    }
}
