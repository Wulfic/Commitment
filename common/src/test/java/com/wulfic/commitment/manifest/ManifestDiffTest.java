package com.wulfic.commitment.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wulfic.commitment.manifest.ManifestDiff.Change;
import com.wulfic.commitment.manifest.ManifestDiff.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** B.2: what the LockedScreen and the server log show for each change, in what order, and where it stops. */
class ManifestDiffTest {
	private static final String HASH_A = "sha256:" + "a".repeat(64);
	private static final String HASH_B = "sha256:" + "b".repeat(64);

	static Stream<Arguments> lines() {
		return Stream.of(
				line(Kind.CHANGED, "minecraft", "26.2", "26.3", "Minecraft 26.2 -> 26.3"),
				line(Kind.CHANGED, "loader", "Fabric 0.19.5", "Fabric 0.19.6", "Loader Fabric 0.19.5 -> Fabric 0.19.6"),
				line(Kind.ADDED, "mod/sodium", null, "0.9.3 " + HASH_A, "Mod added: sodium 0.9.3"),
				line(Kind.REMOVED, "mod/lithium", "0.18.0 " + HASH_A, null, "Mod removed: lithium 0.18.0"),
				line(Kind.CHANGED, "mod/create", "6.0.1 " + HASH_A, "6.0.2 " + HASH_B, "Mod changed: create 6.0.1 -> 6.0.2"),
				line(Kind.CHANGED, "mod/create", "6.0.1 " + HASH_A, "6.0.1 " + HASH_B, "Mod changed: create 6.0.1 (a different jar)"),
				line(Kind.CHANGED, "mod/nested", "1.0", "1.1", "Mod changed: nested 1.0 -> 1.1"),
				line(Kind.CHANGED, "mod/odd", "1.0 beta " + HASH_A, "1.0 beta 2 " + HASH_B, "Mod changed: odd 1.0 beta -> 1.0 beta 2"),
				line(Kind.CHANGED, "config/create-common.toml", HASH_A, HASH_B, "Config changed: config/create-common.toml"),
				line(Kind.ADDED, "defaultconfigs/x.toml", null, HASH_A, "Config added: defaultconfigs/x.toml"),
				line(Kind.REMOVED, "serverconfig/y.toml", HASH_A, null, "Config removed: serverconfig/y.toml"),
				line(Kind.CHANGED, "options/resourcePacks", "[\"vanilla\",\"file/A.zip\",\"file/B\"]",
						"[\"vanilla\",\"file/B\",\"file/A.zip\"]", "Resource packs: order changed"),
				line(Kind.CHANGED, "options/resourcePacks", "[\"vanilla\",\"file/A.zip\"]",
						"[\"vanilla\",\"file/C.zip\"]", "Resource packs: enabled C.zip; disabled A.zip"),
				line(Kind.CHANGED, "options/incompatibleResourcePacks", "[]", "[\"file/Old.zip\"]",
						"Incompatible resource packs: enabled Old.zip"),
				line(Kind.CHANGED, "options/resourcePacks", "not json [", "[]", "Resource packs changed"),
				line(Kind.ADDED, "options/resourcePacks", null, "[]", "Resource packs added"),
				line(Kind.CHANGED, "resourcepack/A.zip", HASH_A, HASH_B, "Resource pack changed: A.zip"),
				line(Kind.CHANGED, "resourcepack/A.zip", HASH_A, "missing", "Resource pack missing: A.zip"),
				line(Kind.ADDED, "datapack/terra.zip", null, HASH_A, "Datapack added: terra.zip"),
				line(Kind.CHANGED, "server.properties/rcon.password", HASH_A, HASH_B, "server.properties: rcon.password changed"),
				line(Kind.REMOVED, "something/else", "x", null, "something/else removed"));
	}

	@ParameterizedTest(name = "{4}")
	@MethodSource("lines")
	void eachChangeReadsAsOneLine(Kind kind, String key, String was, String now, String expected) {
		String line = ManifestDiff.line(new Change(kind, key, was, now));
		assertEquals(expected, line);
		assertFalse(line.contains("sha256"), "a line never shows a hash: " + line);
	}

	@Test
	void theMostSignificantChangesComeFirst() {
		// Built in key order, the way Manifest.diff hands them over.
		ManifestDiff diff = new ManifestDiff(List.of(
				new Change(Kind.CHANGED, "config/a.toml", HASH_A, HASH_B),
				new Change(Kind.ADDED, "datapack/d.zip", null, HASH_A),
				new Change(Kind.CHANGED, "loader", "Fabric 1", "Fabric 2"),
				new Change(Kind.CHANGED, "minecraft", "26.2", "26.3"),
				new Change(Kind.ADDED, "mod/b", null, "1"),
				new Change(Kind.ADDED, "mod/a", null, "1"),
				new Change(Kind.CHANGED, "options/resourcePacks", "[]", "[\"file/P.zip\"]"),
				new Change(Kind.CHANGED, "resourcepack/P.zip", HASH_A, HASH_B),
				new Change(Kind.CHANGED, "server.properties/motd", HASH_A, HASH_B),
				new Change(Kind.CHANGED, "serverconfig/s.toml", HASH_A, HASH_B),
				new Change(Kind.CHANGED, "defaultconfigs/d.toml", HASH_A, HASH_B),
				new Change(Kind.REMOVED, "zzz/unknown", "x", null)));
		assertEquals(List.of(
				"Minecraft 26.2 -> 26.3",
				"Loader Fabric 1 -> Fabric 2",
				"Mod added: a 1",
				"Mod added: b 1",
				"Resource packs: enabled P.zip",
				"Resource pack changed: P.zip",
				"Datapack added: d.zip",
				"Config changed: config/a.toml",
				"Config changed: defaultconfigs/d.toml",
				"Config changed: serverconfig/s.toml",
				"server.properties: motd changed",
				"zzz/unknown removed"), diff.lines(100));
	}

	@Test
	void atTheCapEveryChangeShows() {
		assertEquals(List.of("Mod added: m0 1", "Mod added: m1 1", "Mod added: m2 1"), mods(3).lines(3));
	}

	@Test
	void pastTheCapTheLastLineCountsTheRest() {
		List<String> lines = mods(10).lines(4);
		assertEquals(List.of("Mod added: m0 1", "Mod added: m1 1", "Mod added: m2 1", "...and 7 more changes"), lines);
	}

	@Test
	void oneOverTheCapStillFitsTheCap() {
		assertEquals(List.of("Mod added: m0 1", "...and 2 more changes"), mods(3).lines(2));
	}

	@Test
	void noChangesNoLines() {
		assertEquals(List.of(), new ManifestDiff(List.of()).lines(10));
	}

	@Test
	void aCapWithNoRoomIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> mods(3).lines(1));
	}

	private static ManifestDiff mods(int n) {
		List<Change> changes = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			changes.add(new Change(Kind.ADDED, "mod/m" + i, null, "1"));
		}
		return new ManifestDiff(changes);
	}

	private static Arguments line(Kind kind, String key, String was, String now, String expected) {
		return Arguments.of(kind, key, was, now, expected);
	}
}
