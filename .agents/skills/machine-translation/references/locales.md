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
- **Scope:** the base every Spanish speaker falls back to; a region overrides single strings. `values-es-rAR`
  (voseo, celular) exists since 2026-10-31, empty until Weblate's translators fill it: a fix here reaches it unless
  it carries that string.
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

## de: German (neutral base)

- **Folder** `values-de`, listing `fastlane/metadata/android/de/`. Play has no plain `de`: it goes up as `de-DE`
  (`gpc` dry run 2026-10-28). Machine pass 2026-10-28.
- **Scope:** the base for Germany, Austria and Switzerland; ß kept (a `de-CH` override can write ss), no
  regionalisms (no Handy, Jänner).
- **Register:** du, lowercase, as Android's own German and German messengers write it; imperatives "Tippe",
  "Öffne". "ihr" only where "you" means the user and one other person ("ihr beide"). No pronoun points at a
  placeholder name: the name again, "diese Person", "die andere Person"; the caller is "die anrufende Person". No
  :innen or *innen: groups take neutral forms (Unterstützende, Personen), compounds keep theirs (Nutzername).
- **Typography:** „ “ quotes; ’ where an apostrophe is needed (geht’s); an en dash with spaces where the English
  pauses with —; U+00A0 between a number and its unit or `%%`; Min. / Std. / Sek.; nouns capitalised; compounds
  hyphenated through (Wi-Fi-Aware-Verbindung, LoRa-Board).
- **Plurals:** one, other. `one` is 1 only, so it may say "eine Minute".
- **Words:** plain Wi-Fi = WLAN (Android's name), Wi-Fi Aware / Direct kept; phone = Smartphone; mesh = das
  Mesh-Netzwerk, short das Mesh (neuter); Nearby = In der Nähe, quoted in a sentence (der Raum „In der Nähe“); room =
  Raum; commons = Gemeinschaftsraum; relay = das Relay; "Relay all calls" = „Alle Anrufe über Server“, route pill
  „Über Server“; board = das Board; a LoRa radio device = Funkgerät, the link = Funk, the phone's own radio hardware
  = Funkmodul; heard (radio) = empfangen; conversation = Unterhaltung, chat = Chat; voice note = Sprachnachricht;
  recovery key = Wiederherstellungsschlüssel; Low-power mode = Stromsparmodus (Android's is Energiesparmodus);
  supporter label = „Unterstützt Knit“, badge = Unterstützer-Abzeichen; Backup, Ticket, Walkie-Talkie, Forward
  Secrecy kept as German tech writing does. "Direct file send" = „Datei direkt senden“.
- **Lint:** the `Typos` check flags "seit dem" as a misspelling of "seitdem", even where it is correct; reword
  ("ab dem", "nach dem") rather than ignore it.
- **Final-read sweeps:** formal forms `(?<![.:!?–] )\b(Sie|Ihr|Ihre|Ihren|Ihnen)\b`; ihr-forms
  `\b(ihr|euch|euer|eure|seid)\b` (each must mean the user and one other); plain Wi-Fi `Wi-Fi(?![ -](Aware|Direct))`;
  off-glossary `(?i)\b(klick\w*|Handy\w*|Telefon|Relais|Benutzer\w*|Spende\w*)\b`; gender marks
  `\w(:|\*|_)innen|[a-z]Innen\b`.
- **Open:** Family Link's German path („Steuerelemente › Angemeldete Geräte › App-Berechtigungen“, "downtime" as
  Ruhezeiten), „Vom Administrator deaktiviert“, the battery options and „Akkunutzung durch Apps“, „App-Aktivität
  pausieren, wenn die App nicht verwendet wird“, „Vollbildbenachrichtigungen zulassen“; „Relay“ vs. a German word
  for ordinary users.

## zh-CN: Simplified Chinese

- **Folder** `values-zh-rCN` (phone and watch), listing `fastlane/metadata/android/zh-CN/`, Play `zh-CN` (`gpc` dry
  run 2026-11-04). Emoji overlay `emoji_zh-CN.tsv` from CLDR `zh`, tagged zh-CN so a Traditional (zh-TW, zh-HK) phone
  keeps the English names. Machine pass 2026-11-04.
- **Scope:** mainland Putonghua in Simplified characters; no Taiwan or Hong Kong vocabulary (软件, 网络, 消息, 视频,
  默认).
- **Register:** 你, never 您; 点按, never 点击. No 他 / 她 for a placeholder person: the name, 对方 or no pronoun.
- **Typography:** full-width ，。：；！？（） and “ ” quotes; a half-width space between Han and a Latin word or a number
  (and before a measure word after `%d`), none beside a `%s` that holds a name, a language or a sentence; no `—`; `…`
  single. A Settings path is one quoted span: “设置 › 通话”.
- **Plurals:** `other` only, keeping the number, with the right measure word (条消息, 个联系人, 部手机, 台设备, 块开发板).
  `_zero` strings keep their `%d` too ("%d 名成员", "对话：%d 个").
- **Words:** plain Wi-Fi = WLAN (Android's name), Wi-Fi Aware / Direct kept; mesh = Mesh 网络, short Mesh; Nearby =
  附近, quoted in a sentence (“附近”聊天室); room = 聊天室; commons = 公共聊天室; relay = 中继 (the server 中继服务器);
  "Relay all calls" = “所有通话经由服务器”, route pill 经由服务器, a TURN allocation 转发, a mesh or radio node passing
  frames on 转发 / 中转; carry = 代传; direct link = 直连 (Wi-Fi Direct's own Android name is WLAN 直连); board = 开发板;
  LoRa radio = 电台, the phone's radios = 无线模块; the Meshtastic channel = 频道, the LoRa air channel = 信道; airtime
  = 发射时间; walkie-talkie = 对讲机 for the idea, 对讲 for the mode and its calls; voice note = 语音消息; transcript =
  转写文本; profile photo = 头像; identity = Knit 身份 (账号 only in "no account"); sign out = 退出登录; missed call =
  未接来电; Dismiss = 忽略, Close = 关闭; Not now = 以后再说; forward secrecy = 前向保密; supporter = 支持者, perks =
  支持者答谢; ticket = 通行证; Internet = 互联网; this phone = 这部手机; backup = 备份 (translated).
- **Android's words:** 设置, 附近的设备, 位置信息, 省电模式, 勿扰模式, 流量节省程序, 飞行模式; battery page
  “应用电池用量” with “不受限制 / 优化 / 受限”; unused-app switch “如果未使用，则暂停应用活动”; “允许全屏通知”;
  “已被管理员停用”; Family Link “控制 › 已登录的设备 › 应用权限”; Quick Share = 快速分享. All unverified.
- **Final-read sweeps:** Traditional characters (a set of 軟體網訊預設螢幕們這個還麼為說時區開關後臺電話號碼與從會來對發應點擊);
  `您`; `点击`; ASCII punctuation after Han `[\u4e00-\u9fff][,.:;!?()]`; Han and Latin touching
  `[\u4e00-\u9fff][A-Za-z0-9]|[A-Za-z0-9][\u4e00-\u9fff]` (expected beside a `%s` language name); `—`.
- **Open:** every Android, Family Link and Quick Share label above; 你 against 您; compact chat-list times with 前
  (“5 分钟前”); 群组照片 against 群头像; the watch's “9时59分” short duration.

## The watch app (all locales)

- **Files** `wear/src/main/res/values-<dir>/strings.xml`, machine pass 2026-10-28 for every shipped locale at once
  (zh-CN 2026-11-04, with its phone pass), one translator per language, the phone's wording reused for every shared term (`translations.md`, "The watch app").
- **Seven characters** forced short words: Linked = de „Im Mesh“, es «Enlace», fr « Relié », pt "Em rede", uk
  «Зв’язок»; Paused = „Pause“ / «Pausa» / « Pause » (de, es, fr); `aged_title_short` = just `^1` everywhere but zh-CN, where `^1前` fits (the library writes "12 Min." in German, "12min" in pt);
  `allow_short` is a noun where the verb is too long (Zugriff, Permiso, Accès, Дозвіл; pt "Permita").
- **Relayed** (passed on) is the phone's verb, never the relay server's noun: weitergegeben, reenviados, relayés,
  retransmitidas, передано. The glossary's relay row flags each as a warning; leave it.
- **Open:** every abbreviation above, de `short_relayed` „gesamt“, es `short_radios` / `short_radios_off` «Sin» /
  «radios», pt state words in the feminine (a mesh), uk `last_peer` «Хтось був поруч».
