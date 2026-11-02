package app.getknit.knit.ui.share

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Which URIs another app hands Knit may be read at all (#284, ADR 2026-11.vdx4).
 *
 * `ContentResolver.openInputStream` reads with **Knit's** permissions, not the sender's: a `file://` URI is opened as
 * Knit, and a `content://` URI on one of Knit's own authorities is served by Knit's own provider. So a share, a paste
 * or a keyboard insert naming `file:///data/data/<us>/files/datastore/…` or `content://<us>.fileprovider/fieldlog/…`
 * would stage Knit's private files in a chat. What another app may hand Knit is a `content://` URI on **someone
 * else's** provider, which answers with that provider's permissions and the grant the sender gave.
 *
 * The authority is compared as `ContentResolver` resolves it: past the last `@`, because a `<userId>@authority` form
 * (`content://0@<us>.fileprovider/…`) reaches the same provider. Lowercased on both sides, so a case variant is refused
 * too; refusing one more URI than Android would resolve costs nothing.
 *
 * [ownAuthorities] is every provider authority this package declares; [of] reads them off the installed manifest.
 */
class OutsideUris(
    ownAuthorities: Set<String>,
) {
    private val own = ownAuthorities.mapTo(HashSet()) { it.lowercase() }

    /** Whether [uri], handed over by another app, may be read. [Uri.getAuthority] is the decoded form the resolver uses. */
    fun admits(uri: Uri): Boolean = admits(uri.scheme, uri.authority)

    /** [admits] on a URI's parts: the scheme exactly `content`, and an authority that is not one of Knit's own. */
    fun admits(
        scheme: String?,
        authority: String?,
    ): Boolean {
        if (scheme != ContentResolver.SCHEME_CONTENT) return false
        val bare = authority?.substringAfterLast('@')?.lowercase()
        return !bare.isNullOrEmpty() && bare !in own
    }

    companion object {
        /**
         * The rule for this install: every authority of every provider the merged manifest declares (a provider may
         * list several, `;`-separated), and always `<package>.fileprovider`, so a lookup that throws still refuses
         * the provider that serves Knit's files.
         */
        fun of(context: Context): OutsideUris {
            val pm = context.packageManager
            val pkg = context.packageName
            val declared =
                runCatching {
                    val info =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS)
                        }
                    info.providers.orEmpty().flatMap { it.authority.orEmpty().split(';') }
                }.getOrDefault(emptyList())
            return OutsideUris(declared.map { it.trim() }.filter { it.isNotEmpty() }.toSet() + "$pkg.fileprovider")
        }
    }
}
