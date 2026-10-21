package com.wulfic.commitment.lock;

import com.wulfic.commitment.Commitment;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Internet time: the HTTPS {@code Date} header of a few big hosts, asked at once. Two have to agree within five minutes,
 * so one wrong or blocked host can't decide it. Only asked at {@code /unlock confirm} and at world open while an unlock
 * timer runs. The README's privacy note names these hosts, and a test holds the two together.
 *
 * <p>{@code HttpURLConnection}, not {@code java.net.http}: it's in {@code java.base}, so a launcher's trimmed Java
 * runtime can't leave the unlock crashing instead of saying "no internet time".
 */
public final class InternetTime implements TrustedClock {
	/** Asked with a HEAD request; nothing is sent but the request line and the JRE's default headers. */
	public static final List<URI> HOSTS = List.of(
			URI.create("https://www.google.com/"),
			URI.create("https://www.cloudflare.com/"),
			URI.create("https://www.microsoft.com/"));
	static final int NEEDED = 2;
	static final Duration AGREE = Duration.ofMinutes(5);

	private final List<URI> hosts;
	private final int connectTimeoutMs;
	private final int readTimeoutMs;

	public InternetTime() {
		this(HOSTS, 3000, 5000);
	}

	InternetTime(List<URI> hosts, int connectTimeoutMs, int readTimeoutMs) {
		this.hosts = List.copyOf(hosts);
		this.connectTimeoutMs = connectTimeoutMs;
		this.readTimeoutMs = readTimeoutMs;
	}

	record Answer(URI host, long epochMillis, String failure) {
	}

	@Override
	public Reading read() {
		ExecutorService pool = Executors.newFixedThreadPool(hosts.size(), r -> {
			Thread t = new Thread(r, "Commitment internet time");
			t.setDaemon(true);
			return t;
		});
		try {
			List<CompletableFuture<Answer>> asks = hosts.stream()
					.map(host -> CompletableFuture.supplyAsync(() -> ask(host), pool))
					.toList();
			// Every ask carries its own timeouts; this cap only stops a stuck TLS handshake from holding the caller.
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMs + readTimeoutMs + 1000L);
			List<Answer> answers = new ArrayList<>();
			for (int i = 0; i < asks.size(); i++) {
				answers.add(await(asks.get(i), hosts.get(i), deadline));
			}
			return decide(answers);
		} finally {
			pool.shutdownNow();
		}
	}

	private static Answer await(CompletableFuture<Answer> ask, URI host, long deadline) {
		try {
			return ask.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
		} catch (TimeoutException e) {
			Commitment.LOGGER.warn("Commitment: internet time from {} took too long", host);
			return new Answer(host, 0, "timed out");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			Commitment.LOGGER.warn("Commitment: interrupted while asking {} for internet time", host);
			return new Answer(host, 0, "interrupted");
		} catch (ExecutionException e) {
			Commitment.LOGGER.warn("Commitment: internet time from {} failed: {}", host, e.getCause().toString());
			return new Answer(host, 0, e.getCause().toString());
		}
	}

	private Answer ask(URI host) {
		HttpURLConnection http = null;
		try {
			http = (HttpURLConnection) host.toURL().openConnection();
			http.setRequestMethod("HEAD");
			http.setConnectTimeout(connectTimeoutMs);
			http.setReadTimeout(readTimeoutMs);
			// The host's own clock: a redirect would hand the answer to whoever it points at.
			http.setInstanceFollowRedirects(false);
			http.setUseCaches(false);
			http.getResponseCode();
			String date = http.getHeaderField("Date");
			if (date == null) {
				Commitment.LOGGER.warn("Commitment: {} sent no Date header", host);
				return new Answer(host, 0, "no Date header");
			}
			return new Answer(host, ZonedDateTime.parse(date.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(), null);
		} catch (DateTimeParseException e) {
			Commitment.LOGGER.warn("Commitment: {} sent a Date header that isn't a date: {}", host, e.getParsedString());
			return new Answer(host, 0, "unreadable Date header");
		} catch (IOException | ClassCastException | IllegalArgumentException e) {
			Commitment.LOGGER.warn("Commitment: internet time from {} failed: {}", host, e.toString());
			return new Answer(host, 0, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
		} finally {
			if (http != null) {
				http.disconnect();
			}
		}
	}

	/** The middle reading of the largest group within {@link #AGREE} of each other, if it has {@link #NEEDED}. */
	static Reading decide(List<Answer> answers) {
		List<Long> times = answers.stream().filter(a -> a.failure() == null).map(Answer::epochMillis).sorted().toList();
		List<Long> best = List.of();
		for (int i = 0; i < times.size(); i++) {
			int j = i;
			while (j + 1 < times.size() && times.get(j + 1) - times.get(i) <= AGREE.toMillis()) {
				j++;
			}
			if (j - i + 1 > best.size()) {
				best = times.subList(i, j + 1);
			}
		}
		if (best.size() >= NEEDED) {
			return new Reading.At(best.get(best.size() / 2));
		}
		List<String> why = new ArrayList<>();
		for (Answer a : answers) {
			why.add(a.host().getHost() + ": " + (a.failure() != null ? a.failure() : "answered"));
		}
		String reason = times.size() >= NEEDED
				? "the time servers disagree by more than " + AGREE.toMinutes() + " minutes"
				: times.size() + " of " + answers.size() + " time servers answered (" + String.join("; ", why) + ")";
		Commitment.LOGGER.warn("Commitment: no internet time: {}", reason);
		return new Reading.Unavailable(reason);
	}
}
