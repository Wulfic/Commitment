# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-21
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21).

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- **Stage 0 done:** the guard datapack is GO on every 26.x node (facts in TODO.md 0.1–0.4).
- **Stage A done:** scaffold of common + fabric/neoforge/forge × Stonecutter nodes 26.1.2/26.2/26.3. Build green,
  boot smoke exit 0 on all 9, client world check PASS on all 3 Fabric nodes.
- **Owner rulings, session 3:** world game settings (difficulty, game rules, cheats, default gamemode) are locked and
  **reverted live** (new step C.6); on a dedicated server the whole `server.properties` is locked (decisions.md).
- **B.1 done:** `common/.../manifest/` scanner, 84 common tests per node, every guard mutation-checked.

## Next
1. **B.2** `ManifestDiff` → readable lines, capped. Then B.3–B.6, one commit each; push when Stage B is green.
2. Stage C needs a real-server smoke tool (copy `../the-continent/tools/server_smoke.py`) for Proof 4 (vanilla
   server, guard pack only) and Proof 7 (Quilt + built jars).

## How to run things
- Build: `./gradlew build` (all 12 nodes; Fabric's `check` includes `runGameTest`). Read counts from
  `*/versions/*/build/test-results/test/*.xml`, not the exit code.
- Dev server: `LOADER=<fabric|neoforge|forge> NODE=<26.1.2|26.2|26.3> scripts/dev-server-smoke.sh scripts/checks/boot.txt`.
  Port **25571**, run dirs `run/<loader>/<node>/server`. `--self-test` checks the verdict (12 cases).
- Client check: `./gradlew :fabric:<node>:runClientGameTest` opens a game window; look for `client world check: PASS`.

## Open questions / blockers
- **Rotate the credentials** tracked in the template's history before 2026-10-21 (commit `8108c25`; gotchas.md).
- `PowerShell(...)` permission-rule matching is unverified; treat the PowerShell tool as ungated.

## Do not forget
- `.claude/skills/` and `.github/skills/` are hand-synced copies — edit both or they drift.
- Never compile while a `runServer` is live. Never two Gradle builds on this tree.
- Development commits only `run/` worlds or disposable copies, never the owner's saves or instance in place.
  Commitment will also write a mirror file under the OS user data dir: clean it up after tests.
