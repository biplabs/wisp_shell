pub fn parse_pairing_uri_json(uri: String) -> String {
    match crate::parse_pairing_uri(&uri).and_then(|payload| Ok(serde_json::to_string(&payload)?)) {
        Ok(json) => json,
        Err(err) => format!(r#"{{"error":"{}"}}"#, err.to_string().replace('"', "'")),
    }
}
