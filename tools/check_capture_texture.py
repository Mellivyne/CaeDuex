"""Check native BGRA sampling using a hidden GLFW context; never starts Minecraft."""
from pathlib import Path
import os,subprocess
ROOT=Path(__file__).resolve().parents[1]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1/org.lwjgl'
jars=[]
for name in ('lwjgl','lwjgl-glfw','lwjgl-opengl'):
    folder=cache/name/'3.3.3'
    choices=list(folder.rglob('*.jar'))
    jars.extend(p for p in choices if p.name==f'{name}-3.3.3.jar' or p.name==f'{name}-3.3.3-natives-windows.jar')
assert len(jars)==6,'Pinned LWJGL 3.3.3 jars/natives missing'
out=ROOT/'build/capture-texture-check';out.mkdir(parents=True,exist_ok=True)
java=Path(os.environ.get('JAVA_HOME','D:/Java/jdk-27'))/'bin'
cp=os.pathsep.join(map(str,jars))
sources=[ROOT/'src/main/java/com/cheatprogram/capture/AppCaptureTextureFormat.java',ROOT/'verification/java/com/cheatprogram/dev/CaptureTextureFormatCheck.java']
sources += [ROOT/'src/main/java/com/cheatprogram/capture'/f'{name}.java' for name in ('CaptureUploadQueue','CaptureUploadFences')]
sources += [ROOT/'verification/java/com/cheatprogram/dev/CaptureSchedulingCheck.java']
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(out),*map(str,sources)],check=True)
subprocess.run([str(java/'java.exe'),'--enable-native-access=ALL-UNNAMED','-cp',str(out)+os.pathsep+cp,'com.cheatprogram.dev.CaptureTextureFormatCheck'],check=True)
subprocess.run([str(java/'java.exe'),'-cp',str(out)+os.pathsep+cp,'com.cheatprogram.dev.CaptureSchedulingCheck'],check=True)
