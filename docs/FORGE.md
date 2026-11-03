# Forge builds

Cheat Program v1.2 has separate Forge client artifacts:

| Minecraft | Forge build used | Required Java | Artifact |
| --- | --- | --- | --- |
| 1.20.1 | 47.4.0 | 21 or newer | `cheat-program-v1.2-forge-mc1.20.1.jar` |
| 1.20.4 | 49.2.13 | 21 or newer | `cheat-program-v1.2-forge-mc1.20.4.jar` |

The Forge dependency ranges stay within their respective loader major versions. Use the matching loader and Minecraft version, and install one Cheat Program JAR in the instance's `mods` folder. Configuration remains local under `config/cheatprogram-open/`.

## Build

Use Python 3 and a Java 21 Gradle runtime:

```powershell
python tools/build_forge.py
```

To build one target, append `1.20.1` or `1.20.4`. The script invokes the dedicated Gradle 8 wrapper in `loaders/forge/`, generates the matching source/resource overlay under `build/forge/<version>/`, builds the JAR, seals its installed-resource manifest and verifies the result. Packaged artifacts and SHA-256 manifests are in `outputs/forge/`; the named JARs are also copied to the project root. No game is launched.

Both targets use a Java 17 toolchain for Forge's patches and Java 21 for shared feature code. Their actual mixin sources receive a separate Java 17 compilation for the legacy Forge Mixin ABI. Both JARs are reobfuscated to SRG and include their generated mixin reference maps. MixinExtras Forge 0.5.0 is bundled through Jar-in-Jar discovery, preserving its license. The local ZXing QR encoder is included in both artifacts.

Loader path and metadata calls use a Forge adapter. Fabric classes and `fabric.mod.json` are absent from the Forge JARs. The Forge client setup event initializes the same declared feature set through the shared client entry code.

## Verification

Both targets pass compilation and packaging checks for their exact version metadata, Java requirement, 48-module scope, loader entry, mixin classes, QR encoder, notices and integrity records. Legacy checks additionally validate Java 17 mixin bytecode, SRG parkour mappings and the nested MixinExtras metadata.

`ForgeMixinSurfaceCheck` checks selectors and explicit invocation/field targets against each actual mapped Forge Minecraft JAR without starting Minecraft. Missing optional `@Pseudo` companion targets are counted separately. This checks names and descriptors; it does not execute mixins, validate every injector ordinal, render a frame or test interaction with other mods.

No Forge gameplay session was launched for this revision. Startup, rendering, application interaction and full gameplay remain for user testing. Standalone parkour regression results are recorded in [the verification report](../verification/RESULTS-v1.1.md).

The build structure follows the [official Forge MDK](https://github.com/MinecraftForge/MinecraftForge/tree/1.20.4/mdk), [Forge mod metadata](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/modfiles/) and [MixinGradle documentation](https://github.com/SpongePowered/MixinGradle).
