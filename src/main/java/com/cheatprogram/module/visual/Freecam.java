package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.Fonts;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** An unregistered camera pose; the real player retains movement and network authority. */
public final class Freecam extends Module {
	private final NumberSetting speed = add(new NumberSetting("speed", 8, .25, 48, .25, "blocks/s"));
	private long speedNoticeUntil;
	private CameraEntity view;
	private ClientLevel world;
	private LocalPlayer body;
	private Entity previousCamera;
	private CameraType previousPerspective;

	public Freecam() { super("freecam", Category.VISUAL); }

	private static Freecam instance() { return ModuleManager.get(Freecam.class); }
	public static boolean isActive() {
		Freecam module = instance();
		return module != null && module.isEnabled() && module.view != null && module.world == mc.level
				&& module.body == mc.player && mc.player != null && mc.player.isAlive();
	}

	/** Runs even when the normal module tick loop has no world, to release a detached view. */
	public static void tickLifecycle() {
		Freecam module = instance();
		if (module != null && module.view != null && (module.world != mc.level || module.body != mc.player
				|| mc.player == null || !mc.player.isAlive())) module.setEnabled(false);
	}

	@Override public void onEnable() { if (inGame() && mc.player.isAlive()) attach(); }
	private void attach() {
		world = mc.level; body = mc.player;
		previousCamera = mc.getCameraEntity(); previousPerspective = mc.options.getCameraType();
		view = new CameraEntity(world);
		var camera = mc.gameRenderer.getMainCamera();
		view.setPos(camera.isInitialized() ? camera.getPosition() : body.getEyePosition());
		view.setYRot(camera.isInitialized() ? camera.getYRot() : body.getYRot());
		view.setXRot(camera.isInitialized() ? camera.getXRot() : body.getXRot());
		view.setOldPosAndRot();
		// Keep cameraEntity == body: LocalPlayer uses it to send movement packets and run physics.
		mc.setCameraEntity(body);
		mc.options.setCameraType(CameraType.FIRST_PERSON);
		body.setDeltaMovement(Vec3.ZERO);
		clearPhysicalBodyInput();
	}

	@Override public void onDisable() {
		if (view == null) return;
		if (previousPerspective != null) mc.options.setCameraType(previousPerspective);
		if (world == mc.level && previousCamera != null && !previousCamera.isRemoved()) mc.setCameraEntity(previousCamera);
		else mc.setCameraEntity(mc.player);
		// The body's current yaw/pitch may belong to AI; returning the view must preserve it.
		speedNoticeUntil = 0;
		view = null; world = null; body = null; previousCamera = null; previousPerspective = null;
	}

	@Override public void onTick() {
		if (view == null) { if (inGame() && mc.player.isAlive()) attach(); else return; }
		if (!isActive()) return;
		clearPhysicalBodyInput(); // AI writes its own body input after modules finish ticking.
		view.setOldPosAndRot();
		if (mc.screen != null || mc.getOverlay() != null || mc.isPaused() || !mc.isWindowActive()) return;
		long window = mc.getWindow().getWindow();
		double forward = pressed(window, GLFW.GLFW_KEY_W) - pressed(window, GLFW.GLFW_KEY_S);
		double strafe = pressed(window, GLFW.GLFW_KEY_D) - pressed(window, GLFW.GLFW_KEY_A);
		double vertical = pressed(window, GLFW.GLFW_KEY_SPACE)
				- Math.max(pressed(window, GLFW.GLFW_KEY_LEFT_SHIFT), pressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT));
		double yaw = Math.toRadians(view.getYRot());
		// Minecraft positive lateral input points left in the camera plane.
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 movement = view.getViewVector(1).scale(forward).add(right.scale(strafe)).add(0, vertical, 0);
		if (movement.lengthSqr() > 1.0e-8) view.setPos(view.position().add(movement.normalize().scale(speed.val() / 20)));
	}

	/** Scroll changes the saved movement speed without applying an acceleration curve. */
	public static void adjustSpeed(double verticalScroll) {
		Freecam module = instance();
		if (!isActive() || !Double.isFinite(verticalScroll) || Math.abs(verticalScroll) < .01) return;
		double current = module.speed.val();
		double step = current < 2 || current == 2 && verticalScroll < 0 ? .25 : 1;
		int notches = (int) Math.min(8, Math.max(1, Math.round(Math.abs(verticalScroll))));
		double next = Math.max(module.speed.min, Math.min(module.speed.max,
				current + Math.copySign(notches * step, verticalScroll)));
		if (next != current) module.speed.set(next);
		module.speedNoticeUntil = System.nanoTime() + 1_500_000_000L;
	}

	@Override public void onRender2D(HudContext hud) {
		if (!isActive() || System.nanoTime() >= speedNoticeUntil) return;
		String text = speed.name() + "  ·  " + speed.format();
		float width = hud.c.textWidth(Fonts.Face.REGULAR, text, 10) + 18;
		float y = hud.height - 87;
		hud.c.rrect((hud.width - width) * .5f, y, width, 19, 6, 0xae0d1117);
		hud.c.textMid(Fonts.Face.REGULAR, text, hud.width * .5f, y + 9.5f, 10, 0xfff3f5f7);
	}

	private static int pressed(long window, int key) { return InputConstants.isKeyDown(window, key) ? 1 : 0; }
	private void clearPhysicalBodyInput() {
		if (body == null) return;
		body.input.keyPresses = Input.EMPTY; body.input.forwardImpulse = 0; body.input.leftImpulse = 0;
		mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false); mc.options.keyPickItem.setDown(false);
	}

	/** Receives vanilla sensitivity-adjusted mouse deltas, without rotating the body. */
	public static void look(double yaw, double pitch) {
		Freecam module = instance();
		if (isActive() && mc.screen == null && mc.getOverlay() == null && !mc.isPaused()) module.view.turn(yaw, pitch);
	}
	public static Vec3 cameraPosition(float partialTick) { return instance().view.getPosition(partialTick); }
	public static float cameraYaw(float partialTick) { return instance().view.getViewYRot(partialTick); }
	public static float cameraPitch(float partialTick) { return instance().view.getViewXRot(partialTick); }

	private static final class CameraEntity extends Entity {
		CameraEntity(ClientLevel level) { super(EntityType.MARKER, level); noPhysics = true; setNoGravity(true); }
		@Override public boolean hurtServer(ServerLevel level, DamageSource source, float damage) { return false; }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
		@Override protected void readAdditionalSaveData(CompoundTag tag) { }
		@Override protected void addAdditionalSaveData(CompoundTag tag) { }
	}
}
