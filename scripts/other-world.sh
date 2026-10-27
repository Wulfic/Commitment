#!/usr/bin/env bash
# E.1f: a second, uncommitted world next to a dev server's committed one, opened once with --world
# (scripts/checks/e1f/02-other-world.txt). create makes its dir with a marker before the boot (the server builds the
# world inside it); remove deletes it after, only when it's exactly run/<loader>/<node>/server/commitment-e1f-other,
# holds the marker, and carries no lock of Commitment's.
#
#   LOADER=neoforge NODE=26.3 bash scripts/other-world.sh create|remove
#   bash scripts/other-world.sh --self-test
#
# Exit codes: 0 done · 1 refused (nothing removed) · 2 bad arguments.
set -euo pipefail

cd "$(dirname "$0")/.."

NAME=commitment-e1f-other
MARKER=stage-e-other-world.marker

# remove_world <server dir> <world dir>
remove_world() {
	local server=$1 world=$2
	if [[ -z $server || $world != "$server/$NAME" || $world == *..* ]]; then
		echo "other-world: refusing to remove '$world': not $server/$NAME" >&2
		return 1
	fi
	[[ -e $world ]] || return 0
	if [[ ! -f $world/$MARKER ]]; then
		echo "other-world: refusing to remove $world: no $MARKER, so this script didn't make it" >&2
		return 1
	fi
	if [[ -e $world/data/commitment.dat || -n $(compgen -G "$world/datapacks/commitment_guard.zip*") ]]; then
		echo "other-world: refusing to remove $world: it holds a lock of Commitment's" >&2
		return 1
	fi
	rm -r -- "$world"
	echo "other-world: removed $world"
}

self_test() {
	local dir failures=0
	dir=$(mktemp -d)
	expect() { # expect <present|absent> <name> <path>
		if [[ ($1 == present && -e $3) || ($1 == absent && ! -e $3) ]]; then echo "ok   $2"; else echo "FAIL $2: $3 should be $1"; failures=$((failures + 1)); fi
	}
	mkdir -p "$dir/server/$NAME/region" && touch "$dir/server/$NAME/$MARKER"
	remove_world "$dir/server" "$dir/server/$NAME" > /dev/null
	expect absent "the marked world goes" "$dir/server/$NAME"
	mkdir -p "$dir/server/$NAME/region"
	remove_world "$dir/server" "$dir/server/$NAME" 2> /dev/null || true
	expect present "a world with no marker stays" "$dir/server/$NAME"
	touch "$dir/server/$NAME/$MARKER" && mkdir -p "$dir/server/$NAME/data" && touch "$dir/server/$NAME/data/commitment.dat"
	remove_world "$dir/server" "$dir/server/$NAME" 2> /dev/null || true
	expect present "a world with a lock record stays" "$dir/server/$NAME"
	mkdir -p "$dir/server/world" && touch "$dir/server/world/$MARKER"
	remove_world "$dir/server" "$dir/server/world" 2> /dev/null || true
	expect present "the committed world stays, marker or not" "$dir/server/world"
	remove_world "" "/$NAME" 2> /dev/null || true
	expect present "an empty server dir is refused" "$dir/server/world"
	rm -r -- "$dir"
	if (( failures > 0 )); then echo "other-world self-test: $failures failed"; return 1; fi
	echo "other-world self-test: all passed"
}

case "${1:-}" in
	--self-test) self_test; exit $? ;;
	create | remove) ;;
	*) echo "usage: LOADER=.. NODE=.. bash scripts/other-world.sh create|remove | --self-test" >&2; exit 2 ;;
esac
SERVER=run/${LOADER:?LOADER is required}/${NODE:?NODE is required}/server
if [[ ! -d $SERVER ]]; then
	echo "other-world: no dev server at $SERVER" >&2
	exit 2
fi
if [[ $1 == create ]]; then
	if [[ -e $SERVER/$NAME ]]; then
		echo "other-world: $SERVER/$NAME exists; refusing to run over it ('remove' takes one this script made)" >&2
		exit 1
	fi
	mkdir -- "$SERVER/$NAME"
	touch -- "$SERVER/$NAME/$MARKER"
	echo "other-world: made $SERVER/$NAME (marked) for one --world boot"
	exit 0
fi
remove_world "$SERVER" "$SERVER/$NAME"
