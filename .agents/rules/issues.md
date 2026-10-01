# Filing issues

Issues live on the GitLab project (`origin`, `knit/knit-next`); `glab` is the client. Every issue an agent
files uses one template, `.gitlab/issue_templates/Agent.md`, so the backlog reads as one format. The human
templates beside it (`Bug.md`, `Feature.md`) are for people filing from the web UI.

## Before you file

- **Search first.** `glab issue list --all --search '<Component>'`. If an open issue covers it, add a
  comment with the new evidence instead of a second issue.
- **Wire-break-only fixes** still get an issue, and the item is also parked in `docs/NEXT_WIRE_BREAK.md`
  (`rules/mesh.md`).
- **The iOS half** of a cross-platform finding is filed on knit-ios by that repo's session, never from here.

## The shape

- **Title:** `<area>/<Component>: <what goes wrong, as one plain sentence>`. The behaviour, never the fix.
- **Headings,** verbatim and in order: `## What happens`, `## Evidence`, `## In the field` (optional),
  `## Options`, `## Test`. Delete an optional section rather than leaving it empty; don't rename, merge or
  add sections ("Seen on the lab", "Directions", "Suggested fix" are all `Evidence` or `Options`).
- **Labels:** exactly one of `bug` / `enhancement` / `task`, the area label (`mesh`, `ui`, `testing`, `ci`,
  `deployment`, …), then any of `reliability`, `performance`, `security`, `minor`, `needs verification`.
  Use only labels that exist (`glab label list`); never create one.
- **Prose:** plain declarative sentences, absolute dates, code references a reader can open
  (`File.kt:123`, `Class.member`, ADR ids). Strip the template's `<!-- -->` guidance from the body.

## Filing it

Write the body to a file in the scratchpad with the Write tool (a prose heredoc in Bash trips the prose
hook), then:

```sh
glab issue create --title '<area>/<Component>: …' --label bug,mesh \
  --description-file <scratchpad>/issue.md --yes
```

Report the issue number back. A later fix's commit and ADR cite it as `#NN`.
