---
name: build-run
description: "Run Gradle builds, tests, GameTests, dev servers and scripts, and interpret their output. Use when: building the mod, running JUnit or GameTests, starting/stopping runServer or runClient, running a long background task, interpreting non-zero exit codes, or orchestrating multi-step command sequences. Manages process lifecycle and output parsing."
argument-hint: "What to run (e.g. 'build every node', 'run the server GameTests', 'start the dev server')"
---

# Build and Run: Gradle, the Game, and Reading the Result

Running a command without reading its output is worthless. And in this stack, **the exit
code and the log disagree often enough that the exit code alone is not a result.** Every
item in the traps table below shipped a false green in one of the owner's mod repos.

## When to Use

- Building the mod (`./gradlew build`, one node or all)
- Running JUnit, server GameTests, client GameTests
- Starting the dev server or client (hand off to `dev-server` for what to prove there)
- Any long background run (builds, GameTests, smokes, live-world runs)
- Interpreting why a CI step failed

## Standard commands

```bash
./gradlew build                          # compile + JUnit + server GameTests (as wired); all nodes under Stonecutter
./gradlew :<node>:build                  # one Stonecutter node
./gradlew test                           # JUnit only
./gradlew runGameTest                    # server GameTests (Fabric: runGametest / runGameTest; NeoForge: runGameTestServer)
./gradlew :<node>:runClientGameTest      # client GameTests: opens a game window, ~3 min, one node at a time
./gradlew :<node>:runServer              # the dev server: always qualified, see dev-server
./gradlew dependencies --configuration compileClasspath   # what is actually on the classpath
./gradlew genSources                     # decompile Minecraft for reading (~8 min)
```

Prefer the repo's own wrapper scripts (`scripts/verify-all.sh`, `scripts/dev-server-smoke.sh`)
over hand-typed Gradle when they exist: they carry the verdict logic.

**Never `./gradlew runServer` or `runClient` unqualified under Stonecutter.** It starts every
node's game, all on the same port.

## Capturing a result you can trust

Redirect to a file and read Gradle's own status. **Never a bare pipe:**

```bash
./gradlew <tasks> --console=plain > build/agent-build.log 2>&1; echo "GRADLE EXIT: $?"
grep -n '^BUILD\|^FAILURE\|tests completed\|required tests' build/agent-build.log
```

Then count what ran:

- JUnit: the XML under `build/test-results/*/TEST-*.xml` (**all** test tasks, not just
  `test/`; a second `Test` task has its own dir). Check the newest file's mtime is from
  this run.
- GameTests: the "N required tests passed" line, and the count **went up by the number of
  tests you added**.
- Wrapper scripts: their PASS marker, not Gradle's exit code.

## The traps (each one shipped a false green)

| Trap | What happened | Do this |
|---|---|---|
| `./gradlew ... \| tail` | `$?` is `tail`'s. A BUILD FAILED arrived as "exit code 0" | Redirect to a file; or `set -o pipefail` |
| Stale results | Compile failed, tests never ran, the previous run's XML was read as this run's | Check `^BUILD` and the XML mtime; a failure quoting text you already changed is stale |
| Registered `Test` task | `tasks.register('x', Test)` inherits no `testClassesDirs`/`classpath`: runs 0 tests, BUILD SUCCESSFUL in 6 s | Set both, plus `filter { failOnNoMatchingTests = true }` |
| Unlisted GameTest class | Fabric finds GameTests from `fabric.mod.json` entrypoints, not by scanning. New class compiled, never ran, count unchanged | List it, then check the count rose |
| Undeclared test input | A test reading `README.md`/`docs/`/`scripts/` stayed UP-TO-DATE (or got a build-cache hit) after only that file changed | `inputs.files(...)` on the `test` task for every repo file a test reads |
| `processResources` closure | Editing a `filesMatching` pattern list left the task UP-TO-DATE | Declare the values it reads with `inputs.property` |
| Chained commit | `./gradlew test; grep "required tests" && git commit` committed a red run (grep succeeds either way) | Gate on the build's exit code: `./gradlew build && git commit` |
| Fast = empty | A suite that normally takes minutes finished in seconds | The clock is evidence. Count what executed |
| Lower count, 0 failures | A forked JVM crashed and a whole class wrote no XML | Copy `build/test-results/` before re-running; compare declared vs executed |

## Long runs

Builds with GameTests, client GameTests, smokes and live-world runs take minutes to hours.
Run them in the background **and** watch them with a monitor that prints on failure, on
hang and periodically (so silence is visible). Specifics:

- A client GameTest whose log ends at "Stopping singleplayer server" is hung: `jstack`
  the `-Dfabric.client.gametest` JVM, then stop that PID.
- A Forge GameTest server whose mod loading failed never exits: give the run task a
  timeout.
- Monitor with a Python poll (seek to the last offset every ~30 s). `tail -F` over several
  files delivered nothing in this Git Bash, and there's no `jq` (use `gh ... --jq`). Make
  every poll loop print when its own command fails, or it looks exactly like "still running".

## Machine reality (the owner's Windows box)

- **Never kill java by name.** The owner's game, other repos' dev servers and other
  sessions' Gradle runs are all `java.exe`. Find processes by command line
  (`Get-CimInstance Win32_Process -Filter "Name='java.exe'"`) and stop a verified PID you
  started.
- **Another build on the same tree** (a previous session's `verifyAll` still in its daemon)
  locks GameTest worlds and deletes your reports. Check before starting; wait, don't kill.
- **"The paging file is too small" (DOS error 1455) or "Could not write standard input to
  Gradle Test Executor"** is memory, not a test failure. Several JVMs are up. Run
  `test`, then `runGameTest`, then `build` separately, or pass a smaller
  `-Dorg.gradle.jvmargs`.
- **VS Code's Java Problems panel lies after branch switches** (stale language-server
  model). Don't fix a diagnostic until a forced Gradle compile (`--rerun-tasks`) agrees it
  exists. Keep `java.jdt.ls.vmargs` at `-Xmx4G`; a 128 GB cap once ate the machine.
- **Shell traps:** Git Bash heredocs collapse backslashes even with a quoted delimiter,
  so put edit scripts in the scratchpad and run them by path. From Windows Python,
  `subprocess.run(["bash", ...])` resolves to **WSL** (`execvpe` failure, empty stdout); use
  `C:\Program Files\Git\usr\bin\bash.exe`, or drive Gradle from the shell, not from Python.
  Python `write_text` writes CRLF here, and `sed -i`/`perl -i` under MSYS rewrite line
  endings or silently write nothing, so assert every scripted edit changed what you
  meant. Commands on separate lines run even if the one above failed; only `&&` chains.
- Long paths: `git -c core.longpaths=true` (Loom caches inside a worktree exceed MAX_PATH).
- Symlinks need Developer Mode; a test that needs one falls back to a junction (`mklink /J`).
- Workflow YAML and release-script tests: run them in Docker (`ubuntu:24.04`, `rhysd/actionlint`),
  not Git Bash, which lacks `jq`/`shellcheck` and fakes `ln -s`.

## When the build fails

1. Read the log file. Find the **first** error, not the last.
2. Compile or type error → `debug-errors` (budget of 3).
3. Dependency resolution failure (403/429/timeout, Mavenizer `HttpTimeoutException`) is
   usually the network: a run far shorter than normal is the tell. Read why before
   suspecting the change; re-run once.
4. Re-run after each fix. **Never stack untested changes.**

## Commands that destroy state

Run the `guard-destructive` five gates first, and never chain one behind `&&`.

| Command | What it actually does |
|---|---|
| `./gradlew clean` | Deletes every node's `build/`, including GameTest worlds, screenshots, logs you haven't read |
| `rm -rf run/` or a run dir | **Dev worlds, copies of the owner's save, harness scripts, `.PREV-*` backups** |
| `clearRunDirectory = true` on a run you point elsewhere | Wipes that directory each run |
| `git clean -xfd` | Ignored files too: `run/`, `.env`, local configs |
| Copying into the owner's instance `mods/` | Outside the repo; see `dev-server` Part E |
| `> file` | Truncates before the command runs; a failed command leaves it empty |

"Clean and rebuild" is not a debugging step. It destroys the evidence. If you reached for a
clean twice, the problem is somewhere else.

## Rules

- **Read the log, not just the exit code.** Count what ran.
- **Never a bare pipe on a command whose status matters.**
- **Qualify run tasks** under Stonecutter.
- **Never kill java by name.**
- **Never suppress a warning** to get green. Fix it.
- **Never chain a destructive command behind `&&`.**
- If an install or build fails 3 times in a row, stop and hand off to `debug-errors`.
