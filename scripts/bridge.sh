#!/usr/bin/env bash
#
# Fire one debug-bridge action at a device and print its JSON reply on stdout.
#
#   scripts/bridge.sh <serial|-> <ACTION> [am-extras…]
#
#   scripts/bridge.sh emulator-5554 STATE --es conv nearby | jq '.messages[0]'
#   scripts/bridge.sh - SEND --es text 'hi there 😀' --es conv nearby      # "-" = $ANDROID_SERIAL
#
# The receiver is debug-only (app/src/debug/.../debug/DebugBridgeReceiver.kt); its actions are listed in
# .agents/context/debug-bridge.md and the procedure is the `debug-bridge` skill. Obey
# .agents/rules/devices.md before pointing this at a physical phone.
#
# Three things a bare `adb shell am broadcast` gets wrong, which this does for you:
#
#   1. Quoting. adb re-parses the command on the device, so `--es text "hi there"` arrives as `hi`. Every
#      extra is single-quoted for the device shell here; pass values the way you would to any local command.
#   2. The stopped state. A package-targeted broadcast is not delivered to a package that is stopped (a fresh
#      install, `adb install -r`, a force-stop) until someone opens it, so the reply is silently empty.
#      `-f 0x20` is FLAG_INCLUDE_STOPPED_PACKAGES.
#   3. Reading the reply. It is the `data="…"` of `Broadcast completed`; when that is missing the receiver's
#      one-line mirror under `KnitBridge:I` is tried. That fallback is racy if two callers hit one device in
#      the same second (the tail may be the other's reply), so re-verify through STATE when it matters.
#
# Exit status: 0 with JSON on stdout; 1 with a hint on stderr when no JSON came back; 2 on usage.

set -euo pipefail

PKG=app.getknit.knit

if [[ $# -lt 2 ]]; then
    sed -n '3,9p' "$0" >&2
    exit 2
fi

serial=$1 action=$2
shift 2
if [[ $serial == - ]]; then
    serial=${ANDROID_SERIAL:?"serial '-' needs ANDROID_SERIAL set"}
fi
command -v jq >/dev/null || { echo "bridge.sh: jq is required" >&2; exit 2; }

# Single-quote each extra for the device shell (mksh): ' becomes '\''.
extras=""
for arg in "$@"; do
    extras+=" '${arg//\'/\'\\\'\'}'"
done

# The device's clock, so the log fallback below only reads lines this call could have produced — without
# it, an action the manifest never delivered would print the previous call's reply.
since=$(timeout 15 adb -s "$serial" shell "date '+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r') || since=""

out=$(timeout 30 adb -s "$serial" shell \
    "am broadcast -f 0x00000020 -a $PKG.debug.$action -p $PKG$extras" 2>&1) || {
    printf 'bridge.sh: adb failed for %s:\n%s\n' "$serial" "$out" >&2
    exit 1
}

json=$(printf '%s' "$out" | tr -d '\r' | sed -n 's/.*data="//;s/"$//p' | tail -1)
if [[ -n $json ]] && printf '%s' "$json" | jq -e . >/dev/null 2>&1; then
    printf '%s\n' "$json"
    exit 0
fi

if [[ -z $json && -n $since ]]; then
    fallback=$(timeout 15 adb -s "$serial" logcat -d -T "$since" -s KnitBridge:I 2>/dev/null \
        | tr -d '\r' | tail -1 | sed -n 's/^.*KnitBridge: //p') || fallback=""
    if [[ -n $fallback ]] && printf '%s' "$fallback" | jq -e . >/dev/null 2>&1; then
        echo "bridge.sh: no data= in the reply; read the KnitBridge log mirror instead" >&2
        printf '%s\n' "$fallback"
        exit 0
    fi
fi

{
    printf 'bridge.sh: no JSON reply for %s on %s. am said:\n%s\n' "$action" "$serial" "$out"
    if [[ $out == *"result=0"* && -z $json ]]; then
        echo "Likely causes: '$action' is missing from the <intent-filter> in app/src/debug/AndroidManifest.xml" \
            "(or misspelt), the installed build is not a debug build, or the app is not installed."
    fi
} >&2
exit 1
