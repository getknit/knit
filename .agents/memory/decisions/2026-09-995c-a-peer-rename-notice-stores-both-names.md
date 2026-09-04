---
id: "2026-09.995c"
slug: a-peer-rename-notice-stores-both-names
title: "A peer rename notice stores both names"
date: 2026-09-04
topics: [ui, data]
---

# ADR 2026-09.995c — A peer rename notice stores both names

Status: Accepted (2026-09-04). Supersedes the peer half of ADR 066's "composed at render time" rule.

**What was observed.** A DM thread ended in two status lines, `I am a songwriter is now Kai` directly
above `Bushybramblepat h is now Kai`, and the second read as a bug: the reader knew the contact's previous
handle, and it was the first one. It was not a duplicate. The contact had renamed themselves twice, and
under ADR 066 a `KIND_PEER_RENAMED` row stored only the *old* name, with the "is now …" half rendered from
the live peer directory. The first line had said `… is now Bushybramblepat h` until the "Kai" profile
landed, at which point both lines re-rendered to end in "Kai". ADR 066 recorded that rewrite as an
accepted cost. In practice the rewrite erases the middle step and the two lines then say the same thing,
which is what a duplicate looks like. (The odd space in the middle name is what the peer's device sent;
the name field is single-line and capped at 32, so it is a typo, not truncation.)

**What changed.** `StatusNotices.peerRenamed` now takes the new name as well, and the row's `body` holds
both as a small JSON object, `{"from":"Old","to":"New"}`, decoded by `PeerRename`. `ChatScreen`'s
`statusNoticeText` renders `from` and `to` verbatim, so a second rename reads as a progression, `Old is now
Mid` then `Mid is now New`, and each line stays a record of its own step. The alternative a reader reaches
for first is a second column on `messages`. That is a Room bump (v9 to v10, a hand-written migration, a
schema JSON, a migration-test case) for one nullable string that only ever accompanies one `kind`, and it
would strand every lab device on an older build the moment it opened the database. The `body` column is
already free text for a notice, and `mentions` set the JSON-in-a-TEXT-column precedent, so the pair rides
in the column the row already has and the schema is untouched. The row id, its `sentAt`, the deterministic
upsert on a re-served profile, the thread gate and the exclusion from unread counts are all as ADR 066 left
them; only what the body carries changed.

**What it costs and does not cover.** Rows written before this hold a bare previous name. `PeerRename.decode`
reads anything that is not an encoded pair as that legacy form with `to = null`, and the renderer falls back
to the live label for it, which is exactly what those rows rendered with before, so the two lines in the
screenshot above stay as they are on that device. A peer who *clears* their name is stored the same way
(`to` blank reads as null), so the line ends in their live label, their alias, rather than in nothing. The
new half no longer picks up the ` (Alias)` collision suffix of ADR 058, because it is a snapshot rather than
the live label; the old half never did. The group form is unchanged and still stores only the *new* name,
for the reason ADR 066 gives. `PeerRenameTest` pins the codec boundary (round-trip, JSON-looking names, the
legacy bare form, the cleared name, odd-but-valid JSON) and `InboundPipelineTest` pins the two-step
progression that motivated this.
