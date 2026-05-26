# WispShell v1: Codex Handoff

Last updated: 2026-05-24

## 0. Mission

Build WispShell v1: a single-purpose remote shell product for accessing a user-owned Linux machine from an Android phone or tablet without router port forwarding and without exposing SSH to the public internet.

The v1 shape is:

```text
Android app  <->  Cloud register/rendezvous  <->  Linux daemon
      \                                             /
       \_______ P2P QUIC/UDP when possible ________/
                relay fallback when needed
```

The daemon owns the shell session. The Android app owns the terminal UI. The cloud owns discovery, pairing brokerage, and rendezvous metadata. The cloud must not see terminal plaintext.

## 1. Product constraints

### Must have

- Linux daemon that runs as the logged-in user, not root.
- Android companion app.
- Cloud register for device registration, pairing brokerage, presence, and rendezvous.
- QR-code pairing initiated from the Linux daemon.
- P2P-preferred UDP transport, with relay fallback.
- End-to-end encrypted terminal traffic.
- Persistent shell session across Android disconnect/reconnect.
- One default session named `main`.
- No SSH, no VPN, no router port forwarding.

### Must not have in v1

- Remote desktop.
- File sync.
- Browser IDE.
- Team/workspace sharing.
- Full VPN.
- Public SSH gateway.
- Stealth installation or hidden persistence.
- Root-level agent by default.
- Arbitrary third-party server access.

This product is only for devices the user owns and explicitly pairs locally.

## 2. Technical choices

### Linux daemon

Use Rust.

Suggested crates:

```toml
tokio = { version = "1", features = ["full"] }
anyhow = "1"
thiserror = "1"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
uuid = { version = "1", features = ["v4", "serde"] }
tracing = "0.1"
tracing-subscriber = "0.3"
directories = "5"
portable-pty = "0.9"
iroh = "0.97"
ed25519-dalek = "2"
rand = "0.8"
sha2 = "0.10"
hmac = "0.12"
base64 = "0.22"
qr2term = "0.3"
reqwest = { version = "0.12", features = ["json", "rustls-tls"] }
```

### Cloud register

Use Rust + Axum.

Suggested crates:

```toml
axum = { version = "0.8", features = ["ws"] }
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
sqlx = { version = "0.8", features = ["runtime-tokio", "sqlite", "uuid", "chrono"] }
chrono = { version = "0.4", features = ["serde"] }
uuid = { version = "1", features = ["v4", "serde"] }
tracing = "0.1"
```

Use SQLite for local development. Keep the schema simple enough to move to Postgres later.

### Android app

Use Kotlin + Jetpack Compose.

Use Google Code Scanner or ML Kit for QR scanning. Use a bundled local WebView running xterm.js for v1 terminal rendering. The WebView is not a remote webpage. It loads local assets bundled inside the APK.

Use a Rust networking core compiled for Android and exposed to Kotlin through UniFFI. Keep Kotlin UI code separate from protocol/transport logic.

Suggested Android modules:

```text
apps/android/
  app/                       Kotlin Compose app
  wispshell-core-android/    generated UniFFI/Kotlin bindings
```

Suggested Rust crates:

```text
crates/wispshell-core/       shared client-side Rust core, built for Android
crates/wispshell-protocol/   shared message definitions
```

## 3. Repository layout

```text
repo/
  Cargo.toml
  README.md
  TASK.md
  justfile
  docker-compose.yml
  .env.example

  crates/
    wispshell-protocol/
      Cargo.toml
      src/lib.rs
      src/messages.rs
      src/crypto.rs
      src/signing.rs

    wispshell-agent/
      Cargo.toml
      src/main.rs
      src/config.rs
      src/state.rs
      src/device.rs
      src/pairing.rs
      src/session.rs
      src/pty.rs
      src/transport_iroh.rs
      src/cloud_client.rs
      src/systemd.rs

    wispshell-cloud/
      Cargo.toml
      migrations/
      src/main.rs
      src/db.rs
      src/routes.rs
      src/auth_device.rs
      src/devices.rs
      src/pairing.rs
      src/presence.rs
      src/rendezvous.rs
      src/ws.rs

    wispshell-core/
      Cargo.toml
      src/lib.rs
      src/client.rs
      src/pairing.rs
      src/terminal.rs
      src/ffi.udl
      src/ffi.rs

  apps/
    android/
      settings.gradle.kts
      build.gradle.kts
      app/
        build.gradle.kts
        src/main/AndroidManifest.xml
        src/main/assets/terminal/index.html
        src/main/assets/terminal/xterm.js
        src/main/assets/terminal/xterm.css
        src/main/java/dev/wispshell/app/MainActivity.kt
        src/main/java/dev/wispshell/app/ui/PairScreen.kt
        src/main/java/dev/wispshell/app/ui/DeviceListScreen.kt
        src/main/java/dev/wispshell/app/ui/TerminalScreen.kt
        src/main/java/dev/wispshell/app/terminal/WispTerminalWebView.kt
```

## 4. Identity model

Use accountless QR-first identity for v1.

Every device has a long-term Ed25519 identity:

```text
device_private_key
wisp_public_key
device_id = base64url(sha256(wisp_public_key))[0..32]
```

The Linux daemon has:

```text
daemon_device_id
daemon_private_key
daemon_public_key
iroh_secret_key
iroh_node_id
trusted_clients[]
```

The Android app has:

```text
client_device_id
client_private_key
client_public_key
bound_daemons[]
```

The cloud stores public keys and presence metadata only. It never stores private keys or terminal data.

## 5. QR pairing flow

Pairing must require local access to the Linux machine.

### User experience

User runs:

```bash
wispshelld pair
```

The daemon prints:

```text
WispShell pairing

Scan this code in the Android app.
Expires in 2 minutes.

<terminal QR code>

Code: 7KQ9-MD2P
```

Android app:

```text
Open app -> Scan daemon QR -> Pair -> Laptop appears in device list
```

### QR payload

Use a custom URI:

```text
wispshell://pair?v=1&cloud=https%3A%2F%2Fapi.example.dev&pair_id=...&daemon_id=...&daemon_pub=...&secret=...&expires=...
```

Fields:

```json
{
  "v": 1,
  "cloud": "https://api.example.dev",
  "pair_id": "base64url-128-bit-random",
  "daemon_id": "device id",
  "daemon_pub": "base64url-ed25519-pubkey",
  "secret": "base64url-256-bit-random",
  "expires": "2026-05-24T22:15:00Z"
}
```

Rules:

- `secret` is shown only in the QR.
- `secret` is not sent to the cloud by the daemon.
- Pairing expires after 2 minutes.
- Pairing is single-use.
- Pairing must be confirmable from daemon logs or CLI.

### Pairing protocol

Step 1: daemon starts pairing.

```http
POST /v1/pairing/start
```

Body, signed by daemon device key:

```json
{
  "pair_id": "...",
  "daemon_device_id": "...",
  "daemon_public_key": "...",
  "expires_at": "..."
}
```

Cloud stores the pairing session, but not `secret`.

Step 2: Android scans QR and creates its identity.

The app sends:

```http
POST /v1/pairing/claim
```

```json
{
  "pair_id": "...",
  "client_device_id": "...",
  "client_public_key": "...",
  "client_name": "Pixel 8",
  "proof": "base64url(HMAC_SHA256(secret, pair_id || daemon_id || client_device_id || client_public_key))",
  "client_signature": "signature over request hash"
}
```

Step 3: cloud forwards claim to daemon over its WebSocket event channel.

```json
{
  "type": "pairing_claimed",
  "pair_id": "...",
  "client_device_id": "...",
  "client_public_key": "...",
  "client_name": "Pixel 8",
  "proof": "..."
}
```

Step 4: daemon verifies:

```text
pair_id exists locally
pairing not expired
pairing not used
HMAC proof is correct
client public key is valid
```

Step 5: daemon approves and signs a binding certificate.

```json
{
  "binding_id": "uuid",
  "daemon_device_id": "...",
  "daemon_public_key": "...",
  "client_device_id": "...",
  "client_public_key": "...",
  "permissions": ["terminal"],
  "created_at": "..."
}
```

The daemon signs this blob with `daemon_private_key`.

Step 6: cloud stores binding metadata and returns the signed binding to Android.

Android stores:

```text
client private key
client public key
daemon public key
binding certificate
binding signature
```

## 6. Device-authenticated cloud requests

Do not implement password accounts in v1.

Each REST request after device registration should be signed:

```text
X-Wisp-Device-Id: ...
X-Wisp-Timestamp: 2026-05-24T22:15:00Z
X-Wisp-Signature: base64url(ed25519_sign(payload))
```

Signature payload:

```text
METHOD\nPATH\nTIMESTAMP\nSHA256_HEX(BODY)
```

Cloud verifies:

```text
device exists
public key matches device_id
signature is valid
timestamp is within 5 minutes
```

For local development, allow `WISPSHELL_DEV_AUTH=1` to bypass signatures, but keep the production path implemented.

## 7. Cloud schema

SQLite migrations:

```sql
create table devices (
  device_id text primary key,
  kind text not null check (kind in ('daemon', 'android')),
  display_name text not null,
  public_key text not null,
  created_at text not null,
  last_seen_at text
);

create table pairing_sessions (
  pair_id text primary key,
  daemon_device_id text not null references devices(device_id),
  daemon_public_key text not null,
  expires_at text not null,
  used_at text,
  created_at text not null
);

create table bindings (
  binding_id text primary key,
  daemon_device_id text not null references devices(device_id),
  client_device_id text not null references devices(device_id),
  permissions_json text not null,
  binding_cert_json text not null,
  binding_signature text not null,
  created_at text not null,
  revoked_at text,
  unique(daemon_device_id, client_device_id)
);

create table presence (
  device_id text primary key references devices(device_id),
  status text not null check (status in ('online', 'offline')),
  iroh_node_addr_json text,
  updated_at text not null
);
```

## 8. Cloud API

Required routes:

```http
GET  /healthz

POST /v1/devices/register
GET  /v1/devices/:device_id

POST /v1/pairing/start
POST /v1/pairing/claim
POST /v1/pairing/approve
GET  /v1/pairing/:pair_id

POST /v1/presence/update
GET  /v1/rendezvous/:daemon_device_id

GET  /v1/bindings/for-client/:client_device_id
GET  /v1/bindings/for-daemon/:daemon_device_id
POST /v1/bindings/:binding_id/revoke

GET  /v1/agent/events/ws?device_id=...
```

`/v1/rendezvous/:daemon_device_id` returns:

```json
{
  "daemon_device_id": "...",
  "daemon_public_key": "...",
  "status": "online",
  "iroh_node_addr": { "opaque": "json from iroh" },
  "updated_at": "..."
}
```

Only bound clients should be able to fetch rendezvous info for a daemon.

## 9. Transport

Use Iroh for P2P QUIC/UDP connectivity.

The daemon:

```text
starts Iroh endpoint
publishes node address to cloud presence
accepts WispShell ALPN only
```

The Android Rust core:

```text
fetches daemon node address from cloud
opens Iroh connection
performs app-level mutual auth
opens a bidirectional stream
attaches to session main
```

Define ALPN:

```rust
pub const WISP_ALPN: &[u8] = b"wispshell/1";
```

Use newline-delimited JSON for v1 framing:

```text
{json}\n
{json}\n
```

PTY bytes are base64 encoded in JSON. Later, replace this with binary frames.

## 10. App-level handshake over Iroh

After the Iroh connection opens, do not immediately accept terminal commands.

Client sends:

```json
{
  "type": "client_hello",
  "protocol_version": 1,
  "client_device_id": "...",
  "client_public_key": "...",
  "daemon_device_id": "...",
  "binding_id": "...",
  "nonce": "..."
}
```

Daemon replies:

```json
{
  "type": "agent_challenge",
  "daemon_device_id": "...",
  "daemon_public_key": "...",
  "client_nonce": "...",
  "daemon_nonce": "..."
}
```

Client replies:

```json
{
  "type": "client_auth",
  "signature": "sign(client_private_key, transcript_hash)"
}
```

Daemon verifies:

```text
client public key is in trusted_clients
binding not revoked
signature is valid
requested daemon ID matches this daemon
```

Daemon replies:

```json
{
  "type": "agent_auth_ok",
  "signature": "sign(daemon_private_key, transcript_hash)"
}
```

Client verifies the daemon signature using the daemon public key stored from QR pairing.

Only after this can the client send `attach`.

## 11. Terminal protocol

Put shared messages in `wispshell-protocol`.

```rust
pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientToAgent {
    ClientHello {
        protocol_version: u32,
        client_device_id: String,
        client_public_key: String,
        daemon_device_id: String,
        binding_id: String,
        nonce: String,
    },
    ClientAuth {
        signature: String,
    },
    Attach {
        session_name: String,
        cols: u16,
        rows: u16,
    },
    Input {
        session_id: String,
        data_b64: String,
    },
    Resize {
        session_id: String,
        cols: u16,
        rows: u16,
    },
    Detach {
        session_id: String,
    },
    Ping {
        nonce: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AgentToClient {
    AgentChallenge {
        daemon_device_id: String,
        daemon_public_key: String,
        client_nonce: String,
        daemon_nonce: String,
    },
    AgentAuthOk {
        signature: String,
    },
    SessionAttached {
        session_id: String,
        session_name: String,
        cols: u16,
        rows: u16,
    },
    Scrollback {
        session_id: String,
        chunks_b64: Vec<String>,
    },
    Output {
        session_id: String,
        data_b64: String,
    },
    SessionExited {
        session_id: String,
        exit_code: Option<i32>,
    },
    Error {
        code: String,
        message: String,
    },
    Pong {
        nonce: String,
    },
}
```

## 12. Linux daemon behavior

Commands:

```bash
wispshelld run
wispshelld pair
wispshelld status
wispshelld devices list
wispshelld devices revoke <client_device_id>
wispshelld install-user-service
wispshelld uninstall-user-service
```

State files:

```text
~/.config/wispshell/agent.toml
~/.local/share/wispshell/agent-state.json
```

Permissions:

```text
agent-state.json must be chmod 0600
config can be chmod 0644
```

Default config:

```toml
cloud_url = "https://api.wispshell.example"
device_name = "My Linux Laptop"
shell = ""
scrollback_bytes = 1048576
```

Shell resolution:

```text
if config.shell non-empty -> use it
else if SHELL exists -> use SHELL
else /bin/bash
else /bin/sh
```

## 13. Session manager

The daemon owns sessions.

```rust
pub struct Session {
    pub id: String,
    pub name: String,
    pub created_at: DateTime<Utc>,
    pub last_active_at: DateTime<Utc>,
    pub cols: u16,
    pub rows: u16,
    pub scrollback: VecDeque<Vec<u8>>,
    pub attached_clients: HashSet<String>,
    pub input_owner: Option<String>,
}
```

Methods:

```rust
impl SessionManager {
    pub async fn attach_or_create(
        &self,
        client_device_id: String,
        session_name: String,
        cols: u16,
        rows: u16,
    ) -> Result<SessionAttachResult>;

    pub async fn input(
        &self,
        client_device_id: String,
        session_id: String,
        bytes: Vec<u8>,
    ) -> Result<()>;

    pub async fn resize(
        &self,
        session_id: String,
        cols: u16,
        rows: u16,
    ) -> Result<()>;

    pub async fn detach(
        &self,
        client_device_id: String,
        session_id: String,
    ) -> Result<()>;
}
```

Rules:

- Create `main` on first attach.
- Reuse `main` on subsequent attach.
- Detach must not kill the PTY.
- Android disconnect must not kill the PTY.
- Daemon shutdown may kill PTYs in v1.
- Newest attached client becomes `input_owner`.
- All attached clients receive output.
- Only `input_owner` can send input.

## 14. PTY handling

Use `portable-pty`.

Behavior:

```text
spawn child shell inside PTY
read PTY output on a background task
append output to scrollback ring
broadcast output to attached clients
write client input to PTY writer
resize PTY when Android terminal size changes
```

Do not log PTY input or output.

## 15. Android app behavior

Screens:

```text
PairScreen
DeviceListScreen
TerminalScreen
SettingsScreen
```

### PairScreen

Actions:

```text
Scan QR
Parse wispshell://pair URI
Create client identity if needed
Send pairing claim
Wait for approval
Store binding
Navigate to DeviceListScreen
```

Use Google Code Scanner where available. Fall back to ML Kit camera scanner later if needed.

### DeviceListScreen

Shows bound daemons:

```text
ThinkPad T480      Online
Framework Laptop   Offline
```

Actions:

```text
Tap online daemon -> connect and open TerminalScreen
Pull to refresh -> fetch bindings and presence
Long press -> forget local binding, with confirmation
```

### TerminalScreen

Use local xterm.js in WebView.

Bridge:

```text
JS terminal input -> Kotlin -> Rust core -> Iroh stream -> daemon PTY
Daemon output -> Rust core -> Kotlin -> JS terminal.write(bytes)
```

On app foreground:

```text
if last daemon exists -> reconnect -> attach main
```

On app background:

```text
detach if possible, or allow connection to drop
never assume Android will keep the socket alive forever
```

## 16. Android background behavior

Do not design v1 around a permanent background Android socket.

Expected behavior:

```text
App visible: active interactive connection
App backgrounded: detach/connection may stop
App resumed: reconnect and reattach to main
```

No foreground service is required for v1. Add one later only for explicit user-visible long-running sessions.

## 17. Systemd user service

Generate this with `wispshelld install-user-service`:

```ini
[Unit]
Description=WispShell user daemon
After=network-online.target

[Service]
ExecStart=%h/.local/bin/wispshelld run
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

Enable with:

```bash
systemctl --user daemon-reload
systemctl --user enable --now wispshelld.service
```

Do not install as a system service in v1.

## 18. Development commands

`justfile`:

```make
run-cloud:
	cargo run -p wispshell-cloud

run-agent:
	cargo run -p wispshell-agent -- run

pair-agent:
	cargo run -p wispshell-agent -- pair

test:
	cargo test --workspace

android:
	cd apps/android && ./gradlew installDebug
```

`.env.example`:

```env
WISPSHELL_CLOUD_BIND=127.0.0.1:8080
WISPSHELL_DATABASE_URL=sqlite://wispshell.db
WISPSHELL_DEV_AUTH=1
RUST_LOG=wispshell=debug,tower_http=info
```

## 19. Milestones

### Milestone 1: Rust workspace and protocol

Acceptance:

- Workspace builds.
- Protocol crate serializes/deserializes all messages.
- Device ID and signature helpers tested.

### Milestone 2: Agent local PTY

Acceptance:

- `wispshelld run --local-tcp 127.0.0.1:7777` starts.
- A test client connects locally.
- `Attach(main)` spawns shell.
- Input/output works.
- Disconnect does not kill session.
- Reconnect returns to same session.

### Milestone 3: Cloud register

Acceptance:

- Cloud starts with SQLite.
- Device register route works.
- Presence route works.
- Rendezvous route returns online/offline.
- WebSocket agent event channel works.

### Milestone 4: QR pairing

Acceptance:

- `wispshelld pair` prints QR and text code.
- Android can scan/parse QR.
- Android submits pairing claim.
- Daemon receives claim over cloud WebSocket.
- Daemon verifies HMAC.
- Binding certificate created and stored.
- `wispshelld devices list` shows Android device.

### Milestone 5: Android terminal UI

Acceptance:

- Android app has pair screen, device list, terminal screen.
- Terminal WebView loads local xterm.js assets.
- Local mock output renders.
- Input events reach Kotlin.

### Milestone 6: Iroh transport

Acceptance:

- Daemon publishes Iroh node address to cloud.
- Android Rust core fetches rendezvous info.
- Android connects to daemon over Iroh.
- App-level handshake succeeds.
- Terminal attaches to `main`.
- Shell input/output works without SSH or port forwarding.

### Milestone 7: Handoff behavior

Acceptance:

- Android phone attaches to `main`.
- User runs `watch date` or a long-running command.
- App closes or disconnects.
- App reopens and sees the same session.
- A second Android device paired to the daemon can attach to `main` and becomes input owner.

## 20. Tests

### Protocol tests

- Serialize/deserialize all message types.
- Reject unsupported protocol version.
- Verify device ID derivation.
- Verify REST request signing and validation.
- Verify pairing HMAC.

### Agent tests

- First attach creates session.
- Second attach reuses session.
- Detach does not kill session.
- Non-owner input is rejected.
- Revoked client cannot connect.
- Pairing expires.
- Pairing is single-use.

### Cloud tests

- Register daemon.
- Register Android client.
- Start pairing.
- Claim pairing.
- Approve pairing.
- Fetch bindings.
- Update presence.
- Fetch rendezvous.
- Reject unauthorized rendezvous request.

### Android tests

- Parse valid QR URI.
- Reject expired QR URI.
- Reject wrong URI scheme.
- Terminal bridge sends input events.
- Device list renders online/offline state.

## 21. Security requirements

Do:

- Require local QR pairing.
- Store private keys only on the device that owns them.
- Run daemon as the normal Linux user.
- Keep private state file mode `0600`.
- Sign cloud requests.
- Mutually authenticate over Iroh before shell access.
- Never log terminal input/output.
- Support device revocation.
- Show clear device names during pairing.
- Make pairing codes expire quickly.

Do not:

- Add hidden install modes.
- Add stealth persistence.
- Run as root by default.
- Expose SSH.
- Proxy plaintext terminal data through the cloud.
- Store Linux passwords.
- Let cloud alone grant shell access.
- Allow unpaired devices to fetch daemon rendezvous data.

## 22. Error codes

Use these exact strings:

```text
invalid_request
unauthorized
signature_invalid
pairing_expired
pairing_used
pairing_not_found
pairing_proof_invalid
device_not_found
device_not_bound
device_revoked
device_offline
rendezvous_unavailable
protocol_version_unsupported
handshake_failed
session_not_found
not_input_owner
pty_spawn_failed
transport_error
internal_error
```

## 23. Logging rules

Log:

```text
daemon started
device registered
pairing started
pairing claimed
pairing approved
pairing expired
client connected
client disconnected
session created
session attached
session detached
session exited
presence updated
rendezvous requested
```

Never log:

```text
terminal input
terminal output
pair_secret
private keys
request signatures
binding private material
full QR URI
```

## 24. README requirements

Generate README with:

- What WispShell is.
- Architecture diagram.
- Security model.
- Local development setup.
- How to run cloud.
- How to run daemon.
- How to pair Android app.
- Known limitations.
- Threat model notes.

Known limitations:

```text
Linux daemon only in v1.
Android app only in v1.
Sessions survive mobile disconnects, not daemon restarts.
Terminal sync is byte-stream based, not Mosh-style screen-state sync.
Relay fallback depends on Iroh relay availability/configuration.
No account recovery in accountless v1.
No file transfer.
No remote desktop.
```

## 25. Codex implementation instruction

Implement this as a working vertical slice. Prioritize in this order:

1. Explicit local-consent pairing.
2. End-to-end authenticated shell transport.
3. Persistent daemon-owned session.
4. Simple local dev.
5. Android polish.

Build the loopback/local path first, then cloud pairing, then Iroh transport.

Do not get distracted by accounts, billing, browser clients, SSH compatibility, or VPN features. The sparkle moment is:

```text
wispshelld pair
Android scans QR
Android taps laptop
Shell opens
App closes
App reopens
Same shell is still alive
```

## 26. Reference notes checked during planning

- Iroh: P2P QUIC with NAT traversal and relay fallback.
- portable-pty: Rust PTY abstraction for spawning shell processes.
- Axum: WebSocket extractor available with the `ws` feature.
- Google Code Scanner / ML Kit: Android QR scanning options.
- xterm.js: terminal frontend for browser/WebView-style terminal UI.
- UniFFI: generate Kotlin bindings for Rust core code.
