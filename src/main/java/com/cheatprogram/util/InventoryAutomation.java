package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.lwjgl.glfw.GLFW;

/** Shared pacing and user-input ownership for automatic inventory transactions. */
public final class InventoryAutomation {
	private static final Minecraft MC = Minecraft.getInstance();
	private static long manualUntil, manualRevision;
	private static Object contextPlayer, contextLevel, manualScreen;
	private static boolean claimedThisTick;
	private static AbstractContainerMenu highlightedMenu;
	private static int highlightedSlot = -1;
	private static long highlightUntil;
	private InventoryAutomation() { }
	/** Called once per Minecraft tick, including ticks while a screen has paused world simulation. */
	public static void beginTick() {
		claimedThisTick = false;
		if (contextPlayer != MC.player || contextLevel != MC.level) {
			contextPlayer = MC.player; contextLevel = MC.level;
			manualUntil = 0; manualScreen = null;
			highlightedMenu = null; highlightedSlot = -1;
		}
		if (manualScreen != MC.screen) { manualUntil = 0; manualScreen = null; }
	}
	public static long manualRevision() { return manualRevision; }
	public static void manualInteraction() {
		manualRevision++;
		manualScreen = MC.screen;
		manualUntil = System.currentTimeMillis() + 900;
	}
	public static boolean idle(AbstractContainerMenu menu) {
		if (MC.player == null || MC.level == null || MC.gameMode == null || MC.isPaused() || !MC.isWindowActive() || MC.getOverlay() != null
				|| !MC.player.isAlive() || MC.player.isSpectator() || !menu.getCarried().isEmpty()
				|| manualScreen == MC.screen && System.currentTimeMillis() < manualUntil) return false;
		long window = MC.getWindow().getWindow();
		return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
				&& GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS;
	}
	public static boolean ownInventoryReady() {
		return canUseOwnInventory() && idle(MC.player.inventoryMenu)
				&& (MC.screen == null || isOwnInventoryScreen());
	}
	/** Creative's item picker is a different menu, even though its inventory tab edits inventoryMenu. */
	public static boolean canUseOwnInventory() {
		return ownInventoryContext() && MC.player.inventoryMenu.getCarried().isEmpty()
				&& MC.player.containerMenu.getCarried().isEmpty();
	}
	private static boolean ownInventoryContext() {
		if (MC.player == null) return false;
		return MC.player.containerMenu == MC.player.inventoryMenu
				|| MC.gameMode != null && MC.gameMode.hasInfiniteItems()
					&& MC.screen instanceof CreativeModeInventoryScreen creative
					&& MC.player.containerMenu == creative.getMenu();
	}
	private static boolean isOwnInventoryScreen() {
		return MC.screen instanceof InventoryScreen || MC.screen instanceof CreativeModeInventoryScreen;
	}
	public static boolean claim(AbstractContainerMenu menu, int slot) {
		if (!idle(menu)) return false;
		if (claimedThisTick) return false;
		claimedThisTick = true;
		highlightedMenu = menu; highlightedSlot = slot; highlightUntil = System.currentTimeMillis() + 450;
		return true;
	}
	public static int highlightedSlot(AbstractContainerMenu menu) {
		return menu == highlightedMenu && System.currentTimeMillis() < highlightUntil ? highlightedSlot : -1;
	}
}
