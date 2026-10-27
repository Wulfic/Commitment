package com.wulfic.commitment.manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The never-locked and ignored lists free exactly what they name: a look-alike in another place is still locked. */
class ExclusionsTest {
	@ParameterizedTest
	@ValueSource(strings = {
			"sodium-options.json", "sodium-mixins.properties", "sodium-fingerprint.json", "iris.properties",
			"iris-excluded.json", "DistantHorizons.toml", "immediatelyfast.json", "entityculling.json",
			"dynamic_fps.json", "dynamic_fps-client.json", "sound_physics_remastered/soundphysics.properties",
			"presencefootsteps/userconfig.json", "ambientsounds/config.json", ".DS_Store", "deep/down/Thumbs.db",
			"desktop.ini", "controlify/profile-1.lock", "anymod.lock"})
	void excluded(String relPath) {
		assertTrue(Exclusions.excluded(relPath), relPath);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"sodium-options.json.bak", "othermod/sodium-options.json", "sodium-extra-options.json",
			"sound_physics_remastered.json", "my_dynamic_fps.json", "iris.properties.old", "alpha.json",
			"controlify/profile-1.json", "controlify.lock.json", "lock", "lockdown.toml"})
	void stillLocked(String relPath) {
		assertFalse(Exclusions.excluded(relPath), relPath);
	}
}
