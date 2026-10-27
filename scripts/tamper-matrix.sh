#!/usr/bin/env bash
# D.1: every row of the threat-model table (TODO.md), as one chain of boots per loader x node pair over one lock,
# through commit-smoke.sh (which removes the lock's files after). Pairs run one after another: they share the dev port.
# Each pair's output goes to build/tamper-matrix/<loader>-<node>.log, and the summary to build/tamper-matrix.md.
#
#   bash scripts/tamper-matrix.sh                         # all 13 pairs
#   bash scripts/tamper-matrix.sh quilt:26.3 forge:26.2   # just these
#
# Quilt pairs boot the built Fabric jar: build first. Needs internet (/unlock confirm). Exit codes as the smokes: 0 every
# pair proven · 1 a pair failed · 2 a pair proved nothing (and none failed).
set -euo pipefail

cd "$(dirname "$0")/.."

CHAIN=(
	scripts/checks/tamper/01-commit.txt
	scripts/checks/tamper/02-mod-gone.txt
	scripts/checks/tamper/03-reinstalled.txt
	scripts/checks/tamper/04-pack-deleted.txt
	scripts/checks/tamper/05-record-deleted.txt
	scripts/checks/tamper/06-record-and-pack-deleted.txt
	scripts/checks/tamper/07-record-edited.txt
	scripts/checks/tamper/08-config-moved.txt
	scripts/checks/tamper/09-properties-edited.txt
	scripts/checks/guard-disable.txt
	scripts/checks/guard-reenable.txt
	scripts/checks/rules-revert.txt
	scripts/checks/tamper/13-both-edited.txt
	scripts/checks/tamper/14-bypass.txt
)
ALL_PAIRS=(fabric:1.21.11 fabric:26.1.2 fabric:26.2 fabric:26.3 neoforge:26.1.2 neoforge:26.2 neoforge:26.3
	forge:26.1.2 forge:26.2 forge:26.3 quilt:26.1.2 quilt:26.2 quilt:26.3)

for f in "${CHAIN[@]}"; do
	[[ -f $f ]] || { echo "tamper-matrix: no $f" >&2; exit 2; }
done
if (( $# > 0 )); then PAIRS=("$@"); else PAIRS=("${ALL_PAIRS[@]}"); fi

mkdir -p build/tamper-matrix
SUMMARY=build/tamper-matrix.md
{
	echo "# Tamper matrix, $(date -u +%Y-%m-%dT%H:%MZ), $(git rev-parse --short HEAD)$(git diff --quiet || echo '+dirty')"
	echo
	echo "| Pair | Exit | Boots passed | First failing boot |"
	echo "|---|---|---|---|"
} > "$SUMMARY"

worst=0
for pair in "${PAIRS[@]}"; do
	loader=${pair%%:*} node=${pair#*:}
	log=build/tamper-matrix/$loader-$node.log
	echo "tamper-matrix: $loader $node (${#CHAIN[@]} boots), log in $log"
	set +e
	LOADER=$loader NODE=$node bash scripts/commit-smoke.sh "${CHAIN[@]}" > "$log" 2>&1
	status=$?
	set -e
	passed=$(grep -c '^dev-server-smoke: PASS' "$log" || true)
	failing=""
	if (( status != 0 )); then
		# The boot after the last one that passed.
		failing=${CHAIN[$passed]:-cleanup}
		failing=${failing##*/}
	fi
	echo "| $loader $node | $status | $passed / ${#CHAIN[@]} | ${failing:--} |" >> "$SUMMARY"
	echo "tamper-matrix: $loader $node exit $status ($passed of ${#CHAIN[@]} boots passed${failing:+, first failing: $failing})"
	if (( status == 1 )); then
		worst=1
	elif (( status != 0 && worst == 0 )); then
		worst=2
	fi
done
echo "tamper-matrix: summary in $SUMMARY"
exit $worst
