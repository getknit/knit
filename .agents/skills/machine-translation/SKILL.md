---
name: machine-translation
description: Run Knit's machine (LLM) translation of the app's strings, the way the pt-BR, es, uk and fr passes were made — a glossary column, seven parallel translator agents on chunks of `values/strings.xml` with a shared brief, seven chunk reviewers, cross-chunk reconciliation, translation-check and lint, a rendered-preview fit check, a final read of every string, the store listing and the docs. Also translates new English strings into every shipped locale. Use when asked to start, prepare or do a machine pass, machine translation or first pass for a language or locale (`de`, `ar`, `pt-BR`, …), to add a language to the app, to do a final (quick) pass or review sweep over a locale's translations, or when translation-check, lint's MissingTranslation or CI's "Translations match the English" names a missing string.
---

# Machine translation pass

A locale lands whole: a machine pass over every string, then native speakers correct it on Weblate. The policy (one
locale complete or not at all, `MissingTranslation` stays an error, what stays English, layouts for long text) is
`.agents/context/translations.md`: read it first. This skill is the procedure. `references/locales.md` holds what
each shipped locale decided; read the nearest sibling before starting a new one.

`$SK` below is this skill's `scripts/` (`.agents/skills/machine-translation/scripts`), `$S` a scratch folder per
locale (`<scratchpad>/<locale>`), `$L` the locale as Android writes it (`fr`, `pt-BR`, `es-419`). The scripts are
stdlib Python and handle elements as raw text, so an escape or a no-break space reaches the file exactly as written.

| Script | Does |
| --- | --- |
| `chunk.py $S [--missing $L]` | the English (or what `$L` lacks) into 7 `chunkN.en.xml`, comments kept |
| `check_chunk.py EN TR --locale $L` | `scripts/translation-check.py` on one chunk, plus order, attributes, `\'` |
| `assemble.py $S --locale $L [--merge]` | chunks into `values-<dir>/strings.xml`, English order; `--merge`: a delta |
| `flat.py $L [--en] [--scan] [--grep L=RE]` | one string per line to read, or sweeps over the file |
| `renders.sh $L $S` | every screenshot subject rendered in `$L`, split in 3 lists |

## A. A new locale

Work on a branch `chore/translate-<locale>` from an up-to-date `main`. Settle the language's conventions yourself, as
Android's own translation into that language does (register, typography, Android's names for system screens), and
list them under "Decision for you" in the final report rather than asking first. Ask before starting only when the
scope is genuinely open: which variant a bare code means (`zh`), or a neutral base against one region.

1. **Plural table.** If the language is missing from `PLURALS` in `scripts/translation-check.py`, add its CLDR
   categories there (never a guess).
2. **Glossary column.** Add a `$L` column to `scripts/translation-glossary.tsv` with a short script that splits each
   row on tabs and pads it to the header's width; a hand edit left a stray tab once. A cell is a case-insensitive
   stem the checker looks for (`bloqu`, `|` between alternatives); keep=yes rows stay empty. Add the language's
   short form of "mesh" to that row's note. Check that the other locales' warning counts did not move.
3. **Chunks.** `python3 $SK/chunk.py $S` (about 230 strings each).
4. **Brief.** Fill `references/brief-template.md` into `$S/BRIEF.md`. Its glossary table matches the new column.
5. **Translate.** Seven translator agents in one message (`references/prompts.md`). Wait for their completion
   notices: a chunk file appears before its agent has checked it, so never poll the folder or sleep-loop on it.
   Read each report's judgment calls as they arrive and note what spans chunks (setting names quoted elsewhere,
   a term rendered two ways, "(s)" forms).
6. **Meanwhile:**
   - **Store listing** in `fastlane/metadata/android/<folder>/`: `title.txt` (Knit), `short_description.txt` and
     `full_description.txt` from `en-US/`, within Play's 30 / 80 / 4,000 characters (count with `len()` on the
     stripped text; trim wording, never whole sections). F-Droid reads the folder as is. Play has its own codes
     and refuses a bare `es`, `fr` or `de`: check the code (`gpc listings push --dir <a folder holding only this
     locale> --dry-run` reads, never writes) and, if refused, give the folder Play's codes in `play_codes` in the
     release skill's `play.sh` (the maintainer's `.private/`; tell the user if it is not there).
   - **Docs:** the locale's sentence in the opening paragraph of `.agents/context/translations.md` (and the plural
     line if its categories are new); the language name in the translations bullet of `CHANGELOG.md`'s
     `## Unreleased`, obeying `.agents/rules/changelog.md` (humanizer before the commit).
7. **Assemble and check.** `python3 $SK/assemble.py $S --locale $L`, then
   `python3 scripts/translation-check.py --locale $L > $S/check1.txt`. Fix errors in the chunk files. Triage the
   warnings with `flat.py $L --en --names …`: keep-terms, URLs and TURN strings are expected. Then **reconcile across
   chunks yourself, before the review**: every string that quotes a setting, screen or button must match the string
   that names it (grep the English title, then that name in the new file), and each term rendered two ways gets
   one. Edit the chunk files and reassemble.
8. **Review.** Fill `references/review-template.md` into `$S/REVIEW.md`, its "Decided across chunks" from step 7.
   Seven reviewer agents in one message. When all are in, reconcile again centrally: two reviewers "fixing" the same
   mismatch each adopt the other's wording, and it stays mismatched. Reassemble; `translation-check` must report 0
   errors. Then lint, one Gradle run at a time:
   `scripts/gradle-turn.sh ./gradlew :app:lintDebug -Ptranslate=false -q`. From here on, fix in the locale's
   `strings.xml` directly: the chunks are retired.
9. **Renders.** `$SK/renders.sh $L $S` (a few minutes: run it in the background, after lint). Three inspector agents
   (`references/prompts.md`) on `$S/shots-part00..02`. Apply the shorter wordings they give; a label that no wording
   fits is a layout bug, fixed as `translations.md` "A layout for every language" says (`ButtonRow`, `AdaptiveRow`,
   `BarTitle`) or reported. Check `git status` shows `ScreenshotPreviews.kt` unchanged.
10. **Final read.** Sweep: `flat.py $L --scan` plus the language's own `--grep` patterns (register, foreign letters,
    off-glossary words; `locales.md` has examples). Then read every string yourself: `flat.py $L --en > $S/flat.txt`,
    in pieces of about 250 lines. Fix the slips (wrong pronoun, a participle agreeing with a name, two wordings of
    one thing). This is the "final quick pass" the user used to ask for separately; it is part of every pass.
11. **Wrap up.** `translation-check` 0 errors, lint clean, `python3 scripts/doc-lint.py` clean, only the intended
    files changed (strings, listing, glossary, `translation-check.py` if step 1 ran, docs, CHANGELOG). Add the
    locale's section to `references/locales.md`. Report: counts, how it was made (fixes per round), the conventions,
    "Decision for you", the native-review questions (by string name, grouped as Android/Family Link labels, terms,
    wording, store listing) and anything found in code but not fixed. Commit with the `commit` skill (hint e.g.
    `French machine pass, refs GitHub #43`) when the user says so.
12. **Before it merges:** rebase on `main` and run `python3 scripts/translation-check.py --errors-only` for every
    locale. English strings that landed meanwhile show as `missing` (the French pass merged 8 short and failed
    CI's "Translations match the English"): translate them as in B.
13. **Issue #43** (GitHub `getknit/knit`), when the user asks: a row in "Where things stand" and a section for the
    language (one paragraph on the conventions, then a `<details>` of open questions). Draft it in a scratch file,
    run the humanizer skill on it, show the user, then
    `gh issue edit 43 -R getknit/knit --body-file <file>` (the whole body: fetch it first and edit that copy).

## B. New or changed English strings

`translation-check` (or lint, or CI) names strings missing from shipped locales, or extra ones the English dropped.

- **A handful:** write each locale's translation by hand, placed after the string that precedes it in the English
  file, using that locale's `locales.md` section, its glossary column, and how its neighbouring strings word the
  same feature (`flat.py $L --names …`). Delete a string the English dropped from every locale.
- **Dozens for a locale:** `chunk.py $S --missing $L`, translator agents with a brief rebuilt from the locale's
  section, then `assemble.py $S --locale $L --merge` (adds the delta, drops what the English lost, rewrites in the
  English order).
- A changed English string whose meaning moved needs every locale re-translated, not just new keys.
- **On Weblate:** a new key in the repo is fine (Weblate picks it up from `main`). Correcting a string a native
  speaker may already have reviewed goes up as a suggestion through the `weblate-cli` skill, never as a repo edit.
- Then `translation-check` 0 errors and lint, as in A.

## C. A final pass on its own

"Do one final quick pass over the X translations" is step 10 alone, then `translation-check` again.

## Traps the earlier passes hit

- **Crossed fixes:** parallel reviewers each switched to the other chunk's wording. Settle cross-chunk names
  centrally, before and after the review; tell reviewers to keep their own and report the mismatch.
- **Quoted names:** a string that tells the user to open « Relay all calls » must quote that setting's own
  translation. Every pass had at least one.
- **"(s)" forms in plurals** (`appareil(s)`, `envoyé(e)`): each item writes its form outright.
- **A name's gender:** nothing may agree with a placeholder name; rephrase («%1$s : …», a noun, the present tense).
- **Android's own labels** (battery options, the unused-app pause switch, the Family Link path, full-screen
  notifications, Data Saver) cannot be verified from here: one wording everywhere, flagged for native review.
- **"Relay all calls"** sends calls through a TURN server: it must not read as Knit's relays (uk «Усі дзвінки через
  сервер»; fr « Relayer tous les appels », the verb, never « relais »).
- **Diagnostics** that read as if a call were recorded (uk): read the comment and the code before translating
  capture or tap wording.
- **One Gradle run per worktree:** lint, then renders, never both. `-Ptranslate=false` matches CI.
- **The preview file:** `renders.sh` restores `ScreenshotPreviews.kt`; never run `updateDebugScreenshotTest` with
  the temporary preview in place (it would write `_<Locale>_` references).
- **Play language codes** differ from Android's (bare `es`, `fr`, `de` refused): step 6.
