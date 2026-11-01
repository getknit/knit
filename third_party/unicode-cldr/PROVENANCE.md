# Provenance: Unicode CLDR emoji annotations

Vendored source for the search keywords in the generated emoji catalog shipped at
`app/src/main/assets/emoji/emoji_en.tsv` (the reaction picker's search: "100" finds 💯), and for the localized
names and keywords in its per-language overlays `emoji_<tag>.tsv` (the `LOCALES` table of
`scripts/gen-emoji-catalog.py`).

- **Upstream:** <https://github.com/unicode-org/cldr> (Unicode Common Locale Data Repository)
- **Release tag:** `release-48` (also recorded in [`RELEASE`](RELEASE), which the generator stamps into the asset)
- **Pinned files:**
  - `annotations-en.xml` ← `common/annotations/en.xml`,
    sha256 `8511aadd046fdba2f0ffe590266ced8bbf48175ad139b2675d85d7141057b235`
  - `annotationsDerived-en.xml` ← `common/annotationsDerived/en.xml`,
    sha256 `d76bd041c8c9e7b00b716aff8b7d9dbf509877010e191d5efd068be2553e066e`
  - `annotations-<locale>.xml` and `annotationsDerived-<locale>.xml` for `de`, `es`, `es_419`, `fr`, `pt`, `uk`,
    from the same paths (sha256 of each is stamped into the overlay that reads it):

    | file | sha256 |
    | --- | --- |
    | `annotations-de.xml` | `12eb92612539223d4dcfaec3c45437a31a82022b1c59160873fa4d4439dd7d07` |
    | `annotationsDerived-de.xml` | `c7511229fdfa1471cecba195ff4a094d70507a320e5364e0edc8ca5c72eeae09` |
    | `annotations-es.xml` | `fe8384e9cd2ee0998eb00ddf5549272eb5b56739e539ef33fedcd727fce5026a` |
    | `annotationsDerived-es.xml` | `ea9b3abc0a2c73a815faec1034764b6a503d14638ad4fb9556d8a7341838672c` |
    | `annotations-es_419.xml` | `0f75a4e2a0c19604a140b10bbb27bf58ea679b32c9fc24bf7b8849f1ee548038` |
    | `annotationsDerived-es_419.xml` | `859ccd1f7080cc67ce5a86c5b53cbe5376102ea781c68ff4ec46ab184bb781b1` |
    | `annotations-fr.xml` | `d832108eabaad757700107dab95b3d9b0a198e46c34db3f255f80fe399cfbe94` |
    | `annotationsDerived-fr.xml` | `f76bab496c0e1a9ae8fc309a6a01a313089ec167289db59c308a33cfd225f78b` |
    | `annotations-pt.xml` | `784be701f969963dc1d8240cd8ae02eb5bad28153bc9d0613e5c5777537e5398` |
    | `annotationsDerived-pt.xml` | `8810bb38da561be161413f6292a6ce4a81d815ae9b6906a16a5b38d89f398496` |
    | `annotations-uk.xml` | `05ed3c0d065f33108745abbd5a49a6375a144acd3786c262683e9270093e5352` |
    | `annotationsDerived-uk.xml` | `2cf4358a657874830c19a60907f224a4d41309c6929bc2f97170e1a0ab237b8a` |
- **Retrieved:** 2026-11-01
- **License:** Unicode License v3 (SPDX `Unicode-3.0`), © Unicode, Inc. — see [`LICENSE`](LICENSE) in this
  directory, copied verbatim from the release tag's `LICENSE`

The XML files and `LICENSE` are copied verbatim. They are **not** bundled in the APK — only the generated
`emoji_*.tsv` files are packaged.

## Regenerate

```sh
python3 scripts/gen-emoji-catalog.py                            # rewrite the asset
python3 scripts/gen-emoji-catalog.py --update-cldr release-49   # re-vendor these files at a newer tag, regenerate
```

Move CLDR in step with a Unicode emoji bump (`--update`): the generator hard-fails on a base emoji CLDR has no
keywords for. Then update the tag, sha256 and retrieved lines above. The transform (base entries only,
lower-cased, de-duplicated, the name itself dropped) is documented in the script header.
