package com.cheatprogram.gui;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.compat.NativeBuildingWorld;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.module.world.BuildingBlueprints;
import com.cheatprogram.parkour.ParkourClient;
import com.cheatprogram.render.BuildingModelPreview;
import com.cheatprogram.render.BuildingPreviewEnvironment;
import com.cheatprogram.render.BuildingPreviewEnvironment.Weather;
import com.cheatprogram.render.BuildingTutorialCamera;
import com.cheatprogram.render.ClassicBuildingTutorialCamera;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.BuildingFiles;
import com.cheatprogram.util.BuildingFiles.Blueprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-screen building visits use Minecraft's ordinary world, light and shader pipeline. */
public final class BuildingWorldPreview {
    private enum Phase { STARTING, VISITING, RETURNING }
    private record View(Vec3 position, float yaw, float pitch) {}
    private record TutorialShots(View[] views, View[] classicViews, double[] transitionRises,
                                 BuildingTutorialCamera planner) {}
    private static final Minecraft MC = Minecraft.getInstance();
    private static volatile Session session;

    private BuildingWorldPreview() {}

    public static boolean openTour(BuildingBlueprintScreen parent, Blueprint source) {
        return request(parent, source, null, null, 1, null);
    }

    static boolean openTutorial(BuildingBlueprintScreen parent, Blueprint source, int[] order, int[] ranks,
                                int[] sectionEnds, List<BuildingTutorialCamera.Frame> frames,
                                List<ClassicBuildingTutorialCamera.Frame> classicFrames, float speed) {
        return openTutorial(parent, source, order, ranks, sectionEnds, frames, classicFrames, speed, null);
    }

    static void openTutorialAtMark(BuildingBlueprintScreen parent, Blueprint source, int[] order, int[] ranks,
                                  int[] sectionEnds, List<BuildingTutorialCamera.Frame> frames,
                                  List<ClassicBuildingTutorialCamera.Frame> classicFrames, float speed, BlockPos origin) {
        ClientLevel level = MC.level;
        if (level == null || MC.player == null || !level.hasChunkAt(origin)) {
            Notifications.push(Lang.t("ui.building.tutorial"), Lang.t("ui.building.tutorialSiteNearby"), false);
            return;
        }
        BlockPos end = origin.offset(source.width() - 1, source.height() - 1, source.depth() - 1);
        if (!level.isInWorldBounds(origin) || !level.isInWorldBounds(end)
                || !level.getWorldBorder().isWithinBounds(origin) || !level.getWorldBorder().isWithinBounds(end)) {
            Notifications.push(Lang.t("ui.building.tutorial"), Lang.t("ui.building.tutorialSiteBounds"), false);
            return;
        }
        openTutorial(parent, source, order, ranks, sectionEnds, frames, classicFrames, speed, origin);
    }

    private static boolean openTutorial(BuildingBlueprintScreen parent, Blueprint source, int[] order, int[] ranks,
                                        int[] sectionEnds, List<BuildingTutorialCamera.Frame> frames,
                                        List<ClassicBuildingTutorialCamera.Frame> classicFrames, float speed, BlockPos site) {
        if (sectionEnds.length == 0 || frames.size() != sectionEnds.length
                || classicFrames.size() != sectionEnds.length) return false;
        var window = MC.getWindow();
        double aspect = (double) window.getWidth() / Math.max(1, window.getHeight());
        double fov = MC.options.fov().get();
        java.util.function.Consumer<Session> prepare = current -> {
            var planner = new BuildingTutorialCamera(source, order, ranks, fov, aspect);
            View[] views = new View[frames.size()];
            View[] classicViews = new View[frames.size()];
            double[] rises = new double[frames.size()];
            for (int i = 0; i < views.length; i++) {
                if (session != current || current.phase == Phase.RETURNING) return;
                var frame = frames.get(i);
                views[i] = new View(frame.eye(), frame.yaw(), frame.pitch());
                var classic = classicFrames.get(i);
                classicViews[i] = new View(classic.eye(), classic.yaw(), classic.pitch());
                if (i > 0) rises[i] = planner.transitionRise(views[i - 1].position, views[i].position, sectionEnds[i - 1]);
            }
            current.shots = new TutorialShots(views, classicViews, rises, planner);
        };
        if (site == null) return request(parent, source, order, sectionEnds, speed, prepare);
        if (session != null || MC.player == null || MC.level == null) return false;
        Session next = new Session(parent, source, order, sectionEnds, speed);
        next.siteLevel = MC.level;
        next.sitePlayer = MC.player;
        next.origin = site.immutable();
        next.sitePreview = new BuildingModelPreview();
        next.sitePreview.set(source);
        next.sitePreview.revealInOrder(order, ranks);
        next.phase = Phase.VISITING;
        session = next;
        prepareShots(next, prepare);
        KeyMapping.releaseAll();
        NativeBuildingWorld.screen(new BuildingNativePreviewControls());
        return true;
    }

    private static boolean request(BuildingBlueprintScreen parent, Blueprint source, int[] order,
                                   int[] ends, float speed, java.util.function.Consumer<Session> prepare) {
        if (source == null || session != null || MC.player == null || MC.level == null) return false;
        Session next = new Session(parent, source, order, ends, speed);
        NativeBuildingWorld.screen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                session = next;
                if (prepare != null) prepareShots(next, prepare);
                startWorld(next);
            } else NativeBuildingWorld.screen(parent.reopen());
        }, Component.literal(Lang.t("ui.building.native.title")),
                Component.literal(Lang.t("ui.building.native.confirm"))));
        return true;
    }

    private static void prepareShots(Session current, java.util.function.Consumer<Session> prepare) {
        BuildingFiles.IO.execute(() -> {
            try { prepare.accept(current); }
            catch (RuntimeException failure) { fail(current, failure, "ui.building.native.failed"); }
        });
    }

    public static boolean active() { return session != null; }
    public static boolean inPlace() { return session != null && session.siteLevel != null; }
    public static boolean tutorial() { return session != null && session.order != null; }
    public static boolean paused() { return session != null && session.paused; }
    public static float speed() { return session == null ? 1 : session.speed; }

    static void cameraModeChanged() {
        Session current = session;
        if (current == null || current.order == null) return;
        current.classicCamera = classicCameraSelected();
        if (!current.initialized || current.error != null) return;
        if (current.classicCamera) {
            current.goal = atOrigin(current.shots.classicViews[current.section], current.origin);
            current.transition = 1;
        } else {
            Vec3 from = current.camera.position.subtract(current.origin.getX(), current.origin.getY(), current.origin.getZ());
            View next = current.shots.views[current.section];
            double rise = current.shots.planner.transitionRise(from, next.position, current.cursor);
            startTransition(current, next, rise);
        }
    }

    private static boolean classicCameraSelected() {
        return ClientSettings.INSTANCE.buildingTutorialCamera.is(ClientSettings.TUTORIAL_CAMERA_CLASSIC);
    }

    public static void setSpeed(float value) {
        Session current = session;
        if (current != null && Float.isFinite(value)) current.speed = Math.clamp(value, .01f, 4);
    }
    public static float speedFraction(float speed) {
        return (float) Math.clamp(Math.log(Math.max(.01f, speed) / .01) / Math.log(400), 0, 1);
    }
    public static float speedAt(float fraction) {
        return Math.round(100 * .01f * (float) Math.pow(400, Math.clamp(fraction, 0, 1))) / 100f;
    }
    public static void togglePause() {
        Session current = session;
        if (current != null && current.order != null) current.paused = !current.paused;
    }
    public static boolean tutorialHudVisible() { return ClientSettings.INSTANCE.buildingTutorialHud.on(); }
    public static void toggleTutorialHud() { ClientSettings.INSTANCE.buildingTutorialHud.toggle(); }

    public static String status() {
        Session current = session;
        if (current == null) return "";
        if (current.error != null) return Lang.t(current.error);
        if (current.phase == Phase.RETURNING) return Lang.t("ui.building.native.returning");
        if (!current.initialized) return Lang.t("ui.building.native.loading");
        if (current.order == null) return Lang.t("ui.building.native.placed", current.cursor, current.source.count());
        return Lang.t("ui.building.tutorialRegion", Math.min(current.section + 1, current.ends.length),
                current.ends.length) + "  ·  " + current.cursor + " / " + current.order.length;
    }

    private static void startWorld(Session current) {
        try {
            current.name = "cp-building-preview-" + UUID.randomUUID();
            NativeBuildingWorld.disconnect(current.transitionMenu);
            NativeBuildingWorld.create(current.name, BuildingPreviewEnvironment.superflat(), current.transitionMenu);
        } catch (RuntimeException failure) {
            fail(current, failure, "ui.building.native.failed");
        }
    }

    /** Called from the regular client tick before enabled modules act on a world. */
    public static void tick() {
        Session current = session;
        if (current == null) return;
        if (current.siteLevel != null) {
            tickSite(current);
            return;
        }
        if (current.phase == Phase.RETURNING) {
            if (current.returnStarted) {
                if (!restorePage(current) && leftTransition(current)) session = null;
            } else if (MC.level != null || NativeBuildingWorld.screen() != current.transitionMenu) {
                session = null;
            }
            return;
        }
        IntegratedServer server = MC.getSingleplayerServer();
        if (!belongs(server, current.name) || MC.level == null || MC.player == null) {
            if (current.error == null && leftTransition(current)) {
                if (MC.level != null) {
                    session = null;
                    BuildingFiles.IO.execute(() -> deleteOwned(current.name));
                } else {
                    exit();
                }
            }
            return;
        }
        if (current.server != server) {
            current.server = server;
            current.identity = MC.player.getUUID();
        }
        if (current.phase == Phase.STARTING) {
            current.phase = Phase.VISITING;
            NativeBuildingWorld.screen(new BuildingNativePreviewControls());
        }
        // Scripted construction does not generate input for Minecraft's AFK frame limiter.
        if (current.order != null && current.error == null && MC.isWindowActive()) ParkourClient.inputActivity();
        if (!current.initialized && current.error == null) {
            enqueue(current, server);
            return;
        }
        if (current.error == null) {
            advanceTutorial(current);
            advanceCamera(current);
            enqueue(current, server);
        }
        if (NativeBuildingWorld.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
            NativeBuildingWorld.screen(new BuildingNativePreviewControls());
        }
    }

    private static void tickSite(Session current) {
        if (MC.level != current.siteLevel || MC.player != current.sitePlayer || !current.sitePlayer.isAlive()) {
            finishSite(current);
            if (NativeBuildingWorld.screen() instanceof BuildingNativePreviewControls) NativeBuildingWorld.screen(null);
            return;
        }
        KeyMapping.releaseAll();
        if (current.error != null || current.shots == null) return;
        if (MC.isWindowActive()) ParkourClient.inputActivity();
        if (!current.initialized) {
            initializeCamera(current);
            current.initialized = true;
        }
        if (NativeBuildingWorld.screen() != null) return;
        advanceTutorial(current);
        advanceCamera(current);
        current.sitePreview.revealThrough(current.limit);
    }

    public static void render(Render3D renderer) {
        Session current = session;
        if (current == null || current.siteLevel == null || current.siteLevel != MC.level || !current.initialized
                || current.error != null) return;
        current.sitePreview.drawWorld(renderer, current.origin, 1);
        current.cursor = current.sitePreview.renderedBlocks();
        if (current.sitePreview.failed()) fail(current, new IllegalStateException("Could not draw the marked-site preview"),
                "ui.building.previewError");
    }

    private static void finishSite(Session current) {
        session = null;
        current.sitePreview.close();
        KeyMapping.releaseAll();
    }

    private static void advanceCamera(Session current) {
        if (current.order == null || current.camera == null) return;
        current.previous = current.camera;
        if (current.classicCamera) {
            current.camera = ease(current.camera, current.goal, .14f);
            return;
        }
        if (current.transition >= 1) return;
        current.transition = Math.min(1, current.transition + .05f / current.transitionSeconds);
        float t = current.transition;
        float smooth = t * t * t * (t * (t * 6 - 15) + 10);
        View view = ease(current.transitionFrom, current.goal, smooth);
        double rise = current.transitionRise * 4 * smooth * (1 - smooth);
        current.camera = new View(view.position.add(0, rise, 0), view.yaw, view.pitch);
    }

    private static void enqueue(Session current, IntegratedServer server) {
        if (!current.workPending.compareAndSet(false, true)) return;
        server.execute(() -> {
            try {
                if (session != current || current.phase == Phase.RETURNING || !belongs(server, current.name)) return;
                ServerLevel level = server.overworld();
                if (!current.initialized) initialize(current, level);
                if (current.error == null) place(current, level);
            } catch (RuntimeException failure) {
                fail(current, failure, "ui.building.native.failed");
            } finally {
                current.workPending.set(false);
            }
        });
    }

    private static void initialize(Session current, ServerLevel level) {
        int bottom = NativeBuildingWorld.minY(level) + 4;
        if ((long) bottom + current.source.height() > NativeBuildingWorld.maxYExclusive(level)) {
            current.error = "ui.building.native.height";
            current.initialized = true;
            return;
        }
        if (current.order != null && current.shots == null) return;
        var player = level.getServer().getPlayerList().getPlayer(current.identity);
        if (player == null) return;
        current.origin = new BlockPos(0, bottom, 0);
        for (var state : current.source.palette()) current.palette.add(NativeBuildingWorld.decode(level, state));
        NativeBuildingWorld.prepare(level, current.origin);
        NativeBuildingWorld.setDayTime(level, BuildingPreviewEnvironment.time());
        applyWeather(level, BuildingPreviewEnvironment.weather());
        NativeBuildingWorld.fly(player);
        View overview = initializeCamera(current);
        NativeBuildingWorld.teleport(player, overview.position.x, overview.position.y - player.getEyeHeight(),
                overview.position.z, overview.yaw, overview.pitch);
        current.limit = current.order == null ? current.source.count() : 0;
        current.initialized = true;
    }

    private static View initializeCamera(Session current) {
        View initial = current.order == null || current.classicCamera ? overview(current.source) : current.shots.views[0];
        View view = atOrigin(initial, current.origin);
        current.camera = current.previous = current.goal = view;
        return view;
    }

    private static void place(Session current, ServerLevel level) {
        if (current.order != null && current.camera != null) moveServerPlayer(current, level, current.camera);
        long deadline = System.nanoTime() + 3_000_000L;
        int work = 0;
        int limit = current.limit;
        while (session == current && current.phase == Phase.VISITING && current.cursor < limit
                && work < 256 && System.nanoTime() < deadline) {
            int index = current.order == null ? current.cursor : current.order[current.cursor];
            BlockState state = current.palette.get(current.source.blocks()[index]);
            if (!state.isAir()) {
                work++;
                BlockPos point = current.origin.offset(index % current.source.width(),
                        index / (current.source.width() * current.source.depth()),
                        index / current.source.width() % current.source.depth());
                level.getChunkAt(point);
                level.setBlock(point, state, Block.UPDATE_ALL);
                NativeBuildingWorld.placeEntityData(level, point, current.source.blockEntities().get(index));
            }
            current.cursor++;
        }
    }

    private static void moveServerPlayer(Session current, ServerLevel level, View view) {
        var player = level.getServer().getPlayerList().getPlayer(current.identity);
        if (player == null) return;
        Vec3 feet = view.position.subtract(0, player.getEyeHeight(), 0);
        if (player.position().distanceToSqr(feet) > 1e-10) {
            player.setPos(feet.x, feet.y, feet.z);
            level.getChunkSource().move(player);
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.setYRot(view.yaw);
        player.setXRot(view.pitch);
        player.setYHeadRot(view.yaw);
        player.setYBodyRot(view.yaw);
    }

    private static void advanceTutorial(Session current) {
        if (current.order == null || current.paused || NativeBuildingWorld.screen() != null) return;
        int end = current.ends[current.section];
        int start = current.section == 0 ? 0 : current.ends[current.section - 1];
        if (current.cursor >= end) {
            current.hold += .05f * current.speed;
            if (current.hold < 1.5f || current.section + 1 >= current.ends.length) return;
            current.section++;
            current.hold = 0;
            current.elapsed = 0;
            if (!current.classicCamera) {
                startTransition(current, current.shots.views[current.section], current.shots.transitionRises[current.section]);
            }
            end = current.ends[current.section];
            start = current.ends[current.section - 1];
        }
        if (current.classicCamera) {
            if (current.elapsed == 0) current.goal = atOrigin(current.shots.classicViews[current.section], current.origin);
        } else if (current.transition < 1) return;
        current.elapsed += .05f * current.speed;
        double duration = Math.min(12, 2.3 + (end - start) / 600d);
        double delay = current.classicCamera ? 1.2 : .2;
        double progress = Math.clamp((current.elapsed - delay) / duration, 0, 1);
        current.limit = Math.max(current.limit, start + (int) Math.round((end - start) * progress));
    }

    private static void startTransition(Session current, View next, double rise) {
        current.transitionFrom = current.camera;
        current.goal = atOrigin(next, current.origin);
        double distance = current.camera.position.distanceTo(current.goal.position);
        current.transitionRise = rise;
        current.transitionSeconds = (float) Math.clamp(.75 + distance / 22 + rise / 24, .85, 3.4);
        current.transition = 0;
    }

    private static View overview(Blueprint source) {
        double centerX = source.width() * .5;
        double centerY = source.height() * .5;
        double centerZ = source.depth() * .5;
        double span = Math.max(source.width(), Math.max(source.height(), source.depth()));
        double distance = Math.max(7, span * .8);
        double angle = Math.toRadians(-32);
        Vec3 eye = new Vec3(centerX + Math.sin(angle) * distance,
                Math.max(source.height() + 3, centerY + distance * .7),
                centerZ - Math.cos(angle) * distance);
        float pitch = (float) Math.toDegrees(Math.atan2(eye.y - centerY, distance));
        return new View(eye, (float) Math.toDegrees(angle), pitch);
    }

    private static View atOrigin(View view, BlockPos origin) {
        return new View(view.position.add(origin.getX(), origin.getY(), origin.getZ()), view.yaw, view.pitch);
    }

    private static View ease(View from, View to, float amount) {
        if (from == null || to == null) return to;
        float yaw = from.yaw + net.minecraft.util.Mth.wrapDegrees(to.yaw - from.yaw) * amount;
        return new View(from.position.lerp(to.position, amount), yaw, from.pitch + (to.pitch - from.pitch) * amount);
    }

    public static boolean automatedCamera() {
        Session current = session;
        return current != null && current.phase == Phase.VISITING && current.order != null && current.initialized
                && current.camera != null && (current.siteLevel == null || MC.level == current.siteLevel && MC.player == current.sitePlayer);
    }

    /** The local body and the camera share the same tick poses, including those read by shaders. */
    public static boolean drivePlayer(LocalPlayer player) {
        Session current = session;
        if (!automatedCamera() || current.siteLevel != null || MC.player != player) return false;
        View before = current.previous;
        View view = current.camera;
        float eyeHeight = player.getEyeHeight();
        Vec3 feet = view.position.subtract(0, eyeHeight, 0);
        Vec3 oldFeet = before.position.subtract(0, eyeHeight, 0);
        player.setPos(feet.x, feet.y, feet.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.setYRot(view.yaw);
        player.setXRot(view.pitch);
        player.setYHeadRot(player.getYRot());
        player.setYBodyRot(player.getYRot());
        player.xo = player.xOld = oldFeet.x;
        player.yo = player.yOld = oldFeet.y;
        player.zo = player.zOld = oldFeet.z;
        player.yRotO = player.getYRot() - net.minecraft.util.Mth.wrapDegrees(view.yaw - before.yaw);
        player.xRotO = before.pitch;
        player.yHeadRotO = player.yBodyRotO = player.yRotO;
        // Keep the first-person renderer's rotation lag aligned with the scripted camera.
        player.xBobO = player.getViewXRot(0);
        player.xBob = player.getViewXRot(1);
        player.yBobO = player.getViewYRot(0);
        player.yBob = player.getViewYRot(1);
        return true;
    }
    public static Vec3 cameraPosition(float partial) {
        Session current = session;
        return current.previous.position.lerp(current.camera.position, Math.clamp(partial, 0, 1));
    }
    public static float cameraYaw(float partial) {
        Session current = session;
        return current.previous.yaw + net.minecraft.util.Mth.wrapDegrees(current.camera.yaw - current.previous.yaw)
                * Math.clamp(partial, 0, 1);
    }
    public static float cameraPitch(float partial) {
        Session current = session;
        return current.previous.pitch + (current.camera.pitch - current.previous.pitch) * Math.clamp(partial, 0, 1);
    }

    public static boolean key(int key, int action) {
        Session current = session;
        if (current == null) return false;
        if ((key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_F8)
                && action == GLFW.GLFW_PRESS && current.phase == Phase.VISITING && NativeBuildingWorld.screen() == null) {
            NativeBuildingWorld.screen(new BuildingNativePreviewControls());
            return true;
        }
        return NativeBuildingWorld.screen() == null && current.phase == Phase.VISITING
                && key == com.cheatprogram.core.ClientSettings.INSTANCE.guiKey;
    }

    public static void hud(HudContext hud) {
        Session current = session;
        if (current == null || current.phase != Phase.VISITING || NativeBuildingWorld.screen() != null) return;
        if (current.order != null && !tutorialHudVisible()) return;
        float width = Math.min(460, hud.width - 32);
        if (width < 44) return;
        hud.panel(14, 14, width, 64, 16);
        hud.c.textC(GuiBase.BOLD, hud.c.ellipsize(GuiBase.BOLD, status(), 17, width - 28), 28, 35, 17, Theme.TEXT);
        String hint = Lang.t(current.order == null ? "ui.building.native.controls" : "ui.building.native.tutorialControls");
        hud.c.textC(GuiBase.REG, hud.c.ellipsize(GuiBase.REG, hint, 14, width - 28), 28, 60, 14, Theme.TEXT_2);
    }

    public static void resume() {
        if (session != null) NativeBuildingWorld.screen(null);
    }

    public static void useTime(int tick) {
        Session current = session;
        if (current == null || current.siteLevel != null) return;
        BuildingPreviewEnvironment.setTime(tick);
        int time = BuildingPreviewEnvironment.time();
        IntegratedServer server = current.server;
        if (belongs(server, current.name)) server.execute(() -> {
            if (session == current && belongs(server, current.name))
                NativeBuildingWorld.setDayTime(server.overworld(), time);
        });
    }

    public static void useWeather(Weather weather) {
        Session current = session;
        if (current == null || current.siteLevel != null) return;
        BuildingPreviewEnvironment.setWeather(weather);
        IntegratedServer server = current.server;
        if (belongs(server, current.name)) server.execute(() -> {
            if (session == current && belongs(server, current.name)) applyWeather(server.overworld(), weather);
        });
    }

    private static void applyWeather(ServerLevel level, Weather weather) {
        NativeBuildingWorld.setWeather(level, weather != Weather.CLEAR, weather == Weather.THUNDER);
    }

    public static void restartTerrain() {
        Session current = session;
        if (current == null || current.siteLevel != null) return;
        NativeBuildingWorld.screen(new ConfirmScreen(confirmed -> {
            if (session != current) return;
            if (!confirmed) {
                NativeBuildingWorld.screen(new BuildingNativePreviewControls());
                return;
            }
            BuildingPreviewEnvironment.toggleTerrain();
            Session replacement = new Session(current.parent, current.source, current.order, current.ends,
                    current.speed, current.returnWorld, current.returnServer, current.shots);
            session = replacement;
            startWorld(replacement);
            BuildingFiles.IO.execute(() -> deleteOwned(current.name));
        }, Component.literal(Lang.t("ui.building.native.title")),
                Component.literal(Lang.t("ui.building.native.rebuild"))));
    }

    public static void exit() {
        Session current = session;
        if (current == null || current.phase == Phase.RETURNING) return;
        if (current.siteLevel != null) {
            finishSite(current);
            NativeBuildingWorld.screen(MC.level == current.siteLevel ? current.parent.reopen() : null);
            return;
        }
        current.phase = Phase.RETURNING;
        try {
            NativeBuildingWorld.disconnect(current.transitionMenu);
        } catch (RuntimeException failure) {
            CheatProgram.LOGGER.warn("Native preview disconnect failed", failure);
        }
        BuildingFiles.IO.execute(() -> {
            deleteOwned(current.name);
            MC.execute(() -> {
                if (session != current) return;
                if (MC.level != null || NativeBuildingWorld.screen() != current.transitionMenu) {
                    session = null;
                    return;
                }
                try {
                    current.returnStarted = true;
                    current.menuTicks = 0;
                    if (current.returnWorld != null) NativeBuildingWorld.open(current.returnWorld, current.transitionMenu);
                    else if (current.returnServer != null) NativeBuildingWorld.reconnect(current.returnServer, current.transitionMenu);
                    else session = null;
                } catch (RuntimeException failure) {
                    session = null;
                    Notifications.push(Lang.t("ui.building.native.title"), Lang.t("ui.building.native.returnFailed"), false);
                    CheatProgram.LOGGER.warn("Could not return from native preview", failure);
                }
            });
        });
    }

    private static boolean restorePage(Session current) {
        if (MC.level == null || MC.player == null) return false;
        if (current.returnWorld != null) {
            if (!belongs(MC.getSingleplayerServer(), current.returnWorld)) return false;
        } else {
            ServerData connected = MC.getCurrentServer();
            if (current.returnServer == null || connected == null
                    || !current.returnServer.ip.equalsIgnoreCase(connected.ip)) return false;
        }
        session = null;
        BuildingBlueprints module = ModuleManager.get(BuildingBlueprints.class);
        if (module != null) module.previewBuilding(current.source);
        NativeBuildingWorld.screen(current.parent.reopen());
        return true;
    }

    private static boolean leftTransition(Session current) {
        var screen = NativeBuildingWorld.screen();
        if (MC.level != null) return MC.player != null;
        if (screen instanceof DisconnectedScreen) return true;
        if (screen instanceof TitleScreen) return ++current.menuTicks >= 20;
        current.menuTicks = 0;
        return false;
    }

    private static void fail(Session current, RuntimeException error, String key) {
        current.error = key;
        CheatProgram.LOGGER.error("Native building preview failed", error);
        MC.execute(() -> {
            if (session == current && current.phase != Phase.RETURNING)
                NativeBuildingWorld.screen(new BuildingNativePreviewControls());
        });
    }

    private static void deleteOwned(String name) {
        if (name == null || !name.startsWith("cp-building-preview-")) return;
        try { NativeBuildingWorld.delete(name); }
        catch (Exception failure) { CheatProgram.LOGGER.warn("Could not clean up the temporary preview world", failure); }
    }

    private static boolean belongs(IntegratedServer server, String name) {
        return name != null && name.equals(worldName(server));
    }

    private static String worldName(IntegratedServer server) {
        if (server == null) return null;
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return root.getFileName().toString();
    }

    private static final class Session {
        final BuildingBlueprintScreen parent;
        final Blueprint source;
        final int[] order, ends;
        final String returnWorld;
        final ServerData returnServer;
        final TitleScreen transitionMenu = new TitleScreen();
        final List<BlockState> palette = new ArrayList<>();
        final AtomicBoolean workPending = new AtomicBoolean();
        ClientLevel siteLevel;
        LocalPlayer sitePlayer;
        BuildingModelPreview sitePreview;
        volatile String name, error;
        volatile Phase phase = Phase.STARTING;
        volatile IntegratedServer server;
        volatile UUID identity;
        volatile BlockPos origin;
        volatile boolean initialized, paused;
        volatile int cursor, limit;
        volatile float speed;
        volatile View camera, previous, goal;
        volatile TutorialShots shots;
        volatile boolean classicCamera = classicCameraSelected();
        View transitionFrom;
        double transitionRise;
        float transition = 1, transitionSeconds;
        int section, menuTicks;
        boolean returnStarted;
        float elapsed, hold;

        Session(BuildingBlueprintScreen parent, Blueprint source, int[] order, int[] ends, float speed) {
            this(parent, source, order, ends, speed, worldName(MC.getSingleplayerServer()),
                    MC.getSingleplayerServer() == null ? MC.getCurrentServer() : null, null);
        }

        Session(BuildingBlueprintScreen parent, Blueprint source, int[] order, int[] ends, float speed,
                String returnWorld, ServerData returnServer, TutorialShots preparedShots) {
            this.parent = parent;
            this.source = source;
            this.order = order;
            this.ends = ends;
            this.speed = speed;
            this.returnWorld = returnWorld;
            this.returnServer = returnServer;
            this.shots = preparedShots;
        }
    }
}
