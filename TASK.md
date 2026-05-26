# WispShell v1 Task Notes

- Components are split into `components/agent`, `components/cloud`, and `components/android`.
- Shared protocol and client helpers live in `crates/wispshell-protocol` and `crates/wispshell-core`.
- Current vertical slice focuses on local development: in-memory cloud register, local TCP daemon transport, persistent daemon-owned PTY session, and Android UI scaffolding.
- Camera-free pairing is modeled with Android-generated `WISP-...` codes. The cloud stores code hashes, rejects expired sessions, and enforces single-use approval.
