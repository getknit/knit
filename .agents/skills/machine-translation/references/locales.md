# Decisions per locale

What each shipped locale settled, beyond its column in `scripts/translation-glossary.tsv`: the brief's slots, the
calls made while reconciling, the sweeps its final read runs and what was left for a native speaker (the full list is
in that language's section of GitHub issue #43). Read the nearest sibling before writing a new brief; read the
locale's own section before adding strings to it (mode B). A new pass adds its section here, in the same shape.

## pt-BR: Brazilian Portuguese

- **Folder** `values-pt-rBR`, listing `fastlane/metadata/android/pt-BR/`, Play `pt-BR`. Machine pass 2026-10-22,
  second full review 2026-10-24.
- **Register:** você; sentence case.
- **Plurals:** one, many, other (many = other). `one` covers 0, so a count shown at 0 has a `_zero` string.
- **Words:** phone = celular; chat = conversa, chat list = lista de conversas; mesh = a rede mesh, short a mesh
  (feminine); Nearby = Por perto; relay = retransmissor; commons = sala comum; message request = solicitação de
  mensagem; board = placa; live call = ao vivo; mesh call = chamada pela mesh; voice note = mensagem de voz;
  forward secrecy = sigilo futuro; supporter = apoiador, badge = selo de apoiador; ticket = passe; Open to chat =
  Aberto a conversas. Kept in English: backup, walkie-talkie, Online / Offline.
- **Android's words:** Configurações, Dispositivos por perto, Localização, Economia de bateria, Não perturbe.
- **Final-read sweeps:** European Portuguese `\b(telemóvel|ecrã|utilizador|ficheiro|registar)\b`; tu-forms
  `\b(tu|teu|tua)\b`.
- **Open:** Family Link and battery labels, "Build" / "Commit" / "Flags" in About, "issue" for a GitHub issue.

## es: Spanish (neutral base)

- **Folder** `values-es`, listing `fastlane/metadata/android/es/`. Play has no plain `es`: `play.sh` pushes it as
  `es-419` and `es-ES`. Machine pass 2026-10-26.
- **Scope:** the base every Spanish speaker falls back to; a region (`values-es-rAR` for voseo) overrides single
  strings later.
- **Register:** tú and ustedes; never vos, usted or vosotros. ¿ ¡. Gender-neutral about the user ("Te damos la
  bienvenida", not "Bienvenido"); no @, x or e endings.
- **Plurals:** one, many, other (many = other).
- **Words:** neutral vocabulary: teléfono (not celular, móvil), archivo (not fichero), video, tocar (not pulsar),
  Configuración, en vivo, agregar, Mantén presionado. mesh = la red mesh, short la mesh (feminine); Nearby = Cerca;
  relay = retransmisor; commons = sala común; message request = solicitud de mensaje; board = placa; voice note =
  nota de voz; forward secrecy = secreto hacia adelante; supporter = colaborador; ticket = pase; Open to chat =
  Disponible para chatear (not "Abierto", which agrees with a gender); airtime = tiempo de aire. Kept in English:
  backup, walkie-talkie.
- **Final-read sweeps:** `\b(vosotros|vuestr\w*|usted|celular|móvil|ordenador|fichero|pulsa\w*)\b`.
- **Open:** whether anything reads as one country's, `colaborador` / `Desarrollador` as masculine one-word labels.

## uk: Ukrainian

- **Folder** `values-uk`, listing `fastlane/metadata/android/uk/`, Play `uk`. Machine pass 2026-10-26.
- **Register:** ви (lowercase), imperatives in -іть / -йте, as Android's own Ukrainian. Past tense about the user in
  the plural (gender-neutral); never a gendered past tense about another person: «%1$s: …», a noun or the present.
- **Typography:** ’ everywhere (never `\'`); «» quotes; units хв, год, КБ, МБ.
- **Plurals:** one (1, 21), few (2–4), many (0, 5–20), other (fractions: genitive singular where a fraction is
  plausible, else as many). `_zero` takes the many form.
- **Words:** genitive «телефона» file-wide; identity = «профіль Knit»; mesh = mesh-мережа, short mesh; Nearby =
  Поруч (quoted inside a sentence); relay = ретранслятор; commons = спільна кімната; message request = запит на
  листування; radio = радіо; board = плата; walkie-talkie = рація; forward secrecy = пряма секретність; ticket =
  перепустка; app = застосунок (додаток only inside a quoted Google label); "Relay all calls" = «Усі дзвінки через
  сервер», route pill «Через сервер»; Low-power mode = Режим енергозбереження (Android's Battery Saver is
  Енергозбереження).
- **Final-read sweeps:** Russian letters `[ыэъёЫЭЪЁ]`; ти-imperatives
  `\b(натисни|торкнись|увімкни|вимкни|спробуй|відкрий|зачекай|додай|скасуй|перевір|надішли|введи|дозволь|збережи)\b`;
  Russianisms `приймати участь|на протязі|слідуюч|співпада|відключ|(?<!’)являєт` (з’являється is Ukrainian).
- **Open:** Family Link, battery, unused-app and full-screen labels; the gender-neutral rewrites.

## fr: French (neutral base)

- **Folder** `values-fr`, listing `fastlane/metadata/android/fr/`. Play has no plain `fr` (`gpc` refuses it with
  LISTING_INVALID_LANGUAGE, checked 2026-10-28): it goes up as `fr-FR` and `fr-CA`. Machine pass 2026-10-27.
- **Scope:** the base for France, Belgium, Switzerland, Canada and Africa; no regionalisms.
- **Register:** vous. No inclusive-writing dots: rephrase.
- **Typography:** ’ everywhere; U+00A0 (the literal character) before `:` `;` `!` `?` `»`, after `«`, and between a
  number and its unit or `%%`; Ko / Mo / Go.
- **Plurals:** one, many, other (many = other). `one` covers 0 and 1, so it keeps `%d`.
- **Words:** mesh = le réseau mesh in sentences, le mesh in short labels (masculine); Nearby = À proximité; room =
  salon; commons = salon commun; relay = relais; message request = demande de message; board = carte; heard (radio)
  = capter; live call = appel en direct; voice note = message vocal; forward secrecy = confidentialité persistante;
  supporter = soutien; ticket = pass; backup = sauvegarde (translated, unlike pt-BR and es); "Relay all calls" =
  « Relayer tous les appels »; Not now = Plus tard; Retry = Réessayer; Android's Settings app = Paramètres (capital P).
  Battery options « Restreinte / Optimisée / Non restreinte » are unverified.
- **Final-read sweeps:** tu-forms `\b(tu|te|ton|ta|tes|toi)\b`; ASCII space before punctuation ` [:;!?»]`; a
  missing no-break space `\w[;!?»]` and `«\S`; off-glossary `\b(portable|cellulaire|cliquer|donateur)\b`.
- **Open:** "de %1$s" does not elide before a vowel ("de Ada"), about 24 strings; Family Link and battery labels.
