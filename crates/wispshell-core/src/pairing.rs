use anyhow::{anyhow, bail};
use chrono::{DateTime, Utc};
use url::Url;
use wispshell_protocol::PairingPayload;

pub fn parse_pairing_uri(uri: &str) -> anyhow::Result<PairingPayload> {
    let url = Url::parse(uri)?;
    if url.scheme() != "wispshell" || url.host_str() != Some("pair") {
        bail!("invalid wispshell pairing URI");
    }

    let get = |key: &str| -> anyhow::Result<String> {
        url.query_pairs()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.into_owned())
            .ok_or_else(|| anyhow!("missing pairing field: {key}"))
    };

    let v = get("v")?.parse::<u8>()?;
    if v != 1 {
        bail!("unsupported pairing version: {v}");
    }

    let expires = DateTime::parse_from_rfc3339(&get("expires")?)?.with_timezone(&Utc);
    if expires <= Utc::now() {
        bail!("pairing URI is expired");
    }

    Ok(PairingPayload {
        v,
        cloud: get("cloud")?,
        pair_id: get("pair_id")?,
        daemon_id: get("daemon_id")?,
        daemon_pub: get("daemon_pub")?,
        secret: get("secret")?,
        expires,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Duration;

    #[test]
    fn parses_valid_qr_uri() {
        let expires = (Utc::now() + Duration::minutes(2)).to_rfc3339();
        let uri = format!(
            "wispshell://pair?v=1&cloud=http%3A%2F%2F127.0.0.1%3A8080&pair_id=p&daemon_id=d&daemon_pub=pub&secret=s&expires={}",
            urlencoding::encode(&expires)
        );
        let parsed = parse_pairing_uri(&uri).unwrap();
        assert_eq!(parsed.cloud, "http://127.0.0.1:8080");
    }

    #[test]
    fn rejects_wrong_scheme() {
        assert!(parse_pairing_uri("https://pair?v=1").is_err());
    }
}
