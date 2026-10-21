# Commitment

Commit a world to the setup you play it with. After `/commit`, the world refuses to open if its mods,
resource packs or config change, and shows what changed. Changing them anyway takes a 24-hour unlock.

**Status: in development.** Nothing is released yet, and the commands below don't exist yet. The plan is in
[TODO.md](TODO.md).

## Supported versions

The current band is **26.x**: Minecraft 26.1 to 26.3, on Fabric, Quilt, NeoForge and Forge. Fixes and features
go to the current band only. Older bands are frozen: their releases stay up, but they get no fixes.

Each release has one jar per Minecraft band and loader, named `commitment-<version>+<loader>-mc<band>.jar`. Pick
the loader you run and the band that holds your Minecraft version:

| Minecraft | Fabric and Quilt | NeoForge | Forge |
|---|---|---|---|
| **26.1 – 26.1.2** | `mc26.1`: 26.1 – 26.1.2 | `mc26.1.2`: 26.1.2 only | `mc26.1.2`: 26.1.2 only |
| **26.2** | `mc26.2` | `mc26.2` | `mc26.2` |
| **26.3** | `mc26.3` | `mc26.3` | `mc26.3` |

Quilt runs the Fabric jar, so there is no separate Quilt jar. Quilt Loader has only betas for 26.x
(0.31.0-beta.4). NeoForge and Forge have no stable build for 26.1 or 26.1.1, so their 26.1 jars run on 26.1.2
only.

## License

MIT, see [LICENSE](LICENSE).
