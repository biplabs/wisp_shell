use crate::db::{AppState, Device};
use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::json;

#[derive(Debug, Deserialize)]
pub struct RegisterDeviceRequest {
    pub device_id: String,
    pub kind: String,
    pub display_name: String,
    pub public_key: String,
}

#[derive(Debug, Serialize)]
pub struct RegisterDeviceResponse {
    pub device: Device,
}

pub async fn register(
    State(state): State<AppState>,
    Json(req): Json<RegisterDeviceRequest>,
) -> Result<Json<RegisterDeviceResponse>, (StatusCode, Json<serde_json::Value>)> {
    if !matches!(req.kind.as_str(), "daemon" | "android") {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(json!({"error": "invalid_request"})),
        ));
    }
    let device = Device {
        device_id: req.device_id.clone(),
        kind: req.kind,
        display_name: req.display_name,
        public_key: req.public_key,
        created_at: Utc::now(),
        last_seen_at: Some(Utc::now()),
    };
    state
        .inner
        .lock()
        .unwrap()
        .devices
        .insert(req.device_id, device.clone());
    state.persist_or_log();
    tracing::info!(device_id = %device.device_id, "device registered");
    Ok(Json(RegisterDeviceResponse { device }))
}

pub async fn get_device(
    State(state): State<AppState>,
    Path(device_id): Path<String>,
) -> Result<Json<Device>, (StatusCode, Json<serde_json::Value>)> {
    state
        .inner
        .lock()
        .unwrap()
        .devices
        .get(&device_id)
        .cloned()
        .map(Json)
        .ok_or((
            StatusCode::NOT_FOUND,
            Json(json!({"error": "device_not_found"})),
        ))
}
