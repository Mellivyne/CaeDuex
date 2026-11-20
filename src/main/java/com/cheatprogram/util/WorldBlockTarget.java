package com.cheatprogram.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared world selection for tools that mark the block under the crosshair. */
public final class WorldBlockTarget {
    public record Aim(BlockPos block, Vec3 point) { }

    private WorldBlockTarget() { }

    public static Aim pick(Level level, Player player, Vec3 from, Vec3 direction, double range, boolean raycast) {
        Vec3 end = from.add(direction.scale(range));
        if (raycast) {
            var hit = level.clip(new ClipContext(from, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                var face = hit.getDirection();
                return new Aim(hit.getBlockPos().immutable(), hit.getLocation()
                        .add(face.getStepX() * .08, face.getStepY() * .08, face.getStepZ() * .08));
            }
        }
        BlockPos block = BlockPos.containing(end);
        return level.isInWorldBounds(block) ? new Aim(block, Vec3.atCenterOf(block)) : null;
    }
}
