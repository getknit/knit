# Coding standards

Invariants for all Kotlin/Compose/data code. (Mesh-specific invariants are in `rules/mesh.md`.)

## Style & DI

- Match the surrounding Kotlin style: official Kotlin style, 4-space indent, trailing commas.
- **DI is Koin.** Declare singletons/ViewModels in the `di/` modules; resolve ViewModels in Compose with
  `org.koin.androidx.compose.koinViewModel()` and the ViewModel DSL from
  `org.koin.core.module.dsl.viewModel` (**not** the deprecated `androidx.viewmodel.dsl` one). Why Koin and
  not Hilt: `context/toolchain.md`.

## Compose

- **Don't bind a Compose `TextField` directly to a DataStore-backed flow.** The async write→emit
  round-trip lags a keystroke and resets the field (you can only type one character). Hold editable text
  in a local `MutableStateFlow` in the ViewModel and persist to DataStore in the background — see
  `ProfileViewModel`.

## Data layer

- **Multi-step data-layer mutations must be transactional — the "single writer" was a myth.** The mesh
  serializes *inbound* frames through one `MeshRouter` `inbound.collect`, but the same repos are also
  written off that path — UI actions on `viewModelScope`, `NotificationActionReceiver` on the app mesh
  scope, and session-scope loops (the 10-min custody prune, `watch*`, `heal`) — all on multi-threaded
  dispatchers, so a read-then-write or two-table write **can** interleave. Any such mutation runs in
  **`db.withTransaction { }` at the repository layer** (the `GroupRepository.recordDeparture` idiom; there
  are **no** DAO `@Transaction` methods — keep DAOs thin). `withTransaction` issues `BEGIN EXCLUSIVE`, so a
  second transaction's `SELECT` can't run until the first commits — that alone closes the check-then-act
  cases (`ReactionRepository.apply`'s LWW, `ForwardRepository`'s count→evict, `GroupRepository.leave/delete`,
  `BlobRepository.deleteIfUnreferenced`) and the UI-`leave`-vs-`InboundPipeline.reconcileGroup`
  group-resurrection race (both sides must be transactional, or the blind roster upsert re-creates a
  just-left group). **Two things a Room transaction can't cover:** (1) in-memory state that must stay in
  lockstep with the committed rows — `ForwardRepository`'s shared `StoreDigest` — also needs a repo-level
  **`Mutex`, held *outer* to `withTransaction`** (inner deadlocks on SQLCipher's single connection), with
  the digest updated **after** commit under that lock; (2) a **DataStore** read (own-avatar hash,
  blocked-ids) can't enroll, so hoist it **before** the transaction. A blob GC racing an *independent*
  inserter is only narrowed, not closed — the content-addressed blob self-heals via a `BlobExchange`
  re-pull. Finding #13 in `docs/ARCHITECTURE_REVIEW.md`.
