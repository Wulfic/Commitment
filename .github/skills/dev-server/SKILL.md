---
name: dev-server
description: "Prove a Minecraft mod change on the live dev server: boot it, drive it with console commands, read the log, stop it cleanly. Also live-world runs on a copy of the owner's save, booting the built jar on a real loader server, the client create-world check, and installing into the owner's instance. Use when: any gameplay, server, data, mixin, config or networking change is about to be called done; reproducing an owner's bug report; before any release push; setting up a new mod repo's dev server."
argument-hint: "What to prove (e.g. 'the new loot table drops on the dev server', 'reproduce the owner's stuck-village report')"
---

# Dev Server: Run the Real Game Before You Call It Done

Unit tests and GameTests run a Minecraft that was set up for the test: sealed arenas,
mock players that are creative and never ticked, a world that is flat. The dev server runs
the real one. It has caught what every suite missed, in every mod repo here:

- **mcMMO v1.5.1** shipped to 26.3 with an advancement the registry refused, so no player
  could create or join a world (GitHub #20). Suite green, every structural gate green, and
  the launch gates were written down as "NOT run" two releases in a row.
- **real-villages:** a hunger/fuel deadlock survived sessions of unit tests, GameTests and
  reasoning. One run on a copy of the owner's save found it in minutes. Owner, 2026-10-01:
  *"test in live worlds since it's REAL data"*.
- **the-continent:** the Forge server reached `Done`, logged no ERROR, said "initialized",
  and the world was **vanilla**. Every step of the failure was a WARN.
- **enchanting-2:** the client crashed at startup since Stage 2C. Nobody had launched one.

**The rule:** a change that touches gameplay, server logic, data files, mixins, config or
networking is not done until it ran on the dev server and the log says it did what you
claim. A green suite is the entry ticket, not the verdict.

## When to Use

| Situation | Rung (see the ladder below) |
|---|---|
| Any gameplay / server / data / mixin / config / networking change | 3: dev-server smoke |
| A new datapack file, tag, recipe, loot table, advancement, worldgen JSON | 3. A bad tag is a hard load failure, not a warning |
| An owner bug report, or anything that needs time, terrain or many entities | 4: live-world run |
| A client-side change (screen, renderer, keybind, HUD, client mixin) | 5: client check |
| **Any push that can publish a release** | 5 + 6 on every band and loader being released |
| The owner says it doesn't work but the tests are green | 7: read the owner's instance first |

## The verification ladder

Each rung catches what the one below cannot. Climb as high as the change reaches.

| # | Rung | Proves | Blind to |
|---|---|---|---|
| 1 | JUnit (`./gradlew test`) | Pure logic, codecs, data-file shape | Registries, a real world, a tick |
| 2 | Server GameTests (`runGameTest`) | Behaviour inside a running server | Mock players, sealed arenas, flat world, shared batch state |
| 3 | **Dev-server smoke** (`scripts/dev-server-smoke.sh`) | The mod boots in the dev runtime, commands work, the log is clean | Long runs, real terrain, a real player, the shipped jar |
| 4 | **Live-world run** (copy of the owner's save) | Behaviour over days of game time on real data | Clients |
| 5 | Client world check (`runClientGameTest`) | The real client creates and joins a **new** world with the mod | The shipped jar, other bands |
| 6 | Built jar on a real loader server | The artifact players download boots and does its job | Client-side code |
| 7 | Owner's instance | Their modpack (~200 mods) doesn't break it | Nothing you control |

A dedicated server is a **proxy** for the game, not the game. It can't see a client mixin,
a renderer, a screen, or the create-world flow. Rung 5 can.

---

## Part A: The dev server, set up once per repo

Do this in the first plan that scaffolds the mod. Each item answers a failure that already
happened in one of the owner's repos.

1. **Its own port.** The owner runs other servers on 25565, and other mod repos' dev
   servers use 25566–25599. Pick one unused port, put it in `gradle.properties`
   (`dev_server_port=`) and pass it as `programArgs '--port', dev_server_port` on the
   server run. Record it in `state.md`. Never default a harness to 25565.
2. **Its own run dirs.** `run/server` and `run/client`, never a shared `run/`. Client saves
   and server worlds otherwise mix, and a newer Minecraft upgrades the other's world. With
   Stonecutter, one run root per node. `run/` is gitignored and disposable.
3. **EULA written only if absent.** A `acceptDevServerEula` task that `runServer` depends on,
   writing `eula=true` only when `eula.txt` doesn't exist. Never overwrite it.
4. **`scripts/dev-server-smoke.sh [commands-file]`** with this contract. Copy and adapt
   `../enchanting-2/scripts/dev-server-smoke.sh` (owner's machine), which meets it:
   - **Refuses with exit 2 if the port is already listening**: no second server.
   - Starts **one qualified run task** (`./gradlew :<node>:runServer`). A bare
     `runServer` under Stonecutter starts every node's server on the same port.
   - Waits for **this run's** `Done (` line. Check the log is newer than a marker file
     touched before the start, or you grade the previous run's log.
   - Feeds console commands **after** `Done (`, with the pipe held open. `echo stop |
     ./gradlew runServer` closes stdin straight away; on Windows it logs "An unexpected
     error occurred trying to execute that command" and never stops. On Linux use a FIFO
     held open on fd 3 (`mkfifo f; exec 3<>f; ./gradlew runServer <&3 >log 2>&1 &`).
   - Commands-file directives: `@expect <regex>` (the log must contain it),
     `@allow-error <regex>` (a known, named ERROR), `@sh <cmd>` (a shell step between
     commands), `#` comments.
   - Stops with `stop` on stdin, under a `timeout`. **Never kills a process.**
   - Verdict, read from the log and not Gradle's exit code: `Done (` reached, the mod's
     own init line present, **zero ERROR/FATAL lines from the mod** (match
     `/(ERROR|FATAL)\]`; the level shares a bracket with the thread name, as in
     `[Worker-Main-13/ERROR]`, and a bare `\[ERROR\]` regex matches nothing, ever), every
     `@expect` matched, clean exit.
   - **Exit codes: 0 proven · 1 the mod is bad · 2 nothing proven** (port busy, Gradle
     failed before launch, a download failed). Exit 2 is never reported as a pass, and
     never as a verdict on the mod.
   - `--self-test` drives the verdict function with synthetic logs, both polarities,
     **including a log with an ERROR line**. mcMMO's ERROR count printed 0 for its whole
     life because nothing ever fed it one.
5. **Commands files are committed** under `scripts/checks/<feature>.txt`, one per feature
   worth re-proving. They're the regression suite for rung 3.

## Part B: Running a smoke (rung 3)

1. **Check nothing else is running on this tree or port.** Find game JVMs by command line,
   never by name:
   ```powershell
   Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
     Select-Object ProcessId, @{n='cmd';e={$_.CommandLine.Substring(0,[Math]::Min(200,$_.CommandLine.Length))}}
   ```
   Look for `KnotServer`, `gametest`, or this repo's path. Another session's `verifyAll`
   on the same tree locks the GameTest worlds and deletes your report. Wait for it.
   **Never `taskkill /IM java.exe`**: that kills the owner's game and other repos' servers.
2. Write the commands file: the setup, the action, an `@expect` for the log line that
   proves the effect. If the mod logs nothing that proves it, add the log line. An
   unobservable effect is an error path with no logging.
3. Run it in the background with a monitor that prints on failure and on silence. A
   server that failed to load can sit there without exiting.
4. Read the verdict **and the log**. Record the result in `TODO.md` against the step:
   command, exit code, the matched `@expect` lines.

**Fingerprint the effect, not the absence of errors.** "No ERROR" is what a mod that did
nothing prints. Assert the thing itself: `/locate` lands where your worldgen puts it, the
saved generator names your preset, `/data get` shows your component, the drop appears.
Rule of thumb: if reverting your change would leave the smoke green, the smoke is vacuous.
Run it once against the revert to prove it isn't.

## Part C: Live-world runs (rung 4)

For owner reports, and for anything needing real terrain, time, players or many entities.
This is the real-villages method (gotchas 2026-10-01 / 10-02).

1. **Copy, never touch, the owner's save.** Copy `saves/<world>` without `session.lock`
   (and big caches like `voxy/`) into `run/<name>`, and `cmp`-verify the copy. Keep a
   pristine snapshot (`run/seed-<name>`) and start every run from a fresh copy of it.
   Never let the dev server *generate* terrain the owner's pack generates differently
   (Tectonic and friends); copy already-generated chunks.
2. **Back up the run files you change** (`server.properties`, the mod's config) to
   `.PREV-<task>`, and restore + `cmp` them when done. Copy the owner's config in
   **unchanged** unless the test is about config.
3. **Server properties for a headless run:** `pause-when-empty-seconds=-1` (a dedicated
   server pauses after 60 s with no players), `enable-rcon=true` with a local-only
   password, the right `level-name` and `difficulty`.
4. **Player presence:** with no player, a lot of mod logic never runs (chunk ticking,
   anything gated on nearby players, discovery). Install fabric-carpet in `run/mods/`
   (checksum it) and spawn fake players:
   `player <name> spawn at <x> <y> <z> facing 0 90 in minecraft:overworld in spectator`.
   Spectators count as players but don't interact. They don't survive a restart; respawn
   each boot. Also check the mod's own dormancy settings for headless runs.
5. **Drive it over RCON or stdin.** RCON traps: replies over 4096 bytes arrive as several
   packets (read until quiet); one packet per read, so a pipelined command is dropped;
   **two RCON clients at once garble replies**, so one client only. Console commands run
   at world spawn; use `execute positioned <x y z> run ...` for anything positional.
6. **Bound every run before it starts.** Arm a background stop at the planned game day
   (`tick freeze` or `save-all flush` + `stop`) **before** asking the owner anything. A
   run left unbounded ran ~97 game days instead of ~10 while a question sat unanswered.
   `time query daytime` is already mod 24000; use `gametime / 24000` for the day.
7. **Never compile while `runServer` is live.** It runs from `build/classes`, so
   `compileJava` swaps classes under the running JVM, and everything after is suspect.
   `save-all flush` + `stop` first.
8. **Read state offline after `save-all flush`**: region files and `.dat` files with a small
   Python NBT reader, not by hammering the server. A block census: `forceload add` areas
   (≤ 256 chunks each, a few at a time), save, stop, read the region palettes. One sample
   per biome gives false misses; search the whole saved world before calling a block
   missing.
9. **Harness scripts live in `run/harness/`** (gitignored), not the session scratchpad, so
   the next session finds them. The method goes in `state.md`.
10. **Unattended overnight runs:** Windows Update restarts the PC, and session timers die
    with the session. Ask the owner to pause updates; copy `run/logs/debug.log` before the
    next server start rotates it; `Get-WinEvent -FilterHashtable @{LogName='System';
    Id=1074,6006,6008,41}` tells you what happened.
11. **Stopping:** stop the sampler first, then kill leftover harness processes **by verified
    PID** (a `TaskStop` on a background `bash` leaves its python child alive: a second
    RCON client waiting to happen), then `save-all flush` + `stop`.

## Part D: Release gate (rungs 5 and 6), mandatory before any release push

**A push that can publish a release** is a version bump, a push to a branch whose version
has no release yet, or (in repos whose CI releases on `feat`/`fix` pushes) **any such
push**. When unsure, treat it as one. On **every band and loader being released**:

1. **Client world check**: a Fabric client GameTest that creates a **new** singleplayer
   world through the client (`context.worldBuilder().create()`), joins it, and asserts the
   mod is running in it, then logs a PASS marker. The wrapper script's verdict is the
   marker, **not Gradle's exit code**: exit 0 with no marker ran nothing. Vanilla
   `--quickPlaySingleplayer` can't stand in. It shows an error screen for a missing world,
   never creates one. Reference: `../mcMMO-Singleplayer/scripts/client-world-check.sh`.
2. **Built jar on a real server**: install the loader's real server into an empty work
   dir, add the **built** jar (and its real dependencies, checksummed), boot it, fingerprint
   the world, `stop`, require exit 0 and no ERROR line. `runServer` runs the source tree
   and can never prove the artifact. Gotchas that bite here: the player-facing `fabric-api`
   jar has no GameTest module; Forge loads a mod's data only with a `pack.mcmeta`; Forge's
   Maven 403s Python's default user agent; a crashed NeoForge server can hang without
   exiting (give up after silence). References: `../the-continent/tools/server_smoke.py`,
   `../mcMMO-Singleplayer/scripts/boot-check.sh`.
3. **Record both results** (band, loader, exit codes, date) in `TODO.md` in the release's
   section. A skip that is written down is still a skip. **Exit 1 blocks the push. Exit 2
   blocks the push.** If the harness truly can't run, the owner launches the built jar by
   hand (install, Create New World, join, exercise the feature) and that is recorded instead.

## Part E: The owner's instance (rung 7)

- **Installing a jar there writes outside the repo.** It's a `guard-destructive` action:
  resolve the instance's `mods/` path, list what's there, remove only this mod's old jar
  by exact name, say what you're replacing. Never glob-delete in the owner's `mods/`.
- After installing, prove it loaded: the jar is in `mods/`, and the instance's
  `latest.log` names the mod. A jar has vanished from `mods/` between sessions twice in
  real-villages, and observations from a game without the mod measure nothing.
- **An owner report that contradicts green tests: read the instance before the code.** Its
  `mods/` list and `latest.log`. Seen so far: another mod showing descriptions the owner
  blamed on ours (enchdesc), a slot mod hiding the elytra and cancelling the vanilla tick
  ours hooked (Elytra Slot), an old datapack override of `dimension_type` freezing the sky
  (extended-build-limit). Scan every jar for `data/minecraft/` overrides when the symptom
  is vanilla-shaped.
- The owner's instances are Prism (`%APPDATA%/PrismLauncher/instances/<name>/minecraft/`).
  Ask which instance; don't guess.

## Rules

- **Not done until it ran on the dev server** (rung 3 at least), for any change the
  ladder says reaches it. Record the run in `TODO.md`.
- **Before any release push, rungs 5 and 6 on every band and loader**, recorded. No green
  suite, CI run or structural gate substitutes. No exception for a "small" change: #20 was
  one missing line in one data file.
- **Exit 2 is never a pass.** Neither is Gradle's exit 0 without the harness's own marker.
- **Never kill java by name.** Find by command line, kill a verified PID, and only your own.
- **Never compile under a live server.** Never two RCON clients. Never an unbounded run.
- **Never touch the owner's save or instance in place.** Copy saves; gate installs.
- **Fingerprint the effect.** A check that passes with the change reverted proved nothing.
