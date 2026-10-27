# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-27 (session 9 end: D.1 matrix 10 of 13 pairs green; NeoForge row 08 rerun in flight)
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21).

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- Stages 0, A, B, C done and pushed (8e4e61f). 13 nodes; common 290 JUnit per node (ClockSourcesTest added).
- **Stage D**: harness committed in ae07669 (**not pushed**: Stage D isn't green yet). Full matrix at ae07669
  (`build/tamper-matrix-full-ae07669.md`): fabric x4, forge x3, quilt x3 exit 0, 14/14 boots each. NeoForge x3 exit 1
  at row 08: `Config removed: config/neoforge-synced.toml` (NeoForge writes it after our check; gotchas 2026-10-27).
  The outcome is fail closed, not a bypass. Fixed in the session 9 checkpoint commit: `08-config-moved-neoforge.txt` pins the refusal,
  `tamper-matrix.sh` picks it for NeoForge, threat table row reworded.
- A rerun of the 3 NeoForge pairs was started at session end (`build/tamper-matrix-neoforge-run.log`, per-pair logs in
  `build/tamper-matrix/neoforge-*.log`; the 26.2/26.3 logs there are from the old run until the rerun reaches them).

## Next
1. **First:** look for a leftover dev-server JVM on port 25571 by command line (the session may have ended mid-run;
   never kill java by name). Then read `build/tamper-matrix-neoforge-run.log`; if it's incomplete, rerun
   `bash scripts/tamper-matrix.sh neoforge:26.1.2 neoforge:26.2 neoforge:26.3` (background, ~45 min).
2. All 13 green → record D.1 results in TODO (per pair, exit, boots, notable proof lines), check D.1, push Stage D.
3. Owner decision worth raising: NeoForge players who delete `config/` are locked out until it comes back (or 24 h).
   Ignore `neoforge-synced.toml` when it's missing, or keep fail-closed? Today: kept, documented in the threat table.
- Untested: SettingsLock/CommitmentServer catch blocks around the tick check (TODO C.6).
- Stale bookkeeping: C.1 checkbox unticked though done; C.2b bullet 3 waits on E.1.

## How to run things
- Build: `./gradlew build` (all 13 nodes with 1.21.11; Fabric's `check` includes `runGameTest`). Read counts from
  `*/versions/*/build/test-results/test/*.xml`, not the exit code.
- Settings revert smoke: `commit-smoke.sh scripts/checks/commit-flow.txt scripts/checks/rules-revert.txt`.
- Dev server: `LOADER=<fabric|quilt|neoforge|forge> NODE=<1.21.11 (fabric only)|26.1.2|26.2|26.3> scripts/dev-server-smoke.sh scripts/checks/boot.txt`.
  Port **25571**, run dirs `run/<loader>/<node>/server`. `--self-test` checks the verdict (23 cases + world-audit + commit-smoke). `@reject` = must not match, `@exits-nonzero` = a refusal, `@gradle <arg>`, `@wait <re>` = hold until the reply is logged.
- Client check: `./gradlew :fabric:<node>:runClientGameTest` opens a game window; look for `client world check: PASS`
  and `client gate check: PASS`. Locked boots: `LOADER=.. NODE=.. bash scripts/guard-pack-smoke.sh scripts/checks/locked-{boot,unlock,cancel}.txt`.
- Commit flow: `LOADER=.. NODE=.. bash scripts/commit-smoke.sh` (4 boots on one world, cleans up by marker + lock id).
- Client locked screen (NeoForge/Forge): `LOADER=.. NODE=.. bash scripts/client-locked-smoke.sh` (opens a game window).
- Guard without the mod: `NODE=.. bash scripts/vanilla-guard-smoke.sh` (Mojang server jar into run/vanilla/<node>).
- Tamper matrix: `bash scripts/tamper-matrix.sh [loader:node ...]` → `build/tamper-matrix.md`; Quilt needs the built
  Fabric jar. `tamper.sh` backups live in `run/<loader>/<node>/tamper-backup/` (never auto-deleted).

## Open questions / blockers
- `PowerShell(...)` permission-rule matching is unverified; treat the PowerShell tool as ungated.

## Do not forget
- `.claude/skills/` and `.github/skills/` are hand-synced copies — edit both or they drift.
- Never compile while a `runServer` is live. Never two Gradle builds on this tree.
- Development commits only `run/` worlds or disposable copies, never the owner's saves or instance in place.
  Commitment will also write a mirror file under the OS user data dir: clean it up after tests.
