#!/usr/bin/env bash
# Boot one loader node's dev server, type console commands into it, stop it with `stop`, and judge the output.
#
#   scripts/dev-server-smoke.sh                                  # boot + stop: fabric, node 26.3
#   scripts/dev-server-smoke.sh scripts/checks/boot.txt          # boot, send each command, stop
#   LOADER=neoforge NODE=26.1.2 scripts/dev-server-smoke.sh ...  # another loader or node
#   LOADER=quilt NODE=26.3 scripts/dev-server-smoke.sh ...       # the built Fabric jar on a real Quilt server
#   scripts/dev-server-smoke.sh --self-test                      # check the verdict against synthetic logs
#
# Exit codes: 0 proven · 1 the mod is bad · 2 nothing proven (port busy, bad arguments, Gradle failed before the
# server launched). Exit 2 is never a pass.
#
# LOADER=quilt runs outside Gradle: Quilt Loader's server in run/quilt/<node>/server (scripts/server-jars.sh), with
# the node's built Fabric jar (build it first) and Fabric API in mods/, and LOCALAPPDATA set to run/quilt/<node>/appdata
# so the lock mirror of a released jar stays in run/ too.
#
# Lines in the commands file:
#   <command>          typed into the server console, after this run's "Done (" line
#   @sh <shell>        run with bash from the repo root between console commands (output to stderr)
#   @expect <regex>    the server output must contain a line matching this (grep -E)
#   @reject <regex>    the server output must contain no line matching this (grep -E)
#   @allow-error <re>  an ERROR line from the mod matching this is expected, not a failure
#   @exits-nonzero     the server is expected to stop by itself with a non-zero exit (a refusal); a timeout still fails
#   @gradle <arg>      passed to Gradle, e.g. @gradle -Pcommitment.unlock=start (each loader's run forwards it)
#   @audit-allow <re>  a world path the check's own @sh steps change on purpose (see the world audit below)
#   @before <shell>    run with bash from the repo root before the server launches (a failure: exit 2, nothing proven)
#   @after <shell>     run with bash from the repo root once the server has stopped, whatever the verdict (cleanup)
#   @server vanilla    boot Mojang's own server jar (no loader, no Commitment) on this loader node's world instead:
#                      Commitment's boot line must be absent. @gradle -Pcommitment.<x>=<y> becomes -Dcommitment.<x>=<y>
#                      @sh, @before and @after see LOADER and NODE in their environment
#   @wait <regex>      hold the next command until this run's log has a line matching this (up to WAIT_TIMEOUT s,
#                      default 90), for a reply that comes later than the command; counts as an @expect too.
#                      Pick a pattern that only that step's reply prints: an earlier line of this boot matches as well
#   # comment, blank   ignored
#
# Every run audits the world folder (scripts/world-audit.sh, TODO.md C.2b): a snapshot before the launch and after
# the stop, and a path Commitment created, removed or changed outside its own files and vanilla's fails the run.
#
# One qualified run task per run: a bare `runServer` under Stonecutter starts every node, all on one port.
# Never kills java by name: the owner's game and other repos' servers run on this machine.
set -euo pipefail

cd "$(dirname "$0")/.."

BOOT_LINE='Commitment .* loaded on (Fabric|Quilt|NeoForge|Forge) '
# Log4j puts the level in the thread's bracket ("[Server thread/ERROR]"), so a bare "\[ERROR\]" matches nothing.
ERROR_LINE='/(ERROR|FATAL)\]'
# Any log4j line ("[17:08:08] [main/INFO] ...") means the game's JVM ran, so whatever happened next is a verdict on
# the mod. Without one, Gradle failed before the launch and nothing was proven.
LAUNCHED_LINE='^\[[0-9:]+\] \[[^]]+/(INFO|WARN|ERROR|FATAL|DEBUG)\]'

# Written after a server launched outside Gradle ends, so the console feed stops waiting for a "Done (" that a refusal
# never prints (Gradle's own "BUILD ..." line does that for runServer).
EXITED_LINE='^dev-server-smoke: server exited [0-9]+$'

# launched <output>: did the game's JVM run at all? No means exit 2, whatever Gradle's exit code says.
launched() {
	grep -qE -- "$LAUNCHED_LINE" "$1"
}

# server_ended <output>: has the server process ended (Gradle finished, or a jar launch wrote EXITED_LINE)?
server_ended() {
	grep -qE -- "^BUILD (SUCCESSFUL|FAILED)|$EXITED_LINE" "$1" 2> /dev/null
}

# jvm_props <@gradle arg>...: each -Pcommitment.<x>=<y> as -Dcommitment.<x>=<y>, for a server launched outside Gradle
# (each loader's build.gradle forwards exactly these). Fails on any other arg: it would be silently dropped.
jvm_props() {
	local arg
	for arg in "$@"; do
		if [[ $arg =~ ^-P(commitment\.[a-zA-Z.]+=[^[:space:]]+)$ ]]; then
			echo "-D${BASH_REMATCH[1]}"
		else
			echo "dev-server-smoke: @gradle $arg means nothing to a server launched outside Gradle" >&2
			return 1
		fi
	done
}

# verdict <server output> [commands file]: prints each failure, returns 0 (proven) or 1 (the mod is bad).
verdict() {
	local out=$1 commands=${2:-} fail=0 line re errors refusal=no server=mod
	local -a allowed=() expected=() rejected=()
	if [[ -n "$commands" ]]; then
		while IFS= read -r line || [[ -n "$line" ]]; do
			line=${line%$'\r'}
			case "$line" in
				@allow-error\ *) allowed+=("${line#@allow-error }") ;;
				@expect\ *) expected+=("${line#@expect }") ;;
				@wait\ *) expected+=("${line#@wait }") ;;
				@reject\ *) rejected+=("${line#@reject }") ;;
				@exits-nonzero) refusal=yes ;;
				@server\ vanilla) server=vanilla ;;
			esac
		done < "$commands"
	fi
	# A refusal stops before the world loads, so there's no "Done (" to wait for.
	if [[ $refusal == no ]]; then
		grep -q 'Done (' "$out" || { echo "FAIL: the server never finished starting (no 'Done (')"; fail=1; }
	fi
	if [[ $server == vanilla ]]; then
		if line=$(grep -m1 -E -- "$BOOT_LINE" "$out"); then
			echo "FAIL: Commitment loaded on a server meant to be vanilla: $line"
			fail=1
		fi
	else
		grep -qE -- "$BOOT_LINE" "$out" || { echo "FAIL: no boot line matching: $BOOT_LINE"; fail=1; }
	fi
	errors=$(grep -E -- "$ERROR_LINE" "$out" | grep -i 'commitment' || true)
	for re in ${allowed[@]+"${allowed[@]}"}; do
		errors=$(grep -vE -- "$re" <<< "$errors" || true)
	done
	if [[ -n "$errors" ]]; then
		echo "FAIL: Commitment logged errors:"
		echo "$errors"
		fail=1
	fi
	for re in ${expected[@]+"${expected[@]}"}; do
		if line=$(grep -m1 -E -- "$re" "$out"); then
			echo "matched @expect $re: $line"
		else
			echo "FAIL: no line matching @expect $re"
			fail=1
		fi
	done
	for re in ${rejected[@]+"${rejected[@]}"}; do
		if line=$(grep -m1 -E -- "$re" "$out"); then
			echo "FAIL: a line matches @reject $re: $line"
			fail=1
		else
			echo "no line matches @reject $re"
		fi
	done
	return $fail
}

# exit_verdict <runServer's exit status> <yes|no: @exits-nonzero>: prints a failure, returns 0 or 1.
exit_verdict() {
	local status=$1 refusal=$2
	if [[ $refusal == yes ]]; then
		if (( status == 0 )); then
			echo "FAIL: the server was expected to refuse and exit non-zero, but runServer exited 0"
			return 1
		fi
		if (( status == 124 )); then
			echo "FAIL: the server was expected to refuse by itself, but the run timed out (124)"
			return 1
		fi
		echo "runServer exited $status, as @exits-nonzero expects"
		return 0
	fi
	if (( status != 0 )); then
		echo "FAIL: runServer exited with $status (124 = timed out; check for a leftover server JVM by command line, never by name)"
		return 1
	fi
}

# Feeds the verdict synthetic logs of both polarities. A verdict nothing ever fed an ERROR line can't be trusted.
self_test() {
	local dir failures=0
	dir=$(mktemp -d)
	local done_line='[16:00:05] [Server thread/INFO] (Minecraft) Done (3.2s)! For help, type "help"'
	local boot='[16:00:01] [main/INFO] (commitment) Commitment 0.1.0 loaded on Fabric 0.19.5, Minecraft 26.3'
	printf '%s\n' "$boot" "$done_line" > "$dir/good.log"
	printf '%s\n' "$boot" "$done_line" '[16:00:06] [Server thread/ERROR] (commitment) Commitment: lock record unreadable' > "$dir/error.log"
	printf '%s\n' "$boot" "$done_line" '[16:00:06] [Server thread/ERROR] (Minecraft) some other mod failed' > "$dir/other-error.log"
	printf '%s\n' "$boot" '[16:00:06] [Server thread/FATAL] [commitment/]: crashed' > "$dir/fatal-no-done.log"
	printf '%s\n' "$done_line" > "$dir/no-boot.log"
	printf '%s\n' '@expect Commitment: world committed' > "$dir/expect.txt"
	printf '%s\n' "$boot" "$done_line" '[16:00:07] [Server thread/INFO] (commitment) Commitment: world committed (3 mods)' > "$dir/expected.log"
	printf '%s\n' '@allow-error lock record unreadable' > "$dir/allow.txt"
	printf '%s\r\n' '@expect Commitment: world committed' > "$dir/expect-crlf.txt"
	printf '%s\n' '@reject Commitment guard: Commitment is missing' > "$dir/reject.txt"
	printf '%s\n' "$boot" "$done_line" '[16:00:08] [Server thread/INFO] (Minecraft) [Server] Commitment guard: Commitment is missing' \
		> "$dir/rejected.log"

	printf '%s\n' "$boot" '[16:00:02] [main/ERROR] (commitment) Commitment: setup changed since /commit, refusing to load world' \
		> "$dir/refused.log"
	printf '%s\n' '@exits-nonzero' '@allow-error refusing to load' > "$dir/refusal.txt"
	printf '%s\n' '@allow-error refusing to load' > "$dir/refusal-allowed-only.txt"

	check() { # check <expected status> <name> <log> [commands]
		local want=$1 name=$2 got=0
		verdict "$3" "${4:-}" > "$dir/out.txt" || got=$?
		if [[ $got == "$want" ]]; then
			echo "ok   $name"
		else
			echo "FAIL $name: verdict returned $got, expected $want"
			sed 's/^/     /' "$dir/out.txt"
			failures=$((failures + 1))
		fi
	}
	check 0 "a clean boot passes" "$dir/good.log"
	check 1 "an ERROR from Commitment fails" "$dir/error.log"
	check 0 "an ERROR from someone else doesn't" "$dir/other-error.log"
	check 1 "a FATAL with no Done fails" "$dir/fatal-no-done.log"
	check 1 "no boot line fails" "$dir/no-boot.log"
	check 1 "an unmatched @expect fails" "$dir/good.log" "$dir/expect.txt"
	printf '%s\n' '@wait Commitment: world committed' > "$dir/wait.txt"
	check 1 "an @wait that never matched fails" "$dir/good.log" "$dir/wait.txt"
	check 0 "a matched @wait passes" "$dir/expected.log" "$dir/wait.txt"
	check 0 "a matched @expect passes" "$dir/expected.log" "$dir/expect.txt"
	check 0 "a CRLF commands file still matches" "$dir/expected.log" "$dir/expect-crlf.txt"
	check 0 "@allow-error lets a named ERROR through" "$dir/error.log" "$dir/allow.txt"
	check 1 "a line matching @reject fails" "$dir/rejected.log" "$dir/reject.txt"
	check 0 "no line matching @reject passes" "$dir/good.log" "$dir/reject.txt"
	check 0 "@exits-nonzero: a refusal with no Done passes" "$dir/refused.log" "$dir/refusal.txt"
	check 1 "without @exits-nonzero, no Done fails" "$dir/refused.log" "$dir/refusal-allowed-only.txt"

	# A crash during mod loading launched the game: that's a verdict (1), not "nothing proven" (2).
	printf '%s\n' '> Task :forge:26.3:runServer' \
		'[17:08:22] [main/FATAL] [ne.mi.fm.ModLoader/LOADING]: File build/resources/main constructed 0 mods' \
		'BUILD SUCCESSFUL in 42s' > "$dir/forge-crash.out"
	printf '%s\n' '> Task :fabric:26.3:compileJava FAILED' 'Foo.java:3: error: cannot find symbol' \
		'BUILD FAILED in 9s' > "$dir/compile-failed.out"
	if launched "$dir/forge-crash.out"; then
		echo "ok   a mod-loading crash counts as launched"
	else
		echo "FAIL a mod-loading crash counts as launched"
		failures=$((failures + 1))
	fi
	if launched "$dir/compile-failed.out"; then
		echo "FAIL a compile failure counts as not launched"
		failures=$((failures + 1))
	else
		echo "ok   a compile failure counts as not launched"
	fi
	check 1 "a mod-loading crash fails the verdict" "$dir/forge-crash.out"

	# @server vanilla: the same verdict with the boot line's polarity turned round.
	printf '%s\n' '@server vanilla' > "$dir/vanilla.txt"
	check 0 "@server vanilla: no boot line passes" "$dir/no-boot.log" "$dir/vanilla.txt"
	check 1 "@server vanilla: Commitment's boot line fails" "$dir/good.log" "$dir/vanilla.txt"
	printf '%s\n' '[01:26:21] [main/INFO]: Starting minecraft server version 26.3' "dev-server-smoke: server exited 1" > "$dir/jar-exit.out"
	if server_ended "$dir/jar-exit.out" && ! server_ended "$dir/good.log"; then
		echo "ok   a jar launch's exit line ends the wait, a running server's log doesn't"
	else
		echo "FAIL a jar launch's exit line ends the wait, a running server's log doesn't"
		failures=$((failures + 1))
	fi
	if [[ $(jvm_props -Pcommitment.unlock=start) == "-Dcommitment.unlock=start" ]] && ! jvm_props --offline 2> /dev/null &&
		! jvm_props -Pother.flag=1 2> /dev/null; then
		echo "ok   -Pcommitment.* maps to -D, anything else is refused"
	else
		echo "FAIL -Pcommitment.* maps to -D, anything else is refused"
		failures=$((failures + 1))
	fi

	exit_check() { # exit_check <expected> <name> <status> <yes|no>
		local got=0
		exit_verdict "$3" "$4" > /dev/null || got=$?
		if [[ $got == "$1" ]]; then
			echo "ok   $2"
		else
			echo "FAIL $2: exit_verdict returned $got, expected $1"
			failures=$((failures + 1))
		fi
	}
	exit_check 0 "exit 0 passes" 0 no
	exit_check 1 "a non-zero exit fails" 1 no
	exit_check 0 "@exits-nonzero: a refusal's exit 1 passes" 1 yes
	exit_check 1 "@exits-nonzero: exit 0 fails" 0 yes
	exit_check 1 "@exits-nonzero: a timeout fails" 124 yes
	rm -rf "$dir"
	bash scripts/world-audit.sh --self-test || failures=$((failures + 1))
	bash scripts/commit-smoke.sh --self-test || failures=$((failures + 1))
	bash scripts/client-locked-smoke.sh --self-test || failures=$((failures + 1))
	bash scripts/vanilla-guard-smoke.sh --self-test || failures=$((failures + 1))
	if (( failures > 0 )); then
		echo "self-test: $failures failed"
		return 1
	fi
	echo "self-test: all passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
	self_test
	exit $?
fi

export LOADER=${LOADER:-fabric}
export NODE=${NODE:-26.3}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-420}
WAIT_TIMEOUT=${WAIT_TIMEOUT:-90}
COMMANDS_FILE=${1:-}
PORT=$(grep -E '^dev_server_port=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
RUN_DIR=run/$LOADER/$NODE/server
LOG=$RUN_DIR/logs/latest.log
OUT=build/dev-server-smoke-$LOADER-$NODE.out
MARKER=build/dev-server-smoke-$LOADER-$NODE.marker

case "$LOADER" in
	fabric | neoforge | forge) ;;
	quilt)
		if [[ $NODE == 1.21.11 ]]; then
			echo "no Quilt on 1.21.11: that node is Fabric only" >&2
			exit 2
		fi
		;;
	*) echo "LOADER must be fabric, quilt, neoforge or forge, not '$LOADER'" >&2; exit 2 ;;
esac
# Every branch lists the same nodes in settings.gradle.
if ! grep -qE "versions .*'$NODE'" settings.gradle; then
	echo "no node '$NODE' in settings.gradle" >&2
	exit 2
fi
if [[ -z "$PORT" ]]; then
	echo "gradle.properties has no dev_server_port" >&2
	exit 2
fi
if [[ -n "$COMMANDS_FILE" && ! -f "$COMMANDS_FILE" ]]; then
	echo "commands file not found: $COMMANDS_FILE" >&2
	exit 2
fi
if netstat -an 2>/dev/null | grep -qE "[:.]${PORT}[[:space:]].*LISTEN"; then
	echo "port $PORT is already in use; refusing to start a second dev server" >&2
	exit 2
fi

REFUSAL=no
GRADLE_ARGS=()
AUDIT_ALLOW=()
BEFORE=()
AFTER=()
if [[ -n "$COMMANDS_FILE" ]]; then
	while IFS= read -r line || [[ -n "$line" ]]; do
		line=${line%$'\r'}
		case "$line" in
			@exits-nonzero) REFUSAL=yes ;;
			@gradle\ *) GRADLE_ARGS+=("${line#@gradle }") ;;
			@audit-allow\ *) AUDIT_ALLOW+=("${line#@audit-allow }") ;;
			@before\ *) BEFORE+=("${line#@before }") ;;
			@after\ *) AFTER+=("${line#@after }") ;;
			@server\ vanilla) SERVER=vanilla ;;
			@server\ *) echo "unknown $line in $COMMANDS_FILE (only @server vanilla)" >&2; exit 2 ;;
		esac
	done < "$COMMANDS_FILE"
fi
SERVER=${SERVER:-mod}

# The world the server opens: server.properties' level-name, vanilla's "world" until the first boot writes the file.
LEVEL_NAME=$(sed -n 's/^level-name=//p' "$RUN_DIR/server.properties" 2>/dev/null | tr -d '\r') || true
WORLD=$RUN_DIR/${LEVEL_NAME:-world}

# The servers launched outside Gradle: Mojang's jar on this node's world, or the Quilt server with the built jar.
# Anything missing that can't be fetched and checked is exit 2: nothing was proven.
source scripts/server-jars.sh
JAR_DIR=""
JAR_ARGS=()
if [[ $SERVER == vanilla || $LOADER == quilt ]]; then
	JVM_PROPS=()
	if (( ${#GRADLE_ARGS[@]} > 0 )); then
		props=$(jvm_props "${GRADLE_ARGS[@]}") || exit 2
		mapfile -t JVM_PROPS <<< "$props"
	fi
fi
if [[ $SERVER == vanilla ]]; then
	if [[ ! -f $WORLD/level.dat ]]; then
		echo "dev-server-smoke: no world at $WORLD for the vanilla server to open" >&2
		exit 2
	fi
	JAR_DIR=run/vanilla/$NODE
	vanilla_jar "$NODE" "$JAR_DIR/server.jar" || { echo "dev-server-smoke: NOTHING PROVEN (exit 2): no checked vanilla jar" >&2; exit 2; }
	# The loader's server dir as the universe: the very world the mod boots open, not a copy.
	JAR_ARGS=(${JVM_PROPS[@]+"${JVM_PROPS[@]}"} -jar server.jar --port "$PORT" --universe "$PWD/$RUN_DIR" --world "${LEVEL_NAME:-world}" nogui)
elif [[ $LOADER == quilt ]]; then
	JAR_DIR=$RUN_DIR
	BUILT=$(compgen -G "fabric/versions/$NODE/build/libs/commitment-*+fabric-mc*.jar" | grep -v -- '-sources\.jar$' || true)
	if [[ $(wc -w <<< "$BUILT") -ne 1 ]]; then
		echo "dev-server-smoke: need exactly one built Fabric jar in fabric/versions/$NODE/build/libs (found: ${BUILT:-none}); run ./gradlew :fabric:$NODE:build" >&2
		exit 2
	fi
	quilt_server "$NODE" "$RUN_DIR" || { echo "dev-server-smoke: NOTHING PROVEN (exit 2): no Quilt server" >&2; exit 2; }
	fabric_api "$NODE" "$RUN_DIR/mods" || { echo "dev-server-smoke: NOTHING PROVEN (exit 2): no checked Fabric API" >&2; exit 2; }
	for old in "$RUN_DIR"/mods/commitment-*.jar; do
		if [[ -e $old && $(basename "$old") != "$(basename "$BUILT")" ]]; then
			rm -- "$old"
			echo "dev-server-smoke: removed the older $old" >&2
		fi
	done
	cp -- "$BUILT" "$RUN_DIR/mods/"
	[[ -f $RUN_DIR/eula.txt ]] || echo "eula=true" > "$RUN_DIR/eula.txt"
	mkdir -p "run/quilt/$NODE/appdata"
	JAR_ARGS=(-Xmx3G ${JVM_PROPS[@]+"${JVM_PROPS[@]}"} -jar quilt-server-launch.jar --port "$PORT" nogui)
fi
if [[ -n $JAR_DIR ]]; then
	LOG=$JAR_DIR/logs/latest.log
	mkdir -p "$JAR_DIR"
	[[ -f $JAR_DIR/eula.txt ]] || echo "eula=true" > "$JAR_DIR/eula.txt"
fi
AUDIT_BEFORE=build/world-audit-$LOADER-$NODE.before
AUDIT_AFTER=build/world-audit-$LOADER-$NODE.after

# run_steps <before|after> <shell>...: each with bash from the repo root; returns 1 after the first that fails.
run_steps() {
	local kind=$1 step
	shift
	for step in "$@"; do
		echo "dev-server-smoke: @$kind \$ $step" >&2
		if ! bash -c "$step" >&2; then
			echo "dev-server-smoke: @$kind step failed: $step" >&2
			return 1
		fi
	done
}

mkdir -p build
if ! run_steps before ${BEFORE[@]+"${BEFORE[@]}"}; then
	run_steps after ${AFTER[@]+"${AFTER[@]}"} || true
	echo "dev-server-smoke: NOTHING PROVEN (exit 2): an @before step failed" >&2
	exit 2
fi
bash scripts/world-audit.sh snapshot "$WORLD" "$AUDIT_BEFORE"
touch "$MARKER"

# wait_for_line <regex>: an @wait step. Polls this run's log; on a timeout it says so and lets the commands go on,
# and the verdict fails the unmatched @wait.
wait_for_line() {
	local re=$1 waited=0
	until grep -qE -- "$re" "$LOG" 2>/dev/null; do
		if (( waited >= WAIT_TIMEOUT )); then
			echo "dev-server-smoke: @wait gave up after ${WAIT_TIMEOUT}s: $re" >&2
			return
		fi
		sleep 1
		waited=$((waited + 1))
	done
	echo "dev-server-smoke: @wait matched after ${waited}s: $re" >&2
}

# Writes the server's stdin, and holds it open until "stop": waits for this run's "Done (" line (the log has to be
# newer than the marker, or it's the previous run's), sends the commands, then "stop".
feed_console() {
	local waited=0 cmd
	until [[ -f "$LOG" && "$LOG" -nt "$MARKER" ]] && grep -q 'Done (' "$LOG"; do
		# The server died before "Done (", and nobody reads stdin any more.
		if server_ended "$OUT"; then
			echo "dev-server-smoke: the server exited before 'Done ('" >&2
			return
		fi
		if (( waited >= BOOT_TIMEOUT )); then
			echo "dev-server-smoke: no 'Done (' after ${BOOT_TIMEOUT}s; sending stop" >&2
			echo stop
			return
		fi
		sleep 2
		waited=$((waited + 2))
	done
	if [[ -n "$COMMANDS_FILE" ]]; then
		while IFS= read -r cmd || [[ -n "$cmd" ]]; do
			cmd=${cmd%$'\r'}
			[[ -z "$cmd" || "$cmd" == \#* || "$cmd" == @expect\ * || "$cmd" == @reject\ * || "$cmd" == @allow-error\ * ||
				"$cmd" == @exits-nonzero || "$cmd" == @gradle\ * || "$cmd" == @audit-allow\ * || "$cmd" == @before\ * ||
				"$cmd" == @after\ * || "$cmd" == @server\ * ]] && continue
			if [[ "$cmd" == @wait\ * ]]; then
				wait_for_line "${cmd#@wait }"
				continue
			fi
			if [[ "$cmd" == @sh\ * ]]; then
				echo "dev-server-smoke: \$ ${cmd#@sh }" >&2
				bash -c "${cmd#@sh }" >&2 || echo "dev-server-smoke: @sh step failed: ${cmd#@sh }" >&2
				continue
			fi
			echo "$cmd"
			sleep 1
		done < "$COMMANDS_FILE"
		sleep 2
	fi
	echo stop
}

set +e
if [[ -n $JAR_DIR ]]; then
	echo "dev-server-smoke: java ${JAR_ARGS[*]} in $JAR_DIR ($SERVER server, $LOADER $NODE world) on port $PORT, output in $OUT"
	# A released jar's lock mirror (MirrorDir reads LOCALAPPDATA) lands in run/quilt/<node>/appdata, never in yours.
	# Mojang's server never reads it.
	APPDATA_DIR=${LOCALAPPDATA:-}
	if [[ $LOADER == quilt && $SERVER == mod ]]; then
		APPDATA_DIR=$(cygpath -w "$PWD/run/quilt/$NODE/appdata" 2> /dev/null || echo "$PWD/run/quilt/$NODE/appdata")
	fi
	feed_console | {
		(cd "$JAR_DIR" && LOCALAPPDATA=$APPDATA_DIR timeout $((BOOT_TIMEOUT + 180)) java "${JAR_ARGS[@]}")
		code=$?
		echo "dev-server-smoke: server exited $code"
		exit $code
	} > "$OUT" 2>&1
	status=$?
else
	echo "dev-server-smoke: :$LOADER:$NODE:runServer on port $PORT, output in $OUT"
	feed_console | timeout $((BOOT_TIMEOUT + 180)) ./gradlew ":$LOADER:$NODE:runServer" --console=plain ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"} \
		> "$OUT" 2>&1
	status=$?
fi
set -e
# Cleanup first, so no verdict below can skip it.
after_status=0
run_steps after ${AFTER[@]+"${AFTER[@]}"} || after_status=1

if ! launched "$OUT"; then
	echo "dev-server-smoke: NOTHING PROVEN (exit 2): the server never launched (gradle exit $status). Tail of $OUT:" >&2
	tail -40 "$OUT" >&2
	exit 2
fi
if ! exit_verdict "$status" "$REFUSAL" >&2; then
	echo "dev-server-smoke: FAIL. Tail of $OUT:" >&2
	tail -40 "$OUT" >&2
	exit 1
fi
verdict_status=0
verdict "$OUT" "$COMMANDS_FILE" || verdict_status=1
if (( after_status != 0 )); then
	echo "FAIL: an @after step failed; what it cleans up may still be there"
	verdict_status=1
fi
bash scripts/world-audit.sh snapshot "$WORLD" "$AUDIT_AFTER"
bash scripts/world-audit.sh compare "$AUDIT_BEFORE" "$AUDIT_AFTER" ${AUDIT_ALLOW[@]+"${AUDIT_ALLOW[@]}"} || verdict_status=1
if (( verdict_status == 0 )); then
	echo "dev-server-smoke: PASS ($LOADER $NODE)"
	exit 0
fi
echo "dev-server-smoke: FAIL ($LOADER $NODE)"
exit 1
