package com.cheatprogram.render;

import com.cheatprogram.compat.gl.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

/** A camera-facing glass billboard projected from one known world anchor. */
public final class HudWorldPanel {
	private static final Minecraft MC = Minecraft.getInstance();
	public record Projection(float x, float y, float scale, long time) { }
	private HudWorldPanel() { }

	public static Projection project(Render3D renderer, Vec3 anchor, double maxDistance) {
		double distance = renderer.camera.distanceTo(anchor);
		if (distance < 0.4 || distance > maxDistance) return null;
		Vec3 relative = anchor.subtract(renderer.camera);
		var projection = RenderSystem.getProjectionMatrix();
		Vector4f clip = new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1)
				.mul(renderer.viewMatrix()).mul(projection);
		if (clip.w <= 0.01f) return null;
		float x = clip.x / clip.w, y = clip.y / clip.w, depth = clip.z / clip.w;
		if (depth < -1 || depth > 1 || Math.abs(x) > 1.1f || Math.abs(y) > 1.1f) return null;
		int width = MC.getWindow().getWidth(), height = MC.getWindow().getHeight();
		float focal = Math.abs(projection.m11()) * height / 2f;
		float scale = Math.max(0.45f, Math.min(2.8f, focal * 3.2f / ((float) distance * 190f)));
		return new Projection((x + 1) * width / 2f, (1 - y) * height / 2f, scale, System.nanoTime());
	}
}
