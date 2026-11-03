# v1.2 release preparation

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
