# v1.2 release preparation

## Source-quality default and 1080p120 sharing

All five AppCast adapters default new/reset sharing settings to source quality and capture cadence, retaining the existing saved mode IDs. The three sharing pages cycle through source, 1080p60 and 1080p120; native-caption pages retain no quality selector. Capture requests, the publisher's frame gate and encoder input metadata all use the chosen rate. The 1080p variants preserve aspect ratio.

The real TLS/codec integration check passed with the hardware encoder and with software encoding forced. It includes live 1080p120 encoding and independent decoding, the source default and simulated capture pacing for all three modes. The pacing check supplies 125 Hz capture timestamps: both rate caps retain their expected count within two frames, avoiding the former repeated under-rate drop when capture arrives just before a fixed interval. UI behavior checks execute all three production screen adapters and verify all three labels, cycling, blocked changes during publishing and absent video controls for native captions. Actual sustained 120 FPS across real computers and networks remains for user testing.

## Scroll-clipping hotfix

The follow-up review found that vanillaText did not pop its GUI matrix if glyph submission threw. A fault-injection harness reproduces the old leak and verifies all seven production fallback method bodies after adding finally-based restoration, including normal text advance and the next successful draw. Native GUI bridges now reuse their existing scissor snapshot rather than reading it a second time. Input hit clipping, native scissor use and shared-viewing state paths were also reviewed; this is a targeted review rather than an exhaustive correctness or security audit.

All twelve follow-up artifacts rebuilt and passed packaging checks. The updated 26.3 runtime again passed the explicit clip regression and six window/font/scale variants across all 48 module setting lists. Nine inactive PCL instances were backed up and refreshed; the currently running 26.2 instance was left on the preceding clip-fix build. No GitHub files were changed during this follow-up review.

An isolated 26.3 runtime reproduced the shipped v1.2 crash through R2D.vanillaText, GuiBridge.drawSpecial and NativeCachedMesh.draw: the native pass rejected an empty 100-by-0 scissor. This confirms a concrete failure path compatible with the report, without establishing that it is the only cause in the reporter's environment.

The patched runtime passed an explicit empty nested native-text clip, a partially offscreen clip, visible text after clipping, and repeated top/middle/bottom scrolling through all 48 module settings. Six combinations covered 854x480, 1280x720 and 1920x1080, 70%/150% interface scales, and native/smooth fonts. The fixture runs separately from user worlds and production JARs.

The headless clipping check invokes the real native render-pass validator. It first verifies that old zero-size and out-of-bounds rectangles throw, then runs 50,000 varied nested/scaled cases through the production clipping helper. Empty intersections remain invisible; nonempty rectangles satisfy native bounds checks. The helper also covers nonfinite input and integer overflow. Version-specific build results remain recorded in the matrix build reports.

Both 26.2 and 26.3 native validators passed. A separate check executes the actual pushClip/popClip/applyScissor method bodies from all seven production canvas adapters, verifying invisible rows, invalid input, partial intersections, parent restoration and final scissor cleanup. These adapters cover all twelve Fabric/Forge targets. Runtime gameplay on every target and the original reporter's full mod environment remain untested.

All ten Fabric and both Forge hotfix artifacts compiled and passed packaging checks. Every JAR includes FramebufferClip and the corresponding canvas adapter; applicable native-mesh adapters reference the bounded helper. Private runtime fixtures and regression classes are excluded. The local flat release bundle and hashes have been refreshed. Nine inactive PCL instances were backed up and updated; one running instance was left unchanged. The GitHub release has not been changed by this local hotfix task.

The project version is 1.2 for both Fabric and Forge. Minecraft target versions and the mod ID remain unchanged. The build scripts validate Forge/Fabric version agreement and retain loader/game identifiers in artifact names.

This revision changes release versioning and documentation. It includes the previously built shared-projection and native-caption implementation, private-invitation controls and existing feature fixes. Detailed earlier checks remain recorded in RESULTS-v1.1.md; no older gameplay result is represented as a new v1.2 gameplay run.

The release bundle contains only exact-version mod JARs, SHA-256 checksums, release notes and a machine-readable artifact manifest. Runtime profiles, accounts, API keys, invitations, local worlds and private launch helpers are excluded.

## Build and packaging results

- All ten Fabric targets compiled and packaged: 1.20.1, 1.20.4, 1.21.4, 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 and 26.3.
- Both Forge targets compiled and packaged: 1.20.1 and 1.20.4.
- Every filename, embedded mod version, exact Minecraft dependency and artifact manifest identifies project version 1.2 and the matching loader/game target. Forge resource-pack descriptions also identify v1.2.
- All twelve artifacts pass project-link integrity and notice-content checks. Shared-video/native-caption classes and the invitation-masking controls are present; private launch/test fixtures and runtime files are absent from the release JARs.
- The flat bundle at outputs/releases/v1.2 contains twelve JARs, SHA256SUMS.txt, RELEASE_NOTES.md and manifest.json. The copies at the project root match every recorded SHA-256 value.
- Superseded distribution files and the previous release metadata are preserved in an external backup. Existing installed game instances were not modified for this version-preparation step.

No Minecraft session was launched for the version bump. Prior real-client sharing tests used the preceding v1.1 build of the same feature implementation; they are not a new v1.2 gameplay verification. No GitHub upload, release, tag or visibility change was performed.
