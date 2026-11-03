"""Build and verify independent Forge client JARs; never launches Minecraft."""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tomllib
import zipfile
from repository_integrity import ENTRIES,MANIFEST,verify_artifact

ROOT=Path(__file__).resolve().parents[1]
PROJECT_VERSION=next(line.partition('=')[2].strip() for line in (ROOT/'gradle.properties').read_text(encoding='utf-8').splitlines() if line.startswith('mod_version='))

def seal(source,destination):
    with zipfile.ZipFile(source) as jar:
        records=''.join(f'{hashlib.sha256(jar.read(n)).hexdigest()}  {n}\n' for n in sorted(ENTRIES))
        staged=destination.with_suffix('.jar.tmp')
        destination.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(staged,'w') as out:
            for item in jar.infolist():
                if item.filename!=MANIFEST:out.writestr(item,jar.read(item))
            info=zipfile.ZipInfo(MANIFEST,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            out.writestr(info,records.encode('utf-8'))
    verify_artifact(staged)
    os.replace(staged,destination)

def verify(path,target):
    scope=json.loads((ROOT/'compatibility/features.json').read_text(encoding='utf-8'))
    with zipfile.ZipFile(path) as jar:
        names=jar.namelist();metadata=tomllib.loads(jar.read('META-INF/mods.toml').decode('utf-8'))
        pack=json.loads(jar.read('pack.mcmeta'))['pack']
        assert pack['description']==f'Cheat Program v{PROJECT_VERSION} resources'
        assert pack['pack_format']=={'1.20.1':15,'1.20.4':22}[target['minecraft']]
        assert metadata['modLoader']=='javafml'
        mod=metadata['mods'][0];assert mod['modId']=='cheatprogram' and mod['version']==PROJECT_VERSION and mod['displayName']=='Cheat Program'
        assert mod['features']['java_version']=='[21,)'
        mc=next(d for d in metadata['dependencies']['cheatprogram'] if d['modId']=='minecraft')
        assert mc['versionRange']==f"[{target['minecraft']}]" and mc['side']=='CLIENT'
        assert 'fabric.mod.json' not in names and not any(n.startswith('net/fabricmc/') for n in names)
        assert 'com/cheatprogram/forge/CheatProgramForge.class' in names
        assert 'com/cheatprogram/platform/ModPlatform.class' in names
        assert 'com/google/zxing/qrcode/QRCodeWriter.class' in names
        expected={n.replace('.','/')+'.class' for n in scope['modules']}
        actual={n for n in names if n.startswith('com/cheatprogram/module/') and n.endswith('.class') and '$' not in n}
        assert actual==expected,'Forge feature scope differs from the declared module set'
        assert not any(n.startswith('com/cheatprogram/dev/') and n.endswith('.class') and not n.endswith('/DevTools.class') for n in names)
        for notice in ['LICENSE','LICENSING.md','LICENSE-LEGACY-MIT','THIRD_PARTY_NOTICES.md']:
            assert jar.read(notice)==(ROOT/notice).read_bytes()
        assert jar.read('FORGE-NOTICES.md')==(ROOT/'loaders/forge/NOTICES.md').read_bytes()
        config=json.loads(jar.read('cheatprogram.mixins.json'))
        assert b'MixinConfigs: cheatprogram.mixins.json' in jar.read('META-INF/MANIFEST.MF')
        for mixin in config.get('client',[])+config.get('mixins',[]):
            assert config['package'].replace('.','/')+'/'+mixin+'.class' in names
        if target['minecraft'] in ('1.20.1','1.20.4'):
            bridge=jar.read('com/cheatprogram/forge/ForgeClientEvents.class')
            for call in (b'ClientTickEvent',b'RenderTickEvent',b'RenderGuiEvent$Post',b'InputEvent$Key',b'onTick',b'onPostTick',b'onInput',b'HudRenderer',b'ForgePanelKeyState'):
                assert call in bridge,('Missing Forge native event route',call)
            physical=jar.read('com/cheatprogram/forge/ForgePanelKeyState.class')
            for call in (b'GetAsyncKeyState',b'GetForegroundWindow',b'glfwGetWin32Window',b'glfwGetKey'):
                assert call in physical,('Missing native modifier/fallback route',call)
            assert b'user32' in jar.read('com/cheatprogram/forge/ForgePanelKeyState$Windows.class')
            blend=json.loads(jar.read('assets/cheatprogram/shaders/core/backdrop.json'))['blend']
            assert blend['srcrgb']=='srcalpha' and blend['dstrgb']=='1-srcalpha'
            assert blend['srcalpha']=='one' and blend['dstalpha']=='1-srcalpha'
            for file in ['R2D','Backdrop','RenderStateSnapshot']:
                assert b'com/cheatprogram/forge/LegacyBlendState' in jar.read('com/cheatprogram/render/'+file+'.class'),('Missing legacy alpha state repair',file)
            assert 'com/cheatprogram/forge/LegacyBlendState.class' in names
            assert config['client'].count('LegacyBlendModeAccessor')==1
            assert b'nativeBlendMode' in jar.read('com/cheatprogram/render/RenderStateSnapshot.class')
            assert 'com/cheatprogram/forge/ForgePanelRenderProbe.class' not in names
            panel=jar.read('com/cheatprogram/gui/ClickGuiScreen.class')
            assert b'SharedConstants' in panel and b'1.21.4' not in panel
            for file,call in [('MinecraftMixin',b'onTick'),('MinecraftMixin',b'onPostTick'),('KeyboardHandlerMixin',b'onInput'),('MouseHandlerMixin',b'onInput'),('GuiMixin',b'HudRenderer')]:
                assert call not in jar.read('com/cheatprogram/mixin/'+file+'.class'),('Duplicate native/shared event route',file,call)
            assert b'ForgeHooksClient;drawScreen' in jar.read('com/cheatprogram/mixin/GameRendererMixin.class'), 'Forge-specific screen event hook is missing'
            assert config['compatibilityLevel']=='JAVA_17'
            assert all(struct.unpack_from('>H',jar.read(n),6)[0]<=61 for n in names if n.startswith('com/cheatprogram/mixin/') and n.endswith('.class'))
            ref=json.loads(jar.read(config['refmap']));assert 'm_6140_' in ref['mappings']['com/cheatprogram/mixin/ParkourSprintMixin']['serverAiStep']
            nested=json.loads(jar.read('META-INF/jarjar/metadata.json'))
            for record in nested['jars']:assert jar.read(record['path'])
        else:assert b'applyInput' in jar.read('com/cheatprogram/mixin/ParkourSprintMixin.class')
    verify_artifact(path)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('versions',nargs='*');parser.add_argument('--package-only',action='store_true')
    args=parser.parse_args();matrix=json.loads((ROOT/'compatibility/forge.json').read_text(encoding='utf-8'))
    assert matrix['projectVersion']==PROJECT_VERSION,'Forge and Fabric project versions must match'
    targets={t['minecraft']:t for t in matrix['targets']};selected=args.versions or list(targets)
    if any(v not in targets for v in selected):parser.error('Choose an explicitly configured Forge target')
    reports=ROOT/'verification/forge-build-reports';reports.mkdir(parents=True,exist_ok=True)
    output=ROOT/'outputs/forge';output.mkdir(parents=True,exist_ok=True);results=[]
    for mc in selected:
        target=targets[mc];log=reports/f'{mc}.log'
        if not args.package_only:
            command=[str(ROOT/'loaders/forge/gradlew.bat'),'--no-daemon','--console=plain',f'-Pminecraft_version={mc}',f'-PpythonExecutable={sys.executable}','jar']
            print(f'Forge {mc}: compile, package and verify',flush=True)
            with log.open('w',encoding='utf-8') as stream:
                result=subprocess.run(command,cwd=ROOT/'loaders/forge',stdout=stream,stderr=subprocess.STDOUT)
            if result.returncode:raise SystemExit(f'Forge {mc} build failed; inspect {log}')
        source=ROOT/f'build/forge/{mc}/libs/cheat-program-v{PROJECT_VERSION}-forge-mc{mc}.jar'
        destination=output/mc/source.name
        seal(source,destination);verify(destination,target)
        record={'minecraft':mc,'forge':target['forge'],'loader':'forge','projectVersion':PROJECT_VERSION,'java':21,'file':destination.relative_to(output).as_posix(),'sha256':hashlib.sha256(destination.read_bytes()).hexdigest(),'compiled':True,'artifactVerified':True,'clientStarted':False,'gameplayRetested':False}
        results.append(record);shutil.copyfile(destination,ROOT/destination.name)
        print(f'  PASS {destination.name}',flush=True)
    combined={}
    previous=output/'manifest.json'
    if previous.exists():
        for record in json.loads(previous.read_text(encoding='utf-8')).get('artifacts',[]):
            p=output/record['file']
            if p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==record['sha256']:
                combined[record['minecraft']]=record
    combined.update({r['minecraft']:r for r in results})
    results=[combined[mc] for mc in targets if mc in combined]
    (output/'manifest.json').write_text(json.dumps({'projectVersion':PROJECT_VERSION,'artifacts':results},indent=2)+'\n',encoding='utf-8')
    (output/'SHA256SUMS.txt').write_text(''.join(f"{r['sha256']}  {r['file']}\n" for r in results),encoding='utf-8')
    (reports/'latest.json').write_text(json.dumps({'generatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'results':results},indent=2)+'\n',encoding='utf-8')
    records=json.loads((ROOT/'outputs/fabric/manifest.json').read_text(encoding='utf-8'))['artifacts']+results
    (ROOT/f'SHA256SUMS-v{PROJECT_VERSION}.txt').write_text(''.join(f"{r['sha256']}  {Path(r['file']).name}\n" for r in records),encoding='utf-8')

if __name__=='__main__':main()
