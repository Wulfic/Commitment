# Commitment

Commit a world to the setup you play it with. After `/commit`, the world refuses to open if its mods,
resource packs or config change, and shows what changed. Changing them anyway takes a 24-hour unlock.

It's for the player who wants a playthrough to stay honest: no adding a mod halfway through to skip a grind, no
swapping a config to make a boss easier, no turning the difficulty down after a bad death. It's friction you choose,
not security (see [What it can't stop](#what-it-cant-stop)).

Download a jar from the [Releases](https://github.com/Wulfic/Commitment/releases) page. The changes in each release are
in [CHANGELOG.md](CHANGELOG.md).

## How it works

1. Open the world with the setup you want to keep, and run **`/commit`**. It shows what will be locked ("42 mods,
   3 resource packs, 118 config files") and asks you to confirm.
2. Run **`/commit confirm`** within 60 seconds. The world is now committed.
3. From then on, every time the world opens, Commitment compares the setup with the committed one. If anything was
   added, removed or changed, the world doesn't load: a screen lists what changed, with **Back** and **Start 24-hour
   unlock**. Put the setup back and the world opens as usual.

**`/commit status`** shows whether the world is committed and how far along an unlock is.

Only the singleplayer host can run these commands (cheats don't need to be on). On a dedicated server, it's the console
or an operator with level 4.

### What's locked

- The loader and its version, and the Minecraft version.
- Every mod, by its jar's contents. Updating Commitment itself doesn't count as a change.
- `config/` and `defaultconfigs/`, by content, and the world's `serverconfig/` (Forge and NeoForge).
- The enabled resource packs, in order, by content.
- The world's datapacks.
- The world's game settings: difficulty (and whether it's locked), every game rule, whether cheats are allowed, and
  the default game mode. These aren't refused at world open. A change is put back within a second, with
  "Commitment: difficulty is locked" in chat, whether it came from a command, the difficulty button, Open to LAN or
  another mod.
- On a dedicated server, every key of `server.properties`. A changed key is logged by name, never by value
  (`rcon.password` lives there), and the server stops before loading the world, with a non-zero exit code.

### What's never locked

- Every `options.txt` setting except the resource pack list: video, audio, controls, keybinds, language.
- Shader packs, and which one you use.
- The config files of video, shader and audio mods: Sodium, Iris, Distant Horizons, ImmediatelyFast, Entity
  Culling, Dynamic FPS, Sound Physics Remastered, Presence Footsteps, AmbientSounds, and Language Reload's language.
- Files mods rewrite on their own: process lock files (`*.lock`) and Crash Assistant's localization overrides.

## Getting out

The only way out of a commitment is the 24-hour unlock:

1. Run **`/unlock`**, then **`/unlock confirm`** within 60 seconds, or press **Start 24-hour unlock** on the screen
   that refused the world. The timer starts: "Commitment: unlock available at 2026-11-02 18:30 CET (24h)".
2. Wait 24 hours. The world stays committed while you wait, and the refusal screen shows the countdown.
   **`/unlock cancel`** (or **Cancel unlock** on the screen) stops the timer.
3. The next time the world opens after the 24 hours, the lock lifts by itself: "Commitment: lock lifted, run
   /commit to lock again". The setup is yours to change, and you can remove Commitment.

The timer uses internet time (see [Privacy](#privacy)), so moving your computer's clock doesn't shorten it. With no
internet connection, an unlock can't start or finish, and the world stays locked until it can check.

**Don't remove Commitment from a committed world.** The world still opens, but a datapack Commitment left in it blinds
and freezes every player, with "This world is committed. Commitment is missing: reinstall it to play". Nothing in the
world is damaged, and the effects wear off three seconds after Commitment is back.

## What it can't stop

You own every file, so Commitment can make getting around it a chore, not impossible.

| You try | What happens |
|---|---|
| Remove or disable Commitment | The world opens, but the guard datapack blinds and freezes you until Commitment is back |
| Put Commitment back | The lock is back, and the unlock timer where it was |
| Delete or edit `config/` | Fabric, Quilt and Forge write their config files again before the check, so a deleted `config/` opens the world. NeoForge writes one of its files later, so the world is refused until `config/` is back, until you open any other world once, or until the unlock runs out. An edited config file is refused on every loader |
| Delete the guard datapack | Commitment rebuilds it the next time the world opens |
| Delete the world's lock record (`data/commitment.dat`) | Restored from the copy Commitment keeps outside the game folder |
| Edit the lock record | It's signed, so an edited copy counts as damaged and the good copy is written back. Edit both copies and the world counts as tampered: locked, and only the 24-hour unlock gets you out |
| Move the computer's clock forward | Still locked: the timer uses internet time |
| `/datapack disable` on the guard, with cheats on | Works only with Commitment removed. With it, the pack is turned back on the next time the world opens |
| `/tick freeze` with Commitment removed | The guard stops, but so does the world |
| `/difficulty`, `/gamerule`, the difficulty button, Open to LAN with cheats | Put back within a second |
| Edit difficulty or game rules in `level.dat` with Commitment removed | Put back the next time the world opens with Commitment |
| Edit `server.properties` on a committed server | The server logs the changed keys and stops |

**What still works**, and is meant to: removing Commitment **and** deleting `datapacks/commitment_guard.zip` from the
world folder. So does rewriting both copies of the lock record with a valid signature, which takes the key out of a
decompiled jar. Either takes knowing where the world folder is and what to delete, which is the bar Commitment sets.

Commitment never writes to `level.dat` or any other file of the game's. Everything it adds is three small files: the
lock record, its copy outside the game folder, and the guard datapack. Minecraft records the guard as enabled in its
own save, as it does for any datapack. When a lock lifts, Commitment deletes those three files (only files that carry
its marker and that world's lock id), and logs each path it deleted.

## Supported versions

The current band is **26.x**: Minecraft 26.1 to 26.3, on Fabric, Quilt, NeoForge and Forge. Minecraft
**1.21.11 on Fabric** is supported too, and gets the same fixes. Fixes and features go to these only. Older bands
are frozen: their releases stay up, but they get no fixes.

Each release has one jar per Minecraft band and loader, named `commitment-<version>+<loader>-mc<band>.jar`. Pick
the loader you run and the band that holds your Minecraft version:

| Minecraft | Fabric and Quilt | NeoForge | Forge |
|---|---|---|---|
| **1.21.11** | `mc1.21.11`: Fabric only | — | — |
| **26.1 – 26.1.2** | `mc26.1`: 26.1 – 26.1.2 | `mc26.1.2`: 26.1.2 only, NeoForge 26.1.2.114 or newer | `mc26.1.2`: 26.1.2 only, Forge 64.0.0 or newer |
| **26.2** | `mc26.2` | `mc26.2`: NeoForge 26.2.0.90 or newer | `mc26.2`: Forge 65.0.0 or newer |
| **26.3** | `mc26.3` | `mc26.3`: NeoForge 26.3.0.51-beta or newer | `mc26.3`: Forge 66.0.0 or newer |

The Fabric jar needs Fabric Loader 0.19.5 or newer and Fabric API. Quilt runs the Fabric jar, so there is no
separate Quilt jar; Quilt Loader has only betas for 26.x (0.31.0-beta.4). NeoForge and Forge have no stable build for
26.1 or 26.1.1, so their 26.1 jars run on 26.1.2 only. NeoForge 26.1.2 and 26.2 builds older than the ones above
bundle a MixinExtras that crashes on Commitment at startup, so the jars ask for those builds at least.

Commitment locks a server's own setup, not the mods its players' clients run.

## Privacy

Commitment goes online for one thing: the unlock timer uses internet time, so moving your computer's clock can't
shorten it. It asks only when you run `/unlock confirm`, and when you open a world while an unlock is running. It
sends a plain HEAD request to each of these, and reads the `Date` header of the reply:

- `www.google.com`
- `www.cloudflare.com`
- `www.microsoft.com`

Two of them have to agree within five minutes. The request carries nothing about you, your world or your mods,
though like any web request it shows those hosts your IP address. With no internet connection, an unlock can't
start or finish, and the world stays locked.

Commitment also keeps a copy of each world's lock outside the game folder, so deleting the mod or its config
doesn't unlock anything: `%LOCALAPPDATA%\Commitment\locks` on Windows, `~/Library/Application Support/Commitment/locks`
on macOS, and `$XDG_DATA_HOME/commitment/locks` (or `~/.local/share/commitment/locks`) on Linux. Each file is a few KB.

## License

MIT, see [LICENSE](LICENSE).
