package app.getknit.knit.net

import app.getknit.knit.mesh.spool.DirectoryRead
import app.getknit.knit.mesh.spool.PublicSpoolSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection

/**
 * The one read of Knit's public relay directory (ADR 2026-10.8kfx): `GET` [URL] over https on the network the gate
 * vouched for, never the Wi-Fi Aware link. Through `Network.openConnection`, as the speech model's download is, so
 * okhttp3 stays confined to the files `rules/mesh.md` names. No redirect is followed, timeouts are short, and the
 * body is capped at [MAX_BODY_BYTES]: an honest directory is a few hundred bytes. Nothing it reads is logged.
 */
class AndroidPublicSpoolDirectory(
    private val gate: AndroidInternetGate,
) : PublicSpoolSource {
    override suspend fun fetch(): DirectoryRead =
        withContext(Dispatchers.IO) {
            val network = gate.currentNetwork() ?: return@withContext DirectoryRead.Failed("offline")
            try {
                val connection = network.openConnection(URI(URL).toURL()) as HttpsURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                try {
                    val code = connection.responseCode
                    if (code != HttpsURLConnection.HTTP_OK) return@withContext DirectoryRead.Failed("http $code")
                    readCapped(connection)?.let { DirectoryRead.Document(it) } ?: DirectoryRead.Failed("too large")
                } finally {
                    connection.disconnect()
                }
            } catch (_: UnknownHostException) {
                DirectoryRead.Failed("dns")
            } catch (_: InterruptedIOException) {
                DirectoryRead.Failed("timeout")
            } catch (e: IOException) {
                DirectoryRead.Failed(e.javaClass.simpleName)
            }
        }

    private fun readCapped(connection: HttpsURLConnection): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        connection.inputStream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BODY_BYTES) return null
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val URL = "https://hosted.getknit.app/api/v1/public-spools"
        const val MAX_BODY_BYTES = 64 * 1024
        private const val TIMEOUT_MS = 10_000
        private const val BUFFER_BYTES = 4 * 1024
    }
}
