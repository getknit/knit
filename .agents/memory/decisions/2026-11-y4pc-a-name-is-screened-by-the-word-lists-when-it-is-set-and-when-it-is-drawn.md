---
id: "2026-11.y4pc"
slug: a-name-is-screened-by-the-word-lists-when-it-is-set-and-when-it-is-drawn
title: "A name is screened by the word lists when it is set and when it is drawn"
date: 2026-11-13
topics: [moderation, identity, privacy, contacts]
---

# ADR 2026-11.y4pc — A name is screened by the word lists when it is set and when it is drawn

Status: Accepted (2026-11-14). Extends ADR 2026-10.9rh6's avatar exception to names and
statuses, and uses the lists of ADR 2026-10.d62w through a matcher built for names.

**What was observed.** No classifier ever sees a display name, a status line or a group name.
`TextLimits.cleanReceived` cleans their Unicode and length (ADR 2026-10.2kfr), and nothing else touches them. A name
also reaches more people than any message does: the first-sighting profile flood carries it to strangers in range, it
labels its owner in the Nearby room, in mentions and in notification titles, the launcher keeps it in conversation
shortcuts, and a stock Meshtastic client shows a board's long name on the public band.

The message pipeline could not just be pointed at names, and it was measured on name data before anything was
designed (2026-11-13). The shipped `LexicalTextFilter` (compiled on its own) and the shipped `toxicity.tflite` (fed
exactly as `MlTextModerator` feeds it) were run over:

- 91,213 US given names from Social Security baby-name data, births since 1950, weighted by births;
- 162,253 US surnames from the 2010 Census, weighted by people;
- 4,062 given names and surnames from other countries (`sigpwned/popular-names-by-country-dataset`);
- 8.3 million usernames (SecLists' `xato-net-10-million-usernames`), used for hit rates and hand-labelled samples
  only;
- 4,500 synthetic offensive names, made by putting every term of both lists into ten username shapes (`BigX`, `bigx`,
  `xXXxx`, `X69`, `x_lord`, leetspeak, `Mr X` and others).

| Method | Given names hidden | Surnames hidden | Synthetic names caught |
| --- | --- | --- | --- |
| Shipped filter, slur list | 0 | 0.0002 % | 50 % |
| Shipped filter, both room lists | 0.002 % | 0.024 % | 50 % |
| Plus name splitting (camelCase, digits, `_ . -`) | 0.002 % | 0.024 % | 80 % |
| Plus inside-word matching, every term of 4+ letters | 0.025 % | 0.36 % | about 100 % |
| Plus inside-word matching, embeddable terms only (surnames held out) | 0 (in-sample) | 0.003 % slurs, 0.005 % room | about 95 % |
| Toxicity model alone, app thresholds | 0 | 0.003 % | 1.2 % profane, 0.4 % slurs |

The toxicity model is no use on names. It flagged no given name and four surnames (Kill, Balls, Mastin, Nigg), and
caught about one synthetic offensive name in a hundred. It scores names the way ADR 2026-10.d62w found it scores a
bare slur. Whole-word matching misses most usernames (`BigD1ck69`, `xXslurXx`), and matching every term inside words
hides about 1 American in 280 by surname (Dickson, Hancock, Cummings, Montenegro, Spicer). An allow-list of the real
names that contain a term (Dickson covers `dick`) did not generalize. Built from given names and tested on surnames,
it still hid 0.05 % of people.

Two smaller findings came out of the run:

- The room lists already hide real names as whole words. Surnames: Dick, Coon, Poon, Cumming, Boner, Negro, Wank,
  Rape, Felch, Wanker, Cock, Porn. Given names: Dick, Shota (a common Japanese name), Haji and Hadji, Hymie, Skeet.
  Two surnames, Beaner and Pikey, are on the slur list.
- A bug that hid every lone "x" in the rooms, fixed on its own in commit 4e47bc5.

**What changed.**

Where names are screened:

- **Your own name and status, when you set them.** `ProfileViewModel.save` and `OnboardingViewModel` screen with
  the room's rules (slurs and profanity). A flagged value is not saved, and the screen says why, as
  `PhotoChange.REFUSED` does for a photo. Like the avatar screen, this runs whatever the Content filtering toggle says
  and fails open, and a modified client can skip it.
- **A received person's name and status, where they are drawn.** The room's rules apply, because strangers see
  them. The name is never rewritten or dropped. The profile frame is signed, custody carries it byte for byte, and
  `peer_profiles` keeps it verbatim.
- **A group's name, where it is drawn.** Only the slur list applies, because only members see it, which matches the
  private scope of ADR 2026-10.d62w. The Message Requests list is the exception: a stranger made a request's group and
  added us without asking, so its row reads the name with both lists, as a stranger's name is. Once the request is
  opened, the group's own screens read it as members' names are. `groupNameForDisplay` is already the one place a group name is cleaned. The
  stored name stays raw for the reason ADR 2026-10.2kfr gives: we sign it into our own group frames.
- **A Meshtastic board's long name, where it is drawn.** The room's rules apply.

The verdict is worked out where the name is drawn, and is not stored on the row. The word-list pass is cheap: 8.5
million names took 15 seconds across twelve JVMs. A small cache keyed by the cleaned string makes repeats free. Storing a
`nameFlagged` column was the obvious alternative. It would need a migration, and a backfill each time the lists are
regenerated, and a stale column would keep hiding a name the new lists allow.

With Content filtering on, a flagged name draws as if it were blank. It takes the alias path that already serves
names made only of invisible characters: `NameKey.isBlankName`'s consumers (`PeerLabels`, `displayNameFor`,
`groupTitle`) switch together on one predicate. So a flagged person shows as their alias, and two flagged people stay
apart. A flagged status shows nothing. With the toggle off, both show as written, as a flagged message does.

The matcher is a name screen beside `LexicalTextFilter`. It is not a change to the message filter, which keeps
whole-word matching so a sentence stays clear of false hits inside words.

- **Split the name.** Try the name as written, with camelCase boundaries split, and with digit runs as separators.
  Trying the original as well keeps leetspeak working.
- **Match whole words** on every variant, with both lists, or the slur list alone for a group name.
- **Match inside words only for embeddable terms.** A term qualifies when it has four or more letters and no word in
  the English dictionaries (`american-english`, `british-english`) or the given-name and surname data contains it.
  A word that is itself a list term, holds a longer list term ("motherfuckers"), or is the term with an inflection
  ("fucked", "bitchier", "bullshitted") is not counted as such a word. That left 339 terms on the day it was built.
  Exclude the families that hand-labelling usernames found:
  - `spikey`, which contains `pikey`;
  - `darkeye`, which contains `darkey`;
  - the Russian `stranny` ("strange"), which contains `tranny`;
  - the Uzbek name Dildora;
  - `phonky`, from the music genre phonk, which contains `honky`.

  Leetspeak counts inside a word only between letters, so `ashish17tripathi` is not read as containing a profanity.
- **Allow real names.** A profanity term that is also a given name or surname in the census and baby-name data
  passes as a word written in plain letters. That is 19 terms (Dick, Coon, Cumming, Shota, Haji and most of the rest
  above), each with at least 100 people or births; Hymie falls under that floor and stays hidden. A leet spelling is not a name, so `BigD1ck69` and `D!ck` are still read
  against the list. A slur is never allow-listed.
- **No toxicity model.**

The embeddable set, the exclusions and the real-name allow-list are generated and committed, as `slurs_en.txt` is,
by `scripts/gen-name-screen-lists.py` from the shipped lists and the pinned name data. They are never edited by hand.

The alternative a reader reaches for first is running names through the message pipeline (`ScopedTextModerator`).
The table shows why that fails: whole words miss half the shapes a username takes, and the classifier adds a model
load to catch almost nothing.

**What it costs.** Hidden real names: 0.003 % (slurs) or 0.007 % (room) of the surnames, measured with the surnames
held out of the generator, plus the surnames Beaner and Pikey (0.0002 %). Allowing real names lets "Dick", "BigDick"
and "big_dick" through: a real name passes wherever it is written in plain letters.

On usernames, splitting adds hits on about 0.06 % of names, and 70–85 % of a labelled sample was really offensive.
Inside-word matching, with the lists as built, adds 0.056 % (room), and about 87 % of a labelled sample of 60 was
really offensive.

Recall has a known hole. A run-together lowercase name built on a term that is not embeddable gets through:
`bigshit` passes, because `shit` sits inside the Indian name Akshit, and `niggerbitch` passes the inside-word look,
because `nigger` sits inside "snigger". Either is caught when it is written as words (`BigShit`, `big_shit`). On the
synthetic names, recall was 96 % with the slur list and 93 % with both.

The data is mostly American. Names from other countries were 4,062 entries, and the Russian and Central Asian false
hits only showed up through usernames. These counts are point-in-time evidence for this decision, not current
figures. The measurement harness lives outside the repository today; building this brings it in, so the dials can be
measured again.

The trap is a surface that reads the stored name directly. It would draw a flagged name in full, and the
notification shade and the launcher keep that string after the app moves on. Every surface goes through the one
predicate, the way every surface reads `MessageEntity.hiddenByFilter` for a message.

knit-ios draws the same names, so this is a paired change. A `vectors/name-screen-v1.json` file pinning name →
verdict for both lists keeps the two apps in step, as `vectors/name-key-v1.json` does for `NameKey`.

Guards: `NameScreenTest` (each variant, the embeddable rule, the exclusions, leet between letters, real names in
plain letters), `NameListsTest` (no allow-listed slur, no dead exclusion, real names pass, the labelled benign words
pass), `NameScreenVectorTest` (the file knit-ios checks), `PeerLabelsTest` and `PeerRepositoryTest` (a flagged name
labels as the alias while filtering is on, a raw name a caller passes included, our own never), `ConversationFacesTest`
(the notification and shortcut title), and `ProfileViewModelTest` / `OnboardingViewModelTest` (refused on set).
`NameScreenCorpusTest` re-runs the measurement on the pinned name data when `KNIT_NAME_CORPUS` names it.
