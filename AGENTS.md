# Agent Instructions

Work like a senior developer with zero patience for slop. Be direct. Assume code is
broken until a test proves otherwise. Never pad a report with praise — silence means
"no objection", not "good work". When something is wrong, say so plainly and fix it.

**Non-negotiables:**
- **Nothing irreversible without a guard.** Before anything that deletes, overwrites,
  rewrites history, or changes shared state: resolve the target, prove it's recoverable,
  dry-run it, narrow the scope, write the undo, confirm. And code that destroys ships with
  its own guards. See [Destructive Actions](#destructive-actions) — this is rule zero.
- **Write the plan down before you write code.** A plan that lives only in chat did not happen.
- **Record what you learn.** Decisions and gotchas go in `.agent/memory/` — see [Memory](#memory) below. Write at checkpoints as you reach them (finishing phase 3 of 12, not just at session end). Not optional.
- **Tests before "done".** No feature is complete without a test that fails when the feature breaks.
- **Proven on the live dev server.** A change that touches gameplay, server logic, data
  files, mixins, config or networking is not done until it ran on the dev server and the log
  shows it working. Green tests are the entry ticket, not the verdict. See
  [Minecraft Mod Development](#minecraft-mod-development).
- **Launch the game before any release push.** The real client creates and joins a new
  world, and the built jar boots on a real server, on every band and loader being released,
  recorded in `TODO.md`. Nothing substitutes for it.
- **Commit to `main`.** No branches or PRs unless the owner asks for one. This outranks any
  harness-assigned branch.
- **Never commit a secret.** Every repo must be safe to make public: real values live only
  in git-ignored files; the tracked `*.example*` files hold placeholders.
- **Log every error path.** If it can fail and there's no log, that's a bug.
- **Zero suppressions.** No empty `catch`, unexplained `@SuppressWarnings`, mixin `require = 0` / `-Dmixin.ignoreRequired`, unexplained `@allow-error`, or `--no-verify`. If you think you need one, you've misdiagnosed the problem.
- **Never commit red.** Zero errors and green tests, or it doesn't get committed.

---

## Destructive Actions

**Rule zero. Everything below this section assumes it.** Full procedure lives in the
`guard-destructive` skill; this is the part you are expected to know without opening it.

An action is destructive if **a mistake would lose state that isn't reproducible from
what's on disk and committed.** Not "sounds dangerous" — that test, mechanically:

| Class | Examples |
|---|---|
| Filesystem | `rm -rf`, `Remove-Item -Recurse -Force`, `truncate`, `> file`, `mv` onto an existing path, **writing a file you never read** |
| Git — local | `reset --hard`, `clean -fd`, `checkout -- .`, `restore`, `branch -D`, `stash drop`, `commit --amend`, `rebase` |
| Git — shared | `push --force`, deleting a remote branch, tag, or release |
| Data | `DROP`, `TRUNCATE`, `DELETE`/`UPDATE` with no `WHERE`, migration `down`, reset/seed scripts |
| Containers / infra | `docker system prune`, `volume rm`, `compose down -v`, `kubectl delete`, `terraform apply`/`destroy` |
| Outward-facing | Publishing, releasing, closing an issue or PR, anything other people see |

### The five gates — all five, in order, every time

1. **Resolve the target.** Print the variable, expand the glob, `ls` the directory. You
   destroy the output you just read, never the pattern you typed. An unset variable turns
   a scoped delete into a root delete.
2. **Prove it's recoverable.** Name the specific copy — a commit, a verified backup, a
   snapshot. If the honest answer is *nowhere*, make the copy first.
3. **Dry-run it.** `git clean -nd`, `terraform plan`, `--dry-run`. No dry-run in the tool?
   Build one — `SELECT` before `DELETE`, `COUNT(*)` before `TRUNCATE`. Read the output.
4. **Narrow the scope.** The file, not the directory. One named branch, not `-fd`.
   `WHERE id = @id`, not the table. **Never inside a `&&` chain or a loop** — one command,
   read the result, then decide the next.
5. **Write the undo, then confirm.** If you can't write the reversing command, you have a
   hope, not a plan. Quote the exact command and blast radius to the user before running it.

### Absolute stops — need explicit, in-the-moment instruction

`rm -rf` on `/`, `~`, a drive root, or the repo root · `push --force` to a shared branch ·
`reset --hard` or `clean -fd` with uncommitted work · dropping or truncating a database
this session didn't create · `docker volume rm` / `compose down -v` · `terraform destroy` ·
deleting `.agent/memory/`, `.env`, or credentials · deleting a test or using `--no-verify`
· rewriting work that isn't yours · killing `java` by name · writing into the owner's saves
or launcher instance · moving or deleting a published tag or release.

**"I assumed that's what you meant" is not authorization.** Ambiguous instruction plus
irreversible action means stop and ask. Approval for one destructive action never carries
to the next.

### Code you write gets the same treatment

Anything that deletes, overwrites, resets, or migrates is not done when it works — it's
done when it's hard to misuse:

- `--dry-run` is the **default**; `--force` is the opt-in
- Confirmation states **what and how many** — a prompt with no count trains people to hit enter
- **Refuse on an empty filter.** `if (!filter) throw`. "No filter means match everything"
  has destroyed more data than any bug
- Parameterised queries; paths resolved and asserted inside the intended root
- Soft-delete over hard delete; every migration `up` has a `down` that has actually been run
- Back up, **verify the backup**, then destroy — in that order
- Fail closed: if the guard can't prove it's safe, it refuses
- Log before and after, including the recovery path and the actual affected count
- **A test that feeds the bad input and asserts nothing was destroyed.** A guard with no
  test is decoration — it gets refactored away as dead code and nothing fails

### Mechanical backup, and its limits

`.claude/settings.json` blocks unrecoverable commands (`permissions.deny`) and prompts on
destructive-shaped ones (`permissions.ask`). It matches on **command prefix**, so
`cd sub && rm -rf ../..` slips past it, `PowerShell` coverage is unverified, and Copilot
has no permission layer at all. **It's a seatbelt, not a sandbox.** The five gates are the
actual guard.

---

## Operating Reality

This repo is driven primarily by **Opus via GitHub Copilot**, not the Anthropic API.
That means there is no enforced thinking budget, no hooks, no permission gates, and
context gets truncated without warning. The rules below are written to survive that:

- **Every gate produces an artifact.** "Think first" is unenforceable. "Write the plan
  to `TODO.md` before your first edit" is checkable — if the entry isn't there, the step
  was skipped. Prefer instructions that leave evidence.
- **Checkpoint as you go.** Update `.agent/memory/state.md` at each phase boundary, not
  just at session end. If context is truncated mid-task, `state.md` is how the next turn
  recovers instead of restarting.
- **Small diffs, verified often.** Do not stack ten edits and then build. One logical unit
  → build → next. A truncated context with ten unverified edits is unrecoverable.
- **Every loop has a budget.** See [Attempt Budgets](#attempt-budgets). A model that can't
  see its own repetition will grind on the same failure forever unless the budget is explicit.

---

## Minecraft Mod Development

These rules come from the owner's mod repos (mcMMO-Singleplayer, enchanting-2, the-continent,
real-villages, diagonal-fences). Each one is there because skipping it shipped a defect.
Detail lives in the `dev-server`, `mc-port`, `test-iterate` and `build-run` skills.

### The verification ladder: climb as high as the change reaches

| # | Rung | Required for |
|---|---|---|
| 1 | JUnit | Logic, codecs, data-file shape |
| 2 | Server GameTests | Anything that runs in a world |
| 3 | **Dev-server smoke** (`scripts/dev-server-smoke.sh` + a committed commands file) | **Every gameplay, server, data, mixin, config or networking change** |
| 4 | **Live-world run** on a copy of the owner's save | Owner bug reports; anything needing time, terrain, players or many entities |
| 5 | Client world check (client GameTest creates and joins a new world) | Client-side changes; **every release** |
| 6 | Built jar on a real loader server, world fingerprinted | **Every release** |
| 7 | Owner's instance | When the owner reports what the tests can't see |

**Why the live dev server is mandatory.** mcMMO v1.5.1 shipped with an advancement that
Minecraft 26.3 refused at registry load: no player could create or join a world, with a
green suite and every structural gate green. A hunger deadlock in real-villages survived
unit tests, GameTests and reasoning, and one run on a copy of the owner's save found it in
minutes. A Forge build of the-continent booted with no ERROR line and generated a
**vanilla** world. Tests run a Minecraft that was set up for the test; the dev server runs
the real one.

- **Fingerprint the effect, not the absence of errors.** "No ERROR" is also what a mod that
  did nothing prints. The dev-server check `@expect`s the log line or command reply that
  proves the change. A check that stays green with the change reverted is vacuous.
- **Exit codes mean one thing:** 0 proven · 1 the mod is bad · **2 nothing proven** (port
  busy, Gradle failed before launch). Exit 2 is never a pass. Neither is Gradle's exit 0
  without the harness's own PASS marker.
- **Record every run** (rung, command, exit code, matched lines) in `TODO.md` against the step.
- Live-world runs work on **copies**: never the owner's save or instance in place.

### The release gate

**Before any push that can publish a release** (a version bump, a tag, a push to a branch
whose version has no release, or any push in a repo whose CI releases on `feat`/`fix`; when
unsure, treat it as one), run rungs 5 and 6 on **every band and loader being released** and
record the results in `TODO.md`. Exit 1 or 2 blocks the push. A skip that is written down is
still a skip. There's no exception for a "small" change: #20 was one missing line in one
data file.

### Bands: fixes go to the current band only

The repo ships one **current band** (today the **26.x** band, which may span several
Minecraft versions as Stonecutter nodes). Older bands are **frozen**: releases stay up, no
fixes, features or back-ports unless the owner explicitly asks for a specific one. A fix is
done when every node of the current band is green and smoked.

**The README always states the current band**, the Minecraft versions it covers, and that
older bands get no fixes. It's updated in the same commit that changes the band, and a test
pins the README to `gradle.properties`.

### Facts come from the jar, never from memory

- Minecraft signatures, anonymous-class numbering, what a method calls: `javap` or decompiled
  sources of **that version** (`mc-port`). A mixin that "applied" can be on the wrong `$N`.
- Data formats change between versions and stale keys are **ignored, not rejected**. Read
  one vanilla file of each kind you ship from that version's jar.
- Dependency and loader versions come from official metadata, dated in a comment, with the
  Maven coordinate checked to resolve. Never bump from memory.
- **Never write a comment saying which Minecraft version this build targets.** It's false on
  every other band, and nothing reads comments.

### Count what ran

A green build has meant "ran zero tests" here more than once: an unlisted GameTest class, a
registered `Test` task with no classpath, a test reading a file Gradle didn't declare as an
input, `./gradlew | tail` reporting `tail`'s exit code. **Read the log, count the tests, and
watch the count move when you add one.** Mutation-check every guard (break it, watch it go
red, restore).

### Machine reality

- The owner works on **Windows 11 + Git Bash**, often with their own game and other repos'
  dev servers running. **Never kill `java` by name.** Find processes by command line and stop
  a verified PID you started. The dev server has its own port (not 25565) and its own run dir.
- **Never compile under a live `runServer`** (it runs from `build/classes`). Never run two
  builds on one tree.
- Under Stonecutter, **qualify run tasks** (`:<node>:runServer`); a bare one starts every node.
- Long runs go in the background with a monitor that reports failures, hangs and silence.

---

## Workflow — Scale the Process to the Task

### Tier 0 — Trivial
Typo, comment, string change, single-line fix, rename inside one file.

> Verify the claim → make the change → build → done.

No plan artifact, no memory entry. **If it touches logic, it is not Tier 0. If it deletes
or overwrites anything, it is not Tier 0 either** — destruction has no trivial tier.

### Tier 1 — Standard
Bug fix, bounded feature, refactor under ~5 files. **This is the default tier.**

```
1. Orient      /recall-session   — read state.md, TODO.md, git log
2. Plan        /plan-work        — write the approach into TODO.md
3. Explore     /code-explore     — find the existing pattern; do not invent a new one
4. Research    /research-docs    — only if a third-party API is involved
5. Implement                     — smallest working change
        ↳ anything destructive → /guard-destructive — five gates before the command runs
6. Verify      /build-run        — build + typecheck + lint
        ↳ fail → /debug-errors → back to 6   (budget: 3)
7. Test        /test-iterate     — write it, run it, watch it fail for the right reason
        ↳ red  → /debug-errors → back to 7   (budget: 3)
8. Prove live  /dev-server       — dev-server smoke (+ live world / client as the change reaches)
        ↳ fail → /debug-errors → back to 7   (budget: 3)
9. Review      /code-review      — read your own diff like you're rejecting it
10. Record     /save-memory      — decisions + gotchas → .agent/memory/
11. Commit     /git-github       — conventional commit on main, clean diff
        ↳ push that can release → release gate (/dev-server Part D) first
```

### Tier 2 — Complex
New subsystem, architecture change, migration, anything over ~5 files or crossing a
service boundary. **In a mod, always Tier 2:** a new Minecraft version or band, a new loader,
a new networking protocol, a world-data or config-format migration, a release.

Tier 1, plus:
- **A written plan reviewed before any code.** File-by-file, with the rollback path.
- **A decision record** in `.agent/memory/decisions.md` *before* implementing, not after.
- **Staged commits.** One reviewable commit per logical unit — never one 40-file commit.
- **An explicit "what I am NOT doing"** section in the plan, to stop scope creep.
- **A blast-radius line for every destructive step** — what it touches, what's lost if it's
  wrong, where it comes back from — and a rollback path that has been verified, not assumed.
  Migrations, deletions, and schema changes get their reverse written and tested *first*.

**Skipping a tier down to move faster is the most expensive mistake available.**
When genuinely unsure between two tiers, pick the higher one.

---

## Attempt Budgets

Unbounded "repeat until green" is an invitation to grind. Every loop stops:

| Loop | Budget | On exhaustion |
|------|--------|---------------|
| Same build/type error | 3 fixes | Stop. Write the failure + all 3 attempts to `gotchas.md`. Report to the user. |
| Same failing test | 3 fixes | Stop. Is the test wrong, or the design? Ask — do not delete the test. |
| Same runtime exception | 3 fixes | Stop. Add logging around the failure and re-run to get real data before guessing again. |
| Same dev-server / live-run failure | 3 fixes | Stop. Read the whole log from that run, not the verdict line; check the environment (port, stale log, other JVMs) before the mod. |
| Exploration with no useful hit | ~10 min | Stop searching. Say what you looked for and where. Ask. |

**"Budget exhausted" is a valid, professional outcome.** Reporting a hard blocker with
three documented failed hypotheses is genuinely more useful than a fourth guess. What is
never acceptable is silently widening the fix (deleting the test, adding a suppression,
`catch {}`) to make the symptom disappear.

**Rule:** if fix attempt #2 for a given failure doesn't work, stop patching and re-diagnose
from scratch. Two failed fixes means the diagnosis is wrong, not the fix.

---

## Memory

**Memory is repo-local files.** Not an MCP server, not chat history. Committed, reviewable
in a PR, and readable by any agent in any tool without a network call.

```
.agent/memory/
  decisions.md   append-only — what we chose, and why
  gotchas.md     append-only — traps, dead ends, environment quirks
  state.md       rewritten   — where we are right now, what's next
```

### Read at session start
`state.md` first (it's short by design), then grep `decisions.md` / `gotchas.md` for the
area you're about to touch. Before proposing an approach, **check whether it's already
been tried and rejected.** Re-litigating a settled decision wastes the user's time.

### Write at these moments — not just at session end
| Trigger | File |
|---------|------|
| Chose between real alternatives | `decisions.md` |
| A bug took more than 2 attempts | `gotchas.md` |
| Something behaved contrary to the docs | `gotchas.md` |
| An approach failed and was abandoned | `gotchas.md` |
| Finished a phase / about to hand off | `state.md` |

### Entry format — keep it greppable
```markdown
## 2026-08-12 — Short title
**Context:** what forced the decision
**Choice:** what we did
**Why:** the actual reason
**Rejected:** what we didn't do, and why not
**Affects:** src/path/to/file.ts
```

**Write the reasoning, not the diff.** Git already stores what changed; it cannot store
why, or which three approaches failed first. That "why" is the entire point — an entry
that just restates the code is wasted effort.

**A session that changed real behavior and wrote nothing to `.agent/memory/` is incomplete.**

---

## Skills

Skills live in `.github/skills/` (Copilot) and `.claude/skills/` (Claude Code). **The two
trees are byte-identical copies and are synced by hand — edit a skill in both, or they drift.**

Invoke explicitly with `/skill-name`, or let the agent select on the trigger.

| Skill | Use when |
|-------|----------|
| `guard-destructive` | **Before anything irreversible** — deleting, overwriting, force-pushing, dropping, pruning, destroying; or writing code that does |
| `recall-session` | Starting or resuming work; "what was I doing?" |
| `plan-work` | Any Tier 1+ task, before the first edit |
| `code-explore` | "Where is X?", "does this already exist?", finding the pattern to follow |
| `research-docs` | Any third-party API, SDK, or config option |
| `build-run` | Gradle builds, tests, GameTests, run tasks, long background runs |
| `debug-errors` | Build fails, type errors, exceptions, red diagnostics |
| `test-iterate` | Writing tests, fixing a red suite, TDD; JUnit, server and client GameTests |
| `dev-server` | **Proving a change live** — dev-server smoke, live-world runs, client world check, built-jar boot, the release gate, the owner's instance |
| `mc-port` | Minecraft versions, bands, loaders, mappings, dependency pins, mixin targets that move, the current-band support policy |
| `code-review` | Before commit, before PR, security or OWASP pass |
| `save-memory` | Recording a decision, gotcha, or session state |
| `git-github` | Branch, commit, tag, issues, PRs, CI status |

---

## Tools

**Native tools first.** File editing, search, terminal, git, and web fetch are all native
to both Copilot and Claude Code. An MCP server earns its place only by doing something
native tools cannot. Exactly two currently qualify:

| Server | Use for | Skill |
|--------|---------|-------|
| `github` | Issues, PRs, CI runs, releases | `git-github` |
| `context7` | Live library and API documentation | `research-docs` |

Config lives in `.mcp.json` (Claude Code) and `.claude/mcp.vscode-reference.json` (VS Code
format). Both route through `mcp-compressor`, which strips JSON-Schema noise to keep
context cheap. **Both are git-ignored and hold real tokens — never commit them.** Copy the
tracked `.mcp.example.json` / `.claude/mcp.vscode-reference.example.json` and fill in your
own values. Same for `.env` from `.env.example`.

**GitHub fallback:** if the `github` MCP is absent or erroring, use the `gh` CLI
(`gh auth status` to verify first). Never report a GitHub action as done without knowing
which path actually executed it — a silent MCP timeout looks exactly like success.

> **Dropped, and why:** `think` and `mem0` (Opus 5 reasons natively; memory moved to
> `.agent/memory/` where it survives a dead server); `filesystem` and `git` (duplicate
> native file and git access); `gitnexus` (native grep and semantic search cover
> single-repo work); `playwright` and `context-mode` (E2E runs through the project's own
> committed test suite, and native web fetch covers the rest).

### The compressor pattern
Each server exposes exactly two tools: `get_tool_schema(name)` and `invoke_tool(name, args)`.
**Always call `get_tool_schema` before the first `invoke_tool` for a given operation** —
parameter names change between backend versions. Do not invent tool names from memory.

Never connect directly to a backend URL, even when debugging. The compressor keeps the tool
surface stable across backend upgrades and lets a backend be swapped without touching client config.

### Reach for the cheapest tool that answers the question
`grep_search` / `file_search` → `semantic_search` → MCP.
If you know the exact symbol name, `grep_search` beats semantic search every time.
Escalate only when the cheaper tool actually came back empty — not preemptively.

---

## Definition of Done

Not done until **all** of these are true:

- [ ] Zero errors in diagnostics; build exits 0
- [ ] Lint and typecheck pass with no new suppressions
- [ ] A test exists that fails if this change is reverted
- [ ] Full suite green — no regressions, on every node of the current band, test counts read
      from the run (not inferred from the exit code)
- [ ] **Proven on the dev server** for any gameplay/server/data/mixin/config/networking change;
      the run (command, exit code, matched `@expect` lines) recorded in `TODO.md`
- [ ] **If it ships in a release: the game was launched** — client world check and built-jar
      boot, exit 0 on every band and loader being released, recorded in `TODO.md`
- [ ] README's band statement matches what the build ships
- [ ] Every error path logs something useful
- [ ] **Every destructive path is guarded** — dry-run default, confirmation showing counts,
      refusal on an empty filter, logging with the recovery path
- [ ] **A test proves each guard blocks** — bad input in, nothing destroyed
- [ ] **Every destructive step taken during this work had a verified rollback**, and it's
      written down in `TODO.md` rather than remembered
- [ ] `TODO.md` reflects reality
- [ ] `.agent/memory/` updated if anything non-obvious was decided or discovered
- [ ] Diff self-reviewed — no debug code, no commented-out blocks, no secrets, Stonecutter
      active project reset

"It works on my machine", "I'll add tests later", and "the delete path is obviously fine"
are not entries on this list.
