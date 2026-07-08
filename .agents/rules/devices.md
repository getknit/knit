# Driving devices

**Never drive a non-emulator (physical) device without the user's explicit go-ahead for that specific
session** — installing, uninstalling, sending broadcasts, adb-driving the UI, or anything else that
touches a real phone. The emulator is fair game per the usual rules; physical hardware (including any lab
devices on network adb) is not a default target just because it's reachable. **Ask first each time** —
prior authorization doesn't carry over to a new task or a new conversation.

The *how* of driving a device once authorized (the headless debug bridge, resource-ids, cold-start
navigation) is in `context/debug-bridge.md`; emulator adb tips are in `context/testing.md`.
