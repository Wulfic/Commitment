#!/usr/bin/env bash
# Release cleanup (TODO.md 1.0.1; owner, decisions.md 2026-10-29): once a release is published, delete every older
# vX.Y.Z release and tag on GitHub, so the Releases page holds the newest only. A dry run unless --apply.
#
#   scripts/release-prune.sh --keep vX.Y.Z --assets N [--apply]
#   scripts/release-prune.sh --self-test
#
# Refuses, deleting nothing (exit 1): a keep release that is missing, a draft or a prerelease, or hasn't exactly N
# non-empty assets; an older tag with no local copy, or whose commit isn't on $MAIN_REF (default origin/main):
# re-tagging that commit is the way back, so a tag off main would lose its source. Every deletion is planned and
# checked before the first one runs. Leaves alone, and says so: tags that aren't vX.Y.Z, versions not older than
# keep. Logs each deletion with its commit and the command that undoes it, then checks each one is gone.
#
# Exit codes: 0 done, or nothing to do · 1 refused, or a deletion failed · 2 bad arguments.
# gh needs GH_TOKEN and GH_REPO (or a checkout whose origin is the repo); git needs the tags and $MAIN_REF fetched.
set -euo pipefail

SEMVER='^v[0-9]+\.[0-9]+\.[0-9]+$'
USAGE="usage: $0 --keep vX.Y.Z --assets N [--apply] | --self-test"

# older a b: version a sorts below version b.
older() {
	[[ $1 != "$2" && $(printf '%s\n%s\n' "${1#v}" "${2#v}" | sort -V | head -n 1) == "${1#v}" ]]
}

refuse() {
	echo "release-prune: REFUSED, nothing deleted: $*" >&2
	return 1
}

# The repo's releases and its v* tags, one name per line.
releases() { gh release list --limit 1000 --json tagName --jq '.[].tagName'; }
tags() { gh api 'repos/{owner}/{repo}/git/matching-refs/tags/v' --paginate --jq '.[].ref | ltrimstr("refs/tags/")'; }

prune() {
	local keep="" assets="" apply=0 main_ref=${MAIN_REF:-origin/main}
	while (($#)); do
		case $1 in
			--keep) keep=${2-}; shift $(($# < 2 ? 1 : 2)) ;;
			--assets) assets=${2-}; shift $(($# < 2 ? 1 : 2)) ;;
			--apply) apply=1; shift ;;
			*) echo "$USAGE" >&2; return 2 ;;
		esac
	done
	if [[ ! $keep =~ $SEMVER ]]; then
		echo "release-prune: --keep must be a tag like v1.0.1, not '$keep'; $USAGE" >&2
		return 2
	fi
	if [[ ! $assets =~ ^[1-9][0-9]*$ ]]; then
		echo "release-prune: --assets must be the number of jars the release has, not '$assets'; $USAGE" >&2
		return 2
	fi

	local state draft pre count
	state=$(gh release view "$keep" --json isDraft,isPrerelease,assets \
		--jq '[.isDraft, .isPrerelease, ([.assets[] | select(.size > 0)] | length)] | @tsv') ||
		{ refuse "there is no release $keep to keep"; return 1; }
	read -r draft pre count <<< "$state"
	[[ $draft == false && $pre == false ]] || { refuse "$keep is a draft ($draft) or a prerelease ($pre)"; return 1; }
	[[ $count == "$assets" ]] || { refuse "$keep has $count non-empty assets, not $assets"; return 1; }

	local listed_releases listed_tags
	listed_releases=$(releases) || { refuse "can't list the releases"; return 1; }
	listed_tags=$(tags) || { refuse "can't list the tags"; return 1; }

	local t commit plan=() kind
	declare -A released=()
	for t in $listed_releases; do released[$t]=1; done
	for t in $(printf '%s\n' $listed_releases $listed_tags | sort -u); do
		if [[ ! $t =~ $SEMVER ]]; then
			echo "release-prune: left alone: $t (not a vX.Y.Z tag)"
			continue
		fi
		[[ $t == "$keep" ]] && continue
		if ! older "$t" "$keep"; then
			echo "release-prune: left alone: $t (not older than $keep)"
			continue
		fi
		commit=$(git rev-parse --verify --quiet "refs/tags/$t^{commit}") ||
			{ refuse "$t has no local tag, so its commit can't be checked (fetch the tags first)"; return 1; }
		git merge-base --is-ancestor "$commit" "$main_ref" ||
			{ refuse "$t is on $commit, which isn't on $main_ref: re-tagging it would have no source"; return 1; }
		plan+=("$t $commit")
	done

	if ((${#plan[@]} == 0)); then
		echo "release-prune: nothing older than $keep; nothing to delete"
		return 0
	fi
	echo "release-prune: keeping $keep ($count assets); ${#plan[@]} older to delete:"
	for t in "${plan[@]}"; do
		kind=tag
		[[ -n ${released[${t% *}]-} ]] && kind="release and tag"
		echo "  $kind ${t% *} (commit ${t#* })"
	done
	if ((!apply)); then
		echo "release-prune: dry run, nothing deleted; --apply deletes these ${#plan[@]}"
		return 0
	fi

	local deleted=0
	for t in "${plan[@]}"; do
		commit=${t#* }
		t=${t% *}
		echo "release-prune: deleting $t (commit $commit); undo: git push origin refs/tags/$t from a clone that has it," \
			"or git tag -a $t $commit -m '<name> ${t#v}' && git push origin $t, then rebuild its jars at that commit"
		if [[ -n ${released[$t]-} ]]; then
			gh release delete "$t" --cleanup-tag --yes || { echo "release-prune: FAILED deleting release $t; $deleted of ${#plan[@]} deleted" >&2; return 1; }
		else
			gh api -X DELETE "repos/{owner}/{repo}/git/refs/tags/$t" > /dev/null || { echo "release-prune: FAILED deleting tag $t; $deleted of ${#plan[@]} deleted" >&2; return 1; }
		fi
		deleted=$((deleted + 1))
	done

	listed_releases=$(releases) || { echo "release-prune: deleted $deleted, but can't list the releases to check" >&2; return 1; }
	listed_tags=$(tags) || { echo "release-prune: deleted $deleted, but can't list the tags to check" >&2; return 1; }
	for t in "${plan[@]}"; do
		t=${t% *}
		if grep -qxF -- "$t" <<< "$listed_releases"$'\n'"$listed_tags"; then
			echo "release-prune: FAILED: $t is still there after its deletion" >&2
			return 1
		fi
	done
	grep -qxF -- "$keep" <<< "$listed_releases" || { echo "release-prune: FAILED: $keep is gone" >&2; return 1; }
	echo "release-prune: deleted $deleted of ${#plan[@]}, each checked gone; $keep is still there"
}

self_test() {
	local tmp failures=0 out code
	tmp=$(mktemp -d)
	[[ -n $tmp && -d $tmp && $tmp == */tmp.* ]] || { echo "release-prune self-test: mktemp gave '$tmp'; not running" >&2; return 2; }
	# EXIT, not RETURN: a RETURN trap also fires as each helper function below returns. The path is expanded now:
	# $tmp is local, gone by the time the trap runs.
	trap "rm -rf -- '$tmp'" EXIT
	mkdir -p "$tmp/bin" "$tmp/fix" "$tmp/repo"
	# gh, from fixture files: view-<tag> (the --jq output), releases, tags. Deletions are logged to calls and taken out
	# of the fixtures, as GitHub would; STUB_FAIL names a tag whose deletion fails.
	cat > "$tmp/bin/gh" << 'STUB'
#!/usr/bin/env bash
set -euo pipefail
drop() { grep -vxF -- "$2" "$STUB_DIR/$1" > "$STUB_DIR/$1.new" || true; mv "$STUB_DIR/$1.new" "$STUB_DIR/$1"; }
case "$1 $2" in
	"release view") [[ -f $STUB_DIR/view-$3 ]] || { echo "release not found" >&2; exit 1; }; cat "$STUB_DIR/view-$3" ;;
	"release list") cat "$STUB_DIR/releases" ;;
	"release delete")
		[[ ${STUB_FAIL-} != "$3" ]] || { echo "HTTP 500" >&2; exit 1; }
		echo "release-delete $3" >> "$STUB_DIR/calls"; drop releases "$3"; drop tags "$3"; rm -f "$STUB_DIR/view-$3" ;;
	"api repos/{owner}/{repo}/git/matching-refs/tags/v") cat "$STUB_DIR/tags" ;;
	"api -X")
		t=${4##*/}
		[[ ${STUB_FAIL-} != "$t" ]] || { echo "HTTP 500" >&2; exit 1; }
		echo "tag-delete $t" >> "$STUB_DIR/calls"; drop tags "$t" ;;
	*) echo "gh stub: unexpected call: $*" >&2; exit 3 ;;
esac
STUB
	chmod +x "$tmp/bin/gh"
	# main: c1 (v0.9.0, a tag without a release) - c2 (v1.0.0, annotated) - c3 (v1.0.1, v2.0.0, nightly);
	# side: c4 (v0.8.0), off main.
	(
		cd "$tmp/repo"
		git init -q -b main
		git config user.email self-test@invalid
		git config user.name self-test
		for c in c1 c2 c3; do git commit -q --allow-empty -m "$c"; done
		git tag v0.9.0 HEAD~2
		git tag -a v1.0.0 -m "x 1.0.0" HEAD~1
		git tag v1.0.1
		git tag v2.0.0
		git tag nightly
		git checkout -q -b side
		git commit -q --allow-empty -m c4
		git tag v0.8.0
		git checkout -q main
	)

	# fixture <keep state> [extra tag]: the repo above as GitHub lists it; releases v1.0.0, v1.0.1, v2.0.0.
	fixture() {
		rm -f "$tmp/fix"/*
		printf '%s\n' v2.0.0 v1.0.1 v1.0.0 > "$tmp/fix/releases"
		printf '%s\n' nightly v0.9.0 v1.0.0 v1.0.1 v2.0.0 ${2-} > "$tmp/fix/tags"
		[[ -z $1 ]] || printf '%s\n' "$1" > "$tmp/fix/view-v1.0.1"
		: > "$tmp/fix/calls"
	}
	# case <name> <want exit> <want calls, space-separated> <args...>
	case_() {
		local name=$1 want=$2 calls=$3
		shift 3
		set +e
		out=$(cd "$tmp/repo" && PATH="$tmp/bin:$PATH" STUB_DIR="$tmp/fix" MAIN_REF=main prune "$@" 2>&1)
		code=$?
		set -e
		local got
		got=$(tr '\n' ' ' < "$tmp/fix/calls" | sed 's/ $//')
		if [[ $code != "$want" || $got != "$calls" ]]; then
			echo "release-prune self-test: FAIL $name: exit $code (want $want), calls '$got' (want '$calls')" >&2
			sed 's/^/    /' <<< "$out" >&2
			failures=$((failures + 1))
		else
			echo "release-prune self-test: ok   $name"
		fi
	}
	[[ $(PATH="$tmp/bin:$PATH" command -v gh) == "$tmp/bin/gh" ]] || { echo "release-prune self-test: the gh stub isn't first on PATH; not running" >&2; return 2; }

	local ok=$'false\tfalse\t10'
	fixture "$ok"; case_ "no arguments" 2 ""
	fixture "$ok"; case_ "an empty --keep" 2 "" --keep "" --assets 10 --apply
	fixture "$ok"; case_ "a --keep that isn't vX.Y.Z" 2 "" --keep 1.0.1 --assets 10 --apply
	fixture "$ok"; case_ "no --assets" 2 "" --keep v1.0.1 --apply
	fixture "$ok"; case_ "--assets 0" 2 "" --keep v1.0.1 --assets 0 --apply
	fixture ""; case_ "no release to keep" 1 "" --keep v1.0.1 --assets 10 --apply
	fixture $'true\tfalse\t10'; case_ "the keep release is a draft" 1 "" --keep v1.0.1 --assets 10 --apply
	fixture $'false\ttrue\t10'; case_ "the keep release is a prerelease" 1 "" --keep v1.0.1 --assets 10 --apply
	fixture $'false\tfalse\t9'; case_ "the keep release lacks a jar" 1 "" --keep v1.0.1 --assets 10 --apply
	fixture "$ok" v0.8.0; case_ "an old tag off main stops every deletion" 1 "" --keep v1.0.1 --assets 10 --apply
	fixture "$ok" v0.7.0; case_ "an old tag with no local copy stops every deletion" 1 "" --keep v1.0.1 --assets 10 --apply
	fixture "$ok"; case_ "a dry run deletes nothing" 0 "" --keep v1.0.1 --assets 10
	grep -q "release and tag v1.0.0" <<< "$out" && grep -q "  tag v0.9.0" <<< "$out" && grep -q "left alone: v2.0.0" <<< "$out" &&
		grep -q "left alone: nightly" <<< "$out" || { echo "release-prune self-test: FAIL the dry run's plan: $out" >&2; failures=$((failures + 1)); }
	fixture "$ok"; case_ "--apply deletes the older two only" 0 "tag-delete v0.9.0 release-delete v1.0.0" --keep v1.0.1 --assets 10 --apply
	grep -q "deleted 2 of 2, each checked gone" <<< "$out" || { echo "release-prune self-test: FAIL --apply's summary: $out" >&2; failures=$((failures + 1)); }
	fixture "$ok"; STUB_FAIL=v1.0.0 case_ "a failed deletion stops with exit 1" 1 "tag-delete v0.9.0" --keep v1.0.1 --assets 10 --apply
	fixture "$ok"; cp "$tmp/fix/view-v1.0.1" "$tmp/fix/view-v0.9.0"
	case_ "nothing older than the oldest" 0 "" --keep v0.9.0 --assets 10 --apply
	if ((failures)); then
		echo "release-prune self-test: $failures failed"
		return 1
	fi
	echo "release-prune self-test: all passed"
}

case ${1-} in
	--self-test) self_test ;;
	*) prune "$@" ;;
esac
