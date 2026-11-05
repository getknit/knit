---
id: "2026-11.tj49"
slug: a-declared-language-overrules-sure-words-when-only-it-can-be-translated
title: "a declared language overrules sure words when only it can be translated"
date: 2026-11-04
topics: [ml, ui]
---

# ADR 2026-11.tj49 — a declared language overrules sure words when only it can be translated

Status: Accepted (2026-11-04). Overturns one rule of ADR 2026-10.t2h6's 2026-10-29 amendment: "Words the identifier is
still sure of win, in any registry language".

**Context:** A sender's build declares the language a message was typed in (`lang`, from the composer's translation or
the keyboard), and the receiver read it only when CLD2 could not tell. A sure read always won, so a confident misread
hid Translate even when the sender had said the right language. The emulator pair trial on 2026-11-04 (an English and a
Spanish emulator meshing over the nRF52840 dongles) showed both shapes:

- "que fotos tan bonitas", declared Spanish, read as English at 95% even with Spanish as CLD2's hint. An English
  reader got no Translate and no "Not English?": the message had nowhere to go for them.
- "нема за що" and "так само хаха", declared Ukrainian, read as Serbian at 95%. Serbian has no model, so Translate was
  hidden for everyone. These lines carry none of the letters `LanguagePolicy` uses to rule Serbian out.

The rule had a reason: English typed on a Spanish keyboard is English. A Spanish speaker who writes English to an
English group declares Spanish by keyboard, and trusting that declaration would hide Translate from a Spanish reader,
for whom the message would then be "already Spanish".

**Decision:** When the words are sure of one language and the sender declared another that a model translates from,
the resolver keeps both (`Resolution.contested`), and the reader decides which one is used
(`TranslateTarget.forReader`):

- If the words' language goes somewhere for this reader, it is used, unguessed, as before. The Spanish reader of an
  English line typed on a Spanish keyboard still translates it from English.
- Otherwise, if the declared language goes somewhere, it is used as a `DECLARED` read, marked guessed. The bubble then
  says "Translated from Spanish · Not Spanish?". The English reader of "que fotos tan bonitas" gets Spanish; the
  reader of "нема за що" gets Ukrainian.
- Otherwise neither has a target, and nothing is offered, as before.

The menu's offer (`TranslateTarget.offers`), the tap (`ChatViewModel.translateMessage`) and the debug bridge's
`offers` and `translate --es id` all go through `forReader`. Unsure words are unchanged: the declaration settles them
unguessed, ahead of the author's and the chat's language.

Two alternatives were rejected. Letting the declaration always win brings back the keyboard case it was made for:
Translate disappears for the Spanish reader. Trusting it only over a read of a language with no model fixes the Serbian
case but not the English one, which is the commoner misread: CLD2 reads casual unaccented Spanish as English.

**Consequences / limits:**

- An English line typed on a Spanish keyboard now shows Translate to an English reader too, "from Spanish". A tap runs
  the Spanish model over English text and labels the result as a guess. The menu gains one item the reader will not
  use, where it used to lose one they needed.
- A Russian line typed on a Ukrainian keyboard is offered as Ukrainian. The Ukrainian model then translates Russian, and
  says it is guessing.
- The declaration stays the sender's claim. It can make Translate appear and choose the source model, but it decides
  nothing about moderation, trust or routing. The translation is screened in the message's scope like any other, and
  nothing happens until the reader taps.
- A build that does not declare, or has the Translation switch off, changes nothing on its readers' phones.
- Guarded by `LanguageResolverTest.sureWordsKeepAContraryDeclarationBesideThemOnlyWhenAModelTranslatesIt` and
  `TranslateTargetTest.sureWordsAndAContraryDeclarationTranslateFromWhicheverGoesSomewhereForTheReader`.
