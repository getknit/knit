#!/usr/bin/env python3
"""Generate the Compose preview screenshot tests in `app/src/screenshotTest/`.

    python3 scripts/gen-screenshot-tests.py          # rewrite the wrapper files
    python3 scripts/gen-screenshot-tests.py --check  # fail if they are stale (a preview has no test)

Then re-render the references with `./gradlew :app:updateDebugScreenshotTest` and review the PNG diff.

**What it makes.** The Compose Preview Screenshot Testing plugin only renders `@PreviewTest` functions in the
`screenshotTest` source set, so every `@Preview` in `app/src/main` gets a one-line wrapper there that calls it:
`fun ChatScreenDm() = ChatScreenDmPreview()`. One file per UI package (`ui.chat` -> `ChatScreenshots.kt`), in
source order, the wrapper named for the preview without its `Preview` suffix. The wrapper's own annotations
decide how it renders: `@ScreenShots` (a fixed phone-sized canvas, light + dark) for a preview that draws a
whole screen, `@ComponentShots` (content-sized) for everything else, each light, dark, long text (en-XA) and
right to left (ar-XB) — both in
`ScreenshotPreviews.kt`, which this script does not touch. The sample data stays in main, in one place.

**The judgement calls live in the tables below**: which previews draw a screen (`SCREEN_CALLS`), which keep a
main-side size the light/dark pair would drop (`CUSTOM`), which also render at a large font scale (`EXTRA`),
and which a single-frame render cannot draw at all (`EXCLUDE`, each with its reason).

**A preview must be public and deterministic.** A `private` one stops the script (make it public — the
convention in `ui/preview/PreviewSupport.kt`). Time comes from `PREVIEW_NOW`, never the wall clock; the zone
and locale are pinned on the screenshot tasks in `app/build.gradle.kts`. A preview whose content arrives on a
later frame (a `LaunchedEffect`, `produceState`, `onSizeChanged`) renders as that first frame — start it
settled under `LocalInspectionMode` (see `EncryptionSection`'s QR) or accept the partial image.
"""

import collections
import os
import re
import sys
from pathlib import Path

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "app/src/main/java")
OUT = os.path.join(ROOT, "app/src/screenshotTest/kotlin/app/getknit/knit/screenshot")
HAND_WRITTEN = {"ScreenshotPreviews.kt"}

# A preview whose body calls one of these draws a whole screen; the onboarding pages fill one too, and the call
# screen's previews draw it through `PreviewCall`.
SCREEN_CALLS = re.compile(r"\b(\w+ScreenContent|DonateScreen|StorageUnavailableScreen|FullscreenImageViewer|PreviewCall)\(")

# File stem per UI package, where capitalising the package name would read wrong.
FILE_NAMES = {
    "ui": "App",
    "addcontact": "AddContact",
    "callsettings": "CallSettings",
    "chatlist": "ChatList",
    "components": "Component",
    "devview": "DevView",
    "signout": "SignOut",
    "yourmesh": "YourMesh",
}

# Previews whose own @Preview carries something the shared annotations would drop. Each keeps the long-text and
# right-to-left renders `@ComponentShots` and `@ScreenShots` give every other subject (`ScreenshotPreviews.kt`).
CUSTOM = {
    # The skeleton fills its height; main pins it at 420 dp.
    "ChatSkeletonPreview": [
        '@Preview(name = "Light", showBackground = true, heightDp = 420, locale = ENGLISH)',
        (
            '@Preview(name = "Dark", showBackground = true, heightDp = 420, locale = ENGLISH, '
            "uiMode = Configuration.UI_MODE_NIGHT_YES)"
        ),
        '@Preview(name = "LongText", showBackground = true, heightDp = 420, locale = LONG_TEXT)',
        '@Preview(name = "Rtl", showBackground = true, heightDp = 420, locale = RIGHT_TO_LEFT)',
    ],
    # The caller's context is the one call screen main also checks on a small phone, where it competes with the
    # answer buttons for height.
    "CallScreenCallerContextPreview": [
        '@Preview(name = "Light", showBackground = true, device = "spec:width=411dp,height=891dp", locale = ENGLISH)',
        '@Preview(',
        '    name = "Dark",',
        '    showBackground = true,',
        '    device = "spec:width=411dp,height=891dp",',
        '    locale = ENGLISH,',
        '    uiMode = Configuration.UI_MODE_NIGHT_YES,',
        ')',
        '@Preview(name = "LongText", showBackground = true, device = "spec:width=411dp,height=891dp", locale = LONG_TEXT)',
        '@Preview(name = "Rtl", showBackground = true, device = "spec:width=411dp,height=891dp", locale = RIGHT_TO_LEFT)',
        '@Preview(name = "Small", showBackground = true, device = "spec:width=360dp,height=640dp", locale = ENGLISH)',
    ],
}

# Previews also rendered at a large font scale: (wrapper suffix, KDoc, scale).
EXTRA = {
    "ConversationListItemDmPreview": (
        "LargeText",
        "The DM row at the largest system font scale, where the title, preview and time compete for one line.",
        2,
    ),
    "PermissionsPagePreview": ("LargeText", "The permissions page at 1.5x, the scale main's own preview checks.", 1.5),
}

# Previews a single-frame render cannot draw.
EXCLUDE = {
    # A Popup is its own window, which layoutlib's capture leaves out: the image is an empty box.
    "ReactionPickerPreview",
}

PREVIEW = re.compile(r"@Preview[^\n]*\n(?:@[^\n]*\n)*(private )?fun (\w+)\(\)")


def collect():
    """{package: [(preview function, draws a screen)]} in source order."""
    by_pkg = collections.OrderedDict()
    for dirpath, _, files in sorted(os.walk(SRC)):
        for f in sorted(files):
            if not f.endswith(".kt"):
                continue
            src = Path(dirpath, f).read_text(encoding="utf-8")
            if "@Preview" not in src:
                continue
            pkg = re.search(r"^package (\S+)", src, re.MULTILINE).group(1)
            for m in PREVIEW.finditer(src):
                name = m.group(2)
                if m.group(1):
                    sys.exit(f"{f}: {name} is private; make it public so a screenshot test can call it")
                if name in EXCLUDE:
                    continue
                nxt = src.find("@Preview", m.end())
                body = src[m.end() : m.end() + 400 if nxt < 0 else min(nxt, m.end() + 400)]
                screen = bool(SCREEN_CALLS.search(body)) or (pkg.endswith(".onboarding") and "Page" in name)
                by_pkg.setdefault(pkg, []).append((name, screen))
    wrappers = [n.removesuffix("Preview") for v in by_pkg.values() for n, _ in v]
    dupes = sorted(n for n, c in collections.Counter(wrappers).items() if c > 1)
    if dupes:
        sys.exit(f"two previews would share a wrapper name: {', '.join(dupes)}")
    return by_pkg


def render(pkg, previews):
    imports = {"androidx.compose.runtime.Composable", "com.android.tools.screenshot.PreviewTest"}
    if any(n in CUSTOM or n in EXTRA for n, _ in previews):
        imports |= {"android.content.res.Configuration", "androidx.compose.ui.tooling.preview.Preview"}
    imports |= {f"{pkg}.{n}" for n, _ in previews}
    out = ["package app.getknit.knit.screenshot", ""]
    out += [f"import {i}" for i in sorted(imports)]
    out += ["", f"// Screenshot tests over the previews in `{pkg.removeprefix('app.getknit.knit.')}`.", ""]
    for n, screen in previews:
        w = n.removesuffix("Preview")
        out.append("@PreviewTest")
        out += CUSTOM.get(n, ["@ScreenShots" if screen else "@ComponentShots"])
        out += ["@Composable", f"fun {w}() = {n}()", ""]
        if n in EXTRA:
            suffix, doc, scale = EXTRA[n]
            out += [
                f"/** {doc} */",
                "@PreviewTest",
                f'@Preview(name = "Light-{scale}x", showBackground = true, fontScale = {scale}f, locale = ENGLISH)',
                (
                    f'@Preview(name = "Dark-{scale}x", showBackground = true, fontScale = {scale}f, locale = ENGLISH, '
                    "uiMode = Configuration.UI_MODE_NIGHT_YES)"
                ),
                "@Composable",
                f"fun {w}{suffix}() = {n}()",
                "",
            ]
    return "\n".join(out).rstrip() + "\n"


def main():
    check = "--check" in sys.argv[1:]
    wanted = {}
    for pkg, previews in collect().items():
        seg = pkg.rsplit(".", 1)[1]
        wanted[f"{FILE_NAMES.get(seg, seg[:1].upper() + seg[1:])}Screenshots.kt"] = render(pkg, previews)
    present = {f for f in os.listdir(OUT) if f.endswith(".kt") and f not in HAND_WRITTEN}
    stale = sorted(
        f
        for f in wanted.keys() | present
        if f not in wanted or f not in present or Path(OUT, f).read_text(encoding="utf-8") != wanted[f]
    )
    if check:
        if stale:
            sys.exit(f"stale screenshot tests: {', '.join(stale)} — run python3 scripts/gen-screenshot-tests.py")
        print(f"✓ screenshot tests are up to date ({sum(t.count('@PreviewTest') for t in wanted.values())} subjects)")
        return
    for f in present - wanted.keys():
        os.remove(os.path.join(OUT, f))
    for f, text in wanted.items():
        Path(OUT, f).write_text(text, encoding="utf-8")
    print(f"✓ wrote {len(wanted)} files, {sum(t.count('@PreviewTest') for t in wanted.values())} subjects")


if __name__ == "__main__":
    main()
