use crate::{b64_decode, b64_encode, device_id_from_public_key, sha256_hex};
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand_core::OsRng;

#[derive(Debug, Clone)]
pub struct DeviceKeypair {
    signing_key: SigningKey,
}

impl DeviceKeypair {
    pub fn generate() -> Self {
        Self {
            signing_key: SigningKey::generate(&mut OsRng),
        }
    }

    pub fn public_key_b64(&self) -> String {
        b64_encode(self.signing_key.verifying_key().as_bytes())
    }

    pub fn device_id(&self) -> anyhow::Result<String> {
        device_id_from_public_key(&self.public_key_b64())
    }

    pub fn private_key_b64(&self) -> String {
        b64_encode(self.signing_key.to_bytes())
    }

    pub fn from_private_key_b64(private_key_b64: &str) -> anyhow::Result<Self> {
        let bytes: [u8; 32] = b64_decode(private_key_b64)?
            .try_into()
            .map_err(|_| anyhow::anyhow!("invalid private key length"))?;
        Ok(Self {
            signing_key: SigningKey::from_bytes(&bytes),
        })
    }

    pub fn sign_b64(&self, payload: &[u8]) -> String {
        b64_encode(self.signing_key.sign(payload).to_bytes())
    }
}

pub fn verify_b64(
    public_key_b64: &str,
    payload: &[u8],
    signature_b64: &str,
) -> anyhow::Result<bool> {
    let public_key: [u8; 32] = b64_decode(public_key_b64)?
        .try_into()
        .map_err(|_| anyhow::anyhow!("invalid public key length"))?;
    let signature: [u8; 64] = b64_decode(signature_b64)?
        .try_into()
        .map_err(|_| anyhow::anyhow!("invalid signature length"))?;
    let verifying_key = VerifyingKey::from_bytes(&public_key)?;
    Ok(verifying_key
        .verify(payload, &Signature::from_bytes(&signature))
        .is_ok())
}

pub fn rest_signature_payload(method: &str, path: &str, timestamp: &str, body: &[u8]) -> String {
    format!(
        "{}\n{}\n{}\n{}",
        method.to_ascii_uppercase(),
        path,
        timestamp,
        sha256_hex(body)
    )
}

#[allow(clippy::too_many_arguments)]
pub fn transport_handshake_payload(
    protocol_version: u32,
    client_device_id: &str,
    client_public_key: &str,
    daemon_device_id: &str,
    daemon_public_key: &str,
    binding_id: &str,
    client_nonce: &str,
    daemon_nonce: &str,
) -> String {
    [
        "wispshell-transport-v1".to_string(),
        protocol_version.to_string(),
        client_device_id.to_string(),
        client_public_key.to_string(),
        daemon_device_id.to_string(),
        daemon_public_key.to_string(),
        binding_id.to_string(),
        client_nonce.to_string(),
        daemon_nonce.to_string(),
    ]
    .join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_id_and_signatures_work() {
        let keys = DeviceKeypair::generate();
        let device_id = keys.device_id().unwrap();
        assert_eq!(device_id.len(), 32);

        let payload = b"hello";
        let sig = keys.sign_b64(payload);
        assert!(verify_b64(&keys.public_key_b64(), payload, &sig).unwrap());
        assert!(!verify_b64(&keys.public_key_b64(), b"bye", &sig).unwrap());

        let restored = DeviceKeypair::from_private_key_b64(&keys.private_key_b64()).unwrap();
        assert_eq!(restored.public_key_b64(), keys.public_key_b64());
    }

    #[test]
    fn rest_payload_hashes_body() {
        let payload = rest_signature_payload("post", "/v1/devices/register", "now", br#"{"a":1}"#);
        assert!(payload.starts_with("POST\n/v1/devices/register\nnow\n"));
    }

    #[test]
    fn transport_handshake_payload_is_stable() {
        let payload = transport_handshake_payload(1, "c", "cpub", "d", "dpub", "b", "cn", "dn");
        assert_eq!(
            payload,
            "wispshell-transport-v1\n1\nc\ncpub\nd\ndpub\nb\ncn\ndn"
        );
    }
}
