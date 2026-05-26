use crate::{db::AppState, devices, pairing, presence, rendezvous, ws};
use axum::{
    routing::{get, post},
    Json, Router,
};
use serde_json::json;
use tower_http::trace::TraceLayer;

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(|| async { Json(json!({"ok": true})) }))
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
