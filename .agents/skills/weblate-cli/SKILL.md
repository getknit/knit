---
name: weblate-cli
description: Drive a Weblate translation project from the command line with `wlc`, Weblate's official client. Read translation progress and failing quality checks, keep Weblate's copy in step with the repository (pull, commit, push, lock), download a translation to validate or diff it locally, upload machine or offline translations safely (as suggestions once people are reviewing), and read or edit single strings. Use whenever a task mentions Weblate or `wlc`, translation progress or statistics for a language, failing translation checks, a pending Weblate commit or pull request, importing or uploading translations, adding a language on Weblate, or locking a component around a source-string refactor.
---

# Weblate from the command line (`wlc`)

`wlc` talks to a Weblate server's REST API: one command per call, paths in the form
`PROJECT/COMPONENT/LANGUAGE`. It covers what a translation workflow needs day to day: progress, checks, the
repository sync, downloads, uploads and single strings. It does not change project or component settings
(license, review workflow, new-language policy, component-wide check flags); those live in the web UI or the
admin API, and are the maintainer's call.

## Before the first call

- **Installed?** `wlc version`. If missing: `pipx install wlc` (or `pip install wlc`).
- **Configuration.** The user's file (`~/.config/weblate` on Linux and macOS) holds the server and the API key:

  ```ini
  [weblate]
  url = https://hosted.weblate.org/api/
  translation = PROJECT/COMPONENT

  [keys]
  https://hosted.weblate.org/api/ = <API key from the Weblate profile's "API access" page>
  ```

  A project can commit a `.weblate` file at its root with `url` and `translation` only. `wlc` reads the nearest
  one, so commands run from the checkout need no path. **Never put a key in a project file**, never echo one, and
  never read the user's config file into a transcript. To see which servers have a key without showing it:
  `sed -n '/^\[keys\]/,/^\[/p' ~/.config/weblate | cut -d= -f1`. `--debug` redacts keys.
- **Use a key.** Anonymous requests to hosted Weblate are throttled to 100 a day per address, and a search
  (`list-units`) pages through results, so one query can spend many requests. A throttled call answers
  `Throttling on the server. Limit is 100 requests. Retry after N seconds.`; stop and ask for a key rather than
  retrying.
- **Language codes are Weblate's**, not the file's folder name: `pt_BR`, `zh_Hans`, `sr_Latn`, where an Android
  project has `values-pt-rBR/` and a gettext one `pt_BR.po`. `wlc ls PROJECT/COMPONENT` lists the codes.
- **Parse JSON, show text.** `wlc --format json …` for anything a script reads (list commands return a list);
  the default text output is for people.

## Read, write, destroy

| Kind | Commands | Rule |
| --- | --- | --- |
| Read | `version`, `ls`, `show`, `list-projects`, `list-components`, `list-translations`, `list-languages`, `list-units`, `stats`, `changes`, `repo`, `lock-status`, `download` | Run freely. |
| Write | `commit`, `push`, `pull`, `lock`, `unlock`, `upload`, `edit-unit` | Shared state other people see. Say what the call will do and confirm with the user before each one. |
| Destroy | `reset`, `cleanup`, `delete`, `upload --method replace`, `upload --conflicts replace-approved` | Can discard translators' unpushed or approved work. Only on an explicit instruction that names the command; `delete` has no prompt of its own. |

A `403 You do not have permission to perform this action` means the key's account lacks that role on the
project (`repo` and every write need one); it is not a broken command, and there is no workaround to try.

## Tasks

### Progress

```sh
wlc stats PROJECT/COMPONENT/LANGUAGE          # one language: translated, approved, needs editing, failing checks
wlc ls PROJECT/COMPONENT                      # every translation of a component
python3 -I <skill dir>/scripts/progress.py PROJECT/COMPONENT   # a Markdown table across languages
```

`progress.py` spends one request per language plus the listing. Read the numbers this way: **translated** is
filled in; **approved** is passed by a reviewer, which only exists when the project's review workflow is on;
**fuzzy** (needs editing) was flagged for another look; **suggestions** wait for someone to accept them;
**failing** counts strings with at least one failing quality check. Weblate counts a plural as one string, so
its total can differ from a count of the file's entries.

### Triage failing checks

```sh
wlc list-units PROJECT/COMPONENT/LANGUAGE --query 'has:check'        # every string with a failing check
wlc list-units PROJECT/COMPONENT/LANGUAGE --query 'check:same'       # one check
```

A unit's JSON has no check names, so group by querying the checks that usually fire: `same` (identical to the
source), `inconsistent` (one source translated two ways), `translated` (translated differently before),
`end_stop`, `end_colon`, `end_question`, `end_exclamation`, `end_ellipsis`, `end_space`, `begin_space`,
`double_space`, `escaped_newline`, `newline-count`, `plurals`, `same-plurals`, `placeholders`, `max-length`, the
format check for the file's language (`java_printf_format`, `python_format`, `c_format`, `icu_message_format`,
…), and `check_glossary`. Some names use hyphens, some underscores; Weblate's checks page has the full list.

For each unit decide:

- **A real problem:** fix it where the project's translations are edited (on Weblate, or in the repository file
  if that is the source of truth), then let the fix sync.
- **A false positive:** dismiss it on Weblate (web UI), or silence it at the source with a flag, `ignore-<check>`
  (for `same`, `ignore-same`), on the source string or for the whole component. Brand names, units and
  technical identifiers are the usual `same` false positives.

### Keep Weblate and the repository in step

```sh
wlc repo PROJECT/COMPONENT     # outgoing commits, missing commits, merge failure
wlc pull PROJECT/COMPONENT     # fetch the repository now (a push webhook normally does this)
wlc commit PROJECT/COMPONENT   # write pending translations as a commit in Weblate's copy
wlc push PROJECT/COMPONENT     # send it: a direct push, or a pull request for pull-request integrations
```

- Weblate commits pending changes by itself once they are older than the component's commit age (24 hours by
  default). `commit` then `push` gets them out now, before a release or before a source change that would
  conflict with them.
- With a GitHub or GitLab pull-request integration, `push` opens or updates a pull request from Weblate's fork;
  nothing lands on the branch until someone merges it.
- **Around a source refactor** (renamed keys, moved or split files): `lock`, then `commit` and `push`, get that
  pull request merged, land the refactor, `pull`, check `repo` shows no failure, `unlock`. The lock stops people
  translating strings that are about to move.
- **A merge failure** in `repo`: don't reach for `reset` or `cleanup`. Both discard Weblate's unpushed
  translations. Get the work out first (`commit` and `push`, or `download` each translation), then resolve the
  conflict in the repository and `pull`.

### Download to validate or compare

```sh
wlc download PROJECT/COMPONENT/LANGUAGE --output /tmp/LANGUAGE.file   # the file in the component's format
wlc download PROJECT/COMPONENT/LANGUAGE --convert po --output -       # converted, to stdout
wlc download PROJECT/COMPONENT --output /tmp/dir                      # every translation, one ZIP per component
```

Download Weblate's version of a translation to diff it against the repository's file, or to run the project's
own validators and linters on it before Weblate's pull request lands.

### Upload translations

| `--method` | Effect | Needs |
| --- | --- | --- |
| `translate` (default) | Uploaded text becomes the translation of existing strings. | Edit strings |
| `suggest` | Uploaded text is added as a suggestion beside each string. | Add suggestion |
| `fuzzy` | Uploaded text becomes the translation, flagged as needing editing. | Edit strings |
| `approve` | Uploaded text becomes an approved translation. | Review strings |
| `replace` | The whole file is replaced; strings people translated can be lost. | Component settings, or add, remove and edit strings |
| `add` | Adds new strings, skipping existing ones. | Add new string, string management on |
| `source` | Updates source strings in a bilingual file (some formats only). | Upload translations |

`--conflicts` decides what happens to strings that are already translated: `ignore` (the default: only empty
strings take the upload), `replace-translated` (overwrite, but keep approved ones; `--overwrite` is shorthand) and
`replace-approved` (overwrite everything, approved included).

- **Before anyone works on a language**, a bulk upload with `translate` (or committing the file to the
  repository and running `pull`) is fine.
- **Once people are translating or reviewing it**, machine or offline edits go up as `--method suggest` and
  nothing stronger. A suggestion sits beside the string for a person to accept, overwrites nothing, and cannot
  conflict with pending work. Name the source with `--author-name "Machine translation"` and an
  `--author-email` the project uses for bots, so reviewers know what they are accepting.
- Upload only the strings you changed. A file of a few entries is a valid upload for formats keyed by ID
  (Android XML, JSON, XLIFF, PO), and it keeps the review queue to what is actually new.
- `approve` is for text a qualified reviewer already approved, and needs that role.
- Never `replace` a language people are editing. The file must declare the language it is uploaded to.

### Read and edit single strings

```sh
wlc list-units PROJECT/COMPONENT/LANGUAGE --query 'context:some_key'   # find a string by its key
wlc show UNIT_ID                                                     # one string: source, target, state, flags
wlc edit-unit UNIT_ID --target "Text" --state 20                     # write one translation
```

`--state` is `0` empty, `10` needs editing, `20` translated, `30` approved. A plural takes one `--target` per
form, in the language's plural order (`wlc show` lists the forms). `--explanation` sets the explanation
translators see beside the string.

### Audit what changed

```sh
wlc changes PROJECT/COMPONENT/LANGUAGE
```

Each change carries its action (`action_name`), author and time. Use it to see what a pending Weblate pull
request will contain, who has been working on a language, or what arrived from the repository.

### A new language

`wlc` has no command to start a translation. Whether one appears depends on the component's policy for new
translations: people may add one on the web, request one from the maintainers, or neither. If the repository
requires every shipped language to be complete (a build that fails on a missing string), start the language in
the repository as a whole file and `pull`: Weblate finds it through the component's file mask. Adding it on
Weblate would produce a partial file in Weblate's pull request.

## Search query cheatsheet

Queries go to `list-units --query`. Wrap the whole query in single quotes for the shell.

| Query | Finds |
| --- | --- |
| `state:<translated` | empty and needs-editing strings |
| `state:>=translated` | translated and approved strings |
| `is:untranslated`, `is:fuzzy`, `is:approved` | by state |
| `has:suggestion`, `has:comment`, `has:check`, `has:dismissed-check` | strings carrying one |
| `check:end_stop` | one failing check |
| `context:some_key`, `key:some_key` | by the string's key |
| `source:"Exact words"`, `target:r"regex"` | by text; `:=` for an exact match |
| `changed:>="2 weeks ago"`, `changed_by:username` | by recent change |
| `label:name`, `component:name`, `language:pt_BR` | by label or scope |

`AND` is implied between terms; `OR`, `NOT` and parentheses work as expected. The state values are `empty`,
`needs-editing`, `needs-rewriting`, `needs-checking`, `translated`, `approved` and `read-only`.
