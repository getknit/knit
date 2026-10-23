# Internet voice calls

| | |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Status             | **Draft sketch, not normative.** Nothing here is on the wire                                                                  |
| Decision           | ADR 2026-10.3tee (Proposed); §3.10, a call between neighbours over Wi-Fi Direct, is ADR 2026-10.kxnw; §3.11, the walkie-talkie, is ADR 2026-10.79yb; §3.12, call records, is 3tee's second amendment; §3.13, the audio route, is ADR 2026-10.wbua |
| Spool side         | §2.1–§2.6 are written to drop into `docs/SPOOL_PROTOCOL.md` as §7.6, and §2.7 as §7.7, each with §10.1 bullets, once accepted |
| Client side        | §3 is app protocol: a new `MessageContent.ctl` value, invisible to spools and relays                                          |

## 1. Overview

A 1:1 audio call has three paths:

```
           sealed CTL_CALL frames (existing DM scope, unchanged)
 caller ──────────────────── spool(s) / mesh ──────────────────── callee
   │  turnreq → turncred                         turnreq → turncred │
   │                                                                │
   └──────────── DTLS-SRTP (Opus) ── direct, or via TURN ───────────┘
```

- **Signaling** is ordinary sealed DM traffic. It needs **no spool change**: a `CTL_CALL` frame is a
  v2-sealed `chat` frame between the scope's two members, which C-4.4-5…7 already admit. It also rides
  the mesh and the §9.4 bridge like any other DM, so two phones on one LAN with no Internet can still
  signal a call.
- **Media** is WebRTC: Opus over DTLS-SRTP, peer to peer where ICE finds a path and through TURN where
  it does not. The DTLS fingerprint rides inside the sealed SDP, so Knit identity authenticates the
  media keys (§3.7).
- **TURN credentials** (§2) and **casts** (§2.7) are the only new spool-side behaviours. The spool hands out
  short-lived credentials for a TURN server its operator runs, and never carries a live call's media. It does carry a
  walkie's chunks, unstored, between two phones with no radio path, on a spool that sells that to the pair (§2.7).
- **Between neighbours** the media can skip the Internet altogether: the two phones raise a Wi-Fi Direct group and
  ICE prefers its pair to any other (§3.10). That needs no spool and no STUN, and with them it still wins.

## 2. Spool side: TURN provisioning

### 2.1 Advertisement

| ID          | Requirement                                                                                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-7.6-1** | A spool that issues TURN credentials advertises `turn: true` in HELLO. A spool that does not MUST omit the field; `false` is never sent. Presence is the signal. |
| **S-7.6-2** | A spool MUST NOT advertise `turn` unless it is configured with a TURN secret and at least one TURN URL.                                                           |
| **C-7.6-3** | A client MUST NOT send `turnreq` to a spool that omitted `turn`.                                                                                                  |

> **Why a gate, not a hint.** The §7.3 reasoning, reapplied: an unknown record is skipped (B-2-2), and a
> skipped request is never answered, so a `turnreq` to an older spool would hang until the client's
> request timeout, once per call.

### 2.2 Records

| Record     | Direction | Fields                                                            | Semantics                                                                     |
|------------|-----------|-------------------------------------------------------------------|-------------------------------------------------------------------------------|
| `turnreq`  | c→s       | `t, q: Long`                                                      | Ask for one TURN credential. Answered by `turncred`, or by `err`               |
| `turncred` | s→c       | `t, q: Long, urls: [String], user: String, pass: String, exp: Long` | The credential. `exp` is Unix **seconds**. `urls` are RFC 7065 `turn:`/`turns:` URIs |

`turncred` carries `q` and is itself terminal, like `ahas`. `not_subscribed`, `rate` and `internal`
cover every refusal, plus `ticket` on a spool that takes entitlement tickets (§2.6).

| ID           | Requirement                                                                                                                                                                                                                       |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-7.6-4**  | A spool MUST refuse `turnreq` with `not_subscribed` on a connection that holds no subscription. The request names **no scope**, and the spool MUST NOT ask for one. On a connection whose HELLO carried `ent`, S-7.6-16 replaces this gate. |
| **S-7.6-5**  | A spool MUST rate-limit `turnreq` per connection and per IP, refusing with `rate` + `retryMs`. Suggested: 4 per connection per 10 min, burst 2.                                                                                  |
| **S-7.6-6**  | `user = decimal(exp) ‖ ":" ‖ base32lower(random(10))`, and `pass = base64(HMAC-SHA1(turnSecret, UTF8(user)))`: the TURN REST shared-secret scheme that coturn's `use-auth-secret` verifies.                                      |
| **S-7.6-7**  | The random suffix MUST be fresh per issue. It MUST NOT be derived from a scope id, an IP, a token or any connection state. A ticketed issue (S-7.6-17) is the one exception.                                                       |
| **S-7.6-8**  | `exp = now + turnTtl`. Suggested default 5 min, at most 1 h.                                                                                                                                                                      |
| **S-7.6-9**  | A spool MUST NOT log or persist issued credentials. Metrics MAY count issues and refusals.                                                                                                                                       |
| **S-7.6-10** | The spool process MUST NOT relay media. TURN is a separate service that shares only the secret.                                                                                                                                  |
| **C-7.6-11** | A client SHOULD fetch a credential per call, or reuse a cached one only while `exp` is at least 1 h away. It MUST NOT send a credential to its peer or into any frame.                                                          |
| **C-7.6-12** | A client SHOULD request from every spool of the DM scope that advertises `turn` and is connected, and SHOULD offer relay candidates from at most two TURN servers, to bound ICE checks.                                          |
| **C-7.6-26** | A client SHOULD prune its TURN ports once one is ready: on each network, a relay that ranks below the best ready one (relay protocol first, then address family) is released. This refines C-7.6-12, which still asks up to two spools. With one spool, a pruned call holds about one allocation per network. With two, each spool whose best relay ties the best ready one keeps a relay there, so two spools that both answer over UDP each keep one, and the second stays a backup. |

> **Why no scope.** Asking for a credential "for scope S" would tell the spool which conversation is
> calling, a fact it otherwise never learns. Holding *some* subscription is the whole gate: it costs a
> squatter a PoW stamp (§8) and admits any member of any conversation the spool carries. On a private
> spool, the `k` token (S-7.1-3) already gates the connection, so TURN inherits that gate.
>
> **Why minutes, not hours.** coturn checks a REST credential's expiry only when a session first
> authenticates, which is its Allocate; refreshes reuse the cached key (4.18.0, `check_stun_auth`). So
> `exp` bounds when a credential can *start* relaying, not how long a call lasts, and a few minutes is
> enough for ICE to allocate. The flip side: an allocation refreshed past `exp` lives on, so what a
> leaked or farmed credential is worth is bounded by the TURN server's quotas (§2.4), not by `exp`.
>
> **Why HMAC-SHA1.** It is what coturn and every TURN server implementing the REST scheme verify. It is
> not a choice this protocol makes, and it carries nothing a short-lived credential needs beyond a MAC.
>
> **Why prune (C-7.6-26).** Unpruned, a phone keeps an allocation for every network, address family and TURN URL
> it tries, and keeps them all for the call (knit-spool ADR 2026-10.y2ph): two URLs on a phone with Wi-Fi and mobile
> data, each with IPv4 and IPv6, make about 8 before pruning, and the third URL S-7.6-25 drops made up to 12. Those
> count against the TURN server's per-user and total quotas (§2.4). libwebrtc's
> `PortPrunePolicy.PRUNE_BASED_ON_PRIORITY` ranks a network's relays (by interface, across every server) UDP over
> TCP over TLS to the server, then IPv6 over IPv4, and releases the lower ones. Ties are kept, which is why two spools
> answering over UDP both keep a relay; a second spool loses its relay on a network only where it ranks lower, as a
> TLS relay against the first's UDP one. A pruned port frees its allocation (a Refresh with lifetime 0) once it holds
> no candidate pair. On the caller that is at once, because pruning happens while it gathers, before the answer. The
> callee has the offer's candidates already, so a relay it prunes after its checks began lingers until the ICE agent
> drops those pairs, about a minute after the call connects.

**Test vector** (proposed §13 addition). `turnSecret = "knit-turn-test-secret"`, `exp = 1800000000`,
suffix bytes `fixture(10, 11)`:

```
user = 1800000000:bmjbsibhfy2tyq2k
pass = yV18C+LcL6biIfL7ZVOuV9xVuoU=
```

### 2.3 Daemon configuration

Environment variables, following the house rule that an invalid value refuses to start:

| Variable                 | Meaning                                                                                                  |
|--------------------------|----------------------------------------------------------------------------------------------------------|
| `SPOOL_TURN_SECRET`      | The shared secret, the same value as coturn's `static-auth-secret`. Unset ⇒ no `turn` in HELLO           |
| `SPOOL_TURN_URLS`        | Comma-separated `turn:`/`turns:` URIs. Required when the secret is set. Two (UDP and TLS) are the recommended set (S-7.6-25) |
| `SPOOL_TURN_TTL_S`       | Credential lifetime, default `300` (60–3600; S-7.6-8)                                                    |
| `SPOOL_TURN_RATE`        | Issues per connection per 10 min, default `4`                                                            |

`SpoolServer` gains one record handler. `ScopeStore` is untouched, because nothing is stored.

| ID           | Requirement                                                                                                                                                                                                                       |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-7.6-25** | A spool SHOULD hand out one TURN URL per transport for each TURN server, in this order: `turn:<host>:3478?transport=udp`, then `turns:<host>:<TLS port>?transport=tcp`. A plain `turn:…?transport=tcp` URL adds an allocation per network to every call, and seldom reaches a network that the other two miss. |

The TLS port is 5349 for a TURN sidecar on a spool's own box, where Caddy holds 443, and 443 for a TURN box of its
own. An operator may still list a plain TCP URL; the client takes what it is given.

### 2.4 Running the TURN server

coturn runs beside the daemon: a sidecar in `deploy/`, `network_mode: host`, because it needs the
relay port range. A starting `turnserver.conf`:

```
use-auth-secret
static-auth-secret=<SPOOL_TURN_SECRET>
realm=<spool host>
listening-port=3478
tls-listening-port=5349
alt-tls-listening-port=443
min-port=49160
max-port=49660
fingerprint
no-cli
no-multicast-peers
no-tcp-relay
user-quota=3
total-quota=16
max-bps=16000
bps-capacity=125000
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=::1
denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
```

- **The `denied-peer-ip` list is not optional.** Without it, any credential holder can use the relay
  to reach the host's loopback, its private network or a cloud metadata endpoint. This is the
  standard TURN SSRF.
- **`max-bps` and `bps-capacity` are bytes per second.** One Opus call leg is about 4 kB/s. On a
  public spool anyone can get a credential, so these caps, not the credential, are what bound a
  stranger: 1 Mbps for the whole relay is about 325 GB/month if saturated around the clock.
- **Bandwidth.** A relayed call is Opus at about 32 kbps each way, about 128 kbps through the server
  counting both legs, which is roughly 58 MB per call-hour. A Nanode's 1 TB transfer allowance covers
  on the order of 10,000 relayed call-hours a month.
- **TLS on 443** gets through networks that block UDP and 3478. Media is DTLS-encrypted either way, and
  TLS additionally hides the TURN username from the network path.
- **Hosted.** A TURN sidecar is an add-on for Knit Hosted. Any metering or billing lives in the control
  plane, never in `daemon/`.

### 2.5 What the spool and the TURN server observe

Proposed §10.1 bullet:

- **The spool** sees that an IP asked for a TURN credential at a given time. It does not see which
  scope the IP is calling from.
- **The TURN server** sees both endpoint IPs of a relayed call, the call's duration and its byte counts.
  It never sees content.
- **Joined**, as one operator can join them, this sharpens §10.1's residual leak. "These k IPs touch the
  same scope" becomes "these two IPs talked for 14 minutes at 09:14". Tor cannot cover it, because media
  is UDP.
- **The "relay all calls" setting** (§3.7) moves the IP-pair exposure from the peer to the TURN
  operator. It does not remove the exposure.
- **Casts** (§2.7) show the spool, live and never on disk, that a DM scope is in a walkie session, which side talks
  when and for how long (about two casts a second per talking side), and both subscribers' IPs. Padding (C-7.7-12) and
  a constant-bitrate encoder keep a chunk's size from saying what was said; nothing hides that something was. On a
  spool that sells casts, the ticket rule (S-7.7-6) ties the payer's daily pseudonym to that timing, which the spool
  could already tie to the payer's subscriptions.

### 2.6 Entitlement tickets and STUN (experimental)

Two additions for a spool whose operator sells TURN (Knit Anywhere; knit-spool ADR 2026-10.bxsg).
Which capabilities need a ticket is the operator's choice, advertised in HELLO (knit-spool ADR 2026-10.h6pt).
A **ticket** is an Ed25519 grant an issuer signs and the spool checks with only the public half, so
the spool can tell a paying caller from anyone else without holding an account. A **STUN list** lets
any client, paying or not, learn its own public address — without which a free phone offers only
private addresses, and a paying peer's relay cannot permit it (coturn keys permissions on IP alone).

The `ent` record, the ticket's claims (`am` included), the spool's rules and the client's rules at the spool
(S-7.6-13 to C-7.6-32) are normative in `docs/SPOOL_PROTOCOL.md` §7.6, and their vectors in its §13. This section
no longer repeats them, so the two cannot drift. What it keeps is a client's exchange with the ticket issuer an
operator names, which that protocol leaves out: the key swap (C-7.6-27 to C-7.6-29) and the free account (C-7.6-33
onward), each specified for any client, not only the reference one. The free tier's values (what a free ticket's
`ab` and `am` are, how many accounts a day, the stamp's difficulty) are the issuer operator's and are not stated
here.

> **How a client keeps C-7.6-24 and C-7.6-32.** It may upload on a connection whose gates lack bit 1, or that holds
> an accepted ticket with bit 1, not past `exp` on its clock, whose `ps` it has not seen spent at that relay.
> Before the `ent` answer it does not upload; with no presentable files ticket it is locked at once, so it sends
> neither `aput` nor the `ahave` that would precede one, and the sender's attachment reads "Nearby only". A `budget`
> refusal marks the (relay host, `ps`) pair spent in memory: the mark outlives a reconnect and a re-presented ticket
> with that `ps`, and a ticket with another `ps` clears it. It is never a refusal verdict on the stored ticket, which
> would stop that ticket's TURN and casts (knit-next ADR 2026-10.8t2y). Under a ticket with `am`, an attachment
> whose `total = ⌈|A| / 49152⌉` exceeds `⌊am / 49152⌋` is never offered to that relay: it is too large there and
> still goes nearby. `|A|` is the plaintext plus 28 B (§4.5's 12 B nonce and 16 B tag), or the plaintext alone for
> a keyless one. A `size` refusal settles that one attachment the same way, and locks nothing.

#### The key swap

| ID           | Requirement |
|--------------|-------------|
| **C-7.6-27** | While the Internet plane runs, a client holding a key for a relay asks that key's issuer for a ticket: when a customer key arrives; for a customer key, whenever that relay's held ticket is missing, not presentable (`SpoolTicketPolicy.presentable`), or less than 6 h from its `exp` on the phone's clock; and, for any key, at once when an upload of its own is locked `missing` or `expired` on a connection to that relay whose `entGates` has bit 1 (C-7.6-24), within C-7.6-28's backoff and park. A free key (C-7.6-37) asks only for such a waiting upload: it is never renewed on a schedule, the 6 h margin does not apply to it, and a held free ticket is presented until its `exp`. After a 200 a client asks no sooner than 1 h later, whatever its key, so neither an account whose `exp` is capped at its end nor a ticket refused on import or at `ent` can make it loop. With the plane off it asks nothing. |
| **C-7.6-28** | A 200 imports the ticket under the host rule. For a customer key, a 401 parks the key until a different one arrives, and a 403 records the end and retries daily; a 403 `ended` also lets the client make a free account at its next locked upload (C-7.6-35), which then takes the key's place. A free key answered 401 or 403 is forgotten (C-7.6-37), and one answered 503 with `retry-after` waits that long. Anything else, a 503 without `retry-after` included, backs off from 1 min to 1 h. |
| **C-7.6-29** | A customer key goes only to the issuer origin it arrived with, only in the `Authorization` header, only over `https://` (`http://` in a debug build). It is stored beside the relay's `?k=` token, under the protection ADR 2026-09.66cw gives that token, and never minted into an invite. Removing the relay forgets its key, issuer and ticket. |

> **The key swap (C-7.6-27 to C-7.6-29).** A ticket is a day's grant, so something has to fetch the next one. An
> issuer's operator hands a customer a relay invite (`docs/RELAY_INVITE.md` §2) that carries, beside the relay, the
> issuer's origin `i` and a customer key `k` (`kak1_…`). Accepting it holds the key for the relay's host name, and
> the client trades it at `POST <i>/api/v1/ticket` with `Authorization: Bearer <k>` and no body (knit-spool-coordinator
> ADR 2026-10.ukvm, as amended by knit-spool-coordinator ADR 2026-10.mrnm). The answers are 200
> `{ ticket, aud, nbf, exp, flags: { turn, files, cast }, endsAt }`, 401
> `{ error: "unknown-key" }` and 403 `{ error: "ended", endsAt }`, all `no-store`; a free account's swap may also
> answer 503 (below). The client acts on the claims inside `ticket`, never the JSON copies, and puts it through the
> same import door as a pasted one, so a ticket whose `aud` is not that relay's host is never held; the connection
> then presents it at once (C-7.6-19). A phone never re-mints a key into an invite: it lends out the customer's own
> quota. Client: knit-next ADR 2026-10.3sk7. Every request with a customer key also carries a device proof, which adds
> the 400 `bad-proof`, 409 `bound-elsewhere` and 426 `upgrade-required` answers ("Device binding", below).
>
> **Why a waiting upload asks.** Without it a phone with a key asks only on its schedule, so a photo sent in the hour
> a ticket lapsed, or before the first one arrived, waits for the next scheduled ask. A locked upload is held and
> re-sent when uploads reopen, so asking then is what gets it there.
>
> **Why a free key's 503 waits for `retry-after`.** The issuer sends it only when the day's cap is reached, and sets it
> to the next UTC midnight: no ticket comes before then, so asking every hour would only add load. A client that
> predates the header backs off as C-7.6-28 says, which costs nothing but the asks.
>
> **Why the 1 h floor holds for every key.** A 200 brings a ticket good for about a day, so no honest ask comes
> within the hour after one. A ticket that is never held (its `aud` is not the relay's host) or is refused at `ent`
> (a clock far off) leaves the upload locked, and without the floor the phone would ask again at once, every time.

#### Device binding

A customer key is for one phone (knit-spool-coordinator ADR 2026-10.473c). The phone proves each request comes from
the same phone with a **device key**: a key pair it makes for that one customer key, which never leaves the phone and
names nobody. The issuer binds the account to the first device key it sees, and refuses another until the person
moves the account. A free key (C-7.6-37) is made on the phone and never leaves it, so it carries no proof.

| ID           | Requirement |
|--------------|-------------|
| **C-7.6-39** | When a customer key arrives, a client makes an ECDSA P-256 key pair for it in the Android Keystore: non-exportable, StrongBox-backed where the phone has StrongBox, one pair per customer key. A key that replaces it, or removing the relay, deletes the pair. The private key never leaves the Keystore, and no backup, log line, bridge reply or crash report carries the public key or a signature. |
| **C-7.6-40** | Every request a client sends with a customer key — the swap (C-7.6-27), the badge (`docs/SUPPORTER_BADGE.md` C-5-3) and the move (C-7.6-44) — carries `Knit-Device-Key` (the public key's SPKI DER), `Knit-Device-Time` (Unix seconds, decimal) and `Knit-Device-Signature` (ECDSA over SHA-256, DER), the two binary values in unpadded base64url. A request with a free key carries none of them. |
| **C-7.6-41** | The signature is over `m = "knit/issuer/v1/device" ‖ 0x00 ‖ path ‖ 0x00 ‖ u64be(t) ‖ SHA-256(k)`: the label and `path` in ASCII with no terminator, `path` the request's path (`/api/v1/ticket`, `/api/v1/badge`, `/api/v1/ticket/move`), `t` the value of `Knit-Device-Time`, and `k` the customer key's ASCII. Each request is signed afresh; a signature is never reused. |
| **C-7.6-42** | A 409 `{ error: "bound-elsewhere", movableAt }` on the swap or the badge means another phone holds the account. The client keeps a held ticket and badge until they lapse, asks nothing on a schedule or for a waiting upload, and tells the person, once, that this Knit Anywhere is in use on another phone, offering to move it here from `movableAt` (an ISO 8601 instant; null means now). It asks again only when the person moves it (C-7.6-44) or a different key arrives, or once with each pair it holds that the issuer has not refused (C-7.6-45). A 426 `{ error: "upgrade-required" }` is backed off from as C-7.6-28's "anything else". |
| **C-7.6-43** | A 400 `{ error: "bad-proof" }` whose response `Date` differs from the phone's clock by more than 60 s is re-signed once with `t` taken from the issuer's clock (the phone's clock plus that difference, kept in memory per issuer origin). Any other 400 `bad-proof`, or a second one, backs off as C-7.6-28's "anything else". |
| **C-7.6-44** | Moving is the person's act, never the client's: only when they choose to move the account to this phone does it send `POST <i>/api/v1/ticket/move` with the customer key, no body and a proof signed for that path. A 200 is the swap's 200 and is imported the same way. A 409 `{ error: "move-too-soon", movableAt }` is shown to the person with `movableAt`. 400, 401 and 403 are read as the swap reads them. It is never retried on a schedule. |
| **C-7.6-45** | A client that holds no device key for a customer key and cannot make one (its Keystore holds no EC P-256 key) sends the request without the three headers and reads the answer as above: an issuer that does not yet require proofs answers it as before, and one that does answers 409 or 426. A client whose Keystore refused to list, look up, make or sign with a device key this time sends nothing, and backs off as C-7.6-28's "anything else". It never makes a pair over one it holds, never deletes one on the Keystore's word, and signs with the pair the issuer last accepted, else its oldest. A 409 `bound-elsewhere` is asked once more with each other pair the client holds, and, after a request without a proof, once with a proof. |
| **C-7.6-46** | A phone restored from a backup, or one given the same invite again, holds the customer key but not its device key, since a Keystore key rides no backup. It makes a new pair (C-7.6-39), and its first swap is answered as any other phone's: 409 `bound-elsewhere` while another phone holds the account, which offers the move. |

> **Why a device key and not the Node ID.** A Node ID is public: it is the hash of the identity key bundle and rides
> every Bluetooth advert, so a bare one proves nothing. Proving the identity key would fix that, but the issuer would
> then hold the person's Node ID against a paid account, and so against the email Polar keeps, which is the link this
> protocol keeps out of every server's hands (§2.5, `docs/SPOOL_PROTOCOL.md` §1, `docs/SUPPORTER_BADGE.md` §1). A
> device key is random and per customer key: the issuer learns that a request comes from the same phone as the last
> one, and nothing about which phone, nor that two accounts share one.
>
> **Why the path and the key are signed.** The path stops a swap's proof from being replayed as a move, and the key's
> hash stops a proof for one account from being replayed against another. A timestamp keeps the swap at one round
> trip; the issuer accepts it within ±300 s, the ticket's own skew (`docs/SPOOL_PROTOCOL.md` S-7.6-14).
>
> **Why moving is the person's act and has a cooldown.** A move takes the account from the other phone, so only the
> person may start it. The issuer's cooldown, which the client reads from `movableAt` and never hardcodes, stops two
> people from taking turns with one key. A lost phone inside the cooldown goes through the issuer's support, which
> reissues the key.
>
> **Why a Keystore refusal sends nothing, and no pair is ever made over another.** An issuer answers a bound account
> asked without the bound key's proof 409, and 409 parks the key until the person moves it, behind the issuer's
> cooldown. The Android Keystore refuses now and then (a busy keystore2, a key-blob upgrade that fails once), and
> below API 31 it can read a dead daemon as "no key". If the client sent unsigned on a refusal, or made a pair over one
> it could not read, a hiccup would cost the person the account for days. So a refusal costs one backoff, a new pair
> goes under a new alias beside the old one, and the pair the issuer accepted, or else the oldest, signs: once the old
> one reads again, it is the one that answers.
>
> **What it does not stop.** A rooted phone, or software that can drive the first phone's Keystore, can still use the
> key there. It stops a forwarded invite and a second phone scanning the QR code.
>
> **Records.** Issuer: knit-spool-coordinator ADR 2026-10.473c, which holds the cooldown, the two phases
> (`optional`, then `required` before the issuer sells) and the privacy policy's wording. Client: knit-next ADR
> 2026-10.wk3m.

#### Free accounts

A phone with no key can still send small files through a relay whose `entGates` has bit 1, if the client knows a
**free issuer** for that relay. It makes an anonymous **free account** there, paid for with a proof-of-work stamp,
and swaps its key for tickets exactly as a customer key is swapped. A free account's ticket grants files only
(`fl` 2), with the issuer's own `ab` and `am` for that audience, so what a free phone may send is bounded by those
claims and by the spool (S-7.6-22, S-7.6-31). The issuer keeps no per-request log, and keeps an address only as a
daily-keyed hash in its per-address counter, dropped each day.

Every answer is JSON with `cache-control: no-store`, and every body is at most 1 KiB:

| Request | Answer | Meaning |
|---------|--------|---------|
| `GET <issuer>/api/v1/account?aud=<host>` | 200 `{ aud, bits, day }` | The stamp's difficulty in bits, and today's UTC day, `⌊unix s / 86400⌋` |
| | 503 `{ error: "unavailable" }` | That audience has no free tier turned on, or is unknown |
| `POST <issuer>/api/v1/account`, body `{ aud, day, nonce, n }` | 201 `{ key, aud }` | A new free account. `key` is `kak1_…`, the same format as a customer key |
| | 400 `{ error: "bad-stamp", bits, day }` | For an audience whose free tier is on: the stamp is short of `bits`, stale, spent or malformed, or the rest of the body is |
| | 429 `{ error: "rate-limited" }`, `retry-after` in seconds | The address has made its day's accounts for that audience; the count is per address and per audience |
| | 503 `{ error: "unavailable" }` | The audience's free tier is off or unknown, or the day's cap is reached (then with `retry-after`, to the next UTC midnight). So is a body with no readable `aud`: another content type, over 1 KiB, not JSON, or `aud` missing or malformed — with no audience there are no `bits` for a 400 |
| `POST <issuer>/api/v1/ticket`, `Bearer <free key>` | 200, 401, 403 | As for a customer key. The ticket carries `fl` 2 and the audience's `ab` and `am`; `endsAt` is the account's rolling end, which each swap moves forward, at most once a day. An account unused that long ends: 403 `ended` |
| | 503 `{ error: "unavailable" }` | Free accounts only: the audience's free tier is off, or the day's cap is reached and this account has not been ticketed today (then with `retry-after`, to the next UTC midnight). A customer account is never answered 503 for either |

`nonce` is 16 random bytes in unpadded base64url, and `n` a non-negative integer below 2^53. The stamp:

```
h      = SHA-256("knit/issuer/v1/account" ‖ u64be(day) ‖ nonce ‖ u64be(n))   // label: ASCII, no terminator
valid  ⇔ leadingZeroBits(h) ≥ bits, counted as SPOOL_PROTOCOL.md §8 counts them,
         day is today or yesterday (UTC) at the issuer, and (day, nonce) is spent once
```

**Test vector**, copied from knit-spool-coordinator `feat/free-tickets` @ `05b7ec4` (`packages/core/src/free-tier.test.ts`,
computed there with Python's `hashlib`): `day = 20743`, `nonce = 00 01 … 0f` (`AAECAwQFBgcICQoLDA0ODw`). The first `n`
whose `h` has at least 16 leading zero bits, and the first with exactly 15, one short of 16:

```
input  = 6b6e69742f6973737565722f76312f6163636f756e74 0000000000005107 000102030405060708090a0b0c0d0e0f
         000000000002ab06                                        // label ‖ u64be(20743) ‖ nonce ‖ u64be(174854)
n      = 174854   h = 00000d2a3c4d623b4eb90ef544c507c7760cf1a4dbf5968173a09277f841510c   // 20 zero bits
n      = 96100    h = 00018266ffcdc658d739b8a2c7ff73a32f78a0af100c402e07fb99a0d57434df   // 15: refused at 16
```

The issuer checks a `POST` in this order: the audience (503); the stamp's form, day and bits (400); then, in one
transaction, the day's cap (503), the spent stamp (400) and the address (429). A refusal spends neither the stamp nor
the address's count.

| ID           | Requirement |
|--------------|-------------|
| **C-7.6-33** | A client makes a free account for a relay only at a free issuer origin it is configured with for that relay's host name, under C-7.6-29's origin rule. It never takes one from a spool, a peer or an invite: an invite's `i` comes with a customer key and is that key's issuer. The reference client knows one, `https://anywhere.getknit.app` for its seeded `lax.spool.getknit.app`; its debug build may set one for any host. |
| **C-7.6-34** | Free tickets are on for a relay until the person turns them off. The first time an upload of theirs waits on that relay for want of a ticket, a client tells the person, once, that the phone contacts that host, named, on each day it sends files through the relay, and keeps telling until they acknowledge it or turn it off, even after the account is made. Once turned off it asks the issuer nothing for that relay; turning it off forgets the free key. |
| **C-7.6-35** | A client with no key for a relay, or with a customer key last answered 403 `ended`, where C-7.6-33 gives it a free issuer and the person has not turned free tickets off there (C-7.6-34), makes a free account when an upload of its own is locked `missing` or `expired` on a connection to that relay whose `entGates` has bit 1, and at no other time — so never for a relay the person has turned off, nor with the plane off. It `GET`s the parameters for the relay's host name, mines a stamp, `POST`s it, holds the 201's key, and swaps it for a ticket at once (C-7.6-27). An answer whose `aud` is not that host name is not acted on. With the plane off, or with no route, it waits; a phone that comes online with an upload still locked asks then. |
| **C-7.6-36** | A client mines with the `day` and `bits` of the 200 it just got, never its own clock's day, and a fresh `nonce` for each account request, counting `n` up from 0. A 400 `bad-stamp` is re-mined once, with the `bits` and `day` it carries; a second one backs off as a 503 does. |
| **C-7.6-37** | A free key is held in the relay host's one key slot, marked free. A customer key that arrives for that host replaces it. It goes only to the issuer origin that made it, under C-7.6-29's other rules, and never rides a backup. Answered 401 or 403, it is forgotten with its state, and a new account is made at the next locked upload, no sooner than the next UTC day. |
| **C-7.6-38** | On the account route, a 429 waits `retry-after` seconds, and a 503 waits `retry-after`, or without one 1 h, doubling to 24 h. Anything else, a failed request included, backs off from 1 min, doubling to 1 h, as C-7.6-28's "anything else" does: the issuer never answered, so nothing was asked of its cap. |

> **Why a free key asks only for a waiting upload.** The issuer's daily cap counts the accounts it tickets in a day.
> A free phone that renewed on a schedule would count every day, whether or not it sent anything, and turn away the
> people who do. Asking only for a waiting upload makes the cap count the day's free senders, not free users, and
> the issuer hears from a free phone only on a day it sends something through that relay.
>
> **Why a stamp.** The account route is unauthenticated, so creating many accounts is the attack, and that is where
> it is paid for: a few seconds of hashing on a phone, beside the issuer's per-address limit and its daily cap. It
> is a speed bump, not a wall. It reuses §8's hashcash family under its own label, and binds the issuer's day, so a
> stamp cannot be mined ahead or spent twice.
>
> **Why the notice.** The relay's own consent covers the relay. A free account makes the phone contact a second host
> the person did not name, which links one day's pseudonym to an account (`docs/SPOOL_PROTOCOL.md` §10.1), so the
> person is told, by name, and can turn it off. It informs rather than asks, so small photos go through by default (knit-next
> ADR 2026-10.s3ep's 2026-10-20 amendment).
>
> **Why a free key is not backed up.** It is anonymous and cheap to make again. A restored phone makes its own.
>
> **Records.** Client: knit-next ADR 2026-10.s3ep. Issuer: knit-spool-coordinator ADR 2026-10.3aqb, which holds the free
> tier's values and the privacy policy the route keeps.

### 2.7 Casts: a walkie's chunks through a spool (experimental)

A walkie session (§3.11) streams a chunk about every half second, and over the radios those are `talk` frames nobody
custodies. Two phones with no radio path to each other have nothing to carry them. A stored blob is the wrong
carrier: each one costs a digest entry, an eviction, a tombstone, a place in every listing and a pull per member per
heal round, and two a second push a scope's real history out within minutes. A **cast** is the other carrier: one
sealed frame that the spool hands to the scope's other live subscribers and keeps nothing of. It is written to drop
into `docs/SPOOL_PROTOCOL.md` as §7.7 (knit-spool ADR 2026-10.3wdy; here, ADR 2026-10.79yb's sixth amendment).

| Record  | Direction | Fields                                   | Semantics                                                                                   |
|---------|-----------|------------------------------------------|---------------------------------------------------------------------------------------------|
| `hello` | s→c       | `…, cast: { maxBytes: Int, rate: Int }`  | Present when the spool fans out casts: `maxBytes` caps a cast's `data`, `rate` is casts per second per connection, with a burst of four times that |
| `cast`  | c→s       | `t, scope: bstr32, data: bstr`           | One sealed frame for the scope's other subscribers. No `q`, and never answered              |
| `cast`  | s→c       | `t, scope: bstr32, data: bstr`           | The same record, relayed unchanged to every other subscriber of the scope, caster excluded  |

| ID           | Requirement |
|--------------|-------------|
| **S-7.7-1**  | A spool that fans out casts advertises `cast` in HELLO; one that does not MUST omit it. Presence is the signal. |
| **S-7.7-2**  | A spool MUST NOT answer a cast, with `ok` or with `err`, whatever becomes of it. B-7.1-8 gains its one exception: a client record that carries no `q`. |
| **S-7.7-3**  | A cast is fanned out only when its connection holds a `sub` for the scope, its `data` is at most `maxBytes`, it is within the per-connection, per-IP and spool-wide budgets, the scope is not the commons, and the ticket rule (S-7.7-6) holds. Anything else is dropped, and MAY be counted. |
| **S-7.7-4**  | A spool MUST NOT store a cast or let one touch a stored scope: no blob, digest fold, tombstone, listing, `maxScopes` hold, creation gate or disk write. A cast for a scope the store has shed still fans out while subscribers hold the scope. |
| **S-7.7-5**  | Fan-out is best-effort, and a dropped copy MUST NOT count toward the slow-consumer close: a subscriber whose channel is full loses that cast and nothing else. |
| **S-7.7-6**  | On a connection whose HELLO carried `ent` with bit 3 in `entGates`, a cast fans out only while some live subscriber of its scope on this spool holds an accepted ticket with bit 3 of `fl` (`FL_CAST = 8`): the caster first, then the others. Liveness and skew are S-7.6-14's. A spool without a ticket key admits any subscriber's cast. |
| **S-7.7-7**  | A dropped cast MUST NOT be a strike. A flood still pays the per-record budget that every record pays, and that budget strikes. |
| **S-7.7-8**  | A spool MUST NOT log or persist a cast, the scope it names, a ticket or a pseudonym. Metrics MAY count casts, copies fanned out and drops by reason. |
| **C-7.7-9**  | A client MUST NOT cast to a spool whose HELLO omitted `cast`, and MUST keep within its `maxBytes` and `rate` unprompted, because a refusal is silent. On a spool whose `entGates` has bit 3 it casts only while it holds a cast ticket there, or its peer said it does (`CallPayload.ct`, §3.2). |
| **C-7.7-10** | A client casts only into a scope whose SUB that spool has answered. |
| **C-7.7-11** | Every cast is signed: its `sig` is the Ed25519 frame signature over `signed`, whatever the same frame's radio copy carries. |
| **C-7.7-12** | The seal is the one below: its own version byte, nonce label and aad, the plaintext padded to a multiple of 128 bytes. An opener refuses any plaintext but the canonical one: whole blocks, a nonzero length inside them, at most one block of padding, all of it zero. |
| **C-7.7-13** | The cast set: on a pair's own DM scope (never a pair scope, §3.5, a group or the commons), `type = talk`, no group, sender and recipient the scope's two members in the direction of travel; then the signature verifies against the pinned sender key (C-4.4-1's step). Nothing else is sealed into a cast or accepted out of one. |
| **C-7.7-14** | A received cast re-enters delivery as a frame for this phone alone (`ttl = 0`, `relay = false`): never through §9.4, and never custodied, accounted (§9.6), quarantined (§9.3) or taken as presence. A cast that fails is dropped and forgotten. |
| **C-7.7-15** | One frame may arrive over the radios and from several spools at once. The receiver keeps the first copy by frame id. |

```
pt    = sig(64) ‖ u16be(|signed|) ‖ signed ‖ 0x00…            // padded to a multiple of 128 bytes
nonce = HKDF(ikm = nonceKey, info = "knit/scope/v1/cnonce" ‖ SHA-256(pt), L = 12)
aad   = "knit/scope/v1/cast" ‖ scopeId                          // 50 bytes: a frame's is 45, a chunk's 52
data  = 0x04 ‖ nonce(12) ‖ AES-256-GCM(key = sealKey, nonce, pt, aad)
```

A 480 ms walkie chunk's frame is about 693 bytes, so its plaintext pads to 768 and its cast is 797 bytes: about
1.7 KB/s at two a second.

> **Why no answer.** The shipped client reads a scoped `err` with no `q` as a refused `sub`, and would drop the
> conversation. A `q` would put an ack, a 30 s pending slot and the silent-request strike (C-9.1-4) on every chunk. A
> chunk is live or lost, and HELLO already states every bound a refusal could have named.
>
> **Why a seal of its own.** With `0x04` and its own aad, a cast never opens as a frame blob (§4.3) or a chunk (§4.5),
> and neither of those opens as a cast. So nothing can slip a custodial frame into the one lane that skips custody and
> the digest, or a cast into a scope's digest. It is deterministic for §4.3's reason, though nothing dedups a cast: the
> same frame cast to two spools is the same bytes.
>
> **Why signed when the radio copy is not.** §4.4's invariant holds on this plane too: every frame the Internet carries
> is checked against the pinned key before any ratchet work. The 64 bytes land only on the spool's copy, which has no
> Coded PHY budget to keep.
>
> **Why one payer unlocks the scope.** As one payer is enough for TURN (§2.6). A ticket on every caster would make a
> free friend's walkie one-way. It ties the payer's pseudonym to nothing the spool had not already tied it to.
>
> **Why `push` with a marker was rejected.** An older spool ignores an unknown key (B-2-2) and stores the chunk. The
> HELLO gate is what makes an unstored lane safe to add.

Proposed §12.2 rows: casts off by default (`rate` 0, omitted); a suggested `rate` of 4 a second, burst 4×; `maxBytes`
2048; a spool-wide budget of 200 casts a second, burst 4×.

**Test vectors.** One record for both directions, with `scope = 00 01 … 1f` and `data = a0 a1 … af`; a HELLO with `ent`
and `cast = {2048, 4}`; and §2.6's ticket fixture with `fl = 9` (TURN and cast):

```
cast      = a3617464636173746573636f70655820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
            646461746150a0a1a2a3a4a5a6a7a8a9aaabacadaeaf
helloCast = a461746568656c6c6f61760163656e74f56463617374a2686d61784279746573190800647261746504
tk        = a7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200
            636578701a6b4b238062707350000102030405060708090a0b0c0d0e0f62666c09
sig       = 9f0d82c5e15ddc486fc693a848dd2e937df32e505f22631dde6a49f82435d147
            b64da30b5ac432ef18ee9e2349c4c8915cc8e9d5a3e829f95c2784bc5a5f530f
```

The seal on §13's DM fixture (`dmSealKey`, `dmNonceKey`, `dmScopeId`), with `sig` and `signed` the `sealBlob` inputs
(64 and 40 bytes):

```
castSeal  = 0486dfeaff6b30c3cebc549d35183a43023f9b0c354d6f49d5cc1bac81def2774409f7f4ef746f0f4491271f09f7d2635d25
            34931edda7a37adf7b73bd7c0e4a7a08e381a0861afaa8d7bd57436bf0bf26a80fd14a99a9aeadc356cc58a785ee926ca293
            e1632be7c7da48dbbb51a7949e6743e392774d9f469d69cdd86b02f9c71f91415ca1b114fe8db955ad94fa4679cffb59ade4
            6563a3650fd4b3
```

## 3. Signaling (client)

### 3.1 Carriage

```
CTL_CALL = 10        MessageContent.cl: CallPayload?
CAP_CALL = 0x4000    profile capability bit
```

| ID          | Requirement                                                                                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.1-1** | Call signaling is a `MessageContent.ctl` value carried in an ordinary v2-sealed DM. It is never persisted as a message, never notified as one and never receipted. |
| **C-3.1-2** | A client MUST send an `OFFER` only toward a pinned profile carrying `CAP_CALL`, because a build without it consumes the ctl as a silent no-op.                   |
| **C-3.1-3** | Signaling rides every path a DM rides: the mesh, and every spool of the DM scope. A client MUST NOT special-case its routing.                                      |

> **Why not a dedicated call scope with a short `ttlMs`.** It would need a new scope kind in §3 and a
> new derivation, would not ride the mesh, and would buy only the early expiry that §3.4's `sentAt`
> window already gives. The cost of reuse is a few signaling blobs sitting in the DM scope for its 48 h
> retention. They are opaque, and §3.6 bounds them to a handful per call.

### 3.2 Payload

```
CallPayload {
    id:   String     // 128-bit random, base32; one per call attempt, both directions
    ph:   Int        // phase, below
    gen:  Int = 0    // negotiation generation; > 0 is an ICE restart (§3.8)
    sdp:  String?    // OFFER, ANSWER
    cand: [String]?  // CANDS: "candidate:…" lines; an empty list = end of candidates
    rsn:  Int?       // DECLINE, CANCEL, END
    m:    Int = 1    // media bitmask: 1 audio; 2 video reserved; 4 walkie-talkie (§3.11)
    dw:   DirectInvite?  // OFFER: the Wi-Fi Direct group the caller will host (§3.10)
    dx:   Int?       // RINGING, ANSWER: 1 = the callee is joining that group (§3.10)
    ct:   Boolean = false  // OFFER, ANSWER beside the walkie bit: the sender holds a cast ticket (§2.7)
    tk:   TalkChunk? // TALK: one chunk of a walkie burst, {b, q, a, e} (§3.11)
}
```

| `ph` | Name      | Sender | Meaning                                                                    |
|------|-----------|--------|----------------------------------------------------------------------------|
| 1    | `OFFER`   | caller | Start a call (`gen = 0`) or restart ICE (`gen > 0`, §3.8)                  |
| 2    | `RINGING` | callee | The offer reached a device that is alerting. The caller UI moves from "Calling" to "Ringing" |
| 3    | `ANSWER`  | callee | Accept, carrying the answer SDP for this `gen`                             |
| 4    | `DECLINE` | callee | Refuse, with `rsn`                                                         |
| 5    | `CANCEL`  | caller | Withdraw before an answer, with `rsn`                                      |
| 6    | `END`     | either | Hang up after connecting, with `rsn`                                       |
| 7    | `CANDS`   | either | Late candidates for this `gen` (§3.6)                                      |
| 8    | `TALK`    | either | A chunk of a walkie burst, in a `talk` frame (§3.11)                       |

Reasons, in one registry as `TransferPayload` has: `1` user · `2` busy · `3` timeout · `4` no route
(ICE failed or no network) · `5` media unsupported · `6` error.

### 3.3 State machine

```
caller: Idle ─OFFER→ Calling ─RINGING→ Ringing ─ANSWER→ Connecting ─ICE ok→ Connected ─END→ Ended
                        └──────────────┴── 45 s / user ─CANCEL→ Ended
callee: Idle ─OFFER (fresh)→ Alerting ─user─ANSWER→ Connecting ─ICE ok→ Connected ─END→ Ended
                                 └──user / busy─DECLINE→ Ended
both:   Connected ─path lost→ Reconnecting ─ICE ok→ Connected
                                   └── 20 s ─END no_route→ Ended   (§3.8)
```

| ID          | Requirement                                                                                                                                                            |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.3-1** | A caller MUST send `CANCEL rsn=timeout` after 45 s without an `ANSWER`, or `CANCEL rsn=no_route` when no `RINGING` came back either (C-3.3-6). |
| **C-3.3-2** | A callee already in a call answers a new `OFFER` with `DECLINE rsn=busy` and does not alert.                                                                           |
| **C-3.3-3** | Connecting that does not reach ICE `connected` within 20 s ends with `END rsn=no_route`.                                                                               |
| **C-3.3-4** | Every phase is idempotent on `(id, ph, gen)`. Custody and the spool can deliver a frame twice, and the second copy MUST change nothing.                                |
| **C-3.3-5** | Until a `RINGING` (or an `ANSWER`) comes back, a caller looks every 2 s at whether anything it can see reaches the peer, and says so on the call screen while nothing does. It withdraws with `CANCEL rsn=no_route` once nothing has for 15 s. |
| **C-3.3-6** | A caller that something reaches (C-3.3-5) but that has no `RINGING` 8 s in says it is waiting for the peer's phone. With no `RINGING` at its ring-out, the call ends `no_route`, since nothing shows the peer's phone rang. |
| **C-3.3-7** | A callee whose person switched off incoming calls of the offer's kind (live or walkie, the Calls screen's two switches) answers `DECLINE rsn=timeout` at once, which the caller shows as "No answer", and does not alert, prefetch TURN or record a missed call, busy or not. A glare offer (C-3.5-1) is still taken. ADR 2026-10.fcrd. |

> **What counts as reaching the peer (C-3.3-5).** Any one of these, read off state already in memory (`call/CallReach`,
> `CallReachRule`), never on the inbound path:
>
> - a live link to the peer;
> - the peer's own frames heard through other phones lately, with a link of this phone's own to flood on;
> - a Wi-Fi Aware sighting a link can be raised on (`MeshTransport.bulkRouteTo`);
> - the peer's own recent traffic on a connected spool we share a scope with (`spoolPresentPeers`, the presence dot's
>   45 min), so a relay the peer is not on never counts.
>
> A LoRa board's reach does not count: a live OFFER's SDP is far past the plane's three-packet ceiling, and a walkie
> chunk never rides it. A quiet peer on a shared relay leaves no evidence, but its RINGING comes back within seconds,
> and a RINGING ends the look. Device-observed 2026-10-14: a P9 on one relay called a Moto on another, with the Moto's
> Bluetooth wedged and no Wi-Fi Aware on its image. The call rang out its full 45 s, and the walkie path read "Through
> the relay". Fifteen minutes later the Moto's Bluetooth stack wedged outbound only: the link stood both ways in the
> tables, the OFFER and the CANCEL reached it and it alerted, but its RINGING never left the phone. That is C-3.3-6's
> case: a link is evidence the OFFER can go, never that the RINGING can come back. Either withdrawal is a CANCEL, so an OFFER a carrier brings the peer later is recorded as missed (C-3.4-2),
> and the caller's record reads "Couldn't reach them" (§3.12).

### 3.4 Staleness and ordering

Signaling rides store-and-forward paths, so a frame can arrive late, twice or out of order.

| ID          | Requirement                                                                                                                                                                                         |
|-------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.4-1** | A callee MUST NOT alert for an `OFFER` whose envelope `sentAt` lies outside `[now − 60 s, now + 60 s]`.                                                                                             |
| **C-3.4-2** | A stale `OFFER` for which no `CANCEL rsn=user` or `ANSWER` has been seen is recorded as a **missed call**. A `CANCEL rsn=user` that arrives first suppresses it.                                      |
| **C-3.4-3** | A terminal phase (`DECLINE`, `CANCEL`, `END`) for an unknown `id` MUST be remembered as a tombstone, at least for the custody TTL and in a bounded set, so a later-arriving `OFFER` for that `id` never alerts. |

> **Why the tombstones outlive the process.** A spool keeps a DM scope's frames for 48 h, and a carrier's custody keeps
> them for 24 h, so a phone can pull a call's `OFFER` long after it heard the call end, and after a process death in
> between. The Android client keeps its tombstones in a small journal for 48 h (ADR 2026-10.3tee's fifth amendment),
> reads it before it judges its first signal, and never carries it in a backup.
>
> **Why `sentAt` and not a payload expiry.** `sentAt` is signed inside the `RelayEnvelope` and is what
> `CTL_TRANSFER`'s `STALE_OFFER_MS` already reads. A payload `exp` would be the same sender clock with
> a second field to keep consistent.
>
> **Missed calls fall out for free.** A callee that was offline pulls the stale offer on reconnect,
> and C-3.4-2 turns it into the missed-call row every phone app shows.

### 3.5 Glare

Each side sends an `OFFER` to the other before seeing the other's offer.

| ID          | Requirement                                                                                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.5-1** | The offer from the **lower node id** survives. The higher side MUST drop its own attempt without sending `CANCEL`, and MUST answer the surviving offer at once without alerting. |

> **Why auto-answer.** Both users just asked to talk to each other. A `CANCEL` would race the surviving
> offer and could suppress it on a peer that sees them out of order (C-3.4-3).

### 3.6 Candidates

| ID          | Requirement                                                                                                                                                                         |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.6-1** | A client MUST NOT trickle candidates one frame each. It gathers for up to 1.5 s, or until gathering completes, and embeds the candidates in the SDP.                                 |
| **C-3.6-2** | At most one `CANDS` frame per side per `gen` carries the late ones, usually a slow relay allocation.                                                                                |

> **Why.** Every signaling frame is a ratchet step, a spool blob kept 48 h and, on the mesh, a flood.
> Per-candidate trickle is 10–20 frames per call. Batched, a whole call is about five: `OFFER`,
> `RINGING`, `ANSWER`, at most one `CANDS`, then `END`. An SDP is 2–5 KB, well inside `maxBlob` (64 KiB)
> and the link layer's 512 KiB.

### 3.7 Media and identity binding

| ID          | Requirement                                                                                                                                                                                              |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.7-1** | Media MUST be DTLS-SRTP. SDES and plain RTP MUST be refused.                                                                                                                                             |
| **C-3.7-2** | The remote DTLS certificate MUST match the `a=fingerprint` in the SDP that arrived in that peer's sealed `CallPayload`, and SDP from any other source MUST NOT be applied.                              |
| **C-3.7-3** | With "relay all calls" on, a client sets `iceTransportPolicy = relay` **and** MUST strip its own host and server-reflexive candidates from the SDP it sends. Otherwise its own IP still reaches the peer. |
| **C-3.7-4** | Opus is mandatory; `bundle` and `rtcp-mux` are required.                                                                                                                                                 |
| **C-3.7-5** | *(Experimental.)* A client MAY caption the peer's audio on the phone as it plays (below). Nothing about it is on the wire, and the text MUST NOT be sent, stored, logged or kept past the next call. |

> **"Relay all calls" on Android** is a switch on the Calls screen (Settings → Calls), off by default, shown only by a
> build that wires calls. It also keeps every call off a Wi-Fi Direct group (§3.10), whose pair would show a neighbour
> the phone's address, and leaves the walkie (§3.11) alone, which carries no address. A call connects only where a TURN
> server is available: one a spool issues, or the person's own (§3.14). So the switch turns on only while one is: a
> connected spool that issues this phone TURN (`SpoolStatus.turnForUs`: TURN advertised, and not gated or a ticket with
> bit 0, which a free ticket lacks), or an own TURN server switched on and valid. Left on after that goes, it can still
> be turned off, and its line says live calls cannot connect meanwhile. A call is never begun that it would strand
> (`TurnSource.turnAvailable`, read as a call is placed and as an offer lands, plus any issued credential still live):
> a live call is refused before anything is sent (`CallRefusal.NO_RELAY`), an automatic one is a walkie where the peer
> runs one, an incoming live offer is declined `no_route` without ringing and left as a missed call that says why, and
> a TURN fetch that still issues nothing ends the call before its OFFER or ANSWER.
>
> **Captions on Android (C-3.7-5; ADR 2026-10.nz3g, its 2026-10-16 amendment).** Where `BuildConfig.STT` and
> `BuildConfig.LIVE_CALLS` are on (ADR 2026-10.swvp), a
> live call's screen has a CC button beside Mute and the audio route, off until the person turns it on and remembered
> from call to call (`TranscriptStore.liveCaptions`), drawn only while the Calls screen's "Transcripts and captions"
> switch is on. On, the peer's words show in the card slot as they play, large, with no speaker or times: what the
> model has settled as it will stay, and its guess at the rest dimmed. This phone's own voice is not captioned: the
> module's recorded PCM is before WebRTC's echo canceller, so on the loudspeaker it holds the peer's voice too.
>
> - **The audio.** The peer's received track, through `AudioTrack.addSink` (`WebRtcCallMedia`), decoded and before the
>   mix: 16-bit PCM at the track's rate, averaged down to 16 kHz (`BurstPcm`) on the audio thread and fed to the model
>   off it (`CallCaptioner`). The sink comes off before the connection is disposed. Each session has its own source and
>   only the newest is taken, so a glare's two sessions never double the audio.
> - **Split at pauses.** A live call has no talk button to bound its speech, so its streams always run the model's voice
>   detector (`SpeechToText.open(continuous = true)`), whatever the walkie's line mode. A look then decodes only the
>   stretch still open, where a whole-line stream would decode up to 30 s again every half second, and silence costs the
>   detector alone. A look that finds every stretch closed ends the run: its words become a settled line and a fresh
>   stream takes the audio after (`CaptionPolicy.step`). A run that has heard nothing for 30 s is let go unheard; one
>   still talking at 60 s is finished where it stands. A line appears with its first words, so a quiet call shows none.
> - **When it stops.** More than 8 s of audio waiting for the model, once its stream is open, means the phone cannot keep
>   up: the captions stop for the call and say so (`CaptionStatus.BEHIND`), keeping the lines they had. A model that
>   will not run stops them the same way (`FAILED`). Turning CC off and on tries again. While the model is not on the
>   phone the audio is dropped and the card offers the download, its Not now turning CC off.
> - **The earpiece.** The screen is dark at the ear (§3.13), so on the earpiece the card says captions read better on
>   the loudspeaker or a headset. The model keeps running, so the last line is there when the phone comes away.
> - **The model** is the walkie's (C-3.11-9): the same download, lease, guard, key terms and one call at a time. It is
>   built split for a live call and whole for a walkie, so a walkie after a live call reloads it. It is let go when the
>   call's last media session closes.
>
> **Why C-3.7-2 is the whole E2E argument.** The SDP is authenticated by the frame signature and the DM
> ratchet, so its fingerprint is the peer's own. DTLS then proves that whoever terminates the media
> holds the matching key. A TURN server, a spool or a network attacker can drop or delay a call, but
> cannot read or join it. Fresh ECDHE per call gives forward secrecy independently of the ratchet.

### 3.8 Network changes: reconnecting by ICE restart

A move from Wi-Fi to cellular, a lost Wi-Fi Direct group or a dead TURN allocation kills the selected candidate pair.
The call does not end there. It is **Reconnecting** for a window, and the caller restarts ICE under the same call id
with `gen + 1` and fresh ICE credentials. The DTLS session and the call `id` survive. ADR 2026-10.3tee's third
amendment records why this is the one recovery mechanism, with no redial under a new id.

| ID          | Requirement                                                                                                                                                                                                          |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.8-1** | A connected call whose media reports the path lost (ICE `disconnected` or `failed`) is Reconnecting. ICE reaching `connected` again makes it Connected, and its connect time is unchanged.                           |
| **C-3.8-2** | Only the **caller** restarts. It sends an `OFFER` with `gen` one above its last, carrying an ICE restart's SDP gathered as C-3.6-1 says, from ICE servers fetched again. The callee never sends a restart, so two never cross. |
| **C-3.8-3** | The caller restarts 2 s after the loss, unless ICE healed meanwhile, and once more 8 s later while still Reconnecting. It sends no more than two per loss.                                                           |
| **C-3.8-4** | A callee answers a restart `OFFER` only for the call it is in, from that call's caller, while connected or reconnecting, with a `gen` above the last it answered and a signed `sentAt` within 20 s of now. It never alerts for a restart and never declines one; it drops any other. |
| **C-3.8-5** | The caller applies only the `ANSWER` for its latest `gen`. It applies that one even if ICE healed while the answer was in flight.                                                                                   |
| **C-3.8-6** | A call still Reconnecting 20 s after the loss ends with `END rsn=no_route`, and ICE `failed` meanwhile ends nothing. A callee's window starts again with each restart it answers while reconnecting. A callee still connected answers without entering Reconnecting. |
| **C-3.8-7** | No `CANDS` follows a restart. Its description carries what gathering found, and the second restart is the second chance.                                                                                            |

| Timer | Value | Why |
|---|---|---|
| first restart | 2 s | a blip ICE heals on its own costs no frame |
| second restart | 8 s after the first | a network still coming up at the first has settled |
| reconnect window | 20 s | long enough for both restarts to connect, short enough that a dead call is not held open |

> **Why the caller alone.** Either side could notice a loss first, and two restarters would need a glare rule over
> store-and-forward signaling. With one, a callee-side change costs only the seconds the caller's ICE takes to see it.
> A callee that was offline when the restart went out pulls it from the spool once its network returns, inside the
> window.
>
> **Why no redial.** A dead peer, a session reset mid-call (ctl frames are never re-sealed, ADR 2026-10.gcrd) and a
> dead network all end the same way: the window runs out. A redial under a new id would ring again, glare with the
> user's own redial and leave two records for one conversation. A call that ends this way is recorded as dropped
> (§3.12), and its call screen offers Call back.

An older build ignores a `gen > 0` `OFFER`, so its restart goes unanswered and the call ends at the window.

### 3.9 Ringing

Android rings only while the callee holds a live spool connection. `event` fan-out then delivers the
`OFFER` in well under a second. Otherwise the offer waits and becomes a missed call (C-3.4-2). iOS
background ringing is out of scope until ADR 2026-10.3tee's push question is settled.

### 3.10 A call between neighbours: the Wi-Fi Direct leg

When caller and callee are mesh neighbours, the call's media rides a Wi-Fi Direct group the two phones raise —
no spool, no STUN — and with an Internet path as well, the group wins and the Internet path is its fallback. The
group is the direct transfer's (ADR 2026-09.wtmz): raised by credentials alone, no discovery, no dialog, with Wi-Fi
Aware paused while it is up and Bluetooth carrying the mesh. ADR 2026-10.kxnw records why.

```
DirectInvite {           // CallPayload.dw — every field optional, so a broken invite costs only itself
    s: String?           // group name, DIRECT-kn-xxxxxxxx
    p: String?           // passphrase
    o: Int?              // the lane's TCP port on the group
    k: String?           // the lane's 32-byte secret, base64
}
```

- **C-3.10-1** The caller puts `dw` in its OFFER only when the callee is a mesh neighbour whose pinned profile
  carries `CAP_DIRECT_TRANSFER`, the radio is free and usable, this phone may host a group (ADR 2026-10.w3dw: never a
  generic system image over an older vendor, nor a phone latched after dying while hosting), and neither relay-only nor
  direct mode `off` is set. Minting raises nothing. A phone that may not host still joins a caller's group.
- **C-3.10-2** A callee that can join answers `dx = 1` on its RINGING and joins while it rings, after a grace that
  lets the RINGING leave before the radio does. It joins with an **IPv4 lease**: libwebrtc never gathers on an IPv6
  link-local. A callee that may not join off screen (API 29-32) or runs direct mode `answer` joins when it answers,
  and says `dx = 1` on its ANSWER. A relay-only callee never joins: its checks would show its real address.
- **C-3.10-3** The caller hosts on the first `dx = 1` whose signed `sentAt` is under 8 s old, on RINGING or ANSWER,
  once per call. A peer that never says it — an older build, a callee that could not join, a glare loser — makes the
  caller raise nothing.
- **C-3.10-4** Once joined, the callee dials the **lane**: TCP to the group owner on port `o`, admitted by the
  transfer's client proof under `HKDF(k, "knit/call/v1/lane-proof")`; the host then writes a 16-byte salt and both
  ends key AES-256-GCM per direction from `k` and the salt (`…/lane/h2j`, `…/lane/j2h`), frame index as nonce, call
  id as AAD. Frames: a `CallPayload`, the sender's group candidates, a ping each second, a bye. A lane silent for
  5 s is gone. One lane per call.
- **C-3.10-5** Every signal of the call rides the lane as well as the mesh, queued from the leg's start (the OFFER
  excepted, which precedes any lane), so what the mesh carried while the radio changed hands reaches the peer again.
  The copies collapse on `(id, ph, gen)`.
- **C-3.10-6** A candidate on the group gathered after the description is lane-only, never a CANDS: only a peer
  inside the group can reach it, and C-3.6-2's one CANDS stands. A remote group candidate that beats the peer's SDP
  waits for it like a CANDS does.
- **C-3.10-7** The group is reported to libwebrtc as an Ethernet network (cost 0, against Wi-Fi's 10), so its pair
  outranks a LAN, IPv6-global, reflexive or relayed one on cost before priority; those stay up as backups, pinged
  every 5 s.
- **C-3.10-8** While a leg is still coming up, an ICE `Failed` does not end the call (with no other route ICE fails
  before the group exists) and C-3.3-3's connect window is 30 s, not 20.
- **C-3.10-9** Whatever ends the call ends the leg once: the terminal frame down the lane first (≤ 300 ms), then the
  lane closes and the radio goes back to the mesh. A lost group is not raised again; the call rides on whatever
  other pair ICE holds, or reconnects by restart on the networks that remain (§3.8).

| Timer | Value | Why |
|---|---|---|
| grace + join delay | 0.5 s + 1 s | the RINGING leaves before the radio; the caller's group gets a head start |
| join window / attempt | 20 s / 10 s | a join is ~4 s of `connect()`, flat; one retry fits |
| host window | 15 s | the host gives the radio back if no lane arrives |
| commit age | 8 s | a custody-delayed "joining" is not acted on |
| lane ping / silence | 1 s / 5 s | a lost group frees the radio fast |
| connect (direct) | 30 s | C-3.10-8 |

The radio is shared with the direct transfer through one lease (`transfer/DirectWifiLease`): whoever holds it
first keeps it, the other is refused `Busy`, and neither can release the other's group.

### 3.11 A call with no live path: the walkie-talkie

When no live path exists, the call screen becomes a walkie-talkie over the mesh. The user holds a button to talk, and
the burst streams to the peer in small chunks while it is spoken. The peer's phone plays them as they land. Nothing is
stored: a chunk that is lost is skipped, and no burst appears in the thread. It is not a live call: playback trails
the speaker by a chunk and the mesh's latency, about a second between neighbours. ADR 2026-10.79yb records why. It is
behind `BuildConfig.MESH_CALLS` (`-PmeshCalls=`, on in every build since 2.10.0-beta.0), read once as
`di/CallWiring.WALKIE_WIRED`. It needs no live calls (ADR 2026-10.swvp): a build may run the walkie alone, packaging no
WebRTC, and then advertises `CAP_WALKIE` without `CAP_CALL`, places every call as a walkie and declines a live offer for
its media (`REASON_MEDIA`).

- **C-3.11-1** A build that runs the walkie advertises `CAP_WALKIE` (`0x8000`). It is never in
  `LOCAL_CAPABILITIES`. A caller offers a walkie session, or the drop to one, only toward a pinned profile that
  carries it.
- **C-3.11-2** A walkie session's OFFER has `m = 4` and no `sdp`, `cand` or `dw`. It rings, goes stale, gets busy
  and glares exactly as §3.3–3.5 say. Answering connects both sides at once, with `m = 4` on the ANSWER: there are
  no ICE servers, no connect timer and no grace. Both carry `hr = true` from a build that reports what it hears
  (C-3.11-10). A build without the walkie declines it with `rsn = 5`, because the
  audio bit is absent.
- **C-3.11-3** A call placed without a kind (`CallKind.AUTO`) is a walkie session only when the peer carries
  `CAP_WALKIE` and no live path looks possible. "Possible" means a validated Internet route here, or a Wi-Fi Direct
  leg (§3.10) to a neighbour. Anything else is a live call.

  The profile offers each kind as its own button beside Message, Call and Mesh call, toward a peer carrying that
  kind's bit (`CAP_CALL`, `CAP_WALKIE`); with both offered they share a row under Message.

  The chat offers both kinds by the same rule: the call it prefers in its top bar, the other in its overflow.
  The Calls screen's "Prefer mesh calls" (`CallPreferenceStore`, off by default, shown only where both kinds are
  wired) puts the mesh call in the top bar whatever the route, toward a peer carrying `CAP_WALKIE`. With it on, a DM
  offers only the kinds its peer carries the bits for: toward a peer without the walkie the live call takes the bar,
  and toward one with neither bit there is no call button.
  A mesh call placed on purpose is a walkie session whatever the route.
- **C-3.11-4** A live OFFER carries `m = 1 | 4` when the caller can drop to a walkie. The callee echoes `4` on its
  ANSWER when it can too. With both bits, each of C-3.3-3's `no_route` endings drops the call to a connected walkie
  session instead:
  - the connect window,
  - ICE failed,
  - a lost path past its reconnect window (§3.8).

  The drop closes the media and ends any direct leg, and sends nothing. The other side's ICE sees the media go and
  drops on its own timer. Without both bits, the call ends as before.

  **Parked (2026-10-09).** The Android build no longer sets the bit on either message, and it ignores the bit when
  a peer sends it (`CallManager.dropToWalkie`, off), so a lost live call ends `no_route`. In a P9↔P7 call over the
  Internet the drop left the two phones in a walkie session neither could send on, because the peer was off every
  radio. A walkie session placed on purpose is unchanged.
- **C-3.11-5** A burst is Opus at 8 kbps, captured at 16 kHz mono, in 20 ms packets, up to 60 s. The microphone's
  session carries the platform's noise suppressor (`android.media.audiofx.NoiseSuppressor`), enabled, where the phone
  implements one; `VOICE_RECOGNITION` has no processing of its own, and a phone without the effect records the burst
  as it comes. Before the encoder the capture passes a gain stage (`call/TalkGain`), because the phone's flat microphone path has no gain control and
  a live call's audio has WebRTC's. Speech is brought toward −18 dBFS RMS, gaining at most 24 dB and never losing.
  The gain falls at once and rises at most 5 dB per second of speech. It holds between words, where a frame is within
  12 dB of the quietest frame of the last 2 s. No peak passes −1 dBFS. Each microphone keeps its own gain across
  bursts. It is cut into chunks: one each time 480 ms of audio is in hand, then the rest marked as the last. A chunk's plaintext is binary:

  ```text
  flags (bit 0: last) · call tag (2 bytes, big-endian) · varint b · varint q · packets
  ```

  - `b` is the sender's burst number in the call, and `q` the chunk number from 0.
  - Each packet sits behind a one-byte length.
  - The tag is the low 16 bits of the call id's Java `String.hashCode`, so a chunk names its session.

  A burst released before its first chunk went out sends nothing. A cancelled one that had sent sends an empty
  last chunk.
- **C-3.11-6** A chunk rides a frame of type `talk`, sealed on the DM ratchet and sent unsigned: its AEAD
  authenticates it to its addressee, as ADR 059's tick is authenticated, and only the addressee reads it.
  - **Payload.** `0x01 · varint se · varint pe · varint n · ek (32 bytes) · ciphertext`: the v3 seal with its nonce
    derived. A header that form cannot carry (an init, a reset flag), or a peer that reads only v2, gets a
    `ChatContent` instead, whose CBOR never begins with `0x01`.
  - **Id.** Its frame id is 11 characters.
  - **Reach.** No node custodies it, parks it, re-serves it or fans it onto the coordination plane or LoRa, and no
    spool stores it. A neighbour of the sender gets it alone, with `ttl = 0`. A peer heard through the mesh gets a
    flood of at most `ttl = 3` relays. Every other peer is one the radios have no path to, and it gets the chunk
    **cast** through every connected spool that would carry it (§2.7), signed, and beside the blind flood when this
    phone has links at all. The radios have a path to a peer that is not a neighbour only while one of the peer's own
    frames reached this phone through a link it still holds, within the last two minutes (`mesh/TalkRadioPaths`). Two
    kinds of frame count: a signed frame that passed verification, stamped by its author within those two minutes, and
    a `talk` frame its ratchet opened. A spool's frame and a LoRa one never count. The reach tiers (`heardIndirectly`,
    ADR 2026-10.fw8g) are presentation and are never read here. A neighbour whose
    live link is near its floor (`MeshTransport.liveLinkFading`: its link RSSI at or under −95, or three reads in a row
    unanswered) gets the cast beside its radio copy, because a dying link counts as a path until its supervision timeout
    closes it, about 20 s later. Any peer gets the cast beside the radio copy while the session's reports say the
    radios lose its chunks (C-3.11-10). A build that does not know the type relays it like any unknown type and
    delivers nothing.
  - **Receipt.** The addressee admits the frame unsigned only in this shape: addressed to it, by someone else, in no
    group, and admits the signed copy a spool casts through the ordinary signed door. It hands the opened plaintext to
    the call layer only from an accepted contact, and a failed open is never a reset trigger, signed or not. Only a connected walkie session with the sender, under the chunk's tag, takes the chunk.
  - **Size.** A 480 ms chunk's frame is about 714 bytes, about 1.5 KB/s, inside Coded PHY's 2.1 KB/s.
- **C-3.11-7** The receiver plays bursts one after another, in the order of `b`, and each in the order of `q`:
  - It starts a burst once it holds two chunks, or the last one, or 400 ms after the first chunk arrived.
  - When chunks after a missing one are held, it waits 500 ms for the missing one, then skips it.
  - A burst ends on its `e` chunk, or after 4 s with nothing held and nothing new.
  - A chunk of a burst that ended, or one behind the play position, is dropped.

  Talking holds playback: what arrives meanwhile plays after the release. Bursts play on the call's audio route, which
  starts on the loudspeaker (§3.13). The session hangs up after 10 min with no burst either way.
- **C-3.11-8** From the ring to the hang-up, a walkie call holds a Wi-Fi Aware link to its peer
  (`MeshTransport.holdLink`), renewing the hold every 10 s; a hold lapses 25 s after its last renewal.
  - **No link yet.** The hold raises one through the hint an attachment fetch gives
    (`MeshTransport.expectBulkTransfer`), within its bounds: a fresh sighting, the initiator hold, the cooldown after a
    failed initiate.
  - **A link.** The hold keeps it open past its idle teardown (5 s for the initiator, 8 s for the responder) but not
    past its absolute window (30 s, 45 s). The window is the only way a responder can tell a dead initiator from a
    quiet one, so a held link comes round every 30 s or so, and the hold raises it again at once.
  - **No link at a send.** A chunk retries for up to 2.5 s, past the recycle's gap of about 1.9 s. A chunk to a neighbour goes over its
  quickest link (`MeshTransport.sendLive`): Wi-Fi Aware when that radio holds one, else Bluetooth. Every other frame
  keeps the Bluetooth-first order.

  The call screen shows how a chunk would reach the peer (`TalkLink`):
  - **direct:** over a link of this phone's own; nothing is shown. Over a fading link with a relay that would cast,
    the chunks also go through the relay; nothing more is shown, and the diagnostics path reads
    `Bluetooth · fading, relay too`;
  - **through the mesh:** no link of its own, but the radios have a path to the peer (C-3.11-6); a notice says the
    chunks go through other phones;
  - **through the relay:** no radio path, but a connected relay the peer was lately seen on would cast the chunks
    (§2.7, `ScopeSync.castCovers`); a notice says so. A relay that would take the cast but never saw the peer still
    gets it, since a quiet subscriber leaves no evidence, but the screen reads **none** and the diagnostics path
    `Relay cast · peer not seen on it`;
  - **none:** no link it can see; a warning says it is linking.

  With neither a link nor a relay that would cast, nothing is sealed, and the burst says it did not go.
- **C-3.11-9** *(experimental, where `BuildConfig.STT` is on; ADR 2026-10.nz3g, 2026-10.swvp)*
  The receiver may write each burst it hears out as text, on the phone, as the burst plays: the words so far while it
  is still coming, settled a moment after it ends. Nothing about it is on the wire: no field, no frame, no capability
  bit, and the text is never sent, stored, logged or kept past the session.
  - **The audio.** The player's decoded PCM (48 kHz mono s16) is tapped on its way to the track
    (`TalkPlayer`'s `TalkPcmTap`), averaged down to 16 kHz (`BurstPcm`), capped at the burst's 60 s, and fed to the
    model as it comes. A burst under 300 ms is no speech: its stream is closed unfinished.
  - **The sender's own.** Each burst this phone speaks is transcribed the same way from the recorder's PCM before
    the encoder (`TalkRecorder`'s tap, 16 kHz already), opened at the press and ended at the release — sent or not —
    and shown as the user's own line. Set beside the other phone's line for the same burst, it separates what Opus
    at 8 kbps cost the model from what the model gets wrong on clean audio. A cancelled burst drops its line.
  - **The model.** Moonshine v2 Streaming, English (int8 `.ort`, MIT), through Moonshine's own runtime
    (`moonshine-voice`, `MoonshineSpeech`; its slim ONNX Runtime inside), packaged only where calls are, like
    libwebrtc: Small Streaming (142 MB; 7.84 % on the Open ASR average), the one model there is.
    Each burst is one stream of the model's: its streaming encoder caches what it has seen, so a look costs the new
    audio and a decode. How a burst becomes lines is `MoonshineSpeech.lines` (`LineMode`): one line of up to 30 s with
    the model's voice detector off (the default, since a split at a pause decodes each half without the other), or a
    line per pause with it on, for an A/B. The model
    looks every 500 ms of new audio, or — on a phone that cannot keep up — once as much audio has come as its last
    look took (`PassGate`, at most 5 s). One model call runs at a time, off the player's and the recorder's threads.
    The model is held on a `ModelLease` (released after 2 min idle) and loaded under `ModelLoadGuard` as `stt`, a
    probe stream included.
  - **The download.** Not in the APK: nine files under `https://getknit.app/models/stt/<model id>/`,
    fetched only on the user's tap (the card's Download on a walkie call, or the speech model's row on the Calls
    screen at any time), each pinned by size and SHA-256 in `SttModel`, streamed
    through the hash to a `.part` and renamed in, the model counted as installed only once a marker follows the last
    file. Stored in `noBackupFilesDir`. The fetch runs on the app scope and holds a `dataSync` foreground service
    (`SttDownloadService`, its notification the progress and Cancel) until it ends, so it outlives the call or screen
    it was started from. The fetch binds to the validated default network
    (`AndroidInternetGate.currentNetwork`, never the Wi-Fi Aware link) through `Network.openConnection`: https,
    `Accept-Encoding: identity`, no redirect followed, the server's declared length checked first. The pins come
    from `scripts/stt-model.py`, which reads Moonshine's own CDN (one dated directory, never rewritten) and stages
    the files for upload.
  - **The screen.** With the walkie's diagnostics toggled off, the card's place shows the transcript: each burst
    `Listening…` until the model has heard something, then its words so far (dimmed, not read out), then its text (a
    polite live region) or why not (no speech, no model, failed). While the model is not on the phone, the card says
    what it costs, flags a metered or Data Saver route, and offers Download, Cancel or Try again, and Dismiss: once
    dismissed (`TranscriptStore.offerDismissed`), no call draws the card while the model is absent.
  - **The switch.** The Calls screen's "Transcripts and captions" (`TranscriptStore.enabled`, on by default) turns
    the whole thing off: no burst is taken, nothing reaches the model, and no card is drawn. It governs a live call's
    captions too (C-3.7-5).
  - **The names.** As a call begins, the model is given a short list of names to listen for (`SttKeytermFeed`,
    `sttKeyterms`; Moonshine's key-term biasing at its default strength): the peer's and this phone's names, the latest
    threads' (DMs and groups), and the capitalised words the thread with the peer keeps using — at most 40, ranked in
    that order, since every listed term costs a little accuracy on the words nobody listed. The list is held in memory,
    given to the model and nowhere else, never logged, and cleared when the call ends.
  - **The lab's clips.** A debug build only: while Diagnostics' "Save walkie clips" is on (off by default), each burst
    the transcript finishes is written by `WalkieClipFiles` as a 16 kHz mono WAV and a JSON sidecar, its text
    included, to the app's external files, unencrypted, for scoring the model off the phone. This is the one exception
    to the text never being stored; a release build has no switch.

- **C-3.11-10** The listener of a walkie session reports each of the speaker's bursts back to it, and the speaker
  casts beside the radios while those reports say the radios lose its chunks (ADR 2026-10.79yb's twelfth amendment).
  - **Who.** Only between two phones whose OFFER and ANSWER both carried `hr = true` (C-3.11-2). A build that does not
    know the field would read a report as a chunk, so it is never sent one.
  - **The report.** A `talk` frame, sealed and sent the way a chunk is (C-3.11-6), whose plaintext is
    `flags (bit 1) · call tag · varint b · varint radio · varint relay`. `radio` and `relay` count the distinct chunks
    of burst `b` that came over the radios and through a relay. A chunk that came both ways counts in both. A chunk
    decoder refuses bit 1, so a report never plays.
  - **When.** 500 ms after the burst's last chunk landed, or 4 s after its newest chunk when the last never came (the
    player's give-up, C-3.11-7). Once per burst, and a straggler of a reported burst is not counted.
  - **The speaker's verdict.** A burst whose report shows the radios lost more than a fifth of its chunks turns the
    cast on. So does a burst with no report 7 s after its last chunk went. While the cast is on, every chunk is also
    cast beside its radio copy, and the report goes the same way. Two reports in a row with the radios whole turn it
    off. A peer that does not report leaves the chunks to C-3.11-6's evidence alone.

### 3.12 Call records in the thread

Every call leaves a line in its DM thread: "Outgoing call · 3:42 · 2:02 PM", "Missed call · 9:15 AM", "They were in
another call". The line is a record this phone writes for itself, so it costs no wire field and no schema change. It is
a `messages` row of `kind = 10` (`MessageEntity.KIND_CALL`) whose body is a small JSON `CallRecord`, the convention a
direct transfer's card already uses. ADR 2026-10.3tee's second amendment records the choice.

| ID           | Requirement                                                                                                                                                                                                                                                      |
|--------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.12-1** | A record is written once, when the call ends on this phone (`CallManager.logged`), and never sent. Each side keeps its own record of the same call under the same id (`call:<id>`), so a second write for one call replaces the row.                           |
| **C-3.12-2** | A record holds facts, not a sentence: who placed the call, its reason, whether the peer's terminal phase ended it, the manager's missed verdict (C-3.4-2), whether nothing reached the peer (C-3.3-5), and when it connected and ended. The line's wording (`CallRecord.outcome`) is derived when it is drawn: a call that connected and then ended `no_route` reads "Call dropped" (§3.8), and one withdrawn under C-3.3-5 reads "Couldn't reach them". |
| **C-3.12-3** | An offer that never rang (C-3.4-1) and was not withdrawn by hand is recorded as missed under its own call id, at its `sentAt` or now, whichever is earlier. A busy DECLINE (C-3.3-2), the DECLINE of a switched-off kind (C-3.3-7) and a glare loser's dropped offer (C-3.5-1) leave no record.           |
| **C-3.12-4** | The signaling hook never writes the record. The manager hands it over on a channel and `call/CallLog` saves it on the app scope, so a closed or refusing database (ADR 2026-10.47rw) costs that one line and never delays `onSignal`.                          |
| **C-3.12-5** | A record never carries an SDP, a candidate, a Wi-Fi Direct credential or a TURN credential.                                                                                                                                                                       |

A record is a status notice in every list that filters on `kind`: it gets no delivery tick and no unread count, and
search never finds it. The chat list is the exception, as it is for a transfer. A record speaks for its thread there, as
the same sentence without the time, and with no "You: " in front of it, because the sentence already says who did
what. A missed call is notified on its own (`ui/call/CallController`), so the badge does not count it twice.

The field log keeps its own account of each call, apart from the thread (`docs/FIELD_LOG.md`, ADR 2026-10.a239's
2026-10-18 amendment). Each call writes a start line and an end line; there is an end line for every offer this phone
saw, refused and never-rung ones included. A live call also writes its selected pairs and a media reading every 30 s,
a Wi-Fi Direct leg writes how it ended, and a walkie writes each burst and its session's totals. These lines hold
types, counts and times, under a pseudonym for the peer. They are off until the user switches the log on, and only
the user shares them.

### 3.13 The audio route

Every call, live or walkie, is registered with the phone as a self-managed Telecom call (Jetpack core-telecom, debug
builds only). Telecom owns where its audio goes: the earpiece, the loudspeaker, or a wired or Bluetooth headset, both
ways. A headset's button, a watch or a car can answer the call and hang it up. ADR 2026-10.wbua records why.

`call/SystemCallSync` keeps the system's call in step with `CallManager`'s. It follows the manager's state on the app
scope, never on the inbound path. `ui/call/TelecomCalls` is the one importer of the library.

| ID           | Requirement                                                                                                                                                                                                                                                                       |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.13-1** | An outgoing call is registered as it dials and set active when it connects. An incoming one is registered as it alerts and answered when this phone answers it. If the system's own answer took it, the system is not answered a second time.                                    |
| **C-3.13-2** | A call that ends is disconnected from the system with what ended it: missed, busy, an error (a lost path, the media, a failure), the peer's own ending (remote), an incoming call never answered (rejected, or missed when it rang out), else local. A call the system ended is not told back. |
| **C-3.13-3** | Glare's surviving call (§3.5) keeps the system call of the call it replaced: same peer, new id.                                                                                                                                                                                  |
| **C-3.13-4** | The system is offered no hold, so a phone call answered over ours ends it. A call the system refuses is declined `busy` while it alerts, else hung up. So is every call on a phone that would not register this app.                                                              |
| **C-3.13-5** | A live call starts where Telecom puts it: a connected headset, else the earpiece. A walkie session starts on the loudspeaker unless a headset is connected, which takes it as it would a call.                                                                                   |
| **C-3.13-6** | When a walkie session's route falls to the earpiece without the user picking it (a headset unplugged, a start not honoured), the session asks for the loudspeaker, once per fall. A live call that drops to a walkie is moved off the earpiece the same way.                        |
| **C-3.13-7** | Nothing but Telecom sets the route while a call is up: no `setCommunicationDevice`, no `startBluetoothSco`, no preferred device on a track. Both kinds play on the voice-communication usage. A walkie burst is recorded from `VOICE_COMMUNICATION` while a headset has the call, else from the phone's microphone (`VOICE_RECOGNITION`, then `MIC`). |
| **C-3.13-8** | The call screen's route control is the loudspeaker toggle while the earpiece and the loudspeaker are the only routes. With a third route it names the current one and lists them all. The notification's one route action goes onto the loudspeaker, or off it to a Bluetooth headset, then a wired one, then the earpiece. It carries that route's key, so a stale tap never moves the call anywhere unseen. |
| **C-3.13-9** | A mute set from a car or a watch reaches the call. The first value the system reports is only where it starts, and is not acted on.                                                                                                                                             |
| **C-3.13-10** | A call the system answered starts the call's microphone service from inside the system's answer, because the call screen may never open. Without the service, a backgrounded call's microphone is silenced. Android allowed that background start on the trial phone (a Pixel 9, API 37). |
| **C-3.13-11** | A live call on the earpiece, from dialing to reconnecting, holds the proximity wake lock, so the screen goes dark and ignores touches while the phone is at the ear, wherever the user left the call screen. It never does while an incoming call rings, for a walkie, or on any other route; leaving the earpiece gives the screen back at once. A call that ends keeps a dark screen dark until the phone leaves the ear. |
| **C-3.13-12** | A live call records from one of two profiles: `VOICE_COMMUNICATION` with the platform's echo canceller and noise suppressor, or the fallback, `VOICE_RECOGNITION` with those off so WebRTC runs its own. A generic system image over an older vendor opens every call on the fallback: its vendor lacks the VoIP record route and hands the call zeros. So does a phone where a call went silent, until the app or the OS updates. The audio module is rebuilt for a new profile between calls, never under an open one. ADR 2026-10.kw29. |
| **C-3.13-13** | A call's microphone is watched. Three seconds of digital silence while unmuted, or a record error, is a dead microphone, unless the platform muted or silenced it. On the standard profile the call then records from a recorder of its own (`VOICE_RECOGNITION`, then `MIC`) for the rest of the call, with no renegotiation, and the next calls open on the fallback. A microphone dead with nowhere further to switch, or silenced, is the call screen's note. |
| **C-3.13-14** | The system plays no tones for a self-managed call, so the caller's phone plays its own on the voice-call stream, under Telecom's route. Ringback sounds only once the callee's `RINGING` is back, never while the call only dials. A call that never connected plays busy when the callee declined or was busy, and congestion when no route, the media or an error ended it. A connected call that the peer or the path ended plays one short tone. A hang-up, a ring-out and a walkie play nothing. |

The system shows the peer's label as the call's name, and `knit:<node id>` as its address. VoIP calls stay out of the
system call log. A call's line in its thread is §3.12's.

**Known limit: a Bluetooth headset and Coded PHY.** While a headset holds a call link, its eSCO slots share the
controller with the mesh's Bluetooth links. Over 1M PHY a walkie loses nothing to it. Over Coded PHY, whose packets
are long, the phone holding the eSCO link received 2 of each 9 chunks in the 2026-10-12 trial, against 9 of 9 with the
link down; its own sends were all delivered. Nothing steers a link off Coded while eSCO is up yet.

### 3.14 A person's own STUN and TURN servers

Every ICE server a call uses comes from the spools by default (§2.2, §2.6). The Calls screen lets a person name their
own instead: up to four STUN URLs, and one TURN server with a static long-term username and password (coturn's
`lt-cred-mech`). Each kind has its own switch and **replaces** what the spools supply for that kind; it never adds to
it. `call/OverridingTurnSource` is the seam, `data/settings/OwnIceStore` holds the fields, and ADR 2026-10.mr88 records
why. For now only a debug build with live calls offers them (`OWN_ICE_WIRED`): elsewhere the screen shows no server
rows and every call takes the spools' servers, whatever the store holds.

| ID           | Requirement |
|--------------|-------------|
| **C-3.14-1** | With an own TURN server switched on and valid, a call gathers relay candidates from it alone and sends no `turnreq` to any spool, on the call or on an ICE restart (§3.8). With own STUN URLs on and valid, they replace every HELLO's `stun` list (C-7.6-20). Each kind is independent: own STUN alone still asks the spools for TURN. |
| **C-3.14-2** | An own TURN URL is `turn[s]:host[:port]`, with at most a `?transport=udp\|tcp` query: a DNS-shaped name, an IPv4 literal or a bracketed IPv6 one, never `user@host`, never a port outside 1..65535. An own STUN URL passes `StunServers`' rule (§2.6). Both are checked as typed and again on every read, so a stored value libwebrtc would refuse never reaches a call: that kind reads as switched off. |
| **C-3.14-3** | The username and password are non-empty and carry no control character. The password is never logged, never put in a frame and never sent to the peer (C-7.6-11). It is stored in the clear beside a relay's `?k=` token (ADR 2026-09.66cw) and rides a Knit backup, like every other choice the person made. |
| **C-3.14-4** | "Relay all calls" (C-3.7-3) holds with an own TURN server and no spool at all. |
| **C-3.14-5** | The screen's Test gathers against the servers as typed, before they are saved: one throwaway connection per kind on the call factory, an audio m-line that only receives, so no microphone opens. A server-reflexive candidate means STUN answered, a relay candidate that TURN allocated; error 401 means the credential, another server error (3xx-6xx) a refusal, anything else no answer. It never names an address, and it is refused while a call holds the media. |

The debug bridge's `…debug.CALL --es op turn` reports `turnSource` and `stunSource` (`own` or `relays`) beside the
servers a call would use, and never the username or password of an own server.

## 4. Tests this would add

- **knit-spool:**
  - `SpoolRecordsTest` vectors for `turnreq`/`turncred`, and the §2.2 HMAC vector.
  - Conformance cases: no `turn` in HELLO without config; `not_subscribed` with no sub; `rate` past the
    budget; two issues never share a suffix.
- **knit-spool, casts (§2.7):** `CastRecordVectorTest` for the vectors; `CastTest` for fan-out, nothing stored,
  silent drops, no strike, the reload kill switch and the ticket rule; conformance checks `cast-advertised`,
  `cast-fanout`, `cast-silent-refusal`, `cast-ticket` and the advisory `cast-rate`.
- **knit-next:**
  - Casts (§2.7): `SpoolCastRecordsTest` against the same hex; `CastSealTest`; the cast set in `ScopeFramesTest`;
    `SpoolConnectionCastTest`; `ScopeSyncTest`'s cast cases; a signed talk frame that fails to open raises no reset
    (`InboundPipelineTest`); the `ct` flag in `CallManagerTest`; and `WalkieTalkLabTest`'s burst cast between two
    phones with no radio path.
  - The key swap (C-7.6-27 to C-7.6-29): `RelayInviteTest`'s `ticketInvite` vector and refusals, `TicketRefresherTest`
    for the schedule row by row and the refresher against the stores (the host rule, the park, the daily retry, the
    route and plane gates, nothing secret in a log), `OkHttpTicketIssuerTest` for the request's shape and the
    coordinator's three answers on a socket, `TicketKeyStoreTest`, `RelayInviteApplierTest`'s key cases,
    `InternetRelayScreenContentTest`'s sheet lines and `SettingsSnapshotTest` (the key rides a backup, its state does
    not).
  - The files gate (C-7.6-24, C-7.6-30): `SpoolTicketRecordsTest` against `tkFiles`, `sigFiles` and `helloEntGates`;
    `SpoolConnectionTicketTest` for the gate table, each verb against its bit, the upload states and a late `ticket`
    refusal; `ScopeSyncTest` for an unticketed member that never `aput`s and still fetches, a spent budget across a
    reconnect and a same-`ps` ticket, and TURN on a files-only spool; `RelayReachTest` for `Locked`'s precedence; and
    `InternetPlaneLabTest`'s photo through a files-gated relay. knit-spool's side is `TicketFilesTest`,
    `TicketGatesTest` and the `ent-gates`, `files-ticket` and `files-receive` conformance checks.
  - The `CallPayload` golden vectors: six `messageContentCall*` fixtures in `vectors/wire-v1.json`
    (`GoldenVectorTest`) — an OFFER with `dw`, a RINGING with `dx`, a CANDS, a restart, a walkie OFFER with `ct` and
    an END.
  - Between real stacks: `CallLabTest`'s call across a relay, every phase a sealed DM that reaches the far call layer
    and no thread, and its offer that reaches a relaunched process after the CANCEL it heard before the restart
    (C-3.4-3).
  - Tombstones across a process death (C-3.4-3): `CallManagerTest`'s restart cases over a fake journal (a cancel heard
    before, a call ended before, one past its 48 h, a journal that cannot be read) and `CallTombStoreTest`.
  - "Relay all calls" (C-3.7-3): the relay-only cases in `CallManagerTest`, the row in `SettingsScreenContentTest` and
    `SettingsViewModelTest`, its TURN gate in `CallSettingsViewModelTest` and `CallSettingsScreenContentTest`, and
    `turnForUs` / `turnAvailable` in `ScopeSyncTest`, and the refusals in `CallManagerTest`'s "nothing to relay through"
    cases and `OverridingTurnSourceTest`.
  - TURN pruning (C-7.6-26): `CallConfigurationTest` pins the prune policy beside continual gathering and relay-only,
    and `RelayTallyTest` the call log's `allocations gathered N, pruned M, held K`; a measured relay-only call gives
    the numbers.
  - Reconnecting (§3.8): the restart cases in `CallManagerTest` — answered under the same call, a blip that costs no
    frame, two restarts then dropped, the callee's window and its renewal, a restart that does not fit, a superseded
    answer, relay-only.
  - Call records (§3.12): `CallRecordTest` for the outcome table and the row, the record cases in `CallManagerTest`,
    `CallLogTest`, and the call line in `ChatScreenContentTest` and `ChatListTransferPreviewTest`.
  - The audio route (§3.13): `SystemCallSyncTest` for the system call's lifecycle, glare, refusals, mute and the
    walkie's correction; `CallAudioPolicyTest` for the start, the correction, the notification's target and
    when the screen goes dark at the ear.
  - The microphone (C-3.13-12, C-3.13-13): `CaptureHealthTest` for the profile a call opens on, the learned verdict
    and the watch (zeros, a mute, a silenced client, a record error), `CaptureTapTest` for the buffers between a call's
    end and the module's stop, `CallStatsReaderTest` for the two audio levels, and `SystemImageTest` for the image that
    opens on the fallback.
  - The caller's tones (C-3.13-14): `CallTonesTest` for which step plays what, an end heard once, and the sync cutting
    a tone when the call moves on.
  - A phone that may not host (C-3.10-1, ADR 2026-10.w3dw): `DirectHostGuardTest`, `DirectWifiLeaseTest` and
    `DirectCallPathTest`'s non-hosting caller.
  - Stale `OFFER` → missed call; `CANCEL` before `OFFER` → no alert.
  - Glare resolution; idempotent duplicates.
  - Relay-only SDP stripping, and fingerprint mismatch → call refused.

## 5. Open questions

- **iOS ringing:** a PushKit relay, foreground-only ringing or outbound-only calls (ADR 2026-10.3tee).
- **Secret rotation:** coturn has one `static-auth-secret`, so rotation is a short window of failed
  allocations unless the sidecar uses its database-backed multi-secret mode.
- **Whose TURN:** a client may want to prefer a TURN server run by someone other than the spool
  operator, to avoid §2.5's join. Nothing above stops that, but no configuration surface exists for it.
- **Timing signature:** a call shows up as a short burst of about five blobs plus a `turnreq`. That is
  more distinctive than chat traffic, and padding (§11) is the only answer. A walkie over a relay (§2.7) is the sharper
  case, two casts a second per talking side: padding hides what was said, but not that something was or when, and only
  cover traffic during the silences would.
