"""Run clipping math against Minecraft 26.2/26.3's real native render-pass validator."""
from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def method_body(text, name):
    start = re.search(r"(?:public|private) static void " + name + r"\(", text).start()
    brace = text.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def canvas_sources(work):
    paths = [ROOT / "src/main/java/com/cheatprogram/render/R2D.java",
             *sorted((ROOT / "versions").glob("*/java/com/cheatprogram/render/R2D.java"))]
    sources = []
    for index, path in enumerate(paths):
        text = path.read_text("utf-8")
        methods = "\n".join(method_body(text, name) for name in ["pushClip", "popClip", "applyScissor"])
        methods = methods.replace("LegacyRenderSystem.", "Probe.").replace("RenderSystem.", "Probe.")
        source = work / f"CanvasClipAdapter{index}.java"
        source.write_text(f"""package com.cheatprogram.render;
import java.util.*;
final class CanvasClipAdapter{index} {{
 static final Deque<FramebufferClip> SCISSORS=new ArrayDeque<>();
 static int fbWidth=853,fbHeight=479;
 static class Probe {{
  static int x,y,w,h;static boolean enabled;
  static void enableScissor(int a,int b,int c,int d){{
   if(a<0||b<0||c<0||d<0||(long)a+c>fbWidth||(long)b+d>fbHeight)throw new AssertionError("Canvas submitted invalid bounds");
   x=a;y=b;w=c;h=d;enabled=true;
  }}
  static void disableScissor(){{enabled=false;}}
 }}
 {methods}
 public static void check(){{
  pushClip(20,20,300,100);int x=Probe.x,y=Probe.y,w=Probe.w,h=Probe.h;
  pushClip(30,400,100,20);if(Probe.w!=0&&Probe.h!=0)throw new AssertionError("Off-list row was not hidden");popClip();
  if(Probe.x!=x||Probe.y!=y||Probe.w!=w||Probe.h!=h)throw new AssertionError("Parent clip not restored");
  pushClip(Float.NaN,0,100,100);if(Probe.w!=0&&Probe.h!=0)throw new AssertionError("Invalid clip was visible");popClip();popClip();
  if(Probe.enabled||!SCISSORS.isEmpty())throw new AssertionError("Clip leaked after pop");
  pushClip(-40,-50,100,100);if(Probe.w!=60||Probe.h!=50)throw new AssertionError("Partial clip changed extent");popClip();
 }}
}}
""", "utf-8")
        sources.append(source)
    checker = work / "CanvasClipAdaptersCheck.java"
    checker.write_text("package com.cheatprogram.render; public class CanvasClipAdaptersCheck {public static void main(String[] args){"
                       + "".join(f"CanvasClipAdapter{i}.check();" for i in range(len(paths)))
                       + f'System.out.println("PASS: all {len(paths)} production canvas adapters preserve empty, partial and nested clip state");'
                       + "}}", "utf-8")
    return sources + [checker]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path, required=True, help="26.2/26.3 instance containing its version JSON and game JAR")
    args = parser.parse_args()
    java_home = os.environ.get("JAVA25_HOME") or os.environ.get("JAVA_HOME")
    if not java_home:
        parser.error("Set JAVA25_HOME to a Java 25 or newer JDK")
    java = Path(java_home) / "bin"
    instance = args.instance.resolve()
    base = instance.parent.parent
    metadata = json.loads((instance / (instance.name + ".json")).read_text("utf-8-sig"))
    libraries = [instance / (instance.name + ".jar")]
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    for library in metadata["libraries"]:
        parts = library["name"].split(":")
        group, name, version = parts[:3]
        suffix = "-" + parts[3] if len(parts) > 3 else ""
        relative = library.get("downloads", {}).get("artifact", {}).get("path",
            group.replace(".", "/") + "/" + name + "/" + version + "/" + name + "-" + version + suffix + ".jar")
        path = base / "libraries" / relative
        if path.is_file():
            libraries.append(path)
        elif len(parts) == 3:
            matches = list((cache / group / name / version).rglob(name + "-" + version + ".jar"))
            if len(matches) == 1:
                libraries.append(matches[0])
    with tempfile.TemporaryDirectory(prefix="cp-clip-check-") as temporary:
        classes = Path(temporary) / "classes"
        cp = os.pathsep.join(map(str, libraries))
        sources = [ROOT / "src/main/java/com/cheatprogram/render/FramebufferClip.java",
                   ROOT / "tools/tests/FramebufferClipCheck.java"]
        sources += canvas_sources(Path(temporary))
        subprocess.run([str(java / "javac.exe"), "-proc:none", "--release", "25",
                        "-encoding", "UTF-8", "-cp", cp, "-d", str(classes), *map(str, sources)], check=True)
        subprocess.run([str(java / "java.exe"), "-cp", str(classes) + os.pathsep + cp,
                        "com.cheatprogram.render.FramebufferClipCheck"], check=True)
        subprocess.run([str(java / "java.exe"), "-cp", str(classes),
                        "com.cheatprogram.render.CanvasClipAdaptersCheck"], check=True)


if __name__ == "__main__":
    main()
