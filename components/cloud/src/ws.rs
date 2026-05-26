use crate::db::AppState;
use axum::{
    extract::{
        ws::{Message, WebSocket},
        Query, State, WebSocketUpgrade,
    },
    response::IntoResponse,
};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
pub struct AgentEventsQuery {
    pub device_id: String,
}

pub async fn agent_events(
    State(state): State<AppState>,
    Query(query): Query<AgentEventsQuery>,
    ws: WebSocketUpgrade,
) -> impl IntoResponse {
    let rx = state.event_sender(&query.device_id).subscribe();
    ws.on_upgrade(move |socket| handle(socket, rx))
}

async fn handle(
    mut socket: WebSocket,
    mut rx: tokio::sync::broadcast::Receiver<crate::db::AgentEvent>,
) {
    while let Ok(event) = rx.recv().await {
        if let Ok(json) = serde_json::to_string(&event) {
            if socket.send(Message::Text(json.into())).await.is_err() {
                break;
            }
        }
    }
}
