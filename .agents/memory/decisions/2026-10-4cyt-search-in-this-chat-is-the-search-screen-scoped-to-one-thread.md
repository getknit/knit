---
id: "2026-10.4cyt"
slug: search-in-this-chat-is-the-search-screen-scoped-to-one-thread
title: "Search in this chat is the search screen scoped to one thread, and its hit is handed back to the open thread"
date: 2026-10-29
topics: [ui, search, navigation]
---

# ADR 2026-10.4cyt — Search in this chat is the search screen scoped to one thread, and its hit is handed back to the open thread

Status: Accepted (2026-10-29; the `search?in=` route, `Routes.SEARCH_HIT`, `SearchViewModel.inConversation`,
ChatScreen's `handedJumpId` and the overflow's Search item)

**What was observed.** App-wide search (ADR 2026-09.wnh6) finds a message anywhere, but someone reading a busy
group who wants the one thing said there had to leave the thread, search everything, and pick the group's hits out
of everyone else's. wnh6 deferred the scoped search on one trap: a hit has to scroll the thread that is already
open, and navigating to `chat(id, messageId)` from there stacks a second entry for the same thread, so two
`ChatViewModel`s race the one-shot draft restore.

**What changed.** The overflow of every thread now starts with Search, and it opens the same search route with an
optional `in` argument. `SearchViewModel` takes it as `inConversation` and answers Messages alone, from the visible
threads filtered to that one. The thread stays inside the chat list's universe, so a stranger's request thread or a
blocked peer's DM answers nothing here, by the same predicate as everywhere else. The screen words its hint and idle
line for one chat. It drops the Messages header and each row's thread avatar and title, which would repeat on every
row, and keeps the speaker, the snippet and the time. A hit does not navigate. It writes the message id into the
previous entry's `savedStateHandle` under `Routes.SEARCH_HIT` and pops. The chat route collects that key and hands
it to `ChatScreen` as `handedJumpId`, which feeds it into the quote-jump machinery that the route's `messageId`
already uses, then clears the key. The clear makes it one-shot by itself. It is kept apart from the route argument's
saveable `consumedJump` guard, because that guard refuses a value it has seen, and the same message found twice
must jump twice. An unconsumed key sits in saved state, so a process death between the pop and the jump still jumps
on restore. The bridged Meshtastic room, which had no overflow at all, now gets one holding Search alone; Send
location and the DM items stay off it. Declined: an in-thread find bar with previous and next arrows (it needs a
second result model inside `ChatScreen` and steps through a paged window, while the search screen and its jump
already exist); People and Chats sections in the scoped screen (a single thread has no others to find).

**What it costs.** Back from the scoped search returns to the thread at the position it had, not to the results; a
second look means tapping Search again. The scoped search inherits wnh6's bounds and a hit deeper than
`ChatWindow.MAX` still opens at the newest with no flash. A scoped search from inside a thread opened by app-wide
search is a third back-stack entry; that is intended, each with its own query. What keeps this true:
`SearchViewModelTest` (a scoped search answers its thread's messages only, no chats or people, and nothing in a
request thread), `SearchScreenContentTest` (the scoped hint and idle line, no header, rows that do not name the
thread), `ChatScreenContentTest` (Search leads the overflow, the bridged room's menu is Search alone, a handed-back
hit is cleared and the same one handed again jumps again) and the `SearchScreenInChat` screenshot references.
