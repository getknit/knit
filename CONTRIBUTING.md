# Contributing to Knit

Thanks for your interest in Knit — an offline, serverless, end-to-end-encrypted mesh messenger for
Android. Contributions are welcome, within the expectations below.

## Support expectations (read this first)

Knit is released **as-is** under the [GNU GPL v3.0-or-later](COPYING). It is developed on a
**best-effort, hobby basis**, with **no support, warranty, or response-time guarantee** of any kind —
this is the "NO WARRANTY" clause of the GPL, stated plainly:

- Issues and pull requests are welcome, but may not be triaged, answered, or accepted.
- There is **no commitment** to fix bugs, review contributions on any timeline, or maintain
  compatibility.
- Do not depend on Knit where failure matters. It is experimental software driving low-level radios.

If that works for you, read on.

## Ground rules

- **Be excellent to each other.** This project has a [Code of Conduct](CODE_OF_CONDUCT.md); by
  participating you agree to uphold it. Harassment or abuse is not welcome in issues, PRs, or discussions.
- **License:** by contributing, you agree your contribution is licensed under **GPL-3.0-or-later**, the
  same as the project.
- **Sign your commits (DCO):** add a `Signed-off-by: Your Name <you@example.com>` trailer to each
  commit (`git commit -s`), certifying the [Developer Certificate of Origin](DCO) — the verbatim 1.1
  text, also published at [developercertificate.org](https://developercertificate.org/). The
  trailer's email must match the commit author's, and `git config core.hooksPath .githooks` opts
  into a repo hook that adds it for you. Only submit code you have the right to license under the
  GPL.
- **Third-party assets:** do not add dependencies or bundled models/data whose license is
  GPL-incompatible or unverified. The shipped dependency graph is intentionally GMS-free and
  Apache/BSD/MIT-only; keep it that way, and when you add or remove a shipped dependency update both
  [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and the matching row in
  `app/src/main/java/app/getknit/knit/legal/ThirdPartyNotices.kt` (the in-app Open-source licenses list).
  `ThirdPartyNoticesSyncTest` and `ReleaseClasspathNoticesTest` fail until the table, the list and
  `app/gradle.lockfile` agree, and name the coordinate that is missing.

## Development

The single source of truth for build/test commands, the (deliberately bleeding-edge) toolchain, the
mesh architecture, and the hard-won gotchas is **[`AGENTS.md`](AGENTS.md)** — start there before touching
build config, the mesh layer, or the DI graph. In short:

- **JDK 21** and the Android SDK (compileSdk 37.1) are required.
- `./gradlew :app:compileDebugKotlin` — fast compile check of main sources.
- `./gradlew :app:testDebugUnitTest` — JVM unit tests (mesh/protocol/data, Robolectric Room).
- `./gradlew detekt ktlintCheck` — static analysis and style (`ktlintFormat` autocorrects).
- Real mesh behavior needs **two or more physical devices** — see the README's *Running* section.
- After any dependency change, regenerate the lockfile with
  `./gradlew :app:dependencies --write-locks`, then the dependency verification files with
  `scripts/verification-metadata.sh` (see `AGENTS.md`).

Please run the unit tests, `detekt`, and `ktlintCheck` before opening a pull request, and match the
surrounding code style.

`git config core.hooksPath .githooks` opts into the repo's hooks — a `prepare-commit-msg` that adds
the DCO sign-off, a `commit-msg` that strips agent session-link trailers, and a `pre-commit` that
refuses an ADR commit whose generated router is out of sync, plus Git LFS's four hooks. That setting
replaces `.git/hooks` wholesale, so anything you keep there stops running; `git commit --no-verify`
skips them for one commit.

The Compose preview screenshot references (`app/src/screenshotTestDebug/reference/`) are stored in
Git LFS — the only files that are. Building the app does not need it; running or updating the
screenshot tests does: install git-lfs, then `git lfs install && git lfs pull`.

## Where to submit

Development and contributions happen on GitHub at <https://github.com/getknit/knit>. Open issues and
pull requests there; the issue and pull-request templates will guide you through what to include. Keep
each pull request focused on a single change with a clear description of what and why.

A DCO job checks that every commit in a pull request carries a sign-off whose email matches its
author; if it fails, `git rebase --signoff origin/main` and a force-push fix the whole branch at
once. The rest of CI mirrors the maintainer's internal GitLab pipeline (build, unit tests, the ADR
and generated-asset sync checks); `detekt`/`ktlintCheck` ride along as advisory.

A change to the radios, the crypto, blob custody or the tests may also get an automated review. The maintainer can
run the same reviewer agents on your pull request that check their own changes. Their findings arrive as one
comment, each marked Blocking or Non-blocking, and you need no AI tools of your own. The maintainer decides what
holds the pull request up.

## Security

**Do not** report security vulnerabilities through public issues or pull requests. See
[`SECURITY.md`](SECURITY.md) for private disclosure.
