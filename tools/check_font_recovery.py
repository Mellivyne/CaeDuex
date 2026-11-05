"""Fault-inject the production font fallback methods without opening Minecraft."""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def method(text):
    start = text.index("private static float vanillaText(")
    brace = text.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    args = parser.parse_args()
    java = Path(os.environ.get("JAVA21_HOME") or os.environ["JAVA_HOME"]) / "bin"
    paths = [args.source_root / "src/main/java/com/cheatprogram/render/R2D.java",
             *sorted((args.source_root / "versions").glob("*/java/com/cheatprogram/render/R2D.java"))]
    with tempfile.TemporaryDirectory(prefix="cp-font-recovery-") as temporary:
        work = Path(temporary)
        fixture = work / "FontRecoveryCheck.java"
        text = r"""package com.cheatprogram.render;
import java.util.*;
final class FakePose {
 final Deque<float[]> stack=new ArrayDeque<>();float x=0,y=0,sx=1,sy=1;
 void pushPose(){stack.push(new float[]{x,y,sx,sy});}void pushMatrix(){pushPose();}
 void popPose(){var old=stack.pop();x=old[0];y=old[1];sx=old[2];sy=old[3];}void popMatrix(){popPose();}
 void translate(float x,float y){this.x+=x;this.y+=y;}void translate(float x,float y,float z){translate(x,y);}
 void scale(float x,float y){sx*=x;sy*=y;}void scale(float x,float y,float z){scale(x,y);}
}
final class FakeGraphics {
 final FakePose pose=new FakePose();boolean fail;int draws,flushes;
 FakePose pose(){return pose;}
 void drawString(Object font,Object text,int x,int y,int color,boolean shadow){if(fail)throw new IllegalStateException("injected glyph failure");draws++;}
 void flush(){flushes++;}
}
final class Fonts {
 enum Face {REGULAR}
 static int pixelSize(float size){return Math.max(1,Math.round(size));}
 static Object vanillaComponent(Face face,String text){return text;}
 static float vanillaWidth(Face face,String text,float size){return text.length()*size;}
}
final class FakeMinecraft {Object font=new Object();}
"""
        for index, path in enumerate(paths):
            body = method(path.read_text("utf-8")).replace("com.cheatprogram.compat.GuiBridge.", "Bridge.")
            text += f"""
final class FontAdapter{index} {{
 static FakeGraphics graphics=new FakeGraphics();static FakeMinecraft MC=new FakeMinecraft();
 static float guiScale=1.25f;static boolean textShadow=true;static int applied;
 static void applyState(){{applied++;}}
 static class Bridge {{
  static void drawString(FakeGraphics g,Object font,Object text,int x,int y,int color,boolean shadow){{g.drawString(font,text,x,y,color,shadow);}}
  static void flush(FakeGraphics g){{g.flush();}}
 }}
 {body}
 static void check(){{
  graphics.fail=true;
  try{{vanillaText(Fonts.Face.REGULAR,"中文",20,30,18,0xFFFFFFFF);throw new AssertionError("Font exception was swallowed");}}
  catch(IllegalStateException expected){{if(!expected.getMessage().equals("injected glyph failure"))throw expected;}}
  if(!graphics.pose.stack.isEmpty()||graphics.pose.x!=0||graphics.pose.y!=0||graphics.pose.sx!=1||graphics.pose.sy!=1)
   throw new AssertionError("Font adapter {index} leaked its matrix after an exception");
  graphics.fail=false;
  if(vanillaText(Fonts.Face.REGULAR,"abc",20,30,18,0xFFFFFFFF)!=54)throw new AssertionError("Text advance changed");
  if(!graphics.pose.stack.isEmpty()||graphics.pose.sx!=1||graphics.pose.sy!=1||graphics.draws!=1||graphics.flushes!=1||applied!=1)
   throw new AssertionError("Normal rendering did not restore state");
 }}
}}
"""
        text += "public class FontRecoveryCheck {public static void main(String[] args){" + "".join(
            f"FontAdapter{i}.check();" for i in range(len(paths))) + (
            f'System.out.println("PASS: {len(paths)} production font adapters restore matrices on failure and preserve normal text advance");'
            + "}}")
        fixture.write_text(text, "utf-8")
        classes = work / "classes"
        subprocess.run([str(java / "javac.exe"), "--release", "21", "-encoding", "UTF-8",
                        "-d", str(classes), str(fixture)], check=True)
        subprocess.run([str(java / "java.exe"), "-cp", str(classes),
                        "com.cheatprogram.render.FontRecoveryCheck"], check=True)


if __name__ == "__main__":
    main()
