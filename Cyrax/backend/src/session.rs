use aes_gcm::{Aes256Gcm, Key, Nonce};
use anyhow::Result;
use async_trait::async_trait;
use dashmap::DashMap;
use parking_lot::RwLock;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::time::{Duration, Instant};
use tracing::debug;
use uuid::Uuid;

// ============================================================================
// SESSION STATE
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionKey {
    pub key_id: String,
    pub key_material: Vec<u8>,           // 32 bytes for AES-256
    pub nonce_counter: u64,              // Counter for nonce generation
    pub created_at: Instant,
    pub rotated_at: Option<Instant>,
}

pub struct Session {
    pub session_id: String,
    pub device_id: String,
    pub device_type: String,             // "websocket", "fcm", "mqtt", etc.
    pub sequence_number: Arc<RwLock<u64>>, // Anti-replay counter
    pub current_key: Arc<RwLock<SessionKey>>,
    pub created_at: Instant,
    pub last_activity: Arc<RwLock<Instant>>,
    pub expires_at: Instant,
}

impl Session {
    pub fn new(session_id: String, device_id: String, device_type: String) -> Self {
        let mut key_material = vec![0u8; 32];
        rand::thread_rng().fill_bytes(&mut key_material);

        let key = SessionKey {
            key_id: Uuid::new_v4().to_string(),
            key_material,
            nonce_counter: 0,
            created_at: Instant::now(),
            rotated_at: None,
        };

        let now = Instant::now();
        Self {
            session_id,
            device_id,
            device_type,
            sequence_number: Arc::new(RwLock::new(0)),
            current_key: Arc::new(RwLock::new(key)),
            created_at: now,
            last_activity: Arc::new(RwLock::new(now)),
            expires_at: now + Duration::from_secs(86400), // 24 hour session timeout
        }
    }

    // Encrypt a message with the current session key
    pub async fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        let mut key_guard = self.current_key.write();
        
        // Generate nonce from counter (ensure uniqueness per message)
        let nonce_bytes = key_guard.nonce_counter.to_le_bytes();
        let mut nonce_full = vec![0u8; 12];
        nonce_full[..8].copy_from_slice(&nonce_bytes);
        nonce_full[8..].copy_from_slice(&[0u8; 4]);
        
        key_guard.nonce_counter += 1;

        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key_guard.key_material).clone());
        let nonce = Nonce::from_slice(&nonce_full);

        match cipher.encrypt(nonce, plaintext) {
            Ok(ciphertext) => {
                self.update_activity().await;
                
                // Return: nonce (12 bytes) + tag (16 bytes implicit in GCM) + ciphertext
                let mut result = Vec::new();
                result.extend_from_slice(&nonce_full);
                result.extend_from_slice(&ciphertext);
                
                Ok(result)
            }
            Err(e) => Err(anyhow::anyhow!("Encryption failed: {}", e)),
        }
    }

    // Decrypt a message with anti-replay
    pub async fn decrypt(&self, ciphertext: &[u8]) -> Result<Vec<u8>> {
        if ciphertext.len() < 28 {
            return Err(anyhow::anyhow!("Ciphertext too short"));
        }

        // Extract nonce (first 12 bytes)
        let nonce_bytes = &ciphertext[..12];
        let nonce = Nonce::from_slice(nonce_bytes);

        // Extract actual ciphertext (rest)
        let encrypted_data = &ciphertext[12..];

        let key_guard = self.current_key.read();
        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key_guard.key_material).clone());

        match cipher.decrypt(nonce, encrypted_data) {
            Ok(plaintext) => {
                self.update_activity().await;
                Ok(plaintext)
            }
            Err(e) => Err(anyhow::anyhow!("Decryption failed: {}", e)),
        }
    }

    // Anti-replay: check sequence number
    pub async fn check_and_increment_sequence(&self, reported_seq: u64) -> Result<()> {
        let mut seq = self.sequence_number.write();
        
        if reported_seq <= *seq {
            return Err(anyhow::anyhow!("Replay detected: {} <= {}", reported_seq, *seq));
        }

        *seq = reported_seq;
        Ok(())
    }

    // Rotate session key
    pub async fn rotate_key(&self) -> Result<String> {
        let mut key_material = vec![0u8; 32];
        rand::thread_rng().fill_bytes(&mut key_material);

        let new_key = SessionKey {
            key_id: Uuid::new_v4().to_string(),
            key_material,
            nonce_counter: 0,
            created_at: Instant::now(),
            rotated_at: None,
        };

        let new_key_id = new_key.key_id.clone();
        let mut key_guard = self.current_key.write();
        *key_guard = new_key;

        debug!("Session key rotated for device: {}", self.device_id);
        Ok(new_key_id)
    }

    // Update last activity timestamp
    async fn update_activity(&self) {
        let mut activity = self.last_activity.write();
        *activity = Instant::now();
    }

    // Check if session has expired
    pub fn is_expired(&self) -> bool {
        Instant::now() > self.expires_at
    }
}

// ============================================================================
// SESSION MANAGER
// ============================================================================

pub struct SessionManager {
    sessions: DashMap<String, Arc<Session>>,          // session_id -> session
    device_to_session: DashMap<String, String>,       // device_id -> session_id
}

impl SessionManager {
    pub fn new() -> Self {
        Self {
            sessions: DashMap::new(),
            device_to_session: DashMap::new(),
        }
    }

    pub async fn create_session(
        &self,
        device_id: &str,
        session_id: &str,
        device_type: &str,
    ) -> Result<Arc<Session>> {
        let session = Arc::new(Session::new(
            session_id.to_string(),
            device_id.to_string(),
            device_type.to_string(),
        ));

        self.sessions.insert(session_id.to_string(), session.clone());
        self.device_to_session.insert(device_id.to_string(), session_id.to_string());

        Ok(session)
    }

    pub fn get_session(&self, session_id: &str) -> Option<Arc<Session>> {
        self.sessions.get(session_id).map(|entry| entry.clone())
    }

    pub fn get_device_session(&self, device_id: &str) -> Option<Arc<Session>> {
        self.device_to_session
            .get(device_id)
            .and_then(|entry| {
                let session_id = entry.value();
                self.sessions.get(session_id).map(|s| s.clone())
            })
    }

    pub async fn end_session(&self, session_id: &str) -> Result<()> {
        if let Some((_, session)) = self.sessions.remove(session_id) {
            self.device_to_session.remove(&session.device_id);
            debug!("Session ended: {}", session_id);
        }
        Ok(())
    }

    pub fn cleanup_expired(&self) {
        let expired: Vec<_> = self.sessions
            .iter()
            .filter(|entry| entry.value().is_expired())
            .map(|entry| entry.key().clone())
            .collect();

        for session_id in expired {
            if let Some((_, session)) = self.sessions.remove(&session_id) {
                self.device_to_session.remove(&session.device_id);
                debug!("Expired session removed: {}", session_id);
            }
        }
    }

    pub fn session_count(&self) -> usize {
        self.sessions.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_session_encrypt_decrypt() {
        let session = Session::new(
            "test_session".to_string(),
            "test_device".to_string(),
            "websocket".to_string(),
        );

        let plaintext = b"secret message";
        let ciphertext = session.encrypt(plaintext).await.unwrap();
        let decrypted = session.decrypt(&ciphertext).await.unwrap();

        assert_eq!(plaintext, decrypted.as_slice());
    }

    #[tokio::test]
    async fn test_replay_protection() {
        let session = Session::new(
            "test_session".to_string(),
            "test_device".to_string(),
            "websocket".to_string(),
        );

        session.check_and_increment_sequence(1).await.unwrap();
        assert!(session.check_and_increment_sequence(1).await.is_err()); // Replay
        session.check_and_increment_sequence(2).await.unwrap();       // OK
    }

    #[tokio::test]
    async fn test_key_rotation() {
        let session = Session::new(
            "test_session".to_string(),
            "test_device".to_string(),
            "websocket".to_string(),
        );

        let old_key_id = session.current_key.read().key_id.clone();
        let new_key_id = session.rotate_key().await.unwrap();

        assert_ne!(old_key_id, new_key_id);
    }
}
