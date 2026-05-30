use crate::{db::AppState, devices, pairing, presence, rendezvous, ws};
use axum::{
    response::Html,
    routing::{get, post},
    Json, Router,
};
use serde_json::json;
use tower_http::trace::TraceLayer;

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/", get(landing_page))
        .route("/healthz", get(|| async { Json(json!({"ok": true})) }))
        .route("/privacy", get(privacy_policy))
        .route("/v1/devices/register", post(devices::register))
        .route("/v1/devices/{device_id}", get(devices::get_device))
        .route("/v1/pairing/start", post(pairing::start))
        .route("/v1/pairing/claim", post(pairing::claim))
        .route("/v1/pairing/approve", post(pairing::approve))
        .route("/v1/pairing/code/start", post(pairing::start_code))
        .route("/v1/pairing/code/resolve", post(pairing::resolve_code))
        .route("/v1/pairing/code/approve", post(pairing::approve_code))
        .route("/v1/pairing/{pair_id}", get(pairing::get_pairing))
        .route("/v1/presence/update", post(presence::update))
        .route("/v1/rendezvous/{daemon_device_id}", get(rendezvous::get))
        .route(
            "/v1/bindings/for-client/{client_device_id}",
            get(pairing::bindings_for_client),
        )
        .route(
            "/v1/bindings/for-daemon/{daemon_device_id}",
            get(pairing::bindings_for_daemon),
        )
        .route("/v1/bindings/{binding_id}/revoke", post(pairing::revoke))
        .route("/v1/agent/events/ws", get(ws::agent_events))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

async fn landing_page() -> Html<&'static str> {
    Html(LANDING_PAGE_HTML)
}

async fn privacy_policy() -> Html<&'static str> {
    Html(PRIVACY_POLICY_HTML)
}

const LANDING_PAGE_HTML: &str = r#"<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>WispShell</title>
  <meta name="description" content="WispShell connects your Android device to your own Linux shell without exposing SSH to the public internet.">
  <style>
    :root {
      color-scheme: light dark;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      color: #17212b;
      background: #f5f7fb;
      line-height: 1.5;
    }
    * {
      box-sizing: border-box;
    }
    body {
      margin: 0;
      min-width: 320px;
      background: #f5f7fb;
    }
    a {
      color: inherit;
    }
    .shell {
      min-height: 100vh;
      display: flex;
      flex-direction: column;
    }
    header {
      width: 100%;
      max-width: 1120px;
      margin: 0 auto;
      padding: 24px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 20px;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 12px;
      font-weight: 800;
      font-size: 1.1rem;
      color: #111827;
    }
    .mark {
      width: 32px;
      height: 32px;
      border-radius: 8px;
      display: grid;
      place-items: center;
      color: #ffffff;
      background: #1f7a5a;
      font-weight: 900;
      font-size: 1rem;
    }
    nav {
      display: flex;
      align-items: center;
      gap: 16px;
      color: #44515f;
      font-size: 0.95rem;
    }
    nav a {
      text-decoration: none;
    }
    main {
      flex: 1;
    }
    .hero {
      width: 100%;
      max-width: 1120px;
      margin: 0 auto;
      padding: 52px 24px 64px;
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(320px, 0.86fr);
      gap: 56px;
      align-items: center;
    }
    .copy {
      max-width: 660px;
    }
    h1 {
      margin: 0;
      color: #111827;
      font-size: clamp(3rem, 8vw, 5.7rem);
      line-height: 0.94;
      letter-spacing: 0;
    }
    .lead {
      margin: 24px 0 0;
      color: #334155;
      font-size: clamp(1.1rem, 2vw, 1.35rem);
      max-width: 620px;
    }
    .actions {
      margin-top: 32px;
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
    }
    .button {
      min-height: 44px;
      padding: 11px 16px;
      border-radius: 8px;
      text-decoration: none;
      font-weight: 700;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid #cbd5e1;
      background: #ffffff;
      color: #17212b;
    }
    .button.primary {
      background: #1f7a5a;
      border-color: #1f7a5a;
      color: #ffffff;
    }
    .proof {
      margin-top: 34px;
      padding: 0;
      list-style: none;
      display: grid;
      gap: 10px;
      color: #475569;
      font-size: 0.98rem;
    }
    .proof li {
      display: flex;
      gap: 10px;
      align-items: flex-start;
    }
    .proof li::before {
      content: "";
      width: 7px;
      height: 7px;
      margin-top: 0.55em;
      border-radius: 50%;
      flex: 0 0 auto;
      background: #1f7a5a;
    }
    .device {
      position: relative;
      border-radius: 28px;
      padding: 14px;
      background: #101820;
      box-shadow: 0 24px 70px rgba(15, 23, 42, 0.25);
      aspect-ratio: 9 / 17;
      max-height: 690px;
      min-height: 500px;
    }
    .screen {
      height: 100%;
      border-radius: 20px;
      overflow: hidden;
      background: #0b1117;
      display: flex;
      flex-direction: column;
      border: 1px solid rgba(255,255,255,0.08);
    }
    .screenbar {
      min-height: 48px;
      padding: 0 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      background: #121a23;
      color: #d8dee9;
      font-size: 0.9rem;
      border-bottom: 1px solid rgba(255,255,255,0.08);
    }
    .status {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      color: #9ee6bd;
      font-weight: 700;
    }
    .status::before {
      content: "";
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #31c36b;
    }
    .terminal {
      flex: 1;
      margin: 0;
      padding: 22px 18px;
      color: #d8f3dc;
      font: 500 0.9rem/1.6 "SFMono-Regular", Consolas, "Liberation Mono", monospace;
      white-space: pre-wrap;
      background:
        linear-gradient(180deg, rgba(31,122,90,0.12), rgba(11,17,23,0) 40%),
        #0b1117;
    }
    .cursor {
      display: inline-block;
      width: 0.6em;
      height: 1em;
      transform: translateY(0.16em);
      background: #9ee6bd;
    }
    .band {
      background: #ffffff;
      border-top: 1px solid #dbe2ea;
    }
    .details {
      width: 100%;
      max-width: 1120px;
      margin: 0 auto;
      padding: 34px 24px;
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 28px;
    }
    .details h2 {
      margin: 0 0 8px;
      color: #111827;
      font-size: 1rem;
    }
    .details p {
      margin: 0;
      color: #52606d;
    }
    @media (max-width: 860px) {
      header {
        align-items: flex-start;
        flex-direction: column;
      }
      .hero {
        padding-top: 28px;
        grid-template-columns: 1fr;
        gap: 42px;
      }
      .device {
        width: min(100%, 390px);
        margin: 0 auto;
        min-height: 0;
      }
      .details {
        grid-template-columns: 1fr;
      }
    }
    @media (max-width: 520px) {
      nav {
        width: 100%;
        justify-content: space-between;
      }
      .hero {
        padding-left: 18px;
        padding-right: 18px;
      }
      .actions {
        flex-direction: column;
      }
      .button {
        width: 100%;
      }
      .terminal {
        font-size: 0.8rem;
      }
    }
    @media (prefers-color-scheme: dark) {
      :root, body {
        color: #d8dee9;
        background: #0f141a;
      }
      .brand, h1, .details h2 {
        color: #f8fafc;
      }
      .lead {
        color: #c7d2df;
      }
      nav, .proof, .details p {
        color: #a4afbc;
      }
      .button {
        background: #17212b;
        border-color: #334155;
        color: #f8fafc;
      }
      .button.primary {
        background: #2f9d75;
        border-color: #2f9d75;
      }
      .band {
        background: #111820;
        border-top-color: #263241;
      }
    }
  </style>
</head>
<body>
  <div class="shell">
    <header>
      <a class="brand" href="/" aria-label="WispShell home">
        <span class="mark">W</span>
        <span>WispShell</span>
      </a>
      <nav aria-label="Primary navigation">
        <a href="/privacy">Privacy</a>
        <a href="https://github.com/biplabs/wisp_shell">GitHub</a>
      </nav>
    </header>

    <main>
      <section class="hero">
        <div class="copy">
          <h1>WispShell</h1>
          <p class="lead">A focused Android remote shell for reaching your own Linux machine without exposing SSH to the public internet.</p>
          <div class="actions">
            <a class="button primary" href="https://github.com/biplabs/wisp_shell">View Source</a>
            <a class="button" href="/privacy">Privacy Policy</a>
          </div>
          <ul class="proof" aria-label="Product highlights">
            <li>Pair an Android device with a user-owned Linux daemon.</li>
            <li>Use a cloud rendezvous service for registration, pairing, and presence.</li>
            <li>Keep terminal access single-purpose and separate from public SSH exposure.</li>
          </ul>
        </div>

        <div class="device" aria-label="WispShell terminal preview">
          <div class="screen">
            <div class="screenbar">
              <span>workstation</span>
              <span class="status">online</span>
            </div>
            <pre class="terminal">$ wispshell pair
pairing code: 482-913
android: trusted

$ hostname
workstation

$ uptime
05:41 up 12 days, 4 users

$ cargo test -p wispshell-cloud
test result: ok. 11 passed

$ <span class="cursor"></span></pre>
          </div>
        </div>
      </section>

      <section class="band" aria-label="How WispShell works">
        <div class="details">
          <div>
            <h2>Android first</h2>
            <p>Designed around a phone or tablet as the terminal client.</p>
          </div>
          <div>
            <h2>User-owned host</h2>
            <p>The companion daemon runs on the Linux machine you control.</p>
          </div>
          <div>
            <h2>Minimal broker</h2>
            <p>The cloud service coordinates pairing and rendezvous, not shell contents.</p>
          </div>
        </div>
      </section>
    </main>
  </div>
</body>
</html>
"#;

const PRIVACY_POLICY_HTML: &str = r#"<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>WispShell Privacy Policy</title>
  <style>
    :root {
      color-scheme: light dark;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      line-height: 1.55;
    }
    body {
      margin: 0;
      color: #17212b;
      background: #f7f8fa;
    }
    main {
      max-width: 760px;
      margin: 0 auto;
      padding: 48px 20px 64px;
      background: #ffffff;
      min-height: 100vh;
    }
    h1, h2 {
      line-height: 1.2;
      color: #111827;
    }
    h1 {
      margin: 0 0 8px;
      font-size: 2rem;
    }
    h2 {
      margin-top: 32px;
      font-size: 1.2rem;
    }
    p, li {
      font-size: 1rem;
    }
    .updated {
      color: #52606d;
      margin-top: 0;
    }
    a {
      color: #175ddc;
    }
    @media (prefers-color-scheme: dark) {
      body {
        color: #d8dee9;
        background: #0f141a;
      }
      main {
        background: #111820;
      }
      h1, h2 {
        color: #f8fafc;
      }
      .updated {
        color: #a4afbc;
      }
      a {
        color: #8bb8ff;
      }
    }
  </style>
</head>
<body>
  <main>
    <h1>WispShell Privacy Policy</h1>
    <p class="updated">Last updated: May 30, 2026</p>

    <p>
      This Privacy Policy explains how BiP Labs handles information for WispShell,
      an Android app and companion service for connecting to your own Linux machine.
    </p>

    <h2>Information We Collect</h2>
    <p>WispShell may process the following information when you use the app and service:</p>
    <ul>
      <li>Device identifiers generated by the app or companion daemon.</li>
      <li>Device display names that you provide or configure.</li>
      <li>Public keys used to identify devices and establish trusted pairings.</li>
      <li>Pairing requests, binding records, permissions, and related timestamps.</li>
      <li>Presence and rendezvous information needed to connect your devices, such as online status and network address metadata.</li>
      <li>Basic service logs, which may include timestamps, request paths, device identifiers, IP addresses, and error information.</li>
    </ul>

    <h2>How We Use Information</h2>
    <p>We use this information to provide WispShell functionality, including device registration, pairing, connection rendezvous, abuse prevention, troubleshooting, and service reliability.</p>

    <h2>What We Do Not Collect</h2>
    <p>WispShell does not intentionally collect the contents of your terminal session, shell commands, files, photos, contacts, precise location, payment information, or advertising identifiers.</p>

    <h2>Sharing</h2>
    <p>We do not sell your personal information. We may share information only when required to operate the service, comply with law, protect users or the service, or with your direction.</p>

    <h2>Security</h2>
    <p>WispShell uses HTTPS for the cloud service and public-key based device pairing. No internet service can be guaranteed to be perfectly secure, but we design the service to limit the information needed for pairing and rendezvous.</p>

    <h2>Retention and Deletion</h2>
    <p>We keep service records only as long as needed to operate WispShell, troubleshoot issues, meet legal obligations, or maintain security. You can request deletion of WispShell service data associated with your devices by contacting us.</p>

    <h2>Children</h2>
    <p>WispShell is not directed to children under 13, and we do not knowingly collect personal information from children.</p>

    <h2>Changes</h2>
    <p>We may update this Privacy Policy from time to time. Updates will be posted on this page with a new last updated date.</p>

    <h2>Contact</h2>
    <p>For privacy questions or deletion requests, contact BiP Labs at <a href="mailto:privacy@biplabs.com">privacy@biplabs.com</a>.</p>
  </main>
</body>
</html>
"#;

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{
        body::{to_bytes, Body},
        http::{Request, StatusCode},
    };
    use chrono::{Duration, Utc};
    use serde_json::{json, Value};
    use tower::ServiceExt;
    use uuid::Uuid;
    use wispshell_protocol::{
        encode_pairing_code, pairing_code_hash, BindingCertificate, DeviceKeypair,
    };

    async fn json_request(
        app: Router,
        method: &str,
        path: &str,
        body: Value,
    ) -> (StatusCode, Value) {
        let response = app
            .oneshot(
                Request::builder()
                    .method(method)
                    .uri(path)
                    .header("content-type", "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        let status = response.status();
        let bytes = to_bytes(response.into_body(), 1024 * 1024).await.unwrap();
        let value = serde_json::from_slice(&bytes).unwrap_or_else(|_| json!(null));
        (status, value)
    }

    #[tokio::test]
    async fn healthz_works() {
        let app = router(AppState::default());
        let response = app
            .oneshot(Request::get("/healthz").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn landing_page_is_public_html() {
        let app = router(AppState::default());
        let response = app
            .oneshot(Request::get("/").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            response
                .headers()
                .get("content-type")
                .unwrap()
                .to_str()
                .unwrap(),
            "text/html; charset=utf-8"
        );
        let bytes = to_bytes(response.into_body(), 1024 * 1024).await.unwrap();
        let body = String::from_utf8(bytes.to_vec()).unwrap();
        assert!(body.contains("<h1>WispShell</h1>"));
        assert!(body.contains("Privacy Policy"));
        assert!(body.contains("https://github.com/biplabs/wisp_shell"));
    }

    #[tokio::test]
    async fn privacy_policy_is_public_html() {
        let app = router(AppState::default());
        let response = app
            .oneshot(Request::get("/privacy").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            response
                .headers()
                .get("content-type")
                .unwrap()
                .to_str()
                .unwrap(),
            "text/html; charset=utf-8"
        );
        let bytes = to_bytes(response.into_body(), 1024 * 1024).await.unwrap();
        let body = String::from_utf8(bytes.to_vec()).unwrap();
        assert!(body.contains("WispShell Privacy Policy"));
        assert!(body.contains("BiP Labs"));
        assert!(body.contains("privacy@biplabs.com"));
    }

    #[tokio::test]
    async fn register_presence_and_rendezvous_roundtrip() {
        let app = router(AppState::default());
        let (status, _) = json_request(
            app.clone(),
            "POST",
            "/v1/devices/register",
            json!({
                "device_id": "daemon-1",
                "kind": "daemon",
                "display_name": "Laptop",
                "public_key": "pub"
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        let (status, _) = json_request(
            app.clone(),
            "POST",
            "/v1/presence/update",
            json!({
                "device_id": "daemon-1",
                "status": "online",
                "iroh_node_addr_json": {"opaque": "addr"}
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        let response = app
            .clone()
            .oneshot(
                Request::get(
                    "/v1/rendezvous/daemon-1?client_device_id=android-1&binding_id=binding-1",
                )
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

        let (status, _) = json_request(
            app.clone(),
            "POST",
            "/v1/pairing/approve",
            json!({
                "binding_id": "binding-1",
                "pair_id": "pair-1",
                "daemon_device_id": "daemon-1",
                "client_device_id": "android-1",
                "permissions": ["terminal"],
                "binding_cert_json": "{}",
                "binding_signature": "sig"
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        let response = app
            .oneshot(
                Request::get(
                    "/v1/rendezvous/daemon-1?client_device_id=android-1&binding_id=binding-1",
                )
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body: Value =
            serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
                .unwrap();
        assert_eq!(body["status"], "online");
        assert_eq!(body["iroh_node_addr"]["opaque"], "addr");
    }

    #[tokio::test]
    async fn sqlite_persists_rendezvous_state_across_restart() {
        let path =
            std::env::temp_dir().join(format!("wispshell-cloud-test-{}.sqlite3", Uuid::new_v4()));
        let state = AppState::persistent(&path).unwrap();
        let app = router(state);

        let (status, _) = json_request(
            app.clone(),
            "POST",
            "/v1/devices/register",
            json!({
                "device_id": "daemon-persist",
                "kind": "daemon",
                "display_name": "Persistent Laptop",
                "public_key": "daemon-pub"
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        let (status, _) = json_request(
            app.clone(),
            "POST",
            "/v1/pairing/approve",
            json!({
                "binding_id": "binding-persist",
                "pair_id": "pair-persist",
                "daemon_device_id": "daemon-persist",
                "client_device_id": "android-persist",
                "permissions": ["terminal"],
                "binding_cert_json": "{}",
                "binding_signature": "sig"
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        let (status, _) = json_request(
            app,
            "POST",
            "/v1/presence/update",
            json!({
                "device_id": "daemon-persist",
                "status": "online",
                "iroh_node_addr_json": {"node": "addr"}
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        let restarted = router(AppState::persistent(&path).unwrap());
        let response = restarted
            .oneshot(
                Request::get(
                    "/v1/rendezvous/daemon-persist?client_device_id=android-persist&binding_id=binding-persist",
                )
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body: Value =
            serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
                .unwrap();
        assert_eq!(body["daemon_public_key"], "daemon-pub");
        assert_eq!(body["status"], "online");
        assert_eq!(body["iroh_node_addr"]["node"], "addr");

        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn android_generated_pairing_code_is_single_use() {
        let app = router(AppState::default());
        let code = encode_pairing_code([9_u8; 8]);
        let expires = Utc::now() + Duration::minutes(2);

        let (status, started) = json_request(
            app.clone(),
            "POST",
            "/v1/pairing/code/start",
            json!({
                "code_hash": pairing_code_hash(&code).unwrap(),
                "client_device_id": "android-1",
                "client_public_key": "client-pub",
                "client_name": "Tablet",
                "expires_at": expires,
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(started["client_name"], "Tablet");
        assert!(started.get("code_hash").is_some());
        assert!(started.get("code").is_none());

        let (status, resolved) = json_request(
            app.clone(),
            "POST",
            "/v1/pairing/code/resolve",
            json!({
                "code": code.to_ascii_lowercase(),
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(resolved["client_device_id"], "android-1");
        assert_eq!(resolved["client_public_key"], "client-pub");
        let daemon_keys = DeviceKeypair::generate();
        let daemon_device_id = daemon_keys.device_id().unwrap();
        let daemon_public_key = daemon_keys.public_key_b64();
        let cert = BindingCertificate {
            binding_id: "binding-1".to_string(),
            daemon_device_id: daemon_device_id.clone(),
            daemon_public_key: daemon_public_key.clone(),
            client_device_id: "android-1".to_string(),
            client_public_key: "client-pub".to_string(),
            permissions: vec!["terminal".to_string()],
            created_at: Utc::now(),
        };
        let cert_json = serde_json::to_string(&cert).unwrap();
        let signature = daemon_keys.sign_b64(cert_json.as_bytes());

        let (status, binding) = json_request(
            app.clone(),
            "POST",
            "/v1/pairing/code/approve",
            json!({
                "code": code.to_ascii_lowercase(),
                "daemon_device_id": daemon_device_id,
                "daemon_public_key": daemon_public_key,
                "daemon_name": "Laptop",
                "binding_id": "binding-1",
                "binding_cert_json": cert_json,
                "binding_signature": signature
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(binding["client_device_id"], "android-1");
        assert_eq!(binding["daemon_device_id"], cert.daemon_device_id);

        let (status, err) = json_request(
            app,
            "POST",
            "/v1/pairing/code/approve",
            json!({
                "code": code,
                "daemon_device_id": cert.daemon_device_id,
                "daemon_public_key": cert.daemon_public_key,
                "daemon_name": "Laptop",
                "binding_id": "binding-2",
                "binding_cert_json": serde_json::to_string(&BindingCertificate {
                    binding_id: "binding-2".to_string(),
                    daemon_device_id: cert.daemon_device_id,
                    daemon_public_key: cert.daemon_public_key,
                    client_device_id: "android-1".to_string(),
                    client_public_key: "client-pub".to_string(),
                    permissions: vec!["terminal".to_string()],
                    created_at: Utc::now(),
                }).unwrap(),
                "binding_signature": "sig"
            }),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT);
        assert_eq!(err["error"], "pairing_used");
    }

    #[tokio::test]
    async fn repairing_same_client_to_same_daemon_replaces_old_binding() {
        let app = router(AppState::default());
        let daemon_keys = DeviceKeypair::generate();
        let daemon_device_id = daemon_keys.device_id().unwrap();
        let daemon_public_key = daemon_keys.public_key_b64();

        for (code_bytes, binding_id) in [([11_u8; 8], "binding-old"), ([12_u8; 8], "binding-new")] {
            let code = encode_pairing_code(code_bytes);
            let (status, _) = json_request(
                app.clone(),
                "POST",
                "/v1/pairing/code/start",
                json!({
                    "code_hash": pairing_code_hash(&code).unwrap(),
                    "client_device_id": "android-1",
                    "client_public_key": "client-pub",
                    "client_name": "Tablet",
                    "expires_at": Utc::now() + Duration::minutes(2),
                }),
            )
            .await;
            assert_eq!(status, StatusCode::OK);

            let cert = BindingCertificate {
                binding_id: binding_id.to_string(),
                daemon_device_id: daemon_device_id.clone(),
                daemon_public_key: daemon_public_key.clone(),
                client_device_id: "android-1".to_string(),
                client_public_key: "client-pub".to_string(),
                permissions: vec!["terminal".to_string()],
                created_at: Utc::now(),
            };
            let cert_json = serde_json::to_string(&cert).unwrap();
            let signature = daemon_keys.sign_b64(cert_json.as_bytes());

            let (status, _) = json_request(
                app.clone(),
                "POST",
                "/v1/pairing/code/approve",
                json!({
                    "code": code,
                    "daemon_device_id": daemon_device_id,
                    "daemon_public_key": daemon_public_key,
                    "daemon_name": "Laptop",
                    "binding_id": binding_id,
                    "binding_cert_json": cert_json,
                    "binding_signature": signature
                }),
            )
            .await;
            assert_eq!(status, StatusCode::OK);
        }

        let response = app
            .oneshot(
                Request::get("/v1/bindings/for-client/android-1")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bindings: Value =
            serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
                .unwrap();
        let bindings = bindings.as_array().unwrap();
        assert_eq!(bindings.len(), 1);
        assert_eq!(bindings[0]["binding_id"], "binding-new");
    }

    #[tokio::test]
    async fn expired_pairing_code_is_rejected() {
        let app = router(AppState::default());
        let code = encode_pairing_code([10_u8; 8]);
        let (status, err) = json_request(
            app,
            "POST",
            "/v1/pairing/code/start",
            json!({
                "code_hash": pairing_code_hash(&code).unwrap(),
                "client_device_id": "android-1",
                "client_public_key": "client-pub",
                "client_name": "Tablet",
                "expires_at": Utc::now() - Duration::seconds(1),
            }),
        )
        .await;
        assert_eq!(status, StatusCode::GONE);
        assert_eq!(err["error"], "pairing_expired");
    }

    #[tokio::test]
    async fn qr_pairing_claim_and_approve_lifecycle() {
        let app = router(AppState::default());
        let expires = Utc::now() + Duration::minutes(2);

        let (status, started) = json_request(
            app.clone(),
            "POST",
            "/v1/pairing/start",
            json!({
                "pair_id": "pair-1",
                "daemon_device_id": "daemon-1",
                "daemon_public_key": "daemon-pub",
                "expires_at": expires,
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(started["used_at"], Value::Null);

        let (status, claim) = json_request(
            app.clone(),
            "POST",
            "/v1/pairing/claim",
            json!({
                "pair_id": "pair-1",
                "client_device_id": "android-1",
                "client_public_key": "client-pub",
                "client_name": "Tablet",
                "proof": "proof"
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(claim["status"], "pending");

        let (status, binding) = json_request(
            app.clone(),
            "POST",
            "/v1/pairing/approve",
            json!({
                "binding_id": "binding-1",
                "pair_id": "pair-1",
                "daemon_device_id": "daemon-1",
                "client_device_id": "android-1",
                "permissions": ["terminal"],
                "binding_cert_json": "{\"binding_id\":\"binding-1\"}",
                "binding_signature": "sig"
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(binding["binding_id"], "binding-1");

        let response = app
            .oneshot(
                Request::get("/v1/pairing/pair-1")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body: Value =
            serde_json::from_slice(&to_bytes(response.into_body(), 1024 * 1024).await.unwrap())
                .unwrap();
        assert!(body["used_at"].is_string());
    }

    #[tokio::test]
    async fn invalid_device_kind_is_rejected() {
        let app = router(AppState::default());
        let (status, body) = json_request(
            app,
            "POST",
            "/v1/devices/register",
            json!({
                "device_id": "bad-1",
                "kind": "browser",
                "display_name": "Bad",
                "public_key": "pub"
            }),
        )
        .await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert_eq!(body["error"], "invalid_request");
    }
}
