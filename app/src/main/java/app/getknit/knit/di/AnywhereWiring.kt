package app.getknit.knit.di

import app.getknit.knit.BuildConfig

/**
 * Knit Anywhere (ADR 2026-10.swvp): getting and keeping this phone's tickets — a paying customer's key and the tickets
 * it buys (ADR 2026-10.xkp4, 2026-10.3sk7), free tickets (ADR 2026-10.s3ep) and the supporter badge (ADR 2026-10.r99s).
 * The one read of `BuildConfig.KNIT_ANYWHERE` — on in every build since 2.10.0-beta.0, `-PknitAnywhere=` overrides.
 * Off, no issuer is built or asked, an invite's customer key is ignored, and no relay row, free-upload offer or own
 * badge switch shows. What a phone with no ticket needs ships in every build: reading a relay's ticket gates (so an
 * upload a relay would refuse is never put, ADR 2026-10.8t2y) and drawing the verified badges other phones send.
 */
const val ANYWHERE_WIRED: Boolean = BuildConfig.KNIT_ANYWHERE
