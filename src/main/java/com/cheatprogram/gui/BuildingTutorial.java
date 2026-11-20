package com.cheatprogram.gui;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.BuildingModelPreview;
import com.cheatprogram.render.BuildingTutorialCamera;
import com.cheatprogram.render.ClassicBuildingTutorialCamera;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.BuildingFiles;
import com.cheatprogram.util.BuildingFiles.Blueprint;
import com.cheatprogram.util.BuildingInspection.Material;
import net.minecraft.network.chat.Component;
import org.joml.Vector3f;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** A preview-only tour of the blocks and materials in a building. */
final class BuildingTutorial {
    private static final float PROGRESS_TOGGLE_WIDTH = 168;

    @FunctionalInterface
    interface DrawPreview { void draw(BuildingModelPreview model, float x, float y, float width, float height); }

    private enum Phase { MATERIALS, BUILDING, COMPLETE }

    private record Region(int x0, int y0, int z0, int x1, int y1, int z1, int first, int end,
                          List<Material> materials) {
        String position() { return x0 + ", " + y0 + ", " + z0; }
        String dimensions() { return (x1 - x0 + 1) + " × " + (z1 - z0 + 1) + " × " + (y1 - y0 + 1); }
    }

    private record Plan(List<Region> regions, List<Material> total, int[] order, int[] ranks,
                        List<BuildingTutorialCamera.Frame> views, List<ClassicBuildingTutorialCamera.Frame> classicViews) {}

    private record Camera(float x, float y, float z, float zoom, float yaw, float pitch, Vec3 eye, float immersivePitch) {
        Camera toward(Camera next, float t) {
            return new Camera(Anim.lerp(x, next.x, t), Anim.lerp(y, next.y, t), Anim.lerp(z, next.z, t),
                    Anim.lerp(zoom, next.zoom, t), yaw + net.minecraft.util.Mth.wrapDegrees(next.yaw - yaw) * t,
                    Anim.lerp(pitch, next.pitch, t), eye.lerp(next.eye, t), Anim.lerp(immersivePitch, next.immersivePitch, t));
        }
    }

    private static final class Counts {
        final Map<String, Long> materials = new HashMap<>();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1, y1, z1, count, cursor;
        void add(String name, int x, int y, int z) {
            materials.merge(name, 1L, Long::sum);
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
            z1 = Math.max(z1, z);
            count++;
        }
    }

    private final GuiBase gui;
    private final Canvas canvas;
    private final BuildingModelPreview preview = new BuildingModelPreview();
    private final Map<String, BuildingModelPreview> materialModels = new LinkedHashMap<>(16, .75f, true);
    private final GuiBase.Scroller scroll = new GuiBase.Scroller();
    private volatile long revision;
    private Blueprint source;
    private Plan plan;
    private boolean failed, paused, immersive;
    private float playbackSpeed = 1;
    private Phase phase;
    private int region;
    private float elapsed, cameraStartedAt;
    private float diagonal;
    private Camera camera, from;

    BuildingTutorial(GuiBase gui) {
        this.gui = gui;
        canvas = gui.c;
    }

    boolean active() { return source != null; }
    boolean immersive() { return active() && immersive; }

    boolean back() {
        if (!immersive) return close();
        immersive = false;
        return true;
    }

    void open(Blueprint source) {
        close();
        this.source = source;
        diagonal = length(source.width(), source.height(), source.depth());
        camera = from = overview();
        phase = Phase.MATERIALS;
        long request = revision;
        double fov = gui.mc.options.fov().get(), aspect = windowAspect();
        BuildingFiles.IO.execute(() -> {
            try {
                Plan result = prepare(source, fov, aspect, () -> revision == request);
                gui.mc.execute(() -> {
                    if (revision == request) plan = result;
                });
            } catch (CancellationException ignored) {
                // This page was closed.
            } catch (RuntimeException error) {
                CheatProgram.LOGGER.warn("Building tutorial could not be prepared", error);
                gui.mc.execute(() -> {
                    if (revision == request) failed = true;
                });
            }
        });
    }

    boolean close() {
        if (!active()) return false;
        revision++;
        preview.close();
        materialModels.values().forEach(BuildingModelPreview::close);
        materialModels.clear();
        source = null;
        plan = null;
        failed = paused = immersive = false;
        region = 0;
        restartStep();
        return true;
    }

    private static Plan prepare(Blueprint source, double fov, double aspect, BooleanSupplier current) {
        int width = source.width(), depth = source.depth();
        int area = width * depth;
        int span = Math.clamp(1 + (Math.max(width, depth) - 1) / 4, 8, 14);
        int columns = 1 + (width - 1) / span;
        int rows = 1 + (depth - 1) / span;
        int bandHeight = 6;
        Counts[] zones = new Counts[columns * rows * (1 + (source.height() - 1) / bandHeight)];
        var states = new HashMap<String, net.minecraft.nbt.CompoundTag>();
        var total = new HashMap<String, Long>();
        String[] names = new String[source.palette().size()];
        for (int i = 0; i < names.length; i++) {
            var state = source.palette().get(i);
            String id = String.valueOf(state.get("Name")).replace("\"", "");
            if (id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")) continue;
            names[i] = id;
            states.putIfAbsent(id, state);
        }
        int count = 0;
        for (int index = 0; index < source.count(); index++) {
            if ((index & 65535) == 0 && !current.getAsBoolean()) throw new CancellationException();
            String id = names[source.blocks()[index]];
            if (id == null) continue;
            int x = index % width, z = index / width % depth;
            int zone = index / area / bandHeight * columns * rows + z / span * columns + x / span;
            if (zones[zone] == null) zones[zone] = new Counts();
            zones[zone].add(id, x, index / area, z);
            total.merge(id, 1L, Long::sum);
            count++;
        }
        var regions = new ArrayList<Region>();
        int offset = 0;
        for (Counts zone : zones) {
            if (zone == null) continue;
            zone.cursor = offset;
            regions.add(new Region(zone.x0, zone.y0, zone.z0, zone.x1, zone.y1, zone.z1,
                    offset, offset + zone.count, materials(zone.materials, states)));
            offset += zone.count;
        }
        if (count == 0) return new Plan(List.of(), List.of(), new int[0], new int[0], List.of(), List.of());
        if (!BuildingFiles.fitsMemory(source.count())) throw new IllegalStateException("Insufficient memory for building tutorial");
        int[] order = new int[count], ranks = new int[source.count()];
        // Stored block order is bottom-up. Within each section, build supports before the layers above them.
        for (int index = 0; index < source.count(); index++) {
            if ((index & 65535) == 0 && !current.getAsBoolean()) throw new CancellationException();
            if (names[source.blocks()[index]] == null) continue;
            int x = index % width, z = index / width % depth;
            int zone = index / area / bandHeight * columns * rows + z / span * columns + x / span;
            int rank = zones[zone].cursor++;
            order[rank] = index;
            ranks[index] = rank + 1;
        }
        var planner = new BuildingTutorialCamera(source, order, ranks, fov, aspect);
        var classicPlanner = new ClassicBuildingTutorialCamera(source, order, ranks);
        var views = new ArrayList<BuildingTutorialCamera.Frame>(regions.size());
        var classicViews = new ArrayList<ClassicBuildingTutorialCamera.Frame>(regions.size());
        BuildingTutorialCamera.Frame previous = null;
        for (Region zone : regions) {
            previous = planner.frame(zone.first(), zone.end(), previous, current);
            views.add(previous);
            classicViews.add(classicPlanner.frame(zone.first(), zone.end()));
        }
        return new Plan(List.copyOf(regions), materials(total, states), order, ranks, List.copyOf(views), List.copyOf(classicViews));
    }

    private static List<Material> materials(Map<String, Long> counts, Map<String, net.minecraft.nbt.CompoundTag> states) {
        return counts.entrySet().stream()
                .map(entry -> new Material(entry.getKey(), entry.getValue(), states.get(entry.getKey())))
                .sorted(Comparator.comparingLong(Material::count).reversed().thenComparing(Material::id))
                .toList();
    }

    void draw(float width, float height, DrawPreview drawPreview) {
        if (plan != null && !failed && gui.mc.isWindowActive()) {
            com.cheatprogram.parkour.ParkourClient.inputActivity();
        }
        if (immersive) {
            updateTimeline();
            drawImmersive(width, height, drawPreview);
            return;
        }
        gui.button("building.tutorial.back", Lang.t("ui.back"), Icon.BACK, 22, 18, 86, 28, false, this::close);
        float progressX = (width - PROGRESS_TOGGLE_WIDTH) / 2;
        canvas.textC(GuiBase.BOLD, canvas.ellipsize(GuiBase.BOLD, Lang.t("ui.building.tutorial"), 16, progressX - 136),
                124, 32, 16, Theme.TEXT);
        drawProgressToggle(gui, "building.tutorial.progress", progressX, 18, PROGRESS_TOGGLE_WIDTH);
        canvas.textRight(GuiBase.MED, canvas.ellipsize(GuiBase.MED, source.name(), 13,
                        width - progressX - PROGRESS_TOGGLE_WIDTH - 36),
                width - 24, 32, 13, Theme.TEXT_2);
        if (failed || plan == null || plan.regions().isEmpty()) {
            String status = failed ? "ui.building.previewError" : plan == null ? "ui.building.counting" : "ui.building.tutorialEmpty";
            canvas.textMid(GuiBase.REG, Lang.t(status), width / 2, height / 2, 13, Theme.TEXT_2);
            return;
        }
        updateTimeline();
        float footer = height - 43;
        gui.button("building.tutorial.pause", Lang.t(paused ? "ui.building.tutorialResume" : "ui.building.tutorialPause"),
                null, 22, footer, 92, 27, false, () -> paused = !paused);
        if (phase == Phase.MATERIALS) drawMaterials(width, footer, drawPreview);
        else drawConstruction(width, footer, drawPreview);
    }

    private void drawMaterials(float width, float footer, DrawPreview drawPreview) {
        boolean overall = region >= plan.regions().size();
        Region zone = overall ? null : plan.regions().get(region);
        String title = overall ? Lang.t("ui.building.tutorialTotal")
                : Lang.t("ui.building.tutorialRegion", region + 1, plan.regions().size());
        String size = overall ? source.width() + " × " + source.depth() + " × " + source.height() : zone.dimensions();
        canvas.textC(GuiBase.MED, title, 38, 70, 14, Theme.TEXT);
        canvas.textRight(GuiBase.REG, Lang.t("ui.building.tutorialSize", size), width - 38, 70, 12, Theme.TEXT_2);
        if (zone != null) canvas.textC(GuiBase.REG, Lang.t("ui.building.tutorialPosition", zone.position()), 38, 93, 12, Theme.TEXT_2);
        BuildingTutorialCameraControls.draw(gui, 36, 110, Math.min(330, width - 72), this::cameraModeChanged);
        float view = footer - 153;
        if (view >= 22) {
            drawList(overall ? plan.total() : zone.materials(), 38, 146, width - 76, view, true, drawPreview);
        }
        gui.button("building.tutorial.next", Lang.t(overall ? "ui.building.tutorialBegin" : "ui.building.tutorialNext"),
                Icon.CHEVRON_RIGHT, width - 156, footer, 134, 27, true, this::advanceMaterials);
        if (!overall) {
            gui.button("building.tutorial.total", Lang.t("ui.building.tutorialTotal"), null,
                    width - 293, footer, 130, 27, false, () -> {
                        region = plan.regions().size();
                        restartStep();
                    });
            gui.button("building.tutorial.skip", Lang.t("ui.building.tutorialSkip"), Icon.CHEVRON_RIGHT,
                    width - 468, footer, 168, 27, false, this::beginConstruction);
        }
        if (!paused && view >= 22 && elapsed >= 3.4f + scroll.max / 21f) advanceMaterials();
    }

    private void advanceMaterials() {
        restartStep();
        if (region < plan.regions().size()) {
            region++;
            return;
        }
        beginConstruction();
    }

    private void beginConstruction() {
        preview.set(null);
        preview.set(source);
        preview.revealInOrder(plan.order(), plan.ranks());
        phase = Phase.BUILDING;
        region = 0;
        from = camera = overview();
        paused = false;
        restartStep();
    }

    private void drawConstruction(float width, float footer, DrawPreview drawPreview) {
        boolean complete = phase == Phase.COMPLETE;
        Region zone = plan.regions().get(region);
        float cameraRow = 66 + BuildingPreviewControls.HEIGHT;
        float viewTop = cameraRow + BuildingTutorialCameraControls.HEIGHT + 8;
        float view = footer - viewTop - 18, previewWidth = width - 253;
        BuildingPreviewControls.draw(gui, 24, 60, Math.min(388, previewWidth - 6));
        BuildingTutorialCameraControls.draw(gui, 24, cameraRow, Math.min(350, previewWidth - 6), this::cameraModeChanged);
        if (view < 38) return;
        advanceCamera(complete, (previewWidth - 12) / (view - 12));
        applyCamera();
        reveal(zone, complete);
        canvas.lens(22, viewTop, previewWidth, view, 16, Theme.lift(.055f), Theme.lensGlass(canvas.scale));
        float x = 28, y = viewTop + 6, w = previewWidth - 12, h = view - 12;
        canvas.clip(x, y, w, h);
        try { drawPreview.draw(preview, canvas.px(x), canvas.py(y), w * canvas.scale, h * canvas.scale); }
        finally { canvas.unclip(); }
        float side = previewWidth + 38;
        canvas.textC(GuiBase.BOLD, Lang.t(complete ? "ui.building.tutorialComplete" : "ui.building.tutorialRegion",
                region + 1, plan.regions().size()), side, 77, 15, Theme.TEXT);
        if (preview.failed()) {
            paused = true;
            canvas.textMid(GuiBase.REG, Lang.t("ui.building.previewError"), x + w / 2, y + h / 2, 12, Theme.TEXT_2);
            return;
        }
        if (!complete) {
            canvas.textC(GuiBase.REG, Lang.t("ui.building.tutorialPosition", zone.position()), side, 101, 12.5f, Theme.TEXT_2);
            canvas.textC(GuiBase.REG, Lang.t("ui.building.tutorialSize", zone.dimensions()), side, 122, 12.5f, Theme.TEXT_2);
            int shown = Math.max(0, preview.renderedBlocks() - zone.first());
            canvas.textC(GuiBase.REG, shown + " / " + (zone.end() - zone.first()), side, 144, 12.5f, Theme.TEXT_2);
        }
        if (footer - 181 > 25) {
            drawList(complete ? plan.total() : zone.materials(), side, 167, width - side - 28, footer - 181, false, drawPreview);
        }
        drawSpeed(133, footer, 162);
        gui.button("building.tutorial.site", Lang.t("ui.building.tutorialSite"), Icon.CUBE,
                width - 415, footer, 123, 27, true, this::enterAtMark);
        gui.button("building.tutorial.enter", Lang.t("ui.building.tutorialSeparateWorld"), Icon.EYE,
                width - 286, footer, 130, 27, false, this::enterNative);
        if (complete) gui.button("building.tutorial.replay", Lang.t("ui.building.tutorialReplay"), Icon.REFRESH,
                width - 147, footer, 125, 27, true, this::beginConstruction);
        finishRegion(zone, complete);
    }

    private void enterNative() {
        if (gui instanceof BuildingBlueprintScreen parent
                && BuildingWorldPreview.openTutorial(parent, source, plan.order(), plan.ranks(),
                plan.regions().stream().mapToInt(Region::end).toArray(), plan.views(), plan.classicViews(), playbackSpeed)) return;
        immersive = true;
    }

    private void enterAtMark() {
        if (!(gui instanceof BuildingBlueprintScreen parent)) return;
        var module = com.cheatprogram.core.ModuleManager.get(com.cheatprogram.module.world.BuildingBlueprints.class);
        var origin = module == null ? null : module.tutorialOrigin();
        if (origin == null) {
            Notifications.push(Lang.t("ui.building.tutorial"), Lang.t("ui.building.tutorialSiteMark"), false);
            return;
        }
        BuildingWorldPreview.openTutorialAtMark(parent, source, plan.order(), plan.ranks(),
                plan.regions().stream().mapToInt(Region::end).toArray(), plan.views(), plan.classicViews(), playbackSpeed, origin);
    }

    private void updateTimeline() {
        if (!paused) elapsed += Math.min(gui.dt, .10f) * playbackSpeed;
    }

    private void advanceCamera(boolean complete, float aspect) {
        float move = Math.min(1, (elapsed - cameraStartedAt) / 1.2f);
        float easing = move * move * (3 - 2 * move);
        camera = from.toward(complete ? overview() : frame(aspect), easing);
    }

    private void cameraModeChanged() {
        from = camera;
        cameraStartedAt = elapsed;
    }

    private void reveal(Region zone, boolean complete) {
        if (complete) return;
        float duration = Math.min(9, 2.3f + (zone.end() - zone.first()) / 600f);
        float progress = Math.max(0, Math.min(1, (elapsed - 1.05f) / duration));
        preview.revealThrough(zone.first() + Math.round((zone.end() - zone.first()) * progress));
    }

    private void finishRegion(Region zone, boolean complete) {
        if (complete || paused || preview.renderedBlocks() < zone.end() || elapsed <= 3) return;
        from = camera;
        restartStep();
        if (region + 1 < plan.regions().size()) region++;
        else phase = Phase.COMPLETE;
    }

    private void drawImmersive(float width, float height, DrawPreview drawPreview) {
        boolean complete = phase == Phase.COMPLETE;
        Region zone = plan.regions().get(region);
        advanceCamera(complete, width / Math.max(1, height));
        reveal(zone, complete);
        applyImmersiveCamera();
        canvas.rect(0, 0, width, height, 0xff151d2a);
        drawPreview.draw(preview, 0, 0, width * canvas.scale, height * canvas.scale);
        // Keep the center clear while the camera shows the construction.
        Backdrop.capture(.15f);
        R2D.applyState();
        boolean showHud = BuildingWorldPreview.tutorialHudVisible();
        if (showHud) {
            canvas.lens(15, 13, 293, 62, 14, Theme.lift(.07f), Theme.lensGlass(canvas.scale));
            canvas.textC(GuiBase.BOLD, Lang.t(complete ? "ui.building.tutorialComplete" : "ui.building.tutorialRegion",
                    region + 1, plan.regions().size()), 27, 30, 14, Theme.TEXT);
            String status = complete ? source.name() : zone.position() + "   ·   " + zone.dimensions()
                    + "   ·   " + Math.max(0, preview.renderedBlocks() - zone.first()) + " / " + (zone.end() - zone.first());
            canvas.textC(GuiBase.REG, canvas.ellipsize(GuiBase.REG, status, 11.5f, 264), 27, 53, 11.5f, Theme.TEXT_2);
        }
        gui.button("building.tutorial.leave", Lang.t("ui.building.tutorialLeave"), Icon.BACK,
                15, 83, 133, 28, false, this::back);
        drawProgressToggle(gui, "building.tutorial.progress", 154, 83, PROGRESS_TOGGLE_WIDTH);
        BuildingTutorialCameraControls.draw(gui, 15, 116, Math.min(350, width - 30), this::cameraModeChanged);
        float footer = height - 43;
        canvas.lens(14, footer - 6, 272, 39, 14, Theme.lift(.07f), Theme.lensGlass(canvas.scale));
        gui.button("building.tutorial.pause", Lang.t(paused ? "ui.building.tutorialResume" : "ui.building.tutorialPause"),
                null, 22, footer, 84, 27, false, () -> paused = !paused);
        drawSpeed(120, footer, 152);
        if (complete) gui.button("building.tutorial.replay", Lang.t("ui.building.tutorialReplay"), Icon.REFRESH,
                15, 151, 134, 28, false, () -> {
                    beginConstruction();
                    immersive = true;
                });
        BuildingPreviewControls.draw(gui, width - 340, height - BuildingPreviewControls.HEIGHT - 15, 324);
        if (preview.failed()) {
            paused = true;
            canvas.textMid(GuiBase.REG, Lang.t("ui.building.previewError"), width / 2, height / 2, 13, Theme.TEXT);
        } else finishRegion(zone, complete);
    }

    static void drawProgressToggle(GuiBase gui, String id, float x, float y, float width) {
        final float height = 28;
        float hover = gui.hoverAnim(id, gui.over(x, y, width, height));
        gui.c.lens(x, y, width, height, height / 2, Theme.lift(.08f + .07f * hover), Theme.lensGlass(gui.c.scale));
        String label = gui.c.ellipsize(GuiBase.MED, Lang.t("ui.buildingTutorialHud"), 11.5f, width - 54);
        gui.c.textC(GuiBase.MED, label, x + 12, y + height / 2, 11.5f, Theme.TEXT);
        gui.toggle(id + ".switch", x + width - 40, y + (height - 15) / 2,
                BuildingWorldPreview.tutorialHudVisible(), 1);
        gui.hit(x, y, width, height).left(BuildingWorldPreview::toggleTutorialHud);
    }

    private void applyImmersiveCamera() {
        preview.touring = true;
        preview.eyeX = (float) camera.eye().x;
        preview.eyeY = (float) camera.eye().y;
        preview.eyeZ = (float) camera.eye().z;
        preview.yaw = camera.yaw();
        preview.pitch = camera.immersivePitch();
    }

    private void drawSpeed(float x, float y, float width) {
        String speed = String.format(java.util.Locale.ROOT, "%.2f×", playbackSpeed);
        canvas.textC(GuiBase.REG, Lang.t("ui.building.tutorialSpeed"), x, y + 5, 11, Theme.TEXT_2);
        canvas.textRight(GuiBase.MED, speed, x + width, y + 5, 11, Theme.TEXT);
        float fraction = BuildingWorldPreview.speedFraction(playbackSpeed);
        canvas.rrect(x, y + 17, width, 2, 1, 0x42ffffff);
        canvas.rrect(x, y + 17, width * fraction, 2, 1, Theme.TEXT_2);
        canvas.circle(x + width * fraction, y + 18, 4, Theme.TEXT);
        gui.hit(x - 4, y + 9, width + 8, 20).left(() -> {
            gui.drag = (px, py) -> playbackSpeed = BuildingWorldPreview.speedAt((px - x) / width);
            gui.drag.drag(gui.mx, gui.my);
        });
    }

    private void drawList(List<Material> materials, float x, float y, float width, float height,
                          boolean automatic, DrawPreview drawPreview) {
        final float row = 36;
        scroll.max = Math.max(0, materials.size() * row - height);
        if (automatic) scroll.target = Math.min(scroll.max, Math.max(0, elapsed - 1.6f) * 21);
        scroll.update(gui.dt);
        if (!automatic) gui.hit(x, y, width, height).scroll(scroll::scroll);
        canvas.lens(x - 6, y - 4, width + 12, height + 8, 13, Theme.lift(.045f), Theme.lensGlass(canvas.scale));
        canvas.clip(x, y, width, height);
        try {
            int first = Math.max(0, (int) (scroll.get() / row));
            int last = Math.min(materials.size(), first + (int) (height / row) + 2);
            for (int i = first; i < last; i++) {
                Material material = materials.get(i);
                String key = "block." + material.id().replace(':', '.').replace('/', '.');
                String name = Component.translatable(key).getString();
                if (key.equals(name)) name = material.id();
                float line = y + i * row - scroll.get();
                if ((i & 1) != 0) canvas.rrect(x, line, width, row - 1, 6, 0x12ffffff);
                if (line + row <= y || line >= y + height) continue;
                drawMaterial(material, x + 5, line + 3, 30, drawPreview);
                canvas.textC(GuiBase.REG, canvas.ellipsize(GuiBase.REG, name, 12.5f, width - 124),
                        x + 43, line + row / 2, 12.5f, Theme.TEXT);
                canvas.textRight(GuiBase.MED, "× " + material.count(), x + width - 9, line + row / 2, 12.5f, Theme.TEXT_2);
            }
        } finally { canvas.unclip(); }
        if (!automatic) gui.panelScrollBar("building.tutorial.scroll", scroll, x + width + 3, y, height);
    }

    private void drawMaterial(Material material, float x, float y, float size, DrawPreview drawPreview) {
        BuildingModelPreview model = materialModels.get(material.id());
        if (model == null) {
            if (materialModels.size() >= 72) {
                var oldest = materialModels.entrySet().iterator();
                oldest.next().getValue().close();
                oldest.remove();
            }
            model = new BuildingModelPreview();
            model.materialIcon();
            model.set(new Blueprint(material.id(), 1, 1, 1, List.of(material.state()), new int[]{0}, Map.of()));
            materialModels.put(material.id(), model);
        }
        drawPreview.draw(model, canvas.px(x), canvas.py(y), size * canvas.scale, size * canvas.scale);
    }

    private void restartStep() {
        elapsed = 0;
        cameraStartedAt = 0;
        scroll.target = 0;
        scroll.value.snap(0);
    }

    private Camera overview() {
        float x = source.width() / 2f, y = source.height() / 2f, z = source.depth() / 2f;
        if (classicCamera()) return classicCamera(x, y, z, .95f, -32, 27);
        double distance = Math.max(6, diagonal * .72);
        double yaw = Math.toRadians(-32), pitch = Math.toRadians(27);
        Vec3 eye = new Vec3(x - Math.sin(yaw) * distance * Math.cos(pitch), y + Math.sin(pitch) * distance,
                z + Math.cos(yaw) * distance * Math.cos(pitch));
        return new Camera(x, y, z, .95f, -32, 27, eye, 27);
    }

    private Camera frame(float aspect) {
        if (classicCamera()) {
            var view = plan.classicViews().get(region);
            float zoom = Math.clamp(diagonal / (float) (view.span() * 1.9), .3f, 25);
            return classicCamera((float) view.target().x, (float) view.target().y, (float) view.target().z,
                    zoom, view.yaw() - 180, view.pitch());
        }
        var view = plan.views().get(region);
        float zoom = (float) Math.clamp(view.insetScale(aspect) * diagonal / 1.6, .05, 25);
        return new Camera((float) view.target().x, (float) view.target().y, (float) view.target().z,
                zoom, view.yaw() - 180, view.pitch(), view.eye(), view.pitch());
    }

    private static boolean classicCamera() {
        return ClientSettings.INSTANCE.buildingTutorialCamera.is(ClientSettings.TUTORIAL_CAMERA_CLASSIC);
    }

    private Camera classicCamera(float x, float y, float z, float zoom, float yaw, float pitch) {
        double angle = Math.toRadians(yaw);
        float offset = Math.max(5, diagonal * .42f / zoom);
        float eyeY = Math.max(source.height() + 2, y + offset * .75f);
        Vec3 eye = new Vec3(x - Math.sin(angle) * offset, eyeY, z + Math.cos(angle) * offset);
        return new Camera(x, y, z, zoom, yaw, pitch, eye, (float) Math.toDegrees(Math.atan2(eyeY - y, offset)));
    }

    private double windowAspect() {
        var window = gui.mc.getWindow();
        return (double) window.getWidth() / Math.max(1, window.getHeight());
    }

    private void applyCamera() {
        preview.touring = false;
        preview.yaw = camera.yaw();
        preview.pitch = camera.pitch();
        preview.zoom = camera.zoom();
        float scale = 1.6f * camera.zoom() / Math.max(1, diagonal);
        Vector3f center = new Vector3f(camera.x() - source.width() / 2f, camera.y() - source.height() / 2f,
                camera.z() - source.depth() / 2f).mul(scale).rotateY((float) Math.toRadians(camera.yaw()))
                .rotateX((float) Math.toRadians(camera.pitch()));
        preview.panX = -center.x;
        preview.panY = center.y;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
    }
}
