"""Exercise the production shared-viewing screens with a headless draw recorder."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

STUBS = {
    "net/minecraft/client/gui/screens/Screen.java": """package net.minecraft.client.gui.screens;
public class Screen {
 public void render(net.minecraft.client.gui.GuiGraphics g,int x,int y,float p){}
 public void render(net.minecraft.client.gui.GuiGraphicsExtractor g,int x,int y,float p){}
 public boolean mouseClicked(double x,double y,int b){return false;}
 public boolean mouseScrolled(double x,double y,double h,double v){return false;}
 public boolean keyPressed(int k,int s,int m){return false;}
 public boolean charTyped(char c,int m){return false;}
 public boolean isPauseScreen(){return false;} public void onClose(){} public void removed(){}
}""",
    "net/minecraft/client/gui/GuiGraphics.java": "package net.minecraft.client.gui; public class GuiGraphics{}",
    "net/minecraft/client/gui/GuiGraphicsExtractor.java": "package net.minecraft.client.gui; public class GuiGraphicsExtractor{}",
    "net/minecraft/client/Minecraft.java": """package net.minecraft.client;
public class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();
 public static Minecraft getInstance(){return INSTANCE;}
 public Keyboard keyboardHandler=new Keyboard();public Gui gui=new Gui();
 public void setScreen(net.minecraft.client.gui.screens.Screen s){}
 public static class Gui { public void setScreen(net.minecraft.client.gui.screens.Screen s){} }
 public static class Keyboard {public String clipboard="";public void setClipboard(String s){clipboard=s;}public String getClipboard(){return clipboard;}}
}""",
    "com/cheatprogram/core/Lang.java": "package com.cheatprogram.core; public class Lang {public static String t(String s){return s;}}",
    "com/cheatprogram/render/Icon.java": "package com.cheatprogram.render; public enum Icon {CLOSE,SERVER,LAYOUT,REFRESH,TRASH,LINK,EYE,NAVIGATION,BACK}",
    "com/cheatprogram/render/R2D.java": "package com.cheatprogram.render; public class R2D {public static void begin(Object g){}public static void end(){}}",
    "com/cheatprogram/gui/Theme.java": "package com.cheatprogram.gui; public class Theme {public static final int TEXT=1,TEXT_2=2,TEXT_3=3;}",
    "com/cheatprogram/module/visual/AppCast.java": """package com.cheatprogram.module.visual;
public abstract class AppCast implements com.cheatprogram.capture.SharedProjectionSource {public boolean shareLocal(){return false;}public void shareLocal(boolean b){}}
""",
    "com/cheatprogram/gui/GuiBase.java": """package com.cheatprogram.gui;
import java.util.*;import com.cheatprogram.render.Icon;
public class GuiBase extends net.minecraft.client.gui.screens.Screen {
 protected static final Object REG=new Object(),BOLD=new Object();
 public final Recorder c=new Recorder();public final Map<Object,Runnable> actions=new HashMap<>();
 protected final net.minecraft.client.Minecraft mc=net.minecraft.client.Minecraft.getInstance();
 protected TextField focused;protected float dt=.016f;
 public GuiBase(String title){}
 protected void beginFrame(){c.drawn.clear();actions.clear();}protected void endFrame(){}
 protected boolean preparePanelBackground(Object g,float p){return true;}
 protected float layoutPanel(float w,float h){return h;}protected void drawPanelSurface(Object g,float w,float h,float r){}
 protected void iconButton(Object k,Icon i,float x,float y,float size,int color,Runnable run){actions.put(k,run);}
 protected void button(Object k,String label,Icon i,float x,float y,float w,float h,boolean active,Runnable run){c.drawn.add(label);actions.put(k,run);}
 protected boolean over(float x,float y,float w,float h){return false;}
 protected Hit hit(float x,float y,float w,float h){return new Hit();}
 protected void pushHitClip(float x,float y,float w,float h){}protected void popHitClip(){}
 protected void textField(TextField f,String p,Icon i,float x,float y,float w,float h){c.drawn.add(f.viewport(512).text());}
 protected void panelScrollBar(Object key,Scroller s,float x,float y,float h){}
 protected boolean dispatchClick(double x,double y,int b){return false;}
 protected boolean dispatchScroll(double x,double y,double v){return false;}
 public static class Hit {public void scroll(java.util.function.Consumer<Float> f){}}
 public static class Scroller {public float max;public void update(float d){}public void scroll(float d){}public float get(){return 0;}}
 public static class Recorder {
  public final List<String> drawn=new ArrayList<>();
  public void textC(Object font,String text,float x,float y,float size,int color){drawn.add(text);}
  public List<String> wrap(Object f,String s,float size,float width){return List.of(s);}
  public String ellipsize(Object f,String s,float size,float width){return s;}
  public void clip(float x,float y,float w,float h){}public void unclip(){}
 }
}""",
    "com/cheatprogram/gui/InvitePrivacyCheck.java": """package com.cheatprogram.gui;
import com.cheatprogram.capture.*;import java.lang.reflect.*;
public class InvitePrivacyCheck {
 static final String FIRST="cpcast://192.0.2.20:8443/first-private-capability#first-private-media-key";
 static final String NEXT="cpcast://192.0.2.21:8443/new-private-capability#new-private-media-key";
 static class Session implements SharedProjectionSession {
  String code=FIRST;public boolean publishing(){return !code.isEmpty();}public boolean watching(){return false;}
  public String invite(){return code;}public String publishStatus(){return "status";}public String watchStatus(){return "status";}
  public long sentFrames(){return 0;}public long receivedFrames(){return 0;}
 }
 static class Source implements SharedProjectionSource {
  Session s=new Session();boolean video;int fps;String received="";
  Source(boolean v){video=v;}public SharedProjectionSession sharing(){return s;}
  public String shareRelay(){return "";}public void shareRelay(String a){}
  public boolean startSharing(){s.code=NEXT;return true;}public void revokeSharing(){s.code="";}
  public void watchSharing(String c){received=c;}public void leaveSharing(){}public boolean videoSharing(){return video;}public int shareFrameRate(){return fps;}public void cycleShareQuality(){fps=fps==0?60:fps==60?120:0;}
 }
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static void draw(SharedProjectionScreen s)throws Exception{
  for(var m:SharedProjectionScreen.class.getDeclaredMethods())if(m.getName().equals("render")){m.invoke(s,m.getParameterTypes()[0].getConstructor().newInstance(),0,0,0f);return;}
  throw new AssertionError("Missing render method");
 }
 static boolean visible(SharedProjectionScreen s,String value){return s.c.drawn.stream().anyMatch(t->t.contains(value));}
 static void click(SharedProjectionScreen s,String name){var a=s.actions.get(name);check(a!=null,"Missing control: "+name);a.run();}
 static TextField input(SharedProjectionScreen s)throws Exception{var f=SharedProjectionScreen.class.getDeclaredField("invite");f.setAccessible(true);return (TextField)f.get(s);}
 public static void main(String[] args)throws Exception{
  for(boolean video:new boolean[]{false,true}){
   var source=new Source(video);var screen=new SharedProjectionScreen(null,source);draw(screen);
   check(!visible(screen,FIRST)&&!visible(screen,"192.0.2.20"),"Default frame exposes invitation/address");
   click(screen,"share.copy");check(net.minecraft.client.Minecraft.getInstance().keyboardHandler.clipboard.equals(FIRST),"Copy must preserve complete invitation");
   draw(screen);check(!visible(screen,FIRST),"Copy reveals invitation");
   click(screen,"share.revealPublished");draw(screen);check(visible(screen,FIRST),"Explicit reveal failed");
   source.s.code=NEXT;draw(screen);check(!visible(screen,NEXT),"Changed invitation stayed revealed");
   click(screen,"share.revealPublished");draw(screen);click(screen,"share.create");draw(screen);check(!visible(screen,NEXT),"Regeneration stayed revealed");
   var field=input(screen);check(field.masked,"Viewer input not masked by default");
   field.setText(FIRST);draw(screen);check(!visible(screen,FIRST),"Pasted viewer invitation is visible");
   click(screen,"share.revealViewer");draw(screen);check(visible(screen,FIRST),"Viewer reveal failed");
   click(screen,"share.watch");draw(screen);check(source.received.equals(FIRST)&&field.masked&&!visible(screen,FIRST),"Joining must use full invitation and hide it");
   click(screen,"share.revealViewer");field.onChange.accept(NEXT);check(field.masked,"Editing left a replacement invitation exposed");
   click(screen,"share.revealPublished");screen.removed();draw(screen);check(!visible(screen,NEXT)&&field.masked,"Page removal did not re-mask");
   click(screen,"share.revealPublished");click(screen,"share.revealViewer");screen.onClose();draw(screen);check(!visible(screen,FIRST)&&!visible(screen,NEXT),"Closing left secrets visible");
   click(screen,"share.revoke");draw(screen);check(!visible(screen,NEXT)&&source.s.code.isEmpty(),"Revoked invitation remains visible");
   if(video){
    check(visible(screen,"ui.appcast.share.sourceQuality"),"Source quality was not the default UI label");
    click(screen,"share.quality");draw(screen);check(visible(screen,"ui.appcast.share.hd60"),"1080p60 choice missing");
    click(screen,"share.quality");draw(screen);check(visible(screen,"ui.appcast.share.hd120"),"1080p120 choice missing");
    click(screen,"share.quality");draw(screen);check(visible(screen,"ui.appcast.share.sourceQuality"),"Quality cycle did not return to source");
    source.s.code=FIRST;click(screen,"share.quality");check(source.fps==0,"Quality changed during publishing");
   }else{check(!screen.actions.containsKey("share.quality"),"Native captions exposed a video quality setting");}
  }
  System.out.println("PASS: publisher/viewer default masking, explicit reveal, hidden copy, regeneration, replacement, join, page removal, close and revoke");
 }
}""",
}


def main():
    java_home = os.environ.get("JAVA21_HOME") or os.environ.get("JAVA_HOME")
    javac = Path(java_home, "bin", "javac.exe") if java_home else Path(shutil.which("javac") or "")
    if not javac.is_file():
        raise SystemExit("Set JAVA21_HOME or JAVA_HOME to a JDK.")
    java = javac.with_name("java.exe")
    source = ROOT / "src/main/java"
    field = (source / "com/cheatprogram/gui/TextField.java").read_text("utf-8")
    constants = sorted(set(re.findall(r"GLFW\.(GLFW_[A-Z_0-9]+)", field))) + ["GLFW_KEY_ESCAPE"]
    STUBS["org/lwjgl/glfw/GLFW.java"] = "package org.lwjgl.glfw; public class GLFW {" + "".join(
        f"public static final int {key}={i + 1};" for i, key in enumerate(constants)
    ) + "}"
    adapters = [
        source,
        ROOT / "versions/unobfuscated-26.1/java",
        ROOT / "versions/unobfuscated-26.2/java",
    ]
    with tempfile.TemporaryDirectory(prefix="cp-invite-privacy-") as temporary:
        work = Path(temporary)
        for name, text in STUBS.items():
            p = work / "src" / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, "utf-8")
        for name in ["gui/TextField", "util/UnicodeText", "capture/SharedProjectionSource", "capture/SharedProjectionSession"]:
            p = work / "src/com/cheatprogram" / (name + ".java")
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes((source / "com/cheatprogram" / (name + ".java")).read_bytes())
        for i, adapter in enumerate(adapters):
            p = work / "src/com/cheatprogram/gui/SharedProjectionScreen.java"
            p.write_bytes((adapter / "com/cheatprogram/gui/SharedProjectionScreen.java").read_bytes())
            classes = work / f"classes-{i}"
            subprocess.run([str(javac), "--release", "21", "-encoding", "UTF-8", "-d", str(classes),
                            *map(str, (work / "src").rglob("*.java"))], check=True)
            subprocess.run([str(java), "-cp", str(classes), "com.cheatprogram.gui.InvitePrivacyCheck"], check=True)


if __name__ == "__main__":
    main()
