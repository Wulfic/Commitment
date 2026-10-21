package com.wulfic.commitment.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wulfic.commitment.manifest.GameEnvironment.ModFile;
import com.wulfic.commitment.manifest.GameEnvironment.Side;
import com.wulfic.commitment.manifest.ManifestDiff.Change;
import com.wulfic.commitment.manifest.ManifestDiff.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** B.1: one change of each kind gives exactly that diff, and everything the owner keeps free gives none. */
class ManifestScannerTest {
	@TempDir
	Path dir;

	private Path game;
	private Path world;
	private Side side;
	private String minecraft;
	private String loaderVersion;
	private List<ModFile> mods;

	@BeforeEach
	void setUp() throws IOException {
		game = dir.resolve("instance");
		world = game.resolve("saves/My World");
		side = Side.CLIENT;
		minecraft = "26.3";
		loaderVersion = "0.19.5";
		write("mods/alpha.jar", "alpha jar v1");
		write("mods/commitment.jar", "commitment jar");
		mods = new ArrayList<>(List.of(
				new ModFile("alpha", "1.0.0", game.resolve("mods/alpha.jar")),
				new ModFile("nested", "2.0.0", null),
				new ModFile("commitment", "0.1.0", game.resolve("mods/commitment.jar")),
				new ModFile("java", "25", null)));
		write("config/alpha.json", "{\"a\": 1, \"b\": {\"x\": true, \"y\": [1, 2]}}");
		write("config/beta.toml", "# Beta config\n[general]\nspeed = 3\n");
		write("config/gamma.properties", "one=1\ntwo=2\n");
		write("config/sub/deep.cfg", "depth=7\n");
		write("config/sodium-options.json", "{\"quality\": \"fancy\"}");
		write("config/iris.properties", "shaderPack=Complementary.zip\n");
		write("config/DistantHorizons.toml", "renderDistance = 64\n");
		write("config/sound_physics_remastered/soundphysics.properties", "reverb=1\n");
		write("defaultconfigs/delta.toml", "level = 1\n");
		write("options.txt", String.join("\n",
				"version:4671",
				"renderDistance:12",
				"soundCategory_master:1.0",
				"key_key.jump:key.keyboard.space",
				"resourcePacks:[\"vanilla\",\"file/Faithful.zip\",\"file/Folder Pack\"]",
				"incompatibleResourcePacks:[]",
				""));
		write("resourcepacks/Faithful.zip", "faithful v1");
		write("resourcepacks/Folder Pack/pack.mcmeta", "{\"pack\":{}}");
		write("resourcepacks/Unused.zip", "not enabled, so not locked");
		write("shaderpacks/Complementary.zip", "shader v1");
		write("server.properties", "#Minecraft server properties\n#Wed Oct 21 17:00:00 EDT 2026\nmotd=Hello\ndifficulty=hard\nrcon.password=hunter2\n");
		write("saves/My World/serverconfig/epsilon.toml", "spawn = true\n");
		write("saves/My World/datapacks/terra.zip", "terra v1");
		write("saves/My World/datapacks/" + ManifestScanner.GUARD_PACK, "guard v1");
	}

	@Test
	void scanRecordsEveryLockedKindAndNothingElse() throws IOException {
		assertEquals(Set.of(
				"minecraft", "loader", "mod/alpha", "mod/nested",
				"config/alpha.json", "config/beta.toml", "config/gamma.properties", "config/sub/deep.cfg",
				"defaultconfigs/delta.toml", "serverconfig/epsilon.toml",
				"options/resourcePacks", "options/incompatibleResourcePacks",
				"resourcepack/Faithful.zip", "resourcepack/Folder Pack",
				"datapack/terra.zip"), scan().entries().keySet());
	}

	@Test
	void fingerprintsHaveTheDocumentedShape() throws IOException {
		Manifest m = scan();
		assertEquals("26.3", m.entries().get("minecraft"));
		assertEquals("Fabric 0.19.5", m.entries().get("loader"));
		assertTrue(m.entries().get("mod/alpha").matches("1\\.0\\.0 sha256:[0-9a-f]{64}"), m.entries().get("mod/alpha"));
		assertEquals("2.0.0", m.entries().get("mod/nested"), "a nested mod has no jar of its own, so its version is the lock");
		assertEquals("[\"vanilla\",\"file/Faithful.zip\",\"file/Folder Pack\"]", m.entries().get("options/resourcePacks"));
	}

	@Test
	void anUnchangedSetupHasNoDiff() throws IOException {
		assertEquals(List.of(), scan().diff(scan()).changes());
	}

	static Stream<Arguments> oneChange() {
		return Stream.of(
				change("Minecraft version", t -> t.minecraft = "26.3.1", Kind.CHANGED, "minecraft"),
				change("loader version", t -> t.loaderVersion = "0.19.6", Kind.CHANGED, "loader"),
				change("mod added", t -> {
					t.write("mods/beta.jar", "beta");
					t.mods.add(new ModFile("beta", "1.0", t.game.resolve("mods/beta.jar")));
				}, Kind.ADDED, "mod/beta"),
				change("mod removed", t -> t.mods.removeIf(m -> m.id().equals("alpha")), Kind.REMOVED, "mod/alpha"),
				change("mod version", t -> t.mods.replaceAll(m -> m.id().equals("alpha")
						? new ModFile("alpha", "1.0.1", m.jar()) : m), Kind.CHANGED, "mod/alpha"),
				change("mod jar rebuilt, same version", t -> t.write("mods/alpha.jar", "alpha jar v1, patched"),
						Kind.CHANGED, "mod/alpha"),
				change("nested mod version", t -> t.mods.replaceAll(m -> m.id().equals("nested")
						? new ModFile("nested", "2.1.0", null) : m), Kind.CHANGED, "mod/nested"),
				change("config value", t -> t.write("config/alpha.json", "{\"a\": 2, \"b\": {\"x\": true, \"y\": [1, 2]}}"),
						Kind.CHANGED, "config/alpha.json"),
				change("config array order", t -> t.write("config/alpha.json", "{\"a\": 1, \"b\": {\"x\": true, \"y\": [2, 1]}}"),
						Kind.CHANGED, "config/alpha.json"),
				change("config file added", t -> t.write("config/new.toml", "x = 1\n"), Kind.ADDED, "config/new.toml"),
				change("config file removed", t -> t.delete("config/beta.toml"), Kind.REMOVED, "config/beta.toml"),
				change("toml value", t -> t.write("config/beta.toml", "[general]\nspeed = 4\n"), Kind.CHANGED, "config/beta.toml"),
				change("properties value", t -> t.write("config/gamma.properties", "one=1\ntwo=3\n"),
						Kind.CHANGED, "config/gamma.properties"),
				change("config in a subfolder", t -> t.write("config/sub/deep.cfg", "depth=8\n"), Kind.CHANGED, "config/sub/deep.cfg"),
				change("defaultconfigs", t -> t.write("defaultconfigs/delta.toml", "level = 2\n"),
						Kind.CHANGED, "defaultconfigs/delta.toml"),
				change("world serverconfig", t -> t.write("saves/My World/serverconfig/epsilon.toml", "spawn = false\n"),
						Kind.CHANGED, "serverconfig/epsilon.toml"),
				change("resource pack order", t -> t.options("resourcePacks:[\"vanilla\",\"file/Folder Pack\",\"file/Faithful.zip\"]"),
						Kind.CHANGED, "options/resourcePacks"),
				change("incompatible resource packs", t -> t.options("incompatibleResourcePacks:[\"file/Faithful.zip\"]"),
						Kind.CHANGED, "options/incompatibleResourcePacks"),
				change("resource pack zip content", t -> t.write("resourcepacks/Faithful.zip", "faithful v2"),
						Kind.CHANGED, "resourcepack/Faithful.zip"),
				change("file added inside a folder pack", t -> t.write("resourcepacks/Folder Pack/assets/x.png", "png"),
						Kind.CHANGED, "resourcepack/Folder Pack"),
				change("enabled resource pack deleted", t -> t.delete("resourcepacks/Faithful.zip"),
						Kind.CHANGED, "resourcepack/Faithful.zip"),
				change("datapack content", t -> t.write("saves/My World/datapacks/terra.zip", "terra v2"),
						Kind.CHANGED, "datapack/terra.zip"),
				change("datapack added", t -> t.write("saves/My World/datapacks/extra/pack.mcmeta", "{}"),
						Kind.ADDED, "datapack/extra"),
				change("datapack removed", t -> t.delete("saves/My World/datapacks/terra.zip"), Kind.REMOVED, "datapack/terra.zip"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("oneChange")
	void oneChangeGivesExactlyThatDiff(String name, Mutation mutation, Kind kind, String key) throws IOException {
		Manifest committed = scan();
		mutation.apply(this);
		List<Change> changes = committed.diff(scan()).changes();
		assertEquals(1, changes.size(), name + ": " + changes);
		assertEquals(kind, changes.getFirst().kind(), name);
		assertEquals(key, changes.getFirst().key(), name);
	}

	static Stream<Arguments> noChange() {
		return Stream.of(
				same("json keys reordered and re-indented", t -> t.write("config/alpha.json",
						"{\n  \"b\": {\"y\": [1, 2], \"x\": true},\n  \"a\": 1\n}\n")),
				same("toml comments, blank lines, CRLF and trailing spaces", t -> t.write("config/beta.toml",
						"# Rewritten by the mod\r\n\r\n[general]   \r\n# the speed\r\nspeed = 3\r\n")),
				same("properties reordered with a comment", t -> t.write("config/gamma.properties", "#saved\ntwo=2\none=1\n")),
				same("a byte-order mark", t -> t.write("config/beta.toml", "﻿# Beta config\n[general]\nspeed = 3\n")),
				same("render distance", t -> t.options("renderDistance:32")),
				same("master volume", t -> t.options("soundCategory_master:0.25")),
				same("a keybind", t -> t.options("key_key.jump:key.keyboard.j")),
				same("a shader pack added", t -> t.write("shaderpacks/BSL.zip", "bsl")),
				same("a shader pack changed", t -> t.write("shaderpacks/Complementary.zip", "shader v2")),
				same("sodium-options.json", t -> t.write("config/sodium-options.json", "{\"quality\": \"fast\"}")),
				same("iris.properties (the chosen shader pack)", t -> t.write("config/iris.properties", "shaderPack=BSL.zip\n")),
				same("DistantHorizons.toml", t -> t.write("config/DistantHorizons.toml", "renderDistance = 256\n")),
				same("a file in an audio mod's folder", t -> t.write("config/sound_physics_remastered/soundphysics.properties", "reverb=0\n")),
				same("dynamic_fps config", t -> t.write("config/dynamic_fps.json", "{}")),
				same("OS junk in config", t -> {
					t.write("config/.DS_Store", "junk");
					t.write("config/sub/Thumbs.db", "junk");
				}),
				same("a resource pack that isn't enabled", t -> t.write("resourcepacks/Unused.zip", "changed")),
				same("Commitment's own update", t -> {
					t.write("mods/commitment.jar", "commitment jar v2");
					t.mods.replaceAll(m -> m.id().equals("commitment") ? new ModFile("commitment", "0.2.0", m.jar()) : m);
				}),
				same("a JRE update", t -> t.mods.replaceAll(m -> m.id().equals("java") ? new ModFile("java", "25.0.5", null) : m)),
				same("the guard pack", t -> t.write("saves/My World/datapacks/" + ManifestScanner.GUARD_PACK, "guard v2")),
				same("server.properties on a client", t -> t.write("server.properties", "motd=Changed\n")),
				same("the mod list in another order", t -> java.util.Collections.reverse(t.mods)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("noChange")
	void freeSettingsGiveNoDiff(String name, Mutation mutation) throws IOException {
		Manifest committed = scan();
		mutation.apply(this);
		assertEquals(List.of(), committed.diff(scan()).changes(), name);
	}

	@Test
	void aDedicatedServerLocksEveryServerPropertyButNotOptionsTxt() throws IOException {
		side = Side.DEDICATED_SERVER;
		Manifest committed = scan();
		assertTrue(committed.entries().keySet().containsAll(Set.of(
				"server.properties/motd", "server.properties/difficulty", "server.properties/rcon.password")));
		assertFalse(committed.entries().keySet().stream().anyMatch(k -> k.startsWith("options/") || k.startsWith("resourcepack/")),
				committed.entries().keySet().toString());

		// The server rewrites the header comment with the date on every boot; that's not a change.
		write("server.properties", "#Minecraft server properties\n#Thu Oct 22 09:00:00 EDT 2026\ndifficulty=hard\nmotd=Hello\nrcon.password=hunter2\n");
		write("options.txt", "resourcePacks:[]\n");
		assertEquals(List.of(), committed.diff(scan()).changes());

		write("server.properties", "motd=Goodbye\ndifficulty=hard\nrcon.password=hunter2\n");
		List<Change> changes = committed.diff(scan()).changes();
		assertEquals(1, changes.size(), changes.toString());
		assertEquals("server.properties/motd", changes.getFirst().key());
	}

	@Test
	void aServerPropertyIsRecordedAsAHashNeverItsValue() throws IOException {
		side = Side.DEDICATED_SERVER;
		Manifest m = scan();
		String password = m.entries().get("server.properties/rcon.password");
		assertTrue(password.matches("sha256:[0-9a-f]{64}"), password);
		assertFalse(m.entries().values().stream().anyMatch(v -> v.contains("hunter2")));
	}

	@Test
	void aResourcePackNamedOutsideResourcepacksIsNotRead() throws IOException {
		options("resourcePacks:[\"file/../options.txt\"]");
		assertEquals("outside resourcepacks/", scan().entries().get("resourcepack/../options.txt"));
	}

	@Test
	void anUnreadableModJarFailsTheScanWithItsPath() {
		mods.add(new ModFile("ghost", "1.0", game.resolve("mods/ghost.jar")));
		IOException e = assertThrows(IOException.class, this::scan);
		assertTrue(e.getMessage().contains("ghost.jar"), e.getMessage());
	}

	@Test
	void aModListedTwiceIsRefused() {
		mods.add(new ModFile("alpha", "9.9", null));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, this::scan);
		assertTrue(e.getMessage().contains("alpha"), e.getMessage());
	}

	@Test
	void keysUseForwardSlashesOnEveryOs() throws IOException {
		assertTrue(scan().entries().keySet().stream().noneMatch(k -> k.contains("\\")));
	}

	// --- fixture ---

	@FunctionalInterface
	interface Mutation {
		void apply(ManifestScannerTest t) throws IOException;
	}

	private static Arguments change(String name, Mutation m, Kind kind, String key) {
		return Arguments.of(name, m, kind, key);
	}

	private static Arguments same(String name, Mutation m) {
		return Arguments.of(name, m);
	}

	private Manifest scan() throws IOException {
		return ManifestScanner.scan(new GameEnvironment(side, minecraft, "Fabric", loaderVersion, mods, game, world));
	}

	private void write(String rel, String content) throws IOException {
		Path file = game.resolve(rel);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private void delete(String rel) throws IOException {
		Files.delete(game.resolve(rel));
	}

	/** Replaces the {@code key:} line of options.txt, the way the game rewrites one setting. */
	private void options(String line) throws IOException {
		String key = line.substring(0, line.indexOf(':') + 1);
		Path file = game.resolve("options.txt");
		List<String> lines = new ArrayList<>(Files.readAllLines(file));
		lines.replaceAll(l -> l.startsWith(key) ? line : l);
		Files.write(file, lines);
	}
}
