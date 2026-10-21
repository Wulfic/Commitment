---
name: mc-port
description: "Target, port to, or add a Minecraft version or mod loader: pins, mappings, Loom/ModDevGradle/ForgeGradle setup, Stonecutter bands, the current-band support policy, finding what changed between versions, mixin targets that move. Use when: scaffolding a mod, bumping Minecraft or a dependency, adding a band or a loader (Fabric, Quilt, NeoForge, Forge), freezing a band, a mixin fails to apply or applies to the wrong thing, or someone asks for a back-port."
argument-hint: "The port (e.g. 'add 26.3 band', 'add NeoForge', 'bump Fabric API')"
---

# MC Port: Versions, Loaders, Mappings

Minecraft changes underneath you between versions, and the compiler catches only some of
it. The dangerous half of every port in the owner's repos was the half that compiled and
applied cleanly: a mixin on the wrong anonymous class, an `Optional == null` guard that is
always false, a loot file whose stale keys parse as *nothing*. **Facts come from the jar,
never from memory**, and a band is done only when its own build, GameTests and dev server
are green.

## When to Use

- Scaffolding a new mod (choosing versions, loaders, mappings, build plugins)
- Bumping Minecraft, a loader, Fabric API, or any mod dependency
- Adding a version band or a loader
- A mixin fails to apply, or applies and does nothing
- Back-porting a fix from the newest band to an older one

## Where the facts live

| Question | Source (in order) |
|---|---|
| A class / method / field signature | `javap -p -c` on the version's Minecraft jar in the Loom cache (`.gradle/loom-cache/` or `~/.gradle/caches/fabric-loom/minecraftMaven/...`) |
| How vanilla does something | Decompiled sources: `./gradlew genSources` (Vineflower, ~8 min) → `minecraft-merged-deobf-<v>-sources.jar`. Beats `javap` for "how", beats docs for everything |
| A NeoForge/Forge-patched method | `javap` on the **patched** jar (`build/moddev/artifacts/minecraft-patched-*.jar`), not the patched sources |
| Vanilla data formats (loot, tags, advancements, worldgen) | The files inside that version's jar (`data/minecraft/...`). Read one real file before writing yours |
| Registry ids for items/blocks | The version's data-generator dump, never `javap` field names, lang keys or model paths |
| Which dependency version is actually on the classpath | `./gradlew dependencies --configuration compileClasspath`. The shared Gradle cache holds newer versions from other repos; reading the newest `-sources.jar` there misled enchanting-2 |
| Loader / Fabric API / NeoForge / Forge versions | `meta.fabricmc.net`, `maven.fabricmc.net`, `maven.neoforged.net`, `files.minecraftforge.net` promotions, and the official example-mod / MDK branch for that version. **Never bump from memory**; date the pin in a comment |
| Fabric API usage | context7 (`research-docs`), then the module's sources at the resolved version |

## Pins and build plugins

- **Pin from metadata, and check the coordinate resolves** before committing:
  `curl -s -o /dev/null -w "%{http_code}" <pom-url>` for each new pin. Modrinth's
  `version_number` is a display string, not the Maven coordinate (`26.3.158+fabric`
  404s; `26.3.158` is right). There's no general rule: some projects' display string *is*
  the coordinate. The old pin's shape is a free spec for the new one.
- **Loom plugin id depends on obfuscation.** ≤ 1.21.11 is obfuscated:
  `net.fabricmc.fabric-loom-remap` + mappings. 26.x ships unobfuscated:
  `net.fabricmc.fabric-loom`, no mappings. Mixing bands: `loom-back-compat` picks per node,
  so never name the plugin in a shared script. A plain (non-Stonecutter) subproject needs
  `loomx.unobfuscated=true` or it tries to remap a 26.x game.
- **Use Mojang official mappings**, even on 1.21.11. 1.21.11 is the last Yarn release; with
  official names a 26.x port is a version bump, not a rename. 1.21.11's official names
  already say `Identifier` (was `ResourceLocation`).
- **Loom 1.18.x needs a Java 25 JVM to run Gradle**, even when the mod targets Java 21
  (`options.release = 21`). Gradle's toolchain and the game's Java are separate questions.
- `gradlew` committed from Windows lacks the exec bit: `git update-index --chmod=+x gradlew`.
  `.gitignore`'s `*.jar` drops `gradle/wrapper/gradle-wrapper.jar` unless un-ignored.
- Cold cache + `org.gradle.parallel=true` gets HTTP 429 from Maven Central; retry with
  `--max-workers=1`.

## Bands (several Minecraft versions)

- **One jar per band, bands as Stonecutter nodes** on one branch (the-continent,
  enchanting-2). The source is committed with one node active (`vcsVersion`); other bands'
  code sits in `//? if >=26.x` blocks. To edit a band as plain Java:
  `./gradlew "Set active project to <band>"`, and **`./gradlew "Reset active project"`
  before every commit**.
- With Stonecutter, **a bare task name runs in every node**: `./gradlew runServer` starts
  every node's server on one port. Qualify run tasks (`:<node>:runServer`). Build output is
  per node (`versions/<node>/build/`).
- The root build script becomes Stonecutter's controller (`stonecutter.gradle`); a root
  `build.gradle` silently stops running. Two branches with same-named nodes (`:common:26.2`,
  `:fabric:26.2`) resolve to each other unless each branch has its own `group`.
- Per-band data goes in the node's overlay (`versions/<band>/src/main/resources`). Data
  formats change between versions (see below), so data is upgraded or overlaid per band,
  under a test that reads the format.
- **A jar's declared range is a claim; prove each version in it.** A band compiled against
  26.1.2 and declaring `>=26.1 <26.2` must be GameTested or smoked on 26.1 and 26.1.1 too.
  A mixin mod crashes at startup on an untested version, so never declare an open range.
## Support policy: the current band only

**Owner ruling, 2026-10-21:** bug fixes, support and back-ports apply to the **current band
only** (today: the **26.x** band). Older bands, such as a 1.21.11 line, are **frozen**: their
published releases stay downloadable, and they get no fixes, no features and no back-ports
unless the owner explicitly asks for that specific one.

- **Fix on the current band, and stop there.** Don't propagate to a frozen band, and don't
  open work to "keep it in step". mcMMO spent whole sessions on drift audits and translated
  back-ports across six bands before archiving them; that cost is what this rule avoids.
- **The current band can hold several Minecraft versions** (26.1, 26.2, 26.3 as Stonecutter
  nodes). "Current band" means all of them: a fix is done when every node of the current
  band is green, GameTested and smoked on the dev server.
- **The README always says which band is supported.** It names the current band, the
  Minecraft versions it covers, and that older bands are frozen with no fixes. Update it in
  the **same commit** that changes the current band, adds a version to it, or freezes a
  band. Players read the README to learn whether their bug will be fixed, and CI will be
  enforcing this.
- **Pin the README to the build with a test** (the-continent's `ReadmeBandsTest` pattern):
  the bands and versions the README states must equal what `gradle.properties` builds.
  Declare `README.md` and `gradle.properties` as `test` inputs, or an edit to either serves
  a cached pass.
- **When the owner does ask for a back-port:** it needs translation, not a cherry-pick. A
  clean cherry-pick says nothing; only that band's own build does. Between yarn and official
  bands, a hunk whose context avoids renamed lines applies silently with the wrong names.
  Read the band's own file for spellings, never resolve a conflict with "take newest", and
  name the source commit in a `Backport-of: <sha>` trailer.

## Loaders

**Common code stays loader-free** (a `common` project each loader compiles into its own
jar; a test scans common's sources for `net.fabricmc` / `net.neoforged` /
`net.minecraftforge` / `org.quiltmc` imports). Differences go behind a small platform seam
(registration, attachments, events, networking).

| Loader | Traps that cost a session |
|---|---|
| Fabric | A new GameTest class runs only once listed in the gametest `fabric.mod.json` entrypoints. `fabric-loader-junit` puts every FAPI `fabric.mod.json` on the test classpath (read **all** `getResources` matches), and needs `modImplementation fabric-loader` or tests die with "ASM not detected". A Loom-remapped dependency's nested jars aren't loaded in dev; a plain jar's are. |
| Quilt | Runs the Fabric jar. Quilt meta's installer hashes were stale; verify against Maven's own `.sha1`/`.sha256`. |
| NeoForge | Built-in registries are **frozen at mod construction**: register through `RegisterEvent`/`DeferredHolder`. `setData(type, null)` throws; mod ids can't contain `-`. 26.x has no `global_loot_modifiers.json`: every file in `loot_modifiers/` is one modifier. Patched methods change signatures: read them with `javap`. **Grep the GameTest log for ERROR after a green run**; 340/340 hid an ERROR on every start. |
| Forge | **No `pack.mcmeta` in the jar → the mod's data never loads**, every step a WARN, and the world silently comes up vanilla. Forge's Mixin rejects `compatibilityLevel: JAVA_25` (write `JAVA_21`); mixins need a `MixinConfigs` manifest attribute and `--mixin.config` in dev. Access transformers need the plugin **and** `accessTransformers = files(<path>)`. ForgeGradle 7 registers `run*` tasks late (`tasks.matching`, not `named`). A Forge GameTest server whose mod loading fails **never exits**: give the task a timeout. Chunk capabilities aren't saved on 26.3. No `FakePlayer`. |

## Finding what changed between versions

1. **Link check first, GameTests after.** On 26.2, 80 of 83 GameTest failures were the test
   jar's own `NoSuchFieldError` during setup, hiding every real mod break behind them. A
   static link check (resolve every reference in the mod and test jars against the target
   version) found the masked ones.
2. **A link check proves a target *exists*, not that the injected method still *calls* it.**
   26.3 swapped `setBlock(pos, state, 3)` for `setBlockAndUpdate` inside methods we
   injected into; `setBlock` still existed, the link check passed, and the server crashed
   at load ("Scanned 0 target(s)"). Diff the target method's body between versions, or run
   it.
3. **Anonymous classes and lambdas are numbered by source order.** `GrindstoneMenu$1` was a
   field initializer, not the first slot; 26.1 removed a class before
   `DispenseItemBehavior$4`, making it `$3`, and the old `$4` still existed and applied,
   silently, to the wrong behaviour. Pick every `$N` and `lambda$x$N` target with
   `javap -c -p` on **that version's** jar ("which `$N` calls the method we wrap"), behind a
   per-band condition. Never "it applied".
4. **Signature changes the compiler accepts:** a method that now returns `Optional` still
   compiles against `== null` (always false); a field that went private gets a setter
   whose meaning changed (`invulnerableTime` vs `damageCooldownTime` on 26.3). After a
   bump, sweep every `== null` / `!= null` on a vanilla return value against the new
   return types.
5. **Strings the compiler can't see:** `@At` descriptor strings keep old class names after
   an import replacement fixed the compile (26.3 moved `RenderPipeline`). Search descriptors
   too.
6. **Data formats change and stale keys are ignored, not rejected.** 26.3 loot reads one
   `condition`/`modifier` dispatched on `type`; a 1.21.11-style `conditions: [...]` loads
   with **no condition at all** (every alternative matched). 26.2 keyed entity predicates by
   sub-predicate id. 26.3 advancements: a visible root needs a background, a child must not
   have one, or the whole registry (and every world) fails to load. 26.1 moved worldgen
   settings out of `level.dat` into `data/minecraft/world_gen_settings.dat`. **On any new
   version: read one vanilla file of each kind you ship from that jar**, and pin the format
   with a test.
7. Input and rendering: 26.3 dropped GLFW (`InputConstants.MOUSE_BUTTON_LEFT` is **1**
   there), and its renderer falls back to Vulkan under Xvfb (CI needs `mesa-vulkan-drivers`).
   26.2 moved screens, toasts and the hide-HUD flag onto `Minecraft.gui`.
8. Fabric's client GameTest API changes per version too (4.3.5 on 1.21.11 → 6.x on 26.2+);
   `javap` every call on every band's resolved version before using it.

## Rules

- **Facts from the jar.** `javap` or decompiled sources of the exact version, never recall.
  A dated observation ("removed in 26.1, javap") is fine; an undated claim isn't.
- **Never write a comment that says what version this build targets.** It's false on every
  other band the moment one exists, and nothing reads comments. State the code fact instead.
- **Pins from metadata, dated, with the coordinate checked** before the commit.
- **A band is done when its build, GameTests and dev server are green**, not when it compiles.
- **Fixes and support go to the current band only.** Frozen bands get nothing unless the
  owner asks. The README states the current band, in the same commit that changes it.
- Record every per-version difference you found in `gotchas.md`: the next band hits it too.
