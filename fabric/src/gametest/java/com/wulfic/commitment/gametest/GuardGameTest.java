package com.wulfic.commitment.gametest;

import com.wulfic.commitment.lock.GuardPack;
import com.wulfic.commitment.server.CommitmentServer;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * C.1: the heartbeat and the guard pack's own commands, on a live server. The GameTest world isn't committed and has no
 * guard pack, so these run check.mcfunction's test on penalty.mcfunction's real lines, as the commands they are.
 */
public class GuardGameTest {
	private static final String PENALTY = "data/commitment_guard/function/penalty.mcfunction";

	@GameTest
	public void heartbeatTellsTheGuardTheModIsHere(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		server.getCommandStorage().set(CommitmentServer.GUARD_STORAGE, new CompoundTag());
		CommitmentServer.heartbeat(server);
		helper.assertTrue(server.getCommandStorage().get(CommitmentServer.GUARD_STORAGE).getBooleanOr("alive", false),
				"storage commitment:guard holds alive after a heartbeat");
		helper.succeed();
	}

	@GameTest
	public void guardSparesPlayersWhileTheModRuns(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer player = onlinePlayer(helper);
		try {
			CommitmentServer.heartbeat(server);
			runPenaltyUnlessAlive(server);
			helper.assertFalse(player.hasEffect(MobEffects.BLINDNESS), "no blindness while the heartbeat runs");
			helper.assertFalse(player.hasEffect(MobEffects.SLOWNESS), "no slowness while the heartbeat runs");
		} finally {
			server.getPlayerList().remove(player);
		}
		helper.succeed();
	}

	@GameTest
	public void guardBlindsAndFreezesPlayersWhenTheModIsGone(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer player = onlinePlayer(helper);
		try {
			server.getCommandStorage().set(CommitmentServer.GUARD_STORAGE, new CompoundTag());
			runPenaltyUnlessAlive(server);
			expect(helper, player, MobEffects.BLINDNESS, 0, "blindness");
			expect(helper, player, MobEffects.SLOWNESS, 255, "slowness");
			expect(helper, player, MobEffects.MINING_FATIGUE, 255, "mining fatigue");
			expect(helper, player, MobEffects.RESISTANCE, 255, "resistance");
		} finally {
			server.getPlayerList().remove(player);
			server.getCommandStorage().set(CommitmentServer.GUARD_STORAGE, new CompoundTag());
		}
		helper.succeed();
	}

	/**
	 * A mock player on the server's player list, which the guard's {@code @a} targets. Vanilla marks this helper for
	 * removal, but on every 26.x node it's still the only one that joins the list ({@code makeMockServerPlayer} doesn't).
	 */
	@SuppressWarnings("removal")
	private static ServerPlayer onlinePlayer(GameTestHelper helper) {
		return helper.makeMockServerPlayerInLevel();
	}

	/** Each effect for 3 s at most, so it runs out by itself once the mod is back. */
	private static void expect(GameTestHelper helper, ServerPlayer player, Holder<MobEffect> effect, int amplifier, String name) {
		MobEffectInstance instance = player.getEffect(effect);
		helper.assertTrue(instance != null, "the guard gives " + name);
		helper.assertTrue(instance.getAmplifier() == amplifier, name + " at amplifier " + amplifier + ", got " + instance.getAmplifier());
		helper.assertTrue(instance.getDuration() <= 60, name + " lasts at most 3 s, got " + instance.getDuration() + " ticks");
	}

	/** check.mcfunction's condition in front of every command of penalty.mcfunction, as the server would run them. */
	private static void runPenaltyUnlessAlive(MinecraftServer server) {
		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		for (String line : GuardPack.files(UUID.randomUUID()).get(PENALTY).split("\n")) {
			String command = line.strip();
			if (!command.isEmpty() && !command.startsWith("#")) {
				server.getCommands().performPrefixedCommand(source, "execute unless data storage commitment:guard alive run " + command);
			}
		}
	}
}
