package com.wulfic.commitment.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What Fabric's GameTest API does for the Fabric nodes, for the NeoForge and Forge GameTest mods (plan step C.0, after
 * ../enchanting-2): finds every {@link GameTest} method in the classes listed under {@code fabric-gametest} in the
 * GameTest mod's {@code fabric.mod.json}, the one list every loader reads. Test ids are Fabric's form,
 * {@code <mod id>:<snake_case of Class_method>}. Each loader's registrar turns the result into test functions and
 * instances its own way.
 */
public final class GameTestDiscovery {
	/** The GameTest mod's id on NeoForge and Forge (Fabric's is {@code commitment-gametest}; theirs can't hold '-'). */
	public static final String MOD_ID = "commitment_gametest";
	private static final Logger LOG = LoggerFactory.getLogger(GameTestDiscovery.class);

	private GameTestDiscovery() {
	}

	/** Every listed class's {@link GameTest} methods. Fails on a listed class with none, or a malformed method. */
	public static List<TestMethod> findTests() {
		List<TestMethod> found = new ArrayList<>();
		for (String className : testClassNames()) {
			Object instance;
			try {
				instance = Class.forName(className).getDeclaredConstructor().newInstance();
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("GameTest class " + className + " can't be created", e);
			}
			int before = found.size();
			for (Class<?> c = instance.getClass(); c != null; c = c.getSuperclass()) {
				for (Method method : c.getDeclaredMethods()) {
					GameTest annotation = method.getAnnotation(GameTest.class);
					if (annotation != null) {
						validate(method);
						found.add(new TestMethod(instance, method, annotation));
					}
				}
			}
			if (found.size() == before) {
				// Fabric only warns; here a listed class with no tests means the import swap missed it, so fail.
				throw new IllegalStateException("GameTest class " + className + " has no @" + GameTest.class.getName()
						+ " methods; did the build swap its GameTest import (neoforge/forge build.gradle)?");
			}
		}
		LOG.info("Commitment GameTests: {} test method(s) in {} class(es)", found.size(),
				found.stream().map(t -> t.method().getDeclaringClass()).distinct().count());
		return found;
	}

	/** The {@code fabric-gametest} entry points in the GameTest mod's fabric.mod.json, the list Fabric's runner reads. */
	private static List<String> testClassNames() {
		try (InputStream in = GameTestDiscovery.class.getResourceAsStream("/fabric.mod.json")) {
			if (in == null) {
				throw new IllegalStateException("the GameTest mod's fabric.mod.json (the test class list) is missing");
			}
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray entries = root.getAsJsonObject("entrypoints").getAsJsonArray("fabric-gametest");
			List<String> names = new ArrayList<>();
			entries.forEach(entry -> names.add(entry.getAsString()));
			if (names.isEmpty()) {
				throw new IllegalStateException("fabric.mod.json lists no fabric-gametest classes");
			}
			return names;
		} catch (IOException e) {
			throw new IllegalStateException("the GameTest mod's fabric.mod.json is unreadable", e);
		}
	}

	private static void validate(Method method) {
		List<String> issues = new ArrayList<>();
		if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != GameTestHelper.class) {
			issues.add("must take one GameTestHelper");
		}
		if (!Modifier.isPublic(method.getModifiers())) {
			issues.add("must be public");
		}
		if (Modifier.isStatic(method.getModifiers())) {
			issues.add("must not be static");
		}
		if (method.getReturnType() != void.class) {
			issues.add("must return void");
		}
		if (!issues.isEmpty()) {
			throw new IllegalStateException("GameTest " + method.getDeclaringClass().getName() + "#" + method.getName()
					+ ": " + String.join(", ", issues));
		}
	}

	/** One test method, on the instance of its class that every test in that class shares (as on Fabric). */
	public record TestMethod(Object instance, Method method, GameTest annotation) {
		public Identifier id() {
			String name = (instance.getClass().getSimpleName() + "_" + method.getName())
					.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
			return Identifier.fromNamespaceAndPath(MOD_ID, name);
		}

		/** The test function: runs the method, letting the test's own failure reach the runner unwrapped. */
		public Consumer<GameTestHelper> function() {
			return helper -> {
				try {
					method.invoke(instance, helper);
				} catch (InvocationTargetException e) {
					if (e.getTargetException() instanceof RuntimeException failure) {
						throw failure;
					}
					LOG.error("GameTest {} threw", id(), e.getTargetException());
					throw new IllegalStateException("GameTest " + id() + " threw " + e.getTargetException(), e);
				} catch (IllegalAccessException e) {
					LOG.error("GameTest {} can't be invoked", id(), e);
					throw new IllegalStateException("GameTest " + id() + " can't be invoked", e);
				}
			};
		}
	}
}
