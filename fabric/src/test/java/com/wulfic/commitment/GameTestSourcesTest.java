package com.wulfic.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The server GameTests in {@code fabric/src/gametest} also run on NeoForge and Forge, which compile them with the
 * {@code GameTest} import swapped (plan step C.0). So they may name no other loader API, and every class with tests has
 * to be in the {@code fabric-gametest} list, which every loader's runner reads. Fabric skips an unlisted class
 * without a word. The {@code gametest.fabric} package and client GameTests are Fabric's alone, never copied.
 */
class GameTestSourcesTest {
	private static final String SWAPPED_IMPORT = "import net.fabricmc.fabric.api.gametest.v1.GameTest;";
	private static final Pattern LOADER_API = Pattern.compile("\\b(net\\.fabricmc|net\\.neoforged|net\\.minecraftforge|org\\.quiltmc)\\.");

	@Test
	void serverGameTestsNameNoLoaderApiButTheSwappedAnnotation() throws IOException {
		List<String> hits = new ArrayList<>();
		for (Path source : serverSources()) {
			List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).strip();
				Matcher m = LOADER_API.matcher(line);
				if (m.find() && !line.equals(SWAPPED_IMPORT)) {
					hits.add(source.getFileName() + ":" + (i + 1) + " " + line);
				}
			}
		}
		assertEquals(List.of(), hits, "loader APIs in server GameTests; NeoForge and Forge can't compile these");
	}

	@Test
	void everyServerGameTestClassIsListedAndEveryListedClassExists() throws IOException {
		TreeSet<String> withTests = new TreeSet<>();
		for (Path source : serverSources()) {
			if (Files.readString(source, StandardCharsets.UTF_8).contains("@GameTest")) {
				String relative = gametestJava().relativize(source).toString().replace('\\', '/');
				withTests.add(relative.substring(0, relative.length() - ".java".length()).replace('/', '.'));
			}
		}
		assertFalse(withTests.isEmpty(), "no server GameTest classes under " + gametestJava());
		JsonObject root = JsonParser.parseString(Files.readString(repoRoot().resolve("fabric/src/gametest/resources/fabric.mod.json")))
				.getAsJsonObject();
		TreeSet<String> listed = new TreeSet<>();
		root.getAsJsonObject("entrypoints").getAsJsonArray("fabric-gametest").forEach(e -> listed.add(e.getAsString()));
		assertEquals(withTests, listed, "fabric-gametest in fabric/src/gametest/resources/fabric.mod.json");
	}

	private static List<Path> serverSources() throws IOException {
		try (Stream<Path> files = Files.walk(gametestJava())) {
			List<Path> sources = files.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> !p.getFileName().toString().endsWith("ClientGameTest.java"))
					// Fabric's own hooks (PackDriverFabric), which neoforge|forge/build.gradle's swapGameTestImports leave out.
					.filter(p -> !gametestJava().relativize(p).toString().replace('\\', '/').contains("/gametest/fabric/"))
					.sorted().toList();
			assertFalse(sources.isEmpty(), "no server GameTest sources under " + gametestJava());
			return sources;
		}
	}

	private static Path gametestJava() {
		return repoRoot().resolve("fabric/src/gametest/java");
	}

	private static Path repoRoot() {
		String root = System.getProperty("commitment.repoRoot");
		assertNotNull(root, "commitment.repoRoot is not set; run this through Gradle");
		return Path.of(root);
	}
}
