package com.wulfic.commitment.lock;

import com.wulfic.commitment.manifest.Manifest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The lock record on disk: gzip of {@code CMTLOCK}, a format version, the lock id, the fields, and an HMAC-SHA256 over
 * all of that. A hand-edited, truncated or foreign-format file decodes as {@link Decoded.Tampered}, never as a record.
 * Commitment's own format rather than NBT, so it compiles the same on every node (decisions.md 2026-10-21).
 */
public final class LockCodec {
	/** Also the marker the lift-lock deleter checks before it removes a file: only files that start with it are ours. */
	static final byte[] MAGIC = "CMTLOCK".getBytes(StandardCharsets.US_ASCII);
	static final int FORMAT = 1;
	private static final int MAC_LENGTH = 32;
	/** Far above any real setup's record; stops a crafted gzip from filling memory. */
	private static final int MAX_DECODED = 16 * 1024 * 1024;

	/**
	 * Not a secret: anyone can read it out of the jar, and the README says so. It makes a hand-edited record fail its
	 * check, which is the friction the owner asked for (owner, 2026-10-21: "inconvenient, not impossible").
	 */
	private static final byte[] JAR_KEY = sha256("Commitment lock record: friction, not security".getBytes(StandardCharsets.UTF_8));

	public sealed interface Decoded {
		record Valid(LockRecord record) implements Decoded {
		}

		record Tampered(String reason) implements Decoded {
		}
	}

	private LockCodec() {
	}

	public static byte[] encode(LockRecord r) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(body)) {
			out.write(MAGIC);
			out.writeInt(FORMAT);
			out.writeLong(r.lockId().getMostSignificantBits());
			out.writeLong(r.lockId().getLeastSignificantBits());
			out.writeLong(r.revision());
			out.writeLong(r.seed());
			writeString(out, r.worldFolder());
			out.writeLong(r.committedAt());
			out.writeBoolean(r.unlockRequestedAt().isPresent());
			out.writeLong(r.unlockRequestedAt().orElse(0));
			out.writeBoolean(r.tampered());
			writeMap(out, r.manifest().entries());
			writeMap(out, r.worldSettings());
		} catch (IOException e) {
			throw new IllegalStateException("a ByteArrayOutputStream can't fail to write", e);
		}
		byte[] payload = body.toByteArray();
		byte[] signed = Arrays.copyOf(payload, payload.length + MAC_LENGTH);
		System.arraycopy(mac(r.lockId(), payload), 0, signed, payload.length, MAC_LENGTH);
		ByteArrayOutputStream gz = new ByteArrayOutputStream();
		try (GZIPOutputStream zip = new GZIPOutputStream(gz)) {
			zip.write(signed);
		} catch (IOException e) {
			throw new IllegalStateException("a ByteArrayOutputStream can't fail to write", e);
		}
		return gz.toByteArray();
	}

	public static Decoded decode(byte[] file) {
		byte[] signed;
		try {
			signed = gunzip(file);
		} catch (IOException e) {
			return new Decoded.Tampered("not a Commitment record (" + e.getMessage() + ")");
		}
		int headerLength = MAGIC.length + 4 + 16;
		if (signed.length < headerLength + MAC_LENGTH || !Arrays.equals(signed, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
			return new Decoded.Tampered("not a Commitment record (no marker)");
		}
		ByteBuffer header = ByteBuffer.wrap(signed, MAGIC.length, 4 + 16);
		int format = header.getInt();
		if (format != FORMAT) {
			return new Decoded.Tampered("record format " + format + ", this Commitment reads " + FORMAT);
		}
		UUID lockId = new UUID(header.getLong(), header.getLong());
		byte[] payload = Arrays.copyOf(signed, signed.length - MAC_LENGTH);
		byte[] mac = Arrays.copyOfRange(signed, signed.length - MAC_LENGTH, signed.length);
		if (!MessageDigest.isEqual(mac, mac(lockId, payload))) {
			return new Decoded.Tampered("signature doesn't match: the record was edited");
		}
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload, headerLength, payload.length - headerLength))) {
			long revision = in.readLong();
			long seed = in.readLong();
			String folder = readString(in);
			long committedAt = in.readLong();
			boolean timer = in.readBoolean();
			long requestedAt = in.readLong();
			boolean tampered = in.readBoolean();
			SortedMap<String, String> manifest = readMap(in);
			SortedMap<String, String> settings = readMap(in);
			if (in.available() != 0) {
				return new Decoded.Tampered("trailing bytes after the record");
			}
			return new Decoded.Valid(new LockRecord(lockId, revision, seed, folder, committedAt,
					timer ? OptionalLong.of(requestedAt) : OptionalLong.empty(), tampered, new Manifest(manifest), settings));
		} catch (IOException | RuntimeException e) {
			// Signed by our key yet unparseable: a bug in an older writer, or a forged MAC. Either way, not trusted.
			return new Decoded.Tampered("signed but unreadable (" + e + ")");
		}
	}

	/** True when {@code file} is a record of ours for this lock id: the deleter's check before it removes anything. */
	static boolean isOurs(byte[] file, UUID lockId) {
		return decode(file) instanceof Decoded.Valid v && v.record().lockId().equals(lockId);
	}

	private static byte[] mac(UUID lockId, byte[] payload) {
		ByteBuffer id = ByteBuffer.allocate(16).putLong(lockId.getMostSignificantBits()).putLong(lockId.getLeastSignificantBits());
		byte[] keyMaterial = Arrays.copyOf(JAR_KEY, JAR_KEY.length + 16);
		System.arraycopy(id.array(), 0, keyMaterial, JAR_KEY.length, 16);
		try {
			Mac hmac = Mac.getInstance("HmacSHA256");
			hmac.init(new SecretKeySpec(sha256(keyMaterial), "HmacSHA256"));
			return hmac.doFinal(payload);
		} catch (GeneralSecurityException e) {
			// Every JRE must ship HmacSHA256 (the Mac javadoc), so this is a broken runtime, not a bad record.
			throw new IllegalStateException("HmacSHA256 is missing from this JRE", e);
		}
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 is missing from this JRE", e);
		}
	}

	private static byte[] gunzip(byte[] file) throws IOException {
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(file))) {
			byte[] out = in.readNBytes(MAX_DECODED + 1);
			if (out.length > MAX_DECODED) {
				throw new IOException("larger than " + MAX_DECODED + " bytes");
			}
			return out;
		}
	}

	private static void writeString(DataOutputStream out, String s) throws IOException {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	private static String readString(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > in.available()) {
			throw new EOFException("string of " + length + " bytes with " + in.available() + " left");
		}
		return new String(in.readNBytes(length), StandardCharsets.UTF_8);
	}

	private static void writeMap(DataOutputStream out, SortedMap<String, String> map) throws IOException {
		out.writeInt(map.size());
		for (var e : map.entrySet()) {
			writeString(out, e.getKey());
			writeString(out, e.getValue());
		}
	}

	private static SortedMap<String, String> readMap(DataInputStream in) throws IOException {
		int size = in.readInt();
		if (size < 0 || size > in.available()) {
			throw new EOFException("map of " + size + " entries with " + in.available() + " bytes left");
		}
		SortedMap<String, String> map = new TreeMap<>();
		for (int i = 0; i < size; i++) {
			map.put(readString(in), readString(in));
		}
		return map;
	}
}
