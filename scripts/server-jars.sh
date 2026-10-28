#!/usr/bin/env bash
# Sourced, not run: the server jars the smokes launch outside Gradle, each download checked against the checksum its
# official source lists before it's used. Every function prints what it did to stderr and returns non-zero on failure,
# leaving no half-downloaded file behind.
#
#   source scripts/server-jars.sh
#   vanilla_jar 26.3 run/vanilla/26.3/server.jar        # Mojang's server jar (SHA-1 from piston-meta)
#   quilt_server 26.3 run/quilt/26.3/server             # Quilt Loader server (installer SHA-1 from maven.quiltmc.org)
#   fabric_server 26.1 run/real/fabric/26.1.2/26.1/server
#                                                       # Fabric Loader server (installer SHA-1 from maven.fabricmc.net)
#   fabric_api 26.3 run/quilt/26.3/server/mods          # Fabric API, the node's pin (SHA-1 from maven.fabricmc.net)
#   fabric_api 26.1.2 run/real/fabric/26.1.2/26.1/server/mods 26.1
#                                                       # Fabric API for another Minecraft of the node's band
#   loader_server neoforge 26.3 26.3.0.51-beta run/real/neoforge/26.3/26.3.0.51-beta/server
#                                                       # a NeoForge or Forge server (installer SHA-1 from its maven)

MANIFEST=https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
QUILT_MAVEN=https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer
FABRIC_API_MAVEN=https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api
FABRIC_INSTALLER_MAVEN=https://maven.fabricmc.net/net/fabricmc/fabric-installer
NEOFORGE_MAVEN=https://maven.neoforged.net/releases/net/neoforged/neoforge
FORGE_MAVEN=https://maven.minecraftforge.net/net/minecraftforge/forge

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

# fabric_server <minecraft version> <dir>: Fabric Loader (gradle.properties' loader_version) installed as a server into
# <dir>, with the vanilla server jar the installer downloads. Skipped when <dir> already holds that install.
fabric_server() {
	local version=$1 dir=$2 loader installer jar stamp
	loader=$(gradle_prop loader_version) || return 1
	installer=$(gradle_prop fabric_installer_version) || return 1
	stamp="minecraft=$version loader=$loader installer=$installer"
	if [[ -f $dir/fabric-server-launch.jar && -f $dir/.commitment-fabric-install && $(< "$dir/.commitment-fabric-install") == "$stamp" ]]; then
		return 0
	fi
	jar=run/real/installers/fabric-installer-$installer.jar
	fetch_checked "$FABRIC_INSTALLER_MAVEN/$installer/fabric-installer-$installer.jar" \
		"$(curl -fsS --max-time 30 "$FABRIC_INSTALLER_MAVEN/$installer/fabric-installer-$installer.jar.sha1" | tr -d '[:space:]')" "$jar" || return 1
	mkdir -p "$dir"
	echo "server-jars: installing Fabric Loader $loader for Minecraft $version into $dir" >&2
	if ! java -jar "$jar" server -dir "$dir" -mcversion "$version" -loader "$loader" -downloadMinecraft >&2; then
		echo "server-jars: the Fabric installer failed" >&2
		return 1
	fi
	[[ -f $dir/fabric-server-launch.jar ]] || { echo "server-jars: the Fabric installer wrote no fabric-server-launch.jar" >&2; return 1; }
	echo "$stamp" > "$dir/.commitment-fabric-install"
}

# fabric_api <node> <mods dir> [<minecraft version>]: Fabric API into <mods dir>, replacing any other Fabric API jar
# there. The node's pin (fabric_api_version.<node>) on the node's own Minecraft; on another Minecraft of the band,
# real_fabric_api.<minecraft>.
fabric_api() {
	local node=$1 mods=$2 minecraft=${3:-$1} version name other
	# It removes jars: only ever in a Quilt or real server's mods dir under run/.
	if [[ ! $mods =~ ^run/(quilt/[0-9.]+|real/(fabric|quilt)/[0-9.]+/[0-9.]+)/server/mods$ ]]; then
		echo "server-jars: refusing to put Fabric API into '$mods': not run/quilt/<node>/server/mods or run/real/<fabric|quilt>/<node>/<minecraft>/server/mods" >&2
		return 1
	fi
	if [[ $minecraft == "$node" ]]; then
		version=$(gradle_prop "fabric_api_version.$node") || return 1
	else
		version=$(gradle_prop "real_fabric_api.$minecraft") || return 1
	fi
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

# loader_artifact <neoforge|forge> <node> <version>: the loader's Maven version and path under its maven (Forge's carry
# the Minecraft version: 26.3-66.0.0).
loader_artifact() {
	case $1 in
		neoforge) echo "$3 $NEOFORGE_MAVEN/$3/neoforge-$3" ;;
		forge) echo "$2-$3 $FORGE_MAVEN/$2-$3/forge-$2-$3" ;;
		*) echo "server-jars: no installer for loader '$1'" >&2; return 1 ;;
	esac
}

# loader_args <neoforge|forge> <node> <version>: the arguments file the installer writes, relative to the server dir
# (what its run.bat/run.sh pass to java after @user_jvm_args.txt). win_args.txt under Git Bash, whose java is Windows'.
loader_args() {
	local artifact os=unix
	artifact=$(loader_artifact "$@") || return 1
	case $(uname -s) in MINGW* | MSYS* | CYGWIN*) os=win ;; esac
	case $1 in
		neoforge) echo "libraries/net/neoforged/neoforge/${artifact%% *}/${os}_args.txt" ;;
		forge) echo "libraries/net/minecraftforge/forge/${artifact%% *}/${os}_args.txt" ;;
	esac
}

# loader_server <neoforge|forge> <node> <version> <dir>: that loader build installed as a server into <dir> (its
# installer downloads and checks the vanilla server and the libraries). Skipped when <dir> already holds that install.
loader_server() {
	local loader=$1 node=$2 version=$3 dir=$4 artifact maven url jar stamp args
	artifact=$(loader_artifact "$loader" "$node" "$version") || return 1
	maven=${artifact%% *}
	url=${artifact#* }-installer.jar
	args=$(loader_args "$loader" "$node" "$version") || return 1
	stamp="loader=$loader version=$maven"
	if [[ -f $dir/$args && -f $dir/.commitment-loader-install && $(< "$dir/.commitment-loader-install") == "$stamp" ]]; then
		return 0
	fi
	jar=run/real/installers/$loader-$maven-installer.jar
	fetch_checked "$url" "$(curl -fsS --max-time 30 "$url.sha1" | tr -d '[:space:]')" "$jar" || return 1
	mkdir -p "$dir"
	echo "server-jars: installing $loader $maven as a server into $dir" >&2
	if ! java -jar "$jar" --installServer "$dir" > "$dir/../installer.log" 2>&1; then
		echo "server-jars: the $loader installer failed; see $dir/../installer.log" >&2
		return 1
	fi
	[[ -f $dir/$args ]] || { echo "server-jars: the $loader installer wrote no $args" >&2; return 1; }
	echo "$stamp" > "$dir/.commitment-loader-install"
}
