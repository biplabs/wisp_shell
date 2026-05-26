use crate::db::Db;
use chrono::{DateTime, Duration, Utc};
use wispshell_protocol::{
    device_id_from_public_key, rest_signature_payload, verify_b64, ERR_DEVICE_NOT_FOUND,
    ERR_SIGNATURE_INVALID,
};

#[allow(dead_code)]
pub fn verify_signed_request(
    db: &Db,
    device_id: &str,
    method: &str,
    path: &str,
    timestamp: &str,
    body: &[u8],
    signature_b64: &str,
    now: DateTime<Utc>,
) -> Result<(), &'static str> {
    let device = db.devices.get(device_id).ok_or(ERR_DEVICE_NOT_FOUND)?;
    if device_id_from_public_key(&device.public_key).map_err(|_| ERR_SIGNATURE_INVALID)?
        != device_id
    {
        return Err(ERR_SIGNATURE_INVALID);
    }

    let timestamp = DateTime::parse_from_rfc3339(timestamp)
        .map_err(|_| ERR_SIGNATURE_INVALID)?
        .with_timezone(&Utc);
    if (now - timestamp).num_seconds().abs() > Duration::minutes(5).num_seconds() {
        return Err(ERR_SIGNATURE_INVALID);
    }

    let payload = rest_signature_payload(method, path, timestamp.to_rfc3339().as_str(), body);
    match verify_b64(&device.public_key, payload.as_bytes(), signature_b64) {
        Ok(true) => Ok(()),
        _ => Err(ERR_SIGNATURE_INVALID),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::{Db, Device};
    use wispshell_protocol::DeviceKeypair;

    #[test]
    fn verifies_valid_device_signature() {
        let keys = DeviceKeypair::generate();
        let device_id = keys.device_id().unwrap();
        let now = Utc::now();
        let body = br#"{"ok":true}"#;
        let payload = rest_signature_payload("POST", "/v1/test", &now.to_rfc3339(), body);
        let signature = keys.sign_b64(payload.as_bytes());
        let mut db = Db::default();
        db.devices.insert(
            device_id.clone(),
            Device {
                device_id: device_id.clone(),
                kind: "android".to_string(),
                display_name: "Tablet".to_string(),
                public_key: keys.public_key_b64(),
                created_at: now,
                last_seen_at: Some(now),
            },
        );

        assert_eq!(
            verify_signed_request(
                &db,
                &device_id,
                "POST",
                "/v1/test",
                &now.to_rfc3339(),
                body,
                &signature,
                now
            ),
            Ok(())
        );
    }

    #[test]
    fn rejects_stale_or_tampered_signature() {
        let keys = DeviceKeypair::generate();
        let device_id = keys.device_id().unwrap();
        let now = Utc::now();
        let stale = now - Duration::minutes(10);
        let body = br#"{"ok":true}"#;
        let payload = rest_signature_payload("POST", "/v1/test", &stale.to_rfc3339(), body);
        let signature = keys.sign_b64(payload.as_bytes());
        let mut db = Db::default();
        db.devices.insert(
            device_id.clone(),
            Device {
                device_id: device_id.clone(),
                kind: "android".to_string(),
                display_name: "Tablet".to_string(),
                public_key: keys.public_key_b64(),
                created_at: now,
                last_seen_at: Some(now),
            },
        );

        assert_eq!(
            verify_signed_request(
                &db,
                &device_id,
                "POST",
                "/v1/test",
                &stale.to_rfc3339(),
                body,
                &signature,
                now
            ),
            Err(ERR_SIGNATURE_INVALID)
        );
        assert_eq!(
            verify_signed_request(
                &db,
                &device_id,
                "POST",
                "/v1/test",
                &now.to_rfc3339(),
                br#"{"ok":false}"#,
                &signature,
                now
            ),
            Err(ERR_SIGNATURE_INVALID)
        );
    }
}
