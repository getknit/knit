---
id: "2026-10.2kfr"
slug: a-lookalike-or-invisible-name-collides
title: "A lookalike or invisible name collides, and received names are cleaned"
date: 2026-10-29
topics: [text, interop, identity]
---

# ADR 2026-10.2kfr — A lookalike or invisible name collides, and received names are cleaned

Status: Accepted (2026-10-29)

**What was observed.** H8 and the Medium "names" item of the malicious-fork review (knit-next #228, knit-ios #30).
ADR 058's `NameKey` folded case, NFKC compatibility forms and format characters, and recorded "no confusable
folding" as a limit. A stranger could name itself "Alice" with an invisible mark that is not Cf (U+FE0F, U+034F, a
Hangul filler, the Braille blank U+2800), a lookalike letter (Cyrillic е, Greek ο) or a capital I for an l ("AIice"),
and it rendered as a plain "Alice" beside a contact of that name — in Nearby, groups, mentions, bubbles and
notification titles — with no " (Alias)" suffix. A received name or status was only clamped, so a line break, a
control or a bidi override reached a header.

**What changed.** `NameKey.of` is five steps, mirrored by knit-ios: NFKC; fold the 73 UTS #39 15.1.0
`confusables.txt` entries whose source is one Latin, Greek or Cyrillic code point and whose prototype is one ASCII
letter, on the case-preserved string (UTS #39 is case-sensitive: Cyrillic `В` is B-like, `в` is not); lowercase in
the root locale, then ς → σ (Java applies Final_Sigma, Swift may not) and l → i (the table's own I → l would
otherwise stop "ALICE" keying with "Alice", so every I, i, l and their lookalikes share one letter); drop Cf, Mn,
Me, the literal Default_Ignorable ranges of Unicode 15.1 and U+2800 (after lowering, so `İ`'s U+0307 goes too);
collapse ART's `\s` runs and trim. The table and ranges are literals, never derived at run time. A collision only
costs a suffix, so the fold is generous: "Eli" and "Ell", or a Hindi name with and without a vowel sign, both show
their alias words, and nothing is merged, hidden or refused.

`NameKey.isBlankName` (null, or an empty key) decides only whether a person or group shows its alias or
member-derived title; `PeerLabels`, `tokensInName`, `displayNameFor` and `groupTitle` flip together, so a U+3164-only
name renders as the alias and never as `Alias (Alias)`. Wire blankness is unchanged: `isBlank` still decides what a
group frame carries and every composer gate, so a U+3164-only group name is still a name on the wire.

`TextLimits.cleanReceived(value, limit)` removes C0/C1 controls outside regex `\s` and the bidi controls,
then `normalizeSingleLine`, `clampToUtf16`, and trims both ends again (a cut can land after a space, and a leading
U+0085 is `\s` but not `isWhitespace`; contract revision 1, so it is idempotent). It keeps ZWJ and ZWNJ for emoji
sequences and Persian names. A received profile's name (32) and status (100) are cleaned at store time, on the
cleartext and the sealed `CTL_PROFILE` paths; the verbatim signed frame in `peer_profiles` stays byte-exact, and a
row stored raw before the upgrade is compared cleaned, so the upgrade posts no rename notice. **A group name is
cleaned only where it is drawn** (`groupNameForDisplay`: the title, notification titles through it, and the rename
notice), and tested display-blank after cleaning, so a name of only controls falls back to the member-derived title
(revision 2). The stored group name stays raw because this device signs it into its own group frames
(`GroupEntity.toGroupInfo`): cleaning it at store would change what we author and spread the cleaned name
last-writer-wins, a convergence change left out of this one.

The regex `\s` that `normalizeSingleLine` and `NameKey` collapse is now a literal class of ART's set
(`ART_WHITESPACE_RUN`, the one ADR 2026-10.swst pins). Identical on a device; on the desktop JDK, whose `\s` is
ASCII, it makes JVM tests and the JVM-written vector answer as a phone does. `DesktopJdkWhitespaceTest` still records
the JDK's own `\s`.

The alternative a reader reaches for first — full UTS #39 skeletons, or `rn` → `m` — costs a table both ports must
hold byte for byte and folds far more real names together, for an attack the discriminator already makes visible.
Cleaning group names at store was the other; see above.

**What it costs.** A suffix between real names that fold together. Multi-character confusables and other scripts
still read as their own names; trust stays with `verified` and the safety number. Cf, Mn and Me come from each
platform's Unicode tables (ART's ICU, the iOS release), so a code point assigned after the older side's Unicode
version may differ: accepted, as U+180E was. Raw names shown outside the label index (a heard Meshtastic `longName`,
a mention's `Mention.name`, relay-invite room names, a contact card's name) are follow-ups. Rows stored before the
upgrade stay raw until that peer's next profile; no migration.

`vectors/name-key-v1.json` (`NameKeyVectorTest`, written on the JVM) pins the fold, the drop ranges, the limits and
59 cases inside Unicode 11; `ArtTextSemanticsTest.nameKeyVectorCasesHoldOnArt` replays them on ART (managed
emulators at API 33 and 34: all agree with the JVM). A case outside Unicode 11 would read API 29's ICU differently.

## Companion change for knit-ios

knit-ios #30 on `fix/name-confusables`: `NameKey` moves to `KnitWire/Identity` beside `TextLimits` with the same
five steps, `isBlankName` and `cleanReceived`; `Alias.displayName`, `tokensInName` and `GroupNaming` use display
blank; `profileRecord` and `applySealedPresentation` clean at store; `GroupNaming` cleans the title and the rename
notice. It syncs `name-key-v1.json` as an optional vector and asserts its constants and every case.
