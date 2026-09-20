---
id: "2026-09.a8ud"
slug: supervised-and-managed-phones-are-named
title: "Supervised and managed phones are named, and a blocked grant points at whoever holds it"
date: 2026-09-16
topics: [ui, onboarding, permissions, reliability]
---

# ADR 2026-09.a8ud — Supervised and managed phones are named, and a blocked grant points at whoever holds it

Status: Accepted (2026-09-16)

**What was observed.** A Family Link phone (the lab Pixel 8, Android 17, supervised child account) was
reported as a place Knit "closes daily and can't restart itself", and where the user "may not be able to
grant certain permissions". The first half was wrong; the second half was right, in a narrower way than it
sounded. Watched over adb through one full day: the per-app pause, the daily screen-time limit and the
scheduled downtime are all `setPackagesSuspended` from the supervision profile owner
(`com.google.android.gms.supervision/…kids.account.receiver.ProfileOwnerReceiver`) plus a
`LockscreenActivityV2` overlay — 40 packages at the daily limit, 48 including Knit under downtime. Through
every one of them Knit's process kept its pid, `MeshService` stayed foreground (procState 4), the bridge
reported `Healthy` with neighbours and the counters kept moving; a SIGKILL while suspended under downtime
came back on the sticky restart in 1 s. Family Link never force-stops, so the stopped-state /
`BOOT_COMPLETED`-only recovery that a kill-based design would have needed never happens. Every earlier
death in `dumpsys activity exit-info` was the lab's own `adb install`. The daily limit counts screen-on
time only.

The permission half: a parent's denial arrives as `setPermissionGrantState(DENIED)`, and the grant reads
`granted=false flags=[USER_SET|POLICY_FIXED|…]` — sideloaded debug APK included. From inside the app that
is *identical* to "don't ask again": an instant, dialog-less refusal with
`shouldShowRequestPermissionRationale == false`, and `POLICY_FIXED` has no public read. So the onboarding
row's "Open settings" sent the kid to a greyed-out toggle with a hint about Android not asking again. What
a parent can hold is also narrower than feared: the Family Link parent app exposes only the classic groups
(Location, Camera, Microphone, …) — never *Nearby devices* — so on API 33+ `requiredRadioPermissions`
cannot be parent-blocked at all, and a denial only reaches the location pin, the photo/QR camera and the
voice-note mic. On 29–32 Location *is* in the radio set, and there a parent's Location denial does brick the
front door. An EMM (device or profile owner) can deny any runtime permission on any tier.

**What changed.** No recovery machinery — the evidence says there is nothing to recover from. Instead the
app learns one fact and uses it in copy. `ui/DeviceSupervision.kt` folds the public policy probes into a
three-position enum: `FamilyLink` when `DevicePolicyManager.isProfileOwnerApp` names the supervision
package (the one thing the trial verified, pinned by package because Android has no public "is this
Family Link" API), else `Managed` when the user is a work profile, the device is organisation-owned with a
managed profile, or any user restriction is in force (a consumer phone reports none; the Pixel 8's eleven
were all Family Link's), else `None`. "In force" means the key's value is `true`: `getUserRestrictions()`
also returns keys held at `false` — the unmanaged Pixel 9 carried `no_record_audio=false` and read as
Managed on 2026-09-17 — and `dumpsys user` prints only the true ones, so the bundle looks empty from the
shell when it is not. It is read where `backgroundBattery` is — the onboarding probe, and
`rememberOnResume` on Settings and Diagnostics, which is that screen's private resume-observer hoisted so
three surfaces share it.

Where an un-askable grant lands on "Open settings", the hint now names who may hold the switch and where.
The rule is `supervisedHint` in `OnboardingPermissions.kt`, pure and pinned: Family Link is named on the
radio row only on the tiers that put Location in it and never on the notifications row, because naming a
parent where a parent cannot act is its own wrong copy; a managed phone is named on both rows on every
tier. The three feature gates share one `PermissionDeniedDialog` (the location pin's dialog lifted out of
`ChatScreen`, with the sentence appended on an administered phone); the voice-note `MicGate` gained the
same permanent-denial split `LocationGate` had, so a parent-held microphone gets that dialog instead of a
toast the kid cannot act on; `CameraGate`'s denial surface carries the sentence as an extra line, worded as
a conditional because that gate cannot tell a first refusal from a permanent one. Settings says the phone
is supervised under the battery row — with the one thing the trial earned, that the mesh keeps running
through pauses and downtime — and Diagnostics' self section carries the same fact for a bug report.
Because `POLICY_FIXED` is unreadable, every hint is conditional — "if Settings greys it out as Disabled by
admin", the platform's own wording on the Pixel 8's permission page — so it can only say who *could* have
turned the grant off, never that they did.

**The alternatives.** An `ACTION_MY_PACKAGE_UNSUSPENDED` receiver that restarts the mesh, with a
"tap to reconnect" notification as the unexempted fallback — designed before the trial, dropped by it: the
service never stops. It would also have been the exact-alarm-as-FGS-exemption pattern's neighbour, which
is a Play policy risk for a messenger. Reading the DPM's grant state directly — `getPermissionGrantState`
is the admin's to call, not ours. Running the mesh over whichever radio still has its grant on 31–32 when
a parent holds Location (BLE needs no location there) — ADR 2026-09.nzpr's objection stands, that the
transports are not permission-safe per plane, and the population is Android 12 kids' phones; parked on the
roadmap. Treating every active device admin as "managed" — Google's Find My Device is one on most phones,
so `getActiveAdmins` is not a management signal.

**What it costs and does not cover.** Three binder reads on the same resumes the battery probe already
uses; two more `ForbiddenImport` patterns (`android.app.admin.*`, `android.os.UserManager`) confined to the
one file and its test. Pause behaviour under an EMM is *not* verified, so the managed copy talks only about
permissions. The Family Link package is pinned, and Android's own Supervision role could replace it in a
future release — the trial was on Android 17 and the package still held the profile-owner slot there; if
that changes, `DeviceSupervisionTest`'s first case keeps passing while the phone reads `Managed`, which
degrades to the vaguer copy, not to silence. The onboarding hint still shows only in the `OpenSettings`
state, so a parent-held Location on a fresh 29–32 phone reads "Allow" once before it reads right; that is
the existing `asked` rule and not worth a persisted flag. Device-verified the same day on the Pixel 8 with
Location parent-denied: Settings and Diagnostics carry the Family Link line, and the chat pin's request went
straight from the consent sheet to the dialog with the parent sentence, whose Open settings lands on
Android's "Disabled by admin" page. The ATF suite passed 25/25 on a rooted API 34 AVD carrying a user
restriction, with the Managed lines rendered and no finding on them.

**Addendum, 2026-09-19 — the restriction sweep is gone.** The lab Pixel 3 (blueline, Android 12, nobody
managing it, no profile owner, `Device managed: false`) read as Managed: its user-0 bundle carried
`no_oem_unlock=true`, the system's own record that the bootloader is locked with OEM unlocking off
(`ro.boot.flash.locked=1`, `sys.oem_unlock_allowed=0`). That key is in the platform's `IMMUTABLE_BY_OWNERS`
set, so no device or profile owner could have put it there — the second time in three days the bundle
carried something that was not an admin's (the Pixel 9's `no_record_audio=false` was the first), and the
"a consumer phone reports none" premise above is wrong. What the bundle holds is whatever the *system*
records there: OEM-lock state, a background user's audio hold, a Guest user's defaults. Only a device owner
or a profile owner can add a restriction through the public API, so a bundle read can never find a manager
the owner probe misses; it can only misname a phone that has none.

`isManaged` now asks the platform's own question: is any active admin a device owner or a profile owner
(`getActiveAdmins` × `isDeviceOwnerApp` / `isProfileOwnerApp`, all public, any app may call them), plus the
two work-profile reads that were already there for the personal side of a COPE device. A plain device
admin — Find My Device — is still not a signal, now pinned by a test. What this does not see: a device
owner on the system user, read from a *secondary* user, where the owner's global restrictions apply but the
owner is not among that user's admins; a secondary user on a fully-managed device is rare enough to leave.
Family Link is unchanged — it is a profile owner and is read first.
