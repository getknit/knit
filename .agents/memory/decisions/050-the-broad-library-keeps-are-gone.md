---
id: "050"
slug: the-broad-library-keeps-are-gone
title: "The broad library keeps are gone: R8 now optimizes 97% of the app, and the xmlpull duplication bites twice"
date: 2026-08-26
topics: [build, release, r8, perf]
---

# ADR 050 — The broad library keeps are gone: R8 now optimizes 97% of the app, and the xmlpull duplication bites twice

Status: Accepted (2026-08-26)

Play Console reported Knit at **48% optimization, 48% obfuscation, 48% shrinking** and flagged all three.
The three numbers being identical is the diagnosis: a `-keep` blocks shrinking *and* optimization *and*
obfuscation over whatever it matches, so one set of over-broad rules moves all three together. R8 itself
had been on since ADR 012 — this was never "R8 is off", it was "R8 is not allowed to touch half the app".

R8's own keep-radius report (`configanalyzer.pb`, written by every `assembleRelease` on AGP 9.3; the
standalone task is `:app:analyzeReleaseR8Config`) named the culprits precisely. Three rules accounted for
**56,456 of the 60,652 blocked items — 93%** — and for 41.3% of the dex by size:

| rule | blocked items | dex |
|---|---|---|
| `-keep class com.google.crypto.tink.** { *; }` | 30,085 | 1.79 MB |
| `-keep class com.reandroid.** { *; }` (ARSCLib) | 23,397 | 1.42 MB |
| `-keep class com.android.apksig.** { *; }` | 2,974 | 0.20 MB |

All three were belt-and-suspenders from the ADR 012 flip, kept broad on the theory that a reflective
library is safer pinned than shrunk. None of them earned it. Tink ships the one consumer rule it actually
needs (`protobuf.pro`, `<fields>` of the shaded `GeneratedMessageLite`s) and is otherwise designed to be
shrunk: `TinkInit` calls `HybridConfig.register()` / `SignatureConfig.register()`, which reference their key
managers by static field, and `KeyTemplates.get(String)` reads the registry those calls populate rather than
doing a `Class.forName`. ARSCLib and apksig are reached only from `ui/invite/ApkMerger.kt` and
`ui/invite/ShareSigningKey.kt`, through six ordinary call sites R8 traces like any other code. Dropping all
three cost nothing and bought everything:

- rates **70.6 / 70.4 / 70.6% → 97.0 / 96.8 / 97.0%** (blocked items 60,652 → 4,744)
- dex bytes still carrying original package names **44.3% → 4.6%** (R8 full mode repackages everything it
  renames into the unnamed root package, so a real package name left in the dex *is* a keep rule's footprint)
- dex **9.90 MB → 6.03 MB**, and it stopped needing a second dex file
- APK 70.4 MB → 66.5 MB

**The xmlpull duplication bites twice, and the second bite is worse.** ADR 012 found that ARSCLib bundles a
copy of the platform package `org.xmlpull.v1` into the APK, so R8 renamed the *interface* out from under
the framework's `XmlBlock$Parser` and resource-XML inflation died with `IncompatibleClassChangeError`; the
fix was to pin `org.xmlpull.v1.**`. Pinning the names is not enough. Because the interface is a **program**
class here, R8 full mode also reasons about it under the closed-world assumption, and counts implementors.
The only ones in the APK are ARSCLib's own (`KXmlParser`, `ResXmlPullParser`) — `XmlBlock$Parser` is a
library class implementing the *library* copy of the interface, which the program copy shadows, so it does
not count. While `com.reandroid.**` was pinned wholesale, ARSCLib's implementors were trivially alive and
the question never arose. Shrink them away and R8 concludes the interface is uninhabited, every value of
that type must therefore be null, and it compiles `parser.next()` down to a literal `throw null`. Compose's
`painterResource()` then dies with a `NullPointerException` inside `seekToStartTag` on the first vector
drawable it loads — which is a chat-list avatar, so the app crashes on its first screen, having launched
and rendered onboarding perfectly. Confirmed by reading the shipped dex: `Resources.getXml()` is invoked
for its side effect and the very next instruction is `throw v3` with `v3 = null`.

The fix is one line and keeps only inhabitance, not names:

```
-keep,allowobfuscation,allowoptimization class * implements org.xmlpull.v1.XmlPullParser
```

R8 may still rename and optimize the implementor; it may not delete the last one. Cost: **+29 KB of dex**,
and the three rates do not move. That is the whole price of the bug.

**Verified on a minified, obfuscated `staging` build on an API-37 emulator**, because both remaining
hazards fail *silently* and a launch check proves nothing: Tink throws at `getPrimitive()` rather than at
load, and the moderators fail **open**. Checked: cold launch and render (xmlpull); `libsqlcipher.so` loads
and a sent message survives a restart (SQLCipher JNI); identity minted on first run and the contact-card QR
renders from the keyset (Tink `KeyTemplates.get` → `generateNew` → `TinkProtoKeysetFormat`, HPKE + Ed25519
public export); a genuinely abusive message is **refused** while a benign one sends (tflite text
moderation — the fail-open surface, and the one where "no crash" is the wrong signal); and Install offline
produces `Knit-2.4.0-alpha.0.apk` through the sharesheet (ARSCLib `ApkBundle`/`ApkModule` + apksig
`ApkSigner`). `missing_rules.txt` is empty and `lintRelease` is clean.

**CI now enforces it**, because the realistic regression is not someone re-adding these lines — it is a
dependency upgrade shipping a broad consumer keep rule, which is invisible in a diff and silent until the
next Play upload. `build:release` loses its `allow_failure: true`, gains `:app:analyzeReleaseR8Config`
(archived as HTML — it names the offending rule), and ends with `scripts/r8-dex-gate.sh`, which fails on a
step change in the share of dex bytes R8 was not allowed to rename. That share needs no proto parsing to
measure and no tooling the build does not already install: `apkanalyzer dex packages --defined-only`,
summed over the top-level packages that still have names. It is a coarse alarm (15%, against 4.6% today),
not a target.

Honest residuals (accepted): the **DM seal/open path is still unverified under R8** — it needs two peers
with radios, and an emulator has neither, so what has been proven is that Tink mints, stores, reloads and
exports keys correctly, not that a message round-trips; ARSCLib's `org.xmlpull.v1` copy is still shipped and
still shadows the platform's, so both keep rules stay load-bearing and a future ARSCLib bump must be
re-verified on a device rather than by reading a changelog; `net.zetetic.**` and `org.tensorflow.lite.**`
remain pinned `{ *; }` (together 1.1% of dex — the remaining slack is small enough that the fail-silent
risk is not worth taking); the baseline profile was **not** regenerated, because its rules name source
symbols that did not change and `compileReleaseArtProfile` reported none unmatched; and the 15% gate
threshold is a judgement call with no principle behind it beyond "far enough above 4.6% never to
false-alarm, far enough below 44.3% to catch this class of regression".

## Amendment 2026-10-16 — the byte ceiling is 9 MB (ADR 2026-10.hmfe)

`r8-dex-gate.sh` also fails on total defined dex bytes, a ceiling that went from 7 MB to 8 MB at 7.02 MB
(b41da399). Media3 Transformer, effect and muxer, which the short-video encoder needs, put the release build
at 8,051,605 bytes. That growth is the library's own classes, renamed and shrunk, not a keep rule, and the
named-package share stays well under its 15% alarm. So the ceiling is now 9,000,000, about 12% above
the measured size, as the last raise was. Media3 cannot be trimmed much further:
`Transformer.Builder.build()` refers to the default extractors and codecs directly, so R8 has to keep them.

## Amendment 2026-10-22 — the byte ceiling is 10.2 MB (ADR 2026-10.swvp)

Live calls, mesh calls and Knit Anywhere went on in release builds with 2.10.0-beta.0 (ADR 2026-10.swvp's
2026-10-22 amendment), and main's release build measured 9,110,099 bytes of defined dex code (job 9873), up from
8,379,001. The step is the call graph, core-telecom and the Anywhere wiring, renamed and shrunk, plus WebRTC's and
JNI Zero's Java classes, which `src/liveCalls/keepRules` pins because libwebrtc's native half calls them by name:
`org` grew from 27 KB to 255 KB, and the named-package share went from 4.4% to 6.6%, still well under the 15%
alarm. So the ceiling is now 10,200,000, about 12% above the measured size, as the last two raises were.

## Amendment 2026-10-29 — the platform copies are stripped before R8 (GitHub #59)

The duplication bit a third time. ARSCLib's jar ships `android.content.res.XmlResourceParser` and
`android.util.AttributeSet` beside `org.xmlpull.v1.*`. Only the xmlpull package was ever pinned, so every release
since the share path landed renamed the other two (`v0b` and `c10` in 2.11.0-alpha.1); ADR 012's comment that the
pin stopped those renames was never checked against a mapping. It stayed harmless until AGP 9.5.0's R8 (9.5.28),
the only toolchain change between 2.11.0-alpha.0 and alpha.1, cast the parser in Compose's `loadVectorResourceInner`
to the renamed `XmlResourceParser`. The framework's `XmlBlock$Parser` does not implement `v0b`, so 2.11.0-alpha.1
threw `ClassCastException` at the first vector drawable it drew: onboarding's welcome page on a new install, the chat
list's room avatar on a phone already set up. Every launch crashed, and no minified build had been launched between
the AGP bump and the tag.

Fixed in three layers:

- **The copies are gone before R8.** `StripPlatformCopies` in `app/build.gradle.kts` is an artifact transform
  requested on every app variant's runtime classpath. It drops each class the compile SDK's android.jar defines from
  the plain jars a variant packages, today ARSCLib's six. R8 then resolves them as library classes, open-world, so
  neither a rename nor the closed-world inference above can happen, and the three pins and the implementor keep are
  retired. Unit-test classpaths keep the copies. Built from 2.11.0-alpha.2's tree with the alpha's flags, only
  `classes*.dex` and the baseline profile differ from the shipped APK, and defined dex code is 6 KB smaller.
- **A mapping gate.** `scripts/r8-platform-gate.sh` fails a build whose mapping renames any class android.jar
  defines, whichever artifact brought it in; the transform does not see an AAR's classes. It runs in GitLab
  `build:release`, after both signed builds in `release.yml`, and in the release skill's CI mirror. It fails
  2.11.0-alpha.1's mapping.
- **A smoke test past onboarding.** `scripts/release-smoke.py` installs a minified APK on an emulator, walks
  onboarding, renders the chat list, sends in Nearby, opens Settings and cold-restarts, failing on any crash. The CI
  mirror runs it, and distribution.md's pre-release steps ask for it before an alpha's tag. It fails 2.11.0-alpha.1 on
  the welcome page and passes 2.11.0-alpha.2.

2.11.0-alpha.2 (code 36) shipped the interim fix, both names pinned, the same evening. The transform was verified on
an API 36 emulator: the smoke test passes on its minified APK, and Install offline on a split install of its bundle
merges and signs `Knit-2.11.0-alpha.2-36-merged-r1.apk`. Installing that merged APK on a second phone was not tried.
This supersedes the residual above that called both xmlpull keep rules load-bearing.
