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

## 2026-10-26 — level.dat moved hardcore into `difficulty_settings` on 26.x; a byte search can't tell you the path
**Symptom:** C.6's hardcore read passed on 1.21.11's level.dat and read "not hardcore" from a flipped 26.3 one.
**Cause:** The fact came from searching the decompressed bytes for `hardcore`, which finds the name, not its parent.
26.x keeps `Data.difficulty_settings.{difficulty, hardcore, locked}`; 1.21.11 has `Data.hardcore`, `Data.Difficulty`,
`Data.DifficultyLocked`.
**Fix:** `WorldSeed.hardcore` tries the 26.x path, then 1.21.11's. Fixture test on both layouts with the byte flipped.
**Dead ends:** Byte-searching NBT for a key. Walk the tree and print full paths (a 30-line Python walker does it).

## 2026-10-27 — Quilt meta's installer SHA-1 is stale; trust the maven .sha1 next to the jar
**Symptom:** `meta.quiltmc.org/v3/versions/installer` lists quilt-installer 0.15.1 with SHA-1 `a6ea7a9e…`; the jar on
maven.quiltmc.org hashes to `e4c4e80c…`, the same size (8734533), and matches maven's own `.sha1` and `.sha256`.
**Cause:** Meta's hash list wasn't regenerated when the jar was republished.
**Fix:** `scripts/server-jars.sh` checks every download against the checksum file published beside it on the same maven.
**Affects:** scripts/server-jars.sh

## 2026-10-27 — `$(sed … file | tr …)` kills a `set -euo pipefail` script silently when the file doesn't exist yet
**Symptom:** `commit-smoke.sh` on a fresh Quilt server exited 2 with an empty log.
**Cause:** No `server.properties` before a server's first boot; `sed` fails, pipefail makes the assignment fail, and
`set -e` exits with nothing printed (`2> /dev/null` hid sed's message). Every dev run dir already had the file.
**Fix:** `|| true` on the `LEVEL_NAME=` reads in commit-smoke.sh, dev-server-smoke.sh, tamper.sh.
**Affects:** scripts/*.sh

## 2026-10-27 — Never edit a bash script that a background job is running
**Symptom:** risk only, caught in time: `commit-smoke.sh` was edited (shorter) while a background chain ran it.
**Cause:** bash reads a script as it executes; after a compound command it resumes at a byte offset of the new file.
**Fix:** reran the pair that was in flight. Wait for background runs before editing the scripts they use.

## 2026-10-27 — Dedicated servers pause when empty after 60 s (`pause-when-empty-seconds=60`)
**Symptom:** none yet; a smoke boot that runs longer than ~60 s with no player stops ticking: no guard cycles, no
20-tick settings checks.
**Fix:** keep each smoke boot's commands under a minute after `Done (`. Not a bypass: with no player, there's no one
to penalise, and the guard runs again the tick a player joins.
**Affects:** scripts/checks/*

## 2026-10-27 — A mutation run leaves its effect in the dev world; a chain must set its own baseline
**Symptom:** the tamper chain's vanilla boot replied "Game rule random_tick_speed is already set to 7".
**Cause:** C.6's mutation check (the 20-tick hold off) ran `rules-revert.txt` on fabric 26.3: the rule was set to 7
and nothing put it back, then the cleanup removed the lock. The next `/commit` committed 7.
**Fix:** `scripts/checks/tamper/01-commit.txt` sets random_tick_speed 3, difficulty easy, default gamemode survival
before `/commit`. Any check that names a committed value sets it first.
**Affects:** scripts/checks/tamper/01-commit.txt

## 2026-10-27 — NeoForge writes neoforge-synced.toml after Commitment's setup check
**Context:** D.1 row 08 (move the server's whole `config/` away) passed on Fabric, Quilt and Forge and failed on all three NeoForge nodes.
**Finding:** at startup NeoForge writes `fml.toml` and `neoforge-local.toml` again before the world opens, but its server-side file only later: `neoforge-server.toml` on 26.1.2/26.2, renamed `neoforge-synced.toml` on 26.3 (loaded with the world). So Commitment's check sees the committed file missing, logs `Config removed: config/neoforge-synced.toml` and refuses. That's fail closed, not a bypass: putting `config/` back (or the 24 h unlock) opens it. A NeoForge player who deletes `config/` is locked out of the committed world until the file comes back. Opening any uncommitted world once should write it again with defaults (not proven).
**Rule:** a "the loader regenerates it" assumption is per loader and per config type: probe it on every loader. The matrix runs `scripts/checks/tamper/08-config-moved-neoforge.txt` for NeoForge pairs.
**Affects:** scripts/tamper-matrix.sh, scripts/checks/tamper/08-config-moved*.txt, TODO.md threat table

## 2026-10-27 — Controlify holds config/controlify/profile-1.lock open: /commit failed on Fabulously Optimized
**Symptom:** E.1's first pack run (Fabulously Optimized 15.0.0-beta.2, Fabric 26.3): `/commit` logged ERROR `can't read
config/controlify/profile-1.lock: The process cannot access the file because another process has locked a portion of
the file`. Every player of that pack could never commit.
**Cause:** Controlify keeps a Windows byte-range lock on its profile lock file while the game runs; the scanner reads
every file under `config/`.
**Fix:** `Exclusions.IGNORED` gets `*.lock` (a process lock holds no setup), with ExclusionsTest cases both ways.
**Rule:** a file the running game holds open can fail the scan at `/commit` and at world open alike. Only a real pack
in a real client shows it; JUnit's temp dirs never will.
**Affects:** common/.../manifest/Exclusions.java

## 2026-10-27 — /commit's preview takes seconds on a big pack; a confirm before it ends gets "nothing to confirm"
**Symptom:** the pack driver sent `/commit confirm` 2 s after `/commit`: "nothing to confirm; run /commit first", then
the preview reply arrived.
**Cause:** `/commit` scans the whole setup off-thread (148 mods, ~2 s) before it records the pending confirm.
**Fix:** PackDriver confirms every 10 s until `data/commitment.dat` exists (6 tries). A player who types fast just
retries; the message already says what to do.
**Affects:** fabric/src/gametest/.../PackDriver.java

## 2026-10-27 — Windows refuses renaming a just-written dir of jars for a few seconds
**Symptom:** `pack-fetch.py`'s final `rename(<dir>.partial, <dir>)`: WinError 5, Access is denied.
**Cause:** a scanner (Defender) still holds the fresh jars; it lets go after ~6 s.
**Fix:** retry once a second, 30 s at most (`pack-fetch.py rename`, `pack-copy-instance.sh`).
**Affects:** scripts/pack-fetch.py, scripts/pack-copy-instance.sh

## 2026-10-27 — Build-script ordering for a pack client run, per loader
**Facts:** Loom: `sourceSets.gametest` exists only after `fabricApi { configureTests {} }`, so a run naming it goes
below that block (a `loom.runs` block of its own; `configureEach` above still applies). ForgeGradle 7 takes a run's type
from its name (`client`, `server`, `gameTestServer`), and already makes `run<Name>` per run, so a run named
`packClient` is untyped and `runPackClient` clashes. The Forge pack client is the `client` run itself, pointed at the
pack by `-Pcommitment.packDir`, launched on the gametest classpath (`runGametestClient`, aliased `runPackClient`).
**Affects:** fabric|neoforge|forge/build.gradle

## 2026-10-27 — Loom's `inherit client` carries the client run's -D properties, and the JVM kept them
**Symptom:** the first passing pack run wrote its lock mirror into `run/fabric/26.3/mirror` (the dev server's, where
the smokes refuse leftovers), not `<pack>/mirror`, though `configureEach` named the pack's dir for `packClient`.
**Cause:** `inherit client` copies the client run's `-Dcommitment.mirrorDir`; the pack's value didn't win.
**Fix:** `client()` instead of `inherit client`; `pack-smoke.sh` now fails a commit whose "wrote lock mirror" line
isn't in the pack's mirror dir (checked against the bad run's log first: it fails there).
**Rule:** a smoke that cleans up a file must also check the file was written where the cleanup looks.
**Affects:** fabric/build.gradle, scripts/pack-smoke.sh

## 2026-10-27 — Forge's dist cleaner kills a GameTest server that so much as loads a class naming client types
**Symptom:** after adding the pack driver hook, every Forge `runGameTest` failed at mod construction: `Attempted to load
class net/minecraft/client/server/IntegratedServer for invalid dist DEDICATED_SERVER` (NeoForge's passed).
**Cause:** `PackDriver.active()` loaded PackDriver; verifying it loaded `IntegratedServer` (`getSingleplayerServer()`
assigned to `MinecraftServer`).
**Fix:** the GameTest mods test `System.getProperty(PackDriver.PROPERTY)`: a compile-time constant, so PackDriver is
never loaded outside a pack run. The full build's Forge GameTests are the proof (red before, green after).
**Affects:** forge|neoforge/src/gametest/.../*GameTests.java

## 2026-10-27 — A Forge client on the gametest classpath loads our GameTests' datapack: the world turns "experimental"
**Symptom:** Forge 26.3 pack run sat on vanilla's `BackupConfirmScreen` for 5 min; the log before it: `Found new data
pack commitment_gametest:test_instances, loading it automatically`.
**Cause:** ForgeGradle enables GameTests for runs on the gametest source set, so ForgeGameTests registered its
test-instance pack; vanilla then calls the world's settings experimental and asks for a backup at open.
**Fix:** in a pack run (`-Dcommitment.packDriver`), the NeoForge and Forge GameTest mods wire the driver and return
before any GameTest registration. The driver never presses a backup or experimental prompt: one is a finding.
**Affects:** forge|neoforge/src/gametest/.../*GameTests.java

## 2026-10-27 — Every NeoForge/Forge pack asks for an older loader than Commitment's floor (the dev pin)
**Finding:** neoforge.mods.toml / mods.toml require `[<dev pin>,)`; Modrinth's packs ask for NeoForge 26.1.2.74,
26.2.0.88, 26.3.0.51-beta and Forge 64.0.8, 66.0.4 (pins: .122, .93, .95, 64.1.5, 66.1.0). A player must update the
loader to add Commitment. On NeoForge 26.3 that update breaks the pack's Sodium 0.9.2 (`LevelExtractorMixin` targets a
NeoForge-patched signature that changed); Sodium 0.9.3-beta.1 is the player's next step, done with `pack-fetch.py swap`.
**Open:** whether F lowers the floor (needs a build or a boot on the lower loader). Raised with the owner.
**Affects:** neoforge|forge mods.toml floors, TODO E.1 runs, Stage F

## 2026-10-27 — A pack's first session rewrites files a later session never touches again
**Symptom:** Fabulously Optimized 26.1.2 locked on its first reopen: 7 Crash Assistant localization overrides and
`languagereload.json` "changed". 26.3's FO stayed clean.
**Cause:** Crash Assistant prunes the pack's localization overrides when the first game quits (70 keys → 50); Language
Reload rewrites its config at startup. A player who commits in the pack's first session gets a false lock.
**Fix:** IGNORED `crash_assistant/crash_assistant_localization_overrides/`; NEVER_LOCKED `languagereload.json` (the
language, free like options.txt's `lang`). Tests both ways, mutation red.
**Rule:** a rerun on a pack dir that already ran doesn't reproduce first-session rewrites: reinstall the pack
(`pack-fetch.py`) before proving a fix for one. The old install goes to `build/packs-replaced/`.
**Affects:** common/.../manifest/Exclusions.java

## 2026-10-27 — Dev-environment-only failures in real packs
**Facts:** ModernFix's `devenv` mixin (applies only in a dev run) crashes the Fabric 26.2 client; off with
`mixin.devenv=false` in that pack's `config/modernfix-mixins.properties` (recorded in pack.txt, before the commit).
Forge 64.1.5 refuses `@OnlyIn` mod classes: World No Particles (Fastly Optimised 26.1.2) dropped with
`pack-fetch.py drop` (a leaf mod; the pack asks for Forge 64.0.8). Fabric's log prints `(commitment) Commitment:`
where NeoForge/Forge print `[commitment/]: Commitment:`: match both (`[])]:? `).
**Affects:** run/packs/*/pack.txt, scripts/pack-smoke.sh, scripts/client-locked-smoke.sh

## 2026-10-27 — A pack client can die of Windows commit exhaustion when another repo's test run is going
**Symptom:** two NeoForge 26.3 pack runs ended mid-chain with `hs_err_pid*.log`: "insufficient memory for the Java
Runtime Environment" (an mmap of 1.5 GB, then a malloc of 8 MB), each after Commitment had already matched.
**Cause:** another session's mcMMO-Singleplayer run (a Fabric client plus four test JVMs) took the machine's commit
charge; our client reserves an 8 GB heap by default (1/4 of RAM).
**Rule:** an `hs_err` "insufficient memory" is the environment, not the mod: list java processes by command line,
wait for the other run to finish, rerun. Never stop another repo's JVMs or `gradlew --stop` (shared daemons).
**Affects:** scripts/pack-smoke.sh runs

## 2026-10-28 — Bash 5.3 drops the backslash in `${v//./\.}`; editing a running script breaks the run
**Finding:** Git Bash's bash 5.3 (patsub_replacement) turns `"${v//./\.}"` and `"${v//./\.}"` into the plain value:
the regex built to name a loader build ("26\.3\.0\.51-beta") had bare dots. `$(sed 's/\./\./g' <<< "$v")` escapes
them. Separately, editing `dev-server-smoke.sh` while a `commit-smoke.sh` chain ran it ended that run with `syntax error
near unexpected token '&'` at a line past the edit: bash reads a script as it goes.
**Fix:** sed for regex escaping (both real-server boot regexes). Never edit a script a background run is executing;
queue the edit for when it ends.
**Affects:** scripts/dev-server-smoke.sh (real_boot_line), scripts/client-locked-smoke.sh (BOOT_RE)

## 2026-10-28 — NeoForge builds bundling MixinExtras 0.5.4 crash on MainMixin's @Redirect at server start
**Finding:** F.0's real servers: NeoForge 26.1.2.71, 26.1.2.74, 26.2.0.57, 26.2.0.88 die before any mod loads with
`ClassCastException: ArrayList cannot be cast to AnnotationNode` in `mixinextras ... FactoryRedirectWrapperMixinTransformer
.transform(:18)`, applying `MainMixin` (our `@Redirect` in `Main.main`). Those builds bundle MixinExtras 0.5.4; 0.5.5
(26.1.2.114+, 26.2.0.90+, every 26.3 from 26.3.0.51-beta) loads it. Read from each universal jar's
`META-INF/jarjar/metadata.json`, bisected over maven-metadata.xml, not booted. Forge 64.0.0/65.0.0/66.0.0 are fine.
**Open:** floor at the first 0.5.5 build, bundle MixinExtras, or rewrite MainMixin: owner's call (TODO F.0).
**Affects:** common/.../mixin/MainMixin.java, gradle.properties neoforge_floor.*, TODO F.0

## 2026-10-28 — Mutating a download/delete guard in a self-test that runs from the repo does real work
**Finding:** F.2's `fabric_api` path-guard cases ran from the repo root; mutated to accept any `real/.*`, the guard let
three bad paths through and the function downloaded Fabric API into new `run/real/x`, `run/real/neoforge/26.3/26.3.0.95`
and `run/real/fabric/26.1.2/26.1/server` (`mods/..`). Nothing pre-existing was touched (the dirs were new); listed,
removed by name.
**Rule:** a guard test runs where a broken guard can't reach anything real: `cd` the self-test's temp dir, with no
`gradle.properties`, so the function fails before any download even if the guard is gone.
**Affects:** scripts/dev-server-smoke.sh (self_test, fabric_api cases)

## 2026-10-28 — dev-server-smoke could read the last boot's "server exited" line and never send stop
**Symptom:** F.2, `commit-smoke.sh` on the real Fabric 26.3 server: the third boot (config-restored) reached `Done (`,
logged `heartbeat on`, then sat until `timeout` killed it (exit 124), with "the server exited before 'Done ('".
**Cause:** `feed_console | { java ... } > "$OUT"`: both sides of a pipeline start together, and the redirect that
truncates `$OUT` runs in the right side. When feed_console's first poll won the race, it read the previous boot's
`dev-server-smoke: server exited 1` (boot 2 is a refusal, so it always leaves one), returned without `stop`.
Intermittent: one pair of 17, once. (The same pair's first failure was a real one: Windows commit exhaustion during
another repo's test run, hs_err "insufficient memory", after `setup matches` had already been logged.)
**Fix:** `: > "$OUT"` before the pipeline; a self-test case pins its place (a race can't be pinned by a run).
**Rule:** never let a reader of a file start in the same pipeline as the redirect that resets it.
**Affects:** scripts/dev-server-smoke.sh

## 2026-10-28 — A network blip fails commit-flow.txt as exit 1, though nothing about the mod was proven
**Symptom:** F.4, `REAL=26.2.0.90 commit-smoke.sh` on NeoForge 26.2: exit 1, four `FAIL: no line matching @expect ...
unlock available at` / `unlock cancelled`. The reply instead: `can't start the unlock without internet time (0 of 3 time
servers answered (www.google.com: SocketTimeoutException ...; www.cloudflare.com: UnknownHostException ...))`.
**Cause:** the machine's network dropped for a moment at 19:41; four minutes later all three hosts answered (HEAD 200).
The mod did what it should (refuse the unlock without internet time); every other check of that boot matched.
**Rule:** an `internet time` refusal in a smoke log is the environment, not the mod: probe the three hosts, then rerun.
The harness can't tell the two apart yet, so it says 1 where 2 ("nothing proven") is the truth.
**Affects:** scripts/checks/commit-flow.txt, scripts/dev-server-smoke.sh (verdict)

## 2026-10-29 — The release gate's jar hashes lived only in a session scratchpad
**Symptom:** F.5: state.md said "SHA-256 in TODO F.4", but TODO held only the jars' build times. The list F.4 checked
(`jars-before.sha256`) sat in session 15's scratchpad, found only by a slow grep across every session's temp folder.
**Cause:** the gate wrote its proof to the scratchpad and the TODO entry summarized it ("unchanged across the gate")
instead of copying it. Scratchpads are per session and get cleaned.
**Rule:** the hashes of the jars a gate proves go into TODO.md in the same commit that records the gate. mtimes are a
fallback, not a proof. F.5's entry now holds 1.0.0's ten hashes.
**Affects:** TODO.md (release gate entries), dev-server skill Part D

## 2026-10-29 — A GitHub release body renders every newline as a line break
**Symptom:** CHANGELOG.md is hard-wrapped at 120 columns; pasted as release notes it breaks lists mid-sentence.
**Rule:** `--notes-file` gets the entry's body with continuation lines (two-space indent) joined to their bullet;
check the alphanumeric content is identical (`tr -cd '[:alnum:]' | md5sum` on both). CHANGELOG.md itself stays wrapped.
**Affects:** releases (git-github skill)
