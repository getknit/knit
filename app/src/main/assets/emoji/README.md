# Emoji catalog asset

Loaded locally at runtime by the reaction picker — the app has **no `INTERNET` permission**, so the data is
**bundled**, never downloaded.

## `emoji_en.tsv`

One fully-qualified RGI emoji per line, in Unicode's own order: `emoji`, `group id`, `skin-tone variant`
(`0|1`), `CLDR short name` (English) and, on base entries, the CLDR search keywords (`|`-separated; the column
is absent when there are none), tab-separated, under a `#` header. **Generated file — do not edit by
hand**; regenerate with `python3 scripts/gen-emoji-catalog.py`. Produced from Unicode's `emoji-test.txt`
(Emoji 17.0, see [Attribution](#attribution) below): `fully-qualified` entries only (the one canonical form
of each emoji, so identical reactions tally as one chip), the `Component` group skipped, and every entry
carrying a skin-tone modifier flagged so the browse grid can hide it under its base while search still finds
it. Search matches the name and the keywords, so "100" finds 💯; tone variants carry no keywords and are
found by name ("thumbs up medium"). The group id mirrors `app.getknit.knit.data.emoji.EmojiGroup` ordinal-for-ordinal.

The loader (`EmojiCatalogLoader`) parses it once per process off the main thread and drops every emoji the
device's fonts cannot draw (`Paint.hasGlyph`), so an older phone never offers a tofu box. If the file is
**absent or fails to load, the picker degrades to an empty grid** (the quick-reaction row still works).

The file is plain text and stays compressed in the APK (~52 KB); it is deliberately **not** on the
`noCompress` list, which exists only so TFLite can mmap the moderation models.

## Attribution

The catalog is a derived work of Unicode data, redistributed under the Unicode License v3. Retain this
notice when shipping.

### `emoji_en.tsv` — Unicode `emoji-test.txt` (Unicode License v3)

Source: <https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt> (Emoji 17.0, 2025-08-04). The shipped
catalog is a derived work produced by `scripts/gen-emoji-catalog.py`; the pinned source, its `LICENSE`, and
provenance are vendored under `third_party/unicode-emoji/` (not shipped in the APK).

### `emoji_en.tsv` keywords — Unicode CLDR annotations (Unicode License v3)

Source: <https://github.com/unicode-org/cldr> release `release-48`, `common/annotations/en.xml` and
`common/annotationsDerived/en.xml`. The keyword column is a derived work of these files, produced by the same
generator; the pinned files, their `LICENSE` and provenance are vendored under `third_party/unicode-cldr/`
(not shipped in the APK). CLDR is published under the same Unicode License v3 as below.

```
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
```
