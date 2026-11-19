---
id: "2026-11.j8pf"
slug: an-onboarding-starting-point-supplies-the-defaults
title: "An onboarding starting point supplies the defaults, and the person's own values outrank it"
date: 2026-11-18
topics: [settings, onboarding, privacy, battery]
---

# ADR 2026-11.j8pf — An onboarding starting point supplies the defaults, and the person's own values outrank it

Status: Accepted (2026-11-18; `data/settings/Preset`, `PresetStore`, `ui/onboarding/PresetPage`,
`ui/preset/StartingPointScreen`). Draft until a device run of each preset is done.

**What was observed.** Each default had its own ADR and its own literal in its getter (`it[KEY] ?: true`), so a new
install got one mixed posture nobody chose as a whole. Relays (019, 064) and link previews (2026-09.n752) were off.
Read receipts (2026-11.z6jw), typing indicators (2026-10.97vs), translation and its language tag on the wire
(2026-10.t2h6), Wi-Fi Aware (2026-10.2t49) and call transcripts (2026-10.nz3g) were on, and Bluetooth downloads asked
above 1 MB (2026-10.js2p). Someone who wants a messenger that just works, and someone who wants it to say as little as
possible, both had to find several switches on several screens.

**What changed.**

1. **A `Preset` is stored, not applied.** Onboarding's second page asks for one of Everyday, Privacy-first or Battery
   saver (`Preset.offered`), with nothing preselected. The pick is stored under `settings_preset`. Each covered getter
   reads `Preferences.orPreset(PresetSetting.X)`: the person's own value if the key is present, otherwise the preset's.
   Setters are unchanged, so a toggle writes the key and outranks the preset from then on. An absent or unknown preset
   reads `STANDARD`, whose values are the literals the getters had before (pinned by
   `PresetStoreTest.standardIsTheDefaultsEveryInstallHadBeforePresets`). An install or a backup that predates presets
   therefore reads exactly what it did. `STANDARD` is never offered.
2. **The one-time write was rejected.** Writing explicit values once at onboarding would freeze a default against a
   later change to it, lose the phone-bound ones on a restore, and leave nothing for Settings to show or switch.
3. **Relays are not a folded default.** Switching relays on is a disclosed act (019): `spoolEnabled` and
   `activeSpoolUrls` keep `?: false`. A preset with `relays = true` writes `spool_consented` and `spool_enabled` in the
   same edit as the pick, the `acceptSpoolConsent` argument. It does so only on a reset (`PresetStore.apply(...,
   resetOverrides = true)`), and only where `RelayConsentText` is on screen: the selected Everyday card at onboarding,
   or Settings' switch dialog until the consent is held. That makes three doors with one disclosure (063). A reset to a
   relays-off preset clears the switch and keeps the consent. The relay list is never touched.
4. **The preset rides a backup; the phone-bound switches still do not.** `wifi_aware_` stays in
   `TRANSIENT_PREFIXES`, but a restored phone's Aware now falls back to the restored preset: off for Battery saver, on
   otherwise. This amends 2026-10.2t49's "a restored phone starts with Aware on": the airtime is this phone's, but
   having chosen a battery posture is the person's choice. `AwareSwitch.readOn` still puts the low-power move's answer
   ahead of the preset until the move is journaled.
5. **Onboarding writes it once, before the name.** The order is `WELCOME, PRESET, NAME, PERMISSIONS`. Leaving PRESET
   runs `apply(picked, resetOverrides = true)` under `NonCancellable`, as `persistName` does (2026-09.nzpr). That
   happens before `onboardingSeen` is written on leaving NAME, so a phone killed between the two pages is asked again.
   A phone that opens past the intro (it was seen, or it was restored) never reaches PRESET: Get started and Back walk
   past it (`OnboardingViewModel.returning`). The Start gate is unchanged: the radio grants alone.
6. **Settings › Starting point** shows the preset ("…, with your changes" when `PresetStore.customised`, which also
   counts relays that are not where the preset has them: a Privacy-first row never reads plainly while relays carry
   messages). Switching opens a dialog. Switch resets the covered keys to the target; where the person has their own
   values, Keep my changes writes only the pick and leaves relays where they are. The dialog lists what each button
   moves, from `PresetStore.changes`: the same edit run on a copy of the preferences, so it cannot claim what the
   switch does not do. Anything that shares more (relays, previews, receipts, typing, the language tag on, the
   screenshot block off) is listed first.
7. **Block screenshots** (`secureScreens`, on in Privacy-first) is `FLAG_SECURE` on `MainActivity` and `CallActivity`
   (`ui/SecureWindow`). It fails closed: set in `onCreate`, cleared only by the store's first `false`, so a cold
   start's first frames never reach a running recorder.

**The table** (`Preset`). Everyday: relays and link previews on, Bluetooth asks above 5 MB. Privacy-first: read
receipts, typing indicators and translation off. Battery saver: Wi-Fi Aware and call transcripts off. Everything else is
`STANDARD`. Direct links, LoRa, open to chat, the field log and watch status stay opt-in in every preset. Content
filtering stays on, since it only governs what this phone shows. One-way consents are never written by a preset, except
the relay consent above.

**What it costs.** A new covered switch needs four things:

- a `PresetSetting` entry, with its key in `SettingsKeys`;
- an `orPreset` getter;
- a column value in each preset;
- a card line in `presetLines` if a preset moves it.

A getter left on its literal silently ignores the preset, and nothing catches that but a test per setting
(`PresetStoreTest.eachGetterReadsThePresetUntilThePersonSetsItsSwitch`). The `stateIn` initial values in the ViewModels
still name the old literals and can flash one frame. A card line for a plane the build or phone lacks is left out
(`presetLines`), so a card never promises what will not happen. Device trial owed: a fresh install per preset on a pair.
It should show Privacy-first sending no read or typing cue and Battery saver's Aware plane never attaching.
