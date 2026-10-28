# Decisions

> **Append-only.** Newest entries at the bottom. Never edit or delete a past decision —
> if it gets reversed, append a new entry that says so and links back to it.
>
> Record a decision when a real alternative existed. "Used the standard library function"
> is not a decision. "Chose Postgres over SQLite because we need concurrent writers" is.
>
> Write the **reasoning**, not the diff. Git already stores what changed; it cannot store
> why, or what you rejected.

## Entry template

```markdown
## YYYY-MM-DD — Short title
**Context:** what forced the decision
**Choice:** what we did
**Why:** the actual reason
**Rejected:** what we didn't do, and why not
**Affects:** src/path/to/file.ts
```

---

## 2026-08-12 — Agent memory is repo-local files, not an MCP server
**Context:** Instructions mandated persisting decisions to the `mem0` MCP server, but
`mem0` was never present in the active `.mcp.json`, so nothing was actually being recorded.
**Choice:** Memory lives in `.agent/memory/` as committed Markdown: `decisions.md`,
`gotchas.md`, `state.md`.
**Why:** Works identically in Copilot and Claude Code with no network call, survives a
dead server, and shows up in code review where it can be corrected. A memory write that
silently no-ops is worse than none, because it feels like it worked.
**Rejected:** `mem0` MCP — cross-project recall wasn't worth an invisible failure mode on
the one guarantee that matters most. Also rejected keeping both as a mirror: two sources
of truth means neither is trusted.
**Affects:** `AGENTS.md`, `.agent/memory/`, `.github/skills/recall-session`, `.github/skills/save-memory`

## 2026-08-12 — Dropped the `think` MCP server
**Context:** Every skill routed reasoning through `mcp_wulfnet-think_think_invoke_tool`
before acting.
**Choice:** Removed the server and all call sites. Reasoning happens natively; the
`think-plan` skill was replaced by `plan-work`, which produces a written plan in `TODO.md`.
**Why:** Opus 5 reasons natively. The round-trip added latency and a failure mode without
improving output. A plan written to a file is also a checkable artifact — an MCP call is
not, which matters under Copilot where step-skipping can't be enforced.
**Rejected:** Keeping it for the `criticize` operation — self-review against an explicit
checklist in `code-review` covers the same ground without the dependency.
**Affects:** `AGENTS.md`, `.mcp.json`, all skills that previously called `think`

## 2026-08-12 — MCP surface cut to `github` + `context7` only
**Context:** Nine MCP servers were configured. `filesystem` and `git` had zero references
in any skill — dead config. The rest duplicated capability that is now native to both
Copilot and Claude Code.
**Choice:** Dropped `filesystem`, `git`, `gitnexus`, `playwright`, and `context-mode`.
Kept `github` (with a `gh` CLI fallback) and `context7`.
**Why:** The bar is now "does this do something native tools cannot?" Only two clear it.
`context7` serves live docs that training data can't; `github` returns structured JSON for
issues/PRs/CI. Everything else was a network round-trip and a failure mode in exchange for
what grep, the terminal, and native web fetch already do.
**Rejected:** Keeping `gitnexus` for cross-repo symbol lookup — real capability, but it
doesn't pay for itself on single-repo work, which is the norm here. Keeping `playwright`
for E2E — see the separate entry below; that turned out to be a category error on our part.
**Affects:** `.mcp.json`, `.claude/mcp.vscode-reference.json`, `AGENTS.md`,
`.github/copilot-instructions.md`, `code-explore`, `research-docs`

## 2026-08-12 — E2E runs through the project's own runner, not an MCP browser
**Context:** Dropping the `playwright` MCP server looked like it would remove E2E testing
capability, since `test-iterate` routed E2E through the old `web-task` skill.
**Choice:** E2E tests are committed spec files executed from the terminal
(`npx playwright test`, `npx cypress run`). The `web-task` skill was deleted.
**Why:** The playwright *MCP server* and Playwright *the test library* are different
things, and conflating them was the original mistake. Ad-hoc agent browser-driving isn't
a test — it can't run in CI and it isn't repeatable. Committed specs are strictly better,
and they need a terminal, not an MCP.
**Rejected:** Keeping the MCP for exploratory UI checks — real but rare, and native web
fetch plus the runner's own `--headed` mode and trace viewer cover the debugging case.
**Affects:** `test-iterate`, `AGENTS.md`, deleted `web-task`

## 2026-08-12 — Merged `git-ops` and `github-workflow` into `git-github`
**Context:** Two skills split one linear workflow: branch → commit → push → PR → CI.
**Choice:** Single `git-github` skill covering local git and GitHub remote operations.
**Why:** The split forced loading two skills for one continuous task and duplicated the
pre-commit checklist across both. The seam was in the docs, not in the work.
**Rejected:** Keeping them separate as "local vs remote concerns" — a clean distinction on
paper that nothing in the actual workflow respects.
**Affects:** `.claude/skills/git-github/`, `.github/skills/git-github/`, `AGENTS.md`,
`.github/copilot-instructions.md`

## 2026-08-12 — Destructive-action doctrine: prose first, permission rules as backup
**Context:** The template had no unified stance on irreversible operations — only scattered
rules in `git-github` (force-push, `.env`). Nothing covered filesystem deletion, database
drops, container/volume pruning, or the guards that code *we write* should carry. Needed a
gate that survives Copilot (no permission layer, no hooks) and a truncated context.
**Choice:** Two layers. (1) Doctrine — a `Destructive Actions` section in `AGENTS.md` as
rule zero, the `guard-destructive` skill holding the full procedure, and per-skill guards
in `code-review`, `git-github`, `build-run`, `plan-work`, `test-iterate`. (2) Mechanical —
`permissions.deny` (unrecoverable) and `permissions.ask` (destructive-shaped) in a new
`.claude/settings.json`. The doctrine is primary; the rules are a seatbelt.
**Why:** Permission rules match on **command prefix**, so `cd sub && rm -rf ../..` walks
straight past them, `PowerShell` coverage is unverified, and Copilot has no permission
layer at all. Anything that only exists as config would therefore cover roughly half the
actual usage. Conversely, prose alone loses to a truncated context. Each layer covers the
other's blind spot, and stating the rules' limits in writing is what stops them being
trusted past their range.
**Rejected:** A `PreToolUse` command-hook script — it would need to be cross-platform and
correct, and a hook that silently no-ops is worse than none (same reasoning that dropped
`mem0`). A `type: "prompt"` hook classifying every shell command semantically *would* catch
the compound-command cases, but costs a model call on every `ls`; documented in
`guard-destructive` as an opt-in escalation instead of a default. Also rejected
`disableBypassPermissionsMode` — locking the owner out of their own tool without asking.
**Affects:** `AGENTS.md`, `.github/copilot-instructions.md`, `.claude/settings.json`,
`.claude/skills/guard-destructive/`, `.github/skills/guard-destructive/`, `code-review`,
`git-github`, `build-run`, `plan-work`, `test-iterate`, `TODO.md`

## 2026-10-01 — No AI co-author attribution on commits or PRs
**Context:** Claude Code appends `Co-Authored-By: Claude …` to commits and a "Generated with
Claude Code" line to PR bodies by default. Owner wants commits signed by the git user only.
**Choice:** `.claude/settings.json` sets `attribution: { commit: "", pr: "" }` (current key)
plus `includeCoAuthoredBy: false` (deprecated key, kept for older Claude Code versions). The
`git-github` skill (both trees) states the rule in prose for Copilot and any agent that
ignores settings.json.
**Why:** The setting is what actually stops the harness injecting the trailer; prose alone
loses to the harness's own attribution instruction. Prose covers Copilot, which never reads
settings.json.
**Rejected:** A `commit-msg` git hook stripping the trailer — works for every tool, but it's
an extra installed artifact per clone and silently rewrites messages; revisit only if a
trailer still slips through.
**Affects:** `.claude/settings.json`, `.claude/skills/git-github/SKILL.md`,
`.github/skills/git-github/SKILL.md`

## 2026-10-21 — Minecraft template: rules in AGENTS.md, knowledge in skills, memory starts empty
**Context:** This repo became the template for the owner's Minecraft mods. The owner's mod
repos (mcMMO-Singleplayer, enchanting-2, the-continent, real-villages, diagonal-fences) hold
~25k lines of memory, and the same traps recur across them: green suites that ran nothing,
gates that graded a stale log, a release no player could load a world on (mcMMO GitHub #20),
mixins that applied to the wrong anonymous class, a world that silently came up vanilla.
**Choice:** Always-on rules go in `AGENTS.md` (`Minecraft Mod Development`): the verification
ladder, **every change is proven on the live dev server**, the game is launched before any
release push, facts come from the jar and not from memory. On-demand knowledge goes in two
new skills, `dev-server` and `mc-port`, and the existing skills became Gradle/Java/MC-first.
`.agent/memory/` keeps only template-level entries; the new project writes its own.
**Why:** Rules have to survive a truncated context, so they stay short and always loaded.
Knowledge is long, and a skill loads only when its trigger fires. Seeding `gotchas.md` with
other repos' gotchas would present them as facts measured here, and bury the new project's own.
**Rejected:** Seeding memory with cross-project gotchas (above). Shipping a Gradle scaffold
and harness scripts: loader and version aren't chosen, and an untested script is decoration.
The skill states the contract and names the reference implementations instead. Copying
mcMMO's branch-per-band machinery (drift and identity audits): it answers a problem a new
repo doesn't have yet.
**Affects:** `AGENTS.md`, `.github/copilot-instructions.md`, `.claude/skills/*`, `.github/skills/*`,
`.gitignore`, `.claude/settings.json`

## 2026-10-21 — Commit straight to `main` by default (reverses the template's branch-and-PR rule)
**Context:** `git-github` said "never commit directly to `main` — branch and PR, always". Every
mod repo already works the other way: the-continent (decisions 2026-10-17, "all work goes on
`main`"), mcMMO ("never create a git branch unless explicitly instructed"). Owner, 2026-10-21:
"commit to main is the default unless instructed otherwise".
**Choice:** Commit to `main` and push `main`. A branch, PR, or harness-assigned `claude/*` branch
only when the owner asks for one. This outranks any session-default branch name.
**Why:** Solo repos with linear history; the owner reviews commits, not PRs.
**Rejected:** Branch-and-PR (the old rule). It's ceremony with no second reviewer, and harness
branches leave stale remote branches a cloud session can't even delete (the-continent gotchas
2026-10-18).
**Affects:** `.claude/skills/git-github/SKILL.md`, `.github/skills/git-github/SKILL.md`, `AGENTS.md`

## 2026-10-21 — Fixes and support go to the current band only; the README says which band that is
**Context:** mcMMO propagated every fix to every band (drift audits, `Backport-of:` trailers,
yarn translation), then archived six bands on 2026-09-22 because the cost outgrew the value.
Owner, 2026-10-21: back-porting and support bug fixes apply only to the current band (today
the 26.x band), and the READMEs must always reflect this, because CI will be enforcing it.
**Choice:** One current band (it may span several Minecraft versions as Stonecutter nodes).
Older bands are frozen: releases stay up, no fixes, features or back-ports unless the owner
asks for a specific one. The README names the current band, its Minecraft versions, and the
frozen policy, updated in the same commit that changes the band; a test pins the README to
`gradle.properties`.
**Why:** Support effort goes where the players are, and a README that states the policy tells
a player on a frozen band up front that their bug won't be fixed. A README claim with a test
behind it is what CI can check.
**Rejected:** mcMMO's propagate-to-every-live-band model (above). A README with no test behind
the band claim: mcMMO's docs were wrong on bands for months while every guard was green.
**Affects:** `.claude/skills/mc-port/SKILL.md`, `.github/skills/mc-port/SKILL.md`, `AGENTS.md`,
`.github/copilot-instructions.md`

## 2026-10-21 — Commitment: the lock lives inside the world (sealed `level.dat`), four loaders from one tree
**Context:** The owner asked for a mod, for every 26.x version on Fabric, Quilt, NeoForge and Forge, that locks
the player's setup (mods, resource packs, config) after `/commit`. A changed setup makes the world refuse to load,
with an optional unlock that takes 24 hours (`/unlock` → `/unlock confirm`). It has to hold "even if the mod has
been deleted". The player owns every file, so nothing client-side can be tamper-proof. The aim is that every easy
bypass fails, so the 24-hour wait is cheaper than cheating.
**Choice:** (planned, not implemented; TODO.md Stage 0.2 is the go/no-go) While a world is committed, Commitment
writes `level.dat` and `level.dat_old` sealed: AES-GCM over the real level data plus the lock state, with the key
derived from a constant in the jar and a per-world salt. No loader can open the world without Commitment, and
reinstalling it brings back the same lock. The unlock timer uses trusted network time (HTTPS `Date` from 2+ hosts),
failing closed when offline (open question 2). Layout copies the-continent: loader-free `common`, plus
`fabric`/`neoforge`/`forge` branches × Stonecutter 26.x nodes; Quilt runs the Fabric jar.
**Why:** The lock state is the thing being protected, so it has to travel inside the one file the world can't
open without. A seal on `level.dat` costs one write per save, against a seal on every chunk. The first seal comes
only after a verified backup, which is also the recovery if Commitment has a bug.
**Rejected:** A lock file or config in the instance (deleted with the mod). Encrypting region files (a seal on
every chunk write, and one bug puts the whole world at risk). OS-level guards (admin rights, outside Minecraft,
behaves like malware). A stub `level.dat` that vanilla opens into an empty world (vanilla would save over the real
player data). System-clock timers (moving the clock forward beats them). A separate Quilt jar and Architectury
(see the-continent decisions 2026-10-20).
**Affects:** TODO.md (Commitment plan), the future `common/` seal, scanner and timer, and the mixins on the
`level.dat` read and write path

## 2026-10-21 — Commitment: guard datapack + mirrored lock record (supersedes the sealed `level.dat` entry above)
**Context:** Owner, reviewing the first plan: make tampering inconvenient, not impossible ("the average user
is too dumb to figure it out"), don't touch `level.dat`, and don't spend space on backups. Their answers to the
open questions: lock per world; unlocking needs internet time; only resource packs locked (video, shader and
audio settings stay free); confirm on `/commit` too; dedicated servers in; the lock lifts automatically after
24 h; Commitment's own updates don't count as a change.
**Choice:** (planned, not implemented; TODO.md Stage 0.2 is the go/no-go) Three small files Commitment owns,
each able to rebuild the others: an HMAC-signed lock record in `<world>/data/commitment.dat`, a mirror in the OS
user data dir keyed by lock id, and a vanilla guard datapack in `<world>/datapacks/`. The pack checks a
heartbeat the mod writes to command storage every tick. Without the mod it blinds, slows and protects every
player with 3-second effects, and shows "reinstall Commitment". Vanilla files are never written by Commitment.
**Why:** The guard runs where the mod can't, after the mod has been deleted, using only vanilla. Nothing the
player owns is overwritten, so there's nothing to back up and a bug can't destroy a world. Getting past it means
finding the world folder and knowing which pack to delete, which matches the owner's "inconvenient" bar.
**Rejected:** The sealed `level.dat` (the entry above): one bug bricks a world, and a backup to make it safe
costs the space the owner won't spend. Gamemode spectator as the penalty (needs the old gamemode stored and
restored; hardcore treats it as death). A mod-registered dimension that vanilla can't load (vanilla's "safe
mode" is one click and may drop the dimension's data). A lock that lives only in `config/` (deleted with the mod).
**Affects:** TODO.md (Commitment plan), the future `common/` lock record, guard pack, scanner and timer

## 2026-10-21 — Commitment: without the mod, the fallback is in-game effects, not a vanilla launch stop
**Context:** Owner asked whether stopping the player at world launch, with the message and the unlock button,
would be better than the guard pack's effects.
**Choice:** With the mod present, the launch stop *is* the primary mechanism: the world-open gate and the
LockedScreen (TODO.md C.2/C.4). The guard pack's effects remain the fallback for a deleted mod only.
**Why:** Without the mod no Commitment code runs at launch, so nothing can show a custom screen or a button.
**Rejected:** A guard pack built to fail validation without the mod, so vanilla refuses the launch. Vanilla's
failed-data-pack screen shows a generic message, and (from memory, to confirm in TODO.md 0.2) its Safe Mode
button loads the world with only the vanilla pack, leaving the guard switched off: one click and it's beaten.
If 0.2 shows Safe Mode leaves world packs enabled, revisit this.
**Affects:** TODO.md (Commitment plan: Approach, 0.2)

## 2026-10-21 — Commitment Stage 0: GO on the guard pack; scaffold keeps the-continent's proven toolchain
**Context:** TODO.md 0.2 was the go/no-go for the vanilla guard datapack. 0.1 found Loom 1.19.2 newer than
the-continent's 1.18.4, and newer Forge recommended builds (64.1.5 / 65.1.5 / 66.1.0).
**Choice:** GO: one `pack.mcmeta` (`min_format` 101, `max_format` 121, no legacy fields) loads on every 26.x node,
world packs are auto-enabled on all four loaders, and tick functions run at gamemaster level without cheats
(evidence in TODO.md 0.2). Scaffold pins: Gradle 9.7.1, Loom 1.18.4, MDG 2.0.148, ForgeGradle 7.0.40,
Stonecutter 0.9.8 (all verified building these exact nodes in the-continent), with today's Fabric API,
NeoForge and Forge builds from official metadata. Owner, same day: push each green stage, MIT, and an expired
timer still needs internet time to lift (fail closed).
**Why:** A toolchain proven on this machine against these nodes takes the build system out of the list of
suspects while the scaffold goes in. A Loom bump is a separate, revertible step.
**Rejected:** Loom 1.19.2 on day one (unproven here, and it may need a newer Gradle). A per-version guard pack
(not needed: the codec is the same on every node).
**Affects:** TODO.md Stage 0, gradle.properties, settings.gradle (Stage A)

## 2026-10-21 — Commitment: world game settings are locked and reverted live; all of `server.properties` is locked
**Context:** Asked at the start of Stage B whether the lock should cover the world's own settings, and
`server.properties` on a dedicated server. Owner: lock difficulty, game rules, cheats and default
gamemode; lock the whole `server.properties`. Those settings live in `level.dat`, which Commitment
doesn't write, so "refuse the world open" would trap a player who ran `/difficulty hard` once: they'd
need an NBT editor or the 24 h timer to undo it.
**Choice:** Owner picked "revert live": while committed, the mod puts the committed value back through
vanilla's API every 20 ticks and at server start, and says so in chat. The settings are a separate
`WorldSettings` snapshot in the lock record, not manifest entries, because they're enforced differently.
`server.properties` goes in the manifest one key per property, hashed, so the diff can name the key
without printing `rcon.password`.
**Why:** One periodic check catches every entry point (commands, the Options button, LAN cheats, other
mods) with one hook, and nothing ever sends the player into the timer for a change they can't undo.
**Rejected:** Refusing at world open (the trap above). Blocking each command and button (a hook per
entry point per loader, and it misses other mods). Hashing `server.properties` as one file (the diff
couldn't say what changed).
**Affects:** TODO.md (spec, threat model, B.1, B.3, C.6), the future `common` WorldSettings and scanner

## 2026-10-21 — Commitment: the lock record is its own gzip+HMAC binary format, not NBT
**Context:** The plan said "LockRecord (NBT, HMAC-signed)". `common` compiles once per 26.x node, and the
NBT API (`CompoundTag` getters, `NbtIo` signatures) has moved between recent Minecraft releases.
**Choice:** A JDK-only format: gzip of a `CMTLOCK` magic, a format version, the fields, and an
HMAC-SHA256 trailer. The magic doubles as the marker B.6's delete guard checks.
**Why:** It compiles the same on every node with no `//? if` blocks, tests without bootstrapping
Minecraft, and gzip makes it no more inviting to hand-edit than NBT. The HMAC is what catches edits.
**Rejected:** NBT via `NbtIo` (per-node API drift for no gain: nothing vanilla reads this file). Plain
JSON (invites editing, and an edit fails the HMAC anyway, which sends the player into the timer).
**Affects:** TODO.md B.3, the future `common/.../lock/`

## 2026-10-21 — Commitment: the lock lifts all-or-nothing, by renaming its files to `*.lifted`
**Context:** B.6, the one destructive path: after the 24 h unlock, Commitment removes its record, mirror and
guard pack. Asked the owner what happens when one file fails a guard, and whether AGENTS.md's soft-delete rule
or their "no disk on backups" ruling wins.
**Choice:** Owner: all-or-nothing (any refused file → nothing touched, the world stays locked, the log names
the file), and rename to `<name>.<lockId>.lifted` (the mirror: `<lockId>.dat.lifted`) instead of deleting.
**Why:** A half-lifted lock is worse than none: a guard pack left behind is evidence to `LockStore.load`, which
would re-lock the world as tampered. Renaming keeps a hand-undoable copy of a buggy lift for a few KB; vanilla
only loads `*.zip` packs (PackDetector), so the renamed pack is inert. The lock id in the name means a later
commit's lift never collides with an earlier one's leftovers.
**Rejected:** Per-file deletion (the re-lock above). Hard delete (owner preferred the soft delete; the rename
also gives the failed-midway rollback something to move back).
**Affects:** common/.../lock/LockLifter.java, GuardPack.java, TODO.md B.6

## 2026-10-21 — Commitment: server GameTests on NeoForge and Forge too, with enchanting-2's harness
**Context:** Proof 2 wants server GameTests on every loader; only Fabric had a harness. Offered the owner Fabric
GameTests + dev-server smokes on the other two, or porting ../enchanting-2's NeoForge/Forge harness first.
**Choice:** Owner: port the harness now (TODO.md C.0). The tests stay in `fabric/src/gametest` with Fabric's
`@GameTest`; NeoForge and Forge compile each node's Stonecutter-processed copy with that one import swapped by a
Gradle filter for our own annotation, and register them (NeoForge: `RegisterEvent` + `RegisterGameTestsEvent`; Forge:
`RegisterEvent` + an in-memory `test_instance` pack). The `fabric-gametest` list in Fabric's gametest
`fabric.mod.json` is the one test list.
**Why:** One set of tests, run by each loader's own GameTest server, so loader glue (events, commands, mixin configs)
is tested where it differs. Taking the node-processed copy from Fabric keeps `//? if` blocks right on every node.
**Rejected:** Smokes only on NeoForge/Forge (owner's call). A second copy of the tests per loader (drifts). Our own
annotation on Fabric too (Fabric's runner only reads its own).
**Affects:** fabric/neoforge/forge build.gradle, forgelike/gametest/, neoforge|forge/src/gametest/

## 2026-10-21 — Commitment: a locked dedicated server starts its unlock with `-Dcommitment.unlock=start`
**Context:** The spec has a dedicated server log the changes and refuse to load a world whose setup changed. The
client's locked screen has a "Start 24-hour unlock" button; a server admin had no way to reach `/unlock`, and with a
damaged record (locked whatever the setup) no way out at all.
**Choice:** Owner: a JVM flag. `-Dcommitment.unlock=start` (or `=cancel`) makes the server gate start (or stop) the
timer with internet time, log `Commitment: unlock available at <time>`, and still refuse to load. After 24 h a normal
start lifts the lock. The refusal's log names the flag.
**Why:** It needs no world to load and no files to edit, and it's as deliberate as the client's confirm dialog.
**Rejected:** A trigger file in the server dir (the same, with a file to create and delete). Reverting the setup only
(no way out of a damaged record).
**Affects:** common/.../gate/ServerGate, TODO.md C.2

## 2026-10-21 — The dev mirror override (`-Dcommitment.mirrorDir`) works in development runs only
**Context:** C.1 added `MirrorDir.OVERRIDE` so dev and GameTest runs keep their lock mirrors under `run/<loader>/<node>/mirror`
instead of the developer's `%LOCALAPPDATA%`. Self-review: it was honoured in a released game too, so a player could add
the flag, delete the world's record and guard pack, and the world would no longer be matched to its mirror by seed.
**Choice:** `Platform.Loader.development()` (Fabric `isDevelopmentEnvironment()`, NeoForge `!FMLEnvironment.isProduction()`,
Forge `!FMLEnvironment.production`, read from the pinned jars) gates it; outside development it's ignored with a warning.
`guard-pack-smoke.sh` now fails a run that left no mirror in the dev dir, or whose lock id shows up in the real one.
**Why:** The seam exists for development only; a production switch that hides every mirror is a bypass for one flag.
**Rejected:** Leaving it on as "same bar as deleting the mirror" (deleting a file in a hidden user dir is harder than a
launcher's JVM-args box). Keying it to a Gradle-only property name (a property is still settable by a player).
**Affects:** common/.../lock/MirrorDir.java, platform/Platform.java, each loader's Platform, scripts/guard-pack-smoke.sh

## 2026-10-22 — Commitment also ships a Fabric 1.21.11 node, and every node gets a real-pack test
**Context:** Stage E planned to run Commitment on a copy of the owner's real instance, but that instance (82 mods,
305 configs) is Minecraft 1.21.11 and Commitment shipped 26.x only.
**Choice:** Owner: add Fabric 1.21.11 (Fabric only), run live on a copy of that instance, and for each 26.x node test
on a simple or premade vanilla+ pack. Added as TODO C.2a (after C.2, before C.3, so the rest of Stage C is built
against it) and C.2b (save safety: one audited set of file writers, a world-dir audit in the smokes, zero diffs on
every pack).
**Why:** "We have to be 100% sure we don't accidentally ruin people's saves." A false lock on a real pack, or a
stray write, is the failure that matters, and only real packs show which files mods rewrite by themselves.
**Rejected:** A scanner-only run on the 1.21.11 copy (doesn't exercise the mod). Retrofitting 1.21.11 after Stage C
(every hook would have to be written twice).
**Affects:** settings.gradle, gradle.properties, common + fabric nodes, README band statement, TODO C.2a/C.2b/E.1

## 2026-10-22 — E.1 runs: a Loom dev client for the 1.21.11 copy, premade Modrinth packs for 26.x
**Context:** E.1 runs Commitment on a copy of the owner's 82-mod 1.21.11 Prism instance and on a pack per 26.x node.
Launching through Prism would mean writing into the owner's launcher (an absolute stop) and their account.
**Choice:** Owner: copy the instance's mods, config and resource packs into `run/packs/1.21.11/` and launch through
`:fabric:1.21.11:runClient` (offline dev account), so the three restarts are scripted. For 26.x: a premade Modrinth
vanilla+ pack per node and loader where one exists, a small handmade pack where none does.
**Why:** Scripted restarts need no one at the keyboard and never touch Prism. Real premade packs are where the "mods
rewrite their own configs" signal comes from.
**Rejected:** Owner importing a prepared instance into Prism (every restart needs them). Handmade packs only (less
realistic).
**Affects:** TODO E.1, run/packs/

## 2026-10-26 — guard-pack.txt becomes guard-live.txt; Claude picks the Stage E packs
**Context:** C.2's gate refuses a world holding only the golden guard pack, so C.1's `scripts/checks/guard-pack.txt`
can't pass. Its checks belong on a world committed with `/commit confirm` (C.3c). Deleting a test is an absolute stop.
Separately, E.1 needs a premade Modrinth vanilla+ pack per 26.x node and loader.
**Choice:** Owner (session 8): `git mv guard-pack.txt guard-live.txt`, rewritten for the committed world, every old
check kept. For E.1, the agent picks the most-downloaded Modrinth vanilla+ pack per node and loader, records the
pick and its version id in TODO.md, and makes a small handmade pack where none exists.
**Why:** A rename keeps the coverage and the file's history; the golden-pack wrapper stays for the locked-* boots,
where a golden-only world is exactly the damaged-record case.
**Rejected:** Delete and re-add (loses history). Keep guard-pack.txt as a second refusal check (duplicates
locked-boot.txt). The owner naming each pack (blocks E.1 on a question).
**Affects:** scripts/checks/guard-live.txt, scripts/commit-smoke.sh, scripts/guard-pack-smoke.sh, TODO C.3c and E.1

## 2026-10-26 — The LockedScreen is vanilla widgets only; its buttons reuse /unlock's code and re-run the gate
**Context:** C.4 replaces C.2's interim AlertScreen. Screen drawing differs by node: 1.21.11 overrides
`render(GuiGraphics, …)`, 26.x `extractRenderState(GuiGraphicsExtractor, …)` (javap, all four client jars).
**Choice:** Build it from `HeaderAndFooterLayout`, `ScrollableLayout`, `MultiLineTextWidget`, `Button` and vanilla's
`ConfirmScreen` (identical on all four nodes), never overriding the draw method: one source, no version blocks. Start
and Cancel call `CommitActions.unlock` / `cancel` (the code `/unlock` runs) off the render thread, then the gate runs
again, so the screen always shows what was saved. The countdown is display only; the lift is still decided by
internet time at the next open. Text lives in `assets/commitment/lang/en_us.json`; logs stay English.
`ClientGate.useTestClock` is the client GameTest's clock seam, refused outside development like
`CommitmentCommands.useTestClocks`.
**Why:** A hand-drawn screen would need a `//? if` block per draw call on every node, and those are the calls most
likely to move again. Reusing the commands' code keeps one path that saves a timer.
**Rejected:** Version-blocked `render`/`extractRenderState` overrides. Having the screen write the record itself
(a second timer path to keep in step). Literal strings (no translation; the client test clicks buttons by key).
**Affects:** common/.../client/LockedScreen.java, ClientGate.java, assets/commitment/lang/en_us.json

## 2026-10-26 — C.6 also locks LAN guest commands and each player's own gamemode
**Context:** C.6 facts (javap, all four nodes): 26.2+ splits cheats into the world's saved `allowCommands` and Open
to LAN's guest command access, and 26.3 adds a World Options screen whose one Apply can turn cheats on, set the
host's own gamemode, and turn cheats off again. Reverting cheats a second later leaves the gamemode changed. The
spec locked only the world's default gamemode. The 20-tick check can't tell who made a change.
**Choice:** Owner (session 8): (1) guest command access is locked with cheats. (2) Each player's own gamemode is
held, but only on an integrated server (singleplayer/LAN) committed with cheats **off**: a player online at
`/commit confirm` is held at their gamemode then, anyone else at the committed default gamemode; a hardcore world's
spectator (death) is left alone. Worlds committed with cheats on, and dedicated servers, don't hold player
gamemodes. (3) "Commitment: <setting> is locked" goes to every online player and the log, at most once per setting
every 10 s.
**Why:** Any switch that grants commands is cheats, whoever uses it. Where commands are allowed, `/gamemode` is
legitimate and a hold would fight it; where they aren't, a gamemode change is a way around the lock.
**Rejected:** Leaving guest access free (a second LAN account could `/gamemode` the host). Holding gamemodes
everywhere (fights an op's admin work and a cheats-on world's own commands). Everyone at the default gamemode (would
flip a host whose gamemode differed at commit). Messaging only ops, or only "the player who changed it" (unknowable
from a periodic check).
**Affects:** TODO C.6, common WorldSettings, CommitmentServer, the snapshot at `/commit confirm`

## 2026-10-27 — Stage D: the tamper matrix as one chain per pair, Quilt built now, the clock by JUnit
**Context:** D.1 runs every threat-model row on every loader. Quilt has no dev-server path: only the built Fabric jar
on a real Quilt server tests it. Proving "move the clock forward" live would mean moving the owner's system clock.
**Choice:** Owner (session 9): the Quilt real-server harness is built in Stage D (F reuses it) and the server rows run
on Quilt × 26.1.2/26.2/26.3 as well as the ten dev pairs. The clock row is a JUnit (local clock +48 h, trusted clock
+1 h → still pending) plus a source scan that pins every local-clock read in shipped code. The "still works" bypass
is proven live on a vanilla server. Rows run as one ordered chain of boots per pair over one lock
(`commit-smoke.sh scripts/checks/tamper/*`), with the "edit both copies" row last because it leaves the world
locked for good. `dev-server-smoke.sh` gets `@server vanilla` (Mojang's jar on the same world via `--universe`) and
`LOADER=quilt`; the built-jar server gets its own `LOCALAPPDATA` so the mirror stays in `run/`.
**Why:** One commit per pair instead of one per row roughly halves the boots (~14 per pair, not ~26). A shared launcher keeps one
verdict, one world audit and one cleanup for every kind of server. `LOCALAPPDATA` is what `MirrorDir` already reads,
so production code needs no test seam.
**Rejected:** Leaving Quilt to Stage F (owner chose now). Moving the clock, or a dev-only clock-offset property (a
seam in shipped code for something the source scan proves structurally). A separate vanilla-runner script per row
(a second verdict to keep in step). Honouring `-Dcommitment.mirrorDir` on a released jar (decisions.md: one flag
would hide every mirror).
**Affects:** TODO D.1, scripts/dev-server-smoke.sh, commit-smoke.sh, server-jars.sh, tamper.sh, tamper-matrix.sh

## 2026-10-27 — NeoForge stays fail-closed on a deleted config/; Stage E packs run as real clients
**Context:** D.1 row 08: NeoForge writes its server-side config (`neoforge-server.toml`, `neoforge-synced.toml` on
26.3) only after Commitment's check, so a committed world is refused when `config/` is deleted. Separately, E.1 had to
pick between dev clients and dedicated servers for the packs.
**Choice:** Owner (session 10): keep fail-closed, no special case for NeoForge's own file. Prove the way back (opening
an uncommitted world writes the file again) with a test. Every Stage E pack (the 1.21.11 copy and each 26.x pack) runs
as a scripted dev client, game windows allowed. The 1.21.11 copy is taken from the Prism instance as it is today.
**Why:** Deleting `config/` is a setup change, and the way back is one world open. Client packs only show their
client-side config rewrites (and the resource-pack and `options.txt` scan) in a real client.
**Rejected:** Ignoring NeoForge's server-side file when it's missing (a hole only NeoForge would have). Dedicated
servers for the 26.x packs (client-only mods' rewrites go untested). Asking before each batch of client launches.
**Affects:** TODO E.1, the threat table, scripts for Stage E

## 2026-10-27 — Stage E: no Quilt; packs driven by Quick Play plus a dev-only pack driver
**Context:** Quilt has no dev client in this tree. The packs need `/commit` typed and the game quit with no one at the
keyboard, on Fabric, NeoForge and Forge.
**Choice:** Owner: skip Quilt in Stage E (Fabric runs the same jar; F.2 boots it). Quick Play opens a copy of the dev
world; a dev-only driver in each loader's GameTest mod sends the commands and quits through `Minecraft#stop`. Packs
are the most-downloaded per node and loader with a version for exactly that pair (TODO E.1 table).
**Why:** Quick Play is already proven on NeoForge and Forge (C.4). A clean quit lets mods write shutdown configs,
which is the rewrite E.1 hunts for.
**Rejected:** Fabric's client GameTest (Fabric only, sets up its own client). A seam in shipped code. Ending the JVM
by PID (no clean exit). A datapack function running `/commit` (level 2; `/commit` needs the host or level 4).
**Affects:** TODO E.1, fabric|neoforge|forge gametest source sets and build.gradle, scripts/pack-*.

## 2026-10-28 — Stage F: lower the NeoForge/Forge floor, GitHub Release only, version 1.0.0
**Context:** E.1 found every NeoForge/Forge pack asks for an older loader than Commitment's floor (the dev pin), and
on NeoForge 26.3 updating the loader breaks the pack's Sodium (gotchas 2026-10-27). Stage F also had to pick where a
release goes and its number; there is no CI, so nothing publishes on a push.
**Choice:** Owner (session 11): lower the floor to the lowest build each node is proven on, separate from the dev pin
(`neoforge_floor.<node>`, `forge_floor.<node>`; the jar still compiles against the pin). Candidates: the lower of
the line's first stable build and what the E.1 pack asked for (NeoForge), the line's first build (Forge); one
fallback to the pack's own ask. Publish as a GitHub Release (`gh release create`) with the 13 jars, version 1.0.0.
**Why:** A player adds Commitment to the pack they have; asking them to update the loader can break the pack.
Compiling against the pin keeps every proof so far valid; the floor gets its own proofs (TODO F.0).
**Rejected:** Keeping the pin as the floor and documenting "update your loader" (the E.1 Sodium break shows the cost).
Compiling the release against the floor (would void Stages C–E's dev proofs). Modrinth/CurseForge now (owner's to add
later). 0.1.0.
**Affects:** gradle.properties, neoforge|forge mods.toml, scripts/dev-server-smoke.sh, commit-smoke.sh, server-jars.sh,
README, TODO Stage F
