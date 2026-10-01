# Driving devices

**Never drive a non-emulator (physical) device without the user's explicit go-ahead for that specific
session** — installing, uninstalling, sending broadcasts, adb-driving the UI, or anything else that
touches a real phone. The emulator is fair game per the usual rules; physical hardware (including any lab
devices on network adb) is not a default target just because it's reachable. **Ask first each time** —
prior authorization doesn't carry over to a new task or a new conversation.

The *how* of driving a device once authorized is the `debug-bridge` skill (`skills/debug-bridge/SKILL.md`,
with `scripts/bridge.sh`); the per-action reference, resource-ids and cold-start navigation are in
`context/debug-bridge.md`; emulator adb tips are in `context/testing.md`.

Before reaching for a physical phone just to get another mesh node: an emulator can be given a **real BLE
radio** by passing a USB Bluetooth dongle through to it (`scripts/emulator-ble-mesh.sh`, and *Real BLE from
an emulator* in `context/testing.md`). It joins the actual mesh, and it is fair game under the rule above.
