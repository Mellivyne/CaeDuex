"""Check ordinary application key/text forwarding and bounded capture scheduling."""
from pathlib import Path
import os,subprocess
ROOT=Path(__file__).resolve().parents[1]
out=ROOT/'build/appcast-input-checks';out.mkdir(parents=True,exist_ok=True)
compiler=Path(os.environ.get('SystemRoot','C:/Windows'))/'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
pipeline=out/'CapturePipelineCheck.exe'
subprocess.run([str(compiler),'/nologo','/target:exe','/main:CapturePipelineCheck','/platform:x64',f'/out:{pipeline}',str(ROOT/'src/main/resources/assets/cheatprogram/capture/capture-pipeline.cs'),str(ROOT/'verification/native/CapturePipelineCheck.cs')],check=True)
subprocess.run([str(pipeline)],check=True)
java=Path(os.environ.get('JAVA_HOME','D:/Java/jdk-27'))/'bin'
subprocess.run([str(java/'javac.exe'),'-d',str(out),str(ROOT/'src/main/java/com/cheatprogram/capture/AppCastInputPolicy.java'),str(ROOT/'verification/java/com/cheatprogram/dev/AppCastFullKeyCheck.java')],check=True)
subprocess.run([str(java/'java.exe'),'-cp',str(out),'com.cheatprogram.dev.AppCastFullKeyCheck'],check=True)
