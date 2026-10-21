---
name: debug-errors
description: "Systematic error triage with a hard attempt budget. Use when: build fails, type errors appear, runtime exceptions occur, diagnostics return problems, or something that worked stopped working. Drives to zero errors, or to a documented blocker — never to a suppression."
argument-hint: "The error message or a description of the failure"
---

# Debug Errors — Triage to Zero, or to a Documented Stop

Fix root causes, not symptoms. Never apply a fix you can't explain. **This loop has a
budget of 3** — see below. Grinding past it produces worse code than stopping does.

## When to Use

- Diagnostics return errors (`get_errors`)
- A build or typecheck exits non-zero
- A runtime exception appears in logs
- "It was working and now it's not"

> **Assertion failures belong to `test-iterate`, not here.** This skill is for errors;
> that one is for a test that ran correctly and reported the wrong answer.

---

## The Budget

| Attempt | What you do |
|---|---|
| 1 | Diagnose, form a hypothesis, apply the minimal fix. |
| 2 | **Re-diagnose from scratch.** Attempt 1 failing means the diagnosis was wrong, not the fix. Do not patch the patch. |
| 3 | Last attempt. Narrow it — add logging, isolate in a scratch file, bisect the change. |
| 4 | **STOP.** Write to `.agent/memory/gotchas.md` and report to the user. |

**Stopping at 3 is a correct, professional outcome.** A clear report — "here is the error,
here are three hypotheses I tested, here is what each ruled out, here is where I'd look
next" — is far more useful than a fourth guess that mutates unrelated code.

**What is never acceptable** is widening the fix so the symptom disappears: an empty
`catch`, a `@SuppressWarnings` with no written reason, a mixin made `require = 0` or run
with `-Dmixin.ignoreRequired=true`, an `@allow-error` in a dev-server check for an error
nobody has explained, or deleting the code that surfaced the error. That converts a
visible bug into an invisible one, and a mixin that silently stopped applying is a feature
that silently stopped working.

---

## Procedure

### Step 1 — Collect every error before fixing any

```
get_errors()                    // whole workspace
get_errors(filePaths: [...])    // or scoped to changed + related files
```

Do not start fixing from a partial list. Errors cascade — the 12th message is often the
only real one, and fixing #1 in isolation can be fixing a symptom of #12.

**Find the first error, not the last.** Compilers report cascades; the first failure is
usually the true one.

### Step 2 — Read the actual output

For runtime errors, get the full log, not the console tail:

| Run | Log |
|---|---|
| Dev server / client | `run/server/logs/latest.log`, `run/client/logs/latest.log` (per node under Stonecutter); `debug.log` beside it holds DEBUG lines |
| GameTests | the node's `build/run/gameTest/logs/` and the Gradle log you redirected |
| Crash | `crash-reports/crash-*.txt` in the run dir; `hs_err_pid*.log` for a JVM crash |
| Owner's game | the instance's `logs/latest.log`. Read its `mods/` list too |

Read the **whole** trace, not the last line. The root cause is usually several frames up,
in the caller. For Minecraft, also read **the WARN lines before the first ERROR**: a mod's
data that failed to load is often only WARN ("Missing metadata in pack", "Failed to parse
level-type ... defaulting"), and the ERROR is the consequence. If the trace is truncated or
unhelpful, add logging (step 5) rather than guess.

### Step 3 — Read the code in context

```
read_file(filePath, startLine, endLine)
```

At least 20 lines either side. The reported line is where the problem *surfaced*, not
where it *originated* — that's usually the caller or the type definition.

For an error in or against Minecraft code, read **that version's** class, not your memory
of it: `javap -p -c` on the Loom-cached jar, or the decompiled sources from `genSources`
(see `mc-port`). A method you "know" may have moved, changed its signature, or stopped
calling the thing your mixin wraps.

### Step 4 — State the hypothesis before editing

Write it out explicitly — one sentence each:

- **Symptom:** the exact error text
- **Hypothesis:** why it happens, mechanically
- **Prediction:** what changes if the hypothesis is right
- **Minimal fix:** the smallest edit that tests it

**A fix without a stated hypothesis is a guess**, and guesses create new errors while
hiding old ones. If you can't fill in "Hypothesis", you haven't read enough yet — go back
to step 3.

### Step 5 — When you can't form a hypothesis, get data

Don't guess harder. Make the failure observable:

- Add logging around the failure point — inputs, state, branch taken
- Re-run and read the real values
- Isolate the failing piece in a scratch file
- Bisect: what was the last state where this worked?

Adding logging is progress, not a detour. It also usually stays in as the error-path
logging the code should have had.

### Step 6 — Apply the minimal fix, one at a time

One change. Then re-check:

```
get_errors()
```

**Never stack multiple fixes before re-checking.** If you change three things and the
error count drops, you've learned nothing about which one mattered — and you may have
added two new problems masked by the fix to the first.

If the fix requires a third-party API you're not certain of, use `research-docs` before
writing it. Inventing an API signature from memory is how attempt 2 gets burned.

### Step 7 — Verify to zero

Repeat 3–6 until `get_errors()` is clean, then run the real build via `build-run`.
Zero diagnostics **and** a clean Gradle build. The IDE's Java diagnostics go stale after
branch or node switches. When they disagree with a forced Gradle compile, Gradle is right.
A runtime error found on the dev server is fixed when the same dev-server run is clean,
not when the suite is.

### Step 8 — Record it

If the bug took more than 2 attempts, or the cause was non-obvious, append to
`.agent/memory/gotchas.md` via `save-memory`. **If you exhausted the budget, this is
mandatory** — that is exactly the case the file exists for.

Include the dead ends. "We tried X, it doesn't work because Y" is what stops the next
session from burning an hour on X.

## Rules

- **Budget of 3.** Then stop and report. No exceptions.
- **Two failures means the diagnosis is wrong**, not the fix. Re-diagnose, don't re-patch.
- **No suppressions.** Ever, without an explicit written justification the user has agreed to.
- **Read before you edit.** Always.
- **One fix per verification cycle.**
- **Never delete code to make an error go away** unless deleting it is genuinely the fix.

## Error Pattern Reference

| Pattern | Likely cause |
|---|---|
| `cannot find symbol` against a Minecraft class | Renamed between versions or mappings (`ResourceLocation` → `Identifier` in 1.21.11 official names). `javap` the jar |
| Mixin `Scanned 0 target(s)` / `Critical injection failure` | The target method no longer calls what `@At` names, or the descriptor string holds an old class name. Diff the method body per version |
| Mixin applied, feature does nothing | Wrong anonymous class / lambda number (`$3` vs `$4`), or another mod cancels the method at HEAD. `javap -c -p` the `$N` classes |
| `invalid signature ... expected <Owner>$4` | `@WrapOperation`/`@Redirect` can't name an anonymous receiver; use `@ModifyArg` |
| `NoSuchFieldError` / `NoSuchMethodError` at runtime | Compiled against another version than the one running; or a dependency mismatch. Link-check against the runtime version |
| `ExceptionInInitializerError: Not bootstrapped` | A vanilla class touched before `Bootstrap.bootStrap()`; poisons every later class in that JVM |
| `Registry is already frozen` | Registering after construction (NeoForge: use `RegisterEvent`/`DeferredHolder`) |
| `Trying to access unbound value` at registry load | A codec evaluated another registry entry while the registry was still loading. Defer to first use |
| `Failed to load datapacks, can't proceed` / world won't load | A bad tag, loot table, advancement or worldgen file. Read the ERROR above it; a missing tag is a hard failure |
| `No key <x> in MapLike` | Data file in another version's format |
| World loads **vanilla** with no ERROR | Forge without `pack.mcmeta`; level-type failed to parse (WARN). Fingerprint the world |
| `FAILED TO BIND TO PORT` | Another server on the port. Environment, not the mod (exit 2) |
| `NoClassDefFoundError` from a library only in dev | A Loom-remapped dependency's nested jars aren't loaded in dev; add them to `localRuntime` |
| `paging file is too small` / `The pipe is being closed` | Out of memory with several JVMs up; not a test failure |
| "Network synchronizer in invalid state" (client GameTest, 1.21.11) | Fabric 4.3.5 race; disable the synchronizer for that node |
| Works for tests, broken in the owner's game | Another mod in their instance. Read `mods/` and `latest.log` first |
| Works locally, fails in CI | Line endings (CRLF in Windows resources), case-sensitive paths, OS path semantics, a slow GPU-less runner |
| Intermittent failure | Shared GameTest batch state, wall-clock timing in a test, multithreaded worldgen order, XP orb merging |
