# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-22 (session 6)
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21).

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- **Stage 0 done:** the guard datapack is GO on every 26.x node (facts in TODO.md 0.1–0.4).
- **Stage A done:** scaffold of common + fabric/neoforge/forge × Stonecutter nodes 26.1.2/26.2/26.3. Build green,
  boot smoke exit 0 on all 9, client world check PASS on all 3 Fabric nodes.
- **Owner rulings, session 3:** world game settings (difficulty, game rules, cheats, default gamemode) are locked and
  **reverted live** (new step C.6); on a dedicated server the whole `server.properties` is locked (decisions.md).
- **Stage B done** (B.1–B.6): scanner, diff lines, signed lock record + mirror + reconciliation, 24 h timer,
  internet time, and B.6's `GuardPack` (generator, marker, a writer that never overwrites a stranger) and `LockLifter`
  (all-or-nothing, **renames** to `*.<lockId>.lifted`: owner, session 4). Common 207 tests per node, every guard
  mutation-checked. The guard pack is proven live on Fabric 26.1.2/26.2/26.3 (`scripts/guard-pack-smoke.sh`).

## Next (session 6)
- C.0, C.1 committed, not pushed. **C.2 done** (server + client gate, proven on all nine / all three Fabric nodes;
  TODO C.2 "Done, session 6"). Push when Stage C is green (no CI: a push can't release).
- **Owner, session 6:** add a **Fabric 1.21.11 node** (C.2a, next, before C.3) so Commitment runs live on a copy of the
  owner's 82-mod Prism instance; **save safety** (C.2b); a test pack per 26.x node (E.1). decisions.md 2026-10-22.
  C.2a facts are in TODO (Java 21, Fabric API 0.141.6, loom-back-compat 0.4.3 as ../enchanting-2); three facts still open.
- C.1's guard-pack smokes refuse at the gate now (golden pack = damaged record): redo them in C.3 with `/commit`.
- Owner rulings (decisions.md): lift is all-or-nothing and renames to `*.<lockId>.lifted`; a locked dedicated server
  unlocks with `-Dcommitment.unlock=start|cancel`; the dev mirror override works in development runs only.
- Stage C still needs a real-server smoke tool (`../the-continent/tools/server_smoke.py`) for Proof 4 and 7.

## How to run things
- Build: `./gradlew build` (all 12 nodes; Fabric's `check` includes `runGameTest`). Read counts from
  `*/versions/*/build/test-results/test/*.xml`, not the exit code.
- Dev server: `LOADER=<fabric|neoforge|forge> NODE=<26.1.2|26.2|26.3> scripts/dev-server-smoke.sh scripts/checks/boot.txt`.
  Port **25571**, run dirs `run/<loader>/<node>/server`. `--self-test` checks the verdict (21 cases). `@reject` = must not match, `@exits-nonzero` = a refusal, `@gradle <arg>`.
- Client check: `./gradlew :fabric:<node>:runClientGameTest` opens a game window; look for `client world check: PASS`
  and `client gate check: PASS`. Locked boots: `LOADER=.. NODE=.. bash scripts/guard-pack-smoke.sh scripts/checks/locked-{boot,unlock,cancel}.txt`.

## Open questions / blockers
- `PowerShell(...)` permission-rule matching is unverified; treat the PowerShell tool as ungated.

## Do not forget
- `.claude/skills/` and `.github/skills/` are hand-synced copies — edit both or they drift.
- Never compile while a `runServer` is live. Never two Gradle builds on this tree.
- Development commits only `run/` worlds or disposable copies, never the owner's saves or instance in place.
  Commitment will also write a mirror file under the OS user data dir: clean it up after tests.
