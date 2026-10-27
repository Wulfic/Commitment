#!/usr/bin/env bash
# The client's world-open gate and locked screen on a real client, for the loaders with no client GameTest (C.4:
# NeoForge and Forge). Copies the dev server's world into the client's saves as a disposable world holding only the
# golden guard pack (a lock whose record is gone: locked whatever its setup), launches :<loader>:<node>:runClient
# straight into it with vanilla's --quickPlaySingleplayer (which opens it through openWorld, so the gate runs), waits
# for the locked screen's log line, stops the one game JVM this run started, and removes the copy and the mirror the
# mod wrote for the golden lock.
#
#   LOADER=neoforge NODE=26.3 bash scripts/client-locked-smoke.sh
#   bash scripts/client-locked-smoke.sh --self-test        # the copy's removal guards, on scratch dirs
#
# Exit codes: 0 proven · 1 the mod is bad · 2 nothing proven (no source world, leftovers, the client never launched,
# or the game JVM couldn't be told apart from others: then it's left running and named).
# Never kills java by name: the owner's game and other repos' servers run on this machine.
set -euo pipefail

cd "$(dirname "$0")/.."

COPY_NAME=commitment-locked-smoke
MARKER_FILE=commitment-locked-smoke.marker
LOCKED_LINE='Commitment: locked screen \([0-9]+ changes\)'
REASON_LINE='Commitment: no lock record can be trusted'
# The LockedScreen (C.4) built its body and its buttons, labelled from the lang file (a raw key means it didn't load).
SHOWN_LINE='Commitment: locked screen shows [1-9][0-9]* lines; buttons: Back, Start 24-hour unlock'
# Each change the screen lists is in the log too (ClientGate.CHANGE_LOG), so a player's log shows why they're locked.
CHANGE_LINE=']:? Commitment:   [^ ]'

# remove_copy <saves dir> <copy dir>: removes the world copy only when it's exactly <saves>/commitment-locked-smoke
# and holds the marker this script wrote into it. Fails, removing nothing, otherwise.
remove_copy() {
	local saves=$1 copy=$2
	if [[ -z $saves || $copy != "$saves/$COPY_NAME" || $copy == *..* ]]; then
		echo "client-locked-smoke: refusing to remove '$copy': not $saves/$COPY_NAME" >&2
		return 1
	fi
	[[ -e $copy ]] || return 0
	if [[ ! -f $copy/$MARKER_FILE ]]; then
		echo "client-locked-smoke: refusing to remove $copy: it has no $MARKER_FILE, so this script didn't make it" >&2
		return 1
	fi
	rm -r -- "$copy"
	echo "client-locked-smoke: removed the world copy $copy"
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

	mkdir -p "$dir/saves/someone-elses-world" && touch "$dir/saves/someone-elses-world/$MARKER_FILE"
	remove_copy "$dir/saves" "$dir/saves/someone-elses-world" 2> /dev/null || true
	expect present "another world stays, marker or not" "$dir/saves/someone-elses-world"

	touch "$dir/saves/$COPY_NAME/$MARKER_FILE"
	status=0
	remove_copy "" "/$COPY_NAME" 2> /dev/null || status=$?
	if [[ $status -ne 0 ]]; then echo "ok   an empty saves dir is refused"; else echo "FAIL an empty saves dir is refused"; failures=$((failures + 1)); fi
	status=0
	remove_copy "$dir/saves" "$dir/saves/../saves/$COPY_NAME" 2> /dev/null || status=$?
	expect present "a path with .. is refused" "$dir/saves/$COPY_NAME"

	rm -r -- "$dir"
	if (( failures > 0 )); then
		echo "client-locked-smoke self-test: $failures failed"
		return 1
	fi
	echo "client-locked-smoke self-test: all passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
	self_test
	exit $?
fi

LOADER=${LOADER:-neoforge}
NODE=${NODE:-26.3}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-480}
case "$LOADER" in
	fabric | neoforge | forge) ;;
	*) echo "LOADER must be fabric, neoforge or forge, not '$LOADER'" >&2; exit 2 ;;
esac
RUN=run/$LOADER/$NODE
SOURCE=$RUN/server/world
SAVES=$RUN/client/saves
COPY=$SAVES/$COPY_NAME
MIRROR=$RUN/mirror
LOG=$RUN/client/logs/latest.log
OUT=build/client-locked-smoke-$LOADER-$NODE.out
STARTED=build/client-locked-smoke-$LOADER-$NODE.started
GOLDEN=common/src/test/resources/guard-pack
GOLDEN_LOCK=$(sed -n 's/^lock=//p' "$GOLDEN/commitment.lock" | tr -d '\r')
# The game JVM's command line names its node's build dir, Windows-style.
NODE_PATH=$(cygpath -w "$PWD/$LOADER/versions/$NODE")

if [[ ! -f $SOURCE/level.dat ]]; then
	echo "client-locked-smoke: no dev world at $SOURCE; boot the dev server once (scripts/dev-server-smoke.sh)" >&2
	exit 2
fi
if [[ -e $COPY ]]; then
	echo "client-locked-smoke: $COPY already exists; refusing to run over it. If it's a leftover of this script, remove it." >&2
	exit 2
fi
if compgen -G "$MIRROR/*.dat" > /dev/null; then
	echo "client-locked-smoke: $MIRROR already holds lock mirrors; refusing to run over them." >&2
	exit 2
fi

# A lock mirror of the golden lock: gzip whose content starts with CMTLOCK (LockCodec).
is_lock_file() {
	[[ "$({ gzip -dc -- "$1" 2> /dev/null || true; } | head -c 7)" == "CMTLOCK" ]]
}

cleanup() {
	remove_copy "$SAVES" "$COPY" || true
	local mirror=$MIRROR/$GOLDEN_LOCK.dat
	if [[ -e $mirror ]]; then
		if is_lock_file "$mirror"; then
			rm -- "$mirror"
			echo "client-locked-smoke: removed $mirror"
		else
			echo "client-locked-smoke: $mirror isn't a Commitment lock file; leaving it" >&2
		fi
	fi
}
trap cleanup EXIT

mkdir -p "$SAVES" build
# A fresh client shows vanilla's accessibility onboarding first and holds Quick Play behind it until someone clicks.
# This is the dev client's own run dir (run/<loader>/<node>/client): only that one key changes, and the change is said.
OPTIONS=$RUN/client/options.txt
if [[ ! -f $OPTIONS ]]; then
	echo "onboardAccessibility:false" > "$OPTIONS"
	echo "client-locked-smoke: wrote $OPTIONS with onboardAccessibility:false (a fresh client would wait at onboarding)"
elif grep -q '^onboardAccessibility:true' "$OPTIONS"; then
	sed -i 's/^onboardAccessibility:true/onboardAccessibility:false/' "$OPTIONS"
	echo "client-locked-smoke: set onboardAccessibility:false in $OPTIONS (a fresh client would wait at onboarding)"
fi
cp -r -- "$SOURCE" "$COPY"
touch "$COPY/$MARKER_FILE"
rm -f -- "$COPY/session.lock"
mkdir -p "$COPY/datapacks"
jar --create --no-manifest --file "$COPY/datapacks/commitment_guard.zip" -C "$GOLDEN" .
echo "client-locked-smoke: copied $SOURCE to $COPY with the golden guard pack (lock $GOLDEN_LOCK)"

# The java processes already running: the one this run starts is the new one with this node's path in its command line.
java_pids() {
	powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe' or name='javaw.exe'\" | ForEach-Object { \$_.ProcessId }" | tr -d '\r' | sort
}
java_pids > "$STARTED.before"
touch "$STARTED"

echo "client-locked-smoke: :$LOADER:$NODE:runClient into $COPY_NAME, output in $OUT"
./gradlew ":$LOADER:$NODE:runClient" "-Pcommitment.quickPlay=$COPY_NAME" --console=plain > "$OUT" 2>&1 &
gradle_pid=$!

waited=0 seen=no
while (( waited < BOOT_TIMEOUT )); do
	if [[ -f $LOG && $LOG -nt $STARTED ]] && grep -qE -- "$LOCKED_LINE" "$LOG"; then
		seen=yes
		break
	fi
	if ! kill -0 "$gradle_pid" 2> /dev/null; then
		break
	fi
	sleep 3
	waited=$((waited + 3))
done
# Let the screen draw a few frames before the window goes.
[[ $seen == yes ]] && sleep 5

# The game JVM: new since the launch, this node's path in its command line, and not Gradle's own.
game=$(powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe' or name='javaw.exe'\" | Where-Object { \$_.CommandLine -like '*$NODE_PATH*' -and \$_.CommandLine -notlike '*org.gradle*' } | ForEach-Object { \$_.ProcessId }" | tr -d '\r' | sort | comm -13 "$STARTED.before" - | tr '\n' ' ')
read -ra games <<< "$game"
status=0
if (( ${#games[@]} == 1 )); then
	echo "client-locked-smoke: stopping the game JVM this run started, PID ${games[0]}"
	powershell -NoProfile -Command "Stop-Process -Id ${games[0]}"
elif (( ${#games[@]} == 0 )); then
	echo "client-locked-smoke: no game JVM of this run is running" >&2
else
	echo "client-locked-smoke: NOTHING PROVEN (exit 2): more than one new java process names $NODE_PATH (${games[*]}); stop the game yourself" >&2
	status=2
fi
wait "$gradle_pid" || true

if [[ $status -eq 2 ]]; then
	exit 2
fi
if [[ ! -f $LOG || ! $LOG -nt $STARTED ]]; then
	echo "client-locked-smoke: NOTHING PROVEN (exit 2): the client never wrote a log. Tail of $OUT:" >&2
	tail -30 "$OUT" >&2
	exit 2
fi
fail=0
for re in "$LOCKED_LINE" "$CHANGE_LINE" "$REASON_LINE" "$SHOWN_LINE" "Commitment [^ ]+ loaded on (Fabric|Quilt|NeoForge|Forge) "; do
	if line=$(grep -m1 -E -- "$re" "$LOG"); then
		echo "matched $re: $line"
	else
		echo "FAIL: no line matching $re in $LOG"
		fail=1
	fi
done
if errors=$(grep -E -- '/(ERROR|FATAL)\]' "$LOG" | grep -i 'commitment'); then
	echo "FAIL: Commitment logged errors:"
	echo "$errors"
	fail=1
fi
# A loading warning puts NeoForge's warnings screen in front of every launch (logoFile on 26.2+, gradle.properties).
if line=$(grep -m1 -E -- 'Mod commitment uses|Mods loaded with [0-9]+ warning' "$LOG"); then
	echo "FAIL: mod loading warned, so players get a warnings screen at launch: $line"
	fail=1
fi
if grep -qE 'Commitment: .* is committed and its setup matches|Preparing start region|Loaded [0-9]+ advancements' "$LOG"; then
	echo "FAIL: the world loaded behind the locked screen"
	fail=1
fi
if (( fail == 0 )); then
	echo "client-locked-smoke: PASS ($LOADER $NODE)"
	exit 0
fi
echo "client-locked-smoke: FAIL ($LOADER $NODE)"
exit 1
