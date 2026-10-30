package app.getknit.knit.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import app.getknit.knit.BuildConfig
import app.getknit.knit.badge.BadgeKeys
import app.getknit.knit.badge.Badges
import app.getknit.knit.badge.PeerBadges
import app.getknit.knit.call.CallAudioRoute
import app.getknit.knit.call.CallCaptioner
import app.getknit.knit.call.CallKind
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CaptureHealth
import app.getknit.knit.call.CaptureProfile
import app.getknit.knit.call.DirectCallPath
import app.getknit.knit.call.DirectMode
import app.getknit.knit.call.NoteStt
import app.getknit.knit.call.OverridingTurnSource
import app.getknit.knit.call.PassGate
import app.getknit.knit.call.SttModelState
import app.getknit.knit.call.SttPolicy
import app.getknit.knit.call.StunServers
import app.getknit.knit.call.SystemCallSync
import app.getknit.knit.call.TranscriptLine
import app.getknit.knit.call.TurnRanking
import app.getknit.knit.call.VoiceNoteSpeech
import app.getknit.knit.call.WalkieStats
import app.getknit.knit.call.WalkieTranscriber
import app.getknit.knit.crash.ProcessExitReasons
import app.getknit.knit.data.AttachmentIdentities
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.LocalMessageDelete
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.decodeBoundedFromBytes
import app.getknit.knit.data.downscale
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.hiddenByFilter
import app.getknit.knit.data.message.quoteHasAttachment
import app.getknit.knit.data.message.quoteSnippet
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.relay.FreeUploads
import app.getknit.knit.data.relay.photoFitBytes
import app.getknit.knit.data.settings.AwareSwitch
import app.getknit.knit.data.settings.BadgeStore
import app.getknit.knit.data.settings.KnitBoardSetup
import app.getknit.knit.data.settings.LowPowerSwitch
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.data.settings.OwnIceStore
import app.getknit.knit.data.settings.ReviewPromptStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TicketKeyStore
import app.getknit.knit.data.settings.TranscriptStore
import app.getknit.knit.data.webp.WebpTranscode
import app.getknit.knit.demo.DemoCalls
import app.getknit.knit.di.LIVE_CALLS_WIRED
import app.getknit.knit.di.WALKIE_WIRED
import app.getknit.knit.di.demoGoRequested
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPause
import app.getknit.knit.mesh.MeshStartGate
import app.getknit.knit.mesh.PublicPostOutcome
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.bluetooth.CodedPhyDiag
import app.getknit.knit.mesh.bluetooth.CodedPhyMode
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.bluetooth.LinkTuneDiag
import app.getknit.knit.mesh.bluetooth.LinkTuneRequest
import app.getknit.knit.mesh.bluetooth.PhyTuning
import app.getknit.knit.mesh.bluetooth.PromotionConfig
import app.getknit.knit.mesh.bluetooth.TunePhy
import app.getknit.knit.mesh.bluetooth.TunePriority
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.crypto.scope.SpoolPow
import app.getknit.knit.mesh.indirectPeers
import app.getknit.knit.mesh.link.FeedWindow
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.lora.BoardOwner
import app.getknit.knit.mesh.lora.BoardSettings
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.power.AppVisibility
import app.getknit.knit.mesh.power.BatterySaverState
import app.getknit.knit.mesh.power.LOW_POWER_OFFERED
import app.getknit.knit.mesh.power.LowPowerSource
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.mesh.spool.AccountStamp
import app.getknit.knit.mesh.spool.CommonsInvite
import app.getknit.knit.mesh.spool.CustomerKey
import app.getknit.knit.mesh.spool.FreeState
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.IssuerOrigin
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.MoveResult
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTicketState
import app.getknit.knit.mesh.spool.SpoolTickets
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.mesh.spool.TicketRefreshPolicy
import app.getknit.knit.mesh.spool.TurnHosts
import app.getknit.knit.mesh.wifiaware.NanFaultInjector
import app.getknit.knit.mesh.wifiaware.NanIcmPolicy
import app.getknit.knit.mesh.wifiaware.NanMsgFault
import app.getknit.knit.moderation.MlTextModerator
import app.getknit.knit.moderation.ModelLease
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.ModelLoadPolicy
import app.getknit.knit.moderation.NsfwImageModerator
import app.getknit.knit.moderation.modelGuardStamp
import app.getknit.knit.notifications.NotifMessage
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.review.ReviewPromptPolicy
import app.getknit.knit.review.ReviewPrompter
import app.getknit.knit.transfer.DirectHostGuard
import app.getknit.knit.transfer.OfferOutcome
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.ui.call.CallActivity
import app.getknit.knit.ui.call.DefaultPath
import app.getknit.knit.ui.call.DirectNetworkDetector
import app.getknit.knit.ui.call.MicFeed
import app.getknit.knit.ui.call.TalkPlayer
import app.getknit.knit.ui.call.TalkRecorder
import app.getknit.knit.ui.call.WalkieTalk
import app.getknit.knit.ui.call.WebRtcCallMedia
import app.getknit.knit.ui.call.stt.LineMode
import app.getknit.knit.ui.call.stt.MoonshineSpeech
import app.getknit.knit.ui.call.stt.SttModelStore
import app.getknit.knit.ui.call.stt.WalkieClipFiles
import app.getknit.knit.ui.chat.ChatWindow
import app.getknit.knit.ui.chat.quoteLabels
import app.getknit.knit.ui.invite.prepareKnitApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.webrtc.NetworkChangeDetector
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Debug-only ADB "bridge" that lets an automation agent drive the app headlessly — originating a chat
 * message straight through [MeshManager.sendChat] and reading message/peer/health state back as JSON —
 * so the send→verify loop needs no screenshots or pixel-hunting for the (unlabeled) send button.
 *
 * **This class and its manifest entry live in `src/debug/` only**, so the manifest merger includes them
 * exclusively in debug variants; the release APK contains neither. No runtime `BuildConfig` guard is
 * needed. The receiver is exported so the `shell` uid (adb) can reach it.
 *
 * Actions (fire with `am broadcast`, target the package with `-p app.getknit.knit`):
 * - [ACTION_SEND] — `--es text <body>` plus a target: `--es conv <conversationId>` (the `nearby` room, a
 *   peer node id for a DM, or a `g-…` group id) or `--es to <peerNodeId>` (a DM shorthand). No target ⇒
 *   the broadcast room. Add `--es replyTo <messageId>` to quote a message already in that thread (the
 *   ReplyRef is built exactly as the UI does; [ACTION_STATE] echoes the stored `replyTo*` fields back).
 * - [ACTION_SENDIMG] — sends a real **image attachment** with no UI (a locked device can't drive the photo
 *   picker): `--es path <file the app can read — e.g. run-as-copied into its filesDir>` plus the same
 *   target extras as [ACTION_SEND] (`conv`/`to`, default the broadcast room) and optional `--es text`.
 *   Runs the exact production pipeline (AttachmentStore.ingest → MeshManager.sendChat), so the image
 *   send→pull→verify loop is fully scriptable; replies with the attachment `hash` to poll for on receivers.
 *   `--ez file true` takes the file picker's door instead (a PDF); `reused` names the identity a pick reused.
 *   `--ez video true` takes the video door (`ingestVideo`): re-encoded, and screened for the room and blocked there
 *   when flagged, as the composer does — the room-send half of a video screening trial.
 * - [ACTION_SAVEATT] — the chat's Save into the app's own storage: `--es id <messageId> --es out <path>` writes
 *   the attachment's plaintext there, so [ACTION_SENDIMG] can pick it again (the forward loop, ADR 2026-10.mptm).
 * - [ACTION_STATE] — self id/name, transport health, the reachable peer set, and the mesh metrics; add
 *   `--es conv <id>` to also dump that thread's most recent messages (`--ei limit N`, default
 *   [DEFAULT_MESSAGE_LIMIT]) with each message's `received` delivery tick — how "verify receipt" works.
 * - [ACTION_STORE] — the store-and-forward carry set (the id set the cue-plane content digest is folded over),
 *   with `digestVersion`, all/live fingerprints, `expiredIds`, the full `allIds`, and capped per-row detail
 *   (`--ei limit N`, default [DEFAULT_STORE_LIMIT]) — to diff why two devices never converge their digests.
 * - [ACTION_REACT] — `--es id <messageId> --es emoji <emoji>` toggles a reaction.
 * - [ACTION_TYPING] — `--es conv <id>` (or `--es to <peerNodeId>`; default the broadcast room) fires one
 *   best-effort "now typing" cue; poll a receiver's [ACTION_STATE] `typing` map to confirm it landed.
 * - [ACTION_SHARE_APK] — runs the offline "Share Knit app" prepare step (merging split installs into one
 *   re-signed APK) headlessly and reports the staged `cacheDir/apk` files, so the result can be pulled +
 *   verified without the share sheet.
 * - [ACTION_WEBPCONV] — `--es path <gifFile>` runs the real send-side GIF → animated-WebP transcode and
 *   reports the byte reduction (`origBytes`/`outBytes`/`pctSmaller`); add `--es out <file>` to write the
 *   WebP out. Optional `--ei dim <px>` / `--ei fps <n>` / `--ei q <quality>` override the bounds.
 * - [ACTION_WEBPCHECK] — `--es path <file>` decodes an image through Android's `ImageDecoder` (Coil's
 *   engine) and reports `animated`/`width`/`height` — the in-app proof a muxed WebP actually plays.
 * - [ACTION_WEBPPROBE] — `--es path <gifFile>` estimates an animated-WebP re-encode's size (sums each
 *   frame's built-in `WEBP_LOSSY` bytes); `--ei dim`/`--ei fps`/`--ei q <quality>` tune it. Feasibility
 *   probe only — reports `webpAnimEstBytes`/`pctSmaller`, writes nothing.
 * - [ACTION_REVIEW] — dumps the rate-prompt gate state (installer, message counts, engagement watermark,
 *   attempts, `shouldPrompt`, and the installer-aware `rateUrl`/`feedbackUrl`) via the same [ReviewPrompter]
 *   reads the real prompt uses. `--ez reset true` clears the persisted review state; `--ez arm true`
 *   additionally backdates the engagement watermark past the age gate and forgets prior attempts, so the
 *   next chat-list visit prompts as soon as the (real) message-count gates hold.
 * - [ACTION_MODEL] — dumps the on-device model poison-pill (ADR 037): the current build stamp, and per
 *   model the stored stamp, `pendingSince` marker, unexplained-death count and whether it is latched —
 *   plus what the platform says about the **previous process exit**, which is what decides a 1-strike
 *   latch. `--ez reset true` clears every model's record. The fault itself is a build flag, not a bridge
 *   op (`-PmodelFaultOnLoad=segv|kill`), so nothing in `src/main` carries an arming seam.
 * - [ACTION_REQNOTIF] — posts the coalesced "message request received" heads-up: writes `--ei count N`
 *   (default 1) synthetic unaccepted inbound DMs from unknown peers and calls [Notifier.notifyMessageRequests],
 *   so the UIAutomator suite can drive the real system notification + Requests inbox. Needs POST_NOTIFICATIONS.
 * - [ACTION_MSGNOTIF] — posts one real incoming-message notification for `--es conv <id>` (a group or DM
 *   conversation id) from `--es from <peerNodeId>` (default the roster's first other member) with body
 *   `--es text <body>`, resolving the conversation the way [app.getknit.knit.mesh.InboundPipeline] would —
 *   title, photo, and for a photo-less group its members' faces — so the shade's avatar can be checked on the
 *   radio-less build (ADR 2026-09.zapp). Needs POST_NOTIFICATIONS.
 * - [ACTION_FLAGMSG] — injects one synthetic **inbound message the on-device text moderator flagged** (the UI
 *   collapses it behind a tap-to-reveal) as the newest row of `--es conv <id>` (default the broadcast room),
 *   from `--es from <peerNodeId>` (default a synthetic sender, named on upsert) with body `--es text <body>`:
 *   the seam the UIAutomator moderation-reveal test drives, since the radio-less build never receives a real
 *   flagged message and the marketing seed deliberately carries none (a hidden bubble would spoil a screenshot).
 * - [ACTION_SPOOL] — configures and inspects the Internet (spool) plane, which has no UI beyond a
 *   debug-only on/off switch: `--es url <ws(s)://…/spool/v1>` adds a spool, `--es drop <url>` removes one,
 *   `--ez on <bool>` flips the global opt-in, `--es url <url> --es ticket <knit-ticket:v1:…|clear>` holds or clears
 *   that relay's entitlement ticket (VOICE_CALLS.md §2.6), `--es url <url> --es issuer <origin> --es key
 *   <kak1_…|clear>` holds or forgets that relay's customer key without an invite (ADR 2026-10.3sk7), `--es url <url>
 *   --es freeIssuer <origin|clear>` sets that relay host's free issuer (docs/VOICE_CALLS.md C-7.6-33), `--es url <url>
 *   --es freeConsent <allow|decline|clear>` answers the free-ticket notice for it (C-7.6-34), `--ei mine <bits>` times
 *   one account stamp on this phone (C-7.6-36, no network), and no extras at all just dumps state. The per-scope
 *   `local` vs `spool` counts are the convergence oracle, the way `liveFingerprint` parity is for custody.
 * - [ACTION_RATCHET] — dumps the DM ratchet's per-peer state (X3DH inputs, session/confirmed, send epoch,
 *   the reset floor's anchor) and, with `--es reset <peerNodeId>`, forces a session reset past the
 *   heuristic. Every gate in the recovery path returns silently, so a peer we hold no prekey for, one
 *   inside its 6 h floor, and one whose heuristic has not counted three distinct failures all look the
 *   same from outside; the dump says which, and the force unwedges a pair that broke before a fix shipped.
 * - [ACTION_NANFAIL] — arms `--ei count N` Wi-Fi Aware attaches to take their failure path without ever
 *   reaching `mgr.attach` (0 disarms). The lab stand-in for a chipset that cannot produce a NAN interface;
 *   see `NanFaultInjector` for what it does and does not reproduce.
 * - [ACTION_NANSTORM] — replays getknit/Knit#9's availability storm: `--ei count N` (default 100) synthetic
 *   Aware availability notifications at `--ei hz H` (default 300). Repeats `true` by default, which must
 *   refund nothing; `--ez cycle true` alternates false/true instead — genuine radio recoveries, the negative
 *   control that must still refund and reattach. The reply's `failuresBefore`/`failuresAfter` is the
 *   measurement: how many attaches the bounds actually let through.
 * - [ACTION_NANREFUSE] — arms `--ei count N` Wi-Fi Aware responder requests to be declared unfulfillable ~10 ms
 *   after they are filed (work item #77's shape; 0 disarms, absent just dumps), delivering the first verdict to
 *   the live responder at once. The reply carries the re-file budget — `refusals` (the uncontended streak),
 *   `cycles` (session cycles this episode), `filed` and `armed` — and the pacing itself is read off logcat
 *   under `WifiAwareTransport`: `re-filing in Nms` per verdict, `cycling the session (c/3 this episode)` at the
 *   give-up. See ADR 2026-09.bgk3 and `NanFaultInjector`.
 * - [ACTION_NANICM] — `--ez on <bool>` turns Wi-Fi Aware Instant Communication Mode on or off for the running
 *   transport and cycles the session so the new publish/subscribe configs take (a lab knob for interop
 *   trials against a peer without ICM; the hardware's own answer bounds `on`). `--es band 2.4|5` pins the band the
 *   configs ask for, bypassing `NanIcmPolicy`'s audio and lonely rules, and `--es band auto` hands it back; a band
 *   never cycles the session (it rides the next config, or one in-place publish update while ICM is lit by demand),
 *   and with both extras `on` goes first. No extras reads. The reply's `icmAllowed` / `icmBandAsked` / `icmReason`
 *   are the verdict the next config carries — the band *asked*; `dumpsys wifiaware`'s `instantModeChannel` is the
 *   one granted — beside `icmBandOverride`, `staFrequencyMhz`, `band5PrimaryUsable`, `postCure` and `icmLitNow`.
 * - [ACTION_NANDIAL] — `--es to <peerNodeId>` initiates a Wi-Fi Aware data path to a discovered peer regardless
 *   of the id tie-break, so the smaller node can knock on the larger node's responder. The far side's HELLO
 *   check still closes the socket; the NDP forming (`onDataPathRequest` there, a 15 s timeout here) is the trial.
 * - [ACTION_NANINIT] — the Wi-Fi Aware **initiator failsafe** (work item #78, ADR 2026-09.m8kc). No extra dumps
 *   `strikes` / `latched` / `probeInMs` / `lastInitiateAgoMs` / `lossPendingMs`; `--ez blip true` injects one Wi-Fi
 *   drop-and-return through the transport's own handlers (a strike only if an initiate of ours went out in the
 *   last two minutes with no link since — pair it with [ACTION_NANDIAL]); `--ez reset true` is Diagnostics' "Try
 *   again"; `--ez probe true` makes a held role's daily probe due on the next tick.
 * - [ACTION_NANMSG] — the Wi-Fi Aware **coordination-plane watchdog** (work item #81, ADR 2026-09.jjhg). No extras
 *   dumps the ack bookkeeping: `unanswered` / `oldestUnansweredMs` / `sinceAckMs` / `failsSinceAck` / `episodeMs` /
 *   `cycles` / `fault`. `--es fault swallow` drops every send callback from here on (the blocked framework queue),
 *   `--es fault fail` turns every ack into a failure (dead unicast), `--es fault off` disarms; the watchdog's
 *   verdict and its session cycle then run for real, so the trial is: arm, watch `coordination plane stalled … —
 *   cycling the session` within ~90 s, disarm, watch `episodeMs` return to 0 on the next acked cue.
 * - [ACTION_BLECAP] — the **Bluetooth link limit** Diagnostics offers in debug builds (`SettingsStore.debugBleLinkCap`).
 *   `--ei max N` caps held L2CAP links at N (0 up to the shipped 6; 6 clears), `--ez clear true` clears; either way
 *   the reply is the stored `cap` (null = shipped budget) and the Bluetooth row's `linked` / `nearby`. The transport
 *   collects the key, so a lower cap sheds the weakest links once they are 20 s old and refuses new inbound dialers;
 *   the side channel still reaches unlinked peers. Persists across restarts until cleared.
 * - [ACTION_NANOFF] — the **Wi-Fi Aware switch** Diagnostics offers in debug builds (`SettingsStore.debugNanOff`).
 *   `--ez off true` stops the plane (links, responder and session go; the mesh runs on its other radios), `--ez off
 *   false` starts it again, no extras reads. The reply is the stored `off`, `userSwitchOff` (the person's Settings
 *   switch, [ACTION_AWARE], which keeps the plane off as well) and the Wi-Fi Aware row's `health` /
 *   `linked` / `nearby` (`present` false where the device has no Wi-Fi Aware plane). Applied live by the debug
 *   build's `SwitchableTransport`; persists across restarts until switched back on.
 * - [ACTION_AWARE] — the person's **Settings › Wi-Fi Aware switch** (`AwareSwitch`, GitHub #44). `--ez on
 *   true|false` sets it, no extras reads. The reply is the stored `on` and the Wi-Fi Aware row's `health` /
 *   `switchedOff` / `dormant`, which follow the plane (the `SwitchableTransport` floors apply).
 * - [ACTION_NANDORM] — Wi-Fi Aware **dormancy** (`NanDormancyPolicy`, GitHub #44). `--ez hold true` pins the plane
 *   awake (a dormant one wakes, why FORCED), `--ez hold false` lets the policy decide again; `--ez now true` closes the
 *   session for dormancy now; `--el duty <ms>` sets the duty interval for a trial (0 hands it back). No extras reads.
 *   The reply carries `dormant`, `why`, `dormantForMs`, `nextDutyInMs`, `dutyActive`, `probeActive`,
 *   `nextProbeAllowedInMs` (the on-screen probe's 5-min floor; null while awake), `idleForMs`, `stayReason`,
 *   `candidatesNear`, `candidatesHeard`, `seesNewcomers`, `held`, `dutyIntervalMs` and the radio evidence (`available`,
 *   `wifiEnabled`, `deviceIdle`, `radioOff`, `wontRun`). Hold, interval and dormancy all go with the transport's stop.
 * - [ACTION_LOWPOWER] — **low-power mode** (ADR 2026-10.d44b), the user's switch (`LowPowerSwitch`). `--ez
 *   on true|false` sets it, no extras reads. The reply is `offered` (`LOW_POWER_OFFERED`: false since GitHub #44, so the
 *   posture stays off whatever the switch says), `lowPower` (the stored `switch`, `batterySaver`, `charging`,
 *   the posture's `active` and `reason`) and the Wi-Fi Aware row's `health` / `switchedOff`. The posture also follows
 *   Battery Saver (`cmd power set-mode 1`) and lifts after a minute on the charger; `STATE` carries the same `lowPower`.
 * - [ACTION_PHY] — the **BLE Coded PHY experiment** (ADR 2026-10.yvn6; `BuildConfig.BLE_CODED_PHY`, else an error).
 *   `--es mode off|auto|coded|1m` stores the mode (`SettingsStore.debugBlePhyMode`, applied live), `--es txpower
 *   high|medium` re-raises the Coded advert, and `--ei stepDown|stepUp|stepDownReads|minGapMs|stepUpHoldMs N` and
 *   `--ei stepUpFast|stepUpFastHoldMs N` (−62, 10000) and `--ei flapWindowMs|stepUpBackoffMaxMs N` (300000, 300000:
 *   the step-up backoff of a link that flaps, #169) override the step thresholds, `--ei credit N` the dB a Coded
 *   advert reading gains on the 1M scale (12), `--ei fastAdvertMs|fastHoldMs N` the Coded advert's interval after a
 *   link at range drops (250) and how long it holds (180000) — on the dialing side, how long the scan hunts the lost
 *   peer — with `--ei huntInUseHoldMs N` that hunt's bound while Knit is open on a lit screen (1800000), `--ei oneMWeak N`
 *   the smoothed 1M reading under which a 1M hit leaves the Coded-only clock running (−90; −200 for the old rule) and
 *   `--ez coldHunt false` no hunt or fast advert for a cold far sighting (the 2026-10-20 amendment's A/B),
 *   `--ei codedPace|codedChunk N` a file's feed rate in B/s
 *   and chunk on a link on Coded (1024, 2048; a windowed feed's fallback there), `--ei codedWindowChunk N` a windowed
 *   feed's smallest chunk on Coded, which sizes the window's floors (1024; 1024 to 8192 — ADR 2026-10.5buf's Coded amendment), and `--ei filePace N` the file budget the links on 1M or 2M share, split
 *   among those feeding (4096; 0 unbounded; a share's chunk is two seconds of it, 2048 to 16384 — ADR 2026-10.8jwn),
 *   and `--ei fade|fadeExit|fadeExitHoldMs|fadeStaleReads N` when a link counts as fading (−95, −90, 10000, 3 — ADR
 *   2026-10.79yb's ninth amendment), until the process dies (`--ez resetTuning true` restores them). The reply: `mode`, `supported`, `advert`
 *   (off|starting|live|dark <status>), `txPower`, the tuning (with `fileChunk`, a lone 1M/2M feed's chunk), `links[]`
 *   (nodeId, phy, linkRssi, drives, attached, switches, gaveUp, fading) and `peers[]` (nodeId, rssi on the 1M scale,
 *   oneMSeenAgoMs, codedSeenAgoMs, each PHY's own rssi1m / rssiCoded, and codedLagMs — the 1M listening its Coded hits
 *   outlast its 1M ones by; over 8000 is Coded alone), then `hunting` (whether the last scan window hunted) and
 *   `hunted` (the peers lost at range it is hunting).
 * - [ACTION_LINKTUNE] — the **Bluetooth drain trial's lever** (#120; any debug build, no Coded flag needed). `--es node
 *   <id prefix>|all` names the links; `--es phy 1m|2m|1m2m`, `--es priority high|balanced|low` and `--ei mtu N` (23..517)
 *   are asked of each, in that order, through a GATT client on the link's own ACL made on first use, then `--ez read
 *   true` reads the PHY and link RSSI back; `--ez close true` lets the clients go. `--ei filePace N` sets the 1M/2M file
 *   budget (as `…debug.PHY`'s, until the process dies), `--ei boostPace N` the budget while every feeding link has earned
 *   its boost, `--ei crowdedPace N` that budget on a phone holding more than four LE connections, and `--ez resetPace
 *   true` restores all three (4096, 18432, 12288), the feed window's switch and its queue. `--es feed window|pace` switches
 *   the feed window (ADR 2026-10.nsr7's phase 2: a 1M/2M file to an Android peer clocked by its progress reports) on or
 *   off in this process, and `--ei windowQueueMs N` (100..5000) sets the queue it keeps ahead of the receiver; a build
 *   without `BLE_FEED_WINDOW` starts with it off. `--ez boost false` stops the link boost in this
 *   process — every boost lets go and asks nothing until `--ez boost true` — the A/B switch on one build (a build
 *   without `BLE_LINK_BOOST` never boosts). `--ez arm false` stops the ACL arm in this process (#142, ADR
 *   2026-10.b8yf): nothing more is armed to idle out when its channel closes (at link-up, at a refused or failed
 *   channel's end, at stop) and no orphan is released, while orphans are still logged (`bt acl orphan …`) and recorded;
 *   `--ez arm true` puts it back for channels that open after. An iPhone link is refused
 *   (its doorbell owns the parameters), and no PHY is asked while the Coded experiment runs here (a mask lasts the link's
 *   life, so it would block either side's stepper: set `…debug.PHY --es mode off` on both phones first). The reply:
 *   `filePace`, `fileChunk`, `boostPace`, `crowdedPace`, `leLinks` (this phone's LE connections), `boost`, `arm`, `feed`,
 *   `windowQueueMs`, `feeding`
 *   (links feeding a file now), `earned` (every one of them earned the boost) and `links[]` (nodeId, address, attached, txPhy, rxPhy, intervalUs / latency / timeoutMs —
 *   reported only on a change after the client attached — paramsStatus, mtu and mtuAsked — from Android 14 the first
 *   ask on an ACL settles on 517 whatever it names — rssi, codedOwned, refused, and the link's boost's boosting and
 *   earned, null before its first file). The `bt tune <id> …` and `bt boost <id> …` log lines carry the same, and the
 *   HCI snoop is the oracle.
 * - [ACTION_HEAL] — nudges the transport to rescan/re-advertise.
 * - [ACTION_PAUSE] / [ACTION_RESUME] — the notification's Pause and Resume, by their store write alone
 *   (`--ei minutes 15|60`, the two offered spans): `MeshService` follows `SettingsStore.meshPausedUntil`, so
 *   this drives the same path as the chat list's banner and also lands on a service that is not running
 *   (its next start comes up paused). [ACTION_STATE] reports `meshPausedUntil` / `meshPaused`.
 * - [ACTION_RESETDB] — empties the database for a fresh lab run, keeping the identity, the profile (the own
 *   avatar's blob put back) and the settings; refused unless paused, and the process dies after the reply.
 *
 * Each action replies as a one-line JSON object: it is returned via the ordered-broadcast result
 * (`am broadcast` prints `Broadcast completed: result=0, data="…"`) and also logged under the [TAG] tag
 * as a size-safe fallback (`adb logcat -d -s KnitBridge:I`).
 */
class DebugBridgeReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val mesh: MeshController by inject()
    private val attachments: AttachmentStore by inject()
    private val messages: MessageRepository by inject()
    private val reactions: ReactionRepository by inject()
    private val localDelete: LocalMessageDelete by inject()
    private val forward: ForwardStore by inject()
    private val peers: PeerRepository by inject()
    private val groups: GroupRepository by inject()
    private val blobs: BlobRepository by inject()
    private val verdicts: BlobVerdictDao by inject()
    private val identities: AttachmentIdentities by inject()
    private val metrics: MeshMetrics by inject()
    private val startGate: MeshStartGate by inject()
    private val identity: Identity by inject()
    private val settings: SettingsStore by inject()
    private val lowPower: LowPowerSource by inject()
    private val lowPowerSwitch: LowPowerSwitch by inject()
    private val awareSwitch: AwareSwitch by inject()
    private val batterySaver: BatterySaverState by inject()
    private val powerState: PowerStateSource by inject()
    private val ticketKeys: TicketKeyStore by inject()
    private val badgeStore: BadgeStore by inject()
    private val contactCards: app.getknit.knit.contacts.ContactCards by inject()
    private val contactImporter: app.getknit.knit.contacts.ContactImporter by inject()
    private val commons: app.getknit.knit.data.commons.CommonsRepository by inject()
    private val forwardDao: ForwardDao by inject()
    private val digest: StoreDigest by inject()
    private val reviewPrompter: ReviewPrompter by inject()
    private val reviewStore: ReviewPromptStore by inject()
    private val modelGuard: ModelLoadGuard by inject()
    private val modelJournal: ModelLoadJournal by inject()
    private val conversationFaces: app.getknit.knit.notifications.ConversationFaces by inject()
    private val textModel: MlTextModerator by inject()
    private val imageModel: NsfwImageModerator by inject()
    private val exits: ProcessExitReasons by inject()
    private val notifier: Notifier by inject()
    private val scope: CoroutineScope by inject()
    private val lora: app.getknit.knit.mesh.lora.LoraMeshTransport by inject()
    private val loraLink: app.getknit.knit.mesh.lora.MeshtasticLink by inject()
    private val transfers: TransferManager by inject()
    private val backupWriter: app.getknit.knit.data.backup.BackupWriter by inject()
    private val restoreStager: app.getknit.knit.data.backup.RestoreStager by inject()
    private val directWifi: app.getknit.knit.transfer.DirectWifi by inject()
    private val database: KnitDatabase by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        // The work is suspending (send + repo reads), so keep the broadcast alive past onReceive and
        // report the JSON result once it completes.
        val pending = goAsync()
        scope.launch {
            val result =
                runCatching {
                    when (action) {
                        ACTION_SEND -> {
                            handleSend(context, intent)
                        }

                        ACTION_SENDIMG -> {
                            handleSendImg(intent)
                        }

                        ACTION_SAVEATT -> {
                            handleSaveAtt(intent)
                        }

                        ACTION_STATE -> {
                            handleState(intent)
                        }

                        ACTION_STORE -> {
                            handleStore(intent)
                        }

                        ACTION_REACT -> {
                            handleReact(intent)
                        }

                        ACTION_TYPING -> {
                            handleTyping(intent)
                        }

                        ACTION_SHARE_APK -> {
                            handleShareApk(context)
                        }

                        ACTION_XFER -> {
                            handleXfer(intent)
                        }

                        ACTION_CALL -> {
                            handleCall(context, intent)
                        }

                        ACTION_STT -> {
                            handleStt(intent)
                        }

                        ACTION_TRANSLATE -> {
                            TranslateBridge.handle(context, intent)
                        }

                        ACTION_DEMOCALL -> {
                            handleDemoCall(context, intent)
                        }

                        ACTION_DEMOGO -> {
                            demoGoRequested()
                            reply("ok", "cued")
                        }

                        ACTION_WEBPPROBE -> {
                            handleWebpProbe(intent)
                        }

                        ACTION_WEBPCONV -> {
                            handleWebpConv(intent)
                        }

                        ACTION_WEBPCHECK -> {
                            handleWebpCheck(intent)
                        }

                        ACTION_REQNOTIF -> {
                            handleReqNotif(intent)
                        }

                        ACTION_MSGNOTIF -> {
                            handleMsgNotif(intent)
                        }

                        ACTION_FLAGMSG -> {
                            handleFlagMsg(intent)
                        }

                        ACTION_MKGROUP -> {
                            handleMkGroup(intent)
                        }

                        ACTION_LEAVE -> {
                            handleLeave(intent)
                        }

                        ACTION_PURGE -> {
                            handlePurge(intent)
                        }

                        ACTION_RESETDB -> {
                            handleResetDb(context)
                        }

                        ACTION_REVIEW -> {
                            handleReview(context, intent)
                        }

                        ACTION_MODEL -> {
                            handleModel(intent)
                        }

                        ACTION_SPOOL -> {
                            handleSpool(intent)
                        }

                        ACTION_INTRO -> {
                            handleIntro(intent)
                        }

                        ACTION_BACKUP -> {
                            handleBackup(context, intent)
                        }

                        ACTION_COMMONS -> {
                            handleCommons(intent)
                        }

                        ACTION_RATCHET -> {
                            handleRatchet(intent)
                        }

                        ACTION_LORA -> {
                            handleLora(intent)
                        }

                        ACTION_LORATX -> {
                            handleLoraTx(intent)
                        }

                        ACTION_LORAPROV -> {
                            handleLoraProv(intent)
                        }

                        ACTION_NANFAIL -> {
                            handleNanFail(intent)
                        }

                        ACTION_NANSTORM -> {
                            handleNanStorm(intent)
                        }

                        ACTION_NANREFUSE -> {
                            handleNanRefuse(intent)
                        }

                        ACTION_NANINIT -> {
                            handleNanInit(intent)
                        }

                        ACTION_NANDIAL -> {
                            val to = intent.getStringExtra("to")?.trim().orEmpty()
                            when {
                                to.isEmpty() -> {
                                    reply("error", "missing --es to <peerNodeId>")
                                }

                                else -> {
                                    when (val verdict = NanFaultInjector.dial(to)) {
                                        null -> reply("error", "Wi-Fi Aware transport is not running")
                                        else -> reply("ok", verdict).put("to", to)
                                    }
                                }
                            }
                        }

                        ACTION_NANMSG -> {
                            handleNanMsg(intent)
                        }

                        ACTION_NANDORM -> {
                            handleNanDorm(intent)
                        }

                        ACTION_AWARE -> {
                            handleAware(intent)
                        }

                        ACTION_BLECAP -> {
                            handleBleCap(intent)
                        }

                        ACTION_NANOFF -> {
                            handleNanOff(intent)
                        }

                        ACTION_LOWPOWER -> {
                            handleLowPower(intent)
                        }

                        ACTION_PHY -> {
                            handlePhy(intent)
                        }

                        ACTION_LINKTUNE -> {
                            handleLinkTune(intent)
                        }

                        ACTION_NANICM -> {
                            handleNanIcm(intent)
                        }

                        ACTION_HEAL -> {
                            mesh.heal()
                            reply("ok", "healed")
                        }

                        ACTION_PAUSE -> {
                            val minutes = intent.getIntExtra(EXTRA_MINUTES, MeshPause.SHORT_MINUTES)
                            val until = MeshPause.deadline(System.currentTimeMillis(), minutes)
                            if (until == null) {
                                reply("error", "minutes must be ${MeshPause.SHORT_MINUTES} or ${MeshPause.LONG_MINUTES}")
                            } else {
                                settings.setMeshPausedUntil(until)
                                reply("ok", "paused until $until")
                            }
                        }

                        ACTION_RESUME -> {
                            settings.setMeshPausedUntil(null)
                            reply("ok", "resumed")
                        }

                        else -> {
                            reply("error", "unknown action: $action")
                        }
                    }
                }.getOrElse { t ->
                    Log.e(TAG, "bridge action $action failed", t)
                    reply("error", t.message ?: t.javaClass.simpleName)
                }
            val json = result.toString()
            Log.i(TAG, json)
            pending.resultCode = 0
            pending.resultData = json
            pending.finish()
        }
    }

    /** `start,length,code` triples ([BodyStyle] codes) as [StyleSpan]s, or null when [raw] is not whole triples. */
    private fun parseStyleTriples(raw: String): List<StyleSpan>? {
        val numbers = raw.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (numbers.size % 3 != 0) return null
        return numbers.chunked(3).map { (start, length, code) ->
            val style = BodyStyle.entries.firstOrNull { it.code == code } ?: return null
            StyleSpan(start, start + length, style)
        }
    }

    private suspend fun handleSend(
        context: Context,
        intent: Intent,
    ): JSONObject {
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (text.isBlank()) return reply("error", "missing 'text' extra")
        // A DM shorthand (`to`) or an explicit conversation id (`conv`); default to the broadcast room.
        val conv = intent.getStringExtra(EXTRA_CONV) ?: intent.getStringExtra(EXTRA_TO) ?: Conversations.NEARBY
        // Optional quoted reply: --es replyTo <messageId> of a message already in this thread. Build the
        // ReplyRef exactly as ChatViewModel does (self-author resolved to our real name, snippet capped).
        val replyTo =
            intent.getStringExtra(EXTRA_REPLY_TO)?.let { replyId ->
                val row =
                    messages.observeMessage(replyId).first()?.takeIf { it.conversationId == conv }
                        ?: return reply("error", "reply target not in $conv: $replyId")
                val authorName =
                    if (row.senderId == identity.nodeId()) {
                        displayNameFor(settings.displayName.first(), row.senderId)
                    } else {
                        displayNameFor(peers.find(row.senderId)?.name, row.senderId)
                    }
                ReplyRef(
                    messageId = row.id,
                    authorId = row.senderId,
                    author = authorName,
                    snippet =
                        quoteSnippet(
                            row.body,
                            row.attachmentName,
                            row.attachmentMime,
                            row.attachmentAnimated,
                            flagged = row.hiddenByFilter(filterOn = true),
                            labels = quoteLabels(context),
                        ),
                    hasAttachment = quoteHasAttachment(row.attachmentHash, row.attachmentMime),
                )
            }
        // Optional formatting (ADR 2026-10.p95v): --es styles "start,length,code,…" triples over `text` as sent, codes
        // 0 bold, 1 italic, 2 strikethrough, 3 monospace. `sendChat` normalises them as it would the composer's.
        val styles =
            intent
                .getStringExtra(EXTRA_STYLES)
                ?.let { raw ->
                    parseStyleTriples(raw) ?: return reply("error", "bad 'styles' extra (start,length,code triples): $raw")
                }.orEmpty()
        // Route exactly as ChatViewModel.send does, resolving the thread kind from its id.
        val sent =
            when (Conversations.kindFor(conv)) {
                ConversationKind.NEARBY -> {
                    mesh.sendChat(text, recipientId = null, group = null, replyTo = replyTo, styles = styles)
                }

                ConversationKind.DM -> {
                    mesh.sendChat(text, recipientId = conv, replyTo = replyTo, styles = styles)
                }

                ConversationKind.GROUP -> {
                    groups.find(conv)?.let { mesh.sendChat(text, group = it.toGroupInfo(), replyTo = replyTo, styles = styles) }
                }

                // A commons post goes to the relay that runs the room, never a radio (§7.4).
                ConversationKind.COMMONS -> {
                    mesh.sendCommons(conv, text, emptyList(), replyTo, styles = styles)
                }

                // The Meshtastic room posts through this phone's own board, and the outcome says why not.
                ConversationKind.MESHTASTIC -> {
                    return when (val outcome = mesh.sendPublicPost(text)) {
                        PublicPostOutcome.Queued -> {
                            reply("ok", "queued on the board for $conv")
                        }

                        PublicPostOutcome.Blocked -> {
                            reply("blocked", "blocked by on-device content filter")
                        }

                        is PublicPostOutcome.Refused -> {
                            reply("refused", "the board refused it: ${outcome.reason}").put("reason", outcome.reason.name)
                        }
                    }.put("conversation", conv)
                }
            }
        return when (sent) {
            null -> reply("error", "unknown group (not joined on this device): $conv")
            true -> reply("ok", "sent to $conv")
            false -> reply("blocked", "blocked by on-device content filter")
        }.put("conversation", conv)
    }

    /**
     * Sends a real image attachment with no UI: ingest the file at `--es path` through the production
     * pipeline ([AttachmentStore.ingest] — downscale/re-encode/moderate) and hand the result to
     * [MeshManager.sendChat] (seal for a DM/group, flood + custody), routed by the same `conv`/`to`
     * extras as [handleSend]. The reply carries the attachment `hash`, so a script can poll the
     * receivers' blob state / logcat for exactly this transfer.
     */
    private suspend fun handleSendImg(intent: Intent): JSONObject {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return reply("error", "missing 'path' extra")
        val file = File(path)
        if (!file.exists()) return reply("error", "no such file: $path")
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val conv = intent.getStringExtra(EXTRA_CONV) ?: intent.getStringExtra(EXTRA_TO) ?: Conversations.NEARBY
        // `--ez file true` takes the file picker's door (ingestFile) instead of the photo picker's, so a PDF can be
        // sent. A file:// Uri offers no display name, so a non-image falls back to the file's own name, as a
        // document provider would give it.
        val asFile = intent.getBooleanExtra(EXTRA_FILE, false)
        // `--ez video true` takes the video door as the composer does: re-encoded, and for the room screened (poster and
        // frames, ADR 2026-10.hmfe) with a flagged one dropped and refused, as `ChatViewModel.stage` does.
        val asVideo = intent.getBooleanExtra(EXTRA_VIDEO, false)
        // `--ez voice true` sends the file's bytes as a recorded voice note (AAC-LC ADTS), through the composer's door.
        val asVoice = intent.getBooleanExtra(EXTRA_VOICE, false)
        val isRoom = Conversations.kindFor(conv) == ConversationKind.NEARBY
        // `--ez fit true` fits a photo to the relays' ticket `am` exactly as the composer does (ADR 2026-10.s3ep): the
        // same `FreeUploads.photoChunks`, the same `photoFitBytes`. Off by default, so every older script is unchanged.
        val fitBytes =
            if (intent.getBooleanExtra(EXTRA_FIT, false) && conv != Conversations.NEARBY) {
                // Null where Knit Anywhere is not wired (`-PknitAnywhere=false`): no free ticket fits anything there.
                getKoin()
                    .getOrNull<FreeUploads>()
                    ?.photoChunks
                    ?.first()
                    ?.let(::photoFitBytes)
            } else {
                null
            }
        val picked =
            when {
                asVoice -> attachments.ingestVoice(withContext(Dispatchers.IO) { file.readBytes() })
                asVideo -> attachments.ingestVideo(Uri.fromFile(file), screen = isRoom)
                asFile -> attachments.ingestFile(Uri.fromFile(file))
                else -> attachments.ingest(Uri.fromFile(file), fitBytes = fitBytes)
            }
        if (asVideo && isRoom && picked is AttachmentStore.IngestResult.Success && picked.flagged) {
            blobs.deleteIfUnreferenced(picked.ingested.hash)
            return reply("blocked", "video blocked by on-device content filter").put("conversation", conv).put("flagged", true)
        }
        val ingested =
            when (picked) {
                is AttachmentStore.IngestResult.Success -> {
                    picked.ingested.let {
                        if (asFile && it.name == null && !it.mime.startsWith("image/")) it.copy(name = file.name) else it
                    }
                }

                is AttachmentStore.IngestResult.Failed -> {
                    return reply("error", "ingest failed (${picked.reason}): $path")
                }
            }
        val sent =
            when (Conversations.kindFor(conv)) {
                ConversationKind.NEARBY -> {
                    mesh.sendChat(text, attachment = ingested, recipientId = null, group = null)
                }

                ConversationKind.DM -> {
                    mesh.sendChat(text, attachment = ingested, recipientId = conv)
                }

                ConversationKind.GROUP -> {
                    groups.find(conv)?.let { mesh.sendChat(text, attachment = ingested, group = it.toGroupInfo()) }
                }

                // The radio channel carries text only.
                ConversationKind.MESHTASTIC -> {
                    return reply("error", "the Meshtastic room carries text only: $conv")
                }

                // Keyed and relay-only (ADR 2026-10.xhfr); false when the room is not joined or the seal failed.
                ConversationKind.COMMONS -> {
                    mesh.sendCommons(conv, text, emptyList(), replyTo = null, attachment = ingested)
                }
            }
        return when (sent) {
            null -> {
                reply("error", "unknown group (not joined on this device): $conv")
            }

            true -> {
                reply(
                    "ok",
                    "sent ${if (asVoice) {
                        "voice note"
                    } else if (asVideo) {
                        "video"
                    } else {
                        "image"
                    }} to $conv",
                )
            }

            false -> {
                reply("blocked", "blocked by on-device content filter")
            }
        }.put("conversation", conv)
            .put("hash", ingested.hash)
            .put("mime", ingested.mime)
            // The plaintext as staged, and the size it was fitted to (null: not asked, or nothing to fit to).
            .put("plainBytes", ingested.sizeBytes)
            .put("fitBytes", fitBytes ?: JSONObject.NULL)
            // The identity this pick reused (ADR 2026-10.mptm): the hash the frame names, null for a fresh seal.
            .put("reused", ingested.reuse?.hash ?: JSONObject.NULL)
            // A video's own description, as the bubble draws it (ADR 2026-10.hmfe).
            .put("videoDurationMs", ingested.video?.durationMs ?: JSONObject.NULL)
            .put("posterBytes", ingested.video?.poster?.size ?: JSONObject.NULL)
    }

    /**
     * Saves a received attachment the way the chat's Save does — its plaintext, opened with the row's key when it is
     * sealed, and its plaintext name recorded on the way (ADR 2026-10.mptm) — but into `--es out <path>` in the app's
     * own storage rather than the gallery, so `SENDIMG --es path` can pick it again: the save-and-forward loop with
     * no UI. `--es id <messageId>` names the message. Replies with the plaintext's hash and size.
     */
    private suspend fun handleSaveAtt(intent: Intent): JSONObject {
        val id = intent.getStringExtra(EXTRA_ID) ?: return reply("error", "missing 'id' extra")
        val out = intent.getStringExtra(EXTRA_OUT) ?: return reply("error", "missing 'out' extra")
        val row = messages.observeMessage(id).first() ?: return reply("error", "no message $id")
        val hash = row.attachmentHash ?: return reply("error", "message $id has no attachment")
        val stored = blobs.bytes(hash) ?: return reply("error", "attachment $hash is not held")
        val key = row.attachmentKey
        val plain =
            if (key == null) {
                stored
            } else {
                AttachmentCrypto.open(stored, b64d(key)) ?: return reply("error", "the row's key does not open $hash")
            }
        if (key != null) identities.notePlain(hash, plain)
        File(out).writeBytes(plain)
        return reply("ok", "saved $id")
            .put("path", out)
            .put("hash", hash)
            .put("plainHash", sha256Hex(plain))
            .put("bytes", plain.size)
            .put("mime", row.attachmentMime ?: JSONObject.NULL)
    }

    /**
     * Decodes a WebP (or any image) through the exact `android.graphics.ImageDecoder` path Coil's
     * `AnimatedImageDecoder` uses, and reports whether it's animated + its dimensions — the definitive
     * in-app proof that a muxed animated WebP actually plays. A decode failure surfaces as an error reply.
     */
    private fun handleWebpCheck(intent: Intent): JSONObject {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return reply("error", "missing 'path' extra")
        val file = File(path)
        if (!file.exists()) return reply("error", "no such file: $path")
        val bytes = file.readBytes()
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        // Also exercise moderation's first-frame decode (BitmapFactory via decodeBoundedFromBytes), the
        // receive-side screening path — it must yield a frame so an animated WebP can still be screened.
        val modFrame = decodeBoundedFromBytes(bytes, WEBP_CHECK_MOD_DIM)
        return JSONObject()
            .put("status", "ok")
            .put("path", path)
            .put("bytes", bytes.size)
            .put("decoded", true)
            .put("animated", drawable is AnimatedImageDrawable)
            .put("drawable", drawable.javaClass.simpleName)
            .put("width", drawable.intrinsicWidth)
            .put("height", drawable.intrinsicHeight)
            .put("moderationDecodes", modFrame != null)
            .put("moderationFrame", if (modFrame != null) "${modFrame.width}x${modFrame.height}" else JSONObject.NULL)
    }

    /**
     * Runs the real send-side GIF → animated-WebP transcode ([WebpTranscode.shrink]) and writes the WebP
     * to `--es out <file>`, so the output can be pulled and validated end-to-end. `--ei dim`/`--ei fps`/
     * `--ei q` override the bounds (default production). Reports `origBytes`/`outBytes`/`pctSmaller`.
     */
    private fun handleWebpConv(intent: Intent): JSONObject {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return reply("error", "missing 'path' extra")
        val file = File(path)
        if (!file.exists()) return reply("error", "no such file: $path")
        val bytes = file.readBytes()
        val dim = intent.getIntExtra("dim", GIF_MAX_DIMENSION)
        val fps = intent.getIntExtra("fps", GIF_MAX_FPS)
        val quality = intent.getIntExtra("q", WEBP_PROBE_QUALITY)
        val webp = WebpTranscode.shrink(bytes, dim, fps, quality)
        val outPath = intent.getStringExtra(EXTRA_OUT)
        if (webp != null && outPath != null) File(outPath).writeBytes(webp)
        val outBytes = webp?.size ?: bytes.size
        val pct = if (bytes.isNotEmpty()) 100 - (outBytes.toLong() * 100 / bytes.size) else 0L
        return JSONObject()
            .put("status", "ok")
            .put("path", path)
            .put("dim", dim)
            .put("fps", fps)
            .put("quality", quality)
            .put("shrunk", webp != null)
            .put("origBytes", bytes.size)
            .put("outBytes", outBytes)
            .put("pctSmaller", pct)
            .put("wroteTo", if (webp != null) outPath ?: JSONObject.NULL else JSONObject.NULL)
    }

    /**
     * Measures what an **animated-WebP** re-encode of a GIF would weigh: decodes frames at `--ei dim` /
     * `--ei fps` (default production bounds) and sums each frame's built-in `WEBP_LOSSY` size at
     * `--ei q` (default [WEBP_PROBE_QUALITY]), plus a small per-frame ANMF mux estimate. This is a
     * feasibility probe for the "GIF → animated WebP via Bitmap.compress + a pure-Kotlin RIFF muxer"
     * path — it does not write a WebP (Android can't mux one yet), just reports the projected bytes.
     */
    @Suppress("DEPRECATION") // Movie is the only built-in GIF frame sampler; still functional.
    private fun handleWebpProbe(intent: Intent): JSONObject {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return reply("error", "missing 'path' extra")
        val file = File(path)
        if (!file.exists()) return reply("error", "no such file: $path")
        val bytes = file.readBytes()
        val dim = intent.getIntExtra("dim", GIF_MAX_DIMENSION)
        val fps = intent.getIntExtra("fps", GIF_MAX_FPS)
        val quality = intent.getIntExtra("q", WEBP_PROBE_QUALITY)
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
        if (movie == null || movie.width() <= 0 || movie.height() <= 0 || movie.duration() <= 0) {
            return reply("error", "Movie could not decode it / unknown size/timing")
        }

        val interval = (MILLIS_PER_SEC.toInt() / fps).coerceAtLeast(1)
        val frameBuffer = createBitmap(movie.width(), movie.height())
        val canvas = Canvas(frameBuffer)
        var frames = 0
        var lossyBytes = 0L
        var outDims = "?"
        var t = 0
        while (t < movie.duration()) {
            frameBuffer.eraseColor(Color.TRANSPARENT)
            movie.setTime(t)
            movie.draw(canvas, 0f, 0f)
            val scaled = downscale(frameBuffer, dim)
            outDims = "${scaled.width}x${scaled.height}"
            val fo = ByteArrayOutputStream()

            // WEBP (deprecated at API 30) is the API-29 lossy WebP format; WEBP_LOSSY is API 30.
            @Suppress("DEPRECATION")
            val webpFormat =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
            scaled.compress(webpFormat, quality, fo)
            lossyBytes += fo.size()
            if (scaled !== frameBuffer) scaled.recycle()
            frames++
            t += interval
        }
        frameBuffer.recycle()

        // Animated-WebP muxing is ~+WEBP_ANMF_OVERHEAD B/frame net (a ~24 B ANMF header per frame, less
        // the ~20 B RIFF/WEBP wrapper we'd strip off each single-frame compress) + a small VP8X/ANIM head.
        val est = lossyBytes + frames * WEBP_ANMF_OVERHEAD + WEBP_HEADER_OVERHEAD
        val pct = if (bytes.isNotEmpty()) 100 - (est * 100 / bytes.size) else 0L
        return JSONObject()
            .put("status", "ok")
            .put("path", path)
            .put("dim", dim)
            .put("fps", fps)
            .put("quality", quality)
            .put("frames", frames)
            .put("outDims", outDims)
            .put("origBytes", bytes.size)
            .put("webpAnimEstBytes", est)
            .put("pctSmaller", pct)
    }

    /**
     * Drives the offline "Share Knit app" prepare step headlessly (no share sheet): runs
     * [prepareKnitApk], which for a Play App Bundle install merges the on-disk splits into one re-signed
     * APK, and reports the staged `cacheDir/apk` files so the merged APK can be pulled and verified —
     * `adb shell run-as app.getknit.knit cat cache/apk/<name>`. `splitInstall` says which path ran.
     */
    private suspend fun handleShareApk(context: Context): JSONObject {
        val splitDirs =
            context.applicationInfo.splitSourceDirs
                ?.toList()
                .orEmpty()
        val uri = prepareKnitApk(context)
        val files = JSONArray()
        File(context.cacheDir, "apk")
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { files.put(JSONObject().put("name", it.name).put("bytes", it.length())) }
        return JSONObject()
            .put("status", "ok")
            .put("splitInstall", splitDirs.isNotEmpty())
            .put("splitCount", splitDirs.size)
            .put("uri", uri.toString())
            .put("files", files)
    }

    private suspend fun handleReact(intent: Intent): JSONObject {
        val messageId = intent.getStringExtra(EXTRA_ID) ?: return reply("error", "missing 'id' extra")
        val emoji = intent.getStringExtra(EXTRA_EMOJI) ?: return reply("error", "missing 'emoji' extra")
        // Resolve the thread context from the stored row, exactly as ChatViewModel.react passes it —
        // without it every bridge reaction is broadcast-shaped and rides the cleartext frame, which
        // would make a sealed-reaction smoke test pass vacuously.
        val conv = messages.conversationOf(messageId)
        val group = conv?.takeIf { Conversations.kindFor(it) == ConversationKind.GROUP }?.let { groups.find(it)?.toGroupInfo() }
        val recipientId = conv?.takeIf { group == null && Conversations.kindFor(it) == ConversationKind.DM }
        mesh.sendReaction(messageId, emoji, recipientId, group)
        return reply("ok", "reacted $emoji to $messageId").put("conversation", conv ?: "unknown")
    }

    /**
     * Fires one best-effort "now typing" cue for `--es conv <id>` (or the `to` DM shorthand; default the
     * broadcast room), exactly as the chat input's throttle does. Poll a receiver's [ACTION_STATE] `typing`
     * field to confirm it landed. Fire-and-forget: replies `ok` regardless of whether anyone was reachable.
     * `--ez indicators <bool>` sets the Settings › Typing indicators switch instead and sends nothing (ADR
     * 2026-10.97vs); every reply names the switch's state as `indicators`.
     */
    private suspend fun handleTyping(intent: Intent): JSONObject {
        if (intent.hasExtra(EXTRA_INDICATORS)) {
            val on = intent.getBooleanExtra(EXTRA_INDICATORS, true)
            settings.setTypingIndicatorsEnabled(on)
            return reply("ok", "typing indicators ${if (on) "on" else "off"}").put("indicators", on)
        }
        val conv = intent.getStringExtra(EXTRA_CONV) ?: intent.getStringExtra(EXTRA_TO) ?: Conversations.NEARBY
        mesh.sendTyping(conv)
        return reply("ok", "typing cue sent to $conv")
            .put("conversation", conv)
            .put("indicators", settings.typingIndicatorsEnabled.first())
    }

    private suspend fun handleState(intent: Intent): JSONObject {
        val selfId = identity.nodeId()
        val selfName = settings.displayName.first()
        val pausedUntil = settings.meshPausedUntil.first()
        val meshEnabled = settings.meshEnabled.first()
        val nameByNode = peers.observePeers().first().associate { it.nodeId to it.name }

        val reachable = JSONArray()
        mesh.neighbors.value.forEach { peer ->
            reachable.put(JSONObject().put("nodeId", peer.nodeId).put("name", nameByNode[peer.nodeId] ?: ""))
        }

        // The radio mesh's indirect reach (ADR 2026-10.fw8g): authors another phone handed us a fresh frame from,
        // within the linger, with that phone and how long ago — the oracle for Diagnostics' "Reachable indirectly".
        val now = System.currentTimeMillis()
        val heard = mesh.heardIndirectly.value
        val indirect = JSONArray()
        indirectPeers(heard, now).forEach { (nodeId, via) ->
            indirect.put(
                JSONObject()
                    .put("nodeId", nodeId)
                    .put("name", nameByNode[nodeId] ?: "")
                    .put("via", via)
                    .put("agoMs", now - heard.getValue(nodeId).heardAt),
            )
        }

        // Ephemeral "who's typing" state (conversationId -> [senderNodeId, …]), so a receiver can be polled
        // headlessly to confirm a best-effort typing cue landed — the indicator itself is UI-only/transient.
        val typing = JSONObject()
        mesh.typing.value.forEach { (conv, senders) -> typing.put(conv, JSONArray(senders.toList())) }

        // Supporter badges (docs/SUPPORTER_BADGE.md, ADR 2026-10.r99s): each peer's verified ids and `exp` at this phone's
        // clock, and our own switch and held badges' ids and `exp`. Never an `r`, a `sub` or a wrapper.
        val pins = BadgeKeys.forBuild(BuildConfig.DEBUG)
        val nowS = System.currentTimeMillis() / 1_000
        val peerBadges = JSONObject()
        PeerBadges.verifyAll(peers.observeBadges().first(), nowS, pins).forEach { (nodeId, shown) ->
            peerBadges.put(nodeId, JSONArray(shown.map { JSONObject().put("b", it.b).put("exp", it.exp) }))
        }
        val ownBadge =
            JSONObject()
                .put("show", badgeStore.show.first())
                .put(
                    "held",
                    JSONArray(
                        badgeStore.held.first().map { held ->
                            val claims = Badges.claimsOf(held.wrapper)
                            JSONObject()
                                .put("b", claims?.b ?: JSONObject.NULL)
                                .put("exp", claims?.exp ?: JSONObject.NULL)
                                .put("verifies", Badges.verify(held.wrapper, held.r, selfId, nowS, pins) != null)
                        },
                    ),
                )

        val out =
            JSONObject()
                .put("status", "ok")
                .put("self", JSONObject().put("nodeId", selfId).put("name", selfName))
                .put("badges", peerBadges)
                .put("ownBadge", ownBadge)
                .put("health", mesh.transportHealth.value.name)
                // Per-radio, because the merged health hides a plane's own verdict behind a sibling that works —
                // `ForegroundOnly` on Wi-Fi Aware reads `Healthy` above whenever Bluetooth is up (ADR 2026-09.535d).
                .put(
                    "transports",
                    JSONArray(
                        mesh.transportStatuses.value.map {
                            JSONObject()
                                .put("kind", it.kind.name)
                                .put("health", it.health.name)
                                .put("linked", it.linked)
                                .put("nearby", it.nearby)
                                .put("initiatorHeld", it.initiatorHeld)
                                .put("switchedOff", it.switchedOff)
                                .put("dormant", it.dormant)
                        },
                    ),
                ).put("neighborCount", mesh.neighborCount.value)
                // True when a MeshService.start was refused (backgrounded, unexempted) and is still owed the
                // retry KnitApp's ON_RESUME observer performs — otherwise a dead mesh is indistinguishable
                // from a live one with no peers. Work item #32.
                .put("meshStartDeferred", startGate.deferred.value)
                // The pause deadline as stored, and whether it is still a pause (the service reads it the same way).
                .put("meshPausedUntil", pausedUntil ?: JSONObject.NULL)
                .put("meshPaused", MeshPause.activeDeadline(pausedUntil, System.currentTimeMillis()) != null)
                // False after the notification's Stop: KnitApp and BootReceiver then start nothing until Start.
                .put("meshEnabled", meshEnabled)
                // Low-power mode's switch and the posture the planes read (ADR 2026-10.d44b).
                .put("lowPower", lowPowerJson())
                .put("reachable", reachable)
                .put("indirect", indirect)
                .put("typing", typing)
                .put("metrics", metricsJson(metrics.snapshot()))

        intent.getStringExtra(EXTRA_CONV)?.let { conv ->
            val limit = intent.getIntExtra(EXTRA_LIMIT, DEFAULT_MESSAGE_LIMIT)
            val recent = messages.observeNewestMessages(conv, limit).first()
            out.put("conversation", conv).put("messages", messagesJson(recent, selfId, selfName, nameByNode))
        }
        return out
    }

    private suspend fun messagesJson(
        rows: List<MessageEntity>,
        selfId: String,
        selfName: String,
        nameByNode: Map<String, String>,
    ): JSONArray {
        // Per-message reaction rows (reactor -> emoji), so a smoke/soak run can verify a reaction
        // APPLIED on the receiving device — the chips render on screen but don't surface any text to
        // `uiautomator dump`, and the sealed forms otherwise have no machine-checkable landing signal.
        val reactionsByMessage = reactions.observeReactions().first().groupBy { it.messageId }
        val arr = JSONArray()
        rows.forEach { m ->
            val from = if (m.senderId == selfId) selfName else nameByNode[m.senderId] ?: ""
            val rx = JSONObject()
            reactionsByMessage[m.id].orEmpty().forEach { r -> r.emoji?.let { rx.put(r.reactorNodeId, it) } }
            val verdict = m.attachmentHash?.let { verdicts.find(it) }
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("from", m.senderId)
                    .put("fromName", from)
                    .put("mine", m.senderId == selfId)
                    .put("body", m.body)
                    // The formatting as stored ([[start, length, code], …]; ADR 2026-10.p95v), or null for none.
                    .put("styles", m.styles ?: JSONObject.NULL)
                    .put("sentAt", m.sentAt)
                    .put("received", m.received)
                    .put("reactions", rx)
                    .put("replyToId", m.replyToId ?: JSONObject.NULL)
                    .put("replyToAuthor", m.replyToAuthor ?: JSONObject.NULL)
                    .put("replyToSnippet", m.replyToSnippet ?: JSONObject.NULL)
                    // The receiver's verdict on the quote (ADR 2026-10.gpa3): 0 unchecked, 1 verified, 2 mismatch; and
                    // whether the quoted words are flagged on this phone.
                    .put("replyCheck", m.replyCheck)
                    .put("replyToFlagged", m.replyToFlagged)
                    // The attachment's identity (ADR 2026-10.mptm): the hash it travels under, whether it is sealed, the
                    // plaintext's name once known, and whether the bytes are here — what a forward trial compares.
                    .put("attachmentHash", m.attachmentHash ?: JSONObject.NULL)
                    .put("attachmentKeyed", m.attachmentKey != null)
                    .put("attachmentPlainHash", m.attachmentPlainHash ?: JSONObject.NULL)
                    .put("attachmentHeld", m.attachmentHash?.let { blobs.exists(it) } ?: JSONObject.NULL)
                    .put("attachmentAnimated", m.attachmentAnimated)
                    // This phone's NSFW screen (ADR 2026-10.hmfe): the attachment's verdict, null until it is screened,
                    // with its score (a video's highest frame), and a video poster's own verdict, kept on the row.
                    .put("attachmentFlagged", verdict?.flagged ?: JSONObject.NULL)
                    .put("attachmentScore", verdict?.score?.toDouble() ?: JSONObject.NULL)
                    .put("videoPosterFlagged", m.videoPosterFlagged)
                    // A voice note's text, kept on the row once this phone transcribed it (ADR 2026-10.am8d).
                    .put("voiceDurationMs", m.voiceDurationMs ?: JSONObject.NULL)
                    .put("voiceTranscript", m.voiceTranscript ?: JSONObject.NULL)
                    // The translation kept on the row, and the languages its author declared (ADR 2026-10.t2h6).
                    .put("translation", m.translation ?: JSONObject.NULL)
                    .put("translationFrom", m.translationFrom ?: JSONObject.NULL)
                    .put("translationTo", m.translationTo ?: JSONObject.NULL)
                    .put("translationFlagged", m.translationFlagged)
                    .put("translationGuessed", m.translationGuessed)
                    .put("bodyLang", m.bodyLang ?: JSONObject.NULL)
                    .put("authorReads", m.authorReads ?: JSONObject.NULL),
            )
        }
        return arr
    }

    /**
     * Dumps the store-and-forward carry set — the **live** rows are the exact id set the cue-plane content
     * digest ([StoreDigest.current]) is folded over (work item #8: expired-but-unswept rows are invisible to
     * the digest, quotas, and serves — pure residue awaiting the sweep), so two devices that never converge
     * (each keeps firing NDPs at the other) can be diffed to find the stranded id. Reports:
     *  - `digestVersion` — the live in-memory digest the transport actually cues (read via [StoreDigest.current],
     *    the same lazy-folding accessor the transports use, so a TTL boundary crossed since the transport's
     *    last read can't masquerade as drift);
     *  - `allFingerprint` / `liveFingerprint` — the digest recomputed over *all* rows vs. *non-expired* rows.
     *    **The invariant is `digestVersion == liveFingerprint`, always** — a mismatch means the in-memory
     *    digest drifted from the table (a bug). `allFingerprint` legitimately lags behind by the expired
     *    residue until the sweep, and is **no longer fleet-comparable** at a TTL boundary — cross-device
     *    convergence checks (soak oracles included) must compare `liveFingerprint`;
     *  - `expiredIds` — the residue rows (benign; reclaimed by the next sweep);
     *  - `allIds` — the full set, for a cross-device diff (`comm`/`diff` the sorted arrays across P7/P8/P9);
     *  - `rows` — per-frame detail (expired first, then newest), capped by `--ei limit` ([DEFAULT_STORE_LIMIT]).
     */
    private suspend fun handleStore(intent: Intent): JSONObject {
        val now = System.currentTimeMillis()
        val limit = intent.getIntExtra(EXTRA_LIMIT, DEFAULT_STORE_LIMIT)
        val rows = forwardDao.allRows()
        val liveRows = rows.filter { it.expiresAt >= now }
        val expiredRows = rows.filter { it.expiresAt < now }

        val rowsJson = JSONArray()
        // Expired first, then newest, so the diagnostically-interesting rows survive a truncated dump.
        rows.sortedWith(compareBy({ it.expiresAt >= now }, { -it.receivedAt })).take(limit).forEach { r ->
            rowsJson.put(
                JSONObject()
                    .put("id", r.id)
                    .put("type", r.type)
                    .put("sender", r.senderId)
                    .put("origin", if (r.origin == ForwardStore.ORIGIN_SELF) "self" else "relay")
                    .put("recipient", r.recipientId ?: JSONObject.NULL)
                    .put("group", r.groupId ?: JSONObject.NULL)
                    // The image blob this frame custodies (see forward_store v19), so a device diff can show
                    // whether a carrier is holding the referenced attachment for a late joiner.
                    .put("attachmentHash", r.attachmentHash ?: JSONObject.NULL)
                    .put("ageSec", (now - r.receivedAt) / MILLIS_PER_SEC)
                    .put("ttlLeftSec", (r.expiresAt - now) / MILLIS_PER_SEC)
                    .put("expired", r.expiresAt < now),
            )
        }

        return JSONObject()
            .put("status", "ok")
            .put("self", JSONObject().put("nodeId", identity.nodeId()).put("name", settings.displayName.first()))
            .put("digestVersion", digest.current().toString())
            .put("allFingerprint", StoreDigest.fingerprint(rows.map { it.id }).toString())
            .put("liveFingerprint", StoreDigest.fingerprint(liveRows.map { it.id }).toString())
            .put(
                "counts",
                JSONObject().put("total", rows.size).put("live", liveRows.size).put("expired", expiredRows.size),
            ).put("expiredIds", JSONArray(expiredRows.map { it.id }))
            .put("allIds", JSONArray(rows.map { it.id }))
            .put("rows", rowsJson)
    }

    /**
     * Dumps the rate-prompt gate as the real prompt would evaluate it right now — the inputs come from
     * [ReviewPrompter.gateInputs] / [ReviewPrompter.installedFromPlay], so this can't drift from the
     * production gate, plus the installer-aware [ReviewPrompter.rateUrl] the positive button would open.
     * `--ez reset true` clears the persisted state first; `--ez arm true` additionally backdates the
     * engagement watermark past the age gate (the message-count gates still need real rows, e.g. via
     * [ACTION_SEND] from a second device).
     */
    private suspend fun handleReview(
        context: Context,
        intent: Intent,
    ): JSONObject {
        val now = System.currentTimeMillis()
        if (intent.getBooleanExtra(EXTRA_RESET, false)) reviewStore.clear()
        if (intent.getBooleanExtra(EXTRA_ARM, false)) {
            reviewStore.clear()
            reviewStore.setEngagementStartedAt(now - ReviewPromptPolicy.MIN_ENGAGEMENT_AGE_MS - ARM_MARGIN_MS)
        }
        @Suppress("DEPRECATION")
        val installer =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            }.getOrNull()
        val inputs = reviewPrompter.gateInputs(now)
        return JSONObject()
            .put("status", "ok")
            .put("installer", installer ?: JSONObject.NULL)
            .put("playInstall", reviewPrompter.installedFromPlay())
            .put("rateUrl", reviewPrompter.rateUrl())
            .put("feedbackUrl", reviewPrompter.feedbackUrl)
            .put("peerMessages", inputs.peerMessageCount)
            .put("sentMessages", inputs.sentMessageCount)
            .put("engagementStartedAt", inputs.engagementStartedAt)
            .put("lastAttemptAt", inputs.lastAttemptAt)
            .put("attemptCount", inputs.attemptCount)
            .put("shouldPrompt", ReviewPromptPolicy.shouldPrompt(inputs))
    }

    /**
     * Reads (and optionally clears) the model poison-pill's persisted state — the seam that makes the
     * acceptance test observable without `adb shell dumpsys activity exit-info`, and the only way to see
     * *why* a latch did or did not fire, since the decision turns on a platform exit record the app
     * otherwise never shows.
     */
    private suspend fun handleModel(intent: Intent): JSONObject {
        if (intent.getBooleanExtra(EXTRA_RESET, false)) ModelLoadGuard.ALL.forEach { modelGuard.clear(it) }
        // The shortcut through the ten-minute idle cycle: close both engines now, so the next classify
        // reloads through the guard — what the memory trial and the reload check watch.
        if (intent.getBooleanExtra(EXTRA_UNLOAD, false)) {
            textModel.unload()
            imageModel.unload()
        }
        val stamp = modelGuardStamp(BuildConfig.VERSION_CODE, Build.FINGERPRINT.orEmpty())
        val models = JSONArray()
        for (model in ModelLoadGuard.ALL) {
            val state = modelJournal.modelLoadState(model)
            val (resident, lastUsedAt) =
                when (model) {
                    ModelLoadGuard.TOXICITY -> textModel.isResident to textModel.lastUsedAt
                    ModelLoadGuard.STT -> (getKoin().getOrNull<MoonshineSpeech>()?.resident == true) to 0L
                    else -> imageModel.isResident to imageModel.lastUsedAt
                }
            models.put(
                JSONObject()
                    .put("model", model)
                    .put("stamp", state.stamp)
                    .put("stale", state.stamp != stamp)
                    .put("pendingSince", state.pendingSince)
                    .put("fails", state.fails)
                    .put("latched", modelGuard.observeLatched(model).first())
                    .put("resident", resident)
                    .put("lastUsedAt", lastUsedAt),
            )
        }
        val exit = exits.lastExit()
        return JSONObject()
            .put("status", "ok")
            .put("stamp", stamp)
            .put("maxFails", ModelLoadPolicy.MAX_FAILS)
            .put("faultOnLoad", BuildConfig.MODEL_FAULT_ON_LOAD)
            .put("idleMs", ModelLease.DEFAULT_IDLE_MS)
            .put("models", models)
            .put(
                "lastExit",
                exit?.let {
                    JSONObject()
                        .put("at", it.at)
                        .put("nativeFault", it.nativeFault)
                        .put("explained", it.explained)
                } ?: JSONObject.NULL,
            )
    }

    /**
     * Posts the coalesced "message request received" heads-up on demand — the seam the UIAutomator
     * notification test drives ([app.getknit.knit] `uiauto`). The radio-less demo build never runs
     * [app.getknit.knit.mesh.InboundPipeline] (the sole production caller of
     * [Notifier.notifyMessageRequests]) and seeds no requests, so nothing would otherwise post one. This
     * writes `count` synthetic **unaccepted inbound DMs** from fresh unknown peers — each a message request
     * per [Conversations.isAccepted] (not accepted / verified / self-authored) so a tap on the heads-up lands
     * on a populated Requests inbox — then posts the heads-up. The app must hold `POST_NOTIFICATIONS` (the
     * test grants it) or the post silently no-ops. `--ei count N` (default 1, capped at [MAX_REQNOTIF]).
     */
    private suspend fun handleReqNotif(intent: Intent): JSONObject {
        val count = intent.getIntExtra(EXTRA_COUNT, 1).coerceIn(1, MAX_REQNOTIF)
        val me = identity.nodeId()
        val now = System.currentTimeMillis()
        repeat(count) { i ->
            val nodeId = "strngr0${i + 1}"
            val name = if (i == 0) "Alex Stranger" else "Stranger ${i + 1}"
            // A discovered-but-unaccepted peer (no pinned key, not verified) so its DM stays a request.
            peers.upsert(PeerEntity(nodeId = nodeId, name = name, updatedAt = now))
            // One inbound DM: for a received DM the conversationId is the sender's node id and recipientId
            // is us (Conversations.idFor); received=false marks it as not ours.
            messages.save(
                MessageEntity(
                    id = "reqnotif-$nodeId-$now",
                    senderId = nodeId,
                    recipientId = me,
                    conversationId = nodeId,
                    body = "Hey! Mind if I join the hike?",
                    sentAt = now - i * 1_000L,
                    // Inbound, so it carries the arrival stamp the real pipeline would have written.
                    arrivedAt = now - i * 1_000L,
                    received = false,
                ),
            )
        }
        // The demo build never starts MeshService, so the channels may not exist yet — ensure them first.
        notifier.createChannel()
        // The exact call InboundPipeline makes when it silences a stranger's first contact as a request.
        notifier.notifyMessageRequests(count)
        return reply("ok", "posted $count message request(s)").put("count", count)
    }

    /**
     * Posts one incoming-message notification for a seeded conversation, the seam for checking the shade's
     * avatar on the radio-less build — through the resolver InboundPipeline hands the notifier from
     * ([app.getknit.knit.notifications.ConversationFaces]): a DM's peer name and avatar, a group's title and photo,
     * and for a photo-less group the `groupFaceIds` pick resolved to bytes, so `NotificationAvatars.clusterAvatar`
     * draws what the list does.
     * `--es conv <id>` (required), `--es from <peerNodeId>` (default the first other member / the DM peer),
     * `--es text <body>`. The app must hold `POST_NOTIFICATIONS` or the post silently no-ops.
     */
    private suspend fun handleMsgNotif(intent: Intent): JSONObject {
        val conv = intent.getStringExtra(EXTRA_CONV) ?: return JSONObject().put("error", "missing --es conv <id>")
        val me = identity.nodeId()
        val kind = Conversations.kindFor(conv)
        val group = if (kind == ConversationKind.GROUP) groups.find(conv) else null
        val memberIds = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
        val labels = peers.labelIndex()
        val from = intent.getStringExtra(EXTRA_FROM) ?: memberIds.firstOrNull { it != me } ?: conv
        val fromName = labels.labelFor(from).text
        val fromAvatar = peers.find(from)?.avatarHash?.let { blobs.bytes(it) }
        val body = intent.getStringExtra(EXTRA_TEXT) ?: "Shade check"
        val conversation = conversationFaces.resolve(conv, me, labels)
        val incoming = NotifMessage(from, fromName, body, System.currentTimeMillis(), conv, fromAvatar)
        notifier.createChannel()
        notifier.notify(incoming, conversation, me, settings.displayName.first(), settings.ownAvatarHash.first()?.let { blobs.bytes(it) })
        return JSONObject()
            .put("conv", conv)
            .put("kind", kind.name)
            .put("title", conversation.title)
            .put("faces", JSONArray(conversation.faces.map { it.nodeId }))
            .put("photo", conversation.avatarBytes != null)
    }

    /**
     * Injects one synthetic **inbound message the on-device text moderator flagged** into a conversation, so
     * the UIAutomator suite can drive the received-flagged "tap to reveal" collapse (the
     * [MessageEntity.MODERATION_TEXT_FLAGGED] path in [app.getknit.knit.ui.chat] `ChatScreen`). The radio-less
     * demo build never receives a real flagged message (no [app.getknit.knit.mesh] `InboundPipeline`), and the
     * marketing seed deliberately carries none (a hidden bubble would spoil a screenshot), so this writes one
     * on demand — timestamped `now`, so it's the **newest** row and a `LazyColumn` composes it on screen.
     * `--es conv <id>` (default the broadcast room), `--es from <peerNodeId>` the sender (default
     * [FLAGGED_SENDER_ID], upserted with a name if unknown), `--es text <body>` the hidden body (default
     * [DEFAULT_FLAGGED_BODY]).
     */
    private suspend fun handleFlagMsg(intent: Intent): JSONObject {
        val conv = intent.getStringExtra(EXTRA_CONV) ?: Conversations.NEARBY
        val body = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: DEFAULT_FLAGGED_BODY
        val from = intent.getStringExtra(EXTRA_FROM) ?: FLAGGED_SENDER_ID
        val me = identity.nodeId()
        val now = System.currentTimeMillis()
        // Give the sender a name so the row renders like a real peer message (idempotent upsert).
        if (peers.find(from) == null) {
            peers.upsert(PeerEntity(nodeId = from, name = FLAGGED_SENDER_NAME, updatedAt = now))
        }
        // For a received DM the conversationId is the sender's node id and recipientId is us; the room/group
        // carries no recipient — mirrors DemoWriter.write / handleReqNotif's routing.
        val recipient = if (Conversations.kindFor(conv) == ConversationKind.DM) me else null
        val id = "flagmsg-$now"
        messages.save(
            MessageEntity(
                id = id,
                senderId = from,
                recipientId = recipient,
                conversationId = conv,
                body = body,
                sentAt = now,
                // Inbound, so it carries the arrival stamp the real pipeline would have written.
                arrivedAt = now,
                received = false,
                moderation = MessageEntity.MODERATION_TEXT_FLAGGED,
            ),
        )
        return reply("ok", "flagged inbound message injected into $conv").put("conversation", conv).put("id", id)
    }

    private fun metricsJson(snap: MeshMetrics.Snapshot): JSONObject =
        JSONObject()
            .put("originated", snap.frames.framesOriginated)
            .put("delivered", snap.frames.framesDelivered)
            .put("relayed", snap.frames.framesRelayed)
            .put("dropped", snap.frames.framesDropped)
            .put("keyRequestsSent", snap.keys.keyRequestsSent)
            .put("keysServed", snap.keys.keysServed)
            .put("keysRecovered", snap.keys.keysRecovered)
            .put("framesHeld", snap.keys.framesHeld)
            .put("framesReplayed", snap.keys.framesReplayed)
            .put("receiptsResent", snap.receipts.receiptsResent)
            // A cut attachment resumed from its kept prefix (#116): tails served, tails taken, and splices whose
            // hash failed (a holder or a prefix that was wrong; the blob is then asked for from byte 0).
            .put("filesResumedOut", snap.files.filesResumedOut)
            .put("filesResumedIn", snap.files.filesResumedIn)
            .put("splicesRefused", snap.files.splicesRefused)
            // The holder query (ADR 2026-10.6e5t): queries sent and answered, then what the fetch did with the answers.
            .put("blobQueriesSent", snap.files.blobQueriesSent)
            .put("blobQueriesAnswered", snap.files.blobQueriesAnswered)
            .put("blobQueriesRefused", snap.files.blobQueriesRefused)
            .put("blobAnswersTaken", snap.files.blobAnswersTaken)
            .put("blobAnswersIgnored", snap.files.blobAnswersIgnored)
            .put("blobFetches", snap.files.blobFetches)
            .put("blobConfirms", snap.files.blobConfirms)
            .put("blobFallbacks", snap.files.blobFallbacks)
            .put("blobBlindAsks", snap.files.blobBlindAsks)
            .put("blobPullThroughs", snap.files.blobPullThroughs)
            .put("blobAsksParked", snap.files.blobAsksParked)
            .put("blobProgressSent", snap.files.blobProgressSent)
            .put("blobProgressTaken", snap.files.blobProgressTaken)
            .put("blobCancelsSent", snap.files.blobCancelsSent)
            .put("blobCancelsTaken", snap.files.blobCancelsTaken)
            // Ask before large Bluetooth downloads (ADR 2026-10.js2p): fast-only holds, refusals and dropped copies.
            .put("blobFastOnlyHeld", snap.files.blobFastOnlyHeld)
            .put("blobFastOnlyRefused", snap.files.blobFastOnlyRefused)
            // A DM photo's recipient asked whether it holds the bytes, and said so (ADR 2026-10.vy27).
            .put("blobProofQueriesSent", snap.files.blobProofQueriesSent)
            .put("blobProofAnswersTaken", snap.files.blobProofAnswersTaken)
            .put("blobProofsHeld", snap.files.blobProofsHeld)
            .put("fastOnlyDropped", snap.files.fastOnlyDropped)
            .put("carrierPullsDeferred", snap.files.carrierPullsDeferred)
            .put("dmSealedV2", snap.seals.dmSealedV2)
            .put("dmSealedV3", snap.seals.dmSealedV3)
            .put("ticksUnsigned", snap.receipts.ticksUnsigned)
            .put("groupSealedRatchet", snap.groups.groupSealedRatchet)
            .put("groupSealedV1Fallback", snap.groups.groupSealedV1Fallback)
            .put("groupSeedsSent", snap.groups.groupSeedsSent)
            .put("groupSeedsAdopted", snap.groups.groupSeedsAdopted)
            // A seed that outran its group's first frame, parked and then replayed once the roster landed
            // (PendingGroupKeys); held without a matching replay is a member that never saw the roster.
            .put("groupSeedsHeld", snap.groups.groupSeedsHeld)
            .put("groupSeedsReplayed", snap.groups.groupSeedsReplayed)
            .put("groupKeyRequestsSent", snap.groups.groupKeyRequestsSent)
            // Read these together: a trickle of adoptions with no local mint is healthy gossip, while
            // mint/adopt alternating on one device is a lineage that is not collapsing (SPOOL_PROTOCOL §3.2).
            .put("groupRootsMinted", snap.groups.groupRootsMinted)
            .put("groupRootsAdopted", snap.groups.groupRootsAdopted)
            // Group frames custody would not carry: a roster that does not derive, or a sender outside it (ADR 2026-10.7mqm).
            .put("groupCustodyRefused", snap.groups.groupCustodyRefused)
            .put("dmSealedV1Fallback", snap.seals.dmSealedV1Fallback)
            .put("receiptsSealed", snap.receipts.receiptsSealed)
            .put("receiptsSealedFallback", snap.receipts.receiptsSealedFallback)
            .put("receiptsCustodied", snap.receipts.receiptsCustodied)
            .put("receiptsCoalesced", snap.receipts.receiptsCoalesced)
            .put("receiptsRidden", snap.receipts.receiptsRidden)
            .put("receiptsSpooled", snap.receipts.receiptsSpooled)
            .put("reactionsSealed", snap.seals.reactionsSealed)
            .put("reactionsSealedFallback", snap.seals.reactionsSealedFallback)
            .put("dropsByReason", JSONObject(snap.frames.dropsByReason.mapKeys { it.key.name }))
            .put("nanServesPeak", snap.nan.nanServesPeak)
            .put("nanOwedNoLinkPeakMs", snap.nan.nanOwedNoLinkPeakMs)
            .put("nanAcceptsRefused", snap.nan.nanAcceptsRefused)
            .put("nanIcmKeepaliveFailed", snap.nan.nanIcmKeepaliveFailed)
            .put("nanMsgsAcked", snap.nan.nanMsgsAcked)
            .put("nanMsgSendsFailed", snap.nan.nanMsgSendsFailed)
            .put("nanMsgPlaneStalledPeakMs", snap.nan.nanMsgPlaneStalledPeakMs)
            .put("nanMsgPlaneCycles", snap.nan.nanMsgPlaneCycles)
            .put("filesSentNan", snap.files.filesSentNan)
            .put("filesSentBt", snap.files.filesSentBt)
            .put("nanBulkGraceTimeouts", snap.nan.nanBulkGraceTimeouts)
            .put("fastCompactSent", snap.fast.fastCompactSent)
            .put("fastLegacySent", snap.fast.fastLegacySent)
            .put("fastFragSent", snap.fast.fastFragSent)
            .put("fastReassembled", snap.fast.fastReassembled)
            .put("fastTooBig", snap.fast.fastTooBig)
            .put("fastTranscodedSent", snap.fast.fastTranscodedSent)
            .put("transcodeFallbacks", snap.fast.transcodeFallbacks)
            .put("fastDropsByReason", JSONObject(snap.fast.fastDropsByReason.mapKeys { it.key.name }))
            .put("bleSideOffered", snap.bluetooth.bleSideOffered)
            .put("bleSidePartsAired", snap.bluetooth.bleSidePartsAired)
            .put("bleSideTooBig", snap.bluetooth.bleSideTooBig)
            .put("bleSideHeard", snap.bluetooth.bleSideHeard)
            .put("bleSideReassembled", snap.bluetooth.bleSideReassembled)
            .put("bleSideDeduped", snap.bluetooth.bleSideDeduped)
            .put("bleSideDropsByReason", JSONObject(snap.bluetooth.bleSideDropsByReason.mapKeys { it.key.name }))
            .put("bleLinkDupSkipped", snap.bluetooth.bleLinkDupSkipped)
            .put("bleCodedSightings", snap.bluetooth.bleCodedSightings)
            .put("bleCodedDials", snap.bluetooth.bleCodedDials)
            .put("blePhyStepsDown", snap.bluetooth.blePhyStepsDown)
            .put("blePhyStepsUp", snap.bluetooth.blePhyStepsUp)
            .put("blePhyGiveUps", snap.bluetooth.blePhyGiveUps)
            .put("bleCodedAdvertDark", snap.bluetooth.bleCodedAdvertDark)
            .put("digestsReplaced", snap.frames.digestsReplaced)
            // Frames custody refused for `signed` over the byte cap (ADR 2026-10.mqzv); knit-ios reports the same key.
            .put("custodyOversizeRefused", snap.frames.custodyOversizeRefused)
            .put("spoolTablesDerived", snap.spool.spoolTablesDerived)
            .put("spoolPushed", snap.spool.spoolPushed)
            .put("spoolPulled", snap.spool.spoolPulled)
            .put("spoolBridged", snap.spool.spoolBridged)
            .put("spoolInvalid", snap.spool.spoolInvalid)
            .put("spoolAccounted", snap.spool.spoolAccounted)
            .put("spoolErrors", snap.spool.spoolErrors)
            .put("spoolAttachPushed", snap.spool.spoolAttachPushed)
            .put("spoolAttachPulled", snap.spool.spoolAttachPulled)
            // The two-island photo trial reads as `spoolAttachDeferred` climbing while the devices are
            // together and `spoolAttachPushed` starting within a heal round of separating them.
            .put("spoolAttachDeferred", snap.spool.spoolAttachDeferred)
            // A DM photo its recipient provably holds: settled for good, never uploaded (ADR 2026-10.vy27).
            .put("spoolAttachProved", snap.spool.spoolAttachProved)
            .put("loraSent", snap.lora.loraSent)
            .put("loraFragSent", snap.lora.loraFragSent)
            .put("loraTranscoded", snap.lora.loraTranscoded)
            .put("loraPadded", snap.lora.loraPadded)
            .put("loraReceived", snap.lora.loraReceived)
            .put("loraReassembled", snap.lora.loraReassembled)
            .put("loraTooBig", snap.lora.loraTooBig)
            .put("loraDroppedQueue", snap.lora.loraDroppedQueue)
            .put("loraAirtimeHeld", snap.lora.loraAirtimeHeld)
            .put("loraAirtimeHeldByBucket", JSONObject(snap.lora.loraAirtimeHeldByBucket))
            .put("loraSuppressed", snap.lora.loraSuppressed)
            .put("loraNak", snap.lora.loraNak)
            .put("loraNakByReason", JSONObject(snap.lora.loraNakByReason))
            .put("loraSessionUps", snap.lora.loraSessionUps)
            .put("loraDmSent", snap.lora.loraDmSent)
            .put("loraDmReceived", snap.lora.loraDmReceived)
            .put("loraReoffered", snap.lora.loraReoffered)
            .put("loraProfileRefanSkipped", snap.lora.loraProfileRefanSkipped)
            .put("loraOfferSent", snap.lora.loraOfferSent)
            .put("loraOfferReceived", snap.lora.loraOfferReceived)
            .put("loraBridged", snap.lora.loraBridged)
            .put("loraBridgeRefused", snap.lora.loraBridgeRefused)
            .put("loraPassive", snap.lora.loraPassive)
            .put("loraSkippedLinked", snap.lora.loraSkippedLinked)
            .put("loraSkippedInternet", snap.lora.loraSkippedInternet)
            // A DM-form frame to a recipient this plane has not heard: held in custody for the first-hearing
            // re-offer, except the one probe per recipient per window (ADR 2026-10.a4pm).
            .put("loraUnheardHeld", snap.lora.loraUnheardHeld)
            .put("loraDmProbes", snap.lora.loraDmProbes)
            // The same gates as `loraSkippedLinked`, asked again at the moment of sending: how much air the
            // queue would have spent on frames whose answer changed while they waited (LINKED/INTERNET/UNHEARD/STALE/PASSIVE).
            .put("loraStaleAtSend", snap.lora.loraStaleAtSend)
            .put("loraStaleAtSendByReason", JSONObject(snap.lora.loraStaleAtSendByReason))
            .put("loraTickDeferred", snap.lora.loraTickDeferred)
            // The Meshtastic room's inbound half. `heard` is every chat packet on the board's primary channel,
            // `ingested` what survived the filters, and the difference is itemised in `refusedByReason`;
            // `viaMqtt` is the share that came off somebody's uplink rather than off the air.
            .put("meshPostHeard", snap.meshtastic.meshPostHeard)
            .put("meshPostIngested", snap.meshtastic.meshPostIngested)
            .put("meshPostViaMqtt", snap.meshtastic.meshPostViaMqtt)
            .put("meshPostMatched", snap.meshtastic.meshPostMatched)
            // What the 2.8 signatures proved: `verified` under a contact's advertised key (the shield),
            // `boardVerified` by our own board's NodeDB key alone, `signatureMismatch` a contact's number
            // signed by some other radio.
            .put("meshPostVerified", snap.meshtastic.meshPostVerified)
            .put("meshPostBoardVerified", snap.meshtastic.meshPostBoardVerified)
            .put("meshPostSignatureMismatch", snap.meshtastic.meshPostSignatureMismatch)
            .put("meshPostRefusedByReason", JSONObject(snap.meshtastic.meshPostRefusedByReason))
            // The outbound half. Unlike the four above these cost airtime, so read `publicPostSent` against
            // `airtime.publicMs`/`publicBudgetMs` to say whether the quota is set right. Every refusal here
            // was shown to the user at the composer.
            .put("publicPostSent", snap.meshtastic.publicPostSent)
            .put("publicPostRefusedByReason", JSONObject(snap.meshtastic.publicPostRefusedByReason))
            // The DM auto-reply. `heard` is every text addressed to the board itself, `sent` the ones it
            // answered (airtime, out of the same PUBLIC share), and the rest is itemised by the policy's caps
            // (REPLIED_RECENTLY, TOO_SOON) and the transport's gates (NOT_SET_UP, DEDICATED, NO_AIR, NAK…).
            .put("autoReplyHeard", snap.meshtastic.autoReplyHeard)
            .put("autoReplySent", snap.meshtastic.autoReplySent)
            .put("autoReplyRefusedByReason", JSONObject(snap.meshtastic.autoReplyRefusedByReason))

    /**
     * Dumps the DM ratchet's per-peer state and, with `--es reset <peerNodeId>`, forces a session reset
     * past the heuristic that normally guards it.
     *
     * Both halves exist because every gate in the recovery path returns **silently**. A peer we hold no
     * prekey for, a peer whose reset floor has not elapsed, and a peer whose heuristic simply has not
     * counted three distinct failures yet all present identically from outside — as a session that does
     * not heal — and they need opposite remedies. The dump names which one it is; the force is the escape
     * hatch for a pair that wedged before a fix shipped, since the recovery path only runs when the
     * heuristic fires and a stuck pair may not be able to produce countable failures at all.
     */
    private suspend fun handleRatchet(intent: Intent): JSONObject {
        val forced = intent.getStringExtra(EXTRA_RESET_PEER)?.takeIf { it.isNotBlank() }?.trim()
        val reply = reply("ok", if (forced == null) "ratchet state" else "reset requested for $forced")
        if (forced != null) {
            val declined = mesh.forceRatchetReset(forced)
            reply.put("forcedReset", forced).put("declined", declined ?: JSONObject.NULL)
        }
        val peers = JSONArray()
        mesh.ratchetState().forEach { p ->
            peers.put(
                JSONObject()
                    .put("peer", p.peerId)
                    .put("name", p.name)
                    // The X3DH inputs. Any false/null here and a reset from THIS side is impossible,
                    // whatever the heuristic decides — the peer's profile has to land first.
                    .put("capRatchet", p.capRatchet)
                    .put("peerPrekeyId", p.peerPrekeyId ?: JSONObject.NULL)
                    .put("peerPrekeyPinned", p.peerPrekeyPinned)
                    // `hasSession` without `confirmed` is a session the scope table will not export, so
                    // the thread also reads "Not covered by relays yet" while looking otherwise healthy.
                    .put("hasSession", p.hasSession)
                    .put("confirmed", p.confirmed)
                    .put("sendEpoch", p.sendEpoch)
                    // The per-peer floor's anchor: a recent value is why an otherwise-eligible reset is
                    // not being sent, and is exactly when `--es reset` is the right tool.
                    .put("lastResetSentAt", p.lastResetSentAt)
                    // Era forensics: two devices whose `rootHash` disagree are in different eras, and
                    // `establishedAt` says whose is older. `localEpochs` is the EPOCH_GONE ground truth —
                    // each entry is one of OUR epoch privs as "epoch@createdAt", so a peer sealing against
                    // an epoch number missing here (or one whose createdAt predates the current era) is the
                    // diagnosis in one line.
                    .put("establishedAt", p.establishedAt)
                    .put("weAreInitiator", p.weAreInitiator)
                    .put("highestPeAcked", p.highestPeAcked)
                    .put("rootHash", p.rootHash ?: JSONObject.NULL)
                    .put("prevRootExpiresAt", p.prevRootExpiresAt)
                    .put("hasPeerInitAnchor", p.hasPeerInitAnchor)
                    .put("localEpochs", JSONArray(p.localEpochs.map { (epoch, at) -> "$epoch@$at" })),
            )
        }
        return reply.put("peers", peers)
    }

    /**
     * Drives the contact-card flow (docs/CONTACT_CARD.md) on a locked lab device: `--ez card true` mints
     * and prints this device's link, `--es import '<link>'` previews + imports one (single-quoted — the
     * shell splits on spaces), and no extras dumps the intro driver's pending/grace sets plus counters.
     */
    private suspend fun handleIntro(intent: Intent): JSONObject {
        val result = JSONObject().put("status", "ok")
        if (intent.getBooleanExtra(EXTRA_CARD, false)) {
            val minted = contactCards.mint()
            result.put("url", minted.url).put("schemeUrl", minted.schemeUrl)
        }
        intent.getStringExtra(EXTRA_IMPORT)?.takeIf { it.isNotBlank() }?.let { text ->
            val preview =
                contactImporter.preview(
                    app.getknit.knit.mesh.crypto.ContactCard
                        .parse(text),
                )
            result.put("preview", preview.toString())
            if (preview is app.getknit.knit.contacts.ContactImporter.Preview.Ready) {
                contactImporter.import(preview, unblock = false)
                result.put("imported", preview.nodeId)
            }
        }
        return result
            .put("pending", JSONArray(settings.pendingIntros.first().toList()))
            .put("grace", JSONArray(settings.introGrace.first().toList()))
            .put("counters", metricsJson(metrics.snapshot()))
    }

    /**
     * Configures and inspects the Internet (spool) plane — the only way to drive it on a locked lab
     * device, since there is no spool-list editor in the UI yet. With no extras it just dumps state.
     *
     * `--es url <ws(s)://host:port/spool/v1[?k=token]>` adds a spool, `--es drop <url>` removes one, and
     * `--ez on <true|false>` flips the global opt-in. `--es park <url>` / `--es unpark <url>` flip one
     * relay's own switch, which is how a soak run takes a single spool out of the rotation without losing
     * its bearer token — the dump's `disabled` array reports the result, and the parked URL leaves
     * `spools[]` within one `ScopeSync` reconcile tick as its worker stops. Debug builds accept plain
     * `ws://` (a LAN daemon terminates no TLS of its own); release refuses it at dial time regardless of
     * what is stored here.
     *
     * The dump's `local` and `spool` counts per scope are the convergence oracle: they agree once the
     * heal loop has settled, exactly like `liveFingerprint` parity for mesh custody.
     */

    private suspend fun handleSpool(intent: Intent): JSONObject {
        val tickets = SpoolTickets(settings)
        val ticketText = intent.getStringExtra(EXTRA_TICKET)?.takeIf { it.isNotBlank() }
        val keyText = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.isNotBlank() }?.trim()
        // With `ticket` or `key`, `url` names the relay they are for and is not added: the relay is configured already,
        // and re-adding a URL typed without its `?k=` token would make a second, untokened row for the same host.
        val ticketNote = ticketText?.let { importTicket(tickets, intent.getStringExtra(EXTRA_URL)?.trim(), it) }
        if (ticketNote?.optString("status") == "error") return ticketNote
        val keyNote = keyText?.let { holdKey(intent.getStringExtra(EXTRA_URL)?.trim(), intent.getStringExtra(EXTRA_ISSUER)?.trim(), it) }
        if (keyNote?.optString("status") == "error") return keyNote
        val freeIssuerText = intent.getStringExtra(EXTRA_FREE_ISSUER)?.takeIf { it.isNotBlank() }?.trim()
        val freeConsentText = intent.getStringExtra(EXTRA_FREE_CONSENT)?.takeIf { it.isNotBlank() }?.trim()
        val freeNote = free(intent.getStringExtra(EXTRA_URL)?.trim(), freeIssuerText, freeConsentText)
        if (freeNote?.optString("status") == "error") return freeNote
        val mineNote = if (intent.hasExtra(EXTRA_MINE)) mineStamp(intent.getIntExtra(EXTRA_MINE, 0)) else null
        if (ticketText == null && keyText == null && freeNote == null) {
            intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let { settings.addSpoolUrl(it.trim()) }
        }
        // The person's Move it here (docs/VOICE_CALLS.md C-7.6-44), for a phone whose screen a trial does not drive.
        val moveNote =
            intent.getStringExtra(EXTRA_MOVE)?.takeIf { it.isNotBlank() }?.let { url ->
                val host = SpoolUrl.hostname(url.trim()) ?: return reply("error", "the url names no host")
                moveJson(mesh.moveAnywhereHere(host))
            }
        intent.getStringExtra(EXTRA_DROP)?.takeIf { it.isNotBlank() }?.let { dropped ->
            settings.removeSpoolUrl(dropped.trim())
            // As the relays screen's Remove does (C-7.6-29): the key and the ticket go unless another row shares the host.
            val host = SpoolUrl.hostname(dropped.trim())
            if (host != null && settings.spoolUrls.first().none { SpoolUrl.hostname(it) == host }) ticketKeys.forgetHost(host)
        }
        intent.getStringExtra(EXTRA_PARK)?.takeIf { it.isNotBlank() }?.let { settings.setSpoolUrlEnabled(it.trim(), false) }
        intent.getStringExtra(EXTRA_UNPARK)?.takeIf { it.isNotBlank() }?.let { settings.setSpoolUrlEnabled(it.trim(), true) }
        if (intent.hasExtra(EXTRA_ON)) settings.setSpoolEnabled(intent.getBooleanExtra(EXTRA_ON, false))

        val spools = JSONArray()
        val freeIssuers = ticketKeys.freeIssuers.first()
        val freeConsent = ticketKeys.freeConsent.first()
        val freeStates = ticketKeys.freeStates.first()
        val heldKeys = ticketKeys.keys.first()
        val asking = mesh.freeTicketAsking()
        mesh.spoolStatus().forEach { spool ->
            val scopes = JSONArray()
            spool.scopes.forEach { scope ->
                scopes.put(
                    JSONObject()
                        .put("scope", scope.scopeHex)
                        // A DM peer's node id or, for a group scope, the group id.
                        .put("of", scope.label)
                        .put("local", scope.localCount)
                        .put("spool", scope.spoolCount)
                        .put("converged", scope.converged)
                        .put("invalid", scope.invalidCount)
                        // How much of `local` is the §9.6 accounted band rather than custody — blobs the
                        // spool still holds that our custody has aged out. Counted in `local` on purpose,
                        // so `local == spool` keeps meaning converged.
                        .put("accounted", scope.accountedCount)
                        // A retiring scope is drained, never refilled, so local > spool for its whole
                        // drain window — expected, not divergence.
                        .put("retiring", scope.retiring)
                        // Millis since this scope's own peer last put a recent frame into it, or -1 if it
                        // never has. The ONLY field here that says anything about the peer: everything
                        // above is equally true of a scope whose peer has been switched off for weeks,
                        // because a scope is derived from the pairwise root and stays subscribed and
                        // converged regardless (ADR 2026-09.2ajk).
                        .put("peerSeenAgoMs", scope.peerSeenAt?.let { System.currentTimeMillis() - it } ?: -1L),
                )
            }
            spools.put(
                JSONObject()
                    .put("url", spool.url)
                    // A completed hello on an open socket — never a socket that merely exists, which a
                    // validated-but-dead Wi-Fi hands out for the whole connect timeout (work item 50).
                    .put("connected", spool.connected)
                    .put("powBits", spool.powBits)
                    .put("lastError", spool.lastError ?: JSONObject.NULL)
                    // Sessions in a row that never reached a hello; 0 once one does. Twelve of these under
                    // `unreachable` is the dead-route signature, one is a missed reconnect.
                    .put("dialFailures", spool.dialFailures)
                    // The reconnect curve's oracle: poll this and the gaps between readings are the waits the
                    // worker actually took (dialFailures alone says how many, never how far apart).
                    .put("lastDialAt", spool.lastDialAt ?: JSONObject.NULL)
                    .put("lastDialAgeMs", spool.lastDialAt?.let { System.currentTimeMillis() - it } ?: JSONObject.NULL)
                    // null ⇒ this spool advertised no attachment support at all (spec §7.3), which is
                    // also what makes the UI mark a photo "nearby only" — worth being able to confirm
                    // from the bridge when a field test sees that marker.
                    .put("maxAttachBytes", spool.maxAttachBytes ?: JSONObject.NULL)
                    // The commons rooms this spool advertised in its HELLO (§7.4): how many, and each joined one's own
                    // name and pinned bounds by thread id — never an id, and a tag only as the room matched it.
                    .put("roomsAdvertised", spool.adverts?.count ?: 0)
                    .put(
                        "rooms",
                        JSONObject().apply {
                            spool.rooms.forEach { (room, info) ->
                                put(
                                    room,
                                    JSONObject()
                                        .put("name", info.name ?: JSONObject.NULL)
                                        .put("maxFrames", info.maxFrames)
                                        .put("ttlMs", info.ttlMs)
                                        .put("maxBlob", info.maxBlob)
                                        .put("attach", info.attach),
                                )
                            }
                        },
                    ).put("scopes", scopes)
                    // VOICE_CALLS.md §2.6: whether this spool takes tickets, how many STUN URIs its hello gave a call,
                    // and where this connection and the store stand with a ticket. Never the ticket itself.
                    .put("ent", spool.ent)
                    .put("stun", spool.stun.size)
                    // §2.1 and §2.7: whether its hello offered TURN, and its cast bounds (null when it fans out none).
                    .put("turn", spool.turn)
                    .put(
                        "cast",
                        spool.cast?.let { JSONObject().put("maxBytes", it.maxBytes).put("rate", it.rate) } ?: JSONObject.NULL,
                    )
                    // §7.8: its sig bounds (null when it fans out none), which every phone may send on with no ticket.
                    .put(
                        "sig",
                        spool.sig?.let { JSONObject().put("maxBytes", it.maxBytes).put("rate", it.rate) } ?: JSONObject.NULL,
                    ).put("ticket", ticketJson(tickets, spool))
                    .put("ticketKey", ticketKeyJson(tickets, spool.url))
                    // The `fl` bits this connection's hello gates behind a ticket (`ent` with no `entGates` reads 9;
                    // no `ent`, 0), and whether it takes this phone's uploads (C-7.6-24): `open`,
                    // `locked:<missing|expired|flags|pending>` or `spent`. Facts about the live connection.
                    .put("entGates", spool.entGates)
                    .put("uploads", spool.uploads.word)
                    // C-7.6-32: the most chunks one attachment of ours may have here under the live files ticket's `am`,
                    // or null for no limit beyond `maxAttachBytes`.
                    .put("uploadLimitChunks", spool.uploadLimitChunks ?: JSONObject.NULL)
                    // C-7.6-33 to C-7.6-38: this host's free issuer (null for none) and where its free key stands —
                    // `none`, `asking`, `held`, `declined` or `backoff:<epoch ms>`.
                    .put("freeIssuer", SpoolUrl.hostname(spool.url)?.let(freeIssuers::get) ?: JSONObject.NULL)
                    .put("freeKey", freeKeyWord(SpoolUrl.hostname(spool.url), heldKeys, freeConsent, freeStates, asking)),
            )
        }
        return JSONObject()
            .put("status", "ok")
            .put("ticketImport", ticketNote?.optString("message") ?: JSONObject.NULL)
            .put("keyImport", keyNote?.optString("message") ?: JSONObject.NULL)
            .put("move", moveNote ?: JSONObject.NULL)
            .put("free", freeNote?.optString("message") ?: JSONObject.NULL)
            .put("mine", mineNote ?: JSONObject.NULL)
            .put("enabled", settings.spoolEnabled.first())
            .put("configured", JSONArray(settings.spoolUrls.first().toList()))
            .put("disabled", JSONArray(settings.disabledSpoolUrls.first().toList()))
            .put("spools", spools)
            .put("counters", metricsJson(metrics.snapshot()))
    }

    /**
     * `…debug.SPOOL --es url <spoolUrl> --es ticket <knit-ticket:v1:…|clear>` (experimental, docs/VOICE_CALLS.md §2.6,
     * ADR 2026-10.xkp4): holds the ticket for the URL's host, refusing one whose `aud` is another host, or clears it.
     * The stand-in for a storefront. A live connection presents an imported ticket at once. The reply names the host
     * and `exp`, never the ticket.
     */
    private suspend fun importTicket(
        tickets: SpoolTickets,
        url: String?,
        text: String,
    ): JSONObject {
        if (url.isNullOrBlank()) return reply("error", "a ticket needs --es url <the relay it is for>")
        if (text.trim() == "clear") {
            tickets.clear(url)
            return reply("ok", "ticket cleared for ${SpoolUrl.hostname(url)}")
        }
        return when (val result = tickets.import(url, text)) {
            is SpoolTickets.Import.Held -> {
                reply("ok", "ticket held for ${result.ticket.claims.aud} until ${result.ticket.claims.exp}")
            }

            SpoolTickets.Import.Unreadable -> {
                reply("error", "not a knit-ticket:v1 ticket this build can read")
            }

            SpoolTickets.Import.NoHost -> {
                reply("error", "the url names no host")
            }

            is SpoolTickets.Import.ForAnotherSpool -> {
                reply("error", "the ticket is for ${result.aud}, not ${result.host}; never held")
            }
        }
    }

    /**
     * `…debug.SPOOL --es url <spoolUrl> --es issuer <origin> --es key <kak1_…|clear>` (experimental, ADR 2026-10.3sk7):
     * holds a customer key for the URL's host without an invite — the same store write the invite sheet makes, so a
     * different key resets the refresher's state and the same one does not — or, with `clear`, forgets the key, its
     * state and the host's ticket, as removing the relay does. The issuer must pass the invite's origin rule (`http://`
     * is a debug build's). The reply names the host and the issuer, never the key.
     */
    private suspend fun holdKey(
        url: String?,
        issuer: String?,
        key: String,
    ): JSONObject {
        if (url.isNullOrBlank()) return reply("error", "a key needs --es url <the relay it is for>")
        val host = SpoolUrl.hostname(url) ?: return reply("error", "the url names no host")
        if (key == "clear") {
            ticketKeys.forgetHost(host)
            return reply("ok", "key, its state and the ticket forgotten for $host")
        }
        if (issuer.isNullOrBlank() || !IssuerOrigin.isAcceptable(issuer, allowCleartext = true)) {
            return reply("error", "a key needs --es issuer <https://host[:port]>, a bare origin")
        }
        if (!CustomerKey.isWellFormed(key)) return reply("error", "not a kak1_ customer key")
        ticketKeys.putKey(host, HeldKey(issuer, key))
        return reply("ok", "key held for $host, traded with ${IssuerOrigin.host(issuer)}")
    }

    /**
     * `…debug.SPOOL --es url <spoolUrl> --es freeIssuer <origin|clear>` sets (or returns to the seeded one) the free
     * issuer for the URL's host (docs/VOICE_CALLS.md C-7.6-33): the lab's door, since only the seeded relay ships with
     * one. `--es freeConsent <allow|decline|clear>` answers the free-ticket notice for that host (C-7.6-34); `decline`
     * forgets a free key held there, as the relay row's switch does. Null when neither extra was given.
     */
    private suspend fun free(
        url: String?,
        issuer: String?,
        consent: String?,
    ): JSONObject? {
        if (issuer == null && consent == null) return null
        if (url.isNullOrBlank()) return reply("error", "freeIssuer and freeConsent need --es url <the relay they are for>")
        val host = SpoolUrl.hostname(url) ?: return reply("error", "the url names no host")
        val notes = mutableListOf<String>()
        if (issuer != null) {
            val origin = issuer.takeUnless { it == "clear" }
            if (origin != null && !IssuerOrigin.isAcceptable(origin, allowCleartext = true)) {
                return reply("error", "a free issuer is a bare origin, https://host[:port]")
            }
            ticketKeys.setFreeIssuer(host, origin)
            notes += origin?.let { "free issuer for $host is ${IssuerOrigin.host(it)}" } ?: "free issuer for $host back to the seeded one"
        }
        if (consent != null) {
            val allowed =
                when (consent) {
                    "allow" -> true
                    "decline" -> false
                    "clear" -> null
                    else -> return reply("error", "freeConsent is allow, decline or clear")
                }
            ticketKeys.setFreeConsent(host, allowed)
            notes += "the free-ticket notice for $host: ${allowed?.let { if (it) "allowed" else "declined" } ?: "will ask again"}"
        }
        return reply("ok", notes.joinToString("; "))
    }

    /** Times one account stamp of [bits] on this phone (C-7.6-36) with a fresh nonce and today's UTC day; no network. */
    private suspend fun mineStamp(bits: Int): JSONObject {
        val nonce = ByteArray(AccountStamp.NONCE_BYTES).also(SecureRandom()::nextBytes)
        val day = SpoolPow.utcDay(System.currentTimeMillis())
        val started = SystemClock.elapsedRealtime()
        val n = withContext(Dispatchers.Default) { AccountStamp.mine(day, nonce, bits.coerceIn(0, AccountStamp.MAX_BITS), 1L shl 34) }
        return JSONObject()
            .put("bits", bits)
            .put("n", n ?: JSONObject.NULL)
            .put("ms", SystemClock.elapsedRealtime() - started)
    }

    /** The bridge's word for where [host]'s free key stands: `none`, `asking`, `held`, `declined` or `backoff:<ms>`. */
    private fun freeKeyWord(
        host: String?,
        keys: Map<String, HeldKey>,
        consent: Map<String, Boolean>,
        states: Map<String, FreeState>,
        asking: Set<String>,
    ): String {
        if (host == null) return "none"
        val backoff = states[host]?.notBefore?.takeIf { it > System.currentTimeMillis() }
        return when {
            host in asking -> "asking"
            keys[host]?.free == true -> "held"
            consent[host] == false -> "declined"
            backoff != null -> "backoff:$backoff"
            else -> "none"
        }
    }

    /**
     * What the refresher knows about [url]'s host (ADR 2026-10.3sk7): the issuer origin, whether a key is held, the last
     * answer, the account's `endsAt` and the next ask — never the key or the ticket. `nextAskAt` is null for a parked
     * (`unknown-key`) key, and for a free one unless an upload waits there with no ticket to present; it is when the
     * refresher would ask with the plane running and a route up.
     */
    private suspend fun ticketKeyJson(
        tickets: SpoolTickets,
        url: String,
    ): JSONObject {
        val host = SpoolUrl.hostname(url) ?: return JSONObject().put("held", false)
        val key = ticketKeys.keys.first()[host] ?: return JSONObject().put("held", false)
        val state = ticketKeys.keyStates.first()[host] ?: KeyState.FRESH
        val presentable = tickets.presentable(url)
        // A free key never asks on the margin, only while an upload waits (C-7.6-27 amended): the refresher's own rule.
        val next =
            if (key.free) {
                TicketRefreshPolicy.freeDueAt(state, mesh.freeTicketWaiting(host), presentable != null)
            } else {
                TicketRefreshPolicy.dueAt(state, presentable?.claims?.exp)
            }
        return JSONObject()
            .put("held", true)
            .put("free", key.free)
            .put("issuer", key.issuer)
            .put("answer", state.answer?.word ?: JSONObject.NULL)
            .put("endsAt", state.endsAt ?: JSONObject.NULL)
            .put("failures", state.failures)
            .put("nextAskAt", next ?: JSONObject.NULL)
            .put("nextAskInMs", next?.let { it - System.currentTimeMillis() } ?: JSONObject.NULL)
            // Device binding (ADR 2026-10.wk3m): when a parked account can move here, and how many of this phone's pairs
            // a 409 has refused — a count, never their ids.
            .put("movableAt", state.movableAt ?: JSONObject.NULL)
            .put("parkedUnsigned", state.parkedUnsigned)
            .put("parkedPairs", state.parkedBy.size)
    }

    /** A move's outcome as words and a time, for the reply's `move`. Names nothing secret. */
    private fun moveJson(result: MoveResult): JSONObject =
        when (result) {
            MoveResult.Moved -> JSONObject().put("result", "moved")
            is MoveResult.TooSoon -> JSONObject().put("result", "too-soon").put("movableAt", result.movableAt ?: JSONObject.NULL)
            MoveResult.UnknownKey -> JSONObject().put("result", "unknown-key")
            MoveResult.Ended -> JSONObject().put("result", "ended")
            is MoveResult.Failed -> JSONObject().put("result", "failed").put("reason", result.reason)
            MoveResult.Unavailable -> JSONObject().put("result", "unavailable")
        }

    /** This connection's ticket state beside what the store holds for the host: claims and verdicts, never bytes. */
    private suspend fun ticketJson(
        tickets: SpoolTickets,
        spool: SpoolStatus,
    ): JSONObject {
        val held = tickets.view(spool.url)
        val state = spool.ticket
        // The grants, from the accepted claims' `fl` when the connection has them, else from the held ticket's.
        val fl = (state as? SpoolTicketState.Accepted)?.fl
        return JSONObject()
            .put(
                "state",
                when (state) {
                    SpoolTicketState.None -> "none"
                    SpoolTicketState.Pending -> "pending"
                    is SpoolTicketState.Accepted -> "accepted"
                    is SpoolTicketState.Refused -> "refused"
                },
            ).put("exp", (state as? SpoolTicketState.Accepted)?.exp ?: held?.exp ?: JSONObject.NULL)
            .put("turn", fl?.let { it and SpoolTicket.FL_TURN != 0L } ?: held?.grantsTurn ?: JSONObject.NULL)
            .put("files", fl?.let { it and SpoolTicket.FL_FILES != 0L } ?: held?.grantsFiles ?: JSONObject.NULL)
            .put("cast", fl?.let { it and SpoolTicket.FL_CAST != 0L } ?: held?.grantsCast ?: JSONObject.NULL)
            .put("reason", (state as? SpoolTicketState.Refused)?.reason ?: JSONObject.NULL)
            // The store's side: whether a ticket is held for this host at all, and the verdict its last answer left
            // (`retry:early`, or the refusal word that keeps it from ever going out again).
            .put("held", held != null)
            .put("verdict", held?.verdict ?: JSONObject.NULL)
    }

    /**
     * Joins, leaves and inspects the **commons** (docs/SPOOL_PROTOCOL.md §7.4) — the relay row's Join/Leave,
     * for a locked lab device. `--es url <spoolUrl> --es invite <knit-commons:v1:…>` joins the room that
     * relay runs (the relay must already be configured, `…debug.SPOOL --es url`); `--es leave <c-…>` leaves
     * one; no extras dumps every joined room with its members. `…debug.SEND --es conv <c-…>` posts into it
     * and `…debug.STATE --es conv <c-…>` reads it back; `…debug.SPOOL` shows its scope under the bound relay.
     */
    private suspend fun handleCommons(intent: Intent): JSONObject {
        // A `-Pcommons=false` debug build hands the mesh no store, so a row written here would never be
        // subscribed — refuse rather than leave a room that looks joined and stays silent.
        if (!BuildConfig.COMMONS) return reply("error", "the commons is hidden in this build (BuildConfig.COMMONS)")
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.trim()
        val invite = intent.getStringExtra("invite")?.takeIf { it.isNotBlank() }?.trim()
        if (url != null && invite != null) {
            val secret = CommonsInvite.decode(invite) ?: return reply("error", "not a commons invite")
            // The name the relay advertises for this room's tag (C-7.4-17), as the relay row's Join takes it.
            val name =
                mesh
                    .spoolStatus()
                    .firstOrNull { it.url == url }
                    ?.adverts
                    ?.advertForScope(ScopeCrypto.commonsScopeId(secret))
                    ?.info
                    ?.name
            val id = commons.join(url, secret, name, System.currentTimeMillis())
            mesh.refreshRelays()
            return reply("ok", "joined $id at $url").put("conversation", id)
        }
        intent.getStringExtra("leave")?.takeIf { it.isNotBlank() }?.let {
            commons.leave(it.trim())
            mesh.refreshRelays()
            return reply("ok", "left $it")
        }
        // `--es react <messageId> --es emoji <e> --es room <c-…>`: the room's own reaction path (C-7.4-13), a toggle.
        intent.getStringExtra("react")?.takeIf { it.isNotBlank() }?.let { messageId ->
            val room = intent.getStringExtra("room")?.trim() ?: return reply("error", "react needs --es room <c-…>")
            val emoji = intent.getStringExtra("emoji") ?: return reply("error", "react needs --es emoji <e>")
            val sent = mesh.sendCommonsReaction(room, messageId.trim(), emoji)
            return reply(if (sent) "ok" else "error", if (sent) "reacted on $messageId" else "not a joined room, or the emoji was refused")
        }
        val spools = mesh.spoolStatus()
        val rooms = JSONArray()
        commons.observeAll().first().forEach { room ->
            val relay = spools.firstOrNull { it.url == room.spoolUrl }
            rooms.put(
                JSONObject()
                    .put("conversation", room.conversationId)
                    .put("url", room.spoolUrl)
                    .put("name", room.name ?: JSONObject.NULL)
                    .put("joinedAt", room.joinedAt)
                    .put("members", JSONArray(commons.members(room.conversationId)))
                    // What the room's relay said: whether it takes this room's attachments (S-7.4-5), null while it is
                    // down or does not run the room; and whether it runs this room at all (a rotated invite, or no room).
                    .put("attach", relay?.rooms?.get(room.conversationId)?.attach ?: JSONObject.NULL)
                    .put("stale", relay?.staleCommons?.contains(room.conversationId) == true),
            )
        }
        return JSONObject().put("status", "ok").put("rooms", rooms)
    }

    /**
     * Configures and inspects the LoRa (Meshtastic) plane — the only way to drive it on a locked lab
     * device. `--es address <MAC>` + `--es name <n>` binds a bonded board, `--ei channel <idx>` sets the
     * channel index, `--ez on <true|false>` flips the switch, `--ez dms <true|false>` the private-messages
     * switch (ADR 039) and `--ez room <true|false>` the Meshtastic room (ADR 2026-09.26q3, on by default —
     * off, slot 0 is dropped where it lands, so `meshPostRefusedByReason.ROOM_OFF` climbs and
     * `meshPostHeard` does not); no extras just dumps status. The reply's
     * `state`/`heard`/counters are the field oracle for the two-board range trial.
     */
    private suspend fun handleLora(intent: Intent): JSONObject {
        val address = intent.getStringExtra(EXTRA_ADDRESS)?.takeIf { it.isNotBlank() }
        if (address != null) settings.setLoraDevice(address.trim(), intent.getStringExtra("name")?.trim() ?: address.trim())
        if (intent.hasExtra("channel")) settings.setLoraChannelIndex(intent.getIntExtra("channel", 0))
        if (intent.hasExtra(EXTRA_ON)) settings.setLoraEnabled(intent.getBooleanExtra(EXTRA_ON, false))
        if (intent.hasExtra("dms")) settings.setLoraDmEnabled(intent.getBooleanExtra("dms", true))
        if (intent.hasExtra("bridge")) settings.setLoraBridgeEnabled(intent.getBooleanExtra("bridge", true))
        if (intent.hasExtra("room")) settings.setLoraRoomEnabled(intent.getBooleanExtra("room", true))

        val status = lora.status.value
        return JSONObject()
            .put("status", "ok")
            .put("enabled", settings.loraEnabled.first())
            .put("dms", settings.loraDmEnabled.first())
            .put("bridge", settings.loraBridgeEnabled.first())
            .put("room", settings.loraRoomEnabled.first())
            .put("address", settings.loraDeviceAddress.first() ?: JSONObject.NULL)
            .put("channel", settings.loraChannelIndex.first())
            .put("state", status.state::class.simpleName)
            .put("boardNodeNum", status.boardNodeNum?.let { "!%08x".format(it.toInt()) } ?: JSONObject.NULL)
            .put("snr", status.lastSnr?.toDouble() ?: JSONObject.NULL)
            .put("rssi", status.lastRssi ?: JSONObject.NULL)
            .put("queueFree", status.queueFree ?: JSONObject.NULL)
            .put("queued", status.queued)
            .put("heard", status.heard) // frame authors, relayed and backfilled ones included
            .put("boardsHeard", status.boardsHeard) // radios on air — "how many boards can I hear"
            // The bridge's own oracle (ADR 044): which board here speaks for the pocket, what the radio is,
            // and how much of the hour's airtime allowance has gone — the numbers a two-pocket trial reads.
            .put("role", status.role.name)
            // The election's inputs, not just its verdict: "why is this board listening?" is otherwise
            // unanswerable in the field, which is exactly how the reachable-vs-linked bug survived review.
            .put("pocketLinks", status.pocketLinks)
            .put("gatewaysHeard", status.gatewaysHeard)
            // pocketSightings > pocketLinks is the shape of the field failure: peers heard but not linked.
            .put("pocketSightings", status.pocketSightings)
            // Peers a connected spool covers: DM-form frames to them stay off the air (ADR 2026-09.y5f3).
            .put("internetCovered", status.internetCovered)
            .put("radio", status.airtime?.let { "${it.region}/${it.preset}${if (it.known) "" else " (assumed)"}" } ?: JSONObject.NULL)
            // ADR 067: true means the board is on its own RF slot, so the budgets below are off the
            // politeness ceiling and bounded only by the region's duty cycle.
            .put("dedicated", status.airtime?.dedicated ?: JSONObject.NULL)
            // ADR 2026-09.mhs5: whether this board's firmware signs what we hand it, and so whether the budget charges
            // for that signature and the codec pads past its cliff. Without it neither is observable here.
            .put("signing", status.airtime?.signing ?: JSONObject.NULL)
            .put(
                "airtime",
                status.airtime?.let { air ->
                    JSONObject()
                        // The board's own measurement beside the governor's estimate. Different quantities on
                        // purpose — an hour of everything this radio sent, against fifteen minutes of what
                        // Knit handed it — so the gap is readable instead of being mistaken for a leak.
                        .put("boardAirUtilTxPercent", status.boardAir?.airUtilTxPercent?.toDouble() ?: JSONObject.NULL)
                        .put("boardChannelUtilPercent", status.boardAir?.channelUtilPercent?.toDouble() ?: JSONObject.NULL)
                        .put("liveMs", air.liveUsedMs)
                        .put("liveBudgetMs", air.liveBudgetMs)
                        .put("bridgeMs", air.bridgeUsedMs)
                        .put("bridgeBudgetMs", air.bridgeBudgetMs)
                        .put("bootstrapMs", air.bootstrapUsedMs)
                        .put("bootstrapBudgetMs", air.bootstrapBudgetMs)
                        .put("publicMs", air.publicUsedMs)
                        .put("publicBudgetMs", air.publicBudgetMs)
                        // No budget of its own — bounded by the Trickle timer and the window total (ADR
                        // 2026-09.7c8n) — so what matters here is that it is no longer inside bridgeMs.
                        .put("gossipMs", air.gossipUsedMs)
                        .put("totalMs", air.totalUsedMs)
                } ?: JSONObject.NULL,
            ).put("counters", metricsJson(metrics.snapshot()))
    }

    /**
     * Sends a raw UTF-8 payload straight to the board on the configured channel (bypassing the frame
     * codec), so a board's LoRa transmission can be confirmed from the other end (`meshtastic --noproto`)
     * without the whole mesh. Requires the plane enabled and the board Ready.
     *
     * `--ei hop <n>` sets `MeshPacket.hop_limit` explicitly instead of leaving it out. The production path
     * omits the field, on the documented assumption that the firmware fills in the node's configured
     * default; on 2.8 it does not, and every Knit packet reaches the air with `hop_limit = 0` — unrelayable
     * by the stock nodes ADR 045 borrows hops from. This is the knob that tells a firmware that will not
     * relay a secondary-channel packet apart from a packet that was never relayable to begin with.
     */
    private suspend fun handleLoraTx(intent: Intent): JSONObject {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return reply("error", "missing --es text")
        val channel = settings.loraChannelIndex.first()
        val hop = if (intent.hasExtra("hop")) intent.getIntExtra("hop", 0) else null
        val result = loraLink.send(text.encodeToByteArray(), channel, hopLimit = hop)
        return reply("ok", "sent")
            .put("result", result::class.simpleName)
            .put("channel", channel)
            .put("hopLimit", hop ?: JSONObject.NULL)
    }

    /**
     * Sets the connected board up for Knit over the Meshtastic admin API (the headless equivalent of the
     * settings screen's one setup button), and — on success — binds the plane to it. Requires the board
     * Ready.
     *
     * `--es mode restore` puts the board back the way it was instead. This is the two-board oracle for the
     * ADR 045 trial, so it persists exactly what the settings screen would.
     */
    private suspend fun handleLoraProv(intent: Intent): JSONObject {
        val mode =
            when (intent.getStringExtra(EXTRA_MODE)?.lowercase()) {
                "restore" -> ProvisionMode.Restore

                // ADR 067's debug-only setup, so that a dedicated-frequency trial is drivable headlessly
                // exactly like the shared-frequency one.
                "dedicated" -> ProvisionMode.SetupDedicated

                // Its reverse: back onto the shared slot with the setup kept, so a trial can flip a board
                // between the two bargains without a restore between.
                "shared" -> ProvisionMode.SetupShared

                else -> ProvisionMode.Setup
            }
        val recorded = settings.loraBoardSetup.first()
        val result =
            lora.provisionKnitChannel(
                mode,
                recorded?.let {
                    BoardSettings(
                        nodeInfoSecs = it.nodeInfoSecs,
                        positionSecs = it.positionSecs,
                        smartPosition = it.smartPosition,
                        telemetrySecs = it.telemetrySecs,
                        rebroadcastMode = it.rebroadcastMode,
                        owner =
                            if (it.longName.isEmpty() && it.shortName.isEmpty()) {
                                null
                            } else {
                                BoardOwner(it.longName, it.shortName)
                            },
                        channelNum = it.channelNum,
                    )
                },
            )
        val address = settings.loraDeviceAddress.first()
        when (result) {
            is app.getknit.knit.mesh.lora.ProvisionResult.Provisioned -> {
                settings.setLoraChannelIndex(result.index)
                val previous = result.previous
                if (previous != null && address != null) {
                    settings.setLoraBoardSetup(
                        KnitBoardSetup(
                            address = address,
                            nodeInfoSecs = previous.nodeInfoSecs,
                            positionSecs = previous.positionSecs,
                            smartPosition = previous.smartPosition,
                            telemetrySecs = previous.telemetrySecs,
                            rebroadcastMode = previous.rebroadcastMode,
                            longName = previous.owner?.longName.orEmpty(),
                            shortName = previous.owner?.shortName.orEmpty(),
                            channelNum = previous.channelNum,
                        ),
                    )
                }
            }

            // Restoring leaves no Knit channel on the board, so the plane goes off with it — same as the UI.
            app.getknit.knit.mesh.lora.ProvisionResult.Restored -> {
                settings.clearLoraBoardSetup()
                settings.setLoraEnabled(false)
            }

            else -> {
                // NoDedicatedSlot / Failed — nothing to persist; the reply below carries the outcome.
            }
        }
        val index =
            when (result) {
                is app.getknit.knit.mesh.lora.ProvisionResult.Provisioned -> result.index
                else -> -1
            }
        return reply("ok", "provision requested")
            .put("mode", mode.name)
            .put("result", result::class.simpleName)
            .put("channel", if (index >= 0) index else JSONObject.NULL)
            .put("state", lora.status.value.state::class.simpleName)
    }

    /**
     * Drives the **backup** half of Backup and restore (ADR 2026-09.6mj7, `docs/BACKUP_FORMAT.md`) on a locked
     * lab phone, where the document picker cannot be reached. No extras writes a backup under a fresh
     * recovery key to `files/backup-test.knitbackup` (`--es path <file>` for another) through the real
     * `BackupWriter` — the live SQLCipher database, the Keystore-wrapped identity, the settings — and replies
     * with the key, the manifest and the byte count. `--es verify <file> --es key <key>` runs the restore's
     * verification (`RestoreStager.stage`: decrypt, per-entry hashes, identity re-wrap, node id, database
     * open under the passphrase, settings parse) and then **discards** the staging, so nothing on the phone
     * changes and no restart is armed; the reply carries the manifest it proved. The write and the verify
     * are the two halves a device trial needs to see; applying a restore is the UI's job.
     */
    private suspend fun handleBackup(
        context: Context,
        intent: Intent,
    ): JSONObject {
        val verify = intent.getStringExtra("verify")
        if (verify != null) {
            val key =
                app.getknit.knit.data.backup.BackupKeys
                    .parse(intent.getStringExtra("key").orEmpty())
                    ?: return reply("error", "--es key must be the 30-digit recovery key")
            val file = File(verify)
            if (!file.exists()) return reply("error", "no such file: $verify")
            val started = System.currentTimeMillis()
            val manifest =
                try {
                    file.inputStream().buffered().use { restoreStager.stage(it, key) }
                } catch (e: app.getknit.knit.data.backup.BackupException) {
                    return reply("refused", "${e.problem}: ${e.message}")
                } finally {
                    // Never leave a READY behind: the next process start would apply it.
                    restoreStager.discard()
                }
            return reply("ok", "backup verified and staging discarded")
                .put("elapsedMs", System.currentTimeMillis() - started)
                .put("manifest", manifestJson(manifest))
        }
        val path = intent.getStringExtra("path") ?: File(context.filesDir, "backup-test.knitbackup").absolutePath
        val file = File(path)
        val key =
            app.getknit.knit.data.backup.BackupKeys
                .generate()
        val started = System.currentTimeMillis()
        var phases = 0
        val manifest =
            try {
                file.outputStream().buffered().use { out -> backupWriter.write(out, key) { _, _ -> phases++ } }
            } catch (e: app.getknit.knit.data.backup.BackupException) {
                file.delete()
                return reply("refused", "${e.problem}: ${e.message}")
            }
        return reply("ok", "backup written")
            .put("path", file.absolutePath)
            .put("bytes", file.length())
            .put("key", key)
            .put(
                "keyDisplay",
                app.getknit.knit.data.backup.BackupKeys
                    .display(key),
            ).put("elapsedMs", System.currentTimeMillis() - started)
            .put("progressCallbacks", phases)
            .put("manifest", manifestJson(manifest))
    }

    private fun manifestJson(manifest: app.getknit.knit.data.backup.BackupManifest): JSONObject =
        JSONObject()
            .put("v", manifest.v)
            .put("schemaVersion", manifest.schemaVersion)
            .put("appVersionCode", manifest.appVersionCode)
            .put("appVersionName", manifest.appVersionName)
            .put("createdAt", manifest.createdAt)
            .put("nodeId", manifest.nodeId)
            .put("displayName", manifest.displayName)
            .put(
                "entries",
                JSONArray().also { arr ->
                    manifest.entries.forEach { arr.put(JSONObject().put("name", it.name).put("size", it.size)) }
                },
            )

    private fun reply(
        status: String,
        message: String,
    ): JSONObject = JSONObject().put("status", status).put("message", message)

    /**
     * The scripted peer of a demo build's calls ([DemoCalls]; `-PseedDemo` / `-PdemoDirector` with calls wired) — the
     * other end the screen's own [CallManager] talks to over loopback, which `…debug.CALL` drives as it drives a real
     * call. `--es op`:
     *
     * - `ring --es peer <id> [--ez walkie true] [--ez open true]`: the peer dials this phone; `open` also brings the call
     *   screen up over the app once it alerts (an unlocked phone in use shows a heads-up instead), without answering.
     * - `say --es line <name>`: the peer in the connected call says a bundled line (`demo/voice/<theme>/<name>.pcm`) —
     *   into a live call's captions, or as a walkie burst. In the background: poll `…debug.CALL` / `…debug.STT`.
     * - `talk --es line <name>`: this phone's walkie burst says that line, the talk button held as long as it lasts.
     * - `mic --es line <name>`: the next burst this phone records says that line in place of the microphone, however
     *   long a finger holds the talk button (a film of the real gesture).
     * - `hangup`: the peer hangs up.
     * - `answer --el ms <n>`: how long a peer this phone rings waits before it answers; negative leaves it ringing.
     * - `captions --ez on true|false`: a live call's CC button, remembered from call to call.
     */
    @Suppress("ReturnCount") // a flat dispatch over the ops
    private suspend fun handleDemoCall(
        context: Context,
        intent: Intent,
    ): JSONObject {
        if (!BuildConfig.SEED_DEMO) return reply("error", "not a demo build (-PseedDemo / -PdemoDirector)")
        val calls = getKoin().getOrNull<CallManager>() ?: return reply("error", "this build wires no calls")
        when (val op = intent.getStringExtra("op") ?: "") {
            "ring" -> {
                val peer = intent.getStringExtra("peer") ?: return reply("error", "missing 'peer' extra")
                DemoCalls.ring(peer, walkie = intent.getBooleanExtra("walkie", false))
                val alerting = DemoCalls.await(CallPhase.Alerting) ?: return reply("error", "the ring never came")
                if (intent.getBooleanExtra("open", false)) {
                    context.startActivity(CallActivity.intent(context, alerting.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return reply("ok", "ringing ${alerting.id}").put("id", alerting.id)
            }

            "say", "talk" -> {
                val line = intent.getStringExtra("line") ?: return reply("error", "missing 'line' extra")
                // Seconds of audio, past a broadcast's own timeout: it runs on the app scope and this replies at once.
                scope.launch { if (op == "say") DemoCalls.say(line) else DemoCalls.talk(line) }
                return reply("ok", "${if (op == "say") "the peer says" else "talking"} '$line'")
            }

            "hangup" -> {
                DemoCalls.peerHangsUp()
                return reply("ok", "the peer hung up")
            }

            "mic" -> {
                val line = intent.getStringExtra("line") ?: return reply("error", "missing 'line' extra")
                if (!DemoCalls.micSays(line)) return reply("error", "no demo line '$line'")
                return reply("ok", "the next burst says '$line'")
            }

            "answer" -> {
                val ms = intent.getLongExtra("ms", DemoCalls.answerAfterMs ?: -1L)
                DemoCalls.answerAfterMs = ms.takeIf { it >= 0 }
                return reply("ok", if (ms >= 0) "a rung peer answers after ${ms}ms" else "a rung peer lets it ring")
            }

            "captions" -> {
                DemoCalls.captions(intent.getBooleanExtra("on", true))
                return reply("ok", "captions ${intent.getBooleanExtra("on", true)}")
            }

            else -> {
                return reply("error", "op must be ring|say|talk|mic|hangup|answer|captions").put(
                    "call",
                    calls.state.value
                        ?.phase
                        ?.name,
                )
            }
        }
    }

    /**
     * Drives the experimental voice calls (docs/VOICE_CALLS.md): `--es op` is `state` (the default: the call, its
     * selected candidate pair as `route`, the relay-only toggle and the Wi-Fi Direct leg), `start --es peer <id>`,
     * `answer`, `decline`, `hangup`, `relay --ez on true|false` (C-3.7-3: every new call through TURN), or
     * `turn --es peer <id>` (asks the connected spools for a credential and reports its URLs and expiry — never the
     * username or password).
     *
     * The direct leg (§3.10, ADR 2026-10.kxnw): `direct --es mode prewarm|answer|off` (when a call between neighbours
     * raises its Wi-Fi Direct group; `off` is Diagnostics' persisted "Wi-Fi Direct calls" switch, so every call goes
     * over STUN/TURN until a timing is set again), `nettype --es as ethernet|wifi` (what the group is reported to libwebrtc as —
     * the steering trial's A/B), and `dropgroup` (closes the live leg's lane as if the peer walked away: the radio
     * goes back and ICE must fail over). A `route` ending `/wifi-direct` is a pair on the group. `pathpref --es mode
     * android|off` sets whether a call prefers the network Android routes over (DefaultPath; on by default), and `turntls --es check
     * off|on` opens the next calls' `turns:` legs without checking the certificate, a diagnosis switch held in memory
     * (libwebrtc's own roots hold no Let's Encrypt root); `webrtc: relays …` then says whether a `tls` relay came.
     * `turnprune --es prune off|on` opens the next calls with TURN pruning off (C-7.6-26's "before", in memory); a
     * call's `webrtc: allocations gathered N, pruned M, held K` at close is the count either way. `turnpin --es slot
     * 1-4|off` puts the TURN server at that place in a spool's `turnHosts` first from the next call on, whatever its
     * score or refusal (in memory, `state`'s `turnPin`): how a trial makes a call try a refusing box first and watch
     * C-7.6-54 move it to the next. `turn` names each credential's `slot`, `of` and `score` beside its URLs, and which
     * one a call would take (`chosen`).
     *
     * `start` and `answer` need Knit in the foreground (`am start` it first): the call's microphone service and its
     * screen can only start from there. `answer` opens the call screen, which answers, so it asks for the microphone
     * like a tap would — pre-grant RECORD_AUDIO with `pm grant` for an unattended run.
     *
     * The walkie-talkie (§3.11, ADR 2026-10.79yb): `start --es kind auto|live|walkie` places the call as the Call button
     * would (`auto`, the default) or forces its kind; `talk --ei ms <500-8000>` holds the talk button that long in a
     * connected walkie session, streaming the burst as it records (the call's microphone service must be up — the call
     * screen starts it). `state` adds `walkie`/`degraded` to the call and the `walkie` block: the talk button's state,
     * the bursts `sent` and `heard` (played to their end), the chunks `chunksOut`, `chunksIn` and `lost` (skipped as
     * never come), and `link`: DIRECT, MESH or NONE, the link a chunk to the peer would take (null outside a walkie).
     *
     * The audio route (§3.13): `audio [--es to speaker|earpiece|wired|bluetooth]` moves the call's audio to the first
     * route of that kind the system offers (none named: just reports), and `state` carries `audio`: the route Telecom
     * has the call on (`current`, null until it says) and every route it offers (`available`), each `kind`/`name`/`key`.
     *
     * The microphone (§3.13, C-3.13-12/13, ADR 2026-10.kw29): `capture --es set standard|fallback|reset` picks the path
     * the next call records from (`reset` forgets every verdict, so the image decides), and `--es fault silence|off`
     * zeroes what the standard path records, a dead microphone for a trial on a phone whose works. `state` carries
     * `capture`: the profile the next call opens on and why, and the open call's microphone (`profile`, `switched`,
     * `health`: WATCHING, LIVE, SILENCED, SWITCHED or DEAD). Hosting (ADR 2026-10.w3dw): `directHost`.
     */

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod") // a flat dispatch over the ops
    private suspend fun handleCall(
        context: Context,
        intent: Intent,
    ): JSONObject {
        val calls = getKoin().getOrNull<CallManager>() ?: return reply("error", "this build wires no calls")
        val foreground = getKoin().get<AppVisibility>().visible.value
        val state = calls.state.value
        when (intent.getStringExtra("op") ?: "state") {
            "start" -> {
                val peer = intent.getStringExtra("peer") ?: return reply("error", "missing 'peer' extra")
                if (!foreground) return reply("error", "not foreground: am start Knit first")
                val kind =
                    when (intent.getStringExtra("kind") ?: "auto") {
                        "auto" -> CallKind.AUTO
                        "live" -> CallKind.LIVE
                        "walkie" -> CallKind.WALKIE
                        else -> return reply("error", "kind must be auto|live|walkie")
                    }
                val refusal = calls.start(peer, kind)
                return reply(if (refusal == null) "ok" else "refused", refusal?.name ?: "calling $peer")
                    .put("id", calls.state.value?.id ?: JSONObject.NULL)
            }

            "answer" -> {
                val id = state?.takeIf { it.phase == CallPhase.Alerting }?.id ?: return reply("error", "nothing is ringing")
                if (!foreground) return reply("error", "not foreground: am start Knit first")
                context.startActivity(
                    CallActivity
                        .intent(context, id)
                        .setAction(CallActivity.ACTION_ANSWER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return reply("ok", "answering $id")
            }

            "decline" -> {
                val id = state?.id ?: return reply("error", "no call")
                calls.decline(id)
                return reply("ok", "declined $id")
            }

            "hangup" -> {
                calls.hangUp()
                return reply("ok", "hung up")
            }

            "mute" -> {
                // `--ez on true|false`: the call screen's Mute, for a trial whose two phones would otherwise howl.
                state ?: return reply("error", "no call")
                val on = intent.getBooleanExtra("on", true)
                calls.setMuted(on)
                return reply("ok", if (on) "muted" else "unmuted")
            }

            "talk" -> {
                val walkie = getKoin().getOrNull<WalkieTalk>() ?: return reply("error", "this build wires no walkie")
                // `--es pcm <file>`: the burst says what that raw 16 kHz mono s16le file says, held as long as it lasts.
                val pcm = intent.getStringExtra("pcm")?.let(::File)
                if (pcm != null && !pcm.canRead()) return reply("error", "can't read $pcm")
                pcm?.let { getKoin().get<TalkRecorder>().nextFrom = it }
                val spoken = pcm?.let { (it.length() / PCM_BYTES_PER_MS + TALK_PCM_TAIL_MS).toInt() }
                val ms = intent.getIntExtra("ms", spoken ?: TALK_DEFAULT_MS).coerceIn(TALK_MIN_MS, TALK_MAX_MS)
                if (!walkie.press()) return reply("error", "no walkie session, or the microphone would not open")
                delay(ms.toLong())
                walkie.release()
                return reply(
                    "ok",
                    "talked ${ms}ms: ${walkie.talk.value}",
                ).put("sent", walkie.sent.get()).put("chunksOut", walkie.chunksOut.get())
            }

            "ns" -> {
                // The walkie microphone's platform noise suppressor (C-3.11-5): `--ez on false` for an A/B, in memory
                // only, until the process ends. Takes effect at the next burst; `suppressor` is what the last one had.
                val recorder = getKoin().getOrNull<TalkRecorder>() ?: return reply("error", "this build wires no walkie")
                if (intent.hasExtra("on")) recorder.suppressNoise = intent.getBooleanExtra("on", true)
                return reply("ok", "noise suppression ${if (recorder.suppressNoise) "asked for" else "off"}")
                    .put("on", recorder.suppressNoise)
                    .put("suppressor", recorder.suppressor ?: JSONObject.NULL)
            }

            "gain" -> {
                // The walkie microphone's own gain (`TalkGain`, C-3.11-5): `--ez on false` sends the source's level as it
                // comes, in memory only, until the process ends. Takes effect at the next burst.
                val recorder = getKoin().getOrNull<TalkRecorder>() ?: return reply("error", "this build wires no walkie")
                if (intent.hasExtra("on")) recorder.applyGain = intent.getBooleanExtra("on", true)
                return reply("ok", "walkie gain ${if (recorder.applyGain) "on" else "off"}").put("on", recorder.applyGain)
            }

            "source" -> {
                // The walkie microphone's audio source, tried before the usual list (C-3.11-5): `--es source
                // unprocessed|recognition|mic|communication|camcorder|performance|default`, in memory only, until the
                // process ends. `unprocessed` says whether the phone claims the raw source.
                val recorder = getKoin().getOrNull<TalkRecorder>() ?: return reply("error", "this build wires no walkie")
                intent.getStringExtra("source")?.let { name ->
                    recorder.sourceFirst =
                        if (name == "default") null else WALKIE_SOURCES[name] ?: return reply("error", "unknown source '$name'")
                }
                val audio = context.getSystemService(AudioManager::class.java)
                val profile =
                    getKoin()
                        .getOrNull<CaptureHealth>()
                        ?.choice
                        ?.value
                        ?.profile
                return reply("ok", "walkie source first ${recorder.sourceFirst ?: "default"}")
                    .put("sourceFirst", recorder.sourceFirst ?: JSONObject.NULL)
                    .put("lastSource", recorder.lastSource ?: JSONObject.NULL)
                    .put("profile", profile?.name?.lowercase() ?: JSONObject.NULL)
                    .put("unprocessed", audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) ?: JSONObject.NULL)
            }

            "relay" -> {
                // Settings' "Relay all calls" (`SettingsStore.callsRelayAll`, C-3.7-3): persisted, and the row shows it.
                val on = intent.getBooleanExtra("on", true)
                getKoin().get<SettingsStore>().setCallsRelayAll(on)
                return reply("ok", "relay-only $on (persisted)")
            }

            "direct" -> {
                // `off` is Diagnostics' persisted switch (`SettingsStore.debugDirectCallsOff`): the two read the same
                // key, so it survives a restart and the screen shows it. A timing turns the leg back on.
                val mode =
                    when (intent.getStringExtra("mode")) {
                        "prewarm" -> DirectMode.PREWARM
                        "answer" -> DirectMode.ANSWER
                        "off" -> null
                        else -> return reply("error", "mode must be prewarm|answer|off")
                    }
                mode?.let { calls.directMode = it }
                getKoin().get<SettingsStore>().setDebugDirectCallsOff(mode == null)
                return reply("ok", "direct ${mode?.name?.lowercase() ?: "off (persisted)"}")
            }

            "nettype" -> {
                DirectNetworkDetector.reportAs =
                    when (intent.getStringExtra("as")) {
                        "ethernet" -> NetworkChangeDetector.ConnectionType.CONNECTION_ETHERNET
                        "wifi" -> NetworkChangeDetector.ConnectionType.CONNECTION_WIFI
                        else -> return reply("error", "as must be ethernet|wifi")
                    }
                return reply("ok", "the group is reported as ${DirectNetworkDetector.reportAs} from the next call on")
            }

            "pathpref" -> {
                DefaultPath.enabled =
                    when (intent.getStringExtra("mode")) {
                        "android" -> true
                        "off" -> false
                        else -> return reply("error", "mode must be android|off")
                    }
                val what = if (DefaultPath.enabled) "follow Android's default network" else "prefer no network"
                return reply("ok", "calls $what from the next call on")
            }

            "turntls" -> {
                WebRtcCallMedia.turnTlsUnchecked =
                    when (intent.getStringExtra("check")) {
                        "off" -> true
                        "on" -> false
                        else -> return reply("error", "check must be on|off")
                    }
                val what = if (WebRtcCallMedia.turnTlsUnchecked) "UNCHECKED (diagnosis only)" else "checked"
                return reply("ok", "turns: certificates $what from the next call on")
            }

            "turnpin" -> {
                val ranking = getKoin().getOrNull<TurnRanking>() ?: return reply("error", "this build takes no live calls")
                ranking.pinnedSlot =
                    when (val slot = intent.getStringExtra("slot")) {
                        "off" -> null
                        else -> slot?.toIntOrNull()?.takeIf { it in 1..TurnHosts.MAX } ?: return reply("error", "slot must be 1-4 or off")
                    }
                val what = ranking.pinnedSlot?.let { "slot $it first" } ?: "pin off"
                return reply("ok", "TURN $what from the next call on")
            }

            "turnprune" -> {
                WebRtcCallMedia.turnPruneOff =
                    when (intent.getStringExtra("prune")) {
                        "off" -> true
                        "on" -> false
                        else -> return reply("error", "prune must be on|off")
                    }
                val what = if (WebRtcCallMedia.turnPruneOff) "OFF (measurement only)" else "on"
                return reply("ok", "TURN pruning $what from the next call on")
            }

            "audio" -> {
                val system = getKoin().get<SystemCallSync>()
                val routing = system.routing.value ?: return reply("error", "the system holds no call")
                val to = intent.getStringExtra("to") ?: return reply("ok", "routes").put("audio", audioJson(system))
                val kind =
                    CallAudioRoute.Kind.entries.firstOrNull { it.name.equals(to, ignoreCase = true) }
                        ?: return reply("error", "to must be speaker|earpiece|wired|bluetooth")
                val route = routing.available.firstOrNull { it.kind == kind } ?: return reply("error", "no $to route now")
                val moved = system.request(route)
                // A Bluetooth route reports in only once its call link is up, a second or so after Telecom took the ask.
                if (moved) withTimeoutOrNull(ROUTE_SETTLE_MS) { system.routing.first { it?.current?.key == route.key } }
                return reply(if (moved) "ok" else "refused", "audio to ${route.name}").put("audio", audioJson(system))
            }

            "capture" -> {
                if (!LIVE_CALLS_WIRED) return reply("error", "this build wires no live calls")
                val health = getKoin().get<CaptureHealth>()
                val set = intent.getStringExtra("set")
                if (set == "reset") {
                    health.reset()
                } else if (set != null) {
                    val profile =
                        CaptureProfile.entries.firstOrNull { it.name.equals(set, ignoreCase = true) }
                            ?: return reply("error", "set must be standard|fallback|reset")
                    health.choose(profile)
                }
                when (intent.getStringExtra("fault")) {
                    null -> Unit
                    "silence" -> getKoin().get<WebRtcCallMedia>().faultSilence = true
                    "off" -> getKoin().get<WebRtcCallMedia>().faultSilence = false
                    else -> return reply("error", "fault must be silence|off")
                }
                // `--es pcm <file>`: the call's microphone says what that raw 16 kHz mono s16le file says, from now (or
                // the next call) until it ends; `--es pcm off` gives the microphone back.
                when (val pcm = intent.getStringExtra("pcm")) {
                    // Not asked: the feed stays as it is.
                    null -> {}

                    "off" -> {
                        getKoin().get<WebRtcCallMedia>().micFeed = null
                    }

                    else -> {
                        val samples = withContext(Dispatchers.IO) { readShorts(File(pcm)) } ?: return reply("error", "can't read $pcm")
                        getKoin().get<WebRtcCallMedia>().micFeed = MicFeed(samples, SttPolicy.SAMPLE_RATE)
                    }
                }
                return reply("ok", "capture").put("capture", captureJson())
            }

            "dropgroup" -> {
                if (!LIVE_CALLS_WIRED) return reply("error", "this build wires no live calls")
                val dropped = getKoin().get<DirectCallPath>().dropGroup()
                return reply(if (dropped) "ok" else "error", if (dropped) "lane closed; the radio goes back" else "no live leg")
            }

            "turn" -> {
                val peer = intent.getStringExtra("peer") ?: return reply("error", "missing 'peer' extra")
                // What a call would gather from (§3.14): the person's own servers replace the spools' per kind, so an
                // own TURN server sends no turnreq here either.
                val own =
                    getKoin()
                        .get<OwnIceStore>()
                        .ownIce
                        .first()
                        .effective()
                val source = OverridingTurnSource({ own }, getKoin().get<MeshManager>())
                // Asked as a call asks (C-7.6-51), so a spool that names its servers signs for the best-ranked one.
                val ranking = getKoin().getOrNull<TurnRanking>() ?: TurnRanking()
                val servers = source.turnServers(peer, ranking::best)
                val chosen = ranking.choose(servers)
                val list = JSONArray()
                servers.forEach { server ->
                    val entry = JSONObject().put("urls", JSONArray(server.urls))
                    // A spool's credential names its expiry first; an own server's username is a name, never printed.
                    if (own.turn == null) entry.put("expires", server.user.substringBefore(':'))
                    if (server.ranked) {
                        entry.put("slot", server.slot).put("of", server.spoolHosts.size)
                        entry.put("score", server.entry?.let(ranking::score) ?: JSONObject.NULL)
                        entry.put("chosen", server === chosen)
                    }
                    list.put(entry)
                }
                // C-7.6-20: the STUN URLs a call would gather from beside them, as the call picks them.
                val stun = StunServers.pick(source.stunUrls())
                return reply("ok", "${servers.size} credential(s), ${stun.size} STUN url(s)")
                    .put("turn", list)
                    .put("stun", JSONArray(stun))
                    .put("turnSource", if (own.turn != null) "own" else "relays")
                    .put("stunSource", if (own.stun.isNotEmpty()) "own" else "relays")
            }
        }
        return JSONObject()
            .put("status", "ok")
            .put("foreground", foreground)
            .put("relayOnly", getKoin().get<SettingsStore>().callsRelayAll.first())
            .put("pathPref", if (DefaultPath.enabled) "android" else "off")
            .put("turnTls", if (WebRtcCallMedia.turnTlsUnchecked) "unchecked" else "checked")
            .put("turnPrune", if (WebRtcCallMedia.turnPruneOff) "off" else "on")
            .put("turnPin", getKoin().getOrNull<TurnRanking>()?.pinnedSlot ?: "off")
            .put("directMode", if (getKoin().get<SettingsStore>().debugDirectCallsOff.first()) "off" else calls.directMode.name.lowercase())
            .put("direct", getKoin().getOrNull<DirectCallPath>()?.describe() ?: JSONObject.NULL)
            .put(
                "directHost",
                getKoin()
                    .getOrNull<DirectHostGuard>()
                    ?.verdict
                    ?.value
                    ?.toString() ?: JSONObject.NULL,
            ).put("capture", captureJson())
            .put(
                "call",
                state?.let {
                    JSONObject()
                        .put("id", it.id)
                        .put("peer", it.peerId)
                        .put("outgoing", it.outgoing)
                        .put("phase", it.phase.name)
                        .put("startedAt", it.startedAt)
                        .put("connectedAt", it.connectedAt ?: JSONObject.NULL)
                        .put("muted", it.muted)
                        .put("audio", getKoin().getOrNull<SystemCallSync>()?.let(::audioJson) ?: JSONObject.NULL)
                        .put("reason", it.reason ?: JSONObject.NULL)
                        .put("route", calls.route() ?: JSONObject.NULL)
                        .put("walkie", it.walkie)
                        .put("degraded", it.degraded)
                        .put("peerCastTicketed", it.peerCastTicketed)
                } ?: JSONObject.NULL,
            ).put(
                "walkie",
                getKoin().getOrNull<WalkieTalk>()?.let {
                    JSONObject()
                        .put("talk", it.talk.value.toString())
                        .put("link", it.link.value?.name ?: JSONObject.NULL)
                        .put("castTicketed", getKoin().get<MeshManager>().castTicketed())
                        .put("sent", it.sent.get())
                        .put("heard", it.heard.get())
                        .put("chunksOut", it.chunksOut.get())
                        .put("chunksIn", it.chunksIn.get())
                        .put("lost", it.lost.get())
                        .put("stats", walkieStatsJson(it.stats()))
                        .put(
                            "transcript",
                            getKoin()
                                .getOrNull<WalkieTranscriber>()
                                ?.lines
                                ?.value
                                ?.let(::transcriptJson) ?: JSONObject.NULL,
                        ).put(
                            "stt",
                            getKoin()
                                .getOrNull<SttModelStore>()
                                ?.state
                                ?.value
                                ?.let(::sttStateJson) ?: JSONObject.NULL,
                        )
                } ?: JSONObject.NULL,
            )
    }

    /** The microphone path the next call opens on and why, and the open call's microphone (C-3.13-12/13). */
    private fun captureJson(): Any {
        val health = getKoin().getOrNull<CaptureHealth>() ?: return JSONObject.NULL
        val media = getKoin().getOrNull<WebRtcCallMedia>()
        val choice = health.choice.value
        val open = media?.captureStatus()
        return JSONObject()
            .put("next", choice.profile.name)
            .put("why", choice.reason?.name ?: JSONObject.NULL)
            .put("fault", media?.faultSilence == true)
            .put("feed", media?.micFeed?.let { if (it.done) "done" else "playing" } ?: JSONObject.NULL)
            .put(
                "open",
                open?.let { JSONObject().put("profile", it.profile).put("switched", it.switched).put("health", it.health.name) }
                    ?: JSONObject.NULL,
            )
    }

    /** The call's audio route as Telecom holds it (§3.13), or null when the system holds no call. */
    private fun audioJson(system: SystemCallSync): Any {
        val routing = system.routing.value ?: return JSONObject.NULL

        fun json(route: CallAudioRoute) = JSONObject().put("kind", route.kind.name).put("name", route.name).put("key", route.key)
        return JSONObject()
            .put("current", routing.current?.let(::json) ?: JSONObject.NULL)
            .put("available", JSONArray().also { arr -> routing.available.forEach { arr.put(json(it)) } })
    }

    /**
     * The walkie transcript's speech model and the session's transcript (docs/VOICE_CALLS.md C-3.11-9). `status` (the
     * default) dumps both; `download` fetches the model from its host as the card's and Settings' buttons do,
     * `sideload --es dir <dir>` installs it from the files pushed there (world-readable, e.g. under /data/local/tmp;
     * `scripts/stt-model.py --out` stages them), each checked against the same pins; `cancel`, `delete` and `unload`
     * (release the model now) do what they say. `pcm --es path <file>` streams (in the background; poll `status`) a raw 16 kHz mono s16le file through the
     * model in 100 ms chunks, as fast as it will take them, looking as a burst does, and replies with the text, the looks,
     * the model's time over it all and the time its last look took — the time from a burst's end to its text — with no
     * call and no radio; `--ez continuous true` opens the stream a live call's captions do (C-3.7-5), split at pauses,
     * for the cost of the two side by side. `status` also dumps the live call's captions (`captions`). `loop --es path <file>` sends that file the whole walkie way but the radio — the recorder's Opus
     * encoder at 8 kbps, the player's decoder and its tap, the transcriber — as burst [LOOP_BURST] of a fresh transcript,
     * in real time, and replies at once; poll `status` for its two lines, the spoken one (`mine`, the file before Opus)
     * and the heard one (after it), whose texts differ by the codec, and whose `live` shows the text while it streams. It
     * plays aloud, and refuses while a walkie call is up. `pcm` also reads a saved walkie clip's WAV. `clips` reports
     * Diagnostics' "Save walkie clips" (where, how many, how big), `--ez on true|false` sets it and `--ez clear true`
     * deletes them.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // a flat dispatch over the ops
    private suspend fun handleStt(intent: Intent): JSONObject {
        val store = getKoin().getOrNull<SttModelStore>() ?: return reply("error", "this build wires no speech")
        val speech = getKoin().get<MoonshineSpeech>()
        // A broadcast can wake a fresh process before its own start has read the disk.
        withContext(Dispatchers.IO) { store.start() }
        when (val op = intent.getStringExtra("op") ?: "status") {
            // Nothing to do but the dump below.
            "status" -> {}

            "download" -> {
                if (!store.download()) return reply("error", "no route to fetch over").put("stt", sttStateJson(store.state.value))
            }

            "sideload" -> {
                val dir = intent.getStringExtra("dir")?.let(::File) ?: return reply("error", "missing 'dir' extra")
                store.sideload(dir)
            }

            "cancel" -> {
                store.cancel()
            }

            "delete" -> {
                speech.unload()
                store.delete()
            }

            "unload" -> {
                speech.unload()
            }

            "lines" -> {
                // `--es mode whole|split` (`LineMode`, whole by default), for an A/B. In memory until the process
                // ends; the next burst reloads the model with it.
                val mode = intent.getStringExtra("mode")
                speech.lines = LineMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
                    ?: return reply("error", "mode is one of ${LineMode.entries.joinToString { it.name.lowercase() }}")
            }

            "captions" -> {
                // `--ez on true|false`: a live call's CC button (C-3.7-5), remembered from call to call as the
                // button's own choice is; read as the peer's audio comes, so a call up now is captioned from here on.
                val transcripts = getKoin().get<TranscriptStore>()
                transcripts.setLiveCaptions(intent.getBooleanExtra("on", true))
            }

            "keyterms" -> {
                // `--es terms 'Ada,Grace Hopper'` replaces the names the next burst listens for (a call's start sets its
                // own); `--ez clear true` empties them. In memory; `status` shows them (`keyterms`).
                speech.keyterms =
                    if (intent.getBooleanExtra("clear", false)) {
                        emptyList()
                    } else {
                        intent
                            .getStringExtra("terms")
                            ?.split(',')
                            ?.map(String::trim)
                            ?.filter(String::isNotEmpty)
                            ?: return reply("error", "missing 'terms' or 'clear' extra")
                    }
            }

            "clips" -> {
                // Diagnostics' "Save walkie clips": `--ez on true|false` sets the persisted switch, `--ez clear true`
                // deletes what was saved; either way the reply says where they are and how many.
                if (!WALKIE_WIRED) return reply("error", "this build wires no walkie")
                val clips = getKoin().get<WalkieClipFiles>()
                if (intent.hasExtra("on")) getKoin().get<SettingsStore>().setDebugWalkieClips(intent.getBooleanExtra("on", false))
                if (intent.getBooleanExtra("clear", false)) clips.clear()
                val (count, bytes) = clips.count()
                return reply("ok", "walkie clips")
                    .put("on", getKoin().get<SettingsStore>().debugWalkieClips.first())
                    .put("dir", clips.dir.path)
                    .put("clips", count)
                    .put("bytes", bytes)
            }

            "loop" -> {
                if (!WALKIE_WIRED) return reply("error", "this build wires no walkie")
                val file = intent.getStringExtra(EXTRA_PATH)?.let(::File) ?: return reply("error", "missing 'path' extra")
                if (!file.canRead()) return reply("error", "can't read $file")
                if (getKoin()
                        .getOrNull<CallManager>()
                        ?.state
                        ?.value
                        ?.walkie == true
                ) {
                    return reply("error", "a walkie call is up")
                }
                getKoin().get<CoroutineScope>().launch { loopBack(file) }
                return reply("ok", "looping $file; poll status")
            }

            "pcm" -> {
                val file = intent.getStringExtra(EXTRA_PATH)?.let(::File) ?: return reply("error", "missing 'path' extra")
                if (store.paths() == null) return reply("error", "the model isn't installed")
                val samples = withContext(Dispatchers.IO) { readPcm16(file) } ?: return reply("error", "can't read $file")
                // `--ez continuous true`: the stream a live call's captions open (C-3.7-5), split at every pause.
                val continuous = intent.getBooleanExtra("continuous", false)
                // Off the broadcast: a long clip outlasts its timeout (an ANR). The result lands in `status`'s `pcm`.
                pcmRun?.cancel()
                lastPcm = JSONObject().put("status", "running").put("path", file.path)
                pcmRun =
                    getKoin().get<CoroutineScope>().launch(Dispatchers.Default) {
                        lastPcm = streamPcm(speech, samples, continuous).put("path", file.path).put("continuous", continuous)
                    }
                return reply("ok", "streaming $file; poll status for `pcm`")
            }

            "note" -> {
                // `--es hash <attachmentHash>`: the voice bubble's Transcribe (ADR 2026-10.am8d), opened with the row's
                // key. `notes` in the dump below is where it stands; `STATE`'s row carries the saved text.
                val hash = intent.getStringExtra("hash") ?: return reply("error", "missing 'hash' extra")
                val notes = getKoin().get<VoiceNoteSpeech>()
                if (store.state.value !is SttModelState.Ready) return reply("error", "the model isn't installed")
                val key = getKoin().get<MessageDao>().attachmentKeyForHash(hash)
                if (!notes.transcribe(
                        hash,
                        key,
                    )
                ) {
                    return reply("refused", "busy, a call is up, or already running").put("notes", notesJson())
                }
            }

            else -> {
                return reply("error", "unknown op '$op'")
            }
        }
        return reply("ok", "stt")
            .put("notes", notesJson())
            .put("model", store.model.id)
            .put("bytes", store.model.bytes)
            .put("stt", sttStateJson(store.state.value))
            .put("resident", speech.resident)
            .put("lines", speech.lines.name.lowercase())
            .put("keyterms", JSONArray(speech.keyterms))
            .put("pcm", lastPcm ?: JSONObject.NULL)
            .put("liveCaptions", getKoin().get<TranscriptStore>().liveCaptions.first())
            .put(
                "transcript",
                getKoin()
                    .getOrNull<WalkieTranscriber>()
                    ?.lines
                    ?.value
                    ?.let(::transcriptJson) ?: JSONObject.NULL,
            ).put(
                "captions",
                getKoin().getOrNull<CallCaptioner>()?.captions?.value?.let { captions ->
                    JSONObject().put("status", captions.status.name).put("lines", transcriptJson(captions.lines))
                } ?: JSONObject.NULL,
            )
    }

    private fun notesJson(): JSONObject =
        JSONObject().apply {
            getKoin().getOrNull<VoiceNoteSpeech>()?.notes?.value?.forEach { (hash, stt) ->
                put(
                    hash,
                    when (stt) {
                        is NoteStt.Running -> {
                            JSONObject()
                                .put(
                                    "state",
                                    "running",
                                ).put("settled", stt.heard.settled)
                                .put("live", stt.heard.live)
                        }

                        // The text is the person's own; the bridge is a debug build's, and the row already holds it.
                        is NoteStt.Done -> {
                            JSONObject().put("state", "done").put("text", stt.text)
                        }

                        NoteStt.Busy -> {
                            JSONObject().put("state", "busy")
                        }

                        NoteStt.Failed -> {
                            JSONObject().put("state", "failed")
                        }
                    },
                )
            }
        }

    // The `loop` op: [file] through the recorder's encoder, then the player's decoder and tap, into the transcript —
    // twice over: the recorder's tap writes the file out as spoken (before Opus), the player's as heard (after it).
    private suspend fun loopBack(file: File) {
        val recorder = getKoin().get<TalkRecorder>()
        val player = getKoin().get<TalkPlayer>()
        val transcript = getKoin().get<WalkieTranscriber>()
        val packets = ConcurrentLinkedQueue<ByteArray>()
        transcript.reset()
        transcript.begin(LOOP_BURST, mine = true)
        recorder.nextFrom = file
        if (!recorder.start(headset = false) { packets.add(it) }) {
            transcript.abandon(mine = true)
            return
        }
        delay(file.length() / PCM_BYTES_PER_MS + TALK_PCM_TAIL_MS)
        recorder.stop()
        transcript.end(LOOP_BURST, mine = true)
        transcript.begin(LOOP_BURST)
        withContext(Dispatchers.IO) { player.play(packets.toList(), first = true, last = true) }
        transcript.end(LOOP_BURST)
    }

    // The `pcm` op: [samples] through one stream in 100 ms chunks, looking whenever a burst's [PassGate] would.
    private suspend fun streamPcm(
        speech: MoonshineSpeech,
        samples: FloatArray,
        continuous: Boolean,
    ): JSONObject {
        val began = System.currentTimeMillis()
        val stream = speech.open(continuous) ?: return reply("error", "the model would not run")
        val gate = PassGate()
        var looks = 0
        var modelMs = 0L
        val chunk = SttPolicy.SAMPLE_RATE / PCM_CHUNKS_PER_SEC
        var at = 0
        while (at < samples.size) {
            val part = samples.copyOfRange(at, minOf(samples.size, at + chunk))
            at += part.size
            val t = System.currentTimeMillis()
            if (!stream.add(part)) return reply("error", "the model failed on the audio")
            if (gate.fed(part.size * MILLIS_PER_SEC.toDouble() / SttPolicy.SAMPLE_RATE)) {
                stream.look() ?: return reply("error", "the model failed on a look")
                looks++
                gate.took(System.currentTimeMillis() - t)
            }
            modelMs += System.currentTimeMillis() - t
        }
        val ending = System.currentTimeMillis()
        val heard = stream.finish()
        val finishMs = System.currentTimeMillis() - ending
        return reply("ok", "transcribed")
            .put("audioMs", samples.size * MILLIS_PER_SEC / SttPolicy.SAMPLE_RATE)
            .put("tookMs", System.currentTimeMillis() - began)
            .put("modelMs", modelMs + finishMs)
            .put("finishMs", finishMs)
            .put("looks", looks)
            .put("text", heard?.text ?: JSONObject.NULL)
    }

    private fun sttStateJson(state: SttModelState): JSONObject =
        when (state) {
            SttModelState.Absent -> JSONObject().put("state", "absent")
            SttModelState.Ready -> JSONObject().put("state", "ready")
            is SttModelState.Downloading -> JSONObject().put("state", "downloading").put("done", state.done).put("total", state.total)
            is SttModelState.Failed -> JSONObject().put("state", "failed").put("reason", state.reason)
        }

    // Debug builds only, on a lab phone: the text is what a trial checks, so it is here, never in logcat.
    private fun transcriptJson(lines: List<TranscriptLine>): JSONArray =
        JSONArray().also { array ->
            lines.forEach { line ->
                val state = line.state
                array.put(
                    JSONObject()
                        .put("burst", line.burst)
                        .put("mine", line.mine)
                        .put("heardAt", line.heardAt)
                        .put("state", if (state is TranscriptLine.State.Text) "Text" else state.toString())
                        .put("text", (state as? TranscriptLine.State.Text)?.text ?: JSONObject.NULL)
                        .put("live", line.live)
                        .put("settled", line.settled)
                        .put("audioMs", line.audioMs)
                        .put("tookMs", line.tookMs)
                        .put("looks", line.looks)
                        .put("modelMs", line.modelMs),
                )
            }
        }

    // Raw 16-bit little-endian mono PCM at the model's rate, as −1..1 floats — or a WAV of it (a saved walkie clip), its
    // header skipped; null when the file won't read.
    private fun readPcm16(file: File): FloatArray? =
        runCatching {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size >= WAV_HEADER && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") buffer.position(WAV_HEADER)
            val shorts = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            FloatArray(shorts.remaining()) { shorts.get(it) / PCM_FULL_SCALE }
        }.getOrNull()

    // A raw 16-bit little-endian mono file (or a WAV of one, its header skipped) as samples; null when it won't read.
    private fun readShorts(file: File): ShortArray? =
        runCatching {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size >= WAV_HEADER && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") buffer.position(WAV_HEADER)
            val shorts = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            ShortArray(shorts.remaining()).also(shorts::get)
        }.getOrNull()

    /** The walkie's diagnostics card as numbers (§3.11): what `CallActivity` draws, null where not yet measured. */
    private fun walkieStatsJson(s: WalkieStats): JSONObject =
        JSONObject()
            .put("transitMs", s.transitMs ?: JSONObject.NULL)
            .put("jitterMs", s.jitterMs ?: JSONObject.NULL)
            .put("lossPercent", s.lossPercent ?: JSONObject.NULL)
            .put("sentLossPercent", s.sentLossPercent ?: JSONObject.NULL)
            .put("cutShort", s.burstsCutShort)
            .put("unheard", s.chunksUnheard)
            .put("unreported", s.burstsUnreported)
            .put("gaps", s.gaps)
            .put("gapMs", s.gapMs)
            .put("sendKbps", s.sendKbps ?: JSONObject.NULL)
            .put("receiveKbps", s.receiveKbps ?: JSONObject.NULL)
            .put("bufferMs", s.bufferMs ?: JSONObject.NULL)
            .put("handMs", s.handMs ?: JSONObject.NULL)
            .put("frameOut", s.frameOut ?: JSONObject.NULL)
            .put("frameIn", s.frameIn ?: JSONObject.NULL)
            .put("failed", s.chunksFailed)
            .put("path", s.path ?: JSONObject.NULL)

    /**
     * Drives a direct file transfer headless (transfer/TransferManager) so two lab phones can run one
     * without touching a screen: `--es to <nodeId> --es path <file>` offers the file at `path` to that peer;
     * `--es accept|decline|cancel <transferId>` answers one; `--ez sweep true` clears a group a dead process
     * left on air; no extras dumps every live transfer and the radio's current refusal, plus the transfer rows
     * of `--es conv <peerId>` when given. The file path is read as the app itself (push it with `run-as`), and
     * the signaling, the Wi-Fi Direct group and the bytes then go exactly as a tap would send them.
     *
     * The `refusal` in the dump is the field worth having on a device: every gate in [DirectWifi.refusal] fails
     * a transfer before the radio is ever touched, and from outside they are indistinguishable from a peer that
     * simply never answered. Note that the disclosure sheet is a UI gate only — accepting an offer through this
     * bridge steps around it, exactly as it steps around the picker.
     */

    private suspend fun handleXfer(intent: Intent): JSONObject {
        if (intent.getBooleanExtra("sweep", false)) {
            directWifi.sweep()
            return reply("ok", "swept")
        }
        intent.getStringExtra("accept")?.let { id ->
            val refusal = transfers.accept(id)
            return reply(if (refusal == null) "ok" else "refused", "accept $id: ${refusal ?: "started"}")
        }
        intent.getStringExtra("decline")?.let { id ->
            transfers.decline(id)
            return reply("ok", "declined $id")
        }
        intent.getStringExtra("cancel")?.let { id ->
            transfers.cancel(id)
            return reply("ok", "cancelled $id")
        }
        val to = intent.getStringExtra("to")
        val path = intent.getStringExtra("path")
        if (to != null && path != null) {
            return when (val outcome = transfers.offer(to, Uri.fromFile(File(path)).toString())) {
                is OfferOutcome.Started -> reply("ok", "offered ${outcome.id}").put("id", outcome.id)
                is OfferOutcome.Refused -> reply("refused", outcome.refusal.name)
            }
        }
        val live = JSONArray()
        transfers.states.value.values.forEach { s ->
            live.put(
                JSONObject()
                    .put("id", s.id)
                    .put("peer", s.peerId)
                    .put("outgoing", s.outgoing)
                    .put("name", s.name)
                    .put("size", s.size)
                    .put("phase", s.phase.name)
                    .put("bytes", s.bytes)
                    .put("reason", s.reason ?: JSONObject.NULL),
            )
        }
        val rows = JSONArray()
        intent.getStringExtra("conv")?.let { conv ->
            messages
                .observeNewestMessages(conv, ChatWindow.MAX)
                .first()
                .filter { it.kind == MessageEntity.KIND_FILE_TRANSFER }
                .forEach { row -> rows.put(JSONObject().put("id", row.id).put("sentAt", row.sentAt).put("record", row.body)) }
        }
        return JSONObject()
            .put("status", "ok")
            .put("refusal", directWifi.refusal()?.name ?: JSONObject.NULL)
            .put("hostRefusal", directWifi.hostRefusal()?.name ?: JSONObject.NULL)
            .put(
                "directHost",
                getKoin()
                    .getOrNull<DirectHostGuard>()
                    ?.verdict
                    ?.value
                    ?.toString() ?: JSONObject.NULL,
            ).put("live", live)
            .put("rows", rows)
    }

    /**
     * Creates (or reopens) a group locally from `--es members <comma-separated peer nodeIds>` (self is
     * added automatically) — the [app.getknit.knit.ui.contacts.ContactsViewModel.createGroup] mechanics
     * without the picker UI, which cannot express a 2-member group (one selection opens a DM). Purely
     * local, exactly like UI creation: other members learn of the group from its first message. The
     * reply carries the derived `groupId` for follow-up `SEND --es conv` calls.
     */
    private suspend fun handleMkGroup(intent: Intent): JSONObject {
        val others =
            intent
                .getStringExtra("members")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        if (others.isEmpty()) return reply("error", "missing --es members <nodeId,nodeId,...>")
        val me = identity.nodeId()
        val members = (others + me).distinct()
        val groupId = Conversations.groupIdFor(members)
        val existing = groups.find(groupId)
        if (existing == null || existing.left) {
            groups.upsert(
                GroupEntity(
                    groupId = groupId,
                    name = intent.getStringExtra("name").orEmpty(),
                    members = GroupMembersStore.encode(members),
                    createdBy = me,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        return reply("ok", "group ready").put("groupId", groupId).put("members", members.size)
    }

    /**
     * Leaves the group [EXTRA_CONV] names, exactly as the group-details screen does (the signed `groupleave`
     * floods first, then the local tombstone), so a locked lab device can run the leave → re-create (ADR
     * 2026-09.v6fu's self-rejoin) flow headlessly: `LEAVE`, then `MKGROUP` with the same members, then `SEND`.
     */
    private suspend fun handleLeave(intent: Intent): JSONObject {
        val groupId = intent.getStringExtra(EXTRA_CONV)?.trim().orEmpty()
        if (!groupId.startsWith(Conversations.GROUP_ID_PREFIX)) return reply("error", "missing --es conv <g-…>")
        val existing = groups.find(groupId) ?: return reply("error", "no such group")
        if (existing.left) return reply("ok", "already left").put("groupId", groupId)
        mesh.sendGroupLeave(groupId)
        groups.leave(groupId)
        return reply("ok", "left").put("groupId", groupId)
    }

    /**
     * Purges soak-harness traffic from this phone: every message whose body starts with one of the
     * `|`-separated `--es prefix` alternatives (the harness stamps `soak …` and `burst …`), in every thread,
     * through the same [LocalMessageDelete] the chat's long-press runs — the row, its reactions, its pin, its receipt
     * rows, the attachment blob once nothing references it — plus the frame's custody row via [ForwardStore.remove],
     * which keeps the content digest in lockstep with the table. The custody half is the point: a message id
     * is its frame id, and custody (24 h) outlives the SeenSet window (10 min), so a purge that left it would
     * watch the message walk back in from a peer's carry set. Run it on every phone in one pass; a phone
     * that was off adb re-supplies until its rows expire. `--ez dry true` counts without deleting; `--ei limit`
     * bounds the newest-N window scanned per thread (default [PURGE_SCAN_LIMIT]); `--es group <g-…>` also
     * drops that group row locally afterwards (no leave frame — the harness's throwaway group). Nothing here
     * is sent over the mesh, and a reaction's own custody frame is left to its TTL.
     */
    private suspend fun handlePurge(intent: Intent): JSONObject {
        val prefixes =
            intent
                .getStringExtra(EXTRA_PREFIX)
                ?.split('|')
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        if (prefixes.isEmpty()) return reply("error", "missing --es prefix 'soak |burst '")
        val dry = intent.getBooleanExtra(EXTRA_DRY, false)
        val limit = intent.getIntExtra(EXTRA_LIMIT, PURGE_SCAN_LIMIT)
        val byConversation = JSONObject()
        var matched = 0
        var deleted = 0
        var custodyRemoved = 0
        var blobsChecked = 0
        messages.distinctConversations().forEach { conv ->
            val hits =
                messages
                    .observeNewestMessages(conv, limit)
                    .first()
                    .filter { m -> prefixes.any { m.body.startsWith(it) } }
            if (hits.isEmpty()) return@forEach
            matched += hits.size
            byConversation.put(conv, hits.size)
            if (dry) return@forEach
            hits.forEach { m ->
                // Custody first: the blob's unreferenced check counts carried frames too.
                if (forward.has(m.id)) {
                    forward.remove(m.id)
                    custodyRemoved++
                }
                localDelete.delete(m.id, m.attachmentHash)
                if (m.attachmentHash != null) blobsChecked++
                deleted++
            }
        }
        val group = intent.getStringExtra(EXTRA_GROUP)?.trim()
        var groupDropped = false
        if (!dry && group != null && group.startsWith(Conversations.GROUP_ID_PREFIX) && groups.find(group) != null) {
            groups.delete(group)
            groupDropped = true
        }
        return reply("ok", if (dry) "would purge $matched" else "purged $deleted")
            .put("dry", dry)
            .put("matched", matched)
            .put("deleted", deleted)
            .put("custodyRemoved", custodyRemoved)
            .put("blobsChecked", blobsChecked)
            .put("byConversation", byConversation)
            .put("groupDropped", groupDropped)
    }

    /**
     * Empties the database for a fresh lab run and keeps who this phone is. The identity and its prekeys
     * (`files/identity.key`), the database key and the DataStore (name, status, profile stamps, settings, the
     * accepted set) live outside it already; the own avatar's bytes are a blob row, so they are read out first
     * and put back under the same hash. The blob caches beside the database go too. Refused unless the mesh is
     * paused ([ACTION_PAUSE]), so no frame lands mid-wipe, and the process is killed just after the reply, so no
     * singleton's in-memory mirror (the custody digest, the SeenSet, the ratchet caches) outlives its rows. The
     * next start comes up paused: [ACTION_RESUME] it, and start the service as after an install.
     */
    private suspend fun handleResetDb(context: Context): JSONObject {
        val pausedUntil = settings.meshPausedUntil.first()
        if (pausedUntil == null || pausedUntil <= System.currentTimeMillis()) {
            return reply("error", "pause the mesh first (debug.PAUSE)")
        }
        val dao = database.blobDao()
        val avatar =
            settings.ownAvatarHash.first()?.let { hash ->
                dao.bytes(hash)?.let { BlobEntity(hash, dao.mimeFor(hash) ?: "image/jpeg", it) }
            }
        database.clearAllTables()
        avatar?.let { dao.insert(it) }
        File(context.cacheDir, "blobtx").deleteRecursively()
        File(context.cacheDir, "blob-partials").deleteRecursively()
        context.cacheDir.listFiles { f -> f.name.startsWith("link-rx-") }?.forEach { it.delete() }
        scope.launch {
            delay(RESET_KILL_AFTER_MS)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        return reply("ok", "database emptied; the process dies in $RESET_KILL_AFTER_MS ms")
            .put("avatarKept", avatar?.hash ?: JSONObject.NULL)
    }

    /**
     * Arms (or disarms, with `count` 0 or absent) forced Wi-Fi Aware attach failures — the lab stand-in for
     * getknit/Knit#9's chipset. Pair it with [ACTION_NANSTORM]: the failures are what make the attach path
     * reachable at all, since a transport that is attached returns from `attach()` before any of it.
     */
    private fun handleNanFail(intent: Intent): JSONObject {
        val count = intent.getIntExtra("count", 0)
        val armed = NanFaultInjector.armFailures(count)
        val what = if (armed > 0) "armed $armed forced attach failures" else "fault injection disarmed"
        return reply("ok", what).put("armed", armed).withNanState()
    }

    /**
     * Replays the availability storm from getknit/Knit#9 (ADR 055) against the running transport, and reports
     * how many attaches got through. Pre-fix that number tracked the broadcast count; post-fix the rate floor
     * holds it near `elapsedMs / 3000`, whatever the storm does.
     */
    private suspend fun handleNanStorm(intent: Intent): JSONObject {
        if (!NanFaultInjector.bound) return reply("error", "Wi-Fi Aware transport is not running")
        val count = intent.getIntExtra("count", 100).coerceIn(1, 100_000)
        val hz = intent.getIntExtra("hz", 300).coerceIn(1, 10_000)
        val cycle = intent.getBooleanExtra("cycle", false)
        val before = NanFaultInjector.status()
        val periodMs = 1_000L / hz
        val startedAt = SystemClock.elapsedRealtime()
        repeat(count) { i ->
            // Repeating `true` is the bug's shape; alternating is a run of genuine recoveries.
            NanFaultInjector.notifyAvailability(!cycle || i % 2 == 1)
            if (periodMs > 0) delay(periodMs)
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val after = NanFaultInjector.status()
        return reply("ok", if (cycle) "storm sent (alternating edges)" else "storm sent (repeated available)")
            .put("sent", count)
            .put("elapsedMs", elapsed)
            .put("ratePerSec", if (elapsed > 0) count * 1_000L / elapsed else -1)
            .put("failuresBefore", before?.total ?: -1)
            .put("failuresAfter", after?.total ?: -1)
            .put("attachesAllowed", (after?.total ?: 0) - (before?.total ?: 0))
            .withNanState()
    }

    /**
     * Arms (or disarms, with `count` 0; dumps, with it absent) forced responder-request refusals — the lab
     * stand-in for a framework that declares the accept-any request unfulfillable on every file (work item #77,
     * ADR 2026-09.bgk3). What this measures is the pacing: the delays in the transport's `re-filing in Nms` lines,
     * the give-up into a session cycle at the fifth uncontended verdict, and the three-cycle cap.
     */
    private fun handleNanRefuse(intent: Intent): JSONObject {
        if (!NanFaultInjector.bound) return reply("error", "Wi-Fi Aware transport is not running")
        val count = intent.getIntExtra("count", -1)
        val armed = if (count >= 0) NanFaultInjector.armResponderRefusals(count) else null
        val what =
            when {
                armed == null -> "responder re-file state"
                armed > 0 -> "armed $armed responder refusals"
                else -> "responder refusals disarmed"
            }
        val r = NanFaultInjector.responderStatus()
        return reply("ok", what)
            .put("filed", r?.filed ?: false)
            .put("refusals", r?.refusals ?: -1)
            .put("cycles", r?.cycles ?: -1)
            .put("armed", r?.armed ?: -1)
    }

    /** [ACTION_BLECAP]: sets or clears the Bluetooth link cap, then reports it with the Bluetooth row. */
    private suspend fun handleBleCap(intent: Intent): JSONObject {
        when {
            intent.getBooleanExtra("clear", false) -> {
                settings.setDebugBleLinkCap(null)
            }

            intent.hasExtra("max") -> {
                val max = intent.getIntExtra("max", -1)
                if (max !in 0..PromotionConfig.DEFAULT_MAX_LINKS) {
                    return reply("error", "max must be 0..${PromotionConfig.DEFAULT_MAX_LINKS}")
                }
                settings.setDebugBleLinkCap(max)
            }
        }
        val ble = mesh.transportStatuses.value.firstOrNull { it.kind == TransportKind.Bluetooth }
        return JSONObject()
            .put("status", "ok")
            .put("cap", settings.debugBleLinkCap.first() ?: JSONObject.NULL)
            .put("default", PromotionConfig.DEFAULT_MAX_LINKS)
            .put("linked", ble?.linked ?: JSONObject.NULL)
            .put("nearby", ble?.nearby ?: JSONObject.NULL)
    }

    /** [ACTION_NANOFF]: switches the Wi-Fi Aware plane off or back on, then reports the switch with the plane's row. */
    private suspend fun handleNanOff(intent: Intent): JSONObject {
        if (intent.hasExtra("off")) settings.setDebugNanOff(intent.getBooleanExtra("off", false))
        val off = settings.debugNanOff.first()
        if (intent.hasExtra("off")) delay(NANOFF_SETTLE_MS) // the transport collects the key; let it land before reading the row
        val nan = mesh.transportStatuses.value.firstOrNull { it.kind == TransportKind.WifiAware }
        return JSONObject()
            .put("status", "ok")
            .put("off", off)
            // The person's Settings switch keeps the plane off as well; either one is enough (GitHub #44).
            .put("userSwitchOff", !awareSwitch.on.first())
            .put("present", nan != null)
            .put("health", nan?.health?.name ?: JSONObject.NULL)
            .put("linked", nan?.linked ?: JSONObject.NULL)
            .put("nearby", nan?.nearby ?: JSONObject.NULL)
    }

    /** [ACTION_LOWPOWER]: sets low-power mode's switch, then reports the posture with the Wi-Fi Aware row. */
    private suspend fun handleLowPower(intent: Intent): JSONObject {
        if (intent.hasExtra("on")) {
            lowPowerSwitch.set(intent.getBooleanExtra("on", false))
            delay(NANOFF_SETTLE_MS) // the posture and the transport collect the key; let it land before reading
        }
        val nan = mesh.transportStatuses.value.firstOrNull { it.kind == TransportKind.WifiAware }
        return JSONObject()
            .put("status", "ok")
            // False since GitHub #44: the posture is off whatever the switch says, and the Settings row is hidden.
            .put("offered", LOW_POWER_OFFERED)
            .put("lowPower", lowPowerJson())
            .put("awarePresent", nan != null)
            .put("awareHealth", nan?.health?.name ?: JSONObject.NULL)
            .put("awareSwitchedOff", nan?.switchedOff ?: JSONObject.NULL)
    }

    /** Low-power mode's inputs and the posture they make, for [ACTION_STATE] and [ACTION_LOWPOWER]. */
    private suspend fun lowPowerJson(): JSONObject {
        val posture = lowPower.current
        return JSONObject()
            .put("switch", lowPowerSwitch.on.first())
            .put("batterySaver", batterySaver.on.value)
            .put("charging", powerState.state.value.charging)
            .put("active", posture.active)
            .put("reason", posture.reason?.name ?: JSONObject.NULL)
    }

    /** [ACTION_PHY]: sets the Coded PHY experiment's mode, Coded advert power or step tuning, then reports it. */
    private suspend fun handlePhy(intent: Intent): JSONObject {
        if (!BuildConfig.BLE_CODED_PHY) return reply("error", "this build keeps the Coded PHY experiment dark (-PbleCodedPhy)")
        intent.getStringExtra("mode")?.let { raw ->
            val mode = CodedPhyMode.parse(raw) ?: return reply("error", "mode must be off|auto|coded|1m")
            settings.setDebugBlePhyMode(mode)
        }
        intent.getStringExtra("txpower")?.let { level ->
            val apply = CodedPhyDiag.setTxPower ?: return reply("error", "Bluetooth transport is not running")
            if (!apply(level)) return reply("error", "txpower must be high|medium")
        }
        if (intent.getBooleanExtra("resetTuning", false)) CodedPhyDiag.tuning = PhyTuning()
        val t = CodedPhyDiag.tuning

        fun int(key: String) = if (intent.hasExtra(key)) intent.getIntExtra(key, 0) else null
        CodedPhyDiag.tuning =
            t.copy(
                stepDownDbm = int("stepDown") ?: t.stepDownDbm,
                stepUpDbm = int("stepUp") ?: t.stepUpDbm,
                stepDownReads = int("stepDownReads") ?: t.stepDownReads,
                minSwitchGapMs = int("minGapMs")?.toLong() ?: t.minSwitchGapMs,
                stepUpHoldMs = int("stepUpHoldMs")?.toLong() ?: t.stepUpHoldMs,
                stepUpFastDbm = int("stepUpFast") ?: t.stepUpFastDbm,
                stepUpFastHoldMs = int("stepUpFastHoldMs")?.toLong() ?: t.stepUpFastHoldMs,
                flapWindowMs = int("flapWindowMs")?.toLong() ?: t.flapWindowMs,
                stepUpBackoffMaxMs = int("stepUpBackoffMaxMs")?.toLong() ?: t.stepUpBackoffMaxMs,
                codedCreditDb = int("credit")?.toDouble() ?: t.codedCreditDb,
                fastAdvertMs = int("fastAdvertMs") ?: t.fastAdvertMs,
                fastHoldMs = int("fastHoldMs")?.toLong() ?: t.fastHoldMs,
                huntInUseHoldMs = int("huntInUseHoldMs")?.toLong() ?: t.huntInUseHoldMs,
                oneMWeakDbm = int("oneMWeak")?.toDouble() ?: t.oneMWeakDbm,
                coldHunt = if (intent.hasExtra("coldHunt")) intent.getBooleanExtra("coldHunt", true) else t.coldHunt,
                codedPaceBytesPerSec = int("codedPace") ?: t.codedPaceBytesPerSec,
                codedChunkBytes = int("codedChunk")?.coerceIn(1, LinkFraming.FILE_CHUNK_BYTES) ?: t.codedChunkBytes,
                codedWindowChunkBytes =
                    int("codedWindowChunk")?.coerceIn(MIN_CODED_WINDOW_CHUNK, MAX_CODED_WINDOW_CHUNK) ?: t.codedWindowChunkBytes,
                filePaceBytesPerSec = int("filePace") ?: t.filePaceBytesPerSec,
                fadeDbm = int("fade") ?: t.fadeDbm,
                fadeExitDbm = int("fadeExit") ?: t.fadeExitDbm,
                fadeExitHoldMs = int("fadeExitHoldMs")?.toLong() ?: t.fadeExitHoldMs,
                fadeStaleReads = int("fadeStaleReads")?.coerceAtLeast(1) ?: t.fadeStaleReads,
            )
        delay(PHY_SETTLE_MS) // the transport collects the stored mode; let it land before reading the status back
        val status = CodedPhyDiag.status?.invoke() ?: return reply("error", "Bluetooth transport is not running")
        val tuned = CodedPhyDiag.tuning
        return JSONObject()
            .put("status", "ok")
            .put("mode", status.mode.wire)
            .put("supported", status.supported)
            .put("advert", status.advert)
            .put("txPower", status.txPower)
            .put(
                "tuning",
                JSONObject()
                    .put("stepDown", tuned.stepDownDbm)
                    .put("stepDownReads", tuned.stepDownReads)
                    .put("stepUp", tuned.stepUpDbm)
                    .put("stepUpHoldMs", tuned.stepUpHoldMs)
                    .put("stepUpFast", tuned.stepUpFastDbm)
                    .put("stepUpFastHoldMs", tuned.stepUpFastHoldMs)
                    .put("minGapMs", tuned.minSwitchGapMs)
                    .put("flapWindowMs", tuned.flapWindowMs)
                    .put("stepUpBackoffMaxMs", tuned.stepUpBackoffMaxMs)
                    .put("credit", tuned.codedCreditDb.toInt())
                    .put("fastAdvertMs", tuned.fastAdvertMs)
                    .put("fastHoldMs", tuned.fastHoldMs)
                    .put("huntInUseHoldMs", tuned.huntInUseHoldMs)
                    .put("oneMWeak", tuned.oneMWeakDbm.toInt())
                    .put("coldHunt", tuned.coldHunt)
                    .put("codedPace", tuned.codedPaceBytesPerSec)
                    .put("codedChunk", tuned.codedChunkBytes)
                    .put("codedWindowChunk", tuned.codedWindowChunkBytes)
                    .put("filePace", tuned.filePaceBytesPerSec)
                    .put("fileChunk", CodedPhyPolicy.pace(LinkPhy.ONE_M, tuned).chunkBytes)
                    .put("fade", tuned.fadeDbm)
                    .put("fadeExit", tuned.fadeExitDbm)
                    .put("fadeExitHoldMs", tuned.fadeExitHoldMs)
                    .put("fadeStaleReads", tuned.fadeStaleReads),
            ).put(
                "links",
                JSONArray(
                    status.links.map {
                        JSONObject()
                            .put("nodeId", it.nodeId)
                            .put("phy", it.phy.name)
                            .put("linkRssi", it.linkRssi ?: JSONObject.NULL)
                            .put("drives", it.drives)
                            .put("attached", it.attached)
                            .put("switches", it.switches)
                            .put("gaveUp", it.gaveUp)
                            .put("fading", it.fading)
                    },
                ),
            ).put(
                "peers",
                JSONArray(
                    status.peers.map {
                        JSONObject()
                            .put("nodeId", it.nodeId)
                            .put("rssi", it.smoothedRssi.toInt())
                            .put("oneMSeenAgoMs", it.oneMSeenAgoMs ?: JSONObject.NULL)
                            .put("codedSeenAgoMs", it.codedSeenAgoMs ?: JSONObject.NULL)
                            .put("rssi1m", it.rssi1m?.toInt() ?: JSONObject.NULL)
                            .put("rssiCoded", it.rssiCoded?.toInt() ?: JSONObject.NULL)
                            .put("codedLagMs", it.codedLagMs ?: JSONObject.NULL)
                    },
                ),
            ).put("hunting", status.hunting)
            .put("hunted", JSONArray(status.hunted.sorted()))
    }

    /**
     * [ACTION_LINKTUNE]: sets the 1M/2M file budget, asks the links `node` names for a PHY, a priority or an MTU
     * through their tuners (#120), then reports them. Needs no Coded flag, so a release-shaped debug build can run the
     * drain trial.
     */
    @Suppress("CyclomaticComplexMethod") // one parse per extra, each with its own refusal
    private suspend fun handleLinkTune(intent: Intent): JSONObject {
        val priority =
            intent.getStringExtra("priority")?.let { raw ->
                when (raw.lowercase()) {
                    "high" -> TunePriority.HIGH
                    "balanced" -> TunePriority.BALANCED
                    "low" -> TunePriority.LOW_POWER
                    else -> return reply("error", "priority must be high|balanced|low")
                }
            }
        val phy =
            intent.getStringExtra("phy")?.let { raw ->
                when (raw.lowercase()) {
                    "1m" -> TunePhy.ONE_M
                    "2m" -> TunePhy.TWO_M
                    "1m2m" -> TunePhy.ONE_M_2M
                    else -> return reply("error", "phy must be 1m|2m|1m2m")
                }
            }
        val mtu =
            if (intent.hasExtra("mtu")) {
                intent.getIntExtra("mtu", 0).takeIf { it in MIN_ATT_MTU..MAX_ATT_MTU }
                    ?: return reply("error", "mtu must be $MIN_ATT_MTU..$MAX_ATT_MTU")
            } else {
                null
            }
        val request =
            LinkTuneRequest(
                node = intent.getStringExtra("node") ?: "all",
                priority = priority,
                phy = phy,
                mtu = mtu,
                read = intent.getBooleanExtra("read", false),
                close = intent.getBooleanExtra("close", false),
            )
        if ((request.asks || request.close) && !intent.hasExtra("node")) {
            return reply("error", "name the links: --es node <id prefix>|all")
        }
        val tune = LinkTuneDiag.tune ?: return reply("error", "Bluetooth transport is not running")
        if (intent.getBooleanExtra("resetPace", false)) {
            val defaults = PhyTuning()
            CodedPhyDiag.tuning =
                CodedPhyDiag.tuning.copy(
                    filePaceBytesPerSec = defaults.filePaceBytesPerSec,
                    boostPaceBytesPerSec = defaults.boostPaceBytesPerSec,
                    crowdedBoostPaceBytesPerSec = defaults.crowdedBoostPaceBytesPerSec,
                    feedWindowQueueMs = defaults.feedWindowQueueMs,
                )
            LinkTuneDiag.feedWindow = BuildConfig.BLE_FEED_WINDOW
        }
        if (intent.hasExtra("filePace")) {
            CodedPhyDiag.tuning = CodedPhyDiag.tuning.copy(filePaceBytesPerSec = intent.getIntExtra("filePace", 0))
        }
        if (intent.hasExtra("boostPace")) {
            CodedPhyDiag.tuning = CodedPhyDiag.tuning.copy(boostPaceBytesPerSec = intent.getIntExtra("boostPace", 0))
        }
        if (intent.hasExtra("crowdedPace")) {
            CodedPhyDiag.tuning = CodedPhyDiag.tuning.copy(crowdedBoostPaceBytesPerSec = intent.getIntExtra("crowdedPace", 0))
        }
        if (intent.hasExtra("boost")) LinkTuneDiag.boostOn = intent.getBooleanExtra("boost", true)
        if (intent.hasExtra("arm")) LinkTuneDiag.aclArm = intent.getBooleanExtra("arm", true)
        intent.getStringExtra("feed")?.let { raw ->
            LinkTuneDiag.feedWindow =
                when (raw.lowercase()) {
                    "window" -> true
                    "pace" -> false
                    else -> return reply("error", "feed must be window|pace")
                }
        }
        if (intent.hasExtra("windowQueueMs")) {
            val ms =
                intent.getIntExtra("windowQueueMs", 0).takeIf { it in MIN_WINDOW_QUEUE_MS..MAX_WINDOW_QUEUE_MS }
                    ?: return reply("error", "windowQueueMs must be $MIN_WINDOW_QUEUE_MS..$MAX_WINDOW_QUEUE_MS")
            CodedPhyDiag.tuning = CodedPhyDiag.tuning.copy(feedWindowQueueMs = ms.toLong())
        }
        val report = tune(request) // pokes every boost, so a switch just flipped takes at once
        val tuned = CodedPhyDiag.tuning
        return JSONObject()
            .put("status", "ok")
            .put("filePace", tuned.filePaceBytesPerSec)
            .put("fileChunk", CodedPhyPolicy.pace(LinkPhy.ONE_M, tuned).chunkBytes)
            .put("boostPace", tuned.boostPaceBytesPerSec)
            .put("crowdedPace", tuned.crowdedBoostPaceBytesPerSec)
            .put("leLinks", report.leLinks)
            .put("boost", BuildConfig.BLE_LINK_BOOST && LinkTuneDiag.boostOn)
            .put("arm", LinkTuneDiag.aclArm)
            .put("feed", if (LinkTuneDiag.feedWindow) "window" else "pace")
            .put("windowQueueMs", tuned.feedWindowQueueMs)
            .put("feeding", report.feeding)
            .put("earned", report.earned)
            .put(
                "links",
                JSONArray(
                    report.links.map {
                        JSONObject()
                            .put("nodeId", it.nodeId)
                            .put("address", it.address ?: JSONObject.NULL)
                            .put("attached", it.attached)
                            .put("txPhy", it.txPhy?.name ?: JSONObject.NULL)
                            .put("rxPhy", it.rxPhy?.name ?: JSONObject.NULL)
                            .put("intervalUs", it.params?.intervalUs ?: JSONObject.NULL)
                            .put("latency", it.params?.latency ?: JSONObject.NULL)
                            .put("timeoutMs", it.params?.timeoutMs ?: JSONObject.NULL)
                            .put("paramsStatus", it.paramsStatus ?: JSONObject.NULL)
                            .put("mtu", it.mtu ?: JSONObject.NULL)
                            .put("mtuAsked", it.mtuAsked ?: JSONObject.NULL)
                            .put("rssi", it.rssi ?: JSONObject.NULL)
                            .put("codedOwned", it.codedOwned)
                            .put("refused", it.refused ?: JSONObject.NULL)
                            .put("boosting", it.boosting ?: JSONObject.NULL)
                            .put("earned", it.earned ?: JSONObject.NULL)
                    },
                ),
            )
    }

    /**
     * `…debug.NANICM`: `--ez on` (the session cycle it always ran), `--es band 2.4|5|auto` (no cycle), both (`on`
     * first), or neither (a read). The band is parsed before anything is applied, so a bad one changes nothing.
     */
    private fun handleNanIcm(intent: Intent): JSONObject {
        if (!NanFaultInjector.bound) return reply("error", "Wi-Fi Aware transport is not running")
        val bandText = intent.getStringExtra("band")?.trim()?.lowercase()
        val band: NanIcmPolicy.Band? =
            when (bandText) {
                null, "auto" -> null
                "2.4" -> NanIcmPolicy.Band.B24
                "5" -> NanIcmPolicy.Band.B5
                else -> return reply("error", "unknown --es band '$bandText' (2.4 | 5 | auto)")
            }
        val did = mutableListOf<String>()
        if (intent.hasExtra("on")) {
            val now =
                NanFaultInjector.setInstantMode(intent.getBooleanExtra("on", true))
                    ?: return reply("error", "Wi-Fi Aware transport is not running")
            did += "instant communication mode ${if (now) "on" else "off"} (session cycled)"
        }
        if (bandText != null) {
            NanFaultInjector.setIcmBand(band)
            did += if (band == null) "band handed back to the policy" else "band pinned to $bandText GHz (no cycle)"
        }
        val s = NanFaultInjector.icmStatus() ?: return reply("error", "Wi-Fi Aware transport is not running")
        return reply("ok", did.joinToString("; ").ifEmpty { "instant communication mode state" })
            .put("instantMode", s.instantMode)
            .put("icmAllowed", s.allowed)
            .put("icmBandAsked", s.bandAsked?.name ?: "none")
            .put("icmReason", s.reason.name)
            .put("icmBandOverride", s.override?.name ?: "auto")
            .put("staFrequencyMhz", s.staFrequencyMhz ?: -1)
            .put("band5PrimaryUsable", s.band5PrimaryUsable?.toString() ?: "unknown")
            .put("postCure", s.postCure)
            .put("icmLitNow", s.litNow)
    }

    /**
     * [ACTION_NANDORM]: `--ez hold`, `--ez now true`, `--el duty <ms>` (any together: hold, then duty, then now), or
     * none (a read). The reply is the dormancy snapshot after them.
     */
    private suspend fun handleNanDorm(intent: Intent): JSONObject {
        if (NanFaultInjector.dormancyStatus() == null) return reply("error", "Wi-Fi Aware transport is not running")
        val did = mutableListOf<String>()
        if (intent.hasExtra("hold")) {
            NanFaultInjector.holdAwake(intent.getBooleanExtra("hold", false))?.let { did += it }
        }
        if (intent.hasExtra("duty")) {
            val ms = intent.getLongExtra("duty", 0L)
            NanFaultInjector.setDutyInterval(ms.takeIf { it > 0 })
            did += if (ms > 0) "duty interval ${ms}ms" else "duty interval handed back"
        }
        if (intent.getBooleanExtra("now", false)) NanFaultInjector.forceDormant()?.let { did += it }
        if (did.isNotEmpty()) delay(NANOFF_SETTLE_MS) // the handler applies them; let them land before the read-back
        val s = NanFaultInjector.dormancyStatus() ?: return reply("error", "Wi-Fi Aware transport is not running")
        return reply("ok", did.joinToString("; ").ifEmpty { "dormancy state" })
            .put("dormant", s.dormant)
            .put("why", s.why?.name ?: JSONObject.NULL)
            .put("dormantForMs", s.dormantForMs)
            .put("nextDutyInMs", s.nextDutyInMs ?: JSONObject.NULL)
            .put("dutyActive", s.dutyActive)
            .put("probeActive", s.probeActive)
            .put("nextProbeAllowedInMs", s.nextProbeAllowedInMs ?: JSONObject.NULL)
            .put("idleForMs", s.idleForMs)
            .put("stayReason", s.stayReason ?: JSONObject.NULL)
            .put("candidatesNear", JSONArray(s.candidatesNear.sorted()))
            .put("candidatesHeard", JSONArray(s.candidatesHeard.sorted()))
            .put("seesNewcomers", s.seesNewcomers)
            .put("held", s.held)
            .put("dutyIntervalMs", s.dutyIntervalMs)
            .put("available", s.available ?: JSONObject.NULL)
            .put("wifiEnabled", s.wifiEnabled ?: JSONObject.NULL)
            .put("deviceIdle", s.deviceIdle ?: JSONObject.NULL)
            .put("radioOff", s.radioOff)
            .put("wontRun", s.wontRun)
    }

    /** [ACTION_AWARE]: sets the person's Wi-Fi Aware switch, then reports it with the plane's row. */
    private suspend fun handleAware(intent: Intent): JSONObject {
        if (intent.hasExtra("on")) {
            awareSwitch.set(intent.getBooleanExtra("on", true))
            delay(NANOFF_SETTLE_MS) // the transport collects the key; let it land before reading the row
        }
        val nan = mesh.transportStatuses.value.firstOrNull { it.kind == TransportKind.WifiAware }
        return JSONObject()
            .put("status", "ok")
            .put("on", awareSwitch.on.first())
            .put("present", nan != null)
            .put("health", nan?.health?.name ?: JSONObject.NULL)
            .put("switchedOff", nan?.switchedOff ?: JSONObject.NULL)
            .put("dormant", nan?.dormant ?: JSONObject.NULL)
    }

    private fun handleNanMsg(intent: Intent): JSONObject {
        if (!NanFaultInjector.bound) return reply("error", "Wi-Fi Aware transport is not running")
        val what =
            when (val fault = intent.getStringExtra("fault")?.trim()?.lowercase()) {
                null -> "coordination-plane watchdog state"
                "off", "none" -> "fault disarmed: ${NanFaultInjector.setMsgFault(NanMsgFault.NONE)}"
                "swallow" -> "every send callback is now dropped: ${NanFaultInjector.setMsgFault(NanMsgFault.SWALLOW)}"
                "fail" -> "every ack is now a failure: ${NanFaultInjector.setMsgFault(NanMsgFault.FAIL)}"
                else -> return reply("error", "unknown --es fault '$fault' (swallow | fail | off)")
            }
        val s = NanFaultInjector.msgPlaneStatus()
        return reply("ok", what)
            .put("unanswered", s?.unanswered ?: -1)
            .put("oldestUnansweredMs", s?.oldestUnansweredMs ?: -1)
            .put("sinceAckMs", s?.sinceAckMs ?: -1)
            .put("failsSinceAck", s?.failsSinceAck ?: -1)
            .put("episodeMs", s?.episodeMs ?: -1)
            .put("cycles", s?.cycles ?: -1)
            .put("fault", s?.fault?.name?.lowercase() ?: "unbound")
    }

    private fun handleNanInit(intent: Intent): JSONObject {
        if (!NanFaultInjector.bound) return reply("error", "Wi-Fi Aware transport is not running")
        val what =
            when {
                intent.getBooleanExtra("blip", false) -> {
                    NanFaultInjector.blipWifi()
                    "injected a Wi-Fi drop and return"
                }

                intent.getBooleanExtra("reset", false) -> {
                    NanFaultInjector.resetInitiator()
                    "initiator hold released"
                }

                intent.getBooleanExtra("probe", false) -> {
                    if (NanFaultInjector.forceInitiatorProbe() ==
                        true
                    ) {
                        "daily probe due on the next tick"
                    } else {
                        "not held — nothing to probe"
                    }
                }

                else -> {
                    "initiator failsafe state"
                }
            }
        val i = NanFaultInjector.initiatorStatus()
        return reply("ok", what)
            .put("strikes", i?.strikes ?: -1)
            .put("latched", i?.latched ?: false)
            .put("probeInMs", i?.probeInMs ?: -1)
            .put("lastInitiateAgoMs", i?.lastInitiateAgoMs ?: -1)
            .put("lossPendingMs", i?.lossPendingMs ?: -1)
    }

    /**
     * Folds the attach budget and this process's Binder-object counts into a reply. The local count is the
     * closest in-process proxy for what AMS is actually watching, which is the count *system_server* holds
     * against our uid — for that, read `adb shell dumpsys activity binder-proxies`.
     */
    private fun JSONObject.withNanState(): JSONObject {
        val nan = NanFaultInjector.status()
        return put("attached", nan?.attached ?: false)
            .put("streak", nan?.streak ?: -1)
            .put("failTotal", nan?.total ?: -1)
            .put("abandoned", nan?.abandoned ?: false)
            .put("retryInMs", nan?.retryInMs ?: -1)
            .put("localBinders", Debug.getBinderLocalObjectCount())
            .put("binderDeathRecipients", Debug.getBinderDeathObjectCount())
    }

    private companion object {
        // The `STT --es op pcm` run under way, and its result (or `running`), which `status` reports. Here, not on the
        // receiver: a new receiver serves every broadcast.
        var pcmRun: Job? = null

        @Volatile
        var lastPcm: JSONObject? = null

        const val TAG = "KnitBridge"

        const val ACTION_SEND = "app.getknit.knit.debug.SEND"
        const val ACTION_SENDIMG = "app.getknit.knit.debug.SENDIMG"
        const val ACTION_SAVEATT = "app.getknit.knit.debug.SAVEATT"
        const val ACTION_STATE = "app.getknit.knit.debug.STATE"
        const val ACTION_STORE = "app.getknit.knit.debug.STORE"
        const val ACTION_REACT = "app.getknit.knit.debug.REACT"
        const val ACTION_TYPING = "app.getknit.knit.debug.TYPING"
        const val ACTION_SHARE_APK = "app.getknit.knit.debug.SHAREAPK"
        const val ACTION_WEBPPROBE = "app.getknit.knit.debug.WEBPPROBE"
        const val ACTION_WEBPCONV = "app.getknit.knit.debug.WEBPCONV"
        const val ACTION_WEBPCHECK = "app.getknit.knit.debug.WEBPCHECK"
        const val ACTION_HEAL = "app.getknit.knit.debug.HEAL"
        const val ACTION_PAUSE = "app.getknit.knit.debug.PAUSE"
        const val ACTION_RESUME = "app.getknit.knit.debug.RESUME"
        const val ACTION_NANFAIL = "app.getknit.knit.debug.NANFAIL"
        const val ACTION_NANSTORM = "app.getknit.knit.debug.NANSTORM"
        const val ACTION_NANREFUSE = "app.getknit.knit.debug.NANREFUSE"
        const val ACTION_NANICM = "app.getknit.knit.debug.NANICM"
        const val ACTION_NANDIAL = "app.getknit.knit.debug.NANDIAL"
        const val ACTION_NANINIT = "app.getknit.knit.debug.NANINIT"
        const val ACTION_NANMSG = "app.getknit.knit.debug.NANMSG"
        const val ACTION_NANDORM = "app.getknit.knit.debug.NANDORM"
        const val ACTION_AWARE = "app.getknit.knit.debug.AWARE"
        const val ACTION_BLECAP = "app.getknit.knit.debug.BLECAP"
        const val ACTION_NANOFF = "app.getknit.knit.debug.NANOFF"
        const val ACTION_LOWPOWER = "app.getknit.knit.debug.LOWPOWER"
        const val ACTION_PHY = "app.getknit.knit.debug.PHY"
        const val ACTION_LINKTUNE = "app.getknit.knit.debug.LINKTUNE"
        const val ACTION_REQNOTIF = "app.getknit.knit.debug.REQNOTIF"
        const val ACTION_MSGNOTIF = "app.getknit.knit.debug.MSGNOTIF"
        const val ACTION_FLAGMSG = "app.getknit.knit.debug.FLAGMSG"
        const val ACTION_MKGROUP = "app.getknit.knit.debug.MKGROUP"
        const val ACTION_LEAVE = "app.getknit.knit.debug.LEAVE"
        const val ACTION_REVIEW = "app.getknit.knit.debug.REVIEW"
        const val ACTION_MODEL = "app.getknit.knit.debug.MODEL"
        const val ACTION_SPOOL = "app.getknit.knit.debug.SPOOL"
        const val ACTION_COMMONS = "app.getknit.knit.debug.COMMONS"
        const val ACTION_INTRO = "app.getknit.knit.debug.INTRO"
        const val ACTION_RATCHET = "app.getknit.knit.debug.RATCHET"
        const val ACTION_LORA = "app.getknit.knit.debug.LORA"
        const val ACTION_LORATX = "app.getknit.knit.debug.LORATX"
        const val ACTION_LORAPROV = "app.getknit.knit.debug.LORAPROV"
        const val ACTION_XFER = "app.getknit.knit.debug.XFER"
        const val ACTION_CALL = "app.getknit.knit.debug.CALL"
        const val ACTION_STT = "app.getknit.knit.debug.STT"
        const val ACTION_TRANSLATE = "app.getknit.knit.debug.TRANSLATE"
        const val ACTION_DEMOCALL = "app.getknit.knit.debug.DEMOCALL"
        const val ACTION_DEMOGO = "app.getknit.knit.debug.DEMOGO"
        const val ACTION_BACKUP = "app.getknit.knit.debug.BACKUP"
        const val ACTION_PURGE = "app.getknit.knit.debug.PURGE"
        const val ACTION_RESETDB = "app.getknit.knit.debug.RESETDB"

        const val EXTRA_TEXT = "text"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_CONV = "conv"
        const val EXTRA_TO = "to"
        const val EXTRA_INDICATORS = "indicators"
        const val EXTRA_REPLY_TO = "replyTo"
        const val EXTRA_STYLES = "styles"
        const val EXTRA_ID = "id"
        const val EXTRA_EMOJI = "emoji"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_PATH = "path"
        const val EXTRA_FILE = "file"
        const val EXTRA_VIDEO = "video"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_OUT = "out"
        const val EXTRA_COUNT = "count"
        const val EXTRA_FROM = "from"
        const val EXTRA_RESET = "reset"
        const val EXTRA_UNLOAD = "unload"
        const val EXTRA_ARM = "arm"
        const val EXTRA_URL = "url"
        const val EXTRA_CARD = "card"
        const val EXTRA_IMPORT = "import"
        const val EXTRA_ON = "on"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DROP = "drop"
        const val EXTRA_MOVE = "move"
        const val EXTRA_PARK = "park"
        const val EXTRA_UNPARK = "unpark"
        const val EXTRA_TICKET = "ticket"
        const val EXTRA_ISSUER = "issuer"
        const val EXTRA_KEY = "key"
        const val EXTRA_FREE_ISSUER = "freeIssuer"
        const val EXTRA_FREE_CONSENT = "freeConsent"
        const val EXTRA_MINE = "mine"
        const val EXTRA_FIT = "fit"
        const val EXTRA_RESET_PEER = "reset"
        const val EXTRA_PREFIX = "prefix"
        const val EXTRA_DRY = "dry"
        const val EXTRA_GROUP = "group"

        /** Default sender + hidden body for [ACTION_FLAGMSG]'s synthetic flagged inbound message. */
        const val FLAGGED_SENDER_ID = "flagger0"
        const val FLAGGED_SENDER_NAME = "Flagged Sender"
        const val DEFAULT_FLAGGED_BODY = "[flagged demo message]"

        /** Cap on the synthetic requests [ACTION_REQNOTIF] injects (keeps each stranger's node-id single-digit). */
        const val MAX_REQNOTIF = 9

        /** How far past the age gate [ACTION_REVIEW]'s arm backdates the watermark (clock-skew slack). */
        const val ARM_MARGIN_MS = 60_000L

        // How long `…debug.PHY` waits after storing a mode before reading the status back: the transport collects it.
        const val PHY_SETTLE_MS = 300L

        // How long `…debug.CALL --es op audio --es to …` waits for the call to report the route it asked for.
        const val ROUTE_SETTLE_MS = 3_000L

        // The queue a `…debug.LINKTUNE --ei windowQueueMs` may set a windowed feed to keep ahead of its receiver.
        const val MIN_WINDOW_QUEUE_MS = 100
        const val MAX_WINDOW_QUEUE_MS = 5_000

        // The chunk `…debug.PHY --ei codedWindowChunk` may give a windowed feed on Coded: no smaller than a dark receiver
        // still reports on, up to four of the 1M/2M floor.
        const val MIN_CODED_WINDOW_CHUNK = FeedWindow.SMALLEST_CHUNK_BYTES
        const val MAX_CODED_WINDOW_CHUNK = 8_192

        // The ATT MTU range a `…debug.LINKTUNE` ask may name: the spec's default up to Android's own ceiling.
        const val MIN_ATT_MTU = 23
        const val MAX_ATT_MTU = 517

        // How long `…debug.NANOFF` waits after a flip before reading the Wi-Fi Aware row back: the transport collects it.
        const val NANOFF_SETTLE_MS = 300L

        // Mirror AttachmentStore.GIF_MAX_DIMENSION / GIF_MAX_FPS (private there) so this diagnostic
        // shrinks a GIF with the same bounds the real ingest path uses.
        const val GIF_MAX_DIMENSION = 480
        const val GIF_MAX_FPS = 15

        // ACTION_WEBPPROBE tunables: default per-frame WEBP_LOSSY quality + the animated-WebP mux
        // overhead we add to the summed per-frame bytes to estimate the final container size.
        const val WEBP_PROBE_QUALITY = 75
        const val WEBP_ANMF_OVERHEAD = 4
        const val WEBP_HEADER_OVERHEAD = 40

        /** Bound for ACTION_WEBPCHECK's moderation first-frame decode (mirrors the screening path). */
        const val WEBP_CHECK_MOD_DIM = 640

        const val DEFAULT_MESSAGE_LIMIT = 20

        /** Newest-N window [handlePurge] scans per thread — far past a night of injected traffic, still bounded. */
        const val PURGE_SCAN_LIMIT = 5000

        /** Long enough for the ordered broadcast to deliver [ACTION_RESETDB]'s reply before the process dies. */
        const val RESET_KILL_AFTER_MS = 1_000L

        /** Default cap on per-row detail in the [ACTION_STORE] dump (`allIds`/`expiredIds` are always complete). */
        const val DEFAULT_STORE_LIMIT = 100

        const val MILLIS_PER_SEC = 1000L
    }
}

// `…debug.CALL --es op source`: the walkie microphone's sources by name.
private val WALKIE_SOURCES =
    mapOf(
        "unprocessed" to MediaRecorder.AudioSource.UNPROCESSED,
        "recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "mic" to MediaRecorder.AudioSource.MIC,
        "communication" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "camcorder" to MediaRecorder.AudioSource.CAMCORDER,
        "performance" to MediaRecorder.AudioSource.VOICE_PERFORMANCE,
    )

// `…debug.CALL --es op talk`: how long the talk button is held — a burst's floor up to what a receiver's goAsync allows.
private const val TALK_DEFAULT_MS = 3_000
private const val TALK_MIN_MS = 500
private const val TALK_MAX_MS = 8_000

// A `talk --es pcm` file's length in ms (16 kHz s16: 32 bytes a millisecond), and the hold past its end, so its last
// packets are out before the release.
private const val PCM_BYTES_PER_MS = 32L
private const val TALK_PCM_TAIL_MS = 400L
private const val PCM_FULL_SCALE = 32_768f

// The canonical header `wavOf` writes a walkie clip with.
private const val WAV_HEADER = 44

// The burst number `…debug.STT --es op loop` gives its one burst.
private const val LOOP_BURST = 0

// `…debug.STT --es op pcm` feeds its file in 100 ms chunks.
private const val PCM_CHUNKS_PER_SEC = 10
