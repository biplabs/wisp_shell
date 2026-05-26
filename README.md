# WispShell

WispShell is a remote shell prototype for accessing a user-owned Linux machine from an Android phone without SSH exposure, VPN setup, or router port forwarding.

```text
Android app  <->  Cloud register/rendezvous  <->  Linux daemon
      \                                             /
       \_________ authenticated direct transport ___/
```

## Components

- `components/agent`: Linux user daemon (`wispshelld`) with QR pairing and a persistent `main` PTY session.
- `components/cloud`: Axum cloud register for devices, pairing, bindings, presence, rendezvous, and agent events.
- `components/android`: Jetpack Compose Android app scaffold with pair, device list, and terminal screens.
- `crates/wispshell-protocol`: Shared message, crypto, signing, and pairing helpers.
- `crates/wispshell-core`: Shared client-side Rust parsing/client helpers intended for Android UniFFI.

## Local Development

```bash
cargo run -p wispshell-cloud
cargo run -p wispshell-agent -- pair
cargo run -p wispshell-agent -- pair WISP-22-2222-2222-2222-2222-2222-2222
cargo run -p wispshell-agent -- run --local-tcp 127.0.0.1:7777
```

Run tests:

```bash
cargo test --workspace
```

The local TCP daemon accepts newline-delimited protocol JSON. `Attach` creates or reuses the daemon-owned `main` PTY, `Input` writes to the shell, and disconnects do not kill the session.

Agent config lives at the `config_path` printed by `wispshelld status`. `scrollback_bytes` controls the in-memory terminal history cap, while `scrollback_replay_bytes` controls how much of that history is streamed to a client on reconnect. The default replay cap is 65536 bytes; set it to 0 to disable replay.

## Pairing Without A Camera

The Android app can generate and display a high-entropy one-time code:

```text
WISP-22-2222-2222-2222-2222-2222-2222
```

The Linux user types that code into the daemon:

```bash
wispshelld pair WISP-...
```

The cloud stores only a hash of the Android-generated code. Code sessions are short-lived and single-use. This keeps the local-consent property of QR pairing while supporting tablets without cameras.

## Security Model

Devices have Ed25519 identities. Pairing is QR-first and local-consent based; the QR carries a short-lived secret that is not sent by the daemon to the cloud. REST request signing helpers are implemented in the protocol crate, and the cloud keeps terminal traffic out of band.

Known limitations: Linux daemon only, Android app only, sessions survive mobile disconnects but not daemon restarts, terminal sync is byte-stream based, relay/P2P transport is not yet wired into this slice, no account recovery, no file transfer, and no remote desktop.
