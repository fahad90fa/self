// [context: Rust, Linux/x64, AES-256-GCM module payload encryption]

use aes_gcm::{Aes256Gcm, Key, Nonce, aead::{Aead, KeyInit}};
use rand_core::{OsRng, RngCore};
use sha2::{Sha256, Digest};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum PayloadCryptoError {
    #[error("encryption failed")]
    EncryptFailed,
    #[error("decryption failed")]
    DecryptFailed,
}

pub struct PayloadCrypto;

impl PayloadCrypto {
    pub fn encrypt_module(
        module_bytes: &[u8],
        device_session_key: &[u8; 32],
        module_type: u8,
        device_id: &str,
    ) -> Result<Vec<u8>, PayloadCryptoError> {
        let key = Key::<Aes256Gcm>::from_slice(device_session_key);
        let cipher = Aes256Gcm::new(key);
        let mut nonce_bytes = [0u8; 12];
        OsRng.fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);

        let mut aad = Sha256::new();
        aad.update(&[module_type]);
        aad.update(device_id.as_bytes());
        let aad_hash: [u8; 32] = aad.finalize().into();

        let mut payload_with_aad = aad_hash.to_vec();
        payload_with_aad.extend_from_slice(module_bytes);

        let ciphertext = cipher
            .encrypt(nonce, payload_with_aad.as_slice())
            .map_err(|_| PayloadCryptoError::EncryptFailed)?;

        let mut out = Vec::with_capacity(4 + 1 + 12 + 4 + ciphertext.len());
        out.extend_from_slice(b"CXMD");    // magic
        out.push(module_type);
        out.extend_from_slice(&nonce_bytes);
        out.extend_from_slice(&(ciphertext.len() as u32).to_le_bytes());
        out.extend_from_slice(&ciphertext);
        Ok(out)
    }

    pub fn decrypt_module(
        ciphertext: &[u8],
        key: &[u8; 32],
    ) -> Result<Vec<u8>, PayloadCryptoError> {
        if ciphertext.len() < 21 || &ciphertext[..4] != b"CXMD" {
            return Err(PayloadCryptoError::DecryptFailed);
        }
        let nonce = Nonce::from_slice(&ciphertext[5..17]);
        let payload_len = u32::from_le_bytes(ciphertext[17..21].try_into().unwrap()) as usize;
        let enc_payload = &ciphertext[21..21 + payload_len];

        let cipher_key = Key::<Aes256Gcm>::from_slice(key);
        let cipher = Aes256Gcm::new(cipher_key);
        let plaintext = cipher
            .decrypt(nonce, enc_payload)
            .map_err(|_| PayloadCryptoError::DecryptFailed)?;

        // strip 32-byte AAD hash prefix
        if plaintext.len() < 32 {
            return Err(PayloadCryptoError::DecryptFailed);
        }
        Ok(plaintext[32..].to_vec())
    }

    pub fn generate_module_key() -> [u8; 32] {
        let mut key = [0u8; 32];
        OsRng.fill_bytes(&mut key);
        key
    }
}
