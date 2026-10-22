package com.wulfic.commitment.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** C.3: what {@code /commit} says it will lock. */
class ManifestCountsTest {
	@Test
	void countsEachKindByItsKeys() {
		TreeMap<String, String> entries = new TreeMap<>();
		entries.put("minecraft", "26.3");
		entries.put("loader", "Fabric 0.19.5");
		entries.put("mod/a", "1");
		entries.put("mod/b", "1");
		entries.put("options/resourcePacks", "[]");
		entries.put("resourcepack/file/x.zip", "h");
		entries.put("config/a.json", "h");
		entries.put("defaultconfigs/b.toml", "h");
		entries.put("serverconfig/c.toml", "h");
		entries.put("datapack/file/p.zip", "h");
		entries.put("server.properties/motd", "h");
		assertEquals(new Manifest.Counts(2, 1, 3, 1), new Manifest(entries).counts());
	}

	@Test
	void describesWithSingularsAndDatapacksOnlyWhenThereAreAny() {
		assertEquals("1 mod, 0 resource packs, 1 config file", new Manifest.Counts(1, 0, 1, 0).describe());
		assertEquals("42 mods, 3 resource packs, 118 config files, 2 datapacks", new Manifest.Counts(42, 3, 118, 2).describe());
		assertEquals("2 mods, 1 resource pack, 0 config files, 1 datapack", new Manifest.Counts(2, 1, 0, 1).describe());
	}
}
