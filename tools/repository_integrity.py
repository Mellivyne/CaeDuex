"""Verify the final JAR's project-link manifest without starting Minecraft."""
from pathlib import Path
import hashlib
import re
import sys
import zipfile

MANIFEST='META-INF/cheatprogram/repository.sha256'
ENTRIES={
    'com/cheatprogram/CheatProgram.class',
    'com/cheatprogram/util/RepositoryIntegrity.class',
    'com/cheatprogram/util/ProjectRepository.class',
    'com/cheatprogram/gui/RepositoryBadge.class',
    'com/cheatprogram/gui/ClickGuiScreen.class',
}

def verify_artifact(path):
    with zipfile.ZipFile(path) as jar:
        names=jar.namelist()
        assert len(names)==len(set(names)), 'Duplicate ZIP entries are not supported'
        manifest=jar.read(MANIFEST)
        assert len(manifest)<=4096, 'Integrity manifest is oversized'
        expected={}
        for line in manifest.decode('utf-8').splitlines():
            if not line:continue
            match=re.fullmatch(r'([0-9a-f]{64})  (.+)',line)
            assert match, 'Malformed integrity record'
            digest,name=match.groups()
            assert name in ENTRIES and name not in expected, 'Unexpected/repeated integrity record'
            expected[name]=digest
        assert set(expected)==ENTRIES, 'Incomplete project-link integrity manifest'
        for name,digest in expected.items():
            assert hashlib.sha256(jar.read(name)).hexdigest()==digest, f'Project-link component changed: {name}'
        assert b'com/cheatprogram/gui/RepositoryBadge' in jar.read('com/cheatprogram/gui/ClickGuiScreen.class')
        assert b'com/cheatprogram/util/ProjectRepository' in jar.read('com/cheatprogram/gui/RepositoryBadge.class')
        assert b'verifyInstalled' in jar.read('com/cheatprogram/CheatProgram.class')
    return len(expected)

if __name__=='__main__':
    for argument in sys.argv[1:]:
        path=Path(argument)
        print(f'INTEGRITY_PASS {path.name}: {verify_artifact(path)} protected components')
