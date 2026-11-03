"""Compile/run pure parkour checks. Never starts Minecraft or reads user profiles."""
import os
from pathlib import Path
import subprocess
import argparse

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build'/'parkour-pure-checks'
OUT.mkdir(parents=True,exist_ok=True)
java=Path(os.environ.get('JAVA_HOME','D:/Java/jdk-27'))/'bin'
gson=next((Path.home()/'.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson').rglob('gson-*.jar'))
stubs={
'Config':'public static void markDirty() {}',
'Module':'public String id;',
'Lang':'public static String t(String key){return key;} public static String setting(String module,String key){return key;}'
}
sources=[]
for name,body in stubs.items():
    path=OUT/'stubs'/f'{name}.java';path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(f'package com.cheatprogram.core; public final class {name} {{{body}}}',encoding='utf-8');sources.append(path)
base=ROOT/'src/main/java/com/cheatprogram'
sources += [base/'parkour'/f'{name}.java' for name in ['ParkourPhysics','ParkourPlanner','ParkourGuide','ParkourManeuvers','ParkourColors','ParkourCourse','ParkourContinuity','ParkourRecovery','ParkourPrediction','ParkourWaypointSetting','ParkourWaypointTarget','ParkourCamera','ParkourCollisionChecks']]
sources += [base/'core/setting/Setting.java']
sources += [base/'parkour/ParkourEnvironment.java']
sources += [base/'parkour/ParkourNavigationMemory.java']
sources += [base/'parkour/ParkourLineHistory.java']
sources += [base/'util/KeyBindingCapture.java']
checks=['ParkourPhysicsCheck','ParkourWaypointsCheck','ParkourThinSupportCheck','ParkourContinuityCheck','ParkourCameraCheck','ParkourCollisionChecksCheck','ParkourDetourCheck','ParkourRecoveryCheck','ParkourSlimeCheck','ParkourWallCheck','KeyBindingCaptureCheck','ParkourRollingCheck','ParkourSpecialCheck','ParkourNeoChainCheck','ParkourTightNeoCheck','ParkourCompactNeoCheck','ParkourLongCompactCheck','SettingSchemaCheck']
checks += ['ParkourNavigationCheck']
checks += ['ParkourCompoundPriorityCheck']
checks += ['ParkourCloseStartCheck']
checks += ['ParkourMixedElevationCheck']
checks += ['ParkourPipelineCheck']
checks += ['ParkourEntryTransitionCheck']
checks += ['ParkourProgressHeightCheck']
checks += ['ParkourVisualCheck']
sources += [ROOT/'verification/java/com/cheatprogram/dev'/f'{name}.java' for name in checks]
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(gson),'-d',str(OUT),*[str(p) for p in sources]],check=True)
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--only',choices=checks,nargs='+')
args=parser.parse_args()
for name in args.only or checks:
    subprocess.run([str(java/'java.exe'),'-cp',str(OUT)+os.pathsep+str(gson),'com.cheatprogram.dev.'+name],check=True)
