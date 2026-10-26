---
id: "2026-10.fm8z"
slug: public-wording
title: "Public wording: Knit is funded by supporters, and Knit Anywhere is their thank-you"
date: 2026-10-25
topics: [copy, spool, anywhere]
---

# ADR 2026-10.fm8z — Public wording: Knit is funded by supporters, and Knit Anywhere is their thank-you

Status: Accepted (2026-10-25). Sets the wording around ADR 2026-10.r99s (the supporter badge), ADR 2026-10.s3ep
(free tickets) and ADR 2026-10.3sk7 (the invite's key). It changes no wire byte, key, route or string resource key.

**What was observed.** Every public word about money said something different. The Support screen called Knit
"funded entirely by tips … no servers" and asked for "a small donation", though Knit now runs a relay. The 2.10
What's New page and the README tied Knit Anywhere to Ko-fi and GitHub Sponsors in the same sentence. The relay
screen said "Your Knit Anywhere has ended", and the profile said a lapsed badge "returns once it's renewed",
which reads like an account and a subscription. The specs called the ticket holder a "paying caller", "payer" or
"customer", and the operator one that "sells TURN".

**What changed.** One vocabulary, in the app, the store listing, the README and the specs:

- People who give money are **supporters**. Never "donate", "donation" or (pt-BR) "doação". "Tip" only for Ko-fi,
  and "Sponsor" only to name GitHub Sponsors.
- Knit Anywhere names the supporter perks. It is never a product, plan, account or subscription, and no copy
  says "get", "buy", "upgrade" or "subscribe" about it. The `kak1_` secret is a **ticket key**, the app's existing
  label. "No account" in the sign-up sense, and the "account" that is a person's Knit identity (the clone
  banner), stay.
- **The app keeps funding and perks apart.** The Support screen says Knit is free, funded by its supporters, and
  its developer's full-time job, and names no badge, relay or file size. Perk strings (relay status, move, badge)
  name no platform, price or link.
- The README's Support section states the funding, the thank-you, the reason (relaying calls and storing files
  costs money per person) and self-hosting, links to the supporter page with no figure, and labels Liberapay as
  the developer's own, with no perks.
- Specs say "ticket holder" and "ticket key" in descriptive prose. The requirement rows keep the defined term
  **customer key**, and the issuer's **free account** (C-7.6-33 on) keeps its name, because they are requirement
  vocabulary the issuer's routes (`/api/v1/account`) and the code (`CustomerKey`) share. The prose names the
  equivalence once where it first says "ticket key".

The alternative a reader reaches for first is renaming `CustomerKey`, the `donate_*` keys and the
`/api/v1/account` route to match. That moves code, string keys and a route for no gain the reader can see, and
it breaks every Weblate translation keyed on the old names. So the names stay and only prose moves.

**What it costs.** Two things keep this true, and neither is a linter: the translator comments above `donate_*`
and `whats_new_2_10_anywhere_body`, and the `supporter` / `supporter perks` rows in
`scripts/translation-glossary.tsv` (mirror them into Weblate's translator instructions by hand). The next person
to write a perk string will reach for "your subscription" or "renew". The shipped `## 2.10.0` changelog section
and fastlane changelog 33 still say "Supporting Knit … comes with Knit Anywhere", because shipped sections are
never rewritten. The Play listing's full description runs within a few characters of its 4,000-character cap, so a
longer funding line needs a cut elsewhere.
