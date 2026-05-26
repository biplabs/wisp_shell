use crate::session::SessionManager;
use crate::transport_stream::handle_json_stream;
use tokio::net::{TcpListener, TcpStream};

pub async fn serve(bind: &str, sessions: SessionManager) -> anyhow::Result<()> {
    let listener = TcpListener::bind(bind).await?;
    tracing::info!(bind = %bind, "local tcp transport listening");
    loop {
        let (stream, addr) = listener.accept().await?;
        tracing::info!(%addr, "client connected");
        tokio::spawn(handle(stream, sessions.clone()));
    }
}

async fn handle(stream: TcpStream, sessions: SessionManager) -> anyhow::Result<()> {
    let client_id = format!("local-{}", uuid::Uuid::new_v4());
    let (read, write) = stream.into_split();
    handle_json_stream(read, write, client_id, sessions).await?;
    tracing::info!("client disconnected");
    Ok(())
}
