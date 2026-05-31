use crate::{P2PTerminalClient, RendezvousInfo};
use iroh::Watcher;
use jni21::{
    objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue},
    sys::{jint, jlong, jstring},
    JNIEnv, JavaVM,
};
use std::{
    any::Any,
    collections::HashMap,
    ffi::c_void,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{
        atomic::{AtomicBool, AtomicI64, Ordering},
        Arc, LazyLock, Mutex,
    },
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt},
    sync::mpsc,
    time::{timeout, Duration},
};
use wispshell_protocol::{
    generate_pairing_code, pairing_code_hash, AgentToClient, ClientToAgent, DeviceKeypair,
};

static RUNTIME: LazyLock<tokio::runtime::Runtime> =
    LazyLock::new(|| tokio::runtime::Runtime::new().expect("create tokio runtime"));
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ANDROID_CONTEXT_INITIALIZED: AtomicBool = AtomicBool::new(false);
static TERMINALS: LazyLock<Mutex<HashMap<i64, NativeTerminal>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

struct NativeTerminal {
    commands: mpsc::UnboundedSender<TerminalCommand>,
    _endpoint: iroh::Endpoint,
}

enum TerminalCommand {
    Input(Vec<u8>),
    Resize { cols: u16, rows: u16 },
    Close,
}

struct Callback {
    vm: Arc<JavaVM>,
    object: GlobalRef,
}

impl Callback {
    fn state(&self, state: &str) {
        let _ = self.with_env(|env, object| {
            let value = env.new_string(state)?;
            env.call_method(
                object,
                "onState",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&JObject::from(value))],
            )?;
            Ok(())
        });
    }

    fn error(&self, message: &str) {
        let _ = self.with_env(|env, object| {
            let value = env.new_string(message)?;
            env.call_method(
                object,
                "onError",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&JObject::from(value))],
            )?;
            Ok(())
        });
    }

    fn output(&self, bytes: &[u8]) {
        self.bytes_callback("onOutput", bytes);
    }

    fn scrollback(&self, bytes: &[u8]) {
        self.bytes_callback("onScrollback", bytes);
    }

    fn transport_path(&self, path: &str, latency_ms: Option<u128>) {
        let _ = self.with_env(|env, object| {
            let value = env.new_string(path)?;
            let latency_ms = latency_ms
                .and_then(|value| i32::try_from(value).ok())
                .unwrap_or(-1) as jint;
            env.call_method(
                object,
                "onTransportPath",
                "(Ljava/lang/String;I)V",
                &[JValue::Object(&JObject::from(value)), JValue::Int(latency_ms)],
            )?;
            Ok(())
        });
    }

    fn bytes_callback(&self, method: &str, bytes: &[u8]) {
        let _ = self.with_env(|env, object| {
            let array = env.byte_array_from_slice(bytes)?;
            env.call_method(
                object,
                method,
                "([B)V",
                &[JValue::Object(&JObject::from(array))],
            )?;
            Ok(())
        });
    }

    fn with_env<T>(
        &self,
        f: impl FnOnce(&mut JNIEnv<'_>, &JObject<'_>) -> jni21::errors::Result<T>,
    ) -> jni21::errors::Result<T> {
        let mut env = self.vm.attach_current_thread()?;
        f(&mut env, self.object.as_obj())
    }
}

#[no_mangle]
pub extern "system" fn Java_com_biplabs_wisp_bridge_WispNative_initializeAndroidContext(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    context: JObject<'_>,
) {
    let result = catch_jni(|| {
        if ANDROID_CONTEXT_INITIALIZED.swap(true, Ordering::SeqCst) {
            return Ok(());
        }

        let vm = env.get_java_vm()?;
        let context = env.new_global_ref(context)?;
        unsafe {
            ndk_context::initialize_android_context(
                vm.get_java_vm_pointer().cast::<c_void>(),
                context.as_obj().as_raw().cast::<c_void>(),
            );
        }

        std::mem::forget(context);
        Ok(())
    });
    if let Err(error) = result {
        throw(&mut env, &error.to_string());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_biplabs_wisp_bridge_WispNative_generateDeviceIdentityJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let result = (|| -> anyhow::Result<String> {
        let keys = DeviceKeypair::generate();
        Ok(serde_json::json!({
            "device_id": keys.device_id()?,
            "public_key": keys.public_key_b64(),
            "private_key": keys.private_key_b64(),
        })
        .to_string())
    })();
    to_jstring(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_biplabs_wisp_bridge_WispNative_deviceIdentityFromPrivateKeyJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    private_key: JString<'_>,
) -> jstring {
    let result = (|| -> anyhow::Result<String> {
        let private_key = env.get_string(&private_key)?.to_string_lossy().into_owned();
        let keys = DeviceKeypair::from_private_key_b64(&private_key)?;
        Ok(serde_json::json!({
            "device_id": keys.device_id()?,
            "public_key": keys.public_key_b64(),
            "private_key": keys.private_key_b64(),
        })
        .to_string())
    })();
    to_jstring(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_biplabs_wisp_bridge_WispNative_generatePairingCode(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_jstring(&mut env, Ok(generate_pairing_code()))
}

#[no_mangle]
pub extern "system" fn Java_com_biplabs_wisp_bridge_WispNative_pairingCodeHash(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    code: JString<'_>,
) -> jstring {
    let result = (|| -> anyhow::Result<String> {
        let code = env.get_string(&code)?.to_string_lossy().into_owned();
        pairing_code_hash(&code)
    })();
    to_jstring(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_biplabs_wisp_bridge_WispNative_connectP2pTerminal(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    rendezvous_json: JString<'_>,
    private_key: JString<'_>,
    client_device_id: JString<'_>,
    binding_id: JString<'_>,
    session_name: JString<'_>,
    callback: JObject<'_>,
) -> jlong {
    let result = catch_jni(|| {
        let rendezvous_json = env
            .get_string(&rendezvous_json)?
            .to_string_lossy()
            .into_owned();
        let private_key = env.get_string(&private_key)?.to_string_lossy().into_owned();
        let client_device_id = env
            .get_string(&client_device_id)?
            .to_string_lossy()
            .into_owned();
        let binding_id = env.get_string(&binding_id)?.to_string_lossy().into_owned();
        let session_name = env
            .get_string(&session_name)?
            .to_string_lossy()
            .into_owned();
        let callback = Callback {
            vm: Arc::new(env.get_java_vm()?),
            object: env.new_global_ref(callback)?,
        };

        let rendezvous: RendezvousInfo = serde_json::from_str(&rendezvous_json)?;
        let client_keys = DeviceKeypair::from_private_key_b64(&private_key)?;
        let terminal = RUNTIME.block_on(async {
            timeout(
                Duration::from_secs(10),
                P2PTerminalClient::connect(rendezvous, &client_keys, client_device_id, binding_id),
            )
            .await
        })??;
        let (endpoint, connection_info, mut reader, mut writer) = terminal.into_parts();
        write_frame(
            &mut writer,
            &ClientToAgent::Attach {
                session_name,
                cols: 80,
                rows: 24,
            },
        )?;

        let (tx, mut rx) = mpsc::unbounded_channel();
        let session_id = std::sync::Arc::new(Mutex::new(None::<String>));
        let read_callback = Callback {
            vm: callback.vm.clone(),
            object: callback.object.clone(),
        };
        let path_callback = Callback {
            vm: callback.vm.clone(),
            object: callback.object.clone(),
        };
        let command_session_id = session_id.clone();

        RUNTIME.spawn(async move {
            let mut paths = connection_info.paths();
            let mut interval = tokio::time::interval(Duration::from_secs(1));
            let mut last_snapshot = None::<(String, Option<u128>)>;
            loop {
                let snapshot = connection_info
                    .selected_path()
                    .map(|path| {
                        let transport = if path.is_relay() {
                            "relay"
                        } else if path.is_ip() {
                            "direct"
                        } else {
                            "unknown"
                        }
                        .to_string();
                        let latency_ms = path.rtt().map(|rtt| rtt.as_millis());
                        (transport, latency_ms)
                    })
                    .unwrap_or_else(|| ("unknown".to_string(), None));
                if last_snapshot.as_ref() != Some(&snapshot) {
                    path_callback.transport_path(&snapshot.0, snapshot.1);
                    last_snapshot = Some(snapshot);
                }
                tokio::select! {
                    result = paths.updated() => {
                        if result.is_err() {
                            break;
                        }
                    }
                    _ = interval.tick() => {}
                }
            }
        });

        RUNTIME.spawn(async move {
            read_callback.state("connecting");
            let mut line = String::new();
            let mut attached = false;
            loop {
                line.clear();
                let read_result = if attached {
                    reader.read_line(&mut line).await
                } else {
                    match timeout(Duration::from_secs(10), reader.read_line(&mut line)).await {
                        Ok(result) => result,
                        Err(_) => {
                            read_callback.error("terminal attach timed out");
                            read_callback.state("disconnected");
                            break;
                        }
                    }
                };
                match read_result {
                    Ok(0) => {
                        read_callback.state("disconnected");
                        break;
                    }
                    Ok(_) => match serde_json::from_str::<AgentToClient>(line.trim_end()) {
                        Ok(AgentToClient::SessionAttached { session_id: id, .. }) => {
                            attached = true;
                            *session_id.lock().unwrap() = Some(id);
                            read_callback.state("attached");
                        }
                        Ok(AgentToClient::Scrollback { chunks_b64, .. }) => {
                            for chunk in chunks_b64 {
                                match base64::Engine::decode(
                                    &base64::engine::general_purpose::STANDARD,
                                    chunk,
                                ) {
                                    Ok(bytes) => read_callback.scrollback(&bytes),
                                    Err(error) => read_callback.error(&error.to_string()),
                                }
                            }
                        }
                        Ok(AgentToClient::Output { data_b64, .. }) => {
                            match base64::Engine::decode(
                                &base64::engine::general_purpose::STANDARD,
                                data_b64,
                            ) {
                                Ok(bytes) => read_callback.output(&bytes),
                                Err(error) => read_callback.error(&error.to_string()),
                            }
                        }
                        Ok(AgentToClient::Error { message, .. }) => read_callback.error(&message),
                        Ok(_) => {}
                        Err(error) => read_callback.error(&error.to_string()),
                    },
                    Err(error) => {
                        read_callback.error(&error.to_string());
                        read_callback.state("disconnected");
                        break;
                    }
                }
            }
        });

        RUNTIME.spawn(async move {
            while let Some(command) = rx.recv().await {
                let frame = match command {
                    TerminalCommand::Input(bytes) => {
                        let Some(session_id) = command_session_id.lock().unwrap().clone() else {
                            continue;
                        };
                        ClientToAgent::Input {
                            session_id,
                            data_b64: base64::Engine::encode(
                                &base64::engine::general_purpose::STANDARD,
                                bytes,
                            ),
                        }
                    }
                    TerminalCommand::Resize { cols, rows } => {
                        let Some(session_id) = command_session_id.lock().unwrap().clone() else {
                            continue;
                        };
                        ClientToAgent::Resize {
                            session_id,
                            cols,
                            rows,
                        }
                    }
                    TerminalCommand::Close => break,
                };
                if write_frame_async(&mut writer, &frame).await.is_err() {
                    break;
                }
            }
        });

        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        TERMINALS.lock().unwrap().insert(
            handle,
            NativeTerminal {
                commands: tx,
                _endpoint: endpoint,
            },
        );
        Ok(handle)
    });
    match result {
        Ok(handle) => handle,
        Err(error) => {
            throw(&mut env, &error.to_string());
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_biplabs_wisp_bridge_WispNative_sendTerminalInput(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    data: JByteArray<'_>,
) {
    let result = (|| -> anyhow::Result<()> {
        let bytes = env.convert_byte_array(data)?;
        let terminals = TERMINALS.lock().unwrap();
        let terminal = terminals
            .get(&(handle as i64))
            .ok_or_else(|| anyhow::anyhow!("terminal handle not found"))?;
        terminal.commands.send(TerminalCommand::Input(bytes))?;
        Ok(())
    })();
    if let Err(error) = result {
        throw(&mut env, &error.to_string());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_biplabs_wisp_bridge_WispNative_resizeTerminal(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    cols: i32,
    rows: i32,
) {
    let result = (|| -> anyhow::Result<()> {
        let terminals = TERMINALS.lock().unwrap();
        let terminal = terminals
            .get(&(handle as i64))
            .ok_or_else(|| anyhow::anyhow!("terminal handle not found"))?;
        terminal.commands.send(TerminalCommand::Resize {
            cols: cols.try_into()?,
            rows: rows.try_into()?,
        })?;
        Ok(())
    })();
    if let Err(error) = result {
        throw(&mut env, &error.to_string());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_biplabs_wisp_bridge_WispNative_closeTerminal(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if let Some(terminal) = TERMINALS.lock().unwrap().remove(&(handle as i64)) {
        let _ = terminal.commands.send(TerminalCommand::Close);
    }
}

fn to_jstring(env: &mut JNIEnv<'_>, result: anyhow::Result<String>) -> jstring {
    let value = match result {
        Ok(value) => value,
        Err(error) => serde_json::json!({"error": error.to_string()}).to_string(),
    };
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn throw(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", message);
}

fn catch_jni<T>(f: impl FnOnce() -> anyhow::Result<T>) -> anyhow::Result<T> {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(result) => result,
        Err(payload) => anyhow::bail!("native panic: {}", panic_message(&payload)),
    }
}

fn panic_message(payload: &Box<dyn Any + Send>) -> &str {
    payload
        .downcast_ref::<&str>()
        .copied()
        .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
        .unwrap_or("unknown panic")
}

fn write_frame(
    writer: &mut iroh::endpoint::SendStream,
    frame: &ClientToAgent,
) -> anyhow::Result<()> {
    RUNTIME.block_on(write_frame_async(writer, frame))
}

async fn write_frame_async(
    writer: &mut iroh::endpoint::SendStream,
    frame: &ClientToAgent,
) -> anyhow::Result<()> {
    writer
        .write_all(serde_json::to_string(frame)?.as_bytes())
        .await?;
    writer.write_all(b"\n").await?;
    writer.flush().await?;
    Ok(())
}
