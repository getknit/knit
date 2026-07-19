<!--
Thanks for contributing to Knit! Keep each pull request focused on a single change.
By submitting, you agree your contribution is licensed under GPL-3.0-or-later (see CONTRIBUTING.md)
and that every commit is signed off under the DCO (`git commit -s`).
-->

### What and why

<!-- What does this change, and why? Link any related issue (e.g. Closes #NN). -->

### How it was tested

<!-- Check what you ran / did. -->

- [ ] `./gradlew :app:testDebugUnitTest` (JVM unit tests)
- [ ] `./gradlew detekt ktlintCheck` (static analysis and style)
- [ ] Built and installed on a device or emulator
- [ ] Verified real multi-device mesh behavior (if this touches `mesh/`, `protocol/`, or `data/`)

### Checklist

- [ ] Commits are signed off (DCO): `git commit -s` — see [CONTRIBUTING.md](CONTRIBUTING.md)
- [ ] My contribution is licensed under **GPL-3.0-or-later**
- [ ] No GPL-incompatible or unverified dependencies, models, or data added
      (updated [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) if a shipped dependency changed)
- [ ] Wire-format / custody / crypto changes note their compatibility impact (see [`docs/WIRE_COMPAT.md`](docs/WIRE_COMPAT.md))
- [ ] Code matches the surrounding style
