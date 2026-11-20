package com.cheatprogram.dev;

import com.cheatprogram.core.setting.*;
import com.cheatprogram.module.visual.AppCast;
import com.google.gson.*;
import java.util.*;

/** Executed with source-extracted production methods and isolated lifecycle/config stubs. */
public final class AppCastSlotSettingsCheck {
    private static final Set<String> GLOBAL = Set.of("shareRelay", "shareQuality", "shareLocal", "screens",
            "interactionKey", "interaction", "keyboardOnly", "windowedLiveCompatible", "typing",
            "nativePointer", "pointerSpeed", "windowTitle", "windowProcess");

    public static void main(String[] arguments) throws Exception {
        AppCast cast = new AppCast();
        Map<String, JsonElement> defaults = snapshot(cast, false);
        JsonObject first = alternatives(cast, false);
        first.addProperty("nativeFrameRate", false);
        first.addProperty("nativeResolution", false);
        first.addProperty("fps", 30);
        first.addProperty("uploadBudget", 4);
        JsonObject explicit = new JsonObject();
        explicit.addProperty("nativeFrameRate", false);
        explicit.addProperty("nativeResolution", false);
        explicit.addProperty("fps", 120);
        explicit.addProperty("uploadBudget", 128);
        explicit.addProperty("resolution", 1920);
        explicit.addProperty("pixelLimit", 8);
        JsonObject invalid = JsonParser.parseString("""
                {"nativeFrameRate":"invalid","nativeResolution":{},"fps":"invalid",
                 "uploadBudget":null,"anchor":"invalid-mode","border":[]}
                """).getAsJsonObject();
        JsonArray slots = new JsonArray();
        for (JsonObject values : List.of(first, new JsonObject(), explicit, invalid)) {
            // A slot must never be allowed to replace global settings.
            alternatives(cast, true).asMap().forEach(values::add);
            values.addProperty("retiredOption", true);
            JsonObject slot = new JsonObject();
            slot.addProperty("title", "screen-" + slots.size());
            slot.addProperty("process", "process-" + slots.size());
            slot.add("settings", values);
            slots.add(slot);
        }
        text(cast, "screens").set(slots.toString());
        Map<String, JsonElement> global = snapshot(cast, true);
        cast.selectScreen(0);
        require(!bool(cast, "nativeFrameRate").on() && number(cast, "fps").intVal() == 30,
                "Explicit 30 FPS setting was not loaded");
        require(!bool(cast, "nativeResolution").on() && number(cast, "uploadBudget").intVal() == 4,
                "Explicit first-screen quality was not loaded");
        assertGlobals(cast, global);

        cast.selectScreen(1);
        require(snapshot(cast, false).equals(defaults), "Missing screen fields inherited the previous screen");
        require(bool(cast, "nativeFrameRate").on() && bool(cast, "nativeResolution").on(),
                "A legacy screen inherited a 30 FPS or reduced-resolution limit");
        assertGlobals(cast, global);

        cast.selectScreen(2);
        for (var entry : explicit.entrySet()) {
            if (defaults.containsKey(entry.getKey())) require(setting(cast, entry.getKey()).save().equals(entry.getValue()),
                    "Explicit second-screen setting was lost: " + entry.getKey());
        }
        JsonObject saved = JsonParser.parseString(text(cast, "screens").get()).getAsJsonArray()
                .get(1).getAsJsonObject().getAsJsonObject("settings");
        require(saved.get("nativeFrameRate").getAsBoolean() && saved.get("nativeResolution").getAsBoolean(),
                "Inherited limits were written back into a legacy screen");
        require(saved.get("uploadBudget").getAsInt() == 32, "Legacy screen saved the previous upload budget");
        require(!saved.has("retiredOption"), "Unknown retired controls were retained in screen settings");

        cast.selectScreen(3);
        for (String id : List.of("nativeFrameRate", "nativeResolution", "fps", "uploadBudget", "anchor", "border")) {
            require(setting(cast, id).save().equals(defaults.get(id)), "Invalid field retained another screen's value: " + id);
        }
        assertGlobals(cast, global);
        cast.selectScreen(0);
        require(!bool(cast, "nativeFrameRate").on() && number(cast, "fps").intVal() == 30,
                "Returning to an explicitly capped screen lost its setting");
        cast.selectScreen(1);
        require(snapshot(cast, false).equals(defaults), "Saved legacy defaults changed on return");

        // The first slot of old top-level configs must be copied before loadCurrent resets settings.
        AppCast legacy = new AppCast();
        alternatives(legacy, false).entrySet().forEach(entry -> setting(legacy, entry.getKey()).load(entry.getValue()));
        bool(legacy, "nativeFrameRate").set(false);
        text(legacy, "windowTitle").set("legacy-source");
        text(legacy, "windowProcess").set("legacy-process");
        Map<String, JsonElement> previous = snapshot(legacy, false);
        legacy.selectScreen(0);
        require(snapshot(legacy, false).equals(previous), "Top-level first-screen migration discarded explicit settings");
        require(text(legacy, "windowTitle").get().equals("legacy-source")
                        && text(legacy, "windowProcess").get().equals("legacy-process"),
                "Top-level source identity was lost");
        System.out.println("APPCAST_SETTINGS_PASS " + arguments[0]
                + " missing-fields explicit-30fps explicit-quality invalid-fields global-isolation saved-defaults top-level-migration");
    }

    private static JsonObject alternatives(AppCast cast, boolean global) {
        JsonObject values = new JsonObject();
        for (Setting<?> setting : cast.settings()) {
            if (GLOBAL.contains(setting.id) != global || setting.id.equals("screens")) continue;
            if (setting instanceof BoolSetting bool) values.addProperty(setting.id, !bool.on());
            else if (setting instanceof NumberSetting number) values.addProperty(setting.id,
                    number.val() == number.min ? number.max : number.min);
            else if (setting instanceof ModeSetting mode) values.addProperty(setting.id,
                    mode.modes.stream().filter(value -> !value.equals(mode.get())).findFirst().orElse(mode.get()));
            else values.addProperty(setting.id, "alternate-" + setting.id);
        }
        return values;
    }

    private static Map<String, JsonElement> snapshot(AppCast cast, boolean global) {
        Map<String, JsonElement> result = new LinkedHashMap<>();
        for (Setting<?> setting : cast.settings()) if (GLOBAL.contains(setting.id) == global
                && !Set.of("screens", "windowTitle", "windowProcess").contains(setting.id)) {
            result.put(setting.id, setting.save().deepCopy());
        }
        return result;
    }

    private static void assertGlobals(AppCast cast, Map<String, JsonElement> expected) {
        require(snapshot(cast, true).equals(expected), "Screen loading reset or replaced global settings");
    }

    private static Setting<?> setting(AppCast cast, String id) {
        return cast.settings().stream().filter(setting -> setting.id.equals(id)).findFirst().orElseThrow();
    }
    private static BoolSetting bool(AppCast cast, String id) { return (BoolSetting) setting(cast, id); }
    private static NumberSetting number(AppCast cast, String id) { return (NumberSetting) setting(cast, id); }
    private static TextSetting text(AppCast cast, String id) { return (TextSetting) setting(cast, id); }
    private static void require(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}
