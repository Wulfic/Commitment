package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B.6: the generated guard pack against its golden files, its marker, and the writer that never overwrites a stranger. */
class GuardPackTest {
	/** The lock id the golden files in {@code src/test/resources/guard-pack/} were written for. */
	static final UUID GOLDEN_ID = UUID.fromString("00000000-0000-4000-8000-00000000c0de");
	static final UUID OTHER_ID = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

	@TempDir
	Path world;

	@Test
	void generatedPackMatchesTheGoldenFiles() throws IOException, URISyntaxException {
		URL root = GuardPackTest.class.getResource("/guard-pack");
		assertNotNull(root, "the golden files are missing from the test classpath");
		Path golden = Path.of(root.toURI());
		SortedMap<String, String> expected = new TreeMap<>();
		try (Stream<Path> files = Files.walk(golden)) {
			for (Path f : files.filter(Files::isRegularFile).toList()) {
				expected.put(golden.relativize(f).toString().replace('\\', '/'), Files.readString(f, StandardCharsets.UTF_8));
			}
		}
		assertEquals(6, expected.size(), "golden files: " + expected.keySet());
		assertEquals(expected, GuardPack.files(GOLDEN_ID));
	}

	@Test
	void zipHoldsExactlyTheFilesAndIsTheSameEveryTime() throws IOException {
		byte[] zip = GuardPack.build(GOLDEN_ID);
		assertEquals(GuardPack.files(GOLDEN_ID), unzip(zip));
		assertArrayEquals(zip, GuardPack.build(GOLDEN_ID));
	}

	@Test
	void markerNamesTheLock() {
		assertEquals(Optional.of(GOLDEN_ID), GuardPack.lockIdOf(GuardPack.build(GOLDEN_ID)));
		assertEquals(Optional.of(OTHER_ID), GuardPack.lockIdOf(GuardPack.build(OTHER_ID)));
	}

	@Test
	void lookAlikeWithoutTheMarkerIsNotOurs() throws IOException {
		SortedMap<String, String> files = new TreeMap<>(GuardPack.files(GOLDEN_ID));
		files.remove(GuardPack.MARKER_ENTRY);
		assertEquals(Optional.empty(), GuardPack.lockIdOf(zip(files)));
	}

	@Test
	void doctoredMarkersAreNotOurs() throws IOException {
		String lock = "lock=" + GOLDEN_ID;
		for (String marker : new String[] {
				lock + "\n",
				"marker=CMTGUARD 2\n" + lock + "\n",
				GuardPack.MARKER + "\n",
				GuardPack.MARKER + "\nlock=not-a-uuid\n",
				GuardPack.MARKER + "\nlock=0-0-4000-8000-c0de\n",
				GuardPack.MARKER + "\n" + lock + "\nlock=" + OTHER_ID + "\n",
				GuardPack.MARKER + "\n" + lock + "\nextra=1\n",
				GuardPack.MARKER + "\n" + lock + "\n" + "#".repeat(5000) + "\n"}) {
			SortedMap<String, String> files = new TreeMap<>(GuardPack.files(GOLDEN_ID));
			files.put(GuardPack.MARKER_ENTRY, marker);
			assertEquals(Optional.empty(), GuardPack.lockIdOf(zip(files)), marker);
		}
	}

	@Test
	void notAZipIsNotOurs() {
		assertEquals(Optional.empty(), GuardPack.lockIdOf("CMTGUARD".getBytes(StandardCharsets.US_ASCII)));
		assertEquals(Optional.empty(), GuardPack.lockIdOf(new byte[0]));
	}

	@Test
	void readFindsTheLockIdOnlyInOurPack() throws IOException {
		assertEquals(Optional.empty(), GuardPack.read(world));
		put(GuardPack.build(GOLDEN_ID));
		assertEquals(Optional.of(GOLDEN_ID), GuardPack.read(world));

		SortedMap<String, String> files = new TreeMap<>(GuardPack.files(GOLDEN_ID));
		files.remove(GuardPack.MARKER_ENTRY);
		put(zip(files));
		assertEquals(Optional.empty(), GuardPack.read(world));

		put(new byte[GuardPack.MAX_FILE + 1]);
		assertEquals(Optional.empty(), GuardPack.read(world));
	}

	@Test
	void aFolderAtThePathIsNotAPack() throws IOException {
		Files.createDirectories(world.resolve(GuardPack.FILE).resolve("data"));
		assertEquals(Optional.empty(), GuardPack.read(world));
		assertInstanceOf(GuardPack.Written.Refused.class, GuardPack.write(world, GOLDEN_ID));
		assertTrue(Files.isDirectory(world.resolve(GuardPack.FILE).resolve("data")));
	}

	@Test
	void writeCreatesThenLeavesItsOwnPackAlone() throws IOException {
		assertInstanceOf(GuardPack.Written.Wrote.class, GuardPack.write(world, GOLDEN_ID));
		assertArrayEquals(GuardPack.build(GOLDEN_ID), Files.readAllBytes(world.resolve(GuardPack.FILE)));
		assertInstanceOf(GuardPack.Written.Unchanged.class, GuardPack.write(world, GOLDEN_ID));
	}

	@Test
	void writeReplacesAnOlderPackOfTheSameLock() throws IOException {
		SortedMap<String, String> older = new TreeMap<>(GuardPack.files(GOLDEN_ID));
		older.put("data/commitment_guard/function/penalty.mcfunction", "say old\n");
		put(zip(older));
		assertInstanceOf(GuardPack.Written.Wrote.class, GuardPack.write(world, GOLDEN_ID));
		assertArrayEquals(GuardPack.build(GOLDEN_ID), Files.readAllBytes(world.resolve(GuardPack.FILE)));
	}

	@Test
	void writeRefusesALookAlikeAndLeavesItOnDisk() throws IOException {
		SortedMap<String, String> files = new TreeMap<>(GuardPack.files(GOLDEN_ID));
		files.remove(GuardPack.MARKER_ENTRY);
		byte[] lookAlike = zip(files);
		put(lookAlike);
		assertInstanceOf(GuardPack.Written.Refused.class, GuardPack.write(world, GOLDEN_ID));
		assertArrayEquals(lookAlike, Files.readAllBytes(world.resolve(GuardPack.FILE)));
	}

	@Test
	void writeRefusesAnotherLocksPackAndLeavesItOnDisk() throws IOException {
		byte[] other = GuardPack.build(OTHER_ID);
		put(other);
		GuardPack.Written result = GuardPack.write(world, GOLDEN_ID);
		assertInstanceOf(GuardPack.Written.Refused.class, result);
		assertTrue(((GuardPack.Written.Refused) result).reason().contains(OTHER_ID.toString()), result.toString());
		assertArrayEquals(other, Files.readAllBytes(world.resolve(GuardPack.FILE)));
	}

	private void put(byte[] bytes) throws IOException {
		Path file = world.resolve(GuardPack.FILE);
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	static byte[] zip(Map<String, String> files) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (var f : files.entrySet()) {
				zip.putNextEntry(new ZipEntry(f.getKey()));
				zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	private static SortedMap<String, String> unzip(byte[] bytes) throws IOException {
		SortedMap<String, String> files = new TreeMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
				files.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		return files;
	}
}
