mod cloud_client;
mod config;
mod device;
mod pairing;
mod pty;
mod session;
mod state;
mod systemd;
mod transport_iroh;
mod transport_local;
mod transport_stream;

use anyhow::Context;
use config::AgentConfig;
use session::SessionManager;
use state::AgentState;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| "wispshell=info".into()),
        )
        .init();

    let mut args = std::env::args().skip(1).collect::<Vec<_>>();
    match args.first().map(String::as_str) {
        Some("run") => {
            args.remove(0);
            run(args).await
        }
        Some("pair") if args.get(1).is_some() => pairing::approve_pairing_code(&args[1]).await,
        Some("pair") => pairing::print_pairing().await,
        Some("status") => status(),
        Some("devices") if args.get(1).map(String::as_str) == Some("list") => devices_list(),
        Some("devices") if args.get(1).map(String::as_str) == Some("revoke") => {
            let client_id = args
                .get(2)
                .context("usage: wispshelld devices revoke <client_device_id>")?;
            revoke(client_id)
        }
        Some("install-user-service") => systemd::install_user_service(),
        Some("uninstall-user-service") => systemd::uninstall_user_service(),
        _ => {
            eprintln!("usage: wispshelld <run|pair [android_code]|status|devices list|devices revoke|install-user-service|uninstall-user-service>");
            Ok(())
        }
    }
}

async fn run(args: Vec<String>) -> anyhow::Result<()> {
    let bind = args
        .windows(2)
        .find(|w| w[0] == "--local-tcp")
        .map(|w| w[1].clone())
        .unwrap_or_else(|| "127.0.0.1:7777".to_string());
    let config = AgentConfig::load_or_create()?;
    let state = AgentState::load_or_create()?;
    tracing::info!(device_id = %state.device_id, "daemon started");
    let sessions = SessionManager::new(config.shell_command(), config.scrollback_bytes);
    let local_sessions = sessions.clone();
    let iroh_config = config.clone();
    let iroh_state = state.clone();
    tokio::select! {
        result = transport_local::serve(&bind, local_sessions) => result,
        result = transport_iroh::serve(iroh_config, iroh_state, sessions) => result,
    }
}

fn status() -> anyhow::Result<()> {
    let state = AgentState::load_or_create()?;
    println!("device_id: {}", state.device_id);
    println!("state_path: {}", AgentState::path()?.display());
    println!("config_path: {}", AgentConfig::path()?.display());
    Ok(())
}

fn devices_list() -> anyhow::Result<()> {
    let state = AgentState::load_or_create()?;
    if state.trusted_clients.is_empty() {
        println!("No trusted Android clients.");
    } else {
        for client in state.trusted_clients {
            println!("{}\t{}", client.client_device_id, client.client_name);
        }
    }
    Ok(())
}

fn revoke(client_id: &str) -> anyhow::Result<()> {
    let mut state = AgentState::load_or_create()?;
    state
        .trusted_clients
        .retain(|c| c.client_device_id != client_id);
    state.save()?;
    println!("revoked {client_id}");
    Ok(())
}
