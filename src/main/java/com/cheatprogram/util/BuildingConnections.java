package com.cheatprogram.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Resolves the connection properties in an AI building before it reaches a preview or a server. */
public final class BuildingConnections {
    private static final String KEEP_CONNECTIONS = "CPKeepConnections";
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final BooleanProperty[] CONNECTION = {
            BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST
    };
    private static final VoxelShape POST_TEST = Block.box(7, 0, 7, 9, 16, 9);
    private static final VoxelShape[] SIDE_TEST = {
            Block.box(7, 0, 0, 9, 16, 9), Block.box(7, 0, 7, 16, 16, 9),
            Block.box(7, 0, 7, 9, 16, 16), Block.box(0, 0, 7, 9, 16, 9)
    };

    private BuildingConnections() { }

    public static void keepAuthoredConnections(CompoundTag state) {
        state.putBoolean(KEEP_CONNECTIONS, true);
    }

    public static boolean keepsAuthoredConnections(CompoundTag state) {
        return state.contains(KEEP_CONNECTIONS);
    }

    public static void resolve(int width, int height, int depth, List<BlockState> states,
                               List<CompoundTag> tags, int[] blocks, BooleanSupplier current) {
        boolean[] auto = new boolean[states.size()];
        Map<BlockState, Integer> indices = new IdentityHashMap<>();
        boolean any = false;
        for (int i = 0; i < auto.length; i++) {
            if (keepsAuthoredConnections(tags.get(i))) continue;
            Block type = states.get(i).getBlock();
            auto[i] = type instanceof IronBarsBlock || type instanceof FenceBlock || type instanceof WallBlock;
            if (auto[i]) indices.putIfAbsent(states.get(i), i);
            any |= auto[i];
        }
        if (!any) return;

        int plane = width * depth;
        // The state of a wall directly above can determine whether the wall beneath it has a post.
        for (int y = height - 1; y >= 0; y--) {
            for (int z = 0; z < depth; z++) {
                if (!current.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                for (int x = 0; x < width; x++) {
                    int index = y * plane + z * width + x;
                    int paletteIndex = blocks[index];
                    if (!auto[paletteIndex]) continue;
                    BlockState original = states.get(paletteIndex);
                    BlockState shaped = original;
                    boolean wall = original.getBlock() instanceof WallBlock;
                    BlockState above = wall && y + 1 < height ? states.get(blocks[index + plane]) : AIR;
                    VoxelShape cover = wall ? above.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).getFaceShape(Direction.DOWN) : null;

                    for (int side = 0; side < HORIZONTAL.length; side++) {
                        Direction direction = HORIZONTAL[side];
                        int neighborX = x + direction.getStepX(), neighborZ = z + direction.getStepZ();
                        BlockState neighbor = neighborX >= 0 && neighborX < width && neighborZ >= 0 && neighborZ < depth
                                ? states.get(blocks[y * plane + neighborZ * width + neighborX]) : AIR;
                        Direction face = direction.getOpposite();
                        boolean sturdy = neighbor.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, face);
                        Block neighborBlock = neighbor.getBlock();
                        if (original.getBlock() instanceof IronBarsBlock pane) {
                            shaped = shaped.setValue(CONNECTION[side], pane.attachsTo(neighbor, sturdy) || neighborBlock instanceof WallBlock);
                        } else if (original.getBlock() instanceof FenceBlock fence) {
                            // No server tags are bound yet when a plan is imported from the title screen.
                            boolean vanillaFence = neighborBlock instanceof FenceBlock && !original.is(BlockTags.FENCES)
                                    && (fence == Blocks.NETHER_BRICK_FENCE) == (neighborBlock == Blocks.NETHER_BRICK_FENCE);
                            shaped = shaped.setValue(CONNECTION[side], fence.connectsTo(neighbor, sturdy, face) || vanillaFence);
                        } else {
                            boolean connects = neighbor.is(BlockTags.WALLS) || neighborBlock instanceof WallBlock
                                    || !Block.isExceptionForConnection(neighbor) && sturdy || neighborBlock instanceof IronBarsBlock
                                    || neighborBlock instanceof FenceGateBlock && FenceGateBlock.connectsToDirection(neighbor, face);
                            WallSide arm = !connects ? WallSide.NONE : covered(cover, SIDE_TEST[side]) ? WallSide.TALL : WallSide.LOW;
                            shaped = shaped.setValue(wallProperty(direction), arm);
                        }
                    }
                    if (wall) shaped = shaped.setValue(BlockStateProperties.UP, raisePost(shaped, above, cover));
                    if (shaped == original) continue;
                    Integer resolved = indices.get(shaped);
                    if (resolved == null) {
                        resolved = states.size();
                        indices.put(shaped, resolved);
                        states.add(shaped);
                        tags.add(NbtUtils.writeBlockState(shaped));
                    }
                    blocks[index] = resolved;
                }
            }
        }
    }

    private static net.minecraft.world.level.block.state.properties.EnumProperty<WallSide> wallProperty(Direction direction) {
        return switch (direction) {
            case NORTH -> BlockStateProperties.NORTH_WALL;
            case EAST -> BlockStateProperties.EAST_WALL;
            case SOUTH -> BlockStateProperties.SOUTH_WALL;
            case WEST -> BlockStateProperties.WEST_WALL;
            default -> throw new IllegalArgumentException("Expected a horizontal wall side");
        };
    }

    private static boolean raisePost(BlockState wall, BlockState above, VoxelShape cover) {
        if (above.getBlock() instanceof WallBlock && above.getValue(BlockStateProperties.UP)) return true;
        WallSide north = wall.getValue(BlockStateProperties.NORTH_WALL), south = wall.getValue(BlockStateProperties.SOUTH_WALL);
        WallSide east = wall.getValue(BlockStateProperties.EAST_WALL), west = wall.getValue(BlockStateProperties.WEST_WALL);
        boolean nsEmpty = north == WallSide.NONE && south == WallSide.NONE;
        boolean ewEmpty = east == WallSide.NONE && west == WallSide.NONE;
        if (nsEmpty && ewEmpty || (north == WallSide.NONE) != (south == WallSide.NONE)
                || (east == WallSide.NONE) != (west == WallSide.NONE)) return true;
        if (north == WallSide.TALL && south == WallSide.TALL || east == WallSide.TALL && west == WallSide.TALL) return false;
        return above.is(BlockTags.WALL_POST_OVERRIDE) || covered(cover, POST_TEST);
    }

    private static boolean covered(VoxelShape cover, VoxelShape test) {
        return !Shapes.joinIsNotEmpty(test, cover, BooleanOp.ONLY_FIRST);
    }
}
