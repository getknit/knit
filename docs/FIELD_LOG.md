# The field log

A record of how a phone's radios behaved over the last three days. Every build can keep one, off until the user
turns it on at **Diagnostics → Field log**, and only the user can share it, from the same screen's **Share as a
file**. Nothing is ever uploaded. Decision record:
ADR 2026-10.a239. Code: `app/src/main/java/app/getknit/knit/fieldlog/`. Reader: `scripts/fieldlog.py`.

## What is never in it

Names, message text, contacts, node ids, group ids, blob hashes, addresses (Bluetooth, Wi-Fi, IP, a LoRa
board's), relay URLs and hosts, scope ids and labels, locations, and the calendar beyond the hour the log starts in.
Another phone appears only as a pseudonym, `p` followed by eight hex digits; a relay (a spool) as one of its own, `r`
followed by eight hex digits, made from its URL without the `?k=` token.

- **Recording side.** `FieldWriter` has no string field, so nothing typed can be written.
- **Export side.** `FieldLogDocument` withholds any line holding a node-id-shaped token and counts it in the
  header.
- **The developer view's console.** A debug build's event console (ADR 2026-10.s7wg) is the log's `FieldTap`: it is
  handed each event as recorded, ahead of the switch, and renders it through a `FieldWriter` of its own, a peer by
  the reader's label. It holds the newest events in memory only, while the view is on, and writes nothing. The journal
  keeps exactly what it kept without it.

## Retention

The journal lives in `noBackupFilesDir/fieldlog/`, so it is never in a backup.

- **Segments.** Two append-only segments, `current.jsonl` and `prev.jsonl`. `current` rolls to `prev` at
  2 MiB, so the log holds 4 MiB at most.
- **Age.** Nothing older than 72 h is exported.
- **When it is written.** Events reach disk on the mesh session's minute tick and at stop, so a process death
  loses at most a minute.
- **Pseudonym salt.** The `salt` file holds 32 random bytes. A pseudonym is HMAC-SHA256(salt, node id), cut to
  4 bytes; a relay's is HMAC-SHA256(salt, `relay` NUL + its redacted URL), so it can never equal a peer's. Clear
  deletes the salt.
- **Clear and the off switch** delete the journal, the salt and the copy last staged in `cacheDir/fieldlog/` for
  a share.

## File format

UTF-8 text in two parts:

1. **Header.** Lines starting with `# `: the schema version, app, device, SoC, Android, the start hour (UTC,
   rounded down), the span, and notes.
2. **Events.** One JSON object per line: `{"t":<ms>,"e":"<kind>",…}`.
   - `t` is milliseconds since the first line.
   - The export-time summaries (`latency`) carry the export's `t`.
   - A field whose value is unknown is left out, never written as null.

Schema 1. A new field is additive. Renaming or removing a field, or changing a field's meaning, bumps
`FieldLogDocument.SCHEMA`.

## Events

| `e` | When | Fields |
|---|---|---|
| `mesh_on` | Mesh session started | `vc` versionCode |
| `mesh_off` | Mesh session stopped (Stop, pause, restart) | — |
| `power` | Power state changed | `screen`, `charging`, `low` (battery low) |
| `battery` | How Android treats the app's background battery use, at the session's start and when it changes (read every 5 min) | `usage` Unrestricted/Optimized/Restricted (App battery usage; Restricted wins, ADR 2026-09.gc3m), `exempt` (the battery-optimization exemption, which can read true under Restricted), `bucket` EXEMPTED/ACTIVE/WORKING_SET/FREQUENT/RARE/RESTRICTED/NEVER/OTHER (the app standby bucket). An OEM's own battery manager is visible to none of them |
| `radio` | A radio's health, link count or flags changed; also every 15 min as a sample | `radio` Bluetooth/WifiAware/LoRa, `health` Healthy/ForegroundOnly/Degraded/Unavailable, `linked`, `nearby` (smoothed), `contended` (BT audio), `held` (NAN initiator hold) |
| `counters` | Every 15 min and at stop | Each `MeshMetrics` counter that moved, by its increase since the last `counters`. `…Peak…` values are high-water marks, written when they change. Reason maps are spread as `drop_<REASON>`, `btFail_<REASON>`, `bleSideDrop_<REASON>`, `fastDrop_<REASON>`, and a session loop's failed rounds as `roundFail_<ROUND>` (`REOFFER`, `SWEEP`, `METRICS`, and `NEIGHBORS` for a failed pass of the link-set watcher; the loop runs on). Names: `FieldCounters.flatten` |
| `bt_up` | A Bluetooth link came up | `peer`, `dialed`, `dial` (ms, connect → HELLO, ours only), `sighted`, `links` (live after), `relink` (ms since this peer's last `bt_down` in this log) |
| `bt_down` | A Bluetooth link ended | `peer`, `reason` EOF/STOP/EVICTED/ADAPTER_OFF/REPLACED/SILENT/OTHER, `life` ms, `links` (live after), `phy`, `rssi` (link RSSI; only with a PHY handle) |
| `bt_fail` | A Bluetooth dial failed | `peer`, `reason` (ConnectFailReason), `dur` ms, `streak` |
| `bt_phy` | A link stepped PHY (Coded experiment) | `peer`, `coded` (to Coded, else up to 1M/2M), `rssi` |
| `bt_scan` | Every 5 min | `links`, `le` (LE connections the phone holds), `sighted`, `hunting`, `coded` (Coded mode) |
| `bt_orphan` | A Bluetooth channel's LE connection outlived it past 15 s (#142), once per connection | `peer`, `linked` (the channel was a link; else a refused dialer, a failed HELLO or dial), `armed` (its arm attached: link-up, an iPhone's doorbell, or at the failed channel's end), `tries` (releases; 0 with the arm off, or when handed over at stop), `cleared` (it went down, else still up after the last try), `age` ms (outlived its channel by, to its disconnect or to this line) |
| `bt_link` | Every 5 min, one per link | `peer`, `age` ms, `tx` feeding a file, `rx` receiving one, `phy`, `rssi`, `interval` µs, `mtu`, `earned` (boosted budget) |
| `bt_peer` | Every 5 min, the four strongest sightings | `peer`, `linked`, `rssi` (smoothed, 1M scale), `rssi1m`, `rssiCoded`, `codedLag` ms |
| `nan_up` | A Wi-Fi Aware data path came up | `peer`, `initiator`, `links` |
| `nan_down` | A data path ended | `peer`, `initiator`, `life` ms, `clean` (quiet sync, not a reset) |
| `nan_cycle` | The Aware session was cycled | — |
| `nan_icm` | What Wi-Fi Aware's configs may ask of Instant Communication Mode changed (`NanIcmPolicy`, GitHub #44); on a change of `on`, `band` or `why` only, and never on a phone without ICM | `on` (an ICM-enabled config may be built), `band` B24/B5 (only when on; the band *asked*, not granted), `why` OVERRIDE/AUDIO/LONELY/STA_CHANNEL/NO_STA/DEFAULT, `sta` (the phone's Wi-Fi frequency in MHz, when known) |
| `nan_icm_lit` | Every 5 min, on a phone with ICM | `lit` ms, `up` ms, `configs` — our **estimate**, cumulative since the plane started: the union of the 30 s each ICM-enabled config of ours opens (sent on demand, by the keepalive or a band push), over the time since the plane started, after that many configs. The grant is the system log's `enableAndConfigure … instantModeChannel=` line, which another app's session or a refused update can make differ |
| `nan_dormant` | Wi-Fi Aware went dormant or woke (`NanDormancyPolicy`, GitHub #44): with nothing to do for 3 min it closes its session (NAN goes down with it) until a phone that can use it is near; one line per transition | `on` (dormant), `why` IDLE/DUTY_EMPTY (went or stayed dormant), SIGHTING/BULK/VISIBLE/DUTY/BLIND (woke), FORCED (the debug bridge) |
| `aware_switch` | The person's Settings › Wi-Fi Aware switch (`AwareSwitch`, GitHub #44), at each session's start and on change; while off the Aware lines stop | `on` |
| `low_power` | Low-power mode went on or off (ADR 2026-10.d44b); while on, Wi-Fi Aware is off and the Aware lines stop | `on`, `why` SWITCH/BATTERY_SAVER (only when on) |
| `lora` | Every 5 min while a board's link is ready | `role`, `snr10` (SNR × 10), `rssi`, `queued`, `heard`, `boards`, `airUsed` / `airBudget` ms (live share); `gateways` heard, `pocket` (short-range links), `sighted` (short-range sightings), `covered` (peers a spool covers); the board's `free` queue slots, `battery` %, `powered`, `chUtil10` / `airTx10` (its own channel use and transmit share, tenths of a percent) |
| `lora_up` | The board's link became ready | `dial` ms (the last attempt's start to ready; none when the log began with it ready), `tries` (failed attempts since the last up or down), `mtu`, the board's `region`, `preset` (only when it uses one), `slot` (on Knit's dedicated RF slot, ADR 067; a slot pinned by hand reads false), `hops`, `xeddsa` (its firmware signs for us), `relink` (ms since the last `lora_down` in this log) |
| `lora_down` | The board's ready link ended | `reason` GATT_DROP/DIAL_FAILED/DIAL_TIMEOUT/HANDSHAKE/IO/REBOOTED/PROVISIONED/NEEDS_PAIRING/STALE_BOND/ADAPTER_OFF/STOPPED/OTHER, `status` (GATT status, where it carried one), `life` ms |
| `lora_fail` | An attempt at the board's link ended before ready; the background wait for an absent board is not one | `reason`, `status` as in `lora_down`, `streak` (the session's backoff position), `wait` ms before the next |
| `lora_role` | The gateway role changed (ADR 044) | `role`, `pocket`, `gateways` |
| `net` | The Internet route at the session's start, then each change (ADR 2026-09.vej5's route) | `route` NONE/WIFI/CELLULAR/OTHER, `restricted` (Data Saver) |
| `spool_up` | A spool's hello completed | `relay`, `hello` ms (dial → hello), `fails` (dials before it that never reached one), `pow` bits, `attach`, `ent`, `turn`, `cast` (what the hello offered) |
| `spool_down` | A session that completed its hello ended | `relay`, `reason` (below), `code` (close code or HTTP status), `life` ms from the hello, `ours` (this phone ended it on a fault) |
| `spool_fail` | A dial ended without a hello | `relay`, `reason`, `code`, `dur` ms, `streak` (failed dials in a row), `retryAfter` ms (the spool's `Retry-After`) |
| `spool_err` | A connected spool answered an `err` code other than the last one logged this session | `relay`, `code` |
| `spool` | Every 5 min, one per configured relay | `relay`, `up`, `scopes`, `converged` (scopes whose digest agrees), `local` / `remote` (records each side holds), `invalid`, `fails`, `pow`, `ticket` NONE/PENDING/ACCEPTED/REFUSED (a spool that takes tickets), `uploads` OPEN/LOCKED/SPENT, `reason` (the status row's standing verdict) |
| `call_start` | A voice call began here: dialled, alerting, or a glare offer answered at once (docs/VOICE_CALLS.md §3). With no `call_end` after it, the process died in the call | `peer`, `out` (this phone dialled), `walkie` |
| `call_end` | A call ended here; one per call this phone saw, an offer refused on arrival or never rung included | `peer`, `out`, `walkie`, `reason` USER/BUSY/TIMEOUT/NO_ROUTE/MEDIA/ERROR, or OFF (a kind switched off, C-3.3-7), STALE (never rang, C-3.4-1/3), GLARE (our offer gave way, C-3.5-1); `last` the phase it ended from (none for an offer that never rang); `life` ms; `byPeer`, `missed`, `degraded` (dropped to a walkie); from its start: `ring` (caller: first RINGING or ANSWER), `answer`, `setup` (connected) ms; `talk` ms connected; `restarts` (ICE restarts, §3.8), `drops` (path lost while connected); `relay` (relay-only), `direct` (a Wi-Fi Direct group on offer), `directUp` (this side hosted or joined); `turn` servers and `stun` URLs gathered from (live calls); `unreached` (C-3.3-5), `unproven` (C-3.3-6) |
| `call_route` | A live call's media selected a candidate pair | `local`, `remote` HOST/SRFLX/PRFLX/RELAY/OTHER, `proto` (local transport), `net` WIFI/CELLULAR/ETHERNET/VPN/…, `direct` (the Wi-Fi Direct group), `seq` (1 for the session's first pair) |
| `call_media` | 5 s after a live call connects, then every 30 s while it is up | `lost` (reconnecting), `rtt`, `jitter`, `buffer` (jitter buffer) ms; `lossIn10` / `lossOut10` (since the last reading, each way) and `lossCall10` (whole call, received), in tenths of a percent; `send`, `recv` kbit/s; `pkts` heard since the last reading; `mic`, `heard` audio level in ‰; the selected pair as in `call_route` plus `relayProto` (the relay's leg to TURN); `pairs` answered, `pending` unanswered; `micPath` STANDARD/FALLBACK, `micHealth` WATCHING/LIVE/SILENCED/SWITCHED/DEAD (C-3.13-13) |
| `call_direct` | A call's Wi-Fi Direct leg ended (§3.10) | `host`, `end` CALL/BYE/CLOSED/SILENT/NO_LANE/RADIO/LANE_FAILED, from the leg's start `group` and `lane` (up) ms, `life` ms, `tries` (joins) |
| `talk` | A walkie burst (§3.11) went out or played to its end | `dir` OUT/IN; OUT: `chunks`, `ms` of speech, `sent` (chunks a link took), `path` (WIFI_AWARE/BLUETOOTH_CODED/BLUETOOTH/FADING/FLOODED/SPOOL/CAST_UNSEEN/NONE), `held` (Aware link held); IN: `chunks`, `lost` (skipped), `wait` (first arrival → playing), `spread` (first → last arrival) ms |
| `walkie_end` | A walkie session ended | `burstsOut`, `burstsIn`, `chunksOut`, `failed` (no link took it), `chunksIn`, `lost`; medians of the latest chunks: `transit` (two clocks, skew included), `jitter`, `hand` (cut → radio) ms; `frameOut`, `frameIn` average `talk` frame bytes |
| `latency` | At export, per plane | `dir` IN (others' messages reaching us; includes clock skew) or ACK (receipts for ours; one clock), `plane` (`Bluetooth` or `WifiAware` for a phone radio, `Nearby` for rows written before ADR 2026-10.e42d recorded which; `Internet`, `LoRa`), `n`, `p50`, `p90`, `max` ms |

A spool's `reason` and `code` are `SpoolVerdict`: NONE (the socket went with nothing said), the client's own verdicts
UNREACHABLE, NO_HELLO, UNRESPONSIVE, OVERLONG_LISTING, CLOSED (with the close `code`), HTTP (with the status),
TRANSPORT (a failure with a response), the `err` codes VERSION, POW, TOMBSTONED, QUOTA, TOO_LARGE, BAD_ID, RATE,
NOT_SUBSCRIBED, MALFORMED, INTERNAL, CONFLICT, TICKET, and OTHER for a code this build does not know.

The LoRa edges follow the plane's status as it is published, so a link that was ready for a few milliseconds can show
only as a `lora_fail` (HANDSHAKE or GATT_DROP) with no `lora_up` or `lora_down`. A missing `lora_up` is not proof the
link never came up. A mesh stop rarely writes the board's `lora_down`, because the session that follows the status ends
as the plane stops: a `mesh_off` closes an open LoRa link, and the next session's first `lora_up` carries no `relink`.

The call lines exist only in a build with calls (`-Pcalls=true`, ADR 2026-10.swvp). They carry no call id, SDP,
candidate address, TURN credential or caption: types, counts and times only.

Owed, once nsr7 lands: `feed_end` (sender: size, PHY, budget share, measured drain, first ETA, outcome),
`rx_end` (receiver: size, resume offset, time, progress reports), park waits and ETA promises.

## Reading two phones together

`python3 scripts/fieldlog.py pair LOG LOG [LOG …]` lines testers' logs up without anyone's notes. A log's `t` counts
from its own first line and its header gives only that hour, so two logs differ by an offset the reader works out.
For any two logs, it tries each pairing of one log's outgoing call with the other's incoming one as an anchor and keeps
the offset most of the other events agree with:

- **Calls.** The two sides begin within a signal's transit (20 s), connect within a round trip (3 s) and end within a
  transit (20 s), one to one.
- **Radio links.** `bt_up`, `bt_down`, `nan_up` and `nan_down` between the two phones fall within 3 s of each other.
  These line two logs up even with no call in common.
- **Confirmations.** These are matched calls whose sides also give the same end reason, and whose first selected pairs
  mirror each other. A pairing that rests on one call with neither, and no link, is marked weak: two unrelated calls
  of one length, placed at one moment, would look the same.

Each phone names the other by one pseudonym for the whole log. A match therefore also says which pseudonym is which
tester, and with several logs no pseudonym goes to two pairings: the better-evidenced pairing keeps it. The search
stays within an hour of the headers' difference, and widens when nothing fits there, for a phone whose clock is set
wrong. A pairing found that way is flagged.

For each pairing it prints the offset, both pseudonyms, and one merged timeline per shared call: both sides' call,
route, media, leg and walkie lines, and the links between the two phones. For a call the other phone never logged, it
says which of two things happened. Either that phone was recording then (a line within 6 min each side), so the offer
never arrived, or it was not recording. A call with a start line and no end line is shown as one where the app died.

## Adding an event

1. Add a `FieldEvent` that writes only through `FieldWriter`.
2. Add its golden line to `FieldEventSchemaTest`.
3. Add a row to the table above.
4. Teach `scripts/fieldlog.py` if a summary should read it.

A periodic read goes through `FieldRecorder.sampler` and must only read: it runs on the log's own tick.
