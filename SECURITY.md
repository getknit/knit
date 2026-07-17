# Security Policy

Knit is an end-to-end-encrypted mesh messenger. Security reports are taken seriously, but note the
project ships **as-is with no warranty or guaranteed response** (see [`CONTRIBUTING.md`](CONTRIBUTING.md)).

## Reporting a vulnerability

**Please do not open a public issue or merge request for security vulnerabilities.** Public disclosure
before a fix exists puts users at risk.

Instead, report privately by email to **jeff.mixon@gmail.com** with:

- a description of the issue and its impact,
- steps to reproduce (or a proof of concept), and
- the affected version / commit.

Please allow reasonable time for a fix before any public disclosure. As a best-effort hobby project,
there is no guaranteed acknowledgement or remediation timeline, but genuine reports will be reviewed.

## Scope and known limitations

Knit is experimental. Several properties are **intentional design trade-offs, not vulnerabilities** —
they are documented and out of scope for reports:

- **The public "Nearby" broadcast room is plaintext by design** (it has no fixed recipient set).
- **Reactions and delivery receipts are cleartext metadata** — signed, but not encrypted.
- **No forward secrecy:** E2E uses long-term static identity keys (no ratchet), so compromise of a
  device's identity key can expose past intercepted messages.
- **DMs currently flood the whole mesh** (only the addressed recipient delivers/acks); targeted
  multi-hop routing is future work.
- **Trust-on-first-use (TOFU)** key pinning: a relay substituting keys before first contact is
  mitigated by out-of-band safety-number / QR verification, not prevented.

See the README's *Security note* and [`docs/`](docs/) for the full threat model and design detail. Novel
issues **beyond** these documented trade-offs — key handling, the crypto envelope, signature
verification, the CBOR wire parser, at-rest encryption, memory-safety in the radio layer — are in scope
and welcome.
