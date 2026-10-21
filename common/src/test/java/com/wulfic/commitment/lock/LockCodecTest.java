package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wulfic.commitment.manifest.Manifest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** B.3: a record survives the round trip, and any edit, truncation or foreign file decodes as tampered. */
class LockCodecTest {
	static final UUID ID = UUID.fromString("0f6c9a3e-1b2d-4c5e-8f70-9a1b2c3d4e5f");

	static LockRecord sample() {
		TreeMap<String, String> manifest = new TreeMap<>();
		manifest.put("minecraft", "26.3");
		manifest.put("mod/sodium", "0.9.3 sha256:" + "a".repeat(64));
		manifest.put("config/ünïcødé.toml", "sha256:" + "b".repeat(64));
		TreeMap<String, String> settings = new TreeMap<>();
		settings.put("difficulty", "hard");
		settings.put("gamerule/keep_inventory", "false");
		return new LockRecord(ID, 7, -4242L, "Mÿ Wörld", 1_760_000_000_000L, OptionalLong.of(1_760_086_400_000L), false,
				new Manifest(manifest), settings);
	}

	@Test
	void aRecordSurvivesTheRoundTrip() {
		LockRecord r = sample();
		assertEquals(new LockCodec.Decoded.Valid(r), LockCodec.decode(LockCodec.encode(r)));
	}

	@Test
	void noTimerAndTheTamperedFlagSurviveTheRoundTrip() {
		LockRecord r = LockRecord.tampered(ID, 3, 9, "w", 5);
		assertEquals(new LockCodec.Decoded.Valid(r), LockCodec.decode(LockCodec.encode(r)));
	}

	@Test
	void everySingleByteEditIsCaught() throws IOException {
		byte[] signed = gunzip(LockCodec.encode(sample()));
		List<Integer> missed = new ArrayList<>();
		for (int i = 0; i < signed.length; i++) {
			byte[] edited = signed.clone();
			edited[i] ^= 0x01;
			if (!(LockCodec.decode(gzip(edited)) instanceof LockCodec.Decoded.Tampered)) {
				missed.add(i);
			}
		}
		assertEquals(List.of(), missed, "byte offsets whose edit went unnoticed, of " + signed.length);
	}

	@Test
	void aTruncatedRecordIsTampered() throws IOException {
		byte[] signed = gunzip(LockCodec.encode(sample()));
		for (int length : new int[] {0, 5, 30, signed.length - 1}) {
			assertInstanceOf(LockCodec.Decoded.Tampered.class, LockCodec.decode(gzip(Arrays.copyOf(signed, length))), "length " + length);
		}
		byte[] file = LockCodec.encode(sample());
		assertInstanceOf(LockCodec.Decoded.Tampered.class, LockCodec.decode(Arrays.copyOf(file, file.length / 2)));
	}

	@Test
	void anythingElseIsTampered() throws IOException {
		assertInstanceOf(LockCodec.Decoded.Tampered.class, LockCodec.decode(new byte[0]));
		assertInstanceOf(LockCodec.Decoded.Tampered.class, LockCodec.decode("{\"locked\": false}".getBytes()));
		assertInstanceOf(LockCodec.Decoded.Tampered.class, LockCodec.decode(gzip("CMTLOCK but nothing after".getBytes())));
	}

	@Test
	void aHugeGzipIsRefusedWithoutReadingItAll() throws IOException {
		assertInstanceOf(LockCodec.Decoded.Tampered.class, LockCodec.decode(gzip(new byte[17 * 1024 * 1024])));
	}

	@Test
	void theDeleterOnlyRecognisesItsOwnLock() {
		byte[] file = LockCodec.encode(sample());
		assertTrue(LockCodec.isOurs(file, ID));
		assertFalse(LockCodec.isOurs(file, UUID.randomUUID()));
		assertFalse(LockCodec.isOurs("not ours".getBytes(), ID));
	}

	static byte[] gunzip(byte[] file) throws IOException {
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(file))) {
			return in.readAllBytes();
		}
	}

	static byte[] gzip(byte[] bytes) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
			zip.write(bytes);
		}
		return out.toByteArray();
	}
}
