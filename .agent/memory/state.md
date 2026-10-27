# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-27 (session 9: Stage D harness built, uncommitted; the matrix not yet run)
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21).

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- Stages 0, A, B, C done and pushed (8e4e61f). 13 nodes; common 289 JUnit per node, fabric 12, GameTests 5 of ours.
- **Stage D in progress** (plan in TODO D.1, owner rulings session 9: Quilt harness now, 13 pairs, clock by JUnit +
  source scan, bypass proven live). Built, uncommitted: `scripts/server-jars.sh` (checksummed vanilla/Quilt/Fabric API),
  `dev-server-smoke.sh` `LOADER=quilt` + `@server vanilla`, `scripts/lock-files.sh` (shared by commit-smoke/tamper),
  `scripts/tamper.sh` (+ self-test 38, mutation-checked), `scripts/checks/tamper/*`, `scripts/tamper-matrix.sh`,
  `ClockSourcesTest` (not yet run). **Quilt GO**: Loader 0.31.0-beta.4 runs the built Fabric jar on 26.1.2/26.2/26.3;
  commit-flow + guard chain exit 0 on Quilt 26.1.2 and 26.3 (26.3's first pass ran on a mid-edit script: rerun).

## Next
1. Quilt 26.2/26.3 chain result; scope the vanilla launch's LOCALAPPDATA to Quilt; `./gradlew build` (ClockSourcesTest,
   mutation: a currentTimeMillis in UnlockTimer → red); `dev-server-smoke.sh --self-test`.
2. `bash scripts/tamper-matrix.sh` (13 pairs, ~14 boots each, background) → record in TODO D.1; commit + push Stage D.
- Untested: SettingsLock/CommitmentServer catch blocks around the tick check (TODO C.6).

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
