"""Assemble verified exact-version JARs and release notes for a later GitHub upload."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import tomllib
import zipfile

from repository_integrity import verify_artifact

ROOT = Path(__file__).resolve().parents[1]


def copy_verified(source, destination, digest):
    destination.parent.mkdir(parents=True, exist_ok=True)
    staged = destination.with_name(destination.name + ".stage")
    shutil.copyfile(source, staged)
    if hashlib.sha256(staged.read_bytes()).hexdigest() != digest:
        raise ValueError(f"Copy verification failed: {destination.name}")
    os.replace(staged, destination)


def main():
    version = next(line.partition("=")[2].strip() for line in
                   (ROOT / "gradle.properties").read_text("utf-8").splitlines()
                   if line.startswith("mod_version="))
    expected = {
        "fabric": {t["minecraft"] for t in json.loads(
            (ROOT / "compatibility/matrix.json").read_text("utf-8"))["targets"]
            if t["state"] != "pending"},
        "forge": {t["minecraft"] for t in json.loads(
            (ROOT / "compatibility/forge.json").read_text("utf-8"))["targets"]},
    }
    artifacts = []
    for loader, targets in expected.items():
        output = ROOT / "outputs" / loader
        manifest = json.loads((output / "manifest.json").read_text("utf-8"))
        assert manifest["projectVersion"] == version, f"Stale {loader} project version"
        assert {r["minecraft"] for r in manifest["artifacts"]} == targets
        assert len(manifest["artifacts"]) == len(targets), "Duplicate artifact records"
        for row in manifest["artifacts"]:
            mc = row["minecraft"]
            name = f"caeduex-v{version}-{loader}-mc{mc}.jar"
            source = (output / row["file"]).resolve()
            assert source.is_relative_to(output.resolve()) and source.name == name
            raw = source.read_bytes()
            digest = hashlib.sha256(raw).hexdigest()
            assert digest == row["sha256"], f"Stale artifact hash: {name}"
            verify_artifact(source)
            with zipfile.ZipFile(source) as jar:
                names = jar.namelist()
                if loader == "fabric":
                    metadata = json.loads(jar.read("fabric.mod.json"))
                    assert metadata["version"] == version and metadata["id"] == "cheatprogram"
                    assert metadata["depends"]["minecraft"] == mc
                    assert metadata["name"] == "CaeDuex"
                else:
                    metadata = tomllib.loads(jar.read("META-INF/mods.toml").decode("utf-8"))
                    mod = metadata["mods"][0]
                    assert mod["version"] == version and mod["modId"] == "cheatprogram"
                    dependency = next(d for d in metadata["dependencies"]["cheatprogram"] if d["modId"] == "minecraft")
                    assert dependency["versionRange"] == f"[{mc}]"
                    assert json.loads(jar.read("pack.mcmeta"))["pack"]["description"] == f"CaeDuex v{version} resources"
                for notice in ["LICENSE", "LICENSING.md", "LICENSE-LEGACY-MIT", "THIRD_PARTY_NOTICES.md"]:
                    assert jar.read(notice) == (ROOT / notice).read_bytes(), f"Stale notice: {name}"
                assert not any(n.startswith(("com/cheatprogram/localtest/", "com/cheatprogram/smoke/",
                                             "config/cheatprogram-open/", "saves/"))
                               or n.endswith((".pem", ".key", ".p12"))
                               or "InvitePrivacyCheck" in n for n in names), f"Private runtime/test file: {name}"
                screen = jar.read("com/cheatprogram/gui/SharedProjectionScreen.class")
                assert b"publishedInviteVisible" in screen and b"share.revealViewer" in screen
                for required in ["SharedCastDirectServer", "SharedCastSession", "SharedSceneSession"]:
                    assert f"com/cheatprogram/capture/{required}.class" in names
            artifacts.append({
                "minecraft": mc, "loader": loader, "projectVersion": version,
                "java": row["java"], "file": name, "bytes": len(raw), "sha256": digest,
                "_source": source,
            })
    destination = ROOT / "outputs/releases" / f"v{version}"
    destination.mkdir(parents=True, exist_ok=True)
    expected_files = {r["file"] for r in artifacts} | {"SHA256SUMS.txt", "RELEASE_NOTES.md", "manifest.json"}
    assert not any(p.is_file() and p.name not in expected_files for p in destination.iterdir()), "Unexpected file in release directory"
    for row in artifacts:
        copy_verified(row["_source"], destination / row["file"], row["sha256"])
        copy_verified(row["_source"], ROOT / row["file"], row["sha256"])
    checksums = "".join(f"{r['sha256']}  {r['file']}\n" for r in artifacts)
    (destination / "SHA256SUMS.txt").write_text(checksums, "utf-8")
    (ROOT / f"SHA256SUMS-v{version}.txt").write_text(checksums, "utf-8")
    notes = ROOT / "docs/releases" / f"v{version}.md"
    shutil.copyfile(notes, destination / "RELEASE_NOTES.md")
    public = [{k: v for k, v in row.items() if k != "_source"} for row in artifacts]
    (destination / "manifest.json").write_text(json.dumps({
        "schema": 1, "project": "CaeDuex", "projectVersion": version,
        "status": "prepared", "artifacts": public,
    }, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(f"PASS: {len(artifacts)} verified v{version} JARs prepared in {destination}")
    print("No GitHub upload, release, tag or visibility change was performed.")


if __name__ == "__main__":
    main()
