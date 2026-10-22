#!/usr/bin/env bash
# Live proof for the guard datapack with Commitment present (C.1): zips the pack's golden files
# (common/src/test/resources/guard-pack, which GuardPackTest holds to the generator) into the dev world's datapacks/,
# runs dev-server-smoke.sh once per commands file given (default scripts/checks/guard-pack.txt), then removes what
# the run left: the zip it wrote, and the lock record and mirror the mod wrote when it found the pack. Only files that
# carry Commitment's marker go.
#
#   LOADER=fabric NODE=26.3 bash scripts/guard-pack-smoke.sh
#   LOADER=fabric NODE=26.3 bash scripts/guard-pack-smoke.sh scripts/checks/guard-disable.txt scripts/checks/guard-reenable.txt
#
# Exit codes are dev-server-smoke.sh's: 0 proven · 1 the pack or the mod is bad · 2 nothing proven.
set -euo pipefail

cd "$(dirname "$0")/.."

export LOADER=${LOADER:-fabric}
export NODE=${NODE:-26.3}
GOLDEN=common/src/test/resources/guard-pack
WORLD=run/$LOADER/$NODE/server/world
ZIP=$WORLD/datapacks/commitment_guard.zip
RECORD=$WORLD/data/commitment.dat
# The dev runs' mirror dir (MirrorDir.OVERRIDE, set by each loader's build.gradle).
MIRROR=run/$LOADER/$NODE/mirror

if [[ ! -f "$GOLDEN/pack.mcmeta" ]]; then
	echo "guard-pack-smoke: no golden pack at $GOLDEN" >&2
	exit 2
fi
# Never over files this run didn't write: leftovers are for a person to look at, not for this script to replace.
for leftover in "$ZIP" "$ZIP.tmp" "$RECORD"; do
	if [[ -e "$leftover" ]]; then
		echo "guard-pack-smoke: $leftover already exists; refusing to run over it. If it's a leftover of this script, remove it." >&2
		exit 2
	fi
done
if compgen -G "$MIRROR/*.dat" > /dev/null; then
	echo "guard-pack-smoke: $MIRROR already holds lock mirrors; refusing to run over them." >&2
	exit 2
fi

mkdir -p "$WORLD/datapacks"
jar --create --no-manifest --file "$ZIP" -C "$GOLDEN" .
written=$(sha256sum "$ZIP" | cut -d' ' -f1)
echo "guard-pack-smoke: wrote $ZIP ($written)"

# A lock record or mirror of Commitment's: gzip whose content starts with its CMTLOCK marker.
is_lock_file() {
	[[ "$(gzip -dc -- "$1" 2> /dev/null | head -c 7)" == "CMTLOCK" ]]
}

# Removes the zip while it's still the one written above, and the lock files only when they carry the marker.
cleanup() {
	if [[ -e "$ZIP" ]]; then
		if [[ "$(sha256sum "$ZIP" | cut -d' ' -f1)" == "$written" ]]; then
			rm -- "$ZIP"
			echo "guard-pack-smoke: removed $ZIP"
		else
			echo "guard-pack-smoke: $ZIP changed during the run; leaving it for you to look at" >&2
		fi
	fi
	local file
	for file in "$RECORD" "$MIRROR"/*.dat; do
		[[ -e "$file" ]] || continue
		if is_lock_file "$file"; then
			rm -- "$file"
			echo "guard-pack-smoke: removed $file"
		else
			echo "guard-pack-smoke: $file isn't a Commitment lock file; leaving it" >&2
		fi
	done
}
trap cleanup EXIT

# One boot per commands file, in order, over the same world (a later boot sees what an earlier one left); the first
# boot that doesn't prove its checks ends the run with its exit code.
status=0
for commands in "${@:-scripts/checks/guard-pack.txt}"; do
	echo "guard-pack-smoke: boot with $commands"
	set +e
	bash scripts/dev-server-smoke.sh "$commands"
	status=$?
	set -e
	if [[ $status -ne 0 ]]; then
		break
	fi
done

# The mod wrote this run's mirror into the dev mirror dir, never into the developer's own user data dir: a run that
# ignored -Dcommitment.mirrorDir would still pass every check above. Checked here, before the exit trap cleans up.
if [[ $status -eq 0 ]] && ! compgen -G "$MIRROR/*.dat" > /dev/null; then
	echo "guard-pack-smoke: FAIL: no lock mirror in $MIRROR; the dev run's -Dcommitment.mirrorDir wasn't honoured" >&2
	status=1
fi
REAL_MIRROR=${LOCALAPPDATA:-}/Commitment/locks/$(sed -n 's/^lock=//p' "$GOLDEN/commitment.lock" | tr -d '\r').dat
if [[ -n "${LOCALAPPDATA:-}" && -e "$REAL_MIRROR" ]]; then
	echo "guard-pack-smoke: FAIL: $REAL_MIRROR exists: a dev run wrote into your own user data dir. Left for you to look at." >&2
	status=1
fi
exit $status
