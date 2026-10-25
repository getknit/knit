# Review rounds

How a change in a reviewed subsystem meets its reviewer personas (`.agents/personas/*-reviewer.md`): when each one
looks, what a pass reads, which findings hold the change up, and when to stop patching. The main session reads it
before it spawns a reviewer or relays one's findings; every reviewer reads it to learn which pass it is on. The Stop
hook (`.claude/hooks/review-gate.sh`) enforces the order and hands a follow-up pass its delta.

Why it exists: #195 took five full rounds of three reviewers each. The first design guessed, on each phone, whether
a pull ask came from the phone that wanted the blob or from an echo, and every round closed one loop and found the
next, until the design gained the signal it lacked (ADR 2026-10.2drc). Each pass also re-read the whole diff, so
findings unrelated to the last round kept arriving, and the test reviewers tightened tests that were later deleted.

## 1. Review the design before the code

- When `.agents/rules/personas.md` says DELEGATE a change to an owner persona, the main session first spawns that
  subsystem's reviewer on the plan: the brief it is about to send, with the files it would change. This is the
  **design pass**.
- The design pass reads the plan and the code the plan would change, walks the plan against its catalogue, and
  reports findings in its usual form, each labelled as in section 3. It runs no tests and no sweeps, and stamps
  nothing: the gate watches code, and none has moved.
- A blocking design finding changes the plan before the owner starts. The brief carries the non-blocking ones, so the
  owner designs against them.
- Skip it for a fix of a few lines whose shape the issue already names.

## 2. The subsystem reviewer goes first, the test reviewers after

- Stage 1 is the subsystem reviewers (`bluetooth`, `wifi-aware`, `lora`, `crypto`, `blobs`); stage 2 is the test
  reviewers (`mesh-lab-reviewer`, `unit-test-reviewer`). `review-gate.conf` records each reviewer's stage.
- Stage 2 runs once no stage-1 review is pending. The gate lists only stage 1 while a stage-1 reviewer is pending
  (owed, or asked for and not yet stamped), so the test reviewers never review tests written for a design the
  subsystem reviewer is about to reject.
- Reviewers in one stage run in parallel, spawned in one message.

## 3. Every finding is Blocking or Non-blocking

A reviewer labels each finding, and the verdict counts the blocking ones: `CLEAN`, or `FINDINGS: <n> (<b> blocking)`,
each finding opening with `[Blocking]` or `[Non-blocking]`.

- **Blocking:**
  - the change ships or keeps wrong behaviour: a loop or a strand (work that never ends, a frame or blob never
    delivered), a wedge, a crash, a leak, a security or privacy hole;
  - a wire, vector, convergence or frozen-string break;
  - a test that cannot fail on the behaviour the change exists for;
  - a flake whose window the chaos sweep or a one-core CI runner reaches in ordinary scheduling.
- **Non-blocking:**
  - a cost the reviewer can bound (a tick's delay, a few extra frames, a slower recovery);
  - a secondary test claim that cannot fail, or an oracle looser than the code;
  - a flake whose window needs a stall the scenario's own timings rule out;
  - a doc's wording, a stale comment, a cleanup.
- When the label is in doubt, the finding is Blocking, and the report says why it is in doubt.

Only a blocking finding opens another round. The main session hands the owner every blocking finding together with
the non-blocking ones in one batch; with no blocking finding left, it either has the non-blocking ones applied (the
gate's follow-up pass then checks them) or lists them for the maintainer as follow-ups, to file per
`.agents/rules/issues.md` if they agree. It never runs another full round for non-blocking findings alone.

## 4. A follow-up pass reads the delta

- A reviewer that stamped this change before, with no commit since, is on a **follow-up pass**. The gate writes the
  diff since its stamp to `<git dir>/claude-review/<name>.delta` and names that file in its message. The caller hands
  the reviewer the file and its previous findings.
- A follow-up pass:
  1. lists each previous finding as closed, or still open and why;
  2. reviews the delta, reading the whole function around each hunk and any new file whole;
  3. counts a new finding outside the delta only if it is Blocking. A non-blocking one outside the delta goes on a
     `Follow-ups:` line after the findings and does not count in the verdict;
  4. runs only the checks the delta touched (section 6), and stamps.
- A commit since the stamp that touches the reviewer's paths makes the next pass a full one.

## 5. Stop patching after two rounds

A round is the reviewers' pass over one batch of fixes. When two rounds in a row each bring a new blocking finding (a
finding still open from before does not count), the main session stops sending fixes. It names the cause the findings
share and puts the options to the maintainer: a different mechanism, a wire field, a narrower scope. Only then does an
owner write more code.

## 6. Don't run a check twice on one tree

- `scripts/lab-chaos.sh --reuse` answers from the last sweep on the same tree. Its fingerprint covers `app/`,
  `gradle/` and the root build files, so `.agents/` and `docs/` edits keep it. The owner runs the sweep as its last
  step that moves `app/`, after `ktlintFormat`, `ktlintCheck` and `detekt` are clean. Nothing edits `app/` between
  that sweep and the reviews.
- A reviewer reruns a test class only when the change it reviews (the delta, on a follow-up pass) touched that class
  or the code it drives. On a follow-up pass, the sweep and the stress cover only the classes the delta touched, at
  `--runs 10` and `--runs 8`, and mutation or `unit-prefix` runs cover only new or changed tests.
- Once the last round has no blocking finding, the main session runs one full sweep of every changed lab class
  (`--runs 20`) before calling the change done.
