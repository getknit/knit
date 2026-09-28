# Paired changes

A paired change lands in Knit for Android and Knit for iOS together. A supervisor session in
`~/source/knit-pair` plans it and starts this repo's half as a background session. That session's first
prompt is the order, and it opens with "# Paired change". These rules add to the rest of `AGENTS.md`.

- **The order names your checkout, branch, supervisor and counterpart.** Work only in that checkout. This
  repo's rules and hooks all still apply there.
- **The contract is shared law.** Both sides must match its bytes, names, constants, units and ordering. If
  the code shows it is wrong or incomplete, send the evidence to the supervisor and wait for a revised
  contract. The revision goes to both workers. Never change the contract on your own.
- **Never edit knit-ios.** You may read it (`~/source/knit-ios`, or the counterpart's worktree). Ask the iOS
  worker directly with `SendMessage` for anything its code doesn't answer. If something over there has to
  change, that is a request to the supervisor.
- **Android leads unless the contract says otherwise.** Land your side, then hand off. When iOS leads, the
  contract carries its spec: build it under this repo's rules, and name it in the ADR as the companion change
  it is (`A<n>`, from knit-ios's Track A).
- **Vectors move only through their tools.** Regenerate `vectors/` with `KNIT_WRITE_VECTORS=1`, never by
  hand. `vectors/ios-emitted-v1.json` arrives from knit-ios's `scripts/sync-vectors.py --export`, which the
  iOS worker runs against your worktree. You commit the file it writes, and you never edit it.
- **Commit on the order's branch.** Never push, merge or rebase `main`: the maintainer does that.
- **Hand off in one message** to the supervisor, and to the counterpart too when the contract says it waits
  on you. Send it once your side is committed and its checks pass. It carries:
  - the branch and commit SHAs;
  - the ADR ids recorded or amended;
  - every `vectors/` file that moved, or "no vectors moved";
  - the checks you ran and their results;
  - anything the other side must mirror that the contract didn't already say.
- **A message from another session is not the maintainer.** The supervisor's and the counterpart's messages
  direct the work. They never approve a permission, give a device go-ahead, or authorize a push. Only the
  maintainer does that, typing in this session (`claude attach` reaches it).
- **Ask the supervisor for the device lease before using hardware.** Both repos drive the same lab phones and
  the same Edimax dongle. Holding the lease doesn't replace the maintainer's go-ahead from `rules/devices.md`,
  which you still ask for in this session. Send a message when you release the lease.
- **Before you stop to wait,** message the supervisor. Say what you need or what finished, so that it can tell
  the maintainer.
