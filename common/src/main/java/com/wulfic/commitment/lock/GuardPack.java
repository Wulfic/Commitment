package com.wulfic.commitment.lock;

import com.wulfic.commitment.Commitment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The guard datapack, {@code <world>/datapacks/commitment_guard.zip}: plain vanilla functions, so it still runs after the
 * mod has been deleted. The mod sets {@code alive} in storage {@code commitment:guard} every tick. Every 40 game ticks
 * the pack checks for it and clears it; when it was missing, every player gets 3 s of blindness, slowness, mining
 * fatigue and resistance, and the title says to reinstall Commitment. The effects run out by themselves once the mod is
 * back, so the pack changes nothing that lasts.
 */
public final class GuardPack {
	/** Relative to the world folder. */
	public static final String FILE = "datapacks/commitment_guard.zip";
	/** The entry that says the pack is ours, and which lock it guards. */
	static final String MARKER_ENTRY = "commitment.lock";
	static final String MARKER = "marker=CMTGUARD 1";
	/** The real pack is a few KB. A bigger file at that path isn't ours, and isn't read whole. */
	static final int MAX_FILE = 1024 * 1024;
	private static final int MAX_MARKER = 4096;
	/** A fixed local time, so the zip's bytes depend on neither the clock nor the time zone. */
	private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

	public sealed interface Written {
		record Wrote() implements Written {
		}

		record Unchanged() implements Written {
		}

		/** A file Commitment can't prove is its own pack for this lock is in the way: it's left alone. */
		record Refused(String reason) implements Written {
		}
	}

	private GuardPack() {
	}

	/** The pack's entries, path → text. Formats and command syntax read from every 26.x jar (plan steps 0.2 and B.6). */
	public static SortedMap<String, String> files(UUID lockId) {
		Objects.requireNonNull(lockId, "lockId");
		SortedMap<String, String> files = new TreeMap<>();
		files.put("pack.mcmeta", """
				{"pack":{"description":"Commitment guard: this world is committed","min_format":101,"max_format":121}}
				""");
		files.put(MARKER_ENTRY, """
				# Written by the Commitment mod: this world is committed, and this pack guards it.
				%s
				lock=%s
				""".formatted(MARKER, lockId));
		files.put("data/minecraft/tags/function/load.json", """
				{"values":["commitment_guard:load"]}
				""");
		files.put("data/commitment_guard/function/load.mcfunction", """
				# Runs on every (re)load. A stale "alive" from the last save must not count as the mod being here.
				data remove storage commitment:guard alive
				data remove storage commitment:guard warned
				schedule function commitment_guard:check 40t replace
				""");
		files.put("data/commitment_guard/function/check.mcfunction", """
				# Every 40 game ticks. The mod sets "alive" every tick while it runs, so a missing "alive" means it's gone.
				execute unless data storage commitment:guard alive run function commitment_guard:penalty
				data remove storage commitment:guard alive
				schedule function commitment_guard:check 40t replace
				""");
		files.put("data/commitment_guard/function/penalty.mcfunction", """
				# Commitment is missing. Each effect lasts 3 s, so they run out by themselves once it's back.
				execute unless data storage commitment:guard warned run say Commitment guard: Commitment is missing
				data modify storage commitment:guard warned set value 1b
				effect give @a minecraft:blindness 3 0 true
				effect give @a minecraft:slowness 3 255 true
				effect give @a minecraft:mining_fatigue 3 255 true
				effect give @a minecraft:resistance 3 255 true
				title @a times 0 50 10
				title @a title {"text":"This world is committed","color":"red"}
				title @a subtitle {"text":"Commitment is missing: reinstall it to play"}
				""");
		return Collections.unmodifiableSortedMap(files);
	}

	/** The pack as a zip. The same lock id always gives the same bytes, so {@link #write} can tell when nothing changed. */
	public static byte[] build(UUID lockId) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
			for (var file : files(lockId).entrySet()) {
				ZipEntry entry = new ZipEntry(file.getKey());
				entry.setTimeLocal(ENTRY_TIME);
				zip.putNextEntry(entry);
				zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		} catch (IOException e) {
			throw new IllegalStateException("a ByteArrayOutputStream can't fail to write", e);
		}
		return bytes.toByteArray();
	}

	/** The lock id a zip guards, or empty when it isn't Commitment's guard pack. Reads only the marker entry. */
	public static Optional<UUID> lockIdOf(byte[] zipBytes) {
		String marker = null;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
				if (!entry.getName().equals(MARKER_ENTRY)) {
					continue;
				}
				byte[] text = zip.readNBytes(MAX_MARKER + 1);
				if (text.length > MAX_MARKER) {
					return Optional.empty();
				}
				marker = new String(text, StandardCharsets.UTF_8);
			}
		} catch (IOException | IllegalArgumentException e) {
			// Not a zip, or a broken one: whatever it is, it isn't a pack Commitment wrote.
			return Optional.empty();
		}
		return marker == null ? Optional.empty() : parseMarker(marker);
	}

	/**
	 * The lock id in the world's guard pack, for {@link LockStore#load}. A file there that isn't ours counts as no pack.
	 *
	 * @throws IOException the file exists but can't be read; the caller keeps the world closed (fail closed)
	 */
	public static Optional<UUID> read(Path worldDir) throws IOException {
		Path file = worldDir.resolve(FILE);
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.empty();
		}
		try {
			Optional<String> notOurs = notOurs(file);
			if (notOurs.isPresent()) {
				Commitment.LOGGER.warn("Commitment: {} {}; it doesn't count as a guard pack", file, notOurs.get());
				return Optional.empty();
			}
			Optional<UUID> id = lockIdOf(Files.readAllBytes(file));
			if (id.isEmpty()) {
				Commitment.LOGGER.warn("Commitment: {} isn't Commitment's guard pack (no marker); it doesn't count as one", file);
			}
			return id;
		} catch (NoSuchFileException e) {
			return Optional.empty();
		} catch (IOException e) {
			throw new IOException("can't read the guard pack " + file + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Writes the pack for {@code lockId}, replacing only a pack of ours for the same lock. Anything else at that path is
	 * left alone and the write is refused: Commitment never overwrites a file it can't prove it wrote.
	 */
	public static Written write(Path worldDir, UUID lockId) throws IOException {
		Path file = worldDir.resolve(FILE);
		byte[] wanted = build(lockId);
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			Optional<String> notOurs = notOurs(file);
			if (notOurs.isPresent()) {
				return refused(file, notOurs.get());
			}
			byte[] have = Files.readAllBytes(file);
			Optional<UUID> id = lockIdOf(have);
			if (id.isEmpty()) {
				return refused(file, "isn't Commitment's guard pack (no marker)");
			}
			if (!id.get().equals(lockId)) {
				return refused(file, "guards lock " + id.get() + ", not " + lockId);
			}
			if (Arrays.equals(have, wanted)) {
				return new Written.Unchanged();
			}
		}
		LockStore.writeAtomically(file, wanted);
		Commitment.LOGGER.info("Commitment: wrote guard pack {} (lock {})", file, lockId);
		return new Written.Wrote();
	}

	/** Why an existing file at the guard path can't be ours, judged before reading it; empty when it may be. */
	static Optional<String> notOurs(Path file) throws IOException {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.of("isn't a regular file (a folder or a link)");
		}
		long size = Files.size(file);
		return size > MAX_FILE ? Optional.of("is " + size + " bytes, too large to be Commitment's guard pack") : Optional.empty();
	}

	private static Written refused(Path file, String reason) {
		Commitment.LOGGER.warn("Commitment: not writing the guard pack: {} {}; leaving it alone", file, reason);
		return new Written.Refused(file + " " + reason);
	}

	private static Optional<UUID> parseMarker(String text) {
		boolean marked = false;
		UUID lockId = null;
		for (String line : text.split("\n")) {
			line = line.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.equals(MARKER) && !marked) {
				marked = true;
			} else if (line.startsWith("lock=") && lockId == null) {
				String id = line.substring("lock=".length());
				try {
					lockId = UUID.fromString(id);
				} catch (IllegalArgumentException e) {
					return Optional.empty();
				}
				// UUID.fromString accepts "1-2-3-4-5"; only the form Commitment writes counts.
				if (!lockId.toString().equals(id)) {
					return Optional.empty();
				}
			} else {
				return Optional.empty();
			}
		}
		return marked ? Optional.ofNullable(lockId) : Optional.empty();
	}
}
