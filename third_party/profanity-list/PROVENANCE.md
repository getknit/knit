# Provenance: dsojevic/profanity-list

Vendored source for the generated English profanity word list shipped at
`app/src/main/assets/moderation/profanity_en.txt`.

- **Upstream:** <https://github.com/dsojevic/profanity-list>
- **Pinned commit:** `c27924319aa9bd6f917e3782b4f4b6604a50b652`
- **File:** `en.json` (434 entries)
- **Retrieved:** 2026-07-18
- **License:** MIT © 2021 David Sojevic (see [`LICENSE`](LICENSE) in this directory)

`en.json` and `LICENSE` are copied verbatim from the pinned commit. They are **not** bundled in the APK —
only the generated `profanity_en.txt` under `app/src/main/assets/` is packaged, so the app's
no-`INTERNET`-permission design is unaffected.

## Regenerate

```sh
python3 scripts/gen-profanity-list.py
```

The generator reads `en.json` from this directory and rewrites
`app/src/main/assets/moderation/profanity_en.txt`. To update the corpus: bump `SOURCE_COMMIT` in the
script, re-download `en.json` + `LICENSE` at that commit into this directory, and regenerate. The exact
transform and curation dials (severity floor, excluded tags) are documented in the script header.
