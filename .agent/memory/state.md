# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-29 (session 16: F.5 done, **1.0.0 released**; Stage F done)
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21); Stage F's push hold
(decisions 2026-10-28) ended with the release.

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- **1.0.0 is out**: https://github.com/Wulfic/Commitment/releases/tag/v1.0.0 (tag `v1.0.0` on `c0f724a`), 10 mod
  jars, published and Latest; the downloaded assets match F.4's pre-gate SHA-256 (listed in TODO F.5).
- Stages 0, A-F done. 13 nodes; common 306 JUnit per node, fabric 18, GameTests 6 on all 10 pairs.
- E.1: ten real packs (9 Modrinth: 26.1.2/26.2/26.3 × fabric/neoforge/forge, plus the owner's Prism 1.21.11 copy) each
  committed and reopened 4× as dev clients, exit 0. Findings fixed: `*.lock`, Crash Assistant localization (IGNORED),
  `languagereload.json` (NEVER_LOCKED), the client logs each locked change. E.1f: NeoForge's way back after a deleted
  `config/` is one boot of another world. E.2: the scan is under 1 s on 175 mods.

## Next
1. **Nothing planned past 1.0.0: the owner picks.** Known loose ends: the smoke harness says exit 1 for an
   `internet time` refusal caused by the network, where 2 ("nothing proven") is true (gotchas 2026-10-28);
   Modrinth/CurseForge weren't in scope. Any fix goes to a 1.0.x with its own release gate (a jar's hashes go in TODO).
2. Prism's `commitment-smoke` instance was removed (owner's word); `prism-smoke.sh` recreates it on its next run.

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
- Real loader server: `LOADER=<neoforge|forge> NODE=.. REAL=<loader version> bash scripts/commit-smoke.sh ...` (built
  jar, `run/real/<loader>/<node>/<version>/`). Client on another loader build: `LOADER_VERSION=<v> client-locked-smoke.sh`.
  GameTests on one build: `-P<loader>_version.<node>=<v>`; `-Pgametest_max_parallel=1` if Windows refuses commit memory.
- Prism client (real Quilt/Fabric client, shipped jar): `PRISM_LOADER=<fabric|quilt> NODE=.. MC=.. bash
  scripts/prism-smoke.sh` after rung 6 of that MC (uses its world + Fabric API); refuses while any Prism runs.
- Tamper matrix: `bash scripts/tamper-matrix.sh [loader:node ...]` → `build/tamper-matrix.md`; Quilt needs the built
  Fabric jar. `tamper.sh` backups live in `run/<loader>/<node>/tamper-backup/` (never auto-deleted).

## Open questions / blockers
- `PowerShell(...)` permission-rule matching is unverified; treat the PowerShell tool as ungated.

## Do not forget
- `.claude/skills/` and `.github/skills/` are hand-synced copies — edit both or they drift.
- Never compile while a `runServer` is live. Never two Gradle builds on this tree.
- Development commits only `run/` worlds or disposable copies, never the owner's saves or instance in place.
  Commitment will also write a mirror file under the OS user data dir: clean it up after tests.
