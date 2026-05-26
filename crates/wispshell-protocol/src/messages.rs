use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientToAgent {
    ClientHello {
        protocol_version: u32,
        client_device_id: String,
        client_public_key: String,
        daemon_device_id: String,
        binding_id: String,
        nonce: String,
    },
    ClientAuth {
        signature: String,
    },
    Attach {
        session_name: String,
        cols: u16,
        rows: u16,
    },
    Input {
        session_id: String,
        data_b64: String,
    },
    Resize {
        session_id: String,
        cols: u16,
        rows: u16,
    },
    Detach {
        session_id: String,
    },
    Ping {
        nonce: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AgentToClient {
    AgentChallenge {
        daemon_device_id: String,
        daemon_public_key: String,
        client_nonce: String,
        daemon_nonce: String,
    },
    AgentAuthOk {
        signature: String,
    },
    SessionAttached {
        session_id: String,
        session_name: String,
        cols: u16,
        rows: u16,
    },
    Scrollback {
        session_id: String,
        chunks_b64: Vec<String>,
    },
    Output {
        session_id: String,
        data_b64: String,
    },
    SessionExited {
        session_id: String,
        exit_code: Option<i32>,
    },
    Error {
        code: String,
        message: String,
    },
    Pong {
        nonce: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PairingPayload {
    pub v: u8,
    pub cloud: String,
    pub pair_id: String,
    pub daemon_id: String,
    pub daemon_pub: String,
    pub secret: String,
    pub expires: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct BindingCertificate {
    pub binding_id: String,
    pub daemon_device_id: String,
    pub daemon_public_key: String,
    pub client_device_id: String,
    pub client_public_key: String,
    pub permissions: Vec<String>,
    pub created_at: DateTime<Utc>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_and_agent_messages_roundtrip() {
        let c = ClientToAgent::Attach {
            session_name: "main".to_string(),
            cols: 80,
            rows: 24,
        };
        let json = serde_json::to_string(&c).unwrap();
        assert_eq!(serde_json::from_str::<ClientToAgent>(&json).unwrap(), c);

        let a = AgentToClient::Output {
            session_id: "s1".to_string(),
            data_b64: "aGk=".to_string(),
        };
        let json = serde_json::to_string(&a).unwrap();
        assert_eq!(serde_json::from_str::<AgentToClient>(&json).unwrap(), a);
    }
}
