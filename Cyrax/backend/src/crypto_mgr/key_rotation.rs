// [context: Rust, Linux/x64, periodic session key rotation]

use dashmap::DashMap;
use rand_core::{OsRng, RngCore};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use thiserror::Error;
use tokio::time::interval;

#[derive(Error, Debug)]
pub enum RotationError {
    #[error("device not found")]
    DeviceNotFound,
}

#[derive(Clone)]
pub struct SessionKey {
    pub key: [u8; 32],
    pub created_at: u64,
    pub rotation_interval: u64,
}

pub struct NewKey {
    pub key: [u8; 32],
    pub created_at: u64,
}

#[derive(Clone)]
pub struct KeyRotationService {
    keys: Arc<DashMap<String, SessionKey>>,
}

impl KeyRotationService {
    pub fn new() -> Self {
        Self { keys: Arc::new(DashMap::new()) }
    }

    pub fn schedule_rotation(&self, device_id: &str, interval_secs: u64) {
        let mut key_bytes = [0u8; 32];
        OsRng.fill_bytes(&mut key_bytes);
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
        self.keys.insert(
            device_id.to_string(),
            SessionKey {
                key: key_bytes,
                created_at: now,
                rotation_interval: interval_secs,
            },
        );
    }

    pub async fn rotate_now(&self, device_id: &str) -> Result<NewKey, RotationError> {
        let mut entry = self.keys.get_mut(device_id).ok_or(RotationError::DeviceNotFound)?;
        let mut new_key = [0u8; 32];
        OsRng.fill_bytes(&mut new_key);
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
        entry.key = new_key;
        entry.created_at = now;
        Ok(NewKey { key: new_key, created_at: now })
    }

    pub fn get_current_key(&self, device_id: &str) -> Option<SessionKey> {
        self.keys.get(device_id).map(|e| e.clone())
    }

    pub fn start_rotation_loop(self: Arc<Self>) {
        tokio::spawn(async move {
            let mut ticker = interval(Duration::from_secs(60));
            loop {
                ticker.tick().await;
                let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
                let expired: Vec<String> = self.keys.iter()
                    .filter(|e| now - e.created_at >= e.rotation_interval)
                    .map(|e| e.key().clone())
                    .collect();
                for device_id in expired {
                    let _ = self.rotate_now(&device_id).await;
                }
            }
        });
    }
}
