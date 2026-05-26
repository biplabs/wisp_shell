use chrono::{DateTime, Utc};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tokio::sync::broadcast;

#[derive(Clone, Default)]
pub struct AppState {
    pub inner: Arc<Mutex<Db>>,
    pub events: Arc<Mutex<HashMap<String, broadcast::Sender<AgentEvent>>>>,
    store: Option<Arc<SqliteStore>>,
}

#[derive(Default)]
pub struct Db {
    pub devices: HashMap<String, Device>,
    pub pairings: HashMap<String, PairingSession>,
    pub code_pairings: HashMap<String, CodePairingSession>,
    pub bindings: HashMap<String, Binding>,
    pub presence: HashMap<String, Presence>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub device_id: String,
    pub kind: String,
    pub display_name: String,
    pub public_key: String,
    pub created_at: DateTime<Utc>,
    pub last_seen_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingSession {
    pub pair_id: String,
    pub daemon_device_id: String,
    pub daemon_public_key: String,
    pub expires_at: DateTime<Utc>,
    pub used_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CodePairingSession {
    pub pair_id: String,
    pub code_hash: String,
    pub client_device_id: String,
    pub client_public_key: String,
    pub client_name: String,
    pub expires_at: DateTime<Utc>,
    pub used_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Binding {
    pub binding_id: String,
    pub daemon_device_id: String,
    pub client_device_id: String,
    pub permissions_json: String,
    pub binding_cert_json: String,
    pub binding_signature: String,
    pub created_at: DateTime<Utc>,
    pub revoked_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Presence {
    pub device_id: String,
    pub status: String,
    pub iroh_node_addr_json: Option<serde_json::Value>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AgentEvent {
    PairingClaimed {
        pair_id: String,
        client_device_id: String,
        client_public_key: String,
        client_name: String,
        proof: String,
    },
}

impl AppState {
    pub fn persistent(path: impl AsRef<Path>) -> anyhow::Result<Self> {
        let store = Arc::new(SqliteStore::open(path)?);
        let db = store.load()?;
        Ok(Self {
            inner: Arc::new(Mutex::new(db)),
            events: Arc::default(),
            store: Some(store),
        })
    }

    pub fn persist_or_log(&self) {
        let Some(store) = &self.store else {
            return;
        };
        let db = self.inner.lock().unwrap();
        if let Err(error) = store.save(&db) {
            tracing::error!(?error, "failed to persist cloud db");
        }
    }

    pub fn event_sender(&self, device_id: &str) -> broadcast::Sender<AgentEvent> {
        let mut events = self.events.lock().unwrap();
        events
            .entry(device_id.to_string())
            .or_insert_with(|| broadcast::channel(128).0)
            .clone()
    }
}

pub fn default_sqlite_path() -> PathBuf {
    if let Ok(path) = std::env::var("WISPSHELL_CLOUD_DB") {
        return PathBuf::from(path);
    }
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home)
        .join(".local")
        .join("share")
        .join("wispshell-cloud")
        .join("cloud.sqlite3")
}

pub struct SqliteStore {
    path: PathBuf,
}

impl SqliteStore {
    pub fn open(path: impl AsRef<Path>) -> anyhow::Result<Self> {
        let path = path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let store = Self { path };
        store.with_connection(|conn| {
            conn.execute_batch(
                r#"
                PRAGMA journal_mode = WAL;
                PRAGMA foreign_keys = ON;

                CREATE TABLE IF NOT EXISTS devices (
                    device_id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    public_key TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    last_seen_at TEXT
                );

                CREATE TABLE IF NOT EXISTS pairings (
                    pair_id TEXT PRIMARY KEY,
                    daemon_device_id TEXT NOT NULL,
                    daemon_public_key TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    used_at TEXT,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS code_pairings (
                    code_hash TEXT PRIMARY KEY,
                    pair_id TEXT NOT NULL,
                    client_device_id TEXT NOT NULL,
                    client_public_key TEXT NOT NULL,
                    client_name TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    used_at TEXT,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS bindings (
                    binding_id TEXT PRIMARY KEY,
                    daemon_device_id TEXT NOT NULL,
                    client_device_id TEXT NOT NULL,
                    permissions_json TEXT NOT NULL,
                    binding_cert_json TEXT NOT NULL,
                    binding_signature TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    revoked_at TEXT
                );

                CREATE TABLE IF NOT EXISTS presence (
                    device_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    iroh_node_addr_json TEXT,
                    updated_at TEXT NOT NULL
                );
                "#,
            )?;
            Ok(())
        })?;
        Ok(store)
    }

    pub fn load(&self) -> anyhow::Result<Db> {
        self.with_connection(|conn| {
            Ok(Db {
                devices: load_devices(conn)?,
                pairings: load_pairings(conn)?,
                code_pairings: load_code_pairings(conn)?,
                bindings: load_bindings(conn)?,
                presence: load_presence(conn)?,
            })
        })
    }

    pub fn save(&self, db: &Db) -> anyhow::Result<()> {
        self.with_connection(|conn| {
            let tx = conn.unchecked_transaction()?;
            tx.execute("DELETE FROM devices", [])?;
            tx.execute("DELETE FROM pairings", [])?;
            tx.execute("DELETE FROM code_pairings", [])?;
            tx.execute("DELETE FROM bindings", [])?;
            tx.execute("DELETE FROM presence", [])?;

            for device in db.devices.values() {
                tx.execute(
                    "INSERT INTO devices (device_id, kind, display_name, public_key, created_at, last_seen_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    params![
                        device.device_id,
                        device.kind,
                        device.display_name,
                        device.public_key,
                        encode_dt(device.created_at),
                        device.last_seen_at.map(encode_dt),
                    ],
                )?;
            }
            for pairing in db.pairings.values() {
                tx.execute(
                    "INSERT INTO pairings (pair_id, daemon_device_id, daemon_public_key, expires_at, used_at, created_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    params![
                        pairing.pair_id,
                        pairing.daemon_device_id,
                        pairing.daemon_public_key,
                        encode_dt(pairing.expires_at),
                        pairing.used_at.map(encode_dt),
                        encode_dt(pairing.created_at),
                    ],
                )?;
            }
            for session in db.code_pairings.values() {
                tx.execute(
                    "INSERT INTO code_pairings (code_hash, pair_id, client_device_id, client_public_key, client_name, expires_at, used_at, created_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                    params![
                        session.code_hash,
                        session.pair_id,
                        session.client_device_id,
                        session.client_public_key,
                        session.client_name,
                        encode_dt(session.expires_at),
                        session.used_at.map(encode_dt),
                        encode_dt(session.created_at),
                    ],
                )?;
            }
            for binding in db.bindings.values() {
                tx.execute(
                    "INSERT INTO bindings (binding_id, daemon_device_id, client_device_id, permissions_json, binding_cert_json, binding_signature, created_at, revoked_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                    params![
                        binding.binding_id,
                        binding.daemon_device_id,
                        binding.client_device_id,
                        binding.permissions_json,
                        binding.binding_cert_json,
                        binding.binding_signature,
                        encode_dt(binding.created_at),
                        binding.revoked_at.map(encode_dt),
                    ],
                )?;
            }
            for presence in db.presence.values() {
                tx.execute(
                    "INSERT INTO presence (device_id, status, iroh_node_addr_json, updated_at)
                     VALUES (?1, ?2, ?3, ?4)",
                    params![
                        presence.device_id,
                        presence.status,
                        presence
                            .iroh_node_addr_json
                            .as_ref()
                            .map(serde_json::to_string)
                            .transpose()?,
                        encode_dt(presence.updated_at),
                    ],
                )?;
            }

            tx.commit()?;
            Ok(())
        })
    }

    fn with_connection<T>(
        &self,
        f: impl FnOnce(&mut Connection) -> anyhow::Result<T>,
    ) -> anyhow::Result<T> {
        let mut conn = Connection::open(&self.path)?;
        f(&mut conn)
    }
}

fn load_devices(conn: &Connection) -> anyhow::Result<HashMap<String, Device>> {
    let mut stmt = conn.prepare(
        "SELECT device_id, kind, display_name, public_key, created_at, last_seen_at FROM devices",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(Device {
            device_id: row.get(0)?,
            kind: row.get(1)?,
            display_name: row.get(2)?,
            public_key: row.get(3)?,
            created_at: decode_dt(row.get::<_, String>(4)?)?,
            last_seen_at: decode_optional_dt(row.get(5)?)?,
        })
    })?;
    collect_by(rows, |device| device.device_id.clone())
}

fn load_pairings(conn: &Connection) -> anyhow::Result<HashMap<String, PairingSession>> {
    let mut stmt = conn.prepare(
        "SELECT pair_id, daemon_device_id, daemon_public_key, expires_at, used_at, created_at FROM pairings",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(PairingSession {
            pair_id: row.get(0)?,
            daemon_device_id: row.get(1)?,
            daemon_public_key: row.get(2)?,
            expires_at: decode_dt(row.get::<_, String>(3)?)?,
            used_at: decode_optional_dt(row.get(4)?)?,
            created_at: decode_dt(row.get::<_, String>(5)?)?,
        })
    })?;
    collect_by(rows, |pairing| pairing.pair_id.clone())
}

fn load_code_pairings(conn: &Connection) -> anyhow::Result<HashMap<String, CodePairingSession>> {
    let mut stmt = conn.prepare(
        "SELECT code_hash, pair_id, client_device_id, client_public_key, client_name, expires_at, used_at, created_at FROM code_pairings",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(CodePairingSession {
            code_hash: row.get(0)?,
            pair_id: row.get(1)?,
            client_device_id: row.get(2)?,
            client_public_key: row.get(3)?,
            client_name: row.get(4)?,
            expires_at: decode_dt(row.get::<_, String>(5)?)?,
            used_at: decode_optional_dt(row.get(6)?)?,
            created_at: decode_dt(row.get::<_, String>(7)?)?,
        })
    })?;
    collect_by(rows, |session| session.code_hash.clone())
}

fn load_bindings(conn: &Connection) -> anyhow::Result<HashMap<String, Binding>> {
    let mut stmt = conn.prepare(
        "SELECT binding_id, daemon_device_id, client_device_id, permissions_json, binding_cert_json, binding_signature, created_at, revoked_at FROM bindings",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(Binding {
            binding_id: row.get(0)?,
            daemon_device_id: row.get(1)?,
            client_device_id: row.get(2)?,
            permissions_json: row.get(3)?,
            binding_cert_json: row.get(4)?,
            binding_signature: row.get(5)?,
            created_at: decode_dt(row.get::<_, String>(6)?)?,
            revoked_at: decode_optional_dt(row.get(7)?)?,
        })
    })?;
    collect_by(rows, |binding| binding.binding_id.clone())
}

fn load_presence(conn: &Connection) -> anyhow::Result<HashMap<String, Presence>> {
    let mut stmt =
        conn.prepare("SELECT device_id, status, iroh_node_addr_json, updated_at FROM presence")?;
    let rows = stmt.query_map([], |row| {
        let iroh_node_addr_json = row
            .get::<_, Option<String>>(2)?
            .map(|value| serde_json::from_str(&value))
            .transpose()
            .map_err(|error| rusqlite::Error::ToSqlConversionFailure(Box::new(error)))?;
        Ok(Presence {
            device_id: row.get(0)?,
            status: row.get(1)?,
            iroh_node_addr_json,
            updated_at: decode_dt(row.get::<_, String>(3)?)?,
        })
    })?;
    collect_by(rows, |presence| presence.device_id.clone())
}

fn collect_by<T>(
    rows: impl Iterator<Item = rusqlite::Result<T>>,
    key: impl Fn(&T) -> String,
) -> anyhow::Result<HashMap<String, T>> {
    let mut out = HashMap::new();
    for row in rows {
        let value = row?;
        out.insert(key(&value), value);
    }
    Ok(out)
}

fn encode_dt(value: DateTime<Utc>) -> String {
    value.to_rfc3339()
}

fn decode_dt(value: String) -> rusqlite::Result<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(&value)
        .map(|value| value.with_timezone(&Utc))
        .map_err(|error| {
            rusqlite::Error::FromSqlConversionFailure(
                0,
                rusqlite::types::Type::Text,
                Box::new(error),
            )
        })
}

fn decode_optional_dt(value: Option<String>) -> rusqlite::Result<Option<DateTime<Utc>>> {
    value.map(decode_dt).transpose()
}
