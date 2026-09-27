// [context: Rust, Linux/x64, WebSocket connection manager for C2]

use axum::extract::ws::Message;
use dashmap::DashMap;
use std::sync::Arc;
use thiserror::Error;
use tokio::sync::mpsc;
use std::time::Instant;

#[derive(Error, Debug)]
pub enum WsError {
    #[error("device not connected")]
    NotConnected,
    #[error("send failed")]
    SendFailed,
}

pub type WsSender = mpsc::UnboundedSender<Message>;

struct ConnectionMeta {
    sender: WsSender,
    connected_at: Instant,
    reconnect_attempts: u32,
    last_heartbeat: Instant,
}

pub struct WsManager {
    connections: Arc<DashMap<String, ConnectionMeta>>,
}

impl WsManager {
    pub fn new() -> Self {
        Self { connections: Arc::new(DashMap::new()) }
    }

    pub fn add_connection(&self, device_id: String, ws_sink: WsSender) {
        self.connections.insert(device_id, ConnectionMeta {
            sender: ws_sink,
            connected_at: Instant::now(),
            reconnect_attempts: 0,
            last_heartbeat: Instant::now(),
        });
    }

    pub fn remove_connection(&self, device_id: &str) {
        self.connections.remove(device_id);
    }

    pub fn send_to_device(&self, device_id: &str, msg: Message) -> Result<(), WsError> {
        self.connections
            .get(device_id)
            .ok_or(WsError::NotConnected)?
            .sender
            .send(msg)
            .map_err(|_| WsError::SendFailed)
    }

    pub fn broadcast(&self, msg: Message) {
        self.connections.iter().for_each(|entry| {
            let _ = entry.sender.send(msg.clone());
        });
    }

    pub fn connection_count(&self) -> usize {
        self.connections.len()
    }

    pub fn is_connected(&self, device_id: &str) -> bool {
        self.connections.contains_key(device_id)
    }

    pub fn online_devices(&self) -> Vec<String> {
        self.connections.iter().map(|e| e.key().clone()).collect()
    }

    pub fn update_heartbeat(&self, device_id: &str) {
        if let Some(mut entry) = self.connections.get_mut(device_id) {
            entry.last_heartbeat = Instant::now();
        }
    }

    pub fn prune_stale(&self, timeout_secs: u64) {
        let threshold = std::time::Duration::from_secs(timeout_secs);
        self.connections.retain(|_, meta| {
            meta.last_heartbeat.elapsed() < threshold
        });
    }
}
