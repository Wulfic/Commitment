#!/usr/bin/env bash
# Boot one loader node's dev server, type console commands into it, stop it with `stop`, and judge the output.
#
#   scripts/dev-server-smoke.sh                                  # boot + stop: fabric, node 26.3
#   scripts/dev-server-smoke.sh scripts/checks/boot.txt          # boot, send each command, stop
#   LOADER=neoforge NODE=26.1.2 scripts/dev-server-smoke.sh ...  # another loader or node
#   scripts/dev-server-smoke.sh --self-test                      # check the verdict against synthetic logs
#
# Exit codes: 0 proven · 1 the mod is bad · 2 nothing proven (port busy, bad arguments, Gradle failed before the
# server launched). Exit 2 is never a pass.
#
# Lines in the commands file:
#   <command>          typed into the server console, after this run's "Done (" line
#   @sh <shell>        run with bash from the repo root between console commands (output to stderr)
#   @expect <regex>    the server output must contain a line matching this (grep -E)
#   @reject <regex>    the server output must contain no line matching this (grep -E)
#   @allow-error <re>  an ERROR line from the mod matching this is expected, not a failure
#   # comment, blank   ignored
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

# launched <output>: did the game's JVM run at all? No means exit 2, whatever Gradle's exit code says.
launched() {
	grep -qE -- "$LAUNCHED_LINE" "$1"
}

# verdict <server output> [commands file]: prints each failure, returns 0 (proven) or 1 (the mod is bad).
verdict() {
	local out=$1 commands=${2:-} fail=0 line re errors
	local -a allowed=() expected=() rejected=()
	if [[ -n "$commands" ]]; then
		while IFS= read -r line || [[ -n "$line" ]]; do
			line=${line%$'\r'}
			case "$line" in
				@allow-error\ *) allowed+=("${line#@allow-error }") ;;
				@expect\ *) expected+=("${line#@expect }") ;;
				@reject\ *) rejected+=("${line#@reject }") ;;
			esac
		done < "$commands"
	fi
	grep -q 'Done (' "$out" || { echo "FAIL: the server never finished starting (no 'Done (')"; fail=1; }
	grep -qE -- "$BOOT_LINE" "$out" || { echo "FAIL: no boot line matching: $BOOT_LINE"; fail=1; }
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
	check 0 "a matched @expect passes" "$dir/expected.log" "$dir/expect.txt"
	check 0 "a CRLF commands file still matches" "$dir/expected.log" "$dir/expect-crlf.txt"
	check 0 "@allow-error lets a named ERROR through" "$dir/error.log" "$dir/allow.txt"
	check 1 "a line matching @reject fails" "$dir/rejected.log" "$dir/reject.txt"
	check 0 "no line matching @reject passes" "$dir/good.log" "$dir/reject.txt"

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
	rm -rf "$dir"
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

LOADER=${LOADER:-fabric}
NODE=${NODE:-26.3}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-420}
COMMANDS_FILE=${1:-}
PORT=$(grep -E '^dev_server_port=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
RUN_DIR=run/$LOADER/$NODE/server
LOG=$RUN_DIR/logs/latest.log
OUT=build/dev-server-smoke-$LOADER-$NODE.out
MARKER=build/dev-server-smoke-$LOADER-$NODE.marker

case "$LOADER" in
	fabric | neoforge | forge) ;;
	*) echo "LOADER must be fabric, neoforge or forge (Quilt runs the Fabric jar), not '$LOADER'" >&2; exit 2 ;;
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

mkdir -p build
touch "$MARKER"

# Writes the server's stdin, and holds it open until "stop": waits for this run's "Done (" line (the log has to be
# newer than the marker, or it's the previous run's), sends the commands, then "stop".
feed_console() {
	local waited=0 cmd
	until [[ -f "$LOG" && "$LOG" -nt "$MARKER" ]] && grep -q 'Done (' "$LOG"; do
		# Gradle finished: the server died before "Done (", and nobody reads stdin any more.
		if grep -qE '^BUILD (SUCCESSFUL|FAILED)' "$OUT" 2>/dev/null; then
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
			[[ -z "$cmd" || "$cmd" == \#* || "$cmd" == @expect\ * || "$cmd" == @reject\ * || "$cmd" == @allow-error\ * ]] && continue
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

echo "dev-server-smoke: :$LOADER:$NODE:runServer on port $PORT, output in $OUT"
set +e
feed_console | timeout $((BOOT_TIMEOUT + 180)) ./gradlew ":$LOADER:$NODE:runServer" --console=plain > "$OUT" 2>&1
status=$?
set -e

if ! launched "$OUT"; then
	echo "dev-server-smoke: NOTHING PROVEN (exit 2): the server never launched (gradle exit $status). Tail of $OUT:" >&2
	tail -40 "$OUT" >&2
	exit 2
fi
if (( status != 0 )); then
	echo "dev-server-smoke: FAIL: runServer exited with $status (124 = timed out; check for a leftover server JVM by" \
		"command line, never by name). Tail of $OUT:" >&2
	tail -40 "$OUT" >&2
	exit 1
fi
if verdict "$OUT" "$COMMANDS_FILE"; then
	echo "dev-server-smoke: PASS ($LOADER $NODE)"
	exit 0
fi
echo "dev-server-smoke: FAIL ($LOADER $NODE)"
exit 1
