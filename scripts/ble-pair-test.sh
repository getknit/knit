#!/usr/bin/env bash
#
# Two emulators meshing over real Bluetooth, with each other and nobody else, and the blepair instrumented classes
# (BlePairMessagingTest, …Attachment…, …Profile…, …Group…, …Link…) run on both at once.
#
# Each emulator's host stack drives one of knit-ios's nRF52840 dongles through scripts/hci-bridge.py (the route
# scripts/emulator-hci-bridge.sh sets up), and both bridges run --isolate: Knit's advert UUIDs go out where only the
# other bridge looks, and every report but the partner's is dropped before the host stack sees it. So the frames
# the test waits on crossed real air between two real controllers, and the lab phones and the iPhone neither hear
# the pair nor are heard by it.
#
# Usage:
#   scripts/ble-pair-test.sh avd                # create the pair's AVD once (up does it when it is missing)
#   scripts/ble-pair-test.sh up [a|b]           # both bridges and emulators, one boot after the other (or one side)
#   scripts/ble-pair-test.sh install            # build the debug + androidTest APKs, install on the pair only
#   scripts/ble-pair-test.sh run [-e K V]…      # fresh identities, grants, then the class on both; logs + verdict
#   scripts/ble-pair-test.sh status             # both bridges and both meshes
#   scripts/ble-pair-test.sh down               # both emulators down, both dongles back to BlueZ
#   scripts/ble-pair-test.sh all [-e K V]…      # up, install, run, down (down whatever the verdict)
#
# `run` passes any `-e KEY VALUE` on to `am instrument` on both sides. KNIT_PAIR_CLASSES=<fqcn>[,<fqcn>…] (or
# `<fqcn>#<method>`) runs a subset, the same on both sides. Each run writes build/ble-pair/<stamp>/ — per side the instrumentation output, logcat, and links to
# that side's bridge log and btsnoop — and prints the test's own `BlePair` lines (link and relink times).
# Env: KNIT_PAIR_NO_BUILD=1 installs the APKs already built instead of building first;
#      KNIT_PAIR_KEEP=1 keeps both identities and their history for a `run` instead of starting clean;
#      KNIT_PAIR_BLIND=1 runs both adverts blinded (the debug default, cleared to it first) instead of switching the
#      blind switch off for the classic order and back to the default after;
#      KNIT_HCI_HEADLESS= (empty) shows the windows; KNIT_HCI_AVD picks the AVD both boot (Knit_BLE_Pair);
#      KNIT_HCI_NICE (10) the emulators' niceness, so a run yields the CPU to whatever else the host is doing.
#
# The AVD is lean on purpose — two of them run at once beside the build: an `aosp_atd` image (the automated-test
# device: no launcher, no SystemUI, nothing the instrumentation does not need), 2 cores and 1.5 GB each, booted
# one after the other. The window of an atd image stays black; drive it with adb, never by hand.
#
# The pair lives on an adb server of its own (port 5038, KNIT_PAIR_ADB_PORT), and the emulators register there
# (they read ANDROID_ADB_SERVER_PORT too). Two reasons. Android Studio's adb client floods the shared 5037 server
# while these emulators are on it (2026-10-05: 12k sockets from Studio within a minute, the server pegged, every
# adb call timing out). And the lab phones live on 5037 over network adb, so nothing here can reach one, and the
# install below can never land on a phone. Gradle only builds: the APKs go on with `adb install`. The emulators also
# take console ports past 5585, the top of the range an adb server scans for emulators when it starts, so a 5037
# server Studio restarts never finds them either (it did, the same evening, and the flood came straight back).

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
HB="$ROOT/scripts/emulator-hci-bridge.sh"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
ADB="$SDK/platform-tools/adb"
export ANDROID_ADB_SERVER_PORT=${KNIT_PAIR_ADB_PORT:-5038}
export ADB_LOCAL_TRANSPORT_MAX_PORT=5605  # so the pair's own server, if it restarts, does scan them
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
PKG=app.getknit.knit
export KNIT_HCI_AVD=${KNIT_HCI_AVD:-Knit_BLE_Pair}
export KNIT_HCI_NICE=${KNIT_HCI_NICE:-10}
export KNIT_HCI_BOOT_S=${KNIT_HCI_BOOT_S:-600}
IMAGE="system-images;android-34;aosp_atd;x86_64"
# The classes, in the order both sides run them (the same list on both is what keeps them in step); the link faults
# go last, so a failure there leaves nothing after it waiting on a link.
P=app.getknit.knit.blepair
CLASSES=${KNIT_PAIR_CLASSES:-$P.BlePairMessagingTest,$P.BlePairAttachmentTest,$P.BlePairProfileTest,$P.BlePairGroupTest,$P.BlePairLinkTest}
OUT="$ROOT/build/ble-pair"
# Side → console port and bridge control port (the control port is emulator-hci-bridge.sh's table; the console
# port is moved out of adb's scan range, above).
SIDES=(a b)
console_of() { case $1 in a) echo 5604 ;; b) echo 5602 ;; esac; }
serial_of() { echo "emulator-$(console_of "$1")"; }
control_of() { case $1 in a) echo 8868 ;; b) echo 8878 ;; esac; }
other() { case $1 in a) echo b ;; b) echo a ;; esac; }

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
on() { local s; s=$(serial_of "$1"); shift; timeout 300 "$ADB" -s "$s" "$@"; }

cmd_avd() {
  local config="$HOME/.android/avd/$KNIT_HCI_AVD.avd/config.ini" kv
  [ -f "$config" ] && return
  [ -d "$SDK/${IMAGE//;//}" ] || die "$IMAGE is not installed (sdkmanager '$IMAGE')"
  say "creating $KNIT_HCI_AVD from $IMAGE"
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" -s create avd -n "$KNIT_HCI_AVD" -k "$IMAGE" -d pixel_6
  for kv in hw.cpu.ncore=2 hw.ramSize=1536 vm.heapSize=256 disk.dataPartition.size=4G hw.gpu.enabled=no \
    hw.audioInput=no hw.audioOutput=no hw.camera.back=none hw.camera.front=none \
    fastboot.forceColdBoot=yes fastboot.forceFastBoot=no; do
    sed -i "/^${kv%%=*}=/d" "$config"
    echo "$kv" >>"$config"
  done
}

cmd_up() {
  local side
  cmd_avd
  "$ADB" start-server
  for side in "${@:-${SIDES[@]}}"; do
    KNIT_HCI_ISOLATE=1 KNIT_HCI_READONLY=1 KNIT_HCI_HEADLESS=${KNIT_HCI_HEADLESS-1} \
      KNIT_HCI_EMU_PORT=$(console_of "$side") "$HB" up "$side"
  done
}

cmd_install() {
  local side
  for side in "${SIDES[@]}"; do
    "$ADB" devices | grep -q "^$(serial_of "$side")[[:space:]]*device" || die "$(serial_of "$side") is not up (up first)"
  done
  if [ -z "${KNIT_PAIR_NO_BUILD:-}" ]; then
    say "building the debug and androidTest APKs"
    (cd "$ROOT" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest)
  fi
  for side in "${SIDES[@]}"; do
    say "installing on $(serial_of "$side")"
    on "$side" install -r -t "$APK" | tail -1
    on "$side" install -r -t "$TEST_APK" | tail -1
  done
}

# The node id the app on that side reports (its graph is built on the broadcast, so a fresh install mints it here).
node_id() {
  on "$1" shell "am broadcast -f 0x20 -p $PKG -a $PKG.debug.STATE" |
    sed -n 's/.*data="//;s/"$//p' | python3 -c 'import json, sys; print(json.load(sys.stdin)["self"]["nodeId"])'
}

# Debug builds blind their advert by default (ADR 2026-11.xp6d, step 6), and the tests' one-sided faults go by the
# classic dial order (BlePairTest.isSmaller: the smaller id never dials). So the switch goes off on both sides before a
# run and back to the default after it (the emulators air on real dongles, and a kept identity would keep an id in the
# clear), unless KNIT_PAIR_BLIND=1 (a blinded case, which must not use isSmaller), which clears it to the default (on)
# first. Each store is checked against the reply's `switch.stored`, not only its status.
SWITCHED=()
blind_switch() {
  local side=$1 extra=$2 want=$3 reply
  reply=$(on "$side" shell "am broadcast -f 0x20 -p $PKG -a $PKG.debug.ADVERT --ez $extra" | sed -n 's/.*data="//;s/"$//p')
  printf '%s' "$reply" | python3 -c '
import json, sys
d = json.load(sys.stdin)
want = {"false": False, "null": None}[sys.argv[1]]
sys.exit(d.get("status") != "ok" or (d.get("switch") or {}).get("stored", "absent") != want)' "$want" ||
    die "the blind switch did not take ($extra) on $(serial_of "$side"): ${reply:-no reply}"
}

# Puts every switch this run stored back to the default; a no-op once done, so the exit trap can call it again.
restore_switches() {
  local side
  for side in "${SWITCHED[@]}"; do blind_switch "$side" "clear true" null || true; done
  SWITCHED=()
}

prepare() {
  local side=$1 p
  if [ -z "${KNIT_PAIR_KEEP:-}" ]; then
    on "$side" shell pm clear "$PKG" >/dev/null
  fi
  for p in NEARBY_WIFI_DEVICES BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
    on "$side" shell pm grant "$PKG" "android.permission.$p"
  done
  # The test starts MeshService from an instrumented process, which is off screen: the exemption is what lets it.
  on "$side" shell cmd deviceidle whitelist "+$PKG" >/dev/null
  on "$side" shell svc power stayon true
  on "$side" shell input keyevent KEYCODE_WAKEUP
  on "$side" shell wm dismiss-keyguard
  # The test's cut goes through this side's bridge control; the guest reaches it on the same port number.
  on "$side" reverse "tcp:$(control_of "$side")" "tcp:$(control_of "$side")" >/dev/null
}

runner_of() {
  on "$1" shell pm list instrumentation | sed -n "s/^instrumentation:\([^ ]*\) (target=$PKG)\$/\1/p" | head -1
}

cmd_run() {
  local side stamp dir pids=() verdict=0
  stamp=$(date +%Y%m%d-%H%M%S)
  dir="$OUT/$stamp"
  mkdir -p "$dir"; ln -sfn "$dir" "$OUT/latest"

  declare -A ids
  for side in "${SIDES[@]}"; do
    prepare "$side"
    ids[$side]=$(node_id "$side")
    [ -n "${ids[$side]}" ] || die "no node id from $(serial_of "$side") (is Knit installed? install first)"
    if [ -n "${KNIT_PAIR_BLIND:-}" ]; then
      blind_switch "$side" "clear true" null
    else
      SWITCHED+=("$side")
      blind_switch "$side" "blind false" false
    fi
    [ -n "$(runner_of "$side")" ] || die "no instrumentation for $PKG on $(serial_of "$side") (install first)"
  done
  [ "${ids[a]}" != "${ids[b]}" ] || die "both emulators hold the same identity ${ids[a]} (drop KNIT_PAIR_KEEP)"
  say "run $stamp: a ${ids[a]} on $(serial_of a), b ${ids[b]} on $(serial_of b) (logs: $dir)"

  for side in "${SIDES[@]}"; do
    on "$side" logcat -c || true
    ln -sfn "$(readlink -f "$ROOT/build/hci-bridge/latest-$side")" "$dir/bridge-$side"
    timeout 1800 "$ADB" -s "$(serial_of "$side")" shell am instrument -w -e class "$CLASSES" \
      -e knitBlePeer "${ids[$(other "$side")]}" -e knitBleRun "$stamp" -e knitBleBridge "$(control_of "$side")" \
      "$@" "$(runner_of "$side")" >"$dir/$side.instrument.txt" 2>&1 &
    pids+=($!)
  done
  local pid; for pid in "${pids[@]}"; do wait "$pid" || true; done
  restore_switches

  for side in "${SIDES[@]}"; do
    on "$side" logcat -d >"$dir/$side.logcat.txt" 2>&1 || true
    say "side $side ($(serial_of "$side"), ${ids[$side]})"
    grep -hE ' I BlePair *:' "$dir/$side.logcat.txt" | sed 's/^/  /' || true
    if grep -q '^OK (' "$dir/$side.instrument.txt"; then
      grep '^OK (' "$dir/$side.instrument.txt" | sed 's/^/  /'
    else
      verdict=1
      # The runner prints each failure twice (as it happens, then in the summary); the summary is enough.
      sed -n '/^There w[ae][rs]e* [0-9]* failure/,$p' "$dir/$side.instrument.txt" | grep -v '^\s*at \|trimmed$' | sed 's/^/  /' | head -60
    fi
  done
  say "$([ $verdict = 0 ] && echo PASSED || echo FAILED) — $dir"
  return $verdict
}

cmd_down() {
  local side
  restore_switches
  for side in "${SIDES[@]}"; do KNIT_HCI_EMU_PORT=$(console_of "$side") "$HB" down "$side" || true; done
  "$ADB" kill-server 2>/dev/null || true
}

cmd_all() {
  trap cmd_down EXIT
  cmd_up
  cmd_install
  cmd_run "$@"
}

case "${1:-}" in
  avd)     cmd_avd ;;
  up)      shift; cmd_up "$@" ;;
  install) cmd_install ;;
  run)     shift; cmd_run "$@" ;;
  status)  for side in "${SIDES[@]}"; do KNIT_HCI_EMU_PORT=$(console_of "$side") "$HB" status "$side"; done ;;
  down)    cmd_down ;;
  all)     shift; cmd_all "$@" ;;
  *)       die "one of: avd up install run status down all" ;;
esac
