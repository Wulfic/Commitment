---
name: test-iterate
description: "Write tests, run them, fix failures, repeat until green — with a hard attempt budget. Use when: implementing a feature, fixing a bug (write the failing test first), a suite is red, adding coverage, doing TDD, writing JUnit, server GameTests or client GameTests for a Minecraft mod."
argument-hint: "What to test, and the layer (JUnit / server GameTest / client GameTest)"
---

# Test-Iterate: Red, Green, and Prove It Can Fail

No feature is done without a test that **fails when the feature breaks**. The loop is:
write test → watch it fail for the right reason → fix the code → green.

The middle step is the one that matters most here. The owner's repos are full of tests
that could not fail: a GameTest class that was never listed and never ran, a guard whose
input file wasn't a Gradle input so it served a cached pass, a "refuses" test that passed
because the mock player was creative. **A test you haven't seen fail has proven nothing.**

Tests are rungs 1, 2 and 5 of the verification ladder in `dev-server`. They are necessary
and **not sufficient**: a gameplay change also runs on the dev server before it's done.

## When to Use

- Implementing any feature, fixing any bug (**the failing test comes first**)
- A red suite
- Verifying a refactor or a port changed nothing observable

## The layers

| Layer | Where | For | Can't see |
|---|---|---|---|
| JUnit | `src/test` | Pure logic, codecs, data-file shape, config parsing, anything with no world | Registries loaded from datapacks, ticks, players |
| Server GameTest | `src/gametest` (Fabric) | Behaviour in a running server: blocks, entities, loot, events, mixins | Real players, real terrain, clients |
| Client GameTest | `src/gametest` client entrypoint | Screens, rendering, input, HUD, the create-world flow, client↔server sync | The shipped jar |

Pick the lowest layer that can actually observe the behaviour. A JUnit test of a codec
won't catch a registry-load failure (the-continent: a density function that read an
unbound holder in its constructor passed JUnit and broke the server). Only a GameTest or
a real server load exercises datapack registry loading.

---

## The Budget

**3 fix attempts per failing test.** On the 4th, stop and ask: *is the test wrong, or is
the design wrong?* **Never delete a failing test to get green.** If it must be skipped,
skip it with a comment naming the reason and the issue.

---

## Procedure

### Step 1: Read the code, decide what matters

The contract, the edges (empty, zero, max, malformed), the failure modes, and for a bug the
exact input that broke it. Test behaviour, not implementation.

### Step 2: Write the test

- JUnit: `src/test/java/<package>/<Class>Test.java`. Name tests after behaviour:
  `aLoweredMaxLevelNeverTouchesExistingItems`, not `test2`.
- GameTest: a method on a class **listed in the gametest `fabric.mod.json` entrypoint list**
  (`fabric-gametest` / `fabric-client-gametest`). An unlisted class compiles and never runs.
- A test that mutates shared server state (config, `/reload`, gamerules) gets its **own test
  environment**, so its batch doesn't leak into others. Readers of shared state assert the
  default first, so a leak fails loudly.

### Step 3: Run it, scoped

```bash
./gradlew test --tests '*ThatClass' > build/agent-test.log 2>&1; echo "EXIT: $?"
./gradlew runGameTest > build/agent-gt.log 2>&1; echo "EXIT: $?"   # see build-run for counting
```

Read the result per `build-run` ("Capturing a result you can trust"). **A new GameTest
class must raise the required-test count by exactly the tests you added.**

### Step 4: Confirm it fails for the right reason

It must fail *because the behaviour is missing*, not a typo, a bad fixture, or a mock
quirk (see the traps below). If the message isn't the one you expected, fix the test first.
**Never change an assertion to match wrong output.**

### Step 5: Fix, re-run, one change at a time

Exceptions rather than assertion failures → `debug-errors`.

### Step 6: Mutation check: prove the test guards the code

For every test that guards a fix or a feature: **break the code, watch the test go red,
restore**. This is the check that caught most of the vacuous tests in the owner's repos.

1. Copy the file to the scratchpad first. `git checkout -- <file>` does nothing for a new,
   untracked file.
2. Apply the mutation and **assert the bytes changed** and the anchor matched exactly once.
   A heredoc that collapsed a backslash once "mutated" nothing and scored a pass.
3. Delete stale reports, run, check the exit code **and** that the named test failed. A
   run with exit ≠ 0 and no named failing test is a harness failure (compile error, Gradle
   never started), not a kill.
4. Restore from the copy and `cmp` it.

When a guard survives a mutation, check for a second guard masking it before calling it
vacuous.

### Step 7: Full suite

`./gradlew build` (every node), or the repo's `scripts/verify-all.sh`. Client changes also
run `:<node>:runClientGameTest` on each node, one at a time. New failures elsewhere are
yours. Don't assume they were "already failing" without checking `git stash` / the last
green commit.

### Step 8: Test the guards, not just the happy path

Any code that deletes, overwrites, resets or migrates (config writers, world-data
migrations, cleanup commands, release scripts) gets tests that feed it the bad input and
**assert the surviving state**: the empty filter refused, the path outside the root
refused, the dry run touching nothing, the backup written before the overwrite. A guard can
throw after it destroyed half the data, so `assertThrows` alone proves nothing.

**Tests never touch real state.** Temp dirs, the GameTest run dir, fixtures. Never
`run/` worlds, never the owner's saves or instance.

---

## GameTest traps (measured in the owner's repos)

**The mock player** (`makeMockServerPlayerInLevel`):

| Trap | Consequence | Fix |
|---|---|---|
| Hard-wired **creative** | Costs are 0, mobs never target it, "refuses" tests pass vacuously | `getAbilities().instabuild = false` / `invulnerable = false`; test targeting on a zombie; `isCreative()` stays true |
| **Never ticked** | Water/lava state, equipment attribute modifiers, held-weapon damage never update | Invoke the specific update in a test-only mixin; give targets ~0.01 health; add modifiers by hand |
| Starts at world **(0, 0, 0)** | Projectiles and drops spawn nowhere near the test | `player.setPos(helper.absoluteVec(...))` |
| Can't be hurt until its client "loads" | A lethal hit leaves it at full health | Mark the connection loaded through a test invoker |
| Starts in the air | Mining speed ÷ 5 | `setOnGround(true)` |

**The test world:**

- Batches share **one live server**; structures are rotated and laid over the same ground,
  never cleared. Lay out in absolute coordinates against the structure box.
- The empty structure is 8 high with a ceiling; arenas sit near world bottom. Zombies
  burn in its daylight (use husks for anything reading health or fire).
- `TestWorldBuilder` defaults to "consistent settings": flat world, `advance_time` off.
  Time or worldgen tests need `setUseConsistentSettings(false)`.
- **`maxTicks` is not a time limit.** The test server runs hundreds of ticks per second.
  Off-thread work needs a wall-clock deadline inside `succeedWhen`, with a large `maxTicks`
  as backstop.
- `assertValueEqual(expected, actual, name)`: expected first, or the message lies.
- A `startSequence()` reports its **last** failing step, not its first.
- Setup commands through `CommandManager.execute` swallow refusals silently. Use the
  dispatcher directly so a refused command throws.
- Rolling a loot table in a test also runs the mod's live loot listeners.
- Seeded "N rolls must include X" tests break when the pool grows. Roll until each case is
  seen, with a cap.
- A missing loot table is `LootTable.EMPTY`, silently.
- A test that fails in its own setup (e.g. `NoSuchFieldError` on a new version) hides every
  real break behind it. On a new version, link-check first (`mc-port`).

**Client GameTests:**

- The client draws only on a tick. Any time-based client state needs a tick after anything
  slow (screenshots included), or better a test clock. **A test never races the wall clock.**
- `pressKey` is a click; held keys (jump, sneak) need `holdKeyFor`.
- `server.execute` work waits for a server tick. Cross-side reads go through an explicit
  delivery barrier (a numbered marker payload echoed over the same connection), never a
  fixed `waitTicks` guess.
- On slow machines (CI's llvmpipe) the integrated server starves and the world never
  loads: `requestGameToSprint` on server start, and **stop the sprint before closing the
  world** or 26.1+ deadlocks on close.
- 1.21.11's client GameTest API (4.3.5) has a network-synchronizer race; disable it with
  `-Dfabric.client.gametest.disableNetworkSynchronizer=true`.
- `I18n.get` runs `String.format`; compare against `Component.translatable(key).getString()`.

**JUnit:**

- Touching a vanilla class before `Bootstrap.bootStrap()` poisons every later test class
  in the JVM (static init failed once = broken for the JVM's life). Bootstrap in the first
  static field's class; static fields initialise in source order.
- `Bootstrap` doesn't load tags. Anything reading tags belongs in a GameTest.
- A resource test reading a path vanilla also ships (`data/minecraft/...`) gets vanilla's
  copy depending on classpath order. Read all `getResources` matches, keep yours.
- Path behaviour differs by OS (`Files.notExists` under a regular file: true on Windows,
  false on Linux). Suspect anything path-shaped until CI has run it.

## Rules

- **Budget of 3** per failing test, then stop and ask.
- **Watch every new test fail**, and mutation-check every guard.
- **Count what ran.** A green run with the old count proves nothing.
- **Never delete a failing test.** Fix it, or skip it with a documented reason.
- **Every destructive path gets a test that asserts nothing was destroyed.**
- **Tests never touch real state.**
- **Green tests are not done for gameplay changes.** Next stop: `dev-server`.
