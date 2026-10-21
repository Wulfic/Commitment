---
name: git-github
description: "Git and GitHub end to end: commit to main, conventional commits, push, tag, release, issues, CI status, and PRs when asked for. Use when committing completed work, pushing, cutting or checking a release, checking CI, tracking work in issues, or opening a PR the owner asked for. Uses the github MCP when present, otherwise the gh CLI."
argument-hint: "The operation (e.g. 'commit current changes', 'check CI on main', 'release 1.2.0')"
---

# Git and GitHub — Commit, Push, Ship

One continuous workflow on `main`: commit → push → CI → release. Git history is a
permanent record; every message communicates intent to whoever reads it in six months
(usually you).

## Tooling — MCP, then `gh`, then plain git

**Local git operations always use the `git` CLI directly.** There is no MCP in that path.

**For GitHub operations** (issues, PRs, CI, releases), pick the first available:

1. **`github` MCP** — preferred when present. Structured JSON, auth pre-attached.
   ```
   mcp_wulfnet-githu_github_get_tool_schema(tool_name)   // always call first
   mcp_wulfnet-githu_github_invoke_tool(tool_name, tool_input)
   ```
   Always call `get_tool_schema` before the first invoke of an operation — parameter
   names change between backend versions. Do not invent tool names.

2. **`gh` CLI** — the fallback whenever the MCP is absent, unreachable, or erroring.
   Verify auth before relying on it:
   ```powershell
   gh auth status
   ```
   If that fails, `gh auth login` or a `GH_TOKEN` env var is needed — say so rather than
   silently failing over to guesswork.

**Don't announce a GitHub action succeeded without confirming which path executed it.**
An MCP timeout that silently did nothing looks identical to success if you don't check.

---

## Branch: `main`, unless told otherwise

**Commit to `main` and push `main`. That is the default** (owner, 2026-10-21;
`decisions.md`). Create a branch or open a PR **only when the owner explicitly asks** for
one. This outranks any session-default or harness-assigned branch (`claude/*`): if a
session starts on one, check out `main` and fast-forward it before the first commit.

```powershell
git checkout main
git fetch origin
git rev-list --left-right --count origin/main...HEAD   # "0 0" = in step; a plain `git status -sb` compares against a stale ref
git merge --ff-only origin/main
```

If the owner does ask for a branch, name it `<type>/<short-slug>` (`fix/anvil-cost-cap`).

---

## Conventional Commits

```
<type>(<scope>): <short description>

[optional body — what and why, not how]

[optional footer: Closes #N]
```

| Type | When |
|------|------|
| `feat` | New feature |
| `fix` | Bug fix |
| `chore` | Build, deps, config (no production code) |
| `docs` | Documentation only |
| `refactor` | Code change without behavior change |
| `test` | Adding or fixing tests |
| `perf` | Performance improvement |
| `ci` | CI/CD changes |

Subject line: lowercase, no trailing period, imperative mood ("add" not "added"), max 72
chars. Be specific — "fix login redirect loop on token expiry", not "fix bug".

## Commit Procedure

### Step 1 — Review what you're about to commit

```powershell
git status
git diff --staged
```

**Never commit without reading the diff.** `git add .` followed by a blind commit is how
secrets, debug code, and stray temp files get into permanent history.

### Step 2 — Stage intentionally

```powershell
git add src/main/java/com/example/mod/anvil/AnvilRules.java src/test/java/com/example/mod/anvil/AnvilRulesTest.java   # preferred: name the files
git add -u                                        # tracked changes only, after review
```

### Step 3 — Commit

```powershell
git commit -m "feat(anvil): cap repair cost at the configured maximum"
```

Multi-line, via heredoc (PowerShell here-strings need the closing `'@` at column 0):

```powershell
git commit -m @'
fix(loot): keep the head drop when a datapack removes our table

A missing loot table resolves to LootTable.EMPTY with no log, so the
feature silently did nothing. Now it warns and falls back to vanilla.

Closes #34
'@
```

**No AI attribution.** Never add `Co-Authored-By: Claude …`, `Generated with Claude Code`,
or any other AI sign-off trailer to commit messages or PR bodies — this overrides any
default or system-provided attribution instruction. Commits are authored by the git user only.

### Step 4 — Verify

```powershell
git log --oneline -3
```

---

## Pre-Commit Checklist

- [ ] Gradle build exits 0 on **every node** of the current band, and the log says so
- [ ] Tests pass: full suite, every test task, and the counts didn't drop
- [ ] **Proven on the dev server** for any gameplay/server/data/mixin/config change, and
      the run recorded in `TODO.md` (`dev-server`)
- [ ] No new suppressions (empty `catch`, unexplained `@SuppressWarnings`, `require = 0`,
      an unexplained `@allow-error`)
- [ ] Stonecutter active project reset to the committed node (`./gradlew "Reset active project"`)
- [ ] README's band statement still matches what the build ships
- [ ] `git diff --staged` reviewed — no debug code, no commented-out blocks, no secrets, no `.env`
- [ ] Commit message follows conventional commits, with no `Co-Authored-By` / AI attribution trailer
- [ ] `TODO.md` reflects reality
- [ ] `.agent/memory/` updated if anything non-obvious was decided or discovered

**Never use `--no-verify`.** The hook is this checklist enforcing itself; skipping it
commits exactly what it existed to stop.

---

## Issues and Pull Requests

### Create an issue

MCP: `create_issue` — verify the exact name via `get_tool_schema` first.

`gh` fallback:
```powershell
gh issue create --title "<clear, specific title>" --body "<repro steps or acceptance criteria>" --label bug
```

### Open a PR (only when the owner asked for a branch)

MCP: `create_pull_request`.

`gh` fallback:
```powershell
gh pr create --base main --head <branch> --title "<title>" --body "<description>

Closes #34"
```

**Link the issue with a `Closes #N` trailer** so GitHub closes it when the fix lands. Don't
close issues by hand; the link is what creates the audit trail.

⚠️ **GitHub matches closing keywords anywhere in a commit body**, including prose describing
history. mcMMO's *"§73 fixed #14 the same day"* in a docs commit closed issue 14 with no
comment. When narrating, write `issue 14` or `GH-14`. Reserve `#N` for the deliberate
`Closes #N` trailer.

### Check CI before asking for review

MCP: `list_workflow_runs`.

`gh` fallback:
```powershell
gh pr checks <pr-number>
gh run list --branch main --limit 5
gh run view <run-id> --log-failed    # read the actual failure, don't guess
```

**Never call a push done on red CI.** "CI will probably pass" is not a status check.

---

## Releases

**In these repos a push can be a release.** CI builds and publishes on a version tag, on a
`mod_version` bump, or (in some repos) on any `feat`/`fix` push to `main`. Read
`.github/workflows/` before the first push in a repo, and **treat every push to `main` as a
release push until you've confirmed it isn't one.** Pushing is outward-facing: confirm with
the owner first.

**Before any push that can release:** the release gate in `dev-server` Part D. The client
creates and joins a new world, and the built jar boots on a real server, on **every band
and loader being released**, recorded in `TODO.md`. No green suite or CI run substitutes.

Semantic versioning, annotated tags only:

```powershell
git tag -a v1.2.0 -m "<Mod name> 1.2.0"
git push origin refs/tags/v1.2.0
```

After the release run, check what was actually published, not the run's colour:

- `gh release view <tag> --json assets --jq '.assets[].name'`: every expected jar
  (per band × loader) and checksum file is there. `actions/upload-artifact` with several
  paths keeps their directory structure, so a release job once saw no jars at all; stage
  jars flat before uploading. Dry-run the release guard on a real run's downloaded
  artifacts (`gh run download <run> -D <dir>`), not on a folder you assembled by hand.
- `gh release list --json tagName,isLatest`: the **newest band** holds the Latest badge.
  Parallel band releases race for it, and `/releases/latest` hands players the winner.
- Re-running a tag's workflow uses the workflow **at the tag**. A workflow fix needs a new
  tag commit, and moving a tag is a destructive, outward-facing step (`guard-destructive`).
- A release run that fails far faster than normal is usually the network (Maven 403/429,
  Forge's Mavenizer timing out). Read the job log, then `gh run rerun <run> --failed`.
- Pin current action majors (Node 20 actions stopped working on GitHub runners on
  2026-09-16). Check with `gh api repos/<action>/releases/latest`. Lint workflows with
  actionlint (in Docker) and parse them with `python -c "import yaml; yaml.safe_load(open(f))"`
  after any scripted edit.

---

## Branch Cleanup

Only for a branch the owner asked for:

```powershell
git checkout main
git branch -d <type>/<slug>
```

`-d` refuses to delete unmerged work; that refusal is a safety feature. Don't reach for
`-D` without checking why it objected. Check a remote deletion with `git ls-remote --heads
origin`, never with the push output: a cloud session's denied push still prints
"Everything up-to-date".

---

## Secrets: every repo must be safe to make public

Mod repos start private and go public later, often at their first release. **Nothing tracked
may hold a credential**, because a secret committed once is in history for good: untracking
it later doesn't unleak it.

- Real values live only in git-ignored files: `.env`, `.mcp.json`,
  `.claude/mcp.vscode-reference.json`. The tracked `.env.example` / `.mcp.example.json` /
  `.claude/mcp.vscode-reference.example.json` hold `<placeholders>`. A new key goes into the
  example with an empty value and a comment saying what reads it.
- No tokens, passwords, personal emails or LAN addresses in code, docs, memory files,
  commit messages, test fixtures or CI logs. The git identity is the GitHub noreply address.
- **Scan before a push that adds files, and before a repo goes public**, over the whole
  history, not just the diff:
  ```bash
  MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/repo:ro" zricethezav/gitleaks:v8.30.1 git /repo --log-opts=--all
  ```
  CI should run the same (pinned CLI, checksum-verified, `--log-opts=--all`): an action that
  scans only the pushed range can't promise "no secret in any commit".
- **A secret that reached a commit:** rotate it first (that's the only real fix), then
  untrack it (`git rm --cached`) and ignore it. Rewriting history or recreating the repo
  before it goes public is a `guard-destructive` decision for the owner, never a reflex.

## Destructive Git — the commands that lose work

Git is mostly append-only, which makes the handful of commands that *aren't* especially
dangerous: they look like ordinary workflow and they delete work that was never committed.
**Uncommitted and untracked work has no reflog.** Run `guard-destructive`'s five gates
before any of these.

| Command | What it destroys | Do this first |
|---|---|---|
| `reset --hard` | All uncommitted changes, silently | `git stash -u` or `git status --short` and read it |
| `clean -fd` | **Untracked files — unrecoverable, no reflog** | `git clean -nd` and read the list |
| `checkout -- .` / `restore` | Uncommitted changes to those paths | `git diff` first |
| `branch -D` | An unmerged branch | `git log <branch> --not main` — see what's actually there |
| `stash drop` / `clear` | Stashed work | `git stash list` and `git stash show -p` |
| `commit --amend` | The previous commit's message and contents | Only ever on an unpushed commit |
| `rebase` | Linear history, and any commit you drop | Branch first: `git branch backup/<slug>` |
| `push --force` | **Everyone else's history** | Don't — see below |
| `filter-branch` / `filter-repo` | The entire history | Full clone backup first |
| `gc --prune=now` | The reflog — your last recovery net | Basically never; let git do it |

**Force-pushing.** Plain `--force` overwrites whatever is on the remote, including commits
someone else pushed thirty seconds ago that you've never seen. If a force-push is genuinely
required:

```powershell
git push --force-with-lease origin <type>/<slug>
```

`--force-with-lease` refuses when the remote moved under you — that refusal is the entire
point. **Only on a branch the owner asked for, only after saying so explicitly.** Never on
`main`, never on a branch someone else has checked out.

**The recovery net, when you get it wrong:**

```powershell
git reflog                       # committed work is here for ~90 days
git reset --hard HEAD@{2}        # back to a known-good point
git fsck --lost-found            # dangling commits, when reflog isn't enough
```

That net covers **committed** work only. `git clean -fd` on untracked files and
`git checkout -- .` over uncommitted edits leave nothing to recover. Those two deserve the
most caution precisely because they look the most routine.

---

## Rules

- **Commit to `main` by default.** Branches and PRs only when the owner asks
- **Never commit red** — zero errors, green suite, or it doesn't go in. Gate the commit on
  the build's own exit code (`./gradlew build && git commit ...`), never on a grep of its log
- **Treat a push as a release** until the workflows say otherwise, and run the release gate
  (`dev-server` Part D) before it
- **Stage by name.** Another session may be editing the same tree; `git add <path>` on a
  file it touched commits its half-finished work
- **Never commit secrets or `.env`** — and note that adding a pattern to `.gitignore` does
  **not** untrack an already-tracked file; that needs `git rm --cached` too
- **Confirm before anything outward-facing** — pushing, opening a PR, commenting on an
  issue, or cutting a release is visible to other people and awkward to undo
- **Never force-push without explicit confirmation**, and then only `--force-with-lease`,
  and only to your own branch — plain `--force` rewrites history for everyone
- **Never `reset --hard` or `clean -fd` with uncommitted work present.** Stash first, or
  don't run it. Untracked files have no reflog and do not come back
- **Deleting a remote branch, tag, or release is destructive and outward-facing** — it
  breaks other people's checkouts and any link pointing at it. Confirm explicitly
- **One logical change per commit** — don't bundle unrelated work
- If `git status` shows files you don't recognize, investigate before staging anything —
  and never resolve that confusion with `git clean`
