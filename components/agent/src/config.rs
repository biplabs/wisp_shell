use directories::ProjectDirs;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentConfig {
    pub cloud_url: String,
    pub relay_url: String,
    pub device_name: String,
    pub shell: String,
    pub scrollback_bytes: usize,
    #[serde(default)]
    pub iroh_bind_addr: String,
    #[serde(default = "default_iroh_bind_prefix_len")]
    pub iroh_bind_prefix_len: u8,
}

impl Default for AgentConfig {
    fn default() -> Self {
        Self {
            cloud_url: std::env::var("WISPSHELL_CLOUD_URL")
                .unwrap_or_else(|_| "https://wisp.biplabs.com".to_string()),
            relay_url: std::env::var("WISPSHELL_RELAY_URL")
                .unwrap_or_else(|_| "https://wisp-relay.biplabs.com".to_string()),
            device_name: hostname(),
            shell: String::new(),
            scrollback_bytes: 1_048_576,
            iroh_bind_addr: String::new(),
            iroh_bind_prefix_len: default_iroh_bind_prefix_len(),
        }
    }
}

impl AgentConfig {
    pub fn path() -> anyhow::Result<PathBuf> {
        let dirs = ProjectDirs::from("dev", "wispshell", "wispshell")
            .ok_or_else(|| anyhow::anyhow!("could not resolve config directory"))?;
        Ok(dirs.config_dir().join("agent.json"))
    }

    pub fn load_or_create() -> anyhow::Result<Self> {
        let path = Self::path()?;
        if path.exists() {
            return Ok(serde_json::from_slice(&std::fs::read(path)?)?);
        }
        let config = Self::default();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&path, serde_json::to_vec_pretty(&config)?)?;
        Ok(config)
    }

    pub fn shell_command(&self) -> String {
        if !self.shell.is_empty() {
            return self.shell.clone();
        }
        std::env::var("SHELL")
            .ok()
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| {
                if std::path::Path::new("/bin/bash").exists() {
                    "/bin/bash".to_string()
                } else {
                    "/bin/sh".to_string()
                }
            })
    }
}

fn default_iroh_bind_prefix_len() -> u8 {
    24
}

fn hostname() -> String {
    std::env::var("HOSTNAME")
        .ok()
        .filter(|name| !name.trim().is_empty())
        .or_else(|| {
            std::fs::read_to_string("/etc/hostname")
                .ok()
                .map(|name| name.trim().to_string())
                .filter(|name| !name.is_empty())
        })
        .unwrap_or_else(|| "linux-laptop".to_string())
}
