package com.wulfic.commitment.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C.2: the seed, read from real files: world_gen_settings.dat written by the 26.1.2 and 26.3 dev servers, and the
 * level.dat of a 1.21.11 dev server world (../enchanting-2), where 1.21.11 keeps it.
 */
class WorldSeedTest {
	@TempDir
	Path world;

	@Test
	void readsTheSeedOfA2612World() throws IOException {
		place(fixture("26.1.2.dat"));
		assertEquals(OptionalLong.of(7878231855728691869L), WorldSeed.read(world));
	}

	@Test
	void readsTheSeedOfA263World() throws IOException {
		place(fixture("26.3.dat"));
		assertEquals(OptionalLong.of(-7577260931055116372L), WorldSeed.read(world));
	}

	@Test
	void readsTheSeedOfA12111WorldFromItsLevelDat() throws IOException {
		placeLevelDat(fixture("1.21.11-level.dat"));
		assertEquals(OptionalLong.of(-1198787977897619755L), WorldSeed.read(world));
	}

	@Test
	void the26xFileWinsOverLevelDat() throws IOException {
		placeLevelDat(fixture("1.21.11-level.dat"));
		place(fixture("26.3.dat"));
		assertEquals(OptionalLong.of(-7577260931055116372L), WorldSeed.read(world));
	}

	@Test
	void aLevelDatWithoutWorldGenSettingsGivesNoSeed() throws IOException {
		placeLevelDat(gzip(nbt(out -> {
			compound(out, "Data");
			out.writeByte(4);
			name(out, "seed");
			out.writeLong(5L);
			out.writeByte(0);
		})));
		assertEquals(OptionalLong.empty(), WorldSeed.read(world));
	}

	@Test
	void noFileNoSeed() {
		assertEquals(OptionalLong.empty(), WorldSeed.read(world));
	}

	@Test
	void garbageAndTruncatedFilesGiveNoSeed() throws IOException {
		place("not gzip".getBytes(StandardCharsets.US_ASCII));
		assertEquals(OptionalLong.empty(), WorldSeed.read(world));
		byte[] real = fixture("26.3.dat");
		byte[] nbt = new GZIPInputStream(new ByteArrayInputStream(real)).readAllBytes();
		place(gzip(Arrays.copyOf(nbt, 20)));
		assertEquals(OptionalLong.empty(), WorldSeed.read(world));
	}

	@Test
	void aSeedThatIsntALongIsNoSeed() throws IOException {
		assertEquals(OptionalLong.empty(), WorldSeed.parse(gzip(nbt(out -> {
			compound(out, "data");
			out.writeByte(8);
			name(out, "seed");
			name(out, "12345");
			out.writeByte(0);
		})), "data", "seed"));
	}

	@Test
	void otherTagsBeforeTheSeedAreSkipped() throws IOException {
		assertEquals(OptionalLong.of(99L), WorldSeed.parse(gzip(nbt(out -> {
			out.writeByte(3);
			name(out, "DataVersion");
			out.writeInt(4000);
			compound(out, "data");
			out.writeByte(9);
			name(out, "list");
			out.writeByte(10);
			out.writeInt(1);
			out.writeByte(1);
			name(out, "b");
			out.writeByte(1);
			out.writeByte(0);
			out.writeByte(12);
			name(out, "longs");
			out.writeInt(2);
			out.writeLong(1);
			out.writeLong(2);
			out.writeByte(4);
			name(out, "seed");
			out.writeLong(99L);
			out.writeByte(0);
		})), "data", "seed"));
	}

	@Test
	void aRootThatIsntACompoundIsRefused() throws IOException {
		assertThrows(IOException.class, () -> WorldSeed.parse(gzip(new byte[] {8, 0, 0, 0, 0}), "data", "seed"));
	}

	private interface Body {
		void write(DataOutputStream out) throws IOException;
	}

	/** A root compound named "" holding {@code body}, then its end tag. */
	private static byte[] nbt(Body body) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(10);
			name(out, "");
			body.write(out);
			out.writeByte(0);
		}
		return bytes.toByteArray();
	}

	private static void compound(DataOutputStream out, String name) throws IOException {
		out.writeByte(10);
		name(out, name);
	}

	private static void name(DataOutputStream out, String name) throws IOException {
		byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
		out.writeShort(bytes.length);
		out.write(bytes);
	}

	private static byte[] gzip(byte[] bytes) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
			zip.write(bytes);
		}
		return out.toByteArray();
	}

	private void place(byte[] bytes) throws IOException {
		Path file = world.resolve(WorldSeed.FILE);
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private void placeLevelDat(byte[] bytes) throws IOException {
		Files.write(world.resolve(WorldSeed.LEVEL_DAT), bytes);
	}

	private static byte[] fixture(String name) throws IOException {
		try (InputStream in = WorldSeedTest.class.getResourceAsStream("/world-seed/" + name)) {
			assertNotNull(in, "fixture world-seed/" + name + " is missing from the test classpath");
			return in.readAllBytes();
		}
	}
}
