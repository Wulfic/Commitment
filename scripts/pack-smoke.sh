#!/usr/bin/env bash
# Stage E (TODO E.1c): a real modpack's client, committed once and reopened, must never be locked by a setup nobody
# changed. Copies the node's dev world into the pack's saves (a marked, disposable copy), then launches the pack's dev
# client (:<loader>:<node>:runPackClient) once per phase. Quick Play opens the copy; the GameTest mod's PackDriver
# types /commit + /commit confirm (commit) or plays 200 ticks (reopen, settings), then quits cleanly so mods write
# their configs on the way out. Before the settings launch, video, audio and shader settings are changed: still free.
#
#   LOADER=fabric NODE=26.3 PACK=run/packs/26.3/fabric-fabulously-optimized bash scripts/pack-smoke.sh
#   PHASES="reopen" ... bash scripts/pack-smoke.sh   # more reopens of a world a passing run left (KEEP=1)
#   PACK=... bash scripts/pack-smoke.sh --clean      # remove the copy and its lock mirror a failed run left
#   bash scripts/pack-smoke.sh --self-test           # the cleanup's guards, on scratch dirs
#
# Every launch's log and Gradle output are kept in <pack>/smoke/. A locked reopen prints the LockedScreen's lines.
# The world audit (scripts/world-audit.sh) runs on every launch; AUDIT_ALLOW (regexes, space-separated) names paths
# a pack's own mods write into the world, recorded per pack in TODO E.1.
# Exit codes: 0 proven · 1 a false lock, a Commitment error or an audit finding · 2 nothing proven (bad arguments,
# leftovers, the client never got into the world, a timeout). Never kills java by name: only the one game JVM this run
# started, found by its command line, and only after a timeout.
set -euo pipefail

cd "$(dirname "$0")/.."

COPY_NAME=commitment-pack-smoke
# Not named after Commitment: the world audit flags any such path that isn't one of Commitment's own.
MARKER_FILE=stage-e-pack-smoke.marker

# remove_copy <saves dir> <copy dir>: removes the world copy only when it's exactly <saves>/commitment-pack-smoke and
# holds the marker this script wrote into it. Fails, removing nothing, otherwise.
remove_copy() {
	local saves=$1 copy=$2
	if [[ -z $saves || $copy != "$saves/$COPY_NAME" || $copy == *..* ]]; then
		echo "pack-smoke: refusing to remove '$copy': not $saves/$COPY_NAME" >&2
		return 1
	fi
	[[ -e $copy ]] || return 0
	if [[ ! -f $copy/$MARKER_FILE ]]; then
		echo "pack-smoke: refusing to remove $copy: it has no $MARKER_FILE, so this script didn't make it" >&2
		return 1
	fi
	rm -r -- "$copy"
	echo "pack-smoke: removed the world copy $copy"
}

# A lock record or mirror: gzip whose content starts with CMTLOCK (LockCodec).
is_lock_file() {
	[[ "$({ gzip -dc -- "$1" 2> /dev/null || true; } | head -c 7)" == "CMTLOCK" ]]
}

# remove_mirrors <mirror dir>: removes each <lock id>.dat in it that is a Commitment lock file; anything else stays.
remove_mirrors() {
	local mirror=$1 f
	if [[ -z $mirror || $mirror != */mirror || $mirror == *..* ]]; then
		echo "pack-smoke: refusing to clear '$mirror': not a pack's mirror dir" >&2
		return 1
	fi
	for f in "$mirror"/*.dat; do
		[[ -e $f ]] || continue
		if is_lock_file "$f"; then
			rm -- "$f"
			echo "pack-smoke: removed the lock mirror $f"
		else
			echo "pack-smoke: $f isn't a Commitment lock file; leaving it" >&2
		fi
	done
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
	mkdir -p "$dir/saves/$COPY_NAME/region" && touch "$dir/saves/$COPY_NAME/$MARKER_FILE"
	remove_copy "$dir/saves" "$dir/saves/$COPY_NAME" > /dev/null
	expect absent "the marked copy goes" "$dir/saves/$COPY_NAME"

	mkdir -p "$dir/saves/$COPY_NAME/region"
	remove_copy "$dir/saves" "$dir/saves/$COPY_NAME" 2> /dev/null || true
	expect present "a copy with no marker stays" "$dir/saves/$COPY_NAME"

	mkdir -p "$dir/saves/players-world" && touch "$dir/saves/players-world/$MARKER_FILE"
	remove_copy "$dir/saves" "$dir/saves/players-world" 2> /dev/null || true
	expect present "another world stays, marker or not" "$dir/saves/players-world"

	touch "$dir/saves/$COPY_NAME/$MARKER_FILE"
	status=0
	remove_copy "" "/$COPY_NAME" 2> /dev/null || status=$?
	if [[ $status -ne 0 ]]; then echo "ok   an empty saves dir is refused"; else echo "FAIL an empty saves dir is refused"; failures=$((failures + 1)); fi
	remove_copy "$dir/saves" "$dir/saves/../saves/$COPY_NAME" 2> /dev/null || true
	expect present "a path with .. is refused" "$dir/saves/$COPY_NAME"

	mkdir -p "$dir/mirror"
	printf 'CMTLOCK fake' | gzip -c > "$dir/mirror/lock.dat"
	echo "not ours" > "$dir/mirror/other.dat"
	remove_mirrors "$dir/mirror" > /dev/null 2>&1
	expect absent "a lock mirror goes" "$dir/mirror/lock.dat"
	expect present "a .dat that isn't a lock file stays" "$dir/mirror/other.dat"
	printf 'CMTLOCK fake' | gzip -c > "$dir/saves/lock.dat"
	remove_mirrors "$dir/saves" 2> /dev/null || true
	expect present "a dir not named mirror is refused" "$dir/saves/lock.dat"
	status=0
	remove_mirrors "" 2> /dev/null || status=$?
	if [[ $status -ne 0 ]]; then echo "ok   an empty mirror dir is refused"; else echo "FAIL an empty mirror dir is refused"; failures=$((failures + 1)); fi

	rm -r -- "$dir"
	if (( failures > 0 )); then
		echo "pack-smoke self-test: $failures failed"
		return 1
	fi
	echo "pack-smoke self-test: all passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
	self_test
	exit $?
fi

PACK=${PACK:?PACK=run/packs/<node>/<pack> is required}
PACK=${PACK%/}
case "$PACK" in
	run/packs/?*/?*) ;;
	*) echo "pack-smoke: PACK must be run/packs/<node>/<pack>, not '$PACK'" >&2; exit 2 ;;
esac
if [[ $PACK == *..* || ! -d $PACK/game ]]; then
	echo "pack-smoke: $PACK/game doesn't exist; install the pack first (scripts/pack-fetch.py)" >&2
	exit 2
fi
GAME=$PACK/game
SAVES=$GAME/saves
COPY=$SAVES/$COPY_NAME
MIRROR=$PACK/mirror

if [[ "${1:-}" == "--clean" ]]; then
	remove_copy "$SAVES" "$COPY"
	remove_mirrors "$MIRROR"
	exit 0
fi

LOADER=${LOADER:?LOADER=fabric|neoforge|forge is required}
NODE=${NODE:?NODE is required}
case "$LOADER" in
	fabric | neoforge | forge) ;;
	*) echo "LOADER must be fabric, neoforge or forge, not '$LOADER'" >&2; exit 2 ;;
esac
PHASES=${PHASES:-commit reopen reopen reopen settings}
LAUNCH_TIMEOUT=${LAUNCH_TIMEOUT:-1200}
SOURCE=run/$LOADER/$NODE/server/world
# Where the commit must write its lock mirror (the pack run's -Dcommitment.mirrorDir), as the log prints it.
MIRROR_WIN=$(cygpath -w "$PWD/$MIRROR")
LOG=$GAME/logs/latest.log
OUT=$PACK/smoke
STARTED=$OUT/started
# The game JVM's command line names its node's build dir, Windows-style.
NODE_PATH=$(cygpath -w "$PWD/$LOADER/versions/$NODE")
read -ra AUDIT_ALLOW_RES <<< "${AUDIT_ALLOW:-}"

first_phase=${PHASES%% *}
if [[ $first_phase == commit ]]; then
	if [[ ! -f $SOURCE/level.dat ]]; then
		echo "pack-smoke: no dev world at $SOURCE; boot the dev server once (scripts/dev-server-smoke.sh)" >&2
		exit 2
	fi
	if [[ -e $SOURCE/data/commitment.dat || -n $(compgen -G "$SOURCE/datapacks/commitment_guard.zip*") ]]; then
		echo "pack-smoke: $SOURCE holds a lock of its own; the copy must start uncommitted" >&2
		exit 2
	fi
	if [[ -e $COPY ]]; then
		echo "pack-smoke: $COPY already exists; refusing to run over it ('--clean' removes a copy this script made)" >&2
		exit 2
	fi
	if compgen -G "$MIRROR/*.dat" > /dev/null; then
		echo "pack-smoke: $MIRROR already holds lock mirrors; refusing to run over them ('--clean')" >&2
		exit 2
	fi
elif [[ ! -f $COPY/$MARKER_FILE ]]; then
	echo "pack-smoke: PHASES starts with $first_phase but there's no committed copy at $COPY; start with commit" >&2
	exit 2
fi

mkdir -p "$SAVES" "$OUT"
if [[ $first_phase == commit ]]; then
	cp -r -- "$SOURCE" "$COPY"
	touch "$COPY/$MARKER_FILE"
	rm -f -- "$COPY/session.lock"
	echo "pack-smoke: copied $SOURCE to $COPY"
fi

java_pids() {
	powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe' or name='javaw.exe'\" | ForEach-Object { \$_.ProcessId }" | tr -d '\r' | sort
}

# The video, audio and shader settings a player changes freely (owner, 2026-10-21): each key present is changed, and
# said. Keys a pack doesn't have are skipped, also said.
change_free_settings() {
	local options=$GAME/options.txt key
	for key in renderDistance:7 gamma:0.75 soundCategory_master:0.35 maxFps:90; do
		if grep -q "^${key%%:*}:" "$options" 2> /dev/null; then
			sed -i "s/^${key%%:*}:.*/$key/" "$options"
			echo "pack-smoke: options.txt ${key%%:*} -> ${key#*:}"
		else
			echo "pack-smoke: options.txt has no ${key%%:*}; not changed"
		fi
	done
	if [[ -f $GAME/config/iris.properties ]]; then
		if grep -q '^enableShaders=' "$GAME/config/iris.properties"; then
			sed -i 's/^enableShaders=.*/enableShaders=true/' "$GAME/config/iris.properties"
		else
			echo "enableShaders=true" >> "$GAME/config/iris.properties"
		fi
		echo "pack-smoke: iris.properties enableShaders -> true"
	fi
	if [[ -f $GAME/config/sodium-options.json ]]; then
		sed -i 's/"use_block_face_culling": *true/"use_block_face_culling": false/' "$GAME/config/sodium-options.json"
		echo "pack-smoke: sodium-options.json use_block_face_culling -> false"
	fi
}

# launch <n> <phase>: one client launch; sets $verdict to 0, 1 or 2.
launch() {
	local n=$1 phase=$2 driver=$2 tag gradle_pid waited=0 game games status
	[[ $phase == settings ]] && driver=reopen
	tag=$OUT/launch-$n-$phase
	bash scripts/world-audit.sh snapshot "$COPY" "$tag.before"
	java_pids > "$STARTED.before"
	touch "$STARTED"
	echo "pack-smoke: launch $n ($phase): :$LOADER:$NODE:runPackClient, output in $tag.out"
	./gradlew ":$LOADER:$NODE:runPackClient" "-Pcommitment.packDir=$PACK" "-Pcommitment.packDriver=$driver" \
		"-Pcommitment.quickPlay=$COPY_NAME" --console=plain > "$tag.out" 2>&1 &
	gradle_pid=$!
	while kill -0 "$gradle_pid" 2> /dev/null && (( waited < LAUNCH_TIMEOUT )); do
		sleep 5
		waited=$((waited + 5))
	done
	verdict=0
	if kill -0 "$gradle_pid" 2> /dev/null; then
		echo "pack-smoke: launch $n ran past ${LAUNCH_TIMEOUT}s" >&2
		game=$(powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe' or name='javaw.exe'\" | Where-Object { \$_.CommandLine -like '*$NODE_PATH*' -and \$_.CommandLine -notlike '*org.gradle*' } | ForEach-Object { \$_.ProcessId }" | tr -d '\r' | sort | comm -13 "$STARTED.before" - | tr '\n' ' ')
		read -ra games <<< "$game"
		if (( ${#games[@]} == 1 )); then
			echo "pack-smoke: closing the game window of PID ${games[0]} (this run's game JVM)" >&2
			powershell -NoProfile -Command "(Get-Process -Id ${games[0]}).CloseMainWindow() | Out-Null"
			sleep 60
			if kill -0 "$gradle_pid" 2> /dev/null; then
				echo "pack-smoke: PID ${games[0]} didn't close; stopping it" >&2
				powershell -NoProfile -Command "Stop-Process -Id ${games[0]}"
			fi
		else
			echo "pack-smoke: NOTHING PROVEN (exit 2): can't tell this run's game JVM (${games[*]:-none}); stop it yourself" >&2
		fi
		verdict=2
	fi
	status=0
	wait "$gradle_pid" || status=$?
	if [[ ! -f $LOG || ! $LOG -nt $STARTED ]]; then
		echo "pack-smoke: NOTHING PROVEN (exit 2): launch $n wrote no log (Gradle exit $status). Tail of $tag.out:" >&2
		tail -30 "$tag.out" >&2
		verdict=2
		return
	fi
	cp -- "$LOG" "$tag.log"
	echo "pack-smoke: launch $n: Gradle exit $status after ${waited}s; log kept as $tag.log"

	local re line
	for re in "Commitment [^ ]+ loaded on (Fabric|Quilt|NeoForge|Forge) " "Commitment pack driver: phase $driver"; do
		if line=$(grep -m1 -E -- "$re" "$tag.log"); then
			echo "matched $re: $line"
		else
			echo "FAIL: no line matching $re"
			(( verdict == 0 )) && verdict=2
		fi
	done
	# Loader and vanilla prompts the driver answered as a player would: part of the run's record.
	grep -E -- 'Commitment pack driver: (pressing|proceeding)' "$tag.log" | sed 's/^/note: /' || true
	if line=$(grep -m1 -E -- "Commitment pack driver: .* phase over \(stuck" "$tag.log"); then
		echo "pack-smoke: NOTHING PROVEN: $line"
		(( verdict == 0 )) && verdict=2
	fi
	if grep -qE -- 'Commitment: locked screen \(' "$tag.log"; then
		echo "FAIL: launch $n ($phase) was locked. What the LockedScreen listed:"
		grep -E -- 'Commitment: locked screen|[])]:? Commitment:   ' "$tag.log" | sed 's/^/    /' | head -60
		verdict=1
	elif [[ $phase == commit ]]; then
		for re in 'Commitment: world committed \(' "Commitment pack driver: commit phase over \(commit done\)"; do
			if line=$(grep -m1 -E -- "$re" "$tag.log"); then
				echo "matched $re: $line"
			else
				echo "FAIL: no line matching $re"
				(( verdict == 0 )) && verdict=1
			fi
		done
		# The mirror goes to this pack's mirror dir, never the dev server's (where the smokes refuse leftovers) or the
		# developer's own user data dir.
		if line=$(grep -m1 -F -- "Commitment: wrote lock mirror $MIRROR_WIN" "$tag.log"); then
			echo "matched the lock mirror in $MIRROR: $line"
		else
			echo "FAIL: the lock mirror wasn't written into $MIRROR_WIN: $(grep -m1 -F 'wrote lock mirror' "$tag.log" || echo 'no mirror line')"
			(( verdict == 0 )) && verdict=1
		fi
	else
		for re in "Commitment: scanned the setup of $COPY_NAME in [0-9]+ ms" "Commitment: $COPY_NAME is committed and its setup matches" \
				"Commitment pack driver: reopen phase over \(reopen done\)"; do
			if line=$(grep -m1 -E -- "$re" "$tag.log"); then
				echo "matched $re: $line"
			else
				echo "FAIL: no line matching $re"
				(( verdict == 0 )) && verdict=1
			fi
		done
	fi
	if line=$(grep -E -- '/(ERROR|FATAL)\]' "$tag.log" | grep -i 'commitment'); then
		echo "FAIL: Commitment logged errors:"
		echo "$line" | head -20
		verdict=1
	fi
	bash scripts/world-audit.sh snapshot "$COPY" "$tag.after"
	if ! bash scripts/world-audit.sh compare "$tag.before" "$tag.after" "${AUDIT_ALLOW_RES[@]}"; then
		echo "FAIL: launch $n: the world audit found changes outside vanilla's and Commitment's own files"
		verdict=1
	fi
}

n=$(find "$OUT" -maxdepth 1 -name 'launch-*.out' | wc -l)
result=0
for phase in $PHASES; do
	case "$phase" in
		commit | reopen | settings) ;;
		*) echo "pack-smoke: unknown phase '$phase' (commit, reopen, settings)" >&2; exit 2 ;;
	esac
	n=$((n + 1))
	[[ $phase == settings ]] && change_free_settings
	launch "$n" "$phase"
	if (( verdict != 0 )); then
		result=$verdict
		break
	fi
done

if (( result == 0 )); then
	if [[ ${KEEP:-0} == 1 ]]; then
		echo "pack-smoke: KEEP=1: the committed copy and its mirror stay for more reopens"
	else
		remove_copy "$SAVES" "$COPY"
		remove_mirrors "$MIRROR"
	fi
	echo "pack-smoke: PASS ($LOADER $NODE $PACK: $PHASES)"
	exit 0
fi
echo "pack-smoke: the world copy $COPY and the mirror in $MIRROR stay to look at; '--clean' removes them"
if (( result == 2 )); then
	echo "pack-smoke: NOTHING PROVEN (exit 2) ($LOADER $NODE $PACK)"
	exit 2
fi
echo "pack-smoke: FAIL ($LOADER $NODE $PACK)"
exit 1
