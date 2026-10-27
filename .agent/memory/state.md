# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-27 (session 9 end: Stage D / D.1 done and pushed)
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21).

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- Stages 0, A, B, C, D done and pushed. 13 nodes; common 290 JUnit per node.
- D.1: the tamper chain (14 boots) exit 0 on all 13 pairs (fabric x4, neoforge x3, forge x3, quilt x3). NeoForge
  refuses row 08 (config/ moved): its server-side config (`neoforge-server.toml`, `neoforge-synced.toml` on 26.3) is
  written after our check; pinned in `08-config-moved-neoforge.txt`, threat table reworded (gotchas 2026-10-27).

## Next
1. Stage E (TODO E.1): a real modpack, on a copy, 3 restarts → zero diffs.
2. Owner decision worth raising: NeoForge players who delete `config/` are locked out until it comes back (or 24 h).
   Ignore NeoForge's server-side config when it's missing, or keep fail-closed? Today: kept, in the threat table.
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
