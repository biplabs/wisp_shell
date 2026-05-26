use crate::session::SessionManager;
use base64::Engine;
use tokio::io::Lines;
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::sync::Mutex;
use wispshell_protocol::{AgentToClient, ClientToAgent};

pub async fn handle_json_stream<R, W>(
    read: R,
    write: W,
    client_id: String,
    sessions: SessionManager,
) -> anyhow::Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let write = std::sync::Arc::new(Mutex::new(write));
    let mut lines = BufReader::new(read).lines();
    handle_json_lines(&mut lines, write, client_id, sessions).await
}

pub async fn handle_json_lines<R, W>(
    lines: &mut Lines<BufReader<R>>,
    write: std::sync::Arc<Mutex<W>>,
    client_id: String,
    sessions: SessionManager,
) -> anyhow::Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let mut output_task: Option<tokio::task::JoinHandle<()>> = None;

    while let Some(line) = lines.next_line().await? {
        let msg: ClientToAgent = serde_json::from_str(&line)?;
        match msg {
            ClientToAgent::Attach {
                session_name,
                cols,
                rows,
            } => {
                let attached = sessions
                    .attach_or_create(client_id.clone(), session_name, cols, rows)
                    .await?;
                write_json(
                    &write,
                    &AgentToClient::SessionAttached {
                        session_id: attached.session_id.clone(),
                        session_name: attached.session_name,
                        cols: attached.cols,
                        rows: attached.rows,
                    },
                )
                .await?;
                write_json(
                    &write,
                    &AgentToClient::Scrollback {
                        session_id: attached.session_id.clone(),
                        chunks_b64: attached.scrollback_b64,
                    },
                )
                .await?;

                let mut rx = attached.output_rx;
                let write_clone = write.clone();
                let session_id = attached.session_id;
                output_task = Some(tokio::spawn(async move {
                    while let Ok(bytes) = rx.recv().await {
                        let frame = AgentToClient::Output {
                            session_id: session_id.clone(),
                            data_b64: base64::engine::general_purpose::STANDARD.encode(bytes),
                        };
                        if write_json(&write_clone, &frame).await.is_err() {
                            break;
                        }
                    }
                }));
            }
            ClientToAgent::Input {
                session_id,
                data_b64,
            } => {
                let bytes = base64::engine::general_purpose::STANDARD.decode(data_b64)?;
                sessions.input(client_id.clone(), session_id, bytes).await?;
            }
            ClientToAgent::Resize {
                session_id,
                cols,
                rows,
            } => {
                sessions.resize(session_id, cols, rows).await?;
            }
            ClientToAgent::Detach { session_id } => {
                sessions.detach(client_id.clone(), session_id).await?;
            }
            ClientToAgent::Ping { nonce } => {
                write_json(&write, &AgentToClient::Pong { nonce }).await?;
            }
            ClientToAgent::ClientHello { .. } | ClientToAgent::ClientAuth { .. } => {}
        }
    }

    if let Some(task) = output_task {
        task.abort();
    }
    Ok(())
}

pub async fn write_json<W>(
    write: &std::sync::Arc<Mutex<W>>,
    frame: &AgentToClient,
) -> anyhow::Result<()>
where
    W: AsyncWrite + Unpin + Send + 'static,
{
    let json = serde_json::to_vec(frame)?;
    let mut write = write.lock().await;
    write.write_all(&json).await?;
    write.write_all(b"\n").await?;
    write.flush().await?;
    Ok(())
}
