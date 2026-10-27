#!/usr/bin/env bash
# Stage D's tamper steps on a dev run's committed world (TODO.md D.1): what a player would do to get round the lock,
# done to run/<loader>/<node>/ only. Nothing is ever destroyed: a "delete" moves the file into a new backup entry, an
# edit copies it there first (checked with cmp), and a restore saves the current file as another entry before putting
# the old one back. Lock files are touched only when they carry Commitment's marker and the world's lock id.
#
#   LOADER=fabric NODE=26.3 bash scripts/tamper.sh delete-pack          # the guard pack
#   LOADER=fabric NODE=26.3 bash scripts/tamper.sh delete-record        # the world's lock record
#   LOADER=fabric NODE=26.3 bash scripts/tamper.sh edit-record          # one byte of the record's revision changed
#   LOADER=fabric NODE=26.3 bash scripts/tamper.sh edit-mirror          # the same, in the OS mirror
#   LOADER=fabric NODE=26.3 bash scripts/tamper.sh move-config          # the server's config/ moved away
#   LOADER=fabric NODE=26.3 bash scripts/tamper.sh edit-property motd x # one server.properties key set
#   LOADER=fabric NODE=26.3 bash scripts/tamper.sh restore <pack|record|mirror|config|properties>
#   bash scripts/tamper.sh --self-test                                  # every guard, on scratch dirs
#
# Backups: run/<loader>/<node>/tamper-backup/<time>-<step>/<path relative to run/<loader>/<node>>. Exit codes: 0 done ·
# 1 refused (nothing changed) · 2 bad arguments.
set -euo pipefail

cd "$(dirname "$0")/.."

# PACK, RECORD, lock_of_pack, lock_of_record, inside, mirror_dir.
source scripts/lock-files.sh

# The byte an edit changes in the decompressed record: the last of the 8-byte revision (after "CMTLOCK", the format
# int and the lock id; LockCodec). The lock id stays readable, so the cleanup still finds the files; the HMAC fails.
EDIT_OFFSET=34

say() { echo "tamper: $*" >&2; }

# backup_entry <root> <step>: a new, empty backup entry dir; prints its path.
backup_entry() {
	local dir
	dir=$1/tamper-backup/$(date +%Y%m%d-%H%M%S-%N)-$2
	mkdir -p "$dir"
	echo "$dir"
}

# save <root> <entry> <target>: copies target (a file or dir) into the entry at its path under root, and checks the
# copy. Fails, leaving the target alone, if it can't.
save() {
	local root=$1 entry=$2 target=$3 copy
	copy=$entry/${target#"$root"/}
	mkdir -p "$(dirname "$copy")"
	cp -r -- "$target" "$copy" || { say "can't back up $target"; return 1; }
	if [[ -d $target ]]; then
		diff -r -- "$target" "$copy" > /dev/null || { say "the backup of $target doesn't match it"; return 1; }
	else
		cmp -s -- "$target" "$copy" || { say "the backup of $target doesn't match it"; return 1; }
	fi
	say "backed up $target to $copy"
}

# lock_id <world>: the world's lock id, from its guard pack, else its record.
lock_id() {
	local id=""
	[[ -e $1/$PACK ]] && id=$(lock_of_pack "$1/$PACK") || true
	[[ -z $id && -e $1/$RECORD ]] && id=$(lock_of_record "$1/$RECORD") || true
	[[ -n $id ]] || { say "no lock id of Commitment's in $1/$PACK or $1/$RECORD"; return 1; }
	echo "$id"
}

# ours <file> <pack|record> <lock id>: is the file Commitment's, of that lock?
ours() {
	local found
	if [[ $2 == pack ]]; then found=$(lock_of_pack "$1") || found=""; else found=$(lock_of_record "$1") || found=""; fi
	if [[ -z $found || $found != "$3" ]]; then
		say "$1 isn't Commitment's for lock $3 (found: ${found:-no marker}); not touching it"
		return 1
	fi
}

# flip <file>: changes one byte of a gzip'd record's content, through a temp file moved into place.
flip() {
	local file=$1 raw byte
	raw=$(mktemp)
	gzip -dc -- "$file" > "$raw" || { rm -f -- "$raw"; say "$file isn't gzip"; return 1; }
	(( $(stat -c %s "$raw") > EDIT_OFFSET )) || { rm -f -- "$raw"; say "$file is too short to edit"; return 1; }
	byte=$(dd if="$raw" bs=1 skip=$EDIT_OFFSET count=1 2> /dev/null | xxd -p)
	printf "\\x$(printf '%02x' $((0x$byte ^ 1)))" | dd of="$raw" bs=1 seek=$EDIT_OFFSET count=1 conv=notrunc 2> /dev/null
	gzip -c < "$raw" > "$file.tamper-tmp" && mv -- "$file.tamper-tmp" "$file"
	rm -f -- "$raw"
	say "edited $file: byte $EDIT_OFFSET of its content 0x$byte -> 0x$(printf '%02x' $((0x$byte ^ 1)))"
}

# tamper <root> <world> <mirror dir> <step> [args]: one step. Every target is resolved and checked before anything moves.
tamper() {
	local root=$1 world=$2 mirror=$3 step=$4 id target entry key value
	shift 4
	if ! inside "$root" "$world" || ! inside "$root" "$mirror"; then
		say "refusing: world '$world' or mirror '$mirror' isn't inside '$root'"
		return 1
	fi
	case $step in
		delete-pack | delete-record)
			id=$(lock_id "$world") || return 1
			if [[ $step == delete-pack ]]; then target=$world/$PACK; else target=$world/$RECORD; fi
			[[ -e $target ]] || { say "no $target to delete"; return 1; }
			ours "$target" "${step#delete-}" "$id" || return 1
			entry=$(backup_entry "$root" "$step")
			mkdir -p "$(dirname "$entry/${target#"$root"/}")"
			mv -- "$target" "$entry/${target#"$root"/}"
			say "deleted $target (moved to $entry; undo: tamper.sh restore ${step#delete-})"
			;;
		edit-record | edit-mirror)
			id=$(lock_id "$world") || return 1
			if [[ $step == edit-record ]]; then target=$world/$RECORD; else target=$mirror/$id.dat; fi
			[[ -f $target ]] || { say "no $target to edit"; return 1; }
			ours "$target" record "$id" || return 1
			entry=$(backup_entry "$root" "$step")
			save "$root" "$entry" "$target" || return 1
			flip "$target"
			;;
		move-config)
			target=$root/server/config
			[[ -d $target ]] || { say "no $target to move"; return 1; }
			entry=$(backup_entry "$root" "$step")
			mkdir -p "$entry/server"
			mv -- "$target" "$entry/server/config"
			say "moved $target to $entry/server/config (undo: tamper.sh restore config)"
			;;
		edit-property)
			key=${1:-} value=${2:-}
			[[ $key =~ ^[a-z][a-z0-9.-]*$ && -n $value ]] || { say "edit-property needs a key and a value"; return 1; }
			target=$root/server/server.properties
			[[ $(grep -c "^$key=" "$target" 2> /dev/null) == 1 ]] || { say "$target has no single $key= line"; return 1; }
			entry=$(backup_entry "$root" "$step")
			save "$root" "$entry" "$target" || return 1
			sed "s|^$key=.*|$key=$value|" "$target" > "$target.tamper-tmp" && mv -- "$target.tamper-tmp" "$target"
			say "set $key in $target (undo: tamper.sh restore properties)"
			;;
		restore)
			restore "$root" "$world" "$mirror" "${1:-}"
			;;
		*)
			say "unknown step '$step'"
			return 1
			;;
	esac
}

# restore <root> <world> <mirror dir> <pack|record|mirror|config|properties>: the newest backup a tamper step took of
# that item back in place. Whatever is there now goes into a new "replaced-" entry first, which restore never picks:
# restoring twice puts back the same original, it doesn't toggle.
restore() {
	local root=$1 world=$2 mirror=$3 item=$4 rel newest="" entry copy
	case $item in
		pack) rel=${world#"$root"/}/$PACK ;;
		record) rel=${world#"$root"/}/$RECORD ;;
		config) rel=server/config ;;
		properties) rel=server/server.properties ;;
		mirror) rel=${mirror#"$root"/}/ ;;
		*) say "restore what? pack, record, mirror, config or properties, not '$item'"; return 1 ;;
	esac
	for entry in "$root"/tamper-backup/*/; do
		[[ $entry != */*-replaced-*/ && -e $entry$rel ]] || continue
		[[ $item != mirror ]] || compgen -G "$entry$rel*.dat" > /dev/null || continue
		newest=${entry%/}
	done
	[[ -n $newest ]] || { say "no backup of $item under $root/tamper-backup"; return 1; }
	if [[ $item == mirror ]]; then
		copy=$(compgen -G "$newest/$rel*.dat" | head -1)
		rel=${copy#"$newest"/}
	else
		copy=$newest/$rel
	fi
	if [[ -e $root/$rel ]]; then
		entry=$(backup_entry "$root" "replaced-$item")
		mkdir -p "$(dirname "$entry/$rel")"
		mv -- "$root/$rel" "$entry/$rel"
		say "moved the current $root/$rel to $entry"
	fi
	mkdir -p "$(dirname "$root/$rel")"
	cp -r -- "$copy" "$root/$rel"
	say "restored $root/$rel from $newest"
}

self_test() {
	local dir failures=0 id=0f0e0d0c-0b0a-4908-8706-050403020100 other=11111111-2222-4333-8444-555555555555 root world mirror
	dir=$(mktemp -d)
	check() { # check <0|1> <name> <command...>: the step's status must be the given one
		local want=$1 name=$2 got=0
		shift 2
		"$@" 2> /dev/null || got=$?
		if [[ $got == "$want" || ($want == 1 && $got != 0) ]]; then
			echo "ok   $name"
		else
			echo "FAIL $name: status $got, expected $want"
			failures=$((failures + 1))
		fi
	}
	expect() { # expect <true|false> <name> <test...>
		local want=$1 name=$2 got=true
		shift 2
		"$@" || got=false
		if [[ $got == "$want" ]]; then echo "ok   $name"; else echo "FAIL $name"; failures=$((failures + 1)); fi
	}
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
		{ printf '%s' "${3:-CMTLOCK}"; printf '\x00\x00\x00\x01'; printf '%s' "${2//-/}" | xxd -r -p; printf 'revision.payload'; } | gzip -c > "$1"
	}
	fresh() { # fresh <name>: run/<name> with a committed world, its mirror, config/ and server.properties
		root=$dir/$1
		world=$root/server/world
		mirror=$root/mirror
		make_pack "$world/$PACK" "$id"
		make_record "$world/$RECORD" "$id"
		make_record "$mirror/$id.dat" "$id"
		mkdir -p "$root/server/config" && echo a=1 > "$root/server/config/a.toml"
		printf 'level-name=world\nmotd=A Minecraft Server\n' > "$root/server/server.properties"
	}

	fresh delete
	cp "$world/$PACK" "$dir/pack.orig"
	check 0 "delete-pack moves our pack away" tamper "$root" "$world" "$mirror" delete-pack
	expect false "the pack is gone from the world" test -e "$world/$PACK"
	check 0 "restore pack puts it back" tamper "$root" "$world" "$mirror" restore pack
	expect true "the restored pack is byte-identical" cmp -s "$dir/pack.orig" "$world/$PACK"
	check 0 "delete-record moves our record away (the pack names the lock)" tamper "$root" "$world" "$mirror" delete-record
	expect true "a backup of the record exists" bash -c "compgen -G '$root/tamper-backup/*-delete-record/server/world/$RECORD' > /dev/null"

	fresh foreign
	make_record "$world/$RECORD" "$other"
	check 1 "a record of another lock than the pack's is refused" tamper "$root" "$world" "$mirror" delete-record
	expect true "the other lock's record is still there" test -e "$world/$RECORD"
	make_record "$world/$RECORD" "$id" NOTOURS
	check 1 "a record with no marker is refused" tamper "$root" "$world" "$mirror" edit-record
	make_pack "$world/$PACK" "$id" 'marker=SOMEONE ELSE'
	check 1 "a pack with no marker is refused" tamper "$root" "$world" "$mirror" delete-pack
	expect true "the unmarked pack is still there" test -e "$world/$PACK"
	expect false "no backup was made for a refusal" bash -c "compgen -G '$root/tamper-backup/*' > /dev/null"

	fresh edit
	cp "$world/$RECORD" "$dir/record.orig"
	cp "$mirror/$id.dat" "$dir/mirror.orig"
	check 0 "edit-record changes our record" tamper "$root" "$world" "$mirror" edit-record
	expect false "the record differs" cmp -s "$dir/record.orig" "$world/$RECORD"
	expect true "the edited record keeps its lock id" test "$(lock_of_record "$world/$RECORD")" = "$id"
	expect true "exactly one byte of its content changed" test "$(cmp -l <(gzip -dc "$dir/record.orig") <(gzip -dc "$world/$RECORD") | wc -l)" = 1
	check 0 "edit-mirror changes our mirror" tamper "$root" "$world" "$mirror" edit-mirror
	expect false "the mirror differs" cmp -s "$dir/mirror.orig" "$mirror/$id.dat"
	check 0 "restore record" tamper "$root" "$world" "$mirror" restore record
	expect true "the restored record is byte-identical" cmp -s "$dir/record.orig" "$world/$RECORD"
	expect true "the edited one was kept as a backup" bash -c "compgen -G '$root/tamper-backup/*-replaced-record/server/world/$RECORD' > /dev/null"

	fresh config
	check 0 "move-config moves config/ away" tamper "$root" "$world" "$mirror" move-config
	expect false "config/ is gone" test -e "$root/server/config"
	check 0 "restore config" tamper "$root" "$world" "$mirror" restore config
	expect true "config/ is back with its file" test "$(cat "$root/server/config/a.toml")" = a=1
	check 0 "edit-property sets one key" tamper "$root" "$world" "$mirror" edit-property motd changed
	expect true "the key has the new value" grep -qx 'motd=changed' "$root/server/server.properties"
	expect true "the other keys stay" grep -qx 'level-name=world' "$root/server/server.properties"
	check 1 "a key that isn't there is refused" tamper "$root" "$world" "$mirror" edit-property no.such.key x
	check 1 "an empty value is refused" tamper "$root" "$world" "$mirror" edit-property motd ""
	check 0 "restore properties" tamper "$root" "$world" "$mirror" restore properties
	expect true "the old value is back" grep -qx 'motd=A Minecraft Server' "$root/server/server.properties"

	fresh outside
	check 1 "a world outside the root is refused" tamper "$dir/elsewhere" "$world" "$mirror" delete-pack
	check 1 "empty paths are refused" tamper "" "" "" delete-pack
	check 1 "a path with .. is refused" tamper "$root" "$root/server/../../outside/server/world" "$mirror" delete-pack
	check 1 "an unknown step is refused" tamper "$root" "$world" "$mirror" delete-everything
	check 1 "restore with nothing backed up is refused" tamper "$root" "$world" "$mirror" restore pack
	expect true "the pack is still there after every refusal" test -e "$world/$PACK"

	rm -r -- "$dir"
	if (( failures > 0 )); then
		echo "tamper self-test: $failures failed"
		return 1
	fi
	echo "tamper self-test: all passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
	self_test
	exit $?
fi

if [[ -z ${LOADER:-} || -z ${NODE:-} || $# -lt 1 ]]; then
	echo "usage: LOADER=<fabric|quilt|neoforge|forge> NODE=<node> bash scripts/tamper.sh <step> [args]" >&2
	exit 2
fi
if [[ ! $LOADER =~ ^(fabric|quilt|neoforge|forge)$ ]] || ! grep -qE "versions .*'$NODE'" settings.gradle; then
	echo "tamper: no $LOADER node '$NODE'" >&2
	exit 2
fi
ROOT=run/$LOADER/$NODE
LEVEL_NAME=$(sed -n 's/^level-name=//p' "$ROOT/server/server.properties" 2> /dev/null | tr -d '\r') || true
tamper "$ROOT" "$ROOT/server/${LEVEL_NAME:-world}" "$(mirror_dir "$LOADER" "$NODE")" "$@" || exit 1
