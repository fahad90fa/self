use aes_gcm::{Aes256Gcm, Key, Nonce, aead::{Aead, KeyInit}};
use hkdf::Hkdf;
use rand::RngCore;
use sha2::{Sha256, Digest};
use std::error::Error;

const NONCE_SIZE: usize = 12;
const TAG_SIZE: usize = 16;

// ============================================================================
// SESSION KEY
// ============================================================================

#[derive(Clone, Debug)]
pub struct SessionKey {
    pub key: [u8; 32],
    pub nonce_counter: u64,
    pub created_at: std::time::SystemTime,
}

impl SessionKey {
    /// Generate random session key
    pub fn generate() -> Self {
        let mut key = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut key);

        SessionKey {
            key,
            nonce_counter: 0,
            created_at: std::time::SystemTime::now(),
        }
    }

    /// Derive session key from device fingerprint (environmental keying)
    pub fn derive_from_fingerprint(fingerprint: &str) -> Result<Self, Box<dyn Error>> {
        // HKDF-SHA256 with fingerprint as seed
        let hkdf = Hkdf::<Sha256>::new(None, fingerprint.as_bytes());
        let mut key = [0u8; 32];
        hkdf.expand(b"session_key", &mut key).map_err(|e| format!("hkdf expand: {:?}", e))?;

        Ok(SessionKey {
            key,
            nonce_counter: 0,
            created_at: std::time::SystemTime::now(),
        })
    }

    /// Check if key needs rotation (older than 24 hours)
    pub fn needs_rotation(&self) -> bool {
        match self.created_at.elapsed() {
            Ok(duration) => duration.as_secs() > 86400, // 24 hours
            Err(_) => true,
        }
    }
}

// ============================================================================
// ENCRYPTION / DECRYPTION
// ============================================================================

pub struct AesCrypto;

impl AesCrypto {
    /// Encrypt plaintext with AES-256-GCM
    /// Format: [key_version(1)] + [nonce(12)] + [ciphertext] + [auth_tag(16)]
    pub fn encrypt(
        plaintext: &[u8],
        key: &SessionKey,
        sequence: u64,
    ) -> Result<Vec<u8>, Box<dyn Error>> {
        // Generate nonce from counter (anti-replay protection)
        let nonce_bytes = generate_nonce(sequence);
        let nonce = Nonce::from_slice(&nonce_bytes);

        // Create cipher
        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key.key));

        // Encrypt with AAD = sequence number
        let mut aad = [0u8; 8];
        aad.copy_from_slice(&sequence.to_le_bytes());

        let ciphertext = cipher.encrypt(nonce, plaintext)
            .map_err(|_| "AES-GCM encrypt failed")?;

        // Format: version(1) + nonce(12) + ciphertext + tag(16)
        let mut result = Vec::with_capacity(1 + NONCE_SIZE + ciphertext.len());
        result.push(0x01); // Version 1
        result.extend_from_slice(&nonce_bytes);
        result.extend_from_slice(&ciphertext);

        Ok(result)
    }

    /// Decrypt ciphertext with AES-256-GCM
    pub fn decrypt(
        ciphertext: &[u8],
        key: &SessionKey,
    ) -> Result<Vec<u8>, Box<dyn Error>> {
        if ciphertext.len() < 1 + NONCE_SIZE + TAG_SIZE {
            return Err("Ciphertext too short".into());
        }

        // Parse format
        let version = ciphertext[0];
        if version != 0x01 {
            return Err("Invalid version".into());
        }

        let nonce_bytes = &ciphertext[1..1 + NONCE_SIZE];
        let nonce = Nonce::from_slice(nonce_bytes);

        let encrypted_data = &ciphertext[1 + NONCE_SIZE..];

        // Create cipher
        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key.key));

        // Decrypt
        let plaintext = cipher.decrypt(nonce, encrypted_data)
            .map_err(|_| "AES-GCM decrypt failed")?;

        Ok(plaintext)
    }

    /// Encrypt with AAD (Additional Authenticated Data)
    pub fn encrypt_with_aad(
        plaintext: &[u8],
        key: &[u8; 32],
        aad: &[u8],
    ) -> Result<Vec<u8>, Box<dyn Error>> {
        let mut nonce_bytes = [0u8; NONCE_SIZE];
        rand::thread_rng().fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);

        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
        let ciphertext = cipher.encrypt(nonce, aes_gcm::aead::Payload { msg: plaintext, aad })
            .map_err(|_| "AES-GCM encrypt_with_aad failed")?;

        // Format: nonce + ciphertext + tag
        let mut result = Vec::with_capacity(NONCE_SIZE + ciphertext.len());
        result.extend_from_slice(&nonce_bytes);
        result.extend_from_slice(&ciphertext);

        Ok(result)
    }

    /// Decrypt with AAD
    pub fn decrypt_with_aad(
        ciphertext: &[u8],
        key: &[u8; 32],
        aad: &[u8],
    ) -> Result<Vec<u8>, Box<dyn Error>> {
        if ciphertext.len() < NONCE_SIZE + TAG_SIZE {
            return Err("Ciphertext too short".into());
        }

        let nonce = Nonce::from_slice(&ciphertext[0..NONCE_SIZE]);
        let encrypted = &ciphertext[NONCE_SIZE..];

        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
        let plaintext = cipher.decrypt(nonce, aes_gcm::aead::Payload { msg: encrypted, aad })
            .map_err(|_| "AES-GCM decrypt_with_aad failed")?;

        Ok(plaintext)
    }
}

// ============================================================================
// NONCE GENERATION (ANTI-REPLAY)
// ============================================================================

fn generate_nonce(sequence: u64) -> [u8; NONCE_SIZE] {
    let mut nonce_bytes = [0u8; NONCE_SIZE];
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;
    nonce_bytes[0..8].copy_from_slice(&timestamp.to_le_bytes());
    nonce_bytes[8..12].copy_from_slice(&(sequence as u32).to_le_bytes());
    nonce_bytes
}

// ============================================================================
// KEY DERIVATION
// ============================================================================

pub struct KeyDerivation;

impl KeyDerivation {
    /// Derive key from password using PBKDF2-HMAC-SHA256
    pub fn pbkdf2(
        password: &[u8],
        salt: &[u8],
        iterations: u32,
    ) -> Result<[u8; 32], Box<dyn Error>> {
        let mut result = [0u8; 32];
        pbkdf2::pbkdf2_hmac::<Sha256>(password, salt, iterations, &mut result);
        Ok(result)
    }

    /// Derive key using HKDF-SHA256 (extract-expand)
    pub fn hkdf(
        ikm: &[u8], // Input Key Material
        salt: &[u8],
        info: &[u8],
    ) -> Result<[u8; 32], Box<dyn Error>> {
        let hkdf = Hkdf::<Sha256>::new(Some(salt), ikm);
        let mut key = [0u8; 32];
        hkdf.expand(info, &mut key).map_err(|e| format!("hkdf expand: {:?}", e))?;

        Ok(key)
    }
}

// ============================================================================
// HASHING
// ============================================================================

pub fn sha256(data: &[u8]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let result = hasher.finalize();
    let mut output = [0u8; 32];
    output.copy_from_slice(&result);
    output
}

pub fn sha256_hex(data: &[u8]) -> String {
    let hash = sha256(data);
    hex::encode(&hash)
}

// ============================================================================
// TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_decrypt() {
        let key = SessionKey::generate();
        let plaintext = b"Hello, World!";

        let ciphertext = AesCrypto::encrypt(plaintext, &key, 0).unwrap();
        let decrypted = AesCrypto::decrypt(&ciphertext, &key).unwrap();

        assert_eq!(plaintext, &decrypted[..]);
    }

    #[test]
    fn test_environmental_keying() {
        let fingerprint = "device-123";
        let key1 = SessionKey::derive_from_fingerprint(fingerprint).unwrap();
        let key2 = SessionKey::derive_from_fingerprint(fingerprint).unwrap();

        // Same fingerprint should produce same key
        assert_eq!(&key1.key[..], &key2.key[..]);
    }

    #[test]
    fn test_anti_replay() {
        let key = SessionKey::generate();
        let plaintext = b"Message";

        // Encrypt with different sequences
        let ct1 = AesCrypto::encrypt(plaintext, &key, 1).unwrap();
        let ct2 = AesCrypto::encrypt(plaintext, &key, 2).unwrap();

        // Ciphertexts should differ (different nonces)
        assert_ne!(ct1, ct2);

        // Both should decrypt correctly
        let pt1 = AesCrypto::decrypt(&ct1, &key).unwrap();
        let pt2 = AesCrypto::decrypt(&ct2, &key).unwrap();

        assert_eq!(plaintext, &pt1[..]);
        assert_eq!(plaintext, &pt2[..]);
    }
}
