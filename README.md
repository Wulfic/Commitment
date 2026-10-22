# Commitment

Commit a world to the setup you play it with. After `/commit`, the world refuses to open if its mods,
resource packs or config change, and shows what changed. Changing them anyway takes a 24-hour unlock.

**Status: in development.** Nothing is released yet, and the commands below don't exist yet. The plan is in
[TODO.md](TODO.md).

## Supported versions

The current band is **26.x**: Minecraft 26.1 to 26.3, on Fabric, Quilt, NeoForge and Forge. Minecraft
**1.21.11 on Fabric** is supported too, and gets the same fixes. Fixes and features go to these only. Older bands
are frozen: their releases stay up, but they get no fixes.

Each release has one jar per Minecraft band and loader, named `commitment-<version>+<loader>-mc<band>.jar`. Pick
the loader you run and the band that holds your Minecraft version:

| Minecraft | Fabric and Quilt | NeoForge | Forge |
|---|---|---|---|
| **1.21.11** | `mc1.21.11`: Fabric only | — | — |
| **26.1 – 26.1.2** | `mc26.1`: 26.1 – 26.1.2 | `mc26.1.2`: 26.1.2 only | `mc26.1.2`: 26.1.2 only |
| **26.2** | `mc26.2` | `mc26.2` | `mc26.2` |
| **26.3** | `mc26.3` | `mc26.3` | `mc26.3` |

Quilt runs the Fabric jar, so there is no separate Quilt jar. Quilt Loader has only betas for 26.x
(0.31.0-beta.4). NeoForge and Forge have no stable build for 26.1 or 26.1.1, so their 26.1 jars run on 26.1.2
only.

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
