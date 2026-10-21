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

## Commitment: lock the mod setup behind a 24-hour unlock (Tier 2, planned 2026-10-21, revised 2026-10-21)

**Goal:** On every 26.x Minecraft version, for Fabric, Quilt, NeoForge and Forge, a player runs
`/commit` → `/commit confirm` in a world to lock the setup it was played with. From then on, that
world refuses to open if the setup changes, and shows what changed. The only way out is a 24-hour
wait, started with `/unlock` → `/unlock confirm` or from a button on the refusal screen. Deleting
the mod makes the world unplayable, not free.

**Owner rulings, 2026-10-21:** inconvenient to tamper with, not impossible ("the average user"
won't get past it). **Don't touch `level.dat`** and **don't spend disk on backups**. Lock per
world. Unlocking needs internet time. Only resource packs are locked; video, shader and audio
settings stay free. `/commit` gets a confirm too. Dedicated servers are in. The lock lifts by
itself after 24 h. Commitment's own updates don't count as a change.

**Owner rulings, 2026-10-21 (session 2):** push each green stage to `origin/main`. Stop and report
only on a 0.2 no-go or a fact that changes the design. Once the 24 h have passed, the lock still
stays on until internet time confirms it (fail closed, and the screen says why). MIT license.

**Approach:** **The mod stops the launch.** Before the world loads, it compares the setup with
the committed one. On a mismatch the player gets the LockedScreen: what changed, Back, and the
optional 24-hour unlock button. A dedicated server logs the changes and stops. No world loads
and nothing else is involved. Everything below is the **fallback for a deleted mod**, when
none of Commitment's code runs at launch. It's built from three small files, each able to
rebuild the others, and none of them belongs to vanilla. (The first plan's sealed `level.dat` was dropped: it put every committed world at risk
from a single bug and needed a backup to be safe.)
1. **Lock record**, `<world>/data/commitment.dat`: the committed manifest, the timer state and a
   random lock id. Signed with an HMAC (key: a constant in the jar plus the lock id), so a
   hand-edited record counts as tampered.
2. **Mirror** of the record outside the instance: `%LOCALAPPDATA%\Commitment\locks\<lock id>.dat`
   (`~/Library/Application Support/Commitment` on macOS, `$XDG_DATA_HOME/commitment` on Linux).
   Deleting the world's record, or the whole `config/` folder, doesn't unlock anything.
3. **Guard datapack**, `<world>/datapacks/commitment_guard.zip`: plain vanilla functions, so it
   runs **even when the mod is gone**. The mod sets a heartbeat in command storage every tick.
   Every 2 s (game ticks, so lag can't fake it) the pack checks for that heartbeat. If it's
   missing, every player gets blindness, max slowness, max mining fatigue and full resistance,
   reapplied with 3 s durations. The title reads "This world is committed. Commitment is missing:
   reinstall it to play", and the log gets `Commitment guard: Commitment is missing` once. Nothing
   of the player's changes: the effects run out by themselves 3 s after the mod comes back.

With the mod present, any surviving piece restores the missing ones at server start. If the
datapack is disabled, the mod re-enables it. Layout copies `../the-continent`: a loader-free
`common`, plus `fabric`/`neoforge`/`forge` branches × Stonecutter 26.x nodes. Quilt runs the
Fabric jar.

Rejected: a sealed `level.dat` (above; superseded, decisions.md 2026-10-21). A guard pack that
fails to load on purpose without the mod, so vanilla stops the launch: vanilla's "failed to load
data packs" screen can't show our message or an unlock button, and its Safe Mode button loads the
world without the pack in one click, on every launch (0.2: Safe Mode doesn't add the pack to the
disabled list, but a pack built to fail fails again on every launch). A lock file only in
`config/` (deleted with the mod in one step). A mod-registered dimension that vanilla can't load
without the mod: vanilla's "safe mode" prompt is one click and may drop the dimension's data.
Gamemode spectator as the penalty: the old gamemode would need storing and restoring, and hardcore
treats spectator as death. Effects need no state. OS-level guards (admin rights, behaves like
malware). A separate Quilt jar and Architectury (the-continent decisions 2026-10-20).

### Threat model (this goes in the README too)

The player owns every file, so this is friction, not security. Each row is a Stage D test.

| Bypass | Result |
|---|---|
| Delete or disable Commitment | The world opens, but the guard pack blinds and freezes the player with "reinstall Commitment". Nothing gets damaged |
| Reinstall Commitment | The lock is back, and the timer state too |
| Delete or edit `config/` | Nothing changes: no lock state lives there |
| Delete the guard datapack, mod present | The mod rebuilds it from the record at the next server start |
| Delete the world's lock record, mod present | Restored from the mirror (matched by the lock id in the guard pack, or by seed + folder name) |
| Hand-edit the lock record | HMAC fails, so it's treated as tampered: locked, and the only way out is the 24 h timer |
| Move the clock forward | Still locked: the timer uses internet time |
| `/datapack disable` with cheats on (e.g. Open to LAN) | Works only without the mod. With it, the pack is re-enabled at server start |
| `/tick freeze` with cheats on, mod deleted | The guard pauses, but so does the world (0.2). Same cheats bar as the row above |
| **Still works, and the README says so:** delete the mod **and** the guard pack from the world folder; edit both records and the mirror; decompile the jar | Takes knowing where the world folder is and what to delete. That's the bar the owner set |

### Behaviour spec

- **`/commit`** prints what will be locked ("42 mods, 3 resource packs, 118 config files"), says
  that changing any of it later takes a 24-hour unlock, and asks for `/commit confirm`.
  **`/commit confirm`** (valid for 60 s) writes the three pieces and logs/prints
  `Commitment: world committed (<counts>)`. **`/commit status`** shows the state and any timer.
- **`/unlock`** prints the 24-hour message and asks for `/unlock confirm`. **`/unlock confirm`**
  (valid for 60 s) checks internet time and starts the timer: `Commitment: unlock available at
  <time> (24h)`. With no internet time it refuses and says so. **`/unlock cancel`** stops a
  pending timer. Only the singleplayer host can run these (op 4 or the console on a dedicated
  server). Cheats don't need to be on.
- **On world open:** scan the setup and compare it with the committed manifest. On a match the
  world loads. On a mismatch the client shows the **LockedScreen**: what changed (added, removed
  or changed, capped at N lines with "and K more"), **Back**, and the optional **Start 24-hour
  unlock** (with its own confirm dialog, internet time required). With a timer running it shows
  the countdown and **Cancel unlock**. A dedicated server logs the diff and stops before loading
  the world, with a non-zero exit.
- **After 24 h (internet time):** the lock lifts on the next world open, on its own. The mod
  deletes its three files (only the ones that carry its marker and this lock id), the world loads,
  and the player sees `Commitment: lock lifted, run /commit to lock again`.
- **What's locked:** the loader and its version, the Minecraft version, every mod (id, version and
  the SHA-256 of its jar; Commitment's own version and jar are left out), `config/` and
  `defaultconfigs/` by content, the enabled resource packs in order with each pack's content hash,
  the world's `datapacks/` (except the guard), and **only** the `resourcePacks` /
  `incompatibleResourcePacks` keys of `options.txt`.
- **Never locked:** every other `options.txt` setting (video, audio, controls, keybinds), shader
  packs (`shaderpacks/`, chosen pack and its options), and a built-in list of video, shader and
  audio mods' config files (the list from 0.4; grown in Stage E). On
  top of that comes the ignore list of files mods rewrite on every boot.

**Not doing (v1):** writing to `level.dat` or any other vanilla file (vanilla records the guard
pack as enabled in its own save, the same way it does for any datapack); backups; blocking the
resource-pack or mod-config screens mid-session (caught at the next world open); a whole-instance
lock; a server enforcing *clients'* mods; hiding the key from someone who decompiles the jar;
OS-level protection.

**Blast radius:** Planning: none, additive only. **Product:** additive files only (the record,
the mirror and the guard pack, a few KB each). Nothing the player owns is overwritten, so no
backup is needed. The penalty is effects that expire within 3 s. The one destructive path is
**lifting the lock**, which deletes Commitment's own three files. B.6 guards it: the path must
resolve inside the world dir or the mirror dir, the file must carry Commitment's marker and this
lock id, and the deletion is logged with each path. Each guard has a test. During development,
only `run/` worlds and disposable copies get committed, never the owner's saves or instance in
place. Clock tests use the injected clock.

**Proof:**
1. JUnit (common): scanner and exclusions, diff, record + HMAC + mirror reconciliation, timer
   with a fake clock, guard-pack generator (golden files), the lift-lock delete guards.
2. Server GameTests on every node × loader: the command state machine and its messages, the
   permission checks, the three files written, a deleted guard pack rebuilt, **the mod present →
   no effect on a test player after 200 ticks**, and the lock lifting.
3. Dev-server smoke on every loader: `scripts/checks/commit-flow.txt` (`@expect Commitment: world
   committed`, `@expect unlock available at`) and `scripts/checks/locked-boot.txt` (a config file
   changed between boots → `@expect Commitment: setup changed .* refusing to load`).
4. **The guard without the mod:** a vanilla server (no loader, no Commitment) on a committed copy
   of the dev world → `@expect Commitment guard: Commitment is missing`, on every 26.x node.
5. Tamper matrix (Stage D) on disposable copies, every row recorded here.
6. Fabric client GameTest on every node: create a world, `/commit confirm`, quit, enable a test
   resource pack, reopen → the LockedScreen lists that pack; disable it → the world opens. Change
   the render distance → still opens.
7. Built jar on a real Fabric, Quilt, NeoForge and Forge server per node: boots, and `locked-boot`
   reproduces.
8. A copy of the owner's instance (Stage E): three restarts with no changes → zero diffs.

### Stage 0: facts before code (no source)

- [x] 0.1 Every 26.x release from `piston-meta` `version_manifest_v2.json`. For each one, the
      Fabric Loader + API, Quilt Loader, NeoForge and Forge builds from official metadata, dated.
      Nodes and per-loader ranges written here (start from the-continent's split: 26.1–26.1.2 on
      26.1.2, 26.2, 26.3; NeoForge/Forge 26.1.2 only). Check for 26.4.
      **Result, fetched 2026-10-21:** releases 26.1, 26.1.1, 26.1.2, 26.2, 26.3. 26.4 is still
      only snapshots (snapshot-5 came out 2026-10-20), so no node for it. Nodes: **26.1.2, 26.2,
      26.3**. Fabric's 26.1.2 jar runs on `>=26.1 <26.2`; the NeoForge and Forge 26.1 jars run on
      26.1.2 only (NeoForge 26.1.0 and 26.1.1 have betas only). Pins: Fabric Loader 0.19.5 (newest
      stable; 0.20.x isn't stable), Fabric API 0.155.3+26.1.2 / 0.161.0+26.2 / 0.164.0+26.3;
      NeoForge 26.1.2.122 / 26.2.0.93 / 26.3.0.95, MDG 2.0.148; Forge (promotions, recommended)
      64.1.5 / 65.1.5 / 66.1.0, ForgeGradle 7.0.40; Stonecutter 0.9.8; Quilt Loader
      0.31.0-beta.4 (betas only). Loom: 1.19.2 is out, but the scaffold keeps the-continent's
      proven Loom 1.18.4 + Gradle 9.7.1 (decisions.md 2026-10-21).
      Data pack format per `version.json`: 26.1/26.1.1/26.1.2 = 101.1, 26.2 = 107.1, 26.3 = 121.0.
- [x] 0.2 **Go/no-go for the guard pack**, from each node's jar: is a zip dropped into
      `<world>/datapacks/` enabled on the next load; when `#minecraft:load` runs relative to the
      first tick; `schedule` counts game ticks; the function permission level in singleplayer
      and the default `function-permission-level` on a dedicated server; the permission levels
      of `effect`, `title`, `say` and `data`; one `pack.mcmeta` `min_format`/`max_format` that
      loads on every 26.x node (data-pack majors 101, 107, 121 per the-continent gotchas;
      re-read from `version.json`); what vanilla does with a world pack outside its range; what
      the failed-data-pack screen offers and whether Safe Mode leaves world packs disabled (if
      it doesn't, a launch-time stop comes back into play as the fallback). If no
      single pack loads everywhere, generate it per version at commit time and re-write it when
      the world opens on another version.
      **Result: GO.** Decompiled with Vineflower 1.12.0 from the cached merged jars of 26.1,
      26.1.2, 26.2 and 26.3, plus the Forge 66.0.13 and NeoForge 26.3.0.95 patched sources.
  - [x] Auto-enable: `MinecraftServer.configurePackRepository` (identical on all four) adds every
        available pack that isn't on the disabled list and whose source `shouldAddAutomatically()`.
        `PackSource.WORLD` returns true. Log line: `Found new data pack file/<name>, loading it
        automatically`. Forge and NeoForge only add their mod packs around that loop.
  - [x] One `pack.mcmeta` for every node: `{"pack":{"description":…,"min_format":101,"max_format":121}}`.
        `PackFormat.IntermediaryFormat.validate` is the same on all four. With `min_format` above
        81 (data's `lastPreMinorVersion`), `pack_format`/`supported_formats` aren't needed, and
        `supported_formats` there is an error. The compatibility check only labels the pack
        "(incompatible)" in lists. It never stops an enabled pack from loading, so a future 26.4
        still loads it.
  - [x] Timing: `ServerFunctionManager.tick()` runs `#minecraft:load` on the first tick after
        every (re)load, then `#minecraft:tick` every tick, **only while
        `tickRateManager().runsNormally()`**, so `/tick freeze` pauses the guard (cheats only).
        `schedule` counts game ticks.
  - [x] Permissions: tag functions run as `getGameLoopSender()`, which is always
        `LevelBasedPermissionSet.GAMEMASTER`, whatever `function-permission-level` says.
        `effect`, `title`, `data`, `schedule` and `say` all need `LEVEL_GAMEMASTERS`, so all of
        them run in singleplayer without cheats and on a dedicated server.
  - [x] Safe Mode: `DatapackLoadFailureScreen` offers Back and Safe Mode. Safe Mode selects
        `vanilla` (plus mod packs on Forge/NeoForge) and saves the config with
        `disableInactive=false`, so the disabled list is **empty**: the guard comes back
        automatically on the next normal launch. It's still a one-click bypass on every launch for
        a pack built to fail, so that design stays rejected. The server has `--safeMode` too.
- [x] 0.3 **Client gate:** `WorldOpenFlows.openWorld(String levelId, Runnable onCancel)`, at HEAD,
      before `createWorldAccess` takes the session lock. Same signature on all nodes. Back →
      `onCancel`. `minecraft.gui.setScreen` arrived in **26.2** (26.1.x uses
      `minecraft.setScreen`). Its only callers, on 26.1 and 26.3 alike, are the world list
      (`WorldSelectionList$WorldListEntry`), the title screen's demo world and `QuickPlay`. **Server gate:**
      `net.minecraft.server.Main.main`, right after `levelStorageSource.validateAndCreateAccess(levelName)`
      and before the level data and datapacks load. Vanilla bails out there with log + `return`.
      For our non-zero exit we need to close the access first. Edge case for Stage D: "Re-Create"
      world copies `datapacks/`, so the new world gets the guard pack without a record.
- [x] 0.4 Never-locked list, from each mod's newest 26.3 jar on Modrinth (2026-10-21): Sodium
      0.9.3-beta.1 `sodium-options.json`, `sodium-mixins.properties`, `sodium-fingerprint.json`; Iris
      1.11.7 `iris.properties`, `iris-excluded.json`, plus the `shaderpacks/` dir; Distant Horizons
      3.3.4 `DistantHorizons.toml`; ImmediatelyFast 1.17.1 `immediatelyfast.json`; EntityCulling
      1.11.3 `entityculling.json`; Dynamic FPS 3.11.10 `dynamic_fps*` (the name is built at runtime,
      so confirm it in Stage E); Sound Physics Remastered 1.5.2 `sound_physics_remastered/`;
      Presence Footsteps 1.14.0 `presencefootsteps/`; AmbientSounds 6.3.11 `ambientsounds/`.
      **Embeddium and Oculus have no 26.x build**, so they're left off.

### Stage A: scaffold (copy `../the-continent`, commit when green)

- [ ] A.1 `settings.gradle`, `stonecutter.gradle`, `gradle.properties`: branches `common`,
      `fabric`, `neoforge`, `forge` × the nodes from 0.1, pins from 0.1 with dates, mod id
      `commitment`, package `com.wulfic.commitment`.
- [ ] A.2 One entrypoint per loader logging `Commitment <ver> loaded on <loader> <mc>`. Dev
      server per `dev-server` Part A: its own unused port (check `netstat` and record it in
      `state.md`), `run/` dirs, EULA task, `scripts/dev-server-smoke.sh` from `../enchanting-2`,
      and a real-server smoke tool from `../the-continent/tools/server_smoke.py` (needed for
      Proof 4 and 7).
- [ ] A.3 Tests: no loader imports in common (the scan test), `ReadmeBandsTest`, an empty Fabric
      client GameTest that creates and joins a new world. The README's band statement.
- [ ] A.4 Every node builds, with test counts read from the log. `scripts/checks/boot.txt` smoke
      (`@expect Commitment .* loaded`) on each loader. Record here and commit.

### Stage B: core in `common`, pure Java, JUnit first (commit per step)

- [ ] B.1 `Manifest` + `ManifestScanner` over a `GameEnvironment` seam (the loader's mod list and
      instance paths): content hashes, semantic compare for json/toml/properties, the never-locked
      list and the ignore list. Tests: add, remove and change one item of each kind → exactly that
      diff; a reordered json → no diff; a change to render distance, volume, a shader pack or
      `sodium-options.json` → no diff; Commitment's own jar left out.
- [ ] B.2 `ManifestDiff` → readable lines, capped. Test the cap and the order.
- [ ] B.3 `LockRecord` (NBT, HMAC-signed) + `LockStore`: world record, OS mirror, and the
      reconciliation rules (one valid copy restores the rest; tampered or missing while the guard
      pack exists → locked, and only the timer gets out; a mirror dir that can't be written →
      logged, world record only). Tests: every present/missing/tampered combination of the three.
- [ ] B.4 `LockState` + `UnlockTimer` with an injected `TrustedClock`: uncommitted → committed →
      pending(requestedAt) → lifted, and cancel. Tests: 23:59:59 locked and 24:00:00 lifted;
      system clock +48 h with internet time +1 h → locked; no internet time → can't start and
      can't finish, with the message.
- [ ] B.5 `TrustedTime`: HTTPS `Date` headers from 2+ hosts that must agree within 5 min, short
      timeouts, every failure logged. Queried only at `/unlock confirm` and at world open while a
      timer runs. Tests against a local stub server. README privacy note.
- [ ] B.6 `GuardPack` generator + the lift-lock deleter (**the destructive path**): golden-file
      test of the generated pack; deletes only files inside the world or mirror dir that carry
      the marker and this lock id. Tests: a look-alike pack without the marker → refused, still on
      disk; a path that resolves outside → refused; a wrong lock id → refused. Mutation-check each
      guard.

### Stage C: hooks, per node and loader (commit per step)

- [ ] C.1 Heartbeat (sets `commitment:guard alive` every tick while the setup matches), and
      self-heal at server start (rebuild a missing piece, re-enable a disabled pack). GameTests
      from Proof 2.
- [ ] C.2 World-open gate: scan, compare; a mismatch → the LockedScreen (client) or log and stop
      (server); a timer that has run out → lift.
- [ ] C.3 Commands per the spec, registered through the platform seam. Server GameTests: every
      transition, each message, the 60 s confirm expiry, a non-host refused.
- [ ] C.4 The LockedScreen per node: diff, Back, start-unlock with confirm, countdown, cancel.
      It logs `Commitment: locked screen (<n> changes)`. Fabric client GameTest from Proof 6.
      NeoForge and Forge have no client GameTest, so `:<node>:runClient` on a disposable world,
      with that line read from the log and recorded here.
- [ ] C.5 Dev-server smokes `commit-flow.txt` and `locked-boot.txt` on each loader, and Proof 4
      on a vanilla server. Revert checks: comment out the gate and watch `locked-boot` go red;
      remove the heartbeat and watch the "no effect" GameTest go red; restore both. Record here.

### Stage D: tamper matrix (disposable world copies only)

- [ ] D.1 Every row of the threat-model table on Fabric, NeoForge and Forge (Quilt with the
      Fabric jar): the action, the expected result, what happened. Record here.

### Stage E: a real modpack (rung 7, on a copy)

- [ ] E.1 Copy the owner's instance (never in place), `/commit`, restart 3× with no changes →
      zero diffs. Every file that changes on its own goes into the ignore list, with a test.
      Change video, audio and shader settings → still zero diffs; anything that trips goes on
      the never-locked list.
- [ ] E.2 Time the scan on that pack. If it adds more than 2 s to world open, optimize, and
      measure before and after.

### Stage F: release (the release gate; push only on the owner's go)

- [ ] F.1 README: what it does, the threat model and its honest limits, the privacy note (the
      internet time check, the OS mirror file), the band/loader table, how to get out (`/unlock`).
      `CHANGELOG.md`.
- [ ] F.2 Rungs 5 and 6 on every node × loader (Quilt boots the Fabric jar), recorded here.

**Rollback:** Planning: before the commit, `git checkout HEAD -- TODO.md .agent/memory/`;
after it, `git revert <sha>`. Each stage is its own commits, so `git revert <sha>..<sha>` takes
a stage back. For a world committed during development: `/unlock` and wait, or delete
`<world>/data/commitment.dat`, `<world>/datapacks/commitment_guard.zip` and the mirror's
`<lock id>.dat`. Those are the only files Commitment ever writes, and nothing vanilla is changed.

## Done

_Move completed tasks here, or delete them once they're in the git history._

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
