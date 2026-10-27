#!/usr/bin/env bash
# Sourced, not run: the server jars the smokes launch outside Gradle, each download checked against the checksum its
# official source lists before it's used. Every function prints what it did to stderr and returns non-zero on failure,
# leaving no half-downloaded file behind.
#
#   source scripts/server-jars.sh
#   vanilla_jar 26.3 run/vanilla/26.3/server.jar        # Mojang's server jar (SHA-1 from piston-meta)
#   quilt_server 26.3 run/quilt/26.3/server             # Quilt Loader server (installer SHA-1 from maven.quiltmc.org)
#   fabric_api 26.3 run/quilt/26.3/server/mods          # Fabric API, the node's pin (SHA-1 from maven.fabricmc.net)

MANIFEST=https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
QUILT_MAVEN=https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer
FABRIC_API_MAVEN=https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api

# gradle_prop <key>: a value from gradle.properties (the repo root's), or fails.
gradle_prop() {
	local value
	value=$(grep -E "^$(sed 's/\./\\./g' <<< "$1")=" gradle.properties | cut -d= -f2- | tr -d '[:space:]')
	[[ -n $value ]] || { echo "server-jars: gradle.properties has no $1" >&2; return 1; }
	echo "$value"
}

# fetch_checked <url> <sha1> <dest>: downloads to <dest>.tmp and moves it into place only when the SHA-1 matches. A
# <dest> that already matches is kept.
fetch_checked() {
	local url=$1 sha1=$2 dest=$3
	if [[ ! $sha1 =~ ^[0-9a-f]{40}$ ]]; then
		echo "server-jars: no SHA-1 to check $url against (got '$sha1')" >&2
		return 1
	fi
	if [[ -f $dest && $(sha1sum "$dest" | cut -d' ' -f1) == "$sha1" ]]; then
		return 0
	fi
	mkdir -p "$(dirname "$dest")"
	echo "server-jars: downloading $url" >&2
	if ! curl -fsS --max-time 300 -o "$dest.tmp" "$url"; then
		rm -f -- "$dest.tmp"
		echo "server-jars: download failed: $url" >&2
		return 1
	fi
	if [[ $(sha1sum "$dest.tmp" | cut -d' ' -f1) != "$sha1" ]]; then
		rm -f -- "$dest.tmp"
		echo "server-jars: $url doesn't match its SHA-1 $sha1; not using it" >&2
		return 1
	fi
	mv -- "$dest.tmp" "$dest"
	echo "server-jars: $dest (SHA-1 $sha1)" >&2
}

# vanilla_jar <minecraft version> <dest>: Mojang's server jar, URL and SHA-1 from the version's own JSON.
vanilla_jar() {
	local version=$1 dest=$2 version_url server
	version_url=$(curl -fsS --max-time 30 "$MANIFEST" | grep -o "\"id\": *\"$version\", *\"type\": *\"[a-z_]*\", *\"url\": *\"[^\"]*\"" |
		sed 's/.*"url": *"\([^"]*\)"/\1/') || true
	[[ -n $version_url ]] || { echo "server-jars: $version isn't in $MANIFEST" >&2; return 1; }
	server=$(curl -fsS --max-time 30 "$version_url" | tr -d '\n' | grep -o '"server": *{[^}]*}') || {
		echo "server-jars: no server download in $version_url" >&2
		return 1
	}
	fetch_checked "$(sed 's/.*"url": *"\([^"]*\)".*/\1/' <<< "$server")" "$(sed 's/.*"sha1": *"\([0-9a-f]*\)".*/\1/' <<< "$server")" "$dest"
}

# quilt_server <minecraft version> <dir>: Quilt Loader (gradle.properties' quilt_loader_version) installed as a server
# into <dir>, with the vanilla server jar the installer downloads. Skipped when <dir> already holds that install.
quilt_server() {
	local version=$1 dir=$2 loader installer jar stamp
	loader=$(gradle_prop quilt_loader_version) || return 1
	installer=$(gradle_prop quilt_installer_version) || return 1
	stamp="minecraft=$version loader=$loader installer=$installer"
	if [[ -f $dir/quilt-server-launch.jar && -f $dir/.commitment-quilt-install && $(< "$dir/.commitment-quilt-install") == "$stamp" ]]; then
		return 0
	fi
	jar=run/quilt/quilt-installer-$installer.jar
	fetch_checked "$QUILT_MAVEN/$installer/quilt-installer-$installer.jar" \
		"$(curl -fsS --max-time 30 "$QUILT_MAVEN/$installer/quilt-installer-$installer.jar.sha1" | tr -d '[:space:]')" "$jar" || return 1
	mkdir -p "$dir"
	echo "server-jars: installing Quilt Loader $loader for Minecraft $version into $dir" >&2
	if ! java -jar "$jar" install server "$version" "$loader" --download-server "--install-dir=$dir" >&2; then
		echo "server-jars: the Quilt installer failed" >&2
		return 1
	fi
	[[ -f $dir/quilt-server-launch.jar ]] || { echo "server-jars: the Quilt installer wrote no quilt-server-launch.jar" >&2; return 1; }
	echo "$stamp" > "$dir/.commitment-quilt-install"
}

# fabric_api <node> <mods dir>: the node's Fabric API pin (fabric_api_version.<node>) into <mods dir>, replacing any
# other Fabric API jar there.
fabric_api() {
	local node=$1 mods=$2 version name other
	# It removes jars: only ever in a Quilt server's mods dir under run/.
	if [[ ! $mods =~ ^run/quilt/[0-9.]+/server/mods$ ]]; then
		echo "server-jars: refusing to put Fabric API into '$mods': not run/quilt/<node>/server/mods" >&2
		return 1
	fi
	version=$(gradle_prop "fabric_api_version.$node") || return 1
	name=fabric-api-$version.jar
	fetch_checked "$FABRIC_API_MAVEN/${version//+/%2B}/fabric-api-${version//+/%2B}.jar" \
		"$(curl -fsS --max-time 30 "$FABRIC_API_MAVEN/${version//+/%2B}/fabric-api-${version//+/%2B}.jar.sha1" | tr -d '[:space:]')" \
		"$mods/$name" || return 1
	for other in "$mods"/fabric-api-*.jar; do
		[[ -e $other && $other != "$mods/$name" ]] || continue
		rm -- "$other"
		echo "server-jars: removed the other Fabric API $other" >&2
	done
}
