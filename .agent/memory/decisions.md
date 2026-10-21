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
