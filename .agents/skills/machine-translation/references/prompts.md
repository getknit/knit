# Agent prompts

Spawn each round's agents in one message so they run at once (`general-purpose`, in the background). Replace `$S`
with the absolute scratch folder, `$SK` with the absolute path of this skill's `scripts/`, `$LOCALE`, `$Language`,
`$N` and `$REPO`. The briefs carry the rules; the prompt only points at them, so all seven agents read one source.

## Translator (one per chunk)

```text
Translate chunk $N of Knit's Android strings into $Language. Read $S/BRIEF.md first and follow it exactly.
Input: $S/chunk$N.en.xml. Write: $S/chunk$N.$LOCALE.xml.
Check it with `python3 $SK/check_chunk.py $S/chunk$N.en.xml $S/chunk$N.$LOCALE.xml --locale $LOCALE` and fix
every error until it prints OK. Write nothing else anywhere: no edits in the repository. Report as the brief's last
section says.
```

## Reviewer (one per chunk)

```text
Second-pass review of chunk $N of Knit's $Language strings. Read $S/REVIEW.md, then $S/BRIEF.md beside it, and
follow REVIEW.md exactly. English: $S/chunk$N.en.xml. Translation: $S/chunk$N.$LOCALE.xml (edit it in place).
Edit nothing else.
```

## Render inspector (one per part, usually three)

```text
You are checking $Language renders of the Knit Android app for text that does not fit. Read-only: edit nothing.

Renders: check exactly the PNG files listed in $S/shots-part0$K, opening each with the Read tool. The English
reference of the same preview is in $REPO/app/src/screenshotTestDebug/reference/: glob for the same function name
with `_Light_` (the hash suffix differs); open it when you need to compare.
Strings: $REPO/app/src/main/res/<values-dir>/strings.xml (English: values/strings.xml).

Look for: a label cut off, ellipsized or clipped (buttons, chips, tabs, segmented buttons, top-bar titles, a row's
title pushed off by its trailing text); text overflowing or overlapping other content; a word broken mid-word; text
left in English that should be translated (sample data such as names and message bodies stays English: ignore it);
visibly wrong text (agreement, a typo, a stray `\'` or ` ` shown literally, punctuation wrapped alone onto a
line). Ignore anything the English render shows the same way.

A layout that cannot take longer text is a layout bug, not a translation bug, but where a shorter natural wording
would fix it, give one. Report only real problems, one line each:
`<render file> | <string name (grep the visible text in the strings file)> | <problem> | <shorter wording, or "layout">`
Then one line: how many renders you checked. No other prose.
```
