mod auth_device;
mod db;
mod devices;
mod pairing;
mod presence;
mod rendezvous;
mod routes;
mod ws;

use routes::router;
use std::net::SocketAddr;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "wispshell=info,tower_http=info".into()),
        )
        .init();

    let bind =
        std::env::var("WISPSHELL_CLOUD_BIND").unwrap_or_else(|_| "127.0.0.1:8080".to_string());
    let addr: SocketAddr = bind.parse()?;
    let sqlite_path = db::default_sqlite_path();
    let state = db::AppState::persistent(&sqlite_path)?;
    tracing::info!(path = %sqlite_path.display(), "cloud sqlite persistence enabled");
    let listener = tokio::net::TcpListener::bind(addr).await?;
    tracing::info!(%addr, "cloud register listening");
    axum::serve(listener, router(state)).await?;
    Ok(())
}
