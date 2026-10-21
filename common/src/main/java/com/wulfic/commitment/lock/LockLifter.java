package com.wulfic.commitment.lock;

import com.wulfic.commitment.Commitment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Lifts a lock once its 24 hours have passed: the guard pack, the mirror and the world's record are renamed to
 * {@code <name>.<lockId>.lifted} (the mirror to {@code <lockId>.dat.lifted}), where neither vanilla nor
 * {@link LockStore} reads them. All or nothing (owner, 2026-10-21): if any of the three that exists can't be proven to
 * be Commitment's own file for this lock, inside the folder it belongs in, nothing is renamed and the world stays
 * locked.
 *
 * <p>{@link #plan} is the dry run and touches nothing; only an allowed plan can be {@link #lift lifted}.
 */
public final class LockLifter {
	/** A lock record is under 16 MB decoded ({@link LockCodec}); its gzip is far smaller. */
	private static final long MAX_RECORD = 16L * 1024 * 1024;

	/** One of Commitment's files: where it is, the folder it must really be in, and its name once lifted. */
	public record Target(String what, Path root, Path file, Path liftedTo, long maxSize, Predicate<byte[]> ours) {
	}

	public sealed interface Check {
		record Missing() implements Check {
		}

		record Ours() implements Check {
		}

		record Refused(String reason) implements Check {
		}
	}

	public record Item(Target target, Check check) {
	}

	/** Items in rename order: guard pack, mirror, then the world's record, so a crash midway leaves the record to retry. */
	public record Plan(UUID lockId, List<Item> items) {
		public Plan {
			items = List.copyOf(items);
		}

		public boolean allowed() {
			return refused().isEmpty();
		}

		public List<Item> refused() {
			return items.stream().filter(i -> i.check() instanceof Check.Refused).toList();
		}

		public List<Item> toRename() {
			return items.stream().filter(i -> i.check() instanceof Check.Ours).toList();
		}
	}

	/** Seam for the failed-rename test. */
	interface Mover {
		void move(Path from, Path to) throws IOException;
	}

	private LockLifter() {
	}

	/** The dry run: what lifting {@code lockId} would rename, or why it can't. Touches nothing. */
	public static Plan plan(Path worldDir, LockStore store, UUID lockId) {
		Objects.requireNonNull(worldDir, "worldDir");
		Objects.requireNonNull(lockId, "lockId");
		List<Target> targets = new ArrayList<>();
		targets.add(new Target("guard pack", worldDir, worldDir.resolve(GuardPack.FILE),
				worldDir.resolve(GuardPack.FILE + "." + lockId + ".lifted"), GuardPack.MAX_FILE,
				bytes -> GuardPack.lockIdOf(bytes).equals(Optional.of(lockId))));
		Path mirror = store.mirrorFile(lockId);
		if (mirror != null) {
			targets.add(new Target("lock mirror", mirror.getParent(), mirror, mirror.resolveSibling(mirror.getFileName() + ".lifted"),
					MAX_RECORD, bytes -> LockCodec.isOurs(bytes, lockId)));
		}
		targets.add(new Target("lock record", worldDir, worldDir.resolve(LockStore.WORLD_RECORD),
				worldDir.resolve(LockStore.WORLD_RECORD + "." + lockId + ".lifted"), MAX_RECORD, bytes -> LockCodec.isOurs(bytes, lockId)));

		List<Item> items = targets.stream().map(t -> new Item(t, check(t))).toList();
		Plan plan = new Plan(lockId, items);
		for (Item refused : plan.refused()) {
			Commitment.LOGGER.warn("Commitment: lock {} can't lift: {} {} {}", lockId, refused.target().what(), refused.target().file(),
					((Check.Refused) refused.check()).reason());
		}
		return plan;
	}

	/**
	 * Renames every file in an allowed plan, re-checking each one just before it moves. A failure moves the ones
	 * already renamed back, so the lock is either fully lifted or untouched.
	 *
	 * @return how many files were renamed
	 * @throws IOException the plan wasn't allowed, a file changed since the plan, or a rename failed; nothing stays
	 *                     renamed unless the log says the undo failed too
	 */
	public static int lift(Plan plan) throws IOException {
		// No ATOMIC_MOVE: with it, whether an existing target is replaced is up to the OS. Without REPLACE_EXISTING a
		// taken name fails instead, and a rename within one folder is a rename either way.
		return lift(plan, (from, to) -> Files.move(from, to));
	}

	static int lift(Plan plan, Mover mover) throws IOException {
		Objects.requireNonNull(plan, "plan");
		if (!plan.allowed()) {
			throw new IOException("lock " + plan.lockId() + " can't lift: " + describe(plan.refused()));
		}
		List<Item> toRename = plan.toRename();
		Commitment.LOGGER.info("Commitment: lifting lock {}: renaming {} files: {}", plan.lockId(), toRename.size(),
				toRename.stream().map(i -> i.target().file() + " -> " + i.target().liftedTo().getFileName()).toList());
		List<Target> renamed = new ArrayList<>();
		for (Item item : toRename) {
			Target t = item.target();
			try {
				if (!(check(t) instanceof Check.Ours)) {
					throw new IOException(t.what() + " " + t.file() + " changed after the lift was planned");
				}
				mover.move(t.file(), t.liftedTo());
				renamed.add(t);
			} catch (IOException e) {
				Commitment.LOGGER.warn("Commitment: lifting lock {} failed at the {} {} ({}); undoing {} renames", plan.lockId(), t.what(),
						t.file(), e.getMessage(), renamed.size());
				undo(plan.lockId(), renamed, mover);
				throw new IOException("lock " + plan.lockId() + " didn't lift: " + e.getMessage(), e);
			}
		}
		Commitment.LOGGER.info("Commitment: lock {} lifted: {} files renamed to *.lifted (to undo, rename them back: {})", plan.lockId(),
				renamed.size(), renamed.stream().map(t -> t.liftedTo() + " -> " + t.file().getFileName()).toList());
		return renamed.size();
	}

	private static void undo(UUID lockId, List<Target> renamed, Mover mover) {
		for (Target t : renamed.reversed()) {
			try {
				mover.move(t.liftedTo(), t.file());
				Commitment.LOGGER.info("Commitment: put back {}", t.file());
			} catch (IOException e) {
				Commitment.LOGGER.error("Commitment: lock {}: can't put back {} ({}); rename {} to {} by hand", lockId, t.file(),
						e.getMessage(), t.liftedTo(), t.file().getFileName());
			}
		}
	}

	static Check check(Target t) {
		try {
			if (!Files.exists(t.file(), LinkOption.NOFOLLOW_LINKS)) {
				return new Check.Missing();
			}
			if (!Files.isRegularFile(t.file(), LinkOption.NOFOLLOW_LINKS)) {
				return new Check.Refused("isn't a regular file (a folder or a link)");
			}
			// The real path must be exactly where the file should be. A junction or link anywhere on the way out of the
			// root fails this; Java doesn't call a Windows junction a symbolic link, so isRegularFile alone can't tell.
			Path real = t.file().toRealPath();
			Path expected = t.root().toRealPath().resolve(t.root().relativize(t.file()));
			if (!real.equals(expected)) {
				return new Check.Refused("resolves to " + real + ", outside " + t.root());
			}
			long size = Files.size(t.file());
			if (size > t.maxSize()) {
				return new Check.Refused("is " + size + " bytes, too large to be Commitment's");
			}
			if (!t.ours().test(Files.readAllBytes(t.file()))) {
				return new Check.Refused("isn't Commitment's " + t.what() + " for this lock (no marker, another lock's, or edited)");
			}
			if (Files.exists(t.liftedTo(), LinkOption.NOFOLLOW_LINKS)) {
				return new Check.Refused("can't be renamed: " + t.liftedTo() + " already exists");
			}
			return new Check.Ours();
		} catch (IOException e) {
			return new Check.Refused("can't be checked (" + e.getMessage() + ")");
		}
	}

	private static String describe(List<Item> refused) {
		return String.join("; ", refused.stream()
				.map(i -> i.target().what() + " " + i.target().file() + " " + ((Check.Refused) i.check()).reason()).toList());
	}
}
