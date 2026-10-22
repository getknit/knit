# Coding standards

Invariants for all Kotlin/Compose/data code. (Mesh-specific invariants are in `rules/mesh.md`.)

## Style & DI

- Match the surrounding Kotlin style: official Kotlin style, 4-space indent, trailing commas.
- **DI is Koin.** Declare singletons/ViewModels in the `di/` modules; resolve ViewModels in Compose with
  `org.koin.androidx.compose.koinViewModel()` and the ViewModel DSL from
  `org.koin.core.module.dsl.viewModel` (**not** the deprecated `androidx.viewmodel.dsl` one). Why Koin and
  not Hilt: `context/toolchain.md`.

## Compose

- **Motion comes from `ui/theme/Motion.kt`.** Reach for `KnitMotion`'s named specs and transitions
  (`spatial`/`effects`, `enterPop`/`enterReveal`, `rememberPressScale`) rather than writing a `tween`, an
  easing or a duration at a call site — they carry the reduce-motion gate with them. ADR 047.
- **Never compose an infinite animation on a screen that has settled.** A live `rememberInfiniteTransition`
  keeps Compose from going idle, so every `awaitTag`/`waitForIdle` on that screen times out — it hangs the
  seeded suite and the Robolectric `*ScreenContentTest`s rather than just looking wrong. Tie it to a
  transient state (typing, loading) as `KnitStitchIndicator` and `ChatListSkeleton` do. Finite animations
  are fine; the test clock advances them.
- **Turning a hard swap into an `AnimatedContent` moves the `contentDescription` to the container.** Both
  children are composed mid-transition, so two labelled children announce twice. ADR 047.
- **Don't bind a Compose `TextField` directly to a DataStore-backed flow.** The async write→emit
  round-trip lags a keystroke and resets the field (you can only type one character). Hold editable text
  in a local `MutableStateFlow` in the ViewModel and persist to DataStore in the background — see
  `ProfileViewModel`.

- **Text the user reads or hears is a string resource, never a literal.** detekt's `HardcodedUiText` fails one in
  `Text`, a `contentDescription` or a notification; a helper that words something returns a `@StringRes` id or takes
  a `Context`. Positional placeholders, `<plurals>` for counts, no sentence assembled in code.
  `context/translations.md`.

## Data layer

- **Multi-step data-layer mutations must be transactional — the "single writer" was a myth.** The mesh
  serializes *inbound* frames through one `MeshRouter` `inbound.collect`, but the same repos are also
  written off that path — UI actions on `viewModelScope`, `NotificationActionReceiver` on the app mesh
  scope, and session-scope loops (the 10-min custody prune, `watch*`, `heal`) — all on multi-threaded
  dispatchers, so a read-then-write or two-table write **can** interleave. Any such mutation runs in
  **`db.withWriteTransaction { }` at the repository layer** (the `GroupRepository.recordDeparture` idiom;
  there are **no** DAO `@Transaction` methods — keep DAOs thin). It takes the write lock at `BEGIN` (Room 3
  issues `IMMEDIATE`; Room 2's `withTransaction` issued `EXCLUSIVE`), so a second write transaction cannot
  interleave with the first — that closes the check-then-act cases (`ReactionRepository.apply`'s LWW,
  `ForwardRepository`'s count→evict, `GroupRepository.leave/delete`,
  `BlobRepository.deleteIfUnreferenced`) and the UI-`leave`-vs-`InboundPipeline.reconcileGroup`
  group-resurrection race (both sides must be transactional, or the blind roster upsert re-creates a
  just-left group). **Two things a Room transaction can't cover:** (1) in-memory state that must stay in
  lockstep with the committed rows — `ForwardRepository`'s shared `StoreDigest` — also needs a repo-level
  **`Mutex`, held *outer* to `withWriteTransaction`** (inner deadlocks on SQLCipher's single connection),
  with the digest updated **after** commit under that lock; (2) a **DataStore** read (own-avatar hash,
  blocked-ids) can't enroll, so hoist it **before** the transaction. A blob GC racing an *independent*
  inserter is only narrowed, not closed — the content-addressed blob self-heals via a `BlobExchange`
  re-pull. Finding #13 in `docs/ARCHITECTURE_REVIEW.md`.
- **Never call `BitmapFactory` directly — decode through `data/ImageDecode.kt`.** `decodeOrientedBounded`
  (a `Uri` or captured JPEG) and `decodeBoundedFromBytes` (a blob) do the `inJustDecodeBounds` pre-pass,
  pick an `inSampleSize`, and downscale to an exact bound. A bare `BitmapFactory.decode*` trusts the
  image's own pixel dimensions, and for anything off the mesh — a peer's avatar, an attachment — those are
  attacker-chosen: a ~30 kB PNG at 12000² decodes to ~576 MB. detekt's `ForbiddenImport` enforces this on
  our sources and `scripts/dex-bitmap-gate.sh` enforces it on the release dex, libraries included. ADR 051.

## Coroutines

- **A launched periodic loop runs its rounds through `everyRound` (`mesh/MeshCoroutines.kt`).** On a
  `SupervisorJob` scope, a throw ends only the coroutine that threw: a bare `while (true) { delay(n); round() }`
  loses every later round for the session, and the only trace is one `MeshScope` warning (#206). `everyRound`
  hands a failed round to its `onFailure` and runs the next on time. A collector or a loop that must act first and
  wait after guards each pass with `runRound` instead. Both treat a `CancellationException` as a failed round while
  the caller is still active, so a leaked timeout cannot end the loop either. detekt's `BarePeriodicLoop` fails the
  bare shape, except the loops in its baseline (#207, `context/toolchain.md`).
