# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-26 (session 8: C.3c, C.4, C.5 committed; C.6 next)
**Branch:** `main`, always (decisions.md 2026-10-21). Push each green stage (owner, 2026-10-21).

## Where we are
**Commitment**: locks a world's setup after `/commit`, with a 24-hour unlock. Plan in `TODO.md` (Tier 2).
- **Stages 0, A, B done.** Stage C committed, **not pushed**: C.0, C.1, C.2, C.2a (Fabric 1.21.11), C.2b (save
  safety: `FileWritersTest` + `scripts/world-audit.sh` in every smoke; bullet 3 waits for E.1), C.3a (commands in
  common), C.3b (registered on all loaders), C.3c (client test via chat, `commit-smoke.sh` on all ten pairs), C.4 (LockedScreen), C.5 (locked-config smokes, vanilla guard proof). 13 nodes; common 268
  JUnit per node, fabric 12, GameTests 5 of ours on all ten.
- Owner rulings in decisions.md, incl. session 7: E.1 runs the 1.21.11 copy as a Loom dev client, 26.x on premade
  Modrinth packs.

## Next: C.6, world settings reverted live (TODO.md C.6: facts first from each node's jar); then push Stage C
- C.5 done: locked-config on all ten pairs, Proof 4 (`vanilla-guard-smoke.sh`) on all four nodes.
- Owner, session 8: guard-pack.txt renamed to guard-live.txt; Claude picks the E.1 Modrinth packs (decisions.md).
- Noted: "Deprecated Gradle features" comes from a task at execution time; find which before Gradle 10.

## How to run things
- Build: `./gradlew build` (all 13 nodes with 1.21.11; Fabric's `check` includes `runGameTest`). Read counts from
  `*/versions/*/build/test-results/test/*.xml`, not the exit code.
- Dev server: `LOADER=<fabric|neoforge|forge> NODE=<1.21.11 (fabric only)|26.1.2|26.2|26.3> scripts/dev-server-smoke.sh scripts/checks/boot.txt`.
  Port **25571**, run dirs `run/<loader>/<node>/server`. `--self-test` checks the verdict (23 cases + world-audit + commit-smoke). `@reject` = must not match, `@exits-nonzero` = a refusal, `@gradle <arg>`, `@wait <re>` = hold until the reply is logged.
- Client check: `./gradlew :fabric:<node>:runClientGameTest` opens a game window; look for `client world check: PASS`
  and `client gate check: PASS`. Locked boots: `LOADER=.. NODE=.. bash scripts/guard-pack-smoke.sh scripts/checks/locked-{boot,unlock,cancel}.txt`.
- Commit flow: `LOADER=.. NODE=.. bash scripts/commit-smoke.sh` (4 boots on one world, cleans up by marker + lock id).
- Client locked screen (NeoForge/Forge): `LOADER=.. NODE=.. bash scripts/client-locked-smoke.sh` (opens a game window).
- Guard without the mod: `NODE=.. bash scripts/vanilla-guard-smoke.sh` (Mojang server jar into run/vanilla/<node>).

## Open questions / blockers
- `PowerShell(...)` permission-rule matching is unverified; treat the PowerShell tool as ungated.

## Do not forget
- `.claude/skills/` and `.github/skills/` are hand-synced copies — edit both or they drift.
- Never compile while a `runServer` is live. Never two Gradle builds on this tree.
- Development commits only `run/` worlds or disposable copies, never the owner's saves or instance in place.
  Commitment will also write a mirror file under the OS user data dir: clean it up after tests.
