# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-22 (session 7, C.2a done)
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21).

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- **Stages 0, A, B done.** Stage C: C.0, C.1, C.2 and **C.2a (Fabric 1.21.11 node)** committed, not pushed. 13 nodes:
  common + fabric on 1.21.11/26.1.2/26.2/26.3, neoforge + forge on the three 26.x. Common 245 JUnit per node, fabric 12,
  GameTests 4 of ours on all ten GameTest nodes.
- Owner rulings in decisions.md: lift is all-or-nothing and renames to `*.<lockId>.lifted`; a locked dedicated server
  unlocks with `-Dcommitment.unlock=start|cancel`; the dev mirror override works in development runs only; world game
  settings are reverted live (C.6); E.1 runs the 1.21.11 copy as a Loom dev client, 26.x on premade Modrinth packs.

## Next
- **C.2b save safety**, then C.3 (`/commit`; redo C.1's guard-pack smokes with it: a golden-pack-only world is now
  refused by the gate), C.4 LockedScreen, C.5, C.6. Push when Stage C is green (no CI: a push can't release).
- Stage C still needs a real-server smoke tool (`../the-continent/tools/server_smoke.py`) for Proof 4 and 7.
- Noted: "Deprecated Gradle features" comes from a task at execution time; find which before Gradle 10.

## How to run things
- Build: `./gradlew build` (all 13 nodes with 1.21.11; Fabric's `check` includes `runGameTest`). Read counts from
  `*/versions/*/build/test-results/test/*.xml`, not the exit code.
- Dev server: `LOADER=<fabric|neoforge|forge> NODE=<1.21.11 (fabric only)|26.1.2|26.2|26.3> scripts/dev-server-smoke.sh scripts/checks/boot.txt`.
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
