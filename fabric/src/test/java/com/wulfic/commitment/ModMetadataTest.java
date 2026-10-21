package com.wulfic.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Guards the wiring Fabric reads at launch: a drift here means the mod silently never loads. */
class ModMetadataTest {
	private static JsonObject metadata;

	/**
	 * Every Fabric API module on the test classpath ships its own fabric.mod.json, so pick ours by
	 * id. If the id drifts from {@link Commitment#MOD_ID}, nothing matches and this fails.
	 */
	@BeforeAll
	static void loadMetadata() throws IOException {
		metadata = ours();
	}

	/** Our fabric.mod.json as the jar ships it: placeholders expanded. BandLabelTest reads it too. */
	static JsonObject ours() throws IOException {
		List<JsonObject> ours = new ArrayList<>();
		Enumeration<URL> all = ModMetadataTest.class.getClassLoader().getResources("fabric.mod.json");
		while (all.hasMoreElements()) {
			try (Reader reader = new InputStreamReader(all.nextElement().openStream(), StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				if (Commitment.MOD_ID.equals(json.get("id").getAsString())) {
					ours.add(json);
				}
			}
		}
		assertEquals(1, ours.size(), "fabric.mod.json files declaring id " + Commitment.MOD_ID);
		return ours.get(0);
	}

	@Test
	void modIdMatchesConstant() {
		assertEquals(Commitment.MOD_ID, metadata.get("id").getAsString());
	}

	@Test
	void versionIsExpandedByProcessResources() {
		String version = metadata.get("version").getAsString();
		assertFalse(version.contains("${"), "version placeholder was not expanded: " + version);
	}

	@Test
	void mainEntrypointIsCommitmentFabric() throws ClassNotFoundException {
		JsonArray main = metadata.getAsJsonObject("entrypoints").getAsJsonArray("main");
		assertEquals(1, main.size());
		Class<?> entrypoint = Class.forName(main.get(0).getAsString(), false, getClass().getClassLoader());
		assertEquals(CommitmentFabric.class, entrypoint);
		assertTrue(ModInitializer.class.isAssignableFrom(entrypoint));
	}

	/** The jar's declared licence must be the one the repo's LICENSE file grants. fabric/build.gradle passes the repo root. */
	@Test
	void licenseIsMitAndMatchesTheLicenseFile() throws IOException {
		assertEquals("MIT", metadata.get("license").getAsString());
		String root = System.getProperty("commitment.repoRoot");
		assertNotNull(root, "commitment.repoRoot is not set; run this through Gradle");
		String file = Files.readString(Path.of(root, "LICENSE"), StandardCharsets.UTF_8);
		assertTrue(file.startsWith("MIT License"), "LICENSE is not the MIT licence");
	}

	@Test
	void iconExistsAtDeclaredPath() throws IOException {
		String icon = metadata.get("icon").getAsString();
		try (InputStream in = getClass().getResourceAsStream("/" + icon)) {
			assertNotNull(in, "icon declared in fabric.mod.json is missing: " + icon);
		}
	}
}
