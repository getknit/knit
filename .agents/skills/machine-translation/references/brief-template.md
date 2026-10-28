# Translator brief template

Copy to `$S/BRIEF.md` and replace every `{{…}}`. The fixed text is what every earlier pass needed; the slots are the
decisions a language makes once (`locales.md` has them for each shipped locale, and the nearest sibling is the best
model: es or pt-BR for a Romance language, uk for a Slavic one). Keep the glossary table in step with the locale's
column in `scripts/translation-glossary.tsv`. Delete this preamble.

---

## {{Language}} ({{locale}}) machine pass: translator brief

Knit is an offline Android mesh messenger: phones carry encrypted messages to each other over Wi-Fi Aware,
Bluetooth and (optionally) LoRa radio boards, with optional Internet relays. Its users are ordinary people (hikers,
festival-goers, people in an outage), not engineers, except in diagnostic and developer strings. Tone: plain, warm,
direct, short. Never marketing, never bureaucratic. You translate its UI strings from English into {{Language}}.

{{Scope. A language-only folder is the base every speaker falls back to, whatever the region: neutral, standard
{{Language}} with no regionalisms; a region can override single strings later. Name the regions, e.g. "France,
Belgium, Switzerland, Canada, Africa". A region-tagged folder (pt-BR) says whose language it is instead.}}

Repo: {{REPO}}. Read-only for you, except the one output file you are told to write.

### Input and output

- Input: `chunkN.en.xml`, a run of the English `app/src/main/res/values/strings.xml`. Its XML comments are context
  for you: read them, they say where a string shows, what fills a placeholder and how much room there is.
- Output: `chunkN.{{locale}}.xml` beside it, a complete resource file (`<?xml version="1.0" encoding="utf-8"?>`, then
  `<resources xmlns:tools="http://schemas.android.com/tools">`) holding **every** `<string>` and `<plurals>` of the
  chunk **in the same order**, each with the same `name` and **exactly the same attributes** (`tools:ignore`,
  `formatted`, …). No comments, no blank lines; 4-space indent, plural items 8.
- Check it: `python3 {{SK}}/check_chunk.py chunkN.en.xml chunkN.{{locale}}.xml --locale {{locale}}`. Fix every error
  and run it again until it prints OK. Read the warnings and fix the real ones (a short label grown past twice its
  English, a glossary term missed); "same as the English" on a product name or a URL is expected.

### Android string rules (the build fails otherwise)

- **Apostrophe: always the typographic ’ (U+2019)**, never `'` and never `\'`.
- **Quotation marks: {{the language's own, e.g. « » with no-break spaces inside, „ “, “ ”}}.** A literal `"` breaks
  the string unless written `\"`; avoid it.
- A string that starts with `@` or `?` writes it `\@` or `\?`.
- Keep `\n` line breaks exactly as the English has them (the same count), entities (`&amp;`, `&lt;`) as entities, and
  inline markup (`<b>`, `<annotation …>`, `<xliff:g …>`) around the corresponding words.
- **Placeholders** (`%1$s`, `%2$d`, `%d`, `%1$.1f`, `%%`): the same set with the same numbers in every string and in
  every plural item. You may move one within the sentence. A literal percent sign is `%%`.
- `…` (U+2026), never `...`.

### Plurals

{{The CLDR categories from the PLURALS table in scripts/translation-check.py, what numbers each covers, and an example
noun in each. Romance (pt, es, fr, it, ca): `one`, `many`, `other`, with `many` word for word the same as `other` (it
only covers numbers like a million). Slavic (uk, ru, pl): `one` (1, 21), `few` (2–4), `many` (0, 5–20), `other`
(fractions). Agreement of adjectives and verbs with the count in every form.}}

- Emit **every** category for every `<plurals>`, no more.
- Keep the number placeholder in `one` even where the English `one` says "a minute": {{one covers 0 in pt and fr, 21 in
  uk}}.
- A string named `…_zero` is shown only at 0: write the natural zero form ({{example}}).
- Never a "(s)"-style form (`appareil(s)`, `envoyé(e)`): each item says its own form outright.

### Typography

{{The language's rules: spaces before punctuation (French: U+00A0, written as the literal character, before `:` `;`
`!` `?` `»` and after `«`, and between a number and its unit or `%%`), inverted marks (Spanish ¿ ¡), the decimal
separator, unit abbreviations (Ko / Mo / Go, КБ / МБ), the dash.}}

- Sentence case for titles, buttons and settings: only the first word and proper nouns capitalised, never English
  Title Case.
- `·` separators stay as they are.

### Voice and register

- {{The form of address and its imperative, chosen as Android's own {{Language}} does: vous, ви, tú/ustedes, você.
  Never mix forms.}}
- Buttons are verbs as Android {{Language}} writes them: {{Cancel, Save, Delete, Retry, Share, Send, Block, Allow}}.
- Android's own words for system things: {{Settings, Notifications, Permissions, Nearby devices, Battery Saver,
  Airplane mode, Location, Camera, Do not disturb, Data Saver}}.
- **Short labels** (buttons, tabs, chips, switch titles, notification actions, metric labels) stay short: aim at
  1.5× the English at most; the checker warns past 2×. Pick the shorter natural word. Never truncate meaning.
- **Gender:** a placeholder name's gender is unknown, so nothing may agree with it. Rephrase ({{example: a noun, a
  present tense, "%1$s: …"}}). No inclusive-writing marks. Speak to the user in a form that needs no gender
  ({{example}}).
- {{The language's own traps: calques and Russianisms for uk, regional words for es, …}}
- Translate meaning, not word order. Don't add exclamation marks or meaning the English doesn't have.

### Keep as is

Knit, Knit Anywhere, Meshtastic, Wi-Fi Aware, Wi-Fi Direct, Wi-Fi, Bluetooth, LoRa, GitHub, Ko-fi, Google Play,
F-Droid, Android, SQLCipher, TURN, STUN, NAN, BLE, PHY, Coded (the Bluetooth PHY's name), spool (diagnostics only),
URLs, `wss://…`, `knit-commons:v1:…`, `geo:`, file names and codes. {{walkie-talkie and backup: kept by pt-BR and es,
translated by uk (рація, резервна копія) and fr (sauvegarde); say which.}}

### Glossary (fixed renderings: consistency matters more than elegance)

| English | {{Language}} | what it means |
|---|---|---|
| mesh / the mesh | {{sentence form; short form; gender}} | the network the phones form |
| Nearby (the public room) | {{}} | a proper name: keep its capital |
| room | {{}} | a chat room |
| commons | {{}} | a private relay's group chat |
| relay | {{}} | an Internet server that carries messages |
| relay invite | {{}} | |
| "Relay all calls" (setting) | {{}} | calls go through a TURN server: must not read as Knit's relays |
| message request | {{}} | someone not yet a contact writing to you |
| contact / verify / block | {{}} | verify = safety-number verification |
| group / direct message | {{}} | |
| chat / chat list | {{}} | |
| phone / device | {{}} | phone = a device, never a call |
| board | {{}} | a Meshtastic / LoRa radio board |
| radio | {{}} | a LoRa radio |
| call / live call / mesh call | {{}} | live = over the Internet, mesh call = push-to-talk over the mesh |
| walkie-talkie | {{}} | |
| voice note | {{}} | |
| transcript / transcribe | {{}} | |
| attachment / photo / file | {{}} | |
| end-to-end | {{}} | encryption |
| forward secrecy | {{}} | |
| Diagnostics / field log | {{}} | the settings section; a log the user can share |
| Low-power mode | {{}} | Knit's own mode, distinct from Android's Battery Saver |
| Content filtering | {{}} | |
| Open to chat | {{}} | a profile flag: avoid a form that agrees with a gender |
| supporter / badge / perks | {{}} | gives money, never a donor or customer; perks: a thank-you, never a purchase |
| ticket | {{}} | Knit Anywhere access |
| recovery key / backup / restore | {{}} | |
| profile / identity | {{}} | identity = the user's Knit profile and keys |
| safety number | {{}} | |
| pause the mesh | {{}} | |
| tap | {{}} | never click |
| carry (others' messages) | {{}} | phones hold messages for others until they meet the recipient |
| seen / delivered | {{}} | receipts |
| Developer view | {{}} | |

### Consistency

Other translators are doing the other chunks with this same brief. Stick to the glossary, and where the English
repeats a phrase, repeat your wording. A string that quotes a setting, screen or button by name must use that
setting's own translation. When a string's meaning is unclear, the existing translations
({{values-pt-rBR, values-es, values-uk, values-fr}}) show by the same `name` how it was read (for meaning only:
translate from the English), and `grep -rn 'R.string.<name>' app/src/main/java` shows where it is used.

### Report

Reply with: the output path, the count of strings written against the chunk's count, and at most ten judgment calls a
native reviewer should look at, as `name → your choice → why`. No other prose.
