#!/usr/bin/env bash
# B.6 live proof for the guard datapack: zips its golden files (common/src/test/resources/guard-pack, which GuardPackTest
# holds to the generator) into the dev world's datapacks/, runs dev-server-smoke.sh with scripts/checks/guard-pack.txt,
# then removes the zip it wrote, and only that one.
#
#   LOADER=fabric NODE=26.3 bash scripts/guard-pack-smoke.sh
#
# Exit codes are dev-server-smoke.sh's: 0 proven · 1 the pack or the mod is bad · 2 nothing proven.
set -euo pipefail

cd "$(dirname "$0")/.."

export LOADER=${LOADER:-fabric}
export NODE=${NODE:-26.3}
GOLDEN=common/src/test/resources/guard-pack
DATAPACKS=run/$LOADER/$NODE/server/world/datapacks
ZIP=$DATAPACKS/commitment_guard.zip

if [[ ! -f "$GOLDEN/pack.mcmeta" ]]; then
	echo "guard-pack-smoke: no golden pack at $GOLDEN" >&2
	exit 2
fi
# Never over a file this run didn't write: a leftover is for a person to look at, not for this script to replace.
if [[ -e "$ZIP" ]]; then
	echo "guard-pack-smoke: $ZIP already exists; refusing to overwrite it. If it's a leftover of this script, remove it." >&2
	exit 2
fi

mkdir -p "$DATAPACKS"
jar --create --no-manifest --file "$ZIP" -C "$GOLDEN" .
written=$(sha256sum "$ZIP" | cut -d' ' -f1)
echo "guard-pack-smoke: wrote $ZIP ($written)"

# Removes the zip only while it's still the one written above.
cleanup() {
	if [[ ! -e "$ZIP" ]]; then
		return
	fi
	if [[ "$(sha256sum "$ZIP" | cut -d' ' -f1)" == "$written" ]]; then
		rm -- "$ZIP"
		echo "guard-pack-smoke: removed $ZIP"
	else
		echo "guard-pack-smoke: $ZIP changed during the run; leaving it for you to look at" >&2
	fi
}
trap cleanup EXIT

set +e
bash scripts/dev-server-smoke.sh scripts/checks/guard-pack.txt
status=$?
set -e
exit $status
