use crate::session::{append_scrollback, new_runtime, SessionRuntime};
use portable_pty::{native_pty_system, CommandBuilder, PtySize};
use std::collections::VecDeque;
use std::io::Read;
use std::sync::{Arc, Mutex};
use tokio::sync::broadcast;

pub fn spawn_session(
    name: String,
    shell: String,
    cols: u16,
    rows: u16,
    max_scrollback: usize,
) -> anyhow::Result<SessionRuntime> {
    let pty_system = native_pty_system();
    let pair = pty_system.openpty(PtySize {
        rows,
        cols,
        pixel_width: 0,
        pixel_height: 0,
    })?;
    let cmd = CommandBuilder::new(shell);
    let mut child = pair.slave.spawn_command(cmd)?;
    drop(pair.slave);

    let mut reader = pair.master.try_clone_reader()?;
    let writer = pair.master.take_writer()?;
    let scrollback = Arc::new(Mutex::new(VecDeque::new()));
    let (tx, _) = broadcast::channel(256);
    let reader_scrollback = scrollback.clone();
    let reader_tx = tx.clone();

    std::thread::spawn(move || {
        let mut buf = [0_u8; 8192];
        while let Ok(n) = reader.read(&mut buf) {
            if n == 0 {
                break;
            }
            let bytes = buf[..n].to_vec();
            append_scrollback(&reader_scrollback, max_scrollback, bytes.clone());
            let _ = reader_tx.send(bytes);
        }
    });

    std::thread::spawn(move || {
        let _ = child.wait();
        tracing::info!("session exited");
    });

    Ok(new_runtime(
        name,
        cols,
        rows,
        pair.master,
        writer,
        scrollback,
        tx,
        max_scrollback,
    ))
}
