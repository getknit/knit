# Reviewer brief template

Copy to `$S/REVIEW.md` and fill the slots. "Decided across chunks" holds what you settled after assembly (step 6):
every quoted setting or screen name, every term the translators rendered two ways. A reviewer applies it instead of
picking a side, which is what crossed the fixes in the French pass. Delete this preamble.

---

## {{Language}} ({{locale}}) second pass: reviewer brief

A first machine pass translated Knit's Android strings into {{Language}}, chunk by chunk, with `BRIEF.md` (same folder:
read it first; its rules, typography and glossary all still apply). You review one chunk: compare every string in
`chunkN.{{locale}}.xml` against its English in `chunkN.en.xml` (and the comment above it) and **fix only what is
wrong**. Don't restyle a correct string.

The whole first pass, assembled, is `app/src/main/res/{{values-dir}}/strings.xml` in {{REPO}}. Read it (don't edit
it) to see how the other chunks say the same thing.

### What counts as wrong

- **Meaning:** a mistranslation, an omission, an addition, a wrong sense. Read the comment; grep the Kotlin in
  `app/src/main/java` if unsure. Known traps: "heard" in the radio sense, not hearing audio; "carry" as holding
  others' messages; "relay" the Internet server vs. "Relay all calls" through a TURN server; diagnostics that read as
  if a call were being recorded.
- **Glossary:** a term not rendered as BRIEF.md says, or rendered two ways.
- **Grammar and agreement:** {{case, gender, number, aspect; agreement in every plural form}}; anything agreeing with
  a placeholder name of unknown gender (rephrase); a "(s)"-style form (write each plural item outright); `one`
  keeping its number placeholder. {{Elision before a placeholder for fr: avoid "d’%1$s" unless it surely starts with a
  vowel.}}
- **Register:** {{the wrong form of address anywhere}}, a calque of English word order, phrasing a native speaker
  would stumble over, Android's standard wording ignored ({{Settings, Cancel, Retry, Allow …}}).
- **Fit:** a short label (button, tab, chip, switch title, notification action, metric label) that a shorter natural
  wording would carry. Never cut meaning to fit.
- **Typography:** {{the language's spacing and quotation marks}}; ’ not `'`; `…` not `...`; sentence case.
- **Mechanics:** placeholders, `\n`, markup, attributes and plural items exactly as the English.

### Decided across chunks (apply these; don't relitigate)

- `settings_relay_calls_title` = {{…}}. Any string quoting a setting, screen or button quotes the translation of the
  string that names it exactly: find it by its English in `values/strings.xml`, then the same `name` in the
  assembled file. If your chunk holds the title and another chunk quotes it differently, keep yours and report the
  mismatch: don't adopt the other chunk's wording.
- {{forward secrecy = …; "Not now" = …; "Retry" = …; "Settings" = …; size units = …}}
- {{Android/Google system labels nobody could verify (battery options, unused-app pause, Family Link path): keep the
  first pass's wording unless clearly wrong; they are flagged for native review.}}

### Output

Edit `chunkN.{{locale}}.xml` in place, and nothing else. Run
`python3 {{SK}}/check_chunk.py chunkN.en.xml chunkN.{{locale}}.xml --locale {{locale}}` until it prints OK. Then
reply with: the number of strings you changed; one line per change, `name: old → new (why)` (at most 40; summarise
the rest by kind); any cross-chunk mismatch you could not fix inside your chunk; and at most five strings a native
speaker should look at first. No other prose.
