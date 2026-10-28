#!/usr/bin/env bash
# Live proof for /commit and /unlock on a dedicated dev server (C.3c): boots the dev world once per commands file, in
# order, over the same world (default: commit-flow.txt, guard-live.txt, guard-disable.txt, guard-reenable.txt), then
# removes what /commit confirm wrote: the guard pack, the lock record and its mirror. Each goes only when it carries
# Commitment's marker and this run's lock id (from the guard pack's commitment.lock, or the record's header when the
# pack is missing); anything else is left for a person to look at.
#
#   LOADER=fabric NODE=26.3 bash scripts/commit-smoke.sh
#   LOADER=forge NODE=26.2 bash scripts/commit-smoke.sh scripts/checks/commit-flow.txt
#   bash scripts/commit-smoke.sh --self-test        # the cleanup's guards, on scratch worlds
#
# Exit codes are dev-server-smoke.sh's: 0 proven · 1 the mod is bad · 2 nothing proven (or leftovers refused).
set -euo pipefail

cd "$(dirname "$0")/.."

# PACK, RECORD, lock_of_pack, lock_of_record, inside, mirror_dir.
source scripts/lock-files.sh

# remove_if_ours <file> <lock id> <pack|record>: removes the file only when it's Commitment's and of that lock.
remove_if_ours() {
	local file=$1 id=$2 kind=$3 found
	[[ -e $file ]] || return 0
	if [[ $kind == pack ]]; then
		found=$(lock_of_pack "$file") || found=""
	else
		found=$(lock_of_record "$file") || found=""
	fi
	if [[ -n $found && $found == "$id" ]]; then
		rm -- "$file"
		echo "commit-smoke: removed $file (lock $id)"
	else
		echo "commit-smoke: $file isn't Commitment's for lock $id (found: ${found:-no marker}); leaving it for you to look at" >&2
	fi
}

# cleanup_lock <root> <world dir> <mirror dir>: removes the lock this run committed. Refuses, removing nothing, when
# a path is empty or outside root, or when no file names a lock id of Commitment's.
cleanup_lock() {
	local root=$1 world=$2 mirror=$3 id=""
	if ! inside "$root" "$world" || ! inside "$root" "$mirror"; then
		echo "commit-smoke: refusing to clean up: world '$world' or mirror '$mirror' isn't inside '$root'" >&2
		return 1
	fi
	if [[ -e $world/$PACK ]]; then
		id=$(lock_of_pack "$world/$PACK") || id=""
	fi
	if [[ -z $id && -e $world/$RECORD ]]; then
		id=$(lock_of_record "$world/$RECORD") || id=""
	fi
	if [[ -z $id ]]; then
		if [[ -e $world/$PACK || -e $world/$RECORD ]]; then
			echo "commit-smoke: no lock id of Commitment's in $world/$PACK or $world/$RECORD; leaving them for you to look at" >&2
		fi
		return 0
	fi
	remove_if_ours "$world/$PACK" "$id" pack
	remove_if_ours "$world/$RECORD" "$id" record
	remove_if_ours "$mirror/$id.dat" "$id" record
}

# The cleanup fed both polarities on scratch worlds. A guard nothing ever fed a foreign file can't be trusted.
self_test() {
	local dir failures=0 id=0f0e0d0c-0b0a-4908-8706-050403020100 other=11111111-2222-4333-8444-555555555555
	dir=$(mktemp -d)

	make_pack() { # make_pack <zip> <lock id> [marker line]
		local src
		src=$(mktemp -d)
		printf '%s\n' '# test' "${3:-marker=CMTGUARD 1}" "lock=$2" > "$src/commitment.lock"
		mkdir -p "$(dirname "$1")"
		jar --create --no-manifest --file "$1" -C "$src" .
		rm -r -- "$src"
	}
	make_record() { # make_record <file> <lock id> [magic]
		mkdir -p "$(dirname "$1")"
		{ printf '%s' "${3:-CMTLOCK}"; printf '\x00\x00\x00\x01'; printf '%s' "${2//-/}" | xxd -r -p; printf 'payload'; } | gzip -c > "$1"
	}
	fresh() { # fresh <name>: an empty root with run/<name>/server/world and run/<name>/mirror
		root=$dir/$1
		world=$root/server/world
		mirror=$root/mirror
		mkdir -p "$world/data" "$world/datapacks" "$mirror"
	}
	expect() { # expect <present|absent> <name> <path>
		if [[ ($1 == present && -e $3) || ($1 == absent && ! -e $3) ]]; then
			echo "ok   $2"
		else
			echo "FAIL $2: $3 should be $1"
			failures=$((failures + 1))
		fi
	}

	fresh ours
	make_pack "$world/$PACK" "$id"
	make_record "$world/$RECORD" "$id"
	make_record "$mirror/$id.dat" "$id"
	make_record "$mirror/$other.dat" "$other"
	if [[ $(lock_of_record "$world/$RECORD") == "$id" && $(lock_of_pack "$world/$PACK") == "$id" ]]; then
		echo "ok   the lock id reads back from a pack and a record"
	else
		echo "FAIL the lock id reads back from a pack and a record"
		failures=$((failures + 1))
	fi
	cleanup_lock "$root" "$world" "$mirror" 2> /dev/null > /dev/null
	expect absent "our guard pack goes" "$world/$PACK"
	expect absent "our record goes" "$world/$RECORD"
	expect absent "our mirror goes" "$mirror/$id.dat"
	expect present "another lock's mirror stays" "$mirror/$other.dat"

	fresh foreign-pack
	make_pack "$world/$PACK" "$id" 'marker=SOMEONE ELSE'
	make_record "$world/$RECORD" "$id"
	cleanup_lock "$root" "$world" "$mirror" 2> /dev/null > /dev/null
	expect present "a pack with no marker stays" "$world/$PACK"
	expect absent "the record names the lock when the pack can't" "$world/$RECORD"

	fresh other-record
	make_pack "$world/$PACK" "$id"
	make_record "$world/$RECORD" "$other"
	cleanup_lock "$root" "$world" "$mirror" 2> /dev/null > /dev/null
	expect present "a record of another lock stays" "$world/$RECORD"
	expect absent "the pack of this lock goes" "$world/$PACK"

	fresh not-ours
	make_record "$world/$RECORD" "$id" NOTOURS
	printf 'plain' > "$mirror/$id.dat"
	cleanup_lock "$root" "$world" "$mirror" 2> /dev/null > /dev/null
	expect present "a record with no marker stays" "$world/$RECORD"
	expect present "a mirror that isn't gzip stays" "$mirror/$id.dat"

	fresh outside
	make_pack "$world/$PACK" "$id"
	local status=0
	cleanup_lock "$dir/elsewhere" "$world" "$mirror" 2> /dev/null > /dev/null || status=$?
	expect present "a world outside the root is refused" "$world/$PACK"
	status=0
	cleanup_lock "" "" "" 2> /dev/null > /dev/null || status=$?
	if [[ $status -ne 0 ]]; then echo "ok   empty paths are refused"; else echo "FAIL empty paths are refused"; failures=$((failures + 1)); fi
	status=0
	cleanup_lock "$root" "$root/server/../../ours/server/world" "$mirror" 2> /dev/null > /dev/null || status=$?
	if [[ $status -ne 0 ]]; then echo "ok   a path with .. is refused"; else echo "FAIL a path with .. is refused"; failures=$((failures + 1)); fi

	rm -r -- "$dir"
	if (( failures > 0 )); then
		echo "commit-smoke self-test: $failures failed"
		return 1
	fi
	echo "commit-smoke self-test: all passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
	self_test
	exit $?
fi

export LOADER=${LOADER:-fabric}
export NODE=${NODE:-26.3}
export REAL=${REAL:-}
RUN=$(run_root "$LOADER" "$NODE")
# No server.properties before a server's first boot (a fresh Quilt server): the pipeline fails, and that's no error.
LEVEL_NAME=$(sed -n 's/^level-name=//p' "$RUN/server/server.properties" 2> /dev/null | tr -d '\r') || true
WORLD=$RUN/server/${LEVEL_NAME:-world}
MIRROR=$(mirror_dir "$LOADER" "$NODE")

# Never over files this run didn't write: leftovers are for a person to look at, not for this script to replace.
for leftover in "$WORLD/$PACK" "$WORLD/$PACK.tmp" "$WORLD/$RECORD" "$WORLD/$RECORD.tmp"; do
	if [[ -e "$leftover" ]]; then
		echo "commit-smoke: $leftover already exists; refusing to run over it. If it's a leftover of this script, remove it." >&2
		exit 2
	fi
done
if compgen -G "$MIRROR/*.dat" > /dev/null; then
	echo "commit-smoke: $MIRROR already holds lock mirrors; refusing to run over them." >&2
	exit 2
fi

trap 'cleanup_lock "$RUN" "$WORLD" "$MIRROR" || true' EXIT

# One boot per commands file, in order, over the same world (a later boot sees what an earlier one left); the first
# boot that doesn't prove its checks ends the run with its exit code.
status=0
if (( $# == 0 )); then
	set -- scripts/checks/commit-flow.txt scripts/checks/guard-live.txt scripts/checks/guard-disable.txt scripts/checks/guard-reenable.txt
fi
for commands in "$@"; do
	echo "commit-smoke: boot with $commands"
	set +e
	bash scripts/dev-server-smoke.sh "$commands"
	status=$?
	set -e
	if [[ $status -ne 0 ]]; then
		break
	fi
done

# The mirror went into the dev mirror dir, never into the developer's own user data dir: a run that ignored
# -Dcommitment.mirrorDir would pass every check above. Checked here, before the exit trap cleans up.
lock=""
if [[ -e $WORLD/$PACK ]]; then
	lock=$(lock_of_pack "$WORLD/$PACK") || lock=""
fi
if [[ $status -eq 0 ]]; then
	if [[ -z $lock ]]; then
		echo "commit-smoke: FAIL: no guard pack of Commitment's in $WORLD after the run" >&2
		status=1
	elif [[ ! -e $MIRROR/$lock.dat ]]; then
		echo "commit-smoke: FAIL: no lock mirror $MIRROR/$lock.dat; the dev run's -Dcommitment.mirrorDir wasn't honoured" >&2
		status=1
	fi
fi
if [[ -n $lock && -n "${LOCALAPPDATA:-}" && -e "$LOCALAPPDATA/Commitment/locks/$lock.dat" ]]; then
	echo "commit-smoke: FAIL: $LOCALAPPDATA/Commitment/locks/$lock.dat exists: a dev run wrote into your own user data dir. Left for you to look at." >&2
	status=1
fi
exit $status
