# Provenance: Unicode CLDR English emoji annotations

Vendored source for the search keywords in the generated emoji catalog shipped at
`app/src/main/assets/emoji/emoji_en.tsv` (the reaction picker's search: "100" finds 💯).

- **Upstream:** <https://github.com/unicode-org/cldr> (Unicode Common Locale Data Repository)
- **Release tag:** `release-48` (also recorded in [`RELEASE`](RELEASE), which the generator stamps into the asset)
- **Pinned files:**
  - `annotations-en.xml` ← `common/annotations/en.xml`,
    sha256 `8511aadd046fdba2f0ffe590266ced8bbf48175ad139b2675d85d7141057b235`
  - `annotationsDerived-en.xml` ← `common/annotationsDerived/en.xml`,
    sha256 `d76bd041c8c9e7b00b716aff8b7d9dbf509877010e191d5efd068be2553e066e`
- **Retrieved:** 2026-11-01
- **License:** Unicode License v3 (SPDX `Unicode-3.0`), © Unicode, Inc. — see [`LICENSE`](LICENSE) in this
  directory, copied verbatim from the release tag's `LICENSE`

The XML files and `LICENSE` are copied verbatim. They are **not** bundled in the APK — only the generated
`emoji_en.tsv` is packaged.

## Regenerate

```sh
python3 scripts/gen-emoji-catalog.py                            # rewrite the asset
python3 scripts/gen-emoji-catalog.py --update-cldr release-49   # re-vendor these files at a newer tag, regenerate
```

Move CLDR in step with a Unicode emoji bump (`--update`): the generator hard-fails on a base emoji CLDR has no
keywords for. Then update the tag, sha256 and retrieved lines above. The transform (base entries only,
lower-cased, de-duplicated, the name itself dropped) is documented in the script header.
