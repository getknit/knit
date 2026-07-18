# Contributing to Knit

Thanks for your interest in Knit — an offline, serverless, end-to-end-encrypted mesh messenger for
Android. Contributions are welcome, within the expectations below.

## Support expectations (read this first)

Knit is released **as-is** under the [GNU GPL v3.0-or-later](COPYING). It is developed on a
**best-effort, hobby basis**, with **no support, warranty, or response-time guarantee** of any kind —
this is the "NO WARRANTY" clause of the GPL, stated plainly:

- Issues and merge requests are welcome, but may not be triaged, answered, or accepted.
- There is **no commitment** to fix bugs, review contributions on any timeline, or maintain
  compatibility.
- Do not depend on Knit where failure matters. It is experimental software driving low-level radios.

If that works for you, read on.

## Ground rules

- **Be excellent to each other.** This project has a [Code of Conduct](CODE_OF_CONDUCT.md); by
  participating you agree to uphold it. Harassment or abuse is not welcome in issues, MRs, or discussions.
- **License:** by contributing, you agree your contribution is licensed under **GPL-3.0-or-later**, the
  same as the project.
- **Sign your commits (DCO):** add a `Signed-off-by: Your Name <you@example.com>` trailer to each
  commit (`git commit -s`), certifying the [Developer Certificate of Origin](https://developercertificate.org/).
  Only submit code you have the right to license under the GPL.
- **Third-party assets:** do not add dependencies or bundled models/data whose license is
  GPL-incompatible or unverified. The shipped dependency graph is intentionally GMS-free and
  Apache/BSD/MIT-only; keep it that way, and update
  [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) when you add or remove a shipped dependency.

## Development

The single source of truth for build/test commands, the (deliberately bleeding-edge) toolchain, the
mesh architecture, and the hard-won gotchas is **[`AGENTS.md`](AGENTS.md)** — start there before touching
build config, the mesh layer, or the DI graph. In short:

- **JDK 21** and the Android SDK (compileSdk 36.1) are required.
- `./gradlew :app:compileDebugKotlin` — fast compile check of main sources.
- `./gradlew :app:testDebugUnitTest` — JVM unit tests (mesh/protocol/data, Robolectric Room).
- `./gradlew detekt ktlint` — static analysis and style (`ktlintFormat` autocorrects).
- Real mesh behavior needs **two or more physical devices** — see the README's *Running* section.
- After any dependency change, regenerate the lockfile with
  `./gradlew :app:dependencies --write-locks` (see `AGENTS.md`).

Please run the unit tests, `detekt`, and `ktlint` before opening a merge request, and match the
surrounding code style.

## Where to submit

Development happens on the project's self-hosted GitLab at
<https://source.jeffmixon.com/knit/knit-next>. Open issues and merge requests there; keep each MR
focused on a single change with a clear description of what and why.

## Security

**Do not** report security vulnerabilities through public issues or merge requests. See
[`SECURITY.md`](SECURITY.md) for private disclosure.
