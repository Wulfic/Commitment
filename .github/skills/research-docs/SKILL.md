---
name: research-docs
description: "Pull live, up-to-date documentation and version facts for Minecraft modding: Fabric/NeoForge/Forge APIs, Loom and other build plugins, library APIs (YACL, Mod Menu, Cloth, Carpet), dependency pins, and vanilla behaviour. Use when: implementing against any third-party API, bumping or adding a dependency, checking what a Minecraft version changed, or when training data may be stale. Uses context7 MCP, metadata endpoints, and the jars themselves."
argument-hint: "Library or topic and what you need to know (e.g. 'Fabric API loot table events in 0.141')"
---

# Research Docs: Live Docs, Metadata, and the Jar

Training data is stale for this ecosystem by construction: Minecraft ships a version every
few months, renames classes, changes data formats, and the loaders follow. Pins recalled
from memory have failed to resolve; API shapes recalled from memory have compiled against
the wrong version. **Look it up, and pin what you learn to a version.**

## When to Use

- Any "how do I use X" for a loader API, build plugin, or library
- Before adding or bumping a dependency
- Checking how vanilla does something, or what changed in a Minecraft version
- Migration guides and changelogs (Fabric API, NeoForge, Loom, Stonecutter)

## Sources, most authoritative first

1. **The jar actually on the classpath.** For Minecraft: `javap` or decompiled sources of
   the exact version (`mc-port`, "Where the facts live"). For a library: its sources jar at
   the version `./gradlew dependencies --configuration compileClasspath` resolves. The
   installed artifact is the version that runs, so it outranks every doc.
2. **Official metadata for versions:** `meta.fabricmc.net/v2/versions/...`,
   `maven.fabricmc.net`, `maven.neoforged.net`, `files.minecraftforge.net` promotions, the
   Gradle Plugin Portal, and the official example mod / MDK branch for the Minecraft
   version. Verify a coordinate resolves (`curl -s -o /dev/null -w "%{http_code}" <pom>`)
   before committing it.
3. **context7** for prose docs and examples (Fabric docs, library READMEs). Procedure below.
4. **Native web search / fetch** for changelogs, Fabric/NeoForge blog posts on a new
   version, issue trackers. Prefer primary sources (the project's own site or repo).

## context7

```
mcp__context7__context7_get_tool_schema(tool_name)   # always first, per operation
mcp__context7__context7_invoke_tool(tool_name, tool_input)
```

Tools: `resolve-library-id`, then `query-docs`.

```json
tool_name: "resolve-library-id"
tool_input: { "query": "<plain-english library>", "libraryName": "<project name if known>" }
```
```json
tool_name: "query-docs"
tool_input: { "libraryId": "<id from step 1>", "query": "<specific question>" }
```

Docs often describe the newest version. Check which Minecraft / API version a page is
for before applying it to an older band.

## Apply and cite

- Quote or summarise the relevant section, and **name the version** it applies to.
- Docs that contradict the jar lose. Docs that contradict your memory win.
- If they disagree with what you expected, that's a `gotchas.md` entry via `save-memory`.
  Surprising API behaviour will surprise the next session, and the next band.

## Rules

- **Never invent an API signature or a dependency version from memory.**
- **Pin to a version**, in prose and in a comment next to the pin ("pinned 2026-10-20 from
  meta.fabricmc.net").
- **Display versions are not coordinates.** Modrinth's `version_number` carried a loader
  suffix the Maven artifact didn't. Fetch the POM and read the status code.
- The shared Gradle cache holds versions from the owner's other mods. Read the version
  *this* build resolves, not the newest one there.
