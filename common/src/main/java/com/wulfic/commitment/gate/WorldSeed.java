package com.wulfic.commitment.gate;

import com.wulfic.commitment.Commitment;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.zip.GZIPInputStream;

/**
 * A world's seed, read before the world loads, from {@code data/minecraft/world_gen_settings.dat}: on 26.x the seed is
 * saved data of its own, gzip NBT shaped {@code {data:{seed:<long>, ...}, DataVersion:<int>}}. Read here rather than
 * through vanilla's {@code NbtIo}, whose signatures change between 26.x nodes. Read-only, never written.
 */
public final class WorldSeed {
	public static final String FILE = "data/minecraft/world_gen_settings.dat";
	/** The real file is a few hundred bytes; this only stops a crafted gzip from filling memory. */
	private static final int MAX_DECODED = 4 * 1024 * 1024;
	private static final int MAX_DEPTH = 64;

	private static final byte END = 0;
	private static final byte LONG = 4;
	private static final byte COMPOUND = 10;

	private WorldSeed() {
	}

	/** The seed, or empty when the file is missing or can't be read (logged): the gate then can't match by seed. */
	public static OptionalLong read(Path worldDir) {
		Path file = worldDir.resolve(FILE);
		try {
			return parse(Files.readAllBytes(file));
		} catch (NoSuchFileException e) {
			Commitment.LOGGER.warn("Commitment: {} has no {}; its seed is unknown", worldDir.getFileName(), FILE);
			return OptionalLong.empty();
		} catch (IOException e) {
			Commitment.LOGGER.warn("Commitment: can't read the seed from {} ({}); it's unknown", file, e.getMessage());
			return OptionalLong.empty();
		}
	}

	/** {@code data.seed} of the gzip NBT, or empty when it isn't there or isn't a long. */
	static OptionalLong parse(byte[] gzip) throws IOException {
		byte[] nbt;
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
			nbt = in.readNBytes(MAX_DECODED + 1);
		}
		if (nbt.length > MAX_DECODED) {
			throw new IOException("larger than " + MAX_DECODED + " bytes");
		}
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(nbt));
		if (in.readByte() != COMPOUND) {
			throw new IOException("the root isn't a compound");
		}
		skipString(in);
		// Root compound: find "data", a compound; in it, "seed", a long.
		for (byte type; (type = in.readByte()) != END; ) {
			String name = readString(in);
			if (type == COMPOUND && name.equals("data")) {
				for (byte inner; (inner = in.readByte()) != END; ) {
					String key = readString(in);
					if (inner == LONG && key.equals("seed")) {
						return OptionalLong.of(in.readLong());
					}
					skip(in, inner, 1);
				}
				return OptionalLong.empty();
			}
			skip(in, type, 1);
		}
		return OptionalLong.empty();
	}

	private static void skip(DataInputStream in, byte type, int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("nested deeper than " + MAX_DEPTH);
		}
		switch (type) {
			case 1 -> skipBytes(in, 1);
			case 2 -> skipBytes(in, 2);
			case 3, 5 -> skipBytes(in, 4);
			case 4, 6 -> skipBytes(in, 8);
			case 7 -> skipBytes(in, (long) length(in));
			case 8 -> skipString(in);
			case 9 -> {
				byte element = in.readByte();
				int count = length(in);
				for (int i = 0; i < count; i++) {
					skip(in, element, depth + 1);
				}
			}
			case 10 -> {
				for (byte inner; (inner = in.readByte()) != END; ) {
					skipString(in);
					skip(in, inner, depth + 1);
				}
			}
			case 11 -> skipBytes(in, 4L * length(in));
			case 12 -> skipBytes(in, 8L * length(in));
			default -> throw new IOException("unknown NBT tag type " + type);
		}
	}

	private static int length(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0) {
			throw new IOException("negative length " + length);
		}
		return length;
	}

	private static void skipBytes(DataInputStream in, long count) throws IOException {
		if (count > in.available()) {
			throw new EOFException("needs " + count + " bytes, " + in.available() + " left");
		}
		in.skipNBytes(count);
	}

	private static void skipString(DataInputStream in) throws IOException {
		skipBytes(in, in.readUnsignedShort());
	}

	/** NBT names are modified UTF-8; the two names looked for here are ASCII, which reads the same either way. */
	private static String readString(DataInputStream in) throws IOException {
		int length = in.readUnsignedShort();
		if (length > in.available()) {
			throw new EOFException("a name of " + length + " bytes, " + in.available() + " left");
		}
		return new String(in.readNBytes(length), StandardCharsets.UTF_8);
	}
}
