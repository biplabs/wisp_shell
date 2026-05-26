use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use hmac::{Hmac, Mac};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};

type HmacSha256 = Hmac<Sha256>;

pub fn b64_encode(bytes: impl AsRef<[u8]>) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn b64_decode(input: &str) -> anyhow::Result<Vec<u8>> {
    Ok(URL_SAFE_NO_PAD.decode(input)?)
}

pub fn random_b64(bytes: usize) -> String {
    let mut buf = vec![0_u8; bytes];
    OsRng.fill_bytes(&mut buf);
    b64_encode(buf)
}

pub fn sha256_hex(bytes: impl AsRef<[u8]>) -> String {
    let digest = Sha256::digest(bytes);
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn device_id_from_public_key(public_key_b64: &str) -> anyhow::Result<String> {
    let public_key = b64_decode(public_key_b64)?;
    Ok(b64_encode(Sha256::digest(public_key))[0..32].to_string())
}

pub fn pairing_proof(
    secret_b64: &str,
    pair_id: &str,
    daemon_id: &str,
    client_device_id: &str,
    client_public_key: &str,
) -> anyhow::Result<String> {
    let mut mac = HmacSha256::new_from_slice(&b64_decode(secret_b64)?)?;
    mac.update(pair_id.as_bytes());
    mac.update(daemon_id.as_bytes());
    mac.update(client_device_id.as_bytes());
    mac.update(client_public_key.as_bytes());
    Ok(b64_encode(mac.finalize().into_bytes()))
}

pub fn verify_pairing_proof(
    expected_secret_b64: &str,
    pair_id: &str,
    daemon_id: &str,
    client_device_id: &str,
    client_public_key: &str,
    proof: &str,
) -> anyhow::Result<bool> {
    Ok(pairing_proof(
        expected_secret_b64,
        pair_id,
        daemon_id,
        client_device_id,
        client_public_key,
    )? == proof)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pairing_hmac_is_stable() {
        let secret = b64_encode([7_u8; 32]);
        let proof_a = pairing_proof(&secret, "p", "d", "c", "pub").unwrap();
        let proof_b = pairing_proof(&secret, "p", "d", "c", "pub").unwrap();
        assert_eq!(proof_a, proof_b);
        assert!(verify_pairing_proof(&secret, "p", "d", "c", "pub", &proof_a).unwrap());
    }
}
