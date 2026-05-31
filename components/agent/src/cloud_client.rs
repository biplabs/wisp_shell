use serde::{Deserialize, Serialize};

#[derive(Debug, Clone)]
pub struct CloudClient {
    base_url: String,
    http: reqwest::Client,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ResolvedCodePairing {
    pub pair_id: String,
    pub client_device_id: String,
    pub client_public_key: String,
    pub client_name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ApproveCodePairingRequest {
    pub code: String,
    pub daemon_device_id: String,
    pub daemon_public_key: String,
    pub daemon_name: String,
    pub binding_id: String,
    pub binding_cert_json: String,
    pub binding_signature: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ApprovedBinding {
    pub binding_id: String,
    pub daemon_device_id: String,
    pub client_device_id: String,
    pub binding_cert_json: String,
    pub binding_signature: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct RegisterDeviceRequest {
    pub device_id: String,
    pub kind: String,
    pub display_name: String,
    pub public_key: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct PresenceUpdateRequest {
    pub device_id: String,
    pub status: String,
    pub iroh_node_addr_json: Option<serde_json::Value>,
    pub agent_version: Option<String>,
}

impl CloudClient {
    pub fn new(base_url: impl Into<String>) -> Self {
        Self {
            base_url: base_url.into(),
            http: reqwest::Client::new(),
        }
    }

    pub async fn resolve_code_pairing(&self, code: &str) -> anyhow::Result<ResolvedCodePairing> {
        let url = format!(
            "{}/v1/pairing/code/resolve",
            self.base_url.trim_end_matches('/')
        );
        Ok(self
            .http
            .post(url)
            .json(&serde_json::json!({ "code": code }))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn approve_code_pairing(
        &self,
        req: &ApproveCodePairingRequest,
    ) -> anyhow::Result<ApprovedBinding> {
        let url = format!(
            "{}/v1/pairing/code/approve",
            self.base_url.trim_end_matches('/')
        );
        Ok(self
            .http
            .post(url)
            .json(req)
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn register_device(&self, req: &RegisterDeviceRequest) -> anyhow::Result<()> {
        let url = format!(
            "{}/v1/devices/register",
            self.base_url.trim_end_matches('/')
        );
        self.http
            .post(url)
            .json(req)
            .send()
            .await?
            .error_for_status()?;
        Ok(())
    }

    pub async fn update_presence(&self, req: &PresenceUpdateRequest) -> anyhow::Result<()> {
        let url = format!("{}/v1/presence/update", self.base_url.trim_end_matches('/'));
        self.http
            .post(url)
            .json(req)
            .send()
            .await?
            .error_for_status()?;
        Ok(())
    }
}
