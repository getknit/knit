---
id: "058"
slug: a-name-is-a-label
title: "A name is a label; the alias is its discriminator"
date: 2026-08-28
topics: [ui, contacts, identity]
---

# ADR 058 — A name is a label; the alias is its discriminator

A display name is free text (≤ 32 chars, whitespace-collapsed, nothing else) and the mesh has no authority
that could make it unique, so two peers named "Alice" are a normal event — in a festival crowd an
inevitable one — and until this decision they were pixel-identical on every screen but Diagnostics and
Blocked, which happen to print the raw node id. The letter avatar has one fixed tint for everyone, and the
notification avatar hue hashed the *name*, so it collapsed duplicates too. The mention pipeline was never
confused (`Mention.nodeId` is what "did this mention me" reads), but the picker showed two identical rows
and the highlighter locates spans by the `@name` text, so the reader could not tell which Alice was meant.

**The decision.** The label a peer is shown under is `name` alone in the common case and `name (Alias)`
whenever another identity the device knows renders to the same name. `identity/PeerLabels.kt` is a pure
resolver over the **universe** — every cached peer row ∪ this device's own name (a peer who adopts our name
is discriminated too; a seeded self row and self collapse to one identity, keyed by node id) — grouped by
`NameKey` (NFKC, format characters stripped, lower-cased, whitespace collapsed). Where the alias cannot
disambiguate — the rendered name *is* the alias (a blank profile), or two same-named peers' aliases coincide,
or someone *chose* "Alice (JoyfulFerret)" as a name — a six-character node-id prefix (`NodeId.shortForm`)
steps in, so every label in an index is distinct by construction. `PeerRepository.observeDirectory()` is
the one seam (the peer table plus its index, rebuilt per peer emission and on our own name changing — the
name arm is `distinctUntilChanged`, because `SettingsStore.displayName` re-emits on every DataStore write);
`labelIndex()` is the suspend snapshot for notifications and the contact-card preview. Row models keep their
`String` name (now `PeerLabel.text`), so every string sink — content descriptions, "X left the chat", the
chat-list preview prefix, group default titles, notification titles — is right with no edits, and list rows
carry the discriminator so `PeerNameText` can draw it muted.

**Why the alias.** It already exists, it is a deterministic function of the node id that every device derives
identically with no exchange (nothing to broadcast, persist, or migrate), it changes exactly when the identity
changes, and it is the word pair the owner already sees as their own placeholder — so two people can tell each
other apart by quoting it. Which is why the precision surfaces show it *always*, collision or not: the mention
picker (where the right Alice gets picked, and where `@joyful` narrows to her), a contact's profile, the
Add-contact preview, and the owner's own Settings — until now the alias vanished from the placeholder the
moment a name was typed, so nobody could learn their own.

**Why one global universe, not per-surface sets.** A peer renders identically on every screen, there is one
rule and one O(n) index per emission (n ≤ 2,000, the `sweepCap`), and a contact's label *changing* is itself
the signal that a second Alice has appeared. The accepted cost: a stranger seen once can suffix a contact
indefinitely, since there is no last-seen column to age them out of the universe. If that proves noisy, a
`lastSeen`-bounded universe is the fix — not per-surface sets. *Amended by ADR 2026-10.k8ge (2026-10-04): it is — a stranger
not heard from for a week gives way (still labelled apart, no longer suffixing anyone), and contacts never go stale.*

**What this is not.** Anti-impersonation. The alias carries ~15 bits (178 × 182 word pairs) and a matching
keypair can be ground in seconds, so this is *disambiguation*: it separates the accidental collision and makes
the deliberate one visible, and nothing more. Trust stays where it was — `verified` and the safety number.
`NameKey` deliberately does no confusable folding (a Cyrillic "а" stays distinct from a Latin "a"); a
homoglyph impersonator is caught by the discriminator only when the two keys fold together, and that limit is
recorded rather than half-solved. *Amended by ADR 2026-10.2kfr (2026-10-29): the key now folds UTS #39's
single-letter Latin, Greek and Cyrillic lookalikes and drops invisible characters, so that limit narrows to
multi-letter lookalikes (`rn` for `m`) and other scripts.*

**The one wire-visible consequence.** The mention token a collided candidate inserts is the label —
`@Alice (JoyfulFerret)` — and `Mention.name` carries that exact text, which is what lets the unchanged
`highlightMentions`, the send-time reconciliation, and every deployed build locate the span. Detection is
still by node id; the wire shape is untouched (`GoldenVectorTest` did not move). The one sink that must
stay plain is `ReplyRef.author`, the quote snapshot a reply puts on the wire — `ChatRow.senderPlainName`
exists for it alone.

**Deferred, by design.** An impersonation *warning* when a non-contact adopts a contact's or your own name;
a node-id-derived avatar hue in-app (the notification hue now keys on the identity, not the name, so two
Alices at least differ in the shade); last-seen pruning of the universe (since done, ADR 2026-10.k8ge); and resolving `ReplyRef.authorId`
through the directory instead of rendering the sender's snapshot.
