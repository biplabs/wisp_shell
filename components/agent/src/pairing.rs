use crate::{
    cloud_client::{ApproveCodePairingRequest, CloudClient},
    config::AgentConfig,
    state::{AgentState, TrustedClient},
};
use chrono::{Duration, Utc};
use qrcode::{render::unicode, QrCode};
use uuid::Uuid;
use wispshell_protocol::{normalize_pairing_code, random_b64, BindingCertificate, DeviceKeypair};

pub async fn print_pairing() -> anyhow::Result<()> {
    let config = AgentConfig::load_or_create()?;
    let state = AgentState::load_or_create()?;
    let pair_id = random_b64(16);
    let secret = random_b64(32);
    let expires = Utc::now() + Duration::minutes(2);
    let code = format!("{}-{}", &pair_id[0..4], &pair_id[4..8]).to_ascii_uppercase();
    let uri = format!(
        "wispshell://pair?v=1&cloud={}&pair_id={}&daemon_id={}&daemon_pub={}&secret={}&expires={}",
        urlencoding::encode(&config.cloud_url),
        pair_id,
        state.device_id,
        state.public_key,
        secret,
        urlencoding::encode(&expires.to_rfc3339())
    );
    let qr = QrCode::new(uri.as_bytes())?
        .render::<unicode::Dense1x2>()
        .quiet_zone(true)
        .build();

    tracing::info!(pair_id = %pair_id, "pairing started");
    println!("WispShell pairing\n");
    println!("Scan this code in the Android app.");
    println!("Expires in 2 minutes.\n");
    println!("{qr}");
    println!("\nCode: {code}");
    println!("Pair ID: {pair_id}");
    Ok(())
}

pub async fn approve_pairing_code(code: &str) -> anyhow::Result<()> {
    let config = AgentConfig::load_or_create()?;
    let mut state = AgentState::load_or_create()?;
    let normalized = normalize_pairing_code(code)?;
    let cloud = CloudClient::new(config.cloud_url.clone());
    let resolved = cloud.resolve_code_pairing(&normalized).await?;
    let binding_id = Uuid::new_v4().to_string();
    let cert = BindingCertificate {
        binding_id: binding_id.clone(),
        daemon_device_id: state.device_id.clone(),
        daemon_public_key: state.public_key.clone(),
        client_device_id: resolved.client_device_id.clone(),
        client_public_key: resolved.client_public_key.clone(),
        permissions: vec!["terminal".to_string()],
        created_at: Utc::now(),
    };
    let cert_json = serde_json::to_string(&cert)?;
    let keys = DeviceKeypair::from_private_key_b64(&state.private_key)?;
    let signature = keys.sign_b64(cert_json.as_bytes());
    let approved = cloud
        .approve_code_pairing(&ApproveCodePairingRequest {
            code: normalized.clone(),
            daemon_device_id: state.device_id.clone(),
            daemon_public_key: state.public_key.clone(),
            daemon_name: config.device_name.clone(),
            binding_id: binding_id.clone(),
            binding_cert_json: cert_json,
            binding_signature: signature,
        })
        .await?;
    state.trust_client(TrustedClient {
        client_device_id: resolved.client_device_id.clone(),
        client_public_key: resolved.client_public_key.clone(),
        client_name: resolved.client_name.clone(),
    });
    if approved.daemon_device_id != state.device_id
        || approved.client_device_id != resolved.client_device_id
        || approved.binding_cert_json.is_empty()
        || approved.binding_signature.is_empty()
    {
        anyhow::bail!("cloud returned mismatched binding");
    }
    state.save()?;

    println!("WispShell Android-code pairing\n");
    println!("Cloud: {}", config.cloud_url);
    println!("Daemon: {}", state.device_id);
    println!(
        "Android: {} ({})",
        resolved.client_name, resolved.client_device_id
    );
    println!("Pair ID: {}", resolved.pair_id);
    println!("Binding: {}", approved.binding_id);
    println!("Code: {normalized}");
    println!();
    println!("Approved and stored trusted Android client.");
    tracing::info!(client_device_id = %resolved.client_device_id, binding_id = %approved.binding_id, "pairing code approved");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use wispshell_protocol::encode_pairing_code;

    #[test]
    fn pairing_code_normalization_accepts_human_entry() {
        let code = encode_pairing_code([11_u8; 8]);
        let typed = code.to_ascii_lowercase().replace('-', " ");
        assert_eq!(normalize_pairing_code(&typed).unwrap(), code);
    }

    #[test]
    fn pairing_code_normalization_rejects_short_entry() {
        assert!(normalize_pairing_code("WISP-1234").is_err());
    }
}
