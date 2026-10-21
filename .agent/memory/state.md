# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-21
**Branch:** `main`, always (decisions.md 2026-10-21).

## Where we are
This repo is the **Minecraft mod template**. No mod code yet: agent rules (`AGENTS.md`
`Minecraft Mod Development`), 13 skills (`dev-server` and `mc-port` new; the rest Gradle/MC
first), and secrets untracked so a repo made from it can start public.

## When a new mod repo is created from this template
1. Fill in `README.md`: what the mod does, **the current band and its Minecraft versions,
   and that older bands get no fixes** (decisions.md 2026-10-21).
2. Copy `.env.example` → `.env`, `.mcp.example.json` → `.mcp.json`,
   `.claude/mcp.vscode-reference.example.json` → `.claude/mcp.vscode-reference.json`; fill in.
3. Scaffold (Tier 2 plan): versions and pins from metadata (`mc-port`), then the dev server
   per `dev-server` Part A: own port (not 25565, not another repo's), `run/server` +
   `run/client`, EULA task, `scripts/dev-server-smoke.sh`. Record the port here.
4. Rewrite this file for that project.

## Open questions / blockers
- **Rotate the credentials** that were tracked before 2026-10-21 (GitHub PAT, context7 key,
  the `.env` passwords). They're still in commit `8108c25` on `origin/main`, and in the
  other mod repos that copied this template (gotchas.md 2026-10-21).
- The template repo itself is private and its history holds those secrets. Making **it**
  public needs a history rewrite or a fresh repo: the owner's call.
- `PowerShell(...)` permission-rule matching is unverified; treat the PowerShell tool as ungated.

## Do not forget
- `.claude/skills/` and `.github/skills/` are hand-synced copies — edit both or they drift.
