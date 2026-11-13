"""Compile and verify exact NeoForge client artifacts."""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tomllib
import zipfile

from build_forge import PROJECT_VERSION, seal
from repository_integrity import verify_artifact

ROOT = Path(__file__).resolve().parents[1]


def verify(path, target):
    scope = json.loads((ROOT / "compatibility/features.json").read_text(encoding="utf-8"))
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        assert jar.testzip() is None
        metadata = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        mod = metadata["mods"][0]
        assert metadata["modLoader"] == "javafml"
        assert metadata["issueTrackerURL"] == "https://github.com/Mellivyne/CaeDuex/issues"
        assert mod["modId"] == "cheatprogram" and mod["version"] == PROJECT_VERSION
        assert mod["features"]["javaVersion"] == f'[{target["java"]},)'
        assert mod["displayName"] == "CaeDuex"
        assert mod["displayURL"] == "https://github.com/Mellivyne/CaeDuex"
        requirements = metadata["dependencies"]["cheatprogram"]
        game = next(item for item in requirements if item["modId"] == "minecraft")
        assert game["versionRange"] == f'[{target["minecraft"]}]' and game["side"] == "CLIENT"
        assert "fabric.mod.json" not in names and "META-INF/mods.toml" not in names
        assert not any(name.startswith(("net/fabricmc/", "net/neoforged/")) for name in names)
        assert "com/cheatprogram/neoforge/CheatProgramNeoForge.class" in names
        assert "com/cheatprogram/platform/ModPlatform.class" in names
        assert "com/google/zxing/qrcode/QRCodeWriter.class" in names
        if target["minecraft"] == "26.3":
            assert "org/lwjgl/util/tinyfd/TinyFileDialogs.class" in names
        expected = {item.replace(".", "/") + ".class" for item in scope["modules"]}
        actual = {name for name in names if name.startswith("com/cheatprogram/module/")
                  and name.endswith(".class") and "$" not in name}
        assert actual == expected, "NeoForge module scope differs from the shared client"
        config = json.loads(jar.read("cheatprogram.mixins.json"))
        for mixin in config.get("mixins", []) + config.get("client", []):
            assert config["package"].replace(".", "/") + "/" + mixin + ".class" in names
        for notice in ("LICENSE", "LICENSING.md", "LICENSE-LEGACY-MIT", "THIRD_PARTY_NOTICES.md"):
            assert jar.read(notice) == (ROOT / notice).read_bytes()
        assert jar.read("NEOFORGE-NOTICES.md") == (ROOT / "loaders/neoforge/NOTICES.md").read_bytes()
    verify_artifact(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("versions", nargs="*")
    parser.add_argument("--package-only", action="store_true")
    parser.add_argument("--compile-only", action="store_true")
    args = parser.parse_args()
    matrix = json.loads((ROOT / "compatibility/neoforge.json").read_text(encoding="utf-8"))
    assert matrix["projectVersion"] == PROJECT_VERSION
    targets = {item["minecraft"]: item for item in matrix["targets"]}
    selected = args.versions or list(targets)
    if any(mc not in targets for mc in selected):
        parser.error("Select an explicitly configured NeoForge target")
    reports = ROOT / "verification/neoforge-build-reports"
    reports.mkdir(parents=True, exist_ok=True)
    output = ROOT / "outputs/neoforge"
    results = []
    for mc in selected:
        target = targets[mc]
        log = reports / f"{mc}.log"
        if not args.package_only:
            command = [str(ROOT / "loaders/neoforge/gradlew.bat"), "--no-daemon", "--console=plain",
                       f"-Pminecraft_version={mc}", f"-PpythonExecutable={sys.executable}",
                       "compileJava" if args.compile_only else "jar"]
            print(f"NeoForge {mc}: compile and verify", flush=True)
            with log.open("w", encoding="utf-8") as stream:
                result = subprocess.run(command, cwd=ROOT / "loaders/neoforge", stdout=stream, stderr=subprocess.STDOUT)
            if result.returncode:
                raise SystemExit(f"NeoForge {mc} build failed. See {log}")
        if args.compile_only:
            print(f"  COMPILED {mc}", flush=True)
            continue
        source = ROOT / f"build/neoforge/{mc}/libs/caeduex-v{PROJECT_VERSION}-neoforge-mc{mc}.jar"
        destination = output / mc / source.name
        seal(source, destination)
        verify(destination, target)
        record = {
            "minecraft": mc, "neoforge": target["neoforge"], "loader": "neoforge",
            "projectVersion": PROJECT_VERSION, "java": target["java"],
            "file": destination.relative_to(output).as_posix(),
            "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
            "compiled": True, "artifactVerified": True, "clientStarted": False, "gameplayRetested": False,
        }
        results.append(record)
        shutil.copy2(destination, ROOT / destination.name)
        print(f"  PASS {destination.name}", flush=True)
    if args.compile_only:
        return
    combined = {}
    manifest = output / "manifest.json"
    if manifest.exists():
        for record in json.loads(manifest.read_text(encoding="utf-8")).get("artifacts", []):
            path = output / record["file"]
            if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == record["sha256"]:
                combined[record["minecraft"]] = record
    combined.update({item["minecraft"]: item for item in results})
    records = [combined[mc] for mc in targets if mc in combined]
    manifest.write_text(json.dumps({"projectVersion": PROJECT_VERSION, "artifacts": records}, indent=2) + "\n",
                        encoding="utf-8")
    (output / "SHA256SUMS.txt").write_text("".join(f'{item["sha256"]}  {item["file"]}\n' for item in records),
                                           encoding="utf-8")
    (reports / "latest.json").write_text(json.dumps({
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "results": records,
    }, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
