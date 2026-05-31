use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

pub const BINARY_FRAME_MAGIC: u8 = 0x1e;
pub const BINARY_FRAME_HEADER_LEN: usize = 8;
pub const SHORT_BINARY_FRAME_MAGIC: u8 = 0x1f;
pub const BINARY_KIND_CLIENT_INPUT: u8 = 1;
pub const BINARY_KIND_AGENT_OUTPUT: u8 = 2;
pub const MAX_BINARY_SESSION_ID_LEN: usize = 4096;
pub const MAX_BINARY_PAYLOAD_LEN: usize = 16 * 1024 * 1024;

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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BinaryFrame {
    pub kind: u8,
    pub session_id: String,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ShortBinaryFrame {
    pub payload: Vec<u8>,
}

pub fn encode_binary_frame(kind: u8, session_id: &str, payload: &[u8]) -> anyhow::Result<Vec<u8>> {
    if session_id.len() > u16::MAX as usize || session_id.len() > MAX_BINARY_SESSION_ID_LEN {
        anyhow::bail!("binary frame session id too long");
    }
    if payload.len() > u32::MAX as usize || payload.len() > MAX_BINARY_PAYLOAD_LEN {
        anyhow::bail!("binary frame payload too large");
    }

    let session_id_len = session_id.len() as u16;
    let payload_len = payload.len() as u32;
    let mut frame = Vec::with_capacity(BINARY_FRAME_HEADER_LEN + session_id.len() + payload.len());
    frame.push(BINARY_FRAME_MAGIC);
    frame.push(kind);
    frame.extend_from_slice(&session_id_len.to_be_bytes());
    frame.extend_from_slice(&payload_len.to_be_bytes());
    frame.extend_from_slice(session_id.as_bytes());
    frame.extend_from_slice(payload);
    Ok(frame)
}

pub fn encode_short_binary_frame(payload: &[u8]) -> anyhow::Result<Vec<u8>> {
    if payload.len() > MAX_BINARY_PAYLOAD_LEN {
        anyhow::bail!("short binary frame payload too large");
    }

    let mut payload_len = payload.len();
    let mut frame = Vec::with_capacity(1 + varint_len(payload.len()) + payload.len());
    frame.push(SHORT_BINARY_FRAME_MAGIC);
    loop {
        let mut byte = (payload_len & 0x7f) as u8;
        payload_len >>= 7;
        if payload_len != 0 {
            byte |= 0x80;
        }
        frame.push(byte);
        if payload_len == 0 {
            break;
        }
    }
    frame.extend_from_slice(payload);
    Ok(frame)
}

pub fn decode_binary_header(
    header: [u8; BINARY_FRAME_HEADER_LEN],
) -> anyhow::Result<(u8, usize, usize)> {
    if header[0] != BINARY_FRAME_MAGIC {
        anyhow::bail!("invalid binary frame magic");
    }
    let kind = header[1];
    let session_id_len = u16::from_be_bytes([header[2], header[3]]) as usize;
    let payload_len = u32::from_be_bytes([header[4], header[5], header[6], header[7]]) as usize;
    if session_id_len > MAX_BINARY_SESSION_ID_LEN {
        anyhow::bail!("binary frame session id too long");
    }
    if payload_len > MAX_BINARY_PAYLOAD_LEN {
        anyhow::bail!("binary frame payload too large");
    }
    Ok((kind, session_id_len, payload_len))
}

pub fn decode_short_binary_payload_len(bytes: &[u8]) -> anyhow::Result<(usize, usize)> {
    let mut value = 0usize;
    for (index, byte) in bytes.iter().copied().enumerate() {
        if index >= 4 {
            anyhow::bail!("short binary frame length is too large");
        }
        value |= ((byte & 0x7f) as usize) << (index * 7);
        if byte & 0x80 == 0 {
            if value > MAX_BINARY_PAYLOAD_LEN {
                anyhow::bail!("short binary frame payload too large");
            }
            return Ok((value, index + 1));
        }
    }
    anyhow::bail!("incomplete short binary frame length")
}

fn varint_len(mut value: usize) -> usize {
    let mut len = 1;
    while value >= 0x80 {
        value >>= 7;
        len += 1;
    }
    len
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

    #[test]
    fn binary_frame_roundtrip() {
        let frame = encode_binary_frame(BINARY_KIND_CLIENT_INPUT, "session", b"ls\n").unwrap();
        let header: [u8; BINARY_FRAME_HEADER_LEN] =
            frame[..BINARY_FRAME_HEADER_LEN].try_into().unwrap();
        let (kind, session_id_len, payload_len) = decode_binary_header(header).unwrap();
        assert_eq!(kind, BINARY_KIND_CLIENT_INPUT);
        assert_eq!(session_id_len, 7);
        assert_eq!(payload_len, 3);
        assert_eq!(
            &frame[BINARY_FRAME_HEADER_LEN..BINARY_FRAME_HEADER_LEN + 7],
            b"session"
        );
        assert_eq!(&frame[BINARY_FRAME_HEADER_LEN + 7..], b"ls\n");
    }

    #[test]
    fn short_binary_frame_roundtrip() {
        let frame = encode_short_binary_frame(b"hi").unwrap();
        let (payload_len, len_len) = decode_short_binary_payload_len(&frame[1..]).unwrap();
        assert_eq!(payload_len, 2);
        assert_eq!(len_len, 1);
        assert_eq!(&frame[1 + len_len..], b"hi");
    }

    #[test]
    fn short_binary_frame_uses_multibyte_lengths_when_needed() {
        let payload = vec![b'x'; 300];
        let frame = encode_short_binary_frame(&payload).unwrap();
        let (payload_len, len_len) = decode_short_binary_payload_len(&frame[1..]).unwrap();
        assert_eq!(payload_len, payload.len());
        assert_eq!(len_len, 2);
        assert_eq!(&frame[1 + len_len..], payload.as_slice());
    }
}
