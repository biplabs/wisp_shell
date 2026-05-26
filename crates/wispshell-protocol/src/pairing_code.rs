use crate::{b64_encode, sha256_hex};
use rand_core::{OsRng, RngCore};

const ALPHABET: &[u8] = b"23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
const BODY_LEN: usize = 13;

pub fn generate_pairing_code() -> String {
    let mut bytes = [0_u8; 8];
    OsRng.fill_bytes(&mut bytes);
    encode_pairing_code(bytes)
}

pub fn encode_pairing_code(bytes: [u8; 8]) -> String {
    let mut value = u64::from_be_bytes(bytes);
    let mut chars = ['2'; BODY_LEN];
    for idx in (0..BODY_LEN).rev() {
        chars[idx] = ALPHABET[(value % 32) as usize] as char;
        value /= 32;
    }
    let raw: String = chars.into_iter().collect();
    format!(
        "WISP-{}-{}-{}-{}",
        &raw[0..3],
        &raw[3..6],
        &raw[6..9],
        &raw[9..13]
    )
}

pub fn normalize_pairing_code(code: &str) -> anyhow::Result<String> {
    let compact: String = code
        .chars()
        .filter(|c| !c.is_ascii_whitespace() && *c != '-')
        .map(|c| c.to_ascii_uppercase())
        .collect();

    let body = compact
        .strip_prefix("WISP")
        .ok_or_else(|| anyhow::anyhow!("pairing code must start with WISP"))?;
    if body.len() != BODY_LEN {
        anyhow::bail!("pairing code has invalid length");
    }
    if !body.bytes().all(|b| ALPHABET.contains(&b)) {
        anyhow::bail!("pairing code contains invalid characters");
    }
    Ok(format!(
        "WISP-{}-{}-{}-{}",
        &body[0..3],
        &body[3..6],
        &body[6..9],
        &body[9..13]
    ))
}

pub fn pairing_code_hash(code: &str) -> anyhow::Result<String> {
    Ok(sha256_hex(normalize_pairing_code(code)?.as_bytes()))
}

pub fn pairing_code_display_secret(code: &str) -> anyhow::Result<String> {
    Ok(b64_encode(normalize_pairing_code(code)?.as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_code_is_valid_and_high_entropy_length() {
        let code = generate_pairing_code();
        assert!(code.starts_with("WISP-"));
        assert_eq!(normalize_pairing_code(&code).unwrap(), code);
        assert_eq!(code.replace('-', "").len(), 17);
    }

    #[test]
    fn normalize_accepts_lowercase_and_missing_dashes() {
        let code = encode_pairing_code([1_u8; 8]);
        let loose = code.to_ascii_lowercase().replace('-', " ");
        assert_eq!(normalize_pairing_code(&loose).unwrap(), code);
    }

    #[test]
    fn hash_is_stable_after_normalization() {
        let code = encode_pairing_code([2_u8; 8]);
        assert_eq!(
            pairing_code_hash(&code).unwrap(),
            pairing_code_hash(&code.to_ascii_lowercase()).unwrap()
        );
    }

    #[test]
    fn rejects_ambiguous_or_short_codes() {
        assert!(normalize_pairing_code("WISP-1234").is_err());
        assert!(normalize_pairing_code("WISP-OOOO-OOOO-OOOO").is_err());
    }
}
