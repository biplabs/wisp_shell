pub fn install_user_service() -> anyhow::Result<()> {
    let service = r#"[Unit]
Description=WispShell user daemon
Wants=network-online.target
After=network-online.target
StartLimitIntervalSec=0

[Service]
ExecStart=%h/.local/bin/wispshelld run
Environment=RUST_LOG=wispshell_agent=info,wispshelld=info
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
"#;
    let path = directories::BaseDirs::new()
        .ok_or_else(|| anyhow::anyhow!("could not resolve home directory"))?
        .home_dir()
        .join(".config/systemd/user/wispshelld.service");
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&path, service)?;
    println!("installed {}", path.display());
    println!(
        "run: systemctl --user daemon-reload && systemctl --user enable --now wispshelld.service"
    );
    Ok(())
}

pub fn uninstall_user_service() -> anyhow::Result<()> {
    let path = directories::BaseDirs::new()
        .ok_or_else(|| anyhow::anyhow!("could not resolve home directory"))?
        .home_dir()
        .join(".config/systemd/user/wispshelld.service");
    if path.exists() {
        std::fs::remove_file(&path)?;
    }
    println!("removed {}", path.display());
    Ok(())
}
