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
