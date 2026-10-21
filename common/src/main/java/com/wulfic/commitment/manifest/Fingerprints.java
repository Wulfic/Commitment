package com.wulfic.commitment.manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.wulfic.commitment.Commitment;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Content hashes. Config files are hashed after normalization, so a mod that rewrites its file with the same
 * settings in another order doesn't count as a change. Everything else is hashed byte for byte.
 */
final class Fingerprints {
	/** Hashed with comments, blank lines and trailing whitespace dropped. No TOML parser is on every loader's path. */
	private static final Set<String> TEXT = Set.of(
			"toml", "cfg", "conf", "ini", "txt", "yml", "yaml", "json5", "jsonc", "hocon", "snbt");

	private Fingerprints() {
	}

	/** {@code sha256:<hex>} of a config file's normalized content. */
	static String config(String relPath, byte[] raw) {
		String ext = extension(relPath);
		if (ext.equals("json")) {
			String json = text(raw);
			try {
				return sha256(canonicalJson(JsonParser.parseString(json)).getBytes(StandardCharsets.UTF_8));
			} catch (JsonParseException e) {
				Commitment.LOGGER.info("Commitment: {} isn't valid JSON ({}); comparing it as text", relPath, e.getMessage());
				return sha256(stripComments(json).getBytes(StandardCharsets.UTF_8));
			}
		}
		if (ext.equals("properties")) {
			return sha256(sortedProperties(text(raw), relPath).getBytes(StandardCharsets.UTF_8));
		}
		if (TEXT.contains(ext)) {
			return sha256(stripComments(text(raw)).getBytes(StandardCharsets.UTF_8));
		}
		return sha256(raw);
	}

	/**
	 * {@code sha256:<hex>} of a file byte for byte, or of a folder as its sorted (relative path, file hash) pairs, so
	 * a renamed, added or removed file inside the folder counts too.
	 */
	static String fileOrFolder(Path path) throws IOException {
		if (!Files.isDirectory(path)) {
			return sha256(Files.readAllBytes(path));
		}
		Map<String, String> files = new TreeMap<>();
		for (Path file : regularFiles(path)) {
			files.put(relative(path, file), sha256(Files.readAllBytes(file)));
		}
		StringBuilder listing = new StringBuilder();
		files.forEach((rel, hash) -> listing.append(rel).append('\0').append(hash).append('\n'));
		return sha256(listing.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Every file under {@code root}, sorted. A walk fails mid-stream as UncheckedIOException; this rethrows the cause. */
	static List<Path> regularFiles(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).sorted().toList();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	static String sha256(byte[] bytes) {
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			// Every JRE must ship SHA-256 (MessageDigest's javadoc), so this is a broken runtime, not a bad input.
			throw new IllegalStateException("SHA-256 is missing from this JRE", e);
		}
	}

	/** {@code root}-relative path with {@code /} separators on every OS, so a manifest written on Windows matches on Linux. */
	static String relative(Path root, Path file) {
		return root.relativize(file).toString().replace('\\', '/');
	}

	/** JSON with every object's keys sorted and no whitespace. */
	static String canonicalJson(JsonElement element) {
		StringBuilder out = new StringBuilder();
		appendCanonical(element, out);
		return out.toString();
	}

	private static void appendCanonical(JsonElement element, StringBuilder out) {
		if (element.isJsonObject()) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<String, JsonElement> e : new TreeMap<>(element.getAsJsonObject().asMap()).entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				out.append(new JsonPrimitive(e.getKey())).append(':');
				appendCanonical(e.getValue(), out);
			}
			out.append('}');
		} else if (element.isJsonArray()) {
			out.append('[');
			boolean first = true;
			for (JsonElement e : element.getAsJsonArray()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				appendCanonical(e, out);
			}
			out.append(']');
		} else {
			out.append(element);
		}
	}

	private static String sortedProperties(String text, String relPath) {
		Properties props = new Properties();
		try {
			props.load(new StringReader(text));
		} catch (IOException | IllegalArgumentException e) {
			// A StringReader can't fail to read; this is a malformed \\u escape.
			Commitment.LOGGER.info("Commitment: {} isn't valid properties ({}); comparing it as text", relPath, e.getMessage());
			return stripComments(text);
		}
		StringBuilder out = new StringBuilder();
		new TreeMap<>(props).forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
		return out.toString();
	}

	/** Drops {@code #}, {@code //} and {@code ;} comment lines, blank lines and trailing whitespace; LF line ends. */
	static String stripComments(String text) {
		StringBuilder out = new StringBuilder();
		for (String line : text.split("\\R")) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//") || trimmed.startsWith(";")) {
				continue;
			}
			out.append(line.stripTrailing()).append('\n');
		}
		return out.toString();
	}

	private static String text(byte[] raw) {
		String text = new String(raw, StandardCharsets.UTF_8);
		return text.startsWith("﻿") ? text.substring(1) : text;
	}

	private static String extension(String relPath) {
		String name = relPath.substring(relPath.lastIndexOf('/') + 1);
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
