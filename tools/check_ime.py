"""Verify IME routing on an isolated hidden Windows window; never launches Minecraft."""
from pathlib import Path
import os
import subprocess

root=Path(__file__).resolve().parents[1]
out=root/'build/ime-checks'
out.mkdir(parents=True,exist_ok=True)
jna=next((Path.home()/'.gradle/caches/modules-2/files-2.1/net.java.dev.jna/jna').rglob('jna-*.jar'))
java=Path(os.environ.get('JAVA_HOME','D:/Java/jdk-27'))/'bin'
files=[root/'src/main/java/com/cheatprogram/util'/f'{name}.java' for name in ['ImeKeyPolicy','WindowsIme']]
files.append(root/'verification/java/com/cheatprogram/dev/ImeNativeCheck.java')
helper=files[1].read_text(encoding='utf-8')
for forbidden in ('ImmAssociateContext','ImmSetCandidateWindow','ImmSetCompositionWindow','CreateCaret','SetCaretPos'):
    assert forbidden not in helper,f'Native IME presentation must remain unchanged: {forbidden}'
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(jna),'-d',str(out),*map(str,files)],check=True)
subprocess.run([str(java/'java.exe'),'--enable-native-access=ALL-UNNAMED','-cp',str(out)+os.pathsep+str(jna),'com.cheatprogram.dev.ImeNativeCheck'],check=True)
