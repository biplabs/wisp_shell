use crate::{
    cloud_client::{CloudClient, PresenceUpdateRequest, RegisterDeviceRequest},
    config::AgentConfig,
    session::SessionManager,
    state::AgentState,
    transport_stream::{
        handle_client_frames, read_client_stream_frame, write_json, ClientStreamFrame,
    },
};
use anyhow::{bail, Context};
use iroh::{
    endpoint::{presets, BindOpts},
    Endpoint, RelayMode, RelayUrl,
};
use std::net::SocketAddr;
use tokio::io::BufReader;
use tokio::sync::Mutex;
use tokio::time::{self, Duration};
use wispshell_protocol::{
    random_b64, transport_handshake_payload, verify_b64, AgentToClient, ClientToAgent,
    DeviceKeypair, PROTOCOL_VERSION, WISP_ALPN,
};

const PRESENCE_REFRESH_INTERVAL: Duration = Duration::from_secs(30);
const AGENT_VERSION: &str = env!("CARGO_PKG_VERSION");

pub async fn serve(
    config: AgentConfig,
    state: AgentState,
    sessions: SessionManager,
) -> anyhow::Result<()> {
    let mut builder = Endpoint::builder(presets::N0).alpns(vec![WISP_ALPN.to_vec()]);
    if !config.relay_url.trim().is_empty() {
        let relay_url: RelayUrl = config
            .relay_url
            .parse()
            .with_context(|| format!("invalid relay_url: {}", config.relay_url))?;
        builder = builder.relay_mode(RelayMode::custom([relay_url.clone()]));
        tracing::info!(relay_url = %relay_url, "using custom iroh relay");
    }
    if !config.iroh_bind_addr.trim().is_empty() {
        let bind_addr: SocketAddr = config
            .iroh_bind_addr
            .parse()
            .with_context(|| format!("invalid iroh_bind_addr: {}", config.iroh_bind_addr))?;
        builder = builder.clear_ip_transports().bind_addr_with_opts(
            bind_addr,
            BindOpts::default()
                .set_prefix_len(config.iroh_bind_prefix_len)
                .set_is_default_route(false),
        )?;
        tracing::info!(
            bind_addr = %bind_addr,
            prefix_len = config.iroh_bind_prefix_len,
            "binding iroh ip transport"
        );
    }
    let endpoint = builder.bind().await?;
    spawn_presence_refresh(config.clone(), state.clone(), endpoint.clone());

    tracing::info!(
        node_id = %endpoint.id(),
        alpn = %String::from_utf8_lossy(WISP_ALPN),
        "iroh transport listening"
    );

    loop {
        let Some(connecting) = endpoint.accept().await else {
            bail!("iroh endpoint closed");
        };
        let state = state.clone();
        let sessions = sessions.clone();
        tokio::spawn(async move {
            match connecting.await {
                Ok(connection) => {
                    tracing::info!(remote = %connection.remote_id(), "iroh client connected");
                    match connection.accept_bi().await {
                        Ok((send, recv)) => {
                            if let Err(error) =
                                handle_authenticated_stream(recv, send, state, sessions).await
                            {
                                tracing::warn!(?error, "iroh stream closed with error");
                            }
                        }
                        Err(error) => tracing::warn!(?error, "iroh failed to accept stream"),
                    }
                }
                Err(error) => tracing::warn!(?error, "iroh connection failed"),
            }
        });
    }
}

fn spawn_presence_refresh(config: AgentConfig, state: AgentState, endpoint: Endpoint) {
    tokio::spawn(async move {
        loop {
            if let Err(error) = publish_presence(&config, &state, &endpoint).await {
                tracing::warn!(?error, "could not publish iroh presence; will retry");
            }
            time::sleep(PRESENCE_REFRESH_INTERVAL).await;
        }
    });
}

async fn publish_presence(
    config: &AgentConfig,
    state: &AgentState,
    endpoint: &Endpoint,
) -> anyhow::Result<()> {
    let cloud = CloudClient::new(config.cloud_url.clone());
    cloud
        .register_device(&RegisterDeviceRequest {
            device_id: state.device_id.clone(),
            kind: "daemon".to_string(),
            display_name: config.device_name.clone(),
            public_key: state.public_key.clone(),
        })
        .await?;
    cloud
        .update_presence(&PresenceUpdateRequest {
            device_id: state.device_id.clone(),
            status: "online".to_string(),
            iroh_node_addr_json: Some(serde_json::to_value(endpoint.addr())?),
            agent_version: Some(AGENT_VERSION.to_string()),
        })
        .await?;
    tracing::info!("published iroh presence");
    Ok(())
}

async fn handle_authenticated_stream(
    recv: iroh::endpoint::RecvStream,
    send: iroh::endpoint::SendStream,
    mut state: AgentState,
    sessions: SessionManager,
) -> anyhow::Result<()> {
    let write = std::sync::Arc::new(Mutex::new(send));
    let mut reader = BufReader::new(recv);
    let hello = match read_client_stream_frame(&mut reader)
        .await?
        .context("client disconnected before hello")?
    {
        ClientStreamFrame::Json(frame) => frame,
        ClientStreamFrame::Binary(_) | ClientStreamFrame::ShortBinary(_) => {
            bail!("first frame must be client_hello")
        }
    };
    let ClientToAgent::ClientHello {
        protocol_version,
        client_device_id,
        client_public_key,
        daemon_device_id,
        binding_id,
        nonce: client_nonce,
    } = hello
    else {
        bail!("first frame must be client_hello");
    };

    if protocol_version != PROTOCOL_VERSION || daemon_device_id != state.device_id {
        bail!("unsupported protocol or daemon mismatch");
    }

    if let Ok(latest_state) = AgentState::load_or_create() {
        if latest_state.device_id == state.device_id && latest_state.public_key == state.public_key
        {
            state = latest_state;
        }
    }

    let trusted = state.trusted_clients.iter().any(|client| {
        client.client_device_id == client_device_id && client.client_public_key == client_public_key
    });
    if !trusted {
        tracing::warn!(
            client_device_id = %client_device_id,
            client_public_key = %client_public_key,
            trusted_count = state.trusted_clients.len(),
            "rejecting untrusted client"
        );
        bail!("client is not trusted");
    }

    let daemon_nonce = random_b64(32);
    write_json(
        &write,
        &AgentToClient::AgentChallenge {
            daemon_device_id: state.device_id.clone(),
            daemon_public_key: state.public_key.clone(),
            client_nonce: client_nonce.clone(),
            daemon_nonce: daemon_nonce.clone(),
        },
    )
    .await?;

    let auth = match read_client_stream_frame(&mut reader)
        .await?
        .context("client disconnected before auth")?
    {
        ClientStreamFrame::Json(frame) => frame,
        ClientStreamFrame::Binary(_) | ClientStreamFrame::ShortBinary(_) => {
            bail!("second frame must be client_auth")
        }
    };
    let ClientToAgent::ClientAuth { signature } = auth else {
        bail!("second frame must be client_auth");
    };

    let transcript = transport_handshake_payload(
        protocol_version,
        &client_device_id,
        &client_public_key,
        &state.device_id,
        &state.public_key,
        &binding_id,
        &client_nonce,
        &daemon_nonce,
    );
    if !verify_b64(&client_public_key, transcript.as_bytes(), &signature).unwrap_or(false) {
        bail!("client handshake signature invalid");
    }

    let daemon_keys = DeviceKeypair::from_private_key_b64(&state.private_key)?;
    write_json(
        &write,
        &AgentToClient::AgentAuthOk {
            signature: daemon_keys.sign_b64(transcript.as_bytes()),
        },
    )
    .await?;

    handle_client_frames(&mut reader, write, client_device_id, sessions).await
}
