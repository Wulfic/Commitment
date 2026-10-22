# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-22 (session 7 end: C.3b done)
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21).

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- **Stages 0, A, B done.** Stage C committed, **not pushed**: C.0, C.1, C.2, C.2a (Fabric 1.21.11), C.2b (save
  safety: `FileWritersTest` + `scripts/world-audit.sh` in every smoke; bullet 3 waits for E.1), C.3a (commands in
  common), C.3b (registered on all loaders, `CommandsGameTest` green on all ten GameTest nodes). 13 nodes; common 266
  JUnit per node, fabric 12, GameTests 5 of ours on all ten.
- Owner rulings in decisions.md, incl. session 7: E.1 runs the 1.21.11 copy as a Loom dev client, 26.x on premade
  Modrinth packs.

## Next: C.3c (plan in TODO.md C.3)
1. `WorldGateClientGameTest`: replace its hand-written `commit()` with real `/commit` + `/commit confirm` from chat
   (`client.player.connection.sendCommand`, wait ~60 ticks between, then wait for `data/commitment.dat`), quit,
   reopen → must open (proves the commit-time scan equals the gate's). Run on all 4 Fabric nodes.
2. `scripts/commit-smoke.sh` (model: `guard-pack-smoke.sh`, no golden pack; lock id from the zip's commitment.lock;
   guarded cleanup with a `--self-test`), checks `commit-flow.txt`, `guard-live.txt` (boot 2: `@expect Commitment:
   world is committed and its setup matches`, heartbeat, pack enabled, `@reject` guard line), then
   `guard-disable.txt` + `guard-reenable.txt`. Retire `guard-pack.txt` (a golden-only world is refused now). All ten
   pairs. Then C.4 to C.6; push when Stage C is green.
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
