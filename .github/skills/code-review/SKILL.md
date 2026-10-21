---
name: code-review
description: "Thorough, skeptical code review. Use when: reviewing a PR, auditing a file before merge, checking for security or OWASP issues, hunting missing error handling or logging, or self-reviewing your own diff before committing."
argument-hint: "File path(s) or feature area to review"
---

# Code Review — Assume It's Broken

Review like someone who has seen every mistake and has no incentive to be polite. Every
real issue gets called out with a file and line. **Don't pad the report with praise —
silence means "no objection", not "good work".**

This applies to **your own diff** too, and that's the harder case: you already believe it
works, which is exactly the state in which people miss things. Read it as if rejecting it.

## When to Use

- Before committing anything non-trivial (self-review)
- Before opening or approving a PR
- Security or OWASP pass
- "Does this actually do what it claims?"

## Procedure

### Step 1 — Read it properly

```
read_file(...)        // the full file, not the diff hunks alone
grep_search(...)      // find every caller of what changed
```

A diff without surrounding context hides the bugs that matter. Specifically:

- **Trace the data end to end** — where does input enter, what validates it, where does it land?
- **Read every caller of a changed signature.** A compatible-looking change that breaks
  one of six call sites is the classic miss.
- **Note what is absent.** Missing error handling, missing logging, missing tests, and
  missing validation don't appear in a diff — you have to actively look for the gap.

### Step 2 — Work the checklist

Don't freestyle. Freestyle review finds whatever you happened to think of that day.

**Correctness**
- [ ] Off-by-one, boundaries, empty collections, null/undefined
- [ ] Right thread: world and entity access on the server thread (or `server.execute`),
      render state on the render thread; every `CompletableFuture` result handled
- [ ] Errors handled at the right level — not swallowed, not blindly rethrown
- [ ] Early returns leave state consistent
- [ ] Concurrency: shared mutable state, race conditions, non-atomic read-modify-write

**Minecraft**
- [ ] **Side safety:** no client-only class (`Minecraft`, screens, renderers, `KeyMapping`)
      reachable from common/server code; client code lives in the client source set
- [ ] **Per-server state, not statics.** A static survives into the next singleplayer world
      and leaks between integrated-server sessions. Per-connection client state is cleared on
      a new connection (configuration `INIT`), not only on disconnect
- [ ] **Server is authoritative.** Every C2S payload validated on the server (the player
      can reach it, owns it, the values are in range); nothing trusts a client-computed price,
      position or result
- [ ] **Networking versioned:** payload ids or a handshake carry a protocol number, and a
      mismatched or vanilla client gets a clean disconnect with a message, not a crash
- [ ] **Mixins:** each target picked by `javap` on every band's jar (anonymous `$N` and
      `lambda$x$N` included); `require`/`defaultRequire` left at 1 so a missed target fails
      loudly; injection point still called in each version's method body; no `@Overwrite`
      where an inject works; handler names prefixed with the mod id
- [ ] **Other mods:** does a common mod cancel or replace the method this hooks? Use events
      or the narrowest injection; never assume an item sits in a vanilla slot
- [ ] **Data files** in each band's format (read one vanilla file from that jar); every tag,
      loot table, recipe and advancement loaded by a GameTest or a dev-server boot, since a
      bad one is a hard load failure, or worse, silently ignored keys
- [ ] **Add/remove safety:** loading a world after the mod is removed keeps the world
      loadable; registry entries the mod adds degrade, don't corrupt
- [ ] **Per-tick cost:** nothing allocating, reflecting or scanning per tick or per block;
      caches keyed on value-equal keys
- [ ] **No comment states which Minecraft version this build targets.** It's false on the
      next band. State the code fact
- [ ] **Bands:** a fix touches the current band only; frozen bands untouched unless the
      owner asked; README band statement still true
- [ ] **Proven live:** the change ran on the dev server and `TODO.md` records the run.
      Gameplay code with only GameTests behind it → HIGH

**Security (OWASP)**
- [ ] Injection — SQL/command/LDAP; parameterised queries, never string concatenation
- [ ] AuthN/AuthZ enforced **server-side**, on every path, not just the happy one
- [ ] No secrets, tokens, or keys in code, logs, or error messages
- [ ] Input validated at the trust boundary, not deep inside
- [ ] No PII in logs; no sensitive data in URLs
- [ ] Dependencies — anything new, unmaintained, or on a known-bad version?
- [ ] Output encoded for its sink (HTML, SQL, shell, JSON)

**Destruction & data safety** — the findings here are the expensive ones
- [ ] Every delete/overwrite/reset/migrate path found — `grep` for `delete`, `drop`,
      `truncate`, `rm`, `unlink`, `rmdir`, `overwrite`, `reset`, `purge`, `prune`
- [ ] **No unbounded filter.** A delete or update that runs with an empty/null filter and
      treats it as "match everything" is CRITICAL, every time
- [ ] Destructive queries parameterised — never a filter built by string concatenation
- [ ] Path operations resolve, then assert the target is inside the intended root; `..`,
      symlinks, and absolute paths from input are rejected
- [ ] Confirmation exists and states **what and how many**; dry-run is the default and
      `--force` the opt-in; non-interactive contexts require an explicit flag
- [ ] Reversible — soft delete where the record is referenced; every migration `up` has a
      `down` that has actually been run
- [ ] Backup happens **and is verified** before the destructive step, not after
- [ ] Fails closed — an indeterminate safety check refuses rather than proceeding
- [ ] Blast radius capped: an operation affecting more than N rows/files stops for an override
- [ ] Logs before and after, with the affected count and the recovery path
- [ ] **A test feeds the bad input and asserts nothing was destroyed** — not just that the
      happy path deletes correctly

**Operability**
- [ ] Every error path logs something actionable, with context — not `log.error(e)` alone
- [ ] Log levels correct; no logging of secrets or full request bodies
- [ ] Failures are diagnosable from logs alone, without a debugger

**Tests**
- [ ] Tests exist and would actually fail if the change were reverted
- [ ] Edge cases covered, not just the happy path
- [ ] No test asserting current-but-wrong behaviour

**Structure**
- [ ] Follows patterns already in this codebase (verify with `code-explore`)
- [ ] Names say what things are; no `data2`, `tmp`, `handleThing2`
- [ ] Not duplicating something that already exists
- [ ] Not over-engineered for requirements that don't exist yet

### Step 3 — Check it against recorded decisions

```
grep_search(query: "<area>", includePattern: ".agent/memory/*.md")
```

Code contradicting a recorded decision is a finding — either the code is wrong, or the
decision is stale and needs a new entry. Don't let them silently diverge; that's how a
decisions file becomes fiction.

### Step 4 — Report by severity

Every finding needs **file, line, and what actually goes wrong.** "This could be better"
is not a finding — it's noise that dilutes the real ones.

**CRITICAL — blocks merge**
Security holes, data loss or corruption, auth bypass, secrets in code, crashes on a normal
path. **Any unguarded destructive path lands here** — an empty filter treated as "match
everything", a delete with no confirmation, a migration with no `down`, a path delete that
doesn't check its root. These do not get downgraded because the happy path works.

**HIGH — fix in this change**
Missing validation at a boundary, no logging on an error path, a logic bug at an edge
case, missing tests on critical paths, an unhandled failure mode.

**MEDIUM — ticket it**
Confusing structure, poor naming, unnecessary complexity, missing docs on non-obvious logic.

**LOW — optional**
Style, minor refactors.

State the failure concretely: *"`parseConfig` at [config.ts:47](config.ts#L47) throws on an
empty file; `loadAll` doesn't catch it, so a missing config crashes startup with no log."*
That's actionable. "Error handling could be improved" is not.

### Step 5 — Record anything structural

If the review reveals a pattern worth enforcing, or a trap likely to recur, append it via
`save-memory`. One-off nits don't belong in memory; recurring classes of mistake do.

## Rules

- **Every finding cites file and line.**
- **"Looks fine" is not a review** unless you actually read the code.
- **No tests → automatic HIGH.**
- **No logging on an error path → automatic HIGH.**
- **An unguarded destructive path → automatic CRITICAL.** See `guard-destructive`.
- **A guard with no test proving it blocks → automatic HIGH.** Untested guards get deleted
  by the next person as dead code, and nothing fails.
- **A suppression** (empty `catch`, `@SuppressWarnings`, a mixin with `require = 0`, an
  unexplained `@allow-error`) **→ automatic HIGH** unless there's a written justification
  next to it.
- **A gameplay, data, mixin or networking change never run on the dev server → automatic
  HIGH.** A release push without the release gate recorded → **CRITICAL**.
- **A test that can't fail** (unlisted GameTest class, undeclared Gradle input, an assertion
  that passes with the change reverted) **→ HIGH**, the same as no test.
- Don't invent problems to look thorough. A short review of clean code is a fine outcome —
  say what you checked, so the scope of that "clean" is clear.
