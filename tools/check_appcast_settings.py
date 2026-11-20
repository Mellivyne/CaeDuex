"""Run the shipped screen-setting methods in isolated JVMs; never loads a game or user config."""
from pathlib import Path
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/appcast-settings-check"
JAVA = Path(os.environ.get("JAVA_HOME", "D:/Java/jdk-27")) / "bin"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson"
GSON = next(CACHE.rglob("gson-*.jar"))
TARGETS = ["src/main", *[f"versions/{name}" for name in (
    "legacy-1.20.4", "modern-1.21.10", "modern-1.21.11", "unobfuscated-26.2")]]


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    # These methods contain no braces inside string literals or comments.
    for end in range(opening + 1, len(source)):
        depth += (source[end] == "{") - (source[end] == "}")
        if depth == 0:
            return source[start:end + 1]
    raise AssertionError(f"Unterminated method: {signature}")


stubs = {
    "com/cheatprogram/core/Config.java": """package com.cheatprogram.core;
        public final class Config { public static void markDirty() {} public static boolean persistenceAllowed(){return true;} }""",
    "com/cheatprogram/core/Lang.java": """package com.cheatprogram.core;
        public final class Lang {
            public static String t(String value){return value;}
            public static String setting(String owner,String value){return value;}
            public static String summary(String value,String name){return value;}
            public static String mode(String value){return value;}
            public static String unit(String value){return value;}
        }""",
    "com/cheatprogram/core/Module.java": """package com.cheatprogram.core;
        import com.cheatprogram.core.setting.Setting;
        public class Module {
            public final String id="appcast";
            private final java.util.List<Setting<?>> settings=new java.util.ArrayList<>();
            protected <S extends Setting<?>> S add(S setting){settings.add(setting);return setting;}
            public java.util.List<Setting<?>> settings(){return settings;}
        }""",
    "org/lwjgl/glfw/GLFW.java": """package org.lwjgl.glfw;
        public final class GLFW { public static final int GLFW_KEY_RIGHT_ALT=346, GLFW_KEY_LAST=348; }""",
}
stub_sources = []
for relative, content in stubs.items():
    path = OUT / "stubs" / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    stub_sources.append(path)
setting_sources = [ROOT / "src/main/java/com/cheatprogram/core/setting" / f"{name}.java"
                   for name in ("Setting", "BoolSetting", "ModeSetting", "NumberSetting", "SliderNumberSetting", "TextSetting")]
check_source = ROOT / "verification/java/com/cheatprogram/dev/AppCastSlotSettingsCheck.java"

for target in TARGETS:
    source = (ROOT / target / "java/com/cheatprogram/module/visual/AppCast.java").read_text(encoding="utf-8")
    # Copy the actual declarations and methods rather than duplicating the loading logic in a test model.
    fields = "\n".join(line for line in source.splitlines()
                       if re.match(r"\s*private final \w+Setting\s+\w+\s*=\s*add\(", line))
    methods = "\n".join(method(source, signature) for signature in (
        "private boolean perScreen(", "private void ensureLoaded(", "private Slot current(",
        "private void loadCurrent(", "private void saveCurrent(", "private void saveScreens(",
        "public void selectScreen(", "private static String mode("))
    fixture = """package com.cheatprogram.module.visual;
        import com.cheatprogram.core.Module;
        import com.cheatprogram.core.Config;
        import com.cheatprogram.core.setting.*;
        import com.google.gson.*;
        import java.util.*;
        import org.lwjgl.glfw.GLFW;
        public final class AppCast extends Module {
            private static final double OFFSET_LIMIT=200;
            private static final Gson GSON=new Gson();
            private final List<Slot> slots=new ArrayList<>();
            private int selectedIndex;
            private boolean loaded;
            private String stored="";
            private static final class Capture { void stop(){} void cancelWindowFollow(){} }
            private static final class Slot {
                final Capture capture=new Capture();
                final Map<String,JsonElement> values=new LinkedHashMap<>();
                String title="",process="",sourceKind="window",sourceId="";
                Object base;
                WorldPoint buildingAnchor;
            }
            private static final class WorldPoint {
                static WorldPoint load(JsonObject value,String key){return null;}
                JsonObject save(){throw new AssertionError("Unexpected world point");}
            }
            private void stopSharing(){}
            private void leaveInteraction(){}
            private void placeAhead(Slot slot){throw new AssertionError("Unexpected placement");}
        """ + fields + "\n" + methods + "\n}"
    directory = OUT / target.replace("/", "-")
    path = directory / "source/com/cheatprogram/module/visual/AppCast.java"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(fixture, encoding="utf-8")
    classes = directory / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(JAVA / "javac.exe"), "-encoding", "UTF-8", "-cp", str(GSON), "-d", str(classes),
                    *map(str, stub_sources + setting_sources + [path, check_source])], check=True)
    subprocess.run([str(JAVA / "java.exe"), "-cp", str(classes) + os.pathsep + str(GSON),
                    "com.cheatprogram.dev.AppCastSlotSettingsCheck", target], check=True)
