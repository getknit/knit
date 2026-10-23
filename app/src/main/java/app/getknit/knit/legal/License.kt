package app.getknit.knit.legal

/**
 * The licenses Knit ships text for, each backed by a file under `assets/legal/` so a phone that got Knit
 * over Bluetooth with no Internet can still read them. Knit's own is [GPL_3_0_OR_LATER]; the rest cover
 * every row in [ThirdPartyNotices]. [spdx] is the SPDX identifier the notices table and the in-app list
 * both print; [routeId] is its lower-cased form, safe in a navigation route, or its own where two texts share
 * an id (each BSD-3-Clause carries its holder's copyright line).
 *
 * `assets/legal/COPYING` is a byte copy of the repository's `COPYING`, pinned by `LicenseAssetsTest` —
 * committed rather than copied at build time, so the release APK stays a function of the tree alone.
 */
enum class License(
    val spdx: String,
    val displayName: String,
    val asset: String,
    val routeId: String = spdx.lowercase(),
) {
    GPL_3_0_OR_LATER("GPL-3.0-or-later", "GNU General Public License v3.0 or later", "legal/COPYING"),
    APACHE_2_0("Apache-2.0", "Apache License 2.0", "legal/Apache-2.0.txt"),
    MIT("MIT", "MIT License", "legal/MIT.txt"),
    BSD_3_CLAUSE("BSD-3-Clause", "BSD 3-Clause License (Zetetic)", "legal/BSD-3-Clause-SQLCipher.txt"),

    // WebRTC's own text, then those of the BSD-3-Clause code libwebrtc links in, each under its holder's copyright line.
    BSD_3_CLAUSE_WEBRTC(
        "BSD-3-Clause",
        "BSD 3-Clause Licenses (WebRTC and its bundled code)",
        "legal/BSD-3-Clause-WebRTC.txt",
        routeId = "bsd-3-clause-webrtc",
    ),
    BSD_2_CLAUSE_WEBRTC("BSD-2-Clause", "BSD 2-Clause Licenses (WebRTC's bundled code)", "legal/BSD-2-Clause-WebRTC.txt"),

    // No SPDX id fits: FFTPACK's terms, two permissive notices and three public-domain dedications, as WebRTC carries them.
    WEBRTC_AUDIO(
        "LicenseRef-WebRTC-Audio",
        "Permissive and public-domain notices (WebRTC's bundled audio code)",
        "legal/WebRTC-audio-notices.txt",
    ),
    UNICODE_3_0("Unicode-3.0", "Unicode License v3", "legal/Unicode-3.0.txt"),
    ;

    companion object {
        fun fromRouteId(id: String): License? = entries.firstOrNull { it.routeId == id }
    }
}
