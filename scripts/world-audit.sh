#!/usr/bin/env bash
# Save safety (TODO.md C.2b): what did a run do to the world folder? Read-only: it lists the world and hashes files,
# and writes nothing but the snapshot file it's given.
#
#   scripts/world-audit.sh snapshot <world dir> <out file>       # a missing world gives an empty snapshot
#   scripts/world-audit.sh compare <before> <after> [allow-regex...]
#   scripts/world-audit.sh --self-test
#
# compare prints each finding and exits 1 on any, 0 on none:
#   - a path naming "commitment" that isn't one of Commitment's own (OURS below)
#   - a path that was there before and is gone, unless it's Commitment's own
#   - a new path outside vanilla's and the loaders' own locations (VANILLA below)
#   - a changed file outside those locations that isn't Commitment's (another datapack, a player's own file)
# An allow-regex (a commands file's @audit-allow) names paths the check itself changes on purpose.
set -euo pipefail

UUID='[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
# Commitment's own: the record and the guard pack, their lifted names (LockLifter), and vanilla's save of the guard's
# command storage (26.x keeps it under data/<namespace>/, 1.21.11 as data/command_storage_<namespace>.dat).
OURS="^((data/commitment\\.dat|datapacks/commitment_guard\\.zip)(\\.$UUID\\.lifted)?|data/commitment|data/commitment/command_storage\\.dat|data/command_storage_commitment\\.dat)\$"
# Where vanilla and the loaders keep a world's own state: written by the game on every save, so not hashed.
VANILLA='^(level\.dat(_old)?|session\.lock|icon\.png|datapacks|(data|DIM-1|DIM1|dimensions|region|entities|poi|players|playerdata|stats|advancements|syncedconfig|serverconfig)(/.*)?)$'

# snapshot <world> <out>: one line per path, "<hash or -><TAB><relative path>", sorted. Directories and vanilla's
# files get "-"; every other file its SHA-256.
snapshot() {
	local world=$1 out=$2 rel
	: > "$out"
	[[ -d "$world" ]] || return 0
	while IFS= read -r -d '' path; do
		rel=${path#"$world"/}
		if [[ -d "$path" || "$rel" =~ $VANILLA ]]; then
			printf -- '-\t%s\n' "$rel"
		else
			printf '%s\t%s\n' "$(sha256sum -- "$path" | cut -d' ' -f1)" "$rel"
		fi
	done < <(find "$world" -mindepth 1 -print0) | LC_ALL=C sort -t $'\t' -k2 > "$out"
}

# compare <before> <after> [allow-regex...]
compare() {
	local before=$1 after=$2 findings=0 hash rel re allowed
	shift 2
	local -a allow=("$@")
	local -A was=() now=()
	while IFS=$'\t' read -r hash rel; do was[$rel]=$hash; done < "$before"
	while IFS=$'\t' read -r hash rel; do now[$rel]=$hash; done < "$after"
	is_allowed() {
		for re in ${allow[@]+"${allow[@]}"}; do
			[[ "$1" =~ $re ]] && return 0
		done
		return 1
	}
	for rel in "${!now[@]}"; do
		[[ "$rel" =~ $OURS ]] && continue
		is_allowed "$rel" && continue
		shopt -s nocasematch
		if [[ "$rel" == *commitment* ]]; then
			shopt -u nocasematch
			echo "world-audit: a path naming Commitment that isn't one of its own: $rel"
			findings=$((findings + 1))
			continue
		fi
		shopt -u nocasematch
		if [[ -z "${was[$rel]+x}" ]]; then
			if ! [[ "$rel" =~ $VANILLA ]]; then
				echo "world-audit: a new path outside vanilla's locations: $rel"
				findings=$((findings + 1))
			fi
		elif [[ "${was[$rel]}" != "${now[$rel]}" ]]; then
			echo "world-audit: a file Commitment must never touch changed: $rel"
			findings=$((findings + 1))
		fi
	done
	for rel in "${!was[@]}"; do
		if [[ -z "${now[$rel]+x}" ]] && ! [[ "$rel" =~ $OURS ]] && ! is_allowed "$rel"; then
			echo "world-audit: a path that was there before is gone: $rel"
			findings=$((findings + 1))
		fi
	done
	if (( findings > 0 )); then
		echo "world-audit: $findings finding(s)"
		return 1
	fi
	echo "world-audit: clean ($(wc -l < "$before" | tr -d ' ') paths before, $(wc -l < "$after" | tr -d ' ') after)"
}

# Real directories through snapshot and compare, both polarities. An audit nothing ever fed a bad change can't be trusted.
self_test() {
	local dir failures=0 lock=0f1e2d3c-4b5a-4987-8a6b-5c4d3e2f1a0b
	dir=$(mktemp -d)
	trap 'rm -rf "$dir"' RETURN
	base() { # a small committed world, the same at every case's start
		rm -rf "$dir/w"
		mkdir -p "$dir/w/region" "$dir/w/data/minecraft" "$dir/w/datapacks"
		echo level > "$dir/w/level.dat"
		echo r > "$dir/w/region/r.0.0.mca"
		echo rules > "$dir/w/data/minecraft/game_rules.dat"
		echo record > "$dir/w/data/commitment.dat"
		echo guard > "$dir/w/datapacks/commitment_guard.zip"
		echo theirs > "$dir/w/datapacks/someone_elses.zip"
		echo mine > "$dir/w/notes.txt"
	}
	case_() { # case_ <expected 0|1> <name> <change...> [-- allow-regex...]
		local want=$1 name=$2 got=0
		shift 2
		local -a change=() allow=()
		while (( $# )) && [[ $1 != -- ]]; do change+=("$1"); shift; done
		(( $# )) && shift
		allow=("$@")
		base
		snapshot "$dir/w" "$dir/before"
		(cd "$dir/w" && for c in ${change[@]+"${change[@]}"}; do eval "$c"; done)
		snapshot "$dir/w" "$dir/after"
		compare "$dir/before" "$dir/after" ${allow[@]+"${allow[@]}"} > "$dir/out.txt" || got=$?
		if [[ $got == "$want" ]]; then
			echo "ok   world-audit: $name"
		else
			echo "FAIL world-audit: $name: compare returned $got, expected $want"
			sed 's/^/     /' "$dir/out.txt"
			failures=$((failures + 1))
		fi
	}
	case_ 0 "nothing changed"
	case_ 0 "vanilla saved: level.dat and a region changed, a new region" 'echo l2 > level.dat' 'echo r2 > region/r.0.0.mca' 'echo n > region/r.1.0.mca'
	case_ 0 "vanilla's 26.x and 1.21.11 layouts are new" 'mkdir -p dimensions/minecraft/the_end/data DIM-1/data players/data' 'echo d > DIM-1/data/raids.dat' 'echo s > session.lock' 'echo o > level.dat_old'
	case_ 0 "Commitment rewrote its record and pack" 'echo record2 > data/commitment.dat' 'echo guard2 > datapacks/commitment_guard.zip'
	case_ 0 "the heartbeat's command storage, 26.x and 1.21.11" 'mkdir -p data/commitment' 'echo s > data/commitment/command_storage.dat' 'echo s > data/command_storage_commitment.dat'
	case_ 0 "a lift renamed the record and the pack" "mv data/commitment.dat data/commitment.dat.$lock.lifted" "mv datapacks/commitment_guard.zip datapacks/commitment_guard.zip.$lock.lifted"
	case_ 0 "the check's own change, allowed" 'echo x > extra.txt' -- '^extra\.txt$'
	case_ 1 "a stray Commitment file" 'echo b > commitment-backup.dat'
	case_ 1 "a temp file left behind" 'echo t > data/commitment.dat.tmp'
	case_ 1 "a lifted name with a bad lock id" 'mv data/commitment.dat data/commitment.dat.nope.lifted'
	case_ 1 "someone else's datapack changed" 'echo changed > datapacks/someone_elses.zip'
	case_ 1 "someone else's datapack removed" 'rm datapacks/someone_elses.zip'
	case_ 1 "a player's own file changed" 'echo changed > notes.txt'
	case_ 1 "a player's own file removed" 'rm notes.txt'
	case_ 1 "a new file outside vanilla's locations" 'echo x > extra.txt'
	case_ 1 "a new datapack" 'echo p > datapacks/new_pack.zip'
	case_ 1 "a vanilla region removed" 'rm region/r.0.0.mca'
	case_ 1 "level.dat removed" 'rm level.dat'
	case_ 1 "an allow-regex doesn't cover everything" 'echo x > extra.txt' 'echo y > other.txt' -- '^extra\.txt$'

	# A world that doesn't exist yet snapshots as empty; vanilla creating it is clean.
	rm -rf "$dir/w"
	snapshot "$dir/w" "$dir/before"
	mkdir -p "$dir/w/region" && echo l > "$dir/w/level.dat"
	snapshot "$dir/w" "$dir/after"
	if [[ ! -s "$dir/before" ]] && compare "$dir/before" "$dir/after" > /dev/null; then
		echo "ok   world-audit: a new world is clean"
	else
		echo "FAIL world-audit: a new world is clean"
		failures=$((failures + 1))
	fi

	if (( failures > 0 )); then
		echo "world-audit self-test: $failures failed"
		return 1
	fi
	echo "world-audit self-test: all passed"
}

case "${1:-}" in
	snapshot) (( $# == 3 )) || { echo "usage: $0 snapshot <world dir> <out file>" >&2; exit 2; }; snapshot "$2" "$3" ;;
	compare) (( $# >= 3 )) || { echo "usage: $0 compare <before> <after> [allow-regex...]" >&2; exit 2; }; shift; compare "$@" ;;
	--self-test) self_test ;;
	*) echo "usage: $0 snapshot|compare|--self-test ..." >&2; exit 2 ;;
esac
