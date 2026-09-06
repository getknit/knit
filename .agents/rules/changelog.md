# Changelog prose

Rules for user-facing release prose: `CHANGELOG.md`'s `## Unreleased` section and the
`fastlane/metadata/android/en-US/changelogs/<code>.txt` file cut from it. **These apply to new entries
only** — the shipped sections below `## Unreleased` are a record of what was published and are never
rewritten to this standard.

Where the file goes and who reads it: `context/distribution.md`. A pre-release has no fastlane changelog,
so its GitHub Release notes are the `## Unreleased` section verbatim — what you write here is published.

## Two sentences, about forty words

One bullet is one change. Say what changed, then who notices; stop.

```markdown
- Phones that only relayed your messages no longer count as nearby. The online dot, chat list, group
  picker and nearby count now show only what your own radios can currently see.
```

- **A third sentence needs a reason.** A caveat the reader must act on, or a limit that would otherwise
  surprise them. "Why we did it" and "how it works" are the commit message's job, not this file's.
- **Cut the mechanism.** The reader has no idea what a custody digest, an NDP or a sender-key ratchet is,
  and does not need one to know their messages arrive.
- **Name the surfaces, don't enumerate the internals.** "The online dot, chat list, group picker" is worth
  the words; "MeshController.neighbors" is not.
- **Keep the wrap at 110 columns**, matching the rest of the file.

## Run humanizer before you commit

Every new entry goes through the `humanizer` skill before it lands. Two `PreToolUse` hooks on `CHANGELOG.md`
then check it, and they split the work on purpose — each one only judges what it can actually be right about.
Both look at the bullets a call *adds* under `## Unreleased`, reconstructed by diffing against the file on
disk, so neighbouring bullets carried along as edit context are never the ones blamed.

- `.claude/hooks/changelog-gate.py` counts: words (55), sentences (3), the 110-column wrap, emoji, curly
  quotes, and a short list of words with no honest use here (delve, seamless, testament, …). Exact numbers,
  no judgement, and it fails open on anything it cannot parse.
- The prompt hook judges taste against the same AI-tells list that gates outbound `gh` prose — marketing
  register, a closing sentence that restates the opening, a forced metaphor. It may block only on a span it
  quotes verbatim from the bullet, and it is barred from citing any count, because the model behind it used
  to invent word counts and quote em dashes that were not in the text.

The tells that keep showing up in this file: negative parallelism ("not just X, but Y"), rule-of-three
padding, "seamless" / "robust" / "ensure" as filler, an em dash used for rhythm rather than an aside, and a
closing sentence that only restates the opening one.

## Categories

[Keep a Changelog](https://keepachangelog.com)'s six, in the order the file already uses: Added, Changed,
Fixed. Frontmatter, heading grammar and the consumer contract are documented in the file's own
`## About this file` section — read it before changing the shape of a heading.
