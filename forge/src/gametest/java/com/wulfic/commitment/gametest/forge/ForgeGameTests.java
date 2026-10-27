package com.wulfic.commitment.gametest.forge;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.wulfic.commitment.gametest.GameTest;
import com.wulfic.commitment.gametest.GameTestDiscovery;
import com.wulfic.commitment.gametest.GameTestDiscovery.TestMethod;
import com.wulfic.commitment.gametest.PackDriver;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import net.minecraftforge.registries.RegisterEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The GameTest mod on Forge (plan step C.0, after ../enchanting-2). Test functions go in through {@code RegisterEvent},
 * as on NeoForge. Forge has no runtime API for test instances ({@code test_instance} is a datapack registry), so they
 * come from a datapack built here in memory, one {@code test_instance/<id>.json} per test, encoded from its annotation.
 * The GameTest server enables every available pack, this one included.
 */
@Mod(GameTestDiscovery.MOD_ID)
public final class ForgeGameTests {
	private static final Logger LOG = LoggerFactory.getLogger(ForgeGameTests.class);
	private static final String PACK_ID = GameTestDiscovery.MOD_ID + ":test_instances";

	public ForgeGameTests(FMLJavaModLoadingContext context) {
		// Stage E's pack runs (scripts/pack-smoke.sh) only. The property, not PackDriver.active(): PROPERTY is a constant,
		// so a GameTest server never loads PackDriver, whose client types the dist cleaner refuses on a dedicated server.
		if (System.getProperty(PackDriver.PROPERTY) != null) {
			TickEvent.ClientTickEvent.Post.BUS.addListener(event -> PackDriver.tick(Minecraft.getInstance()));
		}
		if (!ForgeGameTestHooks.isGametestEnabled()) {
			return;
		}
		List<TestMethod> tests = GameTestDiscovery.findTests();
		RegisterEvent.getBus(context.getModBusGroup()).addListener(event -> event.register(Registries.TEST_FUNCTION,
				helper -> tests.forEach(test -> helper.register(test.id(), test.function()))));
		Map<Identifier, byte[]> files = instanceFiles(tests);
		AddPackFindersEvent.BUS.addListener(event -> {
			if (event.getPackType() == PackType.SERVER_DATA) {
				event.addRepositorySource(packs -> packs.accept(pack(files)));
			}
		});
		LOG.info("Commitment GameTests: {} test instance(s) in datapack {}", files.size(), PACK_ID);
	}

	/** {@code data/<mod>/test_instance/<test>.json} for every test, as vanilla's FunctionGameTestInstance codec reads it. */
	private static Map<Identifier, byte[]> instanceFiles(List<TestMethod> tests) {
		Map<Identifier, byte[]> files = new TreeMap<>();
		for (TestMethod test : tests) {
			GameTest a = test.annotation();
			JsonObject json = new JsonObject();
			json.addProperty("type", "minecraft:function");
			json.addProperty("function", test.id().toString());
			json.addProperty("environment", a.environment());
			//? if >=26.3 {
			/*json.addProperty("dimension", a.dimension());
			*///?}
			json.addProperty("structure", a.structure());
			json.addProperty("max_ticks", a.maxTicks());
			json.addProperty("setup_ticks", a.setupTicks());
			json.addProperty("required", a.required());
			json.add("rotation", Rotation.CODEC.encodeStart(JsonOps.INSTANCE, a.rotation()).getOrThrow());
			json.addProperty("manual_only", a.manualOnly());
			json.addProperty("max_attempts", a.maxAttempts());
			json.addProperty("required_successes", a.requiredSuccesses());
			json.addProperty("sky_access", a.skyAccess());
			json.addProperty("padding", a.padding());
			Identifier file = Identifier.fromNamespaceAndPath(test.id().getNamespace(), "test_instance/" + test.id().getPath() + ".json");
			files.put(file, json.toString().getBytes(StandardCharsets.UTF_8));
		}
		return files;
	}

	private static Pack pack(Map<Identifier, byte[]> files) {
		PackLocationInfo location = new PackLocationInfo(PACK_ID, Component.literal("Commitment GameTest instances"),
				PackSource.BUILT_IN, Optional.empty());
		//? if >=26.3 {
		/*Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
			@Override
			public InstancePack openMetadata(PackLocationInfo info) {
				return new InstancePack(info, files);
			}

			@Override
			public Stream<PackResources> openResources(PackLocationInfo info, Pack.Metadata metadata) {
				return Stream.of(new InstancePack(info, files));
			}
		};
		*///?} else {
		// Before 26.3: one pack for both its metadata and its resources.
		Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
			@Override
			public PackResources openPrimary(PackLocationInfo info) {
				return new InstancePack(info, files);
			}

			@Override
			public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
				return new InstancePack(info, files);
			}
		};
		//?}
		// Metadata given here, not read from a pack.mcmeta: the pack is generated for this game version only.
		Pack.Metadata metadata = new Pack.Metadata(location.title(), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
		return new Pack(location, resources, metadata, new PackSelectionConfig(true, Pack.Position.TOP, false));
	}

	/** The generated files, read-only, as a data pack. */
	private record InstancePack(PackLocationInfo location, Map<Identifier, byte[]> files) implements PackResources {
		@Override
		public @Nullable IoSupplier<InputStream> getRootResource(String... path) {
			return null;
		}

		@Override
		public @Nullable IoSupplier<InputStream> getResource(PackType type, Identifier id) {
			byte[] bytes = type == PackType.SERVER_DATA ? files.get(id) : null;
			return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
		}

		@Override
		public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
			if (type != PackType.SERVER_DATA) {
				return;
			}
			files.forEach((id, bytes) -> {
				if (id.getNamespace().equals(namespace) && id.getPath().startsWith(directory + "/")) {
					output.accept(id, () -> new ByteArrayInputStream(bytes));
				}
			});
		}

		@Override
		public Set<String> getNamespaces(PackType type) {
			return type == PackType.SERVER_DATA ? Set.of(GameTestDiscovery.MOD_ID) : Set.of();
		}

		@Override
		public <T> @Nullable T getMetadataSection(MetadataSectionType<T> type) {
			return null;
		}

		@Override
		public void close() {
			// Nothing to release: the files are byte arrays held in memory.
		}
	}
}
