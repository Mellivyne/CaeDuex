package com.cheatprogram.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.flag.FeatureFlags;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Version-specific native world entry points for a separately owned building preview. */
public final class NativeBuildingWorld {
    private static final Pattern OWNED_NAME = Pattern.compile(
            "cp-building-preview-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private NativeBuildingWorld() {}

    public static void create(String name, boolean superflat, Screen parent) {
        checkOwned(name);
        Minecraft mc = Minecraft.getInstance();
        var rules = new GameRules();
        var settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(0L, false, false), registries -> {
            var defaults = FlatLevelGeneratorSettings.getDefault(registries.lookupOrThrow(Registries.BIOME),
                    registries.lookupOrThrow(Registries.STRUCTURE_SET), registries.lookupOrThrow(Registries.PLACED_FEATURE));
            List<FlatLayerInfo> layers = superflat
                    ? List.of(new FlatLayerInfo(1, Blocks.BEDROCK), new FlatLayerInfo(2, Blocks.DIRT),
                            new FlatLayerInfo(1, Blocks.GRASS_BLOCK))
                    : List.of(new FlatLayerInfo(1, Blocks.AIR));
            var flat = defaults.withBiomeAndLayers(layers, Optional.of(HolderSet.direct()), defaults.getBiome());
            return WorldPresets.createNormalWorldDimensions(registries)
                    .replaceOverworldGenerator(registries, new FlatLevelSource(flat));
        });
    }

    public static void open(String name, Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.createWorldOpenFlows().loadLevel(parent, name);
    }

    public static void reconnect(ServerData server, Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(server.ip), server, false);
    }

    public static void disconnect(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        mc.clearLevel(screen);
    }

    public static void screen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    public static Screen screen() {
        return Minecraft.getInstance().screen;
    }

    /** Call on the preview world's server thread once the world is ready. */
    public static void prepare(ServerLevel level, BlockPos spawn) {
        var rules = level.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.setDefaultSpawnPos(spawn, 0f);
    }

    public static void setDayTime(ServerLevel level, long ticks) {
        level.setDayTime(ticks);
    }

    public static void setWeather(ServerLevel level, boolean raining, boolean thundering) {
        level.setWeatherParameters(0, 0, raining, thundering);
    }

    public static void teleport(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        player.connection.teleport(x, y, z, yaw, pitch);
    }

    public static void fly(ServerPlayer player) {
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.getAbilities().setFlyingSpeed(.10f);
        player.onUpdateAbilities();
    }

    public static int minY(ServerLevel level) { return level.getMinBuildHeight(); }
    public static int maxYExclusive(ServerLevel level) { return level.getMaxBuildHeight(); }

    public static net.minecraft.world.level.block.state.BlockState decode(ServerLevel level, CompoundTag state) {
        return net.minecraft.nbt.NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), state);
    }

    public static void placeEntityData(ServerLevel level, BlockPos pos, CompoundTag data) {
        if (data == null) return;
        var entity = level.getBlockEntity(pos);
        if (entity == null) return;
        CompoundTag copy = data.copy();
        copy.putInt("x", pos.getX());
        copy.putInt("y", pos.getY());
        copy.putInt("z", pos.getZ());
        entity.load(copy);
        entity.setChanged();
        var state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    /** Uses vanilla storage validation and its session lock before deleting this feature's private world. */
    public static void delete(String name) throws IOException {
        checkOwned(name);
        var source = Minecraft.getInstance().getLevelSource();
        if (!source.levelExists(name)) return;
        try (var access = source.validateAndCreateAccess(name)) {
            access.deleteLevel();
        } catch (net.minecraft.world.level.validation.ContentValidationException invalid) {
            throw new IOException("Building preview world contains invalid paths", invalid);
        }
    }

    private static void checkOwned(String name) {
        if (name == null || !OWNED_NAME.matcher(name).matches())
            throw new IllegalArgumentException("Invalid building preview world");
    }
}
