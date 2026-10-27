#!/usr/bin/env bash
# Stage E (TODO E.1b): copy the owner's Prism instance into a dev pack dir, reading it only. Takes mods/ (without
# Fabric API: the dev run has it on its classpath, and a second copy is a duplicate mod), config/, defaultconfigs/,
# resourcepacks/, shaderpacks/ and options.txt; never saves/, the account or the launcher's own files. Then hashes
# every copied file on both sides and fails on any difference. Builds <dest>.partial and renames it into place last.
#
#   bash scripts/pack-copy-instance.sh                                    # the 1.21.11 instance → run/packs/1.21.11/fabric-prism
#   SRC=<instance>/minecraft DEST=run/packs/<node>/<name> bash scripts/pack-copy-instance.sh
#
# Exit codes: 0 copied and verified · 1 a copied file differs from its source · 2 nothing done (missing source,
# a dest that exists, a dest outside run/packs/).
set -euo pipefail

cd "$(dirname "$0")/.."

SRC=${SRC:-$APPDATA/PrismLauncher/instances/1.21.11/minecraft}
DEST=${DEST:-run/packs/1.21.11/fabric-prism}
DEST=${DEST%/}
ITEMS=(config defaultconfigs resourcepacks shaderpacks options.txt)

case "$DEST" in
	run/packs/?*/?*) ;;
	*) echo "pack-copy-instance: DEST must be run/packs/<node>/<name>, not '$DEST'" >&2; exit 2 ;;
esac
if [[ $DEST == *..* ]]; then
	echo "pack-copy-instance: DEST may not hold '..': $DEST" >&2
	exit 2
fi
if [[ ! -d $SRC/mods ]]; then
	echo "pack-copy-instance: no instance at $SRC (no mods/)" >&2
	exit 2
fi
PARTIAL=$DEST.partial
for d in "$DEST" "$PARTIAL"; do
	if [[ -e $d ]]; then
		echo "pack-copy-instance: $d exists; refusing to copy over it" >&2
		exit 2
	fi
done

GAME=$PARTIAL/game
mkdir -p "$GAME/mods"
left_out=()
for jar in "$SRC"/mods/*.jar; do
	if unzip -p "$jar" fabric.mod.json 2> /dev/null | tr -d '\r\n\t ' | grep -q '"id":"fabric-api"'; then
		left_out+=("mods/$(basename "$jar")")
		continue
	fi
	cp -- "$jar" "$GAME/mods/"
done
for item in "${ITEMS[@]}"; do
	if [[ -e $SRC/$item ]]; then
		cp -r -- "$SRC/$item" "$GAME/"
	else
		echo "pack-copy-instance: the instance has no $item; skipped"
	fi
done

# The same relative paths, hashed on both sides: mods/'s jars (not Prism's mods/.index metadata, which isn't copied)
# and every file of the other items. Fabric API's jar is the one file meant to differ (absent).
hashes() {
	(cd "$1" && { find mods -maxdepth 1 -type f -name '*.jar'; find "${ITEMS[@]}" -type f 2> /dev/null; } | LC_ALL=C sort | while IFS= read -r f; do
		printf '%s  %s\n' "$(sha256sum -- "$f" | cut -d' ' -f1)" "$f"
	done)
}
src_hashes=$(hashes "$SRC")
for f in "${left_out[@]}"; do
	src_hashes=$(grep -vF "  $f" <<< "$src_hashes")
done
copy_hashes=$(hashes "$GAME")
if ! diff <(echo "$src_hashes") <(echo "$copy_hashes") > "$PARTIAL/copy.diff"; then
	echo "pack-copy-instance: FAIL: the copy differs from $SRC (see $PARTIAL/copy.diff); $PARTIAL is left to look at" >&2
	exit 1
fi
{
	echo "copy of $SRC, $(date -u +%Y-%m-%dT%H:%M:%SZ), read only"
	echo "$(ls "$GAME/mods" | wc -l) mods, $(find "$GAME/config" -type f 2> /dev/null | wc -l) config files"
	for f in "${left_out[@]}"; do echo "left out (the dev run has fabric-api)  $f"; done
	sed 's/^/copied  /' <<< "$copy_hashes"
} > "$PARTIAL/pack.txt"
rm -- "$PARTIAL/copy.diff"

# Windows refuses the rename while a scanner still holds a fresh jar: wait it out, 30 s at most.
for try in $(seq 1 30); do
	if mv -T -- "$PARTIAL" "$DEST" 2> /dev/null; then
		echo "pack-copy-instance: copied and verified $(wc -l <<< "$copy_hashes") files into $DEST (${#left_out[@]} left out; see pack.txt)"
		exit 0
	fi
	sleep 1
done
echo "pack-copy-instance: NOTHING DONE (exit 2): couldn't rename $PARTIAL to $DEST" >&2
exit 2
