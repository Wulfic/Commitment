# Gotchas

> **Append-only.** Traps, dead ends, and environment quirks that cost real time.
>
> The test for whether something belongs here: **would a competent developer hit this
> again in six months?** If yes, write it down. If it's obvious from reading the code,
> it does not belong here.
>
> Log a failed approach as carefully as a successful one. "We tried X, it doesn't work
> because Y" is what stops the next session from burning an hour on X.

## Entry template

```markdown
## YYYY-MM-DD — Short title
**Symptom:** what you actually saw (error text, wrong output, hang)
**Cause:** the real root cause
**Fix:** what worked
**Dead ends:** what looked right but wasn't, and why
**Affects:** src/path/to/file.ts
```

---

## 2026-08-12 — `.gitignore` does not cover `.env`
**Symptom:** `.env` is tracked in git despite a large "Secrets / config" section in
`.gitignore`.
**Cause:** That section only lists `credentials*`, `secrets*`, `*.pem`, `*.key`, `*.crt`.
There is no `.env` pattern, and a file already tracked stays tracked even after a matching
pattern is added.
**Fix:** Not applied — the repo owner opted to leave credential handling as-is for now.
Anything spawned from this template inherits both the tracked `.env` and the pattern gap.
**Dead ends:** Adding `.env` to `.gitignore` alone does nothing for an already-tracked
file; it needs `git rm --cached` as well.
**Affects:** `.gitignore`, `.env`, `.mcp.json`, `.claude/mcp.vscode-reference.json`

## 2026-08-12 — The two MCP config files disagreed on hosts (RESOLVED)
**Symptom:** `.mcp.json` pointed at `<lan-ip>.120`–`.126`;
`.claude/mcp.vscode-reference.json` pointed at `<lan-ip>.186:7801`–`7806`.
**Cause:** The stack migrated from a single docker-compose host (`.186`, one port per
service) to native OCI containers on Proxmox node `<lan-ip>` (CTIDs 120–126, **one
IP per server**). `.mcp.json` was updated; the reference file was not.
**Fix:** `.120`–`.126` is current. The reference file was realigned to `.120` and the
stale `.186:78xx` addresses removed.
**Dead ends:** We initially guessed the `.186` file might be the newer one because
`AGENTS.md` still described a docker-compose stack — the prose was simply stale too. The
`// description` comment in `.mcp.json` is the reliable source here, not the prose.
**Affects:** `.mcp.json`, `.claude/mcp.vscode-reference.json`

## 2026-08-12 — mcp-compressor 0.31.4 changed its CLI flags
**Symptom:** Server configs written with `--compression-level` and `--header` fail on
current mcp-compressor. Backends without an `Authorization` header fail differently —
mcp-compressor auto-attempts OAuth discovery, which these backends don't support.
**Cause:** 0.31.4 renamed `--compression-level` → `--compression`, `--header` → `-H`, and
now requires a `--` separator before the backend URL and its args.
**Fix:** Use `--compression`, `-H`, and `--`. For backends with no real auth, pass a
placeholder `-H "Authorization=Bearer unused"` to suppress OAuth discovery.
**Dead ends:** Omitting the Authorization header entirely looks correct for an unauthed
backend, but triggers the OAuth path and fails with an unrelated-looking error.
**Affects:** `.mcp.json`, `.claude/mcp.vscode-reference.json`

## 2026-08-12 — A newly created `.claude/settings.json` does not load in the session that created it
**Symptom:** Added `permissions.deny` / `permissions.ask` rules, then ran `dropdb --help`
(matches the `ask` rule `Bash(dropdb*)`). It executed straight through to
`bash: dropdb: command not found` — no prompt, no block.
**Cause:** The settings watcher only watches directories that already contained a settings
file when the session started. `.claude/settings.json` did not exist at session start, so
the new file stays invisible until the session restarts (or `/hooks` is opened, which
reloads config). Nothing wrong with the file — it parses and matches the published schema.
**Fix:** Restart the session, or open `/hooks` once. Verify afterward with a harmless
matching command such as `dropdb --help`.
**Dead ends:** Re-checking the JSON and the rule syntax — both were correct the whole time.
The failure looks exactly like a bad matcher pattern, which is the trap: **a permission
rule that silently doesn't load is indistinguishable from one that doesn't match.** Same
class of invisible-failure problem that got `mem0` dropped. Probe with a real command
rather than assuming the rules are live.
**Affects:** `.claude/settings.json`, `.claude/skills/guard-destructive/SKILL.md`

## 2026-10-21 — RESOLVED: secrets are untracked; the template is safe to make public
**Symptom:** `.env` (passwords, email), `.mcp.json` and `.claude/mcp.vscode-reference.json`
(a GitHub PAT and a context7 key) were tracked, so every repo made from this template
started with live credentials in its first commit (the 2026-08-12 entry above).
**Fix:** Owner, 2026-10-21: "cleanup this repo and any unneeded secrets so we aren't leaking
them in future releases". `git rm --cached` on all three (the files stay on disk, so local
MCP keeps working), `.gitignore` covers them, and tracked templates hold placeholders:
`.env.example`, `.mcp.example.json`, `.claude/mcp.vscode-reference.example.json`. LAN
addresses in this file were masked as `<lan-ip>` (the one edit to past entries here, made to
redact, not to rewrite).
**Still true after the fix:** commit `8108c25` on `origin/main` holds every one of those
values, and so do the owner's other mod repos that copied this template. Untracking stops
new leaks; it does not unleak. The credentials need rotating, and the template's own
history needs rewriting (or the repo recreating) before **it** goes public. Repos created
with "Use this template" start from a fresh single commit of the current tree, so they're clean.
**Affects:** `.gitignore`, `.env.example`, `.mcp.example.json`, `.claude/mcp.vscode-reference.example.json`

## 2026-10-21 — Vanilla Safe Mode does not leave world datapacks disabled; `/tick freeze` stops tag functions
**Symptom:** decisions.md 2026-10-21 ("without the mod, the fallback is in-game effects") assumed, from memory,
that the failed-datapack screen's Safe Mode leaves the guard pack switched off for good.
**Cause:** `configurePackRepository(..., safeMode=true)` selects `vanilla` (plus mod packs on Forge/NeoForge) and
saves with `disableInactive=false`, so the **disabled list is empty**. Any world pack comes back automatically on
the next normal launch ("Found new data pack …, loading it automatically"). Separately, `ServerFunctionManager.tick()`
skips `#load` and `#tick` while `tickRateManager().runsNormally()` is false, so `/tick freeze` pauses the guard.
**Fix:** None needed. The fail-to-load design stays rejected (Safe Mode is still a one-click bypass on every launch for
a pack that always fails). `/tick freeze` needs cheats, which is the same bar as `/datapack disable`.
**Dead ends:** Reasoning about Safe Mode from memory. Decompile `MinecraftServer.getSelectedPacks` instead.
**Affects:** TODO.md 0.2, the future guard pack and threat-model table

## 2026-10-21 — Dev servers on NeoForge/Forge: no console input, and Forge's dev run splits the mod in two
**Symptom:** `scripts/dev-server-smoke.sh` on NeoForge reached `Done (`, then ignored `list` and `stop` until the
timeout (exit 124). On Forge, `runServer` exited 0 in 42 s with no server: `File …/build/resources/main constructed
0 mods: [], but had 1 mods specified: [commitment]` (FATAL), crash report, `BUILD SUCCESSFUL`.
**Cause:** MDG's `RunGameTask` is a `JavaExec` that doesn't set `standardInput`, so the console never sees the pipe
(Loom's run tasks do). ForgeGradle 7 runs `build/classes` and `build/resources` as two mod files unless the run
puts them in one directory: Forge loads a dev mod as a Java module, and a resources dir holding mods.toml with no
classes "constructed 0 mods". the-continent never hit either: it only boots built jars on real servers.
**Fix:** `tasks.named('runServer', JavaExec) { standardInput = System.in }` (neoforge/build.gradle). In forge/build.gradle,
Forge's MDK snippet: every source set's `output.resourcesDir` and `java.destinationDirectory` set to one
`build/sourcesSets/<name>` dir. With that, Forge's console took `list` and `stop` (forge/build.gradle also sets
`standardInput` when the run task is a `JavaExec`; which of the two made the console work is unverified).
**Dead ends:** FG7's per-run `mods { register('commitment') { source sourceSets.main } }`: the same crash, removed.
Gradle's exit code: 0 on the Forge crash. The smoke's first "did it launch" test grepped for
`Starting minecraft server version` and called the Forge crash "nothing proven" (exit 2). It's a verdict (exit 1).
It now keys on any log4j line, with a self-test case for each.
**Affects:** neoforge/build.gradle, forge/build.gradle, scripts/dev-server-smoke.sh

## 2026-10-21 — Forge 26.x `ModList` is all static; use `ForgeVersion.getVersion()`
**Symptom:** `ModList.get()` → `cannot find symbol` on every Forge node (NeoForge still has the instance API).
**Cause:** Forge 26.x fmlcore turned `ModList` into static methods (`ModList.getModContainerById(id)`), read with
`javap` from `fmlcore-26.3-66.0.13.jar`. `ModList` is in fmlcore, not in Forge's injected sources jar.
**Fix:** `net.minecraftforge.versions.forge.ForgeVersion.getVersion()` for Forge's version;
`context.getContainer()` for our own.
**Affects:** forge/src/main/java/com/wulfic/commitment/CommitmentForge.java

## 2026-10-21 — Java doesn't call a Windows junction a symbolic link; and this machine can't make symlinks
**Symptom:** Writing B.6's "a path that resolves outside the world is refused" test: `Files.createSymbolicLink` fails
with "A required privilege is not held by the client" (no Developer Mode), so the test had no link to feed the guard.
**Cause:** Windows symlinks need a privilege; directory junctions (`mklink /J`) don't. And for a junction, Java says
`Files.isSymbolicLink` = **false** (`isOther` = true), so a guard that only checks `isSymbolicLink` /
`isRegularFile(NOFOLLOW_LINKS)` on the file lets a junction in a *parent* folder walk straight out of the world.
**Fix:** `LockLifter.check` compares `file.toRealPath()` with `root.toRealPath().resolve(root.relativize(file))`, which
catches a link or junction anywhere on the path. `LockLifterTest.link` makes a symlink, or on Windows without the
privilege a junction (`cmd /c mklink /J`); it fails rather than skips if neither works.
**Dead ends:** `Files.isSymbolicLink` on each component (misses junctions).
**Affects:** common/.../lock/LockLifter.java, LockLifterTest.java

## 2026-10-21 — `core.autocrlf=true` here: byte-compared fixtures need `eol=lf` in `.gitattributes`
**Symptom:** None yet; caught before the first commit of the guard pack's golden files.
**Cause:** The owner's git converts text to CRLF on checkout, and `GuardPackTest` compares the golden files with the
generator's LF text, so a fresh clone on Windows would go red.
**Fix:** `common/src/test/resources/guard-pack/** text eol=lf` in `.gitattributes`. Do the same for any new fixture
that's compared byte for byte.
**Affects:** .gitattributes, common/src/test/resources/guard-pack/

## 2026-10-21 — GameTest harness traps: `always_pass`, filter closures, and Stonecutter's per-source-set tasks
**Symptom:** Three ways the C.0 harness looked green while proving nothing. (1) The report check ported from
../enchanting-2 ("the report has test cases") passed with the GameTest mod not loaded at all: the server printed
"All 1 required tests passed :)". (2) With the import swap's filter logic broken, Forge's GameTests still compiled:
`swapGameTestImports` was UP-TO-DATE and kept its old output. (3) NeoForge 26.3 compiled a stale copy of the shared
GameTest (the old `FabricLoader` one) although the source had changed.
**Cause:** (1) Vanilla registers `minecraft:always_pass` on every GameTest server, so a report always has a case.
(2) Gradle doesn't track what's inside a `filter { }` closure. (3) Stonecutter generates each source set with its own
task: `stonecutterGenerate` is main's only; the gametest set's is `stonecutterGenerateGametest`.
**Fix:** (1) `checkGameTestReport` counts only `commitment[-_]gametest:*` cases and fails on zero (mutation: the
GameTest mod left out of `loadedMods` → red). (2) The swap is a map declared with `inputs.property('importSwap', …)`
and the closure reads the map (mutation through the map → recompiles → red). (3) The `serverGameTestJava` artifact is
`builtBy stonecutterGenerateGametest`.
**Affects:** gradle/gametest-report.gradle, fabric/neoforge/forge build.gradle

## 2026-10-21 — The leaked credentials are in the template's history, not Commitment's (blocker cleared)
**Symptom:** state.md carried "rotate the credentials (commit `8108c25`)" as an open blocker for this repo.
**Cause:** `8108c25` belongs to the template repo. Commitment was made with "Use this template", so its history starts
from a fresh commit: `git rev-parse 8108c25` fails here, no `.env`, `.mcp.json` or `mcp.vscode-reference.json` was
ever tracked in any of its commits, and `git log --all -p` has no GitHub PAT, context7 key or password pattern (checked
session 5). The tracked `*.example*` files hold only placeholders, and the real files are git-ignored.
**Fix:** Owner, session 5: if this repo is clean, clear the blocker and stop raising it. Cleared from state.md. The
template repo's own history is outside this repo and not tracked here.
**Affects:** .agent/memory/state.md

## 2026-10-21 — `Minecraft.setScreen` is gone on 26.2+; the plan said it was on every node
**Symptom:** C.2's plan (TODO.md) said `Minecraft.setScreen` and `setScreenAndShow` exist on every node. javap: `setScreen`
is only on 26.1.2's `Minecraft`.
**Cause:** 26.2 moved screen handling into `Gui`: vanilla's `WorldOpenFlows` on 26.3 calls `Gui.setScreen(Screen)` (via
`minecraft.gui`) 15 times and `Minecraft.setScreenAndShow(Screen)` 10 times.
**Fix:** Common client code uses `Minecraft.setScreenAndShow(Screen)`, present on 26.1.2, 26.2 and 26.3, or a Stonecutter
block where the plain set is needed. The plan line was written from memory: read the jar.
**Dead ends:** A `javap` per class over the whole client jar to find the new owner (minutes). Disassembling a vanilla
caller (`WorldOpenFlows`) answered it in one command.
**Affects:** C.2 client gate (ClientGate, WorldOpenFlowsMixin)

## 2026-10-22 — A Fabric client GameTest's file name must end in `ClientGameTest.java`
**Symptom:** none yet: caught while writing `ClientGateGameTest.java`.
**Cause:** NeoForge and Forge compile Fabric's server GameTest dir per node (C.0), and both the `serverGameTestJava`
filter and `GameTestSourcesTest` skip client tests by the `*ClientGameTest.java` suffix, not by entrypoint. A client
test named otherwise is copied to NeoForge/Forge, where `net.fabricmc...client.gametest` doesn't exist.
**Fix:** named it `WorldGateClientGameTest`.
**Affects:** fabric/src/gametest/java, fabric/build.gradle, GameTestSourcesTest

## 2026-10-22 — loom-back-compat in a Stonecutter build with branches needs buildscript repositories
**Symptom:** `A problem occurred configuring project ':common'. Cannot resolve external dependency
net.fabricmc.fabric-loom:...gradle.plugin:1.18.4 because no repositories are defined.`
**Cause:** loom-back-compat 0.4.3 registers `gradle.lifecycle.beforeProject` (read from its jar) and adds both Loom
plugins to **every** project's buildscript classpath. A branch project (`:common`, `:fabric`) has no build file of its
own, so pluginManagement's repositories never reach its classpath. ../enchanting-2 has no branch projects, which is
why it never hit this.
**Dead end:** adding `buildscript.repositories` in a settings `beforeProject` while pluginManagement used
`exclusiveContent` → "When using exclusive repository content in 'settings.pluginManagement.repositories', you cannot
add repositories to 'buildscript.repositories'".
**Fix:** the KikuGie repo as a plain `maven { content { includeGroupAndSubgroups 'dev.kikugie' } }`, plus a settings
`gradle.lifecycle.beforeProject { buildscript.repositories { Fabric, mavenCentral, gradlePluginPortal } }`.
**Affects:** settings.gradle

## 2026-10-22 — A mutation that didn't apply looks like a green mutation check
**Symptom:** mutating `scripts/world-audit.sh`'s `(\.$UUID\.lifted)?` with a Python replace passed from Git Bash
reported "0 cases red". The replace had asserted and failed, so the self-test ran on the unmutated copy.
**Cause:** the backslashes didn't survive the trip to Windows Python, both as a command-line argument and inside the
agent's quoted heredoc (`r'(\.…'` arrived as `r'(\.…'`).
**Fix:** build backslashes in Python with `chr(92)`, and have every mutation script `assert s.count(old) == 1` and
print "mutated" before the run, so a mutation that didn't apply can't read as a pass.
**Affects:** mutation checks of any script or source holding backslashes

## 2026-10-22 — Drive Gradle from Git Bash, not from Windows Python's subprocess
**Symptom:** a mutation runner in Python (`subprocess.run(['bash', './gradlew', ...], capture_output=True)`) reported
`exit=1, red=0` for all ten C.3a mutations. Run by hand from Git Bash, the first one was plainly red
(`aConfirmWithin60SecondsGoesThroughOnce` FAILED).
**Cause:** not pinned down: `bash` resolved to Git Bash, yet the captured output never matched the `... FAILED` lines.
Reading a mutation result through a layer whose output can't be seen is how "0 red" turns into a false pass.
**Fix:** Python only edits files (asserting each edit applied); Gradle runs from a Git Bash loop that writes each run's
log to a file, and the verdict is grepped from that log.
**Affects:** any mutation-check script

## 2026-10-22 — Loom does NOT wipe the Fabric GameTest world between runs
**Symptom:** CommandsGameTest passed once, then failed on the next run with "this world is committed" at its first
step, and a lock mirror deleted by hand came back: the server rebuilt it from the world's record at start.
**Cause:** `<node>/build/run/gameTest` (eula.txt, mods/, world/) persists across `runGameTest` runs; it had been there
since Oct 21. fabric/build.gradle and neoforge/build.gradle both said Loom wipes it. Nothing committed the GameTest
world before C.3, so the stale claim never showed.
**Fix:** a guarded `clearGameTestWorld` on Fabric (only `build/run/gameTest/world` and `.../mirror`, fail closed;
mutation: another path → "Refusing to delete") before `runGameTest`, and every loader's GameTest server writes its
lock mirrors into its own wiped run dir instead of the shared `run/<loader>/<node>/mirror`, so a test that fails
midway can't leave a mirror the dev-server smokes then refuse to run over.
**Affects:** fabric/build.gradle, neoforge/build.gradle, forge/build.gradle, CommandsGameTest

## 2026-10-26 — A mutation's "check" that uses the same pattern as the mutation proves nothing
**Symptom:** A mutation of `dev-server-smoke.sh` (delete the `@wait` → expectation line) looked green, and
`grep -c` with the same escaped pattern printed 0, which read as "the line is gone".
**Cause:** The `sed` pattern (`@wait\ \*) expected+=`) matched nothing, and the `grep -c` meant to confirm the
deletion used the same broken pattern, so it also matched nothing. The file was unchanged.
**Fix:** Mutate into a copy and `diff` it against the original before running anything; a mutation with no diff
lines didn't happen. Prefer loose patterns (`/@wait.*expected+=/d`) over escaped literal ones.
**Affects:** every mutation check (see 2026-10-22, "A mutation that didn't apply looks like a green mutation check")

## 2026-10-26 — A ScrollableLayout sized before its content is arranged is 0 high, and every button still works
**Symptom:** C.4's LockedScreen showed its title and buttons but none of its body (the changes, the hint, the
countdown). The client GameTest clicked every button through start, cancel and lift and stayed green.
**Cause:** `ScrollableLayout.setMaxHeight` sets the container to `min(content height, max)`, and the content (a
`LinearLayout`) has height 0 until `arrangeElements` runs. Called first, the container stays 0 high; `arrangeElements`
only clamps the height it already has. Vanilla's `ExperimentsScreen` arranges, sets the max height, arranges again.
**Fix:** `repositionElements` arranges twice around `setMaxHeight`. Found only by `context.takeScreenshot` in the
client GameTest and reading the PNGs. The test now checks each expected line is laid out inside a scroll area with
room (`checkShows`, public vanilla API only), and the screen logs `locked screen shows N lines; buttons: …` from the
scroll area's real height (mutation: one arrange → red at the first line).
**Affects:** common/.../client/LockedScreen.java, WorldGateClientGameTest

## 2026-10-26 — A fresh dev client waits at accessibility onboarding; NeoForge 26.2+ warns on `logoFile`
**Symptom:** `runClient --quickPlaySingleplayer <world>` on NeoForge sat after resource loading until the timeout.
**Cause:** two screens in front of Quick Play. (1) NeoForge 26.2.0.93 / 26.3.0.95 log "Mod commitment uses the
deprecated `logoFile` property" and show a mod-loading warnings screen at **every launch**, for players too.
26.1.2.122 knows only `logoFile` (no `iconFile`/`bannerFile` in its jar). (2) A fresh client's `options.txt` has
`onboardAccessibility:true`, and vanilla holds Quick Play behind the onboarding screen. Fabric's client GameTest
turns onboarding off by itself, so it never showed there.
**Fix:** `neoforge_icon_key.<node>` in gradle.properties (`logoFile` on 26.1.2, `iconFile` on 26.2+), expanded into
neoforge.mods.toml. `client-locked-smoke.sh` sets `onboardAccessibility:false` in the dev client's own options.txt
and fails on "Mod commitment uses" / "Mods loaded with N warning". Forge 26.x doesn't warn on `logoFile`.
**Affects:** gradle.properties, neoforge/build.gradle, neoforge.mods.toml, scripts/client-locked-smoke.sh
