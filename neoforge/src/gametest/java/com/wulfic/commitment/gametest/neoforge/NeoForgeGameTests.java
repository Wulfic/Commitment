package com.wulfic.commitment.gametest.neoforge;

import com.wulfic.commitment.gametest.GameTest;
import com.wulfic.commitment.gametest.GameTestDiscovery;
import com.wulfic.commitment.gametest.GameTestDiscovery.TestMethod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The GameTest mod on NeoForge (plan step C.0, after ../enchanting-2): registers each test {@link GameTestDiscovery}
 * finds as a test function, then as a test instance. Only does anything when NeoForge enables GameTests.
 */
@Mod(GameTestDiscovery.MOD_ID)
public final class NeoForgeGameTests {
	private static final Logger LOG = LoggerFactory.getLogger(NeoForgeGameTests.class);

	private final List<TestMethod> tests;

	public NeoForgeGameTests(IEventBus modBus) {
		if (!GameTestHooks.isGametestEnabled()) {
			tests = List.of();
			return;
		}
		tests = GameTestDiscovery.findTests();
		modBus.addListener(RegisterEvent.class, this::registerFunctions);
		modBus.addListener(RegisterGameTestsEvent.class, this::registerTests);
	}

	private void registerFunctions(RegisterEvent event) {
		event.register(Registries.TEST_FUNCTION, helper -> {
			for (TestMethod test : tests) {
				helper.register(test.id(), test.function());
			}
		});
	}

	private void registerTests(RegisterGameTestsEvent event) {
		// One empty environment of ours per environment name a test uses. Every test here uses vanilla's default, an
		// empty all_of, which only groups tests into a batch; an empty one of our own batches the same.
		Map<String, Holder<TestEnvironmentDefinition<?>>> environments = new HashMap<>();
		for (TestMethod test : tests) {
			GameTest a = test.annotation();
			if (!a.environment().equals("minecraft:default")) {
				throw new IllegalStateException("GameTest " + test.id() + " names environment " + a.environment()
						+ "; NeoForgeGameTests only mirrors minecraft:default (an empty all_of)");
			}
			Holder<TestEnvironmentDefinition<?>> environment = environments.computeIfAbsent(a.environment(),
					n -> event.registerEnvironment(Identifier.fromNamespaceAndPath(GameTestDiscovery.MOD_ID, "minecraft/default")));
			//? if >=26.3 {
			/*TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment,
					ResourceKey.create(Registries.DIMENSION, Identifier.parse(a.dimension())), Identifier.parse(a.structure()),
					a.maxTicks(), a.setupTicks(), a.required(), a.rotation(), a.manualOnly(), a.maxAttempts(),
					a.requiredSuccesses(), a.skyAccess(), a.padding());
			*///?} else {
			TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Identifier.parse(a.structure()),
					a.maxTicks(), a.setupTicks(), a.required(), a.rotation(), a.manualOnly(), a.maxAttempts(),
					a.requiredSuccesses(), a.skyAccess(), a.padding());
			//?}
			event.registerTest(test.id(), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, test.id()), data));
		}
		LOG.info("Registered {} Commitment GameTest(s) on NeoForge", tests.size());
	}
}
