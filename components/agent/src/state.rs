use directories::ProjectDirs;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use wispshell_protocol::DeviceKeypair;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrustedClient {
    pub client_device_id: String,
    pub client_public_key: String,
    pub client_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentState {
    pub device_id: String,
    pub private_key: String,
    pub public_key: String,
    pub trusted_clients: Vec<TrustedClient>,
}

impl AgentState {
    pub fn path() -> anyhow::Result<PathBuf> {
        let dirs = ProjectDirs::from("dev", "wispshell", "wispshell")
            .ok_or_else(|| anyhow::anyhow!("could not resolve data directory"))?;
        Ok(dirs.data_dir().join("agent-state.json"))
    }

    pub fn load_or_create() -> anyhow::Result<Self> {
        let path = Self::path()?;
        if path.exists() {
            return Ok(serde_json::from_slice(&std::fs::read(path)?)?);
        }
        let keys = DeviceKeypair::generate();
        let state = Self {
            device_id: keys.device_id()?,
            private_key: keys.private_key_b64(),
            public_key: keys.public_key_b64(),
            trusted_clients: Vec::new(),
        };
        state.save()?;
        Ok(state)
    }

    pub fn save(&self) -> anyhow::Result<()> {
        let path = Self::path()?;
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&path, serde_json::to_vec_pretty(self)?)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?;
        }
        Ok(())
    }

    pub fn trust_client(&mut self, client: TrustedClient) {
        self.trusted_clients
            .retain(|existing| existing.client_device_id != client.client_device_id);
        self.trusted_clients.push(client);
    }
}
