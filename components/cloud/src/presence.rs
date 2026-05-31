use crate::db::{AppState, Presence};
use axum::{extract::State, Json};
use chrono::Utc;
use serde::Deserialize;

#[derive(Debug, Deserialize)]
pub struct PresenceUpdateRequest {
    pub device_id: String,
    pub status: String,
    pub iroh_node_addr_json: Option<serde_json::Value>,
    pub agent_version: Option<String>,
}

pub async fn update(
    State(state): State<AppState>,
    Json(req): Json<PresenceUpdateRequest>,
) -> Json<Presence> {
    let presence = Presence {
        device_id: req.device_id.clone(),
        status: req.status,
        iroh_node_addr_json: req.iroh_node_addr_json,
        agent_version: req.agent_version,
        updated_at: Utc::now(),
    };
    state
        .inner
        .lock()
        .unwrap()
        .presence
        .insert(req.device_id, presence.clone());
    state.persist_or_log();
    tracing::info!(device_id = %presence.device_id, "presence updated");
    Json(presence)
}
