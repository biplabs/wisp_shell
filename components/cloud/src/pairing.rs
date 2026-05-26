use crate::db::{AgentEvent, AppState, Binding, CodePairingSession, Db, PairingSession};
use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::json;
use uuid::Uuid;
use wispshell_protocol::{
    pairing_code_hash, verify_b64, BindingCertificate, ERR_PAIRING_EXPIRED, ERR_PAIRING_NOT_FOUND,
    ERR_PAIRING_USED, ERR_SIGNATURE_INVALID,
};

#[derive(Debug, Deserialize)]
pub struct StartPairingRequest {
    pub pair_id: String,
    pub daemon_device_id: String,
    pub daemon_public_key: String,
    pub expires_at: chrono::DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct ClaimPairingRequest {
    pub pair_id: String,
    pub client_device_id: String,
    pub client_public_key: String,
    pub client_name: String,
    pub proof: String,
    pub client_signature: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ApprovePairingRequest {
    pub binding_id: String,
    pub pair_id: String,
    pub daemon_device_id: String,
    pub client_device_id: String,
    pub permissions: Vec<String>,
    pub binding_cert_json: String,
    pub binding_signature: String,
}

#[derive(Debug, Deserialize)]
pub struct StartCodePairingRequest {
    pub pair_id: Option<String>,
    pub code_hash: String,
    pub client_device_id: String,
    pub client_public_key: String,
    pub client_name: String,
    pub expires_at: chrono::DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct ApproveCodePairingRequest {
    pub code: String,
    pub daemon_device_id: String,
    pub daemon_public_key: String,
    pub daemon_name: String,
    pub binding_id: String,
    pub binding_cert_json: String,
    pub binding_signature: String,
}

#[derive(Debug, Deserialize)]
pub struct ResolveCodePairingRequest {
    pub code: String,
}

#[derive(Debug, Serialize)]
pub struct ResolveCodePairingResponse {
    pub pair_id: String,
    pub client_device_id: String,
    pub client_public_key: String,
    pub client_name: String,
    pub expires_at: chrono::DateTime<Utc>,
}

#[derive(Debug, Serialize)]
pub struct BoundDaemonResponse {
    pub binding_id: String,
    pub daemon_device_id: String,
    pub daemon_public_key: String,
    pub display_name: String,
    pub status: String,
}

fn revoke_existing_active_bindings(
    db: &mut Db,
    daemon_device_id: &str,
    client_device_id: &str,
    replacement_binding_id: &str,
    revoked_at: chrono::DateTime<Utc>,
) {
    for binding in db.bindings.values_mut() {
        if binding.binding_id != replacement_binding_id
            && binding.daemon_device_id == daemon_device_id
            && binding.client_device_id == client_device_id
            && binding.revoked_at.is_none()
        {
            binding.revoked_at = Some(revoked_at);
        }
    }
}

pub async fn start(
    State(state): State<AppState>,
    Json(req): Json<StartPairingRequest>,
) -> Json<PairingSession> {
    let session = PairingSession {
        pair_id: req.pair_id.clone(),
        daemon_device_id: req.daemon_device_id,
        daemon_public_key: req.daemon_public_key,
        expires_at: req.expires_at,
        used_at: None,
        created_at: Utc::now(),
    };
    state
        .inner
        .lock()
        .unwrap()
        .pairings
        .insert(req.pair_id, session.clone());
    state.persist_or_log();
    tracing::info!(pair_id = %session.pair_id, "pairing started");
    Json(session)
}

pub async fn claim(
    State(state): State<AppState>,
    Json(req): Json<ClaimPairingRequest>,
) -> Result<Json<serde_json::Value>, (StatusCode, Json<serde_json::Value>)> {
    let pairing = state
        .inner
        .lock()
        .unwrap()
        .pairings
        .get(&req.pair_id)
        .cloned()
        .ok_or((
            StatusCode::NOT_FOUND,
            Json(json!({"error": "pairing_not_found"})),
        ))?;

    if pairing.used_at.is_some() {
        return Err((StatusCode::CONFLICT, Json(json!({"error": "pairing_used"}))));
    }
    if pairing.expires_at <= Utc::now() {
        return Err((StatusCode::GONE, Json(json!({"error": "pairing_expired"}))));
    }
    let _client_signature = req.client_signature.as_deref();

    let event = AgentEvent::PairingClaimed {
        pair_id: req.pair_id.clone(),
        client_device_id: req.client_device_id,
        client_public_key: req.client_public_key,
        client_name: req.client_name,
        proof: req.proof,
    };
    let _ = state.event_sender(&pairing.daemon_device_id).send(event);
    tracing::info!(pair_id = %req.pair_id, "pairing claimed");
    Ok(Json(json!({"status": "pending"})))
}

pub async fn approve(
    State(state): State<AppState>,
    Json(req): Json<ApprovePairingRequest>,
) -> Json<Binding> {
    let now = Utc::now();
    let binding = Binding {
        binding_id: req.binding_id.clone(),
        daemon_device_id: req.daemon_device_id.clone(),
        client_device_id: req.client_device_id.clone(),
        permissions_json: serde_json::to_string(&req.permissions)
            .unwrap_or_else(|_| "[]".to_string()),
        binding_cert_json: req.binding_cert_json,
        binding_signature: req.binding_signature,
        created_at: now,
        revoked_at: None,
    };
    let mut db = state.inner.lock().unwrap();
    if let Some(pairing) = db.pairings.get_mut(&req.pair_id) {
        pairing.used_at = Some(now);
    }
    revoke_existing_active_bindings(
        &mut db,
        &req.daemon_device_id,
        &req.client_device_id,
        &req.binding_id,
        now,
    );
    db.bindings.insert(req.binding_id, binding.clone());
    drop(db);
    state.persist_or_log();
    tracing::info!(binding_id = %binding.binding_id, "pairing approved");
    Json(binding)
}

pub async fn start_code(
    State(state): State<AppState>,
    Json(req): Json<StartCodePairingRequest>,
) -> Result<Json<CodePairingSession>, (StatusCode, Json<serde_json::Value>)> {
    if req.expires_at <= Utc::now() {
        return Err((
            StatusCode::GONE,
            Json(json!({"error": ERR_PAIRING_EXPIRED})),
        ));
    }
    let session = CodePairingSession {
        pair_id: req.pair_id.unwrap_or_else(|| Uuid::new_v4().to_string()),
        code_hash: req.code_hash,
        client_device_id: req.client_device_id,
        client_public_key: req.client_public_key,
        client_name: req.client_name,
        expires_at: req.expires_at,
        used_at: None,
        created_at: Utc::now(),
    };
    state
        .inner
        .lock()
        .unwrap()
        .code_pairings
        .insert(session.code_hash.clone(), session.clone());
    state.persist_or_log();
    tracing::info!(pair_id = %session.pair_id, "pairing code started");
    Ok(Json(session))
}

pub async fn resolve_code(
    State(state): State<AppState>,
    Json(req): Json<ResolveCodePairingRequest>,
) -> Result<Json<ResolveCodePairingResponse>, (StatusCode, Json<serde_json::Value>)> {
    let code_hash = pairing_code_hash(&req.code).map_err(|_| {
        (
            StatusCode::BAD_REQUEST,
            Json(json!({"error": "invalid_request"})),
        )
    })?;
    let db = state.inner.lock().unwrap();
    let session = db.code_pairings.get(&code_hash).ok_or((
        StatusCode::NOT_FOUND,
        Json(json!({"error": ERR_PAIRING_NOT_FOUND})),
    ))?;
    if session.used_at.is_some() {
        return Err((
            StatusCode::CONFLICT,
            Json(json!({"error": ERR_PAIRING_USED})),
        ));
    }
    if session.expires_at <= Utc::now() {
        return Err((
            StatusCode::GONE,
            Json(json!({"error": ERR_PAIRING_EXPIRED})),
        ));
    }

    Ok(Json(ResolveCodePairingResponse {
        pair_id: session.pair_id.clone(),
        client_device_id: session.client_device_id.clone(),
        client_public_key: session.client_public_key.clone(),
        client_name: session.client_name.clone(),
        expires_at: session.expires_at,
    }))
}

pub async fn approve_code(
    State(state): State<AppState>,
    Json(req): Json<ApproveCodePairingRequest>,
) -> Result<Json<Binding>, (StatusCode, Json<serde_json::Value>)> {
    let code_hash = pairing_code_hash(&req.code).map_err(|_| {
        (
            StatusCode::BAD_REQUEST,
            Json(json!({"error": "invalid_request"})),
        )
    })?;
    let mut db = state.inner.lock().unwrap();
    let session = db.code_pairings.get_mut(&code_hash).ok_or((
        StatusCode::NOT_FOUND,
        Json(json!({"error": ERR_PAIRING_NOT_FOUND})),
    ))?;
    if session.used_at.is_some() {
        return Err((
            StatusCode::CONFLICT,
            Json(json!({"error": ERR_PAIRING_USED})),
        ));
    }
    if session.expires_at <= Utc::now() {
        return Err((
            StatusCode::GONE,
            Json(json!({"error": ERR_PAIRING_EXPIRED})),
        ));
    }

    let client_device_id = session.client_device_id.clone();
    let client_public_key = session.client_public_key.clone();

    let cert: BindingCertificate = serde_json::from_str(&req.binding_cert_json).map_err(|_| {
        (
            StatusCode::BAD_REQUEST,
            Json(json!({"error": "invalid_request"})),
        )
    })?;
    if cert.binding_id != req.binding_id
        || cert.daemon_device_id != req.daemon_device_id
        || cert.daemon_public_key != req.daemon_public_key
        || cert.client_device_id != client_device_id
        || cert.client_public_key != client_public_key
        || !cert
            .permissions
            .iter()
            .any(|permission| permission == "terminal")
    {
        return Err((
            StatusCode::UNAUTHORIZED,
            Json(json!({"error": ERR_SIGNATURE_INVALID})),
        ));
    }
    if !verify_b64(
        &req.daemon_public_key,
        req.binding_cert_json.as_bytes(),
        &req.binding_signature,
    )
    .unwrap_or(false)
    {
        return Err((
            StatusCode::UNAUTHORIZED,
            Json(json!({"error": ERR_SIGNATURE_INVALID})),
        ));
    }
    session.used_at = Some(Utc::now());

    let now = Utc::now();
    db.devices
        .entry(req.daemon_device_id.clone())
        .or_insert(crate::db::Device {
            device_id: req.daemon_device_id.clone(),
            kind: "daemon".to_string(),
            display_name: req.daemon_name,
            public_key: req.daemon_public_key.clone(),
            created_at: now,
            last_seen_at: Some(now),
        });

    let binding = Binding {
        binding_id: req.binding_id.clone(),
        daemon_device_id: req.daemon_device_id.clone(),
        client_device_id,
        permissions_json: json!(["terminal"]).to_string(),
        binding_cert_json: req.binding_cert_json,
        binding_signature: req.binding_signature,
        created_at: now,
        revoked_at: None,
    };
    revoke_existing_active_bindings(
        &mut db,
        &req.daemon_device_id,
        &binding.client_device_id,
        &req.binding_id,
        now,
    );
    db.bindings.insert(req.binding_id, binding.clone());
    drop(db);
    state.persist_or_log();
    tracing::info!(binding_id = %binding.binding_id, "pairing code approved");
    Ok(Json(binding))
}

pub async fn get_pairing(
    State(state): State<AppState>,
    Path(pair_id): Path<String>,
) -> Result<Json<PairingSession>, (StatusCode, Json<serde_json::Value>)> {
    state
        .inner
        .lock()
        .unwrap()
        .pairings
        .get(&pair_id)
        .cloned()
        .map(Json)
        .ok_or((
            StatusCode::NOT_FOUND,
            Json(json!({"error": "pairing_not_found"})),
        ))
}

pub async fn bindings_for_client(
    State(state): State<AppState>,
    Path(client_device_id): Path<String>,
) -> Json<Vec<BoundDaemonResponse>> {
    let db = state.inner.lock().unwrap();
    let out = db
        .bindings
        .values()
        .filter(|b| b.client_device_id == client_device_id && b.revoked_at.is_none())
        .map(|b| {
            let display_name = db
                .devices
                .get(&b.daemon_device_id)
                .map(|d| d.display_name.clone())
                .unwrap_or_else(|| b.daemon_device_id.clone());
            let daemon_public_key = db
                .devices
                .get(&b.daemon_device_id)
                .map(|d| d.public_key.clone())
                .unwrap_or_default();
            let status = db
                .presence
                .get(&b.daemon_device_id)
                .map(|p| p.status.clone())
                .unwrap_or_else(|| "offline".to_string());
            BoundDaemonResponse {
                binding_id: b.binding_id.clone(),
                daemon_device_id: b.daemon_device_id.clone(),
                daemon_public_key,
                display_name,
                status,
            }
        })
        .collect();
    Json(out)
}

pub async fn bindings_for_daemon(
    State(state): State<AppState>,
    Path(daemon_device_id): Path<String>,
) -> Json<Vec<Binding>> {
    let bindings = state
        .inner
        .lock()
        .unwrap()
        .bindings
        .values()
        .filter(|b| b.daemon_device_id == daemon_device_id)
        .cloned()
        .collect();
    Json(bindings)
}

pub async fn revoke(
    State(state): State<AppState>,
    Path(binding_id): Path<String>,
) -> Result<Json<Binding>, (StatusCode, Json<serde_json::Value>)> {
    let mut db = state.inner.lock().unwrap();
    let binding = db.bindings.get_mut(&binding_id).ok_or((
        StatusCode::NOT_FOUND,
        Json(json!({"error": "device_not_bound"})),
    ))?;
    binding.revoked_at = Some(Utc::now());
    let binding = binding.clone();
    drop(db);
    state.persist_or_log();
    Ok(Json(binding))
}
