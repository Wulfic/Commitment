# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-27 (session 10, E.1: 26.3 green on all three loaders)
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21).

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- Stages 0, A, B, C, D done and pushed. Stage E in progress (TODO E.1), committed locally, **not pushed** (push at
  the end of the stage).
- Owner, session 10: NeoForge stays fail-closed on a deleted `config/` (E.1f proves the way back); packs run as real
  dev clients; Prism 1.21.11 copied as it is today; no Quilt in Stage E (decisions.md 2026-10-27).
- Pack runs pass (exit 0, commit + 4 reopens): fabric/forge/neoforge 26.3. Findings so far: `*.lock` ignored
  (Controlify), client logs each locked change. NeoForge 26.3 pack needed Sodium + Reese's swapped (pack.txt).
- Running at checkpoint: the six 26.1.2/26.2 pairs (`build/pack-smoke-<loader>-<node>.out`).

## Next
1. Read the six results; fix and rerun failures. Then the Prism copy: `LOADER=fabric NODE=1.21.11
   PACK=run/packs/1.21.11/fabric-prism bash scripts/pack-smoke.sh` (expect dev-environment mod failures: report).
2. E.1f (NeoForge way back: dev server `--world` on an uncommitted world), E.2 (scan time).
3. Raise with the owner at F: Commitment's loader floor is the dev pin; every NeoForge/Forge pack asks for less.
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
- Pack run: `LOADER=.. NODE=.. PACK=run/packs/<node>/<pack> bash scripts/pack-smoke.sh` (5 client launches; `--clean`
  removes a failed run's copy). Packs: `python -I scripts/pack-fetch.py <version id> <dir>`.
- Tamper matrix: `bash scripts/tamper-matrix.sh [loader:node ...]` → `build/tamper-matrix.md`; Quilt needs the built
  Fabric jar. `tamper.sh` backups live in `run/<loader>/<node>/tamper-backup/` (never auto-deleted).

## Open questions / blockers
- `PowerShell(...)` permission-rule matching is unverified; treat the PowerShell tool as ungated.

## Do not forget
- `.claude/skills/` and `.github/skills/` are hand-synced copies — edit both or they drift.
- Never compile while a `runServer` is live. Never two Gradle builds on this tree.
- Development commits only `run/` worlds or disposable copies, never the owner's saves or instance in place.
  Commitment will also write a mirror file under the OS user data dir: clean it up after tests.
