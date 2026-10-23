---
id: "2026-10.xukf"
slug: message-notifications-play-knit-s-own-sound
title: "Message notifications play Knit's own sound, a Morse K in felt"
date: 2026-10-22
topics: [notifications, ui]
---

# ADR 2026-10.xukf — Message notifications play Knit's own sound, a Morse K in felt

Status: Accepted (2026-10-22)

**What was observed.** Every Knit channel played the phone's default notification sound, so a Knit message
sounded like any other app's. The brief was a sound that can become recognisable as Knit and is still soft
enough to hear all day. Four ideas were auditioned (Over, Stitch, Hop, Loop), then five takes each of Over and
Stitch. The maintainer picked Over in felt.

**What changed.** Every channel in the Messages group (Nearby, Groups, Direct messages, Meshtastic, the
commons, Mentions, Requests) plays `app/src/main/res/raw/knit.ogg`. The sound is Morse K, dah-dit-dah, radio
procedure for "over, go ahead": A5 held 165 ms, E5 held 55 ms, then C#6 left to ring, three felt-mallet notes
on a 55 ms unit, 1.23 s with the tail. The rhythm carries the identity, because rhythm is what survives a
phone speaker and a noisy room. The felt timbre and a 7 kHz ceiling keep it soft: its energy centres near 1.1
kHz and almost none lies above 5 kHz. It is mixed to -16 LUFS integrated, peak near -5 dBFS, on purpose
quieter than most notification sounds. The App group (the mesh status, alerts, the open-to-chat cue) keeps the
system default.

`NotificationChannels.messageSound` names the resource in the URI (`android.resource://<package>/raw/knit`),
never by its id. A channel keeps the URI it was created with for good, and a resource id can move between
builds. `scripts/gen-notification-sound.py` is the sound's source: it renders the take from a fixed seed,
measures BS.1770 loudness itself, and runs ffmpeg bit-exact, so one toolchain gives the same bytes every time.
The `.ogg` is committed because the release build must not depend on the build machine (F-Droid rebuilds and
byte-compares it), and the script is committed because it is the form the sound is edited in.

The channel ids stay as they are, by the maintainer's choice for now. A channel's sound is fixed once the
channel exists, so only a phone whose channels are created after this build plays the sound: a fresh install,
or data cleared. A phone that already had Knit keeps the sound it plays now. Bumping the ids (`_v2`, as the
importance raises did) would carry the sound to every phone, but it resets whatever each person set on those
channels. That is the next step if the sound should reach everyone.

**What it costs.** `res/raw/knit.ogg` must never be renamed or removed: every channel made since this ADR
holds its name, and a missing resource falls back to the default sound with no error. New bytes under the same
name change the sound on every one of those channels at once, with no channel bump. Mention and request sounds
of their own (the same rhythm at another pitch, say) would need new resources and new channel ids.
`NotificationChannelsTest` pins the group's sound, the name-form URI resolving to the Ogg, and the App group's
default.

## Amendment (2026-10-22): calls ring with Stitches

The maintainer picked a ring from four auditioned loops (Over and over, CQ, Double ring, Stitches): Stitches,
`app/src/main/res/raw/knit_ring.ogg`. It is a music-box line on the message sound's felt with a little more
octave in it, built on the K: the motif and a turn around it, then the same shape a step up, resolving home on
A5. The file is one 4.6 s loop with its pause inside, because the ring notification is `FLAG_INSISTENT` and
the system plays it until the call is answered. The generator folds whatever rings past the loop point back
onto its start, so the seam is silent. It is mixed to -13 LUFS over three loops, 3 dB above the message sound,
since a call has to reach the next room. The same script renders both sounds.

The ring channel moved from `knit_calls` to `knit_calls_v2`, and `CallNotifications.ensureChannels` deletes
the old one: a channel's sound is fixed once it exists, and calls are still debug-only, so no shipped phone
loses a setting. Both sounds reach their channels through `NotificationChannels.rawSound`, under the same
never-rename rule. `CallNotificationsTest` pins the ring's URI resolving to its Ogg and the old channel's
deletion.
