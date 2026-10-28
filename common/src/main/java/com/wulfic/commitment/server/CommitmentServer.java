package com.wulfic.commitment.server;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.client.ClientGameDir;
import com.wulfic.commitment.gate.ServerGate;
import com.wulfic.commitment.lock.GuardPack;
import com.wulfic.commitment.lock.HeldSettings;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.MirrorDir;
import com.wulfic.commitment.lock.WorldLock;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.platform.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Commitment on a running server, integrated or dedicated: at start it opens the world's lock and heals it, and while
 * the world is committed it sets the heartbeat the guard pack checks for. Each loader forwards its server events here.
 */
public final class CommitmentServer {
	/** The storage the guard pack's functions read. The mod sets {@code alive} in it every tick. */
	public static final Identifier GUARD_STORAGE = Identifier.fromNamespaceAndPath("commitment", "guard");
	/** The guard pack's id in vanilla's pack repository. */
	public static final String GUARD_PACK_ID = "file/" + Path.of(GuardPack.FILE).getFileName();

	/** Servers whose world needs the heartbeat. Weak, so a stopped integrated server isn't kept alive by this. */
	private static final Map<MinecraftServer, Boolean> BEATING = Collections.synchronizedMap(new WeakHashMap<>());
	/** The committed game settings each committed world's server puts back. */
	private static final HeldSettings<MinecraftServer> HELD = new HeldSettings<>();
	/** How often the settings are checked: once a second. */
	public static final int CHECK_TICKS = 20;

	private CommitmentServer() {
	}

	public static LockStore store() {
		return new LockStore(MirrorDir.forThisMachine(Platform.loader().development()), System::currentTimeMillis, UUID::randomUUID);
	}

	public static Path worldDir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
	}

	/**
	 * The setup {@code /commit} locks, read from where the world-open gate will read it: the client's game dir on the
	 * integrated server ({@link ClientGameDir}, referenced only there, so a server never loads a client class), the
	 * server's own dir otherwise ({@link ServerGate#serverDir()}).
	 */
	public static GameEnvironment environment(MinecraftServer server) {
		if (Platform.loader().physicalClient()) {
			return Platform.environment(GameEnvironment.Side.CLIENT, ClientGameDir.get(), worldDir(server));
		}
		return Platform.environment(GameEnvironment.Side.DEDICATED_SERVER, ServerGate.serverDir(), worldDir(server));
	}

	/** After the worlds and datapacks have loaded. */
	public static void started(MinecraftServer server) {
		Path world = worldDir(server);
		boolean committed;
		try {
			LockStore.Loaded loaded = WorldLock.open(world, store(), OptionalLong.of(server.overworld().getSeed()), WorldLock.Repair.ONLY_IF_MISSING);
			committed = loaded instanceof LockStore.Loaded.Locked;
			if (loaded instanceof LockStore.Loaded.Locked locked) {
				hold(server, locked.record().worldSettings());
			}
		} catch (IOException e) {
			// The gate keeps a world it can't read closed; one that got this far is open. The mod is here, so the
			// heartbeat is the truth, and the guard pack mustn't blind anyone for an I/O error.
			Commitment.LOGGER.error("Commitment: can't open the lock of {} at server start ({}); keeping the heartbeat on", world,
					e.getMessage());
			committed = true;
		}
		boolean guardThere = Files.exists(world.resolve(GuardPack.FILE));
		if (committed || guardThere) {
			BEATING.put(server, true);
			Commitment.LOGGER.info("Commitment: world {} is committed; heartbeat on", world.getFileName());
		}
		if (committed && guardThere) {
			select(server);
		}
	}

	public static void stopped(MinecraftServer server) {
		BEATING.remove(server);
		HELD.release(server);
	}

	/** Every server tick, after the world and the functions have ticked. */
	public static void tick(MinecraftServer server) {
		if (BEATING.containsKey(server)) {
			heartbeat(server);
		}
		if (server.getTickCount() % CHECK_TICKS == 0) {
			checkSettings(server);
		}
	}

	/**
	 * Starts putting back the committed game settings: at server start for a committed world, and at {@code /commit
	 * confirm}. Puts back anything already drifted at once, so a value changed while the mod was away is set back as
	 * the world opens. A record with no settings (damaged, or committed before they were locked) holds nothing.
	 */
	public static void hold(MinecraftServer server, SortedMap<String, String> settings) {
		if (settings.isEmpty()) {
			Commitment.LOGGER.info("Commitment: world {} has no committed game settings; none are held", worldDir(server).getFileName());
			return;
		}
		HELD.hold(server, settings);
		Commitment.LOGGER.info("Commitment: holding {} committed game settings in world {}", settings.size(), worldDir(server).getFileName());
		checkSettings(server);
	}

	/** Whether this server puts committed game settings back. */
	public static boolean holding(MinecraftServer server) {
		return HELD.holding(server);
	}

	/** Puts back what the server holds; nothing escapes into the tick ({@link HeldSettings#check}). */
	private static void checkSettings(MinecraftServer server) {
		HELD.check(server, worldDir(server).getFileName().toString(), held -> SettingsLock.check(server, held.settings(), held.throttle()));
	}

	/**
	 * Whether this server sets the heartbeat every tick. Asking the storage instead is a race: the guard pack's own
	 * functions clear {@code alive} at load and every 40 ticks, and the heartbeat sets it again at the tick's end.
	 */
	public static boolean beating(MinecraftServer server) {
		return BEATING.containsKey(server);
	}

	/** Starts the heartbeat for a world committed while the server runs ({@code /commit confirm}). */
	public static void beat(MinecraftServer server) {
		BEATING.put(server, true);
		heartbeat(server);
	}

	/** Tells the guard pack that Commitment is running. */
	public static void heartbeat(MinecraftServer server) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("alive", true);
		server.getCommandStorage().set(GUARD_STORAGE, tag);
	}

	/**
	 * Makes sure the guard pack is enabled: vanilla enables a new world pack by itself at load, but not one on the
	 * world's disabled list ({@code /datapack disable}). Reloads the data packs only when it wasn't.
	 */
	public static void select(MinecraftServer server) {
		PackRepository packs = server.getPackRepository();
		packs.reload();
		if (!packs.isAvailable(GUARD_PACK_ID)) {
			Commitment.LOGGER.warn("Commitment: the guard pack {} isn't available to the server; it can't be enabled", GUARD_PACK_ID);
			return;
		}
		if (packs.getSelectedIds().contains(GUARD_PACK_ID)) {
			return;
		}
		List<String> selected = new ArrayList<>(packs.getSelectedIds());
		selected.add(GUARD_PACK_ID);
		Commitment.LOGGER.info("Commitment: enabling the guard pack {} (it was disabled)", GUARD_PACK_ID);
		server.reloadResources(selected).exceptionally(e -> {
			Commitment.LOGGER.error("Commitment: enabling the guard pack {} failed", GUARD_PACK_ID, e);
			return null;
		});
	}
}
