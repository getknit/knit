---
id: "2026-11.pk9u"
slug: chat-menus
title: "Chat menus, sheets and confirmations draw in the chat's own window"
date: 2026-11-01
topics: [ui]
---

# ADR 2026-11.pk9u — Chat menus, sheets and confirmations draw in the chat's own window

Status: Accepted (2026-11-01; `ui/chat/MessageMenuOverlay.kt`: `MessageMenuHost`, `MessageMenu`, `InWindowSheet`,
`InWindowAlertDialog`)

**What was observed.** A long-press on a bubble while typing closed the soft keyboard. The chat then reflowed to the
taller window, its bubbles slid down, and the menu jumped after the one it belonged to. It read as the menu closing
the keyboard on purpose. It was a side effect: the menu was a focusable `Popup`, which is a window of its own, and a
window that takes focus takes it from the composer, so the IME goes. The "+" emoji sheet (`ModalBottomSheet`) and the
Delete confirmation (`AlertDialog`) are windows too and did the same.

**What changed.** All three draw in the chat's own window, in a layer `MessageMenuHost` keeps over
`ChatScreenContent`. Focus never moves, so the keyboard stays up and nothing behind changes size; a text field in the
layer (the sheet's search) takes the same keyboard over. The layer dims the screen and draws the long-pressed bubble
over the scrim from a `GraphicsLayer` the bubble records only while its menu is open. With the keyboard up a seven-row
menu has room on neither side of most bubbles and was clamped over the one it belongs to (seen on an API 36 emulator),
so `menuPlacement` leaves the bubble in place when the menu fits above or below it, and otherwise draws it moved as
little as possible so the two stack inside the area the keyboard, bars and cut-outs leave (`WindowInsets.safeDrawing`):
the long-press lift Signal has. A tap off the layer or Back closes it. The screen behind is hidden from TalkBack while
it is open, and each layer carries a pane title ("Message options", the sheet's "More reactions", the dialog's own
title), since no window opens for TalkBack to notice; on the emulator TalkBack's focus landed on the menu's first
reaction as it opened. One layer at a time, owned by the composable that
opened it: a bubble scrolled away or deleted takes its menu with it.

The alternative a reader reaches for first is a non-focusable `Popup` (`PopupProperties(focusable = false)`): one
line, and the keyboard stays. It lets a tap outside the menu land on whatever is under it as well as close it, it
receives no Back (the press goes to the IME, then to the screen, which navigates out of the chat), and its window
sits behind the IME's, so the menu can be placed under the keyboard. Hiding the keyboard on purpose before opening the
menu keeps the reflow, which was the complaint.

**What it costs.** The sheet has no drag-to-dismiss (a tap above it or Back closes it), and with the keyboard up it is
as tall as the space above the keyboard allows. With the keyboard up, the first Back closes the keyboard and the
second the layer, because the system hands Back to the IME first. Outside a host (a preview) each falls back to its
window form. `ChatScreenContentTest` pins the contract: one root while the menu, the confirmation or the "+" sheet is open
(Robolectric moves no focus between windows, so the root count, not a focus check, is what fails on a window), the
pane title, a tap on the layer's own padding not closing it; `PickerPositionTest` pins `menuPlacement`. A new chat surface that
opens from a long-press or the menu goes through the layer, not a `Popup`, `ModalBottomSheet` or `AlertDialog`.
