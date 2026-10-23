---
id: "2026-10.qpja"
slug: a-renamed-house-relay-is-folded-to-its-new-name-on-every-start
title: "A renamed house relay is folded to its new name on every start"
date: 2026-10-22
topics: [spool, anywhere, settings]
---

# ADR 2026-10.qpja — A renamed house relay is folded to its new name on every start

Status: Accepted (2026-10-22)

**What was observed.** The free public relay `lax.spool.getknit.app` is now `public-1.spool.getknit.app`
(knit-spool-coordinator ADR 2026-10.ftp9, live 2026-10-23): the same spool and rooms, both names resolving to one box,
the spool taking tickets for either `aud`, and Anywhere naming either in a ticket through `?aud=`. Every install seeded
`lax`, and every Anywhere account made so far has `spool_host = lax`. The coordinator retires `lax` once a build that
moves off it is widely installed; an older build that still dials `lax` then gets no answer.

**What changed.**

- `res/values/spools.xml` seeds `public-1`, and `free_issuers` maps both names to Anywhere until `lax` is retired.
- `SpoolUrl.canonical` folds the exact host `lax.spool.getknit.app` to `public-1`, keeping the port, path and `?k=`
  (`currentHost`, `formerHost`). Every door that writes a relay goes through it, and the card preview's
  `unknownRelays` compares the folded form, so an old card or invite cannot bring `lax` back. The lab under `lax`'s
  name (`lab.lax…`, `stun.lab.lax…`) is matched exactly and never touched.
- `SettingsStore.renameRelays` (`data/settings/RelayRename`) runs **on every start**, before the seed: `spool_urls` /
  `spool_urls_disabled` fold (a merged row stays in use if any row it came from was), `spool_key:`, `spool_free_key:`,
  `spool_free_consent:` and `spool_free_issuer:` move unless the new name holds its own, and the held ticket, its verdict
  and both backoff states drop. It reads once and writes nothing when there is nothing to move.
  `CommonsRepository.renameRelays` re-binds a room's `spoolUrl` the same way.
- `TicketIssuer.swap` / `move` take `aud`, the dialled relay's lowercase host, sent as `?aud=` outside the proof; a 400
  `bad-audience` is no verdict.
- The device keys are left alone. `DeviceKeys.slotOf` hashes the host, so a customer key the rewrite moves makes a
  new pair on its next ask, and an issuer that bound the old pair answers `409 bound-elsewhere` until the person moves
  the account.

**Alternatives.** A one-shot behind its own marker is what a reader reaches for first; a restore applied after the
marker brings `spool_urls` and `spool_key:lax` back (both ride a backup), so the rewrite is idempotent instead, and
the restore needs no hook. The coordinator's brief asked that a moved key keep signing with its bound pair, by
pinning the slot's host to `lax` or looking under `lax`'s slot as a fallback. We did neither. On 2026-10-22 the only
customer key in existence was the maintainer's own test purchase, and free keys carry no device key (C-7.6-37), so no
real account is affected. The pin would also have stranded the opposite group: a key redeemed under `public-1` on an
earlier build (Anywhere's invites have named `public-1` since 2026-10-23) would look under `lax`'s slot after the
update. A fallback covers both but stays in the code for good, to protect one key whose binding the coordinator can
clear by hand.

**What it costs.** A customer key bound under `lax` costs one move, with its cooldown, unless the coordinator clears
its binding. A future rename with real customers needs one of the two device-key routes above. Retiring `lax` on
our side means dropping its `free_issuers` line; keep the `RENAMED_HOSTS` entry, since a card, an invite or a backup
can still name `lax`.
`RelayInvite` and contact-card vectors keep `lax`, because their bytes are signed. Tests: `SpoolUrlTest` (fold and
exactness), `RelayRenameTest`, `OkHttpTicketIssuerTest` and `TicketRefresherTest` (`?aud=`).
