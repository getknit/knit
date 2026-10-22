# Translations

How Knit's text reaches other languages. The app ships English only today; this is the groundwork and the plan.

## The plan: one language at a time, complete or not at all

- **Hybrid.** A language starts as a machine (LLM) pass over every string, glossary in hand, then native speakers
  correct it. Corrections arrive as merge requests against the committed `values-<locale>/strings.xml`, or through a
  Weblate instance pointed at the repo once there is one. Either way the repo's XML is the source of truth.
- **One locale lands whole.** Android Lint's `MissingTranslation` stays an error (`lint { warningsAsErrors }`,
  `context/toolchain.md`), so a locale is added only with every translatable string in it. A new English string
  then fails lint until each shipped locale has it: that failure is the reminder, not noise. Never relax the check,
  `tools:ignore` it or mark a user-facing string `translatable="false"` to get past it; translate it.
- **The language picker** is Android 13+'s per-app setting (Settings › Apps › Knit › Language). AGP generates the
  locale config from the `res/values-<locale>/` directories (`androidResources.generateLocaleConfig` in
  `app/build.gradle.kts`, the default's language in `app/src/main/res/resources.properties`). It is derived from the
  tree, so the F-Droid rebuild stays byte-identical (`context/distribution.md`). There is no in-app picker for older
  phones, which would need AppCompat.

## Writing a string

- Every word the user reads or hears is a string resource: text, a `contentDescription`, a semantics label, a
  notification and its channel, a toast. detekt's `HardcodedUiText` (`:detekt-rules`) fails a literal at the sinks it
  can name; text that reaches a sink through a variable or a parameter is out of its reach, so a helper that words
  something returns a `@StringRes` id, or takes a `Context` and is tested under Robolectric against the English
  resource (`CallWordsTest`, `CallerContextTest`). Once a `values-<locale>/` lands, a test that asserts English pins
  it with `@Config(qualifiers = "en-rUS")` rather than trusting Robolectric's default.
- Positional placeholders only (`%1$s`), a `<plurals>` for any count, and a comment above any string whose
  placeholder or context a translator cannot guess. Never build a sentence from pieces in code: two parts join
  through `joined_with_dot` (`%1$s · %2$s`), so a language can reorder them.
- Never change case in code (`lowercase()`, `replaceFirstChar`) on a translated string.

## Deliberately English

These are not oversights; leave them unless the plan changes.

- The call diagnostics card's values (`ui/call/CallDiagnostics.kt`, `CallStats`, `TalkPath`): an engineering readout
  in log notation, pinned by `CallStatsReaderTest`. Its labels are translated.
- Crash and ANR reports and the issue they open (`crash/`): read by the maintainers.
- The Meshtastic DM auto-reply (`mesh/lora/DmAutoReplyPolicy`): it goes to a stranger whose language this phone
  cannot know.
- License names (`legal/`), log lines, exception messages and the speech model's refusal details (shown after a
  translated sentence, as a technical detail).
- The `:wear` and `:watchface` prototypes, dark in release.

## Not covered by translating the UI

- Content filtering's word lists and toxicity model are English (`docs/CONTENT_MODERATION.md`): a room post in
  another language is screened far less.
- Speech-to-text is an English model (Moonshine, ADR 2026-10.nz3g).
- Right-to-left layouts are unchecked beyond the voice note's slide-to-cancel, which follows the layout direction
  (`ChatScreen`'s `MicButton`). Check each screen under a right-to-left locale when the first one lands.
