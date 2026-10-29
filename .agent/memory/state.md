# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-29 (session 18: 1.0.1, new jar names, release workflow)
**Branch:** `main`, always (decisions.md 2026-10-21). **A push to `main` can release now**: `.github/workflows/release.yml`
publishes `v<version>` when gradle.properties' version has none, then deletes every older release and tag.

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- **1.0.1** (TODO "1.0.1: jar names ..."): jars named `commitment-<v>+<fabric-quilt|fabric|neoforge|forge>-mc<LOW[-HIGH]>`
  (`fabric-quilt-mc26.1-26.1.2`; 1.21.11 stays `fabric-mc1.21.11`, no Quilt proven there). No mod code changed; owner
  waived the release gate for it. CI publishes on the version bump and prunes older releases + tags (owner's choice).
- v1.0.0's release and tag are deleted by that run; the backup is `run/release-backup/v1.0.0/` (10 jars SHA-256 checked,
  notes, tag facts) and the local annotated tag `v1.0.0` (on `c0f724a`).
- Builds reproduce byte for byte across this machine and CI's Linux runner (CI's 1.0.0 jars = F.5's hashes).
- Stages 0, A-F done. 13 nodes; common 306 JUnit per node, fabric 22, GameTests 6 on all 10 pairs.

## Next
1. **Owner:** AGENTS.md says frozen bands' "releases stay up"; pruning now deletes them (decisions 2026-10-29).
2. Known loose ends: the smoke harness says exit 1 for an `internet time` refusal caused by the network (2 is true,
   gotchas 2026-10-28); Modrinth/CurseForge weren't in scope.
3. Any next release: bump `version` + CHANGELOG entry; the release gate before that push (AGENTS.md), unless waived.

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
