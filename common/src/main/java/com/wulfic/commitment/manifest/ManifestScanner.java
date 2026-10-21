package com.wulfic.commitment.manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.wulfic.commitment.Commitment;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Reads a world's setup into a {@link Manifest}. Pure Java over a {@link GameEnvironment}; no loader API. */
public final class ManifestScanner {
	/** The guard datapack's file name in {@code <world>/datapacks/}. It's Commitment's own, so it isn't locked. */
	public static final String GUARD_PACK = "commitment_guard.zip";

	/**
	 * Mods whose version isn't part of the setup: Commitment's own updates don't count (owner, 2026-10-21), and a JRE
	 * update isn't a change to the game. Minecraft and the loader have their own entries.
	 */
	static final Set<String> UNLOCKED_MODS = Set.of(Commitment.MOD_ID, "java");

	private static final String RESOURCE_PACK_PREFIX = "file/";

	private ManifestScanner() {
	}

	/**
	 * Scans the setup. Fails closed: a file it can't read throws with that file's path, and the caller logs it and
	 * keeps the world locked, rather than treating it as unchanged.
	 */
	public static Manifest scan(GameEnvironment env) throws IOException {
		TreeMap<String, String> entries = new TreeMap<>();
		entries.put("minecraft", env.minecraftVersion());
		entries.put("loader", env.loaderName() + " " + env.loaderVersion());
		for (GameEnvironment.ModFile mod : env.mods()) {
			if (UNLOCKED_MODS.contains(mod.id())) {
				continue;
			}
			String fingerprint = mod.jar() == null ? mod.version() : mod.version() + " " + read(mod.jar(), Fingerprints::fileOrFolder);
			if (entries.put("mod/" + mod.id(), fingerprint) != null) {
				throw new IllegalArgumentException("the loader listed mod " + mod.id() + " twice");
			}
		}
		configFolder(env.gameDir().resolve("config"), "config/", entries);
		configFolder(env.gameDir().resolve("defaultconfigs"), "defaultconfigs/", entries);
		configFolder(env.worldDir().resolve("serverconfig"), "serverconfig/", entries);
		datapacks(env.worldDir().resolve("datapacks"), entries);
		if (env.side() == GameEnvironment.Side.CLIENT) {
			resourcePacks(env.gameDir(), entries);
		} else {
			serverProperties(env.gameDir().resolve("server.properties"), entries);
		}
		return new Manifest(entries);
	}

	private static void configFolder(Path root, String prefix, TreeMap<String, String> entries) throws IOException {
		if (!Files.isDirectory(root)) {
			return;
		}
		for (Path file : regularFiles(root)) {
			String rel = Fingerprints.relative(root, file);
			if (!Exclusions.excluded(rel)) {
				entries.put(prefix + rel, Fingerprints.config(rel, read(file, Files::readAllBytes)));
			}
		}
	}

	private static void datapacks(Path root, TreeMap<String, String> entries) throws IOException {
		if (!Files.isDirectory(root)) {
			return;
		}
		for (Path pack : list(root)) {
			String name = pack.getFileName().toString();
			if (!name.equals(GUARD_PACK)) {
				entries.put("datapack/" + name, read(pack, Fingerprints::fileOrFolder));
			}
		}
	}

	/**
	 * The enabled pack list and its order from {@code options.txt}, and the content of each {@code file/} pack in it.
	 * Every other {@code options.txt} setting stays free.
	 */
	private static void resourcePacks(Path gameDir, TreeMap<String, String> entries) throws IOException {
		Path options = gameDir.resolve("options.txt");
		if (!Files.isRegularFile(options)) {
			return;
		}
		Set<String> packs = new LinkedHashSet<>();
		for (String line : read(options, Files::readAllLines)) {
			int colon = line.indexOf(':');
			if (colon < 0) {
				continue;
			}
			String key = line.substring(0, colon);
			if (key.equals("resourcePacks") || key.equals("incompatibleResourcePacks")) {
				String value = line.substring(colon + 1).strip();
				entries.put("options/" + key, packList(value, packs));
			}
		}
		Path packDir = gameDir.resolve("resourcepacks").toAbsolutePath().normalize();
		for (String id : packs) {
			if (!id.startsWith(RESOURCE_PACK_PREFIX)) {
				continue;
			}
			String name = id.substring(RESOURCE_PACK_PREFIX.length());
			Path pack = packDir.resolve(name).normalize();
			String fingerprint;
			if (!pack.startsWith(packDir) || pack.equals(packDir)) {
				// Vanilla won't load a pack outside resourcepacks/ either; record it without reading it.
				fingerprint = "outside resourcepacks/";
			} else if (Files.exists(pack)) {
				fingerprint = read(pack, Fingerprints::fileOrFolder);
			} else {
				fingerprint = "missing";
			}
			entries.put("resourcepack/" + name, fingerprint);
		}
	}

	/** The list as canonical JSON (order kept: it's the stacking order), its entries collected into {@code packs}. */
	private static String packList(String value, Set<String> packs) {
		try {
			JsonElement json = JsonParser.parseString(value);
			if (json.isJsonArray()) {
				for (JsonElement e : json.getAsJsonArray()) {
					if (e.isJsonPrimitive()) {
						packs.add(e.getAsString());
					}
				}
			}
			return Fingerprints.canonicalJson(json);
		} catch (JsonParseException e) {
			Commitment.LOGGER.info("Commitment: options.txt has an unreadable pack list ({}); comparing it as text", e.getMessage());
			return value;
		}
	}

	/** One entry per property, hashed: the diff can name a changed key without printing its value (rcon.password). */
	private static void serverProperties(Path file, TreeMap<String, String> entries) throws IOException {
		if (!Files.isRegularFile(file)) {
			return;
		}
		Properties props = new Properties();
		String text = new String(read(file, Files::readAllBytes), StandardCharsets.UTF_8);
		try {
			props.load(new StringReader(text));
		} catch (IllegalArgumentException e) {
			throw new IOException("can't read " + file + ": " + e.getMessage(), e);
		}
		for (String key : props.stringPropertyNames()) {
			entries.put("server.properties/" + key, Fingerprints.sha256(props.getProperty(key).getBytes(StandardCharsets.UTF_8)));
		}
	}

	private static List<Path> regularFiles(Path root) throws IOException {
		return read(root, Fingerprints::regularFiles);
	}

	private static List<Path> list(Path root) throws IOException {
		return read(root, dir -> {
			try (Stream<Path> children = Files.list(dir)) {
				return children.sorted().toList();
			} catch (UncheckedIOException e) {
				throw e.getCause();
			}
		});
	}

	private interface IoFunction<T> {
		T apply(Path path) throws IOException;
	}

	/** Every read goes through here, so the exception always names the file. */
	private static <T> T read(Path path, IoFunction<T> reader) throws IOException {
		try {
			return reader.apply(path);
		} catch (IOException e) {
			throw new IOException("can't read " + path + ": " + e.getMessage(), e);
		}
	}
}
