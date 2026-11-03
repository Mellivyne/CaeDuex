# Shared projections and floating lyrics

Cheat Program clients can share a selected application projection or floating lyrics through an invitation. The wire protocol is independent of Minecraft mappings and mod loaders. The Minecraft server does not need a plugin or a modified networking protocol.

## In the game

Open **Projection → Shared viewing** or **Floating lyrics → Shared viewing**.

The sender can leave the address blank and create a direct LAN invitation, with no account, relay or certificate setup. Alternatively, enter a TLS relay address, such as `tls://relay.example.com:8443`. Choose the desired projection source before creating a video invitation. Only that screen is broadcast. Changing the selected screen does not silently change an existing broadcast. Stop sharing before changing video quality.

The viewer pastes the complete invitation into the corresponding feature's shared-viewing page. The invitation already contains the address and decryption key. The viewer never needs the sender's music-platform credentials. Watching does not enable application input or remote control.

Both the generated invitation and the viewer's invitation field are masked by default. Click the eye icon on the right to reveal or hide either value. Copying the generated invitation still copies the full value while it stays masked on screen. Closing or leaving the page, joining a broadcast, regenerating a code or receiving a replacement code restores the hidden state. Masking protects against accidental on-screen disclosure; the complete invitation in the clipboard remains a private viewing credential.

By default, shared screens and captions occupy the sender's world coordinates and dimension. Stand at that location to view them. Projection viewers can instead enable **Show in front of me**, using their own selected screen's placement and curvature. Native lyrics retain the sender's placement, font choice, sung text, translations, palette, glow and motion. Different font installations can produce small differences in glyph appearance.

- **1080p / 60 FPS**: up to 1920 × 1080 pixels with a 60 FPS upper target. Non-widescreen application windows preserve their original aspect ratio, without stretching or adding empty borders.
- **Source resolution / frame rate**: no deliberate downscale or cadence cap; high-quality compression. Odd source dimensions are padded by at most one pixel for codec compatibility. This is not a promise of mathematically lossless pixels or zero latency.
- **Floating lyrics**: native geometry and text updates, with animations rendered at the viewer's local game frame rate. No video quality or frame-rate selector and no FFmpeg requirement.

Actual video throughput depends on capture cadence, encoding/decoding capacity, upload bandwidth, relay capacity and viewer hardware. Video uses persistent low-delay H.264 with independent IDR pictures. Late viewers can join without waiting for a keyframe sequence, and stale queued pictures can be discarded safely. NVIDIA NVENC, Intel Quick Sync and AMD AMF are probed, with a software fallback. Both video peers need a current FFmpeg installation on `PATH`, including H.264 encoding/decoding support. Restart Minecraft after changing `PATH`.

Audio is not sent by this feature. Existing local spatial audio remains separate.

## Direct sharing without a server

The default blank address starts an in-process TLS host on a temporary port. The invitation contains the local network address, random viewing capability, decryption key and a SHA-256 pin for the ephemeral host public key. The viewer authenticates the exact pinned key; a different self-signed certificate is rejected. Certificates and private keys stay in memory and require no extra library, command-line tool or certificate authority. Stopping or regenerating sharing closes the listener and disconnects its viewers.

This works when the clients can reach one another, such as on the same LAN with Java allowed through the host firewall. To advertise a manually reachable address and fixed listening port, enter `direct://your-host.example:8443`, or a bracketed IPv6 address. Configure any necessary routing and port forwarding yourself. The program does not silently change router settings or firewall rules. A private LAN address is not automatically reachable from the public Internet.

Open source does not imply that someone is operating a public relay. For arbitrary public-network clients behind incompatible NAT or firewall configurations, a reachable shared service is still needed. Both the direct host implementation and optional Python relay are part of this project; users can run their own, and no paid account or central vendor account is required.

## Running an optional relay

There is no bundled public relay, no automatic connection to a third party and no account registration. For relay mode, a reachable relay must be provided by the host or operator. TLS certificate verification is mandatory; the certificate must match the relay hostname and be trusted by the clients' Java runtimes.

The relay needs Python 3.10 or newer and no third-party Python packages:

```sh
python tools/appcast_relay.py --bind 0.0.0.0 --port 8443 --cert /path/to/fullchain.pem --key /path/to/privkey.pem
```

Allow TCP access to the chosen port and use the externally reachable DNS name in the game. A machine behind NAT needs an appropriately configured reachable endpoint or port forwarding. Do not use the documentation's example address as an actual service.

Use a certificate issued by a trusted CA. Private-CA deployments must install their CA in the clients' Java trust stores. The client intentionally offers no insecure certificate bypass. Direct mode authenticates the ephemeral public-key pin embedded in the invitation instead of relying on a public CA.

## Invitation lifecycle and privacy

Each live relay session receives a collision-checked random 128-bit capability. The publishing client also generates a separate 256-bit media key. The full invitation includes both; the media key is never sent to the relay. Share the complete invitation only with intended viewers.

The transport uses TLS with hostname verification. Video, placement and caption payloads also use end-to-end AES-256-GCM authenticated encryption. The relay routes opaque ciphertext and keeps at most one latest packet and one pending packet per viewer. It does not write recordings, platform cookies, API keys or invitations to disk. It can observe connection addresses, packet sizes and timing.

Revoking an invitation closes its publishing connection, removes the session and disconnects all current viewers. Regeneration closes the old session and creates a new capability and media key. Leaving the world, disabling the relevant feature or exiting Minecraft stops sharing. Invitations are not saved in profiles or configuration codes. Closing the settings page alone does not stop an explicitly started broadcast.

The reference relay limits sessions to 64, viewers to 16 per session, total connections to 256 and encrypted packets to 16 MiB. Slow viewers have old queued packets replaced rather than accumulating a playback backlog. Each client can publish one projection and one native lyric scene, and independently join one broadcast of each type.

## Validation

`tools/check_shared_projection.py` runs a temporary local TLS relay and standalone Java clients. It verifies automatic serverless LAN hosting, pinned direct-host identity, direct revocation, certificate identity, invite generation/revocation, viewer fan-out, late joining, authenticated encryption, corrupted-payload rejection, native scenes, live video and mid-stream independent 1080p decoding. Test certificates and keys are temporary and are never packaged. Set `JAVA21_HOME` to a Java 21 JDK. On Windows, certificate generation uses PowerShell 7. Set `CP_TEST_SOFTWARE=1` to exercise the software encoder separately.

Local protocol checks do not replace multiplayer testing across different PCs and real networks. Neither 60 FPS over an arbitrary network nor every GPU driver is guaranteed by a successful build.
