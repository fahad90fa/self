// [context: Rust, Linux/x64, decrypt + decompress inbound device data]

use aes_gcm::{Aes256Gcm, Key, Nonce, aead::{Aead, KeyInit}};
use dashmap::DashMap;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::sync::Arc;
use thiserror::Error;

type HmacSha256 = Hmac<Sha256>;

#[derive(Error, Debug)]
pub enum DecryptError {
    #[error("key not found for device")]
    KeyNotFound,
    #[error("hmac invalid")]
    HmacInvalid,
    #[error("decrypt failed")]
    DecryptFailed,
    #[error("decompress failed")]
    DecompressFailed,
}

pub struct DataDecryptor {
    session_keys: Arc<DashMap<String, [u8; 32]>>,
}

impl DataDecryptor {
    pub fn new(session_keys: Arc<DashMap<String, [u8; 32]>>) -> Self {
        Self { session_keys }
    }

    pub fn decrypt_payload(&self, device_id: &str, ciphertext: &[u8]) -> Result<Vec<u8>, DecryptError> {
        let key_ref = self.session_keys.get(device_id).ok_or(DecryptError::KeyNotFound)?;
        let key = Key::<Aes256Gcm>::from_slice(&*key_ref);
        let cipher = Aes256Gcm::new(key);

        if ciphertext.len() < 12 {
            return Err(DecryptError::DecryptFailed);
        }
        let nonce = Nonce::from_slice(&ciphertext[..12]);
        let plaintext = cipher
            .decrypt(nonce, &ciphertext[12..])
            .map_err(|_| DecryptError::DecryptFailed)?;

        Ok(plaintext)
    }

    pub fn verify_hmac(&self, device_id: &str, data: &[u8], tag: &[u8; 32]) -> bool {
        let key_ref = match self.session_keys.get(device_id) {
            Some(k) => k,
            None => return false,
        };
        let mut mac = HmacSha256::new_from_slice(&*key_ref).unwrap();
        mac.update(data);
        mac.verify_slice(tag).is_ok()
    }

    pub fn decompress(&self, bytes: &[u8]) -> Result<Vec<u8>, DecryptError> {
        use std::io::Read;
        let mut decoder = flate2::read::GzDecoder::new(bytes);
        let mut out = Vec::new();
        decoder.read_to_end(&mut out).map_err(|_| DecryptError::DecompressFailed)?;
        Ok(out)
    }

    pub fn decrypt_and_decompress(&self, device_id: &str, ciphertext: &[u8]) -> Result<Vec<u8>, DecryptError> {
        let plaintext = self.decrypt_payload(device_id, ciphertext)?;
        self.decompress(&plaintext)
    }
}
