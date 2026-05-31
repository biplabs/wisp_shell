use crate::session::SessionManager;
use base64::Engine;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::sync::Mutex;
use wispshell_protocol::{
    decode_binary_header, encode_binary_frame, encode_short_binary_frame, AgentToClient,
    BinaryFrame, ClientToAgent, ShortBinaryFrame, BINARY_FRAME_HEADER_LEN, BINARY_FRAME_MAGIC,
    BINARY_KIND_AGENT_OUTPUT, BINARY_KIND_CLIENT_INPUT, MAX_BINARY_PAYLOAD_LEN,
    SHORT_BINARY_FRAME_MAGIC,
};

pub enum ClientStreamFrame {
    Json(ClientToAgent),
    Binary(BinaryFrame),
    ShortBinary(ShortBinaryFrame),
}

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
    let mut reader = BufReader::new(read);
    handle_client_frames(&mut reader, write, client_id, sessions).await
}

pub async fn handle_client_frames<R, W>(
    reader: &mut BufReader<R>,
    write: std::sync::Arc<Mutex<W>>,
    client_id: String,
    sessions: SessionManager,
) -> anyhow::Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let mut output_task: Option<tokio::task::JoinHandle<()>> = None;
    let binary_output_enabled = Arc::new(AtomicBool::new(false));
    let mut attached_session_id = None::<String>;

    while let Some(frame) = read_client_stream_frame(reader).await? {
        match frame {
            ClientStreamFrame::ShortBinary(ShortBinaryFrame { payload }) => {
                binary_output_enabled.store(true, Ordering::Relaxed);
                let Some(session_id) = attached_session_id.clone() else {
                    continue;
                };
                sessions
                    .input(client_id.clone(), session_id, payload)
                    .await?;
            }
            ClientStreamFrame::Binary(BinaryFrame {
                kind: BINARY_KIND_CLIENT_INPUT,
                session_id,
                payload,
            }) => {
                binary_output_enabled.store(true, Ordering::Relaxed);
                sessions
                    .input(client_id.clone(), session_id, payload)
                    .await?;
            }
            ClientStreamFrame::Binary(_) => {}
            ClientStreamFrame::Json(msg) => match msg {
                ClientToAgent::Attach {
                    session_name,
                    cols,
                    rows,
                } => {
                    let attached = sessions
                        .attach_or_create(client_id.clone(), session_name, cols, rows)
                        .await?;
                    attached_session_id = Some(attached.session_id.clone());
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
                    let binary_output_enabled = binary_output_enabled.clone();
                    output_task = Some(tokio::spawn(async move {
                        while let Ok(bytes) = rx.recv().await {
                            let write_result = if binary_output_enabled.load(Ordering::Relaxed) {
                                match write_short_binary_frame(&write_clone, &bytes).await {
                                    Ok(()) => Ok(()),
                                    Err(_) => {
                                        write_binary_frame(
                                            &write_clone,
                                            BINARY_KIND_AGENT_OUTPUT,
                                            &session_id,
                                            &bytes,
                                        )
                                        .await
                                    }
                                }
                            } else {
                                let frame = AgentToClient::Output {
                                    session_id: session_id.clone(),
                                    data_b64: base64::engine::general_purpose::STANDARD
                                        .encode(bytes),
                                };
                                write_json(&write_clone, &frame).await
                            };
                            if write_result.is_err() {
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
            },
        };
    }

    if let Some(task) = output_task {
        task.abort();
    }
    Ok(())
}

pub async fn read_client_stream_frame<R>(
    reader: &mut BufReader<R>,
) -> anyhow::Result<Option<ClientStreamFrame>>
where
    R: AsyncRead + Unpin,
{
    let mut first = [0u8; 1];
    let read = reader.read(&mut first).await?;
    if read == 0 {
        return Ok(None);
    }
    if first[0] == BINARY_FRAME_MAGIC {
        let mut header = [0u8; BINARY_FRAME_HEADER_LEN];
        header[0] = first[0];
        reader.read_exact(&mut header[1..]).await?;
        let (kind, session_id_len, payload_len) = decode_binary_header(header)?;
        let mut session_id = vec![0u8; session_id_len];
        reader.read_exact(&mut session_id).await?;
        let session_id = String::from_utf8(session_id)?;
        let mut payload = vec![0u8; payload_len];
        reader.read_exact(&mut payload).await?;
        return Ok(Some(ClientStreamFrame::Binary(BinaryFrame {
            kind,
            session_id,
            payload,
        })));
    }
    if first[0] == SHORT_BINARY_FRAME_MAGIC {
        let payload_len = read_short_binary_payload_len(reader).await?;
        let mut payload = vec![0u8; payload_len];
        reader.read_exact(&mut payload).await?;
        return Ok(Some(ClientStreamFrame::ShortBinary(ShortBinaryFrame {
            payload,
        })));
    }

    let mut line = vec![first[0]];
    reader.read_until(b'\n', &mut line).await?;
    let frame = serde_json::from_slice::<ClientToAgent>(trim_line_end(&line))?;
    Ok(Some(ClientStreamFrame::Json(frame)))
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

pub async fn write_binary_frame<W>(
    write: &std::sync::Arc<Mutex<W>>,
    kind: u8,
    session_id: &str,
    payload: &[u8],
) -> anyhow::Result<()>
where
    W: AsyncWrite + Unpin + Send + 'static,
{
    let frame = encode_binary_frame(kind, session_id, payload)?;
    let mut write = write.lock().await;
    write.write_all(&frame).await?;
    write.flush().await?;
    Ok(())
}

pub async fn write_short_binary_frame<W>(
    write: &std::sync::Arc<Mutex<W>>,
    payload: &[u8],
) -> anyhow::Result<()>
where
    W: AsyncWrite + Unpin + Send + 'static,
{
    let frame = encode_short_binary_frame(payload)?;
    let mut write = write.lock().await;
    write.write_all(&frame).await?;
    write.flush().await?;
    Ok(())
}

async fn read_short_binary_payload_len<R>(reader: &mut BufReader<R>) -> anyhow::Result<usize>
where
    R: AsyncRead + Unpin,
{
    let mut value = 0usize;
    for index in 0..4 {
        let byte = reader.read_u8().await?;
        value |= ((byte & 0x7f) as usize) << (index * 7);
        if byte & 0x80 == 0 {
            if value > MAX_BINARY_PAYLOAD_LEN {
                anyhow::bail!("short binary frame payload too large");
            }
            return Ok(value);
        }
    }
    anyhow::bail!("short binary frame length is too large")
}

fn trim_line_end(line: &[u8]) -> &[u8] {
    line.strip_suffix(b"\n")
        .and_then(|line| line.strip_suffix(b"\r").or(Some(line)))
        .unwrap_or(line)
}
