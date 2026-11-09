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

## Amendment (2026-11-09): mentions and the open-to-chat cue get sounds of their own

Mentions played the same sound as every other message, so a mention in a busy room sounded no different from the
room. The open-to-chat cue played the system default, so it sounded like another app. The maintainer picked from
four takes of each. Mentions play `res/raw/knit_mention.ogg`: the message's K followed by a high A6 pip, "over,
you", 1.5 s at -16 LUFS. It is still the K, so it still sounds like Knit. The open-to-chat cue plays
`res/raw/knit_open_to_chat.ogg`: a slow rising arpeggio (A5, C#6, E6), 1.4 s at -19 LUFS. It has no K rhythm,
because there is no message to read, and it is quieter because it is only a nudge. `scripts/gen-notification-sound.py`
renders both from fixed seeds, and the two earlier sounds come out byte-identical.

Both channels changed id, because a channel's sound is fixed once it exists: `knit_msg_mentions` became
`knit_msg_mentions_v2`, and `knit_open_to_chat` became `knit_open_to_chat_v2`. A new channel starts from its
defaults, so `NotificationChannels.ensure` reads each old channel before deleting it and builds the new one on
what the user set there. Importance carries when the user turned it down (silenced, or blocked): the new channel
is made at the lower of the two. The sound carries when it was None or one the user picked; only the stock
default or Knit's message sound gets the new sound. Vibration, lights and the badge carry as they were.
Lock-screen visibility and Do Not Disturb bypass can't carry: `PreferencesHelper.createNotificationChannel`
resets both on any channel an app creates (to the package's visibility, and to no bypass without DND access). A
user who had hidden mentions on the lock screen per channel sees them there again until they set it again.
The maintainer accepted that, with a CHANGELOG line telling users so, over two alternatives: keeping the old
channel unbumped for those users (they would miss the new sound), or storing the old setting and stamping it on
every notification (a stricter setting nothing in Android's Settings could later clear).
`NotificationChannelsTest` pins both sounds, each carried choice and the untouched defaults. The other message
channels keep their ids, so a phone that had Knit before 2.11.0 still plays the system default on them.
