# Development preview check

`python tools/check_ime.py` tests native input-method routing on its own hidden Windows window. It verifies composition-key isolation, normal Unicode forwarding, input-context preservation and cleanup without opening Minecraft or typing into another application. Third-party IME candidate UI still needs in-game testing. The implementation follows the native [input context](https://learn.microsoft.com/en-us/windows/win32/intl/input-context), [candidate window](https://learn.microsoft.com/en-us/windows/win32/api/imm/nf-imm-immsetcandidatewindow) and [window subclass](https://learn.microsoft.com/en-us/windows/win32/api/commctrl/nf-commctrl-setwindowsubclass) APIs.

`ParkourCloseStartCheck` adds eight nine-wall regressions with actual float player dimensions and separate unit collision boxes. These cover retreat preparation from the first wall, rolling continuations and sole-final/ordered checkpoints. The previous implementation fails a continuation case; the corrected implementation completes all variants.

The GPU-contention follow-up adds 15 pure upload/cadence cases and hidden native GL tests for 24-bit/32F depth backgrounds, full-size color/depth restoration, scissor/framebuffer-state preservation and fence recycling. The scheduler test retains 120 video presentations with 20 stationary-background refreshes. This counts avoided work; it is not a real-game frame-rate benchmark.

Current checks include 48 native ownership/pointer cases, 12 bounded readback cases, physical key/text policy and setting-schema/waypoint validation. `python tools/check_capture_texture.py` uses a hidden native GLFW/OpenGL context to sample six exact BGRA colors through the production texture mapping; it starts no game and emits no input.

`ParkourLongCompactCheck` covers twelve 5/8/12-wall courses with a sole final point or ordered selected-face checkpoints. It executes exact collision replay and precomputes contact-state continuations. The complete pure parkour suite also retains ordinary detours, 92 single-checkpoint neo replays, 80 narrow-gap cases, 64 compact up-front plans, input, slime and recovery checks. Saved-world diagnostic fixtures are kept locally outside the repository and are not release content.

Application input and capture scheduling have isolated regressions:

```powershell
python tools/check_appcast_input.py
```

`python tools/check_appcast_settings.py` runs the actual screen-setting declarations and loading/saving methods from all five AppCast source variants in isolated JVMs. It checks missing-field defaults after an explicitly capped 30 FPS screen, explicit quality settings, invalid fields, global-setting isolation, persisted defaults and migration of old top-level settings. It uses production setting classes with test-only configuration/lifecycle stubs and starts no game.

These exercise the production key/text policy and 16 bounded readback/capture-clock cases without activating a user window or generating input. `CaptureTestSurface.cs` is an independent nonactivating background-window fixture for WGC pixel validation and is excluded from release artifacts.

Sequential AutoParkour checks run without Minecraft:

```powershell
python tools/check_parkour.py
```

Set `JAVA_HOME` to a JDK. The script uses the already cached Gson dependency and isolated test-only stubs for configuration notifications/localization. It compiles the production waypoint setting, planner, physics and camera code. Checks cover 10,000 saved points, atomic loading, independent legs, continuous rolling windows and momentum-preserving handoff, stale/unsafe replay rejection, movement-equivalent terrain, smooth camera interpolation, block-component targeting, lily pads above water and ladder motion. The gameplay fixture now targets the same waypoint API, but it was not launched for this revision at the user's request.

`ParkourDetourCheck` additionally exercises wall bypass, an L corner, a U exit requiring backward travel, an offset doorway with ordered goals, and a sealed unreachable target using the production planner's default 100,000-node/96-beam/350 ms budgets. Every returned action is replayed through production physics. `ParkourRecoveryCheck` verifies actual-state correction in midair, suffix timing, preservation of unexecuted controls, goal coverage and rejection of unsafe replay. These do not certify all Minecraft maps or live servers.

`ParkourWallCheck` covers the documented wall/neo and constrained landing geometries in both input families. Its three-block-length neo has a one-block-lower landing; it is not a same-height universal triple-neo claim. `ParkourSlimeCheck` checks 1.20.x, newer block-effect order and 26.2 restitution formulas, including repeated bounces and rejection of normal dangerous impacts. `KeyBindingCaptureCheck` verifies press/text/repeat/release ownership without activating world hotkeys. `ParkourRollingCheck` prepares and adopts overlapping trajectories over twenty ordered points without adding idle inputs, and checks latency/cooldown guards. No Minecraft session is started by this suite.

`ParkourSpecialCheck` runs 16 compound scenes in both input families, including a three-wall ice approach in four rotations, alternating neos, island/head-hitter/step/fence/trapdoor/lily-pad/ice/ladder/slime combinations. It checks exact native action replay, collision-free player bounds, ordered arrival and node budgets. Its overhang-friction check distinguishes actual support from an off-platform centre sample. The new maneuver proposals are production search candidates, never scripted teleports or forced velocity changes.

`ParkourNeoChainCheck` adds 92 ordinary-ground, same-height single-checkpoint replays: single/short-approach triple neos, consecutive two/three triple neos with 4/5/6-block clear gaps in four rotations, and eight consecutive triple neos using full terrain or successive 18-block local snapshots. It verifies the entire approach/input/state replay, ordinary jump height, wall clearance, turn limits and shared search budgets. Only the final user waypoint is supplied; internal obstacle exits come from geometry. Twenty-four of these replays cover early overlapping eight-neo handoffs through local snapshots in four rotations and both input families, with exact momentum/cooldown adoption and no inserted idle inputs. A distant-coordinate guard checks bounded local obstacle queries. These checks do not start Minecraft.

`ParkourTightNeoCheck` adds 80 closely spaced three-block-high wall replays with one-block landing gaps, four rotations, incoming speed, major-collision state, ordered/final-only targets, and early predictive handoffs. These geometry-only fixtures contain no user save or profile data. Checkpoint progression also covers grounded between-sample crossing, temporal order, airborne rejection and external-correction rejection.

`ParkourCompactNeoCheck` adds 64 complete up-front plans for consecutive three-block-long walls with one-block landing gaps, including two/five walls, two/three-block heights, four rotations, two initial view headings and both movement families. It uses the production forward-biased capture bounds, requires every subsequent wall jump exactly twelve ticks after the preceding one, and replays every input through collision physics. Unlike the earlier tight-wall suite, each obstacle here is three blocks long. This is code-level coverage; no game client is started.

Run `python tools/check_parkour_input.py` for the actual base/legacy/modern native input adapters with isolated test-only client stubs. It checks that keys, yaw and sprint intent are finalized after intervening vanilla input changes, walking intent is retained, minor wall grazes keep sprint, major collision blocks it, and disabled/unrelated-player guards preserve vanilla behavior. The production `ParkourSprintMixin` injects at local `serverAiStep` HEAD for older clients or `applyInput` HEAD on 1.21.10+, before native movement; no position/velocity/packet changes are used. This is code-level coverage, not a new in-game test.

The recovery suite includes 28 small airborne drift cases, complete collision-checked suffix replay, configured yaw bounds and safe goal arrival. Camera checks distinguish physical correction headings from the stable trajectory view. Rolling checks reject early replacement plans that lose ordered checkpoint coverage or slow a completed suffix. Waypoint checks distinguish completed-course restart from interrupted-course resume. Parkour HUD visibility is configured by a master switch and separate bottom-status, detailed-metrics and feature-list controls.

AutoParkour's implementation, supported terrain, measured results and limitations are documented in [AUTO_PARKOUR.md](../docs/AUTO_PARKOUR.md).

Its standalone regression can be run with Java 21 or newer:

```powershell
javac -d build/parkour-checks src/main/java/com/cheatprogram/parkour/ParkourPhysics.java src/main/java/com/cheatprogram/parkour/ParkourPlanner.java src/main/java/com/cheatprogram/parkour/ParkourColors.java verification/java/com/cheatprogram/dev/ParkourPhysicsCheck.java
java -cp build/parkour-checks com.cheatprogram.dev.ParkourPhysicsCheck
./gradlew.bat runClient -Pgameplay_checks "-Pminecraft_version=26.2" "-Dcheatprogram.dev=parkour-check"
```

The integrated check writes tick samples to `run-gameplay/<version>/parkour-physics.csv` and a route screenshot to that directory's `screenshots/` folder. It uses disposable test worlds; it never opens the user's launcher worlds. Use a JDK compatible with the selected game's toolchain.

The optional preview check starts a separate development client, creates a test world inside this project's `run/` directory, checks the registered features, and captures the interface and particle preview.

```powershell
./gradlew.bat runClient -Popen_checks "-Dcheatprogram.dev=open-check"
```

This check targets Minecraft 1.21.4. Its additional Java sources are enabled only with `-Popen_checks` and are excluded from normal release JARs. Screenshots and development runtime data stay local. A successful compile or preview check does not verify every feature on every supported version.

The isolated check also verifies repeated projection key binding, per-line lyric placement, and curved-screen picking after rotation. Curved-screen settings are checked independently across screens, including migration from older settings.

For the standalone geometry regression (no game needed), compile `src/main/java/com/cheatprogram/render/CastSurface.java` and `verification/java/com/cheatprogram/dev/CurvedSurfaceCheck.java`, then run `com.cheatprogram.dev.CurvedSurfaceCheck`. It covers both curve axes, positive/negative angles, flat compatibility, borders and dragging.

The multi-screen regression uses three synthetic capture sessions to check visible-surface selection, back-face and curved picking, click/drag/wheel routing, returning to a previously selected screen, and rejection of stale frames. It checks local command queues; it does not assert that a real external application handled Windows input messages.

The v1.1 building fixture checks native block validation, invalid dimensions and fills, material totals excluding air, per-layer indexing, and visit-camera movement without moving the real player. It also captures the material list, a selected floor and the first-person tour. Real paid AI-provider calls are not part of these local checks.

The legacy preview fixture is enabled separately, with an output directory per Minecraft target:

```powershell
./gradlew.bat runClient "-Pminecraft_version=1.20.4" -Plegacy_checks "-Dcheatprogram.dev=open-check"
./gradlew.bat runClient "-Pminecraft_version=1.20.1" -Plegacy_checks "-Dcheatprogram.dev=open-check"
```

It creates a fresh isolated test world and verifies native glass shaders and player previews as well as the shared AI/building checks. Development fixtures are excluded from normal JARs. All supported builds require the Java version listed in the compatibility matrix, including Java 21 for the legacy targets.

The optional gameplay diagnostic uses `-Pgameplay_checks` and `-Dcheatprogram.dev=gameplay-check`, with a separate world and configuration under `run-gameplay/<version>/`. Its checked 1.21.11 path injects the native inventory key event and invokes native attack/use routines, then checks the resulting server block states. Glass inventory rendering is disabled in this diagnostic to establish the native input baseline; its screenshots are not evidence that the glass overlay is correct. Runtime probes are excluded from release JARs and their worlds/configuration are excluded from source packages.

The standalone BuildingJobQueueCheck can be compiled with BuildingJobQueue alone (Java 21 or newer); no game is needed. It simulates an old integrated server abandoning work and checks that later jobs still advance. TextFieldUnicodeCheck exercises committed text and editing; it needs TextField, UnicodeText, GLFW constants and a Minecraft clipboard class on the compilation classpath. These checks do not replace a real Windows IME retest.

AlbumSceneGeometryCheck and ArtworkPreloadCheck run without Minecraft. Compile them with AlbumSpaceModel, ArtworkPreloadCursor and JOML using Java 21 or newer. They verify the constellation and planet layouts, rejection of retired layouts and full-library prefetch scheduling. These checks do not validate Minecraft integration or third-party platform playback.
