"""Stage E (TODO E.1b): install a Modrinth modpack version for a dev pack client, checked file by file.

    python -I scripts/pack-fetch.py <version id> <dir under run/packs/>
    python -I scripts/pack-fetch.py swap <pack dir> <mods/old.jar> <version id>   # update one mod, as a player would
    python -I scripts/pack-fetch.py drop <pack dir> <mods/leaf.jar> <why>                # a leaf mod the dev loader refuses
    python -I scripts/pack-fetch.py --self-test

Downloads the .mrpack and every file the client needs, each checked against the sha512 Modrinth publishes, and
unpacks overrides/ then client-overrides/ into <dir>/game. Leaves out the jars the dev run already has (Fabric API,
on Fabric's classpath) and says so. Writes <dir>/pack.txt: the version, the loader it asks for, and every file with
its hash and whether it went in. Builds everything in <dir>.partial and renames it into place last, so <dir> is
either whole or absent. Refuses a <dir> that exists, outside run/packs/, or any pack path that leaves <dir>/game.

Exit codes: 0 installed · 1 a hash or a path was wrong (nothing installed) · 2 nothing done (bad arguments, network).
Standard library only; run it with python -I.
"""
import hashlib
import io
import json
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

API = "https://api.modrinth.com/v2"
USER_AGENT = "wulfic/commitment-stage-e (github.com/wulfic/Commitment)"
# The mrpack format's allowed download hosts (Modrinth's spec).
HOSTS = {"cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com"}
# Mod ids the dev run already loads from its own classpath: a second copy in mods/ is a duplicate mod.
DEV_PROVIDED = {"fabric": {"fabric-api"}, "neoforge": set(), "forge": set()}
REPO = Path(__file__).resolve().parent.parent


class PackError(Exception):
    """A pack that doesn't match what Modrinth says it is: exit 1."""


def get(url):
    host = urllib.parse.urlparse(url).hostname
    if url.startswith(API):
        pass
    elif host not in HOSTS:
        raise PackError(f"{url}: host {host} isn't one the mrpack format allows")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def checked(data, sha512, what):
    actual = hashlib.sha512(data).hexdigest()
    if actual != sha512:
        raise PackError(f"{what}: sha512 {actual[:16]}... isn't the published {sha512[:16]}...")
    return data


def inside(root, rel):
    """root/rel, refusing an absolute path, a drive, a '..' or anything else that resolves outside root."""
    posix = PurePosixPath(rel.replace("\\", "/"))
    if posix.is_absolute() or ".." in posix.parts or ":" in rel or not posix.parts:
        raise PackError(f"pack path {rel!r} leaves the game dir")
    target = (root / Path(*posix.parts)).resolve()
    if root.resolve() not in target.parents:
        raise PackError(f"pack path {rel!r} resolves to {target}, outside {root}")
    return target


def mod_ids(jar):
    """The mod ids a jar declares (fabric.mod.json, quilt.mod.json, META-INF/(neoforge.)mods.toml), best effort."""
    ids = set()
    try:
        with zipfile.ZipFile(io.BytesIO(jar)) as z:
            names = set(z.namelist())
            if "fabric.mod.json" in names:
                ids.add(json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False).get("id", ""))
            for toml in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
                if toml in names:
                    for line in z.read(toml).decode("utf-8", "replace").splitlines():
                        key, _, value = line.partition("=")
                        if key.strip() == "modId":
                            ids.add(value.strip().strip('"'))
    except (zipfile.BadZipFile, ValueError) as e:
        print(f"pack-fetch: couldn't read the mod id of a jar ({e}); keeping it", file=sys.stderr)
    return ids - {""}


def install(version_id, dest):
    version = json.loads(get(f"{API}/version/{urllib.parse.quote(version_id)}"))
    loaders = version["loaders"]
    if len(loaders) != 1 or loaders[0] not in DEV_PROVIDED:
        raise PackError(f"version {version_id} targets {loaders}; pick one for exactly fabric, neoforge or forge")
    loader = loaders[0]
    primary = next((f for f in version["files"] if f["primary"]), version["files"][0])
    mrpack = checked(get(primary["url"]), primary["hashes"]["sha512"], primary["filename"])

    partial = dest.with_name(dest.name + ".partial")
    if partial.exists():
        raise PackError(f"{partial} exists: a run that died midway. Look at it, remove it, run again")
    game = partial / "game"
    game.mkdir(parents=True)
    (partial / "pack.mrpack").write_bytes(mrpack)
    lines = [f"version {version_id}: {version['name']} ({version['version_number']}), loader {loader}",
             f"mrpack {primary['filename']} sha512 {primary['hashes']['sha512']}"]
    with zipfile.ZipFile(io.BytesIO(mrpack)) as z:
        index = json.loads(z.read("modrinth.index.json"))
        lines.append("asks for " + ", ".join(f"{k} {v}" for k, v in sorted(index.get("dependencies", {}).items())))
        for f in index["files"]:
            path = f["path"]
            target = inside(game, path)
            if f.get("env", {}).get("client") == "unsupported":
                lines.append(f"left out (server only)  {path}")
                continue
            data = checked(get(f["downloads"][0]), f["hashes"]["sha512"], path)
            provided = mod_ids(data) & DEV_PROVIDED[loader] if path.endswith(".jar") else set()
            if provided:
                lines.append(f"left out (the dev run has {', '.join(sorted(provided))})  {path}  {f['hashes']['sha512'][:16]}")
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            lines.append(f"installed  {path}  {f['hashes']['sha512'][:16]}")
        # overrides/ first, then client-overrides/ on top (the mrpack format's order for a client).
        for prefix in ("overrides/", "client-overrides/"):
            for name in sorted(n for n in z.namelist() if n.startswith(prefix) and not n.endswith("/")):
                rel = name[len(prefix):]
                target = inside(game, rel)
                target.parent.mkdir(parents=True, exist_ok=True)
                data = z.read(name)
                target.write_bytes(data)
                lines.append(f"override ({prefix.rstrip('/')})  {rel}  {hashlib.sha512(data).hexdigest()[:16]}")
    (partial / "pack.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    rename(partial, dest)
    installed = sum(1 for l in lines if l.startswith("installed"))
    print(f"pack-fetch: installed {version['name']} {version['version_number']} ({loader}) into {dest}: {installed} files"
          f" downloaded, {sum(1 for l in lines if l.startswith('override'))} overrides,"
          f" {sum(1 for l in lines if l.startswith('left out'))} left out (see pack.txt)")


def rename(partial, dest):
    """Windows refuses the rename while a scanner still holds a fresh jar (WinError 5): wait it out, 30 s at most."""
    for attempt in range(1, 31):
        try:
            partial.rename(dest)
            return
        except PermissionError as e:
            if attempt == 30:
                raise
            print(f"pack-fetch: rename refused ({e.strerror}), try {attempt}; retrying in 1 s", file=sys.stderr)
            time.sleep(1)


def swap(pack, old_rel, version_id):
    """Replaces one jar of an installed pack with a Modrinth version's primary file (sha512-checked), the way a player
    updates one mod. The old jar moves to <pack>/swapped/, never deleted; pack.txt records both."""
    game = pack / "game"
    old = inside(game, old_rel)
    if not old.is_file() or not old_rel.startswith("mods/") or not old_rel.endswith(".jar"):
        raise PackError(f"{old_rel} isn't a jar in {game / 'mods'}")
    version = json.loads(get(f"{API}/version/{urllib.parse.quote(version_id)}"))
    primary = next((f for f in version["files"] if f["primary"]), version["files"][0])
    data = checked(get(primary["url"]), primary["hashes"]["sha512"], primary["filename"])
    new = inside(game, "mods/" + primary["filename"])
    if new.exists():
        raise PackError(f"{new} exists already")
    kept = pack / "swapped" / old.name
    kept.parent.mkdir(exist_ok=True)
    if kept.exists():
        raise PackError(f"{kept} exists already; a swap of this jar was done before")
    new.write_bytes(data)
    old.rename(kept)
    with open(pack / "pack.txt", "a", encoding="utf-8") as txt:
        txt.write(f"swapped  {old_rel} -> mods/{primary['filename']} (version {version_id}, {version['version_number']})"
                  f"  {primary['hashes']['sha512'][:16]}; the old jar is in swapped/\n")
    print(f"pack-fetch: swapped {old_rel} for {primary['filename']} ({version['version_number']}); old jar kept in {kept}")


def drop(pack, old_rel, why):
    """Takes one leaf mod out of an installed pack (one that won't load on the dev loader, and nothing depends on). The
    jar moves to <pack>/swapped/, never deleted; pack.txt records it and why."""
    if not why.strip():
        raise PackError("a drop needs a reason; pack.txt records it")
    game = pack / "game"
    old = inside(game, old_rel)
    if not old.is_file() or not old_rel.startswith("mods/") or not old_rel.endswith(".jar"):
        raise PackError(f"{old_rel} isn't a jar in {game / 'mods'}")
    kept = pack / "swapped" / old.name
    kept.parent.mkdir(exist_ok=True)
    if kept.exists():
        raise PackError(f"{kept} exists already")
    old.rename(kept)
    with open(pack / "pack.txt", "a", encoding="utf-8") as txt:
        txt.write(f"dropped  {old_rel} ({why}); the jar is in swapped/\n")
    print(f"pack-fetch: dropped {old_rel}; kept in {kept}")


def checked_pack(arg):
    pack = (REPO / arg).resolve() if not Path(arg).is_absolute() else Path(arg).resolve()
    packs = (REPO / "run" / "packs").resolve()
    if packs not in pack.parents or not (pack / "pack.txt").is_file():
        raise ValueError(f"{pack} isn't an installed pack under {packs}")
    return pack


def checked_dest(arg):
    dest = (REPO / arg).resolve() if not Path(arg).is_absolute() else Path(arg).resolve()
    packs = (REPO / "run" / "packs").resolve()
    if packs not in dest.parents:
        raise ValueError(f"{dest} isn't under {packs}")
    if dest.exists():
        raise ValueError(f"{dest} exists; refusing to install over it")
    return dest


def self_test():
    failures = 0

    def expect(ok, name):
        nonlocal failures
        print(("ok   " if ok else "FAIL ") + name)
        failures += 0 if ok else 1

    def refused(fn):
        try:
            fn()
            return False
        except (PackError, ValueError):
            return True

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "game"
        root.mkdir()
        expect(inside(root, "mods/a.jar") == (root / "mods" / "a.jar").resolve(), "a plain path goes inside")
        expect(refused(lambda: inside(root, "../escape.jar")), "a '..' path is refused")
        expect(refused(lambda: inside(root, "mods/../../escape.jar")), "a nested '..' path is refused")
        expect(refused(lambda: inside(root, "/etc/passwd")), "an absolute path is refused")
        expect(refused(lambda: inside(root, "C:/Windows/x.dll")), "a drive path is refused")
        expect(refused(lambda: inside(root, "")), "an empty path is refused")
        expect(refused(lambda: checked(b"x", "0" * 128, "x")), "a wrong hash is refused")
        expect(checked(b"x", hashlib.sha512(b"x").hexdigest(), "x") == b"x", "the right hash passes")
        expect(refused(lambda: get("https://evil.example/x.jar")), "a host outside the mrpack list is refused")
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("fabric.mod.json", '{"id": "fabric-api", "version": "1"}')
        expect(mod_ids(jar.getvalue()) == {"fabric-api"}, "a Fabric jar's id is read")
    expect(refused(lambda: checked_pack("run/fabric")), "a swap outside run/packs is refused")
    expect(refused(lambda: checked_pack("run/packs")), "a swap in a dir that isn't an installed pack is refused")
    with tempfile.TemporaryDirectory() as tmp:
        pack = Path(tmp)
        (pack / "game" / "mods").mkdir(parents=True)
        (pack / "game" / "config.txt").write_text("x")
        expect(refused(lambda: swap(pack, "config.txt", "x")), "a swap of a file outside mods/ is refused")
        expect(refused(lambda: swap(pack, "mods/../config.txt", "x")), "a swap with '..' is refused")
        expect(refused(lambda: swap(pack, "mods/missing.jar", "x")), "a swap of a missing jar is refused")
        (pack / "game" / "mods" / "leaf.jar").write_bytes(b"jar")
        expect(refused(lambda: drop(pack, "mods/leaf.jar", " ")), "a drop with no reason is refused")
        expect(refused(lambda: drop(pack, "config.txt", "why")), "a drop of a file outside mods/ is refused")
        (pack / "pack.txt").write_text("")
        drop(pack, "mods/leaf.jar", "test")
        expect((pack / "swapped" / "leaf.jar").is_file() and not (pack / "game" / "mods" / "leaf.jar").exists(),
               "a drop keeps the jar in swapped/")
    expect(refused(lambda: checked_dest("run/elsewhere/x")), "a dir outside run/packs is refused")
    expect(refused(lambda: checked_dest("run/packs/../x")), "a dir that resolves outside run/packs is refused")
    expect(refused(lambda: checked_dest("run/packs")), "run/packs itself is refused")
    print(f"pack-fetch self-test: {'all passed' if failures == 0 else f'{failures} failed'}")
    return 1 if failures else 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if len(argv) == 4 and argv[0] in ("swap", "drop"):
        try:
            pack = checked_pack(argv[1])
            (swap if argv[0] == "swap" else drop)(pack, argv[2], argv[3])
        except ValueError as e:
            print(f"pack-fetch: {e}", file=sys.stderr)
            return 2
        except PackError as e:
            print(f"pack-fetch: FAIL: {e}. Nothing swapped", file=sys.stderr)
            return 1
        except OSError as e:
            print(f"pack-fetch: NOTHING DONE (exit 2): {e}", file=sys.stderr)
            return 2
        return 0
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        dest = checked_dest(argv[1])
    except ValueError as e:
        print(f"pack-fetch: {e}", file=sys.stderr)
        return 2
    try:
        install(argv[0], dest)
    except PackError as e:
        print(f"pack-fetch: FAIL: {e}. Nothing installed; {dest.name}.partial is left to look at", file=sys.stderr)
        return 1
    except OSError as e:
        print(f"pack-fetch: NOTHING DONE (exit 2): {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
