pub mod crypto;
pub mod messages;
pub mod pairing_code;
pub mod signing;

pub use crypto::*;
pub use messages::*;
pub use pairing_code::*;
pub use signing::*;

pub const PROTOCOL_VERSION: u32 = 1;
pub const WISP_ALPN: &[u8] = b"wispshell/1";

pub const ERR_INVALID_REQUEST: &str = "invalid_request";
pub const ERR_UNAUTHORIZED: &str = "unauthorized";
pub const ERR_SIGNATURE_INVALID: &str = "signature_invalid";
pub const ERR_PAIRING_EXPIRED: &str = "pairing_expired";
pub const ERR_PAIRING_USED: &str = "pairing_used";
pub const ERR_PAIRING_NOT_FOUND: &str = "pairing_not_found";
pub const ERR_PAIRING_PROOF_INVALID: &str = "pairing_proof_invalid";
pub const ERR_DEVICE_NOT_FOUND: &str = "device_not_found";
pub const ERR_DEVICE_NOT_BOUND: &str = "device_not_bound";
pub const ERR_DEVICE_REVOKED: &str = "device_revoked";
pub const ERR_DEVICE_OFFLINE: &str = "device_offline";
pub const ERR_RENDEZVOUS_UNAVAILABLE: &str = "rendezvous_unavailable";
pub const ERR_PROTOCOL_VERSION_UNSUPPORTED: &str = "protocol_version_unsupported";
pub const ERR_HANDSHAKE_FAILED: &str = "handshake_failed";
pub const ERR_SESSION_NOT_FOUND: &str = "session_not_found";
pub const ERR_NOT_INPUT_OWNER: &str = "not_input_owner";
pub const ERR_PTY_SPAWN_FAILED: &str = "pty_spawn_failed";
pub const ERR_TRANSPORT_ERROR: &str = "transport_error";
pub const ERR_INTERNAL_ERROR: &str = "internal_error";
