<!--
Engineering issue for Knit — the one shape every agent-filed issue takes (rules: .agents/rules/issues.md).

Title: `<area>/<Component>: <what goes wrong, as one plain sentence>`. The area is the source directory
the behaviour lives in (mesh/AckSync, ui/addcontact, crypto/ratchet, wear, mesh-lab); the sentence names the
behaviour, never the fix. e.g. "mesh/AckSync: a group tick sent over a live link that goes down with it is
dropped for good".

Labels: exactly one kind (bug, enhancement or task), the area (mesh, ui, testing, ci, …), then any of
reliability, performance, security, minor, needs verification.

Keep the headings below exactly as written and in this order. Delete a section marked optional rather than
leaving it empty; never rename one.
-->

## What happens

<!--
Bug: the behaviour, its mechanism traced to the code (`BleDoorbell.kt:199`, `ForwardSync.onDigest`), and
what it costs the user. Enhancement or task: what is missing today and what that costs. Facts only — the fix
goes under Options.
-->

## Evidence

<!--
Where and when it was seen, with an absolute date: lab devices by name and node-id prefix (`fd5onyk…`), a
lab test with its seed, logcat or snoop lines on the device's clock, an @Ignore'd repro. Say where the raw
material is kept. A finding from reading the code says so plainly and carries the `needs verification` label.
-->

## In the field

<!-- Optional. How likely a real user is to hit this, and how bad it is when they do. -->

## Options

<!--
Numbered, each with its cost and the ADR or invariant it touches (ADR 2026-09.u8qj, docs/WIRE_COMPAT.md).
End with one sentence naming the option you recommend and why. A task puts its checklist (- [ ]) here.
-->

1.

## Test

<!--
What proves the fix: a unit case, a mesh/lab/ scenario ending in assertConverged, or a device trial with its
recipe. Name any @Ignore'd repro the fix should un-ignore.
-->
