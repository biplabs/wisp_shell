use crate::db::AppState;
use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use serde::Deserialize;
use serde_json::json;
use wispshell_protocol::{ERR_DEVICE_NOT_BOUND, ERR_DEVICE_NOT_FOUND, ERR_DEVICE_REVOKED};

#[derive(Debug, Deserialize)]
pub struct RendezvousQuery {
    pub client_device_id: String,
    pub binding_id: String,
}

pub async fn get(
    State(state): State<AppState>,
    Path(daemon_device_id): Path<String>,
    Query(query): Query<RendezvousQuery>,
) -> Result<Json<serde_json::Value>, (StatusCode, Json<serde_json::Value>)> {
    let db = state.inner.lock().unwrap();
    let device = db.devices.get(&daemon_device_id).ok_or((
        StatusCode::NOT_FOUND,
        Json(json!({"error": ERR_DEVICE_NOT_FOUND})),
    ))?;
    let binding = db.bindings.get(&query.binding_id).ok_or((
        StatusCode::UNAUTHORIZED,
        Json(json!({"error": ERR_DEVICE_NOT_BOUND})),
    ))?;
    if binding.daemon_device_id != daemon_device_id
        || binding.client_device_id != query.client_device_id
    {
        return Err((
            StatusCode::UNAUTHORIZED,
            Json(json!({"error": ERR_DEVICE_NOT_BOUND})),
        ));
    }
    if binding.revoked_at.is_some() {
        return Err((
            StatusCode::FORBIDDEN,
            Json(json!({"error": ERR_DEVICE_REVOKED})),
        ));
    }
    let presence = db.presence.get(&daemon_device_id);
    tracing::info!(daemon_device_id = %daemon_device_id, "rendezvous requested");
    Ok(Json(json!({
        "daemon_device_id": daemon_device_id,
        "daemon_public_key": device.public_key,
        "status": presence.map(|p| p.status.clone()).unwrap_or_else(|| "offline".to_string()),
        "iroh_node_addr": presence.and_then(|p| p.iroh_node_addr_json.clone()),
        "updated_at": presence.map(|p| p.updated_at),
    })))
}
