---
id: "2026-11.7t9z"
slug: versioncode-is-derived-from-versionname-so-a-hotfix-never-outranks-the-next-line
title: "versionCode is derived from versionName so a hotfix never outranks the next line's pre-releases"
date: 2026-11-03
topics: [build, release, versioning]
---

# ADR 2026-11.7t9z — versionCode is derived from versionName so a hotfix never outranks the next line's pre-releases

Status: Accepted (2026-11-03)

**Context:** `knit.versionCode` was one hand-bumped counter shared by stable and pre-release builds
(`gradle.properties`, `.agents/context/distribution.md` "Pre-releases"). Play hands a tester the
highest versionCode among the tracks they are eligible for, so a production build numbered above the
alpha track pulls every alpha tester back onto the older line. That line may not open the newer
database, and Android refuses the downgrade in the other direction. A hotfix to the shipped line takes
the branch's code + 1, which main's alphas have usually already used. 2.10.1 (code 34) fitted under
alpha.2 (36) only because 34 happened to be free. 2.10.2 found no free code: 35 and 36 were alphas on
Play. It shipped as 37, and 2.11.0-alpha.3 (38) had to reach the alpha track before 37 could reach
production. Each hotfix therefore waited on an alpha that was fit to publish. Nothing checked Play for
a code clash before upload, either: `lib.sh` computed 35 and the code was set by hand.

**Decision:** `app/build.gradle.kts` computes versionCode from `knit.versionName`, which stays the
single source of truth:

```text
versionCode = major·10⁷ + minor·10⁵ + patch·10³ + stage·10² + n
stage: 0 = alpha, 1 = beta, 2 = rc, 9 = stable
```

| Build | versionCode |
| --- | --- |
| 2.10.2 | 21 002 900 |
| 2.10.9 | 21 009 900 |
| 2.11.0-alpha.3 | 21 100 003 |
| 2.11.0-beta.1 | 21 100 101 |
| 2.11.0 | 21 100 900 |
| 21.0.2 | 210 002 900 |

Every hotfix of a line sorts below every pre-release of the next line, and a stable sorts above its
own pre-releases. Testers stay on the newer line until it ships, then the stable outranks their alpha
and they move onto it. Nobody has to cut an alpha before a hotfix. Promoting an alpha build to beta on Play reuses its code; a `-beta.N` build
sorts between that line's alphas and its stable. The fields have fixed widths counted from the right,
so 2.10.2 and 21.0.2 cannot collide. Every field is at most 99, major included: Play's cap of
2 100 000 000 alone would allow major 209, but `:wear` and `:watchface` take 1 000 000 000 plus the phone's
code, and a phone code at or above 10⁹ would land in the watch range. Main's stored code went from 38 to
21 100 003 (2.11.0-alpha.3, published as 38 and never rebuilt), a one-time jump forward that Play and
F-Droid both accept. Until an alpha on the new numbering reaches Play's alpha track, that track still
holds alpha.3 as 38, and any 2.10.x hotfix (about 21 003 900) would outrank it. Publish 2.11.0-alpha.4
(21 100 004) to the alpha track before the next production hotfix.

`knit.versionCode` stays in `gradle.properties`: fdroiddata's `UpdateCheckData` reads it from that file,
and the watch modules add it to their base. The build refuses a stored value that disagrees with the
derived one rather than pick either, and `scripts/version-code.py --write` sets it. The old
`-Pknit.versionCode` override is gone; nothing used it, and an override is the one way to break the
ordering.

The first alternative is the status quo plus a rule: always publish a fresh alpha before a production
hotfix. That is what 2.10.2 did. It ties every hotfix to the alpha line's readiness, and it breaks when
that line is red. Reserving a gap of codes per line was rejected too: a guessed gap runs out, and the
code still does not say which line it belongs to. A separate applicationId for testers was rejected
because it splits tester data from the real install. A pre-release must install in place over the
F-Droid, GitHub or mesh-shared app (ADR 014's shared distribution key).

The alpha-only build flags (`translate`, `commons`, `directLinks`, `stt`) stay manual: set in
`gradle.properties` for the tag, taken off main after it. Gating them on a pre-release versionName was
considered and rejected, because main keeps the last alpha's versionName between cuts, so every CI build
of main would become the tester build, and `translate=true` switches dependency locking off.

**Consequences / limits:** codes are large and no longer read as a release count. Changelog files
become `fastlane/metadata/android/en-US/changelogs/21100900.txt`, and `.fdroid.yml`'s
`CurrentVersionCode` takes the large number from the next stable on. A pre-release suffix other than
`-alpha.N`, `-beta.N` or `-rc.N`, or any n above 99, fails the build instead of guessing a stage. The
release skill's `lib.sh` "branch code + 1" (in the `.private` overlay) must give way to
`scripts/version-code.py --write`: set the name, derive the code. Testers still cannot downgrade without uninstalling; this does
not change that. A hotfix to a line whose pre-releases are still on Play (2.11.1 while 2.11.0-rc.N is
the alpha) is out of scope and cannot happen: the stable 2.11.0 already outranks them.

Guards: `scripts/test_version_code.py` pins the mapping (the table above, the 2.10.2 / 21.0.2
non-collision, the ordering across stages and lines, the refusals) and greps `app/build.gradle.kts` for
the same weights. The build fails on a stored code that disagrees with the name. CI's `doc-lint` job runs
the tests and `scripts/version-code.py --check`, and release.yml's preflight refuses a tag whose stored
code is not the derived one.
