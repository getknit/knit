package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MeshcoreBind
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.crypto.b64
import com.google.crypto.tink.subtle.Ed25519Sign
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The MeshCore bind (`vectors/meshcore-bind-v1.json`, ADR 2026-11.gsqu's Stage 5d amendment): a board key from a fixed test
 * seed, a node id, the 69-byte message `"knit-meshcore-bind/v1"` ‖ node ‖ key, the board's RFC 8032 signature over it, the
 * sealed profile carrying both on each layout, and the receiver's steps: what a strict decoder keeps of `meshcoreSig` and
 * which key it stores, for the accepted claim and for every refusal the iOS port must reproduce (the copy attack, a
 * non-canonical or 63-byte signature, a signature with no key, a low-order key whose forged signature Tink alone accepts,
 * `S + L`). Rebuilt on every run and compared; each step is also replayed through the production decoders and
 * [MeshcoreBind.provenKey]. Additive: a new file, no other vector moved.
 *
 * Write mode (`KNIT_WRITE_VECTORS=1`) rewrites the file; only an intended change of the bind does that.
 */
class MeshcoreBindVectorTest {
    private val seed = fixtureBytes(32, 77)
    private val key = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey
    private val alice = NodeId.fromBytes(fixtureBytes(NodeId.BYTES, 5))
    private val mallory = NodeId.fromBytes(fixtureBytes(NodeId.BYTES, 9))
    private val message = checkNotNull(MeshcoreBind.message(NodeId.toBytes(alice), key))
    private val sig = Ed25519Sign(seed).sign(message)

    private fun profile(
        key: String?,
        sig: String?,
    ) = MessageContent(
        body = "",
        ctl = MessageContent.CTL_PROFILE,
        pr = ProfilePayload(name = "Ann", status = "", version = 1_763_000_000_000L, meshcoreKey = key, meshcoreSig = sig),
    )

    /** One receiver step: [sender] sealed [content], laid out [compact] or named. */
    private class Step(
        val name: String,
        val sender: String,
        val content: MessageContent,
        val compact: Boolean,
        val decodedSig: String?,
        val storedKey: String?,
    )

    private fun steps(): List<Step> {
        val k = b64(key)
        val s = b64(sig)
        val identity = ByteArray(32).also { it[0] = 1 }
        val sPlusL =
            (BigInteger(1, sig.copyOfRange(32, 64).reversedArray()) + L)
                .toByteArray()
                .reversedArray()
                .copyOf(32)
        val signed = profile(k, s)
        return listOf(
            Step("accepted, compact", alice, signed, compact = true, decodedSig = s, storedKey = k),
            Step("accepted, named", alice, signed, compact = false, decodedSig = s, storedKey = k),
            Step("copied: Alice's key and signature sealed by Mallory", mallory, signed, compact = true, decodedSig = s, storedKey = null),
            Step("no signature", alice, profile(k, null), compact = true, decodedSig = null, storedKey = null),
            Step(
                "non-canonical signature (named only)",
                alice,
                profile(k, s.trimEnd('=')),
                compact = false,
                decodedSig = null,
                storedKey = null,
            ),
            Step("63-byte signature, compact", alice, profile(k, b64(sig.copyOf(63))), compact = true, decodedSig = null, storedKey = null),
            Step("63-byte signature, named", alice, profile(k, b64(sig.copyOf(63))), compact = false, decodedSig = null, storedKey = null),
            Step("signature with no key, compact", alice, profile(null, s), compact = true, decodedSig = null, storedKey = null),
            Step("signature with no key, named", alice, profile(null, s), compact = false, decodedSig = null, storedKey = null),
            Step(
                "low-order key (the identity), R = identity, S = 0",
                alice,
                profile(b64(identity), b64(identity + ByteArray(32))),
                compact = true,
                decodedSig = b64(identity + ByteArray(32)),
                storedKey = null,
            ),
            Step(
                "S + L",
                alice,
                profile(k, b64(sig.copyOf(32) + sPlusL)),
                compact = true,
                decodedSig = b64(sig.copyOf(32) + sPlusL),
                storedKey = null,
            ),
        )
    }

    private fun plaintext(step: Step): ByteArray =
        if (step.compact) checkNotNull(MessageContentV2.encodeOrNull(step.content)) { step.name } else step.content.encode()

    private fun build(): JsonObject =
        buildJsonObject {
            put("about", ABOUT)
            put("seed", seed.toHex())
            put("key", key.toHex())
            put("keyBase64", b64(key))
            put("nodeId", alice)
            put("nodeIdBytes", NodeId.toBytes(alice).toHex())
            put("message", message.toHex())
            put("sig", sig.toHex())
            put("sigBase64", b64(sig))
            putJsonArray("steps") {
                for (step in steps()) {
                    add(
                        buildJsonObject {
                            put("name", step.name)
                            put("sender", step.sender)
                            put("layout", if (step.compact) "compact" else "named")
                            put("plaintext", plaintext(step).toHex())
                            put("decodedSig", step.decodedSig?.let(::JsonPrimitive) ?: JsonNull)
                            put("storedKey", step.storedKey?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                }
            }
            putJsonObject("domains") {
                put("bind", "knit-meshcore-bind/v1")
                put("consent", "knit-meshcore-consent/v1")
            }
        }

    @Test
    fun `the bind rebuilds byte for byte`() {
        assumeFalse(VectorFiles.writing)
        val file = VectorFiles.read(FILE)
        val built = build()
        for (name in built.keys) assertEquals("meshcore-bind vector '$name' drifted", built.getValue(name), file[name])
    }

    @Test
    fun `every pinned step decodes and stores what the file says`() {
        assumeFalse(VectorFiles.writing)
        val file = VectorFiles.read(FILE)
        assertTrue(
            "the pinned signature verifies",
            MeshcoreBind.verifies(
                file
                    .getValue("nodeIdBytes")
                    .jsonPrimitive.content
                    .fromHex(),
                file
                    .getValue("key")
                    .jsonPrimitive.content
                    .fromHex(),
                file
                    .getValue("sig")
                    .jsonPrimitive.content
                    .fromHex(),
            ),
        )
        for (element in file.getValue("steps").jsonArray) {
            val step = element.jsonObject
            val name = step.getValue("name").jsonPrimitive.content
            val bytes =
                step
                    .getValue("plaintext")
                    .jsonPrimitive.content
                    .fromHex()
            val decoded =
                if (step.getValue("layout").jsonPrimitive.content ==
                    "compact"
                ) {
                    MessageContentV2.decode(bytes)
                } else {
                    MessageContent.decode(bytes)
                }
            val pr = checkNotNull(checkNotNull(decoded) { "$name: the profile decodes" }.pr)
            assertEquals(name, step.getValue("decodedSig").jsonPrimitive.contentOrNullValue(), pr.meshcoreSig)
            val sender = step.getValue("sender").jsonPrimitive.content
            assertEquals(
                name,
                step.getValue("storedKey").jsonPrimitive.contentOrNullValue(),
                MeshcoreBind.provenKey(sender, pr.meshcoreKey, pr.meshcoreSig),
            )
        }
    }

    @Test
    fun `KNIT_WRITE_VECTORS=1 rewrites the meshcore-bind vectors`() {
        assumeTrue(VectorFiles.writing)
        VectorFiles.write(FILE, build())
    }

    private fun JsonPrimitive.contentOrNullValue(): String? = if (this is JsonNull) null else content

    private companion object {
        const val FILE = "meshcore-bind-v1.json"

        /** The order of the prime subgroup. */
        val L: BigInteger = BigInteger.valueOf(2).pow(252).add(BigInteger("27742317777372353535851937790883648493"))

        const val ABOUT =
            "MeshCore proof of possession (ADR 2026-11.gsqu Stage 5d). message = ASCII \"knit-meshcore-bind/v1\" (21) || node id " +
                "(16 raw bytes) || the board's Ed25519 public key (32) = 69 bytes; sig = the board's RFC 8032 Ed25519 signature " +
                "over it (key here from the fixed test seed; verify, never compare: a port's signer may randomize). " +
                "A sealed profile carries it as meshcoreSig beside meshcoreKey: canonical padded base64 on the named layout, 64 raw " +
                "bytes at ProfileV2 label 10 on the compact one, to direct contacts only. A receiver decodes strictly (exactly 64 " +
                "canonical bytes, beside a key, else the field alone is dropped: decodedSig) and stores the key only when the key " +
                "is canonical, an Ed25519 point and not of low order, S < L, and the signature verifies over the bind of the " +
                "frame's authenticated sender (storedKey; null drops the field and clears a stored key). Check the key before " +
                "the signature: under the low-order key below, R = identity and S = 0 verifies every message in a cofactorless " +
                "verifier. Each step's plaintext is the sealed CTL_PROFILE content in the layout the step names."
    }
}
