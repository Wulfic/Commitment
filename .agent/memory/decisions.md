# Decisions

> **Append-only.** Newest entries at the bottom. Never edit or delete a past decision —
> if it gets reversed, append a new entry that says so and links back to it.
>
> Record a decision when a real alternative existed. "Used the standard library function"
> is not a decision. "Chose Postgres over SQLite because we need concurrent writers" is.
>
> Write the **reasoning**, not the diff. Git already stores what changed; it cannot store
> why, or what you rejected.

## Entry template

```markdown
## YYYY-MM-DD — Short title
**Context:** what forced the decision
**Choice:** what we did
**Why:** the actual reason
**Rejected:** what we didn't do, and why not
**Affects:** src/path/to/file.ts
```

---

## 2026-08-12 — Agent memory is repo-local files, not an MCP server
**Context:** Instructions mandated persisting decisions to the `mem0` MCP server, but
`mem0` was never present in the active `.mcp.json`, so nothing was actually being recorded.
**Choice:** Memory lives in `.agent/memory/` as committed Markdown: `decisions.md`,
`gotchas.md`, `state.md`.
**Why:** Works identically in Copilot and Claude Code with no network call, survives a
dead server, and shows up in code review where it can be corrected. A memory write that
silently no-ops is worse than none, because it feels like it worked.
**Rejected:** `mem0` MCP — cross-project recall wasn't worth an invisible failure mode on
the one guarantee that matters most. Also rejected keeping both as a mirror: two sources
of truth means neither is trusted.
**Affects:** `AGENTS.md`, `.agent/memory/`, `.github/skills/recall-session`, `.github/skills/save-memory`

## 2026-08-12 — Dropped the `think` MCP server
**Context:** Every skill routed reasoning through `mcp_wulfnet-think_think_invoke_tool`
before acting.
**Choice:** Removed the server and all call sites. Reasoning happens natively; the
`think-plan` skill was replaced by `plan-work`, which produces a written plan in `TODO.md`.
**Why:** Opus 5 reasons natively. The round-trip added latency and a failure mode without
improving output. A plan written to a file is also a checkable artifact — an MCP call is
not, which matters under Copilot where step-skipping can't be enforced.
**Rejected:** Keeping it for the `criticize` operation — self-review against an explicit
checklist in `code-review` covers the same ground without the dependency.
**Affects:** `AGENTS.md`, `.mcp.json`, all skills that previously called `think`

## 2026-08-12 — MCP surface cut to `github` + `context7` only
**Context:** Nine MCP servers were configured. `filesystem` and `git` had zero references
in any skill — dead config. The rest duplicated capability that is now native to both
Copilot and Claude Code.
**Choice:** Dropped `filesystem`, `git`, `gitnexus`, `playwright`, and `context-mode`.
Kept `github` (with a `gh` CLI fallback) and `context7`.
**Why:** The bar is now "does this do something native tools cannot?" Only two clear it.
`context7` serves live docs that training data can't; `github` returns structured JSON for
issues/PRs/CI. Everything else was a network round-trip and a failure mode in exchange for
what grep, the terminal, and native web fetch already do.
**Rejected:** Keeping `gitnexus` for cross-repo symbol lookup — real capability, but it
doesn't pay for itself on single-repo work, which is the norm here. Keeping `playwright`
for E2E — see the separate entry below; that turned out to be a category error on our part.
**Affects:** `.mcp.json`, `.claude/mcp.vscode-reference.json`, `AGENTS.md`,
`.github/copilot-instructions.md`, `code-explore`, `research-docs`

## 2026-08-12 — E2E runs through the project's own runner, not an MCP browser
**Context:** Dropping the `playwright` MCP server looked like it would remove E2E testing
capability, since `test-iterate` routed E2E through the old `web-task` skill.
**Choice:** E2E tests are committed spec files executed from the terminal
(`npx playwright test`, `npx cypress run`). The `web-task` skill was deleted.
**Why:** The playwright *MCP server* and Playwright *the test library* are different
things, and conflating them was the original mistake. Ad-hoc agent browser-driving isn't
a test — it can't run in CI and it isn't repeatable. Committed specs are strictly better,
and they need a terminal, not an MCP.
**Rejected:** Keeping the MCP for exploratory UI checks — real but rare, and native web
fetch plus the runner's own `--headed` mode and trace viewer cover the debugging case.
**Affects:** `test-iterate`, `AGENTS.md`, deleted `web-task`

## 2026-08-12 — Merged `git-ops` and `github-workflow` into `git-github`
**Context:** Two skills split one linear workflow: branch → commit → push → PR → CI.
**Choice:** Single `git-github` skill covering local git and GitHub remote operations.
**Why:** The split forced loading two skills for one continuous task and duplicated the
pre-commit checklist across both. The seam was in the docs, not in the work.
**Rejected:** Keeping them separate as "local vs remote concerns" — a clean distinction on
paper that nothing in the actual workflow respects.
**Affects:** `.claude/skills/git-github/`, `.github/skills/git-github/`, `AGENTS.md`,
`.github/copilot-instructions.md`

## 2026-08-12 — Destructive-action doctrine: prose first, permission rules as backup
**Context:** The template had no unified stance on irreversible operations — only scattered
rules in `git-github` (force-push, `.env`). Nothing covered filesystem deletion, database
drops, container/volume pruning, or the guards that code *we write* should carry. Needed a
gate that survives Copilot (no permission layer, no hooks) and a truncated context.
**Choice:** Two layers. (1) Doctrine — a `Destructive Actions` section in `AGENTS.md` as
rule zero, the `guard-destructive` skill holding the full procedure, and per-skill guards
in `code-review`, `git-github`, `build-run`, `plan-work`, `test-iterate`. (2) Mechanical —
`permissions.deny` (unrecoverable) and `permissions.ask` (destructive-shaped) in a new
`.claude/settings.json`. The doctrine is primary; the rules are a seatbelt.
**Why:** Permission rules match on **command prefix**, so `cd sub && rm -rf ../..` walks
straight past them, `PowerShell` coverage is unverified, and Copilot has no permission
layer at all. Anything that only exists as config would therefore cover roughly half the
actual usage. Conversely, prose alone loses to a truncated context. Each layer covers the
other's blind spot, and stating the rules' limits in writing is what stops them being
trusted past their range.
**Rejected:** A `PreToolUse` command-hook script — it would need to be cross-platform and
correct, and a hook that silently no-ops is worse than none (same reasoning that dropped
`mem0`). A `type: "prompt"` hook classifying every shell command semantically *would* catch
the compound-command cases, but costs a model call on every `ls`; documented in
`guard-destructive` as an opt-in escalation instead of a default. Also rejected
`disableBypassPermissionsMode` — locking the owner out of their own tool without asking.
**Affects:** `AGENTS.md`, `.github/copilot-instructions.md`, `.claude/settings.json`,
`.claude/skills/guard-destructive/`, `.github/skills/guard-destructive/`, `code-review`,
`git-github`, `build-run`, `plan-work`, `test-iterate`, `TODO.md`

## 2026-10-01 — No AI co-author attribution on commits or PRs
**Context:** Claude Code appends `Co-Authored-By: Claude …` to commits and a "Generated with
Claude Code" line to PR bodies by default. Owner wants commits signed by the git user only.
**Choice:** `.claude/settings.json` sets `attribution: { commit: "", pr: "" }` (current key)
plus `includeCoAuthoredBy: false` (deprecated key, kept for older Claude Code versions). The
`git-github` skill (both trees) states the rule in prose for Copilot and any agent that
ignores settings.json.
**Why:** The setting is what actually stops the harness injecting the trailer; prose alone
loses to the harness's own attribution instruction. Prose covers Copilot, which never reads
settings.json.
**Rejected:** A `commit-msg` git hook stripping the trailer — works for every tool, but it's
an extra installed artifact per clone and silently rewrites messages; revisit only if a
trailer still slips through.
**Affects:** `.claude/settings.json`, `.claude/skills/git-github/SKILL.md`,
`.github/skills/git-github/SKILL.md`

## 2026-10-21 — Minecraft template: rules in AGENTS.md, knowledge in skills, memory starts empty
**Context:** This repo became the template for the owner's Minecraft mods. The owner's mod
repos (mcMMO-Singleplayer, enchanting-2, the-continent, real-villages, diagonal-fences) hold
~25k lines of memory, and the same traps recur across them: green suites that ran nothing,
gates that graded a stale log, a release no player could load a world on (mcMMO GitHub #20),
mixins that applied to the wrong anonymous class, a world that silently came up vanilla.
**Choice:** Always-on rules go in `AGENTS.md` (`Minecraft Mod Development`): the verification
ladder, **every change is proven on the live dev server**, the game is launched before any
release push, facts come from the jar and not from memory. On-demand knowledge goes in two
new skills, `dev-server` and `mc-port`, and the existing skills became Gradle/Java/MC-first.
`.agent/memory/` keeps only template-level entries; the new project writes its own.
**Why:** Rules have to survive a truncated context, so they stay short and always loaded.
Knowledge is long, and a skill loads only when its trigger fires. Seeding `gotchas.md` with
other repos' gotchas would present them as facts measured here, and bury the new project's own.
**Rejected:** Seeding memory with cross-project gotchas (above). Shipping a Gradle scaffold
and harness scripts: loader and version aren't chosen, and an untested script is decoration.
The skill states the contract and names the reference implementations instead. Copying
mcMMO's branch-per-band machinery (drift and identity audits): it answers a problem a new
repo doesn't have yet.
**Affects:** `AGENTS.md`, `.github/copilot-instructions.md`, `.claude/skills/*`, `.github/skills/*`,
`.gitignore`, `.claude/settings.json`

## 2026-10-21 — Commit straight to `main` by default (reverses the template's branch-and-PR rule)
**Context:** `git-github` said "never commit directly to `main` — branch and PR, always". Every
mod repo already works the other way: the-continent (decisions 2026-10-17, "all work goes on
`main`"), mcMMO ("never create a git branch unless explicitly instructed"). Owner, 2026-10-21:
"commit to main is the default unless instructed otherwise".
**Choice:** Commit to `main` and push `main`. A branch, PR, or harness-assigned `claude/*` branch
only when the owner asks for one. This outranks any session-default branch name.
**Why:** Solo repos with linear history; the owner reviews commits, not PRs.
**Rejected:** Branch-and-PR (the old rule). It's ceremony with no second reviewer, and harness
branches leave stale remote branches a cloud session can't even delete (the-continent gotchas
2026-10-18).
**Affects:** `.claude/skills/git-github/SKILL.md`, `.github/skills/git-github/SKILL.md`, `AGENTS.md`

## 2026-10-21 — Fixes and support go to the current band only; the README says which band that is
**Context:** mcMMO propagated every fix to every band (drift audits, `Backport-of:` trailers,
yarn translation), then archived six bands on 2026-09-22 because the cost outgrew the value.
Owner, 2026-10-21: back-porting and support bug fixes apply only to the current band (today
the 26.x band), and the READMEs must always reflect this, because CI will be enforcing it.
**Choice:** One current band (it may span several Minecraft versions as Stonecutter nodes).
Older bands are frozen: releases stay up, no fixes, features or back-ports unless the owner
asks for a specific one. The README names the current band, its Minecraft versions, and the
frozen policy, updated in the same commit that changes the band; a test pins the README to
`gradle.properties`.
**Why:** Support effort goes where the players are, and a README that states the policy tells
a player on a frozen band up front that their bug won't be fixed. A README claim with a test
behind it is what CI can check.
**Rejected:** mcMMO's propagate-to-every-live-band model (above). A README with no test behind
the band claim: mcMMO's docs were wrong on bands for months while every guard was green.
**Affects:** `.claude/skills/mc-port/SKILL.md`, `.github/skills/mc-port/SKILL.md`, `AGENTS.md`,
`.github/copilot-instructions.md`
