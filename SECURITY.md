# Security Policy

Knit is an end-to-end-encrypted mesh messenger. Security reports are taken seriously, but note the
project ships **as-is with no warranty or guaranteed response** (see [`CONTRIBUTING.md`](CONTRIBUTING.md)).

## Reporting a vulnerability

**Please do not open a public issue or pull request for security vulnerabilities.** Public disclosure
before a fix exists puts users at risk.

Instead, report privately through GitHub's [**private vulnerability reporting**][report] — the
"Report a vulnerability" button on the repository's *Security* tab — which opens a private draft
advisory visible only to the maintainers. If you'd prefer email, write to **jeff@getknit.app**.
Please include:

- a description of the issue and its impact,
- steps to reproduce (or a proof of concept), and
- the affected version / commit.

Please allow reasonable time for a fix before any public disclosure. As a best-effort project,
there is no guaranteed acknowledgement or remediation timeline, but genuine reports will be reviewed.

[report]: https://github.com/getknit/knit/security/advisories/new

## Scope and known limitations

Knit is experimental. Several properties are **intentional design trade-offs, not vulnerabilities** —
they are documented and out of scope for reports:

- **The public "Nearby" broadcast room is plaintext by design** (it has no fixed recipient set), and
  its receipts and reactions stay cleartext with it.
- **Forward secrecy is epoch-granular, not per-message.** DMs run an epoch ratchet
  ([`docs/FORWARD_SECRECY_RATCHET.md`](docs/FORWARD_SECRECY_RATCHET.md)) and fully-updated groups a
  sender-key ratchet over those sessions
  ([`docs/GROUP_FORWARD_SECRECY.md`](docs/GROUP_FORWARD_SECRECY.md)), so a compromise exposes a
  bounded window rather than everything ever sent. The window is real, though: §9 of each doc lists
  the retention horizons that bound it.
- **Conversations with pre-ratchet builds fall back to the static-key scheme**, which has no forward
  secrecy, and one member on an old build pins a whole group to it. Receipts and reactions toward
  those peers fall back to their cleartext form for the same reason.
- **DMs currently flood the whole mesh** (only the addressed recipient delivers/acks); targeted
  multi-hop routing is future work, so relays see who is talking to whom and how often.
- **Trust-on-first-use (TOFU)** key pinning: a relay substituting keys before first contact is
  mitigated by out-of-band safety-number / QR verification, not prevented.

See the README's *Security note* and [`docs/`](docs/) for the full threat model and design detail. Novel
issues **beyond** these documented trade-offs — key handling, the crypto envelope, signature
verification, the CBOR wire parser, at-rest encryption, memory-safety in the radio layer — are in scope
and welcome.
