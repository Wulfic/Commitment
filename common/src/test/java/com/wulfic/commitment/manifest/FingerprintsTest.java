package com.wulfic.commitment.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FingerprintsTest {
	private static String config(String name, String content) {
		return Fingerprints.config(name, content.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void sha256IsTheKnownDigest() {
		assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Fingerprints.sha256(new byte[0]));
	}

	@Test
	void jsonIsComparedBySortedKeys() {
		assertEquals("{\"a\":1,\"b\":{\"c\":[3,1]}}",
				Fingerprints.canonicalJson(com.google.gson.JsonParser.parseString("{ \"b\": {\"c\": [3, 1]}, \"a\": 1 }")));
	}

	@Test
	void invalidJsonFallsBackToText() {
		assertEquals(config("x.json", "{ broken"), config("x.json", "# note\n{ broken\n"));
		assertNotEquals(config("x.json", "{ broken"), config("x.json", "{ broken differently"));
	}

	@Test
	void anUnknownExtensionIsComparedByteForByte() {
		assertNotEquals(config("x.dat", "a\n"), config("x.dat", "a\r\n"));
	}

	@Test
	void extensionsAreCaseInsensitive() {
		assertEquals(config("X.TOML", "a = 1\n"), config("X.TOML", "# c\na = 1\n"));
	}
}
