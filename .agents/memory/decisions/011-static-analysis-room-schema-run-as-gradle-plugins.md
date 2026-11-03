---
id: "011"
slug: static-analysis-room-schema-run-as-gradle-plugins
title: "Static analysis + Room schema run as Gradle plugins (supersedes 007)"
date: 2026-07-08
topics: [build, toolchain, static-analysis]
---

# ADR 011 — Static analysis + Room schema run as Gradle plugins (supersedes 007)

Status: Accepted (2026-07-08, branch `build/gradle-plugins`)

Reverses ADR 007's "standalone CLI" doctrine. detekt (`dev.detekt` 2.0.x — the first line supporting
Gradle 9; 1.23.x capped at Gradle 8.12.1), ktlint (`org.jlleitschuh.gradle.ktlint` 14.x), and Room schema
export (`androidx.room`) now run as ordinary Gradle plugins. Safe because each analyzer runs in its own
isolated task classpath and adds nothing to `:app`'s compile/runtime graph (verified: `assembleDebug` +
`lint` unaffected), so the Kotlin-2.4-metadata hazard that motivated the CLIs (and Koin-not-Hilt, ADR 001)
doesn't apply — none does compile-time codegen on `:app`'s sources. Kover was already a plugin (its old
"one exception" framing is retired). ktlint's check task is now `ktlintCheck` (and `ktlintFormat`
autocorrects); the CI `verify:detekt` job runs `./gradlew detekt` (a `verify:ktlint` job was added).
*Amended by ADR 2026-11.acs5 (2026-11-02): detekt no longer runs without type resolution; `detekt` stands for
the type-resolved `detektAll` over every source folder.*
Detail: `context/toolchain.md`.
