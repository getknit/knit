# Provenance: emojilib

Vendored source for the second set of search keywords in the generated emoji catalog shipped at
`app/src/main/assets/emoji/emoji_en.tsv` (the reaction picker's search: "exam" finds 💯). They follow CLDR's
keywords (`third_party/unicode-cldr/`).

- **Upstream:** <https://github.com/muan/emojilib>
- **Pinned package:** npm `emojilib@4.0.3` (published 2026-04-18), <https://registry.npmjs.org/emojilib/-/emojilib-4.0.3.tgz>
- **Pinned files** (copied verbatim out of the tarball's `package/`):
  - `emoji-en-US.json` ← `dist/emoji-en-US.json`,
    sha256 `7974e9162e1505aa1b02d07a04e4eefa5c51864d6c32e477121c635201e2eca2`
  - `LICENSE`
- **`VERSION`:** the pinned npm version, which the generator stamps into the asset. `--update-emojilib` writes it
  after checking the tarball's own `package.json` says the same; that manifest is not vendored, because F-Droid's
  scanner refuses a `package.json` with no lockfile.
- **Retrieved:** 2026-11-01
- **License:** MIT, © 2014 Mu-An Chiou — see [`LICENSE`](LICENSE) in this directory

None of these files is bundled in the APK; only the generated `emoji_en.tsv` is packaged.

## Regenerate

```sh
python3 scripts/gen-emoji-catalog.py                          # rewrite the asset
python3 scripts/gen-emoji-catalog.py --update-emojilib 4.0.4  # re-vendor a newer npm release, regenerate
```

Then update the version, sha256 and retrieved lines above and the version in
`app/src/main/assets/emoji/README.md` and `THIRD-PARTY-NOTICES.md`. emojilib is community data that lags
Unicode: an emoji it lacks keeps CLDR's keywords alone, never an error. The merge (after CLDR, lower-cased,
`_` read as a space, duplicates and the name itself dropped) is documented in the script header.
