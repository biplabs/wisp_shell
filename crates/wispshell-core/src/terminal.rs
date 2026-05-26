use wispshell_protocol::{AgentToClient, ClientToAgent};

pub fn encode_client_frame(frame: &ClientToAgent) -> anyhow::Result<String> {
    Ok(format!("{}\n", serde_json::to_string(frame)?))
}

pub fn decode_agent_frame(line: &str) -> anyhow::Result<AgentToClient> {
    Ok(serde_json::from_str(line.trim_end())?)
}
