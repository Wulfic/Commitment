#!/usr/bin/env bash
# The shipped Fabric jar on a real Fabric or Quilt client, through the owner's Prism Launcher (TODO F.2 step 4): the
# rung-5 check for the clients no dev environment here runs (Quilt; Fabric on 26.1 and 26.1.1). One instance,
# commitment-smoke, made by this script and marked; each run rewrites its loader, Minecraft and mods, then launches
# it twice into copies of rung 6's world of that Minecraft (made by that version, so no downgrade):
#   locked: the copy holds only the golden guard pack (a lock whose record is gone) → the locked screen's lines
#   open:   the copy as it is, uncommitted → the player joins, no locked screen
#
#   PRISM_LOADER=quilt NODE=26.1.2 MC=26.1 bash scripts/prism-smoke.sh
#   PRISM_LOADER=fabric NODE=26.1.2 MC=26.1.1 bash scripts/prism-smoke.sh
#   bash scripts/prism-smoke.sh --self-test        # the instance, mods and copy guards, on scratch dirs
#
# Needs rung 6 first: `LOADER=<loader> NODE=<node> REAL=<mc> scripts/commit-smoke.sh ...` leaves the world and the
# checked Fabric API this uses in run/real/<loader>/<node>/<mc>/server.
# Prism reads an instance's files only when it starts, so this refuses to run while any Prism is running, starts
# Prism itself, and closes the Prism it started. The game's LOCALAPPDATA (the lock mirror's root, MirrorDir) is
# run/prism/appdata, set through the instance's environment; the owner's own %LOCALAPPDATA%\Commitment is checked
# before and after.
#
# Exit codes: 0 proven · 1 the mod is bad · 2 nothing proven (missing inputs, Prism running, a timeout, a process that
# couldn't be told apart from others: then it's left running and named).
# Never kills java or Prism by name: the owner's game and launcher run on this machine.
set -euo pipefail

cd "$(dirname "$0")/.."

INSTANCE=commitment-smoke
INSTANCE_MARKER=.commitment-smoke
COPY_MARKER=commitment-prism-smoke.marker
LOCKED_COPY=commitment-locked-smoke
OPEN_COPY=commitment-open-smoke
LOCKED_LINE='Commitment: locked screen \([0-9]+ changes\)'
REASON_LINE='Commitment: no lock record can be trusted'
SHOWN_LINE='Commitment: locked screen shows [1-9][0-9]* lines; buttons: Back, Start 24-hour unlock'
CHANGE_LINE='[])]:? Commitment:   [^ ]'
JOINED_LINE='joined the game'

# prepare_instance <instances dir>: makes <instances>/commitment-smoke with its marker, or accepts it when the marker is
# there. A folder of that name without the marker is someone else's: refused, nothing touched.
prepare_instance() {
	local instances=$1 dir=$1/$INSTANCE
	if [[ -z $instances || ! -d $instances ]]; then
		echo "prism-smoke: refusing: '$instances' isn't Prism's instances dir" >&2
		return 1
	fi
	if [[ -e $dir && ! -f $dir/$INSTANCE_MARKER ]]; then
		echo "prism-smoke: refusing to use $dir: it has no $INSTANCE_MARKER, so this script didn't make it" >&2
		return 1
	fi
	mkdir -p "$dir/minecraft/mods" "$dir/minecraft/saves"
	[[ -f $dir/$INSTANCE_MARKER ]] || echo "made by scripts/prism-smoke.sh (Commitment repo) for F.2's client checks" > "$dir/$INSTANCE_MARKER"
}

# clear_mods <instance dir>: removes Commitment and Fabric API jars from the instance's mods, only in the marked
# instance, nothing else there.
clear_mods() {
	local dir=$1 jar
	if [[ $(basename -- "$dir") != "$INSTANCE" || ! -f $dir/$INSTANCE_MARKER || $dir == *..* ]]; then
		echo "prism-smoke: refusing to clear mods in '$dir': not the marked $INSTANCE instance" >&2
		return 1
	fi
	for jar in "$dir"/minecraft/mods/commitment-*.jar "$dir"/minecraft/mods/fabric-api-*.jar; do
		[[ -e $jar ]] || continue
		rm -- "$jar"
		echo "prism-smoke: removed $jar"
	done
}

# remove_copy <saves dir> <copy dir>: removes a world copy only when it's <saves>/<one of this script's copy names> and
# holds the marker this script wrote into it.
remove_copy() {
	local saves=$1 copy=$2
	if [[ -z $saves || ($copy != "$saves/$LOCKED_COPY" && $copy != "$saves/$OPEN_COPY") || $copy == *..* ]]; then
		echo "prism-smoke: refusing to remove '$copy': not $saves/$LOCKED_COPY or $OPEN_COPY" >&2
		return 1
	fi
	[[ -e $copy ]] || return 0
	if [[ ! -f $copy/$COPY_MARKER ]]; then
		echo "prism-smoke: refusing to remove $copy: it has no $COPY_MARKER, so this script didn't make it" >&2
		return 1
	fi
	rm -r -- "$copy"
	echo "prism-smoke: removed the world copy $copy"
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
	refused() { # refused <name> <command...>
		local name=$1
		shift
		if "$@" > /dev/null 2>&1; then
			echo "FAIL $name"
			failures=$((failures + 1))
		else
			echo "ok   $name"
		fi
	}

	mkdir -p "$dir/instances/$INSTANCE/minecraft/mods" && touch "$dir/instances/$INSTANCE/minecraft/mods/theirs.jar"
	refused "an unmarked $INSTANCE folder is refused" prepare_instance "$dir/instances"
	expect absent "... and isn't marked" "$dir/instances/$INSTANCE/$INSTANCE_MARKER"
	refused "an empty instances dir is refused" prepare_instance ""
	rm -r -- "$dir/instances/$INSTANCE"
	prepare_instance "$dir/instances" > /dev/null
	expect present "a new instance is marked" "$dir/instances/$INSTANCE/$INSTANCE_MARKER"
	prepare_instance "$dir/instances" > /dev/null || { echo "FAIL a marked instance is accepted again"; failures=$((failures + 1)); }

	local mods=$dir/instances/$INSTANCE/minecraft/mods
	touch "$mods/commitment-1.0.1+fabric-quilt-mc26.1-26.1.2.jar" "$mods/fabric-api-0.145.1+26.1.jar" "$mods/other-mod.jar"
	clear_mods "$dir/instances/$INSTANCE" > /dev/null
	expect absent "clear_mods removes the Commitment jar" "$mods/commitment-1.0.1+fabric-quilt-mc26.1-26.1.2.jar"
	expect absent "clear_mods removes Fabric API" "$mods/fabric-api-0.145.1+26.1.jar"
	expect present "clear_mods leaves any other jar" "$mods/other-mod.jar"
	mkdir -p "$dir/other/$INSTANCE/minecraft/mods" && touch "$dir/other/$INSTANCE/minecraft/mods/commitment-x.jar"
	refused "clear_mods refuses an unmarked instance" clear_mods "$dir/other/$INSTANCE"
	expect present "... and removes nothing there" "$dir/other/$INSTANCE/minecraft/mods/commitment-x.jar"
	mkdir -p "$dir/1.21.11/minecraft/mods" && touch "$dir/1.21.11/$INSTANCE_MARKER" "$dir/1.21.11/minecraft/mods/commitment-x.jar"
	refused "clear_mods refuses another instance, even marked" clear_mods "$dir/1.21.11"
	expect present "... and removes nothing there" "$dir/1.21.11/minecraft/mods/commitment-x.jar"

	local saves=$dir/saves
	mkdir -p "$saves/$LOCKED_COPY/region" "$saves/$OPEN_COPY" && touch "$saves/$LOCKED_COPY/$COPY_MARKER" "$saves/$OPEN_COPY/$COPY_MARKER"
	remove_copy "$saves" "$saves/$LOCKED_COPY" > /dev/null
	remove_copy "$saves" "$saves/$OPEN_COPY" > /dev/null
	expect absent "the marked locked copy goes" "$saves/$LOCKED_COPY"
	expect absent "the marked open copy goes" "$saves/$OPEN_COPY"
	mkdir -p "$saves/$LOCKED_COPY"
	refused "a copy with no marker is refused" remove_copy "$saves" "$saves/$LOCKED_COPY"
	expect present "... and stays" "$saves/$LOCKED_COPY"
	mkdir -p "$saves/my-world" && touch "$saves/my-world/$COPY_MARKER"
	refused "another world is refused, marker or not" remove_copy "$saves" "$saves/my-world"
	expect present "... and stays" "$saves/my-world"
	touch "$saves/$LOCKED_COPY/$COPY_MARKER"
	refused "an empty saves dir is refused" remove_copy "" "/$LOCKED_COPY"
	refused "a path with .. is refused" remove_copy "$saves" "$saves/../saves/$LOCKED_COPY"
	expect present "... and the copy stays" "$saves/$LOCKED_COPY"

	rm -r -- "$dir"
	if (( failures > 0 )); then
		echo "prism-smoke self-test: $failures failed"
		return 1
	fi
	echo "prism-smoke self-test: all passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
	self_test
	exit $?
fi

source scripts/server-jars.sh

PRISM_LOADER=${PRISM_LOADER:-}
NODE=${NODE:-}
MC=${MC:-}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-900}
case "$PRISM_LOADER" in
	fabric) LOADER_UID=net.fabricmc.fabric-loader; LOADER_VERSION=$(gradle_prop loader_version); LOADER_NAME=Fabric ;;
	quilt) LOADER_UID=org.quiltmc.quilt-loader; LOADER_VERSION=$(gradle_prop quilt_loader_version); LOADER_NAME=Quilt ;;
	*) echo "PRISM_LOADER must be fabric or quilt, not '$PRISM_LOADER'" >&2; exit 2 ;;
esac
# The band's lowest Minecraft: the label is that version, or LOW-HIGH when the band spans several.
label=$(gradle_prop "band_label.$NODE" 2> /dev/null || true)
label=${label%%-*}
if [[ ! $MC =~ ^[0-9]+(\.[0-9]+)+$ || -z $label || ($MC != "$label" && $MC != "$label".*) ]]; then
	echo "prism-smoke: MC=$MC isn't a Minecraft of node '$NODE's band (${label:-none})" >&2
	exit 2
fi

PRISM_DIR=${PRISM_DIR:-$(cygpath -u "$APPDATA")/PrismLauncher}
PRISM_EXE=${PRISM_EXE:-$(cygpath -u "$LOCALAPPDATA")/Programs/PrismLauncher/prismlauncher.exe}
IDIR=$PRISM_DIR/instances/$INSTANCE
GAME=$IDIR/minecraft
SAVES=$GAME/saves
LOG=$GAME/logs/latest.log
REAL_DIR=run/real/$PRISM_LOADER/$NODE/$MC/server
SOURCE=$REAL_DIR/world
APPDATA_RUN=run/prism/appdata
MIRROR_DIR=$APPDATA_RUN/Commitment/locks
OWNER_MIRROR=$(cygpath -u "$LOCALAPPDATA")/Commitment
OUT=build/prism-smoke-$PRISM_LOADER-$MC
GOLDEN=common/src/test/resources/guard-pack
GOLDEN_LOCK=$(sed -n 's/^lock=//p' "$GOLDEN/commitment.lock" | tr -d '\r')
BOOT_RE="Commitment [^ ]+ loaded on $LOADER_NAME $(sed 's/\./\\./g' <<< "$LOADER_VERSION"), Minecraft $(sed 's/\./\\./g' <<< "$MC")[[:space:]]*$"

[[ -x $PRISM_EXE ]] || { echo "prism-smoke: NOTHING PROVEN (exit 2): no Prism at $PRISM_EXE" >&2; exit 2; }
[[ -d $PRISM_DIR/instances ]] || { echo "prism-smoke: NOTHING PROVEN (exit 2): no Prism instances dir at $PRISM_DIR/instances" >&2; exit 2; }
if [[ ! -f $SOURCE/level.dat ]]; then
	echo "prism-smoke: NOTHING PROVEN (exit 2): no world at $SOURCE; run rung 6 first (LOADER=$PRISM_LOADER NODE=$NODE REAL=$MC scripts/commit-smoke.sh ...)" >&2
	exit 2
fi
API=$(compgen -G "$REAL_DIR/mods/fabric-api-*.jar" || true)
if [[ $(wc -w <<< "$API") -ne 1 ]]; then
	echo "prism-smoke: NOTHING PROVEN (exit 2): need exactly one checked Fabric API in $REAL_DIR/mods (found: ${API:-none})" >&2
	exit 2
fi
BUILT=$(compgen -G "fabric/versions/$NODE/build/libs/commitment-*+fabric*-mc*.jar" | grep -v -- '-sources\.jar$' || true)
if [[ $(wc -w <<< "$BUILT") -ne 1 ]]; then
	echo "prism-smoke: NOTHING PROVEN (exit 2): need exactly one built Fabric jar in fabric/versions/$NODE/build/libs (found: ${BUILT:-none})" >&2
	exit 2
fi
if [[ -e $OWNER_MIRROR ]]; then
	echo "prism-smoke: NOTHING PROVEN (exit 2): $OWNER_MIRROR exists, so a mirror written there by mistake couldn't be told apart" >&2
	exit 2
fi
if compgen -G "$MIRROR_DIR/*.dat" > /dev/null; then
	echo "prism-smoke: NOTHING PROVEN (exit 2): $MIRROR_DIR already holds lock mirrors; refusing to run over them" >&2
	exit 2
fi

ps_pids() { # ps_pids <filter script>: PIDs of the processes the PowerShell filter selects, sorted
	powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $1 } | ForEach-Object { \$_.ProcessId }" | tr -d '\r' | sort
}
PRISM_FILTER="\$_.Name -eq 'prismlauncher.exe'"
GAME_FILTER="(\$_.Name -eq 'java.exe' -or \$_.Name -eq 'javaw.exe') -and \$_.CommandLine -match 'instances[\\\\/]$INSTANCE[\\\\/]'"
if [[ -n $(ps_pids "$PRISM_FILTER") ]]; then
	echo "prism-smoke: NOTHING PROVEN (exit 2): Prism is running; close it first (it reads the instance's files only at start)" >&2
	exit 2
fi
if [[ -n $(ps_pids "$GAME_FILTER") ]]; then
	echo "prism-smoke: NOTHING PROVEN (exit 2): a game of the $INSTANCE instance is already running" >&2
	exit 2
fi

prepare_instance "$PRISM_DIR/instances" || exit 2
clear_mods "$IDIR" || exit 2
for copy in "$SAVES/$LOCKED_COPY" "$SAVES/$OPEN_COPY"; do
	if [[ -e $copy ]]; then
		echo "prism-smoke: NOTHING PROVEN (exit 2): $copy already exists; if it's a leftover of this script, remove it" >&2
		exit 2
	fi
done

# A lock mirror: gzip whose content starts with CMTLOCK (LockCodec).
is_lock_file() {
	[[ "$({ gzip -dc -- "$1" 2> /dev/null || true; } | head -c 7)" == "CMTLOCK" ]]
}
cleanup() {
	remove_copy "$SAVES" "$SAVES/$LOCKED_COPY" || true
	remove_copy "$SAVES" "$SAVES/$OPEN_COPY" || true
	local mirror=$MIRROR_DIR/$GOLDEN_LOCK.dat
	if [[ -e $mirror ]]; then
		if is_lock_file "$mirror"; then
			rm -- "$mirror"
			echo "prism-smoke: removed $mirror"
		else
			echo "prism-smoke: $mirror isn't a Commitment lock file; leaving it" >&2
		fi
	fi
}
trap cleanup EXIT

# The instance, as this run wants it. Only the marked instance's files are written.
mkdir -p "$APPDATA_RUN" build
cat > "$IDIR/instance.cfg" << EOF
[General]
ConfigVersion=1.3
InstanceType=OneSix
name=$INSTANCE
OverrideEnv=true
Env="{\"LOCALAPPDATA\":\"$(cygpath -m "$PWD/$APPDATA_RUN")\"}"
EOF
cat > "$IDIR/mmc-pack.json" << EOF
{
    "components": [
        { "important": true, "uid": "net.minecraft", "version": "$MC" },
        { "uid": "$LOADER_UID", "version": "$LOADER_VERSION" }
    ],
    "formatVersion": 1
}
EOF
cp -- "$BUILT" "$API" "$GAME/mods/"
echo "prism-smoke: $INSTANCE = Minecraft $MC + $LOADER_NAME $LOADER_VERSION, mods $(basename "$BUILT") $(basename "$API")"
# A fresh client shows vanilla's accessibility onboarding first and holds Quick Play behind it.
OPTIONS=$GAME/options.txt
if [[ ! -f $OPTIONS ]]; then
	echo "onboardAccessibility:false" > "$OPTIONS"
elif grep -q '^onboardAccessibility:true' "$OPTIONS"; then
	sed -i 's/^onboardAccessibility:true/onboardAccessibility:false/' "$OPTIONS"
fi

# launch <copy name> <line to wait for>: Prism into the world copy, until the line is logged or the timeout; then the
# game JVM and the Prism this launch started are stopped. Exit 2 when a process can't be told apart.
launch() {
	local copy=$1 wait_re=$2 started=$OUT-$1.started waited=0 game prism pid
	ps_pids "$GAME_FILTER" > "$started.games"
	ps_pids "$PRISM_FILTER" > "$started.prisms"
	touch "$started"
	echo "prism-smoke: prismlauncher --launch $INSTANCE --world $copy (timeout ${BOOT_TIMEOUT}s)"
	"$PRISM_EXE" --launch "$INSTANCE" --world "$copy" > "$OUT-$copy.prism.out" 2>&1 &
	while (( waited < BOOT_TIMEOUT )); do
		if [[ -f $LOG && $LOG -nt $started ]] && grep -qE -- "$wait_re" "$LOG"; then
			echo "prism-smoke: logged after ${waited}s: $(grep -m1 -E -- "$wait_re" "$LOG")"
			break
		fi
		sleep 3
		waited=$((waited + 3))
	done
	(( waited < BOOT_TIMEOUT )) && sleep 5
	game=$(ps_pids "$GAME_FILTER" | comm -13 "$started.games" - | tr '\n' ' ')
	read -ra game <<< "$game"
	if (( ${#game[@]} > 1 )); then
		echo "prism-smoke: NOTHING PROVEN (exit 2): more than one new game JVM of $INSTANCE (${game[*]}); stop them yourself" >&2
		return 2
	fi
	if (( ${#game[@]} == 1 )); then
		echo "prism-smoke: stopping the game JVM this launch started, PID ${game[0]}"
		powershell -NoProfile -Command "Stop-Process -Id ${game[0]}"
	else
		echo "prism-smoke: no game JVM of this launch is running" >&2
	fi
	prism=$(ps_pids "$PRISM_FILTER" | comm -13 "$started.prisms" - | tr '\n' ' ')
	read -ra prism <<< "$prism"
	for pid in "${prism[@]}"; do
		# Its main window first, so it saves what it keeps; then the process, if it's still there after 15 s.
		echo "prism-smoke: closing the Prism this launch started, PID $pid"
		powershell -NoProfile -Command "\$p = Get-Process -Id $pid -ErrorAction SilentlyContinue; if (\$p) { [void]\$p.CloseMainWindow(); if (-not \$p.WaitForExit(15000)) { Stop-Process -Id $pid } }"
	done
	wait || true
	if (( waited >= BOOT_TIMEOUT )); then
		echo "prism-smoke: NOTHING PROVEN (exit 2): no line matching $wait_re in ${BOOT_TIMEOUT}s. Prism's log: $PRISM_DIR/logs; game log: $LOG" >&2
		[[ -f $LOG && $LOG -nt $started ]] && tail -20 "$LOG" >&2
		return 2
	fi
	cp -- "$LOG" "$OUT-$copy.log"
}

# check_log <log> <must match...> -- <must not match...>: the verdict on one launch's log.
check_log() {
	local log=$1 re line fail=0
	shift
	while (( $# > 0 )) && [[ $1 != -- ]]; do
		if line=$(grep -m1 -E -- "$1" "$log"); then echo "matched $1: $line"; else echo "FAIL: no line matching $1 in $log"; fail=1; fi
		shift
	done
	[[ ${1:-} == -- ]] && shift
	for re in "$@"; do
		if line=$(grep -m1 -E -- "$re" "$log"); then echo "FAIL: matched $re: $line"; fail=1; fi
	done
	if line=$(grep -E -- '/(ERROR|FATAL)\]' "$log" | grep -i 'commitment'); then
		echo "FAIL: Commitment logged errors:"
		echo "$line"
		fail=1
	fi
	return $fail
}

status=0
# locked: the server's world with only the golden guard pack.
cp -r -- "$SOURCE" "$SAVES/$LOCKED_COPY"
touch "$SAVES/$LOCKED_COPY/$COPY_MARKER"
rm -f -- "$SAVES/$LOCKED_COPY/session.lock"
mkdir -p "$SAVES/$LOCKED_COPY/datapacks"
jar --create --no-manifest --file "$SAVES/$LOCKED_COPY/datapacks/commitment_guard.zip" -C "$GOLDEN" .
echo "prism-smoke: copied $SOURCE to $SAVES/$LOCKED_COPY with the golden guard pack (lock $GOLDEN_LOCK)"
launch "$LOCKED_COPY" "$LOCKED_LINE" || exit $?
check_log "$OUT-$LOCKED_COPY.log" "$LOCKED_LINE" "$CHANGE_LINE" "$REASON_LINE" "$SHOWN_LINE" "$BOOT_RE" -- "$JOINED_LINE" || status=1

# open: the server's world as rung 6 left it, uncommitted.
cp -r -- "$SOURCE" "$SAVES/$OPEN_COPY"
touch "$SAVES/$OPEN_COPY/$COPY_MARKER"
rm -f -- "$SAVES/$OPEN_COPY/session.lock"
echo "prism-smoke: copied $SOURCE to $SAVES/$OPEN_COPY, uncommitted"
launch "$OPEN_COPY" "$JOINED_LINE" || exit $?
# An uncommitted world opens without a Commitment line (WorldGate scans only committed ones): the player joining, and
# no locked screen, is the proof.
check_log "$OUT-$OPEN_COPY.log" "$JOINED_LINE" "$BOOT_RE" -- "$LOCKED_LINE" || status=1

if [[ -e $OWNER_MIRROR ]]; then
	echo "FAIL: the game wrote $OWNER_MIRROR: the instance's LOCALAPPDATA didn't take. Left as it is; look before removing it." >&2
	status=1
fi
if (( status == 0 )); then
	echo "prism-smoke: PASS ($LOADER_NAME $LOADER_VERSION, Minecraft $MC, $(basename "$BUILT"))"
	exit 0
fi
echo "prism-smoke: FAIL ($LOADER_NAME $LOADER_VERSION, Minecraft $MC)"
exit 1
