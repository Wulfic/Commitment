#!/usr/bin/env bash
# Proof 4 (C.5): the guard pack with Commitment gone. A vanilla Minecraft server (no loader, no mod) opens a copy of
# the node's Fabric dev world holding the golden guard pack. Every 2 s the guard finds no heartbeat and runs its
# penalty, whose first step logs "Commitment guard: Commitment is missing". The server jar comes from Mojang's
# piston-meta, checked against the SHA-1 listed there, and is kept in run/vanilla/<node>/server.jar. The world copy is
# removed after, by marker.
#
#   NODE=26.3 bash scripts/vanilla-guard-smoke.sh
#   bash scripts/vanilla-guard-smoke.sh --self-test          # the copy's removal guards, on scratch dirs
#
# Exit codes: 0 proven · 1 the pack is bad · 2 nothing proven (no source world, port busy, download failed, leftovers).
set -euo pipefail

cd "$(dirname "$0")/.."

MANIFEST=https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
MARKER_FILE=commitment-vanilla-smoke.marker
GUARD_LINE='Commitment guard: Commitment is missing'
PACK_LINE='There are [0-9]+ data pack\(s\) enabled: .*file/commitment_guard\.zip'
# A function the guard needs that vanilla couldn't load (the pack's syntax isn't this version's).
LOAD_ERROR='(Failed to load function|Couldn.t load function|Unknown function) commitment_guard'

# remove_copy <run dir> <copy dir>: removes the world copy only when it's exactly <run>/world and holds the marker this
# script wrote into it. Fails, removing nothing, otherwise.
remove_copy() {
	local run=$1 copy=$2
	if [[ -z $run || $copy != "$run/world" || $copy == *..* ]]; then
		echo "vanilla-guard-smoke: refusing to remove '$copy': not $run/world" >&2
		return 1
	fi
	[[ -e $copy ]] || return 0
	if [[ ! -f $copy/$MARKER_FILE ]]; then
		echo "vanilla-guard-smoke: refusing to remove $copy: it has no $MARKER_FILE, so this script didn't make it" >&2
		return 1
	fi
	rm -r -- "$copy"
	echo "vanilla-guard-smoke: removed the world copy $copy"
}

self_test() {
	local dir failures=0 status
	dir=$(mktemp -d)
	expect() { # expect <present|absent> <name> <path>
		if [[ ($1 == present && -e $3) || ($1 == absent && ! -e $3) ]]; then
			echo "ok   $2"
		else
			echo "FAIL $2: $3 should be $1"
			failures=$((failures + 1))
		fi
	}
	mkdir -p "$dir/run/world/region" && touch "$dir/run/world/$MARKER_FILE"
	remove_copy "$dir/run" "$dir/run/world" > /dev/null
	expect absent "the marked copy goes" "$dir/run/world"

	mkdir -p "$dir/run/world/region"
	remove_copy "$dir/run" "$dir/run/world" 2> /dev/null || true
	expect present "a world with no marker stays" "$dir/run/world"

	mkdir -p "$dir/run/other" && touch "$dir/run/other/$MARKER_FILE"
	remove_copy "$dir/run" "$dir/run/other" 2> /dev/null || true
	expect present "another dir stays, marker or not" "$dir/run/other"

	touch "$dir/run/world/$MARKER_FILE"
	status=0
	remove_copy "" "/world" 2> /dev/null || status=$?
	if [[ $status -ne 0 ]]; then echo "ok   an empty run dir is refused"; else echo "FAIL an empty run dir is refused"; failures=$((failures + 1)); fi
	remove_copy "$dir/run/.." "$dir/run/../world" 2> /dev/null || true
	expect present "a path with .. is refused" "$dir/run/world"

	rm -r -- "$dir"
	if (( failures > 0 )); then
		echo "vanilla-guard-smoke self-test: $failures failed"
		return 1
	fi
	echo "vanilla-guard-smoke self-test: all passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
	self_test
	exit $?
fi

NODE=${NODE:-26.3}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-300}
if ! grep -qE "versions .*'$NODE'" settings.gradle; then
	echo "no node '$NODE' in settings.gradle" >&2
	exit 2
fi
PORT=$(grep -E '^dev_server_port=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
RUN=run/vanilla/$NODE
SOURCE=run/fabric/$NODE/server/world
COPY=$RUN/world
JAR=$RUN/server.jar
# Absolute: the server runs from inside $RUN, and so does the console feed.
LOG=$PWD/$RUN/logs/latest.log
OUT=$PWD/build/vanilla-guard-smoke-$NODE.out
STARTED=$PWD/build/vanilla-guard-smoke-$NODE.started
GOLDEN=common/src/test/resources/guard-pack

if [[ ! -f $SOURCE/level.dat ]]; then
	echo "vanilla-guard-smoke: no dev world at $SOURCE; boot the Fabric dev server once (scripts/dev-server-smoke.sh)" >&2
	exit 2
fi
if [[ -e $COPY ]]; then
	echo "vanilla-guard-smoke: $COPY already exists; refusing to run over it. If it's a leftover of this script, remove it." >&2
	exit 2
fi
if netstat -an 2> /dev/null | grep -qE "[:.]${PORT}[[:space:]].*LISTEN"; then
	echo "vanilla-guard-smoke: port $PORT is already in use; refusing to start a second server" >&2
	exit 2
fi
mkdir -p "$RUN" build

# The server jar for this version, as Mojang lists it: URL and SHA-1 from the version's own JSON.
server_jar() {
	local version_url server sha1 url
	version_url=$(curl -fsS --max-time 30 "$MANIFEST" | grep -o "\"id\": *\"$NODE\", *\"type\": *\"[a-z_]*\", *\"url\": *\"[^\"]*\"" |
		sed 's/.*"url": *"\([^"]*\)"/\1/') || return 1
	[[ -n $version_url ]] || { echo "vanilla-guard-smoke: $NODE isn't in $MANIFEST" >&2; return 1; }
	server=$(curl -fsS --max-time 30 "$version_url" | tr -d '\n' | grep -o '"server": *{[^}]*}') || return 1
	sha1=$(sed 's/.*"sha1": *"\([0-9a-f]*\)".*/\1/' <<< "$server")
	url=$(sed 's/.*"url": *"\([^"]*\)".*/\1/' <<< "$server")
	if [[ -f $JAR && $(sha1sum "$JAR" | cut -d' ' -f1) == "$sha1" ]]; then
		return 0
	fi
	echo "vanilla-guard-smoke: downloading the $NODE server jar from $url"
	curl -fsS --max-time 300 -o "$JAR.tmp" "$url" || return 1
	if [[ $(sha1sum "$JAR.tmp" | cut -d' ' -f1) != "$sha1" ]]; then
		echo "vanilla-guard-smoke: the download's SHA-1 isn't $sha1; not using it" >&2
		rm -f -- "$JAR.tmp"
		return 1
	fi
	mv -- "$JAR.tmp" "$JAR"
	echo "vanilla-guard-smoke: $JAR (SHA-1 $sha1)"
}
if ! server_jar; then
	echo "vanilla-guard-smoke: NOTHING PROVEN (exit 2): no checked server jar for $NODE" >&2
	exit 2
fi

trap 'remove_copy "$RUN" "$COPY" || true' EXIT
cp -r -- "$SOURCE" "$COPY"
touch "$COPY/$MARKER_FILE"
rm -f -- "$COPY/session.lock"
mkdir -p "$COPY/datapacks"
jar --create --no-manifest --file "$COPY/datapacks/commitment_guard.zip" -C "$GOLDEN" .
[[ -f $RUN/eula.txt ]] || echo "eula=true" > "$RUN/eula.txt"
# This script's own server dir: the dev port, the copied world, no network login (no player ever joins).
[[ -f $RUN/server.properties ]] || printf 'server-port=%s\nlevel-name=world\nonline-mode=false\n' "$PORT" > "$RUN/server.properties"
echo "vanilla-guard-smoke: copied $SOURCE to $COPY with the golden guard pack; no loader, no Commitment"
touch "$STARTED"

feed_console() {
	local waited=0
	until [[ -f $LOG && $LOG -nt $STARTED ]] && grep -q 'Done (' "$LOG"; do
		if (( waited >= BOOT_TIMEOUT )); then
			echo stop
			return
		fi
		sleep 2
		waited=$((waited + 2))
	done
	echo "datapack list enabled"
	# Two guard cycles (40 ticks each), with room to spare.
	sleep 8
	echo stop
}

set +e
(cd "$RUN" && feed_console | timeout $((BOOT_TIMEOUT + 60)) java -jar server.jar nogui > "$OUT" 2>&1)
status=$?
set -e

if [[ ! -f $LOG || ! $LOG -nt $STARTED ]]; then
	echo "vanilla-guard-smoke: NOTHING PROVEN (exit 2): the server wrote no log (java exit $status). Tail of $OUT:" >&2
	tail -30 "$OUT" >&2
	exit 2
fi
fail=0
(( status == 0 )) || { echo "FAIL: the server exited $status (124 = timed out)"; fail=1; }
for re in 'Done \(' "$PACK_LINE" "$GUARD_LINE"; do
	if line=$(grep -m1 -E -- "$re" "$LOG"); then
		echo "matched $re: $line"
	else
		echo "FAIL: no line matching $re in $LOG"
		fail=1
	fi
done
if line=$(grep -m1 -E -- "$LOAD_ERROR" "$LOG"); then
	echo "FAIL: vanilla couldn't load the guard: $line"
	fail=1
fi
if grep -qiE 'commitment [^ ]+ loaded on' "$LOG"; then
	echo "FAIL: Commitment loaded: this server was meant to be vanilla"
	fail=1
fi
if (( fail == 0 )); then
	echo "vanilla-guard-smoke: PASS ($NODE)"
	exit 0
fi
echo "vanilla-guard-smoke: FAIL ($NODE)"
exit 1
