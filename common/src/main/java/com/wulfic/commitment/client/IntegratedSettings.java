package com.wulfic.commitment.client;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
//? if >=26.2 {
/*import net.minecraft.client.Minecraft;
*///?} else {
import net.minecraft.server.level.ServerPlayer;
//?}

/**
 * The cheats settings only a singleplayer or LAN world has, which moved between versions: up to 26.1.x the world's own
 * {@code allowCommands} can't change while it runs and Open to LAN's "Allow cheats" is a flag for everyone, host
 * included; from 26.2 the world's setting can change (World Options) and LAN guests have a flag of their own. Client
 * only: call it only when {@code Platform.loader().physicalClient()}.
 */
public final class IntegratedSettings {
	private IntegratedSettings() {
	}

	public static boolean integrated(MinecraftServer server) {
		return server instanceof IntegratedServer;
	}

	/** Whether the host can run commands without being made an operator. */
	public static boolean cheats(MinecraftServer server) {
		//? if >=26.2 {
		/*return server.getWorldData().isAllowCommands();
		*///?} else {
		return server.getWorldData().isAllowCommands() || server.getPlayerList().isAllowCommandsForAllPlayers();
		//?}
	}

	/** Whether the world's own cheats setting can change while it runs, so it's held. */
	public static boolean allowCommandsSettable() {
		//? if >=26.2 {
		/*return true;
		*///?} else {
		return false;
		//?}
	}

	public static boolean allowCommands(MinecraftServer server) {
		return server.getWorldData().isAllowCommands();
	}

	/** Runs on the client thread, as the World Options screen does: it updates the host's own client too. */
	public static void setAllowCommands(MinecraftServer server, boolean allow) {
		//? if >=26.2 {
		/*IntegratedServer integrated = (IntegratedServer) server;
		Minecraft.getInstance().execute(() -> integrated.setWorldAllowCommands(allow));
		*///?} else {
		throw new UnsupportedOperationException("the world's cheats setting can't change while it runs on this version");
		//?}
	}

	public static boolean guestCommands(MinecraftServer server) {
		//? if >=26.3 {
		/*return ((IntegratedServer) server).getGuestCommandAccess();
		*///?} else if >=26.2 {
		/*return ((IntegratedServer) server).commandsAllowedForOtherPlayers();
		*///?} else {
		return server.getPlayerList().isAllowCommandsForAllPlayers();
		//?}
	}

	/**
	 * From 26.2 on the client thread, as the World Options screen does. Up to 26.1.x on the server thread, as Open to
	 * LAN does it there: the flag, then each player's permission level and command tree sent again.
	 */
	public static void setGuestCommands(MinecraftServer server, boolean allow) {
		//? if >=26.3 {
		/*IntegratedServer integrated = (IntegratedServer) server;
		Minecraft.getInstance().execute(() -> integrated.setGuestCommandAccess(allow));
		*///?} else if >=26.2 {
		/*IntegratedServer integrated = (IntegratedServer) server;
		Minecraft.getInstance().execute(() -> integrated.setCommandsAllowedForOtherPlayers(allow));
		*///?} else {
		server.getPlayerList().setAllowCommandsForAllPlayers(allow);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			server.getPlayerList().sendPlayerPermissionLevel(player);
		}
		//?}
	}
}
