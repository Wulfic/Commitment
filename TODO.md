# TODO

The working plan. `plan-work` writes here **before** any code is written — a plan that
lives only in the chat window did not happen. Keep this current as you execute; a stale
TODO is worse than an empty one, because the next session trusts it.

> Long-lived context does **not** belong here. Decisions and gotchas go to
> `.agent/memory/`; this file is the active work list.

---

## Task template

```markdown
## <Task name>

**Goal:** <one sentence — what "done" means>
**Approach:** <chosen approach, and the one-line reason it beat the alternative>
**Not doing:** <explicit out-of-scope list — this is what stops scope creep>
**Blast radius:** <what this destroys or overwrites, and where it comes back from —
                  or "none: additive only">
**Proof:** <the rungs this reaches (JUnit / GameTest / dev server / live world / client /
           built jar) and the exact check for each, e.g. `scripts/checks/anvil-cap.txt`>

- [ ] 1. <concrete step — names a real file>
- [ ] 2. <concrete step>
- [ ] 3. <the test that proves it works>

**Rollback:** <the exact command that undoes this — not "revert the change">
```

Rules for steps: each names a real file or command, each is independently verifiable, and
tests are steps rather than an afterthought. More than ~8 steps means it's a Tier 2 task —
split it into stages with commits between them.

`Blast radius:`, `Proof:` and `Rollback:` are **mandatory** — "none, additive only" is a
valid blast radius, a blank field is not. `Proof:` names a dev-server check for any change
that runs in the game (see the `dev-server` skill). Destructive steps are marked as such and gated through
`guard-destructive` when you reach them; irreversible steps go last.

---

## Active

## Turn the generic template into a Minecraft-mod template

**Goal:** A fresh mod repo spawned from this template hands the agent the lessons the
owner's mod repos paid for (mcMMO-Singleplayer, enchanting-2, the-continent, real-villages,
diagonal-fences), above all that **every change is proven on the live dev server**, with
no generic Node/Python/Go noise left in the agent files.

**Approach:** Knowledge goes into **skills** (loaded on demand) and the **rules** into
`AGENTS.md` (always loaded). Two new skills, `dev-server` and `mc-port`; the existing
eleven become Gradle/Java/Minecraft-first. Beat the alternative of seeding
`.agent/memory/gotchas.md` with ~80 cross-project gotchas: memory is this project's own
record, and a seeded file would bury the new project's entries and read as facts measured
here.

**Not doing:** No Gradle scaffold, no mod source, no harness scripts. Loader and Minecraft
version aren't chosen yet, and a script that has never run is decoration (the skill states
the contract instead and points at the reference implementations). Not copying mcMMO's
band-branch machinery (drift audits, identity guards): the owner ruled fixes go to the
current band only. Not rewriting `8108c25` or rotating credentials (owner's call; see
`state.md`). No CI workflow (no build to run yet).
**Blast radius:** edits to files tracked at `8108c25`. `TODO.md`'s finished
destructive-doctrine item is removed (it lives in `8108c25`). **Destructive, owner-requested
2026-10-21:** `git rm --cached .env .mcp.json .claude/mcp.vscode-reference.json`. The files
stay on disk and in `8108c25`. Dry-run (`git rm --cached -n`) listed exactly those three.
**Proof:** docs only. `diff -r` of the skill trees empty, `settings.json` parses,
`git check-ignore` on the secret files and the wrapper jar, a gitleaks scan of the files
that would be committed comes back clean.

- [x] 1. `TODO.md` plan (this entry); `decisions.md` entry before implementing
- [x] 2. `AGENTS.md`: non-negotiables, `Minecraft Mod Development` section (verification
      ladder, live dev-server rule, release gate, facts-from-the-jar, machine reality),
      workflow, skills table, Definition of Done
- [x] 3. `.claude/skills/dev-server/SKILL.md` (new): dev-server contract, live-world runs,
      built-jar boot, client world check, owner's instance
- [x] 4. `.claude/skills/mc-port/SKILL.md` (new): versions, loaders, mappings, pins
- [x] 5. Rewrite `build-run`, `test-iterate`, `debug-errors`, `code-explore`,
      `research-docs` for Gradle/Java/MC; MC additions to `code-review`, `recall-session`,
      `git-github` (main-only, a push can release), `plan-work`, `guard-destructive`
- [x] 6. `.github/copilot-instructions.md` condensed MC rules; `.gitignore` for a
      Gradle/Loom repo (wrapper jar kept, `run/` ignored); `settings.json` denies killing
      java by name
- [x] 7. Mirror to `.github/skills/`; verify `diff -r` empty, `settings.json` parses,
      every skill has `name`/`description` frontmatter, no stale Node/npm references
- [x] 8. `state.md` rewritten
- [x] 9. Owner, mid-task: `git-github` commits to `main` by default; fixes and back-ports go
      to the current band only, and the README states it (`mc-port`, `AGENTS.md`, decisions)
- [x] 10. Owner, mid-task: secrets out of the tracked tree. Untracked the three files above,
      `.gitignore` covers them, tracked `*.example*` placeholders added, LAN IPs masked in
      `gotchas.md`, secrets section in `git-github`

**Rollback:** `git checkout 8108c25 -- .` restores every tracked file; `git add -f .env
.mcp.json .claude/mcp.vscode-reference.json` re-tracks the secret files. Then delete the new
skill directories (`.claude/skills/{dev-server,mc-port}`, `.github/skills/{dev-server,mc-port}`)
and the three `*.example*` files. Nothing outside the repo is touched.

## Done

_Move completed tasks here, or delete them once they're in the git history._
