#!/usr/bin/env bash
# Sourced, not run: where a dev run's lock files are and whose lock they are. Shared by commit-smoke.sh (which removes
# them after a run) and tamper.sh (which damages them on purpose), so both recognise Commitment's files the same way.

PACK=datapacks/commitment_guard.zip
RECORD=data/commitment.dat
UUID_RE='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

# lock_of_pack <zip>: prints the lock id of a Commitment guard pack (its marker line and a lock id), or fails.
lock_of_pack() {
	local lock id
	lock=$(unzip -p -- "$1" commitment.lock 2> /dev/null | tr -d '\r') || return 1
	grep -qx 'marker=CMTGUARD 1' <<< "$lock" || return 1
	id=$(sed -n 's/^lock=//p' <<< "$lock")
	[[ $id =~ $UUID_RE ]] || return 1
	echo "$id"
}

# lock_of_record <file>: prints the lock id of a Commitment lock record or mirror, or fails. The file is gzip; its
# content starts with "CMTLOCK", a 4-byte format, then the lock id's 16 bytes (LockCodec).
lock_of_record() {
	local head hex
	head=$({ gzip -dc -- "$1" 2> /dev/null || true; } | head -c 27 | xxd -p | tr -d '\n')
	[[ ${#head} -eq 54 && ${head:0:14} == 434d544c4f434b ]] || return 1
	hex=${head:22:32}
	echo "${hex:0:8}-${hex:8:4}-${hex:12:4}-${hex:16:4}-${hex:20:12}"
}

# inside <root> <path>: is path a strict descendant of root (both as given, no ".." parts)?
inside() {
	[[ -n $1 && -n $2 && $2 == "$1"/* && $2 != *..* ]]
}

# mirror_dir <loader> <node>: the dev run's lock mirror dir. The dev loaders set -Dcommitment.mirrorDir (each
# build.gradle); Quilt runs the released jar, which ignores it, with LOCALAPPDATA pointed into run/ instead.
mirror_dir() {
	if [[ $1 == quilt ]]; then
		echo "run/quilt/$2/appdata/Commitment/locks"
	else
		echo "run/$1/$2/mirror"
	fi
}
