package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wulfic.commitment.lock.TrustedClock.Reading;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** B.5: internet time against local stub hosts, and the README's privacy note against the real host list. */
class InternetTimeTest {
	private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
	private final List<StubHost> stubs = new ArrayList<>();

	/**
	 * A raw-socket HTTP host with a canned reply. Not com.sun.net.httpserver: it stamps its own Date header on every
	 * reply, so a test couldn't send a wrong one or none.
	 */
	static final class StubHost implements AutoCloseable {
		final ServerSocket socket;
		final String reply;

		StubHost(String reply) throws IOException {
			this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			this.reply = reply;
			Thread t = new Thread(this::serve, "stub host " + socket.getLocalPort());
			t.setDaemon(true);
			t.start();
		}

		private void serve() {
			while (!socket.isClosed()) {
				try (Socket client = socket.accept()) {
					BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
					for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
						// Read the request up to its blank line.
					}
					if (reply == null) {
						Thread.sleep(10_000);
						continue;
					}
					OutputStream out = client.getOutputStream();
					out.write(reply.getBytes(StandardCharsets.US_ASCII));
					out.flush();
				} catch (IOException e) {
					// Closed by close() between tests, or the client hung up: either way this stub is done with it.
					if (socket.isClosed()) {
						return;
					}
				} catch (InterruptedException e) {
					return;
				}
			}
		}

		URI uri() {
			return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/");
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}

	@AfterEach
	void closeStubs() throws IOException {
		for (StubHost s : stubs) {
			s.close();
		}
	}

	private URI dated(Instant date) throws IOException {
		return host("HTTP/1.1 200 OK\r\nDate: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(date.atOffset(ZoneOffset.UTC))
				+ "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
	}

	private URI host(String reply) throws IOException {
		StubHost s = new StubHost(reply);
		stubs.add(s);
		return s.uri();
	}

	/** A port nothing listens on: bound, then closed. */
	private static URI refused() throws IOException {
		try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return URI.create("http://127.0.0.1:" + s.getLocalPort() + "/");
		}
	}

	private static Reading read(URI... hosts) {
		return new InternetTime(List.of(hosts), 1000, 500).read();
	}

	@Test
	void threeHostsThatAgreeGiveTheMiddleTime() throws IOException {
		Reading r = read(dated(NOW.plusSeconds(2)), dated(NOW), dated(NOW.plusSeconds(1)));
		assertEquals(new Reading.At(NOW.plusSeconds(1).toEpochMilli()), r);
	}

	@Test
	void oneHostFarOffIsOutvoted() throws IOException {
		Reading r = read(dated(NOW), dated(NOW.plusSeconds(48 * 3600)), dated(NOW.plusSeconds(30)));
		Reading.At at = assertInstanceOf(Reading.At.class, r);
		assertTrue(at.epochMillis() == NOW.toEpochMilli() || at.epochMillis() == NOW.plusSeconds(30).toEpochMilli(), r.toString());
	}

	@Test
	void twoHostsAreEnoughWhenTheThirdIsDown() throws IOException {
		assertInstanceOf(Reading.At.class, read(dated(NOW), refused(), dated(NOW.plusSeconds(240))));
	}

	@Test
	void twoHostsSixMinutesApartAreNotTrusted() throws IOException {
		Reading.Unavailable u = assertInstanceOf(Reading.Unavailable.class, read(dated(NOW), dated(NOW.plusSeconds(360)), refused()));
		assertTrue(u.reason().contains("disagree"), u.reason());
	}

	@Test
	void oneAnswerIsNotEnoughAndTheReasonNamesEachHost() throws IOException {
		Reading.Unavailable u = assertInstanceOf(Reading.Unavailable.class, read(dated(NOW), refused(), refused()));
		assertTrue(u.reason().startsWith("1 of 3 time servers answered"), u.reason());
		assertTrue(u.reason().contains("ConnectException"), u.reason());
	}

	@Test
	void offlineIsUnavailable() throws IOException {
		assertInstanceOf(Reading.Unavailable.class, read(refused(), refused(), refused()));
	}

	@Test
	void aHostThatHangsTimesOutAndTheOthersDecide() throws IOException {
		long start = System.nanoTime();
		Reading r = read(host(null), dated(NOW), dated(NOW));
		long tookMs = (System.nanoTime() - start) / 1_000_000;
		assertEquals(new Reading.At(NOW.toEpochMilli()), r);
		assertTrue(tookMs < 5000, "a hung host held the read for " + tookMs + " ms");
	}

	@Test
	void noDateHeaderOrAGarbledOneDoesntCount() throws IOException {
		URI none = host("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
		URI garbled = host("HTTP/1.1 200 OK\r\nDate: yesterday-ish\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
		Reading.Unavailable u = assertInstanceOf(Reading.Unavailable.class, read(none, garbled, dated(NOW)));
		assertTrue(u.reason().contains("no Date header") && u.reason().contains("unreadable Date header"), u.reason());
	}

	@Test
	void aRedirectIsNotFollowed() throws IOException {
		URI elsewhere = dated(NOW.plusSeconds(7 * 24 * 3600));
		URI redirecting = host("HTTP/1.1 302 Found\r\nDate: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.atOffset(ZoneOffset.UTC))
				+ "\r\nLocation: " + elsewhere + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
		assertEquals(new Reading.At(NOW.toEpochMilli()), read(redirecting, dated(NOW), refused()));
	}

	@Test
	void theRealHostsAreHttpsAndEnoughToOutvoteOne() {
		assertTrue(InternetTime.HOSTS.size() > InternetTime.NEEDED, "one bad host must not decide it");
		assertTrue(InternetTime.NEEDED >= 2);
		for (URI host : InternetTime.HOSTS) {
			assertEquals("https", host.getScheme(), host.toString());
		}
	}

	@Test
	void theReadmeNamesEveryHostTheModAsks() throws IOException {
		String root = System.getProperty("commitment.repoRoot");
		assertNotNull(root, "commitment.repoRoot is not set; run this through Gradle");
		String readme = Files.readString(Path.of(root, "README.md"), StandardCharsets.UTF_8);
		int privacy = readme.indexOf("## Privacy");
		assertTrue(privacy >= 0, "README.md has no '## Privacy' section");
		int next = readme.indexOf("\n## ", privacy + 1);
		String section = readme.substring(privacy, next < 0 ? readme.length() : next);
		List<String> listed = section.lines().filter(l -> l.startsWith("- `")).map(l -> l.substring(3, l.indexOf('`', 3))).toList();
		assertEquals(InternetTime.HOSTS.stream().map(URI::getHost).toList(), listed);
	}
}
