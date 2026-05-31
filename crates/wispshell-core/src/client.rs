use serde::{Deserialize, Serialize};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use wispshell_protocol::{
    random_b64, transport_handshake_payload, verify_b64, AgentToClient, ClientToAgent,
    DeviceKeypair, PROTOCOL_VERSION, WISP_ALPN,
};

const DEFAULT_RELAY_URL: &str = "https://wisp-relay.biplabs.com";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct BoundDaemon {
    pub binding_id: String,
    pub daemon_device_id: String,
    pub daemon_public_key: String,
    pub display_name: String,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RendezvousInfo {
    pub daemon_device_id: String,
    pub daemon_public_key: String,
    pub status: String,
    pub iroh_node_addr: Option<serde_json::Value>,
    pub updated_at: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Clone)]
pub struct WispClient {
    cloud_url: String,
    http: reqwest::Client,
}

impl WispClient {
    pub fn new(cloud_url: impl Into<String>) -> Self {
        Self {
            cloud_url: cloud_url.into(),
            http: reqwest::Client::new(),
        }
    }

    pub async fn bound_daemons(&self, client_device_id: &str) -> anyhow::Result<Vec<BoundDaemon>> {
        let url = format!(
            "{}/v1/bindings/for-client/{}",
            self.cloud_url.trim_end_matches('/'),
            client_device_id
        );
        Ok(self
            .http
            .get(url)
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn rendezvous(
        &self,
        daemon_device_id: &str,
        client_device_id: &str,
        binding_id: &str,
    ) -> anyhow::Result<RendezvousInfo> {
        let url = format!(
            "{}/v1/rendezvous/{}?client_device_id={}&binding_id={}",
            self.cloud_url.trim_end_matches('/'),
            daemon_device_id,
            urlencoding::encode(client_device_id),
            urlencoding::encode(binding_id),
        );
        Ok(self
            .http
            .get(url)
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }
}

#[derive(Debug)]
pub struct P2PTerminalClient {
    _endpoint: iroh::Endpoint,
    connection_info: iroh::endpoint::ConnectionInfo,
    reader: BufReader<iroh::endpoint::RecvStream>,
    writer: iroh::endpoint::SendStream,
}

impl P2PTerminalClient {
    #[allow(clippy::too_many_arguments)]
    pub async fn connect(
        rendezvous: RendezvousInfo,
        client_keys: &DeviceKeypair,
        client_device_id: String,
        binding_id: String,
    ) -> anyhow::Result<Self> {
        let node_addr_json = rendezvous
            .iroh_node_addr
            .ok_or_else(|| anyhow::anyhow!("rendezvous has no iroh node address"))?;
        let node_addr: iroh::EndpointAddr = serde_json::from_value(node_addr_json)?;
        let relay_url: iroh::RelayUrl = DEFAULT_RELAY_URL.parse()?;
        let endpoint = iroh::Endpoint::builder(iroh::endpoint::presets::N0)
            .relay_mode(iroh::RelayMode::custom([relay_url]))
            .bind()
            .await?;
        let connection = endpoint.connect(node_addr, WISP_ALPN).await?;
        let connection_info = connection.to_info();
        let (mut writer, reader) = connection.open_bi().await?;
        let mut reader = BufReader::new(reader);

        let client_nonce = random_b64(32);
        let hello = ClientToAgent::ClientHello {
            protocol_version: PROTOCOL_VERSION,
            client_device_id: client_device_id.clone(),
            client_public_key: client_keys.public_key_b64(),
            daemon_device_id: rendezvous.daemon_device_id.clone(),
            binding_id: binding_id.clone(),
            nonce: client_nonce.clone(),
        };
        write_client_frame(&mut writer, &hello).await?;

        let challenge = read_agent_frame(&mut reader).await?;
        let AgentToClient::AgentChallenge {
            daemon_device_id,
            daemon_public_key,
            client_nonce: echoed_client_nonce,
            daemon_nonce,
        } = challenge
        else {
            anyhow::bail!("expected agent_challenge");
        };
        if daemon_device_id != rendezvous.daemon_device_id
            || daemon_public_key != rendezvous.daemon_public_key
            || echoed_client_nonce != client_nonce
        {
            anyhow::bail!("agent challenge mismatch");
        }

        let transcript = transport_handshake_payload(
            PROTOCOL_VERSION,
            &client_device_id,
            &client_keys.public_key_b64(),
            &daemon_device_id,
            &daemon_public_key,
            &binding_id,
            &client_nonce,
            &daemon_nonce,
        );
        write_client_frame(
            &mut writer,
            &ClientToAgent::ClientAuth {
                signature: client_keys.sign_b64(transcript.as_bytes()),
            },
        )
        .await?;

        let auth_ok = read_agent_frame(&mut reader).await?;
        let AgentToClient::AgentAuthOk { signature } = auth_ok else {
            anyhow::bail!("expected agent_auth_ok");
        };
        if !verify_b64(&daemon_public_key, transcript.as_bytes(), &signature).unwrap_or(false) {
            anyhow::bail!("daemon handshake signature invalid");
        }

        Ok(Self {
            _endpoint: endpoint,
            connection_info,
            reader,
            writer,
        })
    }

    pub async fn send(&mut self, frame: &ClientToAgent) -> anyhow::Result<()> {
        write_client_frame(&mut self.writer, frame).await
    }

    pub async fn read(&mut self) -> anyhow::Result<Option<AgentToClient>> {
        let mut line = String::new();
        let read = self.reader.read_line(&mut line).await?;
        if read == 0 {
            return Ok(None);
        }
        Ok(Some(serde_json::from_str(line.trim_end())?))
    }

    pub fn into_parts(
        self,
    ) -> (
        iroh::Endpoint,
        iroh::endpoint::ConnectionInfo,
        BufReader<iroh::endpoint::RecvStream>,
        iroh::endpoint::SendStream,
    ) {
        (
            self._endpoint,
            self.connection_info,
            self.reader,
            self.writer,
        )
    }
}

async fn write_client_frame(
    writer: &mut iroh::endpoint::SendStream,
    frame: &ClientToAgent,
) -> anyhow::Result<()> {
    writer
        .write_all(serde_json::to_string(frame)?.as_bytes())
        .await?;
    writer.write_all(b"\n").await?;
    writer.flush().await?;
    Ok(())
}

async fn read_agent_frame(
    reader: &mut BufReader<iroh::endpoint::RecvStream>,
) -> anyhow::Result<AgentToClient> {
    let mut line = String::new();
    let read = reader.read_line(&mut line).await?;
    if read == 0 {
        anyhow::bail!("connection closed");
    }
    Ok(serde_json::from_str(line.trim_end())?)
}
