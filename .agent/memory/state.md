# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-21
**Branch:** `main`, always (decisions.md 2026-10-21).

## Where we are
This repo is now **Commitment**: a mod that locks a world's setup (mods, resource packs, config) after
`/commit`, with a 24-hour unlock. **Planned, no code yet.** The plan is in `TODO.md` (Tier 2, Stages 0–F, revised after the
owner's answers), and the design is in decisions.md 2026-10-21 "guard datapack + mirrored lock record". The
sealed-`level.dat` design is superseded: never write vanilla files, never make backups.

## Next
1. The owner's rulings are recorded in `TODO.md` (all 7 questions answered 2026-10-21).
2. Stage 0: version and loader facts from metadata, then **0.2 go/no-go**: does one guard datapack load and
   run on every 26.x node (auto-enable, `#minecraft:load` timing, permission levels, `pack.mcmeta` format range)?
3. Stage A: scaffold copied from `../the-continent` (same common/fabric/neoforge/forge × 26.x layout).
   Pick and record the dev-server port here.

## Open questions / blockers
- **Rotate the credentials** that were tracked in this template's history before 2026-10-21 (commit `8108c25`;
  gotchas.md 2026-10-21). This repo started from the clean template commit `33f52b4`.
- `PowerShell(...)` permission-rule matching is unverified; treat the PowerShell tool as ungated.

## Do not forget
- `.claude/skills/` and `.github/skills/` are hand-synced copies — edit both or they drift.
- Development commits only `run/` worlds or disposable copies, never the owner's saves or instance in place.
  Commitment also writes a mirror file under the OS user data dir: clean it up after tests.
