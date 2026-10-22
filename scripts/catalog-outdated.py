#!/usr/bin/env python3
"""List newer versions for every versioned entry in a Gradle version catalog.

usage: catalog-outdated.py gradle/libs.versions.toml [--all]   (--all also lists up-to-date refs)

Reads each artifact's maven-metadata.xml from Google Maven, Maven Central and the Gradle Plugin Portal; no Gradle,
no JDK, stdlib only. A ref is checked through the first library or plugin that uses it. A row marked * is behind:
a newer stable, or (for a pre-release pin) any newer version. It only reports: whether a bump is allowed is
.agents/context/toolchain.md's call (stable-only, minCompileSdk, the litert 1.4.x hold), and what changed is the
upgrade-notes skill's.
"""
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

import tomllib

REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
    "https://plugins.gradle.org/m2",
]
PRE = re.compile(r"(alpha|beta|rc|dev|eap|snapshot|m\d|-pre|preview)", re.IGNORECASE)
# Plugin ids whose marker is not on the portal under the same name.
PLUGIN_COORDS = {
    "com.android.application": ("com.android.tools.build", "gradle"),
}


def metadata(group, artifact):
    path = f"{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    misses = []
    for repo in REPOS:
        try:
            with urllib.request.urlopen(f"{repo}/{path}", timeout=15) as r:
                root = ET.fromstring(r.read())
            return [v.text for v in root.iter("version")]
        except (urllib.error.URLError, OSError, ET.ParseError) as e:
            misses.append(f"{repo}: {e}")
    print(f"{group}:{artifact} not found: " + "; ".join(misses), file=sys.stderr)
    return None


# Pre-release stages, lowest first. A final release outranks every stage of its own number (1.3.0 > 1.3.0-alpha11).
STAGES = [("snapshot", 0), ("dev", 0), ("eap", 1), ("alpha", 2), ("beta", 3), ("preview", 4), ("pre", 4), ("m", 4),
          ("rc", 5)]
FINAL = 9


def key(v):
    m = re.match(r"(\d+(?:\.\d+)*)(.*)", v)
    if not m:
        return (), (-1, 0)
    nums = [int(x) for x in m.group(1).split(".")]
    while len(nums) > 1 and nums[-1] == 0:  # 1.0 == 1.0.0
        nums.pop()
    rest = m.group(2).lower()
    if not PRE.search(rest):
        return tuple(nums), (FINAL, 0)
    stage = next((rank for name, rank in STAGES if name in rest), 0)
    n = re.findall(r"\d+", rest)
    return tuple(nums), (stage, int(n[-1]) if n else 0)


def newest(versions, stable):
    pool = [v for v in versions if not PRE.search(v)] if stable else versions
    return max(pool, key=key) if pool else None


def main():
    with open(sys.argv[1], "rb") as f:
        cat = tomllib.load(f)
    show_all = "--all" in sys.argv
    versions = cat["versions"]
    coords = {}  # version ref -> first (group, artifact) using it
    for lib in cat.get("libraries", {}).values():
        ref = lib.get("version", {}).get("ref") if isinstance(lib.get("version"), dict) else lib.get("version.ref")
        if ref and ref not in coords:
            g, a = (lib["module"].split(":") if "module" in lib else (lib["group"], lib["name"]))
            coords[ref] = (g, a)
    for plugin in cat.get("plugins", {}).values():
        ref = plugin.get("version", {}).get("ref") if isinstance(plugin.get("version"), dict) else plugin.get("version.ref")
        if ref and ref not in coords:
            coords[ref] = PLUGIN_COORDS.get(plugin["id"], (plugin["id"], plugin["id"] + ".gradle.plugin"))

    def check(ref):
        g, a = coords[ref]
        vs = metadata(g, a)
        return ref, g, a, vs

    with ThreadPoolExecutor(12) as ex:
        results = list(ex.map(check, sorted(coords)))
    unmapped = sorted(set(versions) - set(coords))
    print(f"{'ref':22} {'pinned':18} {'newest stable':18} {'newest any':22} artifact")
    for ref, g, a, vs in results:
        cur = versions[ref]
        if vs is None:
            print(f"{ref:22} {cur:18} {'?':18} {'?':22} {g}:{a} (not found)")
            continue
        st, an = newest(vs, True), newest(vs, False)
        behind = (st and key(st) > key(cur)) or (an and key(an) > key(cur) and PRE.search(cur))
        if behind or show_all:
            mark = "*" if behind else " "
            print(f"{ref:22} {cur:18} {st or '-':18} {an or '-':22} {g}:{a} {mark}")
    if unmapped:
        print("\nno library/plugin uses these refs (check by hand):", ", ".join(unmapped))


main()
