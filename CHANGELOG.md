# Changelog

## 1.0.0

The first release.

- `/commit` and `/commit confirm` lock a world's setup: the loader, Minecraft, every mod, `config/`,
  `defaultconfigs/`, the world's `serverconfig/` and datapacks, the enabled resource packs, and on a dedicated server
  `server.properties`. `/commit status` shows the state.
- A changed setup refuses the world: the client lists what changed, with Back and Start 24-hour unlock; a dedicated
  server logs the changes and stops.
- The world's difficulty, game rules, cheats and default game mode are put back within a second of a change.
- `/unlock`, `/unlock confirm` and `/unlock cancel`: a 24-hour unlock timed by internet time. The lock lifts the
  next time the world opens after it.
- With Commitment removed, a guard datapack blinds and freezes every player in a committed world until it's back.
  The lock record has a copy outside the game folder, and either copy restores the other.
- Video, shader and audio settings and their mods' config files are never locked.
- Jars for Minecraft 1.21.11 (Fabric) and 26.1 to 26.3 (Fabric, Quilt, NeoForge, Forge). NeoForge 26.1.2 needs
  26.1.2.114 or newer and 26.2 needs 26.2.0.90 or newer: older builds bundle MixinExtras 0.5.4, which crashes on
  Commitment at startup. NeoForge 26.3 runs from 26.3.0.51-beta, Forge from 64.0.0, 65.0.0 and 66.0.0.
