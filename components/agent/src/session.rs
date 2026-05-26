use crate::pty;
use anyhow::bail;
use base64::Engine;
use chrono::{DateTime, Utc};
use portable_pty::{MasterPty, PtySize};
use std::collections::{HashMap, HashSet, VecDeque};
use std::io::Write;
use std::sync::{Arc, Mutex};
use tokio::sync::broadcast;
use uuid::Uuid;
use wispshell_protocol::ERR_NOT_INPUT_OWNER;

#[derive(Clone)]
pub struct SessionManager {
    inner: Arc<Mutex<HashMap<String, Arc<SessionRuntime>>>>,
    shell: String,
    scrollback_bytes: usize,
    scrollback_replay_bytes: usize,
}

pub struct SessionAttachResult {
    pub session_id: String,
    pub session_name: String,
    pub cols: u16,
    pub rows: u16,
    pub scrollback_b64: Vec<String>,
    pub output_rx: broadcast::Receiver<Vec<u8>>,
}

#[allow(dead_code)]
pub struct SessionRuntime {
    pub id: String,
    pub name: String,
    pub created_at: DateTime<Utc>,
    pub last_active_at: Mutex<DateTime<Utc>>,
    pub cols: Mutex<u16>,
    pub rows: Mutex<u16>,
    pub scrollback: Arc<Mutex<VecDeque<Vec<u8>>>>,
    pub attached_clients: Mutex<HashSet<String>>,
    pub input_owner: Mutex<Option<String>>,
    master: Mutex<Box<dyn MasterPty + Send>>,
    writer: Mutex<Box<dyn Write + Send>>,
    tx: broadcast::Sender<Vec<u8>>,
}

impl SessionManager {
    pub fn new(shell: String, scrollback_bytes: usize, scrollback_replay_bytes: usize) -> Self {
        Self {
            inner: Arc::new(Mutex::new(HashMap::new())),
            shell,
            scrollback_bytes,
            scrollback_replay_bytes,
        }
    }

    pub async fn attach_or_create(
        &self,
        client_device_id: String,
        session_name: String,
        cols: u16,
        rows: u16,
    ) -> anyhow::Result<SessionAttachResult> {
        let runtime = {
            let mut inner = self.inner.lock().unwrap();
            if let Some(session) = inner.get(&session_name) {
                session.clone()
            } else {
                let session = Arc::new(pty::spawn_session(
                    session_name.clone(),
                    self.shell.clone(),
                    cols,
                    rows,
                    self.scrollback_bytes,
                )?);
                inner.insert(session_name.clone(), session.clone());
                tracing::info!(session_name = %session_name, "session created");
                session
            }
        };

        *runtime.cols.lock().unwrap() = cols;
        *runtime.rows.lock().unwrap() = rows;
        *runtime.last_active_at.lock().unwrap() = Utc::now();
        runtime
            .attached_clients
            .lock()
            .unwrap()
            .insert(client_device_id.clone());
        *runtime.input_owner.lock().unwrap() = Some(client_device_id);
        tracing::info!(session_id = %runtime.id, "session attached");

        let scrollback_b64 = replay_scrollback(&runtime.scrollback, self.scrollback_replay_bytes)
            .into_iter()
            .map(|chunk| base64::engine::general_purpose::STANDARD.encode(chunk))
            .collect();

        Ok(SessionAttachResult {
            session_id: runtime.id.clone(),
            session_name: runtime.name.clone(),
            cols,
            rows,
            scrollback_b64,
            output_rx: runtime.tx.subscribe(),
        })
    }

    pub async fn input(
        &self,
        client_device_id: String,
        session_id: String,
        bytes: Vec<u8>,
    ) -> anyhow::Result<()> {
        let session = self.by_id(&session_id)?;
        if session.input_owner.lock().unwrap().as_deref() != Some(&client_device_id) {
            bail!(ERR_NOT_INPUT_OWNER);
        }
        session.writer.lock().unwrap().write_all(&bytes)?;
        Ok(())
    }

    pub async fn resize(&self, session_id: String, cols: u16, rows: u16) -> anyhow::Result<()> {
        let session = self.by_id(&session_id)?;
        *session.cols.lock().unwrap() = cols;
        *session.rows.lock().unwrap() = rows;
        session.master.lock().unwrap().resize(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })?;
        Ok(())
    }

    pub async fn detach(&self, client_device_id: String, session_id: String) -> anyhow::Result<()> {
        let session = self.by_id(&session_id)?;
        session
            .attached_clients
            .lock()
            .unwrap()
            .remove(&client_device_id);
        tracing::info!(session_id = %session_id, "session detached");
        Ok(())
    }

    fn by_id(&self, session_id: &str) -> anyhow::Result<Arc<SessionRuntime>> {
        self.inner
            .lock()
            .unwrap()
            .values()
            .find(|session| session.id == session_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("session_not_found"))
    }
}

pub fn new_runtime(
    name: String,
    cols: u16,
    rows: u16,
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
    scrollback: Arc<Mutex<VecDeque<Vec<u8>>>>,
    tx: broadcast::Sender<Vec<u8>>,
    _max_scrollback: usize,
) -> SessionRuntime {
    SessionRuntime {
        id: Uuid::new_v4().to_string(),
        name,
        created_at: Utc::now(),
        last_active_at: Mutex::new(Utc::now()),
        cols: Mutex::new(cols),
        rows: Mutex::new(rows),
        scrollback,
        attached_clients: Mutex::new(HashSet::new()),
        input_owner: Mutex::new(None),
        master: Mutex::new(master),
        writer: Mutex::new(writer),
        tx,
    }
}

pub fn append_scrollback(
    scrollback: &Arc<Mutex<VecDeque<Vec<u8>>>>,
    max_bytes: usize,
    bytes: Vec<u8>,
) {
    let mut ring = scrollback.lock().unwrap();
    ring.push_back(bytes);
    let mut total: usize = ring.iter().map(Vec::len).sum();
    while total > max_bytes {
        if let Some(front) = ring.pop_front() {
            total -= front.len();
        } else {
            break;
        }
    }
}

fn replay_scrollback(scrollback: &Arc<Mutex<VecDeque<Vec<u8>>>>, max_bytes: usize) -> Vec<Vec<u8>> {
    if max_bytes == 0 {
        return Vec::new();
    }

    let ring = scrollback.lock().unwrap();
    let mut selected = VecDeque::new();
    let mut total = 0;

    for chunk in ring.iter().rev() {
        if total >= max_bytes {
            break;
        }
        let remaining = max_bytes - total;
        if chunk.len() <= remaining {
            selected.push_front(chunk.clone());
            total += chunk.len();
        } else {
            selected.push_front(chunk[chunk.len() - remaining..].to_vec());
            total = max_bytes;
        }
    }

    selected.into_iter().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn append_scrollback_keeps_newest_bytes_under_limit() {
        let scrollback = Arc::new(Mutex::new(VecDeque::new()));
        append_scrollback(&scrollback, 6, b"abc".to_vec());
        append_scrollback(&scrollback, 6, b"def".to_vec());
        append_scrollback(&scrollback, 6, b"ghi".to_vec());

        let chunks: Vec<Vec<u8>> = scrollback.lock().unwrap().iter().cloned().collect();
        assert_eq!(chunks, vec![b"def".to_vec(), b"ghi".to_vec()]);
    }

    #[test]
    fn replay_scrollback_returns_newest_chunks_under_limit() {
        let scrollback = Arc::new(Mutex::new(VecDeque::new()));
        append_scrollback(&scrollback, 20, b"abc".to_vec());
        append_scrollback(&scrollback, 20, b"def".to_vec());
        append_scrollback(&scrollback, 20, b"ghi".to_vec());

        let chunks = replay_scrollback(&scrollback, 6);

        assert_eq!(chunks, vec![b"def".to_vec(), b"ghi".to_vec()]);
    }

    #[test]
    fn replay_scrollback_trims_oversized_oldest_selected_chunk() {
        let scrollback = Arc::new(Mutex::new(VecDeque::new()));
        append_scrollback(&scrollback, 20, b"abcdef".to_vec());
        append_scrollback(&scrollback, 20, b"ghi".to_vec());

        let chunks = replay_scrollback(&scrollback, 5);

        assert_eq!(chunks, vec![b"ef".to_vec(), b"ghi".to_vec()]);
    }

    #[test]
    fn replay_scrollback_can_be_disabled() {
        let scrollback = Arc::new(Mutex::new(VecDeque::new()));
        append_scrollback(&scrollback, 20, b"abc".to_vec());

        let chunks = replay_scrollback(&scrollback, 0);

        assert!(chunks.is_empty());
    }
}
