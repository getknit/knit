---
id: "2026-11.ut8g"
slug: a-spool-url-s-authority-is-a-bare-host-and-port
title: "a spool URL's authority is a bare host and port, one rule for every door and the dialer"
date: 2026-11-21
topics: [spool, security, privacy]
---

# ADR 2026-11.ut8g — a spool URL's authority is a bare host and port, one rule for every door and the dialer

Status: Accepted (2026-11-21) — #340; amends ADR 2026-09.66cw's "one rule, two callers" and ADR 2026-09.tmbq's
"the host in the largest type".

**What was observed.** `SpoolUrl.host` and `SpoolUrl.hostname` split a spool URL's authority by hand, OkHttp splits it
the way a browser does (a `wss:` URL is parsed as `https:`), and no door checked the authority's shape: a relay
invite refused whitespace and controls, a scope config the same, a card hint and the relay editor only the scheme.
OkHttp 5.5.1's `HttpUrl.parse`, run on the forms the iOS port's security review raised (2026-11-21, by hand):
`wss://public-1.spool.getknit.app@evil.example/spool/v1` dials `evil.example` and the invite sheet titled it
`public-1.spool.getknit.app@evil.example`; `wss://evil.example\@public-1.spool.getknit.app/spool/v1` and
`wss://evil.example#@public-1.spool.getknit.app/spool/v1` dial `evil.example` too, while `hostname()` read
`public-1.spool.getknit.app`; `%2e` in the host dials a longer name than the label shows, and a Cyrillic letter dials
its punycode under a lookalike label. The `hostname()` split is the worse half: `SpoolConnection.present` holds a
ticket back from any spool whose `hostname(url)` is not its `aud` (ADR 2026-10.xkp4), so the two backslash and
fragment forms would hand the house relay's ticket, and its daily pseudonym, to `evil.example`. A scope config takes
its URLs from any group member and dials them with no sheet at all.

**What changed.** One shape rule, `SpoolUrl.isWellFormed`: `scheme://host[:port]`, then nothing, a path or a query;
printable ASCII with no `\` and no `#` anywhere; the host dot-separated labels of letters, digits, `-` and `_`, or a
bracketed IPv6 literal; a port 1..65535 with no leading zero; a host whose every label is a number only as four plain
decimal octets, because OkHttp hands `010.0.0.010` and `3232235777` to the resolver as text and bionic's `inet_aton`
reads them as 8.0.0.8 and 192.168.1.1 (the security review's finding, read from bionic's source). `SpoolUrl.isAcceptable` is now that rule and the
scheme rule together, so every caller of the dialer's rule takes it unedited: the dialer itself (a URL stored before
the rule never dials), `/source`, the relay editor, the invite parser, a scope config's filter and a card hint's
Add. The Add-contact preview lists only the hints `isWellFormed` passes. `host`, `hostname`, `canonical` and
`sourceUrl` read one split, `authorityOf`, which ends the authority at the first `/`, `\`, `?` or `#` and drops the
user info, as OkHttp does; a row stored before the rule is titled with the host it would dial.

The first alternative is to title the sheet from OkHttp's own parse (`HttpUrl.host`): it fixes the label but keeps
the dial, it cannot render a lookalike or a format character honestly, and `okhttp3` is confined to the three
seam files (`rules/mesh.md`). A deny-list of `@` and `\` in the authority (the rule #340 describes Knit for iOS
shipping) still passes `%2e`, and passes `#@` wherever the authority is read as ending at `#`; an allow-list has no such
gap to find, which is why the rule is one.

**What it costs.** An internationalised host must be typed as its punycode, in the editor too; no relay has needed
one. A hostile URL already stored stays in the list and never connects: there is no purge. The rule is pinned by
`SpoolUrlTest`'s shape table, by `OkHttpSpoolDialerSocketTest`'s check that `hostname` and `host` name the host
OkHttp's `Request.Builder` dials for every URL the rule passes and for the spoof forms, and by `RelayInviteTest`'s
spoof cases. `docs/RELAY_INVITE.md` §2 states the pattern for the iOS port and the daemon's minter.
