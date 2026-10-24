# Translations

How Knit's text reaches other languages. English is the source; Brazilian Portuguese (`values-pt-rBR/`, machine pass
2026-10-22 and a second machine review 2026-10-24, awaiting native review) is the first translation. Progress per
language is GitHub issue #43.

## The plan: one language at a time, complete or not at all

- **Hybrid.** A language starts as a machine (LLM) pass over every string, glossary in hand, then native speakers
  correct it, in Weblate or as a pull request against the committed `values-<locale>/strings.xml`. Either way the
  repo's XML is the source of truth.
- **One locale lands whole.** Android Lint's `MissingTranslation` stays an error (`lint { warningsAsErrors }`,
  `context/toolchain.md`), so a locale is added only with every translatable string in it. A new English string
  then fails lint until each shipped locale has it: that failure is the reminder, not noise. Never relax the check,
  `tools:ignore` it or mark a user-facing string `translatable="false"` to get past it; translate it.
- **Adding a language.** The machine pass translates the whole of `values/strings.xml` into `values-<locale>/strings.xml`
  against the glossary (`scripts/translation-glossary.tsv`, mirrored into Weblate's translator instructions),
  skipping `translatable="false"`, in the English file's order so Weblate's first rewrite of it is small. A second
  pass then reviews every string for meaning, glossary, agreement and fit, changing only what is wrong. Each
  `<plurals>` carries every quantity lint names for the language (`MissingQuantity`): Portuguese, Spanish, French and
  Italian need a `many` beside `one` and `other`, the same words as `other` (it only covers numbers like a million).
  The store listing goes in `fastlane/metadata/android/<locale>/` (Play's caps: 30 characters title, 80 short, 4000
  full); Play's copy is pasted into the Console by hand, and F-Droid reads the folder at the built commit.
- **`scripts/translation-check.py`** reads the XML alone, in a second, and names what lint would fail and what a
  reader notices first: a missing or extra string, a placeholder that moved, an unescaped apostrophe, a plural the
  language doesn't use (errors), and text left in English, a short label more than twice its English length, a
  glossary term with none of its renderings (warnings). Run it before `lint`; a new language needs its plural
  categories in the script's `PLURALS` table. Both CIs run it as an advisory job (`translation-check`,
  `verify:translation-check`) until the first locales settle; lint stays the gate.
- **The language picker** is Android 13+'s per-app setting (Settings › Apps › Knit › Language). AGP generates the
  locale config from the `res/values-<locale>/` directories (`androidResources.generateLocaleConfig` in
  `app/build.gradle.kts`, the default's language in `app/src/main/res/resources.properties`). It is derived from the
  tree, so the F-Droid rebuild stays byte-identical (`context/distribution.md`). There is no in-app picker for older
  phones, which would need AppCompat.

## Weblate

The project is `knit` on Hosted Weblate (https://hosted.weblate.org/projects/knit/), set up 2026-10-22 on the Libre
plan. Its one component, `app`, reads `app/src/main/res/values/strings.xml` as the template and
`values-*/strings.xml` as the translations, in Android format with Android locale folders (`values-pt-rBR`).

- **It never pushes.** The component's VCS is `github`: Weblate's bot commits in its fork of `getknit/knit` and opens
  a pull request against `main`, so a correction goes through CI like any other. Each commit carries
  `Signed-off-by: {{ author }}` (the translator) for the DCO check, and a `chore(i18n): …` subject.
- **New languages are requested, not created** (`new_lang: contact`). A language starts in the repo as a complete
  machine pass; Weblate finds the new `values-<locale>/` on its next pull and from then on carries corrections.
  Adding one in Weblate instead would open a pull request with a partial locale, which lint fails.
- **A machine pass imports as Translated**, since Android's format has no draft state; only the project's review step
  (approved vs. translated) tells a person's check from the machine's. Never bulk-mark imported strings "needs
  editing": with no draft state to write, Weblate can drop such a string from the file it commits, and a partial
  locale fails lint.
- **Source strings are edited in the repo only** (`edit_template: false`). Weblate picks them up from `main`.
- **Placeholders are enforced** (`java-printf-format`, enforced): a translation that drops or renumbers a `%1$s`
  cannot be saved.
- **The translator instructions** (tone, the glossary, plurals, length) live in the project's settings on Weblate.
  Change them there.
- A GitHub webhook to `https://hosted.weblate.org/hooks/github/` makes Weblate pull on every push to `main`.

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

## A layout for every language

A translation runs longer than the English, often by a third and by two or three times in a label of a word or two,
and Arabic, Persian and Hebrew lay the screen out right to left. Rewording a translation to fit works once; the
layout has to take the longer text.

- **Check it under the pseudolocales.** Every screenshot subject also renders as `LongText` (en-XA: each string
  accented and padded to about 40 % longer) and `Rtl` (ar-XB: mirrored, right to left), from the debug build
  (`context/testing.md`, "Compose preview screenshot tests"). Read both images of a new or changed subject before
  committing them, because the reference records whatever it shows, clipped or not. On a device, a debug build
  offers both under Settings › Apps › Knit › Language. A string that renders empty in both but not in English is
  aapt2's pseudolocalizer failing on it, not the layout: it blanked one that ended in an escaped apostrophe and a full
  stop (`relays\'.`). Reword the source string.
- **What to look for:** a label cut off or ellipsized on an action, text that wraps under a fixed height (a
  segmented button, a two-button row, a chip), a row whose trailing text pushes its title off, an icon that should
  mirror and doesn't (a back arrow, a chevron) or one that mirrors and shouldn't (a play button, a logo).
- **Fix the layout, not the string.** Give text no fixed width; let a button or a chip take a second line rather
  than clip it; let a row of two actions stack when either label is too long; save ellipsis for text shown in full
  elsewhere (a chat preview), never an action. Use `start`/`end`, never `left`/`right`. A few answers shown at once
  go through `ui/components/ChoiceButtons`, a segmented row that turns into a list of radio buttons when a label
  doesn't fit its segment. A title with something at its end (a status, tags, a button) goes through
  `ui/components/AdaptiveRow`, which moves the end part under the title once the two can't share the width,
  rather than a `weight(1f)` title the end part squeezes. Buttons that share a row go through
  `ui/components/ButtonRow`, which stacks them at the full width once a label would wrap in its share. A row of
  labelled round buttons (the call screen's) aligns its labels by their first baseline and centres them, so a label
  that wraps grows downwards. Text in a fixed height (a top bar's title, its status line)
  keeps one line and steps its size down before it ellipsizes: a top bar's plain title is `ui/components/BarTitle`.
  A card's actions sit outside its scrolling list, so a long explanation scrolls and the buttons stay in view.
- **Direction comes from the text, alignment from the layout.** The theme's type scale (`ui/theme/Type.kt`,
  `knitTypography`) gives every style `TextDirection.Content`, so an English message keeps its full stop in an Arabic
  interface and an Arabic one reads right to left in English, and aligns unaligned text to the layout's start, so a
  Latin name or value still lines up with its column there. Text read as written in every language (a safety number,
  a recovery key, a stack trace, a log line, a measured value, a "12 / 40" count) takes `leftToRight()` from
  `ui/components/LeftToRight.kt`. A `Canvas` or a hand-placed `Layout` does not mirror on its own: draw from the
  layout's start and hit-test the same way (the voice waveform, the emoji grid), and place with `placeRelative`.
  A translated string that opens with a placeholder takes the placeholder's direction, so an RTL translation wraps
  a leading name in an isolate.

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
